package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.match.MatchHooks;
import io.mczju.infernalclash.match.MatchOutcome;
import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.PhaseConfig;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.policy.MatchTimerPolicy;
import io.mczju.infernalclash.policy.XpBarPolicy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★ <b>对局内的战斗 HUD</b>（2026-10-17 第十二轮新增）—— 用户裁定 §A 第 4 条的落点。
 *
 * <h2>用户原话（逐字）</h2>
 * <blockquote>
 * 「Bossbar 显示对局时间，非加时显示一条匀速下降的 boss 条，加时时使用另一条加时 boss 条，
 * 从满的重新扣，一旦对局结束就封锁这个 boss 条不再下降；<b>侧边栏显示其他的战斗状态</b>。
 * 另外，现在的经验条显示也是有问题的，玩家大部分时间完全看不到自己的经验条，
 * 只有在费用（经验）不够时会更新一次经验条状态，然后经验条继续冻结，不显示实际经验」
 * </blockquote>
 *
 * <h2>★ 三件事，三个唯一落点</h2>
 * <ol>
 *   <li><b>BossBar = 对局时间</b> —— 判据全在纯类 {@link MatchTimerPolicy}
 *       （两条条：非加时 + 加时；结束冻结）。本类只负责「按算出来的状态去改两个 BossBar」；</li>
 *   <li><b>侧边栏 = 其他战斗状态</b> —— 行列表由纯函数
 *       {@link MatchTimerPolicy#sidebarLines} 生成（★ 状态集合的**文档依据**写在那个方法的
 *       javadoc 表里，一个字都不编）；</li>
 *   <li><b>经验条</b> —— 换算只有一处：纯函数 {@link XpBarPolicy#of(double, double)}。
 *       ★ 根因：整份源码里**从来没有**调用过 {@code setLevel/setExp}
 *       （{@code grep} 0 命中）⇒ 那一条是原版残留、永远不会跟着战局经验动。</li>
 * </ol>
 *
 * <h2>★★ 铁律 10 的四问（HUD 是"玩家可见播报"，最容易犯"发给全服"的错）</h2>
 * <ol>
 *   <li><b>范围界定</b>：收件人 = {@code MatchService#sides()} 里**本局名册**的 UUID
 *       （阵营 → 玩家）。用 {@code Bukkit.getPlayer(uuid)} 逐个解析
 *       ⇒ ★ <b>没有 {@code getOnlinePlayers()}、没有 {@code getNearbyEntities}</b>。</li>
 *   <li><b>失败方向</b>：某位玩家离线 / 名册为空 ⇒ <b>跳过那一个</b>，
 *       绝不"改成发给所有人"；名册为空时一个 BossBar 都不显示；
 *       {@code ScoreboardManager} 拿不到 ⇒ 跳过侧边栏并告警一次（boss 条照常）。</li>
 *   <li><b>归属判定</b>：靠本局名册的 UUID（**不靠**实体类型、不靠名字、不靠半径）。</li>
 *   <li><b>可对账</b>：创建 / 拆除 boss 条与侧边栏时各打一行<b>带数量</b>的日志。</li>
 * </ol>
 *
 * <h2>★ 对局结束必须**清理掉自己的** boss 条与侧边栏（不留悬挂）</h2>
 * 侧边栏用的是**本局自己 {@code newScoreboard()} 出来的独立记分板**
 * （不碰主计分板、不动别人的 team / objective —— 铁律 10 明令），
 * 收尾时把每位玩家的 {@code setScoreboard} 摘回主计分板并把 objective 注销
 * ⇒ 不留任何残留（{@code scoreboard.dat} 也不会被污染：独立记分板不落盘）。
 */
public final class MatchHud implements MatchHooks {

    /**
     * BossBar 的标题**回退常量**（★ 正常路径读 {@code display.yml} 的 {@code messages.match.hud-title}）。
     *
     * <p>★ 2026-10-17 第十三轮：用户裁定「**进**」（HUD 文案进 `display.yml`）⇒
     * 这三个常量从"唯一来源"降级为**回退值**（配置是唯一来源，常量只是兜底）。
     */
    public static final String TITLE_REGULAR = "对局时间";

    /** 加时条标题的回退常量（★ 用户要求「另一条加时 boss 条」，标题必须能一眼区分）。 */
    public static final String TITLE_OVERTIME = "加时";

    /** 侧边栏标题的回退常量。 */
    public static final String SIDEBAR_TITLE = "炒鸡战争";

    /** 文案键（★ `display.yml` 的 `messages.*`）。 */
    static final String KEY_HUD_TITLE = "match.hud-title";
    static final String KEY_HUD_OVERTIME = "match.hud-overtime";
    static final String KEY_HUD_SIDEBAR = "match.hud-sidebar";

    /**
     * 每 tick 刷新侧边栏太重（它会触发客户端计分板重建）；
     * ★ 但 boss 条**必须每 tick 更新**，否则「匀速下降」会变成每秒跳一次（用户点名要的）。
     * ⇒ 两者节拍分开：boss 条每 tick、侧边栏每 {@value #SIDEBAR_INTERVAL_TICKS} tick。
     */
    public static final int SIDEBAR_INTERVAL_TICKS = 10;

    private final Plugin plugin;
    private final String matchId;
    private final PhaseConfig config;
    private final Map<Side, UUID> players;
    private final Logger logger;

    /**
     * ★ <b>塔数来源</b>（{@code [红, 蓝]}）—— 由调用方注入（目标物系统持有它）。
     *
     * <p>为什么用 {@link java.util.function.Supplier} 而不是构造时取一份快照：
     * 塔数每局都在变，快照会在掉塔之后仍显示旧值（本项目 R44 那条「Supplier 而不是快照」
     * 的同一口径，见 {@code PlayerDamageGuard} 的 displaySource）。
     * ★ 拿不到（未注入 / 抛异常）⇒ 返回 {@code [0,0]} 并**只在第一次**告警 ——
     * 绝不因为"读不到塔数"就不显示侧边栏的其余项。
     */
    private final java.util.function.Supplier<int[]> towerCounts;

    /** ★ 目标物血量来源（侧边栏用）；允许 {@code null} ⇒ 显示 {@code ——} 并告警一次。 */
    private final ObjectiveHealthSource healthSource;

    /** 血量来源拿不到时只告警一次（侧边栏每 10 tick 刷一次，否则会刷屏）。 */
    private boolean healthWarned;

    private final BossBar regularBar;
    private final BossBar overtimeBar;

    /**
     * ★ 两条 boss 条的**标题基座**（已解析成 Component）—— 2026-10-18 新增。
     *
     * <p>为什么要把它们记下来：{@link #titleOf} 每 tick 都要重设标题（拼上剩余时间），
     * 而旧实现是「常量 + 时间」重新拼一个 String ⇒ <b>配置里的标题第一 tick 就被常量化了</b>
     * （`display.yml` 的 `match.hud-title` 形同虚设）。现在改成「解析好的 Component +
     * 时间后缀」⇒ 配置生效、且 `<gray>` / `<red>` 这些标签不会再字面显示。
     */
    private final Component regularTitle;
    private final Component overtimeTitle;

    private Scoreboard sidebar;
    private Objective sidebarObjective;
    private final List<MatchTimerPolicy.SidebarLine> sidebarEntries = new ArrayList<>();

    private int tickCounter;
    private boolean wasEnded;
    private boolean stopped;
    private boolean announced;

    /**
     * ★★ <b>侧边栏的数据来源</b>（2026-10-17 第十三轮新增，用户裁定 E19 结案）。
     *
     * <p>用户要侧边栏显示「玩家名称 / 塔1血量 / 塔2血量 / 信标血量」⇒ 光有"还剩几座塔"不够，
     * 需要**每个目标物的当前血量**（含上限）。这个接口就是那一层的**唯一注入点**：
     * {@code ObjectiveService} 实现它（{@code healthOf} 只读我们自己的目标物列表）。
     *
     * <p>★ 为什么用接口而不是 {@code Supplier<double[]>}：四个返回值（红/蓝 × 塔/信标）
     * 里塔还是**数组**（两座）—— 用 Supplier 拼会变成"位置约定"（谁在 0 号位？），
     * 而位置约定正是本项目最讨厌的那种隐性契约。
     *
     * <p>★ 允许 {@code null}（离线夹具 / 未注入）⇒ {@link MatchHud} 显示 {@code ——} 并告警一次。
     */
    public interface ObjectiveHealthSource {

        /** 某一方**信标**的 {@code {当前, 上限}}；没有 ⇒ {@code null}。 */
        double[] beaconOf(Side side);

        /** 某一方**两座塔**的 {@code {当前, 上限}}（按**左、右**顺序）；没有 ⇒ 空数组。 */
        java.util.List<double[]> towersOf(Side side);
    }

    /**
     * @param players     本局名册（阵营 → 玩家 UUID）；★ **这就是收件人范围**，不是在线玩家列表
     * @param towerCounts 塔数来源（{@code [红, 蓝]}，实时读）；允许 {@code null} ⇒ 显示 0 并告警一次
     */
    public MatchHud(Plugin plugin, String matchId, PhaseConfig config, Map<Side, UUID> players,
                    java.util.function.Supplier<int[]> towerCounts) {
        this(plugin, matchId, config, players, towerCounts, null);
    }

    /**
     * @param healthSource 目标物血量来源（侧边栏的「塔1 / 塔2 / 信标」要用）；允许 {@code null}
     */
    public MatchHud(Plugin plugin, String matchId, PhaseConfig config, Map<Side, UUID> players,
                    java.util.function.Supplier<int[]> towerCounts, ObjectiveHealthSource healthSource) {
        this.plugin = plugin;
        this.matchId = matchId;
        this.config = config;
        this.players = players == null ? Map.of() : new java.util.LinkedHashMap<>(players);
        this.towerCounts = towerCounts;
        this.healthSource = healthSource;
        this.logger = plugin.getLogger();
        // ★★ 标题读 display.yml（用户第十三轮裁定「进」）；取不到 ⇒ 回退到下面三个常量 + 告警一次。
        //   ★★★ 2026-10-18 修：这些文案里**带 MiniMessage 标签**（出厂值是 `<gray>对局时间` /
        //   `<red>加时` / `<gold>炒鸡战争`），而 `Bukkit.createBossBar(String)` 吃的是
        //   **legacy（§）字符串** ⇒ 直接把 MiniMessage 原文塞进去，玩家看到的是
        //   **字面的 `<gold>炒鸡战争`**（用户截图里那一条就是这个）。
        //   ⇒ 现在统一「MiniMessage 解析 → legacy 序列化」再交给 boss 条 / 记分板。
        this.regularTitle = hudComponent(KEY_HUD_TITLE, TITLE_REGULAR, NamedTextColor.GRAY);
        this.overtimeTitle = hudComponent(KEY_HUD_OVERTIME, TITLE_OVERTIME, NamedTextColor.RED);
        this.regularBar = Bukkit.createBossBar(
                legacy(regularTitle), BarColor.BLUE, BarStyle.SOLID);
        this.overtimeBar = Bukkit.createBossBar(
                legacy(overtimeTitle), BarColor.RED, BarStyle.SOLID);
        applyRecipients("创建");
    }

    /** 兼容构造（不注入塔数来源）：侧边栏的塔数显示 0 并告警一次。 */
    public MatchHud(Plugin plugin, String matchId, PhaseConfig config, Map<Side, UUID> players) {
        this(plugin, matchId, config, players, null, null);
    }

    // ------------------------------------------------------------------ MatchHooks

    @Override
    public void onTick(MatchPhase phase, int battleTick) {
        if (stopped) {
            return;
        }
        if (phase == MatchPhase.BATTLE || phase == MatchPhase.OVERTIME) {
            // ★ 「真的打过」的阳性证据（见 MatchTimerPolicy#of 的 wasEnded 说明）
            wasEnded = true;
        }
        int phaseTick = currentPhaseTick(phase);
        MatchTimerPolicy.TimerState state = MatchTimerPolicy.of(phase, phaseTick, config, wasEnded);
        applyBar(regularBar, state.regular());
        applyBar(overtimeBar, state.overtime());

        // 经验条：★ 必须**每 tick** 跟随真实经验（否则又是"冻结"）
        syncXpBars();

        if (++tickCounter % SIDEBAR_INTERVAL_TICKS == 0) {
            renderSidebar(phase, phaseTick, battleTick);
        }
        if (!announced && !players.isEmpty()) {
            announced = true;
            logger.info("[IC] 战斗 HUD 已建立：boss 条 2 条（对局时间 / 加时）＋ 侧边栏 1 块"
                    + "＋ 经验条跟随（每 tick）｜收件人 " + players.size() + " 名"
                    + "（只发给本局名册的 UUID，逐个按名册取；没有任何「全服玩家列表」式的查询）"
                    + "｜matchId=" + matchId);
        }
    }

    @Override
    public void onStop(String reason) {
        if (stopped) {
            return;
        }
        stopped = true;
        int removed = 0;
        for (UUID id : players.values()) {
            Player player = playerOf(id);
            if (player == null) {
                continue;
            }
            regularBar.removePlayer(player);
            overtimeBar.removePlayer(player);
            detachSidebar(player);
            // ★ 经验条归零（而不是"还原成进场前的值"—— 我们没记过那个值，见 docs/05 E20）
            player.setLevel(0);
            player.setExp(0.0f);
            player.setTotalExperience(0);
            removed++;
        }
        regularBar.removeAll();
        overtimeBar.removeAll();
        if (sidebarObjective != null) {
            try {
                sidebarObjective.unregister();
            } catch (Throwable ignored) {
                // 记分板可能已被别的插件/原版回收 ⇒ 收尾不因此中断
            }
        }
        sidebarObjective = null;
        sidebar = null;
        sidebarEntries.clear();
        logger.info("[IC] 战斗 HUD 已拆除（" + reason + "）：boss 条 2 条 + 侧边栏 1 块 + 经验条归零"
                + "｜处理玩家 " + removed + " 名｜matchId=" + matchId
                + "｜侧边栏用的是本局独立的记分板与 objective，"
                + "不碰主计分板、不动别人的队伍（铁律 10）");
    }

    /**
     * 侧边栏有没有真的建起来（排障 / 测试用）。
     *
     * <p>★ 为什么这个方法存在：{@code MatchHudWiringTest} 用**方法级字节码**断言
     * 「{@code writeLines} 会走到 {@code ensureSidebar}」这类接线，需要一个稳定的公开锚点。
     */
    public boolean sidebarReady() {
        return sidebarObjective != null;
    }

    // ------------------------------------------------------------------ 内部：boss 条

    private void applyBar(BossBar bar, MatchTimerPolicy.BarState state) {
        if (!state.visible()) {
            // ★ 用户要求「结束就封锁、不再下降」⇒ 结束那一格是 visible=true；
            //   走到这里说明"这条条跟当前阶段无关"（例如准备阶段的加时条）⇒ 移除悬挂
            if (!bar.getPlayers().isEmpty()) {
                bar.removeAll();
            }
            return;
        }
        bar.setProgress(state.progress());
        bar.setTitle(titleOf(bar, state));
        if (bar.getPlayers().size() != recipients().size()) {
            applyRecipients("补齐");
        }
    }

    private String titleOf(BossBar bar, MatchTimerPolicy.BarState state) {
        Component base = bar == overtimeBar ? overtimeTitle : regularTitle;
        // ★★ 2026-10-18：标题 = 配置里那一段（Component）＋ 剩余时间 ＋ 百分比。
        //   旧实现用**常量**重新拼，等于每 tick 把 display.yml 覆盖掉一次。
        Component suffix = Component.text("　" + MatchTimerPolicy.formatTicks(state.remainingTicks())
                + "（" + state.percent() + "%）");
        return legacy(base.append(suffix));
    }

    /**
     * ★★ <b>MiniMessage 文案 → 普通 String（legacy {@code §} 形式）</b>（2026-10-18 新增）。
     *
     * <p>为什么需要它：{@code Bukkit.createBossBar(String)} / {@code BossBar#setTitle(String)}
     * 吃的是<b>legacy 颜色码</b>，而 `display.yml` 里的文案是 <b>MiniMessage</b>
     * ⇒ 直接把原文塞进去，玩家看到的是**字面的 `<gold>炒鸡战争`**
     * （用户 2026-10-18 那张截图里侧边栏标题就是这个）。
     * <p>★ 失败方向：序列化抛异常 ⇒ 回退成<b>纯文本</b>（去掉标签），绝不把异常抛给玩家、
     * 也绝不因此让 HUD 建不起来。
     */
    private static String legacy(Component component) {
        try {
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacySection().serialize(component);
        } catch (Throwable t) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(component);
            return plain;
        }
    }

    private void applyRecipients(String trigger) {
        List<Player> online = recipients();
        regularBar.removeAll();
        overtimeBar.removeAll();
        for (Player player : online) {
            regularBar.addPlayer(player);
            overtimeBar.addPlayer(player);
        }
        if (!online.isEmpty() && !"补齐".equals(trigger)) {
            logger.info("[IC] 战斗 HUD 的 boss 条收件人已" + trigger + "：" + online.size() + " 名"
                    + "（本局名册 " + players.size() + "，离线/未就绪的跳过）｜matchId=" + matchId);
        }
    }

    /** 本局名册里**此刻在线**的玩家（★ 逐个按 UUID 取，不查在线列表）。 */
    private List<Player> recipients() {
        List<Player> online = new ArrayList<>();
        for (UUID id : players.values()) {
            Player player = playerOf(id);
            if (player != null) {
                online.add(player);
            }
        }
        return online;
    }

    // ------------------------------------------------------------------ 内部：侧边栏

    private void renderSidebar(MatchPhase phase, int phaseTick, int battleTick) {
        MatchService match = MatchService.get(matchId);
        if (match == null) {
            return;   // 对局已不在索引里（收尾中）⇒ 什么都不做
        }
        for (Map.Entry<Side, UUID> entry : players.entrySet()) {
            Player player = playerOf(entry.getValue());
            if (player == null) {
                continue;
            }
            ensureSidebar(player);
            if (sidebarObjective == null) {
                return;   // 记分板服务不可用（已告警）
            }
            MatchTimerPolicy.SidebarSource source = new MatchTimerPolicy.SidebarSource(
                    phase, battleTick,
                    Map.of(Side.RED, towersHealth(Side.RED), Side.BLUE, towersHealth(Side.BLUE)),
                    Map.of(Side.RED, beaconHealthOf(Side.RED), Side.BLUE, beaconHealthOf(Side.BLUE)),
                    playerNames(),
                    match.xp(entry.getKey()), entry.getKey(), config,
                    playerOutcome(match, phase));
            writeLines(player, MatchTimerPolicy.sidebarLines(source));
        }
    }

    /**
     * ★ <b>这场对局里双方玩家的名字</b>（阵营 → 名字）—— 用户要求侧边栏第一段就写「玩家名称」。
     *
     * <p>★ 名字从 {@code MatchService} 的**本局名册**取（{@code SideState#name()}），
     * 不查在线玩家列表（铁律 10）。★ 人机那一侧在名册里也有名字
     * （{@code TrainingBotAssembly} 派生的那个），所以两行都会有人名。
     */
    private Map<Side, String> playerNames() {
        Map<Side, String> names = new java.util.LinkedHashMap<>();
        MatchService match = MatchService.get(matchId);
        if (match == null) {
            return names;
        }
        for (Side side : Side.values()) {
            MatchService.SideState state = match.side(side);
            if (state != null && state.name() != null) {
                names.put(side, state.name());
            }
        }
        return names;
    }

    /** 某一方两座塔的血量（按左、右）；来源不可用 ⇒ 空数组（侧边栏显示 {@code ——}）。 */
    private java.util.List<double[]> towersHealth(Side side) {
        if (healthSource == null) {
            warnNoHealth();
            return java.util.List.of();
        }
        try {
            java.util.List<double[]> towers = healthSource.towersOf(side);
            return towers == null ? java.util.List.of() : towers;
        } catch (Throwable t) {
            warnNoHealth();
            return java.util.List.of();
        }
    }

    /** 某一方信标的血量；来源不可用 ⇒ {@code null}（侧边栏显示 {@code ——}）。 */
    private double[] beaconHealthOf(Side side) {
        if (healthSource == null) {
            warnNoHealth();
            return null;
        }
        try {
            return healthSource.beaconOf(side);
        } catch (Throwable t) {
            warnNoHealth();
            return null;
        }
    }

    /** 「血量来源不可用」只告警一次（失败方向：显示 {@code ——}，绝不谎报满血）。 */
    private void warnNoHealth() {
        if (!healthWarned) {
            healthWarned = true;
            logger.warning("[IC] 侧边栏拿不到目标物血量（血量来源未注入或读失败），"
                    + " 塔/信标那两段显示「——」，其余项照常｜matchId=" + matchId);
        }
    }

    /**
     * ★ <b>塔数来源</b>（{@code [红, 蓝]}）—— ★ 2026-10-17 第十三轮起**只用于"来源是否可用"的自检**：
     * 侧边栏已经改成显示**具体血量**（见 {@link ObjectiveHealthSource}），不再用"还剩几座"。
     *
     * <p>保留这个字段与它的告警是为了**兼容旧调用点**（`MatchHudWiringTest` 等仍按旧签名构造），
     * 而且「塔数来源没注入」仍然是一个值得说的接线问题。
     */
    private MatchOutcome playerOutcome(MatchService match, MatchPhase phase) {
        return phase == MatchPhase.ENDED ? match.outcome() : MatchOutcome.NONE;
    }

    /**
     * ★★ <b>HUD 文案 → Component</b>（MiniMessage 解析 ＋ 无色时补缺省色）—— 2026-10-18 新增。
     *
     * <p>★ 走 {@link MenuTexts#component}（项目唯一的"文案 → 组件"入口）：
     * 占位 / 缺键 / 语法坏 ⇒ 回退常量并按**键**告警一次，绝不把 `[待填]` 或
     * 一个字面的 `<gold>` 印到玩家眼前。
     *
     * <p>★ 2026-10-18 之前这里还有一个只取 String 的 `hudText(...)`，它被
     * `Component.text(hudText(...), NamedTextColor.GOLD)` 这种写法拿去用 ——
     * 而 `Component.text` **不解析 MiniMessage** ⇒ 侧边栏标题字面显示成 `<gold>炒鸡战争`。
     * 那个方法已随修复一起删除（留着它就是给同一个错误留入口）。
     */
    private Component hudComponent(String key, String fallback,
                                   net.kyori.adventure.text.format.TextColor colorIfAbsent) {
        io.mczju.infernalclash.InfernalClashPlugin instance =
                io.mczju.infernalclash.InfernalClashPlugin.getInstance();
        return MenuTexts.component(instance == null ? null : instance.display(), key, fallback,
                colorIfAbsent, message -> logger.warning("[IC] HUD 文案：" + message));
    }

    /** 建（或复用）本局的独立记分板与侧边栏 objective。 */
    private void ensureSidebar(Player player) {
        if (sidebarObjective != null) {
            if (player.getScoreboard() != sidebar) {
                player.setScoreboard(sidebar);
            }
            return;
        }
        try {
            sidebar = Bukkit.getScoreboardManager().getNewScoreboard();
            // ★★★ 2026-10-18 修「标题字面显示 `<gold>炒鸡战争`」：
            //   旧实现是 `Component.text(hudText(...), NamedTextColor.GOLD)` —— `Component.text`
            //   **不解析 MiniMessage**（这是 Adventure 的硬事实），而 display.yml 里那条文案
            //   出厂值就是 `<gold>炒鸡战争` ⇒ 玩家看到的正是带尖括号的原文（用户截图里那一条）。
            //   ⇒ 现在走 `MenuTexts#component`（MiniMessage 解析 ＋ 无色时补金），
            //     并且**先试**是"配置里带了颜色"还是"没带" ⇒ 带了就以配置为准。
            sidebarObjective = sidebar.registerNewObjective(
                    "incl_hud", Criteria.DUMMY,
                    hudComponent(KEY_HUD_SIDEBAR, SIDEBAR_TITLE, NamedTextColor.GOLD));
            sidebarObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
            player.setScoreboard(sidebar);
            logger.info("[IC] 侧边栏已建立（本局独立的 Scoreboard，不碰主计分板）｜matchId=" + matchId);
        } catch (Throwable t) {
            sidebar = null;
            sidebarObjective = null;
            logger.warning("[IC] 侧边栏建立失败（" + t + "），本局只有 boss 条与经验条，"
                    + "绝不退化成「改主计分板」｜matchId=" + matchId);
        }
    }

    /**
     * 把行列表写进侧边栏。
     *
     * <h2>★ 为什么每行用一个「隐性 team 前缀」而不是直接 {@code getScore(行).setScore(n)}</h2>
     * 同一个字符串在同一个 objective 里只能有一条记录。行文本会随剩余时间变化
     * （「剩余：1:23」→「剩余：1:22」），直接当条目名会在切换的那一瞬出现**新旧两行**
     * （经典闪烁）。用「固定条目名 = 行号 + team 前缀 = 真文本」就没这个问题：
     * 条目名恒定，只有前缀在变。
     */
    private void writeLines(Player player, List<MatchTimerPolicy.SidebarLine> lines) {
        if (sidebarObjective == null || sidebar == null) {
            return;
        }
        int size = Math.min(lines.size(), MAX_LINES);
        for (int index = 0; index < size; index++) {
            MatchTimerPolicy.SidebarLine line = lines.get(index);
            String entry = entryName(index);
            String teamName = "incl_line_" + index;
            org.bukkit.scoreboard.Team team = sidebar.getTeam(teamName);
            if (team == null) {
                team = sidebar.registerNewTeam(teamName);
                team.addEntry(entry);
            }
            // ★★★ 2026-10-18（用户第 7 条）：每一行带自己的**色调**
            //   ⇒ 塔血按阵营红/蓝、信标深红/深蓝、名字金色。
            //   ★ 这里必须用 `Component.text(text, color)`（带颜色的组件），
            //     **不是** `Component.text(text)` —— 后者是"无色"，会把上面所有判断吞掉。
            team.prefix(Component.text(line.text(), colorOf(line.tone())));
            // 分数 = 从上到下的顺序（分数高的在上面）
            sidebarObjective.getScore(entry).setScore(size - index);
        }
        // 行数变少时把多余的条目分数清掉（避免残留旧行）
        for (int index = size; index < sidebarEntries.size(); index++) {
            sidebar.resetScores(entryName(index));
        }
        sidebarEntries.clear();
        sidebarEntries.addAll(lines.subList(0, size));
    }

    /**
     * ★ <b>色调 → Adventure 颜色</b>（唯一映射）。
     *
     * <p>★ 为什么映射放在渲染层而不是 {@code policy}：那个包刻意不依赖 Adventure
     * （它只放判据）⇒ 颜色这种"怎么画"的东西归 UI 侧。用户在 2026-10-18 逐字定过：
     * 塔 = 阵营色（红/蓝）、信标 = **更深**的阵营色（深红/深蓝）、名字 = 金色。
     *
     * @return 颜色；{@code PLAIN} / 未知 ⇒ {@code null}（= 用客户端默认前景色）
     */
    private static NamedTextColor colorOf(MatchTimerPolicy.SidebarLine.Tone tone) {
        if (tone == null) {
            return null;
        }
        return switch (tone) {
            case GOLD -> NamedTextColor.GOLD;
            case RED -> NamedTextColor.RED;
            case BLUE -> NamedTextColor.BLUE;
            case DARK_RED -> NamedTextColor.DARK_RED;
            case DARK_BLUE -> NamedTextColor.DARK_BLUE;
            case PLAIN -> null;
        };
    }

    private static String entryName(int index) {
        return "§" + (char) ('0' + index);   // 固定不变的条目名（颜色码字符不会显示）
    }

    /** 原版侧边栏最多 15 行。 */
    public static final int MAX_LINES = 15;

    private void detachSidebar(Player player) {
        try {
            if (sidebar != null && player.getScoreboard() == sidebar) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        } catch (Throwable ignored) {
            // 拿不到主计分板 ⇒ 不动它（绝不自建第二块）
        }
    }

    // ------------------------------------------------------------------ 内部：经验条

    /**
     * ★★ <b>经验条的唯一同步点</b>。
     *
     * <p>根因见类注释：整份源码里**从来没有**同步过经验条 ⇒ 玩家看的那一条是原版残留。
     * 换算走纯函数 {@link XpBarPolicy}（唯一一处口径），本方法只负责写实体。
     */
    private void syncXpBars() {
        MatchService match = MatchService.get(matchId);
        if (match == null) {
            return;
        }
        for (Map.Entry<Side, UUID> entry : players.entrySet()) {
            Player player = playerOf(entry.getValue());
            if (player == null) {
                continue;
            }
            XpBarPolicy.Bar bar = XpBarPolicy.of(match.xp(entry.getKey()), config.maxLevel());
            if (player.getLevel() != bar.level()) {
                player.setLevel(bar.level());
            }
            if (Math.abs(player.getExp() - bar.progressAsFloat()) > 0.001f) {
                player.setExp(bar.progressAsFloat());
            }
            // ★ 原版总经验（影响附魔台与经验条右侧数字）也一起对齐，
            //   否则会出现「条是满的、数字是 0」这种自相矛盾的显示
            int total = bar.level() * 10 + (int) Math.round(bar.progress() * 10.0);
            if (player.getTotalExperience() != total) {
                player.setTotalExperience(total);
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private int currentPhaseTick(MatchPhase phase) {
        MatchService match = MatchService.get(matchId);
        return match == null ? 0 : match.phaseTick();
    }

    private Player playerOf(UUID id) {
        if (id == null) {
            return null;
        }
        Player player = Bukkit.getPlayer(id);
        return player != null && player.isOnline() ? player : null;
    }
}
