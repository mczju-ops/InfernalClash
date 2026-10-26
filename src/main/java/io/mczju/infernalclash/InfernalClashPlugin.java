package io.mczju.infernalclash;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.game.room.AbstractGameRoom;
import com.github.mczjuops.mczjugamecore.game.room.GameRoomState;
import io.mczju.infernalclash.arena.ArenaCatalog;
import io.mczju.infernalclash.arena.ArenaConfig;
import io.mczju.infernalclash.arena.ArenaPool;
import io.mczju.infernalclash.battle.PlayerDamageGuard;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.card.CardItems;
import io.mczju.infernalclash.card.MobGroupCatalog;
import io.mczju.infernalclash.card.SummonSpawner;
import io.mczju.infernalclash.command.InfernalClashCommand;
import io.mczju.infernalclash.config.CombatConfig;
import io.mczju.infernalclash.config.ConfigError;
import io.mczju.infernalclash.config.ConfigMigrator;
import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.config.EconomyConfig;
import io.mczju.infernalclash.config.GameConfig;
import io.mczju.infernalclash.config.MatchConfig;
import io.mczju.infernalclash.config.ObjectiveConfig;
import io.mczju.infernalclash.config.TrainingConfig;
import io.mczju.infernalclash.config.YamlFile;
import io.mczju.infernalclash.game.CardCastListener;
import io.mczju.infernalclash.game.InfernalClashGame;
import io.mczju.infernalclash.game.InfernalClashRoom;
import io.mczju.infernalclash.policy.MatchPayout;
import io.mczju.infernalclash.ui.MenuTexts;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.objective.ObjectiveProtectionListener;
import io.mczju.infernalclash.policy.MatchPayout;
import io.mczju.infernalclash.policy.PackRoller;
import io.mczju.infernalclash.profile.InfernalPlayerData;
import io.mczju.infernalclash.profile.PlayerProgress;
import io.mczju.infernalclash.profile.ProfileStore;
import io.mczju.infernalclash.profile.R44BasicGrants;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * InfernalClash 主类（MCZJUGameCore 子插件）。
 *
 * <h2>当前阶段：项目底座</h2>
 * <ul>
 *   <li>构建链路（Paper 26.2 + MGC 1.1.2 + JDK 25 + JUnit）</li>
 *   <li>严格配置层（缺键即报错，报出「文件 + 键路径」并**禁用插件**）</li>
 *   <li>MGC 接入骨架（对局 / 房间 / 玩家档案 / 等待·退出·死亡策略）</li>
 *   <li>离线单测 + 广域调用字节码守卫</li>
 * </ul>
 * <b>玩法尚未定稿</b> —— 本类不包含任何玩法逻辑，也不预先拍板任何数值。
 *
 * <p>配置校验失败会**明确报出「文件 + 键路径」并禁用插件**，
 * 而不是带着坏配置继续运行（这是相对 Maggoteers「缺键静默读成 0.0」的改进）。
 */
public final class InfernalClashPlugin extends JavaPlugin {

    private static InfernalClashPlugin instance;

    /** 已校验的主配置；{@code onEnable} 校验失败时保持为 null（插件随即被禁用）。 */
    private GameConfig gameConfig;

    /** 已校验的经济配置（{@code economy.yml}）。 */
    private EconomyConfig economyConfig;

    /** 已校验的召唤物实体组目录（{@code mob-groups.yml}）。 */
    private MobGroupCatalog mobGroups;

    /** 已校验的卡牌目录（{@code cards/*.yml}，51 张）。 */
    private CardCatalog cardCatalog;

    /** 由「经济配置 + 卡池」组装出的抽包器 —— **它构造成功就说明卡池与概率表自洽**。 */
    private PackRoller packRoller;

    /** 显示文本配置（{@code display.yml}，R76：所有面向玩家的文案都在这里）。 */
    private DisplayConfig display;

    /** 对局时间轴配置（{@code config.yml} 的 {@code phases} 段）。 */
    private MatchConfig matchConfig;

    /** 战斗配置（{@code config.yml} 的 {@code combat} 段，P5：目标刷新间隔）。 */
    private CombatConfig combatConfig;

    /** 目标物配置（{@code config.yml} 的 {@code objectives} 段，P3）。 */
    private ObjectiveConfig objectiveConfig;

    /**
     * ★ <b>训练人机配置</b>（{@code config.yml} 的 {@code training} 段）。
     *
     * <p>2026-10-15 用户裁定 <b>G12</b>（单卡组）→ ★★ <b>2026-10-21 用户裁定（多套人机）</b>：
     * 「每个人机的卡组和人机的名字都可以在 config.yml 里配置」＋「人机现在需要能够出法术牌」。
     * ⇒ {@code training.bots}（每项 = 名字 ＋ 8 张卡，每局随机抽一个）＋
     * {@code training.spell-rules}（每张法术卡一条全局规则）。
     * 装配点在 {@code game/InfernalClashGame#trainingBotHand()}；
     * 权威规格 = {@code docs/31}。
     */
    private TrainingConfig trainingConfig;

    /**
     * 竞技场点位（{@code arenas.yml}）—— **数据文件**，由 {@code /incl arena} 采集。
     *
     * <p>★ 没有它也能启动（空表）：只是**开不了有目标物的对局**（开局时明确告警）。
     */
    private ArenaConfig arenaConfig;

    /**
     * ★★★ <b>多竞技场目录</b>（{@code arenas.yml}）—— 2026-10-18 新增。
     *
     * <p>{@link #arenaConfig} 仍然是「<b>对局实际用的那一份</b>」= 默认竞技场的点位表：
     * 它的 11 个消费点（目标物生成、半场判定、遗留清理…）<b>一行都没改</b>。
     * 多出来的这一层只回答「文件里现在有哪几个竞技场」。
     */
    private ArenaCatalog arenaCatalog;

    /** {@code config.yml} 的 {@code arena.default} 原文（可为 {@code null} = 没写）。 */
    private String configuredDefaultArena;

    /** 当前生效的默认竞技场名（{@code null} = 一个竞技场都没有）。 */
    private String defaultArenaName;

    /**
     * ★★★ <b>竞技场占用池</b>（2026-10-18 新增；用户：「**我需要让房间开启时随机选择一个空的
     * 竞技场（可参考 ig）**」）。
     *
     * <p>全局一个（与 {@code unitRegistry} 同级）：对局开始时 {@code acquire} 占一个空的、
     * 结束时 {@code release} 还回去。★ 纯类 {@link ArenaPool}（离线可测）。
     */
    private final ArenaPool arenaPool = new ArenaPool();

    /** 竞技场占用池（对局开局 / 收尾用；探针也可读）。 */
    public ArenaPool arenaPool() {
        return arenaPool;
    }

    /**
     * ★ <b>可以作为「开一局」候选的竞技场名</b>（{@code isReadyForMatch()} 为真的那些，保序）。
     *
     * <p>★ 只有 12 个必需点位齐全的竞技场才算候选：点位不全的场子里，
     * 目标物生成不了、召唤卡全被拒 —— 那种"开了但打不了"的局比"开不了"更糟。
     */
    public java.util.List<String> playableArenaNames() {
        ArenaCatalog catalog = arenaCatalog();
        java.util.List<String> playable = new java.util.ArrayList<>();
        for (String name : catalog.names()) {
            ArenaConfig arena = catalog.arena(name);
            if (arena != null && arena.isReadyForMatch()) {
                playable.add(name);
            }
        }
        return java.util.List.copyOf(playable);
    }

    /**
     * ★ <b>全局唯一的实体归属登记表</b>（铁律 10）。
     *
     * <p>为什么是**全局一个**而不是每场一个：PDC 键要在插件维度创建（`NamespacedKey`），
     * 且「清场」只可能由持表的一方发起；每场一个实例反而会出现「谁来清另一场」的问题。
     * 账本本身按 `matchId` 分区（见 {@code OwnershipTable}）。
     */
    private UnitRegistry unitRegistry;

    /** 卡牌物品的构造与识别（P4；卡牌身份在 PDC，因为所有卡牌是同一种物品）。 */
    private CardItems cardItems;

    /** 召唤卡的落地（P4：按卡牌的相对排布生成 + 立刻登记归属）。 */
    private SummonSpawner summonSpawner;

    /** 法术施放管线（P6：目标 → 形状 → 效果）。 */
    private io.mczju.infernalclash.spell.SpellCaster spellCaster;

    /** 能力运行时（P6b：声明式 `abilities` 的触发器执行）。 */
    private io.mczju.infernalclash.ability.AbilityRuntime abilityRuntime;

    /** 驱动所有活跃对局的**单个**全局任务（见 {@code MatchService} 的时钟说明）。 */
    private BukkitTask matchTicker;

    /**
     * ★ 局外成长的读写门面（**唯一**碰 MGC {@code playerData} 的地方）。
     *
     * <p>它在 {@code onEnable}（配置校验通过之后、注册 MGC 之前）建好；
     * 构造它不碰 MGC，所以顺序无所谓 —— 真正需要顺序的是
     * {@link #registerWithMgc()} 里的「**先注册、后加载**」。
     */
    private ProfileStore profileStore;

    @Override
    public void onEnable() {
        instance = this;

        saveShippedFiles();
        if (!loadConfigs()) {
            return;   // loadConfigs 内部已经 disablePlugin
        }
        // ★ 档案门面：配置校验通过后才建（它只用 gameId + 日志器，不需要任何配置值）
        //   ★★ R44 接线（★ 顺序）：**必须**在 loadConfigs() 成功之后建 —— 见 newProfileStore()：
        //      它会把「基础档卡 id 的来源」接到卡池上，而卡池是 loadConfigs() → loadAllOrThrow()
        //      里建的（cardCatalog 字段）。又因为那根线是「每次取都重读 this.cardCatalog 字段」的
        //      Supplier（不是一份 CardCatalog 实例快照）⇒ 就算有人把这两行的先后调换，
        //      结果也只是「空清单 ⇒ 跳过发放 + 告警」，不会变成「持有 null ⇒ 运行期静默失效」
        //      （P4 踩过的那个坑：编译不报错、召唤物全部漏登记）。
        this.profileStore = newProfileStore();
        logSummary();

        registerCommand();
        startMatchTicker();
        // ★★ 2026-10-20（用户：「abort 后重启，上局实体一直残留清不掉」）：
        //   清场只认内存账本 ⇒ 中途退出/关服时丢掉的 UUID 重启后**没人认得**。
        //   ⇒ 自动兜底：启动后跑一次 + 无对局时每 60 秒重试（玩家走近把区块加载起来后也能清到）。
        startLegacyPurgeSweep();

        // ★ 目标物保护：拦住本插件实体被原版机制变形（村民僵尸化 ⇒ 信标会自己跑掉）
        getServer().getPluginManager().registerEvents(
                new ObjectiveProtectionListener(unitRegistry, getLogger()), this);

        // ★ U1 竞技场编辑界面（A2/U1）：点位的采集与写回由它承担
        //   ⚠️ 它**不**走 MenuRegistry（MGC 菜单注册表）：它的选型是原版 27 格 Inventory +
        //   InventoryHolder（IG 的选型，理由见 ui/ArenaEditMenu 的类注释）⇒ 这里单独注册监听器。
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalclash.ui.ArenaEditListener(this), this);

        // ★ 卡牌施放入口（P4）：右键扣费 + 冷却 + 召唤落点
        getServer().getPluginManager().registerEvents(new CardCastListener(this), this);

        // ★ 战斗（P5）：玩家**不得**攻击召唤物（C13/R42 的另一半）——
        //   目标物（塔 / 信标）不在此列，玩家必须能打它们
        // ★ 2026-10-15（A8 文案接线）：把显示配置作为 Supplier 传进去（`this::display`）
        //   —— 用 Supplier 而不是构造时取快照：`/incl debug reload` 会**重建** DisplayConfig，
        //   实时读才能让玩家立刻看到改过的措辞（R44 那条「Supplier 而不是快照」的同一口径）。
        getServer().getPluginManager().registerEvents(
                new PlayerDamageGuard(unitRegistry, getLogger(), this::display), this);

        // ★ 能力触发器（P6b）：受伤 / 攻击 / 死亡 ⇒ 只处理本场账本里的实体（铁律 10）
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalclash.ability.AbilityTriggerListener(
                        unitRegistry, abilityRuntime, getLogger()), this);

        // ★★★ 2026-10-17 第十轮（缺陷 P0-c）：本插件实体的**阳性死亡信号**。
        //   ——「对局结束后防御塔等东西没有被移除」与「莫名奇妙胜利了」两条现场缺陷
        //     都源于「区块卸载被当成死亡」。判据只能在死亡事件上取（见那个类的 javadoc）。
        //   ★★ 注册顺序有意排在 AbilityTriggerListener **之后**：两者都在 MONITOR，
        //     而亡语（ON_DEATH）能力要查账本；本监听器只打「已死亡」标记、**不注销账本**
        //     ⇒ 即便顺序被改也不会让亡语失效（这条注释是为了防止后人把它改回 forget）。
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalclash.match.UnitLifecycleListener(unitRegistry, cardCatalog, getLogger()),
                this);

        // ★★★ 2026-10-17 第十三轮（用户实机报的缺陷）：本插件实体的
        //   **掉落 / 拾取 / 爆炸**守卫 ——
        //   ① 「对局时召唤物仍有掉落物，这是需要拦截的」（它们穿着铁套铁剑，掉了就是白送装备）；
        //   ② 「苦力怕甚至可能直接爆炸」⇒ 拦下引信、改成「纯视觉爆炸 + 按账本只打敌方、
        //      不破方块、不推挤、绝不伤玩家」（用户裁定「禁掉推挤」）。
        //   ★ 判据全部只认**本局账本**（铁律 10）：别人的怪、服主的动物照常。
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalclash.battle.SummonLifecycleGuard(unitRegistry, getLogger()),
                this);

        // ★★★ 2026-10-17 第十三轮批次 3 收尾（用户当场裁定两条）：
        //   ① 「**不需要摘掉射箭 goal，只需要拦截射箭事件就行。需要它射箭时的走位**」
        //      ⇒ 冰术师（`summon.block-ranged: true`）的箭**根本不生成**，
        //        但它的拉弓动作与「保持距离」的走位**一字不动**；
        //   ② 「**女巫的溅射：每次投掷会对敌人造成影响，而对友方和玩家无效**」
        //      ⇒ 一个敌人都没打到就把整瓶作废，否则只把友方/玩家的强度清零。
        //   ★ 判据全在纯类 `policy/RangedAttackRule`（有逐条单测）；本监听器只做
        //     「读 PDC → 查卡池 → 调判据 → 写事件」。
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalclash.battle.RangedAttackInterceptor(
                        unitRegistry, cardCatalog, getLogger()), this);

        // ★★★ 2026-10-18（全卡审计的三条欠账）：召唤物「特性」执行层 ——
        //   ① 末影人免疫远程 / 禁止传送（docs/19 第 88 行）；
        //   ② 唤魔者召出的恼鬼**收养**（第 172 行「需要接管其召唤的恼鬼」）：
        //      原版恼鬼不在账本 ⇒ 清场漏、收尾对不上账（铁律 10）；
        //   ③ 尖牙不分阵营（同行的坑栏）⇒ 同阵营的召唤物不吃自家尖牙。
        //   ★ 判据全在纯类 `policy/SummonTraitRule`（离线逐条单测），本监听器只读 PDC + 写事件。
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalclash.battle.SummonTraitListener(unitRegistry, getLogger()), this);

        if (gameConfig.registerGame()) {
            registerWithMgc();
        } else {
            getLogger().info("config.yml 的 mgc.register-game = false → 不注册到 MGC 大厅"
                    + "（/mgc join 选不到本游戏；/incl 管理指令仍然可用）");
        }

        // ★ 界面注册（P8）：把 U2/U4/U5 三个界面登记进 MGC 的 MenuFacade（重复 id 会抛 ⇒ 装配期只调一次）
        io.mczju.infernalclash.ui.MenuRegistry.register(this);

        // ★ 排行榜注册（P9）：★ 内部 catch (Throwable) ⇒ 注册失败只告警，
        //   绝不让 onEnable 抛出去（「榜单挂了」= 少一个榜；「onEnable 抛了」= 服务器起不来）
        //   ★ A4：id 已是用户裁定的 `incl_top`（docs/24 §1 第 3 条 / docs/08 §7）
        //     —— 现在「没给 -Dinfernalclash.leaderboard-id」不再等于拒绝注册；
        //     只有**覆盖值非法**才会拒绝（拒绝时 LeaderboardWiring 打 warning）
        io.mczju.infernalclash.leaderboard.LeaderboardWiring.register(getLogger());
        // ★ 战绩记录的唯一入口是 recordMatchResult(...)；★ **对局收尾已接上它**
        //   （game/InfernalClashGame#recordMatchResult，覆盖正常结束 / abort / 无人退出三条路径；
        //   记账失败只告警，绝不打断收尾）—— 见 docs/04 §16.13.5。
        //   ★ A4 + A5 之后（2026-10-15 用户裁定）：「已接线」这一条**不再**是"榜是空的"的理由：
        //     榜单 id 已裁定为 `incl_top`（docs/24 §1 第 3 条，E12 结案）、
        //     档案的 realWins / realMatches / realStreak / realBestStreak 四个 public int 已补齐
        //     （docs/24 §1 第 4 条「补」，G2 结案）⇒ 榜**应当有数**。
        //   ★ 这两条判定各自关掉的原缺口（出处保留，便于回溯）：
        //     ① **E12 榜单技术 id** —— docs/05 §E 的 E12（原「未裁定 ⇒ 拒绝注册」）；
        //     ② **G2 档案 real* 字段** —— docs/04 §16.13.6 的 G2（原「四个字段一个都不存在 ⇒ 榜恒空」）。
        //   ⚠️ 剩下**未实机验证**的一条（★ 别把它写成"缺功能"）：展示实体要管理员自己建
        //     （/mgcop leaderboard create incl_top <实体id>，代码里不生成实体），
        //     以及「榜上真的有数」需要一次真人/假 UUID 实机确认（docs/11 §T9 / docs/12）。
        getLogger().info("战绩记录已接入对局收尾（对局结束，写档案 real* 四字段；失败只告警）；"
                + "榜单 id 已裁定为 " + io.mczju.infernalclash.leaderboard.LeaderboardFields.ID
                + "（docs/24 §1 第 3 条）、档案 real* 字段已补齐（同 §1 第 4 条），榜应当有数；"
                + "页面上要看到它还需管理员用 /mgcop leaderboard create "
                + io.mczju.infernalclash.leaderboard.LeaderboardFields.ID + " <实体id> 建展示实体"
                + "（「榜上真的有数」仍未实机验证：docs/11 §T9 / docs/12）");

        getLogger().info("InfernalClash 已启用（玩法链路：时间轴 / 目标物 / 出牌 / 索敌 / 法术 / 能力 / 界面 / 局外成长"
                + " 全部已接线；2026-10-20 起金币结算、金卡粒子、解锁入口、退出判负也已接线）");
    }

    @Override
    public void onDisable() {
        // ★ 停服时先把对局收干净：取消全局时钟 → 停止每一场（取消其定时任务 + 清场）
        //   顺序很重要：时钟先停，避免 stop 过程中又被 tick 一次
        if (matchTicker != null) {
            try {
                matchTicker.cancel();
            } catch (Throwable ignored) {
                // 关服流程里取消任务可能抛，收尾不能因此中断
            }
            matchTicker = null;
        }
        if (legacyPurgeTicker != null) {
            try {
                legacyPurgeTicker.cancel();
            } catch (Throwable ignored) {
                // 同上：关服流程里任何一步抛都不能中断收尾
            }
            legacyPurgeTicker = null;
        }
        for (MatchService service : MatchService.all().values()) {
            try {
                service.stop("插件停用");
            } catch (Throwable t) {
                getLogger().warning("停用对局 " + service.matchId() + " 时出错：" + t);
            }
        }
        getLogger().info("InfernalClash 已禁用");
    }

    /**
     * 启动**驱动所有对局的单个全局任务**（每 tick 一次）。
     *
     * <p>★ 为什么是「一个任务遍历所有对局」而不是「每场一个任务」：
     * 每场一个任务在 3 个房间时就是 3 个任务 + 3 份调度开销，
     * 而且「忘了取消」的爆炸半径更大（`MatchService` 的时钟说明）。
     */
    private void startMatchTicker() {
        matchTicker = getServer().getScheduler().runTaskTimer(this, this::tickMatches, 1L, 1L);
        getLogger().info("对局时钟已启动（单个全局任务，1 tick 周期）");
    }

    // ---------------- ★★★ 遗留实体兜底清理（2026-10-20） ----------------

    /** 启动后第几拍开始第一次兜底清理（留出世界/区块就绪的时间）。 */
    private static final long PURGE_FIRST_DELAY_TICKS = 100L;

    /** 兜底重试的周期（= 60 秒）。只在**没有进行中对局**时跑。 */
    private static final long PURGE_RETRY_TICKS = 1200L;

    /** 兜底清理的周期任务（停用时取消）。 */
    private BukkitTask legacyPurgeTicker;

    /**
     * ★★★ <b>启动兜底清理的接线</b>。
     *
     * <h2>它补的是什么（用户 2026-10-20 逐字）</h2>
     * > 「**abort 似乎没处理好，刚才中途退出后关闭服务器再上线，
     * > 上局的实体就会一直残留清不掉**」
     *
     * <p><b>根因</b>：正常清场只认 {@link UnitRegistry} 的**内存 UUID 账本**。中途退出后
     * 区块一卸载、或服务器一关，账本里那些 UUID 就再也解析不到 ⇒ 重启后账本归零
     * ⇒ <b>没有任何机制会再去认领它们</b>（`/incl debug purge-legacy --yes` 是手动指令）。
     *
     * <p><b>兜底</b>：启动后跑一次 + **无对局时**每 60 秒重试一次 —— 后者让
     * 「玩家走近把区块加载起来」之后仍能被清掉（清理不主动加载区块）。
     *
     * <p>★ <b>安全边界</b>：全部沿用 {@code objective/LegacyPurge} 的铁律 10 四问 ——
     * 范围 = 本插件自己的点位 ±64 格方形、只遍历**已加载**区块；
     * 归属 = **PDC**（没带我们 PDC 的一律只报告、绝不删）；每次都有带数量的日志。
     * ★ 有进行中对局时**整轮跳过**：那一刻场上是玩家的战场。
     * ★ 开关 = {@code config.yml} 的 {@code mgc.purge-legacy-on-start}（缺省开）。
     */
    private void startLegacyPurgeSweep() {
        boolean enabled;
        try {
            enabled = gameConfig() != null && gameConfig().purgeLegacyOnStart();
        } catch (Throwable t) {
            getLogger().warning("[IC] 读不到「自动清遗留实体」开关，按开处理：" + t);
            enabled = true;
        }
        if (!enabled) {
            getLogger().info("[IC] 自动清理遗留实体：关"
                    + "（mgc.purge-legacy-on-start=false），需要时手动敲 /incl debug purge-legacy --yes");
            return;
        }
        try {
            legacyPurgeTicker = getServer().getScheduler().runTaskTimer(this, () -> {
                if (MatchService.activeCount() > 0) {
                    return;   // ★ 有对局 ⇒ 不动（避免碰玩家正在打的战场）
                }
                purgeLegacyNow("兜底扫描", true);
            }, PURGE_FIRST_DELAY_TICKS, PURGE_RETRY_TICKS);
            getLogger().info("[IC] 自动清理遗留实体：开（启动后 "
                    + (PURGE_FIRST_DELAY_TICKS / 20) + " 秒首扫，之后无对局时每 "
                    + (PURGE_RETRY_TICKS / 20) + " 秒重试；范围 = 本插件点位 ±64 格 + PDC 归属）");
        } catch (Throwable t) {
            getLogger().warning("[IC] 自动清理遗留实体的任务没能启动（不影响对局）：" + t);
        }
    }

    /**
     * 立刻跑一次兜底清理（自动兜底与「开局前」都走它）。
     *
     * @param reason 日志用（哪一时刻触发的）
     * @param quiet  {@code true} = 没找到东西就不出声（周期重试用）
     * @return 本次真的删掉的数量
     */
    public int purgeLegacyNow(String reason, boolean quiet) {
        if (unitRegistry == null || arenaConfig() == null) {
            return 0;
        }
        try {
            return new io.mczju.infernalclash.objective.LegacyPurgeService(
                    this, arenaConfig(), unitRegistry)
                    .run(true,
                            msg -> { },   // 自动路径不往聊天栏发（没有发令者）
                            msg -> getLogger().warning(msg),
                            quiet);
        } catch (Throwable t) {
            getLogger().warning("[IC] 遗留清理（" + reason + "）出错（不影响对局）：" + t);
            return 0;
        }
    }

    /**
     * ★ 只清**某一座竞技场**范围内的遗留实体（**开新局之前**跑）。
     *
     * <p>为什么还要这一道：启动兜底只能在区块**已加载**时清；若那时竞技场没人、区块没加载，
     * 残留会留到有人进场。而「开局那一刻」区块必然已加载 ⇒ 这是最可靠的清理时机。
     *
     * @param arena  本局用的竞技场（{@code null} ⇒ 不做，fail-safe）
     * @param reason 日志用
     * @return 本次真的删掉的数量
     */
    public int purgeLegacyForArena(io.mczju.infernalclash.arena.ArenaConfig arena, String reason) {
        if (arena == null || unitRegistry == null) {
            return 0;
        }
        try {
            return new io.mczju.infernalclash.objective.LegacyPurgeService(this, arena, unitRegistry)
                    .run(true, msg -> { }, msg -> getLogger().warning(msg), true);
        } catch (Throwable t) {
            getLogger().warning("[IC] 开局前的遗留清理（" + reason + "）出错（不影响开局）：" + t);
            return 0;
        }
    }

    /**
     * ★ <b>清场重试的节拍</b>（2026-10-17 第十轮，缺陷 P0-c）：
     * 每 {@value #CLEANUP_SWEEP_TICKS} tick（= 1 秒）补删一次「上一局没能删掉的、自己登记的实体」。
     *
     * <p>为什么要重试：清场只删「**能解析到**」的实体，而区块没加载时解析不到
     * （现场：玩家阵亡重生到远处 ⇒ 竞技场区块卸载 ⇒ {@code 已删除 0 / 登记 11}）。
     * 那种实体**必须等区块重新加载**才能删 —— 直接放弃就是用户看到的
     * 「对局结束后防御塔等东西没有被移除」。
     * ★ 范围只有 {@code UnitRegistry#pendingMatchIds(active)}（**已经结束的、自己登记过的**
     * 对局），且删之前逐条再核 PDC（铁律 10 四问写在那个方法上）。
     */
    private static final int CLEANUP_SWEEP_TICKS = 20;

    /** 清场重试的 tick 计数。 */
    private int cleanupTicks;

    /** 对局时钟的一拍：推进所有对局 ＋ 每秒补删一次上局的遗留实体。 */
    private void tickMatches() {
        MatchService.tickAll();
        if (unitRegistry == null || ++cleanupTicks % CLEANUP_SWEEP_TICKS != 0) {
            return;
        }
        try {
            // ★★ 2026-10-18：**终局 5 秒窗口内的对局不算"上局遗留"** ——
                //   否则这里每秒一次的补删会把它们刚关掉 AI 的召唤物立刻删掉
                //   （现场症状：「投降那一刻所有召唤物都消失了」）。
                java.util.Set<String> protectedMatches =
                        new java.util.LinkedHashSet<>(MatchService.all().keySet());
                protectedMatches.addAll(MatchService.finaleMatchIds());
                unitRegistry.retryPendingRemovals(protectedMatches, getLogger());
        } catch (Throwable t) {
            // ★ 补删失败**绝不能**把对局时钟带走（那会让所有对局停摆）
            getLogger().warning("补删上局遗留实体时出错（不影响对局）：" + t);
        }
    }

    /** 全局插件实例（MGC 通过反射创建对局类，那些类只能这样取插件）。 */
    public static InfernalClashPlugin getInstance() {
        return instance;
    }

    // ================= 配置 =================

    /**
     * ★ <b>需要走「只增不改」迁移的配置文件</b>（2026-10-15 P8 接线：从 1 个扩到 3 个）。
     *
     * <p><b>为什么恰好是这 3 个</b>（{@code docs/03} §7 的文件分类表）：
     * 它们是「<b>配置</b>」—— 服主可以改值，我们只补**缺失的键**，已有的一个字节都不覆盖。
     *
     * <p><b>为什么别的文件不在这里</b>（写下来，免得后人「顺手」加进来）：
     * <ul>
     *   <li>{@code cards/*.yml}（51 张，{@code CardCatalog.DIR}）：**「一张卡一个文件」的数据文件**，
     *       键集合由 {@code ShippedCardsTest} 钉住，升级情形只有「整份文件缺失 ⇒ 拷出厂版」，
     *       不存在「现场文件里少了某个子键」这一档 ⇒ 加进来只会多出一条没有意义的写盘路径；</li>
     *   <li>{@code mob-groups.yml}：属于「**内容**」（以出厂值为准，覆盖前备份，见 {@code docs/03} §7 表）；</li>
     *   <li>{@code arenas.yml}：属于「**数据**」（服主自己采集的点位，**不迁移**）；</li>
     *   <li>{@code plugin.yml}：不是数据目录里的文件。</li>
     * </ul>
     */
    public static final List<String> MIGRATED_CONFIG_FILES =
            List.of(GameConfig.FILE, DisplayConfig.FILE, EconomyConfig.FILE);

    /**
     * 把**出厂资源**补齐到数据目录。
     *
     * <p>★ 一律 `replace = false`：**只补缺失的**，绝不覆盖服主改过的文件
     * （与「配置只增不改」的迁移口径一致，见 {@code docs/03} §7）。
     */
    private void saveShippedFiles() {
        saveDefaultConfig();
        for (String name : List.of(EconomyConfig.FILE, MobGroupCatalog.FILE, DisplayConfig.FILE,
                // ★ 2026-10-19：卡牌文案覆盖层（51 张卡的 display-name / flavor / description 集中在这一个文件里）
                io.mczju.infernalclash.config.CardTexts.FILE)) {
            saveIfMissing(name);
        }
        saveShippedDirectory(CardCatalog.DIR);
        // ★ 文件补齐**不等于**配置补齐：往已有文件里加新段落 / 新子键必须走迁移（见 migrateConfigFile）
        //   ★ 2026-10-15（P8 接线）：从「只有 config.yml」扩到 3 个配置文件 ——
        //     `display.yml` 的 `messages:` / `icons:` 加一条子键、`economy.yml` 加一个段落，
        //     在此之前都**不会**被补齐 ⇒ 老安装被严格校验拒绝启动（docs/03 §7.1 的缺口）。
        for (String name : MIGRATED_CONFIG_FILES) {
            migrateConfigFile(name);
        }
    }

    /**
     * ★ <b>只在文件不存在时才写出厂副本</b>。
     *
     * <h2>为什么不直接用 {@code saveResource(name, false)}</h2>
     * 2026-10-15（P3 实机自检）实测：它对**每一个已存在**的文件都会打一条
     * {@code WARN: Could not save X because X already exists} ⇒ 本项目现场每次启动刷
     * **54 条**这种告警（51 张卡 + 3 个配置文件）。后果不是"难看"，而是
     * <b>真正的告警被淹掉</b>（我们恰好有一条很重要的启动告警：16 条文案还是占位 `[待填]`）。
     * ⇒ 先自己判断存在性，已存在就**根本不调**它。
     *
     * <p>语义与 `saveResource(name, false)` 完全一致（只补缺失），只是不再吵。
     */
    private void saveIfMissing(String name) {
        if (new File(getDataFolder(), name).isFile()) {
            return;
        }
        saveResource(name, false);
    }

    /**
     * ★ <b>「只增不改」补齐</b>：把出厂文件里**现场完全没有的键**（顶层段落，或段落里的子键）
     * 按出厂原文插进现场文件；已有的键 —— **哪怕值被服主改过** —— 一个字节都不动。
     *
     * <h2>为什么必须有（P2 实机自检抓到的真问题）</h2>
     * {@code saveResource(name, false)} **只补「文件不存在」，不补「键不存在」** ⇒
     * 出厂 {@code config.yml} 新增 {@code phases} 段之后，
     * **已经跑过一次的服务器**会因为「缺必需段落」而**拒绝启动**。
     * 而 {@code docs/03} §7 明确承诺「升级只补齐缺失键、绝不覆盖服主改过的值」——
     * 这个方法就是那条承诺。
     *
     * <p>做法刻意是**文本级插入**（不是 {@code YamlConfiguration.save()}）：
     * 后者会把注释全部丢掉，而出厂配置的注释就是它的说明书。
     * ⇒ 只补缺失的键，**已有内容一个字节都不动**，并**先备份**。
     *
     * <h2>★ 2026-10-15（P8 接线）：从「只有顶层段落」扩到「子键」</h2>
     * 判据与文本插入计划早就在纯函数 {@link ConfigMigrator#plan(String, String)} 里
     * （{@code MigrationResult} 带 {@code changed() / text() / summary()}），
     * 但**启动入口只用了顶层段落那一半** ⇒ 往 {@code display.yml} 的 {@code messages:} 里
     * 加一条子键，老安装仍然缺键、仍然被严格校验拒绝启动（{@code docs/03} §7.1 记着这个缺口）。
     * 现在这里直接走 {@code plan(...)}：**顶层段落与子键一次覆盖**，且**只在真的缺东西时写盘**。
     *
     * <p>本方法只负责「拿到出厂文本 + 打日志」；所有判据与 IO 都在离线可测的
     * {@link #migrateConfigOnDisk(File, String, String)} 里。
     */
    private void migrateConfigFile(String fileName) {
        File live = new File(getDataFolder(), fileName);
        if (!live.isFile()) {
            return;   // 文件本来就没有：saveResource / saveDefaultConfig 已经拷了出厂版
        }
        String shipped;
        try (InputStream in = getResource(fileName)) {
            if (in == null) {
                getLogger().warning("配置迁移：jar 里没有出厂副本 " + fileName
                        + "，跳过迁移，现场文件一个字节都没动");
                return;
            }
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // ★ 读不出厂副本**不能**让插件起不来（那会让服务器起不来，铁律 10 的失败方向）
            getLogger().warning("配置迁移：读不出厂副本（" + fileName + "）：" + e
                    + "，跳过迁移，现场文件保持原样");
            return;
        }

        MigrationOutcome outcome = migrateConfigOnDisk(live, fileName, shipped);
        // ★ 每个文件一条日志、带数量（summary() 里就有条数）—— 便于对账
        String line = "配置迁移：" + fileName + " " + outcome.summary();
        if (!outcome.changed() && !outcome.warnings().isEmpty()) {
            getLogger().warning(line);
            for (String warning : outcome.warnings()) {
                getLogger().warning(" · " + warning);
            }
        } else {
            getLogger().info(line);
        }
    }

    // ==================================================================================
    // ★ 迁移的**可离线调用**核心（判据 + 备份 + 写盘）
    // ==================================================================================

    /**
     * ★ <b>一次迁移的结果</b>（纯数据，便于离线断言与打日志）。
     *
     * @param changed    是否**真的写了盘**（false ⇒ 现场文件一个字节都没动）
     * @param backupName 备份文件名；<b>只在 {@code changed} 为 true 时非 null</b>
     * @param summary    给日志用的一句话（★ 带数量，便于对账）
     * @param warnings   跳过 / 不处理的原因（调用方应原样打进日志）
     */
    public record MigrationOutcome(boolean changed, String backupName,
                                   String summary, List<String> warnings) {
    }

    /**
     * ★ <b>可离线调用的迁移核心</b>：出厂文本 × 现场文件 ⇒ 「补了什么 / 备份到哪 / 要不要写盘」。
     *
     * <h2>为什么要单独抽成 {@code static}</h2>
     * 上面那个方法依赖 {@code getDataFolder() / getResource() / getLogger()}（要 {@code Plugin} 实例），
     * 而这段逻辑**会去改服主的现场文件** —— 它恰恰是最需要离线单测钉死的一段。
     * 收在这里之后，单测只用「临时目录 + 两个文件」就能验证三条关键性质，
     * **不需要伪造 Bukkit**（{@code ConfigMigratorTest}）：
     * <ol>
     *   <li>现场缺一个子键 ⇒ 补上 + 生成 {@code <文件名>.bak}（且 .bak = 改动前的原文）；</li>
     *   <li>现场**不缺**任何键 ⇒ **一个字节都不写**（连 .bak 都不生成 / 不覆盖）；</li>
     *   <li>出厂副本拿不到 ⇒ **不抛**、只告警（{@code changed()==false}）。</li>
     * </ol>
     *
     * <h2>失败方向（铁律 10）</h2>
     * 出厂副本不可用 / 现场文件读不了或解析失败 / 写盘抛 IO
     * ⇒ <b>只返回带 warnings 的结果，绝不把异常抛给调用方</b>
     * —— 迁移失败若从 {@code onEnable} 逃出去，服务器就起不来了，而这只该是「少补几个键」。
     *
     * @param live        现场文件（**服主的**文件）
     * @param fileName    文件名（只用于备份命名与报错措辞）
     * @param shippedText 出厂副本的文本；{@code null} 表示拿不到
     */
    public static MigrationOutcome migrateConfigOnDisk(File live, String fileName, String shippedText) {
        List<String> warnings = new ArrayList<>();
        if (shippedText == null) {
            warnings.add("拿不到出厂副本，跳过迁移，现场文件一个字节都没动");
            return new MigrationOutcome(false, null, skippedSummary(warnings), List.copyOf(warnings));
        }
        if (live == null || !live.isFile()) {
            warnings.add("现场文件不存在，无需迁移");
            return new MigrationOutcome(false, null, skippedSummary(warnings), List.copyOf(warnings));
        }
        try {
            // ★ 现场文件先过一遍真 YAML 解析器：它已经坏了就**别往里插**（插了只会更坏）
            YamlFile.read(fileName, live);
            String liveText = Files.readString(live.toPath(), StandardCharsets.UTF_8);
            if (!liveText.isEmpty() && liveText.charAt(0) == '\uFEFF') {
                // 与 YamlFile.read 同口径：BOM 不算键的一部分。
                // （否则首行的键会变成 `\uFEFFconfig-version` ⇒ 迁移会以为「缺 config-version」而重复追加）
                liveText = liveText.substring(1);
            }

            ConfigMigrator.MigrationResult plan = ConfigMigrator.plan(shippedText, liveText);
            warnings.addAll(plan.warnings());
            if (!plan.changed()) {
                // ★★ 没有缺失键 ⇒ **一个字节都不写**（现场文件是用户的，铁律 5）
                return new MigrationOutcome(false, null, plan.summary(), List.copyOf(warnings));
            }

            // ① 先备份（升级动作必须可回退；命名沿用本项目既有的 `<文件名>.bak`，与 docs/03 §7 一致）
            String backupName = fileName + ".bak";
            Files.copy(live.toPath(), live.toPath().resolveSibling(backupName),
                    StandardCopyOption.REPLACE_EXISTING);

            // ② 写回「现场原文 + 缺失键的出厂原文」；已有的键（哪怕值被服主改过）一字未改
            Files.writeString(live.toPath(), plan.text(), StandardCharsets.UTF_8);
            return new MigrationOutcome(true, backupName,
                    plan.summary() + "（旧文件已备份为 " + backupName + "）", List.copyOf(warnings));
        } catch (Exception e) {
            // ★★ 迁移失败**绝不能**让插件起不来：onEnable 之后还有严格校验兜底（缺键会被明确报出）
            warnings.add("迁移失败：" + e + "，跳过该文件，现场文件保持原样");
            return new MigrationOutcome(false, null, skippedSummary(warnings), List.copyOf(warnings));
        }
    }

    /** 跳过时的日志措辞（与 {@code MigrationResult.summary()} 一样把原因带上）。 */
    private static String skippedSummary(List<String> warnings) {
        return "配置迁移：已跳过，未改动文件（" + String.join("；", warnings) + "）";
    }

    /**
     * 把 jar 里某个**目录**下的 .yml 全部补齐到数据目录（{@code cards/} 用）。
     *
     * <p>为什么不用「硬编码 51 个文件名」：那种列表一定会与 jar 内容漂移，
     * 而漂移的表现是「新加的卡悄悄没被部署到现场」—— 最难查的一类问题。
     */
    private void saveShippedDirectory(String dirName) {
        try (JarFile jar = new JarFile(getFile())) {
            jar.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith(dirName + "/") && name.endsWith(".yml"))
                    .sorted()
                    .forEach(this::saveIfMissing);
        } catch (IOException e) {
            getLogger().warning("无法从插件 jar 里补齐 " + dirName + "/ 下的文件：" + e.getMessage());
        }
    }

    /**
     * 读取并校验**全部**配置（失败即抛，由调用方决定是禁用还是提示玩家）。
     *
     * @throws ConfigError 任一文件缺失 / 非法 / 交叉引用不成立
     */
    private void loadAllOrThrow() {
        // ★ 只读一次盘：config.yml 现在有两个读者（GameConfig 读大厅 / MatchConfig 读 phases）
        var root = readConfigOrThrow();
        this.gameConfig = GameConfig.load(root);
        this.matchConfig = MatchConfig.load(root);
        warnIfIconInvalid(this.gameConfig.meta().icon());

        // ---- 玩法配置（P1）----
        File data = getDataFolder();
        this.economyConfig = EconomyConfig.load(new File(data, EconomyConfig.FILE));
        this.mobGroups = MobGroupCatalog.load(new File(data, MobGroupCatalog.FILE));
        // ★★★ 2026-10-19：**卡牌文案覆盖层**（用户：「我不想一个一个文件点」）——
        //   `card-texts.yml` 里有这张卡 ⇒ display-name / flavor / description 只认它；
        //   文件不存在（老安装 / 写入失败）⇒ 回落 cards/*.yml 自己的文案，并留一行 WARN。
        io.mczju.infernalclash.config.CardTexts cardTexts =
                io.mczju.infernalclash.config.CardTexts.loadOrNull(
                        new File(data, io.mczju.infernalclash.config.CardTexts.FILE));
        if (cardTexts == null) {
            getLogger().warning("[IC] 没有 " + io.mczju.infernalclash.config.CardTexts.FILE
                    + "，卡牌文案回落 cards/*.yml 自己的 display-name / flavor / description"
                    + "（想集中改文案请在数据目录放一份该文件；出厂副本在 jar 里）");
        } else {
            getLogger().info("[IC] 卡牌文案覆盖层生效：" + cardTexts.describe()
                    + "，display-name / flavor / description 只认该文件，cards/*.yml 里的同名键不再被读");
        }
        this.cardCatalog = CardCatalog.load(data.toPath().resolve(CardCatalog.DIR), this.mobGroups,
                cardTexts);
        // ★★ 2026-10-17 第十二轮：把「优先攻击建筑」的清单与可疑组合**在装载期打一次**
        //   （纯告警，不影响任何解析结果；离线入口不能打日志 —— 见那个方法的 javadoc）
        this.cardCatalog.warnAboutBuildingSemantics(getLogger());
        // ★★ 2026-10-19：**旧键改名提醒** —— 2026-10-19 把 `affected-by-taunt` 改名成
        //   `affected-by-building`，旧键仍然生效（见 CardCatalog#parseSummon）⇒
        //   现场卡里若还写着旧键，这里点名列出，提醒运维改过来（不是错误，不拒绝启动）。
        java.util.List<String> legacyTauntKeys = CardCatalog.legacyTauntKeyCards(
                data.toPath().resolve(CardCatalog.DIR));
        if (!legacyTauntKeys.isEmpty()) {
            getLogger().warning("[IC] 以下卡仍写着旧键 summon.affected-by-taunt（仍按"
                    + " affected-by-building 生效，但请改名）：" + legacyTauntKeys
                    + " —— 新键名见 docs/03 §4.3");
        }
        this.display = DisplayConfig.load(new File(data, DisplayConfig.FILE));

        // ★ 目标物配置（config.yml 的 objectives 段，P3）
        this.objectiveConfig = ObjectiveConfig.load(root);

        // ★ 战斗配置（config.yml 的 combat 段，P5）
        this.combatConfig = CombatConfig.load(root);

        // ★ 训练人机配置（config.yml 的 training 段）
        //   ★ 2026-10-21 用户裁定：多套人机（training.bots）＋ 每张法术卡的全局规则（training.spell-rules）
        //   ★★ 顺序有讲究：它必须在 cardCatalog **之后** —— 紧接着的交叉校验要查卡池
        this.trainingConfig = TrainingConfig.load(root);
        requireBotConfigValid();

        // ★ 竞技场（arenas.yml，**数据文件**）：文件不存在 ⇒ 空目录（不报错）
        //   它由 /incl arena set <名字> 采集；没采集也能启动，只是开不了「有目标物」的对局
        //   ★★ 2026-10-18：改成**多竞技场** —— 一份文件里可以并存多个，对局用「默认竞技场」
        //   （由 config.yml 的 arena.default 指定；不写 = 文件里的第一个）。
        //   ★ 旧格式（顶层 points:）自动读成名叫 `default` 的竞技场 ⇒ 现场文件无需手改。
        //   ★★ 作废键（旧 river / bridge.N）只警告、不拒绝启动 —— 见 ArenaConfig#OBSOLETE_KEYS。
        java.util.List<String> arenaWarnings = new java.util.ArrayList<>();
        this.arenaCatalog = ArenaCatalog.load(new File(data, ArenaConfig.FILE), arenaWarnings);
        for (String warning : arenaWarnings) {
            getLogger().warning("[IC] " + warning);
        }
        this.configuredDefaultArena = defaultArenaFromConfig(root);
        applyDefaultArena();

        // ---- 实体归属登记表（铁律 10）：全局一个，内部按 matchId 分区 ----
        if (this.unitRegistry == null) {
            this.unitRegistry = new UnitRegistry(this);
        } else if (this.unitRegistry.table().size() > 0) {
            // 热重载时账本里还留着进行中对局的实体：**保留**，并明确告诉运维
            getLogger().warning("重载配置时账本里仍有 " + this.unitRegistry.table().size()
                    + " 个实体登记（属于进行中的对局）—— 保留不清，等对局结束自然回收");
        }

        // ★ 卡牌物品与召唤生成器（P4）。
        //   ⚠️ 必须在 unitRegistry **之后**创建：SummonSpawner 持有它来登记召唤物归属
        //   （第一次写时放在了注册表创建之前 ⇒ 拿到 null：编译不报错，但召唤物会全部漏登记）
        this.cardItems = new CardItems(this);
        // ★★★ 2026-10-19（用户现场缺陷）：「结束对局后再加入房间，上局留下的卡牌会进入物品栏」。
        //   根因 = MGC 的 profile 快照（profiles/<uuid>.yml 的 main:）抓的是**带牌**的物品栏，
        //   下一次 /mgc join 切回本 profile 时又贴回来 —— 我们的收尾（onStop）清得比它晚。
        //   ⇒ 在 MGC 的 `PlayerProfileLoadedEvent`（ProfileCapture#apply **之后**）按 PDC
        //     逐件清掉**本插件自己的**物品（卡牌 / 战斗物品 / 选卡菜单），不碰 MGC 的大厅钟。
        getServer().getPluginManager().registerEvents(
                new io.mczju.infernalclash.game.PlayerProfileHygieneListener(cardItems, getLogger()), this);
        this.summonSpawner = new SummonSpawner(this, this.mobGroups, this.unitRegistry);
        // ★ 法术管线（P6）：同样要在 unitRegistry 之后（它读账本来确定"范围内有哪些实体"）
        this.spellCaster = new io.mczju.infernalclash.spell.SpellCaster(
                this, this.unitRegistry, this.summonSpawner, this.cardCatalog);
        // ★ 效果层需要"召唤"这个动作 ⇒ 注入唯一入口（否则 SUMMON* 效果会告警并跳过）
        io.mczju.infernalclash.spell.EffectApplier.install(this.summonSpawner);

        // ★ P6b：能力运行时 + 生成钩子。★ 必须放在**两者都建好之后** ——
        //   顺序反了会变成「钩子里持有 null」（P4 踩过：编译不报错、只在运行期静默失效）
        this.abilityRuntime = new io.mczju.infernalclash.ability.AbilityRuntime(
                this, this.cardCatalog, null);
        this.summonSpawner.setSpawnHook((entity, card, matchId, side, owner, extraAbilities) -> {
            io.mczju.infernalclash.match.MatchService match =
                    io.mczju.infernalclash.match.MatchService.get(matchId);
            if (match != null) {
                this.abilityRuntime.onSummonSpawn(entity, card, matchId, side, owner, match,
                        extraAbilities);
            }
        });

        // ★ 交叉引用：**有权重的档位必须真的有卡**。抽包器的构造器会拒空池，
        //   把它放在启动路径上 ⇒ 这类配置错误在**启动时**就暴露，而不是玩家开包那一刻。
        this.packRoller = this.economyConfig.packRoller(this.cardCatalog.poolByRarity());
    }

    /**
     * ★★ <b>装配期的交叉校验</b>：{@code training.bots} 里每一套人机的卡组、
     * 以及 {@code training.spell-rules} 这张全局表，都必须与卡池对得上。
     *
     * <h2>2026-10-21 用户裁定后的判据（三组）</h2>
     * <ol>
     *   <li><b>卡组</b>（{@link TrainingConfig#crossCheckBots}）：每个写死的 id 必须在卡池里、
     *       且必须是「**人机可用卡**」（卡池 − 速度药水 − 战术冷却 − 回响 − 没配规则的法术卡）；
     *       「随机」名额不能超过可用卡能给的数量；</li>
     *   <li><b>规则表</b>（{@link TrainingConfig#crossCheckSpellRules}）：键必须在卡池里、
     *       <b>必须是法术卡</b>；{@code targeting: ALLY_TARGET} 的卡只能配
     *       {@code RANDOM_ALLY_TARGET}（配别的**永远打不中**）；</li>
     *   <li>两者都过 ⇒ 启动。</li>
     * </ol>
     *
     * <h2>为什么放在这里（而不在 {@code config/TrainingConfig} 里）</h2>
     * 这两条判据要查 {@code card/CardCatalog}，而 {@code config/**} 是纯配置层
     * （它必须能在普通 JUnit 里加载出厂 {@code config.yml}，不该被迫拖上整条卡池链）
     * ⇒ 判据与文案在 {@code TrainingConfig} 那两个纯函数里（可离线断言），
     * 「不合法就怎样」在这里：<b>抛 {@link ConfigError} ⇒ 走既有的「明确禁用 + 说清原因」路径</b>
     * （{@link #loadConfigs()} 会把它打成「配置加载失败，插件将被禁用」+ 完整键路径）。
     *
     * <h2>★ 为什么「法术卡没配规则」也要拒绝（而不是静默跳过）</h2>
     * 表现会是「人机手里凭空少一张牌」而**没有任何第二条告警** ——
     * 那正是本项目最反复警告的那一类静默失效。
     *
     * @throws ConfigError 任一项不合法（消息逐条点名到「第几个人机、第几项、槽位几、哪个 id、为什么」）
     */
    private void requireBotConfigValid() {
        if (trainingConfig == null || cardCatalog == null) {
            // 走到这里说明装配顺序被改了：**绝不静默跳过**（那会让「卡组没被校验」这件事隐身）
            throw new ConfigError(TrainingConfig.FILE, TrainingConfig.PATH_BOTS,
                    "无法交叉校验：trainingConfig=" + (trainingConfig == null ? "null" : "已建")
                            + " / cardCatalog=" + (cardCatalog == null ? "null" : "已建")
                            + " —— 交叉校验必须排在两者都建好之后（见 loadAllOrThrow 的注释）");
        }
        java.util.List<String> allCardIds = new java.util.ArrayList<>(
                new java.util.TreeSet<>(cardCatalog.cards().keySet()));
        java.util.function.Function<String, Object> lookup = id -> cardCatalog.cards().get(id);

        List<String> problems = new java.util.ArrayList<>(
                trainingConfig.crossCheckSpellRules(lookup, botDeckCardAdapter()));
        // ★ 2026-10-21 追加（docs/32）：建筑落点表里的卡必须是**建筑**
        problems.addAll(trainingConfig.crossCheckBuildingPlacements(lookup, botDeckCardAdapter()));
        problems.addAll(trainingConfig.crossCheckBots(allCardIds, lookup, botDeckCardAdapter()));
        if (problems.isEmpty()) {
            return;
        }
        throw new ConfigError(TrainingConfig.FILE, TrainingConfig.PATH_BOTS,
                "人机配置里有 " + problems.size() + " 项不合法（用户 2026-10-21 裁定："
                        + "每套卡组正好 8 张、每张都必须是「人机可用卡」；每张法术卡必须有一条能工作的规则"
                        + " —— 详见 docs/31 §2.2 / §4 / §5）：\n · "
                        + String.join("\n · ", problems));
    }

    /**
     * ★ {@code card/Card} 在 {@link TrainingConfig.DeckCard} 适配器后面的实现
     * —— 委托到 {@code card/BotDeckCardAdapter}（**唯一**实现，装配期与运行期共用）。
     *
     * <p>★ 为什么不在这里写匿名内部类：那会让**离线测试**为了拿这个适配器而加载
     * {@code InfernalClashPlugin}（{@code JavaPlugin} 的实例初始化需要 Bukkit 服务端）⇒ 测试直接炸。
     * 留在本类里的只是一个**转发**，真正的实现是纯的（见那个类的 javadoc）。
     */
    public static TrainingConfig.DeckCard botDeckCardAdapter() {
        return io.mczju.infernalclash.card.BotDeckCardAdapter.INSTANCE;
    }

    /**
     * 读取并校验配置。
     *
     * @return 校验通过返回 true；失败时已禁用插件并返回 false
     */
    private boolean loadConfigs() {
        try {
            loadAllOrThrow();
            return true;
        } catch (ConfigError e) {
            getLogger().severe("配置加载失败，插件将被禁用。");
            getLogger().severe(" " + e.describe());
            getLogger().severe(" 请修正后重启服务器。");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        } catch (RuntimeException e) {
            // ★ 兜底：加载期任何**非** ConfigError 的运行时异常（典型来源是纯数据层的自检，
            //   例如 ObjectiveConfig 的 validate() 抛 IllegalArgumentException）也必须走
            //   **同一条「明确禁用」路径**。否则异常会从 onEnable 逃出去，现场看到的是一段
            //   Paper 的堆栈，而不是「配置哪一项不对」——那才是运维真正需要的。
            getLogger().severe("配置加载失败（非预期错误），插件将被禁用：" + e);
            if (e.getCause() != null) {
                getLogger().severe(" 原因：" + e.getCause());
            }
            getLogger().severe(" 这通常意味着某个出厂值的自检没通过 —— 请把上面这行连同栈一并反馈。");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    /**
     * 核对 {@code meta.icon} 是不是合法的原版物品名。
     *
     * <p>为什么不在 {@link GameConfig} 里查：那一层要能在**离线单测**中加载出厂
     * {@code config.yml}（见 {@code ShippedConfigTest}），而 {@code Material} 的匹配
     * 依赖服务端注册表 ⇒ 配置层只收字符串，**启动时**在这里核对。
     * 写错就告警，**不静默**（否则又是一条「配置写了却没生效」）。
     */
    private void warnIfIconInvalid(String iconName) {
        if (Material.matchMaterial(iconName) == null) {
            getLogger().warning("config.yml 的 meta.icon = " + iconName
                    + " 不是合法的原版物品名，大厅图标将回退到 NETHER_STAR。");
            getLogger().warning(" 正确写法是原版 Material 名（全大写），"
                    + "例如 NETHER_STAR / DIAMOND_SWORD / FIREWORK_ROCKET。");
        }
    }

    /** {@code /incl debug reload}：热重载配置。校验失败时抛出，由调用方提示玩家。 */
    public void reloadAllConfigs() {
        loadAllOrThrow();
        logSummary();
    }

    /** {@code reloadConfig()} 之后取配置；失败时把 Bukkit 的异常转成 {@link ConfigError}。 */
    private org.bukkit.configuration.file.FileConfiguration readConfigOrThrow() {
        try {
            reloadConfig();
            return getConfig();
        } catch (RuntimeException e) {
            throw new ConfigError(GameConfig.FILE, "<root>", "文件读取/解析失败：" + e.getMessage());
        }
    }

    private void logSummary() {
        getLogger().info("配置校验通过：" + gameConfig.describe());
        getLogger().info("大厅展示：名称「" + gameConfig.meta().name() + "」/ 图标 "
                + gameConfig.meta().icon() + " / 作者 " + gameConfig.meta().author());
        getLogger().info("经济校验通过：" + economyConfig.describe());
        getLogger().info("对局时间轴：" + matchConfig.describe());
        getLogger().info("目标物：" + objectiveConfig.describe());
        getLogger().info("训练人机：" + trainingConfig.describe());
        getLogger().info("竞技场点位：" + arenaConfig.describe());
        getLogger().info("卡池校验通过：" + cardCatalog.describe());
        getLogger().info("召唤物实体组：" + mobGroups.describe());
        getLogger().info("显示文本：" + display.describe());
        // ★ 「有待拍板的文案」必须说出来：否则它会表现成「界面上写着 [待填]」而没人知道为什么
        java.util.Set<String> pending = display.pendingKeys();
        if (!pending.isEmpty()) {
            getLogger().warning("还有 " + pending.size() + " 条文案是占位 "
                    + DisplayConfig.PLACEHOLDER + "（等用户拍板）：");
            getLogger().warning(" " + pending);
            getLogger().warning(" 改 plugins/InfernalClash/display.yml 里对应的值即可，改完重启或 /incl debug reload。");
        }
        // ★ 这句话在 P2 就已经过期了（还写着「开局会立即结束本局」= 底座期的自检行为），
        //   是 P3 实机自检时从日志里看出来的 —— 过期的**用户可见文案**与过期的文档同样是缺陷。
        getLogger().info("玩法状态：时间轴 / 目标物 / 出牌 / 索敌 / 法术 均已接线（P6）——"
                + "能力注册表与界面/局外成长在后续阶段"
                + (arenaConfig != null && arenaConfig.isReadyForMatch()
                        ? "；本局将生成 2 信标 + 4 防御塔"
                        : "；竞技场点位不齐，本局不会生成目标物（用 /incl arena 采集）"));
    }

    // ================= 指令 =================

    private void registerCommand() {
        PluginCommand command = getCommand("incl");
        if (command == null) {
            getLogger().warning("plugin.yml 里没有注册指令 incl，管理指令不可用。");
            return;
        }
        InfernalClashCommand executor = new InfernalClashCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    // ================= MGC 接入 =================

    /**
     * 注册到 MCZJUGameCore。
     *
     * <p>失败**不禁用插件** —— 配置与 {@code /incl} 管理指令仍然可用，便于排查。
     */
    private void registerWithMgc() {
        try {
            MCZJUGameCore.getGameManager()
                    .registerGame(InfernalClashGame.class, InfernalClashRoom.class);
            // ★★ 顺序不能反：**先注册、后加载**。
            //   注册只把「gameId → 类」写进 registeredPlayerData 并顺带扫一次盘；
            //   加载才是把磁盘上的 JSON 读进内存。反过来的话加载时 MGC 还不知道
            //   gameId 对应哪个类 ⇒ 一个玩家档案都读不进来（而这不报错，只表现成"进度全丢"）。
            MCZJUGameCore.getPlayerDataManager()
                    .registerPlayerData(InfernalClashGame.ID, InfernalPlayerData.class);
            MCZJUGameCore.getPlayerDataManager()
                    .loadAllPlayerData(InfernalClashGame.ID, InfernalPlayerData.class);
            getLogger().info("[IC] 玩家档案已注册并加载（gameId=" + InfernalClashGame.ID
                    + "，类=" + InfernalPlayerData.class.getSimpleName()
                    + "；落盘 plugins/MCZJUGameCore/player_data/" + InfernalClashGame.ID
                    + "/<uuid>.json）");
            getLogger().info("[IC] " + profileStore().describe());
            ensureDefaultRoom();
            getLogger().info("已注册到 MCZJUGameCore：game id = " + InfernalClashGame.ID);
        } catch (Throwable t) {
            getLogger().severe("注册到 MCZJUGameCore 失败：" + t);
            getLogger().severe(" 配置与 /incl 管理指令仍可用，但 /mgc join 不可用。");
        }
    }

    /**
     * 确保至少存在一个房间实例。
     *
     * <p>{@code registerGame} 只注册**类**；{@code /mgc join} 还需要一个
     * {@code READY} 的**房间实例**，否则挑选房间会返回 null 并提示「无空闲房间」。
     */
    private void ensureDefaultRoom() {
        var manager = MCZJUGameCore.getGameRoomManager();
        List<AbstractGameRoom> rooms = manager.getGameRooms(InfernalClashGame.ID);
        if (rooms != null && !rooms.isEmpty()) {
            getLogger().info("已存在 " + rooms.size() + " 个房间实例");
            // ★「房间存在」不等于「能加入」：MGC 判定空闲的条件是 state == READY。
            //   房间 state **不落盘**，所以开机时通常都是 READY；
            //   这里只是防御性检查 —— 万一不是，直接把话说明白，省得又去猜「无空闲房间」。
            long ready = rooms.stream().filter(r -> r.getState() == GameRoomState.READY).count();
            if (ready == 0) {
                getLogger().warning("⚠ 房间有 " + rooms.size()
                        + " 个，但没有一个处于 READY → /mgc join 会提示「无空闲房间」。");
                getLogger().warning(" 可执行 /incl debug room reset 立即复位（不必重启）。");
            } else {
                getLogger().info("其中 READY（可加入）的房间：" + ready + " 个");
            }
            return;
        }
        AbstractGameRoom room = manager.createGameRoom(InfernalClashGame.ID, "default");
        if (room == null) {
            getLogger().warning("创建默认房间失败，/mgc join 可能提示「无空闲房间」。");
            getLogger().warning(" 可尝试：/mgcop room create " + InfernalClashGame.ID + " default");
            return;
        }
        manager.saveGameRoom(InfernalClashGame.ID);
        getLogger().info("已创建默认房间 default（供 /mgc join 使用）");
    }

    /** 取本游戏的第一个房间（供调试指令读写）。 */
    public InfernalClashRoom firstRoom() {
        List<AbstractGameRoom> rooms =
                MCZJUGameCore.getGameRoomManager().getGameRooms(InfernalClashGame.ID);
        if (rooms == null) {
            return null;
        }
        for (AbstractGameRoom room : rooms) {
            if (room instanceof InfernalClashRoom clashRoom) {
                return clashRoom;
            }
        }
        return null;
    }

    // ================= 访问器 =================

    /** 已校验的主配置（{@code onEnable} 成功后才可用）。 */
    public GameConfig gameConfig() {
        return gameConfig;
    }

    /** 已校验的经济配置（{@code economy.yml}）。 */
    public EconomyConfig economyConfig() {
        return economyConfig;
    }

    /** 已校验的召唤物实体组目录（{@code mob-groups.yml}）。 */
    public MobGroupCatalog mobGroups() {
        return mobGroups;
    }

    /** 已校验的卡牌目录（51 张）。 */
    public CardCatalog cardCatalog() {
        return cardCatalog;
    }

    /** 抽包器（由经济配置 + 卡池组装；构造成功即证明两者自洽）。 */
    public PackRoller packRoller() {
        return packRoller;
    }

    /** 显示文本配置（R76：面向玩家的文案一律从这里取，不许硬编码）。 */
    public DisplayConfig display() {
        return display;
    }

    /** 对局时间轴配置（{@code config.yml} 的 {@code phases} 段）。 */
    public MatchConfig matchConfig() {
        return matchConfig;
    }

    /** 战斗配置（{@code config.yml} 的 {@code combat} 段）。 */
    public CombatConfig combatConfig() {
        return combatConfig;
    }

    /** 卡牌物品构造 / 识别（身份在 PDC）。 */
    public CardItems cardItems() {
        return cardItems;
    }

    /** 召唤卡的落地（生成 + 立刻登记归属）。 */
    public SummonSpawner summonSpawner() {
        return summonSpawner;
    }

    /** 法术施放管线（P6：目标 → 形状 → 效果）。 */
    public io.mczju.infernalclash.spell.SpellCaster spellCaster() {
        return spellCaster;
    }

    /** 目标物配置（{@code config.yml} 的 {@code objectives} 段）。 */
    public ObjectiveConfig objectiveConfig() {
        return objectiveConfig;
    }

    /**
     * ★ <b>训练人机配置</b>（{@code config.yml} 的 {@code training} 段；
     * 2026-10-15 用户裁定 G12：卡组正好 8 张）。
     *
     * <p>装配点只有一处：{@code game/InfernalClashGame#trainingBotHand()} 拿
     * {@link TrainingConfig#toHandCards} 把 8 个 id 变成 {@code ai/BotPlan.HandCard}
     * （槽位 2~9 按配置顺序）⇒ 人机真的会出牌。
     */
    public TrainingConfig trainingConfig() {
        return trainingConfig;
    }

    /** 竞技场点位（{@code arenas.yml} 的**默认竞技场**）；**未采集时为空的点位表**，不是 null。 */
    public ArenaConfig arenaConfig() {
        return arenaConfig;
    }

    /** ★ 多竞技场目录（2026-10-18 新增；{@code arena list} 与写盘路径用）。 */
    public ArenaCatalog arenaCatalog() {
        return arenaCatalog == null ? ArenaCatalog.empty() : arenaCatalog;
    }

    /** 当前默认竞技场的名字（{@code null} = 一个竞技场都没有）。 */
    public String defaultArenaName() {
        return defaultArenaName;
    }

    /**
     * ★ <b>把 {@code config.yml} 的 {@code arena.default} 原文读出来</b>（没写 / 空白 ⇒ {@code null}）。
     *
     * <p>★ 为什么不做成 {@code ConfigReader.requireString}：这个键是**可选**的，
     * 「不写」是合法且有意义的（= 用文件里的第一个竞技场）⇒ 缺键不是错误。
     */
    static String defaultArenaFromConfig(org.bukkit.configuration.ConfigurationSection root) {
        if (root == null || !root.isSet("arena")) {
            return null;
        }
        org.bukkit.configuration.ConfigurationSection section =
                root.getConfigurationSection("arena");
        if (section == null || !section.isSet("default")) {
            return null;
        }
        Object raw = section.get("default");
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    /**
     * 按当前目录 + 配置**重新解析默认竞技场**，并把 {@link #arenaConfig} 指向它。
     *
     * <p>★ 三条失败方向：
     * <ol>
     *   <li>一个竞技场都没有 ⇒ {@code arenaConfig} = 空表（开不了局，但插件照常启动）；</li>
     *   <li>配置里写的名字不存在 / 不合法 ⇒ <b>告警 + 改用第一个</b>
     *       （删了一个竞技场忘了改默认值，不该把整台服务器拦住）；</li>
     *   <li>取到的竞技场是 {@code null}（不该发生）⇒ 空表 + 告警。</li>
     * </ol>
     */
    private void applyDefaultArena() {
        if (arenaCatalog == null) {
            arenaCatalog = ArenaCatalog.empty();
        }
        String problem = arenaCatalog.defaultProblem(configuredDefaultArena);
        if (problem != null) {
            getLogger().warning("[IC] 默认竞技场：" + problem);
        }
        this.defaultArenaName = arenaCatalog.resolveDefault(configuredDefaultArena);
        ArenaConfig resolved = defaultArenaName == null ? null : arenaCatalog.arena(defaultArenaName);
        this.arenaConfig = resolved == null ? ArenaConfig.empty() : resolved;
        getLogger().info("[IC] 竞技场：" + arenaCatalog.describe(defaultArenaName));
    }

    /**
     * ★ <b>热载竞技场</b>（{@code arenas.yml}）：重新读盘 → 重新解析默认竞技场 → 返回一行摘要。
     *
     * <p>★ 失败方向：保持旧目录 + 告警，绝不抛（写完盘之后也会走这条路）。
     */
    public String reloadArenaConfig() {
        File file = new File(getDataFolder(), ArenaConfig.FILE);
        try {
            this.arenaCatalog = ArenaCatalog.load(file);
            applyDefaultArena();
            return arenaCatalog.describe(defaultArenaName)
                    + (arenaConfig == null ? "" : "｜默认竞技场：" + arenaConfig.describe());
        } catch (Throwable t) {
            String kept = arenaConfig == null ? "（内存里还没有点位表）" : arenaConfig.describe();
            getLogger().warning("[IC] 竞技场热载失败，保持内存里的旧表：" + t);
            getLogger().warning("[IC] 旧表：" + kept + "；磁盘文件：" + file.getAbsolutePath());
            return "热载失败（沿用旧表：" + kept + "）：" + t;
        }
    }

    /**
     * ★ <b>把一份编辑好的点位表装进默认竞技场</b>（<b>不落盘</b>）。
     *
     * <p>只有一条路会用它：<b>写盘失败</b>时也要让本次会话的其它消费者看到改动。
     *
     * @param config 新的点位表；{@code null} ⇒ 忽略并告警（**绝不**用一个 null 把表清空）
     */
    public void setArenaConfig(ArenaConfig config) {
        if (config == null) {
            getLogger().warning("[IC] 拒绝把 null 装进竞技场点位表（保持旧表不动）");
            return;
        }
        if (defaultArenaName == null) {
            // 还没有任何竞技场（例如现场文件是空的）⇒ 建一个名叫 default 的
            defaultArenaName = ArenaCatalog.LEGACY_NAME;
        }
        if (arenaCatalog == null) {
            arenaCatalog = ArenaCatalog.empty();
        }
        arenaCatalog = arenaCatalog.with(defaultArenaName, config);
        this.arenaConfig = config;
        getLogger().info("[IC] 竞技场点位已装进内存（未落盘）：" + defaultArenaName + "，"
                + config.describe());
    }

    /**
     * ★★ <b>写盘 + 装进内存</b>（指令与 U1 界面的**唯一保存入口**）。
     *
     * @param catalog 新的目录（已包含本次改动）
     * @param what    给日志用的一句话（例如 {@code set arena1}）
     */
    public ArenaCatalog.SaveOutcome saveArenaCatalog(ArenaCatalog catalog, String what) {
        if (catalog == null) {
            getLogger().warning("[IC] /incl arena " + what + "：拒绝写盘（目录为 null）");
            return new ArenaCatalog.SaveOutcome(false, null, "拒绝写盘（目录为 null）", java.util.List.of());
        }
        File file = new File(getDataFolder(), ArenaConfig.FILE);
        ArenaCatalog.SaveOutcome outcome = ArenaCatalog.save(file, catalog);
        this.arenaCatalog = catalog;
        applyDefaultArena();
        for (String warning : outcome.warnings()) {
            getLogger().warning("[IC] /incl arena " + what + "：" + warning);
        }
        getLogger().info("[IC] /incl arena " + what + "，" + outcome.summary());
        return outcome;
    }

    /** ★ 全局实体归属登记表（铁律 10：清场只可能作用于它记录过的 UUID）。 */
    public UnitRegistry unitRegistry() {
        return unitRegistry;
    }

    /**
     * ★ <b>建档案门面，并把 R44 的「基础档来源」接到卡池上</b>
     * （{@code docs/21} §1 的那条缺口：现场日志曾是 {@code R44 基础档来源=★ 未接线（会跳过发放）}）。
     *
     * <h2>★ 顺序：为什么这里不会出现「持有 null」</h2>
     * 本项目踩过一次「先建 A 后建 B ⇒ A 持有 null」（P4：{@code SummonSpawner} 早于
     * {@code UnitRegistry} 创建 ⇒ 编译不报错、运行期静默漏登记；{@code AGENTS.md} 铁律 3 / §4.1）。
     * 这条接线<b>两层</b>都堵住了：
     * <ol>
     *   <li><b>调用点顺序对</b>：唯一的生产调用点是 {@code onEnable} 里 {@code loadConfigs()} <b>之后</b>
     *       那一行 —— 那时 {@code cardCatalog} 已经建好（它是 {@code loadAllOrThrow()} 里建的），
     *       而 {@code profileStore} 是本方法自己建的 ⇒ <b>注入发生时两边都非 null</b>；</li>
     *   <li><b>即使顺序被后人调换也不会静默失效</b>：注入的不是 {@code CardCatalog} 实例，
     *       而是 {@link #basicCardIdsFromPool()} 这个方法引用 —— <b>每次取值时重新读
     *       {@code this.cardCatalog} 字段</b>。卡池那一刻还没建好 ⇒ 拿到空清单
     *       ⇒ {@code ProfileStore} 走「跳过发放 + 告警」（失败方向明确、可自愈），
     *       而不是拿着 null 卡池当成"基础档 0 张"静默过去。</li>
     * </ol>
     *
     * <h2>★ 为什么用 Supplier（方法引用）而不是一份算好的快照</h2>
     * {@code /incl debug reload} → {@code reloadAllConfigs()} → {@code loadAllOrThrow()}
     * 会<b>重建</b> {@code cardCatalog}。实时读字段 ⇒ 重载后立刻按新卡池发放，
     * 不会拿着一份过期清单发卡（也顺带让启动日志里的张数永远与当前卡池一致）。
     */
    private ProfileStore newProfileStore() {
        ProfileStore store = new ProfileStore(this, InfernalClashGame.ID);
        // ★ 这就是「接线」本身：把卡池读数交给档案侧（档案侧不认识卡池，也不写死卡名）
        store.bindBasicCardIds(this::basicCardIdsFromPool);
        return store;
    }

    /**
     * ★ <b>R44 基础档的来源</b>：卡池里 {@code rarity == BASIC} 的卡 id（<b>保序</b>）。
     *
     * <p>事实来源<b>只有卡池一处</b>：这里不认卡名、不认数量 ——
     * 挑 BASIC 这一步是纯函数 {@link R44BasicGrants#basicCardIds(Collection)}，
     * 卡池改了（加/删一张基础卡）它自动跟着变。★ 卡片数量由卡池决定，
     * 启动日志会把它印出来（{@code R44 基础档来源=卡池（BASIC 11 张）}），
     * 便于与 {@code 卡池校验通过：51 张卡 / BASIC 11 / …} 那行对账。
     *
     * <p>★ <b>失败方向</b>：卡池拿不到（= 配置校验失败、插件即将被禁用）⇒ 返回<b>空清单</b>
     * ⇒ {@code ProfileStore} 保持「跳过发放」并告警（不崩、也绝不用硬编码卡名兜底）。
     * 跳过是<b>可自愈</b>的：档案仍是空白的 ⇒ 下次真的拿到卡池时还会再发一次。
     */
    private Collection<String> basicCardIdsFromPool() {
        // ★ 每次调用都重读字段（不缓存实例）：/incl debug reload 重建卡池后立即生效
        CardCatalog pool = this.cardCatalog;
        if (pool == null) {
            return List.of();
        }
        return R44BasicGrants.basicCardIds(pool.cards().values());
    }

    /**
     * ★ 局外成长的读写门面（**唯一**碰 MGC {@code playerData} 的地方）。
     *
     * <p>{@code onEnable} 里已建好；这里的兜底只在「配置校验失败 ⇒ 提前 return」之后
     * 才会走到（那时插件已被禁用，指令也不会被执行）—— 走的仍是<b>同一条</b>
     * {@link #newProfileStore()}，所以 R44 的接线不会在这条路径上"忘记接"。
     */
    public ProfileStore profileStore() {
        if (profileStore == null) {
            profileStore = newProfileStore();
        }
        return profileStore;
    }

    /**
     * ★ <b>记录一局结果</b>（P9 排行榜 / 战绩）—— 对局收尾侧应当调用的**唯一入口**。
     *
     * <h2>为什么要在这里开一个口子，而不是让收尾侧直接调 {@code leaderboard/**}</h2>
     * 收尾侧（{@code game/InfernalClashGame}）只需要知道「这个人打完了、结果如何」，
     * 不需要知道榜单 id、档案字段名、MGC 的刷新时机 —— 那些全在
     * {@code leaderboard/LeaderboardWiring} 里（薄适配层）。
     *
     * <h2>★ 失败方向</h2>
     * 一律<b>返回 {@code false} + 告警</b>，<b>绝不抛</b>：战绩记录失败不能打断对局收尾
     * （{@code docs/04} §16.13.4）。人机局会被纯核心直接拒绝（{@code docs/08} §7），
     * 属于正常路径（INFO）。
     *
     * @param playerId 玩家 UUID
     * @param kind     {@code VERSUS_PLAYER} / {@code VERSUS_BOT}
     * @param outcome  {@code WIN} / {@code LOSS} / {@code DRAW}
     * @return 战绩是否真的改了并刷新了榜
     */
    public boolean recordMatchResult(java.util.UUID playerId,
                                     io.mczju.infernalclash.leaderboard.LeaderboardStats.MatchKind kind,
                                     io.mczju.infernalclash.leaderboard.LeaderboardStats.Outcome outcome) {
        return io.mczju.infernalclash.leaderboard.LeaderboardWiring
                .onMatchFinished(playerId, kind, outcome, getLogger());
    }

    /**
     * ★★★ <b>结算一局的金币</b>（2026-10-20 新增）。
     *
     * <h2>它补的是什么</h2>
     * 现场缺陷（用户 2026-10-20 报）：「首场胜利的 3000、每次胜利的 100，打赢人机时都不发」。
     * 核查结论：<b>任何对局结束都不发金币</b> —— {@code economy.yml} 的五个金币键
     * 在生产代码里一个读者都没有（{@link EconomyConfig} 只负责校验与保存），
     * 唯一的 {@code addGold} 调用点是调试指令 {@code /incl debug profile grant}。
     * ⇒ 本方法 = 那条断链的接线点：<b>读档案 → 纯函数算钱 → 加钱 + 场次 +1 → 落盘</b>。
     *
     * <h2>★ 口径（全部来自 {@code economy.yml}，见 {@link MatchPayout}）</h2>
     * <ul>
     *   <li>真人胜 / 真人败 / 人机胜 / 人机败 = {@code gold.win-reward} / {@code lose-reward} /
     *       {@code bot-win-reward} / {@code bot-lose-reward}；</li>
     *   <li>首通 = 结算前 {@code gamesPlayed == 0} 时额外给 {@code gold.first-game-bonus}
     *       （用户 2026-10-20：「第一次完成，含人机，老玩家不补发」）；</li>
     *   <li>平局 = 0 金币（文档没给数值 ⇒ 不发明，登记 {@code docs/05} E28）。</li>
     * </ul>
     *
     * <h2>★ 与战绩的关系（别合并成一步）</h2>
     * 战绩（{@link #recordMatchResult}）只记<b>真人局</b>，而金币<b>人机局照发</b>
     * （Q55：R63 只让排行榜不计人机）⇒ 两条链必须分开调，不能因为「榜拒了」就不发钱。
     *
     * <h2>★★ 失败方向</h2>
     * 一律<b>返回 {@code false} + 告警</b>，<b>绝不抛</b>：它跑在对局收尾的同一条路径上
     * （{@code docs/04} §16.13.4「结算失败不能打断收尾」）。
     * ★ 金币溢出 int 时<b>夹到上限并告警</b>（宁可少给，不可回绕成负数）。
     *
     * @param playerId 玩家 UUID
     * @param kind     {@code VERSUS_PLAYER} / {@code VERSUS_BOT}
     * @param outcome  {@code WIN} / {@code LOSS} / {@code DRAW}
     * @return 是否真的结算并落盘
     */
    public boolean grantMatchReward(java.util.UUID playerId,
                                    io.mczju.infernalclash.leaderboard.LeaderboardStats.MatchKind kind,
                                    io.mczju.infernalclash.leaderboard.LeaderboardStats.Outcome outcome) {
        if (playerId == null) {
            return false;
        }
        MatchPayout.Result result =
                InfernalClashGame.payoutResult(kind, outcome);
        if (result == null) {
            return false;
        }
        try {
            EconomyConfig economy = economyConfig();
            if (economy == null) {
                getLogger().warning("[IC] 对局结算跳过：经济配置不可用（插件正在禁用？）");
                return false;
            }
            PlayerProgress before = profileStore().load(playerId);
            MatchPayout.Payout payout =
                    MatchPayout.of(economy.toExchangeMath(), result, before.gamesPlayed());
            long total = payout.totalGold();
            int headroom = Integer.MAX_VALUE - before.gold();
            int add = (int) Math.min(total, headroom);
            if (add < total) {
                getLogger().warning("[IC] 对局结算的金币超过 int 上限，这次只加 " + add
                        + "（应加 " + total + "）；金币 " + before.gold()
                        + "（见 PlayerProgress 的 int 字段说明）");
            }
            PlayerProgress after = before.addGold(add).withGamePlayed();
            profileStore().save(playerId, after);

            getLogger().info("[IC] 对局结算：" + playerId + "｜本局 " + result
                    + "，金币 +" + payout.matchGold()
                    + (payout.firstGame() ? " ＋首通 +" + payout.firstGameBonus() : "（非首通）")
                    + "｜金币 " + before.gold() + " → " + after.gold()
                    + "｜对局场次 " + before.gamesPlayed() + " → " + after.gamesPlayed());
            // ★★★ 2026-10-21（用户逐字）：「**对局结束时需要在对话框里标注获得的金币数目**」
            //   ⇒ 结算成功后**给这位玩家自己**发一行（胜负两边的数目不同 ⇒ 必须逐个发，
            //     不能"全服播报一个数"）。措辞走 display.yml（{@code match.payout}），
            //     读不到就回退下面的常量（铁律 8 / R76：所有显示文本可配置）。
            tellPayout(playerId, payout, add);
            return true;
        } catch (Throwable t) {
            getLogger().warning("[IC] 对局结算失败，已忽略（不影响对局收尾、不影响房间释放）：" + t);
            return false;
        }
    }

    /**
     * ★★★ <b>把「本局拿到多少金币」告诉玩家</b>（2026-10-21 新增）。
     *
     * <h2>用户原话（逐字）</h2>
     * 「**对局结束时需要在对话框里标注获得的金币数目**」。
     *
     * <h2>★ 三条失败方向（一条都不许把收尾搞挂）</h2>
     * <ol>
     *   <li>玩家**不在线** ⇒ 什么都不做（结算照常落盘 —— 他只是没看到那一行）；</li>
     *   <li>{@code display.yml} 取不到 / 值是占位 ⇒ {@link MenuTexts} 回退到下面的常量并告警一次；</li>
     *   <li>发消息本身抛异常 ⇒ 吞掉（这里已经在 {@code catch (Throwable)} 的同一段收尾路径上）。</li>
     * </ol>
     *
     * <p>★ <b>只按 UUID 单点取玩家</b>（{@code Bukkit.getPlayer(uuid)}）—— 不是
     * {@code getOnlinePlayers()} 那种广域查询（{@code BytecodeScopeGuardTest} 的禁令针对的是后者）。
     *
     * @param playerId 玩家 UUID
     * @param payout   本局的结算明细（对局金币 / 首通 / 合计）
     * @param added    真的加进去的数（金币接近 int 上限时会小于 {@code payout.totalGold()}）
     */
    private void tellPayout(java.util.UUID playerId, MatchPayout.Payout payout, int added) {
        try {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            String line = MenuTexts.resolve(display(), KEY_MATCH_PAYOUT, FALLBACK_MATCH_PAYOUT,
                    java.util.Map.of("gold", String.valueOf(added)),
                    warning -> getLogger().warning("[IC] 文案：" + warning));
            if (line != null && !line.isBlank()) {
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(line));
            }
            if (payout.firstGame()) {
                String bonus = MenuTexts.resolve(display(), KEY_MATCH_PAYOUT_FIRST_GAME,
                        FALLBACK_MATCH_PAYOUT_FIRST_GAME,
                        java.util.Map.of("bonus", String.valueOf(payout.firstGameBonus())),
                        warning -> getLogger().warning("[IC] 文案：" + warning));
                if (bonus != null && !bonus.isBlank()) {
                    player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize(bonus));
                }
            }
        } catch (Throwable t) {
            getLogger().warning("[IC] 发送「本局获得金币」失败，已忽略：" + t);
        }
    }

    /** 「本局获得金币」的文案键（{@code display.yml} 的 {@code messages.match.payout}；占位符 {@code {gold}}）。 */
    public static final String KEY_MATCH_PAYOUT = "match.payout";

    /** 「首通额外奖励」的文案键（占位符 {@code {bonus}}）；只在真的首通时发。 */
    public static final String KEY_MATCH_PAYOUT_FIRST_GAME = "match.payout-first-game";

    /** {@link #KEY_MATCH_PAYOUT} 的回退常量（措辞由我拟初稿，用户随时可改配置）。 */
    public static final String FALLBACK_MATCH_PAYOUT = "<green>本局获得金币 <gold>+{gold}</gold>";

    /** {@link #KEY_MATCH_PAYOUT_FIRST_GAME} 的回退常量。 */
    public static final String FALLBACK_MATCH_PAYOUT_FIRST_GAME =
            "<gold>首次完成对局：额外 +{bonus} 金币</gold>";

    /**
     * 取一名玩家的「身份」（对局侧只需要 UUID + 名字，不需要持有 {@code Player} 引用）。
     *
     * <p>为什么不直接传 {@code Player}：{@code MatchService} 只做时间轴与账目，
     * 不碰实体 —— 这样它的逻辑全部可以离线推演，也让「玩家下线后引用变味」这类问题消失。
     */
    public static MatchService.PlayerName identityOf(Player player) {
        return new MatchService.PlayerName(player.getUniqueId(), player.getName());
    }
}
