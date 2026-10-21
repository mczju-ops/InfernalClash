package io.mczju.infernalclash.policy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 卡包抽取 —— **纯函数，可离线单测**（{@code docs/08} §3.2）。
 *
 * <h2>口径（用户给定，照抄）</h2>
 * <ul>
 *   <li>买一次 = **随机抽 5 张**（R48，配置键 {@code pack.cards-per-pack}）；</li>
 *   <li>每张的稀有度按权重抽（普通 50 / 稀有 30 / 史诗 15 / 传说 5，R49）；</li>
 *   <li>同一稀有度内**均匀随机**（【Q29】已裁定：**不做逐卡权重**）；</li>
 *   <li>每张**独立**判定金色（默认 5%，R50 / R52）。</li>
 * </ul>
 *
 * <h2>★ 随机数的消费顺序（改动它 = 改变同种子结果，必须同步改测试）</h2>
 * <ol>
 *   <li>{@code table.roll(random)} —— 抽稀有度（**恰好 1 个随机数**）；</li>
 *   <li>{@code random.nextInt(poolSize)} —— 该档内均匀取一张；</li>
 *   <li>{@code random.nextDouble() < goldChance} —— 金色判定（**每张独立**）。</li>
 * </ol>
 *
 * <h2>★ 空池 = 拒绝（fail-safe，不发明兜底分布）</h2>
 * 若某档的**权重 &gt; 0** 但卡池里**没有任何卡**，构造时**直接抛异常**。
 * 理由：此时唯一"能跑"的做法是重新归一化权重或重抽，而那会**悄悄改变用户定的
 * 50/30/15/5 概率表**。⇒ 正确处置是**在配置层启动时就拦下**（{@code docs/03} §5 的硬失败表），
 * 这个构造异常是**最后一道兜底**。
 *
 * <p>出厂值（5 张/包）由 {@link #SHIPPED_CARDS_PER_PACK} 提供；其余来自 {@code economy.yml}。
 */
public final class PackRoller {

    /** 出厂值：一包 5 张（R48，配置键 {@code pack.cards-per-pack}）。 */
    public static final int SHIPPED_CARDS_PER_PACK = 5;

    /**
     * 一次抽取的结果。
     *
     * @param cardId 卡牌 id（与 {@code cards/*.yml} 的文件名/键一致；
     *               {@code docs/08} §8.3 的存档字段就是 {@code Map<String, CardOwnership>}）
     * @param rarity 该卡所属稀有度（= 抽出它时命中的档位）
     * @param gold   是否为金色变种（R52-R55）
     */
    public record DrawnCard(String cardId, Rarity rarity, boolean gold) {
        public DrawnCard {
            if (cardId == null || cardId.isBlank()) {
                throw new IllegalArgumentException("卡牌 id 不能为空");
            }
            if (rarity == null) {
                throw new IllegalArgumentException("卡牌 " + cardId + " 的稀有度不能为空");
            }
        }
    }

    private final RarityTable table;
    private final int cardsPerPack;
    private final Map<Rarity, List<String>> poolByRarity;

    /**
     * @param table         稀有度权重表
     * @param cardsPerPack  一包几张（&gt;= 1）
     * @param poolByRarity  逐档卡池（**权重 &gt; 0 的档位必须有卡**）
     */
    public PackRoller(RarityTable table, int cardsPerPack, Map<Rarity, List<String>> poolByRarity) {
        if (table == null) {
            throw new IllegalArgumentException("稀有度权重表不能为空");
        }
        if (cardsPerPack < 1) {
            throw new IllegalArgumentException("pack.cards-per-pack 必须 >= 1：实际 " + cardsPerPack);
        }
        this.table = table;
        this.cardsPerPack = cardsPerPack;

        Map<Rarity, List<String>> pools = new EnumMap<>(Rarity.class);
        Set<String> seen = new HashSet<>();
        for (Rarity rarity : Rarity.values()) {
            List<String> raw = poolByRarity == null ? null : poolByRarity.get(rarity);
            List<String> pool = raw == null ? List.of() : List.copyOf(raw);

            for (String cardId : pool) {
                if (cardId == null || cardId.isBlank()) {
                    throw new IllegalArgumentException("稀有度 " + rarity + " 的卡池里出现了空 id");
                }
                if (!seen.add(cardId)) {
                    // 一张卡只能属于一个稀有度；重复 ⇒ 抽取概率与图鉴都会失真
                    throw new IllegalArgumentException(
                            "卡牌 id 重复登记：" + cardId + "（在稀有度 " + rarity + " 处第二次出现）");
                }
            }

            if (pool.isEmpty() && table.isDrawable(rarity)) {
                throw new IllegalArgumentException("稀有度 " + rarity + " 的 pack-weight = "
                        + table.weight(rarity) + "，但卡池里没有任何卡，拒绝抽包。"
                        + "请补齐该档卡牌，或把它的 pack-weight 配成 0");
            }
            pools.put(rarity, pool);
        }

        this.poolByRarity = Map.copyOf(pools);
    }

    /** 出厂张数（5 张/包）的便捷构造。 */
    public static PackRoller shipped(RarityTable table, Map<Rarity, List<String>> poolByRarity) {
        return new PackRoller(table, SHIPPED_CARDS_PER_PACK, poolByRarity);
    }

    /** 一包几张。 */
    public int cardsPerPack() {
        return cardsPerPack;
    }

    /** 某档卡池的大小（测试与日志用）。 */
    public int poolSize(Rarity rarity) {
        return poolByRarity.get(rarity).size();
    }

    /** 抽**一张**。随机数消费顺序见类注释。 */
    public DrawnCard drawOne(RandomGenerator random) {
        Rarity rarity = table.roll(random);
        List<String> pool = poolByRarity.get(rarity);
        String cardId = pool.get(random.nextInt(pool.size()));
        boolean gold = random.nextDouble() < table.goldChance(rarity);
        return new DrawnCard(cardId, rarity, gold);
    }

    /** 抽**一包**（默认 5 张，逐张独立判定）。 */
    public List<DrawnCard> drawPack(RandomGenerator random) {
        List<DrawnCard> drawn = new ArrayList<>(cardsPerPack);
        for (int i = 0; i < cardsPerPack; i++) {
            drawn.add(drawOne(random));
        }
        return List.copyOf(drawn);
    }
}
