package io.mczju.infernalclash.match;

/**
 * 本 tick 的<b>外部事实</b> —— 由 P3 的目标物系统提供；P2 阶段全是 {@link #NONE}。
 *
 * <p>把它做成一整个 record（而不是若干散参数或若干回调）的理由：状态机是<b>纯函数</b>，
 * 它只描述「这一 tick 世界发生了什么」，不自己去查实体 ——
 * 于是整条时间轴可以在**离线单测**里跑完（{@code docs/04} §16.1、§16.5）。
 *
 * <h2>字段口径</h2>
 * <ul>
 *   <li>{@code beaconDownRed} / {@code beaconDownBlue} —— <b>本 tick</b>该方信标死亡（W1、W6）；</li>
 *   <li>{@code towerDownRed} / {@code towerDownBlue} —— <b>本 tick</b>该方防御塔死亡
 *       （**只在加时**参与判负，W4；战斗阶段掉塔不判负，塔数变化体现在下面的计数里）；</li>
 *   <li>{@code towersRed} / {@code towersBlue} —— 该方<b>剩余</b>防御塔数量（W2、W3、W5、W7）；</li>
 *   <li>★★ <b>{@code beaconGoneRed} / {@code beaconGoneBlue}</b> —— 该方信标<b>已经不在了</b>
 *       （<b>电平</b>，不是本 tick 的边沿）。2026-10-20 新增：加时的 W4 改成**电平判据**之后，
 *       它必须能回答「现在还有没有信标」，而不是只回答「这一 tick 刚刚死没死」。</li>
 * </ul>
 *
 * <p>⚠️ 「死亡」是<b>事件</b>（本 tick 发生），「塔数 / 信标还在不在」是<b>状态</b>
 * （本 tick 之后的剩余量）；两者都由 P3 提供，状态机不去推断它们之间的关系
 * （例如不去比对上一 tick 的塔数）。
 *
 * <h2>★★ 为什么加时的 W4 不能只靠「边沿」（2026-10-20 现场缺陷）</h2>
 * 用户实机报：「加时阶段一方玩家被拆掉一座塔时游戏没有立刻结束」。
 * 状态机原本只认 {@code towerDownRed} 这个**本 tick 边沿**；一旦那一个 tick 的信号
 * 因为任何原因没送到状态机（钩子抛异常被吞、`units` 在那一 tick 恰好为空、
 * 链路顺序变了……），这条边沿就**永久丢失**（追踪器是幂等的，不会重发） ⇒
 * 对局会一直拖到加时耗尽。现在改成「塔数不等 / 信标已失」这种**电平**判据：
 * 状态持续可见 ⇒ 下一个 tick 必然补上，**不可能再丢**。
 *
 * <p>本类型是<b>纯数据</b>，不持有实体 / 玩家引用（归属判定由 {@code UnitRegistry} 负责，铁律 10）。
 */
public record MatchSignal(boolean beaconDownRed, boolean beaconDownBlue,
                          boolean towerDownRed, boolean towerDownBlue,
                          int towersRed, int towersBlue,
                          boolean beaconGoneRed, boolean beaconGoneBlue) {

    /**
     * 无信号：没有任何目标物死亡，且双方塔数都是 0。
     *
     * <p>★ P2 阶段全是它（还没有塔与信标）⇒ 能跑完整条时间轴：
     * 准备 → 战斗 → 加时 → 平局（{@code docs/04} §16.1）。
     */
    public static final MatchSignal NONE =
            new MatchSignal(false, false, false, false, 0, 0, false, false);

    /**
     * 兼容构造：只给「边沿 + 塔数」（测试夹具与老调用点用）。
     *
     * <p>★ 电平字段取边沿的值 —— 语义上成立（这一 tick 刚死的信标，现在当然已经不在了），
     * 且让「只关心边沿」的老夹具保持原样可读。
     */
    public MatchSignal(boolean beaconDownRed, boolean beaconDownBlue,
                       boolean towerDownRed, boolean towerDownBlue,
                       int towersRed, int towersBlue) {
        this(beaconDownRed, beaconDownBlue, towerDownRed, towerDownBlue,
                towersRed, towersBlue, beaconDownRed, beaconDownBlue);
    }
}
