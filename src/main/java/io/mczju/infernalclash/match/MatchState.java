package io.mczju.infernalclash.match;

/**
 * 对局状态快照（不可变）—— 阶段 + 两个计时器 + 结果。
 *
 * <h2>两个计时器为什么必须分开</h2>
 * <ul>
 *   <li>{@code phaseTick} —— <b>当前阶段内</b>的 tick（进入新阶段时归零）。
 *       准备 / 战斗 / 加时的时长判据都只看它；</li>
 *   <li>{@code battleTick} —— <b>战斗开始以来</b>的 tick，<b>跨进加时继续增长</b>
 *       （★ D2：加时经验继续按双倍，判据只有 {@code battleTick >= doubleXpAfterTicks}
 *       这一条，没有第三种速率）。</li>
 * </ul>
 * ⇒ 若把两者合成一个计数器，「加时按双倍」与「加时自己的 1200 tick 计时」就会互相打架。
 *
 * <p>本类型是<b>纯数据</b>：{@link #isRunning()} 是唯一的行为，且只读自己的字段。
 *
 * @param phase     当前阶段
 * @param phaseTick 当前阶段内已推进的 tick 数
 * @param battleTick 战斗开始以来已推进的 tick 数（加时期间继续增长）
 * @param outcome   结果；{@code phase != ENDED} 时恒为 {@link MatchOutcome#NONE}
 */
public record MatchState(MatchPhase phase, int phaseTick, int battleTick, MatchOutcome outcome) {

    /** 对局起点：准备阶段第 0 tick，无结果。 */
    public static final MatchState INITIAL =
            new MatchState(MatchPhase.PREPARING, 0, 0, MatchOutcome.NONE);

    /**
     * 对局是否仍在进行中。
     *
     * <p>★ 判据<b>只有</b> {@code phase != ENDED} —— <b>不要</b>用
     * {@code outcome == NONE} 代替：加时中的 {@code outcome} 也是 {@code NONE}，
     * 但那<b>不是</b>「还没开始」而是「还没分出胜负」；
     * 反过来，任何时刻「已结束」与「有结果」应当一致，两者混用迟早会写反。
     */
    public boolean isRunning() {
        return phase != MatchPhase.ENDED;
    }

    /**
     * ★★ <b>就地结束这一局：把 phase 置 {@link MatchPhase#ENDED} 并带上结果</b>
     * （2026-10-16 第八轮为「准备阶段到点判负」新增）。
     *
     * <h2>为什么需要它（而不是让调用方直接 new 一个 MatchState）</h2>
     * 「准备阶段超时」这条判决<b>不走</b> {@link MatchPhaseMachine} 的 W1~W7
     * （那些是「战斗中的事实」，准备阶段一条都不成立），但它<b>必须走同一条收尾路径</b>：
     * {@code MatchService.finish()} → {@code onFinished} → {@code GameManager.endGame}
     * → 释放房间。而 {@code finish()} 读的是 {@code state.outcome()}
     * （{@code MatchService} 的 private 字段，外部改不了）⇒ 唯一干净的接法是
     * 「生成一个『已结束 + 有结果』的新状态」。
     *
     * <h2>★ 为什么 phase 必须一起置 ENDED（不能只换 outcome）</h2>
     * 本 record 的口径是「{@code phase != ENDED} 时 {@code outcome} 恒为 {@code NONE}」
     * （见类注释与 {@link #isRunning()}）。只换 outcome 会造出一个<b>非法状态</b>，
     * 而下游两处都会因此静默走错：
     * <ul>
     *   <li>{@code MatchService.finish()} 里的 {@code state.outcome()} 会印出正确结果，
     *       但 {@code stop()} 之后再也没人把 phase 改成 ENDED；</li>
     *   <li>★ 记账（{@code InfernalClashGame#recordMatchResult}）的判据正是
     *       {@code phase == ENDED} ⇒ <b>这一局会被当成「中止的对局」而不记战绩</b>
     *       （一条 WARN「不记战绩：本局没有走到 ENDED」），榜上白丢一场。</li>
     * </ul>
     * ⇒ 两者必须一起改：这就是「结束」这一件事的完整定义。
     *
     * @throws IllegalArgumentException {@code outcome == null}（「暂无结果」请传 {@code NONE}）
     */
    public MatchState ended(MatchOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("ended：outcome 不能为 null"
                    + "（「暂无结果」请传 MatchOutcome.NONE）");
        }
        return new MatchState(MatchPhase.ENDED, phaseTick, battleTick, outcome);
    }
}
