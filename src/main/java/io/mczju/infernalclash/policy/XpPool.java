package io.mczju.infernalclash.policy;

/**
 * ★ 经验池的<b>扣费</b>逻辑（纯函数）—— {@link XpCurve} 管「怎么涨」，本类管「怎么花」。
 *
 * <h2>口径（{@code docs/04} §16.7.2 / R19~R22 / Q4）</h2>
 * <ul>
 *   <li>经验是<b>连续资源</b>（单位 = 「级」，见 {@link XpCurve} 的类注释）⇒
 *       扣费后<b>允许小数余量</b>；</li>
 *   <li><b>含等于</b>：{@code xp == cost} 时必须能打出去（边界包含）；</li>
 *   <li>不足 ⇒ <b>拒绝 + 告诉玩家还差多少</b>（不是静默失败）⇒ {@link #shortfall}；</li>
 *   <li>★ 不足时 {@link #spend} <b>抛异常而不是返回负数</b> ——
 *       负经验会静默破坏后续所有扣费（{@code canAfford(-3, 2)} 恒 false，玩家再也打不出牌，
 *       而现场只看到「点了没反应」）。调用方必须先 {@link #canAfford}。</li>
 * </ul>
 *
 * <p>本类<b>不 import 任何 {@code org.bukkit} / Paper / MGC</b> ⇒ 可以离线单测
 * （{@link XpPoolTest}），并由 {@code PolicyPurityGuardTest} 与
 * {@code BytecodeScopeGuardTest} 两条字节码守卫钉住。
 *
 * <h2>为什么不带浮点容差</h2>
 * 比较一律用裸的 {@code >=}：{@code 2.999} 对 3 费<b>就是不够付</b>。
 * 若为了「看起来差不多」加个 {@code 1e-6} 容差，就会放行一次 1e-6 的透支，
 * 而 {@link XpCurve} 每次写入都只 clamp 到 {@code [0, maxLevel]}、<b>不会把负数修回来</b>
 * ⇒ 那个负值会一路传下去。容差只允许出现在**测试的断言**里（{@code XpPoolTest} 的 {@code EPS}）。
 */
public final class XpPool {

    private XpPool() {
    }

    /** 池里够不够付这张卡（<b>含等于</b>：正好够就必须能打出去）。 */
    public static boolean canAfford(double xp, int cost) {
        requireNonNegativeCost(cost);
        return xp >= cost;
    }

    /**
     * 扣费后的余量（<b>允许小数余量</b>）。不足 ⇒ 抛 {@link IllegalArgumentException}
     * （调用方必须先 {@link #canAfford}）。
     *
     * <p>★ <b>绝不返回负数</b>：返回值恒 {@code >= 0}。
     */
    public static double spend(double xp, int cost) {
        requireNonNegativeCost(cost);
        if (xp < cost) {
            throw new IllegalArgumentException(
                    "经验不足，不能扣费（调用方必须先 canAfford）：当前 " + xp + "，费用 " + cost);
        }
        return xp - cost;
    }

    /** 还差多少（够付则返回 0）—— 用于「经验不足，还差 x.x」的提示。 */
    public static double shortfall(double xp, int cost) {
        requireNonNegativeCost(cost);
        double missing = cost - xp;
        return missing > 0 ? missing : 0.0;
    }

    /** 费用不能为负（负费用 = 出牌反而加经验，属于配置错误，必须当场炸而不是静默放行）。 */
    private static void requireNonNegativeCost(int cost) {
        if (cost < 0) {
            throw new IllegalArgumentException("卡牌费用不能为负：实际 " + cost);
        }
    }
}
