package io.mczju.infernalclash.policy;

/**
 * ★★★ <b>「锁定后站定」的距离判据</b>（纯函数）—— 2026-10-18 新增。
 *
 * <h2>出处（用户原话，逐字）</h2>
 * 第 2 条：「**我方召唤物还是会有两秒左右发呆的过程：死灵法师甚至去打了两下自家防御塔
 * 才开始正常索敌**」之后紧接着的一条：「**死灵法师锁定后应静止（远程怪跑去近战送）**」。
 * 我追问「N 该取多少」时用户答：「**N 等于它的火球索敌射程**」⇒ 死灵法师 = **8**
 * （它那条能力就是 {@code radius: 8} 的火球，见 {@code cards/necromancer.yml}）。
 *
 * <h2>为什么这条判据要单独成一个纯类</h2>
 * 「几格以内该停」是<b>玩法数值 × 距离比较</b>，而它的落地要在执行层每 tick 做一次
 * （{@code battle/SummonCombat#holdGround}）。若判据留在执行层里，
 * 它就<b>只能靠实机看</b>（而那正是上一轮"卡顿"被报上来的方式）。
 * 抽出来以后可以离线穷举：边线算不算、没目标算不算、{@code null} / 非正数算不算。
 *
 * <h2>口径（四条，全部可复述）</h2>
 * <ol>
 *   <li><b>没写这一格</b>（{@code stopWithin == null}）⇒ <b>永不站定</b>
 *       —— 这是 50/51 张卡的既有行为，加这个键不能静默改它们；</li>
 *   <li><b>没有目标</b> ⇒ <b>不站定</b>：没目标就该继续走（走到能打的地方），
 *       否则它会站在原地等死（正是用户报的那种"发呆"）；</li>
 *   <li><b>距离 ≤ N</b>（<b>含边线</b>）⇒ <b>站定</b>。★ 用闭区间：卡里写 {@code 8}、
 *       目标恰好 8.0 格时，若判成"还在外面"，实机上表现为"在 8 格左右反复挪一下"
 *       —— 那种抖动比完全不动更难看；</li>
 *   <li><b>非正数 / NaN / 无穷</b> ⇒ <b>不站定</b>（防御：装载期已经拒绝 {@code <= 0}，
 *       这里是为了让"绕过装载期的夹具"也不至于把单位钉死在原地）。</li>
 * </ol>
 *
 * <p>★ 纯类：不碰 Bukkit ⇒ 离线逐格可断言（{@code HoldDistanceTest}）。
 */
public final class HoldDistance {

    private HoldDistance() {
    }

    /**
     * 这个召唤物此刻该不该<b>站定不动</b>。
     *
     * @param stopWhenTargetWithin 卡上的 {@code summon.stop-when-target-within}（{@code null} = 没写）
     * @param hasTarget            此刻有没有有效目标
     * @param distance             与目标的距离（格）
     * @return {@code true} = 站定（不移动）；{@code false} = 照常走
     */
    public static boolean shouldHold(Double stopWhenTargetWithin, boolean hasTarget, double distance) {
        if (stopWhenTargetWithin == null) {
            return false;
        }
        double stop = stopWhenTargetWithin;
        if (Double.isNaN(stop) || Double.isInfinite(stop) || stop <= 0.0) {
            return false;
        }
        if (!hasTarget) {
            return false;
        }
        if (Double.isNaN(distance) || Double.isInfinite(distance)) {
            return false;
        }
        return distance <= stop;
    }
}
