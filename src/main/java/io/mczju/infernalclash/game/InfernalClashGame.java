package io.mczju.infernalclash.game;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.GameMeta;
import com.github.mczjuops.mczjugamecore.game.GameState;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.DefaultGameWaitStrategy;
import com.github.mczjuops.mczjugamecore.game.strategy.wait.GameWaitStrategy;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.party.Party;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerDeathStrategy;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerQuitStrategy;
import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.ai.BotDeckResolver;
import io.mczju.infernalclash.ai.BotPlan;
import io.mczju.infernalclash.ai.TrainingBotAssembly;
import io.mczju.infernalclash.ai.TrainingBotDriver;
import io.mczju.infernalclash.ai.TrainingMatchRules;
import io.mczju.infernalclash.battle.CombatDirector;
import io.mczju.infernalclash.config.GameConfig;
import io.mczju.infernalclash.leaderboard.LeaderboardStats;
import io.mczju.infernalclash.match.MatchHooks;
import io.mczju.infernalclash.match.MatchOutcome;
import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.policy.MatchPayout;
import io.mczju.infernalclash.ui.MatchHud;
import io.mczju.infernalclash.objective.ObjectiveKind;
import io.mczju.infernalclash.objective.ObjectiveService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 对局主类：MGC 的 {@link AbstractGame} 子类。
 *
 * <h2>两条实测约束</h2>
 * <ul>
 *   <li><b>必须有无参构造</b> —— MGC 通过反射实例化（{@code createGame}），
 *       所以取插件实例只能走 {@link InfernalClashPlugin#getInstance()}（Maggoteers 的验证做法）。
 *       本类因此不能有带参构造，也不能是内部类。</li>
 *   <li>{@code Sender#info/warn/error} 只接受 <b>String</b>（MiniMessage 文本），不是 Component。</li>
 * </ul>
 *
 * <h2>★ P2 起的行为：真的开一局</h2>
 * {@link #onGameStart()} 会建立本局的 {@link MatchService}，由插件那个**单个**全局 1-tick
 * 任务驱动时间轴：{@code PREPARING(1200) → BATTLE(3600) → OVERTIME(1200) → ENDED}。
 * 结束时走 {@link #endViaManager()}（**必须**走 {@code GameManager}，否则房间停在 {@code IN_GAME}）。
 *
 * <p>★ P2 还没有塔与信标 ⇒ 传给状态机的信号恒为空 ⇒ 战斗耗尽**必然进入加时**、
 * 加时结束仍相等 ⇒ **平局**。这不是缺陷，而是「先把时间轴跑通」的刻意口径：
 * P3 填进目标物后胜负矩阵自动生效，**状态机本身不用改**。
 *
 * <p>底座期那条「开局即结束」的自检行为**已被取代**（它的历史用途与踩过的坑见
 * {@link #endViaManager()} 的注释与 {@code dev-log/2026-10-15-006}）。
 */
public final class InfernalClashGame extends AbstractGame {

    /** MGC 的游戏 id（全小写）。 */
    public static final String ID = "infernalclash";

    /** 图标名非法时的回退值（启动期已就非法值告警，见 {@code InfernalClashPlugin}）。 */
    private static final Material FALLBACK_ICON = Material.NETHER_STAR;

    /** 清理幂等保护：{@code abortGame} 与 {@code endGame} 都可能走到清理。 */
    private volatile boolean cleanedUp = false;

    /** 本局的运行时（对局开始时创建、清理时停止）；未开局时为 {@code null}。 */
    private volatile MatchService match;

    /** 本局的目标物系统（信标 / 防御塔）；未开局或点位不全时为 {@code null} / 空。 */
    private volatile ObjectiveService objectives;

    /** 本局的快捷栏发放（1 号「战斗」物品 + 2~9 号卡牌）。 */
    private volatile CardLoadout loadout;

    /** 本局的战斗指挥（P5：唯一算目标的地方 + AI 装载）。 */
    private volatile CombatDirector combat;

    /**
     * ★★ <b>本局的训练人机执行层</b>（A7 / E13）——
     * 只有「1 名真人 ＋ 有人敲过 {@code /incl bot on}」才非 {@code null}。
     *
     * <p>它接在 <b>对局时钟</b>上（{@link io.mczju.infernalclash.match.MatchHooks#onTick}，
     * 源头是插件那一个全局 1-tick 任务）⇒ 收尾时随 {@code MatchService.stop} 一起停，
     * <b>没有</b>需要单独取消的任务。
     *
     * <p>⚠️ 这里原写「只有训练局（<b>单人开局</b>，R62）非 {@code null}」——
     * ★ 「单人开局<b>自动</b>开」已被 2026-10-15 用户裁定取代为「<b>显式</b>敲 {@code /incl bot on}」
     * （{@code docs/24} §5.5）⇒ 判定改走两维纯规则
     * {@link TrainingMatchRules#botEnabledFor(int, boolean)}。
     */
    private volatile TrainingBotDriver trainingBot;

    /**
     * ★★ <b>本局抽中的那个人机的名字</b>（2026-10-21 用户裁定：多套人机、每局随机抽一个）。
     *
     * <p>它是<b>名册里那条 {@code PlayerName}</b> 的名字（{@code training.bots[].name}），
     * 也就是侧边栏那一行显示的东西（{@code MatchService.SideState#name()} ⇒
     * {@code policy/MatchTimerPolicy#sideHeader}）。★ 纯文本，不解析 MiniMessage（用户裁定）。
     *
     * <p>★ 为空 = 本局没人机（两名真人 / 没敲过 {@code /incl bot on} / 装配失败）。
     */
    private volatile String botName;

    /**
     * ★★ <b>本局解析后的 8 张卡 id</b>（「随机」占位符已经换掉）—— 2026-10-21 新增。
     *
     * <p>★ 为什么必须缓存：「随机」的口径是「**进入对战时**该占位符将会替换为任意一张随机合法卡牌」
     * ⇒ 一局之内必须稳定。每 tick 重解析会让玩家看到人机的牌序乱跳。
     */
    private volatile java.util.List<String> resolvedBotDeck;

    /**
     * ★ <b>人机在哪一侧</b>（2026-10-21 追加）—— 「偷牌」要按**对面（真人）**的卡组解析。
     *
     * <p>{@code null} = 本局没人机。清理时与 {@link #botName} 一起置空。
     */
    private volatile Side botSide;

    /**
     * ★ <b>抽人机 / 解析「随机」用的随机源</b>（2026-10-21）。
     *
     * <p>★ 每局从这里取；离线测试要可复现时用包级 setter 换掉（同种子 ⇒ 同结果）。
     */
    private java.util.Random botRandom = new java.util.Random();

    /** 换随机源（只给离线测试用；生产路径不调）。 */
    void setBotRandom(java.util.Random source) {
        this.botRandom = source == null ? new java.util.Random() : source;
    }

    /** 本局抽中的人机名字（探针 / 测试用；没人机 ⇒ {@code null}）。 */
    String botName() {
        return botName;
    }

    /** 本局解析后的卡组（探针 / 测试用；没人机 ⇒ {@code null}）。 */
    java.util.List<String> resolvedBotDeck() {
        return resolvedBotDeck == null ? null : java.util.List.copyOf(resolvedBotDeck);
    }

    /**
     * ★★ <b>这个房间有没有人敲过 {@code /incl bot on}（且还没 off / 还没开局）</b>
     * —— 2026-10-15 用户裁定的显式开关，{@link #requestBot(boolean)} 是唯一写入口。
     *
     * <h2>★★ 为什么落在<b>对局实例</b>上，而<b>不是</b> {@code game/InfernalClashRoom}</h2>
     * 任务书建议放在 {@code InfernalClashRoom}（"每房间一个布尔"）。<b>实测后我改了落点</b>，
     * 依据是 {@code javap} 反汇编（{@code dev-log/2026-10-15-023} §三）：
     * <ul>
     *   <li>★ {@code JsonGameRoom} 的 {@code save()} 走 <b>Gson {@code toJson(this)}</b>
     *       —— Gson 反射<b>全部</b>实例字段（不看可见性）⇒ 房间类上多一个布尔，
     *       这个开关就会被<b>写进</b> {@code MCZJUGameCore/rooms/infernalclash/<房间>.json}
     *       （现场那份现在是 {@code {}}）⇒ 它会<b>跨重启保留</b>，
     *       与"状态要清零"的要求直接冲突；</li>
     *   <li>★ 且 {@code JsonMGCSerializable.getAllFields()} 走
     *       {@code Class.getDeclaredFields()}（不是 {@code getFields()}），
     *       而 MGC 的房间参数编辑菜单用 {@code RoomParameterEditor.supports(Boolean)} = true
     *       ⇒ 这个布尔会<b>出现在 {@code /mgcop room edit} 的可编辑参数里</b>
     *       —— 那是一次用户没要求的<b>管理员界面改动</b>；</li>
     *   <li>★ 而<b>对局实例本身就是"这一间房这一次等待"的载体</b>：
     *       MGC 的 {@code DefaultGameManager.joinGame} 先 {@code getGame(gameId, roomName)}
     *       复用<b>同一个</b> game 给后续加入的玩家，而 {@code solveGameEnd}
     *       会 {@code gameList.remove(game)} ⇒ <b>下一局必然是新的实例</b>，
     *       标记天然清零、天然不串局、天然不落盘（{@code AbstractGame} 不实现
     *       {@code MGCSerializable}）。</li>
     * </ul>
     * ⇒ 落点从"房间"上移到"对局"，语义<b>仍然是"每房间一个布尔"</b>
     * （一间房在等待期最多只有一个 game 实例）。
     */
    private volatile boolean botRequested = false;

    /**
     * ★ 敲下 {@code /incl bot on} 那一刻的服务端 tick（{@code Bukkit.getCurrentTick()}）；
     * {@code -1} = 没请求 / 时钟取不到（见 {@link #currentTickOrMinusOne()}）。
     *
     * <p>用它算窗口剩余，而<b>不是</b>用墙钟。
     * ★★★ <b>2026-10-19 更正</b>：这里原本写着「调度器与这个方法读的是同一个计数器
     * ⇒ 延迟 N tick 的任务跑起来时剩余必然是 0」。**现场证明这句话是错的**：
     * {@code latest.log} 19:41:22 排窗口、19:41:23 任务真的跑了，而
     * {@code getCurrentTick() - botRequestedAtTick} 算出来是 **19** ⇒ 剩余 **1**
     * ⇒ {@code tryStart()} 走"还没到"分支 return，而窗口任务是**一次性**的
     * ⇒ 之后没有任何东西再推它，房间一直停到玩家手敲 {@code /mgc start}（19:42:31）。
     * ⇒ 真正的判据改成 {@link #botWindowElapsed}（见 {@link #botWindowRemainingTicks()}）。
     */
    private volatile int botRequestedAtTick = -1;

    /**
     * ★★★ <b>那个一次性窗口任务是否已经跑过</b>（2026-10-19 为修「1 真人 ＋ 1 人机不会自动开局」新增）。
     *
     * <p>语义：<b>窗口任务跑过了 ⇒ 窗口一定已满</b>。不再拿"两个时钟相减"赌那 1 tick
     * 的偏差（现场实测偏差过 1 次，代价是整局开不起来）。
     * ★ 复位时机与 {@link #botRequested} 完全一致：{@code /incl bot on} 时置
     * {@code false}，{@link #clearBotRequest()} 时置 {@code false}。
     */
    private volatile boolean botWindowElapsed = false;

    /**
     * ★ 那个「窗口到期后就试着开局」的一次性任务（{@link #BOT_START_WINDOW_TICKS} 之后跑）。
     *
     * <p>为什么必须有它：★ <b>MGC 不会自己再来调用一次 {@code tryStart()}</b>
     * （{@code javap} 实测：{@code DefaultGameWaitStrategy.onJoin} 只在
     * {@code 人数 == playerLimit} 时直接 {@code startGame()}，其余情况不调 {@code tryStart}；
     * 全 MGC 1.1.2 里没有第二个会定时调它的地方）
     * ⇒ 1 真人 ＋ 人机这一局<b>只能由我们自己</b>在窗口到期时把开局推起来。
     * 跑完/被取消后置回 {@code null}。
     */
    private volatile BukkitTask botWindowTask;

    /**
     * ★ <b>本局的参与者快照</b>（{@code 阵营 → 玩家 UUID}）—— 开局时捕获，收尾记账时要用它。
     *
     * <h2>为什么必须自己留一份（不能到收尾时现问）</h2>
     * <ul>
     *   <li><b>正常结束</b>：{@code MatchService.finish()} 先 {@code stop("结束")}，而 {@code stop}
     *       的最后一步是 {@code sides.clear()} ⇒ 等到 {@link #cleanup(String)} 时运行时里的名单<b>已经空了</b>；</li>
     *   <li><b>最后一名玩家离开</b>：退出回调发生在 {@code playerGameMap.remove} <b>之后</b>
     *       （{@code AGENTS.md} §4.2 的实测）⇒ 那时 {@code getPlayers()} 也是空的。</li>
     * </ul>
     * ⇒ 唯一可靠的取法就是开局留快照。
     *
     * <p>★ 取值来源与阵营判定是 {@link MatchService#sideAssignments(List) 同一处} ——
     * 在收尾时再算一遍会出现「传送按一种顺序分阵营、记账按另一种」这种极难查的不一致。
     * 清理时与 {@code joinLocations} 一起复位（房间会被复用，下一局不能沿用上一局的名册）。
     */
    private volatile Map<Side, UUID> participants = Map.of();

    /**
     * 玩家**入局前**的位置，供局末回程兜底（回退链的第三环）。
     *
     * <p>包内可见：{@link InfernalClashQuitStrategy} 单人退出时也要走同一条回程。
     * 只在**本局**有效 —— 清理时必须清空，否则复用房间的下一局会用错兜底位置。
     */
    final Map<UUID, Location> joinLocations = new HashMap<>();

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GameMeta getGameMeta() {
        GameConfig.Meta meta = plugin().gameConfig().meta();
        return GameMeta.builder()
                .displayName(meta.name())
                .icon(resolveIcon(meta.icon()))
                .author(meta.author())
                .description(meta.description())
                .build();
    }

    /** 解析配置里的图标名；非法时回退（启动期已经就此告警，不重复刷屏）。 */
    private static Material resolveIcon(String name) {
        Material material = Material.matchMaterial(name);
        return material == null ? FALLBACK_ICON : material;
    }

    /**
     * 等待策略：入局时送到集合点 + 开局人数门槛。
     *
     * <p>★ 为什么开局判据挂在这里：MGC 的游戏生命周期里**没有 {@code onPlayerJoin} 回调**
     * （{@code AbstractGame} 实测只有 gameInit/gameStart/cancelGame/abortGame/endGame），
     * 玩家入局的唯一挂钩点就是 {@link GameWaitStrategy}。
     *
     * <p>★ 为什么必须是 {@link DefaultGameWaitStrategy} 的匿名子类，
     * 而不是直接继承 {@code GameWaitStrategy}：后者的构造器是
     * <b>package-private</b>（{@code GameWaitStrategy(AbstractGame)}，javap 实测），
     * 跨包子类无法调用 {@code super(...)}。
     *
     * <h2>★★ 传进去的 {@code maxPlayers} 有两个副作用（2026-10-16 {@code javap} 实测）</h2>
     * 第二个构造参数就是 MGC 内部的 {@code private final int playerLimit}，它不只做校验：
     * <ol>
     *   <li>★ <b>游戏内那句「加入了该游戏（1/N）」的 N 就是它</b>
     *       （{@code DefaultGameWaitStrategy.onJoin} 里的 {@code 玩家 %s 加入了该游戏（%d/%d）}，
     *       第二个数读的正是 {@code playerLimit}）⇒ 用户 2026-10-16 报告的「仍显示 1/10」
     *       来源就是这个键（{@code config.yml} 的 {@code game.max-players}，
     *       已按用户裁定改成 2 ⇒ 现在显示 {@code 1/2}）；</li>
     *   <li>★★ <b>满员旁路</b>：同一次实测，{@code onJoin} 在
     *       <b>{@code 人数 == playerLimit}</b> 时会<b>自己 {@code startGame()}</b>
     *       （{@code 153: iload_2 / 155: getfield playerLimit / 158: if_icmpne / 162: startGame}）
     *       ⇒ ★ 上限 = 2 之后，<b>第二名真人一进房就自动开局</b>，
     *       <b>而这条路径根本不经过我们的 {@link #getGameWaitStrategy()} 子类</b>
     *       —— 也就是不过 {@link StartGate}、不过人机判据（那是对的：2 名真人本来就该走
     *       MGC 原生的满员开局，人机不上场）。★ 同一段字节码里还有一条：
     *       {@code 人数 > playerLimit} ⇒ 告警 + <b>拒绝加入</b>（第三个人进不来）。</li>
     * </ol>
     * ⇒ ★ 结论：开局一共有三条路 —— ① MGC 满员自动开局（2 真人，旁路，不过我们）；
     * ② {@code /mgc start} 与我们的窗口任务 ⇒ 我们子类的 {@code tryStart()}（真人够 ⇒ 交给基类）；
     * ③ 同一处 {@code tryStart()} 在「真人不够但已请求人机」时直接 {@code startGame()}。
     * 三条都在 {@code docs/12} §4.18 / §4.24 有逐条核对步骤。
     */
    @Override
    public GameWaitStrategy getGameWaitStrategy() {
        GameConfig config = plugin().gameConfig();
        return new DefaultGameWaitStrategy(this, config.maxPlayers(), config.minPlayers()) {

            @Override
            public boolean onPlayerJoin(PlayerExt player) {
                boolean joined = super.onPlayerJoin(player);
                if (joined && player != null && player.player() != null) {
                    InfernalClashRoom.rememberJoinLocation(joinLocations, player.player());
                    teleportWaitIfStillWaiting("onJoin", player.player());
                }
                return joined;
            }

            @Override
            public boolean onPartyJoin(Party party) {
                boolean joined = super.onPartyJoin(party);
                if (joined && party != null) {
                    for (PlayerExt member : party.getAllPlayer()) {
                        if (member != null && member.player() != null) {
                            InfernalClashRoom.rememberJoinLocation(joinLocations, member.player());
                            teleportWaitIfStillWaiting("onPartyJoin", member.player());
                        }
                    }
                }
                return joined;
            }

            /**
             * ★★★ <b>「送到等待点」必须只在**真的还在等人**的时候做</b>（2026-10-21 修）。
             *
             * <h2>用户报的缺陷（逐字）</h2>
             * 「**在准备阶段，只有蓝方被传送进场地，红方依旧在场外**」。
             *
             * <h2>根因（MGC 的满员旁路 ＋ 我们的调用顺序）</h2>
             * {@code DefaultGameWaitStrategy.onJoin} 在<b>人数 == playerLimit</b> 时
             * <b>自己调 {@code startGame()}</b>（{@code javap} 实测，见本方法上方那段 javadoc）。
             * 而我们的 override 是：
             * <pre>
             *   super.onPlayerJoin(player)   ← 第二名真人一进来，**局就在这里开了**
             *                                （onGameStart → 准备阶段钩子 → 把双方送进竞技场）
             *   teleportWait(...)            ← 又把**刚刚那位**拽回等待点
             * </pre>
             * ⇒ 蓝方（先进、等待点早就传过了）留在场地里，**红方（后进那位）被拽回场外**。
             * ★ 现场日志正好是这个形状（16:43:26 同一秒）：
             * <pre>
             *   _YamatoMaya_ 加入了该游戏（2/2）
             *   [IC] 对局开始｜真人 2 名
             *   [IC] 准备阶段开始：已传送 2 名玩家到出生点（matchId=default）  ← 两人都真的进去过
             *   （然后红方被这一步拽回等待点 —— 它成功时不打日志，所以日志里看不出来）
             * </pre>
             *
             * <h2>修法</h2>
             * 传之前先问一句「局还停在等待状态吗」：{@code getState() == WAITING} 才传；
             * 已经开打（{@code STATING} / {@code RUNNING}）⇒ <b>不传</b>，并留一行日志
             * （★ 这一行是给下次排障用的：以前它成功时一声不响，所以"人被拽走了"在日志里**看不见**）。
             *
             * <p>★ 为什么不把 {@code teleportWait} 挪到 {@code super} 之前：那时还不知道
             * 这次加入有没有被接受（{@code joined} 可能是 false —— 人满 / 被拒），
             * 传了就等于把"没进来的人"也搬到等待点。
             */
            private void teleportWaitIfStillWaiting(String trigger, Player target) {
                GameState state = getState();
                if (state != GameState.WAITING) {
                    // ★ 满员那一下局已经开了：此时玩家**已经被准备阶段钩子送进竞技场**，
                    //   再传等待点就是往回拽（用户看到的「红方依旧在场外」）。
                    plugin().getLogger().info("[IC] 跳过等待点传送（" + trigger + "）：局已经开了"
                            + "（state=" + state + "），玩家 " + target.getName()
                            + " 正由准备阶段/战斗开始的传送负责（2026-10-21 修「红方被拽回场外」）");
                    return;
                }
                InfernalClashRoom.teleportWait(InfernalClashGame.this, target);
            }

            /**
             * ★★ 开局判据 = {@link StartGate}（纯函数 ＋ 单测）＋ <b>显式人机</b>
             * （2026-10-15 用户裁定，留档 {@code docs/24} §5.5）。
             *
             * <h2>★ 有效参战人数（用户裁定的 B 方案：{@code min-players} 仍然是 2）</h2>
             * <pre>
             *   effective = TrainingMatchRules.effectiveCombatants(humans, botRequested)
             *             = humans + (botRequested &amp;&amp; humans == 1 ? 1 : 0)
             * </pre>
             * ⇒ 「1 真人 ＋ 已请求的人机」满足 2 人门槛；而 <b>2 真人时人机不计</b>
             * （用户原话「存在人机时进来第二个人也能正常开战」⇒ 那就是正常 1v1）。
             *
             * <h2>★★ 窗口（用户裁定的"开启后不能立刻开局"）</h2>
             * 「1 真人 ＋ 人机」这一种局，敲下 {@code /incl bot on} 之后<b>还要等</b>
             * {@link TrainingMatchRules#BOT_START_WINDOW_TICKS} tick 才真的开局
             * —— 那段窗口就是留给"第二个人进房"的。判据放在 <b>{@code tryStart()} 里面</b>
             * 而不是只放在那个窗口任务里，理由是：{@code /mgc start} 也会调到这里
             * （{@code javap} 实测 MGCCommand 走的就是 {@code getGameWaitStrategy().tryStart()}），
             * 若窗口只挡我们自己的任务，玩家敲一句 {@code /mgc start} 就绕过去了。
             *
             * <p>人数不够时**只警告**：既不调 {@code startGame()} 也不调 {@code cancelGame()}
             * ⇒ 等待房间原样保留，玩家可以继续等人。
             * MGC 自己的 {@code tryStart()} 本来就是这个行为（warn 后 return），
             * 我们只是把**判据**换成可测的纯函数。
             *
             * <h2>★★ 2026-10-16 修的一跤：「判据放行了，局却没开」</h2>
             * 用户实机（{@code /incl bot on} 之后 1 秒窗口到期）看到的是
             * <b>「既没有『对局开始』、也没有第二次『开局被拦下』」</b>——
             * 因为 <b>MGC 基类又按真人数判了一次</b>：
             * {@code DefaultGameWaitStrategy.tryStart()} 里是
             * {@code getPlayers().size() < minPlayer ⇒ sender().warn(…) 然后 return}
             * （{@code javap} 实测；{@code minPlayer} 是 {@code private final}，运行期改不了），
             * 而那句 {@code warn} 走 {@code AbstractGame.sender}（{@code GameSender}）
             * ⇒ <b>只到房间内玩家的聊天，不进控制台</b> ⇒ 现场看起来「什么都没发生」。
             *
             * <p>★ 所以放行不再只有一条出口，而是<b>按纯函数
             * {@link TrainingMatchRules#startPath(int, boolean, int)} 的三态分派</b>：
             * <pre>
             *   REFUSE              ⇒ 只警告（现状不变）
             *   SUPER_TRY_START     ⇒ super.tryStart()（真人自己够；/mgc start 也走这条）
             *   START_GAME_DIRECTLY ⇒ startGame()（★ 公开入口：真人不够但已请求的人机补足；
             *                          避开基类那次「按真人数重判」）
             * </pre>
             * ★ 为什么<b>不</b>把传给基类的 {@code minPlayer} 改成 1：那会让「基类门槛」与
             * {@code config.yml} 的 {@code game.min-players} 分叉成两个数
             * —— 就是本项目栽过的「第二处白名单」坑（{@code AGENTS.md} §0 末尾）；
             * 完整理由写在 {@link TrainingMatchRules.StartPath} 的 javadoc 里。
             *
             * <p>★ <b>每一条路径都留一行控制台日志</b>（含 {@code super.tryStart()} <b>返回后</b>的
             * {@code getState()}）—— 就是为了让「基类拒了」下次一眼可见，不再有日志盲点。
             * ★ 日志只打真人数量与状态，<b>不打玩家 UUID</b>（与既有风格一致）。
             */
            @Override
            public void tryStart() {
                int humans = getPlayers().size();
                int need = plugin().gameConfig().minPlayers();
                boolean requested = botRequested;
                int effective = TrainingMatchRules.effectiveCombatants(humans, requested);
                TrainingMatchRules.StartPath path =
                        TrainingMatchRules.startPath(humans, requested, need);

                if (path == TrainingMatchRules.StartPath.REFUSE) {
                    game.sender().warn(StartGate.blockedMessage(humans, need));
                    logger.info("[IC] 开局被拦下（等待房间保留）：真人 " + humans
                            + " 名，至少需要 " + need + " 名"
                            + "（有效参战人数 " + effective
                            + " = 真人 " + humans + " ＋ 人机 " + (effective - humans) + "）");
                    return;
                }
                // ★ 窗口只在"1 真人 ＋ 人机"这一种局生效；2 真人不必等（人机不上场）
                if (TrainingMatchRules.botEnabledFor(humans, requested)) {
                    int left = botWindowRemainingTicks();
                    if (left > 0) {
                        // ★ 配置里把 `match.bot-window` 留空 ⇒ 这一行不发（不发空行）
                        String windowText = botWindowText(humans, need, left);
                        if (windowText != null && !windowText.isBlank()) {
                            game.sender().warn(windowText);
                        }
                        logger.info("[IC] 人机已请求，但开局窗口还没到（还剩 " + left + " / "
                                + TrainingMatchRules.BOT_START_WINDOW_TICKS + " tick）"
                                + "，房间保留：等第二名玩家进来，或窗口到期后由窗口任务推起开局");
                        return;
                    }
                }

                if (path == TrainingMatchRules.StartPath.START_GAME_DIRECTLY) {
                    startGameDirectly(humans, need, effective);
                    return;
                }

                // ---- 路径 ①：真人自己就够 ⇒ 原样交给 MGC（/mgc start 走的也是这条） ----
                logger.info("[IC] 开局判据通过（真人 " + humans + " ≥ 需要 " + need
                        + "），交给 MGC 开局");
                super.tryStart();
                // ★ 日志盲点补丁：基类那句拒绝**只发玩家聊天、不进控制台** ⇒ 没有这一行，
                //   「基类拒了」在现场就是「什么都没发生」（2026-10-16 用户实机撞到的那一跤）。
                logger.info("[IC] 基类 tryStart 返回后 state=" + getState()
                        + "（WAITING，基类按真人数拒了这次开局；STATING/RUNNING，已开局）");
            }

            /**
             * ★★ <b>路径 ②：真人不够、但【已请求的人机】把有效人数补到门槛</b>
             * ⇒ 直接走 {@code GameWaitStrategy#startGame()}。
             *
             * <h2>为什么这条入口是合法的</h2>
             * {@code javap} 实测（2026-10-16，MGC 1.1.2）：
             * {@code GameWaitStrategy}（本策略的爷爷类）有
             * <b>{@code public void startGame()}</b> —— 方法体就是
             * {@code MCZJUGameCore.getGameManager().startGame(game)}，
             * 与基类 {@code tryStart()} 放行后调的是<b>同一个</b>东西
             * ⇒ 我们没有另造一条开局流程，只是<b>绕开了那次重复的人数判定</b>。
             *
             * <h2>★★ 三道防护（宁可不开局，也不要重复开局）</h2>
             * <ol>
             *   <li><b>幂等</b>：调之前先看 {@code getState()}，
             *       <b>不是 {@code WAITING} 就直接 return</b> ——
             *       {@code DefaultGameManager.startGame} 只是
             *       {@code game.gameStart()}（把 state 置 STATING → {@code onGameStart()} → RUNNING），
             *       <b>自己不判状态</b>（{@code javap} 实测）⇒ 从 IN_GAME/RUNNING 再调一次
             *       会让 {@code onGameStart()} 跑第二遍（重复建 MatchService、重复发快捷栏）。</li>
             *   <li><b>消费窗口</b>：开局本身会走 {@code onGameStart()} →
             *       {@code clearBotRequest()}（清标记 ＋ 取消窗口任务）—— 既有逻辑照旧，
             *       这里<b>不重排窗口</b>（重排 = 失败后无限循环）。</li>
             *   <li><b>失败方向</b>：任何意外只 {@code warn} ＋ 不重试；
             *       房间仍是等待态，玩家可以敲 {@code /mgc start} 或重新 {@code /incl bot on}。</li>
             *   <li>★★ <b>2026-10-16（第七轮）：两条失败路径必须分开报，且都带上 {@code getState()}</b>
             *       —— 见 {@link InfernalClashGame#startFailureWarning}。
             *       现场吃过一次亏：{@code onGameStart()} <b>内部</b>抛的异常也会落到这个 catch 里，
             *       而旧文案统一写成「直接 {@code startGame()} 失败（房间仍是等待态）」⇒
             *       它把运维指向了完全错误的方向（那时局<b>已经</b>开了、目标物都生成了，
             *       真正的问题是「开局回调里的后续步骤炸了」）。</li>
             * </ol>
             */
            private void startGameDirectly(int humans, int need, int effective) {
                GameState state = getState();
                if (state != GameState.WAITING) {
                    // ★ 幂等护栏：已经开过 / 已经在跑 / 已经收尾 ⇒ 什么都不做（只记一行）
                    logger.info("[IC] 人机补位开局被跳过：state=" + state
                            + "（不是 WAITING），幂等护栏生效，不重复开局");
                    return;
                }
                logger.info("[IC] 开局判据通过（真人 " + humans + " ＜ 需要 " + need
                        + "，但已请求人机，有效 " + effective + "）"
                        + "，直接 startGame()（避开基类按真人数重判）");
                try {
                    startGame();
                    logger.info("[IC] 直接 startGame() 返回后 state=" + getState()
                            + "（STATING/RUNNING，已开局；仍 WAITING，没生效，见上面的告警）");
                } catch (Throwable t) {
                    // ★★ 失败方向：只告警，不重试、不重复开局；★ 文案按**当时的状态**分流
                    //    （「startGame() 自己炸了」与「局开了、但开局回调里的后续步骤炸了」
                    //     是两件完全不同的事，见 InfernalClashGame#startFailureWarning）
                    logger.warn(startFailureWarning(getState(), t));
                }
            }
        };
    }

    // ---------------- ★★ 显式人机开关（/incl bot on|off，2026-10-15 用户裁定） ----------------

    /**
     * ★ <b>找出发令者所在的那一场本游戏对局</b>（不在 ⇒ {@code null}）。
     *
     * <h2>★ 范围界定（铁律 10 的四问之一）</h2>
     * 走 MGC 的 {@code AbstractPlayerManager.getPlayerGame(PlayerExt)} —— 它是
     * <b>按玩家单点查一张 map</b>（{@code javap} 实测：方法体就是
     * {@code playerGameMap.get(ext)}），<b>不是</b>遍历世界实体、也<b>不是</b>遍历在线玩家。
     * ★ {@code PlayerExt} 的 {@code equals} 是定制的（比较 {@code player.getUniqueId()}，
     * 不是 record 默认的字段引用比较）⇒ 现造一个 {@code new PlayerExt(player)} 查得到。
     *
     * <h2>★ 失败方向（fail-safe）</h2>
     * MGC 未就绪（{@code getPlayerManager()} 为 {@code null}）/ 关服中 / 离线单测
     * ⇒ 一律返回 {@code null} = 「不在任何对局里」⇒ 调用方<b>什么都不改</b>，只回一句人话。
     * 这是**失败方向的正确一侧**：判据取不到时宁可拒绝，绝不顺手改"别的那一场"。
     *
     * <p>★ 而且它不抛：不用异常表达"没找到"（那是正常路径，不是编程错误）。
     */
    public static InfernalClashGame ofPlayer(Player player) {
        if (player == null) {
            return null;
        }
        try {
            AbstractGame game = MCZJUGameCore.getPlayerManager().getPlayerGame(new PlayerExt(player));
            return game instanceof InfernalClashGame clash ? clash : null;
        } catch (Throwable t) {
            InfernalClashPlugin p = InfernalClashPlugin.getInstance();
            if (p != null) {
                p.getLogger().warning("[IC] 查「发令者所在的那一场」失败，当作「不在任何对局里」"
                        + "（什么都不改）：" + t);
            }
            return null;
        }
    }

    /** ★ 这个房间有没有人敲过 {@code /incl bot on}（且还没 off / 还没开局）。 */
    public boolean botRequested() {
        return botRequested;
    }

    /** 窗口还剩多少 tick（{@code 0} = 已满，可以开局）。 */
    int botWindowRemainingTicks() {
        // ★★ 2026-10-19：一次性窗口任务跑过了 ⇒ 窗口**一定**已满（不再依赖两个时钟相减：
        //   现场实测它们会差 1 tick ⇒ 直接把「1 真人 ＋ 1 人机」的自动开局卡死）。
        if (botWindowElapsed) {
            return 0;
        }
        if (botRequestedAtTick < 0) {
            return 0;   // 时钟取不到 ⇒ 不拿窗口卡住玩家（见 currentTickOrMinusOne 的失败方向）
        }
        return TrainingMatchRules.botWindowRemainingTicks(
                Bukkit.getCurrentTick() - botRequestedAtTick, false);
    }

    /**
     * ★★ <b>「还在等第二名玩家」那一行的文案</b>（2026-10-19：改成读 {@code display.yml}）。
     *
     * <p>用户逐字：「**之前要求删掉的大段带用户裁定的文本又回来了**」。
     * ★ 根因不是上一轮没改干净，而是**这一条是另一处发出的**：
     * 上一轮只压了 {@code command/InfernalClashCommand#runBot} 自己的几行，
     * 而这一条来自 {@link InfernalClashGame} 的 {@code tryStart()}（走 {@code GameSender}，
     * 所以看起来像"同一串提示"）。
     *
     * <p>⇒ ① 措辞缩成一行、**不带任何裁定引文**；② 出口改为
     * {@code display.yml} 的 {@code messages.match.bot-window}
     * （占位符 {@code {humans}} / {@code {need}} / {@code {seconds}}）。
     * 取不到配置（插件未就绪 / 值是占位 / 解析失败）⇒ 回退到
     * {@link StartGate#botWindowMessage(int, int, int)}（**绝不**因为取不到文案就不提示）。
     */
    private String botWindowText(int humans, int need, int ticksLeft) {
        int left = Math.max(0, ticksLeft);
        InfernalClashPlugin plugin = plugin();
        if (plugin != null && plugin.display() != null) {
            try {
                return plugin.display().format("match.bot-window", java.util.Map.of(
                        "humans", String.valueOf(humans),
                        "need", String.valueOf(Math.max(1, need)),
                        "seconds", String.format("%.1f", left / 20.0)));
            } catch (RuntimeException ignored) {
                // 取不到 / 占位符没给全 / 值是 [待填] ⇒ 走下面的回退常量（不抛给玩家）
            }
        }
        return StartGate.botWindowMessage(humans, need, left);
    }

    /**
     * ★★ <b>开/关本房间的人机</b>（{@code /incl bot on|off} 的落点）。
     *
     * <h2>语义（用户原话：「房间里任何一人敲」/「关掉人机需要敲」）</h2>
     * <ul>
     *   <li>{@code on} ⇒ 标记 ＋ <b>排一个 {@link TrainingMatchRules#BOT_START_WINDOW_TICKS}
     *       tick 后的一次性任务</b>去试开局（窗口的判据仍在 {@code tryStart()} 里）；</li>
     *   <li>{@code off} ⇒ 清标记 ＋ <b>取消</b>那个任务（否则窗口到期会拿旧标记把局推起来）；</li>
     *   <li>★ <b>值没变就什么都不做</b>（也不重排窗口）⇒ 连敲两次 {@code on} 不会
     *       "谎报一次改动"，也不会把窗口无限往后推。</li>
     * </ul>
     *
     * <h2>★ 失败方向</h2>
     * 插件未就绪（关服中）⇒ 只改内存标记、<b>不排任务</b>（不排 = 不会开局；排了才会抛
     * {@code IllegalPluginAccessException}）。{@code BukkitTask.cancel()} 被 try/catch 包住。
     *
     * @return 值是否真的变了（{@code false} = 本来就是该值 ⇒ 调用方应如实说"没有变化"）
     */
    public boolean requestBot(boolean on) {
        if (botRequested == on) {
            return false;
        }
        if (on) {
            this.botRequested = true;
            this.botRequestedAtTick = currentTickOrMinusOne();
            this.botWindowElapsed = false;   // ★ 新窗口：还没到点
            scheduleBotWindow();
        } else {
            clearBotRequest();
        }
        return true;
    }

    /**
     * ★ <b>排窗口任务</b>（窗口到期 ⇒ 试一次开局）。任何取不到调度器的情况都只告警、不抛。
     *
     * <p>★ 这个任务<b>不是清理型延时</b>（{@code AGENTS.md} 点名的反模式：用
     * {@code runTaskLater} 承担必要清理，重启/卸载即丢）：它只是"再试一次开局"的<b>加速器</b>
     * —— 真正的判据（人数 / 窗口 / 人机）都在 {@code tryStart()} 里，任务丢了最多是
     * 这一局要等玩家自己敲一次 {@code /mgc start}，不会留下任何半截状态。
     */
    private void scheduleBotWindow() {
        cancelBotWindowTask();
        InfernalClashPlugin plugin = plugin();
        if (plugin == null || !plugin.isEnabled()) {
            logger.warn("[IC] 人机已标记，但插件未就绪，不排窗口任务（不会自己开局；"
                    + "可敲 /mgc start 手动开）");
            return;
        }
        try {
            this.botWindowTask = plugin.getServer().getScheduler().runTaskLater(plugin,
                    this::onBotWindowElapsed, TrainingMatchRules.BOT_START_WINDOW_TICKS);
            logger.info("[IC] 人机开局窗口已排：" + TrainingMatchRules.BOT_START_WINDOW_TICKS
                    + " tick（" + (TrainingMatchRules.BOT_START_WINDOW_TICKS / 20.0) + " 秒）后"
                    + "若仍只有 1 名真人，带着人机开局；这期间有人进来，正常 1v1");
        } catch (Throwable t) {
            this.botWindowTask = null;
            logger.warn("[IC] 排人机开局窗口任务失败（房间仍然有效，可敲 /mgc start 手动开）：" + t);
        }
    }

    /** 窗口到期：试着开局（★ 一切判据仍在 {@code tryStart()} 里，这里只负责"再问一次"）。 */
    private void onBotWindowElapsed() {
        try {
            if (cleanedUp || !botRequested || getState() != GameState.WAITING
                    || getPlayers().isEmpty()) {
                return;   // 已经开了 / 已经关了 / 已经收尾 / 人跑光了 ⇒ 什么都不做
            }
            // ★★★ 2026-10-19：**先把窗口标记成"已到点"**，再试开局。
            //   否则 tryStart() 会用两个时钟相减重新算剩余，现场实测差 1 tick
            //   ⇒ 它走"还没到"分支 return，而本任务是一次性的 ⇒ 整局永远开不起来。
            this.botWindowElapsed = true;
            getGameWaitStrategy().tryStart();
        } catch (Throwable t) {
            logger.warn("[IC] 人机开局窗口到期后试开局失败（可敲 /mgc start 手动开）：" + t);
        }
    }

    /**
     * ★ <b>清掉"已请求"标记</b>（对局开始 / 房间取消 / 收尾时都调；见
     * {@link #onGameStart()}、{@link #onGameCancel()}、{@link #cleanup(String)}）。
     *
     * <p>顺序是刻意的：<b>先</b>把字段置回、<b>再</b>取消任务 —— 于是即使取消动作
     * 反过来触发了什么，也不会再看到一个"仍然开着"的标记（而且任务自己调
     * {@code tryStart()} → 开局 → 回调到这里是<b>合法</b>的重入，取消正在运行的任务是安全的）。
     */
    private void clearBotRequest() {
        this.botRequested = false;
        this.botRequestedAtTick = -1;
        this.botWindowElapsed = false;
        cancelBotWindowTask();
    }

    private void cancelBotWindowTask() {
        BukkitTask task = this.botWindowTask;
        this.botWindowTask = null;
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (Throwable t) {
            logger.warn("[IC] 取消人机开局窗口任务失败（无害，任务体内已有一道状态护栏）：" + t);
        }
    }

    /**
     * {@code Bukkit.getCurrentTick()} 的 fail-safe 版本：取不到就返回 {@code -1}
     * （{@link #botWindowRemainingTicks()} 把 {@code -1} 解释成"窗口已满"⇒
     * <b>不会因为时钟问题把玩家永久卡在等待房间里</b>；代价只是少了那 1 秒的等待）。
     */
    private static int currentTickOrMinusOne() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public AbstractPlayerQuitStrategy getPlayerQuitStrategy() {
        return new InfernalClashQuitStrategy(this);
    }

    @Override
    public AbstractPlayerDeathStrategy getPlayerDeathStrategy() {
        return new InfernalClashDeathStrategy(this);
    }

    /** 取某玩家入局前的位置（没有记录时返回 {@code null}）。 */
    Location joinLocationOf(UUID id) {
        return id == null ? null : joinLocations.get(id);
    }

    // ---------------- MGC 生命周期 ----------------

    @Override
    protected boolean onGameInit() {
        logger.info("[IC] 对局初始化（等待玩家加入）");
        return true;
    }

    @Override
    protected void onGameStart() {
        // ⚠️ 本方法**不能阻塞**（MGC 调完立即置 RUNNING），所以一切交给对局时钟（1 tick 驱动）。
        logger.info("[IC] 对局开始｜真人 " + getPlayers().size() + " 名");
        InfernalClashPlugin plugin = plugin();
        if (plugin == null || !plugin.isEnabled()) {
            // 关服流程：此时注册任务会抛 IllegalPluginAccessException（见 skipCleanupWhileDisabling）
            return;
        }

        // ★ 阵营按**加入顺序**：第 0 个（房主）= 蓝，第 1 个 = 红（2026-10-15 用户裁定）。
        //   getPlayers() 的顺序就是 MGC 的入局顺序 —— 这是「红蓝归属」的唯一依据，
        //   所以这里**不做排序**（排序会把房主判定搞乱）。
        List<MatchService.PlayerName> players = new ArrayList<>();
        for (PlayerExt ext : getPlayers()) {          // ★ getPlayers() 返回 List<PlayerExt>
            if (ext != null && ext.player() != null) {
                players.add(InfernalClashPlugin.identityOf(ext.player()));
            }
        }

        String matchId = matchId();

        // ★★★ 2026-10-18（用户：「**我需要让房间开启时随机选择一个空的竞技场（可参考 ig）**」）：
        //   开局第一件事 = **从空的竞技场里随机挑一个**并占住它（ArenaPool）。
        //   ★ 参考 IG 的部分：`playable()` 里随机（IG 是 SessionPlanner.selectArena，按本局种子随机）；
        //     ★ 本项目**多了一步「空」**：IG 的竞技场不与对局绑定生命周期，
        //     而我们要多竞技场**同时**开局 ⇒ 已被别的对局占用的必须排除。
        //   ★ 一个空的都没有 ⇒ **拒绝开局**（绝不复用同一座场子开第二局：
        //     两局的实体与目标物会叠在一起，表现是"塔被别人打"这种最难查的事故）。
        String arenaName = null;
        io.mczju.infernalclash.arena.ArenaConfig matchArena = null;
        if (plugin.arenaPool() != null) {
            java.util.List<String> playable = plugin.playableArenaNames();
            long arenaSeed = new java.util.Random().nextLong();
            java.util.Optional<String> picked =
                    plugin.arenaPool().acquire(playable, matchId, arenaSeed);
            if (picked.isEmpty()) {
                logger.warn("[IC] 开局被拒绝：没有空的竞技场"
                        + "｜可用（点位齐全）" + playable + "｜占用中 "
                        + plugin.arenaPool().busy()
                        + " —— 请先绑一座新的：站在西南角敲 /incl arena set <名字>");
                try {
                    sender().error("<red>没有空闲的竞技场，本局无法开始。"
                            + "请用 /incl arena set <名字> 再绑一座（站在西南角敲）。");
                    abortViaManager();
                } catch (Throwable t) {
                    logger.warn("[IC] 拒绝开局时中止对局失败（房间可能停在 IN_GAME，用 "
                            + "/incl debug room reset 复位）：" + t);
                }
                return;
            }
            arenaName = picked.get();
            matchArena = plugin.arenaCatalog().arena(arenaName);
            if (matchArena == null) {
                // 池里挑出来的名字在目录里查不到（热重载竞态）⇒ 按"没有竞技场"处理，绝不带 null 往下走
                logger.warn("[IC] 挑中的竞技场 " + arenaName + " 在目录里查不到，拒绝开局");
                plugin.arenaPool().release(matchId);
                try {
                    sender().error("<red>竞技场数据不一致（控制台已记录），本局无法开始。");
                    abortViaManager();
                } catch (Throwable t) {
                    logger.warn("[IC] 中止对局失败：" + t);
                }
                return;
            }
            logger.info("[IC] 本局随机选中的竞技场：" + arenaName
                    + "（候选 " + playable.size() + " 座，种子 " + arenaSeed + "）");
        }

        // ★ P3：先建立目标物系统（光束索敌与出生点传送都需要「阵营 → 玩家」映射），
        //   再把它作为 hooks 交给对局运行时 —— 运行时不需要知道信标长什么样。
        Map<Side, UUID> sideAssignments = MatchService.sideAssignments(players);
        // ★ P9：把名册留一份 —— 收尾那一刻 MatchService 里的 sides 已被 stop() 清空（见字段注释）
        //   ★★ **只留真人**：战绩/榜单的口径按"有几个真人阵营"判（见 resultRows），
        //      人机那一侧绝不能混进来（否则训练局会被记成 VERSUS_PLAYER，docs/08 §7 R63 失效）。
        this.participants = new EnumMap<>(sideAssignments);
        ObjectiveService objectiveService = ObjectiveService.create(plugin, matchId,
                // ★★ 2026-10-18：用**本局抽到的**那座位次（matchArena），不是全局默认 ——
                //   多竞技场同时开局时，各自的目标物必须落在自己的场子里。
                plugin.unitRegistry(),
                matchArena != null ? matchArena : plugin.arenaConfig(),
                plugin.objectiveConfig(),
                sideAssignments, plugin.getLogger());
        this.objectives = objectiveService;
        // ★★★ 2026-10-20（用户：「abort 后重启，上局实体一直残留清不掉」）：
        //   **开局前**先清一遍**本局这座竞技场**范围内的遗留实体。
        //   为什么非要在这一刻：清理不主动加载区块 ⇒ 启动兜底只能在区块已加载时生效，
        //   而"开局"时竞技场区块**必然已加载**（玩家刚被传送进来）⇒ 这是最可靠的一道。
        //   ★ 范围/归属判据全在 objective/LegacyPurge（本插件点位 ±64 格 + PDC），
        //     不会碰别的对局（ArenaPool 保证同一座场子不会有两场）也不会碰别人的实体。
        plugin.purgeLegacyForArena(matchArena != null ? matchArena : plugin.arenaConfig(),
                "开局前");
        // 点位不全 ⇒ spawnAll() 明确告警并返回 false ⇒ 本局退化成纯时间轴（P2 行为，最后平局）
        objectiveService.spawnAll();

        // ★ P4：快捷栏发放（1 号「战斗」物品；8 张卡由 P7/P8 的真牌组或调试指令给）
        CardLoadout cardLoadout = new CardLoadout(plugin, sideAssignments);
        this.loadout = cardLoadout;

        // ★ P5：战斗指挥（唯一算目标的地方 + AI 装载）
        //   ★★ 2026-10-17 第十二轮（缺陷 P1-a「攻城兽不去打带嘲讽的塔」）：
        //     多传一个**卡池** —— CombatDirector 要靠它把实体 PDC 里的卡 id
        //     翻成「这个召唤物选目标时优不优先打建筑」（summon.affected-by-building；
        //     2026-10-19 之前这个键叫 affected-by-taunt）。
        //     ★ 传 null 也能跑（一律按"只看距离"），但那就等于这个键静默失效 ⇒ 必须传。
        CombatDirector combatDirector = new CombatDirector(plugin, matchId,
                plugin.unitRegistry(), plugin.mobGroups(),
                plugin.combatConfig().targetRefreshTicks(),
                plugin.cardCatalog(),
                // ★★ 2026-10-17 批次 3 收尾：把**召唤器**也传进去 ——
                //   前储君的「坐骑速度斜坡」要往实体的 MOVEMENT_SPEED 上写值，
                //   而"从原始基础值乘起"这件事只有 SummonSpawner 知道（见 applySpeedScale）。
                plugin.summonSpawner());
        this.combat = combatDirector;

        // ★★★ 2026-10-17 第十二轮：**战斗 HUD**（用户裁定 §A 第 4 条：BossBar 显示对局时间 /
        //   侧边栏显示其他战斗状态 / 经验条跟随真实经验）。
        //   ★ 收件人 = sideAssignments（**本局名册**）—— MatchHud 自己逐个按 UUID 取玩家，
        //     绝不用 getOnlinePlayers（铁律 10；BytecodeScopeGuardTest 会拦）。
        //   ★ 塔数来源用 **Supplier 而不是快照**：塔每局都在掉，快照会显示旧值
        //     （与 PlayerDamageGuard 的 displaySource 同一口径）。
        //   ★ 它在钩子链里的位置：与 cardLoadout / combatDirector 并列 —— 三者互不依赖，
        //     但都必须**在 MatchService.start 之前**建立（钩子是构造参数）。
        MatchHud matchHud = new MatchHud(plugin, matchId, plugin.matchConfig().phases(),
                sideAssignments,
                () -> new int[] {
                        objectiveService.tracker().towerCount(Side.RED),
                        objectiveService.tracker().towerCount(Side.BLUE)},
                // ★★★ 2026-10-17 第十三轮（用户裁定 E19 结案）：侧边栏要显示
                //   「玩家名称 / 塔1血量 / 塔2血量 / 信标血量」⇒ 需要一个**血量来源**。
                //   ★ 它就是 ObjectiveService（本局目标物的唯一持有者）；★ 用**接口**而不是
                //     直接传 ObjectiveService：MatchHud 在 ui 包，不该知道目标物的实现细节。
                //   ★ 实时读（每次刷侧边栏现取），不缓存快照 —— 与 towerCounts 同一口径。
                new MatchHud.ObjectiveHealthSource() {
                    @Override
                    public double[] beaconOf(Side side) {
                        double[][] values = objectiveService.healthOf(side, ObjectiveKind.BEACON);
                        return values.length == 0 ? null : values[0];
                    }

                    @Override
                    public java.util.List<double[]> towersOf(Side side) {
                        return java.util.List.of(objectiveService.healthOf(side, ObjectiveKind.TOWER));
                    }
                });

        // ★★ A7 / E13 ＋ 2026-10-15 裁定：训练人机（用户原话「需要房间里任何一人敲/incl bot on」）
        //   —— 它同样实现 MatchHooks ⇒ **接在对局时钟上**（那一个全局 1-tick 任务），
        //   不新起第二个调度器。★ 决策与执行在 ai/BotPlan（纯，可离线断言）与
        //   ai/TrainingBotDriver（薄执行层，唯一生成入口 = 既有 SummonSpawner）。
        //   ⚠️ 手牌的来源（缺口 G12）**已由用户 2026-10-15 裁定**：
        //      = config.yml 的 training.bots（本局随机抽中的那一套、正好 8 张；2026-10-21 裁定）
        //        ⇒ 人机真的会出牌，见 trainingBotHand()。
        //   ★ 「要不要造人机」= 两维纯规则：人数 ＋ 本房间有没有人敲过 /incl bot on。
        //
        // ★★★ 2026-10-16（第七轮）修：人机的装配被拆成**两步，中间夹着 MatchService.start**。
        //   ① 名册（往 `players` 追加「人机那一方」）**必须**在 `MatchService.start` **之前** ——
        //      MatchService 的构造器是**按名册**建 SideState 的，晚一步加 ⇒ 人机那一方根本不存在
        //      （addXp 静默无效、xp 恒为 0 ⇒ 它一张牌都打不出来，而且**不抛任何异常**）；
        //   ② 执行层（TrainingBotDriver）**必须**在 `this.match` **赋值之后** ——
        //      它的构造器要求 match 非 null（执行层至少要能记账、能写日志、知道自己是哪一方），
        //      而 match 就是 `MatchService.start(...)` 的返回值。
        //   ⇒ 依赖是**环形的**，所以用「纯函数注入名册 ＋ 延迟绑定钩子」把它拆开
        //     （延迟绑定的理由见 lateBoundTrainingBotHooks()）。
        //   ★ 两步**各自**都过 fail-safe 闸门 TrainingBotAssembly#assembleOrNull：
        //     人机装配失败 ⇒ 只打一行明确 WARN，**本局按「无人机」继续** ——
        //     绝不让异常从 onGameStart() 逃出去（逃出去会把这一局搞成半初始化、
        //     房间留在 IN_GAME、玩家一脸问号：2026-10-16 现场原文见 dev-log/2026-10-16-026）。
        //   ★★ 闸门只圈住**人机相关的装配**；本方法里其它步骤的异常照旧往外抛（原语义不变）。
        Consumer<String> botAssemblyWarn = text -> logger.warn(text + "｜state=" + getState());
        boolean botRequestedAtStart = botRequested;
        // ★ ① 名册：**抽人机 → 解析「随机」→ 追加一条人机 PlayerName**（2026-10-21 用户裁定）。
        //   ★ 三步都在**同一个** fail-safe 闸门里：任一步失败 ⇒ 只打一行 WARN、本局按「无人机」继续。
        //   ★ 顺序：抽取（纯函数）→ 解析卡组（纯函数）→ 追加名册（纯函数）。
        //     ★ 名字必须在追加名册那一刻就定下来 —— 侧边栏读的就是名册里的 name。
        Side botSide = TrainingBotAssembly.assembleOrNull(
                () -> {
                    BotDeckResolver.BotPick pick = BotDeckResolver.pickBot(
                            plugin.trainingConfig().bots(), botRandom);
                    this.botName = pick.bot().name();
                    logger.info("[IC] " + pick.describe(plugin.trainingConfig().bots().size())
                            + "｜matchId=" + matchId);
                    BotDeckResolver.Deck deck = BotDeckResolver.resolve(
                            pick.bot().deck(), botUsableCards(plugin), botRandom);
                    this.resolvedBotDeck = deck.cards();
                    logger.info("[IC] " + deck.describe(pick.bot().name(), pick.bot().deck())
                            + "｜matchId=" + matchId);
                    return TrainingBotAssembly.appendBotToRoster(
                            matchId, players, botRequestedAtStart, pick.bot().name());
                },
                botAssemblyWarn);
        // ★ 2026-10-21：记住人机在哪一侧 —— 「偷牌」占位符要按**对面（真人）**的卡组解析。
        this.botSide = botSide;
        // ★ 状态清零（用户要求）：对局一开始，"已请求"就消费掉了 ⇒ 清掉标记 + 取消还没跑的窗口任务，
        //   免得下一局（同一个房间会被复用）凭空继承上一局的人机设置。
        clearBotRequest();

        // ★★ 人机那一侧必须**真的进对局运行时**（`players` 已含它的 PlayerName）
        //    —— 否则 MatchService 里没有它那一方的 SideState：addXp 静默无效、xp 恒为 0
        //    ⇒ 人机永远"付不起"，一张牌都打不出来（这是**静默失败**，不抛任何异常）。
        //    ★ 实测记录：本项目的离线夹具第一版就是这么错的（只登记一名真人），
        //      断言读到 `expected: <5.0> but was: <0.0>`；生产侧同理。
        this.match = MatchService.start(plugin, matchId, plugin.matchConfig().phases(),
                plugin.unitRegistry(), players,
                text -> sender().info(text),
                this::endViaManager,
                // ★ 多个钩子串联：目标物（P3）+ 快捷栏（P4）+ 战斗（P5）+ 训练人机（A7）各自独立
                //   ★ 人机那一环是**延迟绑定**的转发器：它此时还不知道 driver（driver 要等下面
                //     ① 之后才能造），但它转发到的字段在**下一次 tick 之前**必然已经有值。
                //   ★★ 2026-10-16 第八轮：多了一层 `prepEntryHooks(...)` —— 准备阶段开始时
                //     的三件事（送出生点 / 发选卡菜单物品 / 自动弹一次 U4）需要
                //     **插件实例 + 本局运行时**，而这两样在 lambda 里取不到实例字段
                //     （钩子是相对**匿名类**写的）⇒ 用显式参数把它们传进去。
                //   ★★★ 2026-10-16 第九轮修：这里**不再**把 `this.match` 当实参传进去！
                //     实参在 `MatchService.start` **调用之前**求值，而 `this.match` 的
                //     赋值发生在它<b>返回之后</b>（javap 实测 onGameStart：291: getfield match
                //     … 323: MatchService.start … 326: putfield match）⇒ 传进去的恒为 null
                //     ⇒ 「自动弹 U4」那一环<b>静默什么都不做</b>（这是与「初始转换没发事件」
                //     并列的第二个根因，见 prepHooks 的 javadoc）。
                //   ★★ 同时把顺序排成「**先传送 → 再发物品 → 最后弹界面**」（第九轮）：
                //     传送若发生在界面打开之后，客户端有可能把刚打开的容器关掉
                //     ⇒ 那就等于「自动弹了一次、玩家看不到」；而「先站好再开界面」也更合直觉。
                MatchHooks.combine(objectiveService, cardLoadout, prepHooks(plugin),
                        playerBuffs(plugin, matchId, sideAssignments),
                        combatDirector, matchHud,
                        // ★★ 2026-10-17 批次 3 收尾：方块怪接触伤害（熔岩精灵 / 烈焰熔炉）
                        //   —— 原版对方块怪以外的目标 **0 输出**，由本钩子接管（纯判据见
                        //   `battle/CubeMobTouch`，执行层见 `battle/CubeMobTouchListener`）。
                        cubeMobTouchHooks(plugin, matchId),
                        lateBoundTrainingBotHooks()));
        // ★★★ 2026-10-18：把本局绑到抽到的那座竞技场（收尾时 MatchService#stop 会把它还回池子）。
        //   ★ 必须排在 start **之后**：start 里就会建 SideState 并立刻可能触发钩子，
        //     而那之前 this.match 还是 null。bindArena 只写字段，幂等。
        if (matchArena != null && arenaName != null && this.match != null) {
            this.match.bindArena(arenaName, matchArena, plugin.arenaPool()::release);
        }
        // ★ ② 执行层：**此刻 this.match 已经赋值** ⇒ driver 拿到的 match 必然非 null
        //   （2026-10-16 现场那个 IllegalArgumentException 就出在这一处：以前这段装配在
        //     `MatchService.start` **之前**，传进去的 this.match 还是 null）
        if (botSide != null) {
            this.trainingBot = TrainingBotAssembly.assembleOrNull(
                    () -> createTrainingBotDriver(plugin, matchId, botSide, this.match),
                    botAssemblyWarn);
        }
        // ★★ 2026-10-16 第八轮：把「人机是哪一侧」告诉发牌器 —— 准备阶段发「选卡菜单」物品时
        //   要跳过它（它没有 U4 界面，8 张牌来自 config.yml 的 training.bots）。
        //   ★ 放在这里（而不是 CardLoadout 的构造参数里）：CardLoadout 在**人机名册装配之前**
        //     就建立了（它要进 MatchService 的钩子链），而 botSide 是装配的返回值。
        cardLoadout.markBotSide(botSide);
        String botLine = botSide == null
                ? "无（两边都是真人）"
                : (trainingBot == null
                        ? "装配失败（见上面的告警），本局按「无人机」继续"
                        : botSide + " 方（每 " + TrainingBotDriver.CAST_INTERVAL_TICKS
                                + " tick 按「从左到右」试一次）");
        logger.info("[IC] 对局运行时已建立：matchId=" + matchId
                + "｜目标物 " + objectiveService.unitCount() + " 个"
                + "｜目标刷新 " + plugin.combatConfig().targetRefreshTicks() + " tick"
                + "｜训练人机 " + botLine
                + "｜活跃对局数 " + MatchService.activeCount());

        // ★★ 用户裁定 ①（docs/24 §5.4 第 3 条）：**对局开始时对每位真人参赛者 load 一次档案**。
        //   放在这最后一步是刻意的：名册（participants / sideAssignments）已经确定、
        //   人机条目也已经加过（TrainingBotAssembly#appendBotToRoster 就地追加）⇒ 这里遍历的**只有真人**。
        loadProfilesForHumans(plugin);

        // ★★★ 2026-10-16 第九轮：**准备阶段的进入通知**（用户侧链路的第一件事）。
        //   为什么必须由这里显式调用、而不是靠阶段机的边沿（这就是现场那一跤的根因）：
        //     ★ MatchState.INITIAL 的 phase **本来就是 PREPARING**（javap 实测：
        //       MatchState 的 static{} 是 getstatic MatchPhase.PREPARING ⇒ new MatchState
        //       ⇒ putstatic INITIAL）；对局一开始就已经在准备阶段里，
        //       阶段机永远<b>不会</b>产生一次「进入 PREPARING」的转换
        //       ⇒ 挂在 onPhaseChanged 上的那条通知在生产路径上一次都不会跑
        //       （现场日志 17:52:56~17:53:56 里那两行"传送 / 自动弹框"一行都没有，
        //        而 60 秒后的「准备阶段到点」照常触发 —— 因为它挂在 tick() 上）。
        //   为什么放在**这里**（顺序约束，见 MatchService#enterPrepPhase 的 javadoc）：
        //     ① 此时 `this.match` 已经赋值（上面那一行 `this.match = MatchService.start(...)`）；
        //     ② 此时钩子链里三个实现都就位了（钩子在 start 的构造参数里就位）；
        //     ③ 此时 `cardLoadout.markBotSide(botSide)` 已经跑过 ⇒ 选卡菜单物品不会发给人机；
        //     ④ 对局运行时那一行日志也已打出（排障时顺序更好读）。
        //   ★ 这条调用被 game/PrepPlayerLinkWiringTest 用**方法级字节码**钉住
        //     （putfield this.match ＜ markBotSide ＜ enterPrepPhase），
        //     删掉它或把它搬前面 ⇒ 那条测试立刻红。
        MatchService service = this.match;
        if (service != null) {
            service.enterPrepPhase();
        } else {
            // 关服流程等极端情况：此时**不能**静默（否则又是一次"人没进去"）
            logger.warn("[IC] 准备阶段的进入通知没能发出：本局运行时为空（match == null）"
                    + "｜matchId=" + matchId()
                    + "｜后果：玩家不会被送到出生点、也拿不到选卡界面；"
                    + "结算前的「没给过机会，不判负」那道门会兜住他");
        }
    }

    /**
     * ★★ <b>对局开始时把每位真人参赛者的档案读进内存一次</b>
     * （2026-10-15 用户裁定 ①：「对局开始发快捷栏卡时 {@code load} 一次」；
     * 留档 {@code docs/24} §5.4 第 3 条）。
     *
     * <h2>★ 它修的是什么（一条「第一局不入账」的静默缺陷）</h2>
     * {@code leaderboard/LeaderboardWiring.recordResult} 用的是<b>只读</b>的
     * {@code profileStore().raw(uuid)}，而既有口径（注释 + 测试都钉着）是
     * 「<b>绝不为记战绩去造档案</b>」⇒ 一名<b>从未被 {@code load} 过</b>的玩家，
     * 第一局结束<b>战绩不入账</b>（只打一条 warning），榜上仍是 0。
     * 按裁定 ①，这件事<b>提前到开局时做</b>：那时读一次，档案就在内存里了
     * ⇒ 收尾的 {@code raw(uuid)} 能拿到 ⇒ 第一局就入账。
     *
     * <h2>★★ 「记账时不造档案」这条口径**没有被改**</h2>
     * 本方法刻意调的是 {@link io.mczju.infernalclash.profile.ProfileStore#load(java.util.UUID)}
     * （**读**路径），<b>不是</b> {@code ensure}，也<b>不是</b>要替换 {@code recordResult} 里的
     * {@code raw} —— 记账路径<b>一个字符都没动</b>。这正是裁定 ① 相对选项 ②
     * （「把 {@code raw} 换成 {@code ensure}」）的区别：口径不变，只是把读提前。
     *
     * <h2>★ 失败方向：只告警，绝不影响开局</h2>
     * 战绩记录本来就是「失败只告警」⇒ 这里同理：拿不到档案（MGC 未注册 / 数据桶不存在 /
     * 读异常）只打一条 warning。★ 而且 {@code load} 自身已经是 fail-safe 的
     * （拿不到 ⇒ 返回 {@code fresh} + 告警，不抛）—— 这里的 try/catch 是第二道锁，
     * 保证「一个玩家的档案读失败」绝不阻止一局开局。
     *
     * <p>★ 可对账：日志报出「读了几位真人」（铁律 10 的「带数量」口径）。
     */
    private void loadProfilesForHumans(InfernalClashPlugin plugin) {
        if (plugin == null || participants == null || participants.isEmpty()) {
            return;
        }
        int loaded = 0;
        int failed = 0;
        for (UUID player : participants.values()) {
            if (player == null) {
                continue;
            }
            try {
                plugin.profileStore().load(player);
                loaded++;
            } catch (Throwable t) {
                // ★ 第二道锁：load 自己已经 fail-safe（返回 fresh + 告警），这里兜住任何意外
                failed++;
                logger.warn("[IC] 开局读档案失败（" + player + "），只告警，不影响本局："
                        + "后果：这一位本局结束时可能记不上战绩（leaderboard 走只读 raw）——" + t);
            }
        }
        logger.info("[IC] 开局已读入 " + loaded + " 位真人的档案（共 " + participants.size()
                + " 位参赛者）"
                + (failed > 0 ? "，其中 " + failed + " 位失败（见上面的告警）" : "")
                + "｜目的：用户裁定 ①—— 让第一局的战绩也入账"
                + "（记账路径仍是只读 raw，绝不为记战绩造档案）");
    }

    /** 本局的战斗指挥（未开局 / 已清理时为 {@code null}）。 */
    public CombatDirector combatDirector() {
        return combat;
    }

    /**
     * ★★ <b>「准备阶段开始」那一环的钩子</b>（2026-10-16 第八轮新增、第九轮修）——
     * 对局开局**之后**的玩家侧链路的第一件事。
     *
     * <h2>它解决的是用户报的那个现象</h2>
     * 用户原话：「<b>控制台开局了，但是玩家还在等待房间里，手里也没有选卡菜单</b>」
     * （逐字留档 {@code docs/24} §5.8）。三件事在旧代码里<b>一件都没有</b>：
     * <pre>
     *   ① 玩家还在等待房间  ⇒ 传送只发生在 `to == BATTLE`（准备阶段整 1200 tick 没人挪他）
     *      ★ 修在 ObjectiveService#teleportHumansToSpawns（同一个钩子链的前一环）
     *   ② 手里没有选卡菜单  ⇒ R15 的物品从来没发过
     *      ★ 修在 CardLoadout#onPrepPhaseStarted（同一条链）
     *   ③ 没有选卡界面      ⇒ U4 在本轮之前**根本没实现**
     *      ★ 第八轮新增 ui/MatchDeckMenu；**自动弹一次**是本方法（用户裁定「自动弹」）
     * </pre>
     *
     * <h2>★★★ 第九轮修：为什么这里<b>不再</b>收 {@code match} 参数，而是现读字段</h2>
     * 第八轮那版是 {@code prepHooks(plugin, this.match)} —— 而实参在
     * {@code MatchService.start(...)} <b>调用之前</b>求值，字段的赋值（{@code putfield}）
     * 发生在它<b>返回之后</b>（{@code javap -c} 实测 {@code onGameStart}：
     * {@code 291: getfield match} … {@code 323: MatchService.start} … {@code 326: putfield match}）
     * ⇒ 传进去的<b>恒为 {@code null}</b> ⇒ {@link #openDeckPickerForHumans} 里的
     * 「{@code match == null} ⇒ 直接返回」把整个「自动弹 U4」<b>静默</b>吞掉了
     * （这也是「两行日志一行都没有」的第二个根因；第一个根因是
     * {@code MatchState.INITIAL} 本来就是 {@code PREPARING} ⇒ 阶段边沿不存在，
     * 见 {@link MatchService#enterPrepPhase()}）。
     *
     * <p>⇒ 修法 = <b>延迟绑定</b>：钩子每次回调时现读 {@link #matchService()}。
     * 触发点是 {@link MatchService#enterPrepPhase()}，它由 {@code onGameStart()} 的
     * <b>最后一步</b>显式调用（那时字段必然已赋值）——
     * 顺序不变式被 {@code game/PrepPlayerLinkWiringTest} 的方法级字节码断言钉着。
     * ★ 与 {@link #lateBoundTrainingBotHooks()} 是同一个套路（那里解的是环形依赖）。
     *
     * <h2>★ 只对真人弹（人机没有 U4）</h2>
     * {@code match.humanSides()} 是构造 {@code MatchService} 时算出来的真人集合
     * （判据是「UUID 是否等于 {@code TrainingBotAssembly#botIdFor(matchId, side)}」）⇒
     * 人机那一侧不会收到界面。★ 账号离线 / 界面打开失败 ⇒ 只告警，绝不影响开局。
     */
    private MatchHooks prepHooks(InfernalClashPlugin plugin) {
        return new MatchHooks() {
            @Override
            public void onPrepPhaseStarted() {
                openDeckPickerForHumans(plugin, matchService());
            }
        };
    }

    /**
     * ★★ <b>玩家本体的常驻效果</b>（{@code docs/02} §5 的 <b>R40</b>）——
     * 2026-10-17 第十轮新增，缺陷 P0-b
     * （用户原话「<b>玩家的常驻的抗性药水效果也没有出现</b>」，{@code docs/24} §7.1）。
     *
     * <h2>为什么是独立的一个钩子实现</h2>
     * 「给药水效果」是<b>玩家侧</b>的事，与目标物（信标/塔）无关
     * ⇒ 与 {@code prepHooks} 同样的口径：一件事一个实现，别塞进目标物系统里。
     *
     * <h2>★★ 名册用的是 {@code sideAssignments}（**真人**）</h2>
     * {@code sideAssignments} 在<b>人机被追加进名册之前</b>就算好了
     * （见 {@code onGameStart} 的装配顺序）⇒ 里面只有真人。
     * 这正是我们要的：人机那一侧是一个**派生出来的假 UUID**，没有真实玩家对象，
     * 给它上效果没有意义（{@code Bukkit.getPlayer(假UUID)} 恒为 null ⇒ 本来也会被跳过）。
     * ★ 范围就是这份名册：**没有 {@code getOnlinePlayers()}**（铁律 10 的第一问）。
     */
    private MatchHooks playerBuffs(InfernalClashPlugin plugin, String matchId,
                                   Map<Side, UUID> sideAssignments) {
        return new MatchPlayerBuffs(plugin, matchId, sideAssignments);
    }

    /**
     * ★★ <b>准备阶段一开始，给每位真人自动弹一次 U4 选卡界面</b>（用户裁定：「自动弹」）。
     *
     * <p>「自动弹」的落地位置就是这里 —— 调用链：
     * {@code InfernalClashGame.onGameStart()} 的最后一步 → {@code MatchService#enterPrepPhase()}
     * → {@code hooks.onPrepPhaseStarted()}（同一条链上还有目标物把玩家送到出生点、
     * 快捷栏发「选卡菜单」物品）。
     *
     * <h2>★★ 它同时负责记下「这一方拿到了选卡机会」（判负的安全前置）</h2>
     * 每一位<b>在线</b>的真人在这里被 {@code prep().markOffered(side)} 记一次 ⇒
     * 判负前那道门（{@code policy/PrepDeadline} 的 {@code offered}）才有事实可依。
     * ★ 记在<b>弹框尝试之前</b>（只要人在线：物品已经发到他手上、界面也试过了
     * ⇒ 他确实"被给过机会"），所以「界面没弹出来」不会把他变成"没给过机会"。
     * ★ <b>唯一的写点</b>就是这里（{@code PrepPlayerLinkWiringTest} 钉着）。
     *
     * <h2>★ 失败方向（第九轮修：不再静默）</h2>
     * ① {@code match == null}（装配顺序错了）⇒ 打一条明确 WARN，<b>不</b>静默返回 ——
     *    第八轮那版就是在这里静默返回，于是现场「两行日志一行都没有」而没人知道为什么；
     * ② 某位玩家离线 / 界面未注册 / MGC 门面抛异常 ⇒ 由
     *    {@link io.mczju.infernalclash.ui.MenuRegistry#openDeckPicker} <b>自己</b>兜住
     *    （它返回 {@code false} 并打日志）⇒ 本方法不再加一层 try/catch 掩盖原因，
     *    但**计数**并打一行 INFO（可对账：弹了几个人、记了几个"给过机会"）。
     * ★ 只要还有「选卡菜单」物品在手，玩家随时能右键重开（R15）—— 自动弹失败不是死路。
     */
    private void openDeckPickerForHumans(InfernalClashPlugin plugin, MatchService match) {
        if (match == null) {
            // ★★ 绝不静默：这一格正是第八轮那个 bug 藏身的地方（docs/24 §6.0）
            logger.warn("[IC] 准备阶段：对局运行时还没就位（match == null），没能自动弹选卡界面"
                    + "、也没能给任何人记「已给过选卡机会」"
                    + "｜这是装配顺序错误（见 prepHooks 的 javadoc：字段必须在"
                    + " MatchService.start 赋值之后才读）；"
                    + "后果：到点判负那道门会兜住玩家（没给过机会，不判负）");
            return;
        }
        if (plugin == null) {
            logger.warn("[IC] 准备阶段：插件实例不可用（plugin == null），跳过自动弹选卡界面");
            return;
        }
        int offered = 0;
        int opened = 0;
        int failed = 0;
        for (Side side : match.humanSides()) {
            MatchService.SideState state = match.side(side);
            UUID id = state == null ? null : state.player();
            Player player = id == null ? null : playerOrNull(id);
            if (player == null || !player.isOnline()) {
                failed++;
                continue;
            }
            // ★★ 判负的安全前置：这一方**确实被给过机会**（人在线、物品发到手、界面弹过一次）
            if (match.prep().markOffered(side)) {
                offered++;
            }
            if (io.mczju.infernalclash.ui.MenuRegistry.openDeckPicker(player, match.matchId())) {
                opened++;
            } else {
                failed++;
            }
        }
        logger.info("[IC] 准备阶段：已自动弹出选卡界面 " + opened + " / "
                + (opened + failed) + " 名真人（matchId=" + match.matchId()
                + "）" + (failed > 0 ? "，其中 " + failed + " 名没弹出来（见上面的告警）" : "")
                + "｜新记「已给过选卡机会」" + offered + " 方"
                + "｜用户裁定「自动弹」；弹不出来也能用 1 号格的"
                + "「选卡菜单」物品右键重开（）；"
                + "没被记过机会的玩家到点不会因为「未确认」被判负");
    }

    /**
     * {@code Bukkit.getPlayer(UUID)} 的 fail-safe 版本（关服 / 离线单测里也不抛）。
     *
     * <p>★ 这是<b>按 UUID 单点查</b>，不是遍历在线玩家 —— 铁律 10 禁的是后者
     * （{@code BytecodeScopeGuardTest} 也在守这条）。
     */
    private static Player playerOrNull(UUID id) {
        try {
            return Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ★★ <b>「人机那一环」的延迟绑定钩子</b> —— 2026-10-16（第七轮）为解**环形依赖**而加。
     *
     * <h2>★★ 为什么需要它（这是本轮那个 null 的结构性根因）</h2>
     * 装配人机需要两步，而两步之间有一步 <b>MGC/运行时的</b>调用：
     * <pre>
     *   ① 名册注入（往 players 追加「人机那一方」）   ← 必须在 MatchService.start **之前**
     *   ② this.match = MatchService.start(…, hooks)  ← 钩子**必须在建 MatchService 时**给出
     *   ③ new TrainingBotDriver(plugin, match, …)    ← 必须在 this.match **赋值之后**（构造器要 match）
     * </pre>
     * ⇒ ②的钩子参数与③的 driver 互为前后，**结构上无法在同一个语句里都满足**。
     * 三种做法里选了最保守的一种：
     * <ul>
     *   <li>★ <b>本方法（采用）</b>：注册一个<b>转发器</b>，每次回调现读字段
     *       {@link #trainingBot} —— 主线程语义下，「②之后、第一次 tick 之前」字段必然已定
     *       （{@code onGameStart()} 是同步跑完的；对局时钟是插件的 1-tick 任务，
     *        不可能在开局回调中间插进来）。</li>
     *   <li>✗ 给 {@code TrainingBotDriver} 加「match 可以为 null / 以后再 set」——
     *       那正是任务书点名<b>不许</b>的做法：执行层会在**运行期**才炸，
     *       而且 {@code onStop()} / 出牌路径上每一处都要再判一次。</li>
     *   <li>✗ 让 driver 自己按 {@code matchId} 去 {@code MatchService.get(...)} 捞 ——
     *       把「哪一个运行时」变成隐式全局查找，装配点就看不出依赖了。</li>
     * </ul>
     * ★ 转发器还有一条**天然的好处**：人机没装配成功时字段是 {@code null}
     * ⇒ 这一环自动变成空操作（fail-safe 的第二步，见 {@code TrainingBotAssembly#assembleOrNull}）。
     *
     * <p>★ 字段 {@code trainingBot} 是 {@code volatile}，且只在这一个类里被读写 ⇒ 无竞态面。
     */
    /**
     * ★★ <b>方块怪接触伤害这一环</b>（2026-10-17 批次 3 收尾新增）。
     *
     * <h2>为什么它是一环 {@code MatchHooks} 而不是监听器</h2>
     * 接触伤害本质是「<b>位置关系</b>」：位置变化没有便宜的事件可听
     * （{@code EntityMoveEvent} 每 tick 每个实体都触发，比自己算还贵），
     * 而用 Goal 又拿不到「账本里算出来的那个目标」（TARGET 层与 MOVE 层必须是同一个目标）。
     * ⇒ 挂在对局时钟上，每个对局 tick 算一次（判据在纯类 {@code battle/CubeMobTouch}）。
     *
     * <p>★ 生命周期：{@code onStop} 会 {@code clear()} 掉节流表 —— 它<b>不挂任何任务</b>，
     * 所以不存在"对局结束后还在跑"的风险（这正是本项目最贵的那类事故）。
     */
    private MatchHooks cubeMobTouchHooks(InfernalClashPlugin plugin, String matchId) {
        io.mczju.infernalclash.battle.CubeMobTouchListener touches =
                new io.mczju.infernalclash.battle.CubeMobTouchListener(
                        plugin.unitRegistry(), plugin.getLogger());
        return new MatchHooks() {
            @Override
            public void onTick(MatchPhase phase, int battleTick) {
                try {
                    touches.onTick(matchId, battleTick, phase);
                } catch (Throwable t) {
                    // ★ 一个钩子抛异常会打断整条链（其它钩子就都不跑了）⇒ 只告警
                    plugin.getLogger().warning("[IC] 方块怪接触伤害这一环出错（本 tick 跳过）：" + t);
                }
            }

            @Override
            public void onStop(String reason) {
                touches.clear();
            }
        };
    }

    /**
     * ★ <b>延迟绑定的人机钩子</b>（第八轮新增 —— 解开「人机 driver 与 match 的环形依赖」）。
     *
     * <h2>为什么需要这一层</h2>
     * {@code TrainingBotDriver} 的构造器要求 {@code match} 非 null，而 {@code this.match}
     * 是 {@code MatchService.start(...)} 的返回值；可那份钩子链又是 {@code start} 的
     * <b>构造参数</b> ⇒ 两者互相依赖。本转发器就是那个环的断开点：
     * <ul>
     *   <li>钩子链在 {@code start} 之前建好、<b>注册的是本转发器</b>；</li>
     *   <li>真正转发给谁，是<b>每次回调时现读字段</b>
     *       {@link #trainingBot} —— 主线程语义下，「②之后、第一次 tick 之前」字段必然已定
     *       （{@code onGameStart()} 是同步跑完的；对局时钟是插件的 1-tick 任务，
     *        不可能在开局回调中间插进来）。</li>
     *   <li>✗ 给 {@code TrainingBotDriver} 加「match 可以为 null / 以后再 set」——
     *       那正是任务书点名<b>不许</b>的做法：执行层会在**运行期**才炸，
     *       而且 {@code onStop()} / 出牌路径上每一处都要再判一次。</li>
     *   <li>✗ 让 driver 自己按 {@code matchId} 去 {@code MatchService.get(...)} 捞 ——
     *       把「哪一个运行时」变成隐式全局查找，装配点就看不出依赖了。</li>
     * </ul>
     * ★ 转发器还有一条**天然的好处**：人机没装配成功时字段是 {@code null}
     * ⇒ 这一环自动变成空操作（fail-safe 的第二步，见 {@code TrainingBotAssembly#assembleOrNull}）。
     *
     * <p>★ 字段 {@code trainingBot} 是 {@code volatile}，且只在这一个类里被读写 ⇒ 无竞态面。
     */
    private MatchHooks lateBoundTrainingBotHooks() {
        return new MatchHooks() {
            @Override
            public void onTick(MatchPhase phase, int battleTick) {
                TrainingBotDriver driver = trainingBot;
                if (driver != null) {
                    driver.onTick(phase, battleTick);
                }
            }

            @Override
            public void onPhaseChanged(MatchPhase from, MatchPhase to) {
                TrainingBotDriver driver = trainingBot;
                if (driver != null) {
                    driver.onPhaseChanged(from, to);
                }
            }

            @Override
            public void onStop(String reason) {
                TrainingBotDriver driver = trainingBot;
                if (driver != null) {
                    driver.onStop(reason);
                }
            }
        };
    }

    /**
     * ★★ <b>建立人机的执行层</b>（A7 / E13）—— 只有「1 名真人 ＋ 已请求」才会被调用
     * （判据在 {@link TrainingBotAssembly#appendBotToRoster}，本方法不再自己判一次）。
     *
     * <h2>★★ 为什么它必须晚于 {@code this.match} 的赋值（2026-10-16 修的缺陷）</h2>
     * {@link TrainingBotDriver} 的构造器要求 {@code match} 非 null（执行层至少要能记账、
     * 能写日志、知道自己是哪一方 —— 那句异常原文就是「plugin / match / side 都不能为 null」）。
     * 而 {@code this.match} 是 {@link MatchService#start} 的返回值，它又<b>必须</b>拿到
     * 「已经含人机那一方」的名册 ⇒ 两者是<b>环形依赖</b>（结构说明见
     * {@link #lateBoundTrainingBotHooks()}）。
     *
     * <p>★★ 原实现把整段装配写在 {@code MatchService.start} <b>之前</b>
     * ⇒ 传进去的 {@code this.match} 还是 {@code null} ⇒ 构造器抛异常，
     * 而它从 {@code onGameStart()} 逃出去 ⇒ 现场是「局开了、目标物生成了、人机没上场、
     * 房间留 {@code IN_GAME}、控制台还打了一条**误导**的 startGame() 失败」
     * （2026-10-16 现场原文留档 {@code dev-log/2026-10-16-026}）。
     * ⇒ 修法：名册由纯函数 {@link TrainingBotAssembly#appendBotToRoster} 在 start <b>之前</b>注入；
     * 本方法在 start <b>之后</b>调用，且 {@code match} <b>作为参数显式传进来</b>
     * （不是从别处现捞，也不靠"运行期再判一次"）。
     *
     * @param match ★ 本局运行时；<b>必须</b>是本局已经建好的那一份（这里做前置断言）
     */
    private TrainingBotDriver createTrainingBotDriver(InfernalClashPlugin plugin, String matchId,
                                                      Side botSide, MatchService match) {
        // ★ 前置断言：把「顺序不变式」钉在装配点上 ——
        //   坏输入在这里**立刻**炸成一句能读懂的话，而不是等运行期某次取用时才炸。
        Objects.requireNonNull(match, "人机执行层的 match 不能为 null："
                + "它必须在 this.match = MatchService.start(...) 之后才装配"
                + "（理由见 createTrainingBotDriver 的 javadoc / dev-log/2026-10-16-026）");
        TrainingBotDriver driver = new TrainingBotDriver(plugin, match, botSide,
                // ★★ 2026-10-18：人机落点用**本局的**竞技场（多竞技场同时开局时不能串场）
                match.arena() != null ? match.arena() : plugin.arenaConfig(), this::trainingBotHand);
        // ★★ 2026-10-21 追加（docs/32）：建筑类召唤牌要按「哪座塔血量低 / 高」决定放左还是右
        //   ⇒ 把**本局目标物系统**的两座塔当前血量注入执行层（顺序 = 左、右）。
        //   ★ 用 Supplier 而不是快照：塔每 tick 都可能掉血，快照会让「血量相等/高低」永远停在开局那一刻。
        //   ★ 拿不到（目标物没建起来）⇒ 传 null ⇒ 执行层按「相等」⇒ 右侧（用户口径），不抛。
        driver.setTowerHealthSource(() -> {
            io.mczju.infernalclash.objective.ObjectiveService service = this.objectives;
            if (service == null) {
                return null;
            }
            double[][] towers = service.healthOf(botSide, ObjectiveKind.TOWER);
            double[] health = new double[towers.length];
            for (int i = 0; i < towers.length; i++) {
                health[i] = towers[i] == null || towers[i].length == 0 ? 0.0 : towers[i][0];
            }
            return health;
        });
        // ★ 2026-10-21：手牌来源 = **本局抽中的那个人机**、且「随机」已在开局时解析掉。
        //   ★★ 「偷牌」（steal）此时**还没解析** —— 玩家要到准备阶段才确认卡组 ⇒
        //      真正的解析发生在进入战斗后第一次取手牌时（见 trainingBotHand()）。
        //      所以这里只报「卡组构成」，不报具体偷到哪张（那行日志在解析时打）。
        //   ★ 这里同时报出名字 / 节拍（G11 裁定 = 1 秒 = 20 tick），便于实机日志对账。
        java.util.List<String> botDeck = this.resolvedBotDeck;
        int deckSize = botDeck == null ? 0 : botDeck.size();
        int stealSlots = 0;
        int spellSlots = 0;
        if (botDeck != null) {
            for (String id : botDeck) {
                if (io.mczju.infernalclash.config.TrainingConfig.isStealToken(id)) {
                    stealSlots++;
                } else if (plugin.cardCatalog() != null) {
                    io.mczju.infernalclash.card.Card card = plugin.cardCatalog().cards().get(id);
                    if (card != null && !card.isSummon()) {
                        spellSlots++;
                    }
                }
            }
        }
        logger.info("[IC] 训练人机已启用：matchId="
                + matchId + " 人机在 " + botSide
                + " 方（名册已加入 " + TrainingBotAssembly.botIdFor(matchId, botSide)
                + "，它有自己的经验池）｜名字（侧边栏显示）：" + botName
                + "｜手牌来源："
                + (deckSize == 0
                        ? "空（training.bots 读不到卡？装载期本应已拒绝启动），本局人机不会出牌"
                        : "config.yml 的 training.bots（" + deckSize + " 张，槽位 2~9 按配置顺序"
                                + (spellSlots > 0 ? "；其中法术 " + spellSlots + " 张" : "")
                                + (stealSlots > 0
                                        ? "；其中 steal " + stealSlots
                                                + " 张＝进入战斗后按玩家卡组同位置解析"
                                        : "")
                                + "），本局人机会出牌")
                + "｜节拍：每 " + TrainingBotDriver.CAST_INTERVAL_TICKS + " tick（"
                + (TrainingBotDriver.CAST_INTERVAL_TICKS / 20.0) + " 秒）试一次"
                + "｜法术目标上限：连续 " + TrainingBotDriver.SPELL_TARGET_TIMEOUT_ATTEMPTS
                + " 次（5 秒）没目标就跳过那一张");
        return driver;
    }

    /**
     * ★★ <b>「人机可用卡」的集合</b>（「随机」的候选池）—— 2026-10-21 新增。
     *
     * <p>判据只有一份：{@code config/TrainingConfig#usableCardIds}
     * （卡池 − 速度药水 − 战术冷却 − 回响 − 没配规则的法术卡）。
     * ★ 卡 id 在这里**按字典序排序**再交给解析器：`ai/BotDeckResolver` 内部还会再排一次，
     * 两处都排是为了「同种子 ⇒ 同结果」不依赖任何 Map 的迭代顺序。
     */
    private java.util.Set<String> botUsableCards(InfernalClashPlugin plugin) {
        if (plugin.trainingConfig() == null || plugin.cardCatalog() == null) {
            return java.util.Set.of();
        }
        java.util.List<String> all = new java.util.ArrayList<>(
                new java.util.TreeSet<>(plugin.cardCatalog().cards().keySet()));
        return plugin.trainingConfig().usableCardIds(all,
                id -> plugin.cardCatalog().cards().get(id), plugin.botDeckCardAdapter());
    }

    /**
     * ★★ <b>人机这一手牌的来源</b>：本局抽中的那个 {@code training.bots[]} 的卡组，
     * 其中「随机」占位符已在 {@code onGameStart()} 里解析成具体卡 id
     * （<b>2026-10-21 用户裁定</b>；旧口径 {@code training.bot-deck} 已作废）。
     *
     * <h2>★ 为什么必须记住「解析后的卡组」而不是每 tick 重解析</h2>
     * 「随机」的口径是「**进入对战时**该占位符将会替换为任意一张随机合法卡牌」
     * ⇒ 一局之内必须**稳定**（否则人机每 tick 都可能换一张牌，玩家看到的牌序会乱跳）。
     * 解析结果放在 {@link #resolvedBotDeck}，本方法只做「id → 手牌」的机械转换。
     *
     * <h2>★ 形状（唯一装配点，只有这一处）</h2>
     * 槽位 = <b>格号 2~9</b>（与 {@code game/CardLoadout} 的「2~9 号格」一致；
     * ★ 不是 Bukkit 的 0-based 下标 —— 下标 = 格号 − 1），顺序 = 配置里的书写顺序；
     * {@code cost} / {@code spell} / {@code rule} 一律取自卡池与 {@code training.spell-rules}，
     * 绝不写死。
     *
     * <h2>★ 失败方向（不能抛）</h2>
     * 本方法被 {@code ai/TrainingBotDriver} 在<b>每 tick 的钩子</b>上调用
     * ⇒ 抛异常 = 对局中刷屏。装载期已经把「缺卡 / 不可用卡」拦在启动之外
     * （{@code InfernalClashPlugin#requireBotConfigValid}）⇒ 这里的缺卡兜底
     * 是「跳过该槽位 + 一条 warning」。
     */
    List<BotPlan.HandCard> trainingBotHand() {
        InfernalClashPlugin plugin = plugin();
        if (plugin == null || plugin.trainingConfig() == null || plugin.cardCatalog() == null) {
            // 关服 / 未装配：如实返回空手牌（执行层会「什么都不做」，不会打错牌）
            return List.of();
        }
        List<String> deck = this.resolvedBotDeck;
        if (deck == null || deck.isEmpty()) {
            // 人机没装配（装配失败 / 本局不是训练局）⇒ 如实返回空手牌
            return List.of();
        }
        // ★★★ 2026-10-21 用户追加：「steal 占位符在**对局开始时**替换成玩家卡组对应位置的卡牌」。
        //    ★ 玩家要到**准备阶段确认卡组**才确定 8 张 ⇒ 准备阶段里先**不发牌**
        //      （人机在准备阶段本来也不出牌：onTick 只在 BATTLE/OVERTIME 动手）。
        //    ★ 进入战斗后第一次取手牌时解析**一次**并写回 resolvedBotDeck（一局内稳定）。
        //    ★ 若这一局是「准备阶段到点、有人没拿到选卡机会 ⇒ 不判负、照常进战斗」那条路，
        //      玩家可能还没确认 ⇒ 解析器会把 steal 兜底成随机合法卡（见 BotDeckResolver#applySteal）。
        if (io.mczju.infernalclash.config.TrainingConfig.hasSteal(deck)) {
            MatchPhase phase = match == null ? null : match.phase();
            if (phase != MatchPhase.BATTLE && phase != MatchPhase.OVERTIME) {
                return List.of();
            }
            deck = resolveStealIfReady(deck);
        }
        List<io.mczju.infernalclash.config.TrainingConfig.HandCardSpec> specs =
                plugin.trainingConfig().toHandCards(deck,
                        id -> plugin.cardCatalog().cards().get(id),
                        plugin.botDeckCardAdapter(),   // ★ 卡对象的取字段方式在插件侧（配置层不认识 Card）
                        text -> logger.warn("[IC] " + text));
        List<BotPlan.HandCard> hand = new ArrayList<>(specs.size());
        for (io.mczju.infernalclash.config.TrainingConfig.HandCardSpec spec : specs) {
            hand.add(new BotPlan.HandCard(spec.slot(), spec.cardId(), spec.spell(), spec.cost(),
                    spec.rule(), spec.buildingRule()));
        }
        return List.copyOf(hand);
    }

    /**
     * ★★★ <b>把本局人机卡组里的 {@code steal} 换成玩家同位置的卡</b>（2026-10-21 用户追加）。
     *
     * <p>只做一次：解析结果写回 {@link #resolvedBotDeck}，之后的 tick 走正常路径。
     * 解析规则（位置对齐 / 不可用卡的兜底）全在纯函数
     * {@code ai/BotDeckResolver#applySteal}，本方法只负责取「玩家卡组」和打日志。
     *
     * <p>★ 取的是 {@code match.prep().draft(human)} —— 玩家在 U4 里排好并**确认**的那 8 张
     * （顺序 = 槽位 2~9）。没确认 ⇒ 传 {@code null} ⇒ 全部随机兜底。
     */
    private List<String> resolveStealIfReady(List<String> deck) {
        InfernalClashPlugin plugin = plugin();
        if (plugin == null || match == null) {
            return deck;   // 关服 / 未装配：保持原样（调用方已经判过非空）
        }
        Side human = botSide == null ? null : botSide.other();
        boolean confirmed = human != null && match.prepConfirmed(human);
        List<String> playerDeck = confirmed ? match.prep().draft(human) : null;
        BotDeckResolver.StealResult result = BotDeckResolver.applySteal(
                deck, playerDeck, botUsableCards(plugin), botRandom);
        this.resolvedBotDeck = result.cards();
        logger.info("[IC] 训练人机偷牌完成：matchId=" + match.matchId()
                + "｜人机在 " + botSide + "｜玩家（" + human + "）卡组"
                + (confirmed ? "（已确认）" : "未确认（全部随机兜底）")
                + "｜偷到 " + result.stolen() + " 张，兜底 " + result.fallback() + " 张"
                + " ⇒ " + result.cards()
                + (result.notes().isEmpty() ? "" : "｜" + String.join("；", result.notes())));
        return result.cards();
    }

    /** 本局的快捷栏发放（未开局 / 已清理时为 {@code null}）。 */
    public CardLoadout loadout() {
        return loadout;
    }

    /**
     * 本局的 {@code matchId} = MGC 房间名（`javap` 实测 {@code AbstractGameRoom.getRoomName()}）。
     *
     * <p>为什么用房间名：它天然唯一、运维在 `/mgc` 里看得到，而且**跨重启稳定**
     * （房间数据落盘在 `rooms/<gameId>/<roomName>.json`）—— 拿它当账本分区键，
     * 日志与 PDC 里的值都能被人直接对上号。
     */
    public String matchId() {
        try {
            String name = getGameRoom() == null ? null : getGameRoom().getRoomName();
            return name == null || name.isBlank() ? ID + ":default" : name;
        } catch (Throwable t) {
            // 房间信息都取不到时**绝不猜**：用一个只在本局内唯一的标识，并让日志说明
            return ID + ":unknown-" + System.identityHashCode(this);
        }
    }

    /** 本局的运行时（未开局 / 已清理时为 {@code null}）。 */
    public MatchService matchService() {
        return match;
    }

    @Override
    protected void onGameCancel() {
        logger.info("[IC] 对局取消（等待阶段未开局）");
        // ★ 状态清零（用户要求）：房间被取消 / 复位 ⇒ "已请求的人机"不能留给下一局
        clearBotRequest();
    }

    @Override
    protected void onGameAbort() {
        if (skipCleanupWhileDisabling("abort")) {
            return;
        }
        startFinale("abort");   // 同 onGameEnd：先关 AI，5 秒后收尾
    }

    /**
     * ★★★ <b>把本局真人送回大厅</b>（2026-10-18；用户：「游戏结束后不会自动返回大厅出生点」）。
     *
     * <h2>根因（查证结论）</h2>
     * {@code InfernalClashRoom#teleportReturn(...)} 全项目只有**一个**调用点
     * （{@code InfernalClashQuitStrategy}，即玩家主动退出房间）。
     * 对局**正常结束 / 中止**的收尾链里从来没有调过它 ⇒ 打完之后玩家留在竞技场。
     *
     * <h2>落点（用户裁定 A）</h2>
     * 复用同一条回程链：{@code returnLocation → waitLocation → 入局前位置}。
     * 三者都拿不到 ⇒ {@code teleportReturn} 自己会"不传送 + 告警"（绝不去猜大厅坐标）。
     *
     * <p>★ 收件人 = {@link #participants}（本局名册，只含真人）—— 绝不用 {@code getOnlinePlayers}。
     */
    private void returnPlayersToLobby(String reason) {
        Map<Side, UUID> roster = this.participants;
        if (roster == null || roster.isEmpty()) {
            logger.warn("[IC] 终局回程跳过：本局没有真人名册（" + reason + "）");
            return;
        }
        int moved = 0;
        for (UUID id : roster.values()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;   // 已退出的玩家不追传（入局前位置也在他们退出时已经用掉了）
            }
            try {
                // ★★★ 2026-10-18（用户：「我需要返回 incl 的 lobby（就是使用 mgc 菜单返回的那个地方），
                //   而**不是**游戏开始时的传送点」）：fallback 传 **null**
                //   ⇒ 回程链变成 returnLocation → waitLocation → 什么都不做（告警），
                //     绝不会把玩家丢回"开局那个出生点"。
                InfernalClashRoom.teleportReturn(this, player, null);
                moved++;
            } catch (Throwable t) {
                logger.warn("[IC] 终局回程失败（" + player.getName() + "）：" + t
                        + " —— 该玩家可能仍留在竞技场");
            }
        }
        logger.info("[IC] 终局回程：已把 " + moved + "/" + roster.size() + " 名真人传回大厅（"
                + reason + "）");
    }
    /** 终局等待时长（tick）：用户裁定「等待五秒」= 100 tick。 */
    static final int FINALE_DELAY_TICKS = 100;

    /**
     * ★★★ <b>终局序列</b>（2026-10-18，用户原话：「先清除场上所有 living entity 的 ai，
     * 等待五秒后再执行终局操作（玩家传送回 lobby 大厅出生点、清除 living entity、
     * 清除弹射物掉落物等）」）。
     *
     * <h2>顺序（三步，不可换）</h2>
     * <ol>
     *   <li><b>立刻</b>：关掉本局账本里所有 {@link Mob} 的 AI（{@code setAI(false)} ＋ 清目标 ＋ 停寻路）
     *       —— 让场上的单位"停住"，玩家看到的是定格画面而不是继续互殴；</li>
     *   <li><b>等 5 秒</b>（{@value #FINALE_DELAY_TICKS} tick）；</li>
     *   <li><b>然后</b>：{@link #cleanup(String)}（= 传送玩家回大厅 ＋ 按账本清实体 ＋ 清弹射物）。</li>
     * </ol>
     *
     * <p>★ <b>失败方向</b>：拿不到插件 / 调度器（关服中）⇒ <b>立刻同步收尾</b>，
     * 绝不让"终局"消失（那会让房间卡在 IN_GAME）。
     */
    private void startFinale(String reason) {
        InfernalClashPlugin plugin = plugin();
        MatchService service = this.match;
        int frozen = freezeLedgerAi();
        if (frozen >= 0) {
            logger.info("[IC] 终局：已关闭本局 " + frozen + " 个单位的 AI（" + reason
                    + "），" + FINALE_DELAY_TICKS + " tick 后传送与清场");
        }
        if (plugin == null || !plugin.isEnabled()) {
            cleanup(reason);   // 关服中：没有调度器可用 ⇒ 立刻收尾
            return;
        }
        try {
            if (service == null || service.tasks() == null) {
                cleanup(reason);
                return;
            }
            service.tasks().runLater(plugin, () -> {
                try {
                    // ★★ 终局 5 秒窗口结束：**这里**才真的回收目标物/召唤物/弹射物
                    //   （窗口内只关过 AI，实体一个都没删）
                    service.completeDeferredCleanup();
                    cleanup(reason);
                } catch (Throwable t) {
                    logger.warn("[IC] 终局收尾失败（" + reason + "）：" + t
                            + " —— 玩家可能仍留在竞技场，可用 /incl debug room reset 复位");
                }
            }, FINALE_DELAY_TICKS);
        } catch (Throwable t) {
            logger.warn("[IC] 终局延时任务没能排上，立刻收尾（只影响那 5 秒停顿）：" + t);
            cleanup(reason);
        }
    }

    /**
     * 把**本局账本**里所有 Mob 的 AI 关掉（返回关了几个；{@code -1} = 拿不到账本）。
     *
     * <p>★ 范围：<b>只认账本</b>（{@code UnitRegistry#tracked(matchId)}）—— 不动别人插件的怪、
     * 动物、村民，也不查世界（铁律 10）。
     */
    private int freezeLedgerAi() {
        InfernalClashPlugin plugin = plugin();
        MatchService service = this.match;
        if (plugin == null || service == null || plugin.unitRegistry() == null) {
            return -1;
        }
        int frozen = 0;
        for (UUID id : plugin.unitRegistry().tracked(service.matchId())) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Mob mob) || mob.isDead()) {
                continue;
            }
            try {
                mob.setAI(false);
                mob.setTarget(null);
                mob.getPathfinder().stopPathfinding();
                frozen++;
            } catch (Throwable ignored) {
                // 实体正在卸载：忽略（终局不该被它打断）
            }
        }
        return frozen;
    }

    @Override
    protected void onGameEnd() {
        if (skipCleanupWhileDisabling("end")) {
            return;
        }
        // ★★★ 2026-10-18（用户：「对局结束后，先清除场上所有 living entity 的 ai，
        //   等待五秒后再执行终局操作（玩家传送回 lobby、清除实体、清除弹射物掉落物等）」）：
        //   ① **立刻**关掉本局账本里所有 Mob 的 AI（只动账本里的，铁律 10）；
        //   ② **5 秒后**才真的收尾（传送 + 清场）。
        //   ★ 定时器挂在本局的 LifecycleTasks 上 ⇒ 对局一旦真的停止，它会被一起取消
        //     （绝不留下"对局结束后还有一个裸调度器在跑"那种形状）。
        //   ★ 这 5 秒里竞技场**还占着池子**（MatchService#stop 还没跑）⇒
        //     新的一局不会挤进同一个场子 —— 这正是我们要的。
        startFinale("end");
    }

    /**
     * 对局清理（幂等）。
     *
     * <p>★ 从 P2 起这里**真的有事要做**了：本局的 {@link MatchService} 必须被停掉 ——
     * 它会取消本局**全部**定时任务（延时召唤 / 金色粒子跟随 / 冷却到期）并清场
     * （只删**本场登记过**的实体）。漏掉这一步的典型症状是
     * 「下一局凭空冒出召唤物」（{@code docs/04} §12.3 逐条列过）。
     *
     * <p>仍然**不做**任何广域操作：不改世界设置、不动别人的实体、不按世界清场。
     */
    private void cleanup(String reason) {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        // ★★★ 2026-10-18（用户：「游戏结束后不会自动返回大厅出生点」）：
        //   收尾的**第一步**就是把真人送回大厅 —— 在删实体之前做（玩家还站在竞技场里）。
        //   ★ 复用玩家主动退出房间时的**同一条链**：returnLocation → waitLocation → 入局前位置。
        returnPlayersToLobby(reason);

        MatchService service = this.match;
        this.match = null;
        this.objectives = null;
        this.loadout = null;
        this.combat = null;
        this.trainingBot = null;
        // ★ 2026-10-21：本局抽中的人机与解析后的卡组也要清掉 ——
        //   房间会被复用，漏掉这两格会让下一局（两名真人 / 换了一个人机）继承上一局的名字与牌序。
        this.botName = null;
        this.resolvedBotDeck = null;
        this.botSide = null;
        // ★ 状态清零（用户要求）：**玩家全退 / 中止 / 正常结束都经过这里** ⇒
        //   「已请求的人机」不许留给复用同一间房的下一局（与 joinLocations / participants 同口径）。
        clearBotRequest();
        if (service != null) {
            // 取消定时任务 + 让钩子回收目标物 + 按账本兜底清场
            // （内部按铁律 10 只动本场登记的 UUID，并打数量日志）
            service.stop(reason);
        }

        InfernalClashPlugin plugin = plugin();
        int ledger = plugin == null || plugin.unitRegistry() == null
                ? 0
                : plugin.unitRegistry().table().size();
        logger.info("[IC] 对局清理（" + reason + "）｜活跃对局数 " + MatchService.activeCount()
                + "｜账本剩余登记 " + ledger + " 条");
        joinLocations.clear();

        // ★★ P9：把这一局的胜负记进战绩（排行榜）。位置与两条约束都是刻意的：
        //   ① 必须是**既有清理顺序之后**的独立一步 —— 绝不插进 stop() / 房间释放之间，
        //      改那个顺序会让房间停在 IN_GAME（AGENTS.md §4.2）；
        //   ② 绝不能挂在 finish() / MatchService 的 onFinished 上 —— **被中止的对局不走 finish()**
        //      （同 §4.2 的实测），而本方法（cleanup）是正常结束与中止**都会经过**的那一处。
        //   记账失败只告警，绝不打断收尾（recordMatchResult 内部 catch Throwable）。
        recordMatchResult(reason, service);
        // 房间会被复用 ⇒ 名册不能留给下一局（与 joinLocations 同一口径）
        participants = Map.of();
    }

    // ---------------- ★ P9：一局结果 ⇒ 战绩（排行榜） ----------------

    /**
     * ★ <b>一局的记账行</b>（纯数据）：谁、按什么口径（真人局 / 人机局）、记成什么结果。
     *
     * <p>只承载判定的<b>结果</b>，不碰服务器、不碰档案 ⇒ 可以离线单测
     * （{@code game/MatchRecordWiringTest}）。
     */
    record ResultRow(UUID player, LeaderboardStats.MatchKind kind, LeaderboardStats.Outcome outcome) {
    }

    /**
     * ★★ <b>纯函数</b>：一局的胜负 + 参与者 ⇒ 每人该记成什么（{@code AGENTS.md} §3：新算法先做成纯函数）。
     *
     * <h2>口径（★ 一条都不是我选的，全部照抄文档）</h2>
     * <table border="1">
     *   <caption>权威：{@code docs/08} §7 / §8.3 + {@code docs/02} §4 / §9</caption>
     *   <tr><th>情形</th><th>结果</th><th>出处</th></tr>
     *   <tr><td>{@code outcome == NONE} / {@code null}（<b>中止的对局</b>）</td>
     *       <td><b>空列表</b>（什么都不记）</td>
     *       <td>{@code docs/02} §4 的胜负矩阵只有胜 / 负 / 平三档 ⇒「没打完」不在统计口径内</td></tr>
     *   <tr><td>{@code RED_WINS} / {@code BLUE_WINS}</td>
     *       <td>胜方 {@code WIN}、负方 {@code LOSS}（双方各记一行）</td>
     *       <td>{@code docs/08} §8.3「赢 +1、输清零」</td></tr>
     *   <tr><td>{@code DRAW}（W5 / W6）</td><td>双方都 {@code DRAW}</td>
     *       <td>{@code docs/02} §4；★「平局是否计入」是缺口 G4，由纯核心拒绝更新（<b>本函数不猜</b>）</td></tr>
     *   <tr><td><b>只有一个阵营有真人</b></td><td>口径记成 {@code VERSUS_BOT}</td>
     *       <td>{@code docs/02} §9 人机 ＋ {@code docs/24} §5.5（2026-10-15 裁定：人机由显式
     *           {@code /incl bot on} 开启）—— 单个真人的对局就是训练局，<b>与"何时开"无关</b></td></tr>
     * </table>
     *
     * <p>★★ <b>「人机局不计」的实现不在本函数里</b>：本函数只把口径标成
     * {@code VERSUS_BOT}，真正的闸门是纯核心 {@link LeaderboardStats#record}
     * （{@code docs/08} §7 R63「只计真人对局胜场（人机局不计）」）——
     * 在这里再写一遍 if 就是「第二处事实来源」，迟早分叉
     * （{@code TrainingMatchRules#countsForLeaderboard} 同样是向核心问一句，不是自己判）。
     *
     * @param outcome      本局结果（{@code null} / {@code NONE} ⇒ 空列表）
     * @param participants 本局参与者（{@code 阵营 → UUID}）；{@code null} / 空 ⇒ 空列表
     */
    static List<ResultRow> resultRows(MatchOutcome outcome, Map<Side, UUID> participants) {
        if (outcome == null || outcome == MatchOutcome.NONE) {
            // 中止 / 未分胜负：没有胜负可分，一个字段都不动（也绝不"算他赢"）
            return List.of();
        }
        if (participants == null || participants.isEmpty()) {
            return List.of();
        }

        // ★ 人机局判定：只有一个阵营有真人 ⇒ 那必然是训练局
        //   （★ 2026-10-15 裁定后，"训练局"由显式 /incl bot on 开启，但**判据不变**：
        //     `participants` 里只装真人，所以"只有一方有真人"就是训练局 —— 见字段注释。
        //     口径留档：docs/24 §5.5；它取代的 R62 只改了"何时开"，没改"怎么记"。）
        //   判据只是「有几个阵营有真人」这一个数，不查世界、不看实体（铁律 10）。
        LeaderboardStats.MatchKind kind = participants.size() >= 2
                ? LeaderboardStats.MatchKind.VERSUS_PLAYER
                : LeaderboardStats.MatchKind.VERSUS_BOT;

        List<ResultRow> rows = new ArrayList<>();
        for (Map.Entry<Side, UUID> entry : participants.entrySet()) {
            UUID player = entry.getValue();
            if (player == null) {
                continue;   // 名册里的坏值：跳过这一行（不猜是谁）
            }
            LeaderboardStats.Outcome row = outcomeOf(outcome, entry.getKey());
            if (row != null) {
                rows.add(new ResultRow(player, kind, row));
            }
        }
        return List.copyOf(rows);
    }

    /** 某一方在这一局里的结果（平局两边都是 {@code DRAW}）；{@code NONE} ⇒ {@code null}（不记）。 */
    private static LeaderboardStats.Outcome outcomeOf(MatchOutcome outcome, Side side) {
        return switch (outcome) {
            case DRAW -> LeaderboardStats.Outcome.DRAW;
            case RED_WINS -> side == Side.RED
                    ? LeaderboardStats.Outcome.WIN : LeaderboardStats.Outcome.LOSS;
            case BLUE_WINS -> side == Side.BLUE
                    ? LeaderboardStats.Outcome.WIN : LeaderboardStats.Outcome.LOSS;
            case NONE -> null;
        };
    }

    /**
     * ★★★ <b>「口径 + 胜负」⇒ 金币结算的五档</b>（纯粹为了可离线单测，2026-10-20 新增）。
     *
     * <p>它<b>只做映射</b>，一个数值都不碰：真正给多少钱由
     * {@code policy/MatchPayout}（纯函数）从 {@code economy.yml} 算。
     * 这样「人机局也算钱」这条口径在这里只有一行（{@code VERSUS_BOT} 不等于「没资格发钱」），
     * 改坏了会在 {@code MatchRecordWiringTest} 里立刻变红。
     *
     * @param kind    口径（{@code VERSUS_PLAYER} / {@code VERSUS_BOT}）
     * @param outcome 胜负（{@code WIN} / {@code LOSS} / {@code DRAW}）
     * @return 五档结果；{@code null} / 缺参数 ⇒ {@code null}（调用方跳过结算）
     */
    public static MatchPayout.Result payoutResult(LeaderboardStats.MatchKind kind,
                                                  LeaderboardStats.Outcome outcome) {
        if (kind == null || outcome == null) {
            return null;
        }
        return switch (outcome) {
            // ★★ 平局（2026-10-20 用户裁定：「平局在发金币这块算双方胜利」）
            //   ⇒ 按**各自口径的胜**那一档发：真人局走 PVP_WIN、人机局走 BOT_WIN。
            //   ★ 排行榜那套不受影响（平局仍不计胜场，见 docs/24 §10 第 17 条）。
            case DRAW -> kind == LeaderboardStats.MatchKind.VERSUS_BOT
                    ? MatchPayout.Result.DRAW_BOT : MatchPayout.Result.DRAW_PVP;
            case WIN -> kind == LeaderboardStats.MatchKind.VERSUS_BOT
                    ? MatchPayout.Result.BOT_WIN : MatchPayout.Result.PVP_WIN;
            case LOSS -> kind == LeaderboardStats.MatchKind.VERSUS_BOT
                    ? MatchPayout.Result.BOT_LOSS : MatchPayout.Result.PVP_LOSS;
        };
    }

    /**
     * ★ <b>把这一局的胜负提交给战绩（排行榜）</b> —— 对局收尾侧的唯一入口是
     * {@link InfernalClashPlugin#recordMatchResult}（{@code docs/04} §16.13.2：
     * 「唯一入口」；榜单 id / 档案字段名 / MGC 刷新时机全在 {@code leaderboard/**}）。
     *
     * <h2>★★ 失败方向（{@code docs/04} §16.13.4）</h2>
     * 本方法<b>整体</b>被 {@code catch (Throwable)} 包住，失败<b>只告警</b>：
     * 它跑在 {@link #cleanup(String)} 里，也就是「房间释放 / 实体清场」的同一条路径上 ——
     * 记战绩把异常抛出去 = <b>一局对局收不了尾</b>（房间卡在 {@code IN_GAME}）。
     * 「榜单挂了」的代价应该是<b>少一个榜</b>，不是「玩家玩不了」。
     *
     * <h2>只记「真的打完了」的对局</h2>
     * {@code phase != ENDED}（被中止 / 关服）⇒ 一句 INFO 说明「没有胜负可记」并<b>什么都不写</b>。
     * 这正是 {@code AGENTS.md} §4.2「<b>被中止的对局不走 {@code finish()}</b>」那条实测要处理的情形 ——
     * 所以记账挂在本路径而不是 {@code finish()} 上。
     *
     * @param reason  收尾原因（日志用）
     * @param service 本局的运行时（未开局 / 已清理 ⇒ {@code null}）
     */
    private void recordMatchResult(String reason, MatchService service) {
        try {
            MatchPhase phase = service == null ? null : service.state().phase();
            MatchOutcome outcome = service == null ? MatchOutcome.NONE : service.state().outcome();

            if (phase != MatchPhase.ENDED) {
                logger.info("[IC] 不记战绩（" + reason + "）：本局没有走到 ENDED（phase=" + phase
                        + "、outcome=" + outcome + "），中止的对局没有胜负可分。"
                        + "记账挂在 cleanup() 而非 finish()，正是因为被中止的对局不走 finish()"
                        + "（AGENTS.md §4.2）");
                return;
            }

            List<ResultRow> rows = resultRows(outcome, participants);
            if (rows.isEmpty()) {
                logger.warn("[IC] 战绩未记录：phase=ENDED、outcome=" + outcome
                        + "，但算不出任何一行（参与者 " + participants.size()
                        + " 名），见 InfernalClashGame#resultRows 的口径");
                return;
            }

            InfernalClashPlugin plugin = plugin();
            if (plugin == null) {
                logger.warn("[IC] 战绩未记录：" + rows.size() + " 行无法提交 —— 插件实例不可用"
                        + "（关服 / 离线）");
                return;
            }

            for (ResultRow row : rows) {
                // ★ 失败只告警（内部还有一层 catch Throwable），返回值不需要判断：
                //   「档案缺字段 / 人机局 / 平局」都是正常路径，各自会打一条说明原因的日志
                plugin.recordMatchResult(row.player(), row.kind(), row.outcome());
                // ★★★ 2026-10-20：**金币单独结算**（以前这条链根本不存在，见 MatchPayout 的类注释）。
                //   ★ 刻意**不**放进 recordMatchResult：那条链只记真人局（R63），
                //     而人机局的金币照发（Q55）⇒ 合并会让「榜拒了 ⇒ 钱也没了」。
                //   ★ 同一条 try 里调：它自己也 catch Throwable，失败只告警。
                plugin.grantMatchReward(row.player(), row.kind(), row.outcome());
            }
            // ★ 带数量的日志（可对账）：记了几个、什么结果、谁的名册
            logger.info("[IC] 已提交战绩 " + rows.size() + " 行（matchId=" + matchId()
                    + "｜结果 " + outcome + "｜参战 " + participants.size() + " 名｜口径 "
                    + rows.get(0).kind() + "）");
        } catch (Throwable t) {
            logger.warn("[IC] 记录战绩失败，已忽略（绝不影响对局收尾与房间释放）：" + t);
        }
    }

    /** 本局是否已清理。 */
    public boolean isCleanedUp() {
        return cleanedUp;
    }

    /**
     * 关服流程里的安全护栏。
     *
     * <p>实测调用链：{@code MCZJUGameCore.onDisable} → {@code forceAbortGame}
     * → {@code solveGameEnd} → {@code PlayerManager.removeAllPlayer(game)}
     * → 对局回调；而此时**我们的插件早已 disabled**，任何
     * {@code getScheduler()} 调用都会抛
     * {@code IllegalPluginAccessException: Plugin attempted to register task while disabled}。
     *
     * <p>护栏本身也是正确行为：插件已禁用后就不该再做收尾。
     *
     * @return true = 已跳过收尾
     */
    private boolean skipCleanupWhileDisabling(String reason) {
        InfernalClashPlugin plugin = plugin();
        if (plugin != null && plugin.isEnabled()) {
            return false;
        }
        logger.warn("[IC] 收到对局" + reason + "回调，但本插件已禁用 → 跳过收尾");
        return true;
    }

    /**
     * ★★ <b>结束对局的正确入口</b>：必须走 {@code GameManager}。
     *
     * <h2>为什么不能直接调 {@code AbstractGame.endGame()}</h2>
     * MGC 1.1.2 里这两个方法**完全不是一回事**（字节码实测）：
     * <pre>
     * // AbstractGame.endGame()  —— 错误入口
     * { onGameEnd(); setState(...); }          // 只回调游戏自己，不碰房间、不碰玩家
     *
     * // DefaultGameManager.endGame(game)  —— ★ 正确入口
     * try { game.endGame(); } finally { solveGameEnd(game); }
     *
     * // DefaultGameManager.solveGameEnd(game)
     * game.setState(终态);
     * MCZJUGameCore.getPlayerManager().removeAllPlayer(game);   // 把玩家移出对局
     * game.getGameRoom().setState(GameRoomState.READY);         // ★★ 释放房间
     * gameList.remove(game);
     * </pre>
     * 调错的实测症状：我们这边日志一切正常，但**房间一直停在 {@code IN_GAME}**
     * → 之后 {@code /mgc join} 报「该游戏没有空闲的房间」，
     * 且**玩家仍留在对局里**（profile 没切回 lobby）。
     * 又因房间 state **不落盘**，重启即恢复 —— 表现为「隔一阵子就没房间了」。
     *
     * <p>不会递归：{@code GameManager.endGame} 内部调 {@code game.endGame()}
     * → 我们的 {@code onGameEnd()} 只做清理，**不再回调 endGame**。
     */
    private void endViaManager() {
        try {
            // ★ 这一行是「本次结束真的走了正规入口」的唯一日志证据 —— 排查时靠它判断新 jar 是否生效
            logger.info("[IC] 通知 MGC 结束对局（走 GameManager → 会释放房间）");
            MCZJUGameCore.getGameManager().endGame(this);
        } catch (Throwable t) {
            logger.error("[IC] 通知 MGC 结束对局失败（房间可能被占住，"
                    + "可用 /incl debug room reset 复位）：" + t);
        }
    }

    /** ★ <b>中止对局的正确入口</b>，理由同 {@link #endViaManager()}。 */
    private void abortViaManager() {
        try {
            logger.info("[IC] 通知 MGC 中止对局（走 GameManager → 会释放房间）");
            MCZJUGameCore.getGameManager().abortGame(this);
        } catch (Throwable t) {
            logger.error("[IC] 通知 MGC 中止对局失败（房间可能被占住）：" + t);
        }
    }

    /**
     * ★ 对局内**一名真人都没有**时立即中止（供 {@link InfernalClashQuitStrategy} 调用）。
     *
     * <p>MGC 只在 {@code WAITING} 阶段自己处理「人跑光了」；{@code RUNNING} 阶段它只把玩家
     * 从自己的名单里摘掉，**不会结束对局** —— 不自己收尾的话，对局会带着 0 名参与者跑完，
     * 期间房间一直 {@code IN_GAME}，玩家再 {@code /mgc join} 会被拒。
     *
     * @param trigger 触发原因（写进日志）
     * @return 是否真的发起了中止
     */
    boolean abortIfNoPlayersLeft(String trigger) {
        if (!getPlayers().isEmpty()) {
            return false;
        }
        if (cleanedUp) {
            return false;   // 已经收尾过，避免重复中止
        }
        logger.info("[IC] 对局内已无任何真人（" + trigger + "）→ 立即中止，不让房间空转");
        abortViaManager();
        return true;
    }

    // ---------------- ★★★ 玩家退出 / 掉线 ⇒ 判负（2026-10-20 用户裁定「掉线/leave=投降」） ----------------

    /**
     * ★★ <b>名册里这个 UUID 的阵营</b>（{@code participants} 是「阵营 → UUID」；不在名册 ⇒ {@code null}）。
     *
     * <p>★ 它只读**本局名册**（{@link #participants}，只含真人），不查在线列表、不查实体
     * （铁律 10：不做广域查询）。
     */
    public Side sideOfParticipant(java.util.UUID player) {
        if (player == null) {
            return null;
        }
        for (Map.Entry<Side, UUID> entry : participants.entrySet()) {
            if (player.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * ★ <b>本局对局现在是否还在运行</b>（供退出处置判据用）。
     *
     * <p>判据是 {@code MatchService#state().isRunning()} —— 与「能不能出牌」同一个事实来源
     * （{@code canCast()} 也看它），不另立第二处。
     */
    public boolean isMatchRunning() {
        MatchService service = this.match;
        return service != null && service.state() != null && service.state().isRunning();
    }

    /**
     * ★★★ <b>玩家退出 / 掉线 ⇒ 按投降判负</b>（2026-10-20 用户裁定「掉线/leave=投降」）。
     *
     * <h2>口径（一条都不是我选的）</h2>
     * <ul>
     *   <li>{@code docs/05} <b>Q28</b>「玩家退出对局 ⇒ <b>判负</b>」；</li>
     *   <li>{@code docs/07} §9 逐字「本项目退出即<b>判该方失败</b>并走结算，<b>不沿用</b>
     *       其『继续打』的规则」；</li>
     *   <li>2026-10-20 用户逐字确认：「<b>掉线/leave=投降</b>」。</li>
     * </ul>
     *
     * <h2>★★ 复用既有收尾路径</h2>
     * 走 {@link MatchService#surrender}（与「投降」按钮**同一个**入口）⇒ {@code finishWith}
     * ⇒ {@code finish} ⇒ MGC {@code endGame}。**没有**新造一条绕开对局收尾的路
     * （铁律：收尾只有一处）。
     *
     * @param leaver 退出者的 UUID
     * @param reason 原因（写进日志）
     * @return 是否真的判了负（不在名册 / 对局已结束 / 已收尾 ⇒ {@code false}）
     */
    public boolean forfeitOnQuit(java.util.UUID leaver, String reason) {
        MatchService service = this.match;
        if (service == null || leaver == null) {
            return false;
        }
        Side side = sideOfParticipant(leaver);
        if (side == null) {
            logger.info("[IC] 退出判负跳过：该玩家不在本局名册里（观战 / 走错房间），不判，"
                    + "免得给无辜的一方记负｜" + reason);
            return false;
        }
        if (cleanedUp || !service.state().isRunning()) {
            logger.info("[IC] 退出判负跳过：对局已不在运行（已结算 / 已收尾），不重复判｜" + reason);
            return false;
        }
        logger.info("[IC] 退出判负：" + side + " 方的玩家退出了对局（" + reason + "）"
                + "，按投降处理");
        return service.surrender(side);
    }

    // ---------------- ★★ 开局失败的两种情形（文案 ＋ 纯函数判据） ----------------

    /**
     * ★★ <b>文案①：{@code startGame()} 调用本身抛异常 ⇒ 局<b>没有</b>开起来。</b>
     *
     * <p>此时 MGC 的状态机还停在 {@code WAITING}（{@code AbstractGame.gameStart()} 的第一句才是
     * {@code setState(STATING)}，见 {@code docs/06} §2.2 的 {@code javap} 实测）
     * ⇒ 房间与对局都还是「可以重试」的样子。
     */
    static final String START_FAILED_MESSAGE = "[IC] startGame() 本身失败（局未开）：";

    /**
     * ★★ <b>文案②：局<b>已经</b>开了，但开局回调（{@code onGameStart()}）里的后续步骤抛异常。</b>
     *
     * <p>判据是「当时的 {@code state} 不是 {@code WAITING}」—— 因为 {@code gameStart()} 的第一句
     * 就已经把状态置成 {@code STATING} 了 ⇒ 回调里抛出来的异常<b>必然</b>留下一个非 WAITING 的状态。
     * ★ 实测口径（MGC 1.1.2 {@code javap}）：回调抛异常时状态停在 <b>{@code STATING}</b>，
     * <b>不是</b> {@code RUNNING}（{@code setState(RUNNING)} 在 {@code onGameStart()} <b>之后</b>）
     * ⇒ 文案里的 state 一律<b>照当时的值打印</b>，不写死任何一种。
     */
    static final String CALLBACK_FAILED_MESSAGE = "[IC] 局已开，但开局回调里的后续步骤失败：";

    /**
     * ★★ <b>纯函数：把 {@code startGame()} 抛出的异常翻译成该打哪一条告警</b>
     * （2026-10-16 第七轮：修掉一句**说反了**的 WARN）。
     *
     * <h2>它修的是什么</h2>
     * 现场（{@code dev-log/2026-10-16-026} §一）：日志里 {@code 对局开始} 与
     * {@code 目标物已就位} 都<b>已经</b>打出来了，紧接着却是
     * 「{@code [IC] 直接 startGame() 失败（★ 不重试、不重复开局；房间仍是等待态…）}」——
     * 这句旧文案把人往完全错误的方向指（读者会以为「局没开、房间还在等人」，
     * 实际上局开了、目标物生成了、房间已经被占住）。
     * <p>根因是 {@code startGame()} 里会<b>同步</b>跑 {@code onGameStart()}：
     * 回调内部抛的异常也会落到同一个 {@code catch} 里。⇒ 用**当时的状态**把两种情况分开。
     *
     * <h2>三档（为什么有第三档）</h2>
     * <ol>
     *   <li>{@code WAITING} ⇒ {@link #START_FAILED_MESSAGE}（局没开 ⇒ 可以重试）；</li>
     *   <li>{@code STATING} / {@code RUNNING} ⇒ {@link #CALLBACK_FAILED_MESSAGE}
     *       （局已开 ⇒ <b>不要</b>再敲 {@code /mgc start}，那是重复开局）；</li>
     *   <li>其它（{@code END} / {@code null} 等）⇒ 如实说明「既不是 WAITING 也不是
     *       STATING/RUNNING」并要求把这一行一起报上来 —— ★ 宁可多一档，
     *       也不要把一个说不清的状态硬塞进上面两档里（那正是这次修的病）。
     *   </li>
     * </ol>
     *
     * @param state 抛异常**当时**的 {@code getState()}（可以是 {@code null}）
     * @param cause 抛出来的异常（可以是 {@code null}）
     */
    static String startFailureWarning(GameState state, Throwable cause) {
        String reason = TrainingBotAssembly.describe(cause);
        if (state == GameState.WAITING) {
            return START_FAILED_MESSAGE + "state=" + state + "｜原因：" + reason
                    + "（不重试、不重复开局；可敲 /mgc start 或重新 /incl bot on 重试）";
        }
        if (state == GameState.STATING || state == GameState.RUNNING) {
            return CALLBACK_FAILED_MESSAGE + "state=" + state + "｜原因：" + reason
                    + "（不重试、不重复开局）｜人机可能没上场，对局继续"
                    + "｜若 /mgc start 回「游戏不在等待状态」或 /mgc join 回"
                    + "「游戏已开始，无法中途加入」：那是【对局实例】还占着（复位房间没用），"
                    + "见 /incl debug room reset 的输出";
        }
        return "[IC] startGame() 抛异常，但当时的状态既不是 WAITING 也不是 STATING/RUNNING：state="
                + state + "｜原因：" + reason + "｜请连同这一行一起报告（这种状态没有被预期过）";
    }

    /** 插件实例（MGC 反射创建本类，因此取插件只能走静态单例）。 */
    public InfernalClashPlugin plugin() {
        return InfernalClashPlugin.getInstance();
    }
}
