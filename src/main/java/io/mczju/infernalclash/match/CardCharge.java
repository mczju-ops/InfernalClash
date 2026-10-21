package io.mczju.infernalclash.match;

/**
 * ★★ <b>「这一次出牌该不该扣经验」的纯判据</b> —— {@code /incl debug infinite-xp} 的全部语义
 * 就压在这一个布尔函数上。
 *
 * <h2>★ 权威（用户 2026-10-15 第二轮裁定，逐字留档）</h2>
 * 用户原话「<b>打牌不扣经验</b>」（{@code docs/24} §1 第 5 条；落地说明见 {@code docs/10} §8
 * 的「2026-10-15（第二轮裁定）」块）。按最字面的读法：
 * <ul>
 *   <li>开关开着 ⇒ <b>出牌这一步不再扣经验</b>：{@link #shouldCharge(boolean, boolean)} 一律
 *       返回 {@code false} —— <b>不再看经验够不够</b>（「经验不足」这个拒绝理由整条消失）；</li>
 *   <li>★ <b>经验本身照常累积</b>：本类<b>只管出牌扣费</b>这一件事，不碰
 *       {@link MatchService#addXp} / {@code growXp()}（时间轴给的照给、等级照升）；</li>
 *   <li>★ 它<b>不是</b>「经验不清零」、也<b>不是</b>「经验固定在某个值」（那两个当时列的选项
 *       <b>均未被采纳</b>）⇒ 代码里不存在任何「清零 / 固定」的分支。</li>
 * </ul>
 *
 * <h2>★★ 为什么单独一个类（而不是写成 {@code if}）</h2>
 * 本项目对「范围 / 判据」一贯要求<b>两道锁</b>：判据做成纯函数并单测，再让真正的调用点读它。
 * 这条判据的形状有个特别容易写反的地方 —— <b>「不扣费」是放行，不是拒绝</b>：
 * 开关开着时必须让出牌继续走下去（只是不扣经验），而不是「因为不够所以拒绝」。
 * ⇒ 把它抽出来，就能在普通 JUnit 里把真值表<b>逐格</b>钉死
 * （{@code match/CardChargeTest}，含「把判据写反 ⇒ 断言必须失败」的阳性对照）。
 *
 * <h2>★ 纯：只依赖 JDK</h2>
 * 没有 Bukkit / MGC / 本插件任何类型 ⇒ 必然可离线单测（满足 {@code AGENTS.md} §3）。
 * ★ 铁律 10：本类不出现 {@code getOnlinePlayers / getEntities / getLivingEntities / getWorlds}。
 *
 * @see MatchService#chargeXp(Side, int) 唯一的调用点（判据 + 扣减收在一处）
 */
public final class CardCharge {

    private CardCharge() {
    }

    /**
     * ★ <b>这一次出牌该不该扣经验</b>。
     *
     * <table border="1">
     *   <caption>真值表（四格逐格都有测试）</caption>
     *   <tr><th>{@code freeCasting}</th><th>经验够不够</th><th>返回</th><th>含义</th></tr>
     *   <tr><td>{@code false}</td><td>{@code true}</td><td>{@code true}</td>
     *       <td>正常对局：扣费并放行</td></tr>
     *   <tr><td>{@code false}</td><td>{@code false}</td><td>{@code false}</td>
     *       <td>正常对局：一分不扣 + 回「经验不足」</td></tr>
     *   <tr><td><b>{@code true}</b></td><td>{@code true}</td><td>{@code false}</td>
     *       <td rowspan="2">★★ {@code infinite-xp}：<b>两种都返回 false</b>
     *           —— 不扣费<b>且放行</b>（「经验够不够」这一维被整个短路掉）</td></tr>
     *   <tr><td><b>{@code true}</b></td><td>{@code false}</td><td>{@code false}</td></tr>
     * </table>
     *
     * <p>★★ <b>读返回值时注意它是「该不该扣」，不是「该不该放行」</b>：
     * 调用方（{@link MatchService#chargeXp}）在开关开着时走的是<b>放行且不扣</b>那一支，
     * 只有在 {@code freeCasting == false} 时才把 {@code false} 解释成「费用校验失败」。
     * 这个区分是刻意的：把「放行」写进返回值会让真值表有两种读法。
     *
     * @param freeCasting 本场是不是「打牌不扣经验」（{@link MatchService#freeCasting()}）
     * @param enoughXp    当前经验够不够付这张卡（{@code XpPool.canAfford(xp, cost)}）
     * @return {@code true} = 该扣（并已足够）；{@code false} = 不扣
     */
    public static boolean shouldCharge(boolean freeCasting, boolean enoughXp) {
        if (freeCasting) {
            // ★ 用户裁定「打牌不扣经验」：这一维一短路，经验够不够都不扣
            //   （★ 也正因此，本行**不能**写成 `return enoughXp;` —— 那会退化成「没开开关」）
            return false;
        }
        return enoughXp;
    }
}
