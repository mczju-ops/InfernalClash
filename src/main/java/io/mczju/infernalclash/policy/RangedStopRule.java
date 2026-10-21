package io.mczju.infernalclash.policy;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ★★★ <b>「远程单位进射程就站定」的判据</b> —— 纯函数，2026-10-21 新增。
 *
 * <h2>用户原话（逐字）</h2>
 * 「2、对于**劫掠者以及其他所有远程召唤物（使用弓、弩的怪，冰术师等）**，
 * 应用**死灵法师同款**『敌人进射程站定不动』的 ai」。
 *
 * <h2>「同款」指什么（已有实现，别再造一份）</h2>
 * 死灵法师那一格早在 2026-10-18 就落地了：卡上写
 * {@code summon.stop-when-target-within: 8}，执行层是
 * {@code battle/SummonCombat#holdGround()} ＋ 纯判据 {@link HoldDistance#shouldHold}：
 * <b>目标进 N 格（含边线）⇒ 每 tick 取消寻路 + 速度归零；目标出 N 格 ⇒ 立刻恢复移动</b>。
 * ⇒ 本轮**不新增机制**，只回答一件事：<b>哪些单位、用多大的 N</b>。
 *
 * <h2>★ 哪些单位算「远程」（判据是**扫它实际挂着的 goal**，不是实体白名单）</h2>
 * 与 {@code battle/AttackGoalDetector} 同一套做法（Paper 的 goal key 从 NMS 类名推导，
 * 狐狸的近战 goal 就 {@code fox_melee_attack} 而不是 {@code melee_attack} ——
 * 写死白名单就会漏一个物种）。这里认四个词根：
 * <ul>
 *   <li>{@code bow} —— 骷髅 / 流浪者 / 冰术师（{@code ranged_bow_attack}）；</li>
 *   <li>{@code crossbow} —— 劫掠者（{@code ranged_crossbow_attack}）；</li>
 *   <li>{@code trident} —— 溺尸（{@code drowned_trident_attack}）；</li>
 *   <li>{@code ranged} —— 兜底（26.2 的远程攻击 key 都是 {@code RANGED_*} 一族）。</li>
 * </ul>
 * ★ 近战单位（{@code zombie_attack} / {@code melee_attack} / {@code polar_bear_melee_attack}）**不匹配**
 * ⇒ 它们行为一字未变（仍然是"走到脸上打"）。
 *
 * <h2>★★ N 取多少（这是我们**唯一**要定的数）</h2>
 * {@link #DEFAULT_STOP_DISTANCE} = <b>8.0 格</b>，不是我新拟的：
 * <ul>
 *   <li>它就是用户 2026-10-18 为同一条规则批准的那个数
 *       （「N 等于它的火球索敌射程」⇒ 死灵法师 = 8）⇒ 本轮是**把同一个数推广**，不是新数值；</li>
 *   <li>方向也是安全的：这个数**只决定"什么时候停下"**，不决定"打不打得到"。
 *       取小了只是"再多走两步"，取大了才会出现"站在射程外傻站着"。
 *       8 格在三种远程武器的实际射程之内（弓 ~15、弩 ~8、三叉戟 ~10 量级），
 *       属于"宁可多走两步"的保守侧；</li>
 *   <li>★ 若某张卡写了 {@code summon.stop-when-target-within}，**以卡上的为准**
 *       （死灵法师仍是 8，行为一字未变）。</li>
 * </ul>
 *
 * <p>★ 纯类（只吃字符串），离线可逐格断言（{@code RangedStopRuleTest}）。
 */
public final class RangedStopRule {

    /**
     * 远程单位「进射程就站定」的**默认**距离（格）。
     *
     * <p>★ 8.0 = 死灵法师那一格（用户 2026-10-18 批准的数）⇒ 本轮是推广，不是新数值。
     * ★ 改它要同时看：{@code docs/03} §4.3（`stop-when-target-within` 的说明）、
     * {@code docs/02} §8（索敌/走位口径）、{@code RangedStopRuleTest}。
     */
    public static final double DEFAULT_STOP_DISTANCE = 8.0;

    /** 远程攻击类 goal key 里的词根（大小写不敏感；见类注释）。 */
    public static final Set<String> RANGED_GOAL_TOKENS = Set.of("bow", "crossbow", "trident", "ranged");

    private RangedStopRule() {
    }

    /**
     * 这组 goal key 里有没有「远程攻击」那一类。
     *
     * @param goalKeys 该实体**实际挂着**的 goal key 名（{@code ranged_bow_attack} 这种形态）
     * @return 有任意一个 key 命中 {@link #RANGED_GOAL_TOKENS} ⇒ {@code true}
     */
    public static boolean isRanged(Collection<String> goalKeys) {
        if (goalKeys == null || goalKeys.isEmpty()) {
            return false;
        }
        for (String key : goalKeys) {
            if (matchesRanged(key)) {
                return true;
            }
        }
        return false;
    }

    /** 单个 goal key 名是否命中远程词根（下划线切成词后**逐词全等**）。 */
    public static boolean matchesRanged(String goalKey) {
        if (goalKey == null || goalKey.isBlank()) {
            return false;
        }
        for (String token : goalKey.toLowerCase(Locale.ROOT).split("_")) {
            if (RANGED_GOAL_TOKENS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 命中远程词根的那些 key（日志/探针用，保序）。 */
    public static List<String> rangedGoals(Collection<String> goalKeys) {
        if (goalKeys == null || goalKeys.isEmpty()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (String key : goalKeys) {
            if (matchesRanged(key)) {
                out.add(key);
            }
        }
        return List.copyOf(out);
    }

    /**
     * ★ <b>这个单位最终该用多大的「站定距离」</b>。
     *
     * <p>优先级（三条，最后一条是"不变"）：
     * <ol>
     *   <li>卡上写了 {@code summon.stop-when-target-within} ⇒ **用它**（死灵法师在这条）；</li>
     *   <li>没写、但它是**远程单位** ⇒ {@link #DEFAULT_STOP_DISTANCE}（8.0）；</li>
     *   <li>其余（近战 / 没有远程攻击 goal）⇒ {@code null} = **不站定**（既有行为）。</li>
     * </ol>
     *
     * @param cardValue 卡上的值（{@code null} = 没写）
     * @param ranged    这个单位是不是远程（{@link #isRanged}）
     * @return 有效距离；{@code null} = 不站定
     */
    public static Double stopDistanceFor(Double cardValue, boolean ranged) {
        if (cardValue != null) {
            return cardValue;
        }
        return ranged ? DEFAULT_STOP_DISTANCE : null;
    }
}
