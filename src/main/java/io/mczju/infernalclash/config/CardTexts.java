package io.mczju.infernalclash.config;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>卡牌文案覆盖层</b>（{@code card-texts.yml}）—— 用户 2026-10-19 的需求：
 * 「我想重新配置卡牌的**显示名称 + 描述 + 风味描述**，但是我不想一个一个文件点，
 * 你能帮我整合到一个配置文件里吗」。
 *
 * <h2>它管哪三个键</h2>
 * 和 {@code cards/<id>.yml} 里的同名键**一一对应**：
 * <ul>
 *   <li>{@code display-name} —— 物品名（卡牌物品 / 图鉴 / 消息里出现的名字）；</li>
 *   <li>{@code description} —— 物品 lore 的多行说明；</li>
 *   <li>{@code flavor} —— 风味一行。</li>
 * </ul>
 *
 * <h2>★ 口径：它是**覆盖层**（overrides），不是"另一个可选来源"</h2>
 * <ul>
 *   <li>文件里**有**这张卡 ⇒ 那三个键**以本文件为准**，{@code cards/<id>.yml} 里的同名键**不再被读**；</li>
 *   <li>文件里**缺**这张卡 ⇒ {@code CardCatalog} **拒绝启动**（不回落、不猜默认值）——
 *       回落会造成本项目最反感的那种事故：「改了没生效」，而且现场看起来"完全正常"；</li>
 *   <li>文件里出现**卡池里没有**的 id ⇒ 同样拒绝启动（拼错 id 的克星，
 *       与 {@code DisplayConfig} 的「未知键」同一条口径）。</li>
 * </ul>
 * ★ 因此出厂文件是**写全 51 张**的（{@code scripts/gen_card_texts.py} 生成），
 * 用户从此只需要改<b>这一个文件</b>；{@code cards/*.yml} 里的同名键退化为"历史默认值"。
 *
 * <h2>为什么不做成"把 cards/*.yml 的键删掉"</h2>
 * 那要动 51 个文件、并且要让 {@code CardCatalog} 容忍"卡里没有文案"，
 * 而<b>每一个测试夹具都是一段卡 YAML</b>（几十处）⇒ 收益（少三个键）远小于风险。
 * ⇒ 覆盖层是"同样的效果、小得多的改动面"。
 *
 * <p>★ 纯数据对象（只依赖 {@link ConfigurationSection}）⇒ 可离线测（{@code CardTextsTest}）。
 */
public final class CardTexts {

    /** 文件名（数据目录根下，与 {@code display.yml} 同级）。 */
    public static final String FILE = "card-texts.yml";

    /** 顶层段落名。 */
    public static final String SECTION = "cards";

    /** 每条文案允许的键（多一个就拒绝 —— 拼错的键不许静默失效）。 */
    private static final Set<String> ENTRY_KEYS =
            Set.of("display-name", "flavor", "description");

    /**
     * 一张卡的文案。
     *
     * @param displayName 物品名（MiniMessage；非空）
     * @param flavor      风味一行（MiniMessage；非空）
     * @param description 说明（MiniMessage 多行；至少一行）
     */
    public record Entry(String displayName, String flavor, List<String> description) {
    }

    private final int configVersion;
    private final Map<String, Entry> cards;

    private CardTexts(int configVersion, Map<String, Entry> cards) {
        this.configVersion = configVersion;
        this.cards = Collections.unmodifiableMap(new LinkedHashMap<>(cards));
    }

    // ------------------------------------------------------------------ 加载

    /** 从已解析的配置根节点加载并校验（离线可用）。 */
    public static CardTexts load(ConfigurationSection root) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "配置为空（文件不存在或解析失败）");
        }
        int version = ConfigReader.requireInt(FILE, root, "config-version", 1, 1_000_000);
        ConfigurationSection section = ConfigReader.requireSection(FILE, root, SECTION);

        Map<String, Entry> cards = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            String path = SECTION + "." + id;
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                throw new ConfigError(FILE, path,
                        "这张卡的文案要写成对象（display-name / flavor / description 三段），"
                                + "而不是一字符串");
            }
            for (String key : entry.getKeys(false)) {
                if (!ENTRY_KEYS.contains(key)) {
                    throw new ConfigError(FILE, path + "." + key,
                            "未知的键（每条文案只认 " + new java.util.TreeSet<>(ENTRY_KEYS)
                                    + "）—— 拼错的键不会静默失效，这里直接拒绝");
                }
            }
            String displayName = ConfigReader.requireString(FILE, entry, "display-name");
            String flavor = ConfigReader.requireString(FILE, entry, "flavor");
            List<String> description = ConfigReader.requireStringList(FILE, entry, "description");
            cards.put(id, new Entry(displayName, flavor, description));
        }
        if (cards.isEmpty()) {
            throw new ConfigError(FILE, SECTION,
                    "一张卡都没有 —— 覆盖层必须写全卡池（缺哪张就拒绝启动，绝不回落到 cards/*.yml）");
        }
        return new CardTexts(version, cards);
    }

    /** 从磁盘加载（**离线可用**：走 {@code YamlFile.read}，不碰 Bukkit 单例）。 */
    public static CardTexts load(File file) {
        return load(YamlFile.read(FILE, file));
    }

    /**
     * 文件不存在 ⇒ {@code null}（= 不启用覆盖层，用 {@code cards/*.yml} 自己的文案）。
     *
     * <p>★ 这是**唯一的回落入口**，且只在"文件根本不存在"时生效
     * （新装/迁移已把出厂文件写进去 ⇒ 正常运行永远走覆盖层）。
     * 文件存在但内容坏 ⇒ 照旧抛 {@link ConfigError}（拒绝启动）。
     */
    public static CardTexts loadOrNull(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        return load(file);
    }

    // ------------------------------------------------------------------ 取值

    /** 这张卡的文案（没有 ⇒ {@link Optional#empty()}）。 */
    public Optional<Entry> find(String cardId) {
        if (cardId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cards.get(cardId));
    }

    /** 文件里写了哪些卡 id（保序、不可变）。 */
    public Set<String> ids() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(cards.keySet()));
    }

    /** 条数。 */
    public int size() {
        return cards.size();
    }

    /** 结构版本。 */
    public int configVersion() {
        return configVersion;
    }

    /** 启动日志一行摘要。 */
    public String describe() {
        return size() + " 张卡的文案（结构版本 " + configVersion + "）";
    }
}
