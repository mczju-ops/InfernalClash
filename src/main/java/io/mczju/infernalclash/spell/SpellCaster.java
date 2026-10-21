package io.mczju.infernalclash.spell;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.card.Facing;
import io.mczju.infernalclash.card.Spell;
import io.mczju.infernalclash.card.SummonSpawner;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★ <b>法术施放管线</b>：目标（targeting）→ 形状（shape）→ 效果（effects）。
 *
 * <h2>三层各管什么</h2>
 * <ol>
 *   <li><b>落点</b>（{@link Spell.Shape#origin()}，Q27）：{@code SELF} ⇒ 对空气时落在玩家处；
 *       {@code IMPACT} ⇒ 落在瞄准的那一格；{@code TARGET} ⇒ 以单体目标为源点；</li>
 *   <li><b>格集合</b>：交给纯函数 {@link ShapeMath}（★ 不查世界、不碰实体 ⇒ 边界能逐格单测）；</li>
 *   <li><b>效果</b>：交给 {@link EffectApplier} 逐类作用到目标上。</li>
 * </ol>
 *
 * <h2>★ 铁律 10：候选从账本里来</h2>
 * 「哪些实体在法术范围内」这件事**不查世界** —— 遍历 {@link UnitRegistry#tracked(String)}
 * （本场登记过的 UUID）→ 解析实体 → 看它落在哪个格 ⇒ 与索敌（P5）是**同一套归属口径**。
 * 玩家也不在账本里 ⇒ **法术打不到玩家**（与 R42「召唤物不索敌玩家」同一口径；
 * ⚠️ 这一点已记在 `docs/04` §16.9 与 `docs/05`，属可实现的决定，可否决）。
 *
 * <h2>★ 嵌套限深 1（Q26）</h2>
 * `depth` 由本类传递：{@code depth >= 1} 时**拒绝再次嵌套**并告警，
 * 绝不出现 A 放 B、B 又放 A 的无限递归。
 */
public final class SpellCaster {

    /**
     * 单体指向（准星）的最大射程。
     *
     * <p>★ `Targeting` 需要准星，而 Raycast 必须给一个距离 —— 文档里**没有**这个数
     * ⇒ 取 32 格（一个明确、可改的常量）并登记在 `docs/04` §16.9，**不是**从别处"推"出来的。
     */
    public static final int TARGET_RANGE = 32;

    /** 一次施放的结果（对账用）。 */
    public record Report(boolean cast, int cells, int targets, int effectsApplied, String note) {
        public static Report refused(String note) {
            return new Report(false, 0, 0, 0, note);
        }
    }

    /**
     * ★★★ <b>施放者</b>（2026-10-21 新增）—— 让法术管线不再强依赖 {@code Player}。
     *
     * <h2>为什么需要它（缺陷来源：人机要放法术）</h2>
     * 用户 2026-10-21 裁定「人机现在需要能够出法术牌」，而人机**没有 Player 对象**。
     * 旧签名 {@code cast(Player caster, …)} 用到 Player 的只有三样：
     * <b>UUID</b>（效果归属）、<b>位置与朝向</b>（落点 / 扇形与长方形的铺开方向）、
     * <b>准星上那个实体</b>（单体目标）。⇒ 把这三样抽成一个值对象，两条路共用同一套管线。
     *
     * <h2>★ 玩家那一侧的行为必须一字不变</h2>
     * {@link #player} 非空时，{@link #aimed()} **仍然**走
     * {@code Player#getTargetEntity(32)}（**有界**射线，铁律 10）——
     * 不是"先算好再传"，调用点与次数都与重构前一致。
     *
     * @param id       施放者 UUID（效果归属 / 冷却窗口 / 「给施放者加经验」都读它）
     * @param location 施放者位置（落点与朝向的来源；<b>不做快照</b>，与原实现的取值时机一致）
     * @param player   真人施放者（人机 ⇒ {@code null}）
     * @param aimed    ★ 非玩家施放者的「单体目标」（人机按规则选出来的友方召唤物；
     *                 玩家路径不用它，恒传 {@code null}）
     */
    public record Caster(UUID id, Location location, Player player, Entity aimed) {

        /** 真人施放者（准星仍然现取，见类注释）。 */
        public static Caster of(Player player) {
            if (player == null) {
                return null;
            }
            return new Caster(player.getUniqueId(), player.getLocation(), player, null);
        }

        /**
         * ★ 非玩家施放者（人机）：显式给位置、朝向与单体目标。
         *
         * @param aimed 单体目标（可空）；★ 只有 {@code RANDOM_ALLY_TARGET} 会传非空
         */
        public static Caster of(UUID id, Location location, Entity aimed) {
            if (id == null || location == null) {
                throw new IllegalArgumentException("非玩家施放者至少要给 UUID 与位置"
                        + "（id=" + id + " / location=" + location + "）");
            }
            return new Caster(id, location, null, aimed);
        }

        /**
         * ★ <b>这一次的单体目标</b>：真人 ⇒ **现查准星射线**（与重构前一致）；
         * 人机 ⇒ 规则选出来的那个召唤物。
         *
         * <p>★ 名字不能叫 {@code aimed}：那与 record 的组件访问器同签名，
         * 而 record 的访问器**必须是 public**（实测报「非法访问器方法」）⇒ 换个名字最省事。
         */
        Entity aimedEntity() {
            if (player != null) {
                return player.getTargetEntity(TARGET_RANGE);
            }
            return aimed;
        }

        /** 真人施放者（没有 ⇒ {@code null}）—— 自我光环（粒子跟着人走）与火球观感要用它。 */
        Player playerOrNull() {
            return player;
        }
    }

    private final Plugin plugin;
    private final UnitRegistry registry;
    private final SummonSpawner spawner;
    private final CardCatalog catalog;
    private final Logger logger;

    /** 特效解析失败只告警一次（否则每次施放都刷屏）。 */
    private boolean fxParticleWarned;

    /** 「纯视觉闪电」的告警去重（见 {@link #strikeLightningFx}）。 */
    private boolean strikeFxWarned;

    /**
     * ★ 已经打过「特效自检」那一行的卡 id（每场每卡一次，见 {@link #playFx}）。
     *
     * <p>为什么要它：用户报的是「**没有特效**」，而这类缺陷**只有真人能看见** ——
     * 日志里原来只有一个像素级的线索（什么都没有）。这一行把"应该播什么"写进日志，
     * 让下一次同类反馈能靠 `grep 法术特效` 直接定位到：
     * 帧数是不是 0 / 落点是不是自己 / 走的哪条粒子路。
     */
    private final java.util.Set<String> fxReported = new java.util.HashSet<>();

    public SpellCaster(Plugin plugin, UnitRegistry registry, SummonSpawner spawner, CardCatalog catalog) {
        this.plugin = plugin;
        this.registry = registry;
        this.spawner = spawner;
        this.catalog = catalog;
        this.logger = plugin.getLogger();
    }

    /**
     * 施放一个法术（<b>真人施放者</b>的入口；行为与重构前一字不差）。
     *
     * @param caster     施放者
     * @param matchId    对局
     * @param side       施放者阵营
     * @param cardId     这张卡的 id（`REFRESH_COOLDOWN` 要用）
     * @param spell      法术定义
     * @param impact     瞄准的那一格（点空气 ⇒ 传 {@code null}）
     * @param depth      嵌套深度（顶层 0；Q26 限 1）
     */
    public Report cast(Player caster, String matchId, Side side, String cardId, Spell spell,
                       Location impact, int depth) {
        Caster handle = Caster.of(caster);
        if (handle == null) {
            return Report.refused("施放者为空（既没有玩家也没有人机身份）");
        }
        return cast(handle, matchId, side, cardId, spell, impact, depth);
    }

    /**
     * ★★★ <b>施放一个法术（通用入口）</b> —— 2026-10-21 起玩家与人机共用同一条管线。
     *
     * <h2>三层各管什么（与重构前一致）</h2>
     * <ol>
     *   <li><b>落点</b>：{@code SELF} ⇒ 施放者处；{@code IMPACT} ⇒ 瞄准的那一格（人机 ⇒ 规则算出的落点）；
     *       {@code TARGET} ⇒ 单体目标处（人机 ⇒ 规则选出的那个友方召唤物）；</li>
     *   <li><b>格集合</b>：{@link ShapeMath}（纯函数；朝向取施放者的 yaw ——
     *       人机传的是那个召唤物自己的 yaw）；</li>
     *   <li><b>效果</b>：{@link EffectApplier} 逐类作用到目标上。</li>
     * </ol>
     *
     * @param caster     施放者（{@link Caster#of(Player)} 或 {@link Caster#of(UUID, Location, Entity)}）
     * @param matchId    对局
     * @param side       施放者阵营
     * @param cardId     这张卡的 id（`REFRESH_COOLDOWN` 要用）
     * @param spell      法术定义
     * @param impact     落点（人机传规则算出的那一格；玩家传瞄准的方块 / {@code null}）
     * @param depth      嵌套深度（顶层 0；Q26 限 1）
     */
    public Report cast(Caster caster, String matchId, Side side, String cardId, Spell spell,
                       Location impact, int depth) {
        if (caster == null) {
            return Report.refused("施放者为空（既没有玩家也没有人机身份）");
        }
        if (spell == null || spell.shape() == null) {
            return Report.refused("法术定义不完整（缺 shape）");
        }

        // ---- ① 落点 ----
        Location origin = resolveOrigin(caster, spell, impact);
        if (origin == null || origin.getWorld() == null) {
            return Report.refused("算不出落点（既没有瞄准格也没有施放者位置）");
        }

        // ---- ② 格集合（纯函数）----
        Spell.Shape shape = spell.shape();
        List<ShapeMath.Cell> cells;
        try {
            cells = ShapeMath.cells(shape.type(), origin.getBlockX(), origin.getBlockZ(),
                    Facing.fromYaw(caster.location().getYaw()),
                    shape.radius(), shape.width(), shape.length(), shape.angle());
        } catch (IllegalArgumentException e) {
            // 形状参数非法 ⇒ **拒绝施放**（不是静默退化成单格）
            return Report.refused("形状参数非法：" + e.getMessage());
        }
        Set<Long> cellKeys = new HashSet<>();
        for (ShapeMath.Cell cell : cells) {
            cellKeys.add(key(cell.x(), cell.z()));
        }

        // ---- ③ 目标 ----
        List<LivingEntity> targets = collectTargets(caster, matchId, side, spell, cellKeys, origin);

        // ---- ③b 形状已覆盖的目标里，再按 `spell.select` 挑（2026-10-17 批次 3 收尾）----
        //   ★ 出处 docs/19 第 136 行（暴风雨）：「取范围内**血量最高的三名**敌人，依次造成
        //     三次雷击」—— 这是一条**选敌规则**，做在法术层（对所有效果统一生效），
        //     理由见 `Spell.Select` 的 javadoc（避免"每条效果各挑一次"那种分叉）。
        //   ★ 不写这个键 ⇒ 全都要（14 张法术卡行为一字未变）。
        String selectNote = null;
        if (!spell.selectsAll()) {
            int before = targets.size();
            targets = applySelection(spell, targets);
            selectNote = "按 " + spell.select().rule() + " 取前 " + spell.select().count()
                    + " 名（范围内原有 " + before + " 个目标）";
        }

        // ---- ④ 效果 ----
        AppliedEffects applied = new AppliedEffects();
        EffectApplier.Context context = new EffectApplier.Context(plugin, matchId, side,
                caster.id(), cardId, depth, applied);

        // ★ 2026-10-21：非玩家施放者（人机）没有 Player 对象 ⇒ 「不需要目标的那些效果」
        //   拿不到替身实体。它们全都只用 origin / context（见 EffectScope 的清单），
        //   所以传 null 是安全的；玩家路径仍传施放者本人（行为与重构前一致）。
        LivingEntity standIn = caster.playerOrNull();

        int appliedCount = 0;
        int skippedWithoutTarget = 0;
        for (Spell.Effect effect : spell.effects()) {
            if (targets.isEmpty()) {
                // ★★★ 2026-10-17 第十轮修（缺陷 P1-a）：**只有不需要目标实体的效果**
                //   才允许在「一个都没命中」时跑一次（召唤/嵌套/刷新冷却/给经验那几个，
                //   它们用的是 origin 与施放者上下文，不碰 target）。
                //   旧实现把所有效果都拿**施放者**当替身 ⇒ 对空放火球 = 打自己 20 点 +
                //   把自己击退（现场日志：`命中 0 个目标 / 应用 2 次效果`，见 EffectScope 的 javadoc）。
                if (!EffectScope.runsWithoutTarget(effect.type())) {
                    skippedWithoutTarget++;
                    continue;
                }
                // 没有实体目标的效果（召唤类等）也要生效一次：把施放者当作"目标"传下去，
                // 但**召唤中心以 origin 为准**（见 EffectApplier 的口径）
                if (EffectApplier.apply(effect, context, standIn, origin,
                        () -> applyNested(caster, matchId, side, effect, origin, depth))) {
                    appliedCount++;
                }
                continue;
            }
            for (LivingEntity target : targets) {
                if (!EffectApplier.appliesTo(effect.type(), target)) {
                    continue;
                }
                if (EffectApplier.apply(effect, context, target, origin,
                        () -> applyNested(caster, matchId, side, effect, origin, depth))) {
                    appliedCount++;
                }
            }
        }

        // ★ 特效（观感）：docs/19 对每张法术都写了「特效」列（火焰粒子环 / 打击粒子环 + 音效），
        //   第十轮起由 spell/Spell.Fx + SpellCaster#playFx 落地（详见那里的 javadoc）。
        //   ★★ 2026-10-18：它现在是**按 FxTimeline 的时间轴反复播**（加粗 + 延长），
        //     持续期取自卡里 SUMMON_DELAYED 的 `delay-ticks` / SUMMON_PERIODICALLY 的 `duration-ticks`。
        playFx(caster, spell, cells, origin, matchId, cardId);
        // ★★ 2026-10-17 第十三轮（补上 docs/19 第 135 行对暴风雨的硬要求）：
        //   「⚠️ **必须用 `strikeLightningEffect`（纯视觉）**」——
        //   它**不是**粒子/音效，所以由独立的一条路负责；而它必须在**每个被雷击的目标**
        //   头上各劈一道（那才是"依次落雷"的观感）。
        //   ★★★ 2026-10-18 改正（用户裁定，逐字：「**暴风雨的落雷+海王的落泪（雷）；其他全去掉**」）：
        //     判据从「有 DAMAGE 就劈」改成「**卡片自己写了 `fx.lightning: true`**」——
        //     旧判据让 14 张伤害法术全都在劈闪电（用户：「法术牌依旧保留着雷击特效，不需要这个」）。
        strikeLightningFx(spell, targets, origin);
        StringBuilder note = new StringBuilder();
        if (applied.size() > 0) {
            note.append("留下 ").append(applied.size()).append(" 条待回滚/待恢复的状态");
        }
        if (skippedWithoutTarget > 0) {
            if (note.length() > 0) {
                note.append("｜");
            }
            // ★ 可对账：这一格就是「对空放法术」的现场表现（以前它会静默打自己）
            note.append("跳过 ").append(skippedWithoutTarget)
                    .append(" 条「必须有目标」的效果（一个都没命中，绝不改打在施放者身上）");
        }
        if (selectNote != null) {
            if (note.length() > 0) {
                note.append("｜");
            }
            // ★ 可对账：暴风雨那一档「取前 N 名」的结果要能看见（否则实机分不清
            //   "挑了前三" 与 "只打到一个"）
            note.append(selectNote);
        }
        return new Report(true, cells.size(), targets.size(), appliedCount, note.toString());
    }

    /**
     * ★★ <b>按 {@code spell.select} 从已覆盖的目标里再挑</b>（2026-10-17 批次 3 收尾新增）。
     *
     * <h2>口径（逐条可复述）</h2>
     * <ol>
     *   <li>{@link Spell.Select.Rule#HIGHEST_HEALTH} ⇒ 按<b>当前生命值</b>从高到低排序；</li>
     *   <li>取前 {@code count} 名；<b>不足</b>就按实际人数（{@code docs/19} 第 136 行
     *       「不足 3 名时按实际人数各打一次」＝【C18】）；</li>
     *   <li>★ <b>并列按实体 UUID 升序</b>打破 —— 否则「同输入 ⇒ 同结果」不成立
     *       （血量相同的两个单位谁先挨雷击会随迭代顺序变）；</li>
     *   <li>返回的清单<b>保序</b>（血量从高到低）——「<b>依次</b>落雷」的观感就来自这个顺序
     *       （{@code strikeLightningEffect} 是按这个列表逐个劈的）。</li>
     * </ol>
     *
     * <p>★ 失败方向：读不到生命值（实体异常）⇒ 当作 0（排到最后），<b>绝不</b>把它排到最前
     * ——「打最弱的那几个」比「漏打最强的」更难被玩家发现是错的。
     */
    private List<LivingEntity> applySelection(Spell spell, List<LivingEntity> targets) {
        Spell.Select select = spell.select();
        if (select == null || targets.isEmpty()) {
            return targets;
        }
        List<LivingEntity> sorted = new ArrayList<>(targets);
        if (select.rule() == Spell.Select.Rule.HIGHEST_HEALTH) {
            sorted.sort((left, right) -> {
                double a = healthOf(left);
                double b = healthOf(right);
                if (a != b) {
                    return Double.compare(b, a);   // 血量高的在前
                }
                return left.getUniqueId().compareTo(right.getUniqueId());
            });
        }
        if (sorted.size() > select.count()) {
            return new ArrayList<>(sorted.subList(0, select.count()));
        }
        return sorted;
    }

    /** 当前生命值（读不出来 ⇒ 0 ⇒ 排到最后，绝不排到最前）。 */
    private static double healthOf(LivingEntity entity) {
        try {
            return entity.getHealth();
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * ★ <b>法术特效</b>（2026-10-17 第十轮新增，缺陷 P1-a 的「无特效」那一半）。
     *
     * <p>做法：把形状的**边界格**（{@link FxRing#boundary}，纯函数）各撒一点粒子，
     * 并在落点播一次音效。★ 失败方向：粒子名解析不出来 / 世界拿不到 ⇒
     * <b>只告警一次、什么都不做</b>（绝不影响伤害与其它效果 —— 观感问题不该让法术失效）。
     *
     * <p>★ 只遍历**本次施放的形状格**（有界、来自 {@link ShapeMath}），不查世界、不查附近实体
     * （铁律 10）。
     */
    /**
     * ★★ <b>方块粒子（{@code fx.block}）</b> —— 2026-10-18 新增
     * （用户：「**掘墓人依旧缺失特效**」）。
     *
     * <h2>它修的是哪一类"写了却不显示"</h2>
     * 原版有两类粒子**必须带 data** 才会被客户端渲染：
     * <ul>
     *   <li>{@code Particle.BLOCK} / {@code FALLING_DUST} / {@code DUST_PILLAR} —— 需要 {@code BlockData}；</li>
     *   <li>{@code Particle.ITEM} / {@code ITEM_SNOWBALL} 等 —— 需要 {@code ItemStack}。</li>
     * </ul>
     * 上一轮给掘墓者写的 {@code particle: ITEM_SNOWBALL} 走的是**无 data** 的那条
     * ⇒ 屏幕上**什么都没有**，而装载、施放、日志全都"正常"。
     *
     * <p>★ 现在 {@code block: DIRT} ⇒ {@code spawnParticle(Particle.BLOCK, …, data)}，
     * 用 {@code Material#createBlockData()} 造那段 data；
     * 落点沿用与普通特效同一套**边界格**（形状为 SINGLE 时就撒在那一格）。
     * <p>★ 失败只告警（观感问题绝不影响伤害），与法术特效整体同一口径。
     * <p>★ 2026-10-18 收口：本方法现在由 {@link #emitFx} 按 {@link FxTimeline} 的时间轴
     * <b>反复</b>调用（掘墓人那 3 秒等待期里一直在落土），音效只在第一次播。
     *
     * @param beat 第几次脉冲（0 = 第一次）—— 用来让土块**往上飘一点**，看起来像在掘
     */
    private void emitBlockFx(Spell.Fx fx, List<ShapeMath.Cell> cells, Location origin,
                             String matchId, int beat) {
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        org.bukkit.block.data.BlockData data;
        try {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(fx.block());
            if (material == null || !material.isBlock()) {
                if (!fxParticleWarned) {
                    fxParticleWarned = true;
                    logger.warning("[IC] 法术特效的方块名 " + fx.block()
                            + " 不是方块，本场不再尝试（只影响观感）｜matchId=" + matchId);
                }
                return;
            }
            data = material.createBlockData();
        } catch (Throwable t) {
            logger.warning("[IC] 方块粒子创建失败（" + fx.block() + "）：" + t);
            return;
        }
        // 落点：有边界格就沿边界撒（能勾出范围）；没有就撒在落点本身
        List<ShapeMath.Cell> where = cells.isEmpty()
                ? List.of(new ShapeMath.Cell(origin.getBlockX(), origin.getBlockZ()))
                : FxRing.boundary(cells);
        double y = origin.getBlockY() + 1.0 + (beat % 3) * 0.25;
        // ★★ 2026-10-19 用户裁定（逐字）：「掘墓人**粒子量翻倍**，**显示范围也要扩大一点**」
        //   ⇒ 每格 2 → **4** 颗；散布 0.35 → **0.6** 格（土块铺得更开）。
        //   ★ 出厂只有 `grave_digger` 用 `fx.block`（全仓 grep 仅 1 处）⇒ 这两格
        //     不影响任何别的法术的观感。
        int perCell = Math.max(2, BLOCK_FX_PARTICLES_PER_CELL);
        for (ShapeMath.Cell cell : where) {
            world.spawnParticle(Particle.BLOCK, cell.x() + 0.5, y, cell.z() + 0.5,
                    perCell, BLOCK_FX_SPREAD, BLOCK_FX_SPREAD, BLOCK_FX_SPREAD, 0.0, data);
        }
    }

    /**
     * ★★ <b>法术特效的一帧</b>（2026-10-18 重构：从"撒一次"变成"按时间轴撒 N 次"）。
     *
     * <h2>为什么改（用户逐字）</h2>
     * 「**法术的环状特效不够明显，请你加粗并延长持续时间**」＋
     * 「**信息素这种持续召唤的法术更是需要一直显示粒子效果直到法术效果结束**」
     * ＋ 「**掘墓人 …召唤前持续放泥土粒子**」。
     * ⇒ 时间轴全部交给纯函数 {@link FxTimeline}（脉冲间隔 / 次数 / 持续期都可离线单测），
     * 本方法只负责"<b>画一帧</b>"。
     *
     * <h2>三条路（按优先级，与以前一致）</h2>
     * <ol>
     *   <li>{@code fx.block} ⇒ 方块粒子（{@link #emitBlockFx}）；</li>
     *   <li>{@code fx.particle} ⇒ 普通粒子（沿形状边界画一圈；落点就是施放者本人时改成
     *       撒在施放者周围 —— 见下方「自我增益卡」那段注释）；</li>
     *   <li>两者都不写 ⇒ 什么都没有（{@code fx.lightning} 那种纯闪电由
     *       {@link #strikeLightningFx} 负责，不在这里）。
     *       ★ 但**音效**仍然播（{@link #playFx} 里独立处理，只在第一帧播一次）。</li>
     * </ol>
     *
     * <p>★ 「加粗」= 每个边界格撒 {@link FxTimeline#PARTICLES_PER_POINT} 点 + 0.25 的随机偏移；
     * ★ 「延长」= 由 {@link #playFx} 每 {@link FxTimeline#PERIOD_TICKS} tick 调本方法一次。
     * <p>★ 整段**只影响观感**：任何一步失败都只告警，绝不影响伤害/召唤/状态结算。
     *
     * @param beat 第几次脉冲（0 = 第一帧；用来让环轻微上浮，观感更"活"）
     */
    private void emitFx(Spell.Fx fx, List<ShapeMath.Cell> cells, Location origin, Caster caster,
                        String matchId, int beat) {
        if (fx == null) {
            return;
        }
        // ★★ 2026-10-18：**方块粒子**（`fx.block`）走单独一条路。
        //   根因（用户「掘墓人依旧缺失特效」）：`ITEM_*` / `BLOCK` 这类粒子**需要 data 参数**，
        //   而这里原先的 `spawnParticle(particle, …)` 没有 data ⇒ **客户端根本不渲染**
        //   ⇒ 配置写对了、装载也过了、屏幕上却什么都没有。
        if (fx.hasBlockParticle()) {
            emitBlockFx(fx, cells, origin, matchId, beat);
            return;
        }
        if (fx.particle() == null) {
            return;
        }
        Particle particle = resolveParticle(fx);
        if (particle == null) {
            return;
        }
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        // ★★★ 2026-10-17 第十三轮（用户裁定，逐字）：
        //   「你提到的这两个（速度药水 / 战术冷却）需要**在使用它们的玩家本体身上**出现粒子」
        //   ⇒ 这两个法术的形状是 SINGLE（只有一个格、还是**施放者自己**那一格），
        //     用"边界格"画环的话玩家几乎看不到（单格的边界就是它自己，粒子会埋在身上）
        //   ⇒ 这两张卡走**另一条路**：以施放者为中心撒一圈粒子（看得见、且明确是"我身上出现了什么"）。
        //   ★ 判据只认「是不是施放者自己那一格」——**不按卡 id 判**（按 id 判会让"再加一张自我增益卡"
        //     看起来能用、实则没有粒子，那种分叉正是本项目最反感的形状）。
        //   ★ 2026-10-18：这条路径现在**每帧都会重读施放者当前位置** ⇒ 玩家边走边亮，
        //     环跟着人走（正是"光环范围实刻显示"要的观感）。
        //   ★★★ 2026-10-18 再修（用户第 3 条：「**「狂暴」法术没有环形特效**」）：
        //     根因 = 这条"撒在自己身上"的分支<b>只判了"落点是不是自己"</b>，没有判形状
        //     ⇒ 嗜血（`CIRCLE radius 5`）**对着自己脚下放**时也被当成"自我增益"
        //     ⇒ 画出来的是**半径 1.0~1.4 的三圈小光环**，而不是它声明的**半径 5 的环**
        //     ⇒ 玩家看到的就是"没有环形特效"（那几圈小光环埋在自己身上，几乎看不见）。
        //     ⇒ 判据改成 {@link #usesSelfRing}：**只有单格形状**才走自身光环，
        //       多格形状（CIRCLE / RECTANGLE / CONE）**永远**画它自己的边界环
        //       —— 即使施放者正站在圆心。★ 判据按"格数"而不是按卡 id：
        //       按 id 判会让"再加一张自我增益法术"看起来能用、实则没有粒子。
        if (usesSelfRing(cells == null ? 0 : cells.size(), isOnCaster(origin, caster))) {
            spawnSelfRing(world, particle, caster);
            return;
        }
        if (cells.isEmpty()) {
            return;
        }
        // ★★★ 2026-10-18（用户第 1 条，逐字）：「**环特效依然不明显，建议除了平面一圈环外加几圈
        //   在 y 轴向上偏移一些的**」⇒ 同一圈边界格在**几个高度**上各画一遍，
        //   看起来是"一整根立起来的圆柱"而不是地面上的一圈线。
        //   ★ 层数与层间距在纯类 {@link FxRing#levelOffsets()} 里（有单测），
        //     这里只管把 y 加上去 —— 层的几何绝不在这里另算一遍。
        java.util.List<ShapeMath.Cell> ring = FxRing.boundary(cells);
        double baseY = origin.getBlockY() + 1.0 + (beat % 3) * 0.25;
        for (double levelOffset : FxRing.levelOffsets()) {
            double y = baseY + levelOffset;
            for (ShapeMath.Cell cell : ring) {
                // ★★★ 2026-10-18（用户第 4 条：「嗜血依旧没有特效（龙息粒子环）」）：
                //   现场日志证明这条 FX **确实在播**（8 帧 / 81 格 / DRAGON_BREATH），
                //   但 DRAGON_BREATH 是极小的深紫火星 ⇒ 每点 1 颗 × 3 层的密度肉眼几乎看不见。
                //   ⇒ 每点密度抬到 max(PARTICLES_PER_POINT, 8)（其它法术一起变粗，正是
                //     用户早先要的「加粗环」）；不改粒子名（仍按 docs/19 的「龙息」）。
                int perPoint = Math.max(FxTimeline.PARTICLES_PER_POINT, 8);
                // ★★★ 2026-10-18 根因（现场日志，用户第 4 条「嗜血依旧看不到粒子效果」）：
                //   `Task #28 … generated an exception:
                //    IllegalArgumentException: data (class java.lang.Boolean) should be class
                //    org.bukkit.Color   at SpellCaster.emitFx`
                //   ⇒ 我上一版把 `force` 当成了 8 参重载的最后一个参数，但它其实是 **`data`**！
                //     于是每帧都抛（整帧粒子一颗都没撒）—— 这才是"看不到"的真因。
                //   ★ 而且 26.2 里 `DRAGON_BREATH` 的 data 类型是 **`Color`**（同一个异常报出来的），
                //     所以必须走带 data 的重载。
                //   ⇒ 现在：DRAGON_BREATH → 带 Color 的 10 参重载；其它粒子 → 8 参重载。
                //     ★ 每一步各自 try：一个粒子坏掉绝不许把整帧拖死（这条已经吃过一次亏）。
                try {
                    if (particle == Particle.DRAGON_BREATH) {
                        world.spawnParticle(particle, cell.x() + 0.5, y, cell.z() + 0.5,
                                perPoint, 0.3, 0.3, 0.3, 0.0,
                                org.bukkit.Color.fromRGB(160, 32, 240));
                    } else {
                        world.spawnParticle(particle, cell.x() + 0.5, y, cell.z() + 0.5,
                                perPoint, 0.3, 0.3, 0.3, 0.0);
                    }
                } catch (Throwable t) {
                    if (!fxParticleWarned) {
                        fxParticleWarned = true;
                        logger.warning("[IC] 环的粒子撒不出去（" + particle + "）：" + t
                                + "，本场不再尝试（只影响观感，绝不影响伤害）");
                    }
                }
                // ★ 可见性兜底：龙息在低密度下仍是极小的深紫火星 ⇒ 同一条环上叠一层
                //   `SOUL_FIRE_FLAME`（亮蓝白、同色系）。粒子名按 docs/19 仍是龙息为主。
                if (particle == Particle.DRAGON_BREATH) {
                    try {
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                                cell.x() + 0.5, y, cell.z() + 0.5,
                                Math.max(2, perPoint / 3), 0.25, 0.25, 0.25, 0.0);
                    } catch (Throwable ignored) {
                        // 叠层失败无所谓：龙息那一层已经在上面撒过了
                    }
                }
            }
        }
    }

    /** 解析 {@code fx.particle}（解析不出来只告警一次 ⇒ {@code null}）。 */
    private Particle resolveParticle(Spell.Fx fx) {
        try {
            return Particle.valueOf(fx.particle());
        } catch (IllegalArgumentException e) {
            if (!fxParticleWarned) {
                fxParticleWarned = true;
                logger.warning("[IC] 法术特效的粒子名 " + fx.particle()
                        + " 解析不出来，本场不再尝试（只影响观感，绝不影响伤害）");
            }
            return null;
        }
    }

    /**
     * ★★★ <b>播法术特效（按 {@link FxTimeline} 的时间轴）</b> —— 2026-10-18 重构。
     *
     * <h2>改了什么（用户逐字）</h2>
     * 「**法术的环状特效不够明显，请你加粗并延长持续时间**」——
     * 旧实现是"施放那一 tick 撒一次边界粒子"，一帧就没了，实机上几乎看不见。
     * ⇒ 现在：<b>第一帧立刻播（含音效）</b>，之后每 {@link FxTimeline#PERIOD_TICKS} tick 补一帧，
     * 一共 {@link FxTimeline#beats} 帧（一次性法术也至少 8 帧 = 2 秒；
     * 信息素按它自己的 {@code duration-ticks} 一直亮到法术结束；掘墓人亮满 3 秒等待期）。
     *
     * <h2>★ 任务边界（为什么这里可以不起 {@code LifecycleTasks}）</h2>
     * 这是<b>有界且自取消</b>的观感任务：帧数固定（≤ {@link FxTimeline#MAX_BEATS}），
     * 跑满就 {@code cancel()}。它<b>不召唤、不改状态</b>，所以即使对局在它跑完之前结束，
     * 最坏也只是在竞技场里多亮几帧粒子 —— 不存在"召唤物在下一局凭空冒出来"那种泄漏
     * （那是 {@code EffectApplier} 里周期召唤必须受 {@code LifecycleTasks} 约束的理由）。
     * ★ 调度器拿不到 / 插件已停用 ⇒ <b>退化成只播一帧</b>并告警，绝不静默没有特效。
     */
    private void playFx(Caster caster, Spell spell, List<ShapeMath.Cell> cells, Location origin,
                        String matchId, String cardId) {
        Spell.Fx fx = spell.fx();
        if (fx == null) {
            return;
        }
        final int total = FxTimeline.beats(spell);
        // ★★ 2026-10-18：**每张卡每场只打一行**特效自检（用户报「右键空气放法术没有特效」，
        //   而"有没有特效"只有真人能看见 ⇒ 这一行是让**日志能回答它**）。
        //   ★ 只打一行，不刷屏；它直接给出「几帧 / 几格 / 落点是不是自己 / 走哪条粒子路」。
        if (fxReported.add(cardId)) {
            logger.info("[IC] 法术特效：" + cardId + " → " + total + " 帧（每 "
                    + FxTimeline.PERIOD_TICKS + " tick 一帧）｜格数 " + cells.size()
                    + "｜落点在自己脚下=" + isOnCaster(origin, caster)
                    // 2026-10-18：把"这一帧走哪条粒子路"也打出来 —— 用户第 3 条
                    //   （「狂暴没有环形特效」）在日志里就是靠这一格一眼看出来的
                    + "｜走自身光环=" + usesSelfRing(cells.size(), isOnCaster(origin, caster))
                    + "｜方块粒子=" + (fx.hasBlockParticle() ? fx.block() : "—")
                    + "｜粒子=" + (fx.particle() == null ? "—" : fx.particle())
                    + "｜闪电=" + fx.hasLightning()
                    + "｜音效=" + (fx.sound() == null ? "—" : fx.sound()));
        }
        final int[] done = {0};
        final BukkitTask[] handle = new BukkitTask[1];
        Runnable step = () -> {
            if (done[0] >= total) {
                if (handle[0] != null) {
                    handle[0].cancel();
                }
                return;
            }
            int beat = done[0];
            done[0]++;
            if (beat == 0) {
                playFxSound(fx, origin, matchId);
            }
            emitFx(fx, cells, origin, caster, matchId, beat);
        };
        if (total <= 1 || plugin == null || !plugin.isEnabled()) {
            step.run();
            return;
        }
        try {
            handle[0] = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, step, 0L, FxTimeline.PERIOD_TICKS);
        } catch (Throwable t) {
            logger.warning("[IC] 法术特效任务没能排上（只播一帧；只影响观感）：" + t
                    + "｜matchId=" + matchId);
            step.run();
        }
        // ★★★ 2026-10-21（用户裁定）：灵狐引路「召唤前摇 2 秒，期间在召唤点向上打一条龙息光柱」
        //   ⇒ `fx.pillar` + `fx.pillar-ticks`：从落点**向上**每 2 tick 撒一列粒子，持续 N tick。
        //   ★ 纯观感：任务排不上只告警（不影响召唤本身）。
        if (fx.pillar() != null && fx.pillarTicks() > 0) {
            pillarFantasy(fx, origin, matchId);
        }
    }

    /** 落点向上的**光柱**（2026-10-21：灵狐引路的召唤前摇）。 */
    private void pillarFantasy(Spell.Fx fx, Location origin, String matchId) {
        if (origin.getWorld() == null) {
            return;
        }
        org.bukkit.Particle particle;
        try {
            particle = org.bukkit.Particle.valueOf(fx.pillar());
        } catch (Throwable t) {
            logger.warning("[IC] 光柱粒子「" + fx.pillar() + "」解析失败 ⇒ 本张卡不画光柱"
                    + "（只影响观感）：" + t + "｜matchId=" + matchId);
            return;
        }
        final int total = fx.pillarTicks();
        final int[] tick = {0};
        Runnable step = () -> {
            if (tick[0] >= total) {
                return;
            }
            tick[0] += PILLAR_PERIOD_TICKS;
            try {
                for (double y = 0.4; y <= PILLAR_HEIGHT; y += 0.6) {
                    origin.getWorld().spawnParticle(particle,
                            origin.clone().add(0.0, y, 0.0), 1, 0.06, 0.06, 0.06, 0.0);
                }
            } catch (Throwable ignored) {
                // 粒子撒不出去不影响召唤
            }
        };
        step.run();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            org.bukkit.scheduler.BukkitTask task = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, step, PILLAR_PERIOD_TICKS, PILLAR_PERIOD_TICKS);
            // 到期自停（避免任务泄漏；任务表本身也随插件停用清空）
            plugin.getServer().getScheduler().runTaskLater(plugin, task::cancel, total + 2L);
        } catch (Throwable t) {
            logger.warning("[IC] 光柱任务没能排上（只画一帧；只影响观感）：" + t
                    + "｜matchId=" + matchId);
        }
    }

    /** 每道闪电之间的间隔（tick）—— 2026-10-21 用户裁定「**放完法术后的 10/20/30 tick 各劈一次**」。 */
    private static final int LIGHTNING_GAP_TICKS = 10;

    /** 光柱的粒子间隔（tick）。 */
    private static final int PILLAR_PERIOD_TICKS = 2;

    /** 光柱的高度（格）：够到"指向天空"的观感即可，不落地图外。 */
    private static final double PILLAR_HEIGHT = 12.0;

    /**
     * ★★ <b>「纯视觉闪电」</b>（docs/19 第 135 行对暴风雨的硬要求：
     * 「⚠️ 必须用 {@code strikeLightningEffect}（纯视觉）」）。
     *
     * <h2>为什么不能用 {@code spell.fx} 表达</h2>
     * {@code fx} 的其它字段只能给「粒子 + 音效」—— 而闪电是**第三种**观感（原版实体级特效），
     * 且它必须落在**每个被雷击的目标**头上（"依次落雷"），而不是形状的边界格上。
     * ⇒ 它在 {@code fx} 里占一个**布尔位**（{@code fx.lightning}），路径仍然独立。
     *
     * <h2>★★★ 判据（2026-10-18 改正，用户裁定逐字）</h2>
     * 「**暴风雨的落雷+海王的落泪（雷）；其他全去掉**」。
     *
     * <p>旧判据是「这张法术有 {@code DAMAGE} 效果 ⇒ 劈闪电」（当时的理由是"将来加落雷类法术自动就有"）。
     * 实机结果是 <b>14 张伤害法术全都在劈闪电</b> —— 用户的反馈正是
     * 「**法术牌依旧保留着雷击特效，不需要这个**」。
     * ⇒ 现在<b>只认卡片自己写的 {@code fx.lightning: true}</b>：
     * 全卡池只有 `storm` 一张写；其余一律不劈（默认 {@code false} ⇒ 行为回到"没有闪电"）。
     * ★ 「海王的落泪」的雷走的是**能力**级 {@code abilities[].fx.lightning}（{@code card.Ability.Fx}），
     * 那是另一条链路，不经过本方法。
     *
     * <p>★ 上限 {@value #MAX_STRIKE_FX} 个（防止将来某张全屏法术把主线程劈爆）。
     * ★ 用 {@code strikeLightningEffect}（**不是** {@code strikeLightning}）：
     *   后者会**引燃方块、造成伤害** —— 而伤害我们已经在效果段结算过了，再劈一次就是双倍。
     * ★ 失败方向：任何异常只告警一次，**绝不影响**已经结算完的伤害。
     */
    private void strikeLightningFx(Spell spell, List<LivingEntity> targets, Location origin) {
        if (strikeFxWarned) {
            return;
        }
        Spell.Fx fx = spell.fx();
        if (fx == null || origin.getWorld() == null) {
            return;
        }
        // ★★★ 2026-10-18 补（用户：「**法术和海王在右键空气释放时不会触发特效**」）：
        //   判据抽成纯类 {@link LightningPlan}：**一个都没打中 ⇒ 在落点劈一道**。
        //   根因：闪电原先**只**劈在命中目标头上 ⇒ 对着空气放暴风雨（附近没有敌人）时
        //   `targets` 为空 ⇒ 屏幕上**一个像素都不会动**（连粒子都没有 —— 暴风雨的 fx 只有
        //   `lightning: true`）。玩家看到的正是"放了但没反应"。
        LightningPlan.Kind plan = LightningPlan.of(fx.hasLightning(),
                targets == null ? 0 : targets.size());
        if (plan == LightningPlan.Kind.NONE) {
            return;
        }
        if (plan == LightningPlan.Kind.AT_ORIGIN) {
            // ★ 2026-10-21（用户：「释放法术后 10 / 20 / 30 tick 各劈一次」）：
            //   一个目标都没命中时的这一道也按**第一道 = +10 tick** 的节拍走。
            final Location at = origin.clone().add(0.0, 1.0, 0.0);
            Runnable strike = () -> {
                try {
                    if (at.getWorld() != null) {
                        at.getWorld().strikeLightningEffect(at);
                    }
                } catch (Throwable t) {
                    strikeFxWarned = true;
                    logger.warning("[IC] 纯视觉闪电没能播放（只影响观感）：" + t);
                }
            };
            if (plugin == null || !plugin.isEnabled()) {
                strike.run();
            } else {
                try {
                    plugin.getServer().getScheduler().runTaskLater(plugin, strike,
                            LIGHTNING_GAP_TICKS);
                } catch (Throwable t) {
                    strike.run();
                }
            }
            return;
        }
        // ★★★ 2026-10-21（用户裁定）：「暴风雨的每道闪电之间加 10 tick 的间隔」
        //   ⇒ 第 i 道在 `i × 10` tick 之后劈（第 0 道立刻）。
        //   ★ 观感用；排不上任务 ⇒ **退化成同一 tick 连劈**（不静默没有闪电）。
        int struck = 0;
        try {
            for (LivingEntity target : targets) {
                if (struck >= MAX_STRIKE_FX) {
                    break;
                }
                if (target.getWorld() == null || target.isDead()) {
                    continue;
                }
                final Location where = target.getLocation().clone();
                // ★ 用户 2026-10-21 的原话：「**释放法术后 10 / 20 / 30 tick 各劈一次**」
                //   ⇒ 第 1 道 +10、第二 +20、第三 +30（不是从 0 开始）。
                final long delay = (long) (struck + 1) * LIGHTNING_GAP_TICKS;
                struck++;
                if (delay <= 0 || plugin == null || !plugin.isEnabled()) {
                    where.getWorld().strikeLightningEffect(where);
                    continue;
                }
                try {
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> {
                                if (where.getWorld() != null) {
                                    where.getWorld().strikeLightningEffect(where);
                                }
                            }, delay);
                } catch (Throwable ignored) {
                    where.getWorld().strikeLightningEffect(where);
                }
            }
        } catch (Throwable t) {
            strikeFxWarned = true;
            logger.warning("[IC] 纯视觉闪电没能播放（只影响观感，绝不影响已经结算完的伤害）：" + t);
        }
    }

    /** 一次施放最多劈几道视觉闪电（防"将来某张全屏法术"把主线程劈爆）。 */
    private static final int MAX_STRIKE_FX = 8;

    /**
     * ★★ <b>方块粒子（{@code fx.block}）的密度与散布</b> —— 2026-10-19 用户裁定
     * 「掘墓人**粒子量翻倍**，**显示范围也要扩大一点**」。
     *
     * <p>取值：原来每格 2 颗、散布 0.35 ⇒ 现在每格 <b>4</b> 颗、散布 <b>0.6</b>（≈ 翻倍）。
     * ★ 这两个数是**观感参数**（铁律 8：由用户定，可随时改），不是玩法数值；
     * 只有 {@code grave_digger} 用 {@code fx.block} ⇒ 改这里不影响别的法术。
     */
    private static final int BLOCK_FX_PARTICLES_PER_CELL = 4;

    /** 见 {@link #BLOCK_FX_PARTICLES_PER_CELL}（单位：格）。 */
    private static final double BLOCK_FX_SPREAD = 0.6;

    /** 落点是不是**施放者自己**那一格（= 自我增益类法术的判据，见 {@link #playFx}）。 */
    private static boolean isOnCaster(Location origin, Caster caster) {
        Location at = caster == null ? null : caster.location();
        return at != null
                && origin.getWorld() != null
                && origin.getWorld().equals(at.getWorld())
                && origin.getBlockX() == at.getBlockX()
                && origin.getBlockY() == at.getBlockY()
                && origin.getBlockZ() == at.getBlockZ();
    }

    /**
     * ★★★ <b>这一帧该不该画「施放者自身光环」</b>（纯函数，2026-10-18 新增）。
     *
     * <h2>为什么这条判据必须存在（用户第 3 条，逐字）</h2>
     * 「**「狂暴」法术没有环形特效**」——查证结果不是"没配粒子"（它配了
     * {@code fx.particle: DRAGON_BREATH}），而是**画错了地方**：
     * 旧判据只问"落点是不是施放者自己"，而 {@code 嗜血} 是 {@code CIRCLE radius 5}
     * ⇒ 对着自己脚下放时落点就在自己身上 ⇒ 走了"自身光环"分支
     * ⇒ 画成半径 1.0/1.2/1.4 的三圈小环，**它声明的半径 5 一圈都没画**。
     *
     * <h2>口径（两条）</h2>
     * <ol>
     *   <li><b>单格形状</b>（{@code cells.size() <= 1}，即 {@code SINGLE}）
     *       且落点在自己身上 ⇒ 走自身光环。这类法术（速度药水 / 战术冷却）的形状就是
     *       "自己这一格"，用边界画环等于把粒子埋在身体里 ⇒ 只能是自我光环；</li>
     *   <li>★ <b>多格形状永远画自己的边界环</b> —— 哪怕施放者站在圆心。
     *       范围是**玩法信息**（半径 5 就是半径 5），把它画成一圈小光环是**错的观感**。</li>
     * </ol>
     *
     * @param cellCount 本帧形状覆盖的格数（{@code cells.size()}）
     * @param onCaster  落点是不是施放者自己那一格
     */
    static boolean usesSelfRing(int cellCount, boolean onCaster) {
        return onCaster && cellCount <= 1;
    }

    /**
     * ★★ <b>「在我身上出现了粒子」</b>（用户对自我增益类法术的明确要求）。
     *
     * <h2>2026-10-18 为什么把它放大成**两圈**</h2>
     * 用户随后报：「**法术和海王在右键空气释放时不会触发特效**」。
     * 查证结果：**右键空气 ⇒ 落点 = 施放者自己的格子**（`PlacementResolver` 的 `AT_SELF`，
     * 这是第十三轮用户亲自定的口径「会以玩家位置释放法术 / 进行召唤」，不改）。
     * 于是这类施放走的就是本方法 —— 而旧实现只有**一圈半径 0.6**（12 点）：
     * 第一人称下那圈粒子基本埋在自己腰上，看起来就是"没有特效"。
     * ⇒ 改成**两圈**（腰 + 头，半径 1.0 / 1.4，各 20 点）：
     * 明显能看见、又不至于糊住视野。★ 仍然只用施放者自己的坐标（铁律 10：没有附近查询）。
     */
    private static void spawnSelfRing(World world, Particle particle, Caster caster) {
        // ★★ 2026-10-21：真人施放者 ⇒ **每帧重读当前位置**（环跟着人走，与重构前一致）；
        //   人机（没有 Player）⇒ 用本次施放记录的位置（人机没有"本人"可以跟着走，
        //   而且这条分支本来是给「自我增益类法术」用的，那类卡对人机不可用）。
        Player player = caster == null ? null : caster.playerOrNull();
        Location base = player != null ? player.getLocation()
                : (caster == null ? null : caster.location());
        if (base == null) {
            return;
        }
        // ★★★ 2026-10-18（用户第 1 条）：从"腰 + 头两圈"改成**三层**、半径随高度略放大
        //   ⇒ 观感是一个立起来的光环（而不是地面上的一圈线）。
        //   ★ 点数走 {@link FxRing#pointsFor}（小半径也至少 16 点，肉眼是连续的）。
        ring(world, particle, base, 1.0, FxRing.pointsFor(1.0), 1.0);
        ring(world, particle, base, 1.2, FxRing.pointsFor(1.2), 1.6);
        ring(world, particle, base, 1.4, FxRing.pointsFor(1.4), 2.2);
    }

    /** 一圈粒子（{@code yOffset} 是相对玩家脚下高度）；几何来自纯类 {@link FxRing#circle}。 */
    private static void ring(World world, Particle particle, Location center,
                             double radius, int points, double yOffset) {
        for (double[] offset : FxRing.circle(radius, points)) {
            world.spawnParticle(particle,
                    center.getX() + offset[0],
                    center.getY() + yOffset,
                    center.getZ() + offset[1],
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** 音效（与粒子分开：粒子段可以没有，音效段也可以单独存在）。 */
    private void playFxSound(Spell.Fx fx, Location origin, String matchId) {
        if (fx == null || fx.sound() == null || fx.sound().isBlank() || origin.getWorld() == null) {
            return;
        }
        try {
            origin.getWorld().playSound(origin, fx.sound(), 1.0f, 1.0f);
        } catch (Throwable t) {
            if (!fxParticleWarned) {
                fxParticleWarned = true;
                logger.warning("[IC] 法术特效的音效 " + fx.sound() + " 播放失败：" + t
                        + "（只影响观感，绝不影响伤害）｜matchId=" + matchId);
            }
        }
    }

    /**
     * ★★★ <b>能力侧的嵌套施放入口</b>（`abilities[].effects[].type: CAST_SPELL`）——
     * 2026-10-19 新增（怨怼轰炸机亡语：「死亡时在死亡处放一次『定点爆破』」）。
     *
     * <p>为什么需要这个 public 门：{@link #applyNested} 是 private，而调用它的
     * {@code nestCaster} 回调现在由 {@code ability/AbilityRuntime} 构造
     * （能力触发位 —— 那里只有实体，没有玩家）。
     *
     * @param effect 那条 {@code CAST_SPELL} 效果（`spell` 键指向要放的那张法术卡）
     * @param caster 施放者 = **召唤者**（能力侧唯一有施放链身份的人）；{@code null} ⇒ 拒绝 + 告警
     * @param origin 落点（ON_DEATH 时 = 死亡位置）
     * @param depth  当前嵌套深度（能力侧传 0；被嵌套的那次以 depth+1 走完整条链，【Q26】限 1）
     * @return 是否真的放出去了
     */
    public boolean castNested(Spell.Effect effect, Player caster, String matchId, Side side,
                              Location origin, int depth) {
        if (caster == null) {
            logger.warning("[IC] 能力里的嵌套施放被拒绝：拿不到施放者（召唤者不在线 / 未知）"
                    + "｜matchId=" + matchId);
            return false;
        }
        try {
            return applyNested(Caster.of(caster), matchId, side, effect, origin, depth);
        } catch (Throwable t) {
            logger.warning("[IC] 能力里的嵌套施放失败（只影响这一次亡语，不影响对局）：" + t);
            return false;
        }
    }

    /**
     * 嵌套施放（`CAST_SPELL`）：★ **深度限 1（Q26）**。
     *
     * <p>由 {@link EffectApplier} 在遇到 `CAST_SPELL` 时通过注入的 `nestCaster` 回调进来。
     * 被嵌套的法术从**卡池**里按 id 取（`spell` 参数指向另一张卡的法术块）。
     */
    private boolean applyNested(Caster caster, String matchId, Side side, Spell.Effect effect,
                                Location origin, int depth) {
        String nestedId = EffectParams.optionalText(effect, "spell", null);
        if (nestedId == null) {
            logger.warning("[IC] CAST_SPELL 没有给 spell（要嵌套哪张卡），跳过");
            return false;
        }
        Card nested = catalog.cards().get(nestedId);
        if (nested == null || nested.spell() == null) {
            logger.warning("[IC] CAST_SPELL 引用的 " + nestedId + " 不是法术卡，跳过");
            return false;
        }
        Report report = cast(caster, matchId, side, nestedId, nested.spell(), origin, depth + 1);
        return report.cast();
    }

    /** 落点：`SELF` ⇒ 施放者处；`IMPACT` ⇒ 瞄准格；`TARGET` ⇒ 单体目标处（没有则退回施放者处）。 */
    private Location resolveOrigin(Caster caster, Spell spell, Location impact) {
        Spell.Origin origin = spell.shape().origin();
        if (origin == null) {
            return impact != null ? impact : caster.location();
        }
        return switch (origin) {
            case SELF -> caster.location();
            case IMPACT -> impact != null ? impact : caster.location();
            case TARGET -> {
                Entity aimed = caster.aimedEntity();
                yield aimed != null ? aimed.getLocation()
                        : (impact != null ? impact : caster.location());
            }
        };
    }

    /**
     * ★★★ <b>准星上瞄到了什么</b>（2026-10-18 新增）—— 治愈术「必须用准星指着友方」的采集端。
     *
     * <p>判定本身在纯类 {@link AimRule}（可离线逐格断言）；本方法只负责
     * <b>把世界里的东西翻译成 {@link AimRule.Aimed} 那一档</b>。
     * 而"准星"用 {@link Player#getTargetEntity(int)}（**有界**的 32 格射线，铁律 10：
     * 它不是"查询附近实体"）。
     *
     * <p>★ 失败方向：任何异常 ⇒ {@link AimRule.Aimed#NOTHING}
     * （= 让调用方按「没有选中目标」处理 —— 用户要的正是这种情形「不使用」）。
     */
    public AimRule.Aimed classifyAim(Player caster, String matchId, Side side) {
        if (caster == null) {
            return AimRule.Aimed.NOTHING;
        }
        Entity aimed;
        try {
            aimed = caster.getTargetEntity(TARGET_RANGE);
        } catch (Throwable t) {
            return AimRule.Aimed.NOTHING;
        }
        if (aimed == null) {
            return AimRule.Aimed.NOTHING;
        }
        if (!(aimed instanceof LivingEntity living)) {
            return AimRule.Aimed.NOT_LIVING;
        }
        if (!living.isValid() || living.isDead()) {
            return AimRule.Aimed.DEAD;
        }
        if (!isAllyOf(living, matchId, side)) {
            return AimRule.Aimed.NOT_ALLY;
        }
        // ★★★ 2026-10-19 用户裁定：「**治愈术…瞄准/目标过滤排除防御塔**」
        //   （同一批还有「铜傀儡不治疗目标物」）⇒ 建筑不是合法治疗目标。
        //   ★ 判据读**账本上的建筑标记**（`UnitRegistry#building`），不按实体类型判：
        //     类型判据会把"将来某张建筑召唤卡"漏掉，也会误伤真的能治的村民类单位。
        return registry.building(living) ? AimRule.Aimed.BUILDING : AimRule.Aimed.ALLY;
    }

    /**
     * 收集受影响的目标。
     *
     * <p>★ 来源只有两处：**本场账本**（召唤物 / 塔 / 信标）与施放者自己。
     * 需要准星的 `ENEMY_TARGET` / `ANY_TARGET` / `ALLY_TARGET` 走
     * {@code getTargetEntity(32)}（**有界** Raycast）。
     */
    private List<LivingEntity> collectTargets(Caster caster, String matchId, Side side, Spell spell,
                                              Set<Long> cellKeys, Location origin) {
        List<LivingEntity> targets = new ArrayList<>();
        Spell.Targeting targeting = spell.targeting();

        if (targeting == Spell.Targeting.SELF) {
            // ★ 只有真人施放者能当「自己」；人机没有 Player ⇒ 这条法术对人机无目标
            //   （速度药水就属于这一档 —— 正是它被列为「人机不可用卡」的原因，见 docs/31 §4）
            LivingEntity self = caster.playerOrNull();
            if (self != null) {
                targets.add(self);
            }
            return targets;
        }
        if (targeting == Spell.Targeting.NONE) {
            return targets;   // 这类法术不需要实体目标（召唤等）
        }
        if (targeting == Spell.Targeting.ALLY_TARGET) {
            // ★★★ 2026-10-18：**只打准星上那一个友方**（不走账本、不看形状覆盖的格）。
            //   理由见 Spell.Targeting#ALLY_TARGET 与 spell/AimRule 的 javadoc：
            //   用户裁定「必须用准星指着友方，如果没有选到目标就不使用」。
            //   ★ 这里仍然复核一次归属（不要只信 CardCastListener 的前置检查：
            //     两者之间玩家可以转身，而且嵌套施放（CAST_SPELL）不会走那个前置检查）。
            //   ★★ 2026-10-21：人机没有准星 ⇒ 由 {@link Caster#aimedEntity()} 给出**规则选出的**
            //      那个友方召唤物（training.spell-rules 的 RANDOM_ALLY_TARGET），
            //      复核逻辑一字未改（归属 + 建筑不可治疗）。
            Entity aimed = caster.aimedEntity();
            if (aimed instanceof LivingEntity living && living.isValid() && !living.isDead()
                    && isAllyOf(living, matchId, side)
                    // ★★★ 2026-10-19：**建筑不能当治疗目标**（治愈术「瞄准/目标过滤排除防御塔」）。
                    //   ★ 这里仍然复核一次：嵌套施放（CAST_SPELL）不走 CardCastListener 的前置检查。
                    && !registry.building(living)) {
                targets.add(living);
            }
            return targets;
        }
        if (targeting == Spell.Targeting.ENEMY_TARGET || targeting == Spell.Targeting.ANY_TARGET) {
            Entity aimed = caster.aimedEntity();
            if (aimed instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                if (targeting == Spell.Targeting.ANY_TARGET || !isAllyOf(living, matchId, side)) {
                    targets.add(living);
                }
            }
            return targets;
        }

        // GROUND / ALLY ⇒ 在形状覆盖的格里，从**账本**里筛
        for (UUID id : registry.tracked(matchId)) {
            OwnershipTable.Owner owner = registry.table().owner(id);
            if (owner == null) {
                continue;
            }
            if (targeting == Spell.Targeting.ALLY && owner.side() != side) {
                continue;
            }
            if (targeting == Spell.Targeting.GROUND && owner.side() == side) {
                continue;   // GROUND 在 R29/R33 里是「对地施放」⇒ 只打敌方
            }
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                continue;
            }
            Location location = living.getLocation();
            if (location.getWorld() == null || !location.getWorld().equals(origin.getWorld())) {
                continue;
            }
            if (cellKeys.contains(key(location.getBlockX(), location.getBlockZ()))) {
                targets.add(living);
            }
        }
        return targets;
    }

    /** 该实体是否与施放者同阵营（不在本场 ⇒ 视为不同阵营）。 */
    private boolean isAllyOf(LivingEntity entity, String matchId, Side side) {
        OwnershipTable.Owner owner = registry.table().owner(entity.getUniqueId());
        return owner != null && owner.matchId().equals(matchId) && owner.side() == side;
    }

    /** 把 (x,z) 打包成一个 long，避免用 List.contains 做 O(n²) 查找。 */
    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
