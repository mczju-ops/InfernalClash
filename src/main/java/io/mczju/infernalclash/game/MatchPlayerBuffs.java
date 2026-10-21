package io.mczju.infernalclash.game;

import io.mczju.infernalclash.match.MatchHooks;
import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.policy.PlayerBuffs;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★★ <b>玩家本体的常驻效果</b>（{@code docs/02} §5 的 <b>R40</b>「持续的速度 II / 夜视 /
 * 抗性提升 V」）—— 2026-10-17 第十轮新增，缺陷 P0-b 的落点。
 *
 * <h2>用户原话（逐字，{@code docs/24} §7.1）</h2>
 * 「防御塔会攻击己方玩家，包括己方的防御塔，<b>而玩家的常驻的抗性药水效果也没有出现</b>」
 * ⇒ 于是「生存模式下用不了多久就会被防御塔打死，根本看不到发生了什么」。
 *
 * <h2>文档依据（这一条**不是**我编的数值）</h2>
 * <ul>
 *   <li>{@code docs/需求.txt} 第 20 行：「…获得<b>持续的速度II、夜视以及抗性提升V</b>，
 *       即玩家无法受到任何伤害，也不会死亡。」</li>
 *   <li>{@code docs/02} §5 第 265~267 行（出处列 R40）：速度 II / 夜视 / 抗性提升 V，都「持续」；</li>
 *   <li>同 §5 第 273~275 行：「★ <b>两层防护都要做</b>：① 药水层：抗性提升 V ＋ 伤害拦截兜底；
 *       ② 索敌层：显式把玩家从候选目标里剔除」⇒ 本类做的是**第 ① 层**。</li>
 * </ul>
 * 清单与判据在纯类 {@link PlayerBuffs}（可离线断言），本类只做「读实体、写效果」。
 *
 * <h2>★★ 铁律 10 的四问（这是「改玩家状态」这类操作）</h2>
 * <ol>
 *   <li><b>范围界定</b>：只遍历 {@link #players}（= 本局名册：阵营 → 玩家 UUID，
 *       来自 {@code MatchService.sideAssignments}）⇒ <b>没有 {@code getOnlinePlayers()}、
 *       没有"附近的玩家"</b>；</li>
 *   <li><b>失败方向</b>：玩家离线 / 名册为空 / 效果名解析不出来 ⇒ <b>跳过那一个</b>
 *       （并说明原因），绝不"改成给所有人上"；</li>
 *   <li><b>归属判定</b>：阵营 → UUID 的映射就是本局名册（**不靠实体类型、不靠名字**）；</li>
 *   <li><b>可对账</b>：每次真的动了效果都打一行<b>带数量</b>的日志（几个玩家 / 几条效果），
 *       收尾还原时也有数量。</li>
 * </ol>
 *
 * <h2>★ 为什么每秒补齐一次</h2>
 * 「持续」的正确写法是无限时长（见 {@link PlayerBuffs}），但**对局里会发生掉效果的合法情况**：
 * 玩家死亡重生、{@code /effect clear}、维度切换…⇒ 每秒检查一次「还缺哪几条」并补上
 * （判据在 {@link PlayerBuffs#missing}，缺才补 ⇒ 不会每秒重放、客户端不闪、日志不刷屏）。
 *
 * <h2>★ 结束时还原（副作用必须可回退）</h2>
 * {@link #onStop} 按 {@link PlayerBuffs#restoreAction} 逐条处理：
 * 本局之前没有的 ⇒ 移除；本来是有限时长的 ⇒ 按原时长放回；本来就是无限时长的 ⇒ 不动。
 * 理由：这三条效果不一定是我们第一个放的（服主可能本来就配了）⇒ 无脑移除会改坏别人的状态。
 */
public final class MatchPlayerBuffs implements MatchHooks {

    /** 「持续」的补齐节拍：每 1 秒检查一次（见类注释）。 */
    public static final int ENFORCE_INTERVAL_TICKS = 20;

    private final Plugin plugin;
    private final String matchId;
    private final Map<Side, UUID> players;
    private final Logger logger;

    /**
     * ★ 本局**之前**这些效果长什么样（玩家 UUID → 每条效果的快照）——
     * 只在**第一次**动某个玩家时记录一次（后面的补齐不许覆盖它，否则还原就还原错了）。
     */
    private final Map<UUID, List<PlayerBuffs.Snapshot>> before = new LinkedHashMap<>();

    /**
     * ★★★ <b>本局之前这些玩家是不是本来就无敌</b>（玩家 UUID → 原值）——
     * 2026-10-18 新增（用户第 2 条：「把玩家彻底从头到尾排除出怪物索敌目标」）。
     *
     * <p>★ 为什么要记：我们的收尾要把他改回"不无敌"，但**本来就无敌的不能动**
     * （可能是别的插件给的无敌 / 创造模式）；判据在纯类
     * {@link io.mczju.infernalclash.policy.PlayerTargetBan#shouldRestore}。
     */
    private final Map<UUID, Boolean> invulnerableBefore = new LinkedHashMap<>();

    private int tickCounter;
    private boolean stopped;

    public MatchPlayerBuffs(Plugin plugin, String matchId, Map<Side, UUID> players) {
        this.plugin = plugin;
        this.matchId = matchId;
        // ★ 注意 EnumMap 的构造器：空 Map 且不是 EnumMap 时会抛 IllegalArgumentException
        //   ⇒ 空名册必须单独处理（这一格是「无人机/关服」那条路，不能因此炸掉开局）
        this.players = players == null || players.isEmpty()
                ? Map.of() : new EnumMap<>(players);
        this.logger = plugin.getLogger();
    }

    // ------------------------------------------------------------------ MatchHooks

    @Override
    public void onPrepPhaseStarted() {
        applyAll("准备阶段开始");
    }

    @Override
    public void onPhaseChanged(MatchPhase from, MatchPhase to) {
        if (to == MatchPhase.BATTLE) {
            applyAll("战斗开始");
        }
    }

    @Override
    public void onTick(MatchPhase phase, int battleTick) {
        if (stopped || phase == MatchPhase.ENDED) {
            return;
        }
        if (++tickCounter % ENFORCE_INTERVAL_TICKS != 0) {
            return;
        }
        // ★ 补齐：只在**真的缺**的时候写实体（见 PlayerBuffs#missing 的判据）
        enforce();
    }

    @Override
    public void onStop(String reason) {
        if (stopped) {
            return;
        }
        stopped = true;
        restoreAll(reason);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * ★★★ 2026-10-21（用户裁定）：「玩家的移动太慢 ⇒ **仅给予游戏中的玩家创造飞行的效果**」。
     *
     * <p>记录「进局前本来能不能飞」，收尾时按原值还原 —— <b>绝不把非创造模式的玩家永久留在可飞状态</b>。
     * ★ 只开 {@code allowFlight}（双击空格起飞），**不强制** {@code setFlying}：
     * 强制起飞会把玩家顶到空中、也会和"准备阶段的传送"打架。
     */
    private final java.util.Map<java.util.UUID, Boolean> flightBefore = new java.util.LinkedHashMap<>();

    /** 首次/阶段开始时的施加（把「本局之前的样子」记下来，再补上缺的）。 */
    private void applyAll(String trigger) {
        if (players.isEmpty()) {
            logger.warning("[IC] 常驻效果未施加（" + trigger + "）：本局名册为空，一个都不上"
                    + "（fail-safe：绝不改成「给所有人上」）｜matchId=" + matchId);
            return;
        }
        int touched = 0;
        int added = 0;
        int unresolved = 0;
        int banned = 0;
        for (Map.Entry<Side, UUID> entry : players.entrySet()) {
            Player player = playerOf(entry.getValue());
            if (player == null) {
                continue;
            }
            if (enforceTargetBan(player)) {
                banned++;
            }
            snapshotIfAbsent(player);
            // ★ 创造飞行（本局内）：只加不清；原值记在 flightBefore 里，收尾时还原
            if (!flightBefore.containsKey(player.getUniqueId())) {
                flightBefore.put(player.getUniqueId(), player.getAllowFlight());
            }
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            PlayerBuffs.Spec[] pending = missingFor(player);
            if (pending.length == 0) {
                continue;
            }
            touched++;
            for (PlayerBuffs.Spec spec : pending) {
                PotionEffectType type = resolve(spec.effect());
                if (type == null) {
                    unresolved++;
                    continue;
                }
                player.addPotionEffect(effect(type, spec));
                added++;
            }
        }
        if (added > 0 || unresolved > 0) {
            logger.info("[IC] 常驻效果已施加（" + trigger + "）：玩家 " + touched + " 名 / 效果 " + added
                    + " 条｜清单 " + PlayerBuffs.describe(PlayerBuffs.DEFAULT)
                    + "｜matchId=" + matchId
                    + "｜出处 docs/02 §5 （速度 II / 夜视 / 抗性提升 V，持续）");
        }
        if (banned > 0) {
            // ★★ 可对账：这一行就是「怪物再也不会追着玩家跑」的阳性证据
            //   （javap 实测：TargetingConditions 要求 canBeSeenAsEnemy ⇒ !isInvulnerable）
            logger.info("[IC] 玩家索敌排除已生效（" + trigger + "）：把 " + banned
                    + " 名玩家设为无敌，原版任何怪物都不会把玩家当目标"
                    + "（含猪灵 / 猪灵蛮兵这类 brain 型）；不是隐身，对手玩家照样看得见"
                    + "｜matchId=" + matchId + "｜" + io.mczju.infernalclash.policy.PlayerTargetBan.describe());
        }
        if (unresolved > 0) {
            logger.warning("[IC] 有 " + unresolved + " 条常驻效果的效果名解析不出来"
                    + "（PotionEffectType 注册表里没有），已跳过那几条，其余照常｜matchId=" + matchId
                    + "｜环境不对时会出现（例如 26.2 的效果名被改名）—— 请把这一行连同版本反馈");
        }
    }

    /** 每秒补齐：只在真的补了东西时打日志（否则一秒一行会刷屏）。 */
    private void enforce() {
        int touched = 0;
        int added = 0;
        int banned = 0;
        for (Map.Entry<Side, UUID> entry : players.entrySet()) {
            Player player = playerOf(entry.getValue());
            if (player == null) {
                continue;
            }
            if (enforceTargetBan(player)) {
                banned++;
            }
            snapshotIfAbsent(player);
            // ★ 创造飞行（本局内）：只加不清；原值记在 flightBefore 里，收尾时还原
            if (!flightBefore.containsKey(player.getUniqueId())) {
                flightBefore.put(player.getUniqueId(), player.getAllowFlight());
            }
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            PlayerBuffs.Spec[] pending = missingFor(player);
            if (pending.length == 0) {
                continue;
            }
            touched++;
            for (PlayerBuffs.Spec spec : pending) {
                PotionEffectType type = resolve(spec.effect());
                if (type == null) {
                    continue;
                }
                player.addPotionEffect(effect(type, spec));
                added++;
            }
        }
        if (added > 0) {
            logger.info("[IC] 常驻效果已补齐 " + added + " 条（玩家 " + touched + " 名）"
                    + "—— 掉效果是合法的（死亡重生 / /effect clear / 维度切换），"
                    + "「持续」的口径就是每秒钉一次｜matchId=" + matchId);
        }
        if (banned > 0) {
            // ★ 死亡重生会把无敌重置掉（玩家实体是新建的）⇒ 这条补齐日志同时是"重生后又被钉住"的证据
            logger.info("[IC] 玩家索敌排除已补齐 " + banned + " 名（重生会把无敌重置掉，"
                    + "所以每秒钉一次）｜matchId=" + matchId);
        }
    }

    /**
     * ★★★ <b>把这个玩家从「怪物的候选目标」里彻底摘掉</b>（2026-10-18 新增，用户第 2 条）。
     *
     * <h2>机制（`javap` 实测的硬事实，见 {@link io.mczju.infernalclash.policy.PlayerTargetBan}）</h2>
     * 原版 {@code TargetingConditions.test} 要求 {@code target.canBeSeenAsEnemy()}，
     * 而它 = {@code !isInvulnerable() && !isSpectator() && isAlive()}
     * ⇒ <b>设成无敌之后，所有索敌（goal 型与 brain 型）都会跳过这个玩家</b>。
     * 猪灵 / 猪灵蛮兵正是 brain 型（{@code setTarget} 对它们无效、Bukkit 也改不到
     * {@code ATTACK_TARGET}）⇒ 这是唯一能治它们的那条路。
     *
     * <p>★ 不隐身：对手玩家照样看得见（用户明确要求）。
     * <p>★ 失败方向：拿不到玩家 / 写属性抛异常 ⇒ <b>只告警一次</b>，绝不影响对局
     * （最坏情况退回旧行为：怪会追玩家，但伤害仍被 {@code PlayerDamageGuard} 拦掉）。
     *
     * @return 这一次真的写进去了吗（{@code false} = 本来就无敌 / 已关闭 / 写失败）
     */
    private boolean enforceTargetBan(Player player) {
        if (!io.mczju.infernalclash.policy.PlayerTargetBan.isEnabled()) {
            return false;
        }
        UUID id = player.getUniqueId();
        try {
            boolean now = player.isInvulnerable();
            // ★ 只记一次「本局之前的样子」（与 before 那条同一口径：后面的补齐不许覆盖它）
            invulnerableBefore.putIfAbsent(id, now);
            if (!io.mczju.infernalclash.policy.PlayerTargetBan.shouldForceInvulnerable(now)) {
                return false;
            }
            player.setInvulnerable(true);
            return true;
        } catch (Throwable t) {
            if (!targetBanWarned) {
                targetBanWarned = true;
                logger.warning("[IC] 「把玩家设为无敌，怪物不再索敌玩家」这一步失败：" + t
                        + "，退回旧行为（怪仍会追玩家，但伤害照旧被拦）｜matchId=" + matchId);
            }
            return false;
        }
    }

    /** 「设无敌失败」只告警一次（每秒会重试，别刷屏）。 */
    private boolean targetBanWarned;

    /** 收尾还原（见类注释的「副作用必须可回退」）。 */
    private void restoreAll(String reason) {
        int removed = 0;
        int reapplied = 0;
        int kept = 0;
        for (Map.Entry<UUID, List<PlayerBuffs.Snapshot>> entry : before.entrySet()) {
            Player player = playerOf(entry.getKey());
            if (player == null) {
                continue;   // 离线 ⇒ 效果到期后本来也会消失；不查世界、不猜
            }
            for (PlayerBuffs.Snapshot snapshot : entry.getValue()) {
                PotionEffectType type = resolve(snapshot.spec().effect());
                if (type == null) {
                    continue;
                }
                switch (PlayerBuffs.restoreAction(snapshot)) {
                    case REMOVE -> {
                        player.removePotionEffect(type);
                        removed++;
                    }
                    case REAPPLY -> {
                        player.addPotionEffect(new PotionEffect(type, snapshot.duration(),
                                snapshot.amplifier(), false, PlayerBuffs.SHOW_PARTICLES, true), true);
                        reapplied++;
                    }
                    case KEEP -> kept++;
                }
            }
        }
        before.clear();
        // ★ 创造飞行还原（2026-10-21）：按进局前记下的原值放回；原本不能飞的 ⇒ 同时落地
        int flightRestored = 0;
        for (Map.Entry<UUID, Boolean> entry : flightBefore.entrySet()) {
            Player player = playerOf(entry.getKey());
            if (player == null) {
                continue;   // 离线：下次上线由他自己/别的插件决定，不猜
            }
            boolean wasAllowed = Boolean.TRUE.equals(entry.getValue());
            player.setAllowFlight(wasAllowed);
            if (!wasAllowed && player.isFlying()) {
                player.setFlying(false);
            }
            flightRestored++;
        }
        flightBefore.clear();
        if (removed + reapplied + kept > 0) {
            logger.info("[IC] 常驻效果已还原（" + reason + "）：移除 " + removed + " / 放回原样 "
                    + reapplied + " / 原本就是永久不动 " + kept + "｜matchId=" + matchId
                    + "｜只动我们记过的那三条效果，绝不 /effect clear 全清"
                    + "｜飞行还原 " + flightRestored + " 名");
        }
        restoreTargetBan(reason);
    }

    /**
     * ★ <b>把无敌还原掉</b>（2026-10-18 新增，与 {@link #enforceTargetBan} 成对）。
     *
     * <p>★ 只还原<b>我们自己改过的那一位</b>：本来无敌的（创造模式飞行 / 别的插件给的）
     * 一律不动 —— 判据在纯类
     * {@link io.mczju.infernalclash.policy.PlayerTargetBan#shouldRestore}。
     */
    private void restoreTargetBan(String reason) {
        int restored = 0;
        int kept = 0;
        for (Map.Entry<UUID, Boolean> entry : invulnerableBefore.entrySet()) {
            Player player = playerOf(entry.getKey());
            if (player == null) {
                continue;   // 离线 ⇒ 下次上线本来也带着我们设的值，但拿不到对象就不猜
            }
            try {
                boolean now = player.isInvulnerable();
                if (io.mczju.infernalclash.policy.PlayerTargetBan.shouldRestore(
                        Boolean.TRUE.equals(entry.getValue()), now)) {
                    player.setInvulnerable(false);
                    restored++;
                } else if (now) {
                    kept++;   // 本来就无敌 ⇒ 保持
                }
            } catch (Throwable t) {
                logger.warning("[IC] 还原「玩家无敌」失败（" + entry.getKey() + "）：" + t
                        + "，他可能仍处于无敌（只影响观感/被索敌，不影响对局结果）");
            }
        }
        invulnerableBefore.clear();
        if (restored + kept > 0) {
            logger.info("[IC] 玩家索敌排除已还原（" + reason + "）：改回不无敌 " + restored
                    + " 名 / 本来就无敌保持不动 " + kept + " 名｜matchId=" + matchId
                    + "｜只还原我们自己改过的那一位");
        }
    }

    /** 这条玩家现在缺哪几条（纯判据在 {@link PlayerBuffs#missing}）。 */
    private PlayerBuffs.Spec[] missingFor(Player player) {
        List<PlayerBuffs.Spec> missing = PlayerBuffs.missing(PlayerBuffs.DEFAULT,
                spec -> alreadyPresent(player, spec));
        return missing.toArray(new PlayerBuffs.Spec[0]);
    }

    /** 「这一条现在已经是无限时长、而且等级不低」——缺了或弱了就返回 false。 */
    private boolean alreadyPresent(Player player, PlayerBuffs.Spec spec) {
        PotionEffectType type = resolve(spec.effect());
        if (type == null) {
            return false;
        }
        PotionEffect current = player.getPotionEffect(type);
        return current != null && current.isInfinite() && current.getAmplifier() >= spec.amplifier();
    }

    /** 无限时长 + 按 {@link PlayerBuffs#SHOW_PARTICLES} 的观感决定。 */
    private PotionEffect effect(PotionEffectType type, PlayerBuffs.Spec spec) {
        return new PotionEffect(type, PotionEffect.INFINITE_DURATION, spec.amplifier(),
                false, PlayerBuffs.SHOW_PARTICLES, true);
    }

    /** 记下「本局之前的样子」（只记一次；后面每秒补齐不许覆盖它）。 */
    private void snapshotIfAbsent(Player player) {
        before.computeIfAbsent(player.getUniqueId(), id -> {
            List<PlayerBuffs.Snapshot> snapshots = new ArrayList<>();
            for (PlayerBuffs.Spec spec : PlayerBuffs.DEFAULT) {
                PotionEffectType type = resolve(spec.effect());
                PotionEffect current = type == null ? null : player.getPotionEffect(type);
                snapshots.add(new PlayerBuffs.Snapshot(spec, current != null,
                        current != null && current.isInfinite(),
                        current == null ? 0 : current.getDuration(),
                        current == null ? 0 : current.getAmplifier()));
            }
            return List.copyOf(snapshots);
        });
    }

    /** 效果名 → 类型（与 {@code spell/EffectApplier} 同一口径：注册表按名查）。 */
    private PotionEffectType resolve(String name) {
        try {
            return PotionEffectType.getByName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取玩家（★ 只按名册里的 UUID 取，不查在线列表）。 */
    private Player playerOf(UUID id) {
        if (id == null) {
            return null;
        }
        Player player = Bukkit.getPlayer(id);
        return player != null && player.isOnline() ? player : null;
    }
}
