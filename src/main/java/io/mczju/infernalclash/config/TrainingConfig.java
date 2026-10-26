package io.mczju.infernalclash.config;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@code config.yml} 的 {@code training} 段（训练人机）。
 *
 * <h2>★★★ 2026-10-21 用户裁定：多套人机 ＋ 人机法术规则</h2>
 * 权威规格 = {@code docs/31-多套人机与法术施放.md}；逐字原话留档 {@code docs/24} §23。
 * 本段现在有两个键（<b>旧的 {@code bot-deck} 已作废</b>）：
 *
 * <pre>
 * training:
 *   bots:                       ← ★ 多套人机：每局随机抽一个
 *     - name: 训练人机              （侧边栏与日志里的名字；纯文本；互不重复）
 *       deck: [ … 正好 8 项 … ]     （= 槽位 2~9 的出牌顺序；可写「随机」）
 *   spell-rules:                ← ★ 每张法术卡一条【全局】规则
 *     fireball: NEAREST_ENEMY
 *     healing: RANDOM_ALLY_TARGET
 * </pre>
 *
 * <h2>★ 三条不能动的硬约束（都是用户原话）</h2>
 * <ol>
 *   <li><b>卡组正好 8 项</b>（2026-10-15：「卡组需要正好 8 张」）——
 *       快捷栏的卡位就是 2~9 号格 8 格；少一张 = 天然少一张牌，多一张 = 被静默忽略；</li>
 *   <li><b>人机不能带 速度药水 / 战术冷却</b>（2026-10-21 原话）＋ <b>回响</b>
 *       （用户在同轮问答里选的候选池规则：它依赖"上一张牌"链路，人机不走那条路）
 *       ⇒ 见 {@link #BOT_FORBIDDEN_CARDS}；</li>
 *   <li><b>「随机」占位符</b>：进入对战时替换成任意一张随机合法卡牌，且
 *       「不能重复」（与卡组里写死的卡、以及彼此之间）。</li>
 * </ol>
 *
 * <h2>★ 本类<b>不</b>做的事（分工写清楚，免得后人往这里塞）</h2>
 * <ul>
 *   <li><b>不认识卡池</b>：这里只管「形状对不对」（列表 / 张数 / 空项 / 重复 / 名字 / 枚举值）。
 *       「这个 id 真的存在吗、是召唤卡吗、这个法术卡能人机用吗」属于<b>交叉校验</b>，
 *       它需要 {@code card/CardCatalog} ⇒ 放在装配期（{@link #crossCheckBots} /
 *       {@link #crossCheckSpellRules} 给出判据与文案，{@code InfernalClashPlugin} 决定抛不抛）。
 *       理由与 {@code ObjectiveConfig} 一致：<b>纯配置层必须能在普通 JUnit 里加载出厂文件</b>。</li>
 *   <li><b>不做随机</b>：「抽哪个人机 / 把『随机』换成哪张卡」是<b>纯策略</b>，在
 *       {@code ai/BotDeckResolver}（可注入种子 ⇒ 同种子同结果）。本类只提供
 *       「哪些令牌算随机」「哪些卡可用」这两条判据。</li>
 * </ul>
 *
 * <h2>★★ 交叉校验：法术卡现在**允许**，但必须配规则</h2>
 * 2026-10-21 之前（R60）法术卡是**拒绝**的（「人机不会放法术」）。
 * 本轮那条被用户裁定取代 ⇒ 判据改成：<b>法术卡可以进卡组，但它必须在
 * {@code training.spell-rules} 里有一条规则</b>；否则它对人机是「不可用卡」，
 * 写进卡组就<b>拒绝启动</b>（而不是表现成"人机少打一张牌"）。
 *
 * <p>★ <b>本类刻意不 import {@code card/**}</b>（配置层不依赖卡牌层）：
 * 装配期把「怎么从卡对象上取 id / 是不是召唤卡 / 费用 / targeting / 是不是建筑」用 {@link DeckCard}
 * 适配器传进来。这样本类仍然只需要 {@code ConfigurationSection} + JDK，能在普通 JUnit 里跑。
 *
 * <h2>★★★ 2026-10-21 追加：建筑类召唤牌的落点（{@code building-placements} / {@code building-front}）</h2>
 * 权威规格 = {@code docs/32-建筑类召唤牌落点规则.md}。两个新键：
 * <pre>
 * training:
 *   building-placements:        ← 卡 id → BACK / CENTER / FRONT（不写 = 走普通召唤规则）
 *     frog_parasite: BACK
 *     sentry: CENTER
 *   building-front:             ← 「前 / 后」的方向（四方位，按世界坐标轴）
 *     red: WEST
 *     blue: EAST
 * </pre>
 * ★ 「前 / 后」刻意做成**配置**而不是写死 −X/+X：竞技场是 {@code arenas.yml} 里可编辑的数据，
 * 换场地后写死的轴会让建筑静默放到错的一侧（{@code docs/32} §2.1）。
 */
public record TrainingConfig(List<BotSpec> bots, Map<String, BotSpellRule> spellRules,
                             Map<String, BuildingPlacementRule> buildingPlacements,
                             Map<io.mczju.infernalclash.match.Side, String> buildingFront) {

    /** 本配置所在的文件名（{@code training} 段在 {@code config.yml} 里）。 */
    public static final String FILE = "config.yml";

    /** 键路径前缀（报错一律给**从根算起**的完整键路径，见 {@code AGENTS.md} §3）。 */
    public static final String SECTION_PATH = "training";

    /** 多套人机的键名（相对 {@code training} 段）。 */
    public static final String KEY_BOTS = "bots";

    /** 法术规则的键名（相对 {@code training} 段）。 */
    public static final String KEY_SPELL_RULES = "spell-rules";

    /** ★ 建筑类召唤牌落点规则的键名（2026-10-21 新增）。 */
    public static final String KEY_BUILDING_PLACEMENTS = "building-placements";

    /** ★ 「前 / 后」方向的键名（2026-10-21 新增，取值 = 四方位名）。 */
    public static final String KEY_BUILDING_FRONT = "building-front";

    /** ★ 已作废的旧键：出现即「存在未知键」（2026-10-21 被 {@link #KEY_BOTS} 取代）。 */
    public static final String RETIRED_KEY_BOT_DECK = "bot-deck";

    /** 从根算起的完整键路径（报错 / 日志用）。 */
    public static final String PATH_BOTS = SECTION_PATH + "." + KEY_BOTS;

    /** 法术规则表的完整键路径。 */
    public static final String PATH_SPELL_RULES = SECTION_PATH + "." + KEY_SPELL_RULES;

    /** 建筑落点表的完整键路径。 */
    public static final String PATH_BUILDING_PLACEMENTS =
            SECTION_PATH + "." + KEY_BUILDING_PLACEMENTS;

    /** 「前 / 后」方向的完整键路径。 */
    public static final String PATH_BUILDING_FRONT = SECTION_PATH + "." + KEY_BUILDING_FRONT;

    /**
     * ★ <b>四方位名</b>（{@code building-front} 的取值域）。
     *
     * <h2>为什么这里是字符串而不是 {@code card/Facing}</h2>
     * 本类是纯配置层，刻意不 import {@code card/**}（见类注释）。而「方位」的**权威定义**是
     * {@code card/Facing} 那四个枚举常量 —— 于是这里留一份**只用于校验的名单**，
     * 由 {@code TrainingConfigTest#directionNamesMatchFacing} 断言它与
     * {@code Facing.values()} **逐字相等**（谁改了另一边，那条测试就红）。
     * 装配期再用 {@code Facing.valueOf(...)} 转成真正的枚举。
     */
    public static final List<String> DIRECTION_NAMES = List.of("SOUTH", "WEST", "NORTH", "EAST");

    /** {@code building-front} 里允许出现的**阵营键**（相对）与它们对应的 {@code match.Side}。 */
    private static final List<String> FRONT_SIDE_KEYS = List.of("red", "blue");

    /** 没写 {@code building-front} 时的出厂方向：红 = 西（−X）、蓝 = 东（+X）—— 现场竞技场的方向。 */
    public static final String DEFAULT_FRONT_RED = "WEST";

    /** 见 {@link #DEFAULT_FRONT_RED}。 */
    public static final String DEFAULT_FRONT_BLUE = "EAST";

    /**
     * ★ 卡组**必须**正好这么多张（用户原话：「卡组需要正好 8 张」）。
     *
     * <p>为什么是 8：快捷栏的卡位就是 2~9 号格（{@code game/CardLoadout.HOTBAR_SLOTS - 1}）。
     * ★ 刻意**不**写成 {@code CardLoadout.HOTBAR_SLOTS - 1}：那会让本类依赖 {@code game/**}
     * （要碰插件的类），而配置层必须能离线加载。两处的一致性由注释 + 测试共同钉住
     * （{@code TrainingConfigTest} 会断言这个数与 {@code CardLoadout.HOTBAR_SLOTS - 1} 相等）。
     */
    public static final int DECK_SIZE = 8;

    /** ★ 「随机」占位符的**中文**字面量（用户原话的写法）。 */
    public static final String RANDOM_TOKEN = "随机";

    /** ★ 「随机」占位符的**英文**字面量（用户裁定：两种都认，忽略大小写）。 */
    public static final String RANDOM_TOKEN_EN = "random";

    /**
     * ★★★ <b>「偷牌」占位符</b>（2026-10-21 用户追加）：
     * 「占位符 steal 在对局开始时会替换成**玩家卡组对应位置的卡牌**」。
     *
     * <p>口径（实现前逐条定死，见 {@code ai/BotDeckResolver#applySteal}）：
     * <ul>
     *   <li><b>位置对齐</b>：人机卡组第 i 项（i 从 0 起）= 玩家卡组第 i 项；</li>
     *   <li><b>解析时机</b>：进入战斗后第一次取手牌时（准备阶段玩家还没确认卡组，此前不解析；</li>
     *   <li><b>失败方向</b>（玩家带的卡人机用不了 / 与前面的格子重复 / 玩家没确认卡组）
     *       ⇒ 该格换成**随机合法卡**并打一行日志，绝不静默留空；</li>
     *   <li>可以写多个（8 个 steal = 整套复刻玩家卡组）。</li>
     * </ul>
     */
    public static final String STEAL_TOKEN = "steal";

    /**
     * ★★ <b>人机一律不能带的卡</b>（2026-10-21 用户裁定）。
     *
     * <table border="1">
     *   <caption>为什么是这三张（共同根因：人机没有 Player）</caption>
     *   <tr><th>卡 id</th><th>为什么人机用不了</th></tr>
     *   <tr><td>{@code speed_potion}（速度药水）</td>
     *       <td>{@code targeting: SELF} ⇒ 法术管线把**施放者本人**当目标，人机没有玩家</td></tr>
     *   <tr><td>{@code tactical_cooldown}（战术冷却）</td>
     *       <td>{@code COOLDOWN_REDUCTION} 作用在**施放者 UUID** 的冷却窗口上，人机没有真实玩家 UUID</td></tr>
     *   <tr><td>{@code echo}（回响）</td>
     *       <td>{@code REPLAY_LAST_CARD} 只由 {@code CardCastListener} 处理 ⇒ 人机走法术管线时它是一张空牌</td></tr>
     * </table>
     *
     * ★ 用户点名的是前两张；第三张是用户在同轮问答里选定的候选池规则
     * （「全部卡池，再排除回响」）。
     * ★ 这份清单是**唯一判据**：卡组装载期拒绝、{@link #isBotUsableCard} 也读它、
     * {@code ai/BotDeckResolver} 的随机池同样读它 —— 不会出现"卡组里禁了、随机还能抽到"。
     * ★ 卡名硬编码在这里是**照用户原话**（用户就是按卡名点的）；改名时
     * {@code TrainingDeckCrossCheckTest} 会红（它断言这三个 id 都在出厂卡池里）。
     */
    //  ★★★ 1.1.0：新增 **援军**（`reinforcement`）—— 理由与「回响」完全同构：
    //   它的语义是「重置**本局记录到的下一张牌**的冷却」，而那套状态挂在**玩家施放入口**
    //   （`game/CardCastListener#castReinforcement`）⇒ 人机不走那条入口，
    //   永远没有记录 ⇒ 永远打不出去。放进卡组只会让那 8 格里有一格永远空转。
    //   ★ 同一批的其它三张新法术（地壳震击 / 凋灵领域 / 大旋风）都在 `training.spell-rules` 里。
    public static final List<String> BOT_FORBIDDEN_CARDS =
            List.of("speed_potion", "tactical_cooldown", "echo", "reinforcement");

    /** {@code training} 段允许出现的全部键（★ 多一个都拒绝：拼错的键被静默忽略 = 「配置写了却没生效」）。 */
    private static final List<String> TRAINING_KEYS =
            List.of(KEY_BOTS, KEY_SPELL_RULES, KEY_BUILDING_PLACEMENTS, KEY_BUILDING_FRONT);

    /** {@code bots[]} 里每一项允许出现的全部键。 */
    private static final List<String> BOT_KEYS = List.of("name", "deck");

    /**
     * 兼容构造（只给「人机 ＋ 法术规则」的旧夹具用）：
     * 建筑落点表为空、方向取出厂默认。
     */
    public TrainingConfig(List<BotSpec> bots, Map<String, BotSpellRule> spellRules) {
        this(bots, spellRules, Map.of(), defaultBuildingFront());
    }

    /** 出厂默认方向（红 = 西 / 蓝 = 东）。 */
    private static Map<io.mczju.infernalclash.match.Side, String> defaultBuildingFront() {
        Map<io.mczju.infernalclash.match.Side, String> front = new LinkedHashMap<>();
        front.put(io.mczju.infernalclash.match.Side.RED, DEFAULT_FRONT_RED);
        front.put(io.mczju.infernalclash.match.Side.BLUE, DEFAULT_FRONT_BLUE);
        return Collections.unmodifiableMap(front);
    }

    /** ★ 第二道防线：绕过配置层直接 new 也要挡住（纯类自证）。 */
    public TrainingConfig {
        if (bots == null) {
            throw new IllegalArgumentException(PATH_BOTS + " 不能为 null");
        }
        bots = List.copyOf(bots);
        if (bots.isEmpty()) {
            throw new IllegalArgumentException(PATH_BOTS + " 不能为空：至少要有一个人机，"
                    + "否则 /incl bot on 之后没有人可以上场");
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < bots.size(); i++) {
            BotSpec bot = bots.get(i);
            if (bot == null) {
                throw new IllegalArgumentException(PATH_BOTS + " 的第 " + (i + 1) + " 项是 null");
            }
            if (!names.add(bot.name())) {
                throw new IllegalArgumentException(PATH_BOTS + " 里有重复的人机名字 " + bot.name()
                        + "（第 " + (i + 1) + " 项）—— 侧边栏与日志只显示名字，重名就分不清是谁");
            }
        }
        spellRules = spellRules == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(spellRules));
        buildingPlacements = buildingPlacements == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(buildingPlacements));
        // ★ 方向：没给 / 缺某一侧 ⇒ 用出厂默认补齐（两侧都必须有值，见 buildingFrontOf）
        Map<io.mczju.infernalclash.match.Side, String> front = new LinkedHashMap<>();
        front.put(io.mczju.infernalclash.match.Side.RED, DEFAULT_FRONT_RED);
        front.put(io.mczju.infernalclash.match.Side.BLUE, DEFAULT_FRONT_BLUE);
        if (buildingFront != null) {
            for (Map.Entry<io.mczju.infernalclash.match.Side, String> entry
                    : buildingFront.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !entry.getValue().isBlank()) {
                    front.put(entry.getKey(), normalizeDirection(PATH_BUILDING_FRONT, entry.getValue()));
                }
            }
        }
        buildingFront = Collections.unmodifiableMap(front);
    }

    /**
     * ★ 规范化一个方位名（去空白 + 大写），并校验它属于 {@link #DIRECTION_NAMES}。
     *
     * @throws IllegalArgumentException 不是四个方位之一（消息里列出合法取值）
     */
    private static String normalizeDirection(String path, String raw) {
        String text = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!DIRECTION_NAMES.contains(text)) {
            throw new IllegalArgumentException(path + " 的取值 " + raw + " 不是合法方位；只接受 "
                    + DIRECTION_NAMES + "（语义与 card/Facing 一致：0=正南 +Z、90=正西 −X、"
                    + "180=正北 −Z、−90=正东 +X）");
        }
        return text;
    }

    // ==================================================================================
    // 一、数据形状
    // ==================================================================================

    /**
     * ★ <b>一套人机</b>：名字 ＋ 8 张卡。
     *
     * @param name 侧边栏 / 日志里的名字（**纯文本**，不解析 MiniMessage；用户裁定）
     * @param deck 卡组，**正好 {@value #DECK_SIZE} 项**，顺序 = 槽位 2~9 的出牌顺序；
     *             其中可以有若干个「随机」占位符
     */
    public record BotSpec(String name, List<String> deck) {

        /** 严格校验：坏夹具当场炸，而不是静默变成一套奇怪的人机。 */
        public BotSpec {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(PATH_BOTS + " 里的人机名字不能为空");
            }
            name = name.trim();
            if (deck == null) {
                throw new IllegalArgumentException(PATH_BOTS + " 里的 " + name + " 的卡组不能为 null");
            }
            deck = List.copyOf(deck);
            if (deck.size() != DECK_SIZE) {
                throw new IllegalArgumentException(PATH_BOTS + " 里的 " + name + " 的卡组必须正好 "
                        + DECK_SIZE + " 项（快捷栏只有 2~9 共 8 个卡位）：实际 " + deck.size() + " 项");
            }
            Set<String> fixed = new LinkedHashSet<>();
            for (int i = 0; i < deck.size(); i++) {
                String entry = deck.get(i);
                if (entry == null || entry.isBlank()) {
                    throw new IllegalArgumentException(PATH_BOTS + " 里的 " + name + " 的第 " + (i + 1)
                            + " 项为空（槽位 " + (i + 2) + "）—— 空项等于少一张牌");
                }
                if (isPlaceholder(entry)) {
                    continue;   // ★ 「随机」/「偷牌」允许出现多次（用户裁定）
                }
                if (!fixed.add(entry.trim())) {
                    throw new IllegalArgumentException(PATH_BOTS + " 里的 " + name + " 有重复的卡 id "
                            + entry.trim() + "（第 " + (i + 1) + " 项）—— 同一张卡占两个槽位几乎必然是手滑");
                }
            }
        }

        /** 随机占位符的个数（0 ~ 8）。 */
        public int randomCount() {
            int count = 0;
            for (String entry : deck) {
                if (isRandomToken(entry)) {
                    count++;
                }
            }
            return count;
        }

        /** ★ 「偷牌」占位符的个数（0 ~ 8；2026-10-21 用户追加）。 */
        public int stealCount() {
            int count = 0;
            for (String entry : deck) {
                if (isStealToken(entry)) {
                    count++;
                }
            }
            return count;
        }

        /** 写死的那几张（非「随机」/非「偷牌」，保序）。 */
        public List<String> fixedCards() {
            List<String> fixed = new ArrayList<>();
            for (String entry : deck) {
                if (!isPlaceholder(entry)) {
                    fixed.add(entry.trim());
                }
            }
            return List.copyOf(fixed);
        }

        /** ★ 纯：摊成「槽位 → 条目」（槽位从 **2** 开始；条目可能是「随机」/「偷牌」）。 */
        public Map<Integer, String> slotToCard() {
            Map<Integer, String> slots = new LinkedHashMap<>();
            for (int i = 0; i < deck.size(); i++) {
                slots.put(i + 2, deck.get(i).trim());
            }
            return Collections.unmodifiableMap(slots);
        }
    }

    /**
     * ★ <b>手牌四元组</b>（纯数据）—— 与 {@code ai/BotPlan.HandCard} 逐字段同构。
     *
     * <p>为什么不直接返回 {@code BotPlan.HandCard}：那会让 {@code config/**}
     * 依赖 {@code ai/**}（配置层必须能离线加载出厂文件，依赖越少越稳）。
     * 装配期只做一次机械转换（一行 {@code map}），方向是 config → ai 的单向依赖。
     *
     * @param slot   快捷栏格号（★ <b>从 2 开始</b>，不是 0-based 下标）
     * @param cardId 卡牌 id
     * @param spell  是否法术卡（★ 2026-10-21 起可能为 {@code true} —— 人机会放法术了）
     * @param cost   费用（与卡池一致，供 {@code BotPlan.decide} 判"付得起吗"）
     * @param rule   ★ 法术规则（召唤卡恒为 {@code null}）；来自 {@code training.spell-rules}
     * @param buildingRule ★ 建筑类召唤牌的落点规则（2026-10-21 新增）：
     *               非建筑 / 没配 ⇒ {@code null}（= 走普通召唤规则）；来自
     *               {@code training.building-placements}。★ 法术卡恒为 {@code null}
     */
    public record HandCardSpec(int slot, String cardId, boolean spell, int cost, BotSpellRule rule,
                              BuildingPlacementRule buildingRule) {

        /** 兼容旧夹具的五参构造（没有建筑规则）。 */
        public HandCardSpec(int slot, String cardId, boolean spell, int cost, BotSpellRule rule) {
            this(slot, cardId, spell, cost, rule, null);
        }

        /** 兼容旧夹具的四参构造（召唤卡：规则恒为 {@code null}）。 */
        public HandCardSpec(int slot, String cardId, boolean spell, int cost) {
            this(slot, cardId, spell, cost, null, null);
        }
    }

    // ==================================================================================
    // 二、加载与校验
    // ==================================================================================

    /**
     * 从已解析的配置根节点加载并校验 {@code training} 段。
     *
     * <p>校验顺序：未知键 → {@code bots} 存在/类型/非空 → 每项形状 → 名字 → 卡组张数 →
     * 写死项重复 → {@code spell-rules} 存在/类型/枚举值 → {@code building-placements} /
     * {@code building-front}（可整段不写）。
     *
     * @throws ConfigError 任一项不合法
     */
    public static TrainingConfig load(ConfigurationSection root) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "配置为空（文件不存在或解析失败）");
        }
        ConfigurationSection section = ConfigReader.requireSection(FILE, root, SECTION_PATH);

        // ★★ **顺序有讲究**：未知键必须排在「缺必需键」之前（2026-10-15 实测）：
        //   把 `bots` 拼成 `bot` 时，Bukkit 里 `isSet("bots")` 为 false ⇒ 若先走 requireStringList，
        //   报出来的是「training.bots 缺少必需的列表键」—— **指着正确的键名说它缺了**。
        //   ⇒ 先扫未知键：`training.bot`「存在未知键」才指得准。
        rejectUnknownKeys(section);

        List<BotSpec> bots = readBots(section);
        Map<String, BotSpellRule> rules = readSpellRules(section);
        Map<String, BuildingPlacementRule> placements = readBuildingPlacements(section);
        Map<io.mczju.infernalclash.match.Side, String> front = readBuildingFront(section);
        return new TrainingConfig(bots, rules, placements, front);
    }

    /** 从磁盘上的 YAML 文件加载（**离线可用**，走 {@link YamlFile#read}）。 */
    public static TrainingConfig load(File file) {
        return load(YamlFile.read(FILE, file));
    }

    /** 启动日志用的一行摘要（★ 带人机数、每个人机的名字与卡组，便于与出厂文件对账）。 */
    public String describe() {
        StringBuilder text = new StringBuilder("训练人机｜" + PATH_BOTS + " = " + bots.size() + " 套");
        for (int i = 0; i < bots.size(); i++) {
            BotSpec bot = bots.get(i);
            text.append("｜#").append(i + 1).append(' ').append(bot.name())
                    .append("（写死 ").append(bot.fixedCards().size()).append(" 张 ＋ 随机 ")
                    .append(bot.randomCount()).append(" 张")
                    .append(bot.stealCount() > 0 ? " ＋ steal " + bot.stealCount() + " 张" : "")
                    .append("）").append(bot.deck());
        }
        text.append("｜").append(PATH_SPELL_RULES).append(" = ").append(spellRules.size()).append(" 条");
        text.append("｜").append(PATH_BUILDING_PLACEMENTS).append(" = ")
                .append(buildingPlacements.size()).append(" 条").append(buildingPlacements);
        text.append("｜").append(PATH_BUILDING_FRONT).append(" = 红 ")
                .append(buildingFrontOf(io.mczju.infernalclash.match.Side.RED)).append(" / 蓝 ")
                .append(buildingFrontOf(io.mczju.infernalclash.match.Side.BLUE));
        return text.toString();
    }

    /** 按名字找人机（找不到 ⇒ {@code null}；日志 / 探针用）。 */
    public BotSpec bot(String name) {
        if (name == null) {
            return null;
        }
        for (BotSpec bot : bots) {
            if (bot.name().equals(name)) {
                return bot;
            }
        }
        return null;
    }

    /** 某张法术卡的规则（没有 ⇒ {@code null} = 「人机不可用卡」）。 */
    public BotSpellRule spellRuleOf(String cardId) {
        return cardId == null ? null : spellRules.get(cardId.trim());
    }

    /**
     * ★ <b>某张建筑类召唤牌的落点规则</b>（{@code docs/32}）。
     *
     * @return 没配 ⇒ {@code null} = **走普通召唤规则（自己信标）** ——
     *         用户原话：「未配置的建筑依然按照普通召唤牌的放置规则放置」（这不是失败，不告警）
     */
    public BuildingPlacementRule buildingRuleOf(String cardId) {
        return cardId == null ? null : buildingPlacements.get(cardId.trim());
    }

    /**
     * ★ <b>某一方的「前向」方位名</b>（{@code training.building-front.<side>}）。
     *
     * <p>★ 返回**字符串**而不是 {@code card/Facing}：本类刻意不 import 卡牌层（见类注释）。
     * 装配期用 {@code Facing.valueOf(...)} 转换；两侧都保证有值（缺的取出厂默认）。
     */
    public String buildingFrontOf(io.mczju.infernalclash.match.Side side) {
        if (side == null) {
            return DEFAULT_FRONT_RED;
        }
        String value = buildingFront.get(side);
        return value == null ? defaultFrontOf(side) : value;
    }

    /** 某一方的出厂默认方向。 */
    public static String defaultFrontOf(io.mczju.infernalclash.match.Side side) {
        return side == io.mczju.infernalclash.match.Side.BLUE ? DEFAULT_FRONT_BLUE : DEFAULT_FRONT_RED;
    }

    /**
     * ★ <b>这个令牌是不是「随机」占位符</b>（纯函数）——
     * 中文 `随机` 与英文 `random` 都认，忽略大小写与首尾空白（用户裁定）。
     */
    public static boolean isRandomToken(String raw) {
        if (raw == null) {
            return false;
        }
        String text = raw.trim();
        return RANDOM_TOKEN.equals(text) || RANDOM_TOKEN_EN.equalsIgnoreCase(text);
    }

    /**
     * ★ <b>这个令牌是不是「偷牌」占位符</b>（纯函数；{@link #STEAL_TOKEN}，忽略大小写与首尾空白）。
     *
     * <p>2026-10-21 用户追加：「占位符 steal 在对局开始时会替换成玩家卡组对应位置的卡牌」。
     * 解析实现见 {@code ai/BotDeckResolver#applySteal}；解析时机见 {@code game/InfernalClashGame}。
     */
    public static boolean isStealToken(String raw) {
        return raw != null && STEAL_TOKEN.equalsIgnoreCase(raw.trim());
    }

    /**
     * ★ <b>占位符</b>（既不是「随机」也不是「偷牌」的条目才是写死的卡 id）。
     *
     * <p>所有「把卡组当卡 id 列表」的地方都必须先过这一道 —— 否则占位符会被当成
     * 「卡池里不存在的卡」而拒绝启动 / 静默少一张。
     */
    public static boolean isPlaceholder(String raw) {
        return isRandomToken(raw) || isStealToken(raw);
    }

    /** ★ 这套卡组里有没有「偷牌」占位符（纯函数；解析前置判断）。 */
    public static boolean hasSteal(java.util.List<String> deck) {
        if (deck == null) {
            return false;
        }
        for (String entry : deck) {
            if (isStealToken(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★★ <b>「人机可用卡」的判据（唯一口径）</b>。
     *
     * <pre>
     * 人机可用卡 = 卡池全部 − 速度药水 − 战术冷却 − 回响 − 没配 spell-rule 的法术卡
     * </pre>
     *
     * @param cardId  卡 id
     * @param card    卡对象（{@code null} = 不在卡池里 ⇒ 不可用）
     * @param adapter 取字段的适配器
     */
    public boolean isBotUsableCard(String cardId, Object card, DeckCard adapter) {
        if (cardId == null || card == null || adapter == null) {
            return false;
        }
        if (BOT_FORBIDDEN_CARDS.contains(cardId.trim())) {
            return false;
        }
        if (adapter.summonOf(card)) {
            return true;                    // 召唤卡：一直可用
        }
        return spellRuleOf(cardId) != null;  // 法术卡：必须配了规则
    }

    /**
     * ★★ <b>纯</b>：算出「人机可用卡」的完整集合（= 「随机」的候选池）。
     *
     * @param allCardIds 卡池里的全部卡 id（顺序即候选池的顺序；调用方一般传**按 id 排序**的列表
     *                   ⇒ 同种子 ⇒ 同结果）
     * @return 保序、不可变
     */
    public Set<String> usableCardIds(Collection<String> allCardIds, Function<String, ?> lookup,
                                     DeckCard card) {
        Set<String> usable = new LinkedHashSet<>();
        if (allCardIds == null) {
            return Collections.unmodifiableSet(usable);
        }
        for (String cardId : allCardIds) {
            Object found = lookup == null ? null : lookup.apply(cardId);
            if (isBotUsableCard(cardId, found, card)) {
                usable.add(cardId);
            }
        }
        return Collections.unmodifiableSet(usable);
    }

    /**
     * ★★ <b>纯</b>：把一套解析后的卡组摊成手牌（槽位 / 卡 id / 是否法术 / 费用 / 规则）。
     *
     * <h2>为什么要传「解析后」的卡组</h2>
     * 「随机」在这里**已经**被 {@code ai/BotDeckResolver} 换成了具体卡 id ⇒
     * 本方法拿到的必须是 8 个真实 id。这样「随机」这件事只有一处实现（可注入种子）。
     *
     * <h2>缺卡 ⇒ 跳过该槽位并记一条告警（不抛）</h2>
     * 本方法跑在**每 tick 的钩子**上（{@code TrainingBotDriver#onTick} → {@code hand.get()}），
     * 抛异常等于把一个配置问题放大成对局中的异常刷屏。装配期已经用
     * {@link #crossCheckBots} 把「缺卡 / 不可用卡」拦下并拒绝启动了 ⇒ 这里是兜底。
     *
     * @param resolvedDeck 解析后的卡组（**长度应为 {@value #DECK_SIZE}**；保序）
     * @param lookup       卡 id → 卡对象（查不到返回 {@code null}）；{@code null} 视为查不到任何卡
     * @param card         从卡对象取字段的适配器
     * @param warner       告警出口（{@code null} ⇒ 静默跳过）；每张缺卡一条
     * @return 槽位从 2 开始、**保序**的手牌描述列表
     */
    public List<HandCardSpec> toHandCards(List<String> resolvedDeck, Function<String, ?> lookup,
                                          DeckCard card, Consumer<String> warner) {
        List<HandCardSpec> cards = new ArrayList<>(DECK_SIZE);
        if (resolvedDeck == null) {
            return List.of();
        }
        for (int i = 0; i < resolvedDeck.size(); i++) {
            int slot = i + 2;
            String cardId = resolvedDeck.get(i);
            if (cardId == null || cardId.isBlank()) {
                continue;   // 构造器已挡住，这里只是不给 NPE 留机会
            }
            String id = cardId.trim();
            Object found = lookup == null ? null : lookup.apply(id);
            if (found == null) {
                if (warner != null) {
                    warner.accept(PATH_BOTS + " 的卡 " + id + " 不在卡池里，槽位 " + slot
                            + " 这次没有牌（装载期本应已经拒绝启动）");
                }
                continue;
            }
            boolean spell = card.spellOf(found);
            cards.add(new HandCardSpec(slot, id, spell, card.costOf(found),
                    spell ? spellRuleOf(id) : null,
                    // ★ 建筑落点规则只对**召唤卡**有意义（法术卡恒 null）
                    spell ? null : buildingRuleOf(id)));
        }
        return List.copyOf(cards);
    }

    // ==================================================================================
    // 三、交叉校验（纯函数；抛不抛由装配期决定）
    // ==================================================================================

    /**
     * ★★ <b>纯</b>：拿卡池交叉校验**全部人机**的卡组。
     *
     * <h2>四类问题（逐条点名到「第几个人机 / 第几项 / 哪个 id / 为什么」）</h2>
     * <ol>
     *   <li><b>写死的卡不在卡池里</b> ⇒ 人机手里凭空少一张牌，且不会有第二条告警；</li>
     *   <li><b>写死的卡是「人机不可用卡」</b> ⇒ 具体分三种原因：
     *       被禁的三张（速度药水 / 战术冷却 / 回响）、或<b>法术卡没配 spell-rule</b>；</li>
     *   <li><b>随机名额太多</b> ⇒ 可用卡不够填满（同样表现成"少牌"）；</li>
     *   <li>写死的卡重复 —— 已在 {@link BotSpec} 的构造器里抛过，这里不会再遇到。</li>
     * </ol>
     *
     * @param allCardIds 卡池里的全部卡 id（用于算「随机」的容量；{@code null} ⇒ 只做存在性 / 可用性校验）
     * @param lookup     卡 id → 卡对象（查不到返回 {@code null}）
     * @param card       从卡对象取字段的适配器
     * @return 问题列表（中文，逐条点名）；<b>空列表 = 全部合法</b>
     */
    public List<String> crossCheckBots(Collection<String> allCardIds, Function<String, ?> lookup,
                                       DeckCard card) {
        List<String> problems = new ArrayList<>();
        Set<String> usable = usableCardIds(allCardIds, lookup, card);
        for (int b = 0; b < bots.size(); b++) {
            BotSpec bot = bots.get(b);
            String where = PATH_BOTS + " 第 " + (b + 1) + " 个人机「" + bot.name() + "」";
            List<String> fixed = bot.fixedCards();
            for (int i = 0; i < bot.deck().size(); i++) {
                String raw = bot.deck().get(i);
                if (isPlaceholder(raw)) {
                    continue;
                }
                String cardId = raw.trim();
                int slot = i + 2;
                Object found = lookup == null ? null : lookup.apply(cardId);
                if (found == null) {
                    problems.add(where + " 第 " + (i + 1) + " 项（槽位 " + slot + "）的卡 id " + cardId
                            + " 不在卡池里（卡池 = cards/*.yml，key = 文件名）"
                            + " —— 人机手里会凭空少一张牌，而且不会有第二条告警");
                    continue;
                }
                if (isBotUsableCard(cardId, found, card)) {
                    continue;
                }
                if (BOT_FORBIDDEN_CARDS.contains(cardId)) {
                    problems.add(where + " 第 " + (i + 1) + " 项（槽位 " + slot + "）的卡 " + cardId
                            + "（" + card.describeOf(found) + "）是**人机不能带的卡**"
                            + "（用户 2026-10-21 裁定：速度药水 / 战术冷却不许人机携带；"
                            + "回响依赖「上一张牌」链路，人机不走那条路）");
                    continue;
                }
                problems.add(where + " 第 " + (i + 1) + " 项（槽位 " + slot + "）的卡 " + cardId
                        + "（" + card.describeOf(found) + "）是法术卡，但它没有配 "
                        + PATH_SPELL_RULES + "." + cardId
                        + " —— 没配规则的法术卡对人机是「不可用卡」（docs/31 §4），"
                        + "写进卡组必须拒绝，否则表现成「人机少打一张牌」");
            }
            int randomSlots = bot.randomCount();
            int stealSlots = bot.stealCount();
            int capacity = usable.size() - fixed.size();
            if (randomSlots + stealSlots > capacity) {
                problems.add(where + " 写了 " + randomSlots + " 个「随机」＋ " + stealSlots
                        + " 个 steal，但可用卡池里去掉写死的 " + fixed.size() + " 张之后只剩 "
                        + Math.max(0, capacity) + " 张"
                        + "（可用卡共 " + usable.size() + " 张）—— 填不满就会被静默少牌");
            }
        }
        return List.copyOf(problems);
    }

    /**
     * ★★ <b>纯</b>：校验 {@code training.spell-rules} 这张表（键必须是**法术卡**、值必须能工作）。
     *
     * <h2>三类问题</h2>
     * <ol>
     *   <li>键不在卡池里；</li>
     *   <li>键不是法术卡（给召唤卡配规则 = 一条永远不生效的配置）；</li>
     *   <li>★ <b>规则与卡恒不兼容</b>：{@code targeting: ALLY_TARGET} 的卡只能配
     *       {@link BotSpellRule#RANDOM_ALLY_TARGET}（见 {@link BotSpellRule#compatibleWith}）。</li>
     * </ol>
     */
    public List<String> crossCheckSpellRules(Function<String, ?> lookup, DeckCard card) {
        List<String> problems = new ArrayList<>();
        for (String cardId : new TreeSet<>(spellRules.keySet())) {
            BotSpellRule rule = spellRules.get(cardId);
            Object found = lookup == null ? null : lookup.apply(cardId);
            if (found == null) {
                problems.add(PATH_SPELL_RULES + "." + cardId
                        + " 指向的卡不在卡池里（卡池 = cards/*.yml，key = 文件名）—— 这条规则永远不会生效");
                continue;
            }
            if (card.summonOf(found)) {
                problems.add(PATH_SPELL_RULES + "." + cardId + "（" + card.describeOf(found)
                        + "）是**召唤卡**，法术规则只对法术卡有意义 —— 这条配置永远不会生效");
                continue;
            }
            boolean allyTarget = card.needsSingleAllyTarget(found);
            if (!rule.compatibleWith(allyTarget)) {
                problems.add(PATH_SPELL_RULES + "." + cardId + " 配了 " + rule
                        + "，但这张卡的 targeting 是 ALLY_TARGET（" + card.targetingOf(found) + "）"
                        + " —— 它必须用准星指着友方，而人机没有准星 ⇒ 用这个规则**永远打不中**。"
                        + "请改成 " + BotSpellRule.RANDOM_ALLY_TARGET);
            }
        }
        return List.copyOf(problems);
    }

    /**
     * ★★ <b>纯</b>：校验 {@code training.building-placements}（2026-10-21 新增，{@code docs/32}）。
     *
     * <h2>两类问题</h2>
     * <ol>
     *   <li>键不在卡池里；</li>
     *   <li>★ <b>键指向的卡不是建筑</b>（{@code summon.building != true}）——
     *       给普通召唤牌配「塔附近落点」是一条**永远不生效**的配置（那条卡仍走信标），
     *       必须在启动时点名（用户裁定「拒绝启动」）。</li>
     * </ol>
     */
    public List<String> crossCheckBuildingPlacements(Function<String, ?> lookup, DeckCard card) {
        List<String> problems = new ArrayList<>();
        for (String cardId : new TreeSet<>(buildingPlacements.keySet())) {
            BuildingPlacementRule rule = buildingPlacements.get(cardId);
            Object found = lookup == null ? null : lookup.apply(cardId);
            if (found == null) {
                problems.add(PATH_BUILDING_PLACEMENTS + "." + cardId
                        + " 指向的卡不在卡池里（卡池 = cards/*.yml，key = 文件名）"
                        + " —— 这条规则永远不会生效");
                continue;
            }
            if (!card.buildingOf(found)) {
                problems.add(PATH_BUILDING_PLACEMENTS + "." + cardId + "（" + card.describeOf(found)
                        + "）**不是建筑**（它的 summon.building 不是 true），配了 " + rule
                        + " 也不会生效 —— 它仍按普通召唤规则（自己信标）放置。"
                        + "请删掉这一行，或者把这张卡的 cards/*.yml 里的 summon.building 改成 true");
            }
        }
        return List.copyOf(problems);
    }

    // ==================================================================================
    // 四、内部
    // ==================================================================================

    /** 读 {@code bots} 列表（形状 + 名字 + 卡组 + 未知键）。 */
    private static List<BotSpec> readBots(ConfigurationSection section) {
        if (!section.isList(KEY_BOTS)) {
            throw new ConfigError(FILE, PATH_BOTS, section.isSet(KEY_BOTS)
                    ? "类型不是列表（应写成逐行 `- name: …` / `  deck: […]`）"
                    : "缺少必需的列表键（" + SECTION_PATH + " 段必须至少有一个人机；"
                            + "旧的 " + PATH_BOTS.replace(KEY_BOTS, RETIRED_KEY_BOT_DECK)
                            + " 已被 " + PATH_BOTS + " 取代）");
        }
        List<?> raw = section.getList(KEY_BOTS);
        if (raw == null || raw.isEmpty()) {
            throw new ConfigError(FILE, PATH_BOTS, "列表不能为空：至少要有一个人机，"
                    + "否则 /incl bot on 之后没有人可以上场");
        }
        List<BotSpec> bots = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String path = PATH_BOTS + "[" + i + "]";
            ConfigurationSection botSection = asSection(raw.get(i));
            if (botSection == null) {
                throw new ConfigError(FILE, path, "每一项必须是对象（写成 `- name: 名字` 后换行缩进 "
                        + "`deck:`），实际是：" + raw.get(i));
            }
            rejectUnknownKeys(botSection, BOT_KEYS, path);
            Object nameRaw = botSection.get("name");
            if (!(nameRaw instanceof String text) || text.isBlank()) {
                throw new ConfigError(FILE, path + ".name", nameRaw == null
                        ? "缺少必需的名字（侧边栏与日志都要显示它）"
                        : "必须是非空字符串（实际：" + nameRaw + "）");
            }
            String name = text.trim();
            if (!names.add(name)) {
                throw new ConfigError(FILE, path + ".name", "人机名字 " + name + " 重复了"
                        + "（第 " + (i + 1) + " 项）—— 侧边栏与日志只显示名字，重名就分不清是谁上场");
            }
            List<String> deck = readDeck(botSection, path, name);
            bots.add(new BotSpec(name, deck));
        }
        return List.copyOf(bots);
    }

    /**
     * 把一个列表项当成配置段落。
     *
     * <h2>★ 为什么需要它（2026-10-21 实测踩到的）</h2>
     * {@code YamlConfiguration#getList} 返回的列表里，**嵌套的 map 项是原样的 {@code Map}**，
     * 不是 {@code ConfigurationSection}（实测报「每一项必须是对象……实际是：
     * {name=…, deck=[…]}」）⇒ 用 {@link org.bukkit.configuration.MemoryConfiguration#createSection}
     * 把它包成段落。
     */
    private static ConfigurationSection asSection(Object item) {
        if (item instanceof ConfigurationSection section) {
            return section;
        }
        if (item instanceof Map<?, ?> map) {
            return new org.bukkit.configuration.MemoryConfiguration().createSection("bot", map);
        }
        return null;
    }

    /** 读一套人机里的 {@code deck}（正好 8 项、每项非空、写死项不重复、「随机」不限个数）。 */
    private static List<String> readDeck(ConfigurationSection botSection, String botPath, String name) {
        String path = botPath + ".deck";
        Object raw = botSection.get("deck");
        if (!(raw instanceof List<?> list)) {
            throw new ConfigError(FILE, path, raw == null
                    ? "缺少必需的卡组（应写成逐行 `- 卡 id`）"
                    : "类型不是列表（应写成逐行 `- 卡 id`），实际：" + raw);
        }
        List<String> deck = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new ConfigError(FILE, path, "列表项必须是字符串（卡 id），实际：" + item);
            }
            deck.add(text);
        }
        if (deck.size() != DECK_SIZE) {
            throw new ConfigError(FILE, path,
                    "「" + name + "」的卡组必须正好 " + DECK_SIZE + " 张（用户的硬要求；快捷栏的卡位就是 "
                            + "2~9 号格共 " + DECK_SIZE + " 个）：实际 " + deck.size() + " 张 —— "
                            + (deck.size() < DECK_SIZE
                            ? "少了 " + (DECK_SIZE - deck.size()) + " 张（人机会天然少几张牌）"
                            : "多了 " + (deck.size() - DECK_SIZE) + " 张（多出来的会被静默忽略）"));
        }
        Set<String> fixed = new LinkedHashSet<>();
        for (int i = 0; i < deck.size(); i++) {
            String entry = deck.get(i);
            if (entry == null || entry.isBlank()) {
                throw new ConfigError(FILE, path,
                        "第 " + (i + 1) + " 项（槽位 " + (i + 2) + "）是空项 —— "
                                + "空项等于少一张牌，必须显式写出来或删掉");
            }
            if (isPlaceholder(entry)) {
                continue;   // ★ 多个「随机」/「偷牌」允许（用户裁定），它们彼此之间不判重复
            }
            if (!fixed.add(entry.trim())) {
                throw new ConfigError(FILE, path,
                        "第 " + (i + 1) + " 项（槽位 " + (i + 2) + "）的卡 id " + entry.trim()
                                + " 重复了 —— 同一张卡占两个槽位几乎必然是手滑"
                                + "（★ 若想每局换成不同的卡，请写「" + RANDOM_TOKEN + "」占位符）");
            }
        }
        return List.copyOf(deck);
    }

    /** 读 {@code spell-rules} 映射（允许为空；值必须是四个枚举之一）。 */
    private static Map<String, BotSpellRule> readSpellRules(ConfigurationSection section) {
        if (!section.isSet(KEY_SPELL_RULES)) {
            // ★ 允许整段不写 = 人机只会放召唤卡（不是错误：新装/测试服可以先不配）
            return Map.of();
        }
        ConfigurationSection rules = section.getConfigurationSection(KEY_SPELL_RULES);
        if (rules == null) {
            throw new ConfigError(FILE, PATH_SPELL_RULES,
                    "该键存在但不是对象节点（应写成 `fireball: NEAREST_ENEMY` 逐行）");
        }
        Map<String, BotSpellRule> parsed = new LinkedHashMap<>();
        for (String cardId : new TreeSet<>(rules.getKeys(false))) {   // 排序 ⇒ 报错顺序稳定
            String raw = rules.getString(cardId, "");
            String path = PATH_SPELL_RULES + "." + cardId;
            BotSpellRule rule = BotSpellRule.parse(raw);
            if (rule == null) {
                throw new ConfigError(FILE, path,
                        "取值 " + raw + " 不是合法规则；只接受 " + BotSpellRule.names()
                                + "（四个枚举的语义见 docs/31 §5）");
            }
            parsed.put(cardId.trim(), rule);
        }
        return Map.copyOf(parsed);
    }

    /**
     * 读 {@code building-placements} 映射（**允许整段不写** = 所有建筑都走普通规则）。
     *
     * <p>校验：值必须是 {@code BACK} / {@code CENTER} / {@code FRONT}（枚举名，忽略大小写）。
     * ★ 「这个 id 是不是建筑」需要卡池 ⇒ 在装配期的
     * {@link #crossCheckBuildingPlacements} 里做（配置层不认识卡）。
     */
    private static Map<String, BuildingPlacementRule> readBuildingPlacements(
            ConfigurationSection section) {
        if (!section.isSet(KEY_BUILDING_PLACEMENTS)) {
            return Map.of();
        }
        ConfigurationSection rules = section.getConfigurationSection(KEY_BUILDING_PLACEMENTS);
        if (rules == null) {
            throw new ConfigError(FILE, PATH_BUILDING_PLACEMENTS,
                    "该键存在但不是对象节点（应写成 `frog_parasite: BACK` 逐行）");
        }
        Map<String, BuildingPlacementRule> parsed = new LinkedHashMap<>();
        for (String cardId : new TreeSet<>(rules.getKeys(false))) {   // 排序 ⇒ 报错顺序稳定
            String raw = rules.getString(cardId, "");
            String path = PATH_BUILDING_PLACEMENTS + "." + cardId;
            BuildingPlacementRule rule = BuildingPlacementRule.parse(raw);
            if (rule == null) {
                throw new ConfigError(FILE, path,
                        "取值 " + raw + " 不是合法规则；只接受 " + BuildingPlacementRule.names()
                                + "（三个枚举的语义见 docs/32 §2）");
            }
            parsed.put(cardId.trim(), rule);
        }
        return Collections.unmodifiableMap(parsed);
    }

    /**
     * 读 {@code building-front}（**允许整段不写** = 取出厂默认 红 WEST / 蓝 EAST）。
     *
     * <p>★ 写了就必须**两侧都写**：只配一侧会让另一侧悄悄用默认方向 —— 那正是本项目最怕的
     * 「配置写了一半，行为看起来对」。
     */
    private static Map<io.mczju.infernalclash.match.Side, String> readBuildingFront(
            ConfigurationSection section) {
        if (!section.isSet(KEY_BUILDING_FRONT)) {
            return defaultBuildingFront();
        }
        ConfigurationSection fronts = section.getConfigurationSection(KEY_BUILDING_FRONT);
        if (fronts == null) {
            throw new ConfigError(FILE, PATH_BUILDING_FRONT,
                    "该键存在但不是对象节点（应写成 `red: WEST` / `blue: EAST` 两行）");
        }
        rejectUnknownKeys(fronts, FRONT_SIDE_KEYS, PATH_BUILDING_FRONT);
        Map<io.mczju.infernalclash.match.Side, String> parsed = new LinkedHashMap<>();
        for (String key : FRONT_SIDE_KEYS) {
            String raw = fronts.getString(key, "");
            String path = PATH_BUILDING_FRONT + "." + key;
            String direction = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (!DIRECTION_NAMES.contains(direction)) {
                throw new ConfigError(FILE, path,
                        "取值 " + raw + " 不是合法方位；只接受 " + DIRECTION_NAMES
                                + "（语义与 card/Facing 一致：0=正南 +Z、90=正西 −X、"
                                + "180=正北 −Z、−90=正东 +X）");
            }
            parsed.put(key.equals("blue")
                    ? io.mczju.infernalclash.match.Side.BLUE
                    : io.mczju.infernalclash.match.Side.RED, direction);
        }
        return Collections.unmodifiableMap(parsed);
    }

    /**
     * 出现**未知键**就抛 {@link ConfigError}（与 {@code CombatConfig} 同口径）。
     *
     * <p>拼错的键（{@code bot} 少个 s）被静默忽略的后果是「配置写了却没生效」且**没有任何报错**。
     */
    private static void rejectUnknownKeys(ConfigurationSection section) {
        rejectUnknownKeys(section, TRAINING_KEYS, SECTION_PATH);
    }

    /** 同上（任意段落）：{@code unknown} 排序 ⇒ 报错顺序稳定。 */
    private static void rejectUnknownKeys(ConfigurationSection section, List<String> allowed,
                                          String path) {
        List<String> unknown = new ArrayList<>();
        for (String key : new TreeSet<>(section.getKeys(false))) {
            if (!allowed.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        String hint = "";
        if (unknown.contains(RETIRED_KEY_BOT_DECK)) {
            hint = "｜★ 特别注意：" + RETIRED_KEY_BOT_DECK + " 是 2026-10-21 已作废的旧键，"
                    + "请把它的 8 张卡搬进 " + PATH_BOTS + " 的第一个人机（原样搬即可，行为不变）";
        }
        throw new ConfigError(FILE, path + "." + unknown.get(0),
                "存在未知键 " + unknown + "（" + path + " 段只接受 " + allowed + "）—— "
                        + "拼错的键必须被当场抓住，否则会变成「配置写了却没生效」" + hint);
    }

    /**
     * ★ <b>卡对象的适配器</b>：把「怎么从一张卡上取字段」这件事交给装配期。
     *
     * <h2>为什么需要它（而不是直接 import {@code card/Card}）</h2>
     * {@code config/**} 是纯配置层：它必须能在普通 JUnit 里加载<b>出厂 config.yml</b>，
     * 而 {@code card/Card} 属于卡牌层。让配置层反过来依赖卡牌层，
     * 会让「只想校验一段 YAML」的测试也被迫拖上整条卡池加载链。
     * ⇒ 依赖方向固定为 {@code config ← 装配期}，卡对象在适配器后面。
     */
    public interface DeckCard {

        /** 这张卡是不是召唤卡（{@code card/Card#isSummon()}）。 */
        boolean summonOf(Object card);

        /** 这张卡的费用（{@code card/Card#cost()}）。 */
        int costOf(Object card);

        /** 这张卡的一行描述（只用于报错措辞）。 */
        default String describeOf(Object card) {
            return String.valueOf(card);
        }

        /**
         * 这张卡是不是法术卡（默认 = {@code !summonOf}）。
         *
         * <p>★ 默认实现是**安全的**：{@code card/Card} 的不变量就是
         * 「SUMMON 必有 summon 块且无 spell 块，SPELL 反之」（{@code CardCatalog} 装载期保证）
         * ⇒ {@code !isSummon()} 与 {@code isSpell()} 恒等。要自定义就覆写。
         */
        default boolean spellOf(Object card) {
            return !summonOf(card);
        }

        /**
         * ★ 这张卡的 {@code spell.targeting} **是不是 {@code ALLY_TARGET}**
         * （2026-10-21 新增：{@link #crossCheckSpellRules} 要用它判「规则与卡兼容」）。
         *
         * <p>★ 返回值刻意是**布尔**而不是枚举：配置层不认识 {@code card/Spell.Targeting}
         * ⇒ 由适配器把「那个枚举」翻译成这一个问题（唯一需要的那个事实）。
         */
        default boolean needsSingleAllyTarget(Object card) {
            return false;
        }

        /** 这张卡的 {@code targeting} 名字（只用于报错措辞；默认空串）。 */
        default String targetingOf(Object card) {
            return "";
        }

        /**
         * ★★ <b>这张卡是不是「建筑」</b>（{@code card/Card.Summon#building()}，2026-10-21 新增）。
         *
         * <p>用途只有一处：{@link #crossCheckBuildingPlacements} 判
         * 「给非建筑配塔附近落点」这条**永远不生效**的配置。
         * <p>★ 默认 {@code false}（fail-safe：适配器没实现时，任何卡都不算建筑 ⇒
         * 规则表里出现它就会被点名拒绝，而不是静默无效）。
         */
        default boolean buildingOf(Object card) {
            return false;
        }
    }
}
