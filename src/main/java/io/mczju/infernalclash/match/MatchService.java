package io.mczju.infernalclash.match;

import io.mczju.infernalclash.policy.XpCurve;
import io.mczju.infernalclash.policy.XpPool;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * ★★ <b>一场对局的运行时</b> —— 按 {@code matchId} 索引（【Q44】多场并发）。
 *
 * <h2>为什么按 matchId 索引</h2>
 * InfernalGuess 的 {@code BattleService} 是**单场单例**（`prepare` 里 `if (current != null) throw`），
 * 两个房间同时开打就会互相踩。本项目从第一行起就按 {@code matchId}（= MGC 房间名）索引。
 *
 * <h2>时钟：**一个全局任务**，不是每场一个</h2>
 * {@link #tickAll()} 由插件注册的**单个** 1-tick 任务驱动，遍历 {@link #ACTIVE}。
 * 每场一个任务在 3 个房间时就是 3 个任务 + 3 份调度开销，且「忘了取消」的爆炸半径更大。
 *
 * <h2>P2 阶段的行为（诚实说明）</h2>
 * <ul>
 *   <li>还没有塔与信标 ⇒ 传给状态机的 {@link MatchSignal} **恒为 {@link MatchSignal#NONE}**；
 *       于是战斗耗尽**必然进入加时**，加时结束仍相等 ⇒ **平局** ——
 *       这正好让「跑完整条时间轴」在 P2 就能被验证（P3 填进目标物后胜负矩阵自动生效，状态机不用改）。</li>
 *   <li>还没有卡牌 ⇒ 经验池**照常累加**（P0 的 {@link XpCurve} 直接复用），只是暂时没人花。</li>
 * </ul>
 *
 * <p>★ 本类不做任何广域操作：实体操作一律经 {@link UnitRegistry}（PDC + 追踪表）。
 */
public final class MatchService {

    /** 活跃对局索引：`matchId → MatchService`（并发安全，但**只在主线程改**）。 */
    private static final Map<String, MatchService> ACTIVE = new ConcurrentHashMap<>();

    /** 一方的运行时状态（1v1 ⇒ 每方一名玩家；`docs/02` §5）。 */
    public static final class SideState {

        private final UUID player;
        private final String name;
        private double xp;

        SideState(UUID player, String name) {
            this.player = player;
            this.name = name;
        }

        /** 该方玩家 UUID。 */
        public UUID player() {
            return player;
        }

        /** 该方玩家名（日志用）。 */
        public String name() {
            return name;
        }

        /** 当前经验池（单位 = 「级」，见 {@link XpCurve}）。 */
        public double xp() {
            return xp;
        }
    }

    private final Plugin plugin;
    private final String matchId;
    private final PhaseConfig config;
    private final UnitRegistry units;
    private final XpCurve xpCurve;
    private final LifecycleTasks tasks = new LifecycleTasks();
    private final Map<Side, SideState> sides = new EnumMap<>(Side.class);
    private final Consumer<String> broadcast;
    private final Runnable onFinished;
    private final MatchHooks hooks;
    private final Logger logger;

    private MatchState state = MatchState.INITIAL;
    private boolean stopped;

    /**
     * ★★ <b>准备阶段的「选卡会话」</b>（2026-10-16 第八轮新增）——
     * 谁确认过卡组、每人挑了哪 8 张；规则在纯类
     * {@link io.mczju.infernalclash.policy.PrepSession} 与
     * {@link io.mczju.infernalclash.policy.PrepDeadline}。
     *
     * <p>会话里的阵营 = <b>本局的真人</b>（{@link #humanSides}）——
     * 人机<b>不进会话</b>（它的 8 张牌来自 {@code config.yml} 的 {@code training.bots}，
     * 已实机证实每秒出牌）⇒ 「人机也被要求确认」这类反例在纯函数层就不可能发生。
     *
     * <p>★ 界面（{@code ui/MatchDeckMenu}）只经 {@link #prep()} 读写它，
     * 于是「什么叫做确认」只有一处判据。
     */
    private final io.mczju.infernalclash.policy.PrepSession prep;

    /**
     * ★★★ 本局的**卡牌冷却表**（见 {@link #cooldowns()}）。
     * ★ 它是本类的字段 ⇒ 新对局自动是空表 ⇒「每局开始清零」结构性成立。
     */
    private final io.mczju.infernalclash.policy.CardCooldowns cooldowns =
            new io.mczju.infernalclash.policy.CardCooldowns();

    /**
     * ★★★ 本局的「每方上一张打出的牌」（见 {@link #castMemory()}；2026-10-19「回响」）。
     *
     * <p>★ 与 {@link #cooldowns} 同一形状：**每局一个新实例** ⇒ 不需要显式清零。
     */
    private final io.mczju.infernalclash.policy.CastMemory castMemory =
            new io.mczju.infernalclash.policy.CastMemory();

    /**
     * ★★★ <b>本局的「援军」状态</b>（1.1.0；见 {@link #reinforcementMemory()}）。
     *
     * <p>★ 与 {@link #castMemory} 同一形状：**每局一个新实例** ⇒ 不需要显式清零。
     * ★ 构造器里两方直接进入「记录下一张牌」状态（卡面「对战开始时…记录」）。
     */
    private final io.mczju.infernalclash.policy.ReinforcementMemory reinforcementMemory =
            new io.mczju.infernalclash.policy.ReinforcementMemory();
    /**
     * ★ <b>本局的真人阵营</b>（= 需要确认卡组的那几方）——构造时按名册算一次。
     *
     * <p>为什么不在 {@link #confirmDeck} 里现算：名册在 {@code MatchService.start} 之前
     * 就被 {@code TrainingBotAssembly#appendBotToRoster} 追加过一条人机
     * （{@code docs/24} §5.5 的人机开局）⇒ 「谁是真人」必须在构造期定下来，
     * 否则运行期再也分不出来。
     */
    private final Set<Side> humanSides;

    /**
     * ★★★ <b>本局用的是哪一座竞技场</b>（2026-10-18 新增；用户：「**我需要让房间开启时随机选择
     * 一个空的竞技场（可参考 ig）**」）。
     *
     * <h2>为什么必须挂在对局上（而不是继续读 {plugin.arenaConfig()}）</h2>
     * 多竞技场要**同时**开打 ⇒ 「本局用哪一座」不再是全局常量。落在对局上之后：
     * <ul>
     *   <li>目标物生成（{@code ObjectiveService}）与半场判定（{@code PlacementResolver}）
     *       读的是<b>本局</b>的点位 ⇒ 两局不会互相串场；</li>
     *   <li>收尾时按 {@link #arenaName} 把场子**还回池子**（{@code ArenaPool#release}）。</li>
     * </ul>
     * ★ {@code null} = 没有绑定（老调用点 / 离线夹具）⇒ 调用方退回 {@code plugin.arenaConfig()}。
     */
    private String arenaName;

    /** 本局的点位表（见 {@link #arenaName}）；{@code null} ⇒ 调用方退回全局默认。 */
    private io.mczju.infernalclash.arena.ArenaConfig arena;

    /**
     * ★★★ <b>正在「终局 5 秒窗口」里的对局</b>（2026-10-18）。
     *
     * <p>为什么要单独记一份：窗口内对局已经被 {@code ACTIVE} 摘掉（那是"停止"的语义），
     * 但实体**还没删**。而插件每秒会跑一次「补删上局遗留实体」
     * （{@code UnitRegistry#retryPendingRemovals}）——它会把窗口里的实体当成"上局遗留"
     * **立刻删掉**，于是用户看到的正是"投降那一刻所有召唤物都消失了"（现场日志：
     * {@code 上局遗留实体已补删 16 个}，发生在终局窗口开始后 1 秒）。
     * ⇒ 这份集合就是给那个补删用的"豁免名单"。
     */
    private static final java.util.Set<String> FINALE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 正在终局窗口里的 matchId（给"补删遗留"当豁免名单；只读快照）。 */
    public static java.util.Set<String> finaleMatchIds() {
        return java.util.Set.copyOf(FINALE);
    }

    /** 终局窗口里"待回收"的原因（{@code null} = 没有延迟回收在等）。 */
    private String pendingFinaleReason;

    /** 实体回收只做一次（延迟路径与普通路径共用）。 */
    private boolean finaleCleaned;

    /** 竞技场归还回调（对局停止时调用一次；{@code null} = 没占用任何竞技场）。 */
    private java.util.function.Consumer<String> arenaReleaser;

    /**
     * ★ 准备阶段已经过的 tick（每 tick +1；只在 {@link MatchPhase#PREPARING} 期间增长）。
     *
     * <p>它<b>只用于一件事</b>：喂给 {@link io.mczju.infernalclash.policy.PrepDeadline}
     * 判「到点没确认」。★ 但阶段时长口径仍只有一处 —— {@link PhaseConfig#preparingTicks()}，
     * 本字段不重复定义任何时长。
     */
    private int prepTick;

    /**
     * ★ <b>已经就「准备阶段到点」做过处置了吗</b>（幂等护栏）。
     *
     * <p>「处置」有<b>两种</b>（2026-10-16 第九轮起）：
     * <ol>
     *   <li><b>判负 / 判平</b> ⇒ {@link #finishWith(MatchOutcome)}；</li>
     *   <li>★★ <b>不判</b> —— 到点仍未确认的玩家里有人<b>没拿到过选卡机会</b>
     *       （{@link io.mczju.infernalclash.policy.PrepDeadline.Outcome#noChance()}）
     *       ⇒ 只打一条明确 WARN，本局照常进战斗（现场痛点见 {@code docs/24} §6.0）。</li>
     * </ol>
     * 两种都只做一次（否则 WARN 会每 tick 刷屏）。
     * ★ 判负之后 {@code state} 仍停在 {@code PREPARING}（判决发生在收尾<b>之前</b>），
     * 而收尾要走 MGC 的 {@code GameManager.endGame} ——它可能同步回调回来。
     * 没有这道护栏，第二次判决会在同一 tick 里再走一遍收尾路径。
     */
    private boolean prepDeadlineNotified;

    /**
     * ★★ <b>「准备阶段进入通知」发过了吗</b>（幂等护栏，2026-10-16 第九轮新增）。
     *
     * <p>它存在的理由只有一个：{@link #enterPrepPhase()} <b>必须恰好跑一次</b>
     * ——「送到出生点 ＋ 发选卡菜单物品 ＋ 自动弹一次 U4」跑两遍的后果是
     * 玩家被弹两次界面、物品被覆盖两次（无害但会刷屏），而<b>跑零遍</b>的后果
     * 就是现场那一跤（{@code docs/24} §6.0：人没进去、没被传送、没弹 U4）。
     */
    private boolean prepEntryNotified;

    /**
     * ★★ <b>本场的「打牌不扣经验」开关</b>（{@code /incl debug infinite-xp on}）。
     *
     * <h2>语义（逐字来自用户 2026-10-15 第二轮裁定，<b>不是</b>我发明的）</h2>
     * 用户原话「<b>打牌不扣经验</b>」（逐字留档 {@code docs/24} §1 第 5 条，
     * 落地说明见 {@code docs/10} §8 的「2026-10-15（第二轮裁定）」块）：
     * <ul>
     *   <li>★ 开着它时，<b>出牌这一步不再扣经验</b> —— 召唤卡与法术卡的费用校验与扣减
     *       <b>整条失效</b>（「经验不足」这个拒绝理由不再出现）；</li>
     *   <li>★ <b>经验本身照常累积</b>（时间轴 / 击杀给的照给、等级照升）——
     *       所以它<b>不是</b>「经验不清零」、也<b>不是</b>「经验固定在某个值」
     *       （那两个是当时列的选项，**均未被采纳**）；</li>
     *   <li>★★ <b>作用域 = 本场对局</b>：它是本实例的字段（按 {@code matchId} 索引），
     *       <b>不影响并发其它对局</b>（【Q42】＋【Q44】「同时支持多场」⇒
     *       绝不设「全局当前对局」那种静态状态）；</li>
     *   <li>★ 默认 {@code false}：不改任何既有行为（本项目对「开关」的一贯口径）。</li>
     * </ul>
     *
     * <p>★ 判据只有一处：{@link #chargeXp(Side, int)}（它内部走
     * {@link CardCharge#shouldCharge(boolean, boolean)} 这条<b>纯函数</b>）。
     * 出牌路径的<b>两处</b>（召唤分支与法术分支）都读它 ⇒ 「只改一处」不可能发生。
     */
    private boolean freeCasting;

    private MatchService(Plugin plugin, String matchId, PhaseConfig config, UnitRegistry units,
                         List<PlayerName> players, Consumer<String> broadcast, Runnable onFinished,
                         MatchHooks hooks) {
        this.plugin = plugin;
        this.matchId = matchId;
        this.config = config;
        this.units = units;
        this.broadcast = broadcast == null ? text -> { } : broadcast;
        this.onFinished = onFinished == null ? () -> { } : onFinished;
        this.hooks = hooks == null ? new MatchHooks() { } : hooks;
        this.logger = plugin.getLogger();
        this.xpCurve = new XpCurve(config.xpTicksPerLevel(), config.xpTicksPerLevelDouble(),
                config.doubleXpAfterTicks(), config.maxLevel());

        // ★ 阵营按**加入顺序**：第一个进房间的（房主）= 蓝，后进 = 红（2026-10-15 用户裁定）
        for (int index = 0; index < players.size(); index++) {
            PlayerName entry = players.get(index);
            Side side = Side.byJoinOrder(index);
            if (sides.containsKey(side)) {
                // 超过两人：P2 只支持 1v1，多出来的先只记日志（观战/拒绝的规则待用户裁定）
                logger.warning("[IC] matchId=" + matchId + " 的第 " + (index + 1) + " 名玩家 "
                        + entry.name() + " 没有阵营（本游戏为 1v1，多余玩家暂不参与）");
                continue;
            }
            sides.put(side, new SideState(entry.id(), entry.name()));
        }

        // ★★ 2026-10-16 第八轮：准备阶段的选卡会话。
        //   会话里的阵营 = **真人那一侧**。判据**不是**「名字像机器人」、也**不是**查实体，
        //   而是**身份比对**：人机那一方的 UUID 由
        //   TrainingBotAssembly#botIdFor(matchId, side) 从 `matchId + side` 派生
        //   （同一处派生 ⇒ 这里算出来的与装配时用的是同一个 id）。
        //   ★ 为什么不能「sides 里的都是真人」：人机是 appendBotToRoster **追加进名册**的，
        //     它同样会在 sides 里占一方（那段 javadoc 写着「晚一步 ⇒ 人机那一方不存在」）。
        //   ★ 为什么不能「查 OfflinePlayer / 在线玩家」：那是**广域查询**（铁律 10 明令避免），
        //     而且人机没有真实玩家对象。
        Set<Side> human = EnumSet.noneOf(Side.class);
        for (Map.Entry<Side, SideState> entry : sides.entrySet()) {
            UUID id = entry.getValue().player();
            if (id != null && id.equals(
                    io.mczju.infernalclash.ai.TrainingBotAssembly.botIdFor(matchId, entry.getKey()))) {
                continue;   // 人机那一侧：不进选卡会话（它的牌来自 training.bots）
            }
            human.add(entry.getKey());
        }
        this.humanSides = Collections.unmodifiableSet(human);
        this.prep = new io.mczju.infernalclash.policy.PrepSession(human);
    }

    /** 玩家身份（避免直接持有 Player 引用）。 */
    public record PlayerName(UUID id, String name) {
    }

    /**
     * 按**加入顺序**算出「阵营 → 玩家 UUID」。
     *
     * <p>规则只有一处（{@link Side#byJoinOrder}）；本方法供**目标物系统**（光束索敌与出生点传送）
     * 与构造函数共用，避免出现「运行时按一种顺序分阵营、传送按另一种」这种极难查的不一致。
     */
    public static Map<Side, UUID> sideAssignments(List<PlayerName> players) {
        Map<Side, UUID> assignments = new EnumMap<>(Side.class);
        for (int index = 0; index < players.size(); index++) {
            assignments.putIfAbsent(Side.byJoinOrder(index), players.get(index).id());
        }
        return assignments;
    }

    /**
     * 开一场对局并登记到索引。
     *
     * @param players 按**加入顺序**排列的玩家（第 0 个 = 房主 = 蓝方）
     * @param hooks   外部事实与回调（P3 起由目标物系统实现）；传 {@code null} ⇒ 退化成纯时间轴
     */
    public static MatchService start(Plugin plugin, String matchId, PhaseConfig config,
                                     UnitRegistry units, List<PlayerName> players,
                                     Consumer<String> broadcast, Runnable onFinished, MatchHooks hooks) {
        MatchService service = new MatchService(plugin, matchId, config, units, players, broadcast,
                onFinished, hooks);
        ACTIVE.put(matchId, service);
        service.logger.info("[IC] 对局开始：matchId=" + matchId + "｜" + service.describeSides()
                + "｜时间轴 " + config.preparingTicks() + "/" + config.battleTicks()
                + "/" + config.overtimeTicks() + " tick");
        return service;
    }

    /** 取某场的运行时（没有则 null）。 */
    public static MatchService get(String matchId) {
        return matchId == null ? null : ACTIVE.get(matchId);
    }

    /** 全部活跃对局（只读快照，保序）。 */
    public static Map<String, MatchService> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(ACTIVE));
    }

    /** 活跃对局数量。 */
    public static int activeCount() {
        return ACTIVE.size();
    }

    /**
     * ★ 推进**所有**活跃对局一个 tick（由插件的单个全局任务调用）。
     *
     * @return 本 tick 推进过的对局数
     */
    public static int tickAll() {
        int ticked = 0;
        for (MatchService service : new ArrayList<>(ACTIVE.values())) {
            service.tick();
            ticked++;
        }
        return ticked;
    }

    /** 清空索引（插件停用 / 测试用）。 */
    static void clearIndex() {
        ACTIVE.clear();
    }

    // ------------------------------------------------------------------ 运行时

    public String matchId() {
        return matchId;
    }

    public MatchState state() {
        return state;
    }

    /**
     * ★ 当前阶段的进度：{@code phase} + 阶段内的 tick（2026-10-17 第十二轮新增）。
     *
     * <p>为什么不是让调用方自己读 {@code state()} 再取两个字段：
     * 读 {@code state()} 会拿到一个**快照引用**，而 {@code state} 每 tick 都被换成新对象
     * ⇒ 调用方很容易把"这一刻的状态"缓存下来（然后就再也没更新过 ——
     * 那正是本轮要修的"经验条冻结"那类缺陷）。把两个值一起取出来，
     * 用的人就没法只拿一半。
     */
    public MatchPhase phase() {
        return state.phase();
    }

    /** 当前阶段内已过的 tick（见 {@link #phase()}）。 */
    public int phaseTick() {
        return state.phaseTick();
    }

    /**
     * ★★★ <b>本局的卡牌冷却表</b>（2026-10-18 新增；用户：「冷却系统完全没有实现」）。
     *
     * <p>★ 生命周期是**本类的一个字段** ⇒ 新对局 = 新 {@code MatchService} = 新表
     * ⇒ 「**每局开始清零**」（用户 2026-10-18 裁定）**结构性成立**，
     * 不需要任何 reset 调用，也就没有"忘了清零"这一格。
     *
     * <p>★ 时钟用 {@link #battleTick()}（跨进加时继续增长，见 {@code MatchState}）
     * ⇒ 冷却不会因为阶段切换被意外归零。
     */
    public io.mczju.infernalclash.policy.CardCooldowns cooldowns() {
        return cooldowns;
    }

    /**
     * ★★★ <b>本局「每方上一张成功打出的牌」</b>（2026-10-19，为「回响」而建）。
     *
     * <p>★ 它是 {@code policy/CastMemory} 的实例，**每局一个**（本类就是每局 new 一个）
     * ⇒ 新的一局天然是空的 —— 这正是用户口径「回响**不能**在对战里被当成第一张牌打出」的判据。
     * 写点只有 {@code game/CardCastListener} 的两处成功回调（召唤成功 / 法术施放成功）。
     */
    public io.mczju.infernalclash.policy.CastMemory castMemory() {
        return castMemory;
    }

    /**
     * ★★★ <b>本局「援军」的状态机</b>（1.1.0，{@code policy/ReinforcementMemory}）。
     *
     * <p>★ 与 {@link #castMemory()} 同一形状：**每局一个实例** ⇒ 新的一局两方都回到
     * 「对战开始时…记录你使用的下一张牌」的初始状态（卡面原文）。
     * 写点只有 {@code game/CardCastListener}（成功施放记下一张；打出援军时取走并重新开始）。
     */
    public io.mczju.infernalclash.policy.ReinforcementMemory reinforcementMemory() {
        return reinforcementMemory;
    }

    /** 战斗开始以来的 tick（★ 冷却时钟；见 {@link #cooldowns()}）。 */
    public int battleTick() {
        return state.battleTick();
    }

    /** 本局结果（{@code phase != ENDED} 时恒为 {@link MatchOutcome#NONE}，见 {@link MatchState}）。 */
    public MatchOutcome outcome() {
        return state.outcome();
    }

    public PhaseConfig config() {
        return config;
    }

    /** 某一方的运行时状态（没有该方则 null）。 */
    public SideState side(Side side) {
        return sides.get(side);
    }

    /** 某玩家所在阵营（不在本局则 null）。 */
    public Side sideOf(UUID player) {
        for (Map.Entry<Side, SideState> entry : sides.entrySet()) {
            if (entry.getValue().player().equals(player)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 按玩家找对局（不在任何对局里 ⇒ {@code null}）。
     *
     * <p>★ 遍历的是**活跃对局索引**（最多几场），不是世界里的实体 ——
     * 与「按世界找实体」是完全不同量级的事（铁律 10）。
     */
    public static MatchService ofPlayer(UUID player) {
        if (player == null) {
            return null;
        }
        for (MatchService service : ACTIVE.values()) {
            if (service.sideOf(player) != null) {
                return service;
            }
        }
        return null;
    }

    /** 某一方当前的经验池（没有该方 ⇒ 0）。 */
    public double xp(Side side) {
        SideState state = sides.get(side);
        return state == null ? 0.0 : state.xp();
    }

    /**
     * 从某一方的经验池里扣费（R19~R22、Q4）。
     *
     * <p>★★ <b>出牌路径不要直接调它</b> —— 调 {@link #chargeXp(Side, int)}：后者会先读本场的
     * 「打牌不扣经验」开关（{@code /incl debug infinite-xp}）。本方法刻意保持**只做扣减与判余额**
     * 这一件事（它是判据的底层原语，也是既有测试钉住的语义：**不足则一分不扣，绝不扣成负数**）。
     *
     * @return 是否扣成功（**不足则一分不扣**，返回 false —— 绝不扣成负数）
     */
    public boolean spendXp(Side side, int cost) {
        SideState state = sides.get(side);
        if (state == null || !XpPool.canAfford(state.xp, cost)) {
            return false;
        }
        state.xp = XpPool.spend(state.xp, cost);
        return true;
    }

    /** 现在能不能打牌 —— ★ 只有 BATTLE / OVERTIME（准备阶段的语义是选卡，且经验为 0）。 */
    public boolean canCast() {
        return state.phase() == MatchPhase.BATTLE || state.phase() == MatchPhase.OVERTIME;
    }

    /**
     * ★ 本场是不是「打牌不扣经验」（{@code /incl debug infinite-xp on}）。
     *
     * @see #freeCasting 字段注释（用户原话、作用域与「不是哪两个选项」都在那里）
     */
    public boolean freeCasting() {
        return freeCasting;
    }

    /**
     * ★ 设置本场的「打牌不扣经验」开关。
     *
     * <p>★ <b>作用域就是这个实例</b>（= 一个 {@code matchId}）：多场并发时各改各的。
     * 调用方是 {@code /incl debug infinite-xp on|off}（{@code InfernalClashCommand}），
     * 它只对<b>发令者当前那一场</b>调用本方法 —— 那正是【Q42】裁定的作用域。
     *
     * @return 是否<b>真的变了</b>（{@code false} = 本来就已是该值）——
     *         调用方据此诚实地说「本来就是开着/关着的」，而不是谎报一次改动
     */
    public boolean setFreeCasting(boolean enabled) {
        if (freeCasting == enabled) {
            return false;
        }
        freeCasting = enabled;
        logger.info("[IC] /incl debug infinite-xp " + (enabled ? "on" : "off")
                + "：matchId=" + matchId + " 的「打牌不扣经验」"
                + (enabled ? "已开启（出牌不再扣经验；经验照常累积）" : "已关闭（恢复正常的费用校验与扣减）"));
        return true;
    }

    /**
     * ★★ <b>一次出牌的扣费（唯一的扣费入口）</b>：把「要不要扣、扣得成扣不成」收在<b>一处</b>。
     *
     * <h2>为什么出牌路径必须调它、而不是直接调 {@link #spendXp}</h2>
     * 出牌有<b>两处</b>扣费（{@code game/CardCastListener} 的召唤分支与法术分支）。
     * 若两处各自写一遍「先看开关再扣」，那么「开关只接到了一处」这种缺陷
     * <b>编译不报错、离线测试也可能照样绿</b>（本项目「第二处白名单」的教训）。
     * ⇒ 判据（{@link CardCharge#shouldCharge}）与扣减都只在这里，两处调用同一行。
     *
     * <h2>三条可能的结果（逐条都有测试）</h2>
     * <ol>
     *   <li>{@code freeCasting == true} ⇒ <b>不扣、直接放行</b>（用户裁定「打牌不扣经验」）——
     *       ★ 这时<b>不能</b>走 {@link #refundXp}，因为<b>根本没有扣过</b>
     *       （调用方据 {@code cost() == 0} 判断要不要退还，否则会把经验凭空加上去）；</li>
     *   <li>{@code freeCasting == false} 且经验够 ⇒ 正常扣减，返回 {@code cost() == 原费用}；</li>
     *   <li>{@code freeCasting == false} 且经验不够 ⇒ <b>一分不扣</b>，返回 {@code cost() == -1}
     *       （调用方据此回「经验不足：这张卡要 X，还差 Y」）。</li>
     * </ol>
     *
     * @return 本次<b>实际扣掉</b>的经验：{@code 0} = 这回免费（开关开着）；
     *         {@code -1} = 经验不足、一分没扣（费用校验失败）；{@code > 0} = 真的扣了这么多
     */
    public int chargeXp(Side side, int cost) {
        SideState state = sides.get(side);
        if (state == null) {
            // 没有这一方 ⇒ 与 spendXp 的口径一致：扣不成（-1 = 「费用校验失败」）
            return -1;
        }
        boolean enough = XpPool.canAfford(state.xp, cost);
        if (!CardCharge.shouldCharge(freeCasting, enough)) {
            // 开关开着 ⇒ 这一次不扣费；★ 注意这里是「放行」而不是「拒绝」
            return freeCasting ? 0 : -1;
        }
        state.xp = XpPool.spend(state.xp, cost);
        return cost;
    }

    /**
     * 给某一方加经验（P6：「悦灵」这类「给召唤者加经验」的效果走它）。
     *
     * <p>★ 与 {@link #refundXp} 的区别：**退还**是把扣掉的还回去（打牌失败时），
     * **加经验**是效果产生的收益 —— 两者语义不同，所以分成两个方法。
     * 二者都受**等级上限**约束（否则「给经验」会变成刷上限的漏洞）。
     */
    public void addXp(Side side, int amount) {
        SideState state = sides.get(side);
        if (state == null || amount <= 0) {
            return;
        }
        state.xp = Math.min(config.maxLevel(), state.xp + amount);
    }

    /**
     * 退还费用（**生成失败时**调用）。
     *
     * <p>★ 退还后仍受**等级上限**约束：不退这一步的话，「生成失败」会被玩家当成刷经验的漏洞
     * （打一张必然失败的卡 → 退费 → 上限失效）。这类"看起来是帮忙、实际是漏洞"的写法要避免。
     */
    public void refundXp(Side side, int cost) {
        SideState state = sides.get(side);
        if (state == null || cost <= 0) {
            return;
        }
        state.xp = Math.min(config.maxLevel(), state.xp + cost);
    }

    /** 本局的生命周期任务登记表（P4/P5 的延时召唤、金色粒子都挂它）。 */
    public LifecycleTasks tasks() {
        return tasks;
    }

    /** 本局的实体登记表。 */
    public UnitRegistry units() {
        return units;
    }

    /**
     * ★★ 转发「骑手打到了敌人」给钩子链（{@link MatchHooks#onRiderHit}）—— 2026-10-18 新增。
     *
     * <p>由 {@code battle/SummonTraitListener} 在伤害事件里调用；真正消费它的是
     * {@code battle/CombatDirector}（前储君的坐骑速度清零）。
     */
    public void notifyRiderHit(org.bukkit.entity.Entity damager) {
        if (damager == null) {
            return;
        }
        hooks.onRiderHit(damager);
    }

    /** 推进一个 tick。 */
    public void tick() {
        if (stopped || !state.isRunning()) {
            return;
        }
        MatchPhase previous = state.phase();

        // ★ 先让钩子做本 tick 的副作用（光束伤害 / 传送 / 存活刷新），**再**取事实喂状态机。
        //   顺序反了的话，死亡那一 tick 的边沿会被自己吃掉（W1/W4 永不触发）。
        hooks.onTick(previous, state.battleTick());

        // ★★ 2026-10-16 第八轮：准备阶段的「到点没确认 ⇒ 判对面胜利」。
        //   位置是刻意的：**在状态机推进之前**、且**只在 PREPARING 期间**跑 ——
        //   于是它不会和 W1~W7 的任何一条判据抢顺序（判决走的是同一条收尾路径）。
        if (previous == MatchPhase.PREPARING) {
            prepTick++;
            checkPrepDeadline();
        }

        MatchState next = MatchPhaseMachine.advance(state, hooks.signal(), config);
        state = next;

        if (next.phase() != previous) {
            onPhaseChanged(previous, next.phase());
        }
        if (next.phase() == MatchPhase.BATTLE || next.phase() == MatchPhase.OVERTIME) {
            growXp();
        }
        if (!next.isRunning()) {
            finish();
        }
    }

    // ------------------------------------------------------------------ ★★ 准备阶段（第八轮）

    /**
     * ★★★ <b>「进入准备阶段」这件事的唯一通知点</b>（2026-10-16 第九轮新增）——
     * 它把「送到出生点 ＋ 发选卡菜单物品 ＋ 自动弹一次 U4」交给钩子，<b>恰好一次</b>。
     *
     * <h2>★★ 为什么必须有它（这就是现场那个 bug 的根因，用字节码钉着）</h2>
     * 本轮之前，这条通知<b>只</b>挂在 {@link #onPhaseChanged} 的
     * {@code to == PREPARING} 分支上，而那个分支<b>在一次对局里永远不会被执行</b>：
     * <pre>
     *   MatchState.INITIAL.phase() == MatchPhase.PREPARING
     *     ↑ javap 实测：MatchState 的 static{} 是
     *       4: getstatic MatchPhase.PREPARING / 0: new MatchState / 15: putstatic INITIAL
     *   而 MatchService.&lt;init&gt; 又是 29: getstatic MatchState.INITIAL / 32: putfield state
     *   ⇒ 对局<b>一开始就已经在 PREPARING 里</b>，从来没有过「从别的阶段进入 PREPARING」
     *     这一次转换（{@link MatchPhase} 里那个 {@code INITIAL} 只是常量名，不是阶段值）。
     *   而 MatchService.tick 只在 next.phase() != previous 时才调 onPhaseChanged
     *   （javap：94: if_acmpeq 107 / 104: invokevirtual onPhaseChanged）
     *   ⇒ 第一 tick 是 PREPARING → PREPARING，<b>不发事件</b>。
     * </pre>
     * 现场证据（{@code E:\MCpaper\logs\latest.log} 17:52:56~17:53:56）：局开起来了、
     * 目标物生成了、人机上场了，<b>但「准备阶段开始：已传送 …」与
     * 「准备阶段：已自动弹出选卡界面 …」两行一次都没出现</b>；
     * 而 60 秒后 {@code 准备阶段到点} 照常触发（因为它挂在 {@code tick()} 上、不经钩子）
     * ⇒ 用户看到的就是「人没进去却照样被判负」。
     *
     * <h2>★★ 顺序约束（后人改这里之前必须先读这一段）</h2>
     * <ol>
     *   <li>它<b>必须</b>在 {@code MatchHooks} 已经交给构造器之后调用
     *       （{@link #start} 的 {@code hooks} 参数）—— 否则钩子是空的，
     *       本方法就成了一次静默的空操作；</li>
     *   <li>它<b>必须</b>在调用方（{@code InfernalClashGame#onGameStart}）把
     *       {@code this.match = MatchService.start(...)} <b>赋值完成之后</b>调用 ——
     *       因为「自动弹 U4」那一环要从对局实例里读 {@code humanSides()} / {@code side()}
     *       （★ 第八轮那版把 {@code this.match} 当<b>实参</b>传进钩子，
     *       而那时字段还是 {@code null} ⇒ 即使事件发了也弹不出来；
     *       javap 实测：{@code onGameStart} 里 291: getfield match … 323: MatchService.start …
     *       <b>326: putfield match</b> —— 读在写之前 ⇒ 传进去的是 null）；</li>
     *   <li>它<b>必须</b>在 {@code CardLoadout#markBotSide} 之后调用 ——
     *       否则「选卡菜单」物品会发给人机那一侧（它没有 U4）。</li>
     * </ol>
     * ⇒ 因此调用方把这一次调用放在 {@code onGameStart()} 的<b>最后一步</b>，
     * 并由 {@code game/PrepPlayerLinkWiringTest} 用<b>方法级字节码</b>钉住
     * 「{@code putfield this.match} ＜ {@code markBotSide} ＜ {@code enterPrepPhase}」。
     *
     * <h2>★ 幂等</h2>
     * {@link #prepEntryNotified} 保证只发一次；{@link #onPhaseChanged} 的
     * {@code to == PREPARING} 分支也<b>调它</b>（而不是自己再写一遍）⇒
     * 「这件事只有一处实现」，将来阶段机若真的产生了那条边沿，也不会变成发两次。
     */
    public void enterPrepPhase() {
        if (prepEntryNotified) {
            return;
        }
        prepEntryNotified = true;
        logger.info("[IC] 进入准备阶段：已通知钩子（送到出生点 ＋ 发选卡菜单物品 ＋ 自动弹选卡界面）"
                + "｜matchId=" + matchId + "｜真人 " + humanSides.size() + " 方"
                + "｜准备时长 " + config.preparingTicks() + " tick"
                + "｜本通知必须恰好一次：对局一开始就在 PREPARING 里（MatchState.INITIAL），"
                + "阶段机不会产生「进入 PREPARING」的边沿（见本方法 javadoc）");
        try {
            hooks.onPrepPhaseStarted();
        } catch (Throwable t) {
            logger.warning("[IC] 钩子的「准备阶段开始」回调出错（matchId=" + matchId + "）：" + t);
        }
    }

    /** 本局的选卡会话（U4 界面与到期判负共用它）。 */
    public io.mczju.infernalclash.policy.PrepSession prep() {
        return prep;
    }

    /** 本局的**真人阵营**（需要确认卡组的那些方；人机不在里面）。 */
    public Set<Side> humanSides() {
        return humanSides;
    }

    /**
     * ★ <b>这一方要用 U4 界面吗</b>（= 它是真人吗）。
     *
     * <p>★ 人机**不用**：它的 8 张牌来自 {@code config.yml} 的 {@code training.bots}
     * （实机已证每秒出牌）⇒ 「自动弹 U4 / 发选卡菜单物品」都只对真人做。
     */
    public boolean usesDeckPicker(Side side) {
        return humanSides.contains(side);
    }

    /** 准备阶段已经过的 tick（只在 {@link MatchPhase#PREPARING} 期间增长）。 */
    public int prepTick() {
        return prepTick;
    }

    /** 这一方确认卡组了吗（人机 ⇒ {@code false}，且它本来也不需要确认）。 */
    public boolean prepConfirmed(Side side) {
        return prep.isConfirmed(side);
    }

    /**
     * ★★ <b>某一方确认了卡组</b>（U4 界面「确认卡组」按钮的落点）。
     *
     * <h2>它做什么</h2>
     * <ol>
     *   <li>把 8 张卡交给纯状态机 {@link io.mczju.infernalclash.policy.PrepSession#confirmDeck}
     *       —— 三道门（在会话里 / 没确认过 / <b>正好 8 张</b>）都在那里，本方法不重写一遍；</li>
     *   <li>成功 ⇒ 通知钩子 {@link MatchHooks#onDeckConfirmed}
     *       （{@code game/CardLoadout} 据此把 8 张放进 2~9 号格 —— 发牌实现只有那一处）；</li>
     *   <li>★★ <b>双方都就绪 ⇒ 立即结束准备阶段、进战斗</b>
     *       （用户 2026-10-16 第九轮裁定「<b>双方都确认后直接开始</b>」，
     *       {@code docs/24} §6.4）。判据 = 纯函数
     *       {@link io.mczju.infernalclash.policy.PrepSession#allConfirmed()}
     *       —— ★ 人机那侧<b>不在会话里</b>，因此它天然算「不需要确认」已就绪
     *       （「1 真人 ＋ 人机」这一格里，那位真人一确认 ⇒ 立刻开打）。
     *       ★ 切换走<b>既有</b>的 {@code onPhaseChanged(PREPARING → BATTLE)}
     *       （{@link #enterBattleEarly()}），<b>不</b>另造反抢收尾/绕开房间释放的路。</li>
     * </ol>
     *
     * @return 是否确认成功（{@code false} ⇒ 界面应提示「还差 N 张」/「已经确认过了」）
     */
    public boolean confirmDeck(Side side, List<io.mczju.infernalclash.card.Card> deck) {
        if (side == null) {
            return false;
        }
        List<String> ids = new ArrayList<>();
        if (deck != null) {
            for (io.mczju.infernalclash.card.Card card : deck) {
                ids.add(card == null ? "" : card.id());
            }
        }
        if (!prep.confirmDeck(side, ids)) {
            return false;
        }
        logger.info("[IC] 准备阶段：已确认卡组｜matchId=" + matchId + "｜" + side
                + "｜" + ids.size() + " 张｜余下未确认 " + prep.pendingCount() + " 方");
        try {
            hooks.onDeckConfirmed(side, deck == null ? List.of() : List.copyOf(deck));
        } catch (Throwable t) {
            logger.warning("[IC] 钩子的「确认卡组」回调出错（matchId=" + matchId + "）：" + t);
        }
        if (prep.allConfirmed()) {
            // ★★ 用户裁定（docs/24 §6.4）：「双方都确认后直接开始」——
            //   判据只此一处（PrepSession#allConfirmed，人机不在会话里 ⇒ 天然就绪）
            broadcast.accept("<green>双方卡组已就绪，立即进入战斗！");
            enterBattleEarly();
        }
        return true;
    }

    /**
     * ★★ <b>提前进战斗</b>（用户 2026-10-16 第九轮裁定「双方都确认后直接开始」，
     * {@code docs/24} §6.4）—— 准备阶段<b>还没走完</b>就把阶段切到 {@link MatchPhase#BATTLE}。
     *
     * <h2>★★ 为什么它<b>不是</b>一条新造的路径</h2>
     * 它做的只有两件事：① 把 {@code state} 换成<b>与计时耗尽完全相同</b>的起点
     * （{@link MatchPhaseMachine#beginBattle()} —— 两条路共用这一个工厂，见它的 javadoc）；
     * ② 调<b>同一个</b> {@link #onPhaseChanged(MatchPhase, MatchPhase)}
     * ⇒ BATTLE 分支该做的一件不少：经验清零、播报、钩子的 {@code onPhaseChanged}
     * （目标物把玩家送到出生点 / 快捷栏发「战斗」物品）。
     * ★ <b>没有</b>绕过对局收尾、没有第二个阶段切换实现、没有自己 new {@code MatchState}。
     *
     * <h2>★ 幂等护栏</h2>
     * 只有 {@code state.phase() == PREPARING} 时才动手 —— 已经在战斗 / 已收尾
     * （例如这一方确认的同时，另一条路刚好把局判掉）⇒ <b>什么都不做</b>。
     */
    private void enterBattleEarly() {
        if (state.phase() != MatchPhase.PREPARING) {
            return;
        }
        MatchPhase previous = state.phase();
        state = MatchPhaseMachine.beginBattle();
        logger.info("[IC] 准备阶段提前结束（双方卡组已就绪），进入战斗｜matchId=" + matchId
                + "｜准备阶段只走了 " + prepTick + " / " + config.preparingTicks() + " tick"
                + "｜用户裁定「双方都确认后直接开始」"
                + "｜阶段切换走既有的 onPhaseChanged(PREPARING → BATTLE)，不另造收尾路");
        onPhaseChanged(previous, MatchPhase.BATTLE);
    }

    /**
     * ★★ <b>到点了没有：谁没确认卡组 ⇒ 判他负</b>（用户裁定「自动判对面胜利」）。
     *
     * <h2>它走的是哪一条收尾路径</h2>
     * 与 W1~W7 **完全同一条**：{@code MatchPhaseMachine} 决定 {@code outcome} →
     * {@code MatchService.finish()} → {@code onFinished} → {@code InfernalClashGame.endViaManager()}
     * → {@code GameManager.endGame} → 释放房间。
     * ★ 本方法<b>不另造</b>一条绕过对局收尾的路：它只负责把「判决」交给那条既有路径。
     *
     * <h2>怎么做（为什么不直接改 {@link #state}）</h2>
     * {@code MatchState} 是<b>不可变 record</b>，而「谁赢了」的判据只该有一处
     * （{@code MatchPhaseMachine} 的 W 条）。⇒ 本轮的做法是：
     * <b>把「准备阶段超时判负」作为一条<b>显式判决</b>交给收尾路径</b>
     * —— 判决走 {@link #finishWith(MatchOutcome)}：{@code state} 被换成
     * 「{@code ENDED} + 结果」，于是收尾、记账（{@code InfernalClashGame#recordMatchResult}
     * 读的正是 {@code state.phase() == ENDED} 与 {@code state.outcome()}）、
     * 房间释放全部走**既有**那一条路。
     *
     * <h2>★★ 判负之前的<b>安全前置</b>（2026-10-16 第九轮新增；现场痛点）</h2>
     * 现场撞到过「<b>人没进去（没被传送、没弹 U4）却照样被判负</b>」
     * （{@code E:\MCpaper\logs\latest.log} 17:52:56~17:53:56；逐字留档 {@code docs/24} §6.0）。
     * 根因是「准备阶段进入通知一次都没发」⇒ 玩家<b>根本没被给过选卡机会</b>。
     * ⇒ 现在：只要到点仍未确认的玩家里有人<b>没拿到过机会</b>
     * （{@link io.mczju.infernalclash.policy.PrepDeadline.Outcome#noChance()} 非空），
     * <b>整局不判</b>，并打一条明确 WARN 说明「这个玩家没拿到选卡机会 ⇒ 不判负」。
     * ★ 这是「失败方向要安全」的体现：判据指出「准备阶段那条链没跑通」时，
     * 正确的动作是<b>不判</b>，而不是把没被服务到的一方判成输家。
     *
     * <p>★ 判决本身<b>只算一次</b>（{@link #prepDeadlineNotified} 幂等护栏，两种处置共用）。
     */
    private void checkPrepDeadline() {
        if (prepDeadlineNotified) {
            return;
        }
        io.mczju.infernalclash.policy.PrepDeadline.Outcome verdict =
                prep.verdict(prepTick, config.preparingTicks());
        if (!verdict.reached()) {
            return;   // ★ 还没到点 ⇒ 什么都不做（连日志都不打）
        }
        if (verdict.hasUnservedHumans()) {
            // ★★ 安全前置：有人**没拿到过选卡机会** ⇒ 整局不判（绝不重现「人没进去却输了」）
            prepDeadlineNotified = true;
            logger.warning("[IC] 准备阶段到点，但 "
                    + io.mczju.infernalclash.policy.PrepDeadline.describe(verdict.noChance())
                    + " 没拿到选卡机会（准备阶段进入通知没跑到他 / 选卡界面从没弹过）"
                    + "，不判负，本局照常进入战斗｜matchId=" + matchId
                    + "｜准备 " + prepTick + " / " + config.preparingTicks() + " tick"
                    + "｜失败方向安全：宁可这一局不判，也绝不重现"
                    + "「人没进去却输了」");
            return;
        }
        if (!verdict.decided()) {
            return;   // 没人欠确认（都确认过了）⇒ 对局照常进战斗
        }
        prepDeadlineNotified = true;
        logger.info("[IC] 准备阶段到点："
                + io.mczju.infernalclash.policy.PrepDeadline.describe(verdict.timedOut())
                + (verdict.outcome() == MatchOutcome.DRAW
                        ? "，双方都没确认，平局" : "，判负（对面获胜）")
                + "｜matchId=" + matchId
                + "｜准备 " + prepTick + " / " + config.preparingTicks() + " tick"
                + "｜结果 " + verdict.outcome()
                + "｜规则 = 用户裁定「1200 tick 内没选完/不选，自动判对面胜利」"
                + "＋「两方都没确认，平局」");
        broadcast.accept(verdict.outcome() == MatchOutcome.DRAW
                ? "<yellow>准备阶段结束：双方都没有确认卡组，本局平局（用户裁定，docs/24 §6.1）。"
                : "<yellow>准备阶段结束：有一方没有确认卡组，判其对手获胜。");
        // ★ 判负 / 判平都走既有收尾路径（与信标死亡那条一模一样）
        finishWith(verdict.outcome());
    }

    /**
     * ★ <b>用指定的结果收尾</b>（{@link #finish()} 的带结果版本）。
     *
     * <p>为什么不直接构造一个新的 {@link MatchState} 去替换：{@code state} 是**不可变**的，
     * 而 {@code finish()} 读的是 {@code state.outcome()}。这里用
     * {@link MatchState#ended(MatchOutcome)} 生成「已结束 + 有结果」的新状态
     * （唯一一处「就地结束」的地方），其余字段原样保留
     * —— 于是收尾、记账、房间释放全部走**既有**那一条路。
     * ★ {@code phase} 必须一起置 {@code ENDED}：否则记账那一步会把这一局当成
     * 「中止的对局」而不写战绩（理由逐条写在 {@code MatchState#ended} 的 javadoc）。
     */
    private void finishWith(MatchOutcome outcome) {
        state = state.ended(outcome);
        finish();
    }
    /**
     * ★★★ <b>投降</b>（U3 战斗菜单的「投降」按钮，{@code docs/02} §6.1 ＋ 【Q9】）——
     * 2026-10-17 第十二轮新增（缺陷 {@code docs/27} §D7）。
     *
     * <h2>规则（★ 一条都没有新造）</h2>
     * <ul>
     *   <li><b>投降方判负、对面获胜</b> —— 依据 <b>【Q28】</b>（{@code docs/05} §B）：
     *       用户对「玩家退出对局」的裁定就是「<b>判负</b>」；投降是同一件事的**主动版本**
     *       （同样是这一方的玩家不再打下去，同样由对面获胜）。★ 因此这里<b>没有</b>引入
     *       「投降 = 平局」之类的新规则（铁律 11：不许我替玩法做决定）；</li>
     *   <li><b>只在战斗 / 加时可用</b>（{@code state.isRunning()}）—— 准备阶段的
     *       玩法是「选卡」，那边已有自己的判据（没确认 ⇒ 判负 / 双方都没确认 ⇒ 平局，
     *       第九轮裁定）；在准备阶段投降会出现「两边都能一键认输」这种与文档无关的分叉；</li>
     *   <li><b>幂等</b>：已经结束的对局再投一次 ⇒ 返回 {@code false}，什么都不做
     *       （收尾路径本身也是幂等的，见 {@link #finishWith}）。</li>
     * </ul>
     *
     * <h2>★ 走的是既有收尾路径</h2>
     * {@link #finishWith(MatchOutcome)} → {@code MatchState#ended} → {@link #finish()} →
     * {@code stop()} → {@code onFinished}（= MGC 的 {@code GameManager.endGame} ⇒ 释放房间）。
     * <b>没有</b>新造绕开收尾的路 —— 与「信标被打掉」「准备阶段判负」「两方都没确认判平」
     * 完全同一条。
     *
     * @param side 投降的那一方（{@code null} ⇒ 什么也不做）
     * @return 真的执行了投降 ⇒ {@code true}（调用方据此决定要不要给玩家一句回执）
     */
    public boolean surrender(Side side) {
        if (side == null || stopped || state == null || !state.isRunning()) {
            return false;
        }
        MatchOutcome outcome = side == Side.RED ? MatchOutcome.BLUE_WINS : MatchOutcome.RED_WINS;
        SideState who = sides.get(side);
        logger.info("[IC] 投降：matchId=" + matchId + "｜" + side
                + (who == null ? "" : "（" + who.name() + "）")
                + " 投降，结果 " + outcome
                + "｜战斗 " + state.battleTick() + " tick"
                + "｜规则 = 【】「玩家退出对局，判负」的主动版本");
        broadcast.accept("<yellow>" + (who == null ? side + " 方" : who.name())
                + " 投降了 —— 本局由对面获胜。");
        finishWith(outcome);
        return true;
    }

    /** 结束收尾（幂等）：取消任务 + 清场 + 移出索引 + 回调。 */
    public void stop(String reason) {
        stop(reason, false);
    }

    /**
     * 同上，并可<b>推迟实体清场</b>（2026-10-18 终局 5 秒窗口）。
     *
     * <p>★ {@code deferCleanup = true} 时：停对局、取消定时任务、**归还竞技场**都照常，
     * 但「钩子收尾（目标物回收 + 弹射物清扫）」与「按账本删实体」**留到**
     * {@link #completeDeferredCleanup()}。⇒ 玩家能在这 5 秒里看到"定格"的战场，
     * 而不是一结算所有召唤物就凭空消失。
     *
     * <p>★ 幂等：重复调用只有第一次生效；{@code deferCleanup} 只对第一次有意义。
     */
    public void stop(String reason, boolean deferCleanup) {
        if (stopped) {
            return;
        }
        stopped = true;
        ACTIVE.remove(matchId, this);
        // ★★ 2026-10-18：把本局占的竞技场**还回池子**（先还再清场：清场失败也不该让场子永久被占）
        releaseArena();
        int cancelled = tasks.cancelAll();
        if (deferCleanup) {
            // ★ 终局 5 秒窗口：**此刻不回收任何实体**，留给 completeDeferredCleanup()
            this.pendingFinaleReason = reason;
            FINALE.add(matchId);   // ★ 豁免「补删上局遗留实体」：窗口内一个都不许删
            logger.info("[IC] 终局窗口开始（" + reason + "）：本场实体暂不回收"
                    + "（竞技场继续占着池子），等玩家侧 5 秒后再清");
            return;
        }
        int cleaned = cleanupEntities(reason);
        if (cleaned < 0) {
            return;   // 已经在 completeDeferredCleanup 里做过
        }
    }

    /** 真的做「钩子收尾 + 按账本清实体 + 归还竞技场」；返回删了几个（-1 = 已经做过了）。 */
    private int cleanupEntities(String reason) {
        if (finaleCleaned) {
            return -1;
        }
        finaleCleaned = true;
        FINALE.remove(matchId);   // ★ 窗口结束：从豁免名单里摘掉，补删逻辑恢复常态
        // ★ 先让钩子回收它自己拥有的一切（目标物等），再按账本兜底清场
        try {
            hooks.onStop(reason);
        } catch (Throwable t) {
            logger.warning("[IC] 钩子收尾出错（matchId=" + matchId + "）：" + t);
        }
        // ★★ 2026-10-17 第十轮（缺陷 P0-c）：清场返回的是**对账结果**（不是"删了几个"的裸数字）
        //   —— 「登记 11 / 删除 0」那种日志现场出现过，而它恰恰就是漏回收本身
        //   （删不掉的实体必须留在账本里等重试，见 UnitRegistry#retryPendingRemovals）。
        UnitRegistry.CleanupReport cleanup = units.cleanup(matchId, logger);
        logger.info("[IC] 对局停止（" + reason + "）：matchId=" + matchId
                + "｜本场实体 " + cleanup.describe()
                + (cleanup.fullyRemoved()
                        ? "｜本场实体已全部回收"
                        : "｜还有 " + cleanup.pending() + " 个实体留在账本里等每秒重试"
                                + "（区块未加载时删不了；绝不退化成按世界清）"));
        sides.clear();
        releaseArena();   // ★ 实体清完之后才把场子还回池子
        return 1;
    }

    /**
     * ★★★ <b>终局窗口结束：现在才真的回收</b>（2026-10-18）。
     *
     * <p>由 {@code game/InfernalClashGame#startFinale} 在 100 tick 后调用 ——
     * 那 5 秒里只关 AI、不删实体（用户原话：「等待的这五秒开始的时候不应该清掉所有场上召唤物，
     * 而是仅清除其 ai，然后等这五秒过去再清除召唤物」）。
     *
     * @return 这一次真的做了回收 ⇒ {@code true}；已经回收过 / 没走延迟路径 ⇒ {@code false}
     */
    public boolean completeDeferredCleanup() {
        if (!stopped || pendingFinaleReason == null || finaleCleaned) {
            return false;
        }
        String reason = pendingFinaleReason;
        pendingFinaleReason = null;
        return cleanupEntities(reason) >= 0;
    }

    // ------------------------------------------------------------------ 竞技场（2026-10-18）

    /**
     * ★★★ <b>把本局绑定到某一座竞技场</b>（开局时调一次）。
     *
     * @param name     竞技场名（{@code null} / 空白 ⇒ 忽略，保持"没绑定"）
     * @param arena    该竞技场的点位表（{@code null} ⇒ 忽略）
     * @param releaser 收尾时用来把场子还回池子的回调（{@code null} ⇒ 不占用任何场子）
     */
    public void bindArena(String name,
                          io.mczju.infernalclash.arena.ArenaConfig arena,
                          java.util.function.Consumer<String> releaser) {
        if (name == null || name.isBlank() || arena == null) {
            logger.warning("[IC] 绑定竞技场跳过：名字/点位表为空（matchId=" + matchId + "）"
                    + "，本局将退回全局默认竞技场");
            return;
        }
        this.arenaName = name;
        this.arena = arena;
        this.arenaReleaser = releaser;
        logger.info("[IC] 本局竞技场：" + name + "｜" + arena.describe());
    }

    /** 本局用的竞技场名（没绑定 ⇒ {@code null}）。 */
    public String arenaName() {
        return arenaName;
    }

    /**
     * 本局的点位表；<b>没绑定 ⇒ {@code null}</b>。
     *
     * <p>★ 调用方必须自己退回 {@code plugin.arenaConfig()}（那是"默认竞技场"）——
     * 本方法**不**去插件里取，因为 {@code match} 包不该依赖插件实现。
     */
    public io.mczju.infernalclash.arena.ArenaConfig arena() {
        return arena;
    }

    /** 把竞技场还回池子（幂等：还过一次之后回调被清空）。 */
    private void releaseArena() {
        java.util.function.Consumer<String> releaser = this.arenaReleaser;
        String name = this.arenaName;
        this.arenaReleaser = null;
        if (releaser == null || name == null) {
            return;
        }
        try {
            releaser.accept(name);
            logger.info("[IC] 竞技场已归还：" + name + "（matchId=" + matchId + "）");
        } catch (Throwable t) {
            logger.warning("[IC] 归还竞技场失败（" + name + "，matchId=" + matchId + "）：" + t
                    + "，它可能一直到下次重启都被当成「占用中」");
        }
    }

    // ------------------------------------------------------------------ 内部

    private void finish() {
        broadcast.accept("<yellow>对局结束：" + outcomeText(state.outcome()));
        logger.info("[IC] 对局结束：matchId=" + matchId + "｜结果 " + state.outcome()
                + "｜战斗 " + state.battleTick() + " tick");
        // ★★★ 2026-10-18（用户：「等待的这五秒开始时不应该清掉场上召唤物，仅清 AI，
        //   五秒过去再清除召唤物」）：终局 5 秒窗口内**先不删实体**。
        //   ⇒ 这里把「钩子收尾 + 按账本清实体 + 归还竞技场」推迟到 `completeDeferredCleanup()`，
        //     由 `game/InfernalClashGame#startFinale` 在 100 tick 后调用。
        stop("结束", true);
        onFinished.run();
    }

    private void onPhaseChanged(MatchPhase from, MatchPhase to) {
        logger.info("[IC] 阶段切换：matchId=" + matchId + " " + from + " → " + to);
        // ★ 先通知钩子（P3：战斗开始时传送双方到出生点），再播报
        try {
            hooks.onPhaseChanged(from, to);
        } catch (Throwable t) {
            logger.warning("[IC] 钩子的阶段回调出错（matchId=" + matchId + "）：" + t);
        }
        // ★★ 2026-10-16 第八轮新增、第九轮修：准备阶段开始 = 玩家侧链路的起点。
        //   单独一个回调（而不是让各实现方各自判 to == PREPARING）：
        //   「送到出生点 + 发选卡菜单物品 + 自动弹一次 U4」是一份**清单**，
        //   分散在三处必然漏掉一件且不报错（见 MatchHooks#onPrepPhaseStarted）。
        //   ★ 顺序：先给阶段回调（目标物等），再给这条 —— 它与前者无依赖。
        //   ★★★ 第九轮：这里**只转发**到 enterPrepPhase()（唯一实现 + 幂等护栏）。
        //     理由（现场那一跤的根因）：对局一开始就已经是 PREPARING
        //     （MatchState.INITIAL），所以**这个分支在生产路径上永远不会执行** ——
        //     真正发通知的是 {@code InfernalClashGame.onGameStart()} 最后那一次
        //     显式调用；保留这里是为了「将来阶段机真的产生这条边沿时也只发一次」。
        if (to == MatchPhase.PREPARING) {
            enterPrepPhase();
        }
        switch (to) {
            case BATTLE -> {
                // ★★★ 2026-10-18（用户第 8 条，逐字）：「**现在改为战斗开始时 5 级**」
                //   ⇒ 清零改成「置为 phases.xp-at-battle-start」（R20 的「清零」口径已被用户替换）。
                //   ★ 仍然在**进入战斗那一刻**统一赋值（不是每 tick 覆盖）—— 玩家一开局打出的费用
                //     必须真的被扣掉，所以这里只在阶段切换的那一次写。
                double startXp = Math.min(config.maxLevel(), Math.max(0.0, config.xpAtBattleStart()));
                for (SideState side : sides.values()) {
                    side.xp = startXp;
                }
                broadcast.accept("<green>战斗开始！");
                if (startXp > 0) {
                    logger.info("[IC] 战斗开始的经验已置为 " + startXp + " 级（phases.xp-at-battle-start）"
                            + "｜matchId=" + matchId
                            + "｜出处：用户 2026-10-18「战斗开始时 5 级，每 2 秒恢复 1 级，"
                            + "双倍时间每秒恢复 1 级」");
                }
            }
            case OVERTIME -> broadcast.accept(
                    "<red>时间到，双方塔数相等 —— 进入加时！加时内任意一方的塔或信标被摧毁即判负。");
            default -> {
                // PREPARING / ENDED 不需要额外动作（ENDED 由 finish() 处理）
            }
        }
    }

    private void growXp() {
        long battleTick = state.battleTick();
        for (SideState side : sides.values()) {
            side.xp = xpCurve.tick(side.xp, battleTick);
        }
    }

    private String describeSides() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<Side, SideState> entry : sides.entrySet()) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue().name());
        }
        return builder.length() == 0 ? "无玩家" : builder.toString();
    }

    private static String outcomeText(MatchOutcome outcome) {
        return switch (outcome) {
            case RED_WINS -> "红方获胜";
            case BLUE_WINS -> "蓝方获胜";
            case DRAW -> "平局";
            case NONE -> "未分胜负";
        };
    }
}
