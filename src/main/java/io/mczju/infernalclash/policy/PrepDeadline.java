package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.match.MatchOutcome;
import io.mczju.infernalclash.match.Side;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * ★★ <b>准备阶段的「到点没确认卡组 ⇒ 自动判负」裁决</b>（纯函数，可离线逐格断言）。
 *
 * <h2>规则的来源（★ 不是我发明的）</h2>
 * <table border="1">
 *   <caption>逐条出处；★ 标「用户裁定」的是 2026-10-16 第八轮的原话留档</caption>
 *   <tr><th>规则</th><th>出处</th></tr>
 *   <tr><td>准备阶段 <b>1200 tick</b>，玩家选卡</td>
 *       <td>{@code docs/02} §2（R13）+ {@code docs/09} §5.4</td></tr>
 *   <tr><td>★ <b>1200 tick 内没选完 / 不选 ⇒ 自动判对面胜利</b></td>
 *       <td><b>用户裁定</b>（第八轮原话「c，自动判对面胜利」）⇒ 留档 {@code docs/24} §5.8</td></tr>
 *   <tr><td>★ <b>人机不参与这条</b>（它没有 U4 界面，牌来自 {@code config.yml}
 *       ｜{@code training.bots}）</td>
 *       <td><b>用户裁定</b>（第八轮任务口径）＋ 实机证据：人机每 20 tick 自己出牌
 *       （{@code latest.log} 17:15:16 起每秒一条「训练人机（RED）打出 …」）</td></tr>
 *   <tr><td>★ <b>两方都是真人、且两方都没确认</b></td>
 *       <td>★★ <b>用户已裁定 = 平局</b>（{@code docs/24} §6.1：用户原话「<b>平局</b>」）
 *           ⇒ 返回 {@link MatchOutcome#DRAW}。★ 这一格在裁定之前是「文档没写的保守默认」
 *           （登记 {@code docs/05} §E 的 <b>E15</b>），<b>2026-10-16 第九轮已结案</b>；
 *           任何人要改它，等于改用户裁定的玩法值（铁律 8）</td></tr>
 *   <tr><td>★★ <b>判负的前置条件：这一方<b>确实拿到过选卡机会</b></b></td>
 *       <td><b>本项目新增的安全前置</b>（{@code docs/24} §6.0）：现场撞到过
 *           「人没进去（没被传送、没弹 U4）却照样被判负」（{@code E:\MCpaper\logs\latest.log}
 *           17:52:56~17:53:56）⇒ 只要有一方<b>没拿到机会</b>，
 *           本类<b>不判</b>（{@link MatchOutcome#NONE}）并在 {@link Outcome#noChance()}
 *           里如实报出来；判据缺一格时宁可<b>不判</b>，绝不「顺手判一个输赢」</td></tr>
 * </table>
 *
 * <h2>★★ 为什么必须是纯函数（而不是散在 tick 回调里的 if）</h2>
 * 这条判据一头连着<b>玩法裁定</b>（谁输谁赢）、一头连着<b>对局收尾</b>（结算 / 房间释放）。
 * 把它摊进 {@code MatchService.tick()} 里手写，就会出现
 * 「人机那一侧也被要求确认」「两方同时到期时结果取决于 if 的书写顺序」这类
 * <b>编译不报错、离线也测不出</b>的缺陷（本项目最反复的那一类事故）。
 * ⇒ 判据收在这里，{@code MatchService} 只负责「问一句 + 执行」。
 *
 * <p>本类<b>只依赖 JDK ＋ {@link Side} / {@link MatchOutcome} 两个枚举</b>，
 * 不 import 任何 Bukkit / MGC（{@code PolicyPurityGuardTest} 守着这条）。
 *
 * <h2>★ 「人机」为什么是一个显式的格子</h2>
 * 任务口径原文：「<b>请在纯函数里把「人机」这一格也表达出来并测</b>」。
 * 人机那一侧<b>没有 U4 界面</b>（它的 8 张牌来自 {@code config.yml} 的 {@code training.bots}，
 * 已实机证实会出牌）⇒ 它<b>永远不需要确认</b>，
 * 既不能因为「它没确认」判它输，也不能因为「它没确认」判对面输。
 */
public final class PrepDeadline {

    /** 一方在准备阶段的身份 —— ★ 「人机」与「真人」在判据里是<b>两个格子</b>。 */
    public enum Controller {

        /**
         * 真人：要在准备阶段用 U4 界面确认卡组，到点没确认 ⇒ 判负。
         *
         * <p>出处：{@code docs/09} §5.4【Q37】「是准备阶段的选择卡组/现搭卡组的界面，
         * 只在准备阶段出现」＋ 本次用户裁定「1200 tick 内没选完 ⇒ 自动判对面胜利」。
         */
        HUMAN,

        /**
         * 人机：<b>不参与</b>准备阶段确认（它没有 U4 界面，牌来自 {@code training.bots}）。
         *
         * <p>实机证据：{@code E:\MCpaper\logs\latest.log} 17:15:16 起每秒一条
         * 「训练人机（RED）打出 swamp_guerrillas ⇒ 生成 3 个召唤物 @0,75,-130」
         * ＋ 收尾「训练人机收尾（结束）：… 本局共打出 180 张牌」
         * ⇒ 它的牌**不经玩家侧选卡链路**（{@code dev-log/2026-10-16-027} §二）。
         */
        BOT
    }

    /**
     * <b>一方</b>的准备阶段快照。
     *
     * @param controller 这一方是人还是人机（{@code null} ⇒ 按「未知」处理，见 {@link #verdict}）
     * @param confirmed  这一方<b>已经确认过卡组</b>（人机恒为无关值：判据先看身份）
     * @param offered    ★★ <b>这一方在准备阶段确实拿到过选卡机会</b>（选卡界面被弹过 /
     *                   「选卡菜单」物品被发过 —— 生产侧的唯一写点是
     *                   {@code InfernalClashGame#openDeckPickerForHumans} 里的
     *                   {@code prep().markOffered(side)}）。<b>判负的前置门</b>：
     *                   {@code false} ⇒ 这一方永远<b>不会</b>因为「到点未确认」被判负
     *                   （见 {@link #verdict}）。★ 人机恒为无关值。
     */
    public record SideStatus(Controller controller, boolean confirmed, boolean offered) {
    }

    /**
     * 一方的**裁决结果**。
     *
     * @param side     哪一方
     * @param confirmed 这一方<b>此刻</b>是否已确认
     * @param required  这一方<b>是不是</b>「需要在准备阶段确认」的那一类（人机 ⇒ {@code false}）
     * @param known     身份是否<b>已查明</b>（{@code false} = 快照缺失 / {@code controller == null}
     *                  ⇒ {@link #verdict} 一律不判）
     * @param offered   ★ 这一方<b>拿到过</b>选卡机会吗（见 {@link SideStatus#offered()}）
     * @param timedOut  这一方<b>到点仍未确认、且确实拿到过机会</b> ⇒ 它要按本次裁定<b>判负</b>
     * @param missedEntry ★★ 这一方<b>到点仍未确认、却从未拿到过机会</b>
     *                  ⇒ 这是「没给过机会」的那一格：<b>不判负</b>，
     *                  由调用方打一条明确的 WARN（见 {@link Outcome#noChance()}）
     */
    public record Verdict(Side side, boolean confirmed, boolean required, boolean known,
                          boolean offered, boolean timedOut, boolean missedEntry) {
    }

    /**
     * 整批裁决（一次算完两边）—— 用 record 而不是「若干 out 参数」或「三个独立方法」，
     * 是为了让<b>「哪些方到点没确认」与「本局结果」出自同一次计算</b>
     * （分两次算就可能出现「日志说甲没确认、判决却按乙没确认」）。
     *
     * @param outcome     本局结果；{@link MatchOutcome#NONE} = 还没到点 / 不需要判 / 有人没拿到机会
     * @param timedOut    到点仍未确认<b>且拿到过机会</b>的阵营（空集 = 没人超时；★ 可能同时有红与蓝）
     * @param anyHuman    本局是否存在真人（用于日志与「人机局」口径，不参与判负）
     * @param noChance    ★★ 到点仍未确认、却<b>从未拿到过选卡机会</b>的真人阵营
     *                    （非空 ⇒ {@code outcome == NONE}：<b>整局不判</b>）
     * @param reached     准备阶段是否<b>已经到点</b>（{@code elapsedTicks >= limitTicks}）——
     *                    调用方靠它区分「还没到点」与「到点了但什么都不判」
     */
    public record Outcome(MatchOutcome outcome, Set<Side> timedOut, boolean anyHuman,
                          Set<Side> noChance, boolean reached) {

        /** 有没有真的判出结果。 */
        public boolean decided() {
            return outcome != MatchOutcome.NONE;
        }

        /**
         * ★★ <b>是不是「有人没拿到选卡机会」⇒ 整局不判</b>。
         *
         * <p>调用方据此打一条明确 WARN（现场痛点：{@code docs/24} §6.0 的
         * 「人没进去却输了」）—— <b>不要</b>把这一格并进「没人超时」，两者原因完全不同。
         */
        public boolean hasUnservedHumans() {
            return !noChance.isEmpty();
        }

        /** 到点未确认的阵营（升序：{@code RED} 在前，与 {@link Side} 的声明顺序一致）。 */
        public Set<Side> timedOutSides() {
            Set<Side> ordered = EnumSet.noneOf(Side.class);
            for (Side side : Side.values()) {
                if (timedOut.contains(side)) {
                    ordered.add(side);
                }
            }
            return ordered;
        }
    }

    private PrepDeadline() {
    }

    /**
     * ★★ <b>裁决（唯一入口）</b>：这一 tick 该判谁赢？
     *
     * <h2>判据（顺序即优先级，逐条都有单测）</h2>
     * <ol>
     *   <li>任一方的身份未知（{@code status == null} 或 {@code controller == null}）
     *       ⇒ {@link MatchOutcome#NONE}（<b>不判</b>）—— 判据取不到时宁可不下判决，
     *       绝不「顺手猜一个」（与铁律 10 的失败方向同源）；</li>
     *   <li>还没到点（{@code elapsedTicks < limitTicks}）⇒ {@code NONE}
     *       （{@code reached == false}）；</li>
     *   <li>到点了：只把<b>「身份是真人、仍未确认、<u>且拿到过选卡机会</u>」</b>的阵营收进
     *       {@code timedOut}；</li>
     *   <li>★★ <b>安全前置（本项目新增，{@code docs/24} §6.0）</b>：到点仍未确认、
     *       <b>却从未拿到过选卡机会</b>的真人阵营收进 {@code noChance} ⇒
     *       <b>整局不判</b>（{@code NONE}）。理由：现场撞到过「人没被传送、没弹 U4
     *       （= 没给过机会）却照样被判负」⇒ 只要有一方连机会都没拿到，
     *       就说明准备阶段那条链根本没跑通，此时<b>「不判」是唯一安全的方向</b>
     *       （宁可这一局少判一次，也绝不冤枉玩家）；</li>
     *   <li>{@code timedOut} 为空 ⇒ {@code NONE}（没人欠确认 ⇒ 什么都不做，
     *       对局该照常进战斗）；</li>
     *   <li>恰好一方超时 ⇒ <b>对面获胜</b>（本次用户裁定）；
     *       ★ 三人以上时取「先声明的那一方」判负、另一方获胜，
     *       第三个超时的阵营<b>不改变结果</b>（本游戏是 1v1，多余玩家不参与）；</li>
     *   <li>★ 两方都超时 ⇒ {@link MatchOutcome#DRAW}（★★ <b>用户已裁定 = 平局</b>，
     *       {@code docs/24} §6.1 原话「<b>平局</b>」；它取代了此前那条「文档没写」的
     *       保守默认，{@code docs/05} §E 的 <b>E15 已结案</b>）。</li>
     * </ol>
     *
     * @param red       红方快照（{@code null}，或 {@code controller == null} ⇒ 身份未知 ⇒ 不判）
     * @param blue      蓝方快照（同上）
     * @param elapsedTicks 准备阶段已经过的 tick（负数按 0 处理 —— 时钟取不到时的失败方向）
     * @param limitTicks   准备阶段总时长（{@code docs/02} §2 的 {@code phases.preparing-ticks} = 1200）
     * @throws IllegalArgumentException {@code limitTicks < 1}（接线错误 ⇒ fail-fast，
     *         不静默兜底：0 或负的时限会让每一次 tick 都判负）
     */
    public static Outcome verdict(SideStatus red, SideStatus blue,
                                 int elapsedTicks, int limitTicks) {
        if (limitTicks < 1) {
            throw new IllegalArgumentException("PrepDeadline.verdict：limitTicks 必须 >= 1"
                    + "（准备阶段时长来自 docs/02 §2 的 phases.preparing-ticks = 1200），实际 "
                    + limitTicks);
        }

        boolean reached = elapsedTicks >= limitTicks;
        List<Verdict> verdicts = new ArrayList<>();
        verdicts.add(judge(Side.RED, red, reached));
        verdicts.add(judge(Side.BLUE, blue, reached));

        Set<Side> timedOut = EnumSet.noneOf(Side.class);
        Set<Side> noChance = EnumSet.noneOf(Side.class);
        boolean anyHuman = false;
        boolean unknown = false;
        for (Verdict verdict : verdicts) {
            if (verdict.required()) {
                anyHuman = true;
            }
            if (verdict.timedOut()) {
                timedOut.add(verdict.side());
            }
            if (verdict.missedEntry()) {
                noChance.add(verdict.side());
            }
            if (!verdict.known()) {
                unknown = true;
            }
        }

        if (unknown || !noChance.isEmpty() || timedOut.isEmpty()) {
            // ★ 身份未知 ⇒ 不判（失败方向）
            // ★★ 有人没拿到选卡机会 ⇒ 整局不判（安全前置：绝不重现「人没进去却输了」）
            // ★ 没人欠确认 ⇒ 不判（对局照常进战斗）
            return new Outcome(MatchOutcome.NONE, timedOut, anyHuman, noChance, reached);
        }
        if (timedOut.size() >= 2) {
            // ★★ 两方都没确认 ⇒ 平局 —— ★ **用户 2026-10-16 第九轮裁定**（docs/24 §6.1 原话
            //    「平局」）。它不再是"文档没写的保守默认"：E15 已结案，改它 = 改用户裁定的玩法值
            return new Outcome(MatchOutcome.DRAW, timedOut, anyHuman, noChance, reached);
        }
        // ★ 恰好一方超时 ⇒ 对面获胜（本次用户裁定）
        Side loser = timedOut.iterator().next();
        MatchOutcome outcome = loser == Side.RED ? MatchOutcome.BLUE_WINS : MatchOutcome.RED_WINS;
        return new Outcome(outcome, timedOut, anyHuman, noChance, reached);
    }

    /**
     * 单方的逐格判据（{@link #verdict} 的两格就是它逐格跑出来的）。
     *
     * <p>★ 只有 {@code timedOut}（判负）需要 {@code offered}；
     * {@code missedEntry}（没给过机会 ⇒ 不判负）正是它的补集 ——
     * 两者<b>互斥</b>，所以「拿到过机会的正常玩家仍会被判负」这条阳性对照在纯函数层就成立。
     */
    private static Verdict judge(Side side, SideStatus status, boolean reached) {
        boolean known = status != null && status.controller() != null;
        boolean required = known && status.controller() == Controller.HUMAN;
        boolean confirmed = known && status.confirmed();
        boolean offered = required && status.offered();
        boolean late = required && reached && !confirmed;
        boolean timedOut = late && offered;
        boolean missedEntry = late && !offered;
        return new Verdict(side, confirmed, required, known, offered, timedOut, missedEntry);
    }

    /**
     * ★ <b>给日志 / 播报用的可读文案</b>（不含任何玩家可见的排版，纯 String）——
     * 「谁因为到点没确认被判负」要能在控制台一眼看见（铁律 10 的可对账口径）。
     *
     * @return 例：{@code RED（到点未确认）}；空集 ⇒ 空串（调用方据此不打印）
     */
    public static String describe(Set<Side> timedOut) {
        if (timedOut == null || timedOut.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Side side : Side.values()) {
            if (timedOut.contains(side)) {
                parts.add(side.name() + "（到点未确认）");
            }
        }
        return String.join("＋", parts);
    }
}
