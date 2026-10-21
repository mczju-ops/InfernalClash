package io.mczju.infernalclash.config;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 已校验的显示文本配置（{@code display.yml}）—— **R76「所有显示文本可配置」的落点**。
 *
 * <p><b>纯数据对象</b>：只依赖 {@link ConfigurationSection}，**不碰服务端** ⇒
 * {@link #load(File)} 能在 JUnit 里离线校验**出厂文件**（见 {@code ShippedDisplayTest}）。
 *
 * <h2>★ 键结构以 {@code docs/03} §5.2 为权威</h2>
 * <ul>
 *   <li>{@code messages}：**扁平的点号键**（{@code shop.title} / {@code errors.not-enough-gold} …），
 *       与 §5.2 的示例逐字一致；值是非空 MiniMessage 字符串，
 *       {@code shop.buy-pack-lore} 这类是字符串列表；</li>
 *   <li>{@code icons}：**一层嵌套**的对象（{@code icons.shop.buy1} =
 *       {@code { material: …, name: … }}），同样是 §5.2 的示例形状。</li>
 * </ul>
 *
 * <h2>★ 为什么未知键也要报错</h2>
 * 「未知键静默忽略」会让拼错的键（{@code shop.buy5}）看起来配好了却毫无作用 ——
 * 与「缺键静默取默认值」是同一类事故，也是本项目其它加载器（{@code EconomyConfig} /
 * {@code CardCatalog}）一致的口径。
 *
 * <p>★ 未知键的判据是**代码里的键表**（{@link #MESSAGE_KEYS} / {@link #ICON_KEYS}），
 * 所以「加一条新文案」必须同时动出厂文件与这张表 —— 这是有意的：
 * 两端都在版本控制里，**漏一端就会当场红**；而 R76 要的是「文案不用改代码」，
 * 不是「键不用改代码」（键改了代码要跟着读，本来就是一体的）。
 *
 * <h2>★ 缺键一律抛 {@link ConfigError}，不返回默认值</h2>
 * {@link #text(String)} / {@link #format(String, Map)} 走的是**调用方写下的字面键名**，
 * 键不存在时抛异常并带上**从根算起的完整键路径**（如 {@code messages.shop.title}）。
 * 取不到就报错，绝不打印一个占位稿给玩家。
 *
 * <h2>★ {@link #format} 少给占位符 → 报错</h2>
 * 文本里的 {@code {gold}} / {@code {count}} / {@code {price}} / {@code {cost}} / {@code {card}}
 * 必须在占位符表里全部给出。缺一个就抛 {@link ConfigError} ——
 * 「静默留一个 {@code {gold}} 印在玩家脸上」比抛异常难查得多。
 *
 * <h2>★ 内容纪律：不发明玩法文案（铁律 11）</h2>
 * 出厂 {@code display.yml} 里凡是 {@code docs/03} §5.2 / {@code docs/09} / {@code docs/10}
 * 找不到措辞依据的条目，一律留成字面值 {@code "[待填]"}（**不是**缺键），
 * 由用户在接线前拍板。{@code [待填]} 是**合法值**，本类不对它做特殊处理 ——
 * 「缺键」才硬失败。
 */
public final class DisplayConfig {

    /** 本配置对应的文件名（报错与迁移都用它）。 */
    public static final String FILE = "display.yml";

    /** 顶层段落：文案。 */
    public static final String SECTION_MESSAGES = "messages";

    /** 顶层段落：图标。 */
    public static final String SECTION_ICONS = "icons";

    /** 「待用户拍板」的占位值（出厂文件里用它标出措辞未定的条目）。 */
    public static final String PLACEHOLDER = "[待填]";

    /**
     * 占位符语法：{@code {name}}，名字只允许字母 / 数字 / 下划线 / 点。
     *
     * <p>★ 刻意**不**用宽松的 {@code \{[^}]*\}}：MiniMessage 本身的标签是 {@code <…>}，
     * 与这里不冲突，但把 `{` 的匹配收紧能避免误伤玩家名 / 卡名里出现的花括号。
     */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([A-Za-z0-9_.]+)}");

    // ------------------------------------------------------------------ 已知键表

    /**
     * {@code messages} 段**允许**的键（= 出厂文件里定义的全部条目）。
     *
     * <p>出处逐条写在出厂 {@code display.yml} 的注释里；权威结构见 {@code docs/03} §5.2。
     */
    private static final Set<String> MESSAGE_KEYS = new LinkedHashSet<>(List.of(
            // U5 局外商店（docs/09 §4）：标题 / 金币 / 关闭 / 购买按钮 / 购买按钮 lore
            "shop.title", "shop.balance", "shop.close", "shop.buy-pack", "shop.buy-pack-lore",
            // ★★ 2026-10-19（用户「开包行为为什么还没接入？请你帮我调查并接入」）：
            //   开包结果的三条文案（表头 / 逐张一行 / 金币不足）
            "shop.pack-result", "shop.pack-line", "shop.pack-not-enough",
            // U2 图鉴 + 卡组预设（docs/09 §3）：标题 / 保存预设 / 解锁确认
            // ★ 2026-10-17 第十三轮新增 deck.next-preset（用户裁定「添加『下一个预设』按钮」）
            // ★ 2026-10-17 批次 3 后半新增 collection.unlock-title（U3 那个二级界面的**标题**；
            //   与 collection.unlock-confirm 是两件事：后者是确认句、写在按钮 lore 里）
            "collection.title", "deck.save", "deck.next-preset", "collection.unlock-confirm",
            "collection.unlock-title",
            // ★ 排行榜**显示名**（docs/03 §5.2 / §9.1 的 2026-10-15-J 裁定）——
            //   ★ 它**本来就在**，这一行是 2026-10-17 第十三轮补注释时**差点被吃掉**的那个键
            //     （同一批 diff 里我删了旧行、新行漏抄它 ⇒ 幸好 `ShippedDisplayTest` 会当场红）。
            "leaderboard.title",
            // ★★ 2026-10-17 第十三轮新增：战斗 HUD 的三把文案键（用户裁定「HUD 文案进 display.yml」）。
            //   原来的值在 ui/MatchHud 里是代码常量（TITLE_REGULAR / TITLE_OVERTIME / SIDEBAR_TITLE）
            //   —— R76 要求「所有显示文本可配置」⇒ 这三条是**欠账**，本轮补上。
            //   ★ 阶段行那四个词（准备阶段/战斗中/双倍经验/加时）**不进配置**：
            //     它们与 `MatchTimerPolicy#phaseName` / boss 条 / 聊天播报**共用同一处映射**，
            //     进配置就会变成第二处事实来源（改一处、漏一处 = 界面与播报不一致）。
            "match.hud-title", "match.hud-overtime", "match.hud-sidebar",
            // ★ 排行榜的**副标题**（用户裁定「标题、副标题进，其余不进」）。
            //   标题（leaderboard.title）本来就在；★ **行文案格式不进**（用户明确说「其余不进」）。
            "leaderboard.subtitle",
            // U4 比赛内选卡（docs/09 §5）：标题 / 确认卡组
            "match.select-title", "match.confirm",
            // ★ 2026-10-16 第八轮新增三把键：U4 的提示行 + 准备阶段「选卡菜单」物品（R15）
            //   ★ 文档依据：docs/02 §2 的 R15（物品名与"右键任意位置"的提示）、
            //     docs/09 §5.4（打开的是 U4 本身）；措辞由我拟初稿（与 R76 的「文案可配置」一致），
            //     用户随时可改值（改值不用改代码）。
            "match.select-hint", "match.prep-menu", "match.prep-menu-lore",
            // ★ 投降菜单：2026-10-17 第十二轮新增 —— 界面本体做了（docs/27 §D7），
            //   标题 / 确认 / 取消三条文案按 R76「所有显示文本可配置」落在这里。
            //   ★★ 2026-10-17 用户裁定（docs/05 E26 结案，逐字）：「战斗菜单的物品改名『投降』，
            //      内部只有两个按钮『确认投降』和『取消』，对称排布」
            //      ⇒ 标题键 = match.surrender（=「投降」）、确认键 = match.surrender-confirm。
            "match.surrender", "match.surrender-confirm", "match.surrender-cancel",
            // ★ 2026-10-21 新增（`match.surrender-hint`）：1 号格「投降」物品的 lore
            "match.surrender-hint",
            // ★ match.battle-menu-pending 是 2026-10-15 建的**历史键**：那时菜单还没做，
            //   它的值是「战斗菜单尚未开放」。第十二轮菜单做出来之后，它退化成
            //   「菜单打不开时的兜底提示」—— **保留**（不删键 = 不改用户已跑过的配置），
            //   由 ShippedDisplayTest 钉住它仍在清单里。
            "match.battle-menu-pending",
            // ★★ 2026-10-19：`/incl bot on` 之后「还在等第二名玩家」那一行
            //   （用户：「之前要求删掉的大段带用户裁定的文本又回来了」⇒ 缩成一行 + 可配）
            "match.bot-window",
            // ★★★ 2026-10-21（用户逐字）：「**对局结束时需要在对话框里标注获得的金币数目**」
            //   ⇒ 结算成功后逐个玩家发一行；首通那一局多一行。
            //   ★ 是**两条**键而不是一条带 `{detail}` 的：首通行只在真的首通时出现，
            //     合并成一条就得在代码里拼字符串（那是"第二处文案来源"）。
            "match.payout", "match.payout-first-game",
            // 错误提示（docs/03 §5.2 逐字给出）
            "errors.not-enough-gold", "errors.not-enough-powder", "errors.not-your-half",
            // ★ 代码里**现有**的面向玩家中文（2026-10-15 建键；本轮**只建键不接线**）
            //   逐条的代码出处（文件:行）与「现状（代码里的措辞，待用户裁定）」
            //   都写在出厂 display.yml 的注释里；措辞一律 [待填]（铁律 8 / 11）。
            "errors.not-in-match", "errors.not-battle-phase", "errors.no-side", "errors.unknown-card",
            // ★ errors.not-enough-xp 在代码里**同一句出现两次**（召唤 :130 与法术 :167）
            //   ⇒ 只立这一个键，接线时两处都改读它（不许出现第二处文案来源）
            "errors.not-enough-xp",
            "errors.summon-failed-refunded", "errors.spell-failed-refunded",
            // ★★ 2026-10-18：冷却拦截（用户「冷却系统完全没有实现」）
            "errors.card-on-cooldown",
            // ★★★ 2026-10-18：治愈术那一档「必须先用准星瞄到友方」
            //   （用户「必须用准星指着友方，如果没有选到目标就不使用，不消耗费用和冷却」）
            "errors.spell-needs-ally",
            "errors.half-not-configured", "errors.summon-refused",
            "errors.summons-cannot-be-attacked",
            // ★★ 2026-10-19：回响「不能当第一张牌打出」（用户逐字口径）
            "errors.echo-no-previous",
            // /incl 提示（docs/10 只给指令名与权限 ⇒ 措辞留 [待填]）
            "command.no-permission", "command.players-only", "command.usage", "command.debug-usage",
            "command.room-usage", "command.reload-ok", "command.reload-failed",
            "command.room-reset-done", "command.room-none", "command.player-not-found",
            "command.invalid-amount",
            // ★★ 2026-10-19（用户：「为什么还有很多文本无法在 display.yml 里修改？
            //   （开启 bot 后的那一大串提示文本，我想要修改）」）——
            //   把 `/incl bot on|off` 的那一大串提示搬进配置。逐条对应 runBot 里原来硬编码的行：
            //   header = 第一行；on = "已添加电脑玩家"；off / off-hint = 关闭时那两行。
            // ★★ 2026-10-19 第二次裁定（用户逐字：「bot那一大串提示**我希望在开启人机时
            //   只显示一行『已添加电脑玩家』**，然后再不显示别的东西」）⇒ 开启方向只发 `bot-on`
            //   一行；`bot-wait` / `bot-wait-play` / `bot-wait-bot` / `bot-idle` 四条
            //   **已移进 {@link #RETIRED_MESSAGE_KEYS}**（现场留着不报错、没有代码读）。
            //   ★ header 保留：它现在只在 `/incl bot off` 时打印（关闭方向未改）。
            "command.bot-header", "command.bot-on",
            "command.bot-off", "command.bot-off-hint",
            // U1 竞技场编辑（docs/09 §2 只给交互模型 ⇒ 措辞留 [待填]）
            "arena-edit.title", "arena-edit.point", "arena-edit.bind", "arena-edit.teleport",
            "arena-edit.point-empty"));

    /**
     * ★★★ <b>「已退役」的文案键</b>（**允许存在、但不要求存在、且没有任何代码读它**）。
     *
     * <h2>为什么必须有这样一档（这是一次**真实的**部署事故的防线）</h2>
     * 本项目对现场配置（{@code plugins/InfernalClash/display.yml}）的口径是**只增不改**
     * （{@code AGENTS.md} §4.2：改出厂默认对"已经跑过一次"的服务器无效）
     * ⇒ 一旦某把键**改名**，就会出现「**出厂文件有新的、现场文件有旧的**」的窗口期。
     * 而 {@link #MESSAGE_KEYS} 的校验是**双向**的：
     * <ul>
     *   <li>文件里有**未知**键 ⇒ 拒绝启动；</li>
     *   <li>{@code MESSAGE_KEYS} 里有**缺失**键 ⇒ 拒绝启动。</li>
     * </ul>
     * ⇒ 把旧名留在 {@code MESSAGE_KEYS} 里会让**出厂文件缺这把键**（第二条判据红）；
     *   而把它删掉、现场又还留着旧名，会让**用户一升级就拒绝启动**（第一条判据红）。
     * ⇒ **两档分开**：本集合里的键**允许出现**（第一条判据放行），但**不要求出现**
     *   （第二条判据跳过）。★ 这就是"改名"这类变动的唯一安全形状。
     *
     * <h2>什么时候可以清掉一条</h2>
     * 只有在**确认所有现场都不再需要它**之后（本项目**没有**删除现场键的机制 ——
     * {@code ConfigMigrator} 只会补缺失键）⇒ 那时才能连这条一起删。★ 现在**一条都不许删**。
     */
    private static final Set<String> RETIRED_MESSAGE_KEYS = Set.of(
            // 2026-10-17 用户裁定「战斗菜单的物品改名『投降』」⇒ 标题键改用 match.surrender。
            // 出厂文件里已不存在（出厂用新名），而**现场可能还留着旧名** ⇒ 必须放行。
            "match.battle-title",
            // ★★ 2026-10-19 第二次裁定（逐字：「bot那一大串提示我希望在开启人机时
            //  只显示一行『已添加电脑玩家』，然后再不显示别的东西」）——
            //  这四条是**上一批刚加**的 `/incl bot on` 窗口期解释行，本轮因"只留一行"而下线。
            //  ★ 为什么不直接删：现场 `plugins/InfernalClash/display.yml` **已经**有这四条
            //    （上一批同步过去的就是它），而 {@link #MESSAGE_KEYS} 的校验是**双向**的
            //    ⇒ 删掉就会让用户一升级就看到「未知的文案键」+ 插件被禁用。
            //  ★ 它们承载的事实没有丢：20 tick 窗口 / 是否会上场 仍写在
            //    `InfernalClashCommand#runBot` 的控制台日志行里。
            "command.bot-wait", "command.bot-wait-play",
            "command.bot-wait-bot", "command.bot-idle");

    /**
     * {@code messages} 段里**多行**的键（其余都是单条字符串）。
     *
     * <p>出处：{@code docs/03} §5.2 的示例把 {@code shop.buy-pack-lore} 写成列表；
     * ★ {@code match.prep-menu-lore} 是 2026-10-16 第八轮新增的同类键（准备阶段「选卡菜单」
     * 物品的 lore，{@code docs/02} §2 的 R15）。
     */
    private static final Set<String> LORE_KEYS =
            Set.of("shop.buy-pack-lore", "match.prep-menu-lore");

    /**
     * {@code icons} 段允许的**组名**（与出厂文件的 {@code icons.shop} / {@code icons.collection} 对应）。
     */
    private static final Set<String> ICON_GROUPS = Set.of("shop", "collection");

    /** 已知组内允许的图标键（★ 只是**要求存在**的那一批；退役键见 {@link #RETIRED_ICON_KEYS}）。 */
    private static final Map<String, Set<String>> ICON_KEYS = Map.of(
            "shop", Set.of("buy1", "buy10", "exit"),
            "collection", Set.of("prev", "next", "locked"));

    /**
     * ★★ <b>退役的图标键</b>（2026-10-20 新增；与 {@link #RETIRED_MESSAGE_KEYS} 同一条通道）：
     * <b>允许存在、但不要求存在、没有代码读它</b>。
     *
     * <h2>为什么需要这一档（同一个坑第二次）</h2>
     * 2026-10-20 用户裁定「取消『连开 30 包』的商店选项」⇒ 出厂 {@code display.yml} 里删掉了
     * {@code icons.shop.buy30}。但本类对图标键的校验是**双向**的：
     * <ol>
     *   <li>文件里出现**未知键** ⇒ 拒绝启动（拼写错误保护）；</li>
     *   <li>{@link #ICON_KEYS} 里列出的键**必须存在** ⇒ 缺一个也拒绝启动。</li>
     * </ol>
     * 而现场（{@code plugins/InfernalClash/display.yml}）**已经**有 {@code buy30}
     * （上一批同步过去的就是它），且本项目**没有**删除现场键的机制
     * （{@code ConfigMigrator} 只补缺失键）⇒ 直接删会变成
     * 「用户一升级就看到『未知的图标键』+ 插件被禁用」，正是 {@code docs/28} 第 25 条那次事故的形状。
     * ⇒ 把 {@code buy30} 放进本表：现场留着**放行**，出厂不再要求。
     *
     * <h2>什么时候可以清掉</h2>
     * 与文案键同口径：只有在确认所有现场都不再需要它之后（★ 现在一条都不许删）。
     */
    private static final Map<String, Set<String>> RETIRED_ICON_KEYS = Map.of(
            // 2026-10-20：取消「连开 30 包」⇒ 该规格的按钮图标键退役（现场可能还留着）。
            "shop", Set.of("buy30"));

    /** 一个图标槽位：材质名 + 显示名（MiniMessage）。 */
    public record Icon(String material, String name) {
    }

    // ------------------------------------------------------------------ 字段

    private final int configVersion;
    private final Map<String, String> texts;
    private final Map<String, List<String>> loreTexts;
    private final Map<String, Icon> icons;

    /**
     * 唯一的构造器（{@code private}）：外部只能经 {@link #load} 拿到已校验的实例。
     *
     * <p>集合一律包成**保序的不可变视图**：{@code Map.copyOf} 的迭代顺序按 JVM 运行加盐
     * （{@code AGENTS.md} §4.2 的已知坑），而 {@code describe()} / 日志需要稳定顺序。
     */
    private DisplayConfig(int configVersion,
                          Map<String, String> texts,
                          Map<String, List<String>> loreTexts,
                          Map<String, Icon> icons) {
        this.configVersion = configVersion;
        this.texts = Collections.unmodifiableMap(new LinkedHashMap<>(texts));
        this.loreTexts = Collections.unmodifiableMap(new LinkedHashMap<>(loreTexts));
        this.icons = Collections.unmodifiableMap(new LinkedHashMap<>(icons));
    }

    // ------------------------------------------------------------------ 加载

    /** 从已解析的配置根节点加载并校验。 */
    public static DisplayConfig load(ConfigurationSection root) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "配置为空（文件不存在或解析失败）");
        }
        int version = ConfigReader.requireInt(FILE, root, "config-version", 1, 1_000_000);

        ConfigurationSection messages = ConfigReader.requireSection(FILE, root, SECTION_MESSAGES);
        Map<String, List<String>> leafKeys = new LinkedHashMap<>();
        flatten(messages, "", leafKeys);
        Map<String, String> texts = new LinkedHashMap<>();
        Map<String, List<String>> loreTexts = new LinkedHashMap<>();
        for (String key : leafKeys.keySet()) {
            if (!MESSAGE_KEYS.contains(key) && !RETIRED_MESSAGE_KEYS.contains(key)) {
                throw new ConfigError(FILE, SECTION_MESSAGES + "." + key,
                        "未知的文案键（合法键：" + new TreeSet<>(MESSAGE_KEYS) + "）");
            }
            if (leafKeys.get(key) != null) {
                loreTexts.put(key, ConfigReader.requireStringList(FILE, messages, key));
            } else {
                // ★★★ 2026-10-19：**允许空串**（= 玩家要求"这一行别显示"）。
                //   触发场景：用户在 `display.yml` 里把 `command.bot-off-hint` 改成 `""`，
                //   而 `requireString` 会以「不能为空字符串」**拒绝启动** ⇒ 整服起不来。
                //   ⇒ 键必须存在、类型必须是字符串，但**值可以是空的**；
                //     空值在发送侧被 {@code MenuTexts#isSilent} 认出来并跳过（不打印空行）。
                texts.put(key, requireTextAllowBlank(messages, key));
            }
        }

        ConfigurationSection iconSection = ConfigReader.requireSection(FILE, root, SECTION_ICONS);
        Map<String, Icon> icons = new LinkedHashMap<>();
        // ★ 未知的图标组也要报（例把 `collection` 拼成 `colletion`）—— 先于缺键检查，
        //   因为「拼错的组」与「缺失的组」只差一个字符，报错必须点名用户写下的那个键
        for (String group : iconSection.getKeys(false)) {
            if (!ICON_GROUPS.contains(group)) {
                throw new ConfigError(FILE, SECTION_ICONS + "." + group,
                        "未知的图标组（合法组：" + new TreeSet<>(ICON_GROUPS) + "；"
                                + "若这是拼写错误，请改成正确组名）");
            }
        }
        for (String group : new TreeSet<>(ICON_GROUPS)) {
            ConfigurationSection groupSection = requireObject(FILE, iconSection, group);
            // ★ 允许 = 必需键 ∪ 退役键（退役键允许存在、不要求存在、没有代码读）
            Set<String> allowed = new TreeSet<>(ICON_KEYS.get(group));
            allowed.addAll(RETIRED_ICON_KEYS.getOrDefault(group, Set.of()));
            for (String key : groupSection.getKeys(false)) {
                if (!allowed.contains(key)) {
                    throw new ConfigError(FILE, SECTION_ICONS + "." + group + "." + key,
                            "未知的图标键（该组合法键：" + new TreeSet<>(allowed) + "）");
                }
                ConfigurationSection entry = requireObject(FILE, groupSection, key);
                icons.put(group + "." + key, new Icon(
                        ConfigReader.requireString(FILE, entry, "material"),
                        ConfigReader.requireString(FILE, entry, "name")));
            }
        }
        // ★ 缺键检查**放在最后**：先让「用户真的写下的键」全部过一遍校验，
        //   否则「把 buy10 拼成 buy5」会先报「缺 buy10」，掩盖真正的拼写错误。
        //   文案与图标都是这个次序；只要一次只错一处，报出来的一定是**真正的那一处**。
        for (String key : new TreeSet<>(MESSAGE_KEYS)) {
            if (LORE_KEYS.contains(key)) {
                if (!loreTexts.containsKey(key)) {
                    throw new ConfigError(FILE, SECTION_MESSAGES + "." + key,
                            "缺少必需的多行文案键（应写成逐行 - 文本；不猜默认值）");
                }
            } else if (!texts.containsKey(key)) {
                throw new ConfigError(FILE, SECTION_MESSAGES + "." + key,
                        "缺少必需的文案键（不猜默认值：缺文案就等于界面上少一句话，"
                                + "静默回落只会让它在实机里才被发现）");
            }
        }
        for (String group : new TreeSet<>(ICON_GROUPS)) {
            for (String key : new TreeSet<>(ICON_KEYS.get(group))) {
                if (!icons.containsKey(group + "." + key)) {
                    throw new ConfigError(FILE, SECTION_ICONS + "." + group + "." + key,
                            "缺少必需的图标键（不猜默认值：图标缺一条就等于界面上少一格）");
                }
            }
        }

        return new DisplayConfig(version, texts, loreTexts, icons);
    }

    /** 从磁盘上的 YAML 文件加载（**离线可用**，不依赖服务端）。 */
    public static DisplayConfig load(File file) {
        // ★ 走 YamlFile：loadConfiguration(File) 在解析失败时会调 Bukkit.getLogger() ⇒ 离线 NPE
        return load(YamlFile.read(FILE, file));
    }

    // ------------------------------------------------------------------ 取值

    /**
     * 取一条文案的**原文**（不替换占位符，也不因占位符未填而报错）。
     *
     * <p>用途：迁移 / diff / 「这条文案有哪些占位符」这类**元信息**场景。
     * **面向玩家的输出一律走 {@link #format}** —— 原文里的 {@code {gold}} 不能直接印出去。
     *
     * @throws ConfigError 键不存在（带完整键路径）或该键是多行文本
     */
    public String rawText(String key) {
        String raw = texts.get(key);
        if (raw == null) {
            throw new ConfigError(FILE, path(key),
                    loreTexts.containsKey(key)
                            ? "该键是多行文本（列表），不能按单条文案取；请改用 lore(key)"
                            : "没有这条文案（缺文案不返回默认值）");
        }
        return raw;
    }

    /** 取一条多行文案的**原文**（不替换占位符）。 */
    public List<String> rawLore(String key) {
        List<String> raw = loreTexts.get(key);
        if (raw == null) {
            throw new ConfigError(FILE, path(key),
                    texts.containsKey(key) ? "该键是单条文案，不是列表" : "没有这条多行文案");
        }
        return raw;
    }

    /**
     * 取一条文案（MiniMessage 原文的**已替换**结果）。
     *
     * <p>★ 带占位符的条目**不能**走这里用原样（例 {@code shop.balance} 里的 {@code {gold}}
     * 必须要有个值）—— 真的没给占位符时，本方法会抛 {@link ConfigError}，
     * 而不是把 {@code {gold}} 印给玩家。
     *
     * @throws ConfigError 键不存在（带完整键路径），或该条目是列表，或占位符没被填
     */
    public String text(String key) {
        return format(key, Map.of());
    }

    /**
     * 取一条文案并替换占位符（{@code {name}} → 值）。
     *
     * @param placeholders 占位符名（**不含花括号**）→ 替换值
     * @throws ConfigError 键不存在 / 是列表 / **有占位符没给**（缺一个就报错，绝不静默保留）
     */
    public String format(String key, Map<String, String> placeholders) {
        return replace(rawText(key), key, placeholders, true);
    }

    /** 取一条多行文案（例 {@code shop.buy-pack-lore}），逐行同样校验占位符。 */
    public List<String> lore(String key) {
        return lore(key, Map.of());
    }

    /** 取一条多行文案并替换占位符（逐行替换，缺一个就报错）。 */
    public List<String> lore(String key, Map<String, String> placeholders) {
        return rawLore(key).stream().map(line -> replace(line, key, placeholders, false)).toList();
    }

    /**
     * 取一个槽位图标（材质 + 名称）。
     *
     * @param key 形如 {@code shop.buy1}（出厂文件里的 {@code icons.shop.buy1}）
     * @throws ConfigError 键不存在（带完整键路径 {@code icons.shop.buy1}）
     */
    public Icon icon(String key) {
        Icon icon = icons.get(key);
        if (icon == null) {
            throw new ConfigError(FILE, "icons." + key,
                    "没有这个图标键（合法键：" + new TreeSet<>(icons.keySet()) + "）");
        }
        return icon;
    }

    /** 该图标槽位的显示名（同样会校验未知键）。 */
    public String iconName(String key) {
        return icon(key).name();
    }

    // ------------------------------------------------------------------ 只读视图

    /** 配置结构版本号。 */
    public int configVersion() {
        return configVersion;
    }

    /** 单条文案的键（保序、不可变）。 */
    public Set<String> textKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(texts.keySet()));
    }

    /** 多行文案的键（保序、不可变）。 */
    public Set<String> loreKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(loreTexts.keySet()));
    }

    /**
     * ★★ <b>「必须存在」的文案键</b>（= {@link #MESSAGE_KEYS} ＋ {@link #LORE_KEYS}，
     * 保序、不可变）—— 给测试用：出厂文件必须**逐条**有这些键。
     *
     * <p>★ 与它成对的是 {@link #retiredMessageKeys()}：那一批**允许存在、但不要求存在**。
     * 两者**不允许有交集**（{@link #declaredMessageKeys()} 就是那条不变式的落点）。
     */
    public static Set<String> requiredMessageKeys() {
        Set<String> all = new LinkedHashSet<>(MESSAGE_KEYS);
        all.addAll(LORE_KEYS);
        return Collections.unmodifiableSet(all);
    }

    /**
     * ★★ <b>「已退役」的文案键</b>（= {@link #RETIRED_MESSAGE_KEYS}）——
     * 允许出现在现场文件里、**不要求**出现、且没有任何代码读它。
     *
     * <p>见 {@link #RETIRED_MESSAGE_KEYS} 的 javadoc：它就是"键改名"时的安全过渡位。
     */
    public static Set<String> retiredMessageKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(RETIRED_MESSAGE_KEYS));
    }

    /**
     * ★★ <b>全部被认可的文案键</b>（= 必须的 ∪ 已退役的）——
     * ★ 「出厂文件里的每个键都在这里面、且**恰好**等于必须的那一批」就是
     * 「出厂与代码没有分叉」的一条可断言判据（见 {@code ShippedDisplayTest}）。
     */
    public static Set<String> declaredMessageKeys() {
        Set<String> all = new LinkedHashSet<>(requiredMessageKeys());
        all.addAll(RETIRED_MESSAGE_KEYS);
        return Collections.unmodifiableSet(all);
    }

    /** 图标键（保序、不可变）。 */
    public Set<String> iconKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(icons.keySet()));
    }

    /**
     * ★★ <b>「必须存在」的图标键</b>（形如 {@code shop.buy1}，组名 + 键名，保序）——
     * 给测试用：出厂 {@code display.yml} 必须**逐条**有这些键。
     *
     * <p>★ 与它成对的是 {@link #retiredIconKeys()}：那一批**允许存在、但不要求存在**
     * （见 {@link #RETIRED_ICON_KEYS}）。两者**不允许有交集**。
     */
    public static Set<String> requiredIconKeys() {
        Set<String> all = new LinkedHashSet<>();
        for (String group : new TreeSet<>(ICON_GROUPS)) {
            for (String key : new TreeSet<>(ICON_KEYS.get(group))) {
                all.add(group + "." + key);
            }
        }
        return Collections.unmodifiableSet(all);
    }

    /**
     * ★★ <b>「已退役」的图标键</b>（= {@link #RETIRED_ICON_KEYS}）——
     * 允许出现在现场文件里、**不要求**出现、且没有任何代码读它。
     */
    public static Set<String> retiredIconKeys() {
        Set<String> all = new LinkedHashSet<>();
        for (String group : new TreeSet<>(RETIRED_ICON_KEYS.keySet())) {
            for (String key : new TreeSet<>(RETIRED_ICON_KEYS.get(group))) {
                all.add(group + "." + key);
            }
        }
        return Collections.unmodifiableSet(all);
    }

    /** 文案总条数（单条 + 多行，各算一条）。 */
    public int size() {
        return texts.size() + loreTexts.size();
    }

    /** 是否仍是未拍板的占位值（{@code [待填]}）。 */
    public boolean isPlaceholder(String key) {
        String raw = texts.get(key);
        return PLACEHOLDER.equals(raw == null ? null : raw.trim());
    }

    /** 启动日志用的一行摘要。 */
    public String describe() {
        return size() + " 条文案 / 结构版本 " + configVersion;
    }

    /** 启动日志用的明细：还有哪些条目是 {@code [待填]}（便于上线前对账）。 */
    public Set<String> pendingKeys() {
        Set<String> pending = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : texts.entrySet()) {
            if (PLACEHOLDER.equals(e.getValue().trim())) {
                pending.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(pending);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 把 {@code messages} 段摊平成「点号键 → 值」。
     *
     * <h2>★ 为什么不能直接 {@code getKeys(false)}</h2>
     * Bukkit 的 YAML 实现（{@code MemorySection}）会把**含点号的键当成路径**：
     * 出厂文件里写的
     * <pre>
     * messages:
     *   shop.title: "…"
     *   shop.close: "…"
     * </pre>
     * 在内存里是 {@code messages → shop → {title, close}} 的**嵌套结构**
     * （与 {@code docs/03} §5.2 的示例形状一致，但对本类不可见为扁平键）。
     * 若只取一层，看到的是 {@code shop} 这个「中间节点」，三种事故于是同时发生：
     * <ul>
     *   <li>必需键 {@code shop.title} 全部「缺失」；</li>
     *   <li>拼错的键 {@code shop.colse}（与 {@code shop.close} 同层）**根本看不见**；</li>
     *   <li>报错键路径只剩 {@code messages.shop}，运维不知道是哪一条。</li>
     * </ul>
     * ⇒ 递归摊平，并把键路径**重新拼成点号形式**，与文档 / 出厂文件逐一对应。
     *
     * <p>值是 {@code null} 表示叶子是标量，非 {@code null} 表示叶子是列表
     * （{@code shop.buy-pack-lore}）。
     */
    private static void flatten(ConfigurationSection section, String prefix,
                                Map<String, List<String>> out) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            ConfigurationSection child =
                    section.isConfigurationSection(key) ? section.getConfigurationSection(key) : null;
            if (child != null) {
                flatten(child, path, out);
                continue;
            }
            out.put(path, section.isList(key) ? section.getStringList(key) : null);
        }
    }

    /**
     * 读一个**必须是对象**的节点，报错带完整键路径。
     *
     * <p>与 {@link ConfigReader#requireSection} 的区别只在措辞：这里明确点名
     * 「应写成 {@code material: … / name: …} 的对象」，因为图标写成一行字符串
     * （{@code buy1: "…"}）是很自然的手滑。
     */
    /**
     * ★ <b>读一条文案，允许空串</b>（2026-10-19）。
     *
     * <p>与 {@link ConfigReader#requireString} 的差别只有一条：**空白值不报错**。
     * 「缺键」与「类型不是字符串」照旧硬失败（那才是真的配错）；
     * 而「值写成 {@code ""}」是玩家明确的意图：**这一行不要了**。
     * ★ 发送侧用 {@link #isBlank} / {@code ui/MenuTexts#isSilent} 认出来并**跳过发送**，
     * 于是既不会打印空行，也不需要玩家去写一个"看起来像占位符"的字符。
     */
    private static String requireTextAllowBlank(ConfigurationSection section, String key) {
        if (!section.isString(key)) {
            throw new ConfigError(FILE, path(key), section.isSet(key)
                    ? "类型不是字符串（当前值：" + section.get(key) + "）"
                    : "缺少必需的文案键（不猜默认值：缺文案就等于界面上少一句话）");
        }
        return section.getString(key, "");
    }

    /**
     * ★ 这条文案是不是**被玩家留空了**（= 不要显示这一行）。
     *
     * <p>用途：发送侧（{@code InfernalClashCommand#tell} / {@code CardCastListener#tell} /
     * {@code InfernalClashGame#botWindowText}）据此**跳过发送**，而不是发一个空 {@code Component}
     * （那会在聊天栏留一条空行，玩家会以为"点了没反应"）。
     *
     * @throws ConfigError 键不存在 / 是多行文案
     */
    public boolean isBlank(String key) {
        return rawText(key).isBlank();
    }

    private static ConfigurationSection requireObject(String file, ConfigurationSection parent,
                                                      String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            throw new ConfigError(file, parent.getCurrentPath() + "." + key,
                    parent.isSet(key)
                            ? "该键存在但不是对象节点（图标应写成 { material: …, name: … }）"
                            : "缺少必需的对象节点（图标应写成 { material: …, name: … }）");
        }
        return section;
    }

    /**
     * 报错用的完整键路径（从根算起）：{@code messages.<key>}。
     *
     * <p>★ 本方法**只服务 {@code messages} 段**（{@link #text} / {@link #format} /
     * {@link #lore} / {@link #rawText} / {@link #rawLore} 与占位符校验），
     * 所以直接加上 {@code messages.} 前缀即可；图标键的路径由 {@link #icon} 自己拼
     * （{@code icons.<group>.<key>}）。
     *
     * <p>⚠️ 曾经的写法是「按**第一段**是否像图标组名来判断该加哪个前缀」——
     * 因为图标组里有 {@code shop}，于是 {@code shop.balance} / {@code shop.titl}
     * 被误判成 {@code icons.shop.*}（本类测试当场抓出）。**判据必须是键表，不是名字长得像。**
     */
    private static String path(String key) {
        return SECTION_MESSAGES + "." + key;
    }

    private static String replace(String raw, String key, Map<String, String> placeholders,
                                  boolean single) {
        Map<String, String> values = placeholders == null ? Map.of() : placeholders;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(raw);
        StringBuilder out = new StringBuilder();
        Set<String> missing = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                // ★ 静默留 {name} 是「配了却没生效」的另一种形态 ⇒ 硬失败
                missing.add(name);
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        if (!missing.isEmpty()) {
            throw new ConfigError(FILE, path(key),
                    "缺少占位符 " + missing + "（该文案需要：" + placeholderNames(raw)
                            + "；不给值就不许把 " + missing.iterator().next()
                            + " 原样" + (single ? "印给玩家" : "写进 lore") + "）");
        }
        return out.toString();
    }

    /** 列出某条文本里出现的全部占位符名（报错信息用）。 */
    private static Set<String> placeholderNames(String raw) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(raw);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
