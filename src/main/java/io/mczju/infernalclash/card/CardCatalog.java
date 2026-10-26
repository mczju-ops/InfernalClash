package io.mczju.infernalclash.card;

import io.mczju.infernalclash.config.CardTexts;
import io.mczju.infernalclash.config.ConfigError;
import io.mczju.infernalclash.config.ConfigReader;
import io.mczju.infernalclash.config.MapReader;
import io.mczju.infernalclash.config.YamlFile;
import io.mczju.infernalclash.policy.Rarity;
import io.mczju.infernalclash.policy.XpCurve;
import io.mczju.infernalclash.spell.EffectApplier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 卡牌目录：把 {@code cards/*.yml} 全部加载成 {@link Card}（{@code docs/03} §4）。
 *
 * <h2>★ 三条设计要点</h2>
 * <ol>
 *   <li><b>一张卡一个文件 + 「id 必须等于文件名」</b>：否则「按文件名找卡」与
 *       「按 id 找卡」会指向两张不同的卡 —— 这是最难查的一类配置事故（{@code docs/03} §4.1）。</li>
 *   <li><b>两遍加载</b>：第一遍解析全部卡牌，第二遍才校验**跨卡引用**
 *       （{@code CAST_SPELL} 指向的卡必须存在、且必须是法术卡）。
 *       单遍做不到 —— 被引用的卡可能还没读到。</li>
 *   <li><b>加载即拒绝</b>：任何一条不合格就抛 {@link ConfigError}，
 *       <b>没有"跳过这张卡继续跑"的选项</b>。理由是这套配置是**玩法本体**：
 *       少一张卡会让卡池数量、稀有度均衡、卡包概率全部错位，静默降级比拒绝启动更危险。
 *       ★【Q11】连**未知的效果参数键**也在这里拒绝（{@link #rejectUnknownEffectKeys}）——
 *       它以前只在施放时才炸，而"装错就拒绝启动"才是本项目的口径。</li>
 * </ol>
 *
 * <p>本类不碰服务端（只读 YAML 与枚举）⇒ 出厂 51 张卡可以在 JUnit 里整体加载
 * （见 {@code ShippedCardsTest}）。
 */
public final class CardCatalog {

    /** 卡牌子目录名（相对插件数据目录）。 */
    public static final String DIR = "cards";

    /**
     * 费用上限 = 经验等级上限（R21 = 10）。
     *
     * <p>★ 为什么必须校验：费用 &gt; 上限的卡**永远打不出来** ——
     * 那是一条"配置写错了但看起来一切正常"的死卡。
     */
    public static final int MAX_COST = (int) XpCurve.SHIPPED_MAX_LEVEL;

    /** 冷却上限（tick）：48 小时，纯粹是防手滑写出天文数字。 */
    private static final int MAX_COOLDOWN_TICKS = 3_456_000;

    /**
     * ★★ <b>效果参数的「键白名单」（装载期）</b>——【Q11】把未知键的发现**提前到装载期**。
     *
     * <p>★ <b>单一事实来源（{@code docs/11} §R12 的根治）</b>：本表<b>只</b>由
     * {@link EffectApplier#allowedKeys} 派生（见 {@link #runtimeEffectKeys()}），
     * 装载期<b>不再持有自己的键清单</b> —— 「哪一类效果认哪些键」只有一处可改。
     *
     * <p>⚠️ <b>为什么必须这样（R9 的实测缺陷）</b>：修 R9 之前这里取的是
     * 「{@code docs/03} §4.4 ∪ {@link EffectApplier#allowedKeys}」的<b>并集</b>，
     * 等于把文档也当成一处（第二处）事实来源。于是施放期白名单
     * {@code allowedKeys(SUMMON_DELAYED)} 少了一个 {@code count} 也照样能**装载**，
     * 出厂 {@code grave_digger.yml}（{@code count: 1}）/ {@code pheromone.yml}（{@code count: 20}）
     * 就成了「<b>能装载、一施放就失败</b>」，而装载期校验与 401 项离线测试全绿。
     * ⇒ 现在两处合一，并由 {@code ShippedEffectKeysVsRuntimeWhitelistTest}
     * （出厂 51 张卡 × 本表）把这条判据自动化。
     *
     * <p>⚠️ 只查<b>效果条目这一层</b>的键；{@code SUMMON}/{@code SUMMON_DELAYED} 的
     * {@code group:} <b>段内</b>子键（{@code entity}/{@code count}/{@code offset}/…）
     * 仍由施放期的 {@link EffectApplier#groupKeys()} 与 {@code MobGroupCatalog} 负责。
     */
    private static final Map<Spell.EffectType, Set<String>> EFFECT_KEYS = runtimeEffectKeys();

    /**
     * 装载期使用的允许键 = <b>施放期口径</b>（{@link EffectApplier#allowedKeys}）的只读快照。
     *
     * <p>★ 每类都转成 {@link TreeSet}，让报错里的「允许的键」有稳定顺序，便于比对与写测试。
     * ★ 本方法<b>不写死任何键名</b>：加一类新效果或给某类加参数，只改
     * {@link EffectApplier#allowedKeys} 一处（并同步 {@code docs/03} §4.4 —— 铁律 1）。
     */
    private static Map<Spell.EffectType, Set<String>> runtimeEffectKeys() {
        Map<Spell.EffectType, Set<String>> map = new EnumMap<>(Spell.EffectType.class);
        for (Spell.EffectType type : Spell.EffectType.values()) {
            map.put(type, Collections.unmodifiableSet(new TreeSet<>(EffectApplier.allowedKeys(type))));
        }
        return Collections.unmodifiableMap(map);
    }

    private final Map<String, Card> cards;

    private CardCatalog(Map<String, Card> cards) {
        // 保序（按 id 升序）：抽包与图鉴的遍历顺序必须"同输入同结果"，不能靠 HashMap 的运气
        this.cards = Collections.unmodifiableMap(new LinkedHashMap<>(cards));
    }

    /**
     * 从磁盘目录加载全部卡牌。
     *
     * @param dir       卡牌目录
     * @param mobGroups 实体组目录（用于校验 {@code summon.group[].entity} 的引用）
     * @throws ConfigError 目录不存在、没有任何卡、或任意一张卡非法
     */
    public static CardCatalog load(Path dir, MobGroupCatalog mobGroups) {
        return load(dir, mobGroups, null);
    }

    /**
     * ★★★ <b>带"文案覆盖层"的加载</b>（{@code card-texts.yml}；用户 2026-10-19：
     * 「我想重新配置卡牌的显示名称 + 描述 + 风味描述，但是我不想一个一个文件点」）。
     *
     * <p>语义见 {@code config/CardTexts} 的 javadoc：
     * <ul>
     *   <li>{@code texts == null} ⇒ 不启用覆盖层（<b>所有测试夹具走这条</b>，行为一字未变）；</li>
     *   <li>{@code texts != null} ⇒ 那三个键**只认**覆盖层；覆盖层缺一张卡 / 多一个未知 id
     *       ⇒ 当场 {@link ConfigError} 拒绝启动（不回落、不猜默认值）。</li>
     * </ul>
     *
     * @param dir       卡牌目录
     * @param mobGroups 实体组目录（用于校验 {@code summon.group[].entity} 的引用）
     * @param texts     文案覆盖层（{@code null} = 用 {@code cards/*.yml} 自己的文案）
     * @throws ConfigError 目录不存在、没有任何卡、任意一张卡非法、或文案覆盖层与卡池对不上
     */
    public static CardCatalog load(Path dir, MobGroupCatalog mobGroups, CardTexts texts) {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new ConfigError(DIR, "<dir>",
                    "卡牌目录不存在：" + (dir == null ? "null" : dir.toAbsolutePath()));
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ConfigError(DIR, "<dir>", "读取卡牌目录失败：" + e.getMessage());
        }
        if (files.isEmpty()) {
            throw new ConfigError(DIR, "<dir>", "卡牌目录里没有任何 .yml 文件（至少要有一张卡）");
        }

        Map<String, Card> parsed = new LinkedHashMap<>();
        List<SpellRef> spellRefs = new ArrayList<>();
        for (Path file : files) {
            // ★ 报错里的「文件」带上目录前缀（cards/xxx.yml）：运维才知道去哪找。
            //   实测教训：只写 sentry.yml 时，日志里看不出它在 cards/ 子目录下。
            String label = DIR + "/" + file.getFileName();
            Card card = parse(file, label, mobGroups, spellRefs);
            if (parsed.put(card.id(), card) != null) {
                throw new ConfigError(label, "id", "重复的卡牌 id：" + card.id());
            }
        }

        // ★★★ 2026-10-19：文案覆盖层（在跨卡引用那一道之前做 —— 它只改文本，不改 id/type）
        Map<String, Card> finalCards = texts == null ? parsed : applyCardTexts(parsed, texts);

        // 第二遍：跨卡引用（CAST_SPELL 的嵌套法术）
        for (SpellRef ref : spellRefs) {
            Card target = finalCards.get(ref.spellId());
            if (target == null) {
                throw new ConfigError(ref.file(), ref.keyPath(),
                        "引用了不存在的卡牌 " + ref.spellId() + "（嵌套法术必须是本项目已存在的法术卡）");
            }
            if (target.type() != Card.Type.SPELL) {
                throw new ConfigError(ref.file(), ref.keyPath(),
                        "引用的卡牌 " + ref.spellId() + " 不是法术卡（类型是 " + target.type() + "）");
            }
        }

        return new CardCatalog(finalCards);
    }

    /**
     * ★★ <b>把文案覆盖层套到已解析的卡上</b>（{@code card-texts.yml}；2026-10-19）。
     *
     * <h2>双向严格（与 {@code DisplayConfig} 同一条口径）</h2>
     * <ul>
     *   <li>覆盖层里有**卡池里没有**的 id ⇒ 拒绝启动（拼错 id 的克星）；</li>
     *   <li>卡池里有**覆盖层没写**的卡 ⇒ 拒绝启动（缺一张 = "改了没生效"，
     *       那种事故现场看起来完全正常，必须在这里死掉）。</li>
     * </ul>
     *
     * <p>★ 覆盖之后**重新**跑一遍 {@link #requireLegalTags}：否则把文案从卡 YAML 挪到覆盖层
     * 就绕过了 2026-10-18 那道「非法 MiniMessage 标签在装载期就拒绝」的保护
     * （用户实机报的「金卡文本随机出错」就是它拦下的）。
     */
    private static Map<String, Card> applyCardTexts(Map<String, Card> parsed, CardTexts texts) {
        for (String id : texts.ids()) {
            if (!parsed.containsKey(id)) {
                throw new ConfigError(CardTexts.FILE, CardTexts.SECTION + "." + id,
                        "未知的卡牌 id（卡池里没有这张卡）—— 合法值 = " + DIR
                                + "/ 目录下的文件名。已有 " + parsed.size() + " 张："
                                + new java.util.TreeSet<>(parsed.keySet()));
            }
        }
        Map<String, Card> out = new LinkedHashMap<>();
        for (Card card : parsed.values()) {
            CardTexts.Entry entry = texts.find(card.id()).orElse(null);
            String path = CardTexts.SECTION + "." + card.id();
            if (entry == null) {
                throw new ConfigError(CardTexts.FILE, path,
                        "缺少这张卡的文案 —— 覆盖层必须写全卡池（不回落、不猜默认值："
                                + "缺一张就表现为「改了没生效」，现场看起来完全正常）");
            }
            requireLegalTags(CardTexts.FILE, entry.displayName(), path + ".display-name");
            requireLegalTags(CardTexts.FILE, entry.flavor(), path + ".flavor");
            for (int i = 0; i < entry.description().size(); i++) {
                requireLegalTags(CardTexts.FILE, entry.description().get(i),
                        path + ".description[" + i + "]");
            }
            out.put(card.id(), new Card(card.id(), card.type(),
                    entry.displayName(), entry.flavor(), entry.description(),
                    card.icon(), card.cost(), card.cooldownTicks(), card.rarity(),
                    card.summon(), card.spell()));
        }
        return out;
    }

    /** 全部卡牌（按 id 升序）。 */
    public Map<String, Card> cards() {
        return cards;
    }

    /** 卡牌数量。 */
    public int size() {
        return cards.size();
    }

    /** 取一张卡；不存在则抛错（附「你是不是想写 X」的建议）。 */
    public Card require(String id) {
        Card card = cards.get(id);
        if (card == null) {
            throw new ConfigError(DIR, "cards." + id, "没有这张卡" + suggest(id));
        }
        return card;
    }

    /** 某稀有度的全部卡牌（按 id 升序）。 */
    public List<Card> ofRarity(Rarity rarity) {
        return cards.values().stream().filter(card -> card.rarity() == rarity).toList();
    }

    /**
     * 「稀有度 → 卡牌 id 列表」—— 直接喂给 {@code PackRoller} / {@code EconomyConfig.packRoller}。
     *
     * <p>★ 顺序稳定（按 id 升序）⇒「同种子 → 同抽包结果」跨重启成立。
     */
    public Map<Rarity, List<String>> poolByRarity() {
        Map<Rarity, List<String>> pool = new TreeMap<>();
        for (Rarity rarity : Rarity.values()) {
            pool.put(rarity, ofRarity(rarity).stream().map(Card::id).toList());
        }
        return Collections.unmodifiableMap(pool);
    }

    /** 启动日志用的一行摘要（逐档计数）。 */
    public String describe() {
        StringBuilder builder = new StringBuilder(cards.size() + " 张卡");
        for (Rarity rarity : Rarity.values()) {
            builder.append(" / ").append(rarity).append(' ').append(ofRarity(rarity).size());
        }
        return builder.toString();
    }

    /** 写错 id 时的建议（忽略大小写 / 包含关系）。 */
    public String suggest(String wrong) {
        if (wrong == null || wrong.isBlank()) {
            return "（已有 " + cards.size() + " 张卡）";
        }
        String lower = wrong.trim().toLowerCase();
        for (String id : cards.keySet()) {
            if (id.equalsIgnoreCase(lower)) {
                return "；你是不是想写 " + id + "（大小写不一致）？";
            }
        }
        for (String id : cards.keySet()) {
            if (id.contains(lower) || lower.contains(id)) {
                return "；你是不是想写 " + id + "？";
            }
        }
        return "（已有 " + cards.size() + " 张卡）";
    }

    // ------------------------------------------------------------------ 单卡解析

    /** 一次待校验的跨卡引用。 */
    private record SpellRef(String spellId, String file, String keyPath) {
    }

    private static Card parse(Path file, String fileName, MobGroupCatalog mobGroups,
                              List<SpellRef> spellRefs) {
        // ★ 用 YamlFile（自己读文本 + loadFromString）：loadConfiguration(File) 在解析失败时
        //   会去调 Bukkit.getLogger() ⇒ 离线 NPE 把真正的解析错误盖掉（2026-10-15 实测）
        YamlConfiguration yaml = YamlFile.read(fileName, file.toFile());

        // 注意：fileName 是**报错用的标签**（含 `cards/` 前缀），与文件名比对要用真正的文件名
        String baseName = stripExtension(file.getFileName().toString());
        String id = ConfigReader.requireString(fileName, yaml, "id");
        if (!id.equals(baseName)) {
            throw new ConfigError(fileName, "id",
                    "必须与文件名一致：文件名是 " + baseName + "，而 id 写成 " + id + ""
                            + "（不一致会让「按文件名找卡」与「按 id 找卡」指向两张不同的卡）");
        }

        Card.Type type = requireEnum(fileName, yaml, "type", Card.Type.class);
        Rarity rarity = requireEnum(fileName, yaml, "rarity", Rarity.class);

        // ★ 作废口径防线：嘲讽是布尔，等级键一律拒绝（2026-10-15 裁定）
        if (yaml.isSet("taunt-level") || yaml.isSet("summon.taunt-level")) {
            throw new ConfigError(fileName, yaml.isSet("taunt-level") ? "taunt-level" : "summon.taunt-level",
                    "嘲讽没有等级（2026-10-15 用户裁定「只有嘲讽和无嘲讽的区别」）：请改用布尔 taunt");
        }

        // ★ ★★ 1.1.0：`cost` 允许 **0**（用户逐字：「烈焰精灵改为**0费**召唤1只」）。
        //   0 = 不消耗经验；`CardCharge`/`chargeXp` 对 0 费的既有行为就是"不扣、放行"。
        int cost = ConfigReader.requireInt(fileName, yaml, "cost", 0, MAX_COST);
        int cooldownTicks = ConfigReader.requireInt(fileName, yaml, "cooldown-ticks", 0, MAX_COOLDOWN_TICKS);

        Card.Summon summon = null;
        Spell spell = null;
        if (type == Card.Type.SUMMON) {
            if (yaml.isSet("spell")) {
                throw new ConfigError(fileName, "spell", "召唤卡不能有 spell: 块（type: SUMMON）");
            }
            summon = parseSummon(fileName, yaml, mobGroups);
        } else {
            if (yaml.isSet("summon")) {
                throw new ConfigError(fileName, "summon", "法术卡不能有 summon: 块（type: SPELL）");
            }
            spell = parseSpell(fileName, yaml, spellRefs);
        }

        String displayName = ConfigReader.requireString(fileName, yaml, "display-name");
        String flavor = ConfigReader.requireString(fileName, yaml, "flavor");
        List<String> description = ConfigReader.requireStringList(fileName, yaml, "description");
        // ★★★ 2026-10-18：装载期校验 MiniMessage 标签合法性（用户报「金卡文本随机出错」的根治）。
        //   起因：出厂 10 张卡写了 `<purple>` —— MiniMessage **没有**这个命名色（只有
        //   `<light_purple>`）⇒ 未知标签被当纯文本保留 ⇒ 那 10 张卡的名字**字面显示**成
        //   `<purple>灵狐引路`。★ 而它看起来"完全随机"：取决于那张卡数据用了哪种标签。
        //   ⇒ 判据要在**能拒绝的最早时刻**拒绝（渲染期兜底只能"少坏一点"）。
        requireLegalTags(fileName, displayName, "display-name");
        requireLegalTags(fileName, flavor, "flavor");
        for (int i = 0; i < description.size(); i++) {
            requireLegalTags(fileName, description.get(i), "description[" + i + "]");
        }

        return new Card(id, type,
                displayName, flavor, description,
                ConfigReader.requireString(fileName, yaml, "icon"),
                cost, cooldownTicks, rarity, summon, spell);
    }

    /**
     * ★★★ <b>这段文案里的 MiniMessage 标签是否全部合法</b>（非法 ⇒ 拒绝启动）。
     *
     * <p>判据在纯类 {@link io.mczju.infernalclash.policy.MiniMessageTagRule}，
     * 它的白名单与召唤物名牌**共用同一份**（{@code SideNameColor.isColorOrStyleTag}）
     * —— 否则会出现"名牌认这个标签、卡名不认"的第二处事实来源。
     */
    private static void requireLegalTags(String file, String text, String keyPath) {
        List<String> illegal = io.mczju.infernalclash.policy.MiniMessageTagRule.illegalTags(text);
        if (illegal.isEmpty()) {
            return;
        }
        throw new ConfigError(file, keyPath,
                "有 MiniMessage 认不出的标签：" + io.mczju.infernalclash.policy.MiniMessageTagRule
                        .describe(illegal)
                        + "　，未知标签会被当纯文本显示（玩家会看到尖括号），"
                        + "所以这里直接拒绝启动。合法命名色只有 16 个："
                        + "black / dark_blue / dark_green / dark_aqua / dark_red / dark_purple / "
                        + "gold / gray / dark_gray / blue / green / aqua / red / light_purple / "
                        + "yellow / white（注意是 light_purple，不是 purple）");
    }

    // ------------------------------------------------------------------ 骑乘（group[].mount）

    /**
     * ★★ <b>{@code group[].mount}</b> —— 这一组要骑上去的<b>载具组的实体类型名</b>
     * （2026-10-17 批次 3 后半新增）。
     *
     * <p>出处：{@code docs/03} §4.6 早就登记的「载具配对：两个 {@code group} 之间没有骑乘关系
     * ⇒ P4 加 {@code group[].mount}」，＋ {@code docs/19} 第 106 / 109 / 152 行的三张骑乘卡，
     * ＋ 用户第 8 条裁定「<b>骑乘要实现</b>」。
     *
     * <p>★ <b>取值 = 实体类型名</b>（如 {@code STRIDER}）而不是"第几组"那种序号：
     * 序号会在插组时静默错位，而实体名写错会被 {@link #validateMounts} 当场抓出来。
     * 大小写不敏感（统一转大写存）。
     */
    private static String optionalMount(String file, Map<?, ?> entry, String path) {
        if (!MapReader.isSet(entry, "mount")) {
            return null;
        }
        String raw = MapReader.requireString(file, entry, path, "mount").trim().toUpperCase(Locale.ROOT);
        if (raw.isEmpty()) {
            throw new ConfigError(file, path + ".mount",
                    "mount 不能是空串（不骑任何东西就整个键都不写）");
        }
        if (!MOUNT_NAME_PATTERN.matcher(raw).matches()) {
            throw new ConfigError(file, path + ".mount",
                    "" + raw + " 不是合法的实体类型名（应形如 STRIDER / ZOMBIE_HORSE）");
        }
        return raw;
    }

    /**
     * ★ <b>跨组校验</b>：{@code mount} 指向的组必须真的存在，且不能指向自己。
     *
     * <p>★ 为什么必须在<b>装载期</b>拒绝：写错一个实体的名字，运行期的表现是
     * 「僵尸猪灵<b>没有</b>骑上炽足兽」—— 那看起来和"骑乘功能没做"一模一样，
     * 而实机核对的人只会以为"这功能还没生效"。当场报错才能避免这种静默。
     */
    private static void validateMounts(String file, List<Card.Group> groups) {
        for (int index = 0; index < groups.size(); index++) {
            Card.Group group = groups.get(index);
            if (group == null || !group.ridesSomething()) {
                continue;
            }
            String path = "summon.group[" + index + "].mount";
            if (group.mount().equals(group.entity())) {
                throw new ConfigError(file, path, "" + group.mount()
                        + " 就是这一组自己的实体 —— 一组召唤物不能骑自己");
            }
            boolean found = false;
            for (Card.Group candidate : groups) {
                if (candidate != null && group.mount().equals(candidate.entity())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new ConfigError(file, path, "这一组要骑的 " + group.mount()
                        + " 不在本卡的 summon.group 里（载具必须自己也作为一组声明，"
                        + "且写在乘客组之前 —— 本卡的组 = " + entitiesOf(groups) + "）");
            }
        }
    }

    /**
     * ★★★ 读 {@code group[].rows}（方阵排布）—— 2026-10-18 新增（用户第 4 条，逐字：
     * 「**虫群的末影螨不应站成一排，而是站成 3-4-3 的居中对称方阵**」）。
     *
     * <h2>装载期校验（两条，都是"改了方阵忘了改数量"的克星）</h2>
     * <ol>
     *   <li>必须是<b>整数列表</b>，每一项 ≥ 0（{@code 0} 表示这一行空着，用来表达"3-0-3"这种对称形状）；</li>
     *   <li>各行之和<b>必须等于 {@code count}</b> —— 两个数各写一遍是刻意的：
     *       少了这一条，写错时只会"少生成两只"或"多出来的一只站在原点"，没有人会发现。</li>
     * </ol>
     *
     * @return 逐行人数；没写这一格 ⇒ <b>空表</b>（= 老的"沿右方向站成一排"）
     */
    private static List<Integer> rows(String file, Map<?, ?> entry, String path, int count) {
        if (!MapReader.isSet(entry, "rows")) {
            return List.of();
        }
        Object raw = entry.get("rows");
        if (!(raw instanceof List<?> list)) {
            throw new ConfigError(file, path + ".rows",
                    "rows 必须是整数列表（例如 rows: [3, 4, 3]）；实际是 " + raw);
        }
        List<Integer> rows = new ArrayList<>(list.size());
        int total = 0;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new ConfigError(file, path + ".rows[" + i + "]",
                        "每一行必须是整数（人数）；实际是 " + item);
            }
            int inRow = number.intValue();
            if (inRow < 0) {
                throw new ConfigError(file, path + ".rows[" + i + "]",
                        "每行人数不能为负（0 = 这一行空着）；实际 " + inRow);
            }
            rows.add(inRow);
            total += inRow;
        }
        if (rows.isEmpty()) {
            throw new ConfigError(file, path + ".rows",
                    "rows 是空列表，请整段删掉（不写 = 沿右方向站成一排）");
        }
        if (total != count) {
            throw new ConfigError(file, path + ".rows",
                    "各行人数之和必须等于 count：实际 " + total + " ≠ " + count
                            + "。两个数各写一遍是刻意的 —— 它让「改了方阵忘了改数量」"
                            + "当场报错，而不是悄悄少生成 / 多生成几只");
        }
        return List.copyOf(rows);
    }

    /**
     * ★★★ 读 {@code group[].formation}（<b>成形排布</b>）—— 2026-10-18 新增。
     *
     * <h2>用户原话（逐字）</h2>
     * 「**可以。我希望这五个猪灵最好能站成一个类似正五边形**」（{@code piglin_squad}）。
     *
     * <h2>装载期校验（两条）</h2>
     * <ol>
     *   <li>★ 与 {@code rows}（方阵）<b>互斥</b>：同时写 ⇒ <b>拒绝启动</b>并同时点名两个键
     *       —— 两种排布叠在一起没有任何定义，静默选一个的表现是"配置写了却没生效"；</li>
     *   <li>{@code PENTAGON} 要求 {@code count == 5}：写了"正五边形"却给 4 / 6 只，
     *       按 {@code count} 画会得到"四边形 / 六边形"，而配置上还写着 PENTAGON
     *       —— 那是本次缺陷（"站着站着不是五边形"）的同一类，直接报出来。</li>
     * </ol>
     *
     * @return 成形排布；没写这一格 ⇒ {@code null}（= 老行为：方阵或站成一排）
     */
    private static Card.Formation formation(String file, Map<?, ?> entry, String path, int count,
                                            List<Integer> rows) {
        if (!MapReader.isSet(entry, "formation")) {
            return null;
        }
        if (rows != null && !rows.isEmpty()) {
            throw new ConfigError(file, path + ".formation",
                    "formation 与 rows 只能写一个（一个是成形排布、一个是方阵排布）。"
                            + "同时写没有定义，静默选一个只会让人以为「配置没生效」"
                            + "，请把 rows 删掉再写 formation（或反过来）");
        }
        Card.Formation formation = parseEnum(file,
                MapReader.requireString(file, entry, path, "formation"),
                path + ".formation", Card.Formation.class);
        if (formation == Card.Formation.PENTAGON && count != 5) {
            throw new ConfigError(file, path + ".formation",
                    "formation: PENTAGON 要求这一组的 count 恰好是 5（实际 " + count
                            + "）。正五边形的定义就是 5 个顶点 —— 按 " + count
                            + " 画会得到一个不是五边形的形状，而配置上还写着 PENTAGON");
        }
        return formation;
    }

    /** 本卡所有组的实体名（报错信息里用）。 */
    private static String entitiesOf(List<Card.Group> groups) {        List<String> names = new ArrayList<>(groups.size());        for (Card.Group group : groups) {
            names.add(group == null ? "<null>" : group.entity());
        }
        return String.valueOf(names);
    }

    /** 实体类型名的形态（与附魔键同一个「原版裸键」形态）。 */
    private static final java.util.regex.Pattern MOUNT_NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * ★★★ <b>解析 {@code group[].abilities}</b>（1.1.0 新增，巫术壁垒）。
     *
     * <p>★ 为什么"逐组"：那张卡是「史莱姆 ＋ 骑手」，两者能力完全不同
     * （史莱姆：40 血 + 凋零 I + 不能分裂；骑手：24 血 + 每 3 秒一颗 20 点火球），
     * 而卡级 {@code summon.abilities} 会同时挂到两支身上 ⇒ 史莱姆也会放火球。
     *
     * <p>★ 实现上直接复用 {@link #parseAbilities} —— 用一个只装了这一格的内存配置包一层，
     * 从而**不出现第二份能力解析器**（这份键表/校验/报错文案只有一处）。
     */
    private static List<Card.Ability> parseGroupAbilities(String file, Map<?, ?> entry) {
        if (!MapReader.isSet(entry, "abilities")) {
            return List.of();
        }
        org.bukkit.configuration.MemoryConfiguration section =
                new org.bukkit.configuration.MemoryConfiguration();
        section.set("abilities", entry.get("abilities"));
        return parseAbilities(file, section);
    }

    private static Card.Summon parseSummon(String file, ConfigurationSection root,
                                          MobGroupCatalog mobGroups) {
        ConfigurationSection summon = ConfigReader.requireSection(file, root, "summon");

        List<Map<?, ?>> rawGroups = summon.getMapList("group");
        if (rawGroups.isEmpty()) {
            throw new ConfigError(file, "summon.group", "至少要有一组召唤物（- entity: ...）");
        }

        List<Card.Group> groups = new ArrayList<>(rawGroups.size());
        for (int i = 0; i < rawGroups.size(); i++) {
            Map<?, ?> entry = rawGroups.get(i);
            String path = "summon.group[" + i + "]";
            String entity = MapReader.requireString(file, entry, path, "entity").toUpperCase();
            if (!mobGroups.has(entity)) {
                throw new ConfigError(file, path + ".entity",
                        "mob-groups.yml 里没有实体组 " + entity + "" + mobGroups.suggest(entity));
            }
            int count = MapReader.requireInt(file, entry, path, "count", 1, 64);
            int forward = 0;
            int right = 0;
            if (MapReader.isSet(entry, "offset")) {
                Map<?, ?> offset = MapReader.requireMap(file, entry, path, "offset");
                forward = MapReader.requireInt(file, offset, path + ".offset", "forward", -16, 16);
                right = MapReader.requireInt(file, offset, path + ".offset", "right", -16, 16);
            }
            // ★ 方阵与成形排布**互斥**：两个都读出来再判（这样报错能同时点名两个键）
            List<Integer> rowSpec = rows(file, entry, path, count);
            // ★★ 2026-10-18：存活期（`group[].lifetime-ticks`）—— 悦灵「召唤后 40s 直接死亡」
            //   长期没有可写的位置，键名在 docs/03 §7 早就定名，这里才真正接上。
            //   ★ 上限 72000 tick = 1 小时：比任何一局都长得多，只用来拦住手滑写出的天文数字。
            Integer lifetimeTicks = MapReader.isSet(entry, "lifetime-ticks")
                    ? MapReader.requireInt(file, entry, path, "lifetime-ticks", 1, 72000)
                    : null;
            // ★★★ 1.1.0（巫术壁垒）：「只有史莱被算作建筑」⇒ 逐组覆盖卡级 `summon.building`。
            //   ★ null = 跟随卡级（其余 62 张卡行为一字未变）。
            Boolean groupBuilding = MapReader.isSet(entry, "building")
                    ? MapReader.requireBoolean(file, entry, path, "building")
                    : null;
            // ★★ 1.1.0（实机缺陷）：「史莱姆死后骑手悬在天上动不了」⇒ 逐组站桩。
            //   ★ null = 跟随卡级（其余 62 张卡行为一字未变）。
            Boolean groupStationary = MapReader.isSet(entry, "stationary")
                    ? MapReader.requireBoolean(file, entry, path, "stationary")
                    : null;
            groups.add(new Card.Group(entity, count, forward, right,
                    MobGroupCatalog.spreadOf(file, entry, path),
                    readGroupAttributes(file, entry, path),
                    optionalMount(file, entry, path),
                    rowSpec,
                    formation(file, entry, path, count, rowSpec),
                    lifetimeTicks,
                    groupBuilding,
                    // ★★★ 1.1.0（巫术壁垒）：逐组能力 —— 史莱姆与骑手的能力完全不同，
                    //   卡级 abilities 会把两边都挂上（烈焰熔炉里 SUMMON 效果的 group 早就有同一格）。
                    parseGroupAbilities(file, entry),
                    groupStationary));
        }
        validateMounts(file, groups);

        Card.Placement placement = Card.Placement.GROUND_OR_SELF;
        if (summon.isSet("placement")) {
            placement = requireEnum(file, summon, "placement", Card.Placement.class);
        }

        // ★ 嘲讽：布尔，缺省 false（「召唤物默认无嘲讽」，docs/02 §8.2）
        boolean taunt = summon.isSet("taunt") && ConfigReader.requireBoolean(file, summon, "taunt");

        // ★★★ 2026-10-17 第十二轮（缺陷 P1-a「攻城兽不去打带嘲讽的塔」）：
        //   「**这个召唤物**选目标时优不优先打建筑」—— 与 taunt **同层**的可选布尔。
        //   ★ 缺省 false = 无视优先目标（【Q6】读法 A / R37「绝大部分召唤物无视优先目标」）
        //     ⇒ 没写这一格的 49 张卡行为**一字未变**（这是加键时最重要的性质）。
        //
        // ★★★ 2026-10-19 用户裁定：这一档**改名**为「优先攻击**建筑**」
        //   ⇒ 新键 `affected-by-building`（旧键 `affected-by-taunt` 仍被接受，见下）。
        //   ★ 为什么旧键不能直接删（`docs/03` §5.3 的同一条教训）：现场文件是**只增不改**的，
        //     运维手上那份卡里还写着旧键 ⇒ 直接删键 = 那一格**静默失效**（`summon` 段没有
        //     严格的未知键校验，写了不会报错）⇒ 表现成「这张卡忽然不优先打塔了」。
        //   ⇒ 两个键都读：新键优先；只有旧键时**照旧生效**并告警一次提醒改名。
        boolean affectedByBuilding = summon.isSet("affected-by-building")
                ? ConfigReader.requireBoolean(file, summon, "affected-by-building")
                : summon.isSet("affected-by-taunt")
                        && ConfigReader.requireBoolean(file, summon, "affected-by-taunt");

        // ★★★ 2026-10-17 第十三轮批次 3 收尾（用户原话：「**不需要摘掉射箭 goal，
        //   只需要拦截射箭事件就行。需要它射箭时的走位**」）：
        //   「这个召唤物**发出的远程攻击**要不要被拦掉」—— 与 taunt 同层的可选布尔。
        //   ★ 缺省 false = 照常射（51 张卡里 50 张是这一档，行为**一字未变**）；
        //   ★ 出厂只有**冰术师**写 true（docs/19 第 174 行「手持弓但**不射箭**」）。
        boolean blockRanged = summon.isSet("block-ranged")
                && ConfigReader.requireBoolean(file, summon, "block-ranged");

        // ★★ 2026-10-17 批次 3 收尾：铜傀儡「涂蜡防氧化」
        //   （docs/19 第 176 行蒸汽精灵的坑那一栏：「★ 铜傀儡**涂蜡防氧化**
        //   （CopperGolem#setOxidizing，API 已核实）」）—— 同层可选布尔，缺省 false。
        boolean waxed = summon.isSet("waxed")
                && ConfigReader.requireBoolean(file, summon, "waxed");

        // ★★ 2026-10-17 批次 3 收尾（用户第 4 条「做前储君速度曲线」）：
        //   「未攻击时马每 2s +20% 速度、上限 100%，攻击到敌人时清零」
        //   （docs/19 第 106 行）—— 同层可选布尔，缺省 false（只有前储君写 true）。
        boolean speedRamp = summon.isSet("speed-ramp")
                && ConfigReader.requireBoolean(file, summon, "speed-ramp");

        // ★★★ 2026-10-18（用户：「很多『无法移动』的卡牌的效果依旧没有实现」）：
        //   `stationary: true` ⇒ 生成后 `setAI(false)`（不移动、不主动射击）。
        //   出处 = docs/19 逐字写「无法移动」的 5 张：sentry / frog_parasite /
        //   blaze_furnace / allay / blaze_guard。
        boolean stationary = summon.isSet("stationary")
                && ConfigReader.requireBoolean(file, summon, "stationary");

        Card.Attributes attributes = summon.isSet("attributes")
                ? MobGroupCatalog.readAttributes(file, ConfigReader.requireSection(file, summon, "attributes"))
                : Card.Attributes.NONE;

        List<Card.Ability> abilities = parseAbilities(file, summon);
        Card.Equipment equipment = parseEquipment(file, summon);

        // ★★★ 2026-10-18（烈焰守卫的另外三格，用户第 6 条之后「继续」的落点）：
        //   出处 = docs/19 第 113 行逐字：「索敌为**半径 12 的圆形**，**第一次锁定后不再改变
        //   目标**（LOCK_ON）直到被眩晕或目标死亡；锁定后对该目标的伤害**每 0.5s +1**
        //   （从 1 起、上限每 0.5s 10 点），**重新索敌时重置**」。
        //   ⇒ 三个键：`targeting`（选敌模式）/ `target-radius`（索敌半径）/ `ramping-damage`（递增伤害）。
        //   ★ 缺省值刻意让**其余 50 张卡行为一字未变**：SWITCH / 不限半径 / 没有递增伤害。
        io.mczju.infernalclash.policy.TargetPolicy.Mode targeting =
                io.mczju.infernalclash.policy.TargetPolicy.Mode.SWITCH;
        if (summon.isSet("targeting")) {
            targeting = requireEnum(file, summon, "targeting",
                    io.mczju.infernalclash.policy.TargetPolicy.Mode.class);
        }
        Double targetRadius = summon.isSet("target-radius")
                ? (double) ConfigReader.requireInt(file, summon, "target-radius", 1, 256)
                : null;
        Card.RampDamage rampDamage = parseRampDamage(file, summon);
        // ★★★ 2026-10-18：**轰炸机**（键 `summon.bomber`，缺省 false）
        //   出处 = docs/19 第 110 行怨恨轰炸机「无法通过吐火球攻击，只会飞行到目标上方、
        //   然后垂直向下发射火球」。★ 恶魂的吐火球**不经过** EntityShootBowEvent ⇒
        //   `block-ranged` 拦不到它 ⇒ 必须由我们关掉原版 AI 并自己飞（见 battle/BomberFlightService）。
        boolean bomber = summon.isSet("bomber")
                && ConfigReader.requireBoolean(file, summon, "bomber");
        // ★★★ 2026-10-18（用户第 2 条：「**死灵法师锁定后应静止（远程怪跑去近战送）**」）：
        //   `stop-when-target-within: N` ⇒ 目标进 N 格（含边线）就站定，但照常索敌/攻击。
        //   ★ N 不是我的判断：我追问时用户答「**N 等于它的火球索敌射程**」
        //     ⇒ 死灵法师写 8（它就是 `radius: 8` 的那颗火球）。
        //   ★ 缺省 null = 老行为（会一直走到目标身边），50/51 张卡是这一档。
        Double stopWhenTargetWithin = summon.isSet("stop-when-target-within")
                ? (double) ConfigReader.requireInt(file, summon, "stop-when-target-within", 1, 256)
                : null;
        // ★★★ 2026-10-19 用户裁定（逐字）：「**新增 yaml 键**」（问的是「建筑判定从哪来」）。
        //   `summon.building: true` = 这个召唤物算「建筑」——**纯标签**，
        //   只被「谁优先打它」与「谁不能治疗它」两类判据读（见 Card.Summon#building()）。
        //   ★ 缺省 false ⇒ 未写的 45 张卡行为一字未变。
        boolean building = summon.isSet("building")
                && ConfigReader.requireBoolean(file, summon, "building");
        // ★★★ 2026-10-21 用户裁定（逐字）：「劲弩手…**拦截射击行为**并**每 0.5 秒发射从本体
        //   引向目标的穿透射线，对所有贯穿的敌人造成 2 点伤害，使用紫色射线粒子**」
        //   ⇒ `summon.ray`（块，四把键；`groups` 可选 ⇒ 只给某一组打射线）。
        Card.Ray ray = parseRay(file, summon);
        // ★★★ 2026-10-21 用户裁定（逐字）：「唤魔者（原版的尖牙很容易空）：**拦截召唤尖牙行为**，
        //   每 3 秒先使目标眩晕 1 秒，同时在目标处召唤尖牙」
        //   ⇒ `summon.fang-attack: true`：生成时摘掉原版唤魔者的**尖牙施法 goal**
        //     （`VanillaGoal.EVOKER_ATTACK_SPELL`），改由卡里的 abilities 驱动。
        boolean fangAttack = summon.isSet("fang-attack")
                && ConfigReader.requireBoolean(file, summon, "fang-attack");
        // 2026-10-21（用户：「让蒸汽精灵（铜傀儡）外观变成**锈蚀且涂蜡**的样子」）：
        //   `summon.oxidized: true` ⇒ 召唤时把铜傀儡的风化状态设成 OXIDIZED（涂蜡仍由 `waxed` 管）。
        boolean oxidized = summon.isSet("oxidized")
                && ConfigReader.requireBoolean(file, summon, "oxidized");
        // ★★★ 1.1.0：卡级特殊机制（见 Card.Summon.Extra 的 javadoc，逐格有出处）。
        Card.Summon.Extra extra = parseExtra(file, summon);
        return new Card.Summon(groups, placement, taunt, affectedByBuilding, attributes, abilities,
                equipment, blockRanged, waxed, speedRamp, stationary, targeting, targetRadius,
                rampDamage, bomber, stopWhenTargetWithin, building, ray, fangAttack, oxidized,
                extra);
    }

    /**
     * ★★★ <b>解析 1.1.0 的「卡级特殊机制」</b>（{@code summon.powered} / {@code hover} /
     * {@code deploy} / {@code web} / {@code shadow-step} / {@code bomber-*}）。
     *
     * <h2>口径（与 {@code summon.ray} 同一套严格风格）</h2>
     * <ul>
     *   <li>每一格都<b>可选</b>；不写 ⇒ {@link Card.Summon.Extra#NONE}（= 1.0.0 的行为）；</li>
     *   <li>块内键名<b>严格校验未知键</b>（写错一个字母就拒绝启动 —— 静默失效最难查）；</li>
     *   <li>数值有上下界（时长 1~1200 tick、悬浮 0.1~8 格、半径 1~64 格、伤害 0.5~100）。</li>
     * </ul>
     */
    private static Card.Summon.Extra parseExtra(String file, ConfigurationSection summon) {
        boolean powered = summon.isSet("powered")
                && ConfigReader.requireBoolean(file, summon, "powered");
        Double hover = summon.isSet("hover")
                ? ConfigReader.requireDouble(file, summon, "hover", 0.1, 8.0)
                : null;
        Double shadowStep = summon.isSet("shadow-step")
                ? ConfigReader.requireDouble(file, summon, "shadow-step", 1.0, 64.0)
                : null;
        Card.Summon.Deploy deploy = null;
        if (summon.isSet("deploy")) {
            ConfigurationSection section = ConfigReader.requireSection(file, summon, "deploy");
            rejectUnknownKeys(file, section, "summon.deploy", Set.of("ticks", "sound"));
            deploy = new Card.Summon.Deploy(
                    ConfigReader.requireInt(file, section, "ticks", 1, 1200),
                    section.isSet("sound")
                            ? ConfigReader.requireString(file, section, "sound")
                            : null);
        }

        Double bomberDamage = summon.isSet("bomber-damage")
                ? ConfigReader.requireDouble(file, summon, "bomber-damage", 0.5, 100.0)
                : null;
        Integer bomberInterval = summon.isSet("bomber-interval-ticks")
                ? ConfigReader.requireInt(file, summon, "bomber-interval-ticks", 1, 200)
                : null;
        String bomberParticle = summon.isSet("bomber-particle")
                ? ConfigReader.requireString(file, summon, "bomber-particle")
                : null;
        if (bomberParticle != null) {
            try {
                org.bukkit.Particle.valueOf(bomberParticle.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ConfigError(file, "summon.bomber-particle",
                        "不是合法的粒子名：" + bomberParticle);
            }
        }
        return new Card.Summon.Extra(powered, hover, deploy, shadowStep, bomberDamage,
                bomberInterval, bomberParticle);
    }

    /** 块内的未知键一律拒绝（同 {@code summon.ray} 的做法）。 */
    private static void rejectUnknownKeys(String file, ConfigurationSection section,
                                          String path, Set<String> allowed) {
        for (String key : section.getKeys(false)) {
            if (!allowed.contains(key)) {
                throw new ConfigError(file, path + "." + key,
                        "未知键（允许的：" + String.join(" / ", new TreeSet<>(allowed)) + "）");
            }
        }
    }

    /**
     * ★★ <b>解析可选的 {@code summon.ray} 块</b>（穿透射线攻击；2026-10-21 新增）。
     *
     * <p>四把键（与 {@code summon.ramping-damage} 同一套严格校验风格）：
     * {@code damage} / {@code interval-ticks} / {@code particle} 必填，
     * {@code groups} 可选（实体组键列表；不写 = 整张卡每一组）。
     *
     * <p>★ 严格拒绝未知键：写在块里的错键会表现成「射线没生效」而查不出原因
     * （本项目「第二处白名单」的教训）。
     */
    private static Card.Ray parseRay(String file, ConfigurationSection summon) {
        if (!summon.isSet("ray")) {
            return null;
        }
        ConfigurationSection section = ConfigReader.requireSection(file, summon, "ray");
        Set<String> allowed = new TreeSet<>(List.of("damage", "interval-ticks", "particle", "groups", "range"));
        for (String key : section.getKeys(false)) {
            if (!allowed.contains(key)) {
                throw new ConfigError(file, section.getCurrentPath() + "." + key,
                        "ray 只认 " + allowed + "（写错的键会表现成「射线没生效」）");
            }
        }
        double damage = ConfigReader.requireDouble(file, section, "damage", 0.5, 1000.0);
        int interval = ConfigReader.requireInt(file, section, "interval-ticks", 1, MAX_COOLDOWN_TICKS);
        String particle = ConfigReader.requireString(file, section, "particle").trim().toUpperCase(Locale.ROOT);
        if (!MATERIAL_PATTERN.matcher(particle).matches()) {
            throw new ConfigError(file, section.getCurrentPath() + ".particle",
                    "" + particle + " 不是合法的粒子常量名（全大写，例如 PORTAL / DRAGON_BREATH）");
        }
        List<String> groups = new ArrayList<>();
        if (section.isSet("groups")) {
            for (String raw : section.getStringList("groups")) {
                String group = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
                if (group.isEmpty()) {
                    throw new ConfigError(file, section.getCurrentPath() + ".groups",
                            "groups 里不能有空串（写组键名，例如 PILLAGER）");
                }
                groups.add(group);
            }
        }
        // ★★★ 2026-10-21（用户：「这三张卡释放技能的距离都应该是 8 格」）：
        //   `range` = **射线长度上限 + 施法距离**（缺省 `Card.Ray.DEFAULT_RANGE` = 8）。
        //   ★ 它不是 `summon.target-radius`：后者收紧的是索敌，会让"8 格内没目标，原地不动"。
        int range = section.isSet("range")
                ? ConfigReader.requireInt(file, section, "range", 1, 256)
                : Card.Ray.DEFAULT_RANGE;
        try {
            return new Card.Ray(damage, interval, particle, groups, range);
        } catch (IllegalArgumentException e) {
            throw new ConfigError(file, section.getCurrentPath(), e.getMessage());
        }
    }

    /**
     * ★★ 解析可选的 {@code summon.ramping-damage}（2026-10-18 新增，烈焰守卫）。
     *
     * <p>四个键**全部必需**：{@code base} / {@code step} / {@code max} / {@code interval-ticks}。
     * ★ 不给缺省的理由：这条数值在 docs 里**写得非常具体**（1 / +1 / 10 / 0.5s），
     * 缺任何一个都只能猜 —— 而猜出来的"递增伤害"没人能发现是错的（它只会显得很弱或很疼）。
     */
    private static Card.RampDamage parseRampDamage(String file, ConfigurationSection summon) {
        if (!summon.isSet("ramping-damage")) {
            return null;
        }
        ConfigurationSection section = ConfigReader.requireSection(file, summon, "ramping-damage");
        Set<String> allowed = new TreeSet<>(List.of("base", "step", "max", "interval-ticks"));
        for (String key : section.getKeys(false)) {
            if (!allowed.contains(key)) {
                throw new ConfigError(file, section.getCurrentPath() + "." + key,
                        "ramping-damage 只认 " + allowed + "（写错的键会表现成「递增伤害没生效」）");
            }
        }
        int base = ConfigReader.requireInt(file, section, "base", 1, 1000);
        int step = ConfigReader.requireInt(file, section, "step", 0, 1000);
        int max = ConfigReader.requireInt(file, section, "max", 1, 1000);
        int interval = ConfigReader.requireInt(file, section, "interval-ticks", 1, MAX_COOLDOWN_TICKS);
        try {
            return new Card.RampDamage(base, step, max, interval);
        } catch (IllegalArgumentException e) {
            // ★ 纯数据层自检抛的错要转成配置错（否则现场看到的是一段堆栈而不是"哪个键不对"）
            throw new ConfigError(file, section.getCurrentPath(), e.getMessage());
        }
    }

    /**
     * ★★ <b>「写了 {@code affected-by-building: true}、但这一张的召唤物自己不是建筑」的提醒</b>
     * （2026-10-17 第十二轮新增；2026-10-19 随「建筑」改名一起更新）。
     *
     * <h2>为什么只告警、不拒绝启动</h2>
     * 两种键的组合在玩法上都合法（一张卡「优先打建筑」而自己不是建筑，这完全正常
     * —— 攻城兽就是如此）⇒ 拒绝启动会误伤合法配置。
     * ★ 但这一格更可能是笔误 ⇒ 留一行 WARN，且**只在装载期打一次**。
     *
     * <h2>★ 为什么是「查一遍 + 打日志」而不是在解析时随手打</h2>
     * ① 解析期没有日志器：{@code CardCatalog.load} 是**纯离线**入口（夹具与单测都在用它），
     * 而 {@code Bukkit.getLogger()} 在离线时是 NPE（本项目踩过、并因此盖掉真正的解析错误）；
     * ② 这个提醒需要**跨卡**看（要数「有几张卡写了 true」），放在
     * {@link #affectedByBuilding()} 一遍扫完更省，也不会有"每张卡各判一次"的分叉。
     *
     * <p>★ 本方法**不改变任何解析结果**（纯告警），因此不会成为第二处判据。
     *
     * @param logger 插件日志器（可为 {@code null} —— 那时只收集不打印，返回值照旧可用）
     * @return 真的打了 WARN 的卡 id（**保序**；供测试与日志对账）
     */
    public List<String> warnAboutBuildingSemantics(Logger logger) {
        List<String> warned = new ArrayList<>();
        for (Card card : cards.values()) {
            Card.Summon summon = card.summon();
            if (summon == null || !summon.affectedByBuilding() || summon.building()) {
                continue;
            }
            warned.add(card.id());
            if (logger != null) {
                logger.warning("[IC] cards/" + card.id() + ".yml 的 summon.affected-by-building=true，"
                        + "但这张卡的召唤物自己不是建筑（building=false）。"
                        + "，选目标时会「先找建筑、找不到再比距离」，这是合法配置；"
                        + "如果是误配，请检查这一格");
            }
        }
        if (logger != null && !warned.isEmpty()) {
            logger.info("[IC] 优先攻击建筑的召唤卡共 " + affectedByBuilding().size() + " 张："
                    + affectedByBuilding() + "");
        }
        return List.copyOf(warned);
    }

    /**
     * ★★ <b>「选目标时优先打建筑」的卡 id 清单</b>（保序）—— 唯一可对账视图。
     *
     * <p>它就是「{@code summon.affected-by-building: true}」的落地：
     * 测试用它逐张断言（含反方向「恰好是这些」），排障用它一眼看清全项目有几张。
     * ★ 出处：{@code docs/19} 里逐字写「<b>优先攻击建筑</b>」的只有
     * 第 60 行**攻城兽**与第 108 行**炽足兽骑士**（R37 的「只有部分召唤物会受影响」）。
     */
    public List<String> affectedByBuilding() {
        List<String> ids = new ArrayList<>();
        for (Card card : cards.values()) {
            if (card.summon() != null && card.summon().affectedByBuilding()) {
                ids.add(card.id());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * ★★ <b>「算建筑」的召唤卡 id 清单</b>（保序）—— 2026-10-19 新增。
     *
     * <p>它是「{@code summon.building: true}」的唯一可对账视图：测试要钉住
     * <b>「恰好是这 6 张」</b>（多一张少一张都红），因为「优先打建筑」的集合
     * ＝ 这 6 张 ＋ 信标 ＋ 防御塔×2，必须与改名前的「优先打带嘲讽的单位」集合**完全相等**。
     */
    public List<String> buildings() {
        List<String> ids = new ArrayList<>();
        for (Card card : cards.values()) {
            if (card.summon() != null && card.summon().building()) {
                ids.add(card.id());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * ★★ <b>还写着旧键 {@code summon.affected-by-taunt} 的卡</b>（保序；2026-10-19 新增）。
     *
     * <h2>为什么需要这个「再读一遍文件」的方法</h2>
     * 2026-10-19 把这一档从「受嘲讽影响」改名成「优先攻击建筑」，配置键随之变成
     * {@code affected-by-building}。而 <b>{@code summon} 段没有严格的未知键校验</b>
     * （只有效果参数与几个子块有）⇒ 旧键**不会**让启动失败，只会被静默忽略 ——
     * 那正是本项目最反感的形状（「写了却没生效」）。
     * ⇒ 解析期**两个键都读**（旧键仍然生效，见 {@code parseSummon}），
     * 再由本方法把「还没改名」的卡列出来，启动日志里提醒一次。
     *
     * <p>★ 为什么不把它塞进 {@link #cards()} 的字段里：那会让 {@link Card} 多一个
     * 「配置里写了哪个别名」的字段，而玩法层根本不该知道这件事 ——
     * 别名只在**装载期**有意义的。多读一遍 51 个小 YAML 的代价在启动时完全可以接受。
     *
     * @param dir 卡牌目录（{@code null} / 不存在 ⇒ 空表）
     * @return 标题里写了旧键的卡 id（按文件名排序）
     */
    public static List<String> legacyTauntKeyCards(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();   // 读不到 ⇒ 不猜（真正的读取失败会在 load 里报出来）
        }
        for (Path file : files) {
            try {
                YamlConfiguration yaml = YamlFile.read(DIR + "/" + file.getFileName(), file.toFile());
                ConfigurationSection summon = yaml.getConfigurationSection("summon");
                if (summon != null && summon.isSet("affected-by-taunt")
                        && !summon.isSet("affected-by-building")) {
                    String id = yaml.getString("id");
                    ids.add(id == null || id.isBlank()
                            ? file.getFileName().toString().replaceFirst("\\.yml$", "") : id);
                }
            } catch (RuntimeException ignored) {
                // 这一张自己坏了 ⇒ 由 load() 去报（这里只做提醒，绝不抢报错）
            }
        }
        return List.copyOf(ids);
    }

    /**
     * ★★ <b>枚举里有没有这个常量</b>（**不碰任何注册表**，因此能离线跑）。
     *
     * <p>为什么不用 {@code Enum.valueOf(...)} / {@code Particle.valueOf(...)}：
     * 26.2 的 {@code Particle} 是**注册表化**的枚举（`javap` 实测：
     * {@code public final class Particle extends Enum<Particle>} 但常量表由注册表在服务端启动时填充）
     * ⇒ 离线调用会**对所有名字**都抛（连 {@code FLAME} 也抛），
     * 于是「装载期校验」变成"离线全红、报错还指向别处"（本项目踩过，见 AGENTS.md §4.1）。
     * <p>★ 用 {@code getEnumConstants()}：它只读**类文件里的常量名**，不看注册表 ⇒ 离线可用。
     * ★ 若将来某个枚举**不是**真枚举（没有常量表），本方法保守返回 {@code true}
     * （宁可少拦一次，也不要因为"读不到"把合法配置判死）。
     */
    private static boolean hasEnumConstant(Class<?> enumType, String name) {
        Object[] constants = enumType.getEnumConstants();
        if (constants == null) {
            return true;   // 不是枚举（或常量表不可用）⇒ 不在这里判
        }
        for (Object constant : constants) {
            if (constant instanceof Enum<?> value && value.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★★ <b>解析可选的 {@code summon.equipment} 块</b>（2026-10-17 第十轮，缺陷 P1-b：
     * 「铁甲战士没有获得盔甲和武器」）。
     *
     *
     * <p>{@code docs/19} 第 56 行的「铁套铁剑」+ 第 10 行（策划案）的「所有盔甲和马凯均不提供
     * 防御和盔甲韧性数值」⇒ 需要的键就是这 5 件。逐键校验到「材料名格式」这一层：
     * <ul>
     *   <li>键名只许 {@code helmet / chestplate / leggings / boots / main-hand}
     *       （写错的表现会是「装备没穿上」而没人知道 —— 本项目「第二处白名单」的教训）；</li>
     *   <li>材料名只做格式校验（大写字母 / 数字 / 下划线）：{@code Material} 的解析要服务端注册表
     *       （{@code Material.matchMaterial}），而 {@code ShippedCardsTest} 要求出厂卡能**离线**
     *       加载 ⇒ 名字真不存在时在**召唤那一刻**告警并跳过那一件（见 {@code card/EquipmentApplier}），
     *       不影响单位本身被召唤出来。</li>
     * </ul>
     *
     * @return 装备块；没写 ⇒ {@link Card.Equipment#NONE}
     * @apiNote ★ <b>public 而不是 private</b>（2026-10-18 起）：法术效果的 {@code group} 段
     *          （{@code spell.EffectApplier}）也读这一套键 —— 用户裁定
     *          「**认可：两者用同一套键**」（召唤卡的 {@code summon.equipment} 与
     *          法术 {@code group.equipment} 共用 {@code helmet/chestplate/leggings/boots/main-hand}
     *          ＋ {@code enchantments/trim/color/groups}）。
     *          这样「掘墓者的僵尸持铁镐、戴南瓜灯」不需要第二套解析器，
     *          也不会出现两份白名单各自演化（本项目「第二处白名单」那样的事故）。
     */
    public static Card.Equipment parseEquipment(String file, ConfigurationSection summon) {
        if (!summon.isSet("equipment")) {
            return Card.Equipment.NONE;
        }
        ConfigurationSection section = ConfigReader.requireSection(file, summon, "equipment");
        Set<String> allowed = new TreeSet<>(List.of("helmet", "chestplate", "leggings", "boots",
                "main-hand", "enchantments", "trim", "color", "groups",
                // ★ 1.1.0（美愿轰炸机）：快乐恶魂的挽具（EquipmentSlot.BODY）
                "body"));
        for (String key : section.getKeys(false)) {
            if (!allowed.contains(key)) {
                throw new ConfigError(file, section.getCurrentPath() + "." + key,
                        "装备块只认 " + allowed + "（写错的键会表现成「装备没穿上」而查不出原因）");
            }
        }
        return new Card.Equipment(equipmentMaterial(file, section, "helmet"),
                equipmentMaterial(file, section, "chestplate"),
                equipmentMaterial(file, section, "leggings"),
                equipmentMaterial(file, section, "boots"),
                equipmentMaterial(file, section, "main-hand"),
                parseEnchantments(file, section),
                parseTrims(file, section),
                parseLeatherColor(file, section),
                parseEquipmentGroups(file, section),
                equipmentMaterial(file, section, "body"));
    }

    /**
     * ★★★ <b>解析 {@code summon.equipment.groups}</b>（2026-10-18 新增；
     * 用户裁定「**只给僵尸加，马铠不加**」⇒ 结案 {@code docs/05} **E23**）。
     *
     * <p>取值 = {@code EntityType} 的常量名列表（如 {@code [ZOMBIE]}）。
     * 语义：**只有这几类实体**穿这份装备；不写 = 该卡所有 group 都穿（49/51 张是这一档）。
     *
     * <p>★ 为什么需要它：`summon.equipment` 是**逐卡**的，一份装备块会套给该卡**所有 group**。
     * 前储君的两样装备分属僵尸与僵尸马 ⇒ 逐卡表达会变成「僵尸穿上马铠」。
     *
     * <p>★ 校验：名字必须能对上 {@code EntityType} ⇒ 写错**拒绝启动**
     * （否则表现是"装备没穿上"，与"没实现"无法区分）。
     */
    private static java.util.List<String> parseEquipmentGroups(String file,
                                                              ConfigurationSection section) {
        if (!section.isSet("groups")) {
            return java.util.List.of();
        }
        java.util.List<String> raw = ConfigReader.requireStringList(file, section, "groups");
        if (raw.isEmpty()) {
            throw new ConfigError(file, section.getCurrentPath() + ".groups",
                    "列表不能为空 —— 不限制组就别写这个键（缺省 = 该卡所有 group 都穿）");
        }
        java.util.List<String> out = new java.util.ArrayList<>(raw.size());
        for (String name : raw) {
            String upper = name.trim().toUpperCase(java.util.Locale.ROOT);
            try {
                org.bukkit.entity.EntityType.valueOf(upper);
            } catch (IllegalArgumentException e) {
                throw new ConfigError(file, section.getCurrentPath() + ".groups",
                        "" + upper + " 不是合法的实体类型名（要 EntityType 的常量名，全大写，"
                                + "例如 ZOMBIE / ZOMBIE_HORSE）");
            }
            out.add(upper);
        }
        return java.util.List.copyOf(out);
    }

    /**
     * ★★ <b>解析 {@code summon.equipment.color}</b>（2026-10-18 新增，
     * 用户：「**皮革盔甲没有染色**」）。
     *
     * <p>取值 = {@code DyeColor} 的**常量名**（{@code BLUE} / {@code WHITE} / {@code BLACK}…）。
     * 出处 = {@code docs/19} 装备列里的「蓝皮革」「白皮革」「黑皮革」。
     *
     * <p>★ 为什么用 {@code DyeColor} 而不是 RGB：皮革染色的原版 16 色就是 {@code DyeColor}，
     * 而文档描述的正是"蓝皮革/白皮革"这种**原版色名** ⇒ 用同一套名字最不容易失真，
     * 也让"紫水晶/金锭/铁锭"这些 trim 材料名与它共用同一种"写原版常量名"的风格。
     * <p>★ 写错色名 ⇒ **拒绝启动**（列出 16 个合法值）—— 否则表现是"皮革还是本色"，
     * 那与"没实现"无法区分。
     */
    private static String parseLeatherColor(String file, ConfigurationSection section) {
        if (!section.isSet("color")) {
            return null;
        }
        String raw = ConfigReader.requireString(file, section, "color").trim().toUpperCase(
                java.util.Locale.ROOT);
        try {
            org.bukkit.DyeColor.valueOf(raw);
        } catch (IllegalArgumentException e) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (org.bukkit.DyeColor color : org.bukkit.DyeColor.values()) {
                names.add(color.name());
            }
            throw new ConfigError(file, section.getCurrentPath() + ".color",
                    "" + raw + " 不是合法的皮革颜色（DyeColor 常量名）。合法值：" + names);
        }
        return raw;
    }

    /**
     * ★★ <b>解析 {@code summon.equipment.trim}</b>（2026-10-17 批次 3 后半新增）。
     *
     * <h2>文档依据（逐字）</h2>
     * {@code docs/19-卡牌总表.md} 的装备列里有 <b>5 处</b>「<b>X 纹样</b>」字样，
     * 全部逐字来自 {@code docs/卡牌策划案.txt}：
     * <ul>
     *   <li>L48 死灵法师：「头戴<b>金锭沙丘纹样</b>蓝色皮革头盔，身穿<b>金锭恼鬼纹样</b>…，
     *       <b>金锭海岸纹样</b>…」；</li>
     *   <li>L81 虫群术师：紫水晶 × 沙丘 / 恼鬼 / 海岸（白色皮革）；</li>
     *   <li>L107 海王：「身穿<b>钻石海岸纹样</b>铁套」；</li>
     *   <li>L110 冰术师：铁锭 × 沙丘 / 恼鬼 / 海岸（蓝色皮革）；</li>
     *   <li>L118 不休之魂：紫水晶 × 沙丘 / 恼鬼 / 海岸（黑色皮革）。</li>
     * </ul>
     * ⇒ 用户裁定（{@code docs/24} §10.1 第 4 条：「<b>做</b>（盔甲纹饰）」，
     * 落地要点「需定『纹样 = trim 材料 + 图案』」）。
     *
     * <h2>★★ 「纹样」怎么拆成两组取值（这一步是唯一的解释，写在注释里）</h2>
     * 「<b>A + B</b>纹样」（A = 金锭/钻石/紫水晶/铁锭，B = 沙丘/海岸/恼鬼）——
     * A 是原版 <b>trim 材料</b>（在盔甲上呈现为那种颜色的镶边），B 是原版 <b>trim 图案</b>：
     * <table border="1">
     *   <caption>对照表</caption>
     *   <tr><th>中文</th><th>原版键</th><th>Bukkit 取值</th></tr>
     *   <tr><td>金锭 / 钻石 / 紫水晶 / 铁锭</td>
     *       <td>{@code gold / diamond / amethyst / iron}</td>
     *       <td>{@code TrimMaterial.GOLD / DIAMOND / AMETHYST / IRON}</td></tr>
     *   <tr><td>沙丘 / 海岸 / 恼鬼</td><td>{@code dune / coast / vex}</td>
     *       <td>{@code TrimPattern.DUNE / COAST / VEX}</td></tr>
     * </table>
     * ★ 依据：原版 trim 图案的**英文名**就是这些词（{@code dune} = 沙丘、{@code coast} = 海岸、
     *   {@code vex} = 恼鬼），而「金锭 / 钻石 / 铁锭 / 紫水晶」正是原版 trim 材料的**物品名**
     *   —— 两句都不是我发明的对应关系。★ 权限：用户第 13 条「这种数值小细节完全放权给你」。
     *
     * <h2>★ 校验到什么程度（与附魔同一口径）</h2>
     * 只做**格式**校验（小写字母 / 数字 / 下划线）；材料与图案**真不存在**时在
     * <b>召唤那一刻</b>告警并跳过那一件（{@code card/EquipmentApplier}）——
     * 因为 {@code TrimMaterial} / {@code TrimPattern} 的解析要服务端注册表，
     * 而出厂卡必须能**离线**加载（{@code ShippedCardsTest} 的硬要求）。
     * ★ {@code material} 与 {@code pattern} **两个键都必须写**（缺一个 = 不知道要什么纹饰，
     * 静默跳过只会表现成「纹饰没生效」）。
     */
    private static Map<String, Map<String, String>> parseTrims(String file,
                                                              ConfigurationSection equipment) {
        if (!equipment.isSet("trim")) {
            return Map.of();
        }
        ConfigurationSection all = ConfigReader.requireSection(file, equipment, "trim");
        Set<String> slots = new TreeSet<>(List.of("helmet", "chestplate", "leggings", "boots"));
        Map<String, Map<String, String>> parsed = new LinkedHashMap<>();
        for (String slot : all.getKeys(false)) {
            if (!slots.contains(slot)) {
                throw new ConfigError(file, all.getCurrentPath() + "." + slot,
                        "纹饰只认盔甲那 4 个槽位 " + slots
                                + "（主手武器没有纹饰；键名与 helmet/chestplate/… 同键同名）");
            }
            ConfigurationSection trim = ConfigReader.requireSection(file, all, slot);
            Map<String, String> pair = new LinkedHashMap<>();
            for (String key : List.of("material", "pattern")) {
                if (!trim.isSet(key)) {
                    throw new ConfigError(file, trim.getCurrentPath() + "." + key,
                            "纹饰必须同时写 material（金锭/钻石/紫水晶/铁锭）与 "
                                    + "pattern（沙丘/海岸/恼鬼）—— 缺一个就不知道要哪种纹饰，"
                                    + "静默跳过只会表现成「纹饰没生效」");
                }
                String value = trim.getString(key);
                String bare = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
                if (!ENCHANT_NAME_PATTERN.matcher(bare).matches()) {
                    throw new ConfigError(file, trim.getCurrentPath() + "." + key,
                            "" + value + " 不是合法的原版键名（应形如 dune / coast / vex / "
                                    + "gold / diamond / amethyst / iron：小写字母 / 数字 / 下划线）");
                }
                pair.put(key, bare);
            }
            parsed.put(slot, Collections.unmodifiableMap(pair));
        }
        return Collections.unmodifiableMap(parsed);
    }

    /**
     * ★★ <b>解析 {@code summon.equipment.enchantments}</b>（2026-10-17 第十二轮新增）。
     *
     * <h2>文档依据（逐字）</h2>
     * {@code docs/19-卡牌总表.md} 的装备列里**含附魔**的条目：
     * <ul>
     *   <li>第 57 行 精密猎手：「<b>力量 II 弓</b>」⇒ main-hand: BOW + power 2；</li>
     *   <li>第 82 行 劲弩手：「<b>手持力量 V 弩</b>」⇒ main-hand: CROSSBOW + power 5。</li>
     * </ul>
     * ⇒ 附魔是**装备的一部分**（写在同一个格子里），不是另一件事。
     * ★ 形状：{@code enchantments: { <槽位>: { <附魔名>: <等级> } }}。
     * ★ 那 5 个槽位名与 {@code helmet/chestplate/…} **同键同名**（同一处白名单）。
     *
     * <h2>★ 校验到什么程度（与材料名同一口径）</h2>
     * 附魔名只做**格式**校验（小写字母 / 数字 / 下划线 —— 原版附魔键的形态），
     * 真不存在时在**召唤那一刻**告警并跳过那一件（{@code card/EquipmentApplier}）——
     * 因为 {@code Enchantment} 的解析要服务端注册表，而出厂卡必须能**离线**加载。
     * ★ 等级必须是 <b>1~255</b> 的整数（原版上限；0 或负数无意义）。
     */
    private static Map<String, Map<String, Integer>> parseEnchantments(String file,
                                                                      ConfigurationSection equipment) {
        if (!equipment.isSet("enchantments")) {
            return Map.of();
        }
        ConfigurationSection all = ConfigReader.requireSection(file, equipment, "enchantments");
        Set<String> slots = new TreeSet<>(List.of("helmet", "chestplate", "leggings", "boots",
                "main-hand"));
        Map<String, Map<String, Integer>> parsed = new LinkedHashMap<>();
        for (String slot : all.getKeys(false)) {
            if (!slots.contains(slot)) {
                throw new ConfigError(file, all.getCurrentPath() + "." + slot,
                        "附魔只认那 5 个装备槽位 " + slots
                                + "（与 helmet/chestplate/… 同键同名 —— 写错的表现会是「附魔没生效」）");
            }
            ConfigurationSection enchants = ConfigReader.requireSection(file, all, slot);
            Map<String, Integer> byName = new LinkedHashMap<>();
            for (String name : enchants.getKeys(false)) {
                String key = name.trim().toLowerCase(Locale.ROOT);
                if (!ENCHANT_NAME_PATTERN.matcher(key).matches()) {
                    throw new ConfigError(file, enchants.getCurrentPath() + "." + name,
                            "" + name + " 不是合法的附魔键名（应形如 power / sharpness / "
                                    + "unbreaking：小写字母 / 数字 / 下划线）");
                }
                int level = ConfigReader.requireInt(file, enchants, name, 1, 255);
                byName.put(key, level);
            }
            if (!byName.isEmpty()) {
                parsed.put(slot, Collections.unmodifiableMap(byName));
            }
        }
        return Collections.unmodifiableMap(parsed);
    }

    /** 取一件装备的材料名（缺 ⇒ {@code null}；格式非法 ⇒ 装载期报错）。 */
    private static String equipmentMaterial(String file, ConfigurationSection section, String key) {
        if (!section.isSet(key)) {
            return null;
        }
        String raw = ConfigReader.requireString(file, section, key).trim();
        if (!MATERIAL_PATTERN.matcher(raw).matches()) {
            throw new ConfigError(file, section.getCurrentPath() + "." + key,
                    "" + raw + " 不是合法的原版材料名（全大写，例如 IRON_HELMET / IRON_SWORD）");
        }
        return raw;
    }

    /** 材料名的格式（原版 Material 常量名：大写字母 / 数字 / 下划线）。 */
    private static final java.util.regex.Pattern MATERIAL_PATTERN =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * 附魔键名的格式（原版附魔键：小写字母 / 数字 / 下划线，例如 {@code power} / {@code sharpness}）。
     *
     * <p>★ 有意**不**写成 {@code minecraft:power} 这种带命名空间的形式：
     * {@code docs/19} 的装备列写的是中文附魔名（「力量 II」），我把它对到原版的**裸键**；
     * 带命名空间只会多一种写法、多一处可能分叉的地方。
     */
    private static final java.util.regex.Pattern ENCHANT_NAME_PATTERN =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]*");

    /**
     * 解析一个段落里的 {@code abilities} 列表（{@code summon.abilities}）。
     *
     * <p>★★ 2026-10-19：<b>从 {@code private} 放宽到 {@code public}</b> —— 第二个调用点是
     * {@code spell/EffectApplier#syntheticSummonCard}（效果召唤的 {@code group} 段）。
     * 理由与 {@link #parseEquipment} 同一条：<b>键表只有一份</b>，
     * 绝不复制第二个解析器（本项目「第二处白名单」事故的形状）。
     */
    public static List<Card.Ability> parseAbilities(String file, ConfigurationSection owner) {
        if (!owner.isSet("abilities")) {
            return List.of();
        }
        List<Map<?, ?>> raw = owner.getMapList("abilities");
        if (raw.isEmpty()) {
            throw new ConfigError(file, owner.getCurrentPath() + ".abilities", "列表不能为空（至少要有一项）");
        }
        List<Card.Ability> abilities = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            // ★ 用「从根算起」的完整路径，运维才知道是哪张卡的哪一条
            String path = "abilities[" + i + "]";
            Card.Trigger trigger = parseEnum(file, MapReader.requireString(file, entry, path, "trigger"),
                    path + ".trigger", Card.Trigger.class);
            Integer cooldown = MapReader.isSet(entry, "cooldown-ticks")
                    ? MapReader.requireInt(file, entry, path, "cooldown-ticks", 0, MAX_COOLDOWN_TICKS)
                    : null;
            Boolean affected = MapReader.optionalBoolean(entry, "affected-by-taunt");
            abilities.add(new Card.Ability(
                    MapReader.requireString(file, entry, path, "id"),
                    trigger, cooldown, affected != null && affected,
                    parseEffects(file, entry, path),
                    parseAlignment(file, entry, path),
                    parseAbilityRadius(file, entry, path),
                    parseIncludeSelf(file, entry, path),
                    parseAbilityCenter(file, entry, path),
                    parseAbilityFx(file, entry, path),
                    // ★★ 2026-10-18（用户第 2 条更正）：`search-radius` = 找目标用的距离，
                    //   与 `radius`（**效果**半径）是两个数 —— 死灵法师：找 8 格内、炸半径 2。
                    MapReader.isSet(entry, "search-radius")
                            ? MapReader.requireInt(file, entry, path, "search-radius", 1, 256)
                            : null));
        }
        return List.copyOf(abilities);
    }

    /**
     * ★★ <b>{@code abilities[].fx}</b> —— 这条能力的「看得见」部分
     * （2026-10-17 第十三轮收尾新增；用户：「冰术师等卡牌的攻击…如果不添加特效是看不出来的」＋
     * 「**雷神**（= 海王）登场时需要的闪电特效反而没有了」）。
     *
     * <p>三个键全可选：{@code particle} / {@code sound} / {@code lightning}。
     * 缺省 ⇒ {@link Card.Ability.Fx#NONE}（只有"半径，画范围环"那条默认行为）。
     *
     * <p>★ 校验口径（**宁可在启动时拒绝，也不要在施放时静默无效**）：
     * <ul>
     *   <li>{@code particle} 必须是 {@code org.bukkit.Particle} 的**常量名**（全大写）；
     *       ★ 只查格式、不查 {@code valueOf} —— 因为枚举在离线测试里不可靠，
     *       而运行期 {@code AbilityRuntime} 解析失败会**告警一次**并降级（不影响伤害）；</li>
     *   <li>{@code sound} 必须含一个点（原版音效键的形状 {@code entity.xxx.yyy}）；</li>
     *   <li>{@code lightning} 必须是布尔。</li>
     * </ul>
     */
    private static Card.Ability.Fx parseAbilityFx(String file, Map<?, ?> entry, String path) {
        Object raw = entry.get("fx");
        if (raw == null) {
            return Card.Ability.Fx.NONE;
        }
        Map<?, ?> fx;
        if (raw instanceof Map<?, ?> map) {
            fx = map;
        } else {
            throw new ConfigError(file, path + ".fx",
                    "fx 必须是一个段落（fx: { particle: …, sound: …, lightning: true }）；实际是 "
                            + raw.getClass().getSimpleName());
        }
        String fxPath = path + ".fx";
        String particle = null;
        if (MapReader.isSet(fx, "particle")) {
            particle = MapReader.requireString(file, fx, fxPath, "particle").trim();
            if (!MATERIAL_PATTERN.matcher(particle).matches()) {
                throw new ConfigError(file, fxPath + ".particle",
                        "" + particle + " 不是合法的粒子常量名（全大写，例如 ELECTRIC_SPARK / SNOWFLAKE）");
            }
        }
        String sound = null;
        if (MapReader.isSet(fx, "sound")) {
            sound = MapReader.requireString(file, fx, fxPath, "sound").trim();
            if (!sound.contains(".")) {
                throw new ConfigError(file, fxPath + ".sound",
                        "" + sound + " 不像原版音效键（形状如 entity.lightning_bolt.thunder）");
            }
        }
        Boolean lightning = MapReader.optionalBoolean(fx, "lightning");
        // ★★★ 2026-10-21 用户裁定（逐字）：「**海王不需要指向敌人的射线粒子**」
        //   ⇒ `fx.line: false` 关掉「我 → 目标」那条默认直线。
        //   ★ 缺省 true（不写 = 既有行为）⇒ 没写这一格的卡一字未变。
        Boolean line = MapReader.optionalBoolean(fx, "line");
        return new Card.Ability.Fx(particle, sound, lightning != null && lightning,
                line == null || line);
    }

    // ------------------------------------------------------------------ 作用的「面」与「范围」

    /**
     * ★★ <b>{@code abilities[].targets}</b> —— 这条能力打谁（2026-10-17 批次 3 后半新增）。
     *
     * <p>缺省（这个键缺失、或写成空）⇒ {@code null} ⇒ 运行期按
     * {@link io.mczju.infernalclash.ability.AbilityTargets.Alignment#SELF} 处理
     * —— <b>恰好等于</b>加这个键之前的行为，「给老卡补作用面」不会静默改变任何一张没写它的卡。
     *
     * <p>★ 写非法值（例如 {@code targets: ENEMY} 少一个 S）⇒ <b>拒绝启动</b>并给出
     * 「你是不是想写 X」的建议 —— 与 {@code trigger} 的口径一致（{@link #parseEnum}）。
     */
    private static io.mczju.infernalclash.ability.AbilityTargets.Alignment parseAlignment(
            String file, Map<?, ?> entry, String path) {
        if (!MapReader.isSet(entry, "targets")) {
            return null;
        }
        return parseEnum(file, MapReader.requireString(file, entry, path, "targets"),
                path + ".targets",
                io.mczju.infernalclash.ability.AbilityTargets.Alignment.class);
    }

    /**
     * ★★ <b>{@code abilities[].radius}</b> —— 「周围 N 格」的 N（单位 = 格）。
     *
     * <p>口径（★ 刻意的，不是随手定的）：
     * <ul>
     *   <li><b>缺失</b> ⇒ {@code null} = <b>没有范围</b> ⇒ 只打自己（既有行为）；</li>
     *   <li>{@code 0} ⇒ <b>合法</b>，含义是「只有和源点同一格的那些单位」
     *       —— 0 是一个有意义的范围，不是"写错了"；</li>
     *   <li>负数 ⇒ <b>拒绝启动</b>（负半径没有任何语义，放行只会在运行期表现为"永远打不到"）；</li>
     *   <li>上限 {@value #MAX_ABILITY_RADIUS} 格 ⇒ 防手滑写出天文数字（整个竞技场才 48×32）。</li>
     * </ul>
     * ★ 「写了 {@code targets} 却没写 {@code radius}」<b>不算错</b>：那种写法就是
     * 「只看自己那一档」，运行期按 {@code SELF} 处理，不会静默扩范围。
     */
    private static Integer parseAbilityRadius(String file, Map<?, ?> entry, String path) {
        if (!MapReader.isSet(entry, "radius")) {
            return null;
        }
        return MapReader.requireInt(file, entry, path, "radius", 0, MAX_ABILITY_RADIUS);
    }

    /** {@code abilities[].include-self} —— 范围内含不含自己（缺省 {@code false}）。 */
    private static boolean parseIncludeSelf(String file, Map<?, ?> entry, String path) {
        Boolean value = MapReader.optionalBoolean(entry, "include-self");
        return value != null && value;
    }

    /**
     * ★★ <b>{@code abilities[].center}</b> —— 范围的<b>中心</b>取在哪（{@code SELF} /
     * {@code ATTACK_TARGET}）。
     *
     * <p>缺省（键缺失 / 空）⇒ {@code null} ⇒ 运行期按 {@code SELF} 处理
     * （= 加这个键之前的行为）。非法值 ⇒ <b>拒绝启动</b>并列出合法值。
     */
    private static io.mczju.infernalclash.ability.AbilityTargets.Center parseAbilityCenter(
            String file, Map<?, ?> entry, String path) {
        if (!MapReader.isSet(entry, "center")) {
            return null;
        }
        return parseEnum(file, MapReader.requireString(file, entry, path, "center"),
                path + ".center",
                io.mczju.infernalclash.ability.AbilityTargets.Center.class);
    }

    /** 「周围 N 格」的 N 上限（格）：整个竞技场才 48×32 ⇒ 64 已经远超任何合理范围。 */
    private static final int MAX_ABILITY_RADIUS = 64;

    private static List<Spell.Effect> parseEffects(String file, Map<?, ?> owner, String ownerPath) {
        return parseEffectList(file, MapReader.requireMapList(file, owner, ownerPath, "effects"), ownerPath);
    }

    private static List<Spell.Effect> parseEffects(String file, ConfigurationSection owner, String ownerPath) {
        List<Map<?, ?>> raw = owner.getMapList("effects");
        if (raw.isEmpty()) {
            throw new ConfigError(file, ownerPath + ".effects",
                    owner.isSet("effects") ? "列表不能为空（至少要有一项）" : "缺少必需的列表键");
        }
        return parseEffectList(file, raw, ownerPath);
    }

    private static List<Spell.Effect> parseEffectList(String file, List<Map<?, ?>> raw, String ownerPath) {
        List<Spell.Effect> effects = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            String path = ownerPath + ".effects[" + i + "]";
            Spell.EffectType type = parseEnum(file, MapReader.requireString(file, entry, path, "type"),
                    path + ".type", Spell.EffectType.class);
            requireEffectKeys(file, entry, path, type);
            // ★【Q11】未知键也在装载期拦下（新校验是**补充**，缺必需键那条照旧）
            rejectUnknownEffectKeys(file, entry, path, type);
            // ⚠️ 不能用 Map.copyOf：YAML 里 `damage-type:`（留空）会给出 **null 值**，
            //    而 Map.copyOf / Map.of 遇到 null 会抛 NPE —— 配置里留个空值不该让插件崩。
            //    键统一成 String：效果参数一律按字符串键取用。
            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<?, ?> pair : entry.entrySet()) {
                params.put(String.valueOf(pair.getKey()), pair.getValue());
            }
            effects.add(new Spell.Effect(type, Collections.unmodifiableMap(params)));
        }
        return List.copyOf(effects);
    }

    /**
     * 读**组级**属性覆写（{@code docs/18} §2 的骨架示例用的是这一层）。
     * 没写的字段保持 {@code null} = 不覆写。
     *
     * <p>★★ 属性键在**三处**解析，必须同键同名（改一处必须三处同改）：
     * <ol>
     *   <li><b>本方法</b>（卡牌的组级 / 列表项形式，{@code summon.group[].attributes}）；</li>
     *   <li>{@code MobGroupCatalog#parseAttributes}（{@code mob-groups.yml} 的实体组）；</li>
     *   <li>{@code MobGroupCatalog#readAttributes}（卡牌的 {@code summon.attributes} 召唤块级）。</li>
     * </ol>
     * ★ 分叉的后果就是本项目最反复的那类「第二处白名单」缺陷：**能装载、但不生效**。
     * 已由 {@code EffectiveAttributesTest} / {@code ShippedCardsTest} 钉住键名与取值。
     */
    private static Card.Attributes readGroupAttributes(String file, Map<?, ?> entry, String path) {
        if (!MapReader.isSet(entry, "attributes")) {
            return Card.Attributes.NONE;
        }
        Map<?, ?> attributes = MapReader.requireMap(file, entry, path, "attributes");
        String base = path + ".attributes";
        return new Card.Attributes(
                MapReader.optionalDouble(file, attributes, base, "health"),
                MapReader.optionalDouble(file, attributes, base, "attack-damage"),
                MapReader.optionalDouble(file, attributes, base, "armor"),
                movementSpeedMultiplier(file, attributes, base),
                MapReader.optionalDouble(file, attributes, base, "follow-range"),
                MapReader.optionalDouble(file, attributes, base, "scale"),
                knockbackResistance(file, attributes, base),
                size(file, attributes, base));
    }

    /**
     * ★★★ 读 {@code size}（方块怪的尺寸档位）—— 2026-10-18 新增（用户第 3 条）。
     *
     * <h2>★ 三处解析共用这一个实现（不是各写一遍）</h2>
     * 范围校验只有一处 ⇒ 「某一处 clamp 了、另一处没 clamp」这种分叉不可能发生
     * （这正是 {@code knockback-resistance} 那一条踩过的形状）。三处分别是：
     * {@code CardCatalog#readGroupAttributes} / {@code MobGroupCatalog#parseAttributes} /
     * {@code MobGroupCatalog#readAttributes}。
     *
     * @return 档位（1~127）；**没写 ⇒ {@code null}**（= 不覆写，原版随机档位照旧）
     * @throws ConfigError 不是数字 / 越界（**拒绝启动**，不 clamp、不静默忽略）
     */
    public static Integer size(String file, Map<?, ?> attributes, String base) {
        Double value = MapReader.optionalDouble(file, attributes, base, "size");
        return checkSize(file, base + ".size", value);
    }

    /** 同上，但接 {@code ConfigurationSection}（{@code MobGroupCatalog} 那两处走这条）。 */
    public static Integer size(String file, org.bukkit.configuration.ConfigurationSection section) {
        if (section == null || !section.isSet("size")) {
            return null;
        }
        double value = ConfigReader.requireDouble(file, section, "size",
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        return checkSize(file, section.getCurrentPath() + ".size", value);
    }

    /** {@code size} 的唯一范围校验（1~127 的正整数；原版 {@code Slime#setSize} 的口径）。 */
    private static Integer checkSize(String file, String path, Double value) {
        if (value == null) {
            return null;
        }
        double rounded = Math.rint(value);
        if (!(value >= 1.0) || value > 127.0 || Math.abs(value - rounded) > 1e-9) {
            throw new ConfigError(file, path,
                    "尺寸档位必须是 1~127 的整数：实际 " + value
                            + "。原版史莱姆/岩浆怪的自然档位只有 1（小）/ 2（中）/ 4（大），"
                            + "血 = 尺寸²；写 0 或小数会被原版悄悄夹紧，那正是要拦的那种"
                            + "「配置写了却没生效」");
        }
        return (int) rounded;
    }

    /**
     * ★★ 读 {@code movement-speed}（**倍率**语义，2026-10-17 第十三轮用户裁定「是倍率」）。
     *
     * <h2>为什么单独一个方法（而不是直接 optionalDouble）</h2>
     * 这个键的**量纲变了**：从"绝对值"改成"倍数"⇒ 有一类**过去合法、现在必然荒谬**的值：
     * {@code 0}（怪一步都走不动 ⇒ 看起来像卡住了）与**负数**（原版属性不接受）。
     * ⇒ 在**装载期**就拒绝它们（不 clamp —— 夹成 0.1 只会让"写错了"变成"看起来能用"，
     * 那正是本项目最反感的失败形状）。
     * ★ 上限**不设**：卡表要写 10 倍就写 10 倍（数值由用户定，我们不悄悄限制）。
     *
     * @return 倍率（{@code > 0}）；**没写 ⇒ {@code null}**（= 不覆改，原版速度照旧）
     * @throws ConfigError {@code <= 0}（**拒绝启动**）
     */
    public static Double movementSpeedMultiplier(String file, ConfigurationSection attributes) {
        if (!attributes.isSet("movement-speed")) {
            return null;
        }
        return movementSpeedMultiplier(file,
                Map.of("movement-speed", ConfigReader.requireDouble(file, attributes,
                        "movement-speed", -100_000.0, 100_000.0)),
                attributes.getCurrentPath() == null ? "attributes" : attributes.getCurrentPath());
    }

    public static Double movementSpeedMultiplier(String file, Map<?, ?> attributes, String base) {        Double value = MapReader.optionalDouble(file, attributes, base, "movement-speed");
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw new ConfigError(file, base + ".movement-speed",
                    "必须是正数倍率（例：1.2 = 快 20%；2026-10-17 用户裁定「movement-speed 是倍率」）——"
                            + "实际 " + value + "。0 或负数不是「慢」，而是让这个属性失去含义"
                            + "（原来的绝对值语义下 0 也不合法，所以没有任何卡牌会因此变红）");
        }
        return value;
    }

    /**
     * ★★ 读 {@code knockback-resistance}（2026-10-17 第十二轮新增，用户裁定 §A 第 2 条）。
     *
     * <h2>★ 三处解析共用这一个实现（不是各写一遍）</h2>
     * 范围校验只有一处 ⇒ 「只在某一处 clamp 了、另一处没 clamp」这种分叉不可能发生
     * （那正是 {@code 0.8} 被写成 {@code 8} 之后静默变成"完全免疫"的成因）。
     *
     * @return 取值（0.0~1.0）；**没写 ⇒ {@code null}**（= 不覆写，原版默认照旧）
     * @throws ConfigError 取值越界（**拒绝启动**，不 clamp、不静默忽略）
     */
    public static Double knockbackResistance(String file, Map<?, ?> attributes, String base) {
        Double value = MapReader.optionalDouble(file, attributes, base, "knockback-resistance");
        if (value == null) {
            return null;
        }
        if (!(value >= 0.0) || value > 1.0) {
            throw new ConfigError(file, base + ".knockback-resistance",
                    "击退抗性必须落在 0.0 ~ 1.0（1.0 = 完全免疫击退）：实际 " + value
                            + "。这里刻意不 clamp —— 0.8 写成 8 会静默变成"
                            + "「完全免疫」，那是最难查的一类配置错误");
        }
        return value;
    }

    /**
     * 同上，但接 {@code ConfigurationSection}（{@code MobGroupCatalog} 那两处走这条）。
     */
    public static Double knockbackResistance(String file, org.bukkit.configuration.ConfigurationSection section) {
        if (section == null || !section.isSet("knockback-resistance")) {
            return null;
        }
        double value = ConfigReader.requireDouble(file, section, "knockback-resistance",
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        if (!(value >= 0.0) || value > 1.0) {
            throw new ConfigError(file, section.getCurrentPath() + ".knockback-resistance",
                    "击退抗性必须落在 0.0 ~ 1.0（1.0 = 完全免疫击退）：实际 " + value
                            + "。刻意不 clamp（0.8 写成 8 会静默变成「完全免疫」）");
        }
        return value;
    }

    /**
     * ★★★ <b>{@code group} 的形态校验：只允许「字符串组键」或「段落」</b>
     * （2026-10-17 第十三轮收尾新增）。
     *
     * <h2>为什么要专门加这一条</h2>
     * 用户实机报「灵狐引路 / 信息素 / 掘墓者只扣费不召唤」。根因是
     * 出厂卡写的是<b>字符串</b>（{@code group: FOX}），而施放期只认段落
     * ⇒ 抛「缺少必需的段落键 group」。
     * <p>★ 更严重的是：<b>装载期只查键名、不查类型</b> ⇒ 这类配置
     * <b>能装载、一施放就炸</b>，离线测试也全绿（它们只验出厂卡"能装载"）。
     * 这正是 {@code docs/05} 里「第二处白名单」那类缺陷的形状 ——
     * 判据要在<b>能拒绝的最早时刻</b>拒绝，而不是等到玩家点了卡才炸。
     *
     * <p>支持的三态（与 {@code EffectApplier#groupSection} 逐条对应）：
     * <ul>
     *   <li>{@code group: FOX}（字符串）＋ 可选兄弟 {@code count}；</li>
     *   <li>{@code group: { entity: FOX, count: 1 }}（段落 / Map）；</li>
     *   <li>其它（列表、数字、布尔）⇒ <b>拒绝启动</b>并说清两种合法写法。</li>
     * </ul>
     */
    private static void requireGroupShape(String file, Map<?, ?> entry, String path) {
        Object raw = entry.get("group");
        if (raw instanceof String text) {
            if (text.isBlank()) {
                throw new ConfigError(file, path + ".group",
                        "group 是字符串时不能为空（它应当是一个实体组键，例如 group: ZOMBIE）");
            }
            return;
        }
        if (raw instanceof ConfigurationSection || raw instanceof Map<?, ?>) {
            return;
        }
        throw new ConfigError(file, path + ".group",
                "group 只允许两种形态：①字符串组键 group: ZOMBIE（可配兄弟键 count: 3）；"
                        + "②段落 group: { entity: ZOMBIE, count: 3 }。实际是 "
                        + (raw == null ? "null" : raw.getClass().getSimpleName() + "（" + raw + "）")
                        + "　这条校验存在的理由：这两张卡曾经「能装载、一施放就失败」");
    }

    /**
     * 逐类校验效果的**必需键**。
     *
     * <p>★ 为什么现在就要做：少了 {@code amount} 的伤害效果会在**打出卡的那一刻**
     * 才炸（而且是运行期异常），而配置错误本该在**启动时**就被拒绝。
     */
    private static void requireEffectKeys(String file, Map<?, ?> entry, String path, Spell.EffectType type) {
        switch (type) {
            case DAMAGE -> {
                // `amount` 由末尾的统一检查负责
            }
            case HEAL -> {
                // ★ 「恢复全部血量」在 docs/19 里**没有数值可抄** ⇒ 允许用 `full: true` 表达，
                //   而不是逼着谁编一个数字（治愈术就是这么处理的）。
                boolean full = Boolean.TRUE.equals(MapReader.optionalBoolean(entry, "full"));
                if (!full && MapReader.optionalDouble(file, entry, path, "amount") == null) {
                    throw new ConfigError(file, path,
                            "HEAL 需要 amount（固定数值）或 full: true（恢复至满血）");
                }
            }
            case POTION -> {
                MapReader.requireString(file, entry, path, "effect");
                MapReader.requireInt(file, entry, path, "amplifier", 0, 255);
                MapReader.requireInt(file, entry, path, "duration-ticks", 1, MAX_COOLDOWN_TICKS);
            }
            case ATTRIBUTE -> {
                MapReader.requireString(file, entry, path, "attribute");
                MapReader.requireInt(file, entry, path, "duration-ticks", 1, MAX_COOLDOWN_TICKS);
            }
            case SUMMON -> {
                requirePresent(file, entry, path, "group");
                requireGroupShape(file, entry, path);
                // ★ `count` 可选（缺省 = 组段自己的 count，再缺省 = 1）
                if (MapReader.isSet(entry, "count")) {
                    MapReader.requireInt(file, entry, path, "count", 1, 64);
                }
            }
            case SUMMON_DELAYED -> {
                requirePresent(file, entry, path, "group");
                requireGroupShape(file, entry, path);
                MapReader.requireInt(file, entry, path, "count", 1, 64);
                MapReader.requireInt(file, entry, path, "delay-ticks", 1, MAX_COOLDOWN_TICKS);
            }
            case SUMMON_PERIODICALLY -> {
                // ★ 2026-10-18：周期召唤（信息素）。次数 = duration / interval，**不再另给次数键**
                requirePresent(file, entry, path, "group");
                requireGroupShape(file, entry, path);
                MapReader.requireInt(file, entry, path, "count", 1, 64);
                MapReader.requireInt(file, entry, path, "interval-ticks", 1, MAX_COOLDOWN_TICKS);
                MapReader.requireInt(file, entry, path, "duration-ticks", 1, MAX_COOLDOWN_TICKS);
                if (MapReader.isSet(entry, "radius")) {
                    MapReader.requireInt(file, entry, path, "radius", 0, MAX_ABILITY_RADIUS);
                }
            }
            case CAST_SPELL -> {
                requirePresent(file, entry, path, "spell");
                MapReader.requireString(file, entry, path, "at");
            }
            // ★★ 2026-10-19「回响」：复读**没有参数**（它读的是本局状态，不是配置）。
            //   与 `REFRESH_COOLDOWN` / `REVIVE` 同形 —— 空参数是被刻意允许的。
            case REPLAY_LAST_CARD -> {
            }
            case STUN ->
                    MapReader.requireInt(file, entry, path, "duration-ticks", 1, MAX_COOLDOWN_TICKS);
            case IGNITE -> {
                // `duration-ticks` **可选**：docs/19 只写「点燃」没给秒数 ⇒ 缺省 = 原版点燃时长。
                // ★ 这里刻意不编数字：语义落在「没写就用原版」，而不是某个我拍脑袋的值。
                if (MapReader.isSet(entry, "duration-ticks")) {
                    MapReader.requireInt(file, entry, path, "duration-ticks", 1, MAX_COOLDOWN_TICKS);
                }
            }
            case PUSH -> MapReader.requireInt(file, entry, path, "duration-ticks", 1, MAX_COOLDOWN_TICKS);
            case KNOCKBACK -> {
                // `strength` **可选**：docs/19 只写「击退」没给强度 ⇒ 缺省 = 原版击退强度。
                if (MapReader.isSet(entry, "strength")) {
                    MapReader.optionalDouble(file, entry, path, "strength");
                }
            }
            case GRANT_XP -> MapReader.requireInt(file, entry, path, "amount", 1, 100);
            case COOLDOWN_REDUCTION -> {
                MapReader.requireInt(file, entry, path, "percent", 1, 100);
                MapReader.requireInt(file, entry, path, "duration-ticks", 1, MAX_COOLDOWN_TICKS);
            }
            case REFRESH_COOLDOWN, REVIVE -> {
                // 无必需键：刷新的是**本卡**冷却；复活用的是实体自身血量
            }
        }
        // 「带数值」的效果必须有 amount（缺失会让它成为一个什么都不做的空效果）
        if ((type == Spell.EffectType.DAMAGE || type == Spell.EffectType.ATTRIBUTE)
                && MapReader.optionalDouble(file, entry, path, "amount") == null) {
            throw new ConfigError(file, path + ".amount", "缺少必需的数字键（效果 " + type + " 需要它）");
        }
    }

    private static void requirePresent(String file, Map<?, ?> entry, String path, String key) {
        if (!MapReader.isSet(entry, key)) {
            throw new ConfigError(file, path + "." + key, "缺少必需的键");
        }
    }

    /**
     * ★【Q11】逐效果校验**键白名单**：出现未知键 ⇒ 抛 {@link ConfigError}（拒绝启动）。
     *
     * <h2>为什么必须提前到装载期</h2>
     * 在此之前，未知键只在<b>施放时</b>才被发现（{@code EffectParams.rejectUnknown} 抛
     * {@link IllegalArgumentException} ⇒ <b>整次施放失败</b>）。本项目的一贯口径是
     * 「<b>装错就拒绝启动</b>」—— 卡牌数据错不该等到玩家打牌的那一刻才炸，
     * 更不该表现为「这张卡怪怪的」（{@code taunt-level} 那类静默失效的克星）。
     *
     * <h2>报错必须能直接定位</h2>
     * <ul>
     *   <li>{@code file} = {@code cards/xxx.yml}（带目录前缀，CardCatalog 的既有口径）；</li>
     *   <li>{@code keyPath} = <b>从根算起的完整路径</b>，且一直指到那个坏键，
     *       例如 {@code spell.effects[0].amountt}
     *       （召唤物能力那条是 {@code abilities[0].effects[0].amountt}）；</li>
     *   <li>消息里同时给出<b>未知键名</b>与<b>允许的键</b>（都按字典序，便于比对与写测试）。</li>
     * </ul>
     *
     * <p>⚠️ 与施放期的口径差异：这里只查效果条目这一层；{@code group:} 段内的子键
     * 不在本方法的范围（见 {@link #EFFECT_KEYS} 的说明）。{@code type} 是列表项自身的键，
     * 不是效果参数 ⇒ 与 {@code EffectParams.rejectUnknown} 一样跳过。
     */
    private static void rejectUnknownEffectKeys(String file, Map<?, ?> entry, String path,
                                                Spell.EffectType type) {
        Set<String> allowed = EFFECT_KEYS.getOrDefault(type, Set.of());
        Set<String> unknown = new TreeSet<>();
        for (Object rawKey : entry.keySet()) {
            if (rawKey == null) {
                unknown.add("<null>");
                continue;
            }
            String key = String.valueOf(rawKey);
            if ("type".equals(key)) {
                continue;
            }
            if (!allowed.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        // keyPath 指到排序后的第一个坏键（多个坏键全在消息里列出）
        throw new ConfigError(file, path + "." + unknown.iterator().next(),
                "效果 " + type + " 出现了未知参数 " + unknown + "；该效果只认 " + new TreeSet<>(allowed)
                        + " —— 未知键在加载期就拒绝启动（写了却没生效的键不会一路静默到施放时才炸）");
    }

    private static Spell parseSpell(String file, ConfigurationSection root, List<SpellRef> spellRefs) {
        ConfigurationSection spell = ConfigReader.requireSection(file, root, "spell");
        Spell.Targeting targeting = requireEnum(file, spell, "targeting", Spell.Targeting.class);

        ConfigurationSection shapeSection = ConfigReader.requireSection(file, spell, "shape");
        Spell.ShapeType shapeType = requireEnum(file, shapeSection, "type", Spell.ShapeType.class);
        Double radius = MobGroupCatalog.optionalNumber(file, shapeSection, "radius");
        Double width = MobGroupCatalog.optionalNumber(file, shapeSection, "width");
        Double length = MobGroupCatalog.optionalNumber(file, shapeSection, "length");
        Double angle = MobGroupCatalog.optionalNumber(file, shapeSection, "angle");
        switch (shapeType) {
            case CIRCLE -> requireKey(file, shapeSection, "radius", radius, shapeType);
            case RECTANGLE -> {
                requireKey(file, shapeSection, "width", width, shapeType);
                requireKey(file, shapeSection, "length", length, shapeType);
            }
            case CONE -> {
                requireKey(file, shapeSection, "radius", radius, shapeType);
                requireKey(file, shapeSection, "angle", angle, shapeType);
            }
            case SINGLE -> {
                // 单体：没有参数，这是正确的
            }
        }
        Spell.Origin origin = Spell.Origin.IMPACT;
        if (shapeSection.isSet("origin")) {
            origin = requireEnum(file, shapeSection, "origin", Spell.Origin.class);
        }
        Spell.Shape shape = new Spell.Shape(shapeType, radius, width, length, angle, origin);

        List<Spell.Effect> effects = parseEffects(file, spell, "spell");

        // ★★ 2026-10-17 第十轮（缺陷 P1-a 的「无特效」）：观感块 `spell.fx`
        //   —— docs/19 给每张法术都写了「特效」列（火焰粒子环 / 打击粒子环 + 音效），
        //      而在此之前**根本没有字段可以写它**（出厂 fireball.yml 的注释自己承认了这点）。
        Spell.Fx fx = parseFx(file, spell);

        // 记录 CAST_SPELL 的引用，第二遍校验（被引用的卡可能还没读到）
        for (int i = 0; i < effects.size(); i++) {
            Spell.Effect effect = effects.get(i);
            if (effect.type() == Spell.EffectType.CAST_SPELL) {
                spellRefs.add(new SpellRef(effect.text("spell"), file, "spell.effects[" + i + "].spell"));
            }
        }
        // ★★★ 1.1.0（大旋风 / 凋灵领域）：`spell.scope` —— 形状覆盖到的敌方实体里准哪几类进来。
        //   缺省 `ALL`（含目标物载体）⇒ 既有 15 张法术卡行为一字未变。
        Spell.Scope scope = Spell.Scope.ALL;
        if (spell.isSet("scope")) {
            scope = requireEnum(file, spell, "scope", Spell.Scope.class);
        }
        return new Spell(targeting, shape, effects, fx, parseSelect(file, spell), scope);
    }

    /**
     * ★★ <b>解析 {@code spell.select}</b>（2026-10-17 批次 3 收尾新增）。
     *
     * <h2>文档依据（逐字）</h2>
     * {@code docs/19} 第 136 行（暴风雨）：「取范围内<b>血量最高的三名</b>敌人，
     * <b>依次</b>造成三次雷击」⇒ 形状（半径 6 圆形）负责"范围"，
     * 而这一格负责"范围里再挑谁"。
     *
     * <h2>形状（两把键，都可选）</h2>
     * <pre>
     * spell:
     *   select:
     *     rule: HIGHEST_HEALTH    # 目前只有这一档
     *     count: 3                # 取前几名（1~16）
     * </pre>
     * ★ <b>不写 {@code select} ⇒ 全都要</b>（= 既有的 14 张法术卡行为一字未变）。
     * ★ 写了 {@code select} 却缺 {@code count} ⇒ <b>拒绝启动</b>：
     * 「取前几名」没有合理缺省（猜 3 只对暴风雨成立，对将来别的卡就是我替它做决定）。
     *
     * @return {@code null} = 全都要
     */
    private static Spell.Select parseSelect(String file, ConfigurationSection spell) {
        if (!spell.isSet("select")) {
            return null;
        }
        ConfigurationSection section = ConfigReader.requireSection(file, spell, "select");
        Spell.Select.Rule rule = requireEnum(file, section, "rule", Spell.Select.Rule.class);
        int count = ConfigReader.requireInt(file, section, "count", 1, 16);
        return new Spell.Select(rule, count);
    }

    /**
     * ★ 解析可选的 {@code spell.fx} 块（粒子和/或音效）。
     *
     * <h2>校验到什么程度（诚实说明）</h2>
     * <ul>
     *   <li><b>粒子</b>：用 {@code Particle.valueOf} 逐字校验 —— 26.2 里 {@code Particle}
     *       仍是枚举（{@code javap} 实测：{@code public final class Particle extends Enum<Particle>}）
     *       ⇒ <b>离线也能校验</b>，写错常量名会在**装载期**就报出「文件 + 键路径」；</li>
     *   <li><b>音效</b>：只做格式校验（{@code 小写字母/数字/./_}）—— 音效注册表
     *       （{@code Registry.SOUNDS}）需要真实服务端才能查，配置层是纯的
     *       （{@code ShippedCardsTest} 要求出厂卡能离线加载）⇒ 名字写错的表现是
     *       「这一条音效没响」（不抛、不影响伤害），已登记 {@code docs/27}。</li>
     *   <li><b>{@code lightning}</b>（2026-10-18 新增）：布尔；{@code true} ⇒ 在每个被命中的
     *       目标头上劈一道<b>纯视觉</b>闪电。★ 默认 {@code false} —— 用户裁定
     *       「**暴风雨的落雷+海王的落泪（雷）；其他全去掉**」，所以只有暴风雨写它。</li>
     * </ul>
     *
     * @return 观感块；没写 {@code fx} ⇒ {@code null}（= 不播，行为与第十轮之前一致）
     */
    private static Spell.Fx parseFx(String file, ConfigurationSection spell) {
        // ★ 判据是「键在不在」而不是 isSet：`fx:` 后面什么都不写时 isSet 返回 false
        //   （YAML 里它是个 null 值）⇒ 那样会**静默**变成"没有特效"，
        //   而这正是本项目最讨厌的失败方式 ⇒ 空 fx 段必须报出来。
        if (!spell.getKeys(false).contains("fx")) {
            return null;
        }
        ConfigurationSection fx = ConfigReader.requireSection(file, spell, "fx");
        String particle = fx.isSet("particle")
                ? ConfigReader.requireString(file, fx, "particle").trim().toUpperCase()
                : null;
        if (particle != null) {
            // ★★★ 2026-10-17 第十三轮实测更正：这里原来调 `Particle.valueOf(particle)`。
            //   在**服务端**上它能工作（枚举常量齐全），但本项目要求「出厂卡能**离线**加载」
            //   （`ShippedCardsTest` 等一大批测试都在离线跑），而离线时 `Particle` 的
            //   注册表没建起来 ⇒ **连 `FLAME` 都会被判成"不合法"**。
            //   现场表现极具迷惑性：27 个测试一起报「`SPEED` 不是合法的原版粒子名」，
            //   而真正的错误在那句 `valueOf` 上（★ 这就是 AGENTS.md §4.1 那条
            //   「离线用会碰注册表的 API ⇒ 报错指向别处」的同一个坑）。
            //   ⇒ 改成**纯字段存在性检查**（`Enum.valueOf` 走 `Class#getEnumConstants` 的等价物，
            //     不碰任何注册表）：既能离线跑，又保留"写错粒子名，装载期拒绝启动"这条硬约束。
            if (!hasEnumConstant(org.bukkit.Particle.class, particle)) {
                throw new ConfigError(file, fx.getCurrentPath() + ".particle",
                        "" + particle + " 不是合法的原版粒子名（要 Particle 的常量名，全大写，例如 FLAME / "
                                + "ENCHANTED_HIT / ENTITY_EFFECT）—— 写错会在装载期就拒绝启动，"
                                + "而不是让这张卡的法术「没有特效」却查不出原因"
                                + "（老版本里叫 SPEED 的那个粒子，26.2 里叫 ENTITY_EFFECT）");
            }
        }
        String sound = fx.isSet("sound")
                ? ConfigReader.requireString(file, fx, "sound").trim()
                : null;
        if (sound != null && !SOUND_PATTERN.matcher(sound).matches()) {
            throw new ConfigError(file, fx.getCurrentPath() + ".sound",
                    "" + sound + " 不是合法的音效键（要原版键名，全小写，例如 entity.blaze.shoot；"
                            + "音效注册表需要服务端才能查，所以这里只校验格式）");
        }
        // ★★ 2026-10-18：方块粒子（用户「掘墓人依旧缺失特效」的修法）。
        //   根因：`ITEM_SNOWBALL` 这类粒子**需要 data 参数**，不传 data ⇒ 客户端不渲染
        //   ⇒ 上一轮那行配置"装得进去、也不报错，但屏幕上什么都没有"。
        //   ⇒ 现在写 `block: DIRT` 就走 `Particle.BLOCK` + 该方块的 BlockData。
        String block = fx.isSet("block")
                ? ConfigReader.requireString(file, fx, "block").trim().toUpperCase()
                : null;
        if (block != null) {
            if (!hasEnumConstant(org.bukkit.Material.class, block)) {
                throw new ConfigError(file, fx.getCurrentPath() + ".block",
                        "" + block + " 不是合法的原版方块/材料名（要 Material 的常量名，全大写，例如 DIRT / GRAVEL）");
            }
            // ★★★ 1.1.0（地壳震击）：**允许同时写 particle 与 block** —— 语义是
            //   「粒子画范围的**边界环**，方块粒子铺**环内区域**」（用户逐字：
            //   「使用火焰粒子环+在环内区域持续产生泥土破碎粒子」）。
            //   ★ 只写 block（掘墓者）仍然是"沿边界撒方块粒子"，行为一字未变；
            //     执行层见 {@code SpellCaster#emitFx} 的 allCells 分支。
        }
        // ★★ 2026-10-18：**纯视觉闪电**（键 `fx.lightning`，用户裁定
        //   「暴风雨的落雷+海王的落泪（雷）；其他全去掉」）。
        //   之前判据是"这张法术有 DAMAGE，劈闪电" ⇒ 14 张伤害法术全都在劈
        //   （用户原话：「法术牌依旧保留着雷击特效，不需要这个」）。
        //   ⇒ 改成卡片自己声明；不写 ⇒ false ⇒ 一条闪电都没有。
        boolean lightning = fx.isSet("lightning") && ConfigReader.requireBoolean(file, fx, "lightning");
        // ★★★ 2026-10-21 新增（灵狐引路的召唤前摇）：
        //   `fx.pillar: <粒子名>` = 施法点向上打一条**光柱**；`fx.pillar-ticks` = 它持续多少 tick。
        //   ★ 粒子名必须合法（与 particle 同一套校验）；两者必须成对出现（只写一个 = 配置错误）。
        String pillar = fx.isSet("pillar")
                ? ConfigReader.requireString(file, fx, "pillar").trim().toUpperCase()
                : null;
        int pillarTicks = fx.isSet("pillar-ticks")
                ? ConfigReader.requireInt(file, fx, "pillar-ticks", 1, 20 * 60)
                : 0;
        if (pillar != null && !hasEnumConstant(org.bukkit.Particle.class, pillar)) {
            throw new ConfigError(file, fx.getCurrentPath() + ".pillar",
                    "「" + pillar + "」不是合法的粒子常量名（全大写，例如 DRAGON_BREATH）");
        }
        if ((pillar == null) != (pillarTicks == 0)) {
            throw new ConfigError(file, fx.getCurrentPath(),
                    "`pillar` 与 `pillar-ticks` 必须成对出现（一个给粒子、一个给持续 tick）");
        }
        if (particle == null && sound == null && block == null && !lightning && pillar == null) {
            throw new ConfigError(file, fx.getCurrentPath(),
                    "fx 段至少要给 particle / block / sound / lightning / pillar 之一；"
                            + "都不给，请整段删掉（不写 fx = 不播特效）");
        }
        return new Spell.Fx(particle, sound, block, lightning, pillar, pillarTicks);
    }

    /** 音效键的格式（原版键名：小写字母 / 数字 / 点 / 下划线）。 */
    private static final java.util.regex.Pattern SOUND_PATTERN =
            java.util.regex.Pattern.compile("[a-z0-9_.]+");

    private static void requireKey(String file, ConfigurationSection section, String key,
                                   Double value, Spell.ShapeType shape) {
        if (value == null) {
            throw new ConfigError(file, section.getCurrentPath() + "." + key,
                    "形状 " + shape + " 需要这个参数");
        }
    }

    private static String stripExtension(String fileName) {
        return fileName.endsWith(".yml") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    private static <E extends Enum<E>> E requireEnum(String file, ConfigurationSection section,
                                                     String key, Class<E> type) {
        return parseEnum(file, ConfigReader.requireString(file, section, key),
                section.getCurrentPath() + "." + key, type);
    }

    private static <E extends Enum<E>> E parseEnum(String file, String raw, String path, Class<E> type) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConfigError(file, path,
                    "未知的值 " + raw + "（合法值：" + List.of(type.getEnumConstants()) + "）");
        }
    }
}
