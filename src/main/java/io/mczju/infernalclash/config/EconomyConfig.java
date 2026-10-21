package io.mczju.infernalclash.config;

import io.mczju.infernalclash.policy.ExchangeMath;
import io.mczju.infernalclash.policy.PackRoller;
import io.mczju.infernalclash.policy.Rarity;
import io.mczju.infernalclash.policy.RarityTable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 已校验的经济配置（{@code economy.yml}）。
 *
 * <p><b>纯数据对象</b>：只依赖 {@code ConfigurationSection}，不碰服务端 ⇒
 * {@link #load(File)} 能在 JUnit 里离线校验**出厂文件**（见 {@code ShippedEconomyTest}）。
 *
 * <h2>★ 这里是「配置」与「算法」的交界</h2>
 * 本类**只负责**「键在不在、类型对不对、取值范围合不合理、有没有出现作废的口径」，
 * 并把值搬进 {@link #toRarityTable()} / {@link #toExchangeMath()} 返回的 **P0 纯对象**。
 * 玩法层的不变量（基础档权重必须 0、基础卡不可兑换、必须配置 1 包价……）
 * **由 P0 的构造器再判一次** —— 两道防线，谁也绕不过去。
 *
 * <h2>★ 为什么未知键要报错</h2>
 * 「未知键静默忽略」会让拼错的键（{@code pack-wieght}）**看起来配好了却毫无作用** ——
 * 与「缺键静默取默认值」是同一类事故。所以本类对未知的稀有度名 / 金币键一律硬失败。
 *
 * @param configVersion  配置结构版本号
 * @param rarities       逐档：颜色 / 抽包权重 / 金色概率（5 档齐全）
 * @param cardsPerPack   一包几张（R48 = 5）
 * @param packPrices     包数 → 总价（含 1 包；Q55 要求线性无折扣）
 * @param exchange       逐档兑换表（**只含 4 个非基础档**）
 * @param goldRewards    四种对局结果的金币奖励
 * @param firstGameBonus 第一次完成游戏的额外金币
 */
public record EconomyConfig(int configVersion,
                            Map<Rarity, RaritySpec> rarities,
                            int cardsPerPack,
                            Map<Integer, Long> packPrices,
                            Map<Rarity, ExchangeMath.Rates> exchange,
                            Map<ExchangeMath.Reward, Long> goldRewards,
                            long firstGameBonus) {

    /** 本配置对应的文件名（报错与迁移都用它）。 */
    public static final String FILE = "economy.yml";

    /** 货币量的上限：给个宽松但有限的天花板，防止手滑写出天文数字。 */
    private static final long MAX_AMOUNT = Long.MAX_VALUE / 4;

    /** 一个稀有度的三个配置值。 */
    public record RaritySpec(String color, int packWeight, double goldChance) {
    }

    /**
     * 金币段的键名 → 结果枚举。
     *
     * <p>★ 名字必须与 {@code docs/03} §6 的出厂文件一致；
     * 未知键会被拒绝（防止 {@code win_reward} 这种拼法静默失效）。
     */
    private static final Map<String, ExchangeMath.Reward> GOLD_KEYS = Map.of(
            "win-reward", ExchangeMath.Reward.PVP_WIN,
            "lose-reward", ExchangeMath.Reward.PVP_LOSS,
            "bot-win-reward", ExchangeMath.Reward.BOT_WIN,
            "bot-lose-reward", ExchangeMath.Reward.BOT_LOSS);

    private static final String FIRST_GAME_BONUS_KEY = "first-game-bonus";

    /** 从已解析的配置根节点加载并校验。 */
    public static EconomyConfig load(ConfigurationSection root) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "配置为空（文件不存在或解析失败）");
        }
        int version = ConfigReader.requireInt(FILE, root, "config-version", 1, 1_000_000);

        // ---------------- 稀有度 ----------------
        ConfigurationSection raritySection = ConfigReader.requireSection(FILE, root, "rarities");
        Map<Rarity, RaritySpec> rarities = new EnumMap<>(Rarity.class);
        for (String key : raritySection.getKeys(false)) {
            Rarity rarity = parseRarity(key, raritySection);
            ConfigurationSection entry = ConfigReader.requireSection(FILE, raritySection, key);
            rarities.put(rarity, new RaritySpec(
                    ConfigReader.requireString(FILE, entry, "color"),
                    ConfigReader.requireInt(FILE, entry, "pack-weight", 0, 1_000_000),
                    ConfigReader.requireDouble(FILE, entry, "gold-chance", 0.0, 1.0)));
        }
        for (Rarity rarity : Rarity.values()) {
            if (!rarities.containsKey(rarity)) {
                // 不猜默认值：5 档必须显式写全，否则「少了一档」会表现为抽取概率悄悄变化
                throw new ConfigError(FILE, "rarities." + rarity.name(),
                        "缺少该稀有度的配置（5 档都要显式给出，不猜默认值）");
            }
        }

        // ★ 口径回退防线：基础档**不进卡包**（R44「初始全部拥有」+ R49 的概率表只有 4 档）。
        //   这里先报一次（带文件与键路径，运维能直接定位）；
        //   RarityTable 的构造器里还有第二道 —— 谁也绕不过去。
        RaritySpec basic = rarities.get(Rarity.BASIC);
        if (basic.packWeight() != 0) {
            throw new ConfigError(FILE, "rarities.BASIC.pack-weight",
                    "必须是 0（实际 " + basic.packWeight() + "）：基础卡「初始全部拥有」（R44），"
                            + "不进卡包；R49 的概率表只有 4 档");
        }
        int totalWeight = 0;
        for (RaritySpec spec : rarities.values()) {
            totalWeight += spec.packWeight();
        }
        if (totalWeight <= 0) {
            throw new ConfigError(FILE, "rarities.*.pack-weight",
                    "所有稀有度的权重都是 0，卡包永远抽不出卡");
        }

        // ---------------- 卡包 ----------------
        ConfigurationSection pack = ConfigReader.requireSection(FILE, root, "pack");
        int cardsPerPack = ConfigReader.requireInt(FILE, pack, "cards-per-pack", 1, 64);

        ConfigurationSection priceSection = ConfigReader.requireSection(FILE, pack, "price");
        Map<Integer, Long> prices = new LinkedHashMap<>();
        for (String key : priceSection.getKeys(false)) {
            int packs = parsePackCount(key, priceSection);
            Long previous = prices.put(packs, ConfigReader.requireLong(FILE, priceSection, key, 0, MAX_AMOUNT));
            if (previous != null) {
                throw new ConfigError(FILE, priceSection.getCurrentPath() + "." + key,
                        "包数 " + packs + " 被配置了两次（注意 1 与 01 会被解析成同一个包数）");
            }
        }
        if (!prices.containsKey(1)) {
            throw new ConfigError(FILE, "pack.price.1",
                    "必须配置「1 包」的价格 —— 它是单价；未配置的包数不许按单价现算（Q55「线性、无折扣」）");
        }

        // ---------------- 兑换表 ----------------
        ConfigurationSection exchangeSection = ConfigReader.requireSection(FILE, root, "exchange");
        Map<Rarity, ExchangeMath.Rates> exchange = new EnumMap<>(Rarity.class);
        for (String key : exchangeSection.getKeys(false)) {
            Rarity rarity = parseRarity(key, exchangeSection);
            if (rarity == Rarity.BASIC) {
                throw new ConfigError(FILE, "exchange.BASIC",
                        "基础卡不在兑换表内（R25~R30 只有 4 行）：它「初始全部拥有」（R44），"
                                + "可分解就等于凭空产粉");
            }
            ConfigurationSection entry = ConfigReader.requireSection(FILE, exchangeSection, key);
            exchange.put(rarity, new ExchangeMath.Rates(
                    ConfigReader.requireLong(FILE, entry, "duplicate-powder", 0, MAX_AMOUNT),
                    ConfigReader.requireLong(FILE, entry, "duplicate-gold-powder", 0, MAX_AMOUNT),
                    ConfigReader.requireLong(FILE, entry, "unlock-price", 0, MAX_AMOUNT)));
        }
        for (Rarity rarity : Rarity.values()) {
            if (rarity != Rarity.BASIC && !exchange.containsKey(rarity)) {
                throw new ConfigError(FILE, "exchange." + rarity.name(),
                        "缺少该稀有度的兑换表（4 档都要显式给出，不猜默认值）");
            }
        }

        // ---------------- 金币 ----------------
        ConfigurationSection gold = ConfigReader.requireSection(FILE, root, "gold");
        Map<ExchangeMath.Reward, Long> rewards = new EnumMap<>(ExchangeMath.Reward.class);
        for (Map.Entry<String, ExchangeMath.Reward> e : GOLD_KEYS.entrySet()) {
            rewards.put(e.getValue(), ConfigReader.requireLong(FILE, gold, e.getKey(), 0, MAX_AMOUNT));
        }
        for (String key : gold.getKeys(false)) {
            if (!GOLD_KEYS.containsKey(key) && !FIRST_GAME_BONUS_KEY.equals(key)) {
                throw new ConfigError(FILE, "gold." + key,
                        "未知的金币键（合法键：" + new TreeMap<>(GOLD_KEYS).keySet()
                                + " 与 " + FIRST_GAME_BONUS_KEY + "）");
            }
        }
        long bonus = ConfigReader.requireLong(FILE, gold, FIRST_GAME_BONUS_KEY, 0, MAX_AMOUNT);

        return new EconomyConfig(version, Map.copyOf(rarities), cardsPerPack, Map.copyOf(prices),
                Map.copyOf(exchange), Map.copyOf(rewards), bonus);
    }

    /** 从磁盘上的 YAML 文件加载（**离线可用**，不依赖服务端）。 */
    public static EconomyConfig load(File file) {
        // ★ 走 YamlFile：loadConfiguration(File) 在解析失败时会调 Bukkit.getLogger() ⇒ 离线 NPE
        return load(YamlFile.read(FILE, file));
    }

    private static Rarity parseRarity(String name, ConfigurationSection where) {
        try {
            return Rarity.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ConfigError(FILE, where.getCurrentPath() + "." + name,
                    "未知的稀有度（合法值：" + List.of(Rarity.values()) + "）");
        }
    }

    private static int parsePackCount(String key, ConfigurationSection where) {
        int packs;
        try {
            packs = Integer.parseInt(key.trim());
        } catch (NumberFormatException e) {
            throw new ConfigError(FILE, where.getCurrentPath() + "." + key,
                    "包数必须是正整数（例：1 / 10 / 30）");
        }
        if (packs < 1) {
            throw new ConfigError(FILE, where.getCurrentPath() + "." + key,
                    "包数必须 >= 1：实际 " + packs);
        }
        return packs;
    }

    // ------------------------------------------------------------------ 转 P0 纯对象

    /** 该档的名称颜色（R51）。 */
    public String color(Rarity rarity) {
        return rarities.get(rarity).color();
    }

    /**
     * 转成 P0 的稀有度权重表。
     *
     * <p>★ 由它做**领域判定**：基础档权重必须为 0、总权重必须 &gt; 0。
     */
    public RarityTable toRarityTable() {
        Map<Rarity, Integer> weights = new EnumMap<>(Rarity.class);
        Map<Rarity, Double> goldChances = new EnumMap<>(Rarity.class);
        for (Rarity rarity : Rarity.values()) {
            RaritySpec spec = rarities.get(rarity);
            weights.put(rarity, spec.packWeight());
            goldChances.put(rarity, spec.goldChance());
        }
        return new RarityTable(weights, goldChances);
    }

    /**
     * 转成 P0 的经济算法层。
     *
     * <p>★ 由它做**领域判定**：必须配置 1 包价、四种奖励齐全、兑换表不含基础卡。
     */
    public ExchangeMath toExchangeMath() {
        return new ExchangeMath(exchange, packPrices, goldRewards, firstGameBonus);
    }

    /**
     * 用**卡池**组装抽包器（卡池由 {@code CardCatalog} 提供）。
     *
     * <p>★ 由它做**领域判定**：有权重的档位必须有卡（空池拒绝，不重新归一化概率）。
     */
    public PackRoller packRoller(Map<Rarity, List<String>> poolByRarity) {
        return new PackRoller(toRarityTable(), cardsPerPack, poolByRarity);
    }

    /** 启动日志用的一行摘要。 */
    public String describe() {
        return "稀有度 5 档 / 卡包 " + cardsPerPack + " 张 / 规格 " + new TreeMap<>(packPrices).keySet()
                + " / 首通奖励 " + firstGameBonus + " 金币 / 结构版本 " + configVersion;
    }
}
