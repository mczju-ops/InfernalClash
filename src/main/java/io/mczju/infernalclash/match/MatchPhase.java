package io.mczju.infernalclash.match;

/**
 * 对局阶段 —— 状态机的四个状态（{@code docs/04} §16.1 的状态图）。
 *
 * <pre>
 * PREPARING(1200) ──(计时结束)──▶ BATTLE(3600，第 2400 tick 起双倍经验)
 *                                   │
 *         ┌─────────────────────────┼──────────────────────────┐
 *    信标死亡                  时间耗尽：塔数不等          时间耗尽：塔数相等
 *         │                         │                          │
 *         ▼                         ▼                          ▼
 *   立即结束·对方胜              多者胜                    OVERTIME(1200)
 *                                                              │
 *                                          加时中任一塔/信标死亡 → 该方失败
 *                                          加时结束仍相等       → 平局
 * </pre>
 *
 * <p>★ <b>计时口径一律是 tick</b>（{@code docs/02} §2「全游戏用 tick 计算时间轴」），
 * 所有边界都<b>「达到即触发」</b>（{@code >=}）—— 见 {@link MatchPhaseMachine}。
 *
 * <p>本枚举是<b>纯数据</b>：不持有任何服务端对象，也不做配置加载。
 */
public enum MatchPhase {

    /** 准备阶段（R13：1200 tick）：玩家选卡；计时结束进 {@link #BATTLE}。 */
    PREPARING,

    /** 战斗阶段（R11：3600 tick）：第 2400 tick 起双倍经验（R22）；信标死亡立即分胜负（W1）。 */
    BATTLE,

    /**
     * 加时阶段（R12：1200 tick）：<b>只有</b>「战斗耗尽且塔数相等」才会到这里（W3、W7）。
     *
     * <p>★ 加时期间的 {@code battleTick} <b>继续增长</b>（D2：加时经验继续按双倍，
     * 没有第三种速率）；加时自己有独立的 {@code phaseTick} 计时。
     */
    OVERTIME,

    /** 对局已结束：{@code outcome} 不再是 {@link MatchOutcome#NONE}；再推进是幂等的。 */
    ENDED
}
