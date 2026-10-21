package io.mczju.infernalclash.objective;

import io.mczju.infernalclash.arena.ArenaConfig;
import io.mczju.infernalclash.arena.Point;
import io.mczju.infernalclash.config.ObjectiveConfig;
import io.mczju.infernalclash.match.MatchHooks;
import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.MatchSignal;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.SideRule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★ <b>一场对局的目标物系统</b>（信标 + 防御塔），实现 {@link MatchHooks} 接进 P2 的时间轴。
 *
 * <h2>它负责什么</h2>
 * <ol>
 *   <li>按 `arenas.yml` 的点位生成 2 个信标 + 4 座防御塔（{@link #spawnAll()}）；</li>
 *   <li>每 tick 把「谁还活着」翻译成 {@link MatchSignal}（{@link ObjectiveTracker}，纯类）；</li>
 *   <li>防御塔每 {@code beam-interval-ticks} 对射程内的**敌方**单位造成 {@code beam-damage} 点伤害；</li>
 *   <li>战斗阶段开始时把双方玩家传送到各自的出生点。</li>
 * </ol>
 *
 * <h2>★★ 铁律 10：这里没有一处「按世界/按范围」的查询</h2>
 * <table border="1">
 *   <caption>四问逐条</caption>
 *   <tr><th>问</th><th>本类的回答</th></tr>
 *   <tr><td>范围界定</td>
 *       <td>① 生成：只在那 6 个**配置点位**各生成 1 个；
 *           ② 光束索敌：<b>只遍历本场账本</b>（{@code UnitRegistry.tracked(matchId)}），
 *           ★★ <b>2026-10-17 第十二轮起玩家连候选都不是</b>（R42：玩家不会成为**任何**
 *           实体的目标）—— 旧实现曾把「本局玩家」也放进候选集合，那正是用户
 *           第十一轮报的「<b>敌方防御塔依旧会攻击玩家</b>」的根因。
 *           仍然**没有 {@code getNearbyEntities} / {@code getEntities}**</td></tr>
 *   <tr><td>失败方向</td>
 *       <td>点位不全 ⇒ **一个都不生成 + 明确告警**（本局退化成纯时间轴，绝不猜位置）；
 *           账本为空 ⇒ 光束打不到任何人（不是"改成打附近的"）</td></tr>
 *   <tr><td>归属判定</td>
 *       <td>{@code OwnershipTable.Owner} 的 side 字段（**不是**实体类型）；
 *           目标物的 UUID 由本类自己持有</td></tr>
 *   <tr><td>可对账</td>
 *       <td>生成/清场/传送/每次光束命中都打**数量**日志</td></tr>
 * </table>
 *
 * <h2>★ 两个刻意的口径（都写进 `docs/05` §E，可否决）</h2>
 * <ul>
 *   <li><b>光束不打「敌方目标物」</b>：只打敌方**玩家与召唤物**。
 *       R9 的原文是「对范围内的敌方单位造成伤害」，而"单位"是否包含对方信标/塔没有明说
 *       ⇒ 我取**保守**解释（否则塔会隔着半场削对方信标，等于凭空加了一条规则）。</li>
 *   <li><b>点位不全就不生成</b>，而不是「生成能生成的那几个」：半套目标物会让
 *       塔数比较（W2/W5）得出无意义的结果（1:2 之类的假胜负）。
 *       ⇒ 要么全有、要么全无（全无 = P2 的纯时间轴，结果是平局，语义清楚）。</li>
 * </ul>
 */
public final class ObjectiveService implements MatchHooks {

    /**
     * ★ <b>候选集合那个方法的名字</b>（2026-10-17 第十二轮新增）。
     *
     * <p>为什么要把一个私有方法名做成常量：{@code battle/PlayerTargetWiringTest} 用
     * <b>方法级字节码</b>断言「这个方法的字节码里**不许**出现 {@code Bukkit.getPlayer}」
     * —— 那是「敌方防御塔打玩家」这条缺陷的回归防线。断言要按名字找方法，
     * 而字符串常量放在这里 ⇒ <b>改方法名时编译不过</b>（改测试里的字符串只会静默变成空断言）。
     */
    public static final String METHOD_CANDIDATES = "candidates";

    private final Plugin plugin;
    private final String matchId;
    private final UnitRegistry registry;
    private final ArenaConfig arena;
    private final ObjectiveConfig config;
    private final Logger logger;
    private final Map<Side, UUID> players;

    private final List<ObjectiveUnit> units = new ArrayList<>();
    private final Set<UUID> objectiveIds = new HashSet<>();

    /**
     * ★★ <b>「被塔的光束打中 ⇒ 不击退」的守卫</b>
     * （2026-10-18 新增；用户：「防御塔攻击不应该带有击退效果，否则将永控某些敌人」）。
     *
     * <p>★ 为什么是**单独的 Listener 实例**而不是本类上一个 {@code @EventHandler}：
     * 本类只 {@code implements MatchHooks}，**从来不是 {@code Listener}、也没被注册过**
     * ⇒ 写在本类上的 {@code @EventHandler} 是**死代码**（第一版就是这么写的，差点变成假修复）。
     * 见 {@link ObjectiveKnockbackGuard} 的类注释。
     */
    private final ObjectiveKnockbackGuard knockbackGuard = new ObjectiveKnockbackGuard();
    private final ObjectiveTracker tracker = new ObjectiveTracker();

    /**
     * ★★ <b>本局**生成过**的全部目标物</b>（含中途死掉的那些）—— 2026-10-17 第十轮新增。
     *
     * <h2>为什么必须有它（缺陷 P0-c 的账目面）</h2>
     * 旧的收尾只遍历 {@link #units}（**还活着**的那些），于是现场出现
     * 「生成 6 个 ⇒ 回收 3 个」这种对不上的账（另外 3 个在 {@link #cleanDeadDisplays()}
     * 里被摘掉了，而且**一行日志都没有**）。
     * ⇒ 现在收尾遍历的是这个列表：<b>生成几个就回收几个</b>（回收是幂等的，
     * 已经回收过的再调一次只会得到 {@code SETTLED}）。
     */
    private final List<ObjectiveUnit> allUnits = new ArrayList<>();

    /** 光束判定「归属未知」时的告警只打一次（否则每 0.5 秒刷屏）。 */
    private boolean unknownSideWarned;

    /** 光束计时（tick 计数，达到 beam-interval-ticks 就打一轮）。 */
    private int beamTick;
    private boolean spawned;

    private ObjectiveService(Plugin plugin, String matchId, UnitRegistry registry, ArenaConfig arena,
                             ObjectiveConfig config, Map<Side, UUID> players, Logger logger) {
        this.plugin = plugin;
        this.matchId = matchId;
        this.registry = registry;
        this.arena = arena;
        this.config = config;
        this.players = players == null ? Map.of() : new LinkedHashMap<>(players);
        this.logger = logger;
        // ★★★ 2026-10-18：**真的注册**击退守卫（用户「防御塔攻击不应该带有击退效果」）。
        //   ★ 为什么必须在这里注册：本类**不是 Listener** ⇒ 写在本类上的 @EventHandler 是死代码。
        //   ★ 每局一个实例、`onStop` 里注销 ⇒ 不会把上一局的守卫留在事件总线上。
        try {
            plugin.getServer().getPluginManager().registerEvents(knockbackGuard, plugin);
        } catch (Throwable t) {
            logger.warning("[IC] 击退守卫注册失败，防御塔的伤害可能仍会击退："
                    + t + "（只影响「塔把人按住打」这一条，不影响别的）");
        }
    }

    /**
     * 建立本局的目标物系统。
     *
     * @param players 双方玩家（阵营 → UUID），用于光束索敌与战斗开始时传送
     */
    public static ObjectiveService create(Plugin plugin, String matchId, UnitRegistry registry,
                                          ArenaConfig arena, ObjectiveConfig config,
                                          Map<Side, UUID> players, Logger logger) {
        return new ObjectiveService(plugin, matchId, registry, arena, config, players, logger);
    }

    /**
     * 生成本局的全部目标物。
     *
     * @return 是否**全部**生成成功（false ⇒ 本局无目标物，退化成纯时间轴）
     */
    public boolean spawnAll() {
        if (spawned) {
            return !units.isEmpty();
        }
        spawned = true;

        if (arena == null || !arena.isReadyForMatch()) {
            List<String> missing = arena == null ? List.of("(arenas.yml 未加载)") : arena.missingKeysForMatch();
            logger.warning("[IC] 本局不生成目标物：arenas.yml 的点位不全，缺 " + missing
                    + "（fail-safe：本局退化为纯时间轴，最后按平局结算）"
                    + " —— 用 /incl arena 打开竞技场编辑界面、或 /incl arena set <点位名> 采集点位后重开一局"
                    + "（原写「用 /incl arena 采集点位」是当时的口径：那时这条指令还没实现，"
                    + "照着敲只会拿到用法提示；2026-10-15 已实现，见 docs/10 §8 ）");
            return false;
        }

        boolean allOk = true;
        for (Side side : Side.values()) {
            // ★★★ 2026-10-17 第十三轮：**目标物名牌统一**（用户裁定，逐字）：
            //   「目标物是指什么？」→ 我答「信标 + 防御塔」→ 用户裁定
            //   「**不保留方位**，即『防御塔』（使用蓝色/红色字体）即可」
            //   ⇒ ① **去掉「左 / 右」后缀**（旧值 `蓝方防御塔·左`）；
            //     ② 名字只写**东西本身**（信标 / 防御塔），**阵营靠颜色**区分
            //        （颜色由 UnitRegistry.tag 的阵营 PDC + 名牌渲染承担，见 §C16 的口径）；
            //     ③ 前缀保留「红方 / 蓝方」二字 —— 因为**颜色只是辅助**：
            //        色弱玩家 / 截图 / 日志里都需要能读出来（★ 这一格是我的判断，可否决）。
            String beaconLabel = (side == Side.RED ? "红方" : "蓝方") + "信标";
            ObjectiveUnit beacon = spawn(ObjectiveKind.BEACON, side, arena.beacon(side),
                    config.beacon().displayBlock(), false, config.beacon().health(),
                    config.beacon().taunt(), beaconLabel);
            allOk &= beacon != null;

            // ★ 两座塔**名字完全相同**（不再有 ·左 / ·右）—— 这正是用户要的"不保留方位"；
            //   区分它们靠**位置**（点位本身就在左右两侧），不靠名字。
            boolean left = true;
            for (@SuppressWarnings("unused") String suffix : List.of("左", "右")) {
                String towerLabel = (side == Side.RED ? "红方" : "蓝方") + "防御塔";
                ObjectiveUnit tower = spawn(ObjectiveKind.TOWER, side, arena.tower(side, left),
                        config.tower().displayBlock(), config.tower().displayLit(),
                        config.tower().health(), config.tower().taunt(), towerLabel);
                allOk &= tower != null;
                left = false;
            }
        }

        // ★ 半套目标物会让塔数比较得出假胜负 ⇒ 要么全有、要么全无
        if (!allOk) {
            logger.warning("[IC] 目标物只生成了一部分，全部回收，本局退化为纯时间轴"
                    + "（半套目标物会让「塔数比较」得出无意义的胜负）");
            removeAll("点位不完整");
            return false;
        }

        logger.info(ObjectiveAccounting.spawnLine(matchId, units.size(),
                registry.sizeOf(matchId)));
        return true;
    }

    private ObjectiveUnit spawn(ObjectiveKind kind, Side side, Point point, String block, boolean lit,
                                double health, boolean taunt, String label) {
        ObjectiveUnit unit = ObjectiveUnit.spawn(plugin, registry, matchId, kind, side, point,
                block, lit, health, taunt, label, logger);
        if (unit != null) {
            units.add(unit);
            allUnits.add(unit);
            objectiveIds.add(unit.id());
            tracker.register(unit.id(), kind, side);
        }
        return unit;
    }

    // ------------------------------------------------------------------ MatchHooks

    /**
     * 每 tick：刷新存活状态 → 打光束 → 清掉死亡目标物的外观。
     *
     * <p>★ 顺序理由：**先**刷新（边沿要在这一 tick 报出去）**再**清理外观
     * —— 反过来的话，死亡那一 tick 的边沿会被自己吃掉（`MatchSignal` 恒为空，W1/W4 永不触发）。
     */
    @Override
    public void onTick(MatchPhase phase, int battleTick) {
        if (units.isEmpty()) {
            return;
        }
        refreshAlive();
        // ★★★ 2026-10-18（用户第 5 条「防御塔应该免疫浮空」）：每 tick 让目标物免疫浮空
        //   （潜影贝子弹的漂浮会把它们抬到空中，而载体重力是关的 ⇒ 抬起来就回不来）
        for (ObjectiveUnit unit : units) {
            if (unit.isAlive(registry)) {
                unit.immuneToFloating();
            }
        }
        if (phase == MatchPhase.BATTLE || phase == MatchPhase.OVERTIME) {
            tickBeams();
        }
        cleanDeadDisplays();
    }

    @Override
    public MatchSignal signal() {
        return units.isEmpty() ? MatchSignal.NONE : tracker.signal();
    }

    @Override
    public void onPhaseChanged(MatchPhase from, MatchPhase to) {
        // ★ BATTLE 也传一次（**保留**）：`docs/02` §3.1 的点位表里 `player-spawn` 的用途逐字是
        //   「双方玩家落点（**准备 / 战斗开始位置**）」⇒ 战斗开始那一刻再对齐一次，
        //   同时兜住「准备阶段传送失败（世界未加载等）」这一路。
        if (to == MatchPhase.BATTLE) {
            teleportToSpawns();
        }
    }

    /**
     * ★★★ <b>准备阶段开始：把双方玩家送到各自出生点</b>
     * （2026-10-16 第八轮提出「准备阶段就要传送」、<b>第九轮修好挂点</b>）。
     *
     * <h2>★★★ 第九轮修的是「挂点」——第八轮挂错了回调（这就是现场那一跤的第三处根因）</h2>
     * 第八轮把这次传送写进了 {@link #onPhaseChanged} 的 {@code to == PREPARING} 分支。
     * 但那个分支<b>在一次对局里永远不会被执行</b>：
     * <pre>
     *   MatchState.INITIAL.phase() == MatchPhase.PREPARING
     *     （javap 实测：MatchState 的 static{} 是 getstatic MatchPhase.PREPARING
     *       ⇒ new MatchState ⇒ putstatic INITIAL；MatchService.&lt;init&gt; 再 putfield state）
     *   ⇒ 对局<b>一开始就已经在 PREPARING 里</b>，阶段机永远不产生「进入 PREPARING」这条边沿
     *     （MatchService.tick 只在 next.phase() != previous 时才调 onPhaseChanged）
     * </pre>
     * ⇒ 「准备阶段开始时该做的事」必须挂在<b>专用的</b> {@link MatchHooks#onPrepPhaseStarted()}
     * 上 —— 那份清单（送出生点 ＋ 发选卡菜单物品 ＋ 自动弹一次 U4）的三件事
     * <b>落在同一个回调上</b>，由 {@code MatchService#enterPrepPhase()} <b>恰好发一次</b>。
     *
     * <p>★ <b>为什么这是一处真正的缺陷、而不是"两条路都行"</b>：现场日志
     * （{@code E:\MCpaper\logs\latest.log} 17:52:56~17:53:56）里
     * {@code 准备阶段开始：已传送 …} 这一行<b>一次都没出现</b>，
     * 而 60 秒后的 {@code 准备阶段到点} 照常触发（它挂在 {@code tick()} 上、不经钩子）
     * ⇒ 玩家的体验就是「人没进去却照样被判负」（{@code docs/24} §6.0）。
     *
     * <p>★ 因此这里<b>不再</b>保留 {@code onPhaseChanged} 里的那份重复实现：
     * 同一件事只许有一处落点，否则「哪一份在跑」又要靠猜（本项目最反复的那类事故）。
     */
    @Override
    public void onPrepPhaseStarted() {
        teleportHumansToSpawns();
        // ★★★ 2026-10-20（用户：「abort 后重启，上局实体一直残留清不掉」）：
        //   **传送之后**再清一遍本局竞技场的遗留实体 —— 这一刻玩家已经站在竞技场里，
        //   区块**必然已加载**（而 onGameStart 那一刻其实还没传人，区块可能还没加载）。
        //   ★ 清理不主动加载区块，所以"必须等区块加载"是它唯一的前置；这里正是最可靠的时机。
        //   ★ 只清带我们 PDC 的实体、范围 = 本竞技场点位 ±64 格（objective/LegacyPurge）。
        try {
            if (plugin instanceof io.mczju.infernalclash.InfernalClashPlugin clash) {
                int removed = clash.purgeLegacyForArena(arena, "准备阶段传送后");
                if (removed > 0) {
                    logger.info("[IC] 准备阶段：已清掉上一局遗留的 " + removed
                            + " 个实体（matchId=" + matchId + "，范围 = 本竞技场 ±64 格 + PDC 归属）");
                }
            }
        } catch (Throwable t) {
            logger.warning("[IC] 准备阶段清理遗留实体失败（不影响对局）：" + t);
        }
    }

    @Override
    public void onStop(String reason) {
        // ★★ 2026-10-18：注销击退守卫（与构造里的 registerEvents 成对）。
        //   ★ 不注销 = 每局留一个 Listener 在事件总线上（泄漏 + 上一局的状态还在拦人）。
        try {
            org.bukkit.event.HandlerList.unregisterAll(knockbackGuard);
        } catch (Throwable t) {
            logger.warning("[IC] 击退守卫注销失败：" + t);
        }
        knockbackGuard.clear();
        removeAll(reason);
        sweepProjectiles(reason);
    }

    /**
     * ★★★ <b>收尾清掉留在战场上的弹射物</b>（2026-10-18 新增，用户第 7 条）。
     *
     * <p>口径与四条铁律 10 的回答全部在 {@link ProjectileSweep} 的 javadoc 里；
     * 这里只做三件事：<b>取球</b>（本场 6 个点位 ＋ 余量）、<b>筛弹射物</b>、
     * <b>打一行带数量的日志</b>。
     *
     * <p>★ 跳过本场账本里登记过的实体（目标物/召唤物本体由既有对账路径负责）、
     * 跳过玩家；点位不全或世界拿不到 ⇒ <b>一个都不删</b>并告警（fail-safe）。
     */
    private void sweepProjectiles(String reason) {
        if (arena == null || !arena.isReadyForMatch()) {
            logger.info("[IC] 弹射物收尾跳过（" + reason + "）：本局没有完整点位，无法界定战场范围"
                    + "（fail-safe：绝不退化成按世界清）｜matchId=" + matchId);
            return;
        }
        List<io.mczju.infernalclash.arena.Point> points = new ArrayList<>();
        for (Side side : Side.values()) {
            points.add(arena.beacon(side));
            points.add(arena.tower(side, true));
            points.add(arena.tower(side, false));
        }
        ProjectileSweep.Bounds bounds = ProjectileSweep.bounds(points);
        World world = bounds == null ? null : Bukkit.getWorld(bounds.world());
        if (bounds == null || world == null) {
            logger.info("[IC] 弹射物收尾跳过（" + reason + "）：算不出战场球 / 世界未加载｜matchId=" + matchId);
            return;
        }
        int removed = 0;
        int keptTracked = 0;
        try {
            for (Entity entity : world.getNearbyEntities(
                    new Location(world, bounds.x(), bounds.y(), bounds.z()),
                    bounds.radius(), bounds.radius(), bounds.radius())) {
                if (!isProjectile(entity)) {
                    continue;
                }
                if (registry.table().owner(entity.getUniqueId()) != null) {
                    keptTracked++;   // 本场账本里登记过的（不归这一步删）
                    continue;
                }
                try {
                    entity.remove();
                    removed++;
                } catch (Throwable ignored) {
                    // 删不掉的下一局也不会留在战场上（弹射物有寿命），不为此中断收尾
                }
            }
        } catch (Throwable t) {
            logger.warning("[IC] 弹射物收尾失败（" + reason + "）：" + t
                    + "，只影响残留的箭/火球，不影响对局结算｜matchId=" + matchId);
            return;
        }
        logger.info("[IC] 弹射物收尾（" + reason + "）：删除 " + removed + " 个 / 跳过账本内 "
                + keptTracked + " 个｜范围 = 本场 6 个点位外接球 ＋ " + ProjectileSweep.MARGIN
                + " 格（半径 " + String.format("%.1f", bounds.radius()) + "）｜matchId=" + matchId
                + "｜只删弹射物、不碰玩家、不碰账本内实体（铁律 10）");
    }

    /** 是不是「弹射物」这一类（箭 / 火球 / 潜影贝子弹 / 三叉戟 / 雪球…）。 */
    private static boolean isProjectile(Entity entity) {
        return entity instanceof org.bukkit.entity.Projectile
                || entity instanceof org.bukkit.entity.AbstractArrow
                || entity instanceof org.bukkit.entity.Fireball
                || entity instanceof org.bukkit.entity.SmallFireball
                || entity instanceof org.bukkit.entity.ShulkerBullet;
    }

    // ------------------------------------------------------------------ 内部

    private void refreshAlive() {
        Set<UUID> alive = new HashSet<>();
        for (ObjectiveUnit unit : units) {
            // ★ 判活只认阳性证据（见 ObjectiveUnit#isAlive 的 javadoc）：
            //   区块卸载**不再**等于死亡 ⇒ 不会再出现「没人打塔却判了胜负」
            if (unit.isAlive(registry)) {
                alive.add(unit.id());
            }
        }
        tracker.update(alive);
        // ★★★ 2026-10-20（用户实机报「加时掉塔却不结束」）：把**每一次**目标物出局打成一行日志。
        //   为什么必须有：这类"边沿"极难从游戏画面判断 —— 现场那次报表里既没有"塔被摧毁"的行、
        //   也没有 W4 的判决行，只能靠实体死亡日志（`Villager['红方防御塔'] died…`）去猜。
        //   现在每一次出局都会留下「哪一方、哪一种、当时的塔数」，下一次一眼就能对账。
        MatchSignal signal = tracker.signal();
        if (signal.beaconDownRed() || signal.beaconDownBlue()
                || signal.towerDownRed() || signal.towerDownBlue()) {
            logger.info("[IC] 目标物出局：信标边沿（红/蓝）= "
                    + signal.beaconDownRed() + "/" + signal.beaconDownBlue()
                    + "｜塔边沿（红/蓝）= " + signal.towerDownRed() + "/" + signal.towerDownBlue()
                    + "｜当前塔数 红:蓝 = " + signal.towersRed() + ":" + signal.towersBlue()
                    + "｜信标已失（红/蓝）= " + signal.beaconGoneRed() + "/" + signal.beaconGoneBlue()
                    + "（加时的 W4 用「塔数不等 / 信标已失」这条电平判据，不依赖边沿）");
        }
    }

    /**
     * 已经确定不在的目标物：<b>立刻删掉外观</b>（否则留下一块飘在空中的方块）。
     *
     * <p>★ 与旧实现的区别（缺陷 P0-c）：判据从 {@code carrier.isValid()} 换成
     * {@link ObjectiveUnit#isAlive(UnitRegistry)}（阳性证据），并且**回收结果不再被丢掉**
     * —— 删不掉的会留在账本里等 {@code UnitRegistry#retryPendingRemovals} 重试。
     */
    private void cleanDeadDisplays() {
        for (ObjectiveUnit unit : units) {
            if (!unit.isAlive(registry)) {
                ObjectiveUnit.Removal removal = unit.remove(registry);
                if (!removal.complete()) {
                    // ★ 必须说出来：这正是"对局结束了东西还立着"的那一格
                    logger.warning("[IC] 目标物回收未完成（" + unit.kind() + "/" + unit.side()
                            + "｜" + unit.deathReason() + "）：待回收 "
                            + removal.pending() + " 个实体 —— 已留在账本里等重试"
                            + "（区块未加载时删不了；绝不退化成按世界清）");
                } else {
                    // ★★ 2026-10-21 新增（缺陷：用户报「对局中对面塔被拆了之后建筑没有移除」）：
                    //   **局内**回收以前是完全静默的 —— 现场只能看到"塔没了但方块还在"，
                    //   日志里一个字都没有（连到底是"没删"还是"没跑"都分不清）。
                    //   ⇒ 每次局内回收留一行，带「删掉了几个 / 各自什么结局」。
                    logger.info("[IC] 目标物已在局内回收（" + unit.side() + "/" + unit.kind()
                            + "｜" + unit.deathReason() + "）：载体 " + removal.carrier()
                            + "｜外观 " + removal.display()
                            + "（SETTLED = 已删或已确认不在；PENDING / MISMATCHED 会留在账本里重试）"
                            + "｜matchId=" + matchId);
                }
            }
        }
        units.removeIf(unit -> !unit.isAlive(registry));
    }

    /**
     * 防御塔光束：每 {@code beam-interval-ticks} 一轮，对射程内的**敌方单位**造成伤害。
     *
     * <p>★ 索敌集合 = **本场账本登记的实体** ∪ **本局玩家**（都不是世界查询）。
     * 目标物的 UUID 被显式排除（见类注释的口径）。
     */
    private void tickBeams() {
        int interval = Math.max(1, config.tower().beamIntervalTicks());
        beamTick++;
        if (beamTick % interval != 0) {
            return;
        }
        int halfExtent = config.tower().halfExtentCells();
        double damage = config.tower().beamDamage();
        int hits = 0;

        for (ObjectiveUnit tower : units) {
            if (tower.kind() != ObjectiveKind.TOWER || !tower.isAlive(registry)) {
                continue;
            }
            Location center = tower.carrier().getLocation();
            int centerCellX = center.getBlockX();
            int centerCellZ = center.getBlockZ();

            // ★★★ 2026-10-18（用户原话，逐字）：
            //   「**防御塔攻击不应该带有击退效果，否则将永控某些敌人；并且，不应该能够同时攻击
            //     范围内所有目标，而是只应该锁定第一个进入范围的目标，或是在有多个目标时锁定
            //     最近的目标（烈焰守卫相同索敌规则）**」＋ 追问后「**3，但是需要嘲讽优先**」
            //   ⇒ ① **单体**（每座塔每轮只打一个，原来是 for 循环打全部）；
            //      ② **锁定**（跨 tick 记住，只有目标失效/离开射程才换）；
            //      ③ 选目标时**嘲讽优先**，同档比距离；
            //      ④ **不产生击退**（见下面的 damageBeam）。
            //   ★ 原来那个内层 `for (Entity target : candidates())` 的过滤条件
            //     （玩家剔除 / 阵营判据 / 射程）**一条都没丢** —— 它们全部搬进了
            //     `asBeamVictim(...)`，并由"锁定"与"重新选"共用（分成两份迟早分叉）。
            LivingEntity victim = beamVictimOf(tower, center, centerCellX, centerCellZ, halfExtent);
            if (victim == null) {
                continue;
            }
            Location location = victim.getLocation();
            damageBeam(tower, victim, damage);
            hits++;
            // ★★★ 2026-10-18（用户原话，逐字）：「**防御塔、冰术师这种单体指向性的攻击特效
            //   也需要有从施法者到目标的直线粒子特效**」。
            //   ★ 原来这里只在**目标身上**撒 1 点粒子 ⇒ 看不出"是谁打的、从哪打的"。
            //   ⇒ 现在从塔顶到目标胸口画一条直线（点数按距离算，远的不稀疏、近的不浪费）。
            drawBeamLine(center, location, victim);
        }
        if (hits > 0) {
            // 可对账：只在真的打中时打日志，避免每 0.5 秒刷屏
            logger.info("[IC] 防御塔光束命中 " + hits + " 座塔各 1 个目标（matchId=" + matchId
                    + "，每 " + interval + " tick 一轮，单次 " + (int) damage
                    + " 点，单体锁定 + 无击退）");
        }
    }

    /**
     * ★★★ <b>这座塔这一轮打谁</b>（2026-10-18 新增；单体 + 锁定 + 嘲讽优先）。
     *
     * <h2>口径（用户逐字）</h2>
     * 「**只应该锁定第一个进入范围的目标…（3，但是需要嘲讽优先）**」
     * <ol>
     *   <li><b>已锁定的目标仍合法</b>（活着、仍是敌方、仍在射程内）⇒ <b>继续打它</b>
     *       —— 即使出现了更近的、或新出现的嘲讽单位也**不换**（这就是"锁定"）；</li>
     *   <li>否则重新选：先比<b>有没有嘲讽</b>（{@code OwnershipTable.Owner#taunt}），
     *       同档再比<b>距离平方</b>（不开放 ⇒ 无浮点漂移）；</li>
     *   <li>选不出来 ⇒ 清掉锁定（下次重新选），本轮不开火。</li>
     * </ol>
     *
     * <p>★ 判据复用原来的三道门（{@code beamSkipReason} / {@code SideRule#mayBeamHit} /
     * 射程），**一处都不绕过** —— 本次只是把"遍历全部"改成"挑一个"。
     */
    private LivingEntity beamVictimOf(ObjectiveUnit tower, Location center,
                                      int centerCellX, int centerCellZ, int halfExtent) {
        // ① 还锁着的那个，若仍合法 ⇒ 继续
        UUID locked = tower.lockedTarget();
        if (locked != null) {
            LivingEntity kept = asBeamVictim(locked, tower, centerCellX, centerCellZ, halfExtent);
            if (kept != null) {
                return kept;
            }
            tower.lockTarget(null);   // 不合法（死了/走了/出射程/变友方）⇒ 解锁
        }
        // ② 重新选：嘲讽优先 → 最近
        LivingEntity best = null;
        boolean bestTaunt = false;
        double bestDistance = Double.MAX_VALUE;
        for (Entity target : candidates()) {
            if (!(target instanceof LivingEntity living)) {
                continue;
            }
            if (asBeamVictim(living.getUniqueId(), tower, centerCellX, centerCellZ, halfExtent) == null) {
                continue;
            }
            boolean taunt = isTaunt(living.getUniqueId());
            double distance = living.getLocation().distanceSquared(center);
            boolean better = (taunt && !bestTaunt)
                    || (taunt == bestTaunt && distance < bestDistance);
            if (better) {
                best = living;
                bestTaunt = taunt;
                bestDistance = distance;
            }
        }
        tower.lockTarget(best == null ? null : best.getUniqueId());
        return best;
    }

    /**
     * 这个 UUID 现在能不能被这座塔的光束打（能 ⇒ 返回实体，不能 ⇒ {@code null}）。
     *
     * <p>★ 把原来内层循环那一串 {@code continue} **原样**收进这里 —— 三道门
     * （玩家剔除 / 阵营判据 / 射程）一条都不减，而且现在**锁定与重新选共用同一套**判据
     * （分成两份迟早分叉：锁定那份忘了判射程，塔就会隔空打人）。
     */
    private LivingEntity asBeamVictim(UUID id, ObjectiveUnit tower, int centerCellX,
                                      int centerCellZ, int halfExtent) {
        Entity entity = Bukkit.getEntity(id);
        if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
            return null;
        }
        if (objectiveIds.contains(id)) {
            return null;   // 口径：光束不打目标物
        }
        if (beamSkipReason(living) != null) {
            return null;   // R42：任何一方、任何单位，玩家都不是合法目标
        }
        Side targetSide = sideOfTarget(living);
        if (!SideRule.mayBeamHit(tower.side(), targetSide)) {
            warnUnknownSide(targetSide);
            return null;
        }
        Location location = living.getLocation();
        if (location.getWorld() == null
                || !location.getWorld().equals(tower.carrier().getWorld())) {
            return null;
        }
        if (!BeamRange.inSquare(location.getBlockX(), location.getBlockZ(),
                centerCellX, centerCellZ, halfExtent)) {
            return null;
        }
        return living;
    }

    /**
     * 这个 UUID 在账本里有没有嘲讽（★ 读**账本的 PDC 标记**，不读实体类型 —— 归属判据的唯一来源）。
     *
     * <p>★ 为什么走 {@code registry.taunted(Entity)} 而不是自己判字段：
     * 嘲讽是写在实体 PDC 上的布尔（{@code UnitRegistry#tag(entity, owner, taunted)}），
     * 与 {@code matchId / side / owner} 同一处写入、同一处读出 —— 两处各判一次迟早分叉。
     */
    private boolean isTaunt(UUID id) {
        if (id == null) {
            return false;
        }
        Entity entity = Bukkit.getEntity(id);
        return entity != null && registry.taunted(entity);
    }

    /**
     * ★★★ <b>给光束目标造成伤害，且<b>不产生任何击退</b></b>（2026-10-18 新增）。
     *
     * <h2>用户原话（逐字）</h2>
     * 「**防御塔攻击不应该带有击退效果，否则将永控某些敌人**」
     *
     * <h2>为什么不能只改 {@code damage(...)} 的参数</h2>
     * 原实现是 {@code living.damage(damage, tower.carrier())} —— 带实体源 ⇒ 原版会按
     * 攻击者方向做**击退**。而 {@code KNOCKBACK_RESISTANCE} 属性只影响"原版算出来的击退量"，
     * 设成 1.0 也只是"击退 0 格"，**仍会走一遍击退流程**（把目标的速度写一次）。
     * <p>⇒ 这里改成**两道**：
     * <ol>
     *   <li>伤害改用 {@code damage(double)}（无实体源 ⇒ 不按攻击者方向推）；</li>
     *   <li>★★ 并且把该目标登记进 {@link #beamGuard}，
     *       由 {@link #onKnockback} 在本 tick / 下一 tick 里**取消**任何
     *       {@code EntityKnockbackEvent}（{@code Cause.DAMAGE}）——
     *       ★ 这一道才是"确定性不击退"，因为伤害事件与击退事件的先后在 Paper 里
     *       不属于我们的控制范围（实测过：有的伤害类型会延后一 tick 才推）。</li>
     * </ol>
     */
    private void damageBeam(ObjectiveUnit tower, LivingEntity victim, double damage) {
        // ★ 登记「刚被塔打过」—— 由 ObjectiveKnockbackGuard 在**真正注册的 Listener** 里
        //   取消随后 2 tick 内的任何 Cause.DAMAGE 击退。
        knockbackGuard.guard(victim, beamTick);
        // ★★ **单参** `damage(double)`（不是 `damage(double, Entity)`）：
        //   带实体源时原版会按攻击者方向推一次 ⇒ 那就是"塔把敌人按住打"的根源。
        //   ★ 这一道 + knockbackGuard 那一道，缺一条都不够（见方法 javadoc）。
        victim.damage(damage);
    }

    /**
     * ★★ <b>光束的「直线粒子特效」</b>（2026-10-18 新增）。
     *
     * <h2>用户原话（逐字）</h2>
     * 「**防御塔、冰术师这种单体指向性的攻击特效也需要有从施法者到目标的直线粒子特效**」。
     *
     * <h2>口径</h2>
     * <ul>
     *   <li>起点 = 塔的<b>中心往上 1.5 格</b>（塔身是目标物方块群，从胸口高度射出才像"光束"）；</li>
     *   <li>终点 = 目标的<b>身体中段</b>（{@code getHeight() * 0.5}）；</li>
     *   <li>点数按距离算（{@link io.mczju.infernalclash.spell.LineFx#pointsFor}）——
     *       远的不稀疏、近的不浪费；</li>
     *   <li>★ 另外在目标身上补一小簇（命中感），这一点是原来就有的行为，<b>没有删</b>。</li>
     * </ul>
     * ★ 整段**只影响观感**：任何异常都不许影响已经结算完的伤害
     * （所以这里只 {@code try} 一次、失败静默 —— 每 10 tick 一条 WARN 会把日志刷爆）。
     */
    private void drawBeamLine(Location towerCenter, Location victimLocation, LivingEntity victim) {
        World world = towerCenter.getWorld();
        if (world == null || victimLocation.getWorld() == null
                || !world.equals(victimLocation.getWorld())) {
            return;
        }
        try {
            double fromX = towerCenter.getX();
            double fromY = towerCenter.getY() + 1.5;
            double fromZ = towerCenter.getZ();
            double toX = victim.getLocation().getX();
            double toY = victim.getLocation().getY() + victim.getHeight() * 0.5;
            double toZ = victim.getLocation().getZ();
            double distance = io.mczju.infernalclash.spell.LineFx.distance(
                    fromX, fromY, fromZ, toX, toY, toZ);
            // ★ 贴身（同一个格子里）不画线：那一团粒子会糊在自己身上，反而看不出打了谁
            if (distance >= BEAM_LINE_MIN_DISTANCE) {
                int points = io.mczju.infernalclash.spell.LineFx.pointsFor(
                        distance, BEAM_LINE_STEP, 4, BEAM_LINE_MAX_POINTS);
                Particle particle = particle();
                for (double[] point : io.mczju.infernalclash.spell.LineFx.line(
                        fromX, fromY, fromZ, toX, toY, toZ, points)) {
                    world.spawnParticle(particle, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0);
                }
            }
            // 命中感（原有行为）：目标身上一小簇
            world.spawnParticle(particle(), victim.getLocation().add(0, 1, 0), 1, 0.1, 0.1, 0.1, 0.0);
        } catch (Throwable ignored) {
            // 观感失败绝不影响伤害/记账（这条路径每 10 tick 跑一次，告警会把日志刷爆）
        }
    }

    /** 光束直线的最小距离（格）：低于它就不画线（贴身攻击画线只会糊住双方）。 */
    private static final double BEAM_LINE_MIN_DISTANCE = 1.5;

    /** 光束直线的采样步长（格）：0.5 格一个点 ⇒ 肉眼是连续的。 */
    private static final double BEAM_LINE_STEP = 0.5;

    /** 光束直线最多画几个点（护栏：防止将来射程调大后一次撒几千点）。 */
    private static final int BEAM_LINE_MAX_POINTS = 64;



    /**
     * ★★★ <b>光束的「这一击为什么跳过」判据</b>（2026-10-17 第十二轮新增，缺陷 P0-c）。
     *
     * <h2>用户原话（逐字）</h2>
     * 「<b>敌方防御塔依旧会攻击玩家</b>（这在前面的文档里说过：<b>玩家不会成为任何实体的目标</b>）」
     *
     * <h2>★ 根因（不是猜的，是上轮修的"另一半"）</h2>
     * 第十轮只修了「<b>己方</b>塔打<b>己方</b>玩家」（引入 {@link SideRule#mayBeamHit}：
     * 只有"两侧阵营都已知且不同"才开火）。但
     * {@code SideRule.mayBeamHit(塔方, 敌方玩家)} = <b>true</b> ⇒
     * 「<b>敌方</b>塔打<b>敌方</b>玩家」在旧判据下是**被允许**的 ——
     * 而 R42 的口径是玩家根本不进候选集合。真正的根因在候选集合的构造：
     * 旧的 {@code candidates()} 把「本局玩家」也放了进去（那是为了让 SideRule 那一格能跑）。
     *
     * <h2>★ 为什么做成一个「返回原因 / null」的方法</h2>
     * 纯 {@code if (living instanceof Player) continue;} 也能修，但它<b>不可断言</b>
     * （{@code instanceof} 编译成 {@code instanceof} 指令而不是 {@code invoke*}，
     * 按方法名字节码扫描看不到）。做成一次<b>静态调用</b>之后，
     * {@code battle/PlayerTargetWiringTest} 就能用方法级字节码断言
     * 「{@code tickBeams} 里<b>确实</b>调用了 {@code beamSkipReason}」——
     * 而 {@link #beamSkipReason} 自己又被
     * {@code objective/ObjectiveProtectionListenerTest} 那样的纯断言逐格钉住。
     *
     * @return 跳过原因（人类可读）；{@code null} = **允许这一击**
     */
    public static String beamSkipReason(Entity target) {
        if (target instanceof Player) {
            return "目标是玩家（R42：玩家不会成为任何实体的目标 —— 无论哪一方）";
        }
        return null;
    }

    /**
     * ★ <b>「归属未知 ⇒ 不开火」的告警</b>（只打一次；见 {@link SideRule} 的失败方向）。
     *
     * <p>为什么它值得一条 WARN：归属未知有两种来源 ——
     * ① 玩家不在本局名册里（那是"多余人"或配置漏了）、
     * ② 账本里出现了 {@code side == null} 的实体（登记被写坏）。
     * 两者都是**该被人看见**的异常，而不是"每 0.5 秒都跳过"的日常。
     */
    private void warnUnknownSide(Side targetSide) {
        if (targetSide != null || unknownSideWarned) {
            return;
        }
        unknownSideWarned = true;
        logger.warning("[IC] 防御塔光束本次跳过了归属未知的目标，一律不打（fail-safe："
                + "未知 ≠ 敌方；这条正是「己方玩家被自己的塔打死」的修法）｜matchId=" + matchId
                + "｜若这条反复出现，说明有实体既不在账本、也不在本局名册里");
    }

    /** 候选目标：**只**取本场账本登记的实体（**全部来自我们自己的记录**）。 */
    private List<Entity> candidates() {
        List<Entity> list = new ArrayList<>();
        for (UUID id : registry.tracked(matchId)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) {
                list.add(entity);
            }
        }
        return list;
    }

    /**
     * ★★★ <b>一个候选目标的阵营</b>（缺陷 P0-a 的落点，2026-10-17 第十轮）。
     *
     * <p>查两处，**都来自我们自己的记录**（不查世界、不靠实体类型）：
     * <ol>
     *   <li><b>归属账本</b>（召唤物 / 目标物）：{@code OwnershipTable.Owner#side()}；</li>
     *   <li>★ <b>本局名册</b>（玩家）：玩家**从不进账本**（R42/T3 的结构保证）
     *       ⇒ 旧实现只查账本 ⇒ 玩家的阵营是 {@code null} ⇒ 被当成敌方。
     *       这里用 {@link #players}（阵营 → 本局玩家 UUID，来自
     *       {@code MatchService.sideAssignments}）反查。</li>
     * </ol>
     *
     * @return 阵营；**查不到就返回 {@code null}**（调用方按「未知 ⇒ 不是敌方」处理）
     */
    private Side sideOfTarget(Entity entity) {
        if (entity == null) {
            return null;
        }
        OwnershipTable.Owner owner = registry.table().owner(entity.getUniqueId());
        if (owner != null) {
            return owner.side();
        }
        for (Map.Entry<Side, UUID> entry : players.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equals(entity.getUniqueId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private Particle particle() {
        try {
            return Particle.valueOf(config.tower().beamParticle());
        } catch (IllegalArgumentException e) {
            return Particle.END_ROD;
        }
    }

    /**
     * 战斗开始：把双方玩家送到各自出生点。
     *
     * <p>★ 只传**本局玩家**；出生点缺失 ⇒ **不传送 + 告警**（绝不退化成"传到世界出生点"）。
     */
    private void teleportToSpawns() {
        teleport(players.keySet(), "战斗开始");
    }

    /**
     * ★★ <b>准备阶段开始：把双方玩家送到各自出生点</b>（2026-10-16 第八轮新增、
     * 第九轮修正挂点 —— 由 {@link #onPrepPhaseStarted()} 调用，见那里的 javadoc）。
     *
     * <h2>为什么必须在这里（现场证据）</h2>
     * 用户原话「控制台开局了，但是玩家还在等待房间里」（{@code docs/24} §5.8）。
     * 现场日志（{@code E:\MCpaper\logs\latest.log}）把时间线钉得很死：
     * <pre>
     *   17:12:15  /incl bot on              ⇒ 人机开局窗口已排：20 tick
     *   17:12:16  [IC] 对局开始｜真人 1 名    ⇒ 局已经开了（PREPARING 第 1 tick）
     *   17:12:16  直接 startGame() 返回后 state=RUNNING
     *   （接下来整整 60 秒 **没有任何传送行**，玩家留在原地 = 他说的"等待房间"）
     *   17:13:16  [IC] 阶段切换：PREPARING → BATTLE
     *   17:13:16  [IC] 战斗开始：已传送 1 名玩家到出生点
     * </pre>
     * ⇒ 旧实现只在 {@code to == BATTLE} 时传送，**准备阶段那一分钟玩家一直没被挪动**。
     *
     * <h2>★ 范围与失败方向（铁律 10 的四问）</h2>
     * <ol>
     *   <li><b>范围</b>：只传<b>本局名册里的玩家</b>（{@code players} = 阵营 → 本局玩家 UUID 的
     *       映射，来自 {@code MatchService.sideAssignments}），点位只从 {@code arenas.yml} 取
     *       （{@code arena.spawn(side)}）⇒ 不查世界、不查在线玩家、不用"附近"；</li>
     *   <li><b>失败方向</b>：点位缺失 / 世界未加载 / 玩家离线 ⇒ <b>不传送 + 告警</b>
     *       （{@link #teleportToSpawns} 已有的口径），**绝不**退化成「主世界出生点」；</li>
     *   <li><b>归属</b>：阵营 → UUID 的映射就是本局名册（不是靠实体类型判断）；</li>
     *   <li><b>可对账</b>：传送完成后打一行带<b>数量</b>的日志。</li>
     * </ol>
     */
    public void teleportHumansToSpawns() {
        teleport(players.keySet(), "准备阶段开始");
    }

    /**
     * 把给定阵营的玩家送到各自出生点（{@link #teleportHumansToSpawns} 与
     * {@link #teleportToSpawns} 的<b>唯一</b>实现 —— 免得两条路径的判据分叉）。
     *
     * @param sides   要传送的阵营（**必须**来自本局名册）
     * @param trigger 日志里的触发原因（可对账）
     */
    private void teleport(Set<Side> sides, String trigger) {
        int moved = 0;
        for (Side side : sides) {
            Point spawn = arena == null ? null : arena.spawn(side);
            if (spawn == null) {
                logger.warning("[IC] 未找到 " + side + " 的出生点（arenas.yml 的 spawn."
                        + side.name().toLowerCase() + "），该玩家不传送（fail-safe）");
                continue;
            }
            World world = spawn.world() == null ? null : Bukkit.getWorld(spawn.world());
            Player player = Bukkit.getPlayer(players.get(side));
            if (world == null || player == null || !player.isOnline()) {
                continue;
            }
            player.teleport(new Location(world, spawn.x(), spawn.y(), spawn.z(),
                    spawn.yaw(), spawn.pitch()));
            moved++;
        }
        if (moved > 0) {
            logger.info("[IC] " + trigger + "：已传送 " + moved + " 名玩家到出生点（matchId=" + matchId
                    + "）");
        }
    }

    /**
     * ★★ <b>回收本局的**全部**目标物并给出对账</b>（2026-10-17 第十轮重写，缺陷 P0-c）。
     *
     * <h2>旧实现错在哪</h2>
     * 它只遍历 {@link #units}（**此刻还活着**的那些）并把 {@code units.size()} 当成"回收数"
     * ⇒ 现场出现 {@code 生成 6 个 ⇒ 回收 3 个}，而少掉的那 3 个**一行日志都没有**
     * （它们在 {@code cleanDeadDisplays} 里被摘掉了，而且当时是**误判**的）。
     *
     * <h2>现在</h2>
     * 遍历 {@link #allUnits}（<b>生成过几个就回收几个</b>，回收是幂等的），
     * 并把每个实体的结局按桶统计 ⇒ 交给纯类 {@link ObjectiveAccounting} 做
     * {@code 生成 N ⇒ 回收 N} 的恒等式断言与日志（{@code removed+pending+mismatched == 2N}）。
     */
    private void removeAll(String reason) {
        int settled = 0;
        int pending = 0;
        int mismatched = 0;
        int recoveredUnits = 0;
        for (ObjectiveUnit unit : allUnits) {
            ObjectiveUnit.Removal removal = unit.remove(registry);
            settled += removal.settled();
            pending += removal.pending();
            mismatched += removal.mismatched();
            if (!unit.isAlive(registry)) {
                recoveredUnits++;   // 账本已经不认它 ⇒ 这个目标物确实回收掉了
            }
        }
        ObjectiveAccounting.Recovery recovery = new ObjectiveAccounting.Recovery(
                allUnits.size(), recoveredUnits,
                ObjectiveAccounting.registeredEntities(allUnits.size()),
                settled, pending, mismatched);

        units.clear();
        objectiveIds.clear();
        if (!allUnits.isEmpty()) {
            logger.info(ObjectiveAccounting.recoveryLine(matchId, reason, recovery)
                    + (recovery.balanced()
                            ? "｜对账通过（生成 == 回收，且每个实体都落进了一个桶）"
                            : "｜对账未通过：见上面的「待回收 / 归属不符」计数"
                                    + "（待回收的会在每秒重试里补删，绝不按世界清）"));
        }
    }

    /** 已就位的目标物数量（测试 / 日志用）。 */
    public int unitCount() {
        return units.size();
    }

    /**
     * ★★ <b>某一方某一种类目标物的血量</b>（有序）——
     * 2026-10-17 第十三轮新增，用户裁定侧边栏要显示「塔1 / 塔2 / 信标」的血量。
     *
     * <h2>顺序（★ 用户裁定「左：1，右：2」）</h2>
     * 按 {@link #allUnits} 的**生成顺序**：信标先于两座塔，塔按**左、右**
     * （生成时的循环就是 {@code for (suffix : 左, 右)}）。
     * ★ 用 `allUnits`（含已死的）而不是 `units`（只含活的）：
     * 「已死的塔」在侧边栏上必须还是**那一行**、血量显示 0
     * —— 若从列表里摘掉，玩家的侧边栏会突然少一行（看起来像界面坏了）。
     *
     * <h2>★ 铁律 10</h2>
     * 只读**我们自己的**目标物列表（{@code allUnits}），不查世界、不按类型找。
     *
     * @return 每一个该种类目标物的 {@code {当前血量, 上限}}；没有 ⇒ 空数组
     */
    public double[][] healthOf(Side side, ObjectiveKind kind) {
        if (side == null || kind == null) {
            return new double[0][];
        }
        List<double[]> values = new ArrayList<>();
        for (ObjectiveUnit unit : allUnits) {
            if (unit.side() == side && unit.kind() == kind) {
                values.add(new double[]{unit.currentHealth(), unit.maxHealth()});
            }
        }
        return values.toArray(new double[0][]);
    }

    /** 存活追踪器（排障用）。 */
    public ObjectiveTracker tracker() {
        return tracker;
    }
}
