package io.mczju.infernalclash.objective;

/**
 * 目标物种类。
 *
 * <p>一局里两类目标物都存在：信标 2 个（红 / 蓝各一，R7 / R8）与防御塔 4 座（每方 2 座，R9）。
 * 两者的共同点见 {@code docs/04} §16.6.2 / §16.6.3：
 * <b>受击目标是村民载体，外观是 {@code BlockDisplay} 展示实体</b>，
 * 载体死亡 ⇒ 同步删除展示实体，并把「死亡边沿」上报给状态机（W1~W7）。
 *
 * <p>★ 为什么用枚举而不是字符串：{@link ObjectiveTracker} 里对种类做 {@code switch}
 * 时编译器会要求**穷尽**这两种 —— 将来若新增第三种目标物，漏处理的地方会当场编译失败，
 * 而不是让新种类悄悄落进 {@code default} 分支被忽略。
 * 同理，配置里的取值（{@code SQUARE_25} 之类）也一律走「白名单 + 拒绝未知值」，
 * 决不静默忽略（见 {@code config/ObjectiveConfig}）。
 */
public enum ObjectiveKind {

    /** 信标（R7 / R8）：320 血、不攻击（C7）、{@code taunt: true}（无等级）。 */
    BEACON,

    /** 防御塔（R9）：240 血、25×25 **格数型**射程、每 0.5s 一道光束 2 点伤害（C7 / C15）。 */
    TOWER
}
