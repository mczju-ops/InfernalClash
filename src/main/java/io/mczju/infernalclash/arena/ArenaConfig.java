package io.mczju.infernalclash.arena;

import io.mczju.infernalclash.config.ConfigError;
import io.mczju.infernalclash.config.ConfigReader;
import io.mczju.infernalclash.config.YamlFile;
import io.mczju.infernalclash.match.Side;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 竞技场点位表（{@code arenas.yml}）。
 *
 * <h2>★ 这是数据文件，不是配置文件</h2>
 * 它由竞技场编辑 UI / {@code /incl arena} 写入（{@code docs/09-UI设计.md} §2），
 * 因此 {@code ConfigMigrator} <b>不迁移</b>它（{@code docs/03-配置规范.md} §「数据不迁移」）。
 * 后果是：<b>点位键名由本类定死</b>，写错一个键不会被「升级补齐」救回来 —— 所以
 * {@link #from(ConfigurationSection)} 对未知点位键<b>直接抛错</b>，不允许静默忽略。
 *
 * <h2>★ 文件不存在 ≠ 错误</h2>
 * 竞技场本来就可能还没编辑过；服主刚装插件时 {@code arenas.yml} 当然不存在。
 * 这时返回<b>空表</b>，由 {@link #isReadyForMatch()} / {@link #missingKeysForMatch()}
 * 给出「缺什么」的准确回答（而不是启动就崩）。
 *
 * <h2>★ 保序（{@code AGENTS.md} §4.2）</h2>
 * 点位表的迭代顺序必须是<b>写入顺序</b>，否则 UI 保存 {@code arenas.yml} 时键顺序每次运行都变，
 * 文件会无意义地 churn（InfernalGuess 用 {@code Map.copyOf} 踩过这个坑，
 * 见 {@code docs/09-UI设计.md} §2.3）。
 * ⇒ 这里用 {@code LinkedHashMap} + {@code Collections.unmodifiableMap}，
 * <b>绝不用 {@code Map.copyOf}</b>（JDK 不可变 Map 的迭代顺序按 JVM 运行加盐）。
 */
public record ArenaConfig(Map<String, Point> points) {

    /** 点位数据文件名（{@code arenas.yml}；**数据文件，不迁移**）。 */
    public static final String FILE = "arenas.yml";

    /** {@code points} 段（YAML 里点位键都挂在这一段下）。 */
    private static final String SECTION = "points";

    /**
     * 约定的全部点位键 —— **顺序即 UI 里的推荐采集顺序**，
     * 且 ★★ **前 {@link #REQUIRED_COUNT} 个必须恰好是「对局必需」那一批**。
     *
     * <p>顺序：先两边信标 → 再每方两座塔 → 再出生点 → <b>再半场角点</b> → 观战 → 河与桥。
     *
     * <h2>★ 为什么 {@code half.*} 排在 {@code spectate} <u>前面</u>（2026-10-15 E14 裁定带来的约束）</h2>
     * 「必需几个」的实现是 {@code KEYS.subList(0, REQUIRED_COUNT)}（{@link #requiredKeys()}）——
     * 这一行只有在<b>必需键在列表里是连续的一段前缀</b>时才成立。
     * 裁定前 {@code spectate} 正好在第 9 位（8 之后、{@code half.*} 之前），
     * 于是「把必需数从 8 提到 12」会<b>顺手把 {@code spectate} 也变成必需</b>
     * ——那会让「只差一个观战点」的对局开不起来，且与用户裁定（只提 {@code half.*}）不符。
     * ⇒ 这里把 4 个角点提到 {@code spectate} 之前：前 12 个 = 8 个目标物点位 + 4 个 {@code half.*}。
     * ★ <b>以后往 {@link #KEYS} 里插键时注意同一条约束</b>：
     * 新增的「必需」键要插在 {@code REQUIRED_COUNT} 之前，非必需键插在它之后。
     */
    private static final List<String> KEYS = List.of(
            "beacon.red",
            "beacon.blue",
            "tower.red.left",
            "tower.red.right",
            "tower.blue.left",
            "tower.blue.right",
            "spawn.red",
            "spawn.blue",
            "half.red.1",
            "half.red.2",
            "half.blue.1",
            "half.blue.2",
            "spectate",
            // ★★★ 2026-10-18：河与两座桥**各改成两个对角点**（用户第 5 / 9 条给的就是矩形的两个角）。
            //   旧键 `river` / `bridge.1` / `bridge.2`（各一个点）**已作废** —— 一个点表达不了
            //   「河的宽度」「桥的宽长」，而用户这次的描述正是矩形：信息丢掉就再也算不回来。
            //   ★ 它们都排在第 12 个必需键**之后** ⇒ 不参与就绪判定（改动不影响开局）。
            "river.1",
            "river.2",
            "bridge.1.a",
            "bridge.1.b",
            "bridge.2.a",
            "bridge.2.b");

    /**
     * ★★★ <b>已作废的点位键</b>（读到 ⇒ <b>跳过 + 记一条警告</b>，<b>绝不</b>拒绝启动）。
     *
     * <h2>为什么必须有这张表（2026-10-18 现场事故）</h2>
     * 本轮把 {@code river} / {@code bridge.1} / {@code bridge.2} 换成两角形式后，
     * 现场 {@code arenas.yml}（还是旧键）在启动时被判成「未知的点位键」⇒
     * <b>整个插件被禁用</b>（现场日志原文：
     * {@code [arenas.yml] points.river —— 未知的点位键 … 配置加载失败，插件将被禁用}）。
     *
     * <p>★ 这条失败方向是错的：一个已经绑过竞技场的服主，不该因为我们改了**非必需**键的
     * 命名就开不了服。⇒ 作废键改成「<b>跳过 + 警告</b>」，并在警告里说清下一步
     * （用 {@code /incl arena set <名字>} 重绑，或手工改成两角键）。
     * <p>★ 但**拼错的键**（{@code beaken.red}）仍然是硬错误 —— 那才是真正需要人来看的一类。
     */
    private static final List<String> OBSOLETE_KEYS = List.of("river", "bridge.1", "bridge.2");

    /**
     * 一场对局**开得起来**所需的键（{@link #KEYS} 的前 12 个）：
     * 信标 ×2 + 防御塔 ×4 + 出生点 ×2 + <b>半场角点 ×4</b>。
     *
     * <h2>★★ 2026-10-15 用户裁定 = 选项 ①：把 {@code half.*} 纳入「开局必须」</h2>
     * <b>用户原话</b>：「把它纳入"开局必须"」（留档 {@code docs/24} §5.2 的结案块）。
     *
     * <p><b>为什么必须提上来</b>（原本 8 的口径是一条<b>假绿</b>）：
     * 4 个 {@code half.{red,blue}.1/2} 角点原本<b>一个都不参与就绪判定</b> ⇒ 它们缺失、
     * 或同一方的两个角点<b>世界不一致</b>时，房间照显 {@code READY}、目标物照常生成，
     * 但 {@code card/PlacementResolver} 会对<b>所有召唤卡</b>一律返回
     * {@code REFUSE_NO_HALF_CONFIGURED} ⇒ 主线（打牌）全废，而<b>控制台启动日志一个字都不提</b>。
     * 也就是说：房间说"能开"，实际"一张牌都打不出去"，且没有任何一处报警。
     *
     * <p>★ 裁定 ① 的代价是<b>明说</b>而不是隐藏：少了 {@code half.*} 的对局从此
     * <b>开不起来</b>（{@link #isReadyForMatch()} 为假、{@link #missingKeysForMatch()}
     * 会点名缺的是哪 4 个角点、{@code ObjectiveService} 会明确拒绝生成目标物）——
     * 这是<b>想要的</b>失败方向：宁可"开不了并且说清为什么"，也不要"看起来开了、其实打不了"。
     *
     * <p>{@code river} / {@code bridge.*}（规则与地形）**仍不参与就绪判定** ——
     * 它们只需要能被表达，目前没有消费者（{@code docs/04} §16.6.1 的口径不变）。
     */
    private static final int REQUIRED_COUNT = 12;

    /**
     * 点位键（**约定的全部键**；顺序即 UI 里的推荐采集顺序）。
     *
     * @return 不可变列表，元素是**裸键**（如 {@code beacon.red}，**不带** {@code points.} 前缀）
     */
    public static List<String> allKeys() {
        return KEYS;
    }

    /**
     * ★ <b>一场对局开得起来所需的键</b>（{@link #KEYS} 的前 {@link #REQUIRED_COUNT} 个）。
     *
     * <h2>为什么要有这个访问器（而不是让调用方写死「12 个」）</h2>
     * 「必需几个」这件事<b>只有一处事实来源</b>（{@link #REQUIRED_COUNT} / {@link #KEYS}）。
     * 之前 {@code command/InfernalClashCommand} 与 {@code ui/ArenaEditMenu} 把
     * <b>「对局必需 8 个」写死在玩家可见文案里</b> ⇒ E14 裁定（8 → 12）之后，
     * 那两处会立刻变成<b>说谎的文案</b>（管理员看到的仍是「必需 8 个：已齐全」，
     * 而实际判据已是 12）—— 本项目把这类「文档/文案与代码不符」直接算缺陷（{@code AGENTS.md} 铁律 1）。
     * ⇒ 交给调用方一个读数，文案用 {@code requiredKeys().size()} 拼。
     *
     * @return 不可变列表（**保序**），元素是裸键（如 {@code beacon.red}）；
     *         长度 = {@link #requiredCount()}
     */
    public static List<String> requiredKeys() {
        return KEYS.subList(0, REQUIRED_COUNT);
    }

    /**
     * ★ <b>必需键的个数</b>（{@link #requiredKeys()} 的长度）—— 供文案与测试引用，避免写死数字。
     */
    public static int requiredCount() {
        return REQUIRED_COUNT;
    }

    /**
     * 从磁盘上的 YAML 加载。
     *
     * <h2>★ 文件不存在 → 空表（不报错）</h2>
     * 「还没编辑过竞技场」是正常状态，不是错误。
     *
     * <h2>★ 文件存在但读不了 / 解析不了 / 数值非法 → {@link ConfigError}</h2>
     * 这时**必须**报错：已经存在的文件坏掉是事故（服主会以为是别的原因），
     * 静默降级成空表只会让「竞技场突然开不了」变成一个谜。
     */
    public static ArenaConfig load(File file) {
        if (file == null || !file.isFile()) {
            return empty();
        }
        return from(YamlFile.read(FILE, file));
    }

    /**
     * 直接吃已解析的根节点（离线测试用）。
     *
     * @param root 已解析的 YAML 根节点（例如 {@code YamlFile.read(...)} 的结果）
     */
    public static ArenaConfig from(ConfigurationSection root) {
        return from(root, null);
    }

    /**
     * 同上，但把**作废键**的警告收进 {@code warnings}（{@code null} ⇒ 丢弃）。
     *
     * <p>★ 为什么用"收集器"而不是打日志：本类是**纯离线**入口（{@code ShippedCardsTest}
     * 一类夹具都在用它），而 {@code Bukkit.getLogger()} 在离线时是 NPE（本项目踩过）。
     * ⇒ 载入器只负责**收集**，由插件在装配期统一打出来（与 {@code CardCatalog} 的
     * {@code warnAboutTauntSemantics} 同一套做法）。
     *
     * @param warnings 收作废键警告的列表（可为 {@code null}）
     */
    public static ArenaConfig from(ConfigurationSection root, List<String> warnings) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "根节点为 null（文件不存在或解析失败）");
        }
        // ★ 三段式：没写 points / 写了但空 / 写成了标量。第三种是**错的**，不能与「还没编辑」混为一谈
        if (!root.isSet(SECTION)) {
            return empty();
        }
        ConfigurationSection section = root.getConfigurationSection(SECTION);
        if (section == null) {
            // 走到这里 ⇒ 键**存在**但不是对象节点（键不存在时上面已返回空表）
            throw new ConfigError(FILE, SECTION,
                    "该键存在但不是对象节点（点位应写成 " + SECTION + ": 后换行缩进）");
        }
        // ★ 不能用 section.getKeys(false)：dotted 键被 Bukkit 归一化成嵌套路径（详见 collectPointKeys）
        List<String> fileKeys = new ArrayList<>();
        collectPointKeys(section, "", fileKeys);
        if (fileKeys.isEmpty()) {
            return empty();
        }

        Map<String, Point> parsed = new LinkedHashMap<>();
        for (String key : fileKeys) {
            String path = SECTION + "." + key;
            if (!KEYS.contains(key)) {
                if (OBSOLETE_KEYS.contains(key)) {
                    // ★ 作废键：跳过 + 记一条警告，**绝不**让整台服务器因为一个非必需键开不了服
                    if (warnings != null) {
                        warnings.add("arenas.yml 的 " + path + " 是已作废的点位键"
                                + "（2026-10-18 起河与两座桥改成两个对角点：" + OBSOLETE_KEYS
                                + " → river.1/2、bridge.N.a/b），已跳过这一个点，其余照常加载。"
                                + "下一步：/incl arena set <竞技场名> 重绑一次（推荐），"
                                + "或手工把 " + key + " 拆成两个对角点");
                    }
                    continue;
                }
                // ★ 拼错一个点位键（`beacon.red` 写成 `beaken.red`）必须当场抓住，
                //   否则表现为「配置写了却没生效」，且本文件不迁移 ⇒ 永远不会有人来修
                throw new ConfigError(FILE, path, "未知的点位键（合法键见 " + FILE + " 的约定："
                        + KEYS + "）");
            }
            parsed.put(key, pointAt(section, key));
        }
        // ★ 保序不可变视图（LinkedHashMap 的插入顺序 + 不可修改包装），不要用 Map.copyOf
        return new ArenaConfig(Collections.unmodifiableMap(parsed));
    }

    /**
     * ★ 递归枚举 {@code points} 下的**点位键**（如 {@code beacon.red} / {@code tower.red.left}
     * / {@code half.red.1}），用 {@code .} 连接各层键名。
     *
     * <h2>为什么不能直接 {@code section.getKeys(false)} —— 2026-10-15 实测（本项目）</h2>
     * Bukkit 把 dotted 的 YAML 键**归一化成真正的嵌套路径**。实测（paper-api 26.2）：当
     * {@code points} 段下写 {@code beacon.red: { world: world }} 时
     * <ul>
     *   <li>{@code points.getKeys(false)} = {@code [beacon, tower, spawn, ...]}
     *       —— 只有**中间节点**，点位键根本不在里面；</li>
     *   <li>{@code points.getKeys(true)} = {@code [beacon, beacon.red, beacon.red.world, ...]}
     *       —— 是<b>相对该段</b>的路径（不是从根算起的），靠它筛「恰好深一层」也能做，
     *       但下面这种「递归到叶子」的写法不依赖 getKeys 的语义细节，更抗变更。</li>
     * </ul>
     * 若照抄 {@code getKeys(false)}：点位键全部查不到、{@code size()} 恒为 0，
     * 而 {@code missingKeysForMatch()} 会永远报「必需键全都缺」（现场表现是「竞技场明明配了却开不了局」）。
     *
     * <h2>★ 叶子判据：这段之下**还有没有子段落**</h2>
     * 点位节点（{@code beacon.red}）的子键全是 {@code world/x/y/z/yaw/pitch} 这类**标量**；
     * 中间节点（{@code beacon}）之下还有子段落。所以判据必须是
     * 「没有子段落 ⇒ 叶子」，而<b>不是</b>「{@code getKeys(false).isEmpty()}」——
     * 后者对点位节点永远为 false（它当然有 world/x 这些子键），
     * 结果是每个点位都被当成中间节点继续往下递归、整张表静默变成空表
     * （★ 这是我写错一次、被 debug 输出抓住的，所以留在这里当警示）。
     *
     * <p>顺带兼容把键加引号的扁平写法（{@code "beacon.red": {...}}）——
     * 那种情况下 {@code beacon.red} 本身就是一个叶子对象节点，收集结果完全相同。
     */
    private static void collectPointKeys(ConfigurationSection node, String prefix, List<String> out) {
        for (String relative : node.getKeys(false)) {
            if (!node.isConfigurationSection(relative)) {
                // 标量字段（world / x / yaw ...）一律不是点位键
                continue;
            }
            ConfigurationSection child = node.getConfigurationSection(relative);
            if (child == null) {
                continue;
            }
            String key = prefix + relative;
            if (!hasSubSection(child)) {
                // ★ 叶子对象节点 = 一个点位（它的子键全是 world/x/y/z/yaw/pitch 这类标量）。
                //   若这里判成「child.getKeys(false).isEmpty()」就永远不成立 —— 点位节点**有**子键
                //   （我是这么写错一次、被 debug 输出抓住的），于是整张表会静默变成空表。
                out.add(key);
            } else {
                collectPointKeys(child, key + ".", out);
            }
        }
    }

    /** 该段之下是否还有**子段落**（只有标量 ⇒ 它自己就是叶子）。 */
    private static boolean hasSubSection(ConfigurationSection node) {
        for (String relative : node.getKeys(false)) {
            if (node.isConfigurationSection(relative)) {
                return true;
            }
        }
        return false;
    }

    /** 空表（文件不存在 / 还没编辑过）。 */
    public static ArenaConfig empty() {
        return new ArenaConfig(Collections.unmodifiableMap(new LinkedHashMap<>()));
    }

    /**
     * 读一个点位。**键路径从根算起**（例：{@code points.beacon.red.x}）。
     *
     * <p>{@code yaw} / {@code pitch} 是**可选**的：写了就必须是数字，没写默认 {@code 0}。
     * （与 {@code ConfigReader} 的「缺键即抛」不冲突 —— 朝向的业务默认值就是 0，
     * 即「不关心朝向」，这不是在猜一个有意义但未知的数值。）
     */
    private static Point pointAt(ConfigurationSection section, String key) {
        ConfigurationSection node = ConfigReader.requireSection(FILE, section, key);
        String world = ConfigReader.requireString(FILE, node, "world");
        double x = ConfigReader.requireDouble(FILE, node, "x", -Double.MAX_VALUE, Double.MAX_VALUE);
        double y = ConfigReader.requireDouble(FILE, node, "y", -Double.MAX_VALUE, Double.MAX_VALUE);
        double z = ConfigReader.requireDouble(FILE, node, "z", -Double.MAX_VALUE, Double.MAX_VALUE);
        // ★ 可选：没写就走默认 0，写了就必须是数字（写 `yaw: 东` 要报错，而不是静默 0）
        float yaw = (float) optionalDouble(node, "yaw");
        float pitch = (float) optionalDouble(node, "pitch");
        return new Point(world, x, y, z, yaw, pitch);
    }

    /** 可选数字：没写 → {@code 0.0}；写了但类型不对 → 抛（键路径从根算起）。 */
    private static double optionalDouble(ConfigurationSection node, String key) {
        if (!node.isSet(key)) {
            return 0.0;
        }
        return ConfigReader.requireDouble(FILE, node, key, -Double.MAX_VALUE, Double.MAX_VALUE);
    }

    // ------------------------------------------------------------------ 访问

    /**
     * 点位表（**保序**的不可变视图：写入顺序 = {@code arenas.yml} 里的书写顺序）。
     */
    @Override
    public Map<String, Point> points() {
        return points;
    }

    /** 已采集的点位键（**保序**）。 */
    public Set<String> keys() {
        return points.keySet();
    }

    /** 该键是否已采集。 */
    public boolean has(String key) {
        return points.containsKey(key);
    }

    /** 取点位；**没有 → {@code null}（不抛）**。 */
    public Point point(String key) {
        return points.get(key);
    }

    /** 已采集的点位数。 */
    public int size() {
        return points.size();
    }

    // ------------------------------------------------------------------ 语义访问

    /** 信标点位（键 {@code beacon.red} / {@code beacon.blue}）。 */
    public Point beacon(Side side) {
        return point("beacon." + tag(side));
    }

    /** 防御塔点位（键 {@code tower.red.left} / {@code tower.red.right} / ...）。 */
    public Point tower(Side side, boolean left) {
        return point("tower." + tag(side) + "." + (left ? "left" : "right"));
    }

    /** 出生点点位（键 {@code spawn.red} / {@code spawn.blue}）。 */
    public Point spawn(Side side) {
        return point("spawn." + tag(side));
    }

    /**
     * 取某方**半场矩形**（键 {@code half.<side>.1} / {@code half.<side>.2}，P4 的召唤落点判定用）。
     *
     * <h2>★ 两个角点：任一缺失或世界不一致 ⇒ {@code null}</h2>
     * <ul>
     *   <li><b>任一缺失</b> ⇒ {@code null}：只有「两个角」才构成一个矩形，一个角什么都定不下来；</li>
     *   <li>★★ <b>两个角点世界不一致 ⇒ {@code null}</b>：异世界矩形是<b>配置事故</b>
     *       （{@link HalfField#of} 在这种情况下会抛异常）—— 这里<b>不抛</b>，
     *       而是返回 {@code null}，把「拒绝施放」这件事<b>交由调用方 fail-safe 处理</b>
     *       （{@code PlacementResolver} 收到 {@code null} ⇒ 拒绝施放，绝不退化成放行到全场，
     *       见 {@code AGENTS.md} 铁律 10）。理由：这是每张召唤卡每次右键都会走的路径，
     *       抛异常会把一个「配置没配好」放大成对局中的异常刷屏。</li>
     * </ul>
     *
     * <p>★ 键名本身<b>带点</b>（{@code half.red.1}）：Bukkit 会把 dotted 键归一化成嵌套路径，
     * 所以这里复用上面已经处理过展平的 {@link #point(String)}，<b>不要</b>再写一遍展平逻辑。
     *
     * <p>★★ 与 {@link #isReadyForMatch()} 的关系：
     * <b>2026-10-15 用户裁定（选项 ①「把它纳入"开局必须"」）之后，{@code half.*} <u>参与</u>就绪判定</b>
     * —— 4 个角点都在 {@link #requiredKeys()} 里（{@code REQUIRED_COUNT} = 12）。
     * 本次裁定<b>只改判据、不动本方法</b>：
     * <ul>
     *   <li>「任一缺失 ⇒ {@code null}」不变；</li>
     *   <li>「两个角点世界不一致 ⇒ {@code null}（<b>不抛</b>，交调用方 fail-safe）」不变 ——
     *       就绪判定只看<b>键在不在</b>，而"两角世界不一致"是<b>值</b>的问题，
     *       {@link #isReadyForMatch()} 不校验几何/世界一致性（那需要人眼，见 {@code docs/12}）。</li>
     * </ul>
     * ⇒ 也就是说：裁定之后「缺角点」在开局前就被拦下，但「两角世界不一致」这份配置<b>仍然到得了这里</b>
     * （它有键、值却是坏的）⇒ {@code PlacementResolver} 的
     * {@code REFUSE_NO_HALF_CONFIGURED} 与文案键 {@code errors.half-not-configured}
     * <b>仍然要保留</b>（探针与这种边界仍会遇到）。
     *
     * <p>★ 键名本身<b>带点</b>（{@code half.red.1}）：Bukkit 会把 dotted 键归一化成嵌套路径，
     * 所以这里复用上面已经处理过展平的 {@link #point(String)}，<b>不要</b>再写一遍展平逻辑。
     *
     * @return 该方半场矩形；缺失或世界不一致时 {@code null}
     */
    public HalfField half(Side side) {
        Point first = point("half." + tag(side) + ".1");
        Point second = point("half." + tag(side) + ".2");
        if (first == null || second == null) {
            return null;
        }
        if (!first.sameWorldAs(second)) {
            // ★ 异世界矩形：返回 null（fail-safe），不抛 —— 让调用方按「半场不可用」拒绝施放
            return null;
        }
        return HalfField.of(first, second);
    }

    /** 该方半场矩形是否可用（{@code = half(side) != null}）。 */
    public boolean hasHalf(Side side) {
        return half(side) != null;
    }

    /** 枚举 → 键里的阵营后缀。 */
    private static String tag(Side side) {
        if (side == null) {
            throw new IllegalArgumentException("阵营不能为 null");
        }
        return side.name().toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ 就绪判定

    /**
     * ★ 一场对局**能不能开**：信标 ×2 + 防御塔 ×4 + 出生点 ×2 + **半场角点 ×4** 是否齐全。
     *
     * <p>★ <b>2026-10-15 用户裁定 = ①「把它纳入"开局必须"」</b>：{@code half.*} 从第 12 个键起
     * 一起算（见 {@link #REQUIRED_COUNT} 的 javadoc —— 那里写清了这条裁定修掉的是哪一条假绿）。
     *
     * <p>只按「键在不在」判定，**不校验几何关系**（例如两侧塔是否真的分居左右、
     * 河是否夹在两半场之间、同一方两个半场角点是否在同一个世界）——
     * 那需要真人拍板的世界观，属于实机冒烟清单的范围（{@code docs/12}）。
     */
    public boolean isReadyForMatch() {
        return missingKeysForMatch().isEmpty();
    }

    /**
     * 缺哪些键（**保序**；中文描述留给调用方）—— 用于「配置不全时明确告诉服主缺什么」。
     *
     * @return 缺失的**裸键**列表（判据是 {@link #requiredKeys()}）；齐全时返回空列表
     */
    public List<String> missingKeysForMatch() {
        List<String> missing = new ArrayList<>();
        for (String key : requiredKeys()) {
            if (!points.containsKey(key)) {
                missing.add(key);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * 启动日志用的一行摘要。
     *
     * <p>★ 「对局必需 N 个」里的 N <b>取自 {@link #REQUIRED_COUNT}</b>（不是写死的字面量）——
     * E14 裁定把 8 改成 12 时，这条日志会自己跟着变（这正是它该有的行为）。
     */
    public String describe() {
        return FILE + "：" + points.size() + " / " + KEYS.size() + " 个点位"
                + "（对局必需 " + REQUIRED_COUNT + " 个（含 4 个半场角点），"
                + (isReadyForMatch() ? "已齐全" : "缺 " + missingKeysForMatch()) + "）";
    }

    // ==================================================================================
    // ★ 写回（`/incl arena` 子指令与 U1 竞技场编辑界面**共用**的唯一入口）
    // ==================================================================================

    /**
     * 生成 {@code arenas.yml} 的**表头注释**。
     *
     * <p>它不参与解析（YAML 注释），只回答后人一个问题：「这个文件是谁写的、判据在哪」。
     * 尤其是<b>键名的唯一来源</b>与<b>本文件不迁移</b>这两件事 ——
     * 少写一句，下一个来改的人就会去 {@code ConfigMigrator} 里加它（{@code docs/03} §7 明确不让）。
     */
    private static final List<String> HEADER = List.of(
            "# arenas.yml —— 竞技场点位（数据文件：由 /incl arena 或竞技场编辑界面写入）。",
            "#",
            "# 键名由 arena/ArenaConfig.KEYS 定死（" + KEYS.size() + " 个）；写错一个键，下次加载会明确报错",
            "# —— 本文件不参与「出厂 → 现场」的只增不改迁移（docs/03 §7 的文件分类表）。",
            "# 本文件由程序生成（文本自建、保序、嵌套键）；每次写盘前旧文件会备份为 "
                    + FILE + ".bak，注释不会被保留。");

    /**
     * 可以**不加引号**直接写进 YAML 的字符串形态（世界名就是这一类：{@code lobby} / {@code world_nether}）。
     *
     * <p>★ 为什么不用「什么都不引号」：世界名是服主可控的字符串，
     * 一旦出现 {@code world: 主世界 #1} 这种值，无脑拼接就会写出**语义不同的 YAML**
     * （{@code #} 之后成了注释）。判据收紧到「字母数字下划线开头 + 只含 {@code ._ -}」，
     * 其余一律加双引号 —— 宁可多两个引号，也不要写出读不回来的文件。
     */
    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]*");

    /** YAML 1.1 里会被解析成非字符串的裸词（必须加引号，否则世界名 {@code no} 会变成布尔）。 */
    private static final Set<String> YAML_RESERVED = Set.of(
            "true", "false", "null", "yes", "no", "on", "off", "y", "n", "~");

    /**
     * ★ <b>把点位表写成 {@code arenas.yml} 的文本</b>（纯文本构造，<b>不依赖 Bukkit</b>）。
     *
     * <h2>三条硬性要求（{@code docs/09-UI设计.md} §2.6 的「写回规矩」）</h2>
     * <ol>
     *   <li><b>保序</b>：顺序 = {@link #KEYS} 顺序（★ <b>不是</b> {@code points()} 的迭代顺序，
     *       更不是 {@code Map.copyOf} 的随机序 —— InfernalGuess 就是栽在这里，见 §2.3）；</li>
     *   <li><b>写嵌套键</b>：{@code beacon.red} 必须写成 {@code beacon:} → {@code red:} 两级。
     *       Bukkit 读进来时本来就会把 dotted 键归一化成嵌套段落（{@code AGENTS.md} §4.1，
     *       本项目踩过两次），所以<b>嵌套才是规范形态</b>；写成 {@code beacon.red:} 虽然也能读，
     *       但会让「文件里写的样子」与「内存里的样子」不一致；</li>
     *   <li>只写<b>已采集</b>的点位 —— 没采过的键连空壳都不写（否则「空点位」与「点位缺字段」
     *       会在下一次加载时变成两种完全不同的错）。</li>
     * </ol>
     *
     * <h2>★ 为什么是自建文本而不是 {@code YamlConfiguration.save()}</h2>
     * 后者会丢掉全部注释，而本文件的表头注释就是它的说明书
     * （与 {@code ConfigMigrator} 坚持「文本级插入」同一条理由，{@code docs/03} §7）。
     *
     * <p>★ 输出的字符集由调用方决定：{@link #save} 用 UTF-8 且<b>不写 BOM</b>
     * （{@code ShippedConfigTest} 的同类纪律：BOM 会让首行的键变成 {@code \uFEFFpoints}）。
     *
     * @param cfg 点位表；{@code null} 视同空表（**不抛** —— 写回路径的失败方向是「告警」而不是「炸界面」）
     * @return 可直接写盘的完整文本（以 {@code \n} 结尾）
     */
    public static String toYaml(ArenaConfig cfg) {
        ArenaConfig config = cfg == null ? empty() : cfg;
        StringBuilder out = new StringBuilder(2048);
        for (String line : HEADER) {
            out.append(line).append('\n');
        }
        appendPoints(out, config, 0);
        return out.toString();
    }

    /**
     * ★ <b>把 {@code points:} 这一段追加到 {@code out}</b>（多竞技场文件也走这里 ⇒ 只有一份写入实现）。
     *
     * @param indentLevel {@code points:} 自己所在的缩进级（0 = 顶格；1 = 在 {@code arenas.<名字>:} 下面）
     */
    static void appendPoints(StringBuilder out, ArenaConfig config, int indentLevel) {
        List<String> keys = orderedKeys(config);
        String pointsIndent = indent(indentLevel);
        if (keys.isEmpty()) {
            // ★ 一个点位都没有时写 `points: {}`（显式空表）而不是留一个空的 `points:` 段落 ——
            //   空段落会被解析成 null（AGENTS.md §4.1），读回来虽然也是空表，但那是「碰巧对」，
            //   写显式空表才是**说清楚**了。
            out.append(pointsIndent).append("points: {}\n");
            return;
        }
        out.append(pointsIndent).append("points:\n");
        // 逐键写入，只把「与上一个键不同的那几级」重新写出来（于是 beacon.red 与 beacon.blue 共用 beacon:）
        String[] previous = new String[0];
        for (String key : keys) {
            Point point = config.point(key);
            String[] parts = key.split("\\.");
            int common = commonPrefixLength(previous, parts);
            for (int level = common; level < parts.length; level++) {
                out.append(indent(indentLevel + level + 1)).append(parts[level]).append(":\n");
            }
            previous = parts;
            int fieldIndent = indentLevel + parts.length + 1;
            appendField(out, fieldIndent, "world", yamlScalar(point.world()));
            appendField(out, fieldIndent, "x", Double.toString(point.x()));
            appendField(out, fieldIndent, "y", Double.toString(point.y()));
            appendField(out, fieldIndent, "z", Double.toString(point.z()));
            appendField(out, fieldIndent, "yaw", Float.toString(point.yaw()));
            appendField(out, fieldIndent, "pitch", Float.toString(point.pitch()));
        }
    }

    /** 表头注释（多竞技场写入器复用同一份；见 {@code ArenaCatalog}）。 */
    static List<String> header() {
        return HEADER;
    }

    /**
     * ★ <b>一次写盘的结果</b>（纯数据；形状与 {@code InfernalClashPlugin.MigrationOutcome} 一致）。
     *
     * @param changed    是否**真的写了盘**（{@code false} ⇒ 磁盘上的文件一个字节都没动）
     * @param backupName 备份文件名；<b>只在真的做过备份时非 null</b>
     * @param summary    给玩家 / 日志用的一句话（★ <b>带数量</b>：采了多少个点位）
     * @param warnings   失败或跳过的原因（调用方应原样打进日志与提示）
     */
    public record SaveOutcome(boolean changed, String backupName, String summary, List<String> warnings) {
    }

    /**
     * ★ <b>{@code arenas.yml} 的落盘入口</b>：① 先备份 → ② 写 {@link #toYaml} 的文本。
     *
     * <h2>★ 失败只告警、绝不抛（与 {@code InfernalClashPlugin.migrateConfigOnDisk} 同口径）</h2>
     * 这条路径同时被<b>指令</b>与<b>界面</b>调用：写盘失败若抛出去，
     * 轻则「点一下保存把事件总线炸掉」，重则让管理指令整条链断掉。
     * 而写盘失败**不是**世界末日 —— 正确的话是「告诉操作者没写进去，磁盘上的旧文件还在」。
     *
     * <h2>★ 顺序不能反：先备份，后写</h2>
     * 备份的内容是<b>改动前的原文</b>（覆盖式，{@code arenas.yml.bak}）。反过来的话，
     * 备份下来的就是刚写进去的新内容 —— 那不是备份，那是自我安慰。
     * 备份失败（例如 .bak 被占用）时<b>不写盘</b>：宁可这次不保存，也不要留下一个不可回退的改动。
     *
     * <p>★ 本方法<b>不</b>解析磁盘上的旧文件（与 {@code migrateConfigOnDisk} 不同）：
     * 写进去的内容来自**已经在内存里通过严格校验的点位表**，而且旧内容已经先备份了
     * ⇒ 没有「往里插，越插越坏」的风险；多解析一次只会让「旧文件恰好被手改坏」
     * 变成「这次保存也做不了」。
     *
     * @param file 目标文件（通常是 {@code plugins/InfernalClash/arenas.yml}）
     * @param cfg  要写入的点位表
     * @return 写盘结果（**永远非 null，永远不抛**）
     */
    public static SaveOutcome save(File file, ArenaConfig cfg) {
        List<String> warnings = new ArrayList<>();
        String name = file == null ? FILE : file.getName();
        int collected = cfg == null ? 0 : cfg.size();
        if (file == null) {
            warnings.add("目标文件为 null，一个字节都没写");
            return new SaveOutcome(false, null,
                    FILE + "：未写入磁盘（目标文件为 null）", List.copyOf(warnings));
        }
        if (cfg == null) {
            // ★ 绝不因为「传了个 null」就把服主辛苦采的点位表清空
            warnings.add("点位表为 null，拒绝写盘（否则会用一个空表覆盖现场文件）");
            return new SaveOutcome(false, null,
                    name + "：未写入磁盘（点位表为 null）", List.copyOf(warnings));
        }

        String text = toYaml(cfg);
        String backupName = null;
        try {
            if (file.isFile()) {
                // ① 先备份（覆盖式）：内容 = **改动前**的原文
                backupName = file.getName() + ".bak";
                Files.copy(file.toPath(), file.toPath().resolveSibling(backupName),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            // ② 后写盘（UTF-8、无 BOM）
            Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            return new SaveOutcome(true, backupName,
                    name + "：已写入 " + collected + " / " + KEYS.size() + " 个点位"
                            + (backupName == null
                            ? "（原文件不存在，未生成备份）"
                            : "（旧文件已备份为 " + backupName + "）"),
                    List.copyOf(warnings));
        } catch (Exception e) {
            warnings.add("写盘失败（" + e + "），磁盘上的文件保持原样"
                    + (backupName == null ? "" : "（旧内容已先备份为 " + backupName + "）")
                    + "；内存里的改动仍然有效，但没有写入磁盘");
            return new SaveOutcome(false, null,
                    name + "：未写入磁盘（" + collected + " / " + KEYS.size() + " 个点位只在内存里）",
                    List.copyOf(warnings));
        }
    }

    // ------------------------------------------------------------------ 写回内部

    /**
     * 写入顺序：**先按 {@link #KEYS} 的约定顺序**，再追加内存表里多出来的键（若有）。
     *
     * <p>多出来的那一档是防御性的：{@link #from} 会拒绝未知键，所以正常路径上不会有；
     * 但 {@code ArenaConfig} 的构造器是公开的（record），真有人手工造一个带未知键的表时，
     * <b>静默丢掉</b>比写出去更糟（丢数据不报错）。
     */
    private static List<String> orderedKeys(ArenaConfig cfg) {
        List<String> ordered = new ArrayList<>(KEYS.size());
        for (String key : KEYS) {
            if (cfg.has(key)) {
                ordered.add(key);
            }
        }
        for (String key : cfg.points().keySet()) {
            if (!KEYS.contains(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    /** 上一个键与本键**共有的前几级**（用于少写重复的中间节点）。 */
    private static int commonPrefixLength(String[] previous, String[] current) {
        int limit = Math.min(previous.length, current.length);
        int common = 0;
        while (common < limit && previous[common].equals(current[common])) {
            common++;
        }
        return common;
    }

    /** YAML 缩进（每级 2 空格，与出厂 / 草稿文件一致）。 */
    private static String indent(int level) {
        return "  ".repeat(level);
    }

    private static void appendField(StringBuilder out, int level, String field, String value) {
        out.append(indent(level)).append(field).append(": ").append(value).append('\n');
    }

    /**
     * 把一个字符串写成 YAML 标量：能裸写就裸写，否则加双引号（见 {@link #PLAIN_SCALAR}）。
     *
     * <p>{@code null} 写成 {@code ""}（显式空串）：它读回来会被严格校验拒掉
     * （{@code world} 不能为空）—— 这正是我们要的：<b>坏数据不许静默往返成功</b>。
     */
    private static String yamlScalar(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "\"\"";
        }
        boolean plain = PLAIN_SCALAR.matcher(raw).matches()
                && !YAML_RESERVED.contains(raw.toLowerCase(Locale.ROOT));
        if (plain) {
            return raw;
        }
        return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
