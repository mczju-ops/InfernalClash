package io.mczju.infernalclash.policy;

/**
 * ★★ <b>「这个目标能不能被推动/击退」的判据（纯函数）</b>
 * —— 2026-10-17 第十三轮收尾新增。
 *
 * <h2>为什么需要它（一个我起初也以为「已经做了」的缺口）</h2>
 * 用户实机原话：「**击退抗性依然没有实装，实测防御塔被「狂风突进」法术吹飞很远**」。
 *
 * <p>查证后发现是<b>两个</b>独立的原因叠在一起，缺一条都修不好：
 * <ol>
 *   <li><b>目标物（塔 / 信标）从来没设过 {@code KNOCKBACK_RESISTANCE}</b> ——
 *       {@code SummonSpawner} 只给召唤物写这个属性，而目标物由 {@code ObjectiveUnit} 造，
 *       那里只设了血量与嘲讽。</li>
 *   <li>★★ <b>更隐蔽的一条</b>：{@code PUSH}（狂风推进）与 {@code KNOCKBACK} 是
 *       {@link org.bukkit.entity.LivingEntity#setVelocity} <b>自己设的速度</b>，
 *       而 {@code KNOCKBACK_RESISTANCE} <b>只影响原版击退</b>（{@code damage()} 引起的、
 *       {@code knockback()} 方法给的）⇒ <b>把属性设成 1.0 也推不动不了</b>。
 *       </li>
 * </ol>
 * ⇒ 所以「抗击退」必须落成<b>我们自己效果里的判据</b>（本类），而不是只写一个属性。
 *
 * <h2>为什么判据读「属性」而不是「它是不是目标物」</h2>
 * 加一条「账本里 kind == 目标物就跳过」也能修，但那会造出<b>第二处事实来源</b>：
 * 以后任何「完全免疫击退」的召唤物（原版铁傀儡、卡表里写 {@code knockback-resistance: 1.0} 的怪）
 * 都得在两处各改一次。
 * ⇒ 判据统一读 {@code KNOCKBACK_RESISTANCE} 的<b>当前值</b>（含修饰符）：
 * <b>1.0 = 完全免疫</b>，与配置里那个键的语义<b>逐字一致</b>。
 *
 * <p>★ 阈值取 {@code >= 1.0 - 1e-9} 而不是 {@code == 1.0}：配置走 {@code double}，
 * {@code 1.0} 写成 {@code 1.00} 或经过属性修饰符累加后可能是 {@code 0.9999999999999999}。
 */
public final class DisplacementRule {

    /** 视为「完全免疫推动/击退」的阈值。 */
    public static final double IMMUNE_THRESHOLD = 1.0;

    /** 比较容差（见类 javadoc 最后一段）。 */
    public static final double EPSILON = 1.0e-9;

    private DisplacementRule() {
    }

    /**
     * ★ <b>能不能推动它</b>。
     *
     * @param knockbackResistance 目标当前的 {@code KNOCKBACK_RESISTANCE}（含修饰符）；
     *                            {@code NaN} / 负值按「能推」处理（失败方向 = 照常生效）
     * @return {@code true} = 会被推动；{@code false} = <b>完全免疫</b>
     */
    public static boolean canDisplace(double knockbackResistance) {
        if (Double.isNaN(knockbackResistance)) {
            return true;
        }
        return knockbackResistance < IMMUNE_THRESHOLD - EPSILON;
    }

    /** 便利：反过来问（日志与调用点读起来更顺）。 */
    public static boolean immune(double knockbackResistance) {
        return !canDisplace(knockbackResistance);
    }

    /**
     * ★★★ <b>「狂风推进」能不能推动它</b>（1.1.0 的收紧口径）。
     *
     * <h2>出处（{@code docs/1.1卡牌.txt}，逐字）</h2>
     * 「<b>狂风推进不再影响带有击退抗性的召唤物。</b>」
     *
     * <h2>与 {@link #canDisplace} 的差别（★ 只对 PUSH 收紧，其它位移一字不动）</h2>
     * <ul>
     *   <li>{@link #canDisplace}：只有击退抗性<b>满 1.0</b> 才算免疫 —— 这是「击退 / 持续推动」
     *       两条共用的老口径，目标物（塔 / 信标）靠它挡风；</li>
     *   <li>本方法：<b>只要带了击退抗性（{@code > 0}）就不推</b> —— 用户 1.1.0 的新口径，
     *       于是 0.8 的铁甲战士、以及 1.1.0 起拿到 +1 点击退抗性的「优先攻击建筑」单位
     *       都不会被风墙推着走。</li>
     * </ul>
     * ★ 为什么不用一个新常量把两条都改掉：那样「火球击退」也会跟着失效
     * （用户只点了狂风推进），而 0.8 抗性的铁甲战士被火球推开**本来就是预期**。
     *
     * @param knockbackResistance 目标当前的 {@code KNOCKBACK_RESISTANCE}（含修饰符）
     * @return {@code true} = 可以被狂风推进推走；{@code false} = 不推
     */
    public static boolean pushable(double knockbackResistance) {
        if (Double.isNaN(knockbackResistance)) {
            return true;   // 读不到 ⇒ 按能推（失败方向 = 照常生效）
        }
        return knockbackResistance <= EPSILON;
    }

    /** 一行说明（日志用）。 */
    public static String describe(double knockbackResistance) {
        return immune(knockbackResistance)
                ? "完全免疫位移（击退抗性 " + knockbackResistance + "）"
                : "可被位移（击退抗性 " + knockbackResistance + "）";
    }
}
