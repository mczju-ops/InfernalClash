package io.mczju.infernalclash.policy;

import java.util.EnumMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 稀有度权重表（**纯函数，可离线单测**）—— 对应 {@code economy.yml} 的 {@code rarities} 段。
 *
 * <h2>照抄自用户给定的值（R49 / R50 / R52，**不得改动**）</h2>
 * <pre>
 *   BASIC     权重 0    金色 0.00   ← 基础档不进卡包（R44「初始拥有」）
 *   COMMON    权重 50   金色 0.05
 *   RARE      权重 30   金色 0.05
 *   EPIC      权重 15   金色 0.05
 *   LEGENDARY 权重 5    金色 0.05
 * </pre>
 *
 * <h2>★ 构造时硬失败的三条（防口径回退）</h2>
 * <ol>
 *   <li>{@link Rarity#BASIC} 的权重**必须为 0** —— 否则「基础卡进卡包」会悄悄破坏 R49 的 4 档概率表；</li>
 *   <li>权重不得为负；总权重必须 &gt; 0（否则无法抽包）；</li>
 *   <li>金色概率必须在 {@code [0, 1]} 内。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么 {@link #roll} 遍历 {@code Rarity.values()} 而不是遍历 {@code Map}</h2>
 * 本项目的 {@code AGENTS.md} 记着一条实测坑：{@code Map.copyOf} 的**迭代顺序随机**
 * （跨重启都不同）。抽包要「同种子 → 同结果」，所以判定顺序只能取自**枚举声明顺序**，
 * 绝不能取自传入的 {@code Map} 的迭代顺序。
 *
 * <p>出厂值由 {@link #shipped()} 提供；运行期由配置层（P1）从 {@code economy.yml} 读入，
 * 两者必须一致 —— 由 {@code ShippedEconomyTest}（P1）钉住。
 */
public final class RarityTable {

    private final Map<Rarity, Integer> weights;
    private final Map<Rarity, Double> goldChances;
    private final int totalWeight;

    /** 出厂权重（R49）。 */
    private static Map<Rarity, Integer> shippedWeights() {
        Map<Rarity, Integer> m = new EnumMap<>(Rarity.class);
        m.put(Rarity.BASIC, 0);
        m.put(Rarity.COMMON, 50);
        m.put(Rarity.RARE, 30);
        m.put(Rarity.EPIC, 15);
        m.put(Rarity.LEGENDARY, 5);
        return m;
    }

    /** 出厂金色概率（R50 / R52：每张**独立 5%**）。 */
    private static Map<Rarity, Double> shippedGoldChances() {
        Map<Rarity, Double> m = new EnumMap<>(Rarity.class);
        m.put(Rarity.BASIC, 0.0);
        m.put(Rarity.COMMON, 0.05);
        m.put(Rarity.RARE, 0.05);
        m.put(Rarity.EPIC, 0.05);
        m.put(Rarity.LEGENDARY, 0.05);
        return m;
    }

    /** 出厂表（用户给定值，照抄）。 */
    public static RarityTable shipped() {
        return new RarityTable(shippedWeights(), shippedGoldChances());
    }

    /**
     * @param weights     逐档卡包权重（缺档按 0 处理）
     * @param goldChances 逐档金色概率（缺档按 0 处理）
     */
    public RarityTable(Map<Rarity, Integer> weights, Map<Rarity, Double> goldChances) {
        Map<Rarity, Integer> w = new EnumMap<>(Rarity.class);
        for (Rarity rarity : Rarity.values()) {
            Integer value = weights == null ? null : weights.get(rarity);
            int weight = value == null ? 0 : value;
            if (weight < 0) {
                throw new IllegalArgumentException(
                        "rarities." + rarity + ".pack-weight 不能为负：实际 " + weight);
            }
            w.put(rarity, weight);
        }

        // ★ 硬失败 1：基础档不进卡包（R44 + R49 只有 4 档）
        int basicWeight = w.get(Rarity.BASIC);
        if (basicWeight != 0) {
            throw new IllegalArgumentException(
                    "rarities.BASIC.pack-weight 必须是 0（实际 " + basicWeight + "）："
                            + "基础卡「初始全部拥有」（R44），不进卡包；R49 的概率表只有 4 档");
        }

        Map<Rarity, Double> g = new EnumMap<>(Rarity.class);
        for (Rarity rarity : Rarity.values()) {
            Double value = goldChances == null ? null : goldChances.get(rarity);
            double chance = value == null ? 0.0 : value;
            if (!(chance >= 0.0) || chance > 1.0) {
                throw new IllegalArgumentException("rarities." + rarity
                        + ".gold-chance 必须在 [0, 1] 内：实际 " + chance);
            }
            g.put(rarity, chance);
        }

        int total = 0;
        for (Rarity rarity : Rarity.values()) {
            total += w.get(rarity);
        }
        if (total <= 0) {
            throw new IllegalArgumentException(
                    "所有稀有度的 pack-weight 都是 0，卡包永远抽不出卡，拒绝启动");
        }

        this.weights = Map.copyOf(w);
        this.goldChances = Map.copyOf(g);
        this.totalWeight = total;
    }

    /** 该档的卡包权重（无配置 = 0）。 */
    public int weight(Rarity rarity) {
        return weights.get(rarity);
    }

    /** 该档的金色概率（无配置 = 0.0）。 */
    public double goldChance(Rarity rarity) {
        return goldChances.get(rarity);
    }

    /** 总权重（= 分母）。 */
    public int totalWeight() {
        return totalWeight;
    }

    /** 该档是否会出现在卡包里（权重 &gt; 0）。 */
    public boolean isDrawable(Rarity rarity) {
        return weight(rarity) > 0;
    }

    /**
     * 按权重抽一档稀有度。
     *
     * <p>★ 只消费**一个**随机数（{@code nextInt(totalWeight)}），且判定顺序 = 枚举声明顺序
     * ⇒ 同一种子必然得到同一结果（可离线复现）。
     */
    public Rarity roll(RandomGenerator random) {
        int roll = random.nextInt(totalWeight);
        int accumulated = 0;
        for (Rarity rarity : Rarity.values()) {
            accumulated += weights.get(rarity);
            if (roll < accumulated) {
                return rarity;
            }
        }
        // 数学上不可达：roll < totalWeight 且累计和 == totalWeight
        throw new IllegalStateException(
                "抽包判定越界（roll=" + roll + ", total=" + totalWeight + "）—— 权重表不一致");
    }
}
