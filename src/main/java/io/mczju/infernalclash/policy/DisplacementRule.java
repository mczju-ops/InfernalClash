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

    /** 一行说明（日志用）。 */
    public static String describe(double knockbackResistance) {
        return immune(knockbackResistance)
                ? "完全免疫位移（击退抗性 " + knockbackResistance + "）"
                : "可被位移（击退抗性 " + knockbackResistance + "）";
    }
}
