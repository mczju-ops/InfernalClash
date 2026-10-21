package io.mczju.infernalclash.policy;

/**
 * ★★★ <b>「玩家退出对局」（`/mgc leave` / 掉线）该做什么</b>——纯判据（2026-10-20 新增）。
 *
 * <h2>用户裁定（逐字）</h2>
 * 2026-10-20 用户对我列出的四个问题的答复：「**掉线/leave=投降**」。
 * 它落的其实是文档里早就写着的一条：{@code docs/05} **Q28**「玩家退出对局 ⇒ **判负**」，
 * 以及 {@code docs/07} §9 的逐字「本项目退出即**判该方失败**并走结算，
 * **不沿用**其『继续打』的规则」。
 *
 * <h2>★★ 本条修复的缺陷（2026-10-20 核查）</h2>
 * 修之前 {@code game/InfernalClashQuitStrategy} 只做两件事：把人传回大厅、
 * <b>在「一个真人都不剩」时中止整局</b>（{@code shouldAbortGame(remaining) => remaining <= 0}）。
 * ⇒ 1v1 里一方掉线/离开时：<b>退出方档案不记负、对面要干等 3~4 分钟</b>，
 * 房间还被占着 —— 与文档写的「判负」不是一回事。
 * （唯一实现了「退出判负」的是**主动投降** {@code MatchService#surrender}，
 * 也就是本次复用那条路径。）
 *
 * <h2>★ 判据（三条，顺序不能换）</h2>
 * <ol>
 *   <li><b>一个真人都不剩</b> ⇒ {@link Action#ABORT_MATCH}（空局没有胜负可分，走既有中止路径）；
 *       这条**必须最先**判 —— 否则「最后一个人掉线」会变成"给他自己判负"，荒谬；</li>
 *   <li><b>对局已经不在运行</b>（已结束 / 已收尾 / 关服）⇒ {@link Action#NONE}
 *       （绝不给一局已经结算过的对局再判一次，那是重复记账）；</li>
 *   <li><b>退出者不在本局名册里</b>（观战 / 走错房间 / 人机不算真人）⇒ {@link Action#NONE}
 *       —— ★ 失败方向是「不判」：绝不因为"有人退出了"就给**无辜的一方**判负。</li>
 * </ol>
 * 三条都不命中 ⇒ {@link Action#FORFEIT}（= 对他判负，复用投降那条收尾路径）。
 *
 * <h2>★ 纯（可离线单测）</h2>
 * 不碰 Bukkit / MGC：判据只看三个入参（{@code policy/QuitRulesTest} 逐格覆盖）。
 */
public final class QuitRules {

    /** 退出事件的处置方式。 */
    public enum Action {
        /** 什么都不做（对局已结束 / 退出者不在名册）。 */
        NONE,
        /** 中止整局（一个真人都不剩；走既有 {@code GameManager.abortGame} 路径）。 */
        ABORT_MATCH,
        /** 把退出方判负（用户 2026-10-20：「掉线/leave=投降」；复用 {@code MatchService#surrender}）。 */
        FORFEIT
    }

    private QuitRules() {
    }

    /**
     * 判一次退出事件该怎么处置。
     *
     * @param remainingRealPlayers 退出**之后**仍在局内的真人数（人机不算，{@code >= 0}）
     * @param matchRunning         对局现在是否还在运行（{@code state.isRunning()}）
     * @param leaverOnRoster       退出者是否在本局名册里（能查到他的阵营）
     * @return 处置方式（永不为 {@code null}）
     * @throws IllegalArgumentException 真人数为负（调用方的编程错误）
     */
    public static Action decide(int remainingRealPlayers, boolean matchRunning, boolean leaverOnRoster) {
        if (remainingRealPlayers < 0) {
            throw new IllegalArgumentException(
                    "remainingRealPlayers 不能为负：实际 " + remainingRealPlayers);
        }
        // ① 空局 ⇒ 中止（先判，避免"最后一个人掉线"被当成给他自己判负）
        if (remainingRealPlayers == 0) {
            return Action.ABORT_MATCH;
        }
        // ② 已经不在运行 ⇒ 什么都不做（重复判负 = 重复记账）
        if (!matchRunning) {
            return Action.NONE;
        }
        // ③ 退出者不是本局参与者（观战 / 走错房间）⇒ 不判（无辜的一方不该输）
        if (!leaverOnRoster) {
            return Action.NONE;
        }
        return Action.FORFEIT;
    }
}
