package io.mczju.infernalclash.policy;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 经济数值的算法层 —— **纯函数，可离线单测**（{@code docs/08} §2、§4，{@code docs/03} §6）。
 *
 * <h2>照抄自用户给定的值（R25~R30、Q8、Q55，**不得改动**）</h2>
 * <pre>
 *   兑换表                 分解重复普通版 | 抽到重复金卡的自动转化 | 合成普通版
 *   COMMON   普通              10       |         40             |    40
 *   RARE     稀有              40       |        160             |   100
 *   EPIC     史诗             100       |        400             |   400
 *   LEGENDARY 传说            400       |       1600             |  1600
 *
 *   卡包单价 100 金币；1 / 10 / 30 包**线性、无折扣**（= 100 / 1000 / 3000）
 *   金币奖励：真人胜 +200、真人败 +100、人机胜 +100、人机败 0
 *   首通奖励（第一次完成游戏）+3000
 * </pre>
 *
 * <h2>★ 三点语义（都在 {@code docs/08} 里定过，容易读错）</h2>
 * <ol>
 *   <li>「分解重复**金色**卡」这一列**不是**给玩家手动分解用的入口 ——
 *       【Q7 补充裁定】「<b>金色卡牌无法兑换</b>」，UI 上**不提供分解金卡的入口**；
 *       它是【Q30】「抽到**已拥有**的金卡 → **自动**转成该数量烈焰粉」的结算量；</li>
 *   <li>{@link Rarity#BASIC} **不在兑换表内**（R25~R30 的表只有 4 行）⇒
 *       {@link #isExchangeable} 为 {@code false}，{@link #ratesOf} 直接抛异常
 *       （基础卡 R44「初始全部拥有」，能分解就等于凭空产粉）；</li>
 *   <li>{@link #packPrice} 只认**已配置的规格**（出厂 1 / 10；★ 2026-10-20 起 30 包已下线）——
 *       未配置的包数**抛异常**，**不按单价现算**（免得悄悄绕过"线性、无折扣"这条规则）。</li>
 * </ol>
 */
public final class ExchangeMath {

    /** 一局金币奖励的四种结果（【Q55】）。 */
    public enum Reward {
        /** 真人局 · 胜：+200。 */
        PVP_WIN,
        /** 真人局 · 败：+100。 */
        PVP_LOSS,
        /** 人机局 · 胜：+100。 */
        BOT_WIN,
        /** 人机局 · 败：0。 */
        BOT_LOSS
    }

    /**
     * 一个稀有度的三档数值。
     *
     * @param duplicatePowder     分解**重复的普通版**得到的烈焰粉（R25~R28）
     * @param duplicateGoldPowder 抽到**重复的金卡**时的自动转化量（= 普通版的 4 倍）
     * @param unlockPrice         用烈焰粉**合成一张普通版**的价格
     */
    public record Rates(long duplicatePowder, long duplicateGoldPowder, long unlockPrice) {
        public Rates {
            if (duplicatePowder < 0 || duplicateGoldPowder < 0 || unlockPrice < 0) {
                throw new IllegalArgumentException("兑换表不能有负数："
                        + duplicatePowder + " / " + duplicateGoldPowder + " / " + unlockPrice);
            }
        }
    }

    /**
     * 出厂值：第一次完成游戏额外获得的金币。
     *
     * <p>★ 2026-10-20 用户裁定：<b>3000 → 1000</b>（逐字「将首次胜利给的 3000 金币改成 1000
     * （3000 基本能全卡差 23 张了）」）⇒ 配置键 {@code gold.first-game-bonus}。
     */
    public static final long SHIPPED_FIRST_GAME_BONUS = 1000L;

    private final Map<Rarity, Rates> rates;
    private final Map<Integer, Long> packPrices;
    private final Map<Reward, Long> goldRewards;
    private final long firstGameBonus;

    private static Map<Rarity, Rates> shippedRates() {
        Map<Rarity, Rates> m = new EnumMap<>(Rarity.class);
        m.put(Rarity.COMMON, new Rates(10, 40, 40));
        m.put(Rarity.RARE, new Rates(40, 160, 100));
        m.put(Rarity.EPIC, new Rates(100, 400, 400));
        m.put(Rarity.LEGENDARY, new Rates(400, 1600, 1600));
        return m;
    }

    /**
     * 出厂卡包规格与总价。
     *
     * <p>★ 2026-10-20 用户裁定取消「连开 30 包」（{@code 30: 3000} 下线）⇒ 只剩 1 / 10 两档；
     * 两档仍是「包数 × 100」的线性无折扣价（Q55）。
     */
    private static Map<Integer, Long> shippedPackPrices() {
        Map<Integer, Long> m = new LinkedHashMap<>();
        m.put(1, 100L);
        m.put(10, 1000L);
        return m;
    }

    private static Map<Reward, Long> shippedGoldRewards() {
        Map<Reward, Long> m = new EnumMap<>(Reward.class);
        m.put(Reward.PVP_WIN, 200L);
        m.put(Reward.PVP_LOSS, 100L);
        m.put(Reward.BOT_WIN, 100L);
        m.put(Reward.BOT_LOSS, 0L);
        return m;
    }

    /** 出厂经济表（全部为用户给定值）。 */
    public static ExchangeMath shipped() {
        return new ExchangeMath(shippedRates(), shippedPackPrices(), shippedGoldRewards(),
                SHIPPED_FIRST_GAME_BONUS);
    }

    /**
     * @param rates          逐档兑换表（**只允许 4 个非基础档**）
     * @param packPrices     包数 → 总价（必须包含 1 包，用于取单价）
     * @param goldRewards    四种结果的金币奖励（四种**都必须给**）
     * @param firstGameBonus 首通奖励（&gt;= 0）
     */
    public ExchangeMath(Map<Rarity, Rates> rates, Map<Integer, Long> packPrices,
                        Map<Reward, Long> goldRewards, long firstGameBonus) {
        if (rates != null && rates.containsKey(Rarity.BASIC)) {
            throw new IllegalArgumentException(
                    "exchange.BASIC 不允许配置：基础卡不在兑换表内（R25~R30 只有 4 行），"
                            + "它「初始全部拥有」（R44），可分解就等于凭空产粉");
        }
        Map<Rarity, Rates> r = new EnumMap<>(Rarity.class);
        for (Rarity rarity : Rarity.values()) {
            Rates value = rates == null ? null : rates.get(rarity);
            if (value != null) {
                r.put(rarity, value);
            }
        }

        Map<Integer, Long> prices = new LinkedHashMap<>();
        if (packPrices != null) {
            for (Map.Entry<Integer, Long> entry : packPrices.entrySet()) {
                int packs = entry.getKey() == null ? 0 : entry.getKey();
                long price = entry.getValue() == null ? -1L : entry.getValue();
                if (packs < 1) {
                    throw new IllegalArgumentException("pack.price 的包数必须 >= 1：实际 " + packs);
                }
                if (price < 0) {
                    throw new IllegalArgumentException("pack.price." + packs + " 不能为负：实际 " + price);
                }
                prices.put(packs, price);
            }
        }
        if (!prices.containsKey(1)) {
            throw new IllegalArgumentException(
                    "pack.price 必须至少配置「1 包」的价格（它是单价，见 docs/03 §6）");
        }

        Map<Reward, Long> rewards = new EnumMap<>(Reward.class);
        for (Reward reward : Reward.values()) {
            Long value = goldRewards == null ? null : goldRewards.get(reward);
            if (value == null) {
                throw new IllegalArgumentException("gold 段缺少奖励项：" + reward
                        + "（四种结果都必须显式给出，不猜默认值）");
            }
            if (value < 0) {
                throw new IllegalArgumentException("gold." + reward + " 不能为负：实际 " + value);
            }
            rewards.put(reward, value);
        }

        if (firstGameBonus < 0) {
            throw new IllegalArgumentException("gold.first-game-bonus 不能为负：实际 " + firstGameBonus);
        }

        this.rates = Map.copyOf(r);
        this.packPrices = Map.copyOf(prices);
        this.goldRewards = Map.copyOf(rewards);
        this.firstGameBonus = firstGameBonus;
    }

    /** 该稀有度是否可分解 / 可合成（基础卡**不可**）。 */
    public boolean isExchangeable(Rarity rarity) {
        return rates.containsKey(rarity);
    }

    /** 取该稀有度的兑换表；{@link Rarity#BASIC} 或不存在的档 → 抛异常（不返回 0 糊弄）。 */
    public Rates ratesOf(Rarity rarity) {
        Rates value = rates.get(rarity);
        if (value == null) {
            throw new IllegalArgumentException("稀有度 " + rarity
                    + " 不在兑换表内（基础卡不可分解、也不可合成，见 docs/08 §4）");
        }
        return value;
    }

    /** 分解一张**重复的普通版**卡 → 得到多少烈焰粉。 */
    public long duplicatePowder(Rarity rarity) {
        return ratesOf(rarity).duplicatePowder();
    }

    /**
     * 抽到一张**已拥有**的**金色**卡 → 自动转化多少烈焰粉（= 普通版的 4 倍）。
     *
     * <p>⚠️ 这**不是**玩家可触发的手动分解入口（【Q7】补充裁定：金色卡牌无法兑换）。
     */
    public long duplicateGoldPowder(Rarity rarity) {
        return ratesOf(rarity).duplicateGoldPowder();
    }

    /** 合成（解锁）一张该稀有度**普通版**卡的价格。 */
    public long unlockPrice(Rarity rarity) {
        return ratesOf(rarity).unlockPrice();
    }

    /** 金色档位是否恰好是普通版的 4 倍（R25~R30 表的口径；配置层可以用它做告警）。 */
    public boolean isGoldQuadruple() {
        for (Rates value : rates.values()) {
            if (value.duplicateGoldPowder() != value.duplicatePowder() * 4L) {
                return false;
            }
        }
        return true;
    }

    /** 买 1 包的价格（= 单价）。 */
    public long unitPackPrice() {
        return packPrice(1);
    }

    /** 已配置的售卖规格（如 1 / 10 / 30），升序。 */
    public java.util.List<Integer> packSpecs() {
        return packPrices.keySet().stream().sorted().toList();
    }

    /**
     * 买 {@code packs} 包的总价。
     *
     * @throws IllegalArgumentException 该包数**没有配置**（不按单价现算 ——
     *         否则「线性、无折扣」这条规则会被绕过）
     */
    public long packPrice(int packs) {
        Long price = packPrices.get(packs);
        if (price == null) {
            throw new IllegalArgumentException("没有配置 " + packs + " 包的价格；已配置的规格："
                    + packSpecs() + "（不许按单价现算，见 docs/08 §2）");
        }
        return price;
    }

    /** 每个规格是否都等于「包数 × 单价」（Q55：**线性、无折扣**）。 */
    public boolean isLinear() {
        long unit = unitPackPrice();
        for (Map.Entry<Integer, Long> entry : packPrices.entrySet()) {
            if (entry.getValue() != unit * entry.getKey()) {
                return false;
            }
        }
        return true;
    }

    /** 该结果的金币奖励（【Q55】）。 */
    public long goldReward(Reward reward) {
        return goldRewards.get(reward);
    }

    /** 第一次完成游戏的额外奖励。 */
    public long firstGameBonus() {
        return firstGameBonus;
    }
}
