package io.mczju.infernalclash.ai;

import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.arena.ArenaConfig;
import io.mczju.infernalclash.arena.HalfField;
import io.mczju.infernalclash.arena.Point;
import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.Facing;
import io.mczju.infernalclash.config.BotSpellRule;
import io.mczju.infernalclash.match.MatchHooks;
import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.spell.SpellCaster;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * ★★ <b>训练人机的执行层</b>：把 {@link BotPlan} 的纯决策变成<b>一次真的召唤 / 一次真的施法</b>。
 *
 * <h2>★★ 它接在「对局时钟」上（不新起定时器）</h2>
 * 本类实现 {@link MatchHooks}，由 {@code match/MatchService} 的 {@link MatchHooks#onTick} 每 tick
 * 调一次 —— 而那条链的源头是**插件那一个全局 1-tick 任务**
 * （{@code InfernalClashPlugin} 的 {@code MatchService::tickAll}）。
 * ★ 这正是「照既有形状接」（{@code battle/CombatDirector} 与 {@code objective/ObjectiveService}
 * 都是这么接的）：<b>没有</b>第二个调度器、<b>没有</b>新的全局任务，
 * 于是「对局停了任务还在跑」这类泄漏在结构上不可能发生。
 *
 * <h2>★ 铁律 10：唯一的实体生成入口是 {@code card/SummonSpawner}</h2>
 * 本类<b>不调用</b> {@code world.spawnEntity} / {@code world.spawn}，也不查任何「附近」的东西：
 * 它只把「哪张卡、哪个落点」交给既有的 {@code SummonSpawner} —— 它会在生成后
 * <b>立刻</b>把 PDC 归属（matchId / side / owner）写进账本。自己 spawn = 清场时会漏掉这批实体。
 * ★ <b>2026-10-21 起也一样适用于法术</b>：施放唯一的入口是 {@code spell/SpellCaster}；
 * 本类只负责「按规则选出落点与单体目标」，不自己造形状、不自己算伤害。
 *
 * <h2>★ 召唤物的落点只可能是「自己信标」（{@link BotPlan#decide} 的判定）</h2>
 * 落点算不出来（信标没采集 / 信标不在己方半场）时 {@code decide} 返回 {@code playable == false}
 * ⇒ 本类<b>什么都不做</b>，并且<b>告警只发一次</b>（不是每 tick 刷屏）。
 * ★ <b>绝不</b>退化成「放到玩家脚下」之类别的兜底落点。
 * ★ 法术<b>不</b>受「仅己方半场」限制（{@code docs/02} §6.4），落点由
 * {@link BotSpellTargeting} 按 {@code training.spell-rules} 里那张卡的规则算。
 *
 * <h2>★★ 手牌的来源（2026-10-21：多套人机 ＋ 「随机」占位符）</h2>
 * 本类仍然<b>只接受</b>调用方给的 {@link Supplier}（唯一装配点 =
 * {@code game/InfernalClashGame#trainingBotHand()}）——
 * 那个 Supplier 返回的是**本局抽中的那个人机**、且「随机」已经在开局时解析掉的 8 张。
 *
 * <h2>★ 出牌节拍（{@link #CAST_INTERVAL_TICKS}）</h2>
 * ★ 2026-10-15 用户裁定 <b>G11 = 「1 秒」</b> ⇒ 20 tick（见该常量的 javadoc）。
 *
 * <h2>★★ 「等 5 秒没目标就跳过」（2026-10-21 用户裁定）</h2>
 * 轮到指针上那张法术卡、但算不出合法目标时，本类按<b>出牌节拍</b>计次：
 * 连续 {@link #SPELL_TARGET_TIMEOUT_TICKS} tick（= {@value #SPELL_TARGET_TIMEOUT_ATTEMPTS}
 * 次尝试 = 5 秒）都算不出合法目标 ⇒ <b>跳过这一张</b>（指针前进）并留一行日志。
 * ★ 这是「指针只在真的打出去之后才前进」的<b>唯一例外</b>，理由写在
 * {@link #SPELL_TARGET_TIMEOUT_TICKS} 的 javadoc 里。
 *
 * <h2>★ 冷却：**不读**</h2>
 * 用户 2026-10-21 裁定「<b>不遵守，但是人机需要把它的 8 张卡打完一轮才能打出同一张卡两次</b>」
 * ⇒ 本类<b>不</b>调 {@code match.cooldowns()}；「不过强」由 8 张轮流保证
 * （同一张卡两次成功出牌之间至少隔着另外 7 次）。
 *
 * <h2>★ 为什么本类不是 {@code final}</h2>
 * 接缝方法（{@code cardOf} / {@code locationOf} / {@code summon} / {@code spellTargets} /
 * {@code castSpell}）在离线单测里<b>必须</b>被替换：
 * 造一张真卡要走 {@code CardCatalog}、算落点要 {@code Bukkit.getWorld(...)}、生成实体要真服务端
 * —— 这三样在普通 JUnit 里都拿不到。{@code TrainingBotDriverTest} 用一个同包子类把它们换掉
 * （{@code final} 类无法被继承 ⇒ 编译期就会红，被第一次真跑抓到）。
 * ⇒ 代价明确：**生产路径只有本类一个实现**（没有第二个子类），这条用注释钉住而不是用 {@code final} 钉住。
 */
public class TrainingBotDriver implements MatchHooks {

    /**
     * ★ <b>两次出牌尝试之间的间隔</b>（tick）：<b>20 tick = 1 秒</b>。
     *
     * <p>★★ <b>2026-10-15 用户已裁定 = 「1 秒」</b>（缺口 <b>G11</b> 的数值部分，
     * {@code docs/24} §5.4 第 2 条）⇒ 这个值<b>不再是「我拟的可调占位」</b>，
     * 而是<b>用户给定的玩法值</b>：1 秒 = 20 tick。
     *
     * <p>★ 因此后人<b>不要</b>「顺手调一调」：改动它等于改用户裁定的玩法数值
     * （铁律 8：数值由用户定）。真要改，先让用户重新裁定，再同时改
     * {@code TrainingBotDriverTest} 里那条钉住 {@code == 20} 的断言
     * （它就是为了让这次改动必须被显式看见而存在的）。
     *
     * <p>★ 与 {@code docs/02} §10.3「目标刷新 10 tick = 0.5 秒」同一量级，
     * 但两者是**独立**的数值（一个是索敌节奏，一个是人机出牌节奏），不要互相推导。
     */
    public static final int CAST_INTERVAL_TICKS = 20;

    /**
     * ★★ <b>「法术一直没有合法目标 ⇒ 最多等多久就跳过这一张」</b>（tick）。
     *
     * <h2>出处（用户原话，逐字）</h2>
     * 我问「轮到的法术卡找不到合法目标怎么办」，用户答：「**3，等5秒没目标就跳过**」。
     *
     * <h2>为什么需要这条上限（不这么做会怎样）</h2>
     * 2026-10-16 用户已经裁定「付不起 ⇒ <b>等这一张</b>、指针不动」，而
     * 「指针只在真的打出去之后才前进」是 8 张轮流的基础。
     * 但法术的「没有目标」与「付不起」不同：<b>付不起迟早会付得起</b>，而
     * 「治疗法术没有友方召唤物」可能<b>永远</b>成立（召唤物全死了、后面的牌就都轮不到）
     * ⇒ 人机会整局卡死在那一格，玩家看到的是「人机突然不动了」。
     * ⇒ 用户给了这条上限：<b>等 5 秒，仍没目标就跳过</b>。
     *
     * <p>★ 计次口径：本类只在**出牌节拍**（每 {@link #CAST_INTERVAL_TICKS} tick 一次）判断，
     * 所以 5 秒 = {@value #SPELL_TARGET_TIMEOUT_ATTEMPTS} 次尝试。
     * 中途一旦有了目标并施放成功、或者指针换了、或者本局收尾 ⇒ 计数清零。
     */
    public static final int SPELL_TARGET_TIMEOUT_TICKS = 100;

    /** 见 {@link #SPELL_TARGET_TIMEOUT_TICKS}：5 秒 ÷ 1 秒 = 5 次尝试。 */
    public static final int SPELL_TARGET_TIMEOUT_ATTEMPTS =
            SPELL_TARGET_TIMEOUT_TICKS / CAST_INTERVAL_TICKS;

    private final Plugin plugin;
    private final MatchService match;
    private final Side side;
    private final ArenaConfig arena;
    private final Logger logger;

    /**
     * ★★ <b>人机这一手牌的来源</b>（2026-10-21：本局抽中的那个人机，且「随机」已解析）。
     *
     * <p>★ 为什么是<b>可替换</b>（{@code volatile} + 包级 setter）而不是 {@code final}：
     * 调用方在<b>构造本对象的那一刻还不知道</b>要发什么牌（真牌组要等对局开始后的
     * 卡组装载 / 调试发牌），而「同一个对象、后补手牌」比「先造一个 half-ready 的驱动」
     * 更不容易出错。★ 真实路径上它由 {@code game/InfernalClashGame} 注入。
     */
    private volatile Supplier<List<BotPlan.HandCard>> hand;

    /**
     * ★★ <b>随机源</b>（2026-10-21）：法术选点要用它。
     *
     * <p>★ 生产侧 = 一个新实例；离线测试通过 {@link #setRandom(Random)} 传定种子
     * ⇒「同种子 ⇒ 同结果」（本项目对随机的一贯要求）。
     */
    private Random random = new Random();

    /** 本驱动尝试出牌的 tick 计数（只用来算节拍）。 */
    private int tickCounter;

    /**
     * ★★ <b>轮流出牌的指针</b>（2026-10-16 第八轮裁定：「人机打牌不会只打一张，而是 1-8 张轮流打」
     * ＋ 纠正「它付不起不应该跳过，而是等待直到这张牌可以打出，否则人机永远不会打出某些高费牌」；
     * 逐字留档 {@code docs/24} §5.9）。
     *
     * <p>它是 {@code BotPlan} 排好的那份<b>有序手牌</b>（槽位 2~9 升序）里的下标 ——
     * ★ 只在 {@code castOnce()} <b>真的打出</b>之后前进一格（{@link BotPlan#nextPointerOf}），
     * 末尾回绕到第 1 张；付不起 / 生成失败 ⇒ <b>原地不动</b>（等它）。
     * ★ <b>唯一例外</b>：法术连续 {@link #SPELL_TARGET_TIMEOUT_ATTEMPTS} 次没有合法目标 ⇒
     * 跳过这一张（指针前进），见那条常量的 javadoc。
     */
    private int handPointer;

    /** 已经在出牌路径上记过日志的召唤／施法数量（让「打出 N 张」可对账）。 */
    private int played;

    /** 「这一次不出牌」的原因（上一 tick 算出来的）—— 只在它<b>变化</b>时打一行日志，避免刷屏。 */
    private String lastIdleReason;

    /** 落点算不出来时只告警一次（否则每 20 tick 刷一条，把真告警淹掉）。 */
    private boolean placementWarned;

    /** ★ 建筑落点回落成信标时只告警一次（{@code docs/32} §5）。 */
    private boolean buildingFallbackWarned;

    /** ★ 本方两座塔的当前血量来源（左、右；装配期注入；{@code null} = 读不到）。 */
    private volatile Supplier<double[]> towerHealthSource;

    /** 法术落点/世界算不出来时只告警一次。 */
    private boolean spellOriginWarned;

    /** ★ 法术「连续几次算不出合法目标」（到 {@link #SPELL_TARGET_TIMEOUT_ATTEMPTS} 就跳过这一张）。 */
    private int noTargetAttempts;

    public TrainingBotDriver(Plugin plugin, MatchService match, Side side, ArenaConfig arena,
                             Supplier<List<BotPlan.HandCard>> hand) {
        if (plugin == null || match == null || side == null) {
            throw new IllegalArgumentException("plugin / match / side 都不能为 null"
                    + "（执行层至少要能记账、能写日志、知道自己是哪一方）");
        }
        this.plugin = plugin;
        this.match = match;
        this.side = side;
        this.arena = arena;
        this.hand = hand;
        this.logger = plugin.getLogger();
    }

    /**
     * ★ 公开版的「换一手牌」（2026-10-21 起来源 = 本局抽中的那个人机的卡组）。
     *
     * <p>装配点在真实路径 = {@code game/InfernalClashGame}（注入 {@code trainingBotHand()}）；
     * 另一个是离线测试。
     */
    public void setHand(Supplier<List<BotPlan.HandCard>> source) {
        this.hand = source;
    }

    /** ★ 换随机源（离线测试传定种子；生产侧不用调）。 */
    public void setRandom(Random source) {
        this.random = source == null ? new Random() : source;
    }

    /**
     * ★★ <b>注入「本方两座塔的当前血量」来源</b>（2026-10-21 追加，{@code docs/32}）。
     *
     * <p>装配点：{@code game/InfernalClashGame#createTrainingBotDriver} 从**本局目标物系统**
     * （{@code objective/ObjectiveService#healthOf(side, TOWER)}）取，顺序 = 左、右。
     * ★ 传 {@code null} ⇒ 读不到 ⇒ 按「相等」⇒ **右侧**（用户口径），不抛、不卡死。
     */
    public void setTowerHealthSource(Supplier<double[]> source) {
        this.towerHealthSource = source;
    }

    // ==================================================================================
    // 时钟：每 tick 由 MatchService 调（★ 不新起任务）
    // ==================================================================================

    @Override
    public void onTick(MatchPhase phase, int battleTick) {
        // ★ 只有战斗期才出牌（与 battle/CombatDirector 同口径：准备阶段是选卡，不该开始打架）
        if (phase != MatchPhase.BATTLE && phase != MatchPhase.OVERTIME) {
            return;
        }
        if ((++tickCounter % CAST_INTERVAL_TICKS) != 0) {
            return;
        }
        castOnce();
    }

    @Override
    public void onStop(String reason) {
        if (played > 0) {
            logger.info("[IC] 训练人机收尾（" + reason + "）：matchId=" + match.matchId()
                    + " 本方（" + side + "）本局共打出 " + played + " 张牌"
                    + "｜8 张轮流（指针停在有序手牌的第 " + (handPointer + 1) + " 张）"
                    + "—— 用户 2026-10-16 裁定");
        }
        tickCounter = 0;
        lastIdleReason = null;
        placementWarned = false;
        buildingFallbackWarned = false;
        spellOriginWarned = false;
        noTargetAttempts = 0;
        handPointer = 0;
    }

    /**
     * ★ <b>尝试打一张牌</b>（一次完整的「决策 → 选点/落点 → 扣费 → 生成/施放 → 记账」）。
     *
     * <p>包级可见（不是 {@code private}）：离线测试要能直接调它一次，
     * 而不必伪造「20 tick 的时钟」（{@code ai/TrainingBotDriverTest}）。
     *
     * @return 这一次<b>真的打出去</b>的卡牌 id；没出牌 / 失败 ⇒ {@code null}
     */
    String castOnce() {
        List<BotPlan.HandCard> cards = hand == null ? null : hand.get();
        if (cards == null || cards.isEmpty()) {
            // 手牌为空是「本局无人机」/「配置读不到卡」的兜底：什么都不做，也不刷日志
            return null;
        }
        Point beacon = arena == null ? null : arena.beacon(side);
        HalfField ownHalf = arena == null ? null : arena.half(side);
        // ★★ 指针在这里交给纯策略（它只判「轮到的那一张能不能打」），
        //    进度只在本方法**真的打出**之后才推进（见下面的 handPointer 赋值）
        BotPlan.Decision decision =
                BotPlan.decide(cards, match.xp(side), beacon, ownHalf, handPointer);

        if (!decision.playable()) {
            // ★ 「这一次不出牌」是正常情况（**付不起 ⇒ 等这一张** / 信标没采集）⇒
            //   只在原因**变了**的时候打一行，别把控制台刷满。
            //   ★ 「付不起就等」是用户 2026-10-16 的明确纠正（不是"跳过"，见 BotPlan#rotatePick）
            noTargetAttempts = 0;
            if (!decision.reason().equals(lastIdleReason)) {
                lastIdleReason = decision.reason();
                logger.info("[IC] 训练人机（" + side + "，matchId=" + match.matchId()
                        + "）暂不出牌：" + decision.reason());
            }
            return null;
        }
        lastIdleReason = null;

        Card card = cardOf(decision.cardId());
        if (card == null) {
            logger.warning("[IC] 训练人机拿到的卡 " + decision.cardId()
                    + " 不在卡池里，跳过（matchId=" + match.matchId() + "）"
                    + "｜指针不动（下一次仍试这一张）—— 免得这一张被静默跳过、"
                    + "而 8 张轮流的性质被破坏；若这一条反复出现，请核对 config.yml 的 training.bots");
            return null;
        }
        // ★★ 2026-10-21：法术卡也打 —— 两条分支的判据来自决策本身（不是卡对象）
        if (decision.spell()) {
            return castSpellOnce(decision, card, cards.size(), beacon);
        }
        return summonOnce(decision, card, cards.size());
    }

    // ------------------------------------------------------------------ 召唤分支

    private String summonOnce(BotPlan.Decision decision, Card card, int handSize) {
        if (!card.isSummon()) {
            logger.warning("[IC] 训练人机拿到的卡 " + decision.cardId()
                    + " 不是召唤卡，但决策说是召唤（夹具/卡池不一致），跳过"
                    + "（matchId=" + match.matchId() + "）｜指针不动");
            return null;
        }
        HalfField ownHalf = arena == null ? null : arena.half(side);
        // ★★ 2026-10-21 追加（docs/32）：建筑类召唤牌先按 training.building-placements
        //   算「放塔的哪一侧、前后几格」；算不出来 / 越出己方半场 ⇒ 回落成普通规则（自己信标）。
        SummonSpot spot = summonSpot(decision, ownHalf);
        Location center = locationOf(spot.point(), ownHalf);
        if (center == null) {
            if (!placementWarned) {
                placementWarned = true;
                logger.warning("[IC] 训练人机落点不可用（信标所在世界 "
                        + (spot.point() == null ? "?" : spot.point().world())
                        + " 未加载？），本局不再尝试（matchId=" + match.matchId() + "）"
                        + "｜指针不动（这不是「付不起」，是落点坏了；保留指针便于排障）");
            }
            return null;
        }

        // ★ 唯一的生成入口：走既有的 SummonSpawner（它会在生成后立刻登记 PDC 归属）
        UUID owner = ownerOf();
        int spawned = summon(card, center, spot.facing(), match.matchId(), owner);
        if (spawned <= 0) {
            logger.warning("[IC] 训练人机召唤失败（卡 " + card.id() + "，matchId="
                    + match.matchId() + "），本次不计入（见上面的召唤告警）"
                    + "｜指针不动（下一次仍试这一张）");
            return null;
        }

        // ★ 费用走与玩家**同一条**判据（MatchService.chargeXp ⇒ match/CardCharge）：
        //   正常局扣经验、infinite-xp 那一路分文不取；
        //   ★ 关掉开关且经验不够时返回 -1（此时不该走到这里 —— decide 已经判过 affordability，
        //     但它与 chargeXp 之间隔着一次生成 ⇒ 这里如实报出来，绝不静默）
        int paid = match.chargeXp(side, card.cost());
        if (paid < 0) {
            logger.warning("[IC] 训练人机打出 " + card.id() + " 但经验不够扣（当前 "
                    + String.format("%.2f", match.xp(side)) + " 费 " + card.cost()
                    + "），本次未扣费（余量不会变成负数）");
        }
        played++;
        noTargetAttempts = 0;

        // ★★ 指针**只在这里前进一格**（打完这一张才轮到下一张；到末尾回绕到第 1 张）
        //    —— 这就是「8 张轮流」的全部实现（判据在纯函数 BotPlan#nextPointerOf）
        int advanced = BotPlan.nextPointerOf(handPointer, handSize);
        int playedOrder = decision.order() >= 0 ? decision.order() : handPointer;
        logger.info("[IC] 训练人机（" + side + "）打出 " + card.id() + "，生成 " + spawned
                + " 个召唤物 @" + center.getBlockX() + "," + center.getBlockY() + ","
                + center.getBlockZ() + "｜" + spot.note() + "｜余量 "
                + String.format("%.2f", match.xp(side))
                + (paid == 0 ? "｜本次不扣经验（infinite-xp / 免费）" : "｜扣 " + paid)
                + "｜轮流出牌：第 " + (playedOrder + 1) + " / " + handSize + " 张，下一张是第 "
                + (advanced + 1) + " 张");
        handPointer = advanced;
        return card.id();
    }

    // ------------------------------------------------------------------ 建筑落点（2026-10-21 追加）

    /**
     * ★ <b>这一次召唤的落点 ＋ 朝向 ＋ 一句话说明</b>（{@code docs/32}）。
     *
     * @param point  落点格
     * @param facing 召唤物朝向（塔侧规则 ⇒ **前向**；普通规则 ⇒ 信标 yaw，与以前一致）
     * @param note   写进日志的短语
     */
    private record SummonSpot(Point point, Facing facing, String note) {
    }

    /**
     * ★★★ <b>这一次召唤到底放在哪一格</b>（{@code docs/32}）。
     *
     * <h2>口径</h2>
     * <ul>
     *   <li>这张卡**没配** {@code training.building-placements} ⇒ 原样返回信标（普通规则）；</li>
     *   <li>配了 ⇒ 交给纯函数 {@link BotBuildingPlacer#place}：
     *       选血量低（{@code BACK}/{@code CENTER}）或高（{@code FRONT}）那座塔，
     *       再沿「前向」偏移 −4 / +4 / +10 格；</li>
     *   <li>★ 算不出来（塔点位缺失 / 血量来源不可用 / **越出己方半场**）⇒
     *       **回落成信标** + 告警一次（用户裁定：「退化成普通规则」）。</li>
     * </ul>
     *
     * <p>★ 失败方向刻意是「回落」而不是「拒绝」：拒绝会让这张牌永久打不出去（人机卡死），
     * 而回落既守住 Q23（信标必然在己方半场）又不会让人机停摆。
     */
    private SummonSpot summonSpot(BotPlan.Decision decision, HalfField ownHalf) {
        Point beacon = decision.beacon();
        Facing beaconFacing = Facing.fromYaw(beacon == null ? 0.0f : beacon.yaw());
        io.mczju.infernalclash.config.BuildingPlacementRule rule = decision.buildingRule();
        if (rule == null || arena == null) {
            return new SummonSpot(beacon, beaconFacing, "落点=自己信标");
        }
        Point towerLeft = arena.tower(side, true);
        Point towerRight = arena.tower(side, false);
        Facing front = buildingFront();
        Optional<Point> placed = BotBuildingPlacer.place(rule, towerLeft, towerRight,
                towerHealth(), front, ownHalf);
        if (placed.isEmpty()) {
            if (!buildingFallbackWarned) {
                buildingFallbackWarned = true;
                logger.warning("[IC] 训练人机的建筑落点规则 " + rule + " 算不出合法落点"
                        + "（塔点位 左=" + (towerLeft == null ? "缺" : "有")
                        + " / 右=" + (towerRight == null ? "缺" : "有")
                        + "，前向 " + front + "，半场 "
                        + (ownHalf == null ? "未采集" : ownHalf.describe())
                        + "）⇒ 本局这一档**回落成普通规则**（放自己信标）"
                        + "（用户 2026-10-21 裁定；docs/32 §5）｜matchId=" + match.matchId());
            }
            return new SummonSpot(beacon, beaconFacing, "落点=自己信标（建筑规则回落）");
        }
        return new SummonSpot(placed.get(), front,
                "落点=塔侧规则 " + rule + "（前向 " + front + "）");
    }

    /** ★ 「前向」方位（{@code training.building-front.<side>}；读不到 ⇒ 出厂默认）。 */
    Facing buildingFront() {
        try {
            if (plugin instanceof InfernalClashPlugin ic && ic.trainingConfig() != null) {
                String raw = ic.trainingConfig().buildingFrontOf(side);
                return Facing.valueOf(raw);
            }
        } catch (RuntimeException e) {
            logger.warning("[IC] 训练人机读「前向」失败（" + e + "），本次按出厂默认");
        }
        return Facing.valueOf(io.mczju.infernalclash.config.TrainingConfig.defaultFrontOf(side));
    }

    /**
     * ★ 本方两座塔的**当前血量**（顺序 = 左、右；{@code null} = 读不到）。
     *
     * <p>来源由装配期注入（{@code game/InfernalClashGame} 从目标物系统取）。
     * ★ 读不到 ⇒ {@code null} ⇒ {@link BotBuildingPlacer#towerIndex} 当作「相等」⇒
     * **右侧**（与用户「相等时默认放在右侧」同一口径），不会抛、不会卡死。
     */
    double[] towerHealth() {
        Supplier<double[]> source = towerHealthSource;
        if (source == null) {
            return null;
        }
        try {
            return source.get();
        } catch (RuntimeException e) {
            logger.warning("[IC] 训练人机读塔血量失败（" + e + "），本次按「相等」处理（⇒ 右侧）");
            return null;
        }
    }

    // ------------------------------------------------------------------ 法术分支（2026-10-21 新增）

    /**
     * ★★ <b>放一次法术</b>：按这张卡的规则选出落点 / 单体目标 → 交给
     * {@code spell/SpellCaster} → 扣费 → 记账 → 指针前进。
     *
     * <h2>失败方向（逐条）</h2>
     * <ul>
     *   <li><b>算不出合法目标</b> ⇒ 计次；到 {@link #SPELL_TARGET_TIMEOUT_ATTEMPTS} 次
     *       （= 5 秒）<b>跳过这一张</b>（指针前进）并留一行日志。★ 这是唯一会"不打出也前进"的情形；</li>
     *   <li><b>落点世界算不出来</b>（信标没采集 / 世界未加载）⇒ 告警一次、指针不动；</li>
     *   <li><b>施放被拒</b>（{@link SpellCaster.Report#cast()} 为 false，例如形状参数非法）
     *       ⇒ 告警、指针不动（这不是"没目标"，重试是合理的）；</li>
     *   <li><b>法术卡没配规则</b>（装载期本应已拒绝启动）⇒ 告警、指针不动。</li>
     * </ul>
     */
    private String castSpellOnce(BotPlan.Decision decision, Card card, int handSize, Point beacon) {
        BotSpellRule rule = decision.rule();
        if (card.spell() == null || rule == null) {
            logger.warning("[IC] 训练人机拿到的卡 " + decision.cardId()
                    + " 不是法术卡 / 没有配规则，但决策说是法术，跳过（matchId="
                    + match.matchId() + "）｜指针不动（装载期本应已拒绝启动）");
            return null;
        }
        String world = beacon == null ? null : beacon.world();
        BotSpellTargeting.Spot beaconSpot = beacon == null ? null
                : new BotSpellTargeting.Spot(beacon.world(), beacon.cellX(), beacon.y(),
                        beacon.cellZ(), beacon.yaw());
        List<BotSpellTargeting.UnitView> units = spellTargets(match.matchId());

        Optional<BotSpellTargeting.Resolution> resolved =
                BotSpellTargeting.resolve(rule, units, side, beaconSpot, world, random);
        if (resolved.isEmpty()) {
            noTargetAttempts++;
            String reason = "暂无合法目标（规则 " + rule + "）";
            if (noTargetAttempts >= SPELL_TARGET_TIMEOUT_ATTEMPTS) {
                int advanced = BotPlan.nextPointerOf(handPointer, handSize);
                logger.info("[IC] 训练人机（" + side + "）跳过第 " + (handPointer + 1) + " / "
                        + handSize + " 张 " + card.id() + "：连续 " + noTargetAttempts
                        + " 次（= " + SPELL_TARGET_TIMEOUT_TICKS + " tick = 5 秒）" + reason
                        + "｜指针前进到第 " + (advanced + 1) + " 张"
                        + "（用户 2026-10-21 裁定「等5秒没目标就跳过」）");
                noTargetAttempts = 0;
                handPointer = advanced;
                return null;
            }
            if (!reason.equals(lastIdleReason)) {
                lastIdleReason = reason;
                logger.info("[IC] 训练人机（" + side + "）本次不施放 " + card.id() + "：" + reason
                        + "｜等 " + SPELL_TARGET_TIMEOUT_ATTEMPTS + " 次（5 秒）仍没有就跳过这一张"
                        + "（已等 " + noTargetAttempts + " 次）");
            }
            return null;
        }
        noTargetAttempts = 0;

        BotSpellTargeting.Spot spot = resolved.get().origin();
        Location origin = locationOf(spot);
        if (origin == null) {
            if (!spellOriginWarned) {
                spellOriginWarned = true;
                logger.warning("[IC] 训练人机法术落点的世界 " + spot.world()
                        + " 不可用（未加载？），本局不再尝试（matchId=" + match.matchId()
                        + "）｜指针不动（这不是「没目标」，是落点坏了）");
            }
            return null;
        }

        SpellCastResult result = castSpell(card, origin, resolved.get().target(), match.matchId());
        if (!result.cast()) {
            logger.warning("[IC] 训练人机施放 " + card.id() + " 被拒（" + result.note()
                    + "），本次不计入｜指针不动（matchId=" + match.matchId() + "）");
            return null;
        }

        int paid = match.chargeXp(side, card.cost());
        if (paid < 0) {
            logger.warning("[IC] 训练人机施放 " + card.id() + " 但经验不够扣（当前 "
                    + String.format("%.2f", match.xp(side)) + " 费 " + card.cost()
                    + "），本次未扣费（余量不会变成负数）");
        }
        played++;

        int advanced = BotPlan.nextPointerOf(handPointer, handSize);
        int playedOrder = decision.order() >= 0 ? decision.order() : handPointer;
        logger.info("[IC] 训练人机（" + side + "）施放 " + card.id() + "，规则 " + rule
                + (resolved.get().hasSingleTarget()
                        ? "，单体目标 = " + resolved.get().target() : "，落点 = 该单位所在方块")
                + " @" + origin.getBlockX() + "," + origin.getBlockY() + "," + origin.getBlockZ()
                + "｜覆盖 " + result.cells() + " 格 / 命中 " + result.targets() + " 个目标 / 应用 "
                + result.applied() + " 次效果"
                + (result.note().isEmpty() ? "" : "｜" + result.note())
                + "｜余量 " + String.format("%.2f", match.xp(side))
                + (paid == 0 ? "｜本次不扣经验（infinite-xp / 免费）" : "｜扣 " + paid)
                + "｜轮流出牌：第 " + (playedOrder + 1) + " / " + handSize + " 张，下一张是第 "
                + (advanced + 1) + " 张"
                + "｜冷却：不读（用户 2026-10-21 裁定）");
        handPointer = advanced;
        return card.id();
    }

    /**
     * ★ <b>一次施法的结果</b>（只为了能在离线测试里替换掉 {@link SpellCaster}）。
     *
     * @param cast    施放是否被接受
     * @param cells   覆盖的格数
     * @param targets 命中的目标数
     * @param applied 应用的效果次数
     * @param note    报告附言（可为空串）
     */
    public record SpellCastResult(boolean cast, int cells, int targets, int applied, String note) {

        public SpellCastResult {
            note = note == null ? "" : note;
        }

        public static SpellCastResult refused(String note) {
            return new SpellCastResult(false, 0, 0, 0, note);
        }
    }

    /** 本局本方向已打出的张数（探针 / 测试用）。 */
    public int played() {
        return played;
    }

    /** ★ 轮到有序手牌里的第几张（0 起；探针 / 测试用）—— 「8 张轮流」的可观测状态。 */
    public int handPointer() {
        return handPointer;
    }

    /** ★ 「连续几次算不出合法目标」（探针 / 测试用）。 */
    public int noTargetAttempts() {
        return noTargetAttempts;
    }

    // ==================================================================================
    // 可替换的接缝（默认走真实实现；离线测试可以替换它们）
    // ==================================================================================

    /**
     * ★ 卡池查表：把决策给出的 id 换成真的 {@link Card}
     * （唯一事实来源 = 插件持有的 {@code card/CardCatalog}；本类<b>不写死任何卡名</b>）。
     *
     * <p>包级可见（不是 {@code private}）：离线测试用它塞一张手写的卡，不必起服务端。
     */
    Card cardOf(String cardId) {
        if (cardId == null || !(plugin instanceof InfernalClashPlugin ic)) {
            return null;
        }
        try {
            io.mczju.infernalclash.card.CardCatalog catalog = ic.cardCatalog();
            return catalog == null ? null : catalog.cards().get(cardId);
        } catch (RuntimeException e) {
            logger.warning("[IC] 训练人机取卡池失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * ★ 召唤落点：信标点位 → Bukkit {@link Location}（格中心）。
     *
     * <p>★ 世界未加载 ⇒ {@code null}（调用方告警并跳过）—— <b>绝不</b>换一个世界。
     *
     * <h2>★ 为什么这里的 {@code Bukkit.getWorld(名字)} 不违反铁律 10</h2>
     * 它<b>不是</b>「插件自己挑一个世界」（那正是守卫禁止的 {@code Bukkit.getWorlds()}）：
     * 世界名来自 {@code arenas.yml} 里<b>管理员显式采集</b>的信标点位
     * （{@code Plugin → ArenaConfig.beacon(side)}，落点判定的同一份数据），
     * 而且查不到就返回 {@code null} ⇒ <b>fail-safe 跳过并告警</b>，
     * 绝不会退化成「主世界的出生点」之类猜出来的落点。
     */
    Location locationOf(Point beacon, HalfField ownHalf) {
        if (beacon == null || beacon.world() == null) {
            return null;
        }
        World world = Bukkit.getWorld(beacon.world());
        if (world == null) {
            return null;
        }
        // ★ 落点必须真的在己方半场里（Q23 / R28）：decide 已经判过一次，这里是第二道锁
        //   （与 policy 层「两道锁」的做法一致：判据 + 落地前再确认一次）
        if (ownHalf != null && !ownHalf.contains(beacon.cellX(), beacon.cellZ())) {
            return null;
        }
        return new Location(world, beacon.cellX() + 0.5, beacon.y(), beacon.cellZ() + 0.5,
                beacon.yaw(), beacon.pitch());
    }

    /**
     * ★ 法术落点（{@link BotSpellTargeting.Spot} → Bukkit {@link Location}）。
     *
     * <p>★ 世界未加载 ⇒ {@code null}（调用方告警一次并放弃这一次）—— <b>绝不</b>换一个世界。
     */
    Location locationOf(BotSpellTargeting.Spot spot) {
        if (spot == null || spot.world() == null) {
            return null;
        }
        World world = Bukkit.getWorld(spot.world());
        if (world == null) {
            return null;
        }
        return new Location(world, spot.x() + 0.5, spot.y(), spot.z() + 0.5, spot.yaw(), 0.0f);
    }

    /** ★ 唯一的实体生成入口（默认走既有的 {@code SummonSpawner}；离线测试可覆盖它来计数）。 */
    int summon(Card card, Location center, Facing facing, String matchId, UUID owner) {
        if (!(plugin instanceof InfernalClashPlugin ic)) {
            logger.warning("[IC] 训练人机无法召唤：插件实例不是 InfernalClashPlugin（无法取 SummonSpawner）");
            return 0;
        }
        io.mczju.infernalclash.card.SummonSpawner spawner = ic.summonSpawner();
        if (spawner == null) {
            logger.warning("[IC] 训练人机无法召唤：SummonSpawner 未就绪（插件未 enable？）");
            return 0;
        }
        // ★ 2026-10-21 追加：朝向由调用方给（塔侧规则 ⇒ 前向；普通规则 ⇒ 信标 yaw）
        Facing actual = facing == null ? Facing.SOUTH : facing;
        // ★ 2026-10-18：带上**本局任务表**，人机召出的 `lifetime-ticks` 单位才会自灭
        return spawner.spawn(card, center, actual, matchId, side, owner, match.tasks());
    }

    /**
     * ★★ <b>场上单位快照</b>（法术选点的候选）—— 2026-10-21 新增。
     *
     * <h2>铁律 10：候选只从<b>本场账本</b>来</h2>
     * 遍历 {@link UnitRegistry#tracked(String)}（本场登记过的 UUID）→ {@code Bukkit.getEntity(uuid)}
     * 点查 → 只留 {@code LivingEntity}。★ 没有 {@code getNearbyEntities} / {@code getEntities}。
     * ★ 账本里的**展示方块**（{@code BlockDisplay}）不是 {@code LivingEntity} ⇒ 天然被排除
     * （它也在账本里、也带 {@code building} 标记，但绝不能当成「敌人」或「召唤物」）。
     *
     * <p>★ 包级可见 + 可覆盖：离线测试没有世界、没有账本，直接塞一份夹具。
     */
    List<BotSpellTargeting.UnitView> spellTargets(String matchId) {
        List<BotSpellTargeting.UnitView> units = new ArrayList<>();
        if (!(plugin instanceof InfernalClashPlugin ic) || ic.unitRegistry() == null) {
            return units;
        }
        UnitRegistry registry = ic.unitRegistry();
        for (UUID id : registry.tracked(matchId)) {
            OwnershipTable.Owner owner = registry.table().owner(id);
            if (owner == null || owner.matchId() == null || !owner.matchId().equals(matchId)) {
                continue;
            }
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                continue;
            }
            Location at = living.getLocation();
            if (at.getWorld() == null) {
                continue;
            }
            units.add(new BotSpellTargeting.UnitView(id, owner.side(), registry.isObjective(id),
                    at.getX(), at.getY(), at.getZ(), at.getYaw()));
        }
        return units;
    }

    /**
     * ★★ <b>唯一的施法入口</b>（默认走既有的 {@code spell/SpellCaster}）—— 2026-10-21 新增。
     *
     * @param card    要放的法术卡
     * @param origin  落点（规则算出来的那一格）
     * @param target  单体目标（只有 {@code RANDOM_ALLY_TARGET} 非空）
     * @param matchId 对局
     * @return 施放结果（被拒 ⇒ {@code cast() == false}）
     */
    SpellCastResult castSpell(Card card, Location origin, UUID target, String matchId) {
        if (!(plugin instanceof InfernalClashPlugin ic) || ic.spellCaster() == null) {
            return SpellCastResult.refused("SpellCaster 未就绪（插件未 enable？）");
        }
        Entity aimed = target == null ? null : Bukkit.getEntity(target);
        if (target != null && aimed == null) {
            // ★ 目标实体解析不到（区块卸载 / 已消失）⇒ 当作「没有目标」处理的最保守做法：
            //   直接拒绝这一次（指针不动），下一次节拍会重新选人。
            return SpellCastResult.refused("单体目标 " + target + " 解析不到实体");
        }
        SpellCaster.Caster caster = SpellCaster.Caster.of(ownerOf(), origin, aimed);
        SpellCaster.Report report = ic.spellCaster().cast(caster, matchId, side, card.id(),
                card.spell(), origin, 0);
        return new SpellCastResult(report.cast(), report.cells(), report.targets(),
                report.effectsApplied(), report.note());
    }

    /**
     * ★ 本方的「召唤者 UUID」（写进 PDC 归属 → 谁召唤的）。
     *
     * <p>训练局里人机那一方<b>没有真人玩家</b>（{@code MatchService.SideState.player()} 可能是
     * 真人那一侧的 id）⇒ 这里给一个<b>与本局绑定</b>的稳定 id（由 matchId + side 派生），
     * 而不是 {null}：null 会让「归属判定」少一位（{@code OwnershipTable.Owner} 的 owner）
     * 且日志里看不出是谁放的。
     *
     * <p>★ 2026-10-21 起它**同时**是法术施放者的 UUID（效果归属 / 冷却窗口 / 「给施放者加经验」）。
     */
    UUID ownerOf() {
        MatchService.SideState state = match.side(side);
        if (state != null && state.player() != null) {
            return state.player();
        }
        return TrainingBotAssembly.botIdFor(match.matchId(), side);
    }

    /** 本方阵营（日志 / 测试用）。 */
    public Side side() {
        return side;
    }

    /** 本场对局（日志 / 测试用）。 */
    public MatchService match() {
        return match;
    }

    /** 手牌快照（探针 / 测试用；{@code null} 来源 ⇒ 空列表）。 */
    public List<BotPlan.HandCard> handSnapshot() {
        List<BotPlan.HandCard> cards = hand == null ? null : hand.get();
        return cards == null ? List.of() : new ArrayList<>(cards);
    }
}
