package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.arena.ArenaCatalog;
import io.mczju.infernalclash.arena.ArenaConfig;
import io.mczju.infernalclash.arena.HalfField;
import io.mczju.infernalclash.arena.Point;
import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.match.Side;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * ★ <b>U1 竞技场编辑界面</b>（{@code docs/09-UI设计.md} §2，逐格表见 §2.5）。
 *
 * <h2>★★ 为什么用原版 27 格 {@code Inventory} + {@code InventoryHolder}，而不是 MGC 的 {@code Menu}</h2>
 * 这是 <b>InfernalGuess 的原版选型</b>（{@code docs/09} §1 的载体表逐字引用它的理由）：
 * 「<b>纯管理工具、与对局状态无关，避免为此耦合 MGC 菜单体系</b>」。
 * 本项目沿用它的三条具体理由：
 * <ol>
 *   <li>它<b>不属于玩家日常界面</b>（U2/U4/U5 才是），不需要 MGC 的存档 / 进度 / 翻页那一套；</li>
 *   <li>它<b>与会话无关</b>：管理员随时打开、随时关闭，不参与任何对局生命周期；</li>
 *   <li>它是<b>数据录入工具</b>：交互模型（开界面时记位置 → 左键绑定）与 MGC 菜单的「槽位动作表」
 *       根本不是一回事，硬塞进 {@code Menu} 只会多一层翻译。</li>
 * </ol>
 * ⚠️ 代价是它<b>不走</b> {@code MenuRegistry}（那是 MGC 菜单的注册表）
 * ⇒ 它的打开入口由 {@link io.mczju.infernalclash.command.InfernalClashCommand} 直接调
 * {@link #open}，而 {@code MenuRegistry.menuIds()} 里**不该**出现它
 * （{@code MenuSubcommandsTest#everyRegisteredMenuHasAnEntryPoint} 正守着这条口径）。
 *
 * <h2>交互模型（【复用】IG，{@code docs/09} §2.1）</h2>
 * <ul>
 *   <li>打开界面时<b>记录玩家当时的位置</b>（{@link #openLocation()}）；</li>
 *   <li><b>左键</b>点位格 = 用那个位置<b>绑定</b>该点位（所以「站在目标位置 → 开界面 → 左键」即可，不必输坐标）；</li>
 *   <li><b>右键</b>点位格 = 传送到该点位已存坐标去查看（未采集 ⇒ 一句提示，不传送）；</li>
 *   <li><b>Shift + 左键</b>点位格 = 清除该点位；</li>
 *   <li>{@code 18} 清空全部（★ 必须 Shift + 左键）/ {@code 22} 保存 / {@code 26} 关闭。</li>
 * </ul>
 *
 * <h2>★ 改动只在「保存」时落盘（与 IG 一致，{@code docs/09} §2.5 末段）</h2>
 * 界面里改的是<b>内存中的工作副本</b>（{@link #working()}），
 * ⇒ 「点错了但没保存」可以靠<b>关闭界面</b>无副作用地放弃。
 * 写盘走的是与 {@code /incl arena set|remove} <b>同一个</b>函数
 * {@link ArenaConfig#save(File, ArenaConfig)}（先备份、后写盘、失败只告警）。
 *
 * <h2>★ 文案纪律（R76 + 铁律 8 / 11）</h2>
 * 标题与五条点位文案<b>经 {@link MenuTexts} 读</b> {@code display.yml} 的
 * {@code messages.arena-edit.*}（键已存在，但出厂值仍是占位 {@code [待填]}），
 * 读不到就回退到本类里的中文常量并<b>告警一次</b>（{@code [待填]} 绝不印给玩家）。
 * ★ <b>不改 {@code display.yml}、不新增键</b>；还没有键的反馈文案（绑定成功 / 保存结果 …）
 * 继续用代码常量，等 E1 定案时再建键 —— 不发明键、也不发明措辞（本轮的任务边界）。
 *
 * <h2>★ 图标为什么不读 {@code icons.*}</h2>
 * 出厂 {@code display.yml} 的图标组只有 {@code shop} / {@code collection}
 * （{@code DisplayConfig.ICON_GROUPS}），<b>没有</b> IG 那样的 {@code common.filler}
 * ⇒ 读它会抛 {@code ConfigError}；本轮又明令「不新增键」
 * ⇒ 图标一律用 {@link Material} 常量（{@code Material} 与文案是两件事，不违反 R76 的「文本可配置」）。
 *
 * <h2>★ 铁律 10（本类的范围界定）</h2>
 * 本类<b>不</b>遍历世界/实体/在线玩家；它只读写<b>一个</b>具体的箱子与自己那份工作副本。
 * 唯一的「广域动作」是右键传送，范围与失败方向写在
 * {@link ArenaEditListener#onClick} 的 scope proof 里。
 */
public final class ArenaEditMenu implements InventoryHolder {

    // ------------------------------------------------------------------ 文案键（display.yml 的 messages.*）

    /** 界面标题的配置键（{@code messages.arena-edit.title}）。 */
    static final String KEY_TITLE = "arena-edit.title";

    /** 点位按钮名的配置键（{@code messages.arena-edit.point}，带 {@code {point}}）。 */
    static final String KEY_POINT = "arena-edit.point";

    /** 「左键绑定」说明的配置键（{@code messages.arena-edit.bind}）。 */
    static final String KEY_BIND = "arena-edit.bind";

    /** 「右键传送」说明的配置键（{@code messages.arena-edit.teleport}）。 */
    static final String KEY_TELEPORT = "arena-edit.teleport";

    /** 「点位尚为空」展示的配置键（{@code messages.arena-edit.point-empty}）。 */
    static final String KEY_POINT_EMPTY = "arena-edit.point-empty";

    // ------------------------------------------------------------------ 回退常量（配置读不到时用）

    /** 界面标题的回退常量。★ 目标键 {@link #KEY_TITLE}（出厂值 {@code [待填]}）。 */
    public static final String TITLE = "竞技场编辑";

    /**
     * 点位按钮名的回退常量（{@code %s} = 点位键）。
     *
     * <p>★ 目标键 {@link #KEY_POINT}（带 {@code {point}}）。
     */
    public static final String NAME_POINT = "<yellow>%s";

    /** 「左键绑定」说明的回退常量。★ 目标键 {@link #KEY_BIND}。 */
    public static final String LORE_BIND = "<green>左键：把「打开界面时站的位置」绑定到该点位";

    /** 「右键传送」说明的回退常量。★ 目标键 {@link #KEY_TELEPORT}。 */
    public static final String LORE_TELEPORT = "<aqua>右键：传送过去查看";

    /** 「点位尚为空」的回退常量。★ 目标键 {@link #KEY_POINT_EMPTY}。 */
    public static final String LORE_POINT_EMPTY = "<gray>尚未采集";

    /** 「Shift + 左键清除该点位」的回退常量（与 {@code /incl arena remove <点位名>} 同语义）。 */
    public static final String LORE_CLEAR_ONE = "<red>Shift + 左键：清除该点位";

    /** 信息格名字的回退常量（{@code %d}/{@code %d} = 已采集数 / 总数）。 */
    public static final String NAME_INFO = "<gold>已采集 %d / %d 个点位";

    /** 「保存」按钮名的回退常量。 */
    public static final String NAME_SAVE = "<green>保存";

    /** 「保存」按钮 lore：写回路径（先备份、后写盘、失败只告警）。 */
    public static final String LORE_SAVE = "<gray>写回 arenas.yml（先备份为 arenas.yml.bak）";

    /** 「保存」按钮 lore：改动只在保存时落盘。 */
    public static final String LORE_SAVE_HINT = "<dark_gray>改动只在保存时落盘；关闭界面会丢弃未保存的改动";

    /** 「清空全部点位」按钮名的回退常量。 */
    public static final String NAME_CLEAR = "<red>清空全部点位";

    /** 「清空全部点位」按钮 lore（★ 写明要求的組合，防误触）。 */
    public static final String LORE_CLEAR_ALL = "<red>需要 Shift + 左键才生效（防误触）";

    /** 「清空全部点位」按钮 lore：与 {@code /incl arena remove} 的关系。 */
    public static final String LORE_CLEAR_ALL_HINT = "<gray>会去掉全部点位（等于逐个 remove）；未保存则无副作用";

    /** 「关闭」按钮名的回退常量。 */
    public static final String NAME_CLOSE = "<yellow>关闭";

    /** 「关闭」按钮 lore。 */
    public static final String LORE_CLOSE = "<gray>关闭并丢弃未保存的改动（改动只在保存时落盘）";

    // ------------------------------------------------------------------ 反馈文案（尚无可读的 display.yml 键）

    /** 绑定成功的提示（{@code %s} = 点位键，{@code %s} = 坐标）。 */
    public static final String MSG_BOUND = "已绑定点位 %s ← %s（还没落盘，记得点「保存」）";

    /** 清除单个点位成功的提示。 */
    public static final String MSG_CLEARED = "已清除点位 %s（还没落盘，记得点「保存」）";

    /** 清空全部点位成功的提示（{@code %d} = 清掉几个）。 */
    public static final String MSG_CLEARED_ALL = "已清空全部点位（%d 个）（还没落盘，记得点「保存」）";

    /** 「清空全部」没按 Shift 时的提示。 */
    public static final String MSG_CLEAR_NEEDS_SHIFT = "清空全部点位需要 Shift + 左键（防误触），本次什么都没做。";

    /** 右键一个未采集点位的提示。 */
    public static final String MSG_TELEPORT_EMPTY = "这个点位还没有采集过，没有可传送的坐标（站在目标位置用左键绑定）。";

    /** 右键传送时世界不存在（fail-safe：不传送）。 */
    public static final String MSG_TELEPORT_NO_WORLD = "点位所在的世界不存在：%s，没有传送（fail-safe）。";

    /** 保存成功的提示（{@code %s} = {@code ArenaConfig.SaveOutcome.summary()}）。 */
    public static final String MSG_SAVE_OK = "已保存：%s";

    /** 保存失败的提示（{@code %s} = 原因）。★ 「未写入磁盘」必须说出来。 */
    public static final String MSG_SAVE_FAILED = "未写入磁盘：%s（内存里的改动仍然有效，可再点一次「保存」重试）";

    // ------------------------------------------------------------------ 字段

    private final InfernalClashPlugin plugin;
    private final Player player;
    private final Location openLocation;
    private final Inventory inventory;
    private final MiniMessage mini = MiniMessage.miniMessage();

    /** ★ 内存里的**工作副本**（只有「保存」才会把它写进 {@code arenas.yml}）。 */
    private final Map<String, Point> working = new LinkedHashMap<>();

    /**
     * @param plugin 本插件（{@code null} ⇒ 由 {@link #open} 在构造前拦掉）
     * @param player 打开界面的管理员（★ 只操作<b>这一名</b>玩家）
     */
    public ArenaEditMenu(InfernalClashPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        // ★ 交互模型的关键一步：记下「打开界面时站的位置」（docs/09 §2.1）
        this.openLocation = player.getLocation().clone();
        ArenaConfig current = currentConfig();
        if (current != null) {
            working.putAll(current.points());
        }
        this.inventory = Bukkit.createInventory(this, ArenaEditLayout.SIZE,
                mini.deserialize(title()));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** 打开这个界面的玩家（监听器据此只动他一个人）。 */
    public Player player() {
        return player;
    }

    /** 打开界面时玩家所在的位置 —— <b>绑定操作只用它</b>（{@code docs/09} §2.1）。 */
    public Location openLocation() {
        return openLocation.clone();
    }

    /** 本插件实例（保存 / 热载都要用它）。 */
    public InfernalClashPlugin plugin() {
        return plugin;
    }

    // ------------------------------------------------------------------ 工作副本

    /**
     * 工作副本的快照（★ <b>按 {@link ArenaConfig#allKeys()} 的顺序</b>重建，
     * 与 {@link ArenaConfig#toYaml} 的写入顺序口径一致 —— 这样「界面里看到的顺序」＝「文件里的顺序」）。
     */
    public ArenaConfig working() {
        Map<String, Point> ordered = new LinkedHashMap<>();
        for (String key : ArenaConfig.allKeys()) {
            Point point = working.get(key);
            if (point != null) {
                ordered.put(key, point);
            }
        }
        // 防御性：正常路径上不会有白名单之外的键（bind 自己会拒绝），真有就保留而不是静默丢掉
        for (Map.Entry<String, Point> entry : working.entrySet()) {
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new ArenaConfig(Collections.unmodifiableMap(ordered));
    }

    /** 该点位在工作副本里的值；未采集 ⇒ {@code null}。 */
    public Point point(String key) {
        return working.get(key);
    }

    /** 该点位是否已采集（工作副本口径）。 */
    public boolean has(String key) {
        return working.containsKey(key);
    }

    /**
     * 用「打开界面时站的位置」绑定一个点位。
     *
     * @param key   点位键（★ 只接受 {@link ArenaConfig#allKeys()} 里的键 ——
     *              写进去的键会被 {@code arenas.yml} 的严格校验审视，白名单之外的一律拒绝）
     * @param point 要写进去的坐标
     * @throws IllegalArgumentException 键不在白名单里
     */
    public void bind(String key, Point point) {
        requireLegalKey(key);
        if (point == null) {
            throw new IllegalArgumentException("bind：坐标不能为 null（点位键 " + key + "）");
        }
        working.put(key, point);
    }

    /**
     * 清除一个点位。
     *
     * @return 是否真的清掉了一个已采集的点位（{@code false} = 本来就没采集）
     */
    public boolean clear(String key) {
        requireLegalKey(key);
        return working.remove(key) != null;
    }

    /**
     * 清空全部点位。
     *
     * @return 清掉了几个（用于「带数量」的提示与日志）
     */
    public int clearAll() {
        int before = working.size();
        working.clear();
        return before;
    }

    /** 工作副本里已采集的点位数。 */
    public int size() {
        return working.size();
    }

    /**
     * 把工作副本写回磁盘（★ 与 {@code /incl arena set|remove} 走<b>同一个</b>落盘入口）。
     *
     * <h2>★★ 2026-10-18：界面只编辑「默认竞技场」，写盘必须走**竞技场目录**</h2>
     * 旧实现直接 {@code ArenaConfig.save(file, working())} —— 那是**单竞技场**格式，
     * 而 2026-10-18 起文件里可以有多个竞技场 ⇒ 那样写一次会把**别的竞技场整段抹掉**
     * （表现：管理员在界面里点保存，另一个竞技场消失了）。⇒ 现在改成
     * {@code plugin.saveArenaCatalog(目录.with(默认名, 工作副本))}：只替换默认那一个。
     */
    public ArenaConfig.SaveOutcome save() {
        ArenaCatalog catalog = plugin.arenaCatalog();
        String name = plugin.defaultArenaName() == null
                ? ArenaCatalog.LEGACY_NAME : plugin.defaultArenaName();
        ArenaCatalog.SaveOutcome outcome = plugin.saveArenaCatalog(catalog.with(name, working()),
                "edit " + name);
        return new ArenaConfig.SaveOutcome(outcome.changed(), outcome.backupName(),
                outcome.summary(), outcome.warnings());
    }

    /** {@code arenas.yml} 的路径。 */
    public File file() {
        return new File(plugin.getDataFolder(), ArenaConfig.FILE);
    }

    private static void requireLegalKey(String key) {
        if (key == null || !ArenaConfig.allKeys().contains(key)) {
            throw new IllegalArgumentException("点位键不在约定清单里（写进去会被严格校验拒掉）：" + key
                    + "，合法键：" + ArenaConfig.allKeys());
        }
    }

    // ------------------------------------------------------------------ 渲染

    /** 重新渲染（任何一次改动之后都调它，玩家立刻看到新状态）。 */
    public void render() {
        DisplayConfig display = displayOrNull();
        Consumer<String> warner = this::warn;
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < ArenaEditLayout.SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        for (Map.Entry<Integer, String> entry : ArenaEditLayout.POINT_SLOTS.entrySet()) {
            inventory.setItem(entry.getKey(), pointIcon(display, warner, entry.getValue()));
        }
        inventory.setItem(ArenaEditLayout.INFO_SLOT, infoIcon(display));
        inventory.setItem(ArenaEditLayout.CLEAR_SLOT, icon(Material.MAGMA_BLOCK, NAME_CLEAR,
                List.of(LORE_CLEAR_ALL, LORE_CLEAR_ALL_HINT)));
        inventory.setItem(ArenaEditLayout.SAVE_SLOT, icon(Material.EMERALD_BLOCK, NAME_SAVE,
                List.of(LORE_SAVE, LORE_SAVE_HINT)));
        inventory.setItem(ArenaEditLayout.CLOSE_SLOT, icon(Material.BARRIER, NAME_CLOSE,
                List.of(LORE_CLOSE)));
    }

    /** 一个点位格：已采集 ⇒ 亮色 + 坐标；未采集 ⇒ 灰 + 「尚未采集」。 */
    private ItemStack pointIcon(DisplayConfig display, Consumer<String> warner, String key) {
        String name = MenuTexts.resolve(display, KEY_POINT,
                String.format(NAME_POINT, key), Map.of("point", key), warner);
        Point point = working.get(key);
        List<String> lore = new ArrayList<>();
        if (point == null) {
            lore.add(MenuTexts.resolve(display, KEY_POINT_EMPTY, LORE_POINT_EMPTY, warner));
            lore.add(MenuTexts.resolve(display, KEY_BIND, LORE_BIND, warner));
            return icon(Material.GRAY_DYE, name, lore);
        }
        lore.add(describe(point));
        lore.add(MenuTexts.resolve(display, KEY_BIND, LORE_BIND, warner));
        lore.add(MenuTexts.resolve(display, KEY_TELEPORT, LORE_TELEPORT, warner));
        lore.add(LORE_CLEAR_ONE);
        return icon(Material.LIME_DYE, name, lore);
    }

    /**
     * 信息格：已采集 {@code n/16}、缺哪些<b>对局必需</b>键、世界名、{@code half.*} 是否可用
     * （{@code docs/09} §2.5 第 4 格的要求，一条不落）。
     *
     * <p>★ <b>「必需 N 个」里的 N 不写死</b>：取自 {@link ArenaConfig#requiredCount()}。
     * 2026-10-15 用户裁定（E14 = ①「把它纳入"开局必须"」）把必需键从 8 提到 12
     * （{@code half.*} 四个角点进来了）⇒ 写死「8」会让这一格显示「已齐全」，
     * 而同一格下面的 judging 已经是「缺 half.…」。
     */
    private ItemStack infoIcon(DisplayConfig display) {
        ArenaConfig config = working();
        List<String> lore = new ArrayList<>();
        lore.add("<white>对局必需 " + ArenaConfig.requiredCount() + " 个：" + (config.isReadyForMatch()
                ? "<green>已齐全"
                : "<red>缺 " + config.missingKeysForMatch()));
        lore.add("<white>世界：" + worldsText(config));
        lore.add("<white>half.*：红方 " + halfText(config, Side.RED)
                + "<white> ｜ 蓝方 " + halfText(config, Side.BLUE));
        lore.add("<dark_gray>打开界面时的位置：" + describe(openLocation));
        lore.add("<dark_gray>左键绑定 / 右键传送 / Shift+左键清除；改动只在「保存」时落盘");
        return icon(Material.PAPER,
                String.format(NAME_INFO, config.size(), ArenaConfig.allKeys().size()), lore);
    }

    /** 工作副本里出现的世界名（保序、去重；一个点位都没有时明确写出来）。 */
    private static String worldsText(ArenaConfig config) {
        Set<String> worlds = new LinkedHashSet<>();
        for (String key : ArenaConfig.allKeys()) {
            Point point = config.point(key);
            if (point != null && point.world() != null) {
                worlds.add(point.world());
            }
        }
        return worlds.isEmpty() ? "（还没有任何点位）" : String.join("、", worlds);
    }

    /** 某一方半场是否可用（★ 判据只有 {@link ArenaConfig#half} 一处，不在这里重算矩形）。 */
    private static String halfText(ArenaConfig config, Side side) {
        HalfField field = config.half(side);
        return field == null
                ? "<red>不可用（缺 half." + side.name().toLowerCase(java.util.Locale.ROOT)
                        + ".1/2 或两个角点不在同一个世界）"
                : "<green>可用 " + field.describe();
    }

    /** 坐标 / 位置的一行描述（人看得懂的形状）。 */
    private static String describe(Point point) {
        return point.world() + " " + describe(point.x(), point.y(), point.z())
                + "（yaw " + point.yaw() + " / pitch " + point.pitch() + "）";
    }

    private static String describe(Location location) {
        return location.getWorld() == null
                ? "（世界未知）"
                : location.getWorld().getName() + " "
                        + describe(location.getX(), location.getY(), location.getZ());
    }

    private static String describe(double x, double y, double z) {
        return String.format("%.1f, %.1f, %.1f", x, y, z);
    }

    /**
     * 把一个点位变成可传送的 {@link Location}（★ <b>失败方向：世界不存在 ⇒ 返回 {@code null}</b>）。
     *
     * <p>刻意<b>不</b>退回「主世界出生点」或「玩家当前位置」之类的猜测：
     * 猜不出来就什么都不做，由调用方给一句提示（{@code AGENTS.md} 铁律 10 的失败方向）。
     */
    public static Location locationOf(Point point) {
        if (point == null) {
            return null;
        }
        World world = Bukkit.getWorld(point.world());
        if (world == null) {
            return null;
        }
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    // ------------------------------------------------------------------ 打开

    /**
     * ★ <b>打开界面</b>（{@code /incl arena} 不带子指令时的落点，见 {@code docs/09} §2.2 的【Q54】）。
     *
     * @return 是否真的打开了；{@code false} ⇒ 原因已写进日志
     */
    public static boolean open(InfernalClashPlugin plugin, Player player) {
        if (plugin == null || player == null) {
            return false;
        }
        try {
            ArenaEditMenu menu = new ArenaEditMenu(plugin, player);
            player.openInventory(menu.getInventory());
            // ★ 日志用菜单自己的工作副本大小（**不**去读 plugin.arenaConfig()：
            //   那个字段在「配置加载失败」时是 null，读它会让这里 NPE —— 而它就在 try 里，
            //   表现会是「界面打不开且只留一句模糊告警」）
            plugin.getLogger().info("[IC] 界面（U1）：为 " + player.getName()
                    + " 打开竞技场编辑界面（工作副本 " + menu.size() + " 个点位）");
            return true;
        } catch (Throwable t) {
            // ★ 一次打开失败绝不能冒到指令分发 / 事件总线上
            plugin.getLogger().log(Level.WARNING,
                    "[IC] 界面（U1）：打开竞技场编辑界面失败（已兜住）", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 内部辅助

    /** 界面标题（配置优先，读不到回退常量 + 告警一次）。 */
    private String title() {
        return MenuTexts.resolve(displayOrNull(), KEY_TITLE, TITLE, this::warn);
    }

    /** 本插件的 {@code display.yml}（已校验）；拿不到 ⇒ {@code null}（**绝不抛**）。 */
    private DisplayConfig displayOrNull() {
        try {
            return plugin == null ? null : plugin.display();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 打一条告警（既是 {@link MenuTexts} 的告警出口，也是本类自己的留痕口）。 */
    private void warn(String message) {
        try {
            if (plugin != null) {
                plugin.getLogger().warning("[IC] 界面（U1 竞技场编辑）：" + message);
            }
        } catch (Throwable ignored) {
            // 日志失败不能再造出第二个失败
        }
    }

    /** 造一个图标（名字与 lore 走 MiniMessage，与其它界面同一形状）。 */
    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (name != null && !name.isEmpty()) {
                meta.displayName(mini.deserialize(name));
            }
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(mini.deserialize(line));
                }
                meta.lore(lines);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * 当前的（已加载的）点位表；拿不到就返回空表。
     *
     * <p>「拿不到」= 插件未就绪 / 配置没加载 —— 这不是「界面打不开」的理由，
     * 而是「工作副本从空开始」（管理员照样可以逐点采集）。
     */
    private ArenaConfig currentConfig() {
        try {
            ArenaConfig config = plugin == null ? null : plugin.arenaConfig();
            return config == null ? ArenaConfig.empty() : config;
        } catch (Throwable t) {
            warn("读当前点位表失败（工作副本从空开始）：" + t);
            return ArenaConfig.empty();
        }
    }
}
