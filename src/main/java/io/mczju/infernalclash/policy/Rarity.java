package io.mczju.infernalclash.policy;

/**
 * 卡牌稀有度（**5 档**，【Q1 已裁定】）—— **纯枚举，不碰服务端**。
 *
 * <p>与 {@code economy.yml} 的 {@code rarities} 段**一一对应**（见 {@code docs/03} §6）。
 *
 * <h2>★ 两条最容易搞混的口径</h2>
 * <ul>
 *   <li>{@link #BASIC} 的**卡包权重必须是 0** —— R49 的概率表只有 4 档，
 *       基础卡「初始全部拥有」（R44）⇒ **不进卡包**（{@link RarityTable} 会在构造时硬失败）；</li>
 *   <li>{@link #BASIC} **不在兑换表里**（R25~R30 的表只有 4 行）⇒
 *       {@link ExchangeMath} 认为基础卡**既不可分解也不可合成**。</li>
 * </ul>
 *
 * <p>⚠️ 枚举的**声明顺序即抽包时的判定顺序**，不要为了"看起来整齐"重排 ——
 * 那会改变同一种子下的抽包结果（{@link RarityTable#roll} 依赖它与
 * {@code Map} 的迭代顺序无关，见该类注释里的 `Map.copyOf` 坑）。
 */
public enum Rarity {

    /** 基础：初始全部拥有（R44），不进卡包，不可兑换。 */
    BASIC,

    /** 普通：卡包 50%（R49）。 */
    COMMON,

    /** 稀有：卡包 30%（R49）。 */
    RARE,

    /** 史诗：卡包 15%（R49）。 */
    EPIC,

    /** 传说：卡包 5%（R49）。 */
    LEGENDARY
}
