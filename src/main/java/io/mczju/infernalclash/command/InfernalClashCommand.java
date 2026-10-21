package io.mczju.infernalclash.command;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.game.GameMeta;
import com.github.mczjuops.mczjugamecore.game.GameState;
import com.github.mczjuops.mczjugamecore.game.room.AbstractGameRoom;
import com.github.mczjuops.mczjugamecore.game.room.GameRoomState;
import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.ai.TrainingMatchRules;
import io.mczju.infernalclash.arena.ArenaCatalog;
import io.mczju.infernalclash.arena.ArenaConfig;
import io.mczju.infernalclash.arena.ArenaLayout;
import io.mczju.infernalclash.arena.HalfField;
import io.mczju.infernalclash.arena.Point;
import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.config.ConfigError;
import io.mczju.infernalclash.config.ObjectiveConfig;
import io.mczju.infernalclash.game.CardLoadout;
import io.mczju.infernalclash.game.InfernalClashGame;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.objective.LegacyPurge;
import io.mczju.infernalclash.objective.LegacyPurgeService;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.objective.ObjectiveKind;
import io.mczju.infernalclash.objective.ObjectiveUnit;
import io.mczju.infernalclash.profile.InfernalPlayerData;
import io.mczju.infernalclash.profile.PlayerProgress;
import io.mczju.infernalclash.profile.ProfileStore;
import io.mczju.infernalclash.profile.R44BasicGrants;
import io.mczju.infernalclash.ui.ArenaEditMenu;
import io.mczju.infernalclash.ui.MenuRegistry;
import io.mczju.infernalclash.ui.MenuTexts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /incl} 指令。
 *
 * <h2>底座阶段的定位</h2>
 * 这里只有「打开界面」「看状态」与「排障」三类子命令 —— **没有任何玩法指令**
 * （{@code shop} / {@code cards} 只是**打开界面**，不改任何对局状态）。
 * 玩法指令等玩法定稿后再加（届时同步 {@code plugin.yml} 的 usage 与本类的补全列表）。
 *
 * <h2>★ P8 接线：{@code /incl shop} / {@code /incl cards}（+ {@code /incl codex}）</h2>
 * 在此之前，{@code docs/12} §4.14 让用户敲的这两条指令<b>根本没注册</b>
 * （2026-10-15 实测：{@code grep '"shop"'} 与 {@code grep '"cards"'} 在本包内各 <b>0 命中</b>），
 * 而 {@code ui/MenuRegistry} 的 {@code openShop / openDeck / openCodex} 在 {@code src/main}
 * <b>零调用点</b> ⇒ 两者都是「死」的。现在：
 * <ul>
 *   <li>名字与动作的映射收在一处：{@link MenuSubcommands}（纯类，可离线断言
 *       ⇒ {@code MenuSubcommandsTest}，见那里的 javadoc 说明为什么这两条名字必须有测试钉住）；</li>
 *   <li>真正的打开由 {@link #openScreen} 调 {@link MenuRegistry} 完成
 *       —— <b>不改任何业务逻辑</b>（价格 / 开包 / 扣钱 / 权限语义一个字都没动）。</li>
 * </ul>
 * ⚠️ {@code plugin.yml} 的 {@code usage} 仍写 {@code /incl <info|debug>}（本轮不允许改出厂 YAML）。
 * 它只在 {@code onCommand} 返回 {@code false} 时才会被 Bukkit 打印，而本类<b>恒返回 {@code true}</b>
 * ⇒ 玩家看不到那句过时的 usage；真正的用法由 {@link #printUsage} 输出（已含三条新指令）。
 *
 * <p>⚠️ {@code docs/10} §2.1 的中文子命令别名（Q39：{@code 图鉴} → {@code cards} 等）
 * <b>仍未接</b> —— 本轮只注册英文名；要接时加在 {@link MenuSubcommands} 那张表里（一处）。
 *
 * <h2>★ 排障优先：{@code /incl debug room reset}</h2>
 * 它解决 MGC 子插件最常见的那类「看起来像缺陷」的状态：
 * 对局结束后房间停在 {@code IN_GAME} → {@code /mgc join} 报「没有空闲的房间」。
 * 复位**只作用于本游戏自己的房间**（按 {@code gameId} 取），
 * 不遍历全服、不碰别人的房间与计分板。
 */
public final class InfernalClashCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_PLAY = "infernalclash.play";
    private static final String PERM_ADMIN = "infernalclash.admin";

    /**
     * 控制台（或任何非玩家发送者）执行「打开界面」类子指令时的提示。
     *
     * <p>★ 目标键 {@code messages.command.players-only}：{@code display.yml} 的语义行逐字写着
     * 「控制台 / 玩家执行需要玩家的指令（<b>如 {@code /incl shop}</b>）时的提示」。
     * ★★ <b>2026-10-15（A8）已接线</b>：那句「措辞待 E1 定案 ⇒ 本轮继续用代码常量」的前提消失
     * （用户授权我拟稿，值已填）⇒ 本常量降级为**取不到配置时的回退常量**（玩家看到的仍是这句话）。
     */
    static final String MSG_PLAYERS_ONLY = "该指令只能由玩家执行（控制台没有背包，打不开界面）。";

    /**
     * 打开界面时出现<b>意料之外</b>的异常（已被兜住）时的提示。
     *
     * <p>★ {@code display.yml} 里**没有**同名键（27 条键里不含它）⇒ 它仍是代码常量，
     * 已登记为「R76 尚未覆盖的一条」（见 {@code docs/24} §5.5）。
     */
    static final String MSG_OPEN_FAILED = "打开界面时出错（详情见控制台日志）。";

    // ==================================================================================
    // ★ 文案键（`display.yml` 的 `messages.*`）—— 唯一来源；2026-10-15（A8）接线
    //   ★ 键名写错 ⇒ MenuTexts 静默回退到代码常量（玩家看不出差别）⇒ 由测试逐条钉住。
    // ==================================================================================

    /** 缺少权限（{@code infernalclash.play} / {@code infernalclash.admin}）。 */
    public static final String KEY_NO_PERMISSION = "command.no-permission";
    /** 需要玩家才能执行的指令（控制台没有背包 / 没有位置）。 */
    public static final String KEY_PLAYERS_ONLY = "command.players-only";
    /** 通用用法提示；占位符 {@code {usage}}。 */
    public static final String KEY_USAGE = "command.usage";
    /** {@code /incl debug …} 的用法提示；占位符 {@code {usage}}。 */
    public static final String KEY_DEBUG_USAGE = "command.debug-usage";
    /** {@code /incl debug room reset} 的用法提示；占位符 {@code {usage}}。 */
    public static final String KEY_ROOM_USAGE = "command.room-usage";
    /** 热重载成功。 */
    public static final String KEY_RELOAD_OK = "command.reload-ok";
    /** 热重载失败；占位符 {@code {error}}。 */
    public static final String KEY_RELOAD_FAILED = "command.reload-failed";
    /** 房间复位的对账结果；占位符 {@code {reset}} {@code {total}}。 */
    public static final String KEY_ROOM_RESET_DONE = "command.room-reset-done";
    /** 本游戏一个房间实例都没有。 */
    public static final String KEY_ROOM_NONE = "command.room-none";
    /** 按名字找不到在线玩家；占位符 {@code {player}}。 */
    public static final String KEY_PLAYER_NOT_FOUND = "command.player-not-found";
    /** 数量参数不是合法数字。 */
    public static final String KEY_INVALID_AMOUNT = "command.invalid-amount";

    /**
     * ★ 根子指令表 —— 由 {@link MenuSubcommands#names()}（{@code shop} / {@code cards} / {@code codex}）
     * 拼上底座既有的 {@code info} / {@code debug}。
     *
     * <p>刻意<b>从映射表拼</b>而不是各写一份：补全列表与分发表一旦分叉，就会出现
     * 「Tab 补得出来、敲下去却是未知子指令」（或反过来）。
     */
    private static final List<String> ROOT_ARGS = rootArgs();

    /**
     * ★ {@code /incl debug} 的子指令表（Tab 补全与用法输出共用）。
     *
     * <p>其中 R69 那五条（{@code docs/10} §3）<b>直接由</b> {@link DebugSubcommands#names()} 拼出
     * —— 补全列表与解析表一旦分叉，就会出现「Tab 补得出来、敲下去却是未知子指令」
     * （{@link MenuSubcommands#names()} 的同一条理由，见 {@code docs/11} §W W17 / W18）。
     */
    private static final List<String> DEBUG_ARGS = debugArgs();
    private static final List<String> ROOM_ARGS = List.of("reset");
    private static final List<String> PROFILE_ARGS = List.of("show", "grant", "setowned");

    private final InfernalClashPlugin plugin;

    public InfernalClashCommand(InfernalClashPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 子指令表（纯：不碰 Bukkit / MGC ⇒ 离线可断言） ----------------

    private static List<String> rootArgs() {
        List<String> args = new ArrayList<>(MenuSubcommands.names());
        args.add("info");
        args.add("debug");
        // ★ A2（R66 / Q54）：竞技场编辑是**顶层**分支（不在 debug 下），
        //   与 docs/10 §3 那一行「`/incl arena`（计划中的）—— Q54：顶层分支」逐字一致。
        args.add("arena");
        // ★ 2026-10-15 用户裁定（docs/24 §5.5）：`/incl bot on|off` —— **用户点名的指令名**，
        //   同样是顶层分支（与 arena / debug 同级），且**不要求 admin**（房间里任何玩家可敲）。
        args.add("bot");
        return List.copyOf(args);
    }

    /** ★ 底座既有的 debug 子指令 + R69 那五条（{@link DebugSubcommands}，声明顺序即 {@code docs/10} §3 的表序）。 */
    private static List<String> debugArgs() {
        List<String> args = new ArrayList<>(List.of("mgc", "room", "reload", "profile"));
        args.addAll(DebugSubcommands.names());
        return List.copyOf(args);
    }

    /**
     * ★ 供离线测试断言「{@code debug} 子指令表里确实有这几条」。
     *
     * <p>理由同 {@link #rootSubcommands()}：{@code docs/10} §8 那五行曾经写着「【待实现】」
     * 而代码里 {@code grep} 零命中，<b>没有任何测试会红</b>（{@code docs/11} §W W19）。
     */
    static List<String> debugSubcommands() {
        return DEBUG_ARGS;
    }

    /**
     * ★ 供离线测试断言「根注册表里确实有这几条」。
     *
     * <p>它存在的理由就是 {@code docs/12} 那次实测教训：两条指令从注册表里消失时，
     * <b>没有任何测试会红</b>，于是文档继续让用户敲它们。现在删掉 {@code shop} / {@code cards}
     * 会先让 {@code MenuSubcommandsTest} 变红。
     */
    static List<String> rootSubcommands() {
        return ROOT_ARGS;
    }

    /**
     * ★ <b>动作 → 界面 id</b> 的唯一映射（界面 id 本身只有 {@code ui/MenuRegistry} 一处定义）。
     *
     * <p>离线测试拿它钉住「{@code /incl cards} 到底开哪个界面」这个决定 ——
     * 判断依据写在 {@link MenuSubcommands.Screen#CARDS} 的 javadoc 里。
     */
    static String menuIdFor(MenuSubcommands.Screen screen) {
        return switch (screen) {
            case SHOP -> MenuRegistry.MENU_SHOP;
            case CARDS -> MenuRegistry.MENU_DECK;
            case CODEX -> MenuRegistry.MENU_CODEX;
        };
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        // ★ 「打开界面」三条先查纯解析表，再走同一条出口（表里没有的名字一律落到 default 的用法提示）
        Optional<MenuSubcommands.Screen> screen = MenuSubcommands.screenFor(sub);
        if (screen.isPresent()) {
            openScreen(sender, screen.get());
            return true;
        }
        switch (sub) {
            case "info" -> {
                // ★ 必须真的查这个权限：否则 plugin.yml 里声明的 infernalclash.play
                //   就是一条**永远不会生效**的配置（服主想收回也收不回）。
                if (!sender.hasPermission(PERM_PLAY)) {
                    tell(sender, KEY_NO_PERMISSION, "你没有权限查看该信息。", NamedTextColor.RED);
                    return true;
                }
                printInfo(sender);
            }
            case "debug" -> runDebug(sender, label, args);
            case "arena" -> runArena(sender, label, args);
            // ★ 2026-10-15 用户裁定（docs/24 §5.5）：`/incl bot on|off` —— 顶层、玩家可用
            case "bot" -> runBot(sender, label, args);
            default -> printUsage(sender, label);
        }
        return true;
    }

    // ---------------- 打开界面（shop / cards / codex） ----------------

    /**
     * ★ 「打开界面」三条子指令的<b>共同出口</b>：给<b>发令玩家</b>打开界面。
     *
     * <h2>为什么先判 {@code instanceof Player}</h2>
     * 控制台不是 {@code Player}，而 {@code MenuFacade.open} 要一个玩家才能把箱子推到他面前
     * （{@code docs/12} §4.14「为什么控制台做不到」）⇒ 这里必须给一句可读中文提示并<b>正常返回</b>，
     * 不能把 {@code ClassCastException} 抛给指令分发。
     *
     * <h2>★ 权限检查为什么<b>不在</b>这一层（刻意的）</h2>
     * 「谁能打开界面」的判据只有一处：{@code ui/MenuRegistry.canOpen}（权限 → 插件就绪 → 档案拿得到），
     * 它已经会用 {@code messages.command.no-permission} 那句提示拒绝玩家。
     * 在这一层再判一次同一个权限节点，只会多出<b>第二处判据 + 第二句措辞</b>
     * （本项目「第二处白名单」的实测教训）⇒ 这里只做对账日志，<b>权限语义一个字都不动</b>。
     *
     * <h2>失败方向</h2>
     * {@code MenuRegistry} 自己已把 {@code Throwable} 兜在里面（未注册 / 构造器抛异常 /
     * 权限不足都返回 {@code false} 并给玩家提示）；这里再兜一层，
     * 保证「一次打开失败」绝不会冒到指令分发上。
     */
    /**
     * {@link #openScreen} 的日志出口。
     *
     * <p>为什么不用裸的 {@code plugin.getLogger()}：非玩家那条路（控制台发令）在指令分发的
     * <b>最前面</b>，它不该因为「插件实例不在」而 NPE —— 而且这条路要能在普通 JUnit 里离线跑
     * （{@code MenuSubcommandsTest} 用 {@code new InfernalClashCommand(null)} + 假
     * {@code CommandSender} 验「控制台 ⇒ 只回提示、不抛异常」）。
     * 退回的 logger 名字与 {@code ui/MenuRegistry} 里的静态路径一致。
     */
    private Logger logger() {
        return plugin != null ? plugin.getLogger() : Logger.getLogger("InfernalClash");
    }

    private void openScreen(CommandSender sender, MenuSubcommands.Screen screen) {
        if (!(sender instanceof Player player)) {
            tell(sender, KEY_PLAYERS_ONLY, MSG_PLAYERS_ONLY, NamedTextColor.RED);
            logger().info("[IC] /incl " + screen.commandName()
                    + "：发送者不是玩家，只回了一句提示，没有打开界面");
            return;
        }
        String menuId = menuIdFor(screen);
        boolean opened;
        try {
            opened = switch (screen) {
                case SHOP -> MenuRegistry.openShop(player);
                case CARDS -> MenuRegistry.openDeck(player);
                case CODEX -> MenuRegistry.openCodex(player);
            };
        } catch (Throwable t) {
            logger().log(Level.WARNING, "[IC] /incl " + screen.commandName()
                    + "：打开界面 " + menuId + " 时抛了异常（已兜住）：" + t, t);
            sender.sendMessage(Component.text(MSG_OPEN_FAILED, NamedTextColor.RED));
            return;
        }
        if (!opened) {
            // 玩家那一侧的提示由 MenuRegistry 给（权限不足 / 未就绪 / 档案拿不到 / 该 id 未注册）
            logger().warning("[IC] /incl " + screen.commandName()
                    + "：界面 " + menuId + " 没打开（原因见 MenuRegistry 上一行告警）");
            return;
        }
        // ★ 带 id 的成功日志，便于实机对账（docs/12 §4.14）
        logger().info("[IC] /incl " + screen.commandName() + "：为 " + player.getName()
                + " 打开界面 " + menuId);
    }

    // ---------------- arena（R66 / Q54 的顶层分支：A2 指令 + U1 界面） ----------------

    /**
     * ★ <b>{@code /incl arena …}</b>：竞技场点位的采集 / 查看 / 删除（R66），
     * 以及不带子指令时打开 U1 编辑界面。
     *
     * <h2>为什么是顶层（Q54）</h2>
     * {@code docs/10} §3 那一行逐字写着「{@code /incl arena}（**计划中的**）· 权限
     * {@code infernalclash.admin} · ✅ <b>Q54：顶层分支</b>已裁定（打开竞技场设置 UI，R66）」
     * ⇒ 它<b>不在</b> {@code debug} 下。
     *
     * <h2>★ 入口选择：裸 {@code /incl arena} 打开界面（并说明理由）</h2>
     * 本任务的裁定只有「{@code set <名字>} / {@code list} / {@code remove <名字>}」三条子指令，
     * 而 R66 的原文是「<b>设置竞技场（打开设置 ui）</b>」——
     * 也就是说「打开界面」本身就是这条指令要满足的需求。
     * ⇒ 裸 {@code /incl arena}（管理员）直接打开界面；
     * <b>不另加</b> {@code edit} 之类的子指令名 —— 用户裁定的子指令集就是那三条，
     * 多一个名字就是替用户改指令集（铁律 11）。
     * 另一条路（只保留三条子指令、界面由 {@code arena edit} 打开）被否决的理由写在交付报告里。
     *
     * <h2>★ 权限：{@code infernalclash.admin}（控制台放行）</h2>
     * 同 {@code debug}（{@code docs/10} §5 的权限表）。注意「放行控制台」只到<b>读</b>为止：
     * {@code set} 用的是<b>发令者当前所在位置</b>，控制台没有位置 ⇒ 它会得到一句人话解释；
     * 裸 {@code arena} 要打开界面而控制台没有背包 ⇒ 同样只回提示（与 {@code shop}/{@code cards} 同口径）。
     */
    private void runArena(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            tell(sender, KEY_NO_PERMISSION,
                    "你没有权限使用竞技场编辑指令（需要 " + PERM_ADMIN + "）。", NamedTextColor.RED);
            return;
        }
        String raw = args.length < 2 ? "" : args[1];
        if (raw.isBlank()) {
            openArenaEditor(sender);
            return;
        }
        Optional<ArenaSubcommands.Action> action = ArenaSubcommands.actionFor(raw);
        if (action.isEmpty()) {
            // ★ 未知子指令的提示**不需要插件就绪**（它就是一句用法）—— 判据放在插件检查之前，
            //   于是这条路径在离线测试里也走得到（不必伪造一个 Plugin）
            sender.sendMessage(Component.text(ArenaSubcommands.unknownActionHint(raw),
                    NamedTextColor.RED));
            sender.sendMessage(Component.text("用法：" + cmd("/" + label + " arena "
                    + ArenaSubcommands.usage()), NamedTextColor.YELLOW));
            return;
        }
        if (plugin == null) {
            // 与开放口那条路同口径：拿不到插件实例就明说，绝不 NPE 冒到指令分发
            sender.sendMessage(Component.text("插件还没就绪（正在启动中），竞技场指令暂时不可用。",
                    NamedTextColor.RED));
            logger().warning("[IC] /incl arena：插件实例为 null，只回了一句提示，什么都没做");
            return;
        }
        switch (action.get()) {
            case CREATE -> arenaCreate(sender, label, args);
            case LIST -> printArenaList(sender, label);
            case SET -> arenaSet(sender, label, args);
            case REMOVE -> arenaRemove(sender, label, args);
        }
    }

    /**
     * 裸 {@code /incl arena} ⇒ 给管理员打开 U1 界面。
     *
     * <p>★ 顺序刻意是「先判是不是玩家，再看插件就绪」：控制台拿到的应当是
     * 「该指令只能由玩家执行（控制台没有背包）」这句**准确**的话，
     * 而不是一句含糊的「插件还没就绪」。
     */
    private void openArenaEditor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            tell(sender, KEY_PLAYERS_ONLY, MSG_PLAYERS_ONLY, NamedTextColor.RED);
            logger().info("[IC] /incl arena：发送者不是玩家，只回了一句提示，没有打开界面");
            return;
        }
        if (plugin == null) {
            sender.sendMessage(Component.text("插件还没就绪（正在启动中），界面暂时打不开。",
                    NamedTextColor.RED));
            logger().warning("[IC] /incl arena：插件实例为 null，没有打开界面");
            return;
        }
        if (!ArenaEditMenu.open(plugin, player)) {
            sender.sendMessage(Component.text(MSG_OPEN_FAILED, NamedTextColor.RED));
        }
    }

    /**
     * {@code /incl arena list}：**先列竞技场**（名字 / 几个点位 / 是否默认 / 能不能开局），
     * 再逐条印<b>默认竞技场</b>的点位。
     *
     * <p>★ 2026-10-18 重写：文件从「一个平铺点位表」变成「多竞技场」，
     * 所以第一段必然是「文件里有哪几个竞技场」—— 否则管理员根本不知道
     * {@code remove <名字>} 该敲什么。
     */
    private void printArenaList(CommandSender sender, String label) {
        ArenaCatalog catalog = plugin.arenaCatalog();
        String current = plugin.defaultArenaName();
        sender.sendMessage(Component.text("竞技场（" + ArenaConfig.FILE + "）：共 " + catalog.size()
                + " 个" + (current == null ? "" : "｜默认 = " + current), NamedTextColor.GOLD));
        if (catalog.isEmpty()) {
            sender.sendMessage(Component.text(" 还没有任何竞技场：站在西南角敲 "
                    + cmd("/" + label + " arena set <名字>") + " 一次就能绑好整座",
                    NamedTextColor.YELLOW));
            return;
        }
        for (String name : catalog.names()) {
            ArenaConfig config = catalog.arena(name);
            boolean ready = config != null && config.isReadyForMatch();
            sender.sendMessage(Component.text(" " + (name.equals(current) ? "" : " ") + name
                    + "｜" + (config == null ? 0 : config.size()) + " / "
                    + ArenaConfig.allKeys().size() + " 点位｜"
                    + (ready ? "可开局" : "缺 " + (config == null
                    ? ArenaConfig.requiredCount() : config.missingKeysForMatch().size()) + " 个必需点"),
                    ready ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        ArenaConfig config = plugin.arenaConfig();
        sender.sendMessage(Component.text("默认竞技场 " + (current == null ? "（无）" : current)
                + " 的逐点坐标：", NamedTextColor.GOLD));
        for (String key : ArenaConfig.allKeys()) {
            Point point = config.point(key);
            if (point == null) {
                sender.sendMessage(Component.text(" " + padKey(key) + " 未采集",
                        NamedTextColor.DARK_GRAY));
            } else {
                sender.sendMessage(Component.text(" " + padKey(key) + " " + point.world() + " "
                        + String.format("%.1f, %.1f, %.1f", point.x(), point.y(), point.z())
                        + "（yaw " + point.yaw() + " / pitch " + point.pitch() + "）",
                        NamedTextColor.WHITE));
            }
        }
        boolean ready = config.isReadyForMatch();
        sender.sendMessage(Component.text(" 对局必需 " + ArenaConfig.requiredCount()
                + " 个（前 8 个目标物点位 + 4 个半场角点）：" + (ready
                ? "已齐全，开局会生成 2 信标 + 4 防御塔"
                : "缺 " + config.missingKeysForMatch() + "，开局不会生成目标物"),
                ready ? NamedTextColor.GREEN : NamedTextColor.RED));
        for (Side side : Side.values()) {
            HalfField half = config.half(side);
            sender.sendMessage(Component.text(" 半场（" + side.name().toLowerCase(Locale.ROOT)
                    + "）：" + (half == null
                    ? "不可用（缺 half." + side.name().toLowerCase(Locale.ROOT)
                            + ".1/2，或两个角点不在同一个世界），召唤卡会被拒"
                    : "可用 " + half.describe()),
                    half == null ? NamedTextColor.RED : NamedTextColor.GREEN));
        }
        sender.sendMessage(Component.text(" 绑定：" + cmd("/" + label + " arena set <竞技场名>")
                + "（站西南角敲一次 = 绑全 18 个点）｜界面：" + cmd("/" + label + " arena")
                + "｜删除：" + cmd("/" + label + " arena remove <竞技场名>"), NamedTextColor.DARK_GRAY));
    }

    /**
     * ★★★ <b>{@code /incl arena set <竞技场名>}</b> —— 2026-10-18 重写：
     * <b>站在竞技场西南角敲一次，整座竞技场的 18 个点位一次绑好</b>。
     *
     * <h2>用户原话（逐字）</h2>
     * 「原来的 arena set 太繁琐了…我需要你帮我重写一个 arena 设置指令，
     * 只需要绑定一个点就能成功绑定其他所有点。」
     *
     * <h2>基准点怎么取（★ 这里差半格，整座竞技场就偏半格）</h2>
     * 取<b>你站的那一格的角落</b>：{@code x = floor(playerX)}、{@code z = floor(playerZ)}、
     * {@code y = 你的脚高}。★ 用 {@code floor} 而不是你的浮点坐标：
     * 你站在格子的哪一半都会得到同一个角 ⇒ 重绑一次结果是稳定的（不会每次偏 0.3 格）。
     *
     * <h2>★ 18 个点位从哪来</h2>
     * 全在 {@link ArenaLayout#derive}（纯函数，逐条对着用户给的 11 条坐标，有单测）。
     * 本方法只做三件事：取位置 → 推导 → 写盘。{@code spectate} <b>不推导</b>
     * （用户没给观战点 ⇒ 不许我们凭空造一个）。
     */
    private void arenaSet(CommandSender sender, String label, String[] args) {
        String name = arenaNameArgument(sender, label, args, ArenaSubcommands.Action.SET);
        if (name.isEmpty()) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("这条只能在游戏里执行：set 用的是你当前站的位置"
                    + "（竞技场西南角），控制台没有位置可采集。（控制台可用的只有 "
                    + cmd("/" + label + " arena list") + " 与 remove。）", NamedTextColor.RED));
            logger().info("[IC] /incl arena set " + name + "：发送者不是玩家，拒绝（没有位置可采）");
            return;
        }
        Location at = player.getLocation();
        if (at.getWorld() == null) {
            sender.sendMessage(Component.text("你的位置没有世界信息，没有采集（详见控制台日志）。",
                    NamedTextColor.RED));
            logger().warning("[IC] /incl arena set " + name + "：发令者位置没有世界，拒绝");
            return;
        }
        // ★ 基准格 = 你站的那一格的**最小角**（floor）
        double baseX = Math.floor(at.getX());
        double baseZ = Math.floor(at.getZ());
        double baseY = at.getY();
        Map<String, Point> derived;
        try {
            derived = ArenaLayout.derive(at.getWorld().getName(), baseX, baseY, baseZ);
        } catch (RuntimeException e) {
            sender.sendMessage(Component.text("推导点位失败：" + e.getMessage(), NamedTextColor.RED));
            logger().warning("[IC] /incl arena set " + name + "：推导失败，" + e);
            return;
        }
        if (!ArenaLayout.withinReasonableExtent(derived)) {
            sender.sendMessage(Component.text("没有绑定：从这个位置推出来的竞技场跨了 "
                    + ArenaLayout.MAX_EXTENT + " 格以上 —— 很可能你站错了地方"
                    + "（应当站在竞技场西南角）。什么都没有写盘。", NamedTextColor.RED));
            logger().warning("[IC] /incl arena set " + name + "：位置 (" + baseX + ", " + baseY + ", "
                    + baseZ + ") 推出来的范围过大，拒绝写盘");
            return;
        }
        ArenaConfig working = new ArenaConfig(java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(derived)));
        ArenaConfig before = plugin.arenaCatalog().arena(name);
        logger().info("[IC] /incl arena set " + name + "：操作者=" + player.getName()
                + " 基准格=" + baseX + "," + baseY + "," + baseZ + "（" + at.getWorld().getName()
                + "），推导 " + working.size() + " 个点位（改前 "
                + (before == null ? "没有这个竞技场" : before.size() + " 个点位") + "）");
        applyArenaEdit(sender, plugin.arenaCatalog().with(name, working), "set " + name,
                "竞技场 " + name + "：一次绑定 " + working.size() + " 个点位"
                        + "（基准 = 西南角格 " + (long) baseX + ", " + baseZ + "）");
        warnIfNotTheDefault(sender, label, name);
    }

    /**
     * ★ <b>「你绑的这座不是对局用的那一个」提醒</b>（2026-10-18）。
     *
     * <h2>为什么需要它（一个很容易踩的坑）</h2>
     * 对局用的是 {@code config.yml} 的 {@code arena.default}（不写 = 文件里的**第一个**）。
     * 现场文件是**旧格式**（读成名叫 {@code default} 的竞技场）⇒ 它排在第一个
     * ⇒ 用户绑了 {@code arena1}，但对局仍然用那份旧的 {@code default}，
     * <b>而所有提示都说"绑定成功"</b>。那正是本项目最反感的一类状态（做了却没有效果）。
     * ⇒ 绑完之后**主动说一句**该怎么让它生效。
     */
    private void warnIfNotTheDefault(CommandSender sender, String label, String name) {
        String current = plugin.defaultArenaName();
        if (current != null && current.equals(name)) {
            return;
        }
        sender.sendMessage(Component.text("注意：对局目前用的是默认竞技场 "
                + (current == null ? "（还没有）" : current)
                + "，不是你刚绑的 " + name + "。要让它生效："
                + cmd("config.yml 的 arena.default: " + name) + "（改完重启）。",
                NamedTextColor.YELLOW));
    }

    /**
     * ★★ <b>{@code /incl arena create <竞技场名>}</b> —— 2026-10-18 新增
     * （用户原话：「**怎么没有 arena create？现在竟然不能新增竞技场**」）。
     *
     * <h2>为什么需要一条显式的 create</h2>
     * 之前"新建"只能靠 {@code set <名字>} 顺带完成，而 Tab 补全的第三段只列**已有的**名字
     * ⇒ 管理员看到的全是旧名字，很自然会以为"不能新增"。
     * ⇒ 现在与 IG（{@code /ig arena create <名称>}）同名同义：
     * 建一个**空的**竞技场条目（{@code points: {}}），随后用 {@code set} 一次绑全、或用 U1 界面逐点填。
     * ★ 空竞技场**不参与随机开局**（{@code isReadyForMatch()} 为假）⇒ 建完不会立刻被抽中。
     * ★ 控制台也能敲（不需要位置）。
     */
    private void arenaCreate(CommandSender sender, String label, String[] args) {
        String name = arenaNameArgument(sender, label, args, ArenaSubcommands.Action.CREATE);
        if (name.isEmpty()) {
            return;
        }
        ArenaCatalog catalog = plugin.arenaCatalog();
        if (catalog.has(name)) {
            sender.sendMessage(Component.text("竞技场 " + name + " 已经存在（"
                    + catalog.arena(name).size() + " 个点位），没有改动，也没有写盘。"
                    + "要重绑请用 " + cmd("/" + label + " arena set " + name) + "。",
                    NamedTextColor.YELLOW));
            return;
        }
        logger().info("[IC] /incl arena create " + name + "：操作者=" + sender.getName()
                + "（改前 " + catalog.size() + " 个竞技场）");
        applyArenaEdit(sender, catalog.with(name, ArenaConfig.empty()), "create " + name,
                "已新建空竞技场 " + name + "（0 个点位）—— 下一步：站到西南角敲 "
                        + "/" + label + " arena set " + name + " 一次绑全 18 个点");
        // ★ 新建的空场子**不是**默认竞技场 ⇒ 提醒它现在还不会被用到
        warnIfNotTheDefault(sender, label, name);
    }

    /** {@code /incl arena remove <竞技场名>}：**整座**竞技场一起删。 */
    private void arenaRemove(CommandSender sender, String label, String[] args) {
        String name = arenaNameArgument(sender, label, args, ArenaSubcommands.Action.REMOVE);
        if (name.isEmpty()) {
            return;
        }
        ArenaCatalog catalog = plugin.arenaCatalog();
        if (!catalog.has(name)) {
            // ★ 没有改动就不写盘：免得平白覆盖一次 .bak
            sender.sendMessage(Component.text("竞技场 " + name + " 本来就不存在，没有改动，也没有写盘。"
                    + "现有：" + catalog.names(), NamedTextColor.YELLOW));
            return;
        }
        ArenaCatalog after = catalog.without(name);
        logger().info("[IC] /incl arena remove " + name + "：操作者=" + sender.getName()
                + "（改前 " + catalog.size() + " 个竞技场，改后 " + after.size() + " 个）");
        applyArenaEdit(sender, after, "remove " + name,
                "已删除竞技场 " + name + "（连同它的所有点位）");
    }

    /**
     * 写盘 + 热载 + 报告（{@code set} 与 {@code remove} 的<b>共同出口</b>）。
     *
     * <p>★ 失败方向：写盘失败 ⇒ 只提示 + 告警，<b>绝不抛</b>，且<b>不丢掉内存里的改动</b>。
     * ★ 2026-10-18：落盘改走 {@link InfernalClashPlugin#saveArenaCatalog}（多竞技场；
     * 旧实现写的是「单竞技场平铺格式」—— 那样会把文件里**别的竞技场一起抹掉**）。
     */
    private void applyArenaEdit(CommandSender sender, ArenaCatalog working, String what,
                                String headline) {
        ArenaCatalog.SaveOutcome outcome = plugin.saveArenaCatalog(working, what);
        if (!outcome.changed()) {
            sender.sendMessage(Component.text("未写入磁盘：" + outcome.summary(),
                    NamedTextColor.RED));
            sender.sendMessage(Component.text(" 内存里的改动仍然有效（本次会话可见），"
                    + "但重启会丢；原因见控制台告警。", NamedTextColor.YELLOW));
            return;
        }
        String describe = plugin.reloadArenaConfig();
        sender.sendMessage(Component.text(headline, NamedTextColor.GREEN));
        sender.sendMessage(Component.text(" 已写入 " + ArenaConfig.FILE + "：" + outcome.summary(),
                NamedTextColor.GREEN));
        sender.sendMessage(Component.text(" 备份：" + (outcome.backupName() == null
                ? "（原文件不存在，未生成备份）"
                : outcome.backupName() + "（内容 = 改动前的原文）"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" 现在：" + describe, NamedTextColor.GRAY));
    }

    /**
     * 取并校验**竞技场名**参数（{@code args[2]}）—— 2026-10-18 由「点位名」改成「竞技场名」。
     *
     * @return 合法的竞技场名；<b>空串表示已经回了一句提示</b>（调用方直接 return）
     */
    private String arenaNameArgument(CommandSender sender, String label, String[] args,
                                     ArenaSubcommands.Action action) {
        String raw = args.length < 3 ? null : args[2];
        if (raw == null || raw.isBlank()) {
            sender.sendMessage(Component.text(ArenaSubcommands.MSG_MISSING_NAME + "用法："
                    + cmd("/" + label + " arena " + action.commandName() + " <竞技场名>"),
                    NamedTextColor.YELLOW));
            return "";
        }
        String name = ArenaSubcommands.arenaNameOrEmpty(raw);
        if (name.isEmpty()) {
            sender.sendMessage(Component.text(ArenaSubcommands.unknownNameHint(raw),
                    NamedTextColor.RED));
        }
        return name;
    }

    /**
     * 以 {@code base} 为基础按 {@link ArenaConfig#allKeys()} 的顺序重建一份点位表：
     * {@code replacement != null} ⇒ 覆盖该键，{@code null} ⇒ 删除该键。
     *
     * <p>★ 保序重建（而不是直接改 {@code base.points()}）：顺序 = 约定顺序，
     * 于是「界面里看到的顺序」＝「{@code arenas.yml} 里的顺序」＝「{@code allKeys()} 的顺序」。
     *
     * <p>★ 包级可见（不是 {@code private}）：它是 {@code set} / {@code remove} 的<b>全部</b>
     * 点位变换逻辑，而指令分发本身要一个真插件实例（离线造不出来）⇒
     * 把它单独留下来，`ArenaEditFilesTest` 才能在普通 JUnit 里断言
     * 「只动了该动的那个键、其余原样、且真的能写盘再读回来」。
     */
    static ArenaConfig edited(ArenaConfig base, String key, Point replacement) {
        Map<String, Point> points = new LinkedHashMap<>();
        for (String candidate : ArenaConfig.allKeys()) {
            Point point = candidate.equals(key) ? replacement : base.point(candidate);
            if (point != null) {
                points.put(candidate, point);
            }
        }
        // 防御性：白名单之外的键（正常路径上不会有）原样保留，不静默丢数据
        for (Map.Entry<String, Point> entry : base.points().entrySet()) {
            if (!ArenaConfig.allKeys().contains(entry.getKey()) && !entry.getKey().equals(key)) {
                points.put(entry.getKey(), entry.getValue());
            }
        }
        return new ArenaConfig(Collections.unmodifiableMap(points));
    }

    /** 点位键的一行描述（列表用；宽度按 14 字符对齐，中文世界里只为读起来整齐）。 */
    private static String padKey(String key) {
        return String.format("%-14s", key);
    }

    private static String describe(Point point) {
        return point.world() + " " + String.format("%.1f, %.1f, %.1f", point.x(), point.y(), point.z());
    }

    // ---------------- ★★ bot（2026-10-15 用户裁定：/incl bot on|off） ----------------

    /**
     * ★★ <b>{@code /incl bot on|off}</b> —— 显式开关房间人机的唯一入口。
     *
     * <h2>★★ 用户原话（逐字，权威；留档 {@code docs/24} §5.5）</h2>
     * <blockquote>
     * 「<b>B，需要房间里任何一人敲/incl bot on；关掉人机需要敲/incl bot off。
     * 存在人机时进来第二个人也能正常开战</b>」
     * </blockquote>
     * 它<b>取代</b> {@code docs/02} §9 R62 的「单人开局<b>自动</b>开人机」。
     *
     * <h2>★ 五步，顺序是刻意的（失败路径"什么都不改"是结构上的保证）</h2>
     * <ol>
     *   <li><b>纯解析</b>（{@link BotSubcommands#parse}）：缺参数 / 未知值 ⇒ 拒绝 ＋ 用法，
     *       <b>此时还没有碰过任何状态</b>；</li>
     *   <li><b>权限</b>：{@code infernalclash.play}（{@code plugin.yml} 里 {@code default: true}）
     *       —— ★ <b>刻意不是 {@code infernalclash.admin}</b>：用户原话是「<b>房间里任何一人</b>敲」；</li>
     *   <li><b>控制台不行</b>：复用既有键 {@code command.players-only}
     *       —— 作用域是"发令者所在的那一间房"，控制台没有房间；</li>
     *   <li><b>作用域</b>：{@link InfernalClashGame#ofPlayer} 单点查发令者那一场（不遍历世界 /
     *       不遍历在线玩家 —— 铁律 10），再过一个<b>纯映射</b>
     *       {@link BotSubcommands#scopeOf(boolean, boolean)} ⇒
     *       ★ 不在任何房间 / 对局已开始都在<b>这里</b>被拒（各一句人话）；</li>
     *   <li><b>只有走到这里才改状态</b>：{@link InfernalClashGame#requestBot(boolean)}
     *       —— on 排窗口任务、off 取消它；★ 值没变则什么都不做（不谎报改动）。</li>
     * </ol>
     *
     * <h2>★ {@code plugin == null} 时不许 NPE</h2>
     * 本项目多条离线测试用 {@code new InfernalClashCommand(null)} 构造本类。
     * 这条路径在插件为 {@code null} 时的行为是：{@link InfernalClashGame#ofPlayer} 拿不到
     * MGC 的玩家管理器 ⇒ 返回 {@code null} ⇒ 作用域 = {@code NOT_IN_GAME}
     * ⇒ <b>一句「你不在本游戏的等待房间里」＋ 什么都不改</b>，<b>不 NPE</b>、也不抛。
     * 文案出口一律走 {@link #tell}（它自己守 {@code plugin == null} → 回退到代码常量）。
     */
    private void runBot(CommandSender sender, String label, String[] args) {
        // ① 纯解析：缺参数 / 未知值 ⇒ 明确拒绝 + 用法（一个字节的状态都还没碰）
        BotSubcommands.ParseResult parsed = BotSubcommands.parse(args.length < 2 ? null : args[1]);
        if (parsed.isFailure()) {
            sender.sendMessage(Component.text(parsed.failure(), NamedTextColor.RED));
            sender.sendMessage(Component.text("用法："
                    + cmd("/" + label + " bot " + BotSubcommands.usage()), NamedTextColor.YELLOW));
            logger().info("[IC] /incl bot：参数被拒（" + parsed.failure() + "），什么都没有改");
            return;
        }
        BotSubcommands.Action action = parsed.action();

        // ② 权限：玩家指令（★ 不是 admin —— 用户原话「房间里任何一人敲」）
        if (!sender.hasPermission(PERM_PLAY)) {
            tell(sender, KEY_NO_PERMISSION,
                    "你没有权限使用这条指令（需要 " + PERM_PLAY + "）。", NamedTextColor.RED);
            return;
        }

        // ③ 控制台没有房间 ⇒ 复用既有键 command.players-only
        if (!(sender instanceof Player player)) {
            tell(sender, KEY_PLAYERS_ONLY, MSG_PLAYERS_ONLY, NamedTextColor.RED);
            logger().info("[IC] /incl bot " + action.commandName()
                    + "：发送者不是玩家，只回了一句提示，什么都没有改");
            return;
        }

        // ④ 作用域 = 发令者自己所在的那一场（单点查；不在 / 已开局 ⇒ 拒绝）
        InfernalClashGame game = InfernalClashGame.ofPlayer(player);
        BotSubcommands.Scope scope = botScopeOf(game);
        BotSubcommands.Decision decision = BotSubcommands.decide(action, scope);
        if (!decision.accepted()) {
            sender.sendMessage(Component.text(decision.rejection(), NamedTextColor.RED));
            logger().info("[IC] /incl bot " + action.commandName() + "：发令者=" + player.getName()
                    + " 作用域=" + scope.label() + "，拒绝，什么都没有改");
            return;
        }

        // ⑤ 到这里才真的改状态
        boolean on = action == BotSubcommands.Action.ON;
        boolean changed = game.requestBot(on);
        int humans = game.getPlayers().size();
        // ★ 防御：botEnabledFor 对 <1 是 fail-fast（那是"读判据"的口径），这里用短路守一下
        boolean botWillPlay = on && humans >= 1 && TrainingMatchRules.botEnabledFor(humans, true);

        // ★★ 2026-10-19（用户：「开启 bot 后的那一大串提示文本，我想要修改」）：
        //   整段提示改成逐条读 display.yml 的 command.bot-* 键，失败回退到下面这些常量
        //   （已与接线前的措辞**逐字一致**）。占位符一次给全，改文案时不用改代码。
        // ★★ 2026-10-19 第二次裁定（用户逐字：「bot那一大串提示**我希望在开启人机时只显示一行
        //   『已添加电脑玩家』**，然后再不显示别的东西」）⇒ **开启方向整段压成一行**：
        //   原来那 4 行（20 tick 窗口的解释 ×3 ＋「本房间已有 N 名真人」）**全部下线**，
        //   它们的键已移进 `DisplayConfig.RETIRED_MESSAGE_KEYS`（现场留着不报错、但没有代码读）。
        //   ★ 信息没有丢：同一批事实仍写在下面那条 `logger().info` 里
        //     （`人机会上场=是（20 tick 窗口）/ 否`），控制台照样能回答"为什么没立刻开局"。
        //   ★ **关闭方向（/incl bot off）一字未改** —— 用户只裁定了"开启"。
        Map<String, String> ph = Map.of(
                "player", player.getName(),
                "room", game.matchId(),
                "action", action.commandName(),
                "changed", changed ? "" : "　（本来就已是该值，没有变化）");
        if (on) {
            // 唯一一行。★ 不再先发 header（否则"只显示一行"立刻破功）
            tell(sender, "command.bot-on", "已添加电脑玩家", ph, NamedTextColor.GREEN);
        } else {
            tell(sender, "command.bot-header", "人机开关 · {player} · 本房间（{room}）　{action}{changed}",
                    ph, NamedTextColor.GOLD);
            tell(sender, "command.bot-off", " 已关闭：本房间不再允许人机"
                            + "（还没到期的开局窗口任务已取消，不会带着人机开局）。",
                    ph, NamedTextColor.GREEN);
            tell(sender, "command.bot-off-hint",
                    " （下一次要开人机需要重新敲 /incl bot on；"
                            + "对局开始 / 房间取消 / 收尾时这个标记都会自动清零。）",
                    ph, NamedTextColor.GRAY);
        }
        logger().info("[IC] /incl bot " + action.commandName() + "：操作者=" + player.getName()
                + " matchId=" + game.matchId() + "｜真人 " + humans + " 名"
                + "｜人机会上场=" + (botWillPlay ? "是（" + TrainingMatchRules.BOT_START_WINDOW_TICKS
                        + " tick 窗口）" : "否")
                + "｜" + (changed ? "已改" : "值相同，未变")
                + "（2026-10-15 用户裁定；docs/24 §5.5）");
    }

    /**
     * ★ <b>纯映射：发令者那一场对局 ⇒ 本指令的作用域</b>（{@code null} = 不在任何对局里）。
     *
     * <p>包级可见是刻意的：它让「对局已开始 ⇒ 拒绝」这条路径在<b>离线</b>也能被间接断言
     * （{@code botScopeOf(null) == NOT_IN_GAME}），而生产侧读的
     * {@code getState() == WAITING} 是 MGC 的公开判据（{@code AbstractGame.getState()}，
     * {@code javap} 实测）。
     *
     * <p>★ 为什么"是不是等待阶段"要用 {@code GameState.WAITING} 而不是自己在插件里记一个标志：
     * MGC 是状态的唯一所有者，自己记一份必然与它分叉（本项目"第二处事实来源"的教训）。
     */
    static BotSubcommands.Scope botScopeOf(InfernalClashGame game) {
        return BotSubcommands.scopeOf(game != null,
                game != null && game.getState() == GameState.WAITING);
    }

    // ---------------- info ----------------

    private void printInfo(CommandSender sender) {
        // ★ 2026-10-20：这两行原来写「项目底座 / 玩法尚未定稿」，与线上事实不符
        //   （玩家看到的游戏名在 config.yml 的 meta.name =「代理人战争」）。
        sender.sendMessage(Component.text("代理人战争（InfernalClash）· 实时卡牌策略对战小游戏",
                NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" 玩法：皇室战争式 1v1（卡牌召唤物 + 法术，3 分钟 + 1 分钟加时）",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" MGC 游戏 id：" + InfernalClashGame.ID,
                NamedTextColor.WHITE));
        if (plugin.gameConfig() != null) {
            sender.sendMessage(Component.text(" 配置：" + plugin.gameConfig().describe(),
                    NamedTextColor.WHITE));
        }
        sender.sendMessage(Component.text(" 查看 MGC 状态：" + cmd("/incl debug mgc"),
                NamedTextColor.DARK_GRAY));
    }

    // ---------------- debug ----------------

    private void runDebug(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            tell(sender, KEY_NO_PERMISSION, "你没有权限使用调试指令。", NamedTextColor.RED);
            return;
        }
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "mgc" -> printMgcStatus(sender);
            case "room" -> runRoomDebug(sender, label, args);
            case "reload" -> reload(sender);
            case "objectives" -> runObjectiveProbe(sender, label, args);
            case "card" -> runCardDebug(sender, label, args);
            case "combat" -> runCombatProbe(sender, label, args);
            case "profile" -> runProfileDebug(sender, label, args);
            // ★ R69（docs/10 §3）：那五条调试子指令 —— 认不出来的名字由 DebugSubcommands 明确拒绝
            case DebugSubcommands.GRANT_ALL, DebugSubcommands.RESET_COLLECTION,
                 DebugSubcommands.SET_GOLD, DebugSubcommands.SET_POWDER,
                 DebugSubcommands.INFINITE_XP -> runDebugEdit(sender, label, args);
            // ★★ 2026-10-17 第十二轮：遗留实体清理（默认只报告，--yes 才真删）
            case DebugSubcommands.PURGE_LEGACY -> runDebugEdit(sender, label, args);
            default -> tell(sender, KEY_DEBUG_USAGE,
                    "用法：" + cmd("/" + label + " debug <mgc|room reset|reload|objectives probe"
                            + "|card …|combat probe|profile …|" + DebugSubcommands.usage() + ">"),
                    Map.of("usage", "/" + label + " debug <mgc|room reset|reload|objectives probe"
                            + "|card …|combat probe|profile …|" + DebugSubcommands.usage() + ">"),
                    NamedTextColor.YELLOW);
        }
    }

    // ---------------- R69：debug grant-all / reset-collection / set-gold / set-powder / infinite-xp ----------------
    /**
     * ★ <b>{@code /incl debug} 里 R69 那几条的编排层</b>（{@code docs/10} §3）。
     *
     * <pre>
     *   /incl debug grant-all [玩家]            ← 【Q40】连金色版一起给
     *   /incl debug reset-collection [玩家]     ← ⚠️ 破坏性，docs/10 §4
     *   /incl debug set-gold &lt;玩家&gt; &lt;数量&gt;
     *   /incl debug set-powder &lt;玩家&gt; &lt;数量&gt;   ← 【Q41】新增
     *   /incl debug infinite-xp [玩家] on|off   ← ★ 「打牌不扣经验」（作用于发令者当前那一场）
     * </pre>
     *
     * <h2>★ 只做三件边界的事，业务全在纯函数里</h2>
     * <ol>
     *   <li><b>认名字 + 校验参数</b>：{@link DebugSubcommands#parse} —— 名字不认识 / 数量是负数 /
     *       非数字 / 缺参 / 参数给多 / {@code on|off} 非法一律在这里变成一句中文失败，
     *       <b>此时还没有碰过任何档案</b>（所以「失败 ⇒ 一个字节都不写」是结构上的保证）；</li>
     *   <li><b>解析目标玩家</b>：给了名字就 {@code Bukkit.getPlayerExact}（只按名字单点查，
     *       不是遍历在线列表 —— 铁律 10），没给就用<b>发令者自己</b>（控制台没给名字 ⇒ 明确提示）；</li>
     *   <li><b>读 → 换 → 写</b>：{@code ProfileStore.load} → {@link ProgressEdits} → {@code save}
     *       —— ★ 写档案<b>只有这一条路</b>（不拼 JSON、不绕过 {@code save}）。</li>
     * </ol>
     *
     * <h2>★★ {@code infinite-xp} 走的是另一条路（2026-10-15 第二轮裁定）</h2>
     * 用户裁定的语义是「<b>打牌不扣经验</b>」⇒ 它<b>不改档案</b>，而是改<b>发令者当前那一场对局</b>
     * 的一个开关（{@code match/MatchService.setFreeCasting}）：出牌不再扣经验、经验照常累积，
     * 且只影响那一场（【Q42】＋【Q44】⇒ 不许有「全局当前对局」这种静态状态）。
     * ⇒ 它在<b>读档案之前</b>就分流到 {@link #runInfiniteXp}，一个档案字节都不碰
     * （{@code DebugSubcommands.applyEdit} 对它也明确返回 {@code null}）。
     *
     * <p>★ 失败方向：档案读不到 / 写不进 ⇒ {@code ProfileStore} 自己<b>告警 + 不抛</b>
     * （本方法不额外兜异常，因为那层已经兜住且绝不打断任何东西）；
     * 目标玩家不存在 ⇒ 一句中文提示（{@link DebugSubcommands#MSG_PLAYER_NOT_FOUND}）。
     */
    private void runDebugEdit(CommandSender sender, String label, String[] args) {
        DebugSubcommands.ParseResult parsed = DebugSubcommands.parse(
                args.length < 2 ? null : args[1],
                args.length < 3 ? null : args[2],
                args.length < 4 ? null : args[3]);

        // ① 解析失败（未知名 / 非法数量 / 缺 on|off …）：明确失败，且**没有**碰过档案
        if (parsed.isFailure()) {
            sender.sendMessage(Component.text(parsed.failure().message(), NamedTextColor.RED));
            return;
        }
        DebugSubcommands.Run run = parsed.run();

        // ①.5 ★ infinite-xp：改对局开关，不写档案（所以在读 ProfileStore 之前分流）
        if (DebugSubcommands.INFINITE_XP.equals(run.action())) {
            runInfiniteXp(sender, label, run);
            return;
        }

        // ①.6 ★★ purge-legacy：**不写档案、也不碰对局**，它删的是遗留实体
        //      （2026-10-17 第十二轮，用户裁定 §A 第 1 条）—— 同样在读 ProfileStore 之前分流
        if (DebugSubcommands.PURGE_LEGACY.equals(run.action())) {
            runPurgeLegacy(sender, label, run);
            return;
        }

        // ② 目标玩家：给了名字 ⇒ 单点查（Bukkit.getPlayerExact，不遍历在线列表）；
        //    没给 ⇒ 发令者自己（只有玩家才有"自己"）
        java.util.UUID target;
        String who;
        if (run.target() != null) {
            Player named = Bukkit.getPlayerExact(run.target());
            if (named == null) {
                tell(sender, KEY_PLAYER_NOT_FOUND,
                        "找不到在线玩家：" + run.target() + "　" + DebugSubcommands.MSG_PLAYER_NOT_FOUND,
                        Map.of("player", String.valueOf(run.target())), NamedTextColor.RED);
                return;
            }
            target = named.getUniqueId();
            who = named.getName();
        } else if (sender instanceof Player self) {
            target = self.getUniqueId();
            who = self.getName();
        } else {
            sender.sendMessage(Component.text(DebugSubcommands.MSG_MISSING_TARGET
                    + "用法：" + cmd("/" + label + " debug " + DebugSubcommands.usage()),
                    NamedTextColor.YELLOW));
            return;
        }

        // ③ 读 → 换 → 写（★ 落盘只走 ProfileStore：这里传的就是 store::load / store::save）
        ProfileStore store = plugin.profileStore();
        if (store == null) {
            sender.sendMessage(Component.text("档案门面不可用（插件未就绪），"
                    + "本次没有改动任何数据。", NamedTextColor.RED));
            return;
        }
        List<String> pool;
        List<String> basicPool;
        try {
            pool = cardPoolIds();
            // ★ 2026-10-21 用户裁定：「用 debug 重置卡牌收藏时，不应该删掉我的基础卡」
            //   ⇒ 判据 = rarity == BASIC，只有 R44BasicGrants 一处（本类不写死卡名 / 不写「11」）
            basicPool = R44BasicGrants.basicCardIds(plugin.cardCatalog().cards().values());
        } catch (RuntimeException e) {
            sender.sendMessage(Component.text("这次改动被拒绝（数据没有变）：" + e.getMessage(),
                    NamedTextColor.RED));
            logger().warning("[IC] /incl debug " + run.action() + " 取卡池失败：" + e.getMessage());
            return;
        }
        PlayerProgress before = store.load(target);
        PlayerProgress after = DebugSubcommands.applyEdit(run, target, store::load, store::save, pool,
                basicPool,
                problem -> {
                    sender.sendMessage(Component.text(problem, NamedTextColor.RED));
                    logger().warning("[IC] /incl debug " + run.action() + "：" + problem);
                });
        if (after == null) {
            return;   // 已经回过一句话，且**没有**写任何档案
        }
        reportEdit(sender, run, who, target, before, after, store);
    }

    /**
     * ★★ <b>{@code /incl debug infinite-xp [玩家] on|off}</b> —— 「打牌不扣经验」（2026-10-15 第二轮裁定）。
     *
     * <h2>语义（用户原话「打牌不扣经验」，逐字留档 {@code docs/24} §1 第 5 条）</h2>
     * 它把<b>发令者当前那一场对局</b>的一个开关打开/关闭（{@code MatchService.setFreeCasting}）：
     * 开着时出牌<b>不再扣经验</b>（召唤与法术的费用校验与扣减整条失效，「经验不足」不再出现），
     * 而<b>经验本身照常累积</b>；★ <b>不是</b>「经验不清零」、也<b>不是</b>「经验固定在某个值」。
     *
     * <h2>★★ 作用域 = 那一场（按 {@code matchId}）</h2>
     * 【Q42】明写它作用于「命令使用者的当前对局」，【Q44】又要求多场并发 ⇒
     * <b>不许有「全局当前对局」这种静态状态</b>。落地形态就是：
     * ① 找<b>目标玩家所在的那一场</b>（{@link MatchService#ofPlayer(java.util.UUID) MatchService.ofPlayer}，
     * 它遍历的是<b>活跃对局索引</b>（最多几场），不是世界里的实体 —— 与铁律 10 完全不冲突）；
     * ② 只改那一个实例的字段；③ 日志把 {@code matchId} 打出来（可对账）。
     *
     * <h2>失败方向（一条都不静默）</h2>
     * <ul>
     *   <li>目标玩家不在任何对局里 ⇒ 一句中文 + <b>什么都不改</b>（绝不偷偷改「别的那一场」）；</li>
     *   <li>控制台没写玩家名 ⇒ 同 {@code runDebugEdit} 的口径：要显式指定（控制台没有「自己」）；</li>
     *   <li>值本来就等于目标值 ⇒ 如实说「本来就是开着的 / 关着的」（{@code setFreeCasting}
     *       返回 {@code false}），不谎报一次改动。</li>
     * </ul>
     *
     * <p>★ <b>它不碰档案</b>：所以本方法<b>不读也不写</b> {@code ProfileStore}，
     * 也不需要 {@code cardCatalog}（{@link DebugSubcommands#applyEdit} 对它明确返回 {@code null}）。
     */
    private void runInfiniteXp(CommandSender sender, String label, DebugSubcommands.Run run) {
        // ① 目标玩家：给了名字 ⇒ 单点查（Bukkit.getPlayerExact，不遍历在线列表 —— 铁律 10）
        java.util.UUID target;
        String who;
        if (run.target() != null) {
            Player named = Bukkit.getPlayerExact(run.target());
            if (named == null) {
                tell(sender, KEY_PLAYER_NOT_FOUND,
                        "找不到在线玩家：" + run.target() + "　" + DebugSubcommands.MSG_PLAYER_NOT_FOUND,
                        Map.of("player", String.valueOf(run.target())), NamedTextColor.RED);
                return;
            }
            target = named.getUniqueId();
            who = named.getName();
        } else if (sender instanceof Player self) {
            target = self.getUniqueId();
            who = self.getName();
        } else {
            sender.sendMessage(Component.text(DebugSubcommands.MSG_MISSING_TARGET
                    + "用法：" + cmd("/" + label + " debug " + DebugSubcommands.usage()),
                    NamedTextColor.YELLOW));
            return;
        }

        // ② 找**他所在的那一场**（作用域 = matchId；绝不改别的场）
        MatchService match = MatchService.ofPlayer(target);
        if (match == null) {
            sender.sendMessage(Component.text(who + " 现在不在任何对局中，infinite-xp 没有作用对象"
                    + "（它只作用于你当前那一场，不会改别的对局，也不会变成全局状态）。"
                    + "先 /mgc join infernalclash 开一局再试。", NamedTextColor.RED));
            logger().info("[IC] /incl debug infinite-xp：目标=" + who + "（" + target
                    + "）不在任何对局中，未做任何改动");
            return;
        }

        // ③ 只改那一场
        DebugSubcommands.Toggle toggle = run.toggle();
        boolean changed = match.setFreeCasting(toggle.enabled());
        sender.sendMessage(Component.text("无限经验（打牌不扣经验）· " + who + "（" + target + "）"
                + " · matchId=" + match.matchId(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" " + DebugSubcommands.INFINITE_XP + " "
                + (toggle.enabled() ? "on" : "off") + "，"
                + (toggle.enabled()
                        ? "本场出牌不再扣经验（召唤 / 法术的费用校验与扣减整条失效）；经验照常累积"
                        : "本场恢复正常的费用校验与扣减（经验不足会被拒）")
                + (changed ? "" : "　（本来就已是该值，没有变化）"),
                toggle.enabled() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(" 作用域仅此一场（多场并发时其它对局不受影响）"
                + "，且不写档案，对局结束即失效。", NamedTextColor.DARK_GRAY));
        logger().info("[IC] /incl debug infinite-xp " + (toggle.enabled() ? "on" : "off")
                + "：操作者=" + sender.getName() + " 目标=" + who + "（" + target + "）"
                + " matchId=" + match.matchId()
                + (changed ? "（已改）" : "（值相同，未变）"));
    }

    /**
     * ★★★ <b>{@code /incl debug purge-legacy [--yes]}</b> —— 遗留实体清理
     * （2026-10-17 第十二轮，用户裁定 §A 第 1 条「因为是测试服，所以破格一次，你直接全域清理」）。
     *
     * <h2>★ 默认只报告</h2>
     * 不写 {@code --yes} ⇒ {@link LegacyPurgeService#run(boolean, Consumer, Consumer)} 的
     * {@code confirm = false} ⇒ <b>一个实体都不删</b>，只把三类清单（可清 / 没带 PDC / 跳过）
     * 与对账打印出来。加 {@code --yes} 才真的删。
     *
     * <h2>★★ 铁律 10 的四问（这条是整个项目里唯一"按范围删实体"的入口）</h2>
     * 见 {@link LegacyPurgeService} 的类注释（范围 = 本插件自己的点位各 64 格方形；
     * 失败方向 = 点位缺失就一个都不删；归属 = PDC 三键 + 删前复核；可对账 = 四类桶带数量）。
     */
    private void runPurgeLegacy(CommandSender sender, String label, DebugSubcommands.Run run) {
        if (plugin == null || plugin.unitRegistry() == null) {
            tell(sender, KEY_NO_PERMISSION,
                    "插件还没就绪（追踪表不可用），遗留清理无法进行。", NamedTextColor.RED);
            return;
        }
        boolean confirm = run.confirm();
        InfernalClashPlugin owner = plugin;
        Consumer<String> runner = text -> sender.sendMessage(
                Component.text(text, confirm ? NamedTextColor.GOLD : NamedTextColor.YELLOW));
        Consumer<String> warner = text -> {
            sender.sendMessage(Component.text(text, NamedTextColor.DARK_GRAY));
            owner.getLogger().warning(text);
        };

        LegacyPurgeService service = new LegacyPurgeService(owner, owner.arenaConfig(),
                owner.unitRegistry());
        sender.sendMessage(Component.text("遗留实体清理 · " + service.describeReadiness(),
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" 范围与归属：" + io.mczju.infernalclash.objective
                .LegacyPurge.scopeProof(), NamedTextColor.DARK_GRAY));
        if (!confirm) {
            sender.sendMessage(Component.text(
                    " 本次只报告不删（默认）。确认清单无误后用 "
                            + cmd("/" + label + " debug " + DebugSubcommands.PURGE_LEGACY + " "
                            + DebugSubcommands.CONFIRM_FLAG) + " 真删。",
                    NamedTextColor.GRAY));
        }
        int removed = service.run(confirm, runner, warner);
        logger().info("[IC] /incl debug purge-legacy" + (confirm ? " --yes" : "")
                + "：操作者=" + sender.getName() + " 真的删除 " + removed + " 个"
                + (confirm ? "" : "（只报告，未删）"));
    }

    /** 发这条指令时卡池里的全部卡 id（★ 唯一事实来源 = {@code card/CardCatalog}，不写死 51 张）。 */
    private List<String> cardPoolIds() {
        if (plugin == null || plugin.cardCatalog() == null) {
            throw new IllegalStateException("卡池还没加载（插件未就绪 / /incl debug reload 失败过？）"
                    + "，「全部卡」无从谈起（绝不写死一份卡名兜底，那是第二处事实来源）");
        }
        return List.copyOf(plugin.cardCatalog().cards().keySet());
    }

    /**
     * ★ 把一次档案改动<b>带数量地对账</b>地报出来（操作者 / 目标 / 改前 → 改后），并写一条同样的日志。
     *
     * <p>{@code docs/10} §4 对 {@code reset-collection} 的要求就是这条：
     * 「<b>日志带数量</b>：操作者、目标玩家、清掉了多少张卡 / 多少预设」。
     * 另外三条照同一形状输出（便于实机对账）。
     *
     * <p>⚠️ 参数 {@code store} 只用来把<b>刚写回的内存值</b>读回来，<b>不等于「已落盘」</b>
     * —— 是否真的写进文件要重启后再用 {@code /incl debug profile show <uuid>} 看
     * （{@code docs/12}；与 {@code profileGrant} 同一口径，绝不把「调用过 savePlayerData」
     * 说成「落盘成功」）。
     */
    private void reportEdit(CommandSender sender, DebugSubcommands.Run run, String who,
                            java.util.UUID target, PlayerProgress before, PlayerProgress after,
                            ProfileStore store) {
        PlayerProgress now = store.load(target);
        sender.sendMessage(Component.text("调试档案 · " + run.action() + " · " + who
                + "（" + target + "）", NamedTextColor.GOLD));
        switch (run.action()) {
            case DebugSubcommands.GRANT_ALL -> sender.sendMessage(Component.text(
                    " 已拥有 " + before.owned().size() + " 张 → " + now.owned().size()
                            + " 张（图鉴全部置 GOLD —— 【Q40】连金色版一起给）"
                            + "；金币 / 尘 / 预设保持不变", NamedTextColor.GREEN));
            case DebugSubcommands.RESET_COLLECTION -> sender.sendMessage(Component.text(
                    " 已拥有 " + before.owned().size() + " 张 → " + now.owned().size()
                            + " 张（图鉴 " + before.codex().size() + " 条 → " + now.codex().size()
                            + " 条；★ **基础卡保留**、其余清空 —— 2026-10-21 用户裁定，"
                            + "R44「新玩家初始拥有全部基础卡」）"
                            + "；金币 / 尘保持不变（金币 " + now.gold() + "，尘 " + now.dust() + "）"
                            + "；预设 " + now.presets().size() + " 套未动（docs/10 §4 只要求报数）",
                    NamedTextColor.GREEN));
            case DebugSubcommands.SET_GOLD -> sender.sendMessage(Component.text(
                    " 金币 " + before.gold() + " → " + now.gold()
                            + "（尘 " + now.dust() + " 不变）", NamedTextColor.GREEN));
            case DebugSubcommands.SET_POWDER -> sender.sendMessage(Component.text(
                    " 尘（烈焰粉）" + before.dust() + " → " + now.dust()
                            + "（金币 " + now.gold() + " 不变）", NamedTextColor.GREEN));
            default -> sender.sendMessage(Component.text(" " + now.describe(), NamedTextColor.GRAY));
        }
        if (before == after) {
            sender.sendMessage(Component.text(" （值本来就等于目标值，没有产生新的写入对象）",
                    NamedTextColor.DARK_GRAY));
        }
        sender.sendMessage(Component.text(" 当前（内存）：" + now.describe(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" （是否真的写进文件要重启后再看 "
                + cmd("/incl debug profile show " + target) + "）",
                NamedTextColor.DARK_GRAY));
        logger().info("[IC] /incl debug " + run.action() + "：操作者=" + sender.getName()
                + " 目标=" + who + "（" + target + "）"
                + " 已拥有 " + before.owned().size() + "→" + now.owned().size()
                + "，金币 " + before.gold() + "→" + now.gold()
                + "，尘 " + before.dust() + "→" + now.dust()
                + "，预设 " + now.presets().size() + " 套"
                + (before == after ? "（值相同，未变）" : ""));
    }

    /**
     * {@code /incl debug objectives probe <世界> <x> <y> <z>} —— ★ <b>目标物生成探针</b>。
     *
     * <h2>为什么需要它</h2>
     * 目标物只在**对局开始时**生成，而那需要真人玩家进房间 —— 控制台无法触发。
     * 没有这个探针的话，「村民载体能不能生成 / 血量属性能不能设成 320 /
     * 展示实体能不能带 `lit=true` / PDC 登记是否成功 / 回收是否干净」
     * 这一整条链**只能靠肉眼在游戏里赌**。
     * ⇒ 探针用**显式坐标**（不读 `arenas.yml`、不猜位置）当场生成一套再立刻回收，
     * 把这条链变成**可复现、可对账**的验证。
     *
     * <p>★ 它只操作自己刚生成的两个实体，且**立即删除**；不写任何配置、不动世界方块。
     */
    private void runObjectiveProbe(CommandSender sender, String label, String[] args) {
        // 形如：/incl debug objectives probe <世界> <x> <y> <z>
        // ★ args 是「指令标签之后」的部分：debug / objectives / probe / 世界 / x / y / z = **7 个**
        //   （第一次写成 < 8，结果控制台只收到一句用法提示，探针根本没跑 —— 靠实机日志才发现）
        if (args.length < 7 || !args[2].equalsIgnoreCase("probe")) {
            sender.sendMessage(Component.text("用法：" + cmd("/" + label
                    + " debug objectives probe <世界> <x> <y> <z>"), NamedTextColor.YELLOW));
            return;
        }
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(args[3]);
        if (world == null) {
            sender.sendMessage(Component.text("世界不存在：" + args[3], NamedTextColor.RED));
            return;
        }
        double x;
        double y;
        double z;
        try {
            x = Double.parseDouble(args[4]);
            y = Double.parseDouble(args[5]);
            z = Double.parseDouble(args[6]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("坐标必须是数字：" + e.getMessage(), NamedTextColor.RED));
            return;
        }

        ObjectiveConfig config = plugin.objectiveConfig();
        UnitRegistry registry = plugin.unitRegistry();
        Point point = Point.of(world.getName(), x, y, z);
        String probeMatch = "probe";

        int before = registry.table().size();
        ObjectiveUnit beacon = ObjectiveUnit.spawn(plugin, registry, probeMatch,
                ObjectiveKind.BEACON, Side.RED, point, config.beacon().displayBlock(), false,
                config.beacon().health(), config.beacon().taunt(), "探针·信标", plugin.getLogger());
        ObjectiveUnit tower = ObjectiveUnit.spawn(plugin, registry, probeMatch,
                ObjectiveKind.TOWER, Side.BLUE, Point.of(world.getName(), x + 2.0, y, z),
                config.tower().displayBlock(), config.tower().displayLit(),
                config.tower().health(), config.tower().taunt(), "探针·防御塔", plugin.getLogger());

        if (beacon == null || tower == null) {
            sender.sendMessage(Component.text("探针失败：目标物生成返回 null（见控制台警告）",
                    NamedTextColor.RED));
            if (beacon != null) {
                beacon.remove(registry);
            }
            if (tower != null) {
                tower.remove(registry);
            }
            return;
        }

        sender.sendMessage(Component.text("探针结果", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" 信标载体：" + beacon.carrier().getType()
                + "｜血量上限 " + beacon.carrier().getMaxHealth()
                + "｜当前 " + beacon.carrier().getHealth()
                + "｜外观 " + beacon.display().getBlock().getMaterial(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" 防御塔载体：" + tower.carrier().getType()
                + "｜血量上限 " + tower.carrier().getMaxHealth()
                + "｜外观 " + tower.display().getBlock().getMaterial()
                + "（" + tower.display().getBlock().getAsString() + "）", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" PDC 归属："
                + (tower.carrier().getPersistentDataContainer()
                        .has(new org.bukkit.NamespacedKey(plugin, UnitRegistry.KEY_MATCH))
                        ? "已写入 match/side/owner 三键" : "未写入（异常）"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" 账本登记：" + before + " → " + registry.table().size()
                + "（信标 2 个实体 + 塔 2 个实体 = 应 +4）", NamedTextColor.GRAY));

        beacon.remove(registry);
        tower.remove(registry);
        sender.sendMessage(Component.text(" 已回收：账本 " + registry.table().size()
                + "｜残留实体 beacon.isValid=" + beacon.carrier().isValid()
                + " tower.isValid=" + tower.carrier().isValid(), NamedTextColor.GRAY));
    }

    /**
     * {@code /incl debug card fill [玩家] [卡id,卡id,...]} / {@code … card give <卡id> [玩家]}
     * —— ★ <b>调试用的发牌</b>。
     *
     * <h2>为什么是调试指令而不是「默认牌组」</h2>
     * R16 说 8 张卡来自玩家**自己选的卡组**，而选卡界面属于 **P8**、卡牌仓库属于 **P7**。
     * 在没有真牌组之前，我**不会**编一套「默认 8 张」塞进游戏 —— 那是替用户决定玩法（铁律 8）。
     * ⇒ 用调试指令显式发牌：**没给卡表时用「最便宜的 8 张」并在输出里写明这是临时牌组**，
     * 让操作者一眼看出它不是真牌组。
     *
     * <p>★ 它只动**快捷栏的 2~9 格**（与 `CardLoadout` 同一处实现），不碰背包其余部分。
     */
    private void runCardDebug(CommandSender sender, String label, String[] args) {
        // args: debug / card / fill|give / ...
        if (args.length < 3) {
            sender.sendMessage(Component.text("用法：" + cmd("/" + label
                    + " debug card fill [玩家] [卡id,卡id,...]")
                    + " 或 " + cmd("/" + label + " debug card give <卡id> [玩家]"),
                    NamedTextColor.YELLOW));
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("probe")) {
            runCardProbe(sender, label, args);
            return;
        }
        if (!action.equals("fill") && !action.equals("give")) {
            sender.sendMessage(Component.text("未知的 card 子指令：" + action, NamedTextColor.RED));
            return;
        }

        List<String> ids = new ArrayList<>();
        Player target;
        if (action.equals("give")) {
            if (args.length < 4) {
                sender.sendMessage(Component.text("用法：" + cmd("/" + label
                        + " debug card give <卡id> [玩家]"), NamedTextColor.YELLOW));
                return;
            }
            ids.add(args[3]);
            target = args.length >= 5 ? Bukkit.getPlayerExact(args[4]) : asPlayer(sender);
        } else {
            target = args.length >= 4 ? Bukkit.getPlayerExact(args[3]) : asPlayer(sender);
            if (args.length >= 5) {
                ids.addAll(List.of(args[4].split(",")));
            }
        }
        if (target == null) {
            tell(sender, KEY_PLAYERS_ONLY, "要在控制台用就请显式指定玩家名。", NamedTextColor.RED);
            return;
        }

        List<Card> deck = new ArrayList<>();
        if (ids.isEmpty()) {
            deck.addAll(plugin.cardCatalog().cards().values().stream()
                    .sorted(Comparator.comparingInt(Card::cost).thenComparing(Card::id))
                    .limit(8)
                    .toList());
        } else {
            for (String raw : ids) {
                String id = raw.trim();
                Card card = plugin.cardCatalog().cards().get(id);
                if (card == null) {
                    sender.sendMessage(Component.text("未知卡牌 id：" + id + " —— "
                            + plugin.cardCatalog().suggest(id), NamedTextColor.RED));
                    return;
                }
                deck.add(card);
            }
        }

        int given = CardLoadout.placeDeck(plugin, target, deck);
        String summary = deck.stream().limit(given).map(Card::id).toList().toString();
        sender.sendMessage(Component.text("已给 " + target.getName() + " 放入 " + given
                + " 张卡（2~9 号格）：" + summary
                + (ids.isEmpty() ? "　临时牌组（最便宜的 8 张），真牌组在 P7/P8" : ""),
                NamedTextColor.GRAY));
        plugin.getLogger().info("[IC] /incl debug card " + action + "：给 " + target.getName()
                + " 放入 " + given + " 张卡 " + summary);
    }

    /**
     * {@code /incl debug card probe <世界> <x> <y> <z> <卡id> [yaw]} —— ★ <b>召唤链探针</b>。
     *
     * <h2>为什么需要它</h2>
     * 真正的出牌要真人右键，控制台做不到。但 P4 里最容易错、也最难靠肉眼数清楚的正是**生成那一段**：
     * 「相对排布算得对不对 / 属性有没有真的应用 / 归属有没有登记 / 清场删不删得掉」。
     * ⇒ 探针用**显式坐标 + 显式卡 id** 走完整条生成链，报出每个召唤物的**类型与实际格坐标**
     * （一眼就能看出「尸壳在前面、焦骸在后面并排」），然后**用正常清场路径回收**。
     *
     * <p>★ 它只生成自己这一批实体（matchId 固定为 {@code probe}），并立刻用
     * {@link UnitRegistry#cleanup} 删掉 —— 顺便把 P2 的清场路径也一起验了。
     */
    private void runCardProbe(CommandSender sender, String label, String[] args) {
        // args: debug / card / probe / 世界 / x / y / z / 卡id / [yaw]
        if (args.length < 8) {
            sender.sendMessage(Component.text("用法：" + cmd("/" + label
                    + " debug card probe <世界> <x> <y> <z> <卡id> [yaw]"), NamedTextColor.YELLOW));
            return;
        }
        org.bukkit.World world = Bukkit.getWorld(args[3]);
        if (world == null) {
            sender.sendMessage(Component.text("世界不存在：" + args[3], NamedTextColor.RED));
            return;
        }
        double x;
        double y;
        double z;
        int yaw = 0;
        try {
            x = Double.parseDouble(args[4]);
            y = Double.parseDouble(args[5]);
            z = Double.parseDouble(args[6]);
            if (args.length >= 9) {
                yaw = Integer.parseInt(args[8]);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("坐标 / yaw 必须是数字：" + e.getMessage(), NamedTextColor.RED));
            return;
        }
        Card card = plugin.cardCatalog().cards().get(args[7]);
        if (card == null) {
            sender.sendMessage(Component.text("未知卡牌 id：" + args[7] + " —— "
                    + plugin.cardCatalog().suggest(args[7]), NamedTextColor.RED));
            return;
        }
        if (!card.isSummon()) {
            sender.sendMessage(Component.text("只有召唤卡能做探针（法术卡在 P6）。", NamedTextColor.RED));
            return;
        }

        io.mczju.infernalclash.card.Facing facing = io.mczju.infernalclash.card.Facing.fromYaw(yaw);
        int before = plugin.unitRegistry().table().size();
        int spawned = plugin.summonSpawner().spawn(card, new org.bukkit.Location(world, x, y, z),
                facing, "probe", io.mczju.infernalclash.match.Side.RED, null);

        sender.sendMessage(Component.text("探针结果：" + card.id() + "（朝向 " + facing
                + "，落点 " + (int) x + "," + (int) y + "," + (int) z + "）", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" 生成 " + spawned + " 个｜账本 " + before + " → "
                + plugin.unitRegistry().table().size(), NamedTextColor.GRAY));
        for (java.util.UUID id : plugin.unitRegistry().tracked("probe")) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
            if (entity == null) {
                continue;
            }
            org.bukkit.Location at = entity.getLocation();
            String health = entity instanceof org.bukkit.entity.LivingEntity living
                    ? "｜血量 " + living.getHealth() + "/" + living.getMaxHealth()
                    : "";
            double scale = entity instanceof org.bukkit.entity.LivingEntity living
                    && living.getAttribute(org.bukkit.attribute.Attribute.SCALE) != null
                    ? living.getAttribute(org.bukkit.attribute.Attribute.SCALE).getBaseValue()
                    : -1;
            sender.sendMessage(Component.text(" " + entity.getType()
                    + " @" + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ()
                    + health + (scale > 0 ? "｜scale " + scale : ""), NamedTextColor.GRAY));
        }
        UnitRegistry.CleanupReport report = plugin.unitRegistry().cleanup("probe", plugin.getLogger());
        int cleaned = report.removed();
        sender.sendMessage(Component.text(" 已回收 " + cleaned + " 个（" + report.describe()
                + "，走的是正常清场路径）｜账本现在 " + plugin.unitRegistry().table().size(),
                NamedTextColor.GRAY));
        plugin.getLogger().info("[IC] /incl debug card probe：" + card.id() + " 生成 " + spawned
                + " 回收 " + cleaned + "（朝向 " + facing + "）");
    }

    /**
     * {@code /incl debug combat probe <世界> <x> <y> <z> <卡id>} —— ★★ <b>T4 的直接验证</b>。
     *
     * <h2>它验的是什么</h2>
     * T4 的原话是「生物 A 追着 B 时，更近的 C 出现也不会换」。本探针把这个场景**摆出来**：
     * <ol>
     *   <li>在落点召唤这张卡的召唤物（红方）；</li>
     *   <li>在 +3 格与 +10 格各放一个敌方（蓝方）⇒ 跑一轮选敌 ⇒ **应当选中 +3 那个**；</li>
     *   <li>★ 再在 +1 格放一个**更近**的敌方 ⇒ 再跑一轮 ⇒ **应当切换过去**。</li>
     * </ol>
     * 第 3 步就是 T4 的**直接断言**（离线的 `TargetPolicyTest` 已钉住策略层，
     * 这里钉的是**接线层**：账本 → 候选 → `setTarget`）。
     *
     * <p>★ 只生成自己这一批实体（matchId 固定 {@code probe}），跑完立刻用**正常清场路径**删掉。
     */
    private void runCombatProbe(CommandSender sender, String label, String[] args) {
        // args: debug / combat / probe / 世界 / x / y / z / 卡id
        if (args.length < 8) {
            sender.sendMessage(Component.text("用法：" + cmd("/" + label
                    + " debug combat probe <世界> <x> <y> <z> <卡id>"), NamedTextColor.YELLOW));
            return;
        }
        org.bukkit.World world = Bukkit.getWorld(args[3]);
        if (world == null) {
            sender.sendMessage(Component.text("世界不存在：" + args[3], NamedTextColor.RED));
            return;
        }
        double x;
        double y;
        double z;
        try {
            x = Double.parseDouble(args[4]);
            y = Double.parseDouble(args[5]);
            z = Double.parseDouble(args[6]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("坐标必须是数字：" + e.getMessage(), NamedTextColor.RED));
            return;
        }
        Card card = plugin.cardCatalog().cards().get(args[7]);
        if (card == null || !card.isSummon()) {
            sender.sendMessage(Component.text("需要一张召唤卡的 id；未知 / 非召唤卡：" + args[7],
                    NamedTextColor.RED));
            return;
        }

        String probe = "probe";
        io.mczju.infernalclash.match.UnitRegistry registry = plugin.unitRegistry();
        java.util.UUID fakeOwner = java.util.UUID.randomUUID();
        org.bukkit.Location base = new org.bukkit.Location(world, x, y, z);

        // ① 红方召唤物（就是被验的"生物 A"）
        int mine = plugin.summonSpawner().spawn(card, base,
                io.mczju.infernalclash.card.Facing.fromYaw(0), probe,
                io.mczju.infernalclash.match.Side.RED, fakeOwner);

        // ② 两个蓝方敌人：+3（近）与 +10（远）
        org.bukkit.entity.Entity near = spawnProbeEnemy(world, base, 3.0, probe, fakeOwner);
        org.bukkit.entity.Entity far = spawnProbeEnemy(world, base, 10.0, probe, fakeOwner);
        if (mine == 0 || near == null || far == null) {
            sender.sendMessage(Component.text("探针准备失败（见控制台）", NamedTextColor.RED));
            registry.cleanup(probe, plugin.getLogger());
            return;
        }

        io.mczju.infernalclash.battle.CombatDirector director =
                new io.mczju.infernalclash.battle.CombatDirector(plugin, probe, registry,
                        plugin.mobGroups(), 1);
        director.onTick(io.mczju.infernalclash.match.MatchPhase.BATTLE, 0);

        sender.sendMessage(Component.text("第一轮（敌人在 +3 与 +10，应当选 +3）：", NamedTextColor.GOLD));
        StringBuilder before = new StringBuilder();
        for (java.util.UUID id : registry.tracked(probe)) {
            var unit = director.unit(id);
            if (unit == null) {
                continue;
            }
            org.bukkit.entity.LivingEntity chosen = unit.resolvedTarget();
            double distance = chosen == null ? -1
                    : Math.sqrt(unit.mob().getLocation().distanceSquared(chosen.getLocation()));
            before.append(id).append('=').append(chosen == null ? "无" : (int) distance).append(' ');
            sender.sendMessage(Component.text(" " + unit.mob().getType() + " → "
                    + (chosen == null ? "无目标" : chosen.getType() + "（距离 "
                    + String.format("%.1f", distance) + "）"), NamedTextColor.GRAY));
        }

        // ③ ★ 再放一个**更近**的敌人（+1）⇒ 必须切换（T4 的直接验证）
        org.bukkit.entity.Entity closer = spawnProbeEnemy(world, base, 1.0, probe, fakeOwner);
        director.onTick(io.mczju.infernalclash.match.MatchPhase.BATTLE, 1);

        sender.sendMessage(Component.text("第二轮（+1 处出现更近的敌人，应当切换过去）：",
                NamedTextColor.GOLD));
        boolean switched = false;
        for (java.util.UUID id : registry.tracked(probe)) {
            var unit = director.unit(id);
            if (unit == null || closer == null) {
                continue;
            }
            org.bukkit.entity.LivingEntity chosen = unit.resolvedTarget();
            boolean nowCloser = chosen != null && chosen.getUniqueId().equals(closer.getUniqueId());
            switched |= nowCloser;
            sender.sendMessage(Component.text(" " + unit.mob().getType() + " → "
                    + (chosen == null ? "无目标" : chosen.getType() + "（距离 "
                    + String.format("%.1f", Math.sqrt(unit.mob().getLocation()
                    .distanceSquared(chosen.getLocation()))) + "）")
                    + (nowCloser ? "　已切换到更近的敌人" : "　（未切换）"),
                    nowCloser ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        sender.sendMessage(Component.text("结论：" + (switched
                ? "更近的敌人出现后已切换 —— T4 的修正生效"
                : "⚠️ 未切换 —— 需要排查（T4 缺陷可能被继承）"),
                switched ? NamedTextColor.GREEN : NamedTextColor.RED));

        UnitRegistry.CleanupReport report = registry.cleanup(probe, plugin.getLogger());
        int cleaned = report.removed();
        sender.sendMessage(Component.text("已回收 " + cleaned + " 个实体（" + report.describe()
                + "，走正常清场路径）｜账本现在 " + registry.table().size(), NamedTextColor.GRAY));
        plugin.getLogger().info("[IC] /incl debug combat probe：" + card.id()
                + " 召唤 " + mine + " 个，选敌两轮，切换=" + switched + "，回收 " + cleaned);
    }

    /** 探针用的敌方（goal 型僵尸；带一个假主人 ⇒ 当作「敌方召唤物」，嘲讽为 false）。 */
    private org.bukkit.entity.Entity spawnProbeEnemy(org.bukkit.World world,
                                                     org.bukkit.Location base,
                                                     double offsetZ, String probe,
                                                     java.util.UUID fakeOwner) {
        org.bukkit.Location at = base.clone().add(0.0, 0.0, offsetZ);
        try {
            org.bukkit.entity.Entity entity = world.spawn(at, org.bukkit.entity.Zombie.class);
            plugin.unitRegistry().tag(entity,
                    new io.mczju.infernalclash.match.OwnershipTable.Owner(probe,
                            io.mczju.infernalclash.match.Side.BLUE, fakeOwner), false);
            return entity;
        } catch (Throwable t) {
            plugin.getLogger().warning("[IC] 探针生成敌方失败：" + t);
            return null;
        }
    }

    /** 发送者是玩家时返回它，否则 {@code null}（控制台必须显式指定玩家名）。 */
    private static Player asPlayer(CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }

    /** 打印 MGC 注册状态与房间状态（排障首选）。 */    private void printMgcStatus(CommandSender sender) {
        sender.sendMessage(Component.text("MGC 注册状态", NamedTextColor.GOLD));

        boolean registered;
        String metaLine = "（未注册）";
        try {
            Map<String, GameMeta> metas = MCZJUGameCore.getGameManager().getGameMetas();
            registered = metas != null && metas.containsKey(InfernalClashGame.ID);
            GameMeta meta = metas == null ? null : metas.get(InfernalClashGame.ID);
            if (meta != null) {
                metaLine = "显示名 " + meta.displayName() + " / 图标 " + meta.icon();
            }
        } catch (Throwable t) {
            sender.sendMessage(Component.text(" 读取游戏注册表失败：" + t, NamedTextColor.RED));
            return;
        }
        sender.sendMessage(line("game id", InfernalClashGame.ID));
        sender.sendMessage(Component.text(" 已注册：" + (registered ? "是" : "否")
                + " " + metaLine, registered ? NamedTextColor.GREEN : NamedTextColor.RED));

        try {
            List<AbstractGameRoom> rooms =
                    MCZJUGameCore.getGameRoomManager().getGameRooms(InfernalClashGame.ID);
            if (rooms == null || rooms.isEmpty()) {
                sender.sendMessage(Component.text(" 房间：0 个 → /mgc join 会提示「没有空闲的房间」"
                        + "（可重启让插件重建，或 /mgcop room create " + InfernalClashGame.ID + " default）",
                        NamedTextColor.RED));
                return;
            }
            long ready = rooms.stream().filter(r -> r.getState() == GameRoomState.READY).count();
            sender.sendMessage(Component.text(" 房间：" + rooms.size() + " 个，其中 READY "
                    + ready + " 个", ready > 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
            for (AbstractGameRoom room : rooms) {
                sender.sendMessage(Component.text(" · " + room.getRoomName()
                        + " → " + room.getState(), NamedTextColor.GRAY));
            }
            if (ready == 0) {
                sender.sendMessage(Component.text(" ⚠ 没有 READY 房间 → /mgc join 进不来，"
                        + "执行 " + cmd("/incl debug room reset") + " 复位", NamedTextColor.YELLOW));
            }
        } catch (Throwable t) {
            sender.sendMessage(Component.text(" 读取房间列表失败：" + t, NamedTextColor.RED));
        }
    }

    /**
     * {@code /incl debug room reset}：把**本游戏**所有非 READY 的房间置回 READY。
     *
     * <p>范围界定：只取 {@code getGameRooms(InfernalClashGame.ID)}，
     * **不遍历全服、不碰别的游戏的房间**（安全规程：归属必须可判定）。
     *
     * <h2>★★ 2026-10-16（第七轮）：它<b>不</b>复位「对局实例的 state」—— 输出里必须讲清</h2>
     * 现场实测（{@code javap} 逐条见 {@code docs/06} §2.18 ＋ {@code dev-log/2026-10-16-026} §四）：
     * 本指令只做 {@code room.setState(READY)}；而<b>对局实例</b>还留在
     * {@code DefaultGameManager.gameList} 里、状态停在开局回调抛异常那一刻的
     * {@code STATING} ⇒ 于是
     * <ul>
     *   <li>{@code /mgc start} 回「<b>游戏不在等待状态，无法尝试开始游戏</b>」
     *       —— 它判的是 {@code game.getState() != WAITING}（**不是房间**）；</li>
     *   <li>{@code /mgc join <gameId> <房名>} 回「<b>游戏已开始，无法中途加入</b>」
     *       —— {@code getGame(gameId, roomName)} 仍查得到那个实例。</li>
     * </ul>
     * ⇒ 这正是用户现场遇到的「复位之后 `/mgc start` 仍被拒」。
     * <b>本指令的行为一字未改</b>（改它 = 动 MGC 语义，超出缺陷范围 ⇒ 已作为需裁决项上报）；
     * 只在<b>输出</b>里说清区别与恢复动作（恢复 = 让对局走
     * {@code GameManager.abortGame/endGame} ⇒ {@code solveGameEnd}：
     * 置终态 ＋ {@code removeAllPlayer} ＋ 房间 READY ＋ 从 gameList 摘掉）。
     */
    private void runRoomDebug(CommandSender sender, String label, String[] args) {
        String action = args.length < 3 ? "" : args[2].toLowerCase(Locale.ROOT);
        if (!"reset".equals(action)) {
            tell(sender, KEY_ROOM_USAGE, "用法：" + cmd("/" + label + " debug room reset"),
                    Map.of("usage", "/" + label + " debug room reset"), NamedTextColor.YELLOW);
            return;
        }
        List<AbstractGameRoom> rooms;
        try {
            rooms = MCZJUGameCore.getGameRoomManager().getGameRooms(InfernalClashGame.ID);
        } catch (Throwable t) {
            sender.sendMessage(Component.text("读取房间列表失败：" + t, NamedTextColor.RED));
            return;
        }
        if (rooms == null || rooms.isEmpty()) {
            tell(sender, KEY_ROOM_NONE,
                    "本游戏没有房间实例，无需复位。可重启服务器让插件重建默认房间。",
                    NamedTextColor.YELLOW);
            return;
        }
        int reset = 0;
        for (AbstractGameRoom room : rooms) {
            if (room.getState() != GameRoomState.READY) {
                room.setState(GameRoomState.READY);
                reset++;
                plugin.getLogger().info("[IC] 房间 " + room.getRoomName() + " 已复位为 READY");
            }
        }
        // ★ 日志带数量，便于实机对账
        tell(sender, KEY_ROOM_RESET_DONE,
                "已复位 " + reset + " / " + rooms.size() + " 个房间为 READY。",
                Map.of("reset", String.valueOf(reset), "total", String.valueOf(rooms.size())),
                NamedTextColor.GREEN);
        plugin.getLogger().info("[IC] /incl debug room reset：共 " + rooms.size()
                + " 个房间，复位 " + reset + " 个");
        // ★★ 2026-10-16（第七轮）：把「复位房间 ≠ 复位对局 state」说清楚。
        //   现场实测（`javap` 逐条见 docs/06 §2.18 ＋ dev-log/2026-10-16-026 §四）：
        //   `DefaultGameManager` 只把**房间**置 READY；**对局实例**（gameList 里那一个）
        //   仍然留在 `STATING`/`RUNNING`，玩家也仍然挂在它上面 ⇒ 于是
        //   ① `/mgc start` 回「游戏不在等待状态，无法尝试开始游戏」（它判的是 **game.getState()**）；
        //   ② `/mgc join <gameId> <房名>` 回「游戏已开始，无法中途加入」（`getGame` 仍能查到那个实例）。
        //   ⇒ 正确恢复是让**对局实例**走到 `solveGameEnd`（它才会 `setState(END)` ＋
        //     `removeAllPlayer` ＋ 房间 READY ＋ 从 gameList 摘掉）。
        //   ★ 刻意**硬编码**这两行而不走 display.yml：现场那份 display.yml 走「只增不改」迁移，
        //     改出厂文案对它**无效**；而这两行是排障必需的（不能让一句旧文案把它盖掉）。
        sender.sendMessage(Component.text(" 注意：复位的是【房间】，不是【对局实例的 state】"
                + "（复位房间不等于复位对局）。", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(" 若刚刚有一次开局中途失败，对局可能仍停在 STATING/ "
                + "RUNNING，/mgc start 会回「游戏不在等待状态」，/mgc join 会回「游戏已开始，无法中途加入」。",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" 正确恢复：让房间里最后一名玩家敲 " + cmd("/mgc leave")
                + "（本插件在真人走空时会走 GameManager.abortGame，对局收尾、房间释放），"
                + "然后重新 " + cmd("/mgc join " + InfernalClashGame.ID), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" 若 /mgc leave 也没能收尾，再重启服务器"
                + "（房间 state 不落盘，重启即恢复）。", NamedTextColor.GRAY));
    }

    /**
     * {@code /incl debug profile show|grant|setowned …} —— ★ <b>局外成长档案的跨重启验证三件套</b>。
     *
     * <pre>
     *   /incl debug profile show &lt;uuid&gt;                    ← 打印该 uuid 的全部字段 + raw 是否存在
     *   /incl debug profile grant &lt;uuid&gt; &lt;gold&gt; [dust]      ← 加金币与尘并**立即落盘**
     *   /incl debug profile setowned &lt;uuid&gt; &lt;卡id,卡id,...&gt;  ← **替换**「已拥有」卡列表并落盘
     * </pre>
     *
     * <h2>为什么需要它们（控制台就能跑，不需要真人客户端）</h2>
     * 「跨重启保留」（{@code docs/11} §S6）本来只能靠「真人上线 → 开包/组卡 → 重启 → 再看」，
     * 那条链上任何一环出问题都要来回折腾。而 {@code ProfileStore.ensure} 实测可以为
     * **从没上线过的 UUID** 造出档案（见 {@code ProfileStore} 类注释里的反汇编依据）⇒
     * 这三条指令把整条链变成 {@code grant → stop → start → show} 四步、可复现、可对账。
     *
     * <h2>★ 输出刻意做成「可对账」</h2>
     * 每次操作都打印：<b>uuid / 改了什么 / 改之前的值 / 改之后的值 / 内存档案对象是否存在</b>。
     * ⚠️ 「改之后的值」读的是**内存**里的值（写回后立刻读回来），
     * <b>它不等于「已经写进磁盘」</b> —— 是否真的落盘要靠重启后的 {@code show} 验证
     * （{@code docs/12}）。绝不把「调用过 savePlayerData」说成「落盘成功」。
     *
     * <p>范围界定（铁律 10）：只按**显式给出的 UUID** 读写 MGC 档案，
     * 不遍历在线玩家、不查世界、不动任何实体。
     */
    private void runProfileDebug(CommandSender sender, String label, String[] args) {
        // args: debug / profile / <show|grant|setowned> / <uuid> [参数…]
        String usage = "用法：" + cmd("/" + label + " debug profile show <uuid>")
                + " ｜ " + cmd("/" + label + " debug profile grant <uuid> <gold> [dust]")
                + " ｜ " + cmd("/" + label + " debug profile setowned <uuid> <卡id,卡id,...>");
        if (args.length < 4) {
            sender.sendMessage(Component.text(usage, NamedTextColor.YELLOW));
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        java.util.UUID playerId;
        try {
            playerId = java.util.UUID.fromString(args[3]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("uuid 格式不对：" + args[3]
                    + "（期望形如 069a79f4-44e9-4726-a5be-fca90e38aaf5）", NamedTextColor.RED));
            return;
        }
        ProfileStore store = plugin.profileStore();
        switch (action) {
            case "show" -> profileShow(sender, store, playerId);
            case "grant" -> profileGrant(sender, label, store, playerId, args, usage);
            case "setowned" -> profileSetOwned(sender, label, store, playerId, args);
            default -> sender.sendMessage(Component.text("未知的 profile 子指令：" + action
                    + "　" + usage, NamedTextColor.RED));
        }
    }

    /** {@code … profile show <uuid>}：打印全部字段，以及「内存档案对象在不在」。 */
    private void profileShow(CommandSender sender, ProfileStore store, java.util.UUID playerId) {
        InfernalPlayerData before = store.raw(playerId);
        PlayerProgress progress = store.load(playerId);
        InfernalPlayerData after = store.raw(playerId);

        sender.sendMessage(Component.text("调试档案 · " + playerId, NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" 内存档案对象（raw）：调用前 "
                + (before == null ? "不存在" : "存在")
                + " → 调用后 " + (after == null ? "不存在" : "存在")
                + (before == null && after != null
                        ? "（本次 load 现造了一份空白的；它自己不会落盘）" : ""),
                after == null ? NamedTextColor.RED
                        : (before == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        sender.sendMessage(Component.text(" 金币 " + progress.gold()
                + " ｜ 尘 " + progress.dust()
                + " ｜ 已拥有 " + progress.owned().size() + " 张"
                + " ｜ 预设 " + progress.presets().size() + " 套"
                + " ｜ 图鉴 " + progress.codex().size() + " 条"
                + " ｜ 已开包 " + progress.packsOpened() + " 次"
                + " ｜ 对局 " + progress.gamesPlayed() + " 场"
                + " ｜ 格式版本 " + (after == null ? "?" : after.formatVersion),
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" 已拥有：" + progress.owned(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" （这些是内存里的值；重启后再跑一次这条指令即可"
                + "验证是否真的落盘 —— docs/12）", NamedTextColor.DARK_GRAY));
    }

    /** {@code … profile grant <uuid> <gold> [dust]}：加钱加尘 + 立即落盘 + 打印改前改后。 */
    private void profileGrant(CommandSender sender, String label, ProfileStore store,
                              java.util.UUID playerId, String[] args, String usage) {
        if (args.length < 5) {
            sender.sendMessage(Component.text("用法：" + cmd("/" + label
                    + " debug profile grant <uuid> <gold> [dust]"), NamedTextColor.YELLOW));
            return;
        }
        int gold;
        int dust = 0;
        try {
            gold = Integer.parseInt(args[4]);
            if (args.length >= 6) {
                dust = Integer.parseInt(args[5]);
            }
        } catch (NumberFormatException e) {
            tell(sender, KEY_INVALID_AMOUNT, "gold / dust 必须是整数：" + e.getMessage(),
                    NamedTextColor.RED);
            return;
        }
        if (gold < 0 || dust < 0) {
            sender.sendMessage(Component.text("gold / dust 不能为负（要扣钱请用别的手段）：gold="
                    + gold + " dust=" + dust + "　" + usage, NamedTextColor.RED));
            return;
        }

        PlayerProgress before = store.load(playerId);
        PlayerProgress after;
        try {
            after = before.addGold(gold).addDust(dust);
        } catch (IllegalArgumentException e) {
            // PlayerProgress.addGold/addDust 会在 int 溢出时抛（字段是 int）—— 指令里不能让它逃出去
            sender.sendMessage(Component.text("加完之后溢出（进度字段是 int）：" + e.getMessage()
                    + "　" + usage, NamedTextColor.RED));
            return;
        }
        store.save(playerId, after);
        PlayerProgress now = store.load(playerId);

        sender.sendMessage(Component.text("调试档案 · grant · " + playerId, NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" 本次加上：金币 +" + gold + "，尘 +" + dust,
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" 金币 " + before.gold() + " → " + now.gold()
                + "　｜　尘 " + before.dust() + " → " + now.dust(), NamedTextColor.GREEN));
        sender.sendMessage(Component.text(" 当前（内存）：" + now.describe(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" 内存档案对象："
                + (store.raw(playerId) == null ? "不存在（写回没成功，见控制台告警）" : "存在")
                + "｜已调用 savePlayerData 立即落盘（是否真写进文件请重启后再 show）",
                NamedTextColor.GRAY));
        plugin.getLogger().info("[IC] /incl debug profile grant：uuid=" + playerId
                + " 金币 +" + gold + " 尘 +" + dust
                + "，金币 " + before.gold() + "→" + now.gold()
                + "，尘 " + before.dust() + "→" + now.dust());
    }

    /** {@code … profile setowned <uuid> <卡id,...>}：**替换**已拥有卡列表 + 落盘 + 打印改前改后。 */
    private void profileSetOwned(CommandSender sender, String label, ProfileStore store,
                                 java.util.UUID playerId, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Component.text("用法：" + cmd("/" + label
                    + " debug profile setowned <uuid> <卡id,卡id,...>"), NamedTextColor.YELLOW));
            return;
        }
        String csv = args[4];
        var wanted = ProfileStore.parseOwned(csv);

        PlayerProgress before = store.load(playerId);
        PlayerProgress after = ProfileStore.withOwned(before, csv);
        store.save(playerId, after);
        PlayerProgress now = store.load(playerId);

        // 未知卡 id **只提示不拦截**：调试指令的用途就是"把存档改成某个样子"，
        // 卡池加载失败 / 卡被改名时更需要它照做（判据只有卡池一处，不在这里做第二处白名单）
        List<String> unknown = new ArrayList<>();
        if (plugin.cardCatalog() != null) {
            for (String cardId : wanted) {
                if (!plugin.cardCatalog().cards().containsKey(cardId)) {
                    unknown.add(cardId);
                }
            }
        }

        sender.sendMessage(Component.text("调试档案 · setowned（替换，不是追加）· "
                + playerId, NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" 解析出 " + wanted.size() + " 张：" + wanted,
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" 已拥有 " + before.owned().size() + " 张 → "
                + now.owned().size() + " 张", NamedTextColor.GREEN));
        sender.sendMessage(Component.text(" 现在：" + now.owned(), NamedTextColor.GRAY));
        if (!unknown.isEmpty()) {
            sender.sendMessage(Component.text(" ⚠ 这些 id 不在卡池里（仍然写进去了）：" + unknown,
                    NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text(" 金币 / 尘 / 预设 / 计数保持不变：金币 "
                + now.gold() + "，尘 " + now.dust() + "，预设 " + now.presets().size() + " 套",
                NamedTextColor.GRAY));
        plugin.getLogger().info("[IC] /incl debug profile setowned：uuid=" + playerId
                + " 已拥有 " + before.owned().size() + " → " + now.owned().size()
                + " 张，未知 id=" + unknown);
    }

    private void reload(CommandSender sender) {
        try {
            plugin.reloadAllConfigs();
            tell(sender, KEY_RELOAD_OK, "配置已重新加载并通过校验。", NamedTextColor.GREEN);
        } catch (ConfigError e) {
            tell(sender, KEY_RELOAD_FAILED, "配置重新加载失败：" + e.describe(),
                    Map.of("error", e.describe()), NamedTextColor.RED);
            plugin.getLogger().warning("[IC] 热重载失败：" + e.describe());
        }
    }

    // ---------------- 输出helper ----------------

    /**
     * ★ <b>取一条 {@code display.yml} 文案发给发令者</b>（A8 接线；控制台与玩家通用）。
     *
     * <p>一律走 {@link MenuTexts#component}：解析 MiniMessage ＋ **失败时回退到 {@code fallback}**
     * （代码里的中文常量）＋ **绝不抛**。★ 告警出口是 {@code logger()}（`MenuTexts` 按键去重，
     * 同一个键最多告警一次 ⇒ 不会把真正的告警淹掉）。
     *
     * @param colorIfAbsent 文本自己没带颜色时补的颜色（配置里的 {@code <red>} 等优先）
     */
    private void tell(CommandSender sender, String key, String fallback, NamedTextColor colorIfAbsent) {
        tell(sender, key, fallback, Map.of(), colorIfAbsent);
    }

    /** {@link #tell(CommandSender, String, String, NamedTextColor)} 的带占位符版本。 */
    private void tell(CommandSender sender, String key, String fallback,
                      Map<String, String> placeholders, NamedTextColor colorIfAbsent) {
        // ★★★ 2026-10-19：配置里把这一行留空（`""`）⇒ **直接不发**。
        //   否则会发一个空 Component，聊天栏多出一条空行（玩家会以为"点了没反应"）。
        if (MenuTexts.isSilent(plugin == null ? null : plugin.display(), key)) {
            return;
        }
        // ★★ `plugin` 可能是 null：多条离线测试用 `new InfernalClashCommand(null)` 构造
        //   （指令分发本身不需要插件实例）⇒ 这里必须自己守，否则 `plugin.display()` 会 NPE。
        //   `MenuTexts` 收到 null display 时**回退到 fallback 常量**（而那正是接线前印的那句话）
        //   ⇒ 行为与接线前**逐字一致**，既有断言（如 MSG_PLAYERS_ONLY）继续成立。
        sender.sendMessage(MenuTexts.component(plugin == null ? null : plugin.display(),
                key, fallback, placeholders, colorIfAbsent, msg -> logger().warning(msg)));
    }

    private void printUsage(CommandSender sender, String label) {
        // ★ 顶层用法提示的**首行**走 display.yml（`command.usage`，{usage} = 一行紧凑用法串）；
        //   下面那串逐条清单仍是代码里的（27 条键里没有它的对应键 —— 已登记为 R76 的残余缺口）
        tell(sender, KEY_USAGE, "用法：",
                Map.of("usage", "/" + label + " <shop|cards|codex|info|arena|bot|debug …>"),
                NamedTextColor.YELLOW);
        sender.sendMessage(Component.text(" " + cmd("/" + label + " shop")
                + " —— 打开局外商店（" + menuIdFor(MenuSubcommands.Screen.SHOP) + "）",
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " cards")
                + " —— 打开图鉴 + 卡组预设（" + menuIdFor(MenuSubcommands.Screen.CARDS) + "）",
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " codex")
                + " —— 打开只读图鉴（" + menuIdFor(MenuSubcommands.Screen.CODEX) + "）",
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " info")
                + " —— 查看底座状态", NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " arena")
                + " —— 打开竞技场编辑界面（U1：左键绑定 / 右键传送 / Shift+左键清除）",
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " arena "
                + ArenaSubcommands.usage()) + " —— 点位采集 / 查看 / 删除（先备份再写盘）",
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " bot "
                + BotSubcommands.usage()) + " —— 开/关本房间的人机"
                + "（等待房间里任何玩家可敲；开启后等 "
                + TrainingMatchRules.BOT_START_WINDOW_TICKS + " tick 让第二个人进来 —— "
                + "有人进来就是正常 1v1，人机不上场）",
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " debug mgc")
                + " —— 查看 MGC 注册与房间状态（排障首选）", NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " debug room reset")
                + " —— 把本游戏的房间复位为 READY", NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " debug reload")
                + " —— 热重载配置", NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " debug profile show <uuid>")
                + " —— 查某个 uuid 的局外进度（跨重启验证）", NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label
                + " debug profile grant <uuid> <gold> [dust]")
                + " —— 加金币/尘并立即落盘", NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label
                + " debug profile setowned <uuid> <卡id,卡id,...>")
                + " —— 替换「已拥有」卡列表并落盘", NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" " + cmd("/" + label + " debug "
                + DebugSubcommands.usage()) + " —— R69 调试：全卡（含金色）/ 重置图鉴 / 设金币 /"
                + " 设烈焰粉 / infinite-xp（打牌不扣经验，作用于发令者当前那一场）",
                NamedTextColor.WHITE));
        sender.sendMessage(Component.text(" （shop / cards / codex 只能由玩家执行 —— 控制台没有背包；"
                + "控制台要开界面请用 MGC 的通用通道 " + cmd("/menu <界面id> <玩家名>") + "）",
                NamedTextColor.DARK_GRAY));
    }

    private static Component line(String key, String value) {
        return Component.text(" " + key + "：" + value, NamedTextColor.WHITE);
    }

    /** 把命令行包成醒目的可点样式（仅视觉，不改变行为）。 */
    private static String cmd(String text) {
        return text;
    }

    // ---------------- 补全 ----------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(ROOT_ARGS, args[0]);
        }
        // ★ 2026-10-15 裁定（docs/24 §5.5）：`/incl bot on|off` 是**玩家指令**
        //   ⇒ 它的补全必须放在**admin 闸门之前**，否则房间里没有 admin 的玩家
        //   补不出 on/off（"Tab 补不出来但敲得通"正是本项目点名过的那类分叉）。
        if (args.length == 2 && args[0].equalsIgnoreCase("bot")) {
            return filter(BotSubcommands.names(), args[1]);
        }
        if (!sender.hasPermission(PERM_ADMIN)) {
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return filter(DEBUG_ARGS, args[1]);
        }
        // ★ A2：/incl arena <TAB> ⇒ set | list | remove（与解析表同源：ArenaSubcommands.names()）
        if (args.length == 2 && args[0].equalsIgnoreCase("arena")) {
            return filter(ArenaSubcommands.names(), args[1]);
        }
        // ★ 2026-10-18：/incl arena set|remove <TAB> ⇒ **已有的竞技场名**（不再是点位键）。
        //   ★ 与查表同源：plugin.arenaCatalog().names()（不抄第二份清单）；
        //   ★ 插件为 null（离线夹具 / 未就绪）⇒ 补不出名字，但**不抛**。
        if (args.length == 3 && args[0].equalsIgnoreCase("arena")) {
            Optional<ArenaSubcommands.Action> action = ArenaSubcommands.actionFor(args[1]);
            if (action.isPresent() && action.get() != ArenaSubcommands.Action.LIST
                    && plugin != null) {
                return filter(plugin.arenaCatalog().names(), args[2]);
            }
            return List.of();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("debug")
                && args[1].equalsIgnoreCase("room")) {
            return filter(ROOM_ARGS, args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("debug")
                && args[1].equalsIgnoreCase("profile")) {
            return filter(PROFILE_ARGS, args[2]);
        }
        // ★ R69 那几条的第三个片段 = 目标玩家名（只有在线玩家能按名字查到 ⇒ 补全也只给在线玩家名）。
        //   ★ 枚举在线玩家那一步在 PlayerNameCompletions 里（本类被字节码守卫盯着，不许出现
        //     getOnlinePlayers —— 铁律 10）；本类只调用它，不自己遍历。
        if (args.length == 3 && args[0].equalsIgnoreCase("debug")
                && DebugSubcommands.subcommandFor(args[1]).isPresent()) {
            return PlayerNameCompletions.onlinePlayerNames(args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
