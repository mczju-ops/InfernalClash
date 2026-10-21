package io.mczju.infernalclash.policy;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ 选敌判据 —— **纯函数，可离线单测**（{@code docs/07} §5.2、{@code docs/02} §8）。
 *
 * <h2>为什么要自己写（T4）</h2>
 * InfernalGuess 的 {@code EnemyTargetGoal} **只在目标失效时**才重选
 * （{@code EnemyTargetGoal.java:74-80}）⇒ 「生物 A 追着 B 打，更近的 C 出现也不换」。
 * 用户 R36 要求「**始终**选择最近的敌人」⇒ 本项目**每个刷新周期重算**。
 *
 * <h2>排序键（★ 只有两维，**D1 已裁定**）</h2>
 * <pre>
 *   affectedByBuilding = true  ：[是建筑] → [距离最近]
 *   affectedByBuilding = false ：[距离最近]（**完全跳过建筑维度**）
 * </pre>
 *
 * <ul>
 *   <li>★★ **没有「视线」维度**（**D1 裁定，2026-10-15**）：{@link Candidate} **连字段都不存在**，
 *       {@code TargetPolicyTest} 有一条断言**钉住记录的分量个数**。见 {@code docs/05} §D。</li>
 *   <li>★ **建筑是布尔**（2026-10-15 用户澄清：「嘲讽没有等级，只有嘲讽和无嘲讽的区别」；
 *       2026-10-19 把这一维从「嘲讽」改名为「**建筑**」—— 见 {@link Candidate#building()}）
 *       ⇒ 不存在"先比建筑等级"这一维；信标与防御塔都是 {@code building = true}，
 *       彼此**不分高下**。</li>
 *   <li>★ **【Q6 已裁定】「无视嘲讽」= 嘲讽不存在，直接比距离**（用户原话）
 *       ⇒ 这类单位会**越过信标去打路过的召唤物**，这是**有意的**。</li>
 *   <li>★ **玩家永不成为候选**（R42 / T3）与**友方永不成为候选**（R27）：
 *       由**调用方**在收集候选时剔除 —— 本类是纯函数，拿不到实体阵营。
 *       ★★ 但「候选里没有玩家」**不等于**「单位不会去打玩家」：原版 AI 的目标 goal
 *       一直在跑（潜影贝的 {@code shulker_defense_attack} 就是"谁碰我打谁"）
 *       ⇒ 还必须有一层**执行层兜底** {@link #applyAction}（每 tick 对齐一次），
 *       见 {@code battle/SummonCombat#reassert} 与 {@code docs/27} §D1c。</li>
 * </ul>
 *
 * <h2>★ 防抖动（粘性 ×1.3）</h2>
 * 两个敌人等距时若每 0.5 秒重选一次会来回横跳（表现为原地抽搐）。
 * ⇒ 同一优先级档位内，新目标必须**明显更近**才换：实际距离要近到 30% 以上
 * （在平方距离上即 {@code × 1.3²}）。**跨档位**（无嘲讽 → 有嘲讽）则**无条件**切换。
 *
 * <h2>★ 与 MOVE 层共用（{@code docs/07} §5.3）</h2>
 * {@code MountDriveGoal} 也必须调用本类，否则会重新制造「**走到 C 旁边却不打 C**」。
 * 由 {@code TargetLayerConsistencyTest} 钉住（P5）。
 */
public final class TargetPolicy {

    /**
     * 出厂值：目标刷新间隔 = **10 tick（0.5 秒）**（R34 + 2026-10-15 用户裁定），
     * 配置键 {@code combat.target-refresh-ticks}。
     *
     * <p>⚠️ 这个间隔**是 T4 修正方案的一部分**：没有它，「出现更近的敌人」永远不会被发现。
     */
    public static final int DEFAULT_RESCAN_TICKS = 10;

    /**
     * 出厂值：粘性系数 —— 新目标必须比当前目标**近 30% 以上**才换（防抖动）。
     * ★ 语义作用在**实际距离**上（实现时乘在平方距离上，见 {@link #shouldSwitch}）。
     */
    public static final double DEFAULT_SWITCH_MARGIN = 1.3;

    /** 选敌模式（{@code docs/07} §5.2「另外两个必须支持的『非默认』模式」）。 */
    public enum Mode {
        /** 默认：每个刷新周期都允许换目标（R36）。 */
        SWITCH,
        /**
         * ★ **锁定后不再改变目标**，直到目标死亡或受眩晕 —— 用于**烈焰守卫**（稀有卡）。
         *
         * <p>这正是 T4 里用户抱怨 IG 的行为，**但在这里是故意的设计**
         * ⇒ 与默认行为**严格区分**并**分别钉住**，否则将来有人会"顺手"把它改成默认行为。
         */
        LOCK_ON
    }

    /**
     * 候选目标（**调用方必须已剔除玩家与友方**）。
     *
     * @param id              实体 UUID（用于与「当前目标」比对）
     * @param distanceSquared 与自己的**平方距离**（避免开方；比较结果等价）
     * @param building        ★ 这个候选**算不算建筑**（{@code true} ⇒ 优先档，★ **布尔，无等级**）。
     *                        ★ 2026-10-19 之前这一维叫 {@code taunted}（「有嘲讽」）；
     *                        用户把玩法词汇改成「建筑」之后，判据本身**一字未变**，
     *                        只是取值来源从「嘲讽标记」换成「建筑标记」
     *                        （{@code UnitRegistry#building}）。
     */
    public record Candidate(UUID id, double distanceSquared, boolean building) {
        public Candidate {
            Objects.requireNonNull(id, "候选目标的 UUID 不能为空");
            if (!(distanceSquared >= 0) || Double.isInfinite(distanceSquared)) {
                // 负数 / NaN / 无穷 = 上游算错了，属于编程错误，不是运行时可达状态
                throw new IllegalArgumentException(
                        "distanceSquared 必须是有限的非负数：实际 " + distanceSquared);
            }
        }

        /** 实际距离（**只用于展示 / 日志**；比较一律用平方距离）。 */
        public double distance() {
            return Math.sqrt(distanceSquared);
        }
    }

    /** ★ 不看建筑 —— **只比距离**（【Q6】读法 A）。 */
    private static final Comparator<Candidate> DISTANCE_ONLY =
            Comparator.comparingDouble(Candidate::distanceSquared)
                    .thenComparing(Candidate::id);

    /** ★ 优先建筑 —— **先比是不是建筑，同档再比距离**（R35 / R36）。 */
    private static final Comparator<Candidate> BUILDING_THEN_DISTANCE =
            Comparator.comparingInt((Candidate c) -> c.building() ? 0 : 1)
                    .thenComparingDouble(Candidate::distanceSquared)
                    .thenComparing(Candidate::id);

    private TargetPolicy() {
    }

    /**
     * 排序键（**越小越优**）。
     *
     * <p>末位用 UUID 兜底，只为**「同种子/同输入 → 同结果」**（可复现），
     * **不代表任何玩法含义** —— 两个完全同分的候选本来就没有"更该打哪个"。
     */
    public static Comparator<Candidate> order(boolean affectedByBuilding) {
        return affectedByBuilding ? BUILDING_THEN_DISTANCE : DISTANCE_ONLY;
    }

    /** 从候选集里选出当前应打的目标；候选集为空 → {@link Optional#empty()}。 */
    public static Optional<Candidate> pick(Collection<Candidate> candidates, boolean affectedByBuilding) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream().min(order(affectedByBuilding));
    }

    /** 是否应当把当前目标换成 {@code better}（用出厂粘性系数）。 */
    public static boolean shouldSwitch(Candidate current, Candidate better, boolean affectedByBuilding) {
        return shouldSwitch(current, better, affectedByBuilding, DEFAULT_SWITCH_MARGIN);
    }

    /**
     * 是否应当把当前目标换成 {@code better}。
     *
     * <p>判据（按序）：
     * <ol>
     *   <li>{@code better == null} → 否；{@code current == null} → 是（没有目标，立刻就选）；</li>
     *   <li>{@code better} 的**优先级档位更高**（例如从"不是建筑"变成"是建筑"）→ **无条件**换；</li>
     *   <li>档位更低 → 否；</li>
     *   <li>同档 → 必须**明显更近**：{@code better.d² × margin² < current.d²}
     *       （即实际距离近到 30% 以上才换）。</li>
     * </ol>
     *
     * @param margin 粘性系数（&gt; 1）；1.0 = 只要更近就换（**不防抖动，仅测试用**）
     */
    public static boolean shouldSwitch(Candidate current, Candidate better, boolean affectedByBuilding,
                                       double margin) {
        if (margin < 1.0) {
            throw new IllegalArgumentException("粘性系数不能小于 1.0：实际 " + margin);
        }
        if (better == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        int currentTier = tier(current, affectedByBuilding);
        int betterTier = tier(better, affectedByBuilding);
        if (betterTier != currentTier) {
            return betterTier < currentTier;
        }
        return better.distanceSquared() * margin * margin < current.distanceSquared();
    }

    /**
     * ★★ **一次刷新周期的完整决策** —— 这就是 **T4 修正的落点**。
     *
     * <p>调用方每 {@link #DEFAULT_RESCAN_TICKS} tick 调一次，把当前目标与「是否仍有效」
     * 传进来即可，**不需要自己判断该不该换** —— 判断逻辑全在这里，才能离线钉住。
     *
     * @param current           当前目标（{@code null} = 没有）
     * @param currentStillValid 当前目标是否仍有效（活着 / 仍在射程 / 仍是敌人）
     * @param candidates        本次扫到的候选（**调用方负责剔除玩家与友方**）
     * @param affectedByBuilding 该单位是否优先打建筑（逐卡配置，默认 {@code false}）
     * @param mode              {@link Mode#SWITCH}（默认）或 {@link Mode#LOCK_ON}
     * @return 刷新后的目标；**没有可打的目标** → {@link Optional#empty()}
     */
    public static Optional<Candidate> refresh(Candidate current, boolean currentStillValid,
                                              Collection<Candidate> candidates,
                                              boolean affectedByBuilding, Mode mode) {
        Objects.requireNonNull(mode, "选敌模式不能为空");
        List<Candidate> list = candidates == null ? List.of() : List.copyOf(candidates);

        // ① 锁定模式：还锁着就绝不换 —— 即使出现了更近的敌人（烈焰守卫的设计意图）
        if (mode == Mode.LOCK_ON && current != null && currentStillValid) {
            return Optional.of(refreshView(current, list));
        }

        Optional<Candidate> best = pick(list, affectedByBuilding);
        if (best.isEmpty()) {
            // ② 没有可打的目标 —— 返回 empty 而不是"保持旧目标"：
            //    保持一个已经死掉/出射程的目标，正是 IG 缺陷的另一半
            return Optional.empty();
        }
        if (current == null || !currentStillValid) {
            // ③ 目标失效 → **同一 tick** 立刻重选（本条 IG 本来就有，保留）
            return best;
        }
        // ④ 目标仍有效 → 只在「明显更优」时才换（粘性）
        return shouldSwitch(current, best.get(), affectedByBuilding)
                ? best
                : Optional.of(refreshView(current, list));
    }

    /**
     * ★★ <b>目标该被写成什么</b>（2026-10-17 第十轮新增，缺陷 P0-a 的第二个落点）。
     *
     * <h2>它修的是什么（用户原话：「<b>哨位会攻击己方召唤物</b>」）</h2>
     * 我们的选敌（本类）**从来不会**选出同侧目标 —— 但那不等于单位不会打自己人：
     * {@code CombatDirector} 只在**刷新周期**（默认 10 tick）里 {@code setTarget} 一次，
     * 而<b>原版 AI 的目标 goal 一直在跑</b>（日志里那行
     * {@code 不补 drive goal：SHULKER 自带攻击类 goal [shulker_attack, shulker_nearest_attack,
     * shulker_defense_attack]} 就是"我们故意留着它们"的证据）⇒ 两次刷新之间原版可以
     * 自己挑一个**附近任意生物**当目标（潜影贝的 {@code shulker_defense_attack} 就是
     * 「谁碰我我打谁」，同伴也算）。
     * <p>旧的心跳兜底只补「{@code getTarget() == null}」那一格 ⇒ 原版挑了一个<b>同侧</b>目标时
     * 我们**反而不会纠正它**（直到下一次刷新，最多 10 tick，而且下一轮刷新还会被粘性
     * 逻辑视作"当前目标"）。
     *
     * <h2>判据（★ 2026-10-17 第十二轮：目标被分成三类，不是两类）</h2>
     * <pre>
     *   实体当前的**玩家**目标        ⇒ CLEAR（R42/T3：玩家永不成为任何实体的目标，
     *                                      **不管是谁的玩家** —— 敌方的、我方的、观战的）
     *   实体当前的目标 == 我们算出来的目标  ⇒ KEEP（不做任何写操作，避免每 tick 干扰原版）
     *   我们算出来的是 null（没有敌人）  ⇒ CLEAR（★ 把原版自己捡的目标清掉 ——
     *                                      玩家的召唤物只许打账本里的敌人，R27/R42）
     *   实体当前的**友方**目标           ⇒ CLEAR（R27：友方永不成为目标）
     *   否则                             ⇒ SET（把我们的目标写回去）
     * </pre>
     * ★★ <b>判据顺序有意为之</b>：玩家那一条<b>排在最前面、连 KEEP 都让路</b> ——
     * 理由见 {@link #applyAction} 里的注释（否则「账本里被塞进玩家」会变成一次静默的 KEEP）。
     * ★ 本方法**只做判定**，写实体的是 {@code battle/SummonCombat#reassert}
     * （纯类不许碰 Bukkit，见 {@code PolicyPurityGuardTest}）。
     *
     * <h2>★★ 为什么「玩家目标」必须是 CLEAR，而不是「友方 : 玩家」分开处理</h2>
     * 用户 2026-10-17 的原话是「<b>玩家不会成为任何实体的目标</b>」（{@code docs/02} §5 R42 /
     * {@code docs/11} §P5）。所以口径是：
     * <ul>
     *   <li><b>友方</b>（同侧召唤物）⇒ 按 {@code ALLY} 处理，日志说「友方」；</li>
     *   <li><b>玩家</b>（<b>任何一方</b>，含敌方玩家与观战者）⇒ 按 {@code PROTECTED_TARGET}
     *       处理，日志说「玩家永不成为目标」。</li>
     * </ul>
     * 两者<b>动作相同（都是 CLEAR）但理由不同</b> ⇒ 分成两个枚举值，是为了让
     * 「我方哨位锁自己人」与「敌方塔锁敌方玩家」这两条反例在**日志与断言**里可分辨
     * （合成一个值就会丢掉"为什么清"这个信息，将来排障时看不出是哪条规则生效了）。
     */
    public enum Apply {
        /** 已经一致，什么都不用做。 */
        KEEP,
        /** 把算出来的目标写进实体。 */
        SET,
        /** ★ 清掉实体当前的目标（我们这边没有可打的目标）。 */
        CLEAR,
        /** ★★ 清掉实体当前的目标，因为它是**友方**（R27）。 */
        CLEAR_ALLY,
        /** ★★ 清掉实体当前的目标，因为它是**玩家**（R42：任何一方、任何单位都不许锁玩家）。 */
        CLEAR_PROTECTED
    }

    /**
     * 目标该被写成什么（三类判据，见 {@link Apply}）。
     *
     * <h2>★ 三个布尔参数为什么必须显式传进来（而不是在这里查实体）</h2>
     * 本类是<b>纯函数</b>（{@code PolicyPurityGuardTest} 钉住「policy 包不引 Bukkit」），
     * 拿不到实体、也算不出阵营 ⇒ 「谁是友方 / 谁是玩家」只能由调用方
     * （{@code battle/SummonCombat#reassert}，它手上有 {@link org.bukkit.entity.LivingEntity}）
     * 判定后传进来。★ 三条判据都**只有这一处实现**，调用方不许自己写 if。
     *
     * @param currentTargetId 实体**现在**的目标 UUID（没有 ⇒ {@code null}）
     * @param computedTargetId 我们算出来的目标 UUID（没有 ⇒ {@code null}）
     * @param currentIsAlly 实体当前那个目标是不是**友方**
     *        （调用方口径：账本读得到 owner 且 {@code owner.side() == unit.side()}；
     *        ★ 读不到 owner 的**不是**友方 —— 那时它是"未知"，按 SET 处理，
     *        绝不因为"读不到"就一律清掉别人的东西）
     * @param currentIsPlayer 实体当前那个目标是不是**玩家**
     *        （调用方口径：{@code instanceof Player}；★ 与阵营无关、
     *        与"在不在本局名册里"也无关 —— R42 的原文是「任何」）
     */
    public static Apply applyAction(UUID currentTargetId, UUID computedTargetId,
                                    boolean currentIsAlly, boolean currentIsPlayer) {
        // ★★★ 2026-10-17 第十二轮：**玩家判定排在最前面**，连「一致 ⇒ KEEP」都要让路。
        //   为什么：R42 的原文是「玩家不会成为**任何**实体的目标」——
        //   若把 KEEP 放在前面，「账本里被塞进了玩家 UUID」或「上游算出了一个玩家目标」
        //   这两种（都真实发生过）会变成一次**静默的 KEEP** ⇒ 实体继续打玩家，而且
        //   我们的兜底看起来"判过了"。⇒ 玩家这一格必须无条件 CLEAR。
        if (currentIsPlayer) {
            return Apply.CLEAR_PROTECTED;
        }
        if (Objects.equals(currentTargetId, computedTargetId)) {
            return Apply.KEEP;
        }
        if (computedTargetId == null) {
            return Apply.CLEAR;
        }
        // ★ 到这里说明「实体挂着别人的非玩家目标」：友方 ⇒ CLEAR_ALLY（R27），
        //   其余（未知来头，例如别人的怪）⇒ SET（写回我们的目标，绝不清别人的东西）。
        if (currentIsAlly) {
            return Apply.CLEAR_ALLY;
        }
        return Apply.SET;
    }

    /**
     * 兼容重载（旧签名）：不知道当下挂着的目标是什么来头 ⇒ 按「既不是友方也不是玩家」处理
     * （等价于旧的二值判据）。
     *
     * <p>★ 保留它是为了**不破坏已有的行为断言**（{@code SummonTargetLockBehaviourTest}
     * 的第 1 条：我们算不出目标时清成 null）。生产路径**一律用四参重载**
     * —— 已由 {@code PlayerTargetExclusionTest} 的方法级字节码断言钉住。
     */
    public static Apply applyAction(UUID currentTargetId, UUID computedTargetId) {
        return applyAction(currentTargetId, computedTargetId, false, false);
    }

    /** 这一次处置会不会**写实体**（KEEP 不写；其余三种都写，其中两种写的是 {@code null}）。 */
    public static boolean writes(Apply action) {
        return action != Apply.KEEP;
    }

    /**
     * 优先级档位：**越小越优先**。
     * {@code affectedByBuilding = false} 时**所有候选同档**（建筑维度被完全跳过，【Q6】读法 A）。
     */
    public static int tier(Candidate candidate, boolean affectedByBuilding) {
        return affectedByBuilding && candidate.building() ? 0 : 1;
    }

    /**
     * 保留当前目标时，尽量返回它在**本次候选集里的新副本** ——
     * 距离/嘲讽是**本次刷新**测出来的，用旧副本会把上一個周期的距离一直带下去。
     * 若它不在候选集里（调用方的候选集本就可能不含它），原样返回。
     */
    private static Candidate refreshView(Candidate current, List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            if (candidate.id().equals(current.id())) {
                return candidate;
            }
        }
        return current;
    }
}
