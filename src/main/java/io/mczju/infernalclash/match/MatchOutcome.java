package io.mczju.infernalclash.match;

/**
 * 对局结果 —— 由 {@link MatchPhaseMachine} 依据 {@code docs/02} §4 的胜负矩阵决定。
 *
 * <h2>取值与 W 条的对应（不要自行扩展语义）</h2>
 * <table border="1">
 *   <caption>权威规则见 {@code docs/02} §4</caption>
 *   <tr><th>取值</th><th>由哪些 W 条产生</th></tr>
 *   <tr><td>{@link #NONE}</td><td>对局尚未结束（{@link MatchPhase#PREPARING} /
 *       {@link MatchPhase#BATTLE} / {@link MatchPhase#OVERTIME} 期间的常态）</td></tr>
 *   <tr><td>{@link #BLUE_WINS}</td><td>W1（红方信标死亡）/ W2（战斗耗尽时蓝方塔多）/
 *       W4（加时中红方塔或信标死亡）</td></tr>
 *   <tr><td>{@link #RED_WINS}</td><td>W1（蓝方信标死亡）/ W2（战斗耗尽时红方塔多）/
 *       W4（加时中蓝方塔或信标死亡）</td></tr>
 *   <tr><td>{@link #DRAW}</td><td>W6（双方信标<b>同时</b>死亡）/
 *       W5（加时耗尽时塔数<b>仍</b>相等）</td></tr>
 * </table>
 *
 * <p>★ 命名是「谁获胜」，不是「谁失败」：{@code beaconDownRed ⇒ BLUE_WINS}（W1）。
 *
 * <p>本枚举是<b>纯数据</b>：不持有玩家、不发放奖励（发奖由对局收尾侧负责）。
 */
public enum MatchOutcome {

    /** 尚无结果（对局进行中）。 */
    NONE,

    /** 红方获胜。 */
    RED_WINS,

    /** 蓝方获胜。 */
    BLUE_WINS,

    /** 平局（W6：双方信标同时死亡；W5：加时耗尽仍相等）。 */
    DRAW
}
