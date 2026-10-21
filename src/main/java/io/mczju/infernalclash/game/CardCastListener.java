package io.mczju.infernalclash.game;

import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.arena.HalfField;
import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.Facing;
import io.mczju.infernalclash.card.PlacementResolver;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.policy.CardCooldowns;
import io.mczju.infernalclash.spell.EffectApplier;
import io.mczju.infernalclash.spell.SpellCaster;
import io.mczju.infernalclash.ui.MenuTexts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.function.Consumer;

/**
 * ★ <b>卡牌施放入口</b>：右键卡牌 → 扣费 → 上冷却 → 召唤落点（R24/R25/R28）。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>本类只做「**认领 + 门禁 + 转交**」：认领这次右键（取消原版行为）、逐条过门禁
 *       （在对局中 / 处于可打牌阶段 / 是自己的牌 / 有阵营 / 半场合法 / 经验够），
 *       然后把落点判定转交 {@link PlacementResolver}（纯函数）、把生成转交 {@code SummonSpawner}；</li>
 *   <li>★ **所有判据都不在这里发明**：阶段口径来自 `MatchService.canCast()`、
 *       半场来自 `arenas.yml`、费用与冷却来自卡牌 YAML、落点规则来自 `docs/02` §6.4；
 *       ★★ 而「这一次出牌该不该扣经验」来自 `MatchService.chargeXp`（回指 `match/CardCharge`）
 *       —— 出牌的**两条**路径（召唤 :128 与法术 :165）都只有这一行，所以
 *       `/incl debug infinite-xp on`（「打牌不扣经验」，`docs/10` §8 第二轮裁定块）
 *       不可能只接到其中一条。</li>
 * </ul>
 *
 * <h2>★ 铁律 10：本类不查询任何「附近」的东西</h2>
 * 只用三样**已经在我们手上**的东西：玩家自己、他右键的那个方块、他所在的对局。
 * 没有 {@code getNearbyEntities} / {@code getEntities}。
 *
 * <h2>① 面向玩家的文案（★ 2026-10-15 已接线）</h2>
 * 本类**不再硬编码**任何玩家可见文本：一律走 {@code display.yml} 的 {@code messages.*}
 * （经 {@link MenuTexts#component} 解析 MiniMessage，取不到 / 值是占位 / 解析失败时
 * **回退到代码里的常量** —— 也就是接线前代码印的那句话）。
 * ★ 键名集中在本类的 {@code KEY_*} 常量里（唯一来源，测试逐条钉住），出处见
 * `docs/20` §2.3 与 `docs/05` E1。
 */
public final class CardCastListener implements Listener {

    // ==================================================================================
    // 文案键（★ `display.yml` 的 `messages.*`，唯一来源；测试逐条钉住，防止"静默回退"）
    // ==================================================================================

    /**
     * 「战斗菜单打不开」的兜底提示（{@code match.battle-menu-pending}）。
     *
     * <p>★ <b>语义在 2026-10-17 第十二轮变了</b>（缺陷 {@code docs/27} §D7 修完之后）：
     * 它原来指「战斗菜单还没做」（那时的落点是本类里一句硬编码提示）；
     * 现在菜单真的做了（{@code ui/BattleMenu}）⇒ 这条键退化为
     * <b>「界面没能打开」时的兜底话术</b>。★ 生效路径见下面那处
     * {@code MenuRegistry.openBattleMenu(...) == false} 的分支。
     */
    public static final String KEY_BATTLE_MENU_PENDING = "match.battle-menu-pending";
    /** 手里拿着卡牌物品、但人不在任何对局里。 */
    public static final String KEY_NOT_IN_MATCH = "errors.not-in-match";
    /** 在对局里，但不是可以出牌的阶段。 */
    public static final String KEY_NOT_BATTLE_PHASE = "errors.not-battle-phase";
    /** 在对局里但没有阵营（本局 1v1，多余的玩家不参与）。 */
    public static final String KEY_NO_SIDE = "errors.no-side";
    /** 人物品上的卡牌 id 在卡池里找不到；占位符 {@code {card}}。 */
    public static final String KEY_UNKNOWN_CARD = "errors.unknown-card";
    /**
     * ★★ <b>这张卡还在冷却</b>（2026-10-18 新增）；占位符 {@code {card}} {@code {seconds}}。
     */
    public static final String KEY_CARD_ON_COOLDOWN = "errors.card-on-cooldown";
    /** 经验不足以支付这张卡的费用；占位符 {@code {cost}} {@code {missing}}（★ 两处出牌路径共用）。 */
    public static final String KEY_NOT_ENOUGH_XP = "errors.not-enough-xp";
    /** 召唤物一个都没生成出来（费用与冷却已退还）。 */
    public static final String KEY_SUMMON_FAILED_REFUNDED = "errors.summon-failed-refunded";
    /** 法术未能施放（费用与冷却已退还）；占位符 {@code {note}}。 */
    public static final String KEY_SPELL_FAILED_REFUNDED = "errors.spell-failed-refunded";
    /** 半场矩形还没配置 ⇒ fail-safe 拒绝施放。 */
    public static final String KEY_HALF_NOT_CONFIGURED = "errors.half-not-configured";
    /** 召唤牌只能在己方半场使用（R28）。 */
    public static final String KEY_NOT_YOUR_HALF = "errors.not-your-half";
    /** 落点被拒的兜底说明；占位符 {@code {reason}}。 */
    public static final String KEY_SUMMON_REFUSED = "errors.summon-refused";

    /**
     * ★★★ <b>回响：本局还没有出过牌</b>（2026-10-19 新增）。
     *
     * <p>出处 = 用户对「回响」方案的逐字口径：「再一次使用本场对战上次使用的卡
     * （**在对战中不能被当成第一张牌打出**）」⇒ 没有"上一张"时必须<b>明确拒绝</b>，
     * 而且**不消耗费用与冷却**（同 `errors.spell-needs-ally` 那条的口径：
     * 拒绝发生在扣费之前）。
     */
    public static final String KEY_ECHO_NO_PREVIOUS = "errors.echo-no-previous";

    /**
     * ★★★ <b>这张法术必须先瞄到友方</b>（2026-10-18 新增）；占位符 {@code {aim}}
     * （= 「准星上没有目标」/「准星上的是敌方单位」这类说明）。
     *
     * <p>出处 = 用户裁定逐字：「必须用准星指着友方，如果没有选到目标就不使用，
     * 不消耗费用和冷却」⇒ 这条提示必须**明确告诉玩家"没有消耗"**，
     * 否则玩家会怀疑是不是被白扣了费。
     */
    public static final String KEY_SPELL_NEEDS_ALLY = "errors.spell-needs-ally";

    private final InfernalClashPlugin plugin;

    /**
     * ★★★ <b>「本次右键原本已被别的插件取消，但那是我们的物品 ⇒ 我们照样接管」的告警去重表</b>
     * （2026-10-17 第十三轮收尾新增，缺陷来自用户实机「右键空气完全没反应」）。
     *
     * <p>★ 键 = {@code 动作 + 物品类别}（如 {@code RIGHT_CLICK_AIR/card}），
     * 每类<b>只告警一次</b> ⇒ 既是"确实有人取消了 AIR 事件"的现场证据，
     * 又不会把日志刷爆（一次对局里右键几百次很正常）。
     */
    private final java.util.Set<String> reportedCancelled =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CardCastListener(InfernalClashPlugin plugin) {
        this.plugin = plugin;
    }

    /** 文案取不到时的告警出口（{@link MenuTexts} 会按键去重，同一个键最多告警一次）。 */
    private Consumer<String> warner() {
        return msg -> plugin.getLogger().warning(msg);
    }

    /**
     * ★ 记录「这次右键原本被取消了，但因为是我们的物品，我们照样接管」—— <b>按动作去重</b>。
     *
     * <p>为什么值得留一条告警：用户报的「右键空气完全没反应」在日志里<b>没有任何痕迹</b>
     * （我们连事件都没收到，自然不会打日志）⇒ 现场只能靠猜。
     * 有了这一条，下次同类问题会直接显示
     * 「谁在 AIR 上取消了事件」，而不用再反编译别的插件。
     */
    private void noteCancelledHandled(PlayerInteractEvent event, Action action) {
        String key = action.name() + "/" + (event.getClickedBlock() == null ? "air" : "block");
        if (!reportedCancelled.add(key)) {
            return;   // 这一类已经报过
        }
        plugin.getLogger().info("[IC] 右键事件 " + key + " 在到达我们之前已被取消"
                + "（玩家 " + event.getPlayer().getName() + "），但手持的是我们的物品，照样接管。"
                + "这条 INFO 每个动作只出现一次；它说明有别的插件在取消该事件"
                + "（用户 2026-10-17 报「右键空气完全没反应」的根因方向）");
    }

    /**
     * 取一条 {@code display.yml} 文案发给玩家。
     *
     * <p>★ 一律走 {@link MenuTexts#component}：解析 MiniMessage ＋ **失败时回退到 {@code fallback}**
     * （代码里的中文常量），并且绝不把异常抛到玩家脸上。
     */
    private void tell(Player player, String key, String fallback, Map<String, String> placeholders,
                      NamedTextColor colorIfAbsent) {
        // ★ 2026-10-19：配置里把这一行留空（`""`）⇒ 直接不发（不发空行，见 MenuTexts#isSilent）
        if (io.mczju.infernalclash.ui.MenuTexts.isSilent(plugin.display(), key)) {
            return;
        }
        player.sendMessage(MenuTexts.component(plugin.display(), key, fallback, placeholders,
                colorIfAbsent, warner()));
    }

    /**
     * ★★★ <b>右键入口</b> —— 2026-10-17 第十三轮收尾修掉「**右键空气完全没反应**」。
     *
     * <h2>用户实测（逐字）</h2>
     * 「**卡牌使用。我记得我在文档里提到过，玩家直接手持卡牌右键空气（没有选中任何方块时），
     * 会以玩家位置释放法术/进行召唤。同样，菜单也需要玩家在右键空气时也能打开。IG 就实现了这个效果**」
     * ＋ 我追问「右键方块能打开吗」⇒「**右键方块能打开，只有右键空气不行**」。
     *
     * <h2>因此根因不在我们的分支里</h2>
     * 本方法从一开始就同时接受 {@code RIGHT_CLICK_BLOCK} 与 {@code RIGHT_CLICK_AIR}
     * （下面第 ① 段）。★ 而「对着方块能开、对着空气不能开」这个差值只有一种成因：
     * <b>某个更早的监听器（或服务端策略）在 {@code RIGHT_CLICK_AIR} 上把事件
     * {@code setCancelled(true)} 了</b>，而本类原先标着
     * {@code @EventHandler(ignoreCancelled = true)} ⇒ Bukkit 直接把事件<b>不投递</b>给我们。
     *
     * <h2>修法（以及为什么这样修是安全的）</h2>
     * <ol>
     *   <li><b>去掉 {@code ignoreCancelled = true}</b> ⇒ 我们一定收得到事件；</li>
     *   <li>★★ 收到之后<b>只对「我们自己的物品」无视取消状态</b>
     *       （判据仍是 PDC：{@code isBattleItem} / {@code isPrepMenuItem} / {@code cardIdOf}）；
     *       <b>别人的物品一律不碰</b> —— 不 {@code setCancelled}、不处理、立刻 return。
     *       ★ 这条纪律是 {@code docs/09} §5.4【AI 补充 · 安全】的原话：
     *       「绝不无过滤地 {@code setCancelled(true)}（那会打死大厅菜单与别的玩法）」；</li>
     *   <li>第一次遇到「原本被取消但我们照样接管」时打<b>一条</b>告警（按动作去重）——
     *       那正是这次缺陷的现场证据，以后同类问题不用再靠猜。</li>
     * </ol>
     * ★ 为什么"无视取消"对我们的物品是安全的：卡牌 / 投降钟 / 选卡菜单都是**我们自己发的**
     * 纯功能物品，右键**本就该被完全接管**（下面每一条分支都立刻 {@code setCancelled(true)}），
     * 原版对它们没有任何行为可做。
     */
    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        boolean ground = action == Action.RIGHT_CLICK_BLOCK;
        if (!ground && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        // 只认主手：副手也会触发同一个事件，不挡会出现「一次右键打两张牌」
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem() == null
                ? player.getInventory().getItemInMainHand()
                : event.getItem();

        // ★★ 三个门禁判据**只算一次**，后面各分支复用。
        //   理由：`PrepPlayerLinkWiringTest` 那条「门禁判据只该出现一次（两处判断迟早分叉）」
        //   —— 我第一版为了算 `ours` 又把 isPrepMenuItem 调了第二遍，当场被它抓住。
        //   ★ 现在的形状：PDC 判据各一次，`ours` 只是它们的**并**，没有第二次调用。
        boolean isBattle = plugin.cardItems().isBattleItem(item);
        boolean isPrep = plugin.cardItems().isPrepMenuItem(item);
        String cardId = plugin.cardItems().cardIdOf(item);
        // ★ 先认物品**再**谈取消状态：别人的物品我们一个字节都不碰
        boolean ours = isBattle || isPrep || cardId != null;
        if (ours && event.isCancelled()) {
            noteCancelledHandled(event, action);
        }
        if (isBattle) {
            event.setCancelled(true);
            // ★★★ 2026-10-17 第十二轮（缺陷 docs/27 §D7）：**战斗菜单真的做了**。
            //   用户原话逐字：「投降菜单没有制作，并且是一个真正的可以使用的盾牌。
            //   你可以把它设置成一个钟，这样玩家就无法与其互动了」
            //   ⇒ ① 材质 SHIELD → CLOCK（卡牌物品侧，见 CardItems#BATTLE_ITEM_MATERIAL）；
            //      ② 右键真的打开 U3（ui/BattleMenu，含「投降」按钮）。
            //   ★ 判据与收尾路径全在 MatchService#surrender（【Q28】「玩家退出 ⇒ 判负」的主动版本），
            //     本监听器只负责"转交"。
            //   ★ 拿不到 matchId（不在任何对局里）时走**既有**的「你不在对局中」那条文案键
            //     （不再借用 match.battle-menu-pending —— 那个键是"菜单还没做"的历史遗留，
            //      这一轮它的语义已经不成立了，见 display.yml 里那一行的新注释）。
            MatchService match = MatchService.ofPlayer(player.getUniqueId());
            if (match == null) {
                tell(player, KEY_NOT_IN_MATCH, "你现在不在对局中。", Map.of(), NamedTextColor.RED);
                return;
            }
            // ★ 打开失败（插件未就绪 / 权限不足 / 界面注册失败 —— MenuRegistry 已经给过一句
            //   更具体的话）⇒ 这里再补一句「菜单暂时打不开」，绝不静默无反应。
            if (!io.mczju.infernalclash.ui.MenuRegistry.openBattleMenu(player, match.matchId())) {
                tell(player, KEY_BATTLE_MENU_PENDING,
                        "战斗菜单暂时打不开（原因见控制台日志）。", Map.of(), NamedTextColor.YELLOW);
            }
            return;
        }

        // ★★ 2026-10-16 第八轮：准备阶段的「选卡菜单」物品 —— 右键任意位置重开 U4（R15）。
        //   ★ 出处：docs/02 §2 的 R15「准备时间内玩家持有『选卡菜单』物品，对任何位置右键
        //     可以再次打开该 UI」；docs/09 §5.4 明写「打开后进入的是**就是 U4 本身**」（【Q37】）。
        //   ★ 纪律（docs/09 §5.4 的【AI 补充 · 安全】）：必须**过滤到手持的是选卡菜单物品**，
        //     绝不无过滤地 setCancelled(true)（那会打死大厅菜单与别的玩法）——
        //     过滤判据就是 isPrepMenuItem（PDC，与卡牌 / 战斗物品同一口径）。
        //   ★ 上方那个 `ground`/`RIGHT_CLICK_AIR` 的分支**已经覆盖**了「对空气右键」与
        //     「对方块右键」两种（只拦其一会出现「对着方块右键没反应」）。
        if (isPrep) {
            event.setCancelled(true);
            MatchService match = MatchService.ofPlayer(player.getUniqueId());
            if (match == null) {
                tell(player, KEY_NOT_IN_MATCH, "你现在不在对局中。", Map.of(), NamedTextColor.RED);
                return;
            }
            if (io.mczju.infernalclash.ui.MenuRegistry.openDeckPicker(player, match.matchId())) {
                plugin.getLogger().info("[IC] " + player.getName()
                        + " 用「选卡菜单」物品重开了选卡界面（matchId=" + match.matchId() + "，R15）");
            }
            return;
        }

        if (cardId == null) {
            return;   // 不是我们的物品 —— 绝不干预
        }
        event.setCancelled(true);   // 认领这次右键：卡牌物品不做任何原版行为

        MatchService match = MatchService.ofPlayer(player.getUniqueId());
        if (match == null) {
            tell(player, KEY_NOT_IN_MATCH, "你现在不在对局中。", Map.of(), NamedTextColor.RED);
            return;
        }
        if (!match.canCast()) {
            tell(player, KEY_NOT_BATTLE_PHASE, "现在不是战斗阶段，不能出牌。",
                    Map.of(), NamedTextColor.RED);
            return;
        }
        Side side = match.sideOf(player.getUniqueId());
        if (side == null) {
            tell(player, KEY_NO_SIDE, "你没有阵营（本局为 1v1，多余玩家不参与）。",
                    Map.of(), NamedTextColor.RED);
            return;
        }
        Card card = plugin.cardCatalog().cards().get(cardId);
        if (card == null) {
            tell(player, KEY_UNKNOWN_CARD, "未知卡牌 id：" + cardId,
                    Map.of("card", cardId), NamedTextColor.RED);
            return;
        }
        // ★★★ 2026-10-20（用户：「金卡召唤/释放时的金色粒子特效没有添加」）：
        //   金卡身份从**物品 PDC** 读（`CardItems.KEY_GOLD`）—— 以前金色只染名字，
        //   施放时根本认不出来（见 CardItems#KEY_GOLD 的 javadoc）。
        //   ★ 读不到 ⇒ false（把金卡当普通卡，绝不会反过来让普通卡冒金光）。
        boolean golden = plugin.cardItems().isGolden(item);
        // ★★★ 2026-10-18：**冷却拦截**（用户：「冷却系统完全没有实现；每一张卡都可以随时连续使用」）。
        //   ★ 判据在本场的 `policy/CardCooldowns`（**按阵营分表** + 同一方按卡共享 + 每局清零），
        //     时钟 = battleTick。
        //   ★★★ 2026-10-21 修（用户报的真人对局缺陷）：「**两人的卡牌共享了 cd**：蓝方用完火球后，
        //     红方的火球没有遮罩、也没用过，却打不出来」—— 根因是那张表**只按 cardId 索引**、
        //     而它是**整场共享**的 ⇒ 现在必须带 `side`（红蓝两张表，互不影响）。
        //   ★ 客户端遮罩另由 `applyCooldownOverlay` 用 `setCooldown(Material, ticks)` 做
        //     —— 2026-10-18 起每张卡有**唯一材质**，遮罩才是每卡独立的（而且它是**按玩家**的，
        //     所以只有出牌的那一方看得见变暗 —— 修之前正是"看不见却打不出"）。
        int cooling = match.cooldowns().remaining(side, cardId, match.battleTick());
        if (cooling > 0) {
            tell(player, KEY_CARD_ON_COOLDOWN,
                    "这张卡还在冷却（还要 " + io.mczju.infernalclash.policy.CardCooldowns
                            .seconds(cooling) + " 秒）。",
                    Map.of("card", card.displayName(), "seconds",
                            io.mczju.infernalclash.policy.CardCooldowns.seconds(cooling)),
                    NamedTextColor.RED);
            return;
        }
        // ★ 这两行必须**在法术分支之前**声明（法术与召唤都要用；写在下面会编译不过）
        Block clicked = event.getClickedBlock();
        boolean atGround = ground && clicked != null;

        // ★ P6：法术卡走另一条管线（**没有「仅己方半场」限制** —— docs/02 §6.4：法术可施于任何区域）
        if (!card.isSummon()) {
            // ★★★ 2026-10-19「回响」：**复读**在进法术管线之前被接住（方案 1 ＋ 落点 (b)）。
            //   判据是**卡自己的 YAML**（`spell.effects` 里有没有 REPLAY_LAST_CARD），
            //   不是 `id.equals("echo")` —— 代码里不写死卡名。
            if (replaysLastCard(card)) {
                replayLastCard(player, match, side, card, atGround ? clicked : null, golden);
                return;
            }
            castSpell(player, match, side, card, atGround ? clicked : null, golden);
            return;
        }

        // ---- 落点（★ 判据全在纯函数里）----
        PlacementResolver.Result placement = placementFor(player, match, side, atGround, clicked);
        if (placement == null) {
            return;   // 已被拒，且 refusal(...) 已经给玩家说明
        }

        // ---- 费用（★ 不足则一分不扣；★ 开关开着则**不扣但放行**）----
        //   ★★ 两处出牌路径都走 match.chargeXp（判据在 match/CardCharge，唯一的扣费入口）
        //      = /incl debug infinite-xp on 的「打牌不扣经验」（docs/10 §8 第二轮裁定块）
        int paid = match.chargeXp(side, card.cost());
        if (paid < 0) {
            double missing = Math.max(0.0, card.cost() - match.xp(side));
            tell(player, KEY_NOT_ENOUGH_XP,
                    String.format("经验不足：这张卡要 %.0f，还差 %.1f。",
                            (double) card.cost(), missing),
                    Map.of("cost", String.format("%.0f", (double) card.cost()),
                            "missing", String.format("%.1f", missing)),
                    NamedTextColor.RED);
            return;
        }

        // ---- 冷却（★★ 2026-10-18：**自己的表 + 每卡唯一材质遮罩**）----
        //   ★ 原来这里写的是 `player.setCooldown(Key.key("infernalclash:" + id), ticks)`：
        //     那个 Key **不对应任何真实物品**（卡牌身份在 PDC 上）⇒ 遮罩根本不出现，
        //     而且**没有任何地方读回它** ⇒ 可以连发。
        startCooldown(player, match, card);

        // ---- 生成 ----
        int spawned = spawnSummons(player, match, side, card, atGround, clicked, placement, golden);

        if (spawned <= 0) {
            // ★ 生成失败要把费用**退回去**（且只在**真的扣过**时退：paid == 0 是无尽经验那一路，
            //   退费会把经验凭空加上去 —— 那是刷经验的漏洞）；冷却同样清掉
            if (paid > 0) {
                match.refundXp(side, paid);
            }
            cancelCooldown(player, match, card);
            tell(player, KEY_SUMMON_FAILED_REFUNDED,
                    "召唤失败（见控制台日志）—— 已退还费用与冷却。", Map.of(), NamedTextColor.RED);
            return;
        }
        // ★★★ 2026-10-19：**只记成功的**（召唤真的出来了才记）⇒ 回响不会复读一张"没打出去"的牌
        match.castMemory().remember(side, card.id());
        plugin.getLogger().info("[IC] " + player.getName() + "（" + side + "）打出 " + card.id()
                + "，生成 " + spawned + " 个召唤物 @" + placement.cellX() + ","
                + (atGround ? clicked.getY() : airCastGroundY(player, match, side))
                + "," + placement.cellZ() + "｜余量 " + String.format("%.2f", match.xp(side))
                + (paid == 0 ? "｜本次不扣经验（infinite-xp）" : ""));
    }

    /**
     * ★ <b>这张卡是不是「回响」类</b>（`spell.effects` 里写了 {@code REPLAY_LAST_CARD}）。
     *
     * <p>★ 判据只看卡牌数据 ⇒ 换一张卡 / 改 YAML 就换行为，代码里**不写死任何卡名**
     * （`echo` 只是目前唯一的使用者）。
     */
    static boolean replaysLastCard(Card card) {
        if (card == null || card.spell() == null) {
            return false;
        }
        return card.spell().effects().stream()
                .anyMatch(effect -> effect.type() == io.mczju.infernalclash.card.Spell.EffectType
                        .REPLAY_LAST_CARD);
    }

    /**
     * ★★★ <b>「回响」：把这一方上一张成功打出的牌再打一次</b>（2026-10-19）。
     *
     * <h2>口径（用户逐字裁定）</h2>
     * <ul>
     *   <li>「**再一次使用本场对战上次使用的卡**」⇒ 复读的是<b>同阵营</b>上一张<b>成功</b>打出的牌；</li>
     *   <li>「**在对战中不能被当成第一张牌打出**」⇒ 没有上一张 ⇒ <b>明确拒绝</b>，
     *       **不扣费、不进冷却**（拒绝在扣费之前）；</li>
     *   <li>落点 = 用户选的 **(b)**：「用玩家**现在瞄的那一格**」（不是上一手的落点）
     *       ⇒ 召唤卡仍要过「己方半场」校验，法术照旧不受半场限制。</li>
     * </ul>
     *
     * <h2>付费与冷却：只算**回响自己**那张</h2>
     * 付回响的 5 费、进回响的 30 秒冷却；被复读的牌**不再付它的费、也不进它的冷却**。
     * ★ 这就是这张卡的卖点（5 费重放一张 8 费卡）；也是它必须 30 秒冷却的原因。
     *
     * <h2>★ 复读之后「上一张」不变</h2>
     * 本方法**不**调用 {@code castMemory().remember(...)} ⇒ 连打两次回响仍然是复读同一张牌，
     * 不会往下漂；而回响自己**永远进不了**记忆表（它压根不走下面那两条记点）。
     */
    private void replayLastCard(Player player, MatchService match, Side side, Card replayCard,
                                Block clicked, boolean golden) {
        java.util.Optional<String> lastId = match.castMemory().lastOf(side);
        if (lastId.isEmpty()) {
            tell(player, KEY_ECHO_NO_PREVIOUS,
                    "回响：这一局你还没有打出过任何牌 —— 它不能当第一张牌打出"
                            + "（没有消耗费用与冷却）。",
                    Map.of(), NamedTextColor.RED);
            return;
        }
        Card replayed = plugin.cardCatalog().cards().get(lastId.get());
        if (replayed == null || replaysLastCard(replayed)) {
            // fail-safe：记忆表里出现了卡池里没有的 id、或指向另一张回响类卡
            // （正常路径不可能 —— 回响自己从不入账）⇒ 什么都不做，也别扣玩家的费
            tell(player, KEY_ECHO_NO_PREVIOUS,
                    "回响：找不到上一张牌（卡池变了？）—— 没有消耗费用与冷却。",
                    Map.of(), NamedTextColor.RED);
            plugin.getLogger().warning("[IC] 回响：记忆表里的卡 id " + lastId.get()
                    + " 在卡池里不存在或本身是回响类卡，拒绝施放（未扣费）");
            return;
        }
        boolean atGround = clicked != null;
        // ① 落点：召唤卡要先算（被拒 ⇒ 一分不扣就返回）；法术用同一格当落点，不受半场限制
        PlacementResolver.Result placement = null;
        if (replayed.isSummon()) {
            placement = placementFor(player, match, side, atGround, clicked);
            if (placement == null) {
                return;
            }
        }
        // ② 付费（回响自己的费用）
        int paid = match.chargeXp(side, replayCard.cost());
        if (paid < 0) {
            double missing = Math.max(0.0, replayCard.cost() - match.xp(side));
            tell(player, KEY_NOT_ENOUGH_XP,
                    String.format("经验不足：这张卡要 %.0f，还差 %.1f。",
                            (double) replayCard.cost(), missing),
                    Map.of("cost", String.format("%.0f", (double) replayCard.cost()),
                            "missing", String.format("%.1f", missing)),
                    NamedTextColor.RED);
            return;
        }
        // ③ 冷却（回响自己的）
        startCooldown(player, match, replayCard);

        // ④ 真的再打一次
        if (replayed.isSummon()) {
            int spawned = spawnSummons(player, match, side, replayed, atGround, clicked, placement,
                    golden);
            if (spawned <= 0) {
                if (paid > 0) {
                    match.refundXp(side, paid);
                }
                cancelCooldown(player, match, replayCard);
                tell(player, KEY_SUMMON_FAILED_REFUNDED,
                        "复读的召唤失败了（见控制台日志）—— 已退还费用与冷却。",
                        Map.of(), NamedTextColor.RED);
                return;
            }
            plugin.getLogger().info("[IC] " + player.getName() + "（" + side + "）打出 " + replayCard.id()
                    + "，复读召唤 " + replayed.id() + " ×" + spawned + " @" + placement.cellX()
                    + "," + (atGround ? clicked.getY() : airCastGroundY(player, match, side))
                    + "," + placement.cellZ() + "｜余量 " + String.format("%.2f", match.xp(side)));
            return;
        }
        Location impact = clicked == null ? airCastImpact(player, match, side)
                : new Location(clicked.getWorld(), clicked.getX(), clicked.getY(), clicked.getZ());
        SpellCaster.Report report = plugin.spellCaster().cast(player, match.matchId(), side,
                replayed.id(), replayed.spell(), impact, 0);
        if (!report.cast()) {
            if (paid > 0) {
                match.refundXp(side, paid);
            }
            cancelCooldown(player, match, replayCard);
            tell(player, KEY_SPELL_FAILED_REFUNDED,
                    "复读的法术未能施放：" + report.note() + "（已退还费用与冷却）",
                    Map.of("note", report.note()), NamedTextColor.RED);
            return;
        }
        // ★ 2026-10-20：金卡的金色粒子（回响是金卡 ⇒ 复读的那一刻也冒一次）
        if (golden) {
            playGoldenBurst(impact == null ? player.getLocation()
                    : impact.clone().add(0.5, 0.6, 0.5));
        }
        plugin.getLogger().info("[IC] " + player.getName() + "（" + side + "）打出 " + replayCard.id()
                + "，复读法术 " + replayed.id() + "｜覆盖 " + report.cells() + " 格 / 命中 "
                + report.targets() + " 个目标｜余量 " + String.format("%.2f", match.xp(side)));
    }

    /**
     * ★ <b>落点判定</b>（召唤分支与"复读召唤"共用**同一处实现**）。
     *
     * @return {@code null} = 被拒（<b>已经把说明发给玩家</b>，调用方直接 return，别扣费）
     */
    private PlacementResolver.Result placementFor(Player player, MatchService match, Side side,
                                                  boolean atGround, Block clicked) {
        Location eye = player.getLocation();
        int targetX = atGround ? clicked.getX() : 0;
        int targetZ = atGround ? clicked.getZ() : 0;
        // ★★ 2026-10-18：半场判定用**本局的**竞技场（多竞技场同时开局时不能串场）；
        //   match.arena() 为 null（老调用点 / 离线夹具）⇒ 退回全局默认。
        HalfField ownHalf = (match.arena() != null ? match.arena() : plugin.arenaConfig()).half(side);
        PlacementResolver.Result placement = PlacementResolver.resolve(
                atGround, targetX, targetZ, eye.getBlockX(), eye.getBlockZ(), ownHalf);
        if (!placement.allowed()) {
            player.sendMessage(refusal(placement.decision()));
            return null;
        }
        return placement;
    }

    /**
     * ★ <b>按落点生成召唤物</b>（召唤分支与"复读召唤"共用**同一处实现**）。
     *
     * @param golden 这张卡是不是金色版本（2026-10-20：金卡要给召唤物挂"打蜡粒子"）
     * @return 真的生成出来的个数（{@code <= 0} ⇒ 调用方负责退费/退冷却）
     */
    private int spawnSummons(Player player, MatchService match, Side side, Card card,
                             boolean atGround, Block clicked, PlacementResolver.Result placement,
                             boolean golden) {
        Location eye = player.getLocation();
        int referenceY = atGround ? clicked.getY() : airCastGroundY(player, match, side);
        Location center = new Location(player.getWorld(),
                placement.cellX() + 0.5, referenceY, placement.cellZ() + 0.5);
        return plugin.summonSpawner().spawn(card, center, Facing.fromYaw(eye.getYaw()),
                match.matchId(), side, player.getUniqueId(), match.tasks(), golden);
    }

    /**
     * ★★★ <b>「点空气」时的参照高度 = <u>竞技场地面</u></b>（2026-10-21 新增）。
     *
     * <h2>用户原话（逐字）</h2>
     * 「**玩家在空中飞行时右键释放的卡牌有 y 轴偏移，我希望这些卡牌都在玩家的 x、z 坐标处，
     * 以及竞技场的地面上（与防御塔、信标同 y）释放**」。
     *
     * <h2>修之前是什么样（两条路径各偏一次）</h2>
     * <ul>
     *   <li><b>召唤卡</b>：{@code referenceY = eye.getBlockY()} —— 玩家飞在 y=210 就在半空生成，
     *       而 {@code SummonSpawner#safeSpawnLocation} 只在「参照高度 +2 ~ −3」这 6 格里找落脚点
     *       ⇒ 半空中找不到 ⇒ 按原坐标生成（那条 WARN 原文：「附近 6 格内没有可站立的方块」）；</li>
     *   <li><b>法术卡</b>：{@code impact = null} ⇒ {@code SpellCaster} 用 {@code caster.location()}
     *       ⇒ 覆盖范围的粒子环 / 命中窗口都在半空。</li>
     * </ul>
     *
     * <h2>现在</h2>
     * 「点空气」一律以**本局点位自带的 y**（= 防御塔 / 信标同一个 y = 竞技场地面）为高度，
     * x/z 仍取玩家自己的（召唤卡那边 {@code PlacementResolver} 本来就给玩家所在格）。
     * ★ <b>点了方块时完全不变</b>：那时落点就是你瞄的那一格（那是"瞄哪儿放哪儿"的正路）。
     *
     * <h2>★ 失败方向</h2>
     * 拿不到本局竞技场 / 拿不到该阵营的出生点 ⇒ **退回旧行为（玩家眼睛所在格）＋ 告警一次**，
     * 绝不去猜一个高度（铁律 10 的"中心取不到就跳过"同口径）。
     */
    private int airCastGroundY(Player player, MatchService match, Side side) {
        try {
            io.mczju.infernalclash.arena.ArenaConfig arena =
                    match.arena() != null ? match.arena() : plugin.arenaConfig();
            io.mczju.infernalclash.arena.Point spawn = arena == null ? null : arena.spawn(side);
            if (spawn != null) {
                return (int) Math.floor(spawn.y());
            }
        } catch (Throwable t) {
            warnAirCastOnce("读竞技场地面高度失败：" + t);
        }
        warnAirCastOnce("拿不到 " + side + " 的出生点 ⇒ 「点空气」按玩家眼睛所在格施放"
                + "（旧行为；可能出现在半空，见 docs/12 §4.54）");
        return player.getLocation().getBlockY();
    }

    /**
     * 「点空气」时给法术用的落点：**玩家的 x/z ＋ 竞技场地面 y**（2026-10-21）。
     *
     * <p>★ 12 张 {@code origin: IMPACT} 的法术（含用户这次实测的**火球 / 冰晶坠**）
     * 直接以此格为原点 ⇒ 粒子与命中范围落在地面上；
     * {@code origin: SELF} 的那两张（速度药水 / 战术冷却）是自身增益，仍按施放者本人算（见
     * {@code SpellCaster#resolveOrigin}）—— 它们的语义就是"对我自己生效"，压到地面反而错。
     */
    private Location airCastImpact(Player player, MatchService match, Side side) {
        return new Location(player.getWorld(),
                player.getLocation().getBlockX(), airCastGroundY(player, match, side),
                player.getLocation().getBlockZ());
    }

    /** 「点空气的落点高度」这条链的告警只打一次（每张牌都刷会把真告警淹掉）。 */
    private boolean airCastWarned;

    private void warnAirCastOnce(String message) {
        if (airCastWarned) {
            return;
        }
        airCastWarned = true;
        plugin.getLogger().warning("[IC] " + message);
    }

    /**
     * ★★★ <b>「这张法术必须先瞄到友方」的那道门</b>（2026-10-18 新增；
     * 用户裁定逐字：「**必须用准星指着友方，如果没有选到目标就不使用，不消耗费用和冷却**」）。
     *
     * <h2>为什么在**扣费之前**（这是本轮唯一重要的顺序要求）</h2>
     * 用户的三个要求是同一条规则的三面：不扣费 · 不进冷却 · 没目标就不施放。
     * 若判据放在 {@code SpellCaster#cast}（扣费之后），最优雅也只能"退费"——
     * 那仍会闪一次冷却遮罩、并在日志里留下"施放失败" ⇒ 与"不使用"不是一回事。
     * ⇒ 本方法与 {@code castSpell} 里的 {@code chargeXp} 之间**不允许**插入任何扣费动作；
     * {@code CardCastAimWiringTest} 用方法级字节码钉住这个先后。
     *
     * <p>★ 判定本体在纯类 {@link io.mczju.infernalclash.spell.AimRule}（离线逐格可测），
     * 采集端是 {@code SpellCaster#classifyAim}（32 格**有界**射线，铁律 10）。
     *
     * @return {@code true} = 放行；{@code false} = 已经给玩家发过说明、调用方必须立刻 return
     */
    private boolean aimGatePassed(Player player, MatchService match, Side side, Card card) {
        if (card.spell() == null) {
            return true;   // 不是法术卡（理论上走不到这里）⇒ 不拦
        }
        io.mczju.infernalclash.spell.AimRule.Aimed aimed =
                plugin.spellCaster().classifyAim(player, match.matchId(), side);
        io.mczju.infernalclash.spell.AimRule.Decision decision =
                io.mczju.infernalclash.spell.AimRule.decide(card.spell().targeting(), aimed);
        if (decision.allowed()) {
            return true;
        }
        String label = io.mczju.infernalclash.spell.AimRule.aimedLabel(aimed);
        tell(player, KEY_SPELL_NEEDS_ALLY,
                "这张法术要先用准星指着一个友方召唤物（" + label + "）—— 没有消耗费用与冷却。",
                Map.of("aim", label), NamedTextColor.RED);
        return false;
    }

    /**
     * 法术施放（P6）：扣费 → 上冷却 → 走 {@code SpellCaster} 的三层管线。
     *
     * <p>★ 与召唤卡的区别：**不受「仅己方半场」限制**（`docs/02` §6.4：法术可施于任何区域）。
     * ★ 生成/施放失败一律**退还费用与冷却** —— 扣了费却没效果是最让人恼火的一类缺陷。
     *
     * @param golden 这张卡是不是金色版本（2026-10-20：金卡施放瞬间要冒一簇打蜡粒子）
     */
    private void castSpell(Player player, MatchService match, Side side, Card card, Block impactBlock,
                           boolean golden) {
        // ★★★ 2026-10-18：**准星门必须在扣费之前**（用户：「没有选到目标就不使用，
        //   不消耗费用和冷却」）。判定与采集见 AimRule / SpellCaster#classifyAim。
        if (!aimGatePassed(player, match, side, card)) {
            return;
        }
        // ★★ 与召唤分支**同一行**：/incl debug infinite-xp on ⇒ 不扣经验但照常施放
        int paid = match.chargeXp(side, card.cost());
        if (paid < 0) {
            double missing = Math.max(0.0, card.cost() - match.xp(side));
            tell(player, KEY_NOT_ENOUGH_XP,
                    String.format("经验不足：这张卡要 %.0f，还差 %.1f。",
                            (double) card.cost(), missing),
                    Map.of("cost", String.format("%.0f", (double) card.cost()),
                            "missing", String.format("%.1f", missing)),
                    NamedTextColor.RED);
            return;
        }
        // ---- 冷却（与召唤分支同一个实现）----
        startCooldown(player, match, card);

        Location impact = impactBlock == null ? airCastImpact(player, match, side)
                : new Location(impactBlock.getWorld(), impactBlock.getX(), impactBlock.getY(),
                        impactBlock.getZ());
        SpellCaster.Report report = plugin.spellCaster().cast(player, match.matchId(), side,
                card.id(), card.spell(), impact, 0);

        if (!report.cast()) {
            // ★ 同上：只在真的扣过费时退还（paid == 0 = 无尽经验那一路，退费会变成刷经验）
            if (paid > 0) {
                match.refundXp(side, paid);
            }
            cancelCooldown(player, match, card);
            tell(player, KEY_SPELL_FAILED_REFUNDED,
                    "法术未能施放：" + report.note() + "（已退还费用与冷却）",
                    Map.of("note", report.note()), NamedTextColor.RED);
            return;
        }
        // ★★★ 2026-10-19：**只记成功的**（法术真的放出去了才记）—— 回响的第二个记点。
        //   ★ 回响自己走的是 `replayLastCard`（不进 `castSpell`）⇒ **永远不会**被记进来。
        match.castMemory().remember(side, card.id());
        // ★★ 2026-10-20（用户：「金卡召唤/释放时的金色粒子特效没有添加」）：
        //   金卡**施放成功**的那一刻，在落点（瞄了方块）或脚下（对空气）撒一簇打蜡粒子。
        //   ★ 位置刻意放在"成功之后"：失败（退费）的那一路不该冒金光 —— 那会让人以为放出去了。
        if (golden) {
            playGoldenBurst(impact == null ? player.getLocation()
                    : impact.clone().add(0.5, 0.6, 0.5));
        }
        plugin.getLogger().info("[IC] " + player.getName() + "（" + side + "）施放法术 " + card.id()
                + "，覆盖 " + report.cells() + " 格 / 命中 " + report.targets() + " 个目标 / 应用 "
                + report.effectsApplied() + " 次效果"
                + (report.note().isEmpty() ? "" : "｜" + report.note())
                + (paid == 0 ? "｜本次不扣经验（infinite-xp）" : ""));
    }

    /**
     * ★★ <b>金卡施放瞬间的那一簇打蜡粒子</b>（2026-10-20）。
     *
     * <p>参数全在 {@link io.mczju.infernalclash.policy.GoldenFx}（与
     * {@code SummonSpawner#applyGoldenGlint} 共用同一份，绝不写第二套）。
     * ★ 失败只告警：粒子是观感，不能因为它让"牌打出去了却没生效"。
     */
    private void playGoldenBurst(org.bukkit.Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        try {
            at.getWorld().spawnParticle(
                    org.bukkit.Particle.valueOf(io.mczju.infernalclash.policy.GoldenFx.PARTICLE),
                    at,
                    io.mczju.infernalclash.policy.GoldenFx.CAST_BURST_COUNT,
                    io.mczju.infernalclash.policy.GoldenFx.CAST_BURST_SPREAD,
                    io.mczju.infernalclash.policy.GoldenFx.CAST_BURST_SPREAD * 0.8,
                    io.mczju.infernalclash.policy.GoldenFx.CAST_BURST_SPREAD,
                    0.0);
        } catch (Throwable t) {
            plugin.getLogger().warning("[IC] 金卡施放粒子失败（不影响施放）：" + t);
        }
    }

    /**
     * ★★★ <b>登记一次冷却 + 给客户端上遮罩</b>
     * （2026-10-18 新增；用户：「冷却系统完全没有实现」）。
     *
     * <h2>两件事，缺一不可</h2>
     * <ol>
     *   <li><b>服务端判据</b>：{@code match.cooldowns().start(side, …)} —— 这才是**拦得住**连发的那个
     *       （★ <b>按阵营分表</b> + 同一方按卡共享、每局清零，时钟 = {@code battleTick}；
     *       2026-10-21 修「两人共享 cd」）；</li>
     *   <li><b>客户端遮罩</b>：{@code player.setCooldown(Material, ticks)} —— 物品变暗 + 倒计时数字。
     *       ★ 必须传**材质**而不是 {@code Key}：客户端渲染冷却遮罩是按 **ItemType**，
     *       而原来那个 {@code Key.key("infernalclash:" + id)} 不对应任何真实物品 ⇒ 遮罩永远不出现。
     *       ★ 2026-10-18 起 51 张卡各有**唯一材质**（见 {@code cards/*.yml} 的 {@code icon}），
     *       所以遮罩才是**每卡独立**的；在那之前 51 张卡全部退回
     *       {@code RECOVERY_COMPASS}，任何按材质做的遮罩都会一起变暗。</li>
     * </ol>
     *
     * <p>★ 遮罩失败只告警：它是观感，绝不该让"牌打出去了却没生效"。
     */
    private void startCooldown(Player player, MatchService match, Card card) {
        // ★★ 2026-10-18：**冷却缩减窗口**（「战术冷却」卡，docs/19 #19）在这里折进来。
        //   在此之前 `EffectApplier.activeReduction(...)` 只被写、**没有任何生产代码读**
        //   ⇒ 那张卡 2 费 / 45 秒冷却换来 0 效果（审计发现的真缺口）。
        //   ★ 口径：只影响窗口内**新施加**的冷却（窗口表的 javadoc 原本就是这么写的）。
        double ratio = EffectApplier.activeReduction(player.getUniqueId());
        int ticks = CardCooldowns.withReduction(card.cooldownTicks(), ratio);
        if (ratio > 0.0 && ticks != card.cooldownTicks()) {
            plugin.getLogger().info("[IC] 冷却缩减生效：卡 " + card.id() + " "
                    + card.cooldownTicks() + " → " + ticks + " tick（-"
                    + Math.round(Math.min(1.0, ratio) * 100) + "%，玩家 " + player.getName() + "）");
        }
        match.cooldowns().start(match.sideOf(player.getUniqueId()), card.id(),
                match.battleTick(), ticks);
        Material material = cardMaterial(card);
        if (material == null) {
            return;
        }
        try {
            player.setCooldown(material, ticks);
        } catch (Throwable t) {
            plugin.getLogger().warning("[IC] 上冷却遮罩失败（" + card.id() + "）：" + t);
        }
    }

    /** ★ 撤销冷却（出牌失败退费时用）：**该阵营**那条删掉 + 客户端遮罩清掉。 */
    private void cancelCooldown(Player player, MatchService match, Card card) {
        match.cooldowns().cancel(match.sideOf(player.getUniqueId()), card.id());
        Material material = cardMaterial(card);
        if (material == null) {
            return;
        }
        try {
            player.setCooldown(material, 0);
        } catch (Throwable ignored) {
            // 清不掉遮罩不至于挡住退款
        }
    }

    /**
     * 卡 → 材质（★ 2026-10-18：判据已收到 {@code CardItems#materialOf} 一处
     * —— 原先这里有、`CardLoadout` 与 `EffectApplier` 各有一份，四处会分叉）。
     */
    private Material cardMaterial(Card card) {
        return io.mczju.infernalclash.card.CardItems.materialOf(card);
    }

    /**
     * 拒绝原因 → 面向玩家的说明（★ 每一种都说清「为什么」和「怎么办」）。
     *
     * <p>★ 文案全部走 {@code display.yml}（键见本类的 {@code KEY_*} 常量）；
     * ★★ 其中 {@code REFUSE_NO_HALF_CONFIGURED} 那句**原来教人敲 `/incl arena`，
     * 而当时那条指令还没实现**（照着敲只会拿到用法提示）—— 2026-10-15 本轮已实现，
     * 文案也随之改成指向真动作（`docs/10` §8 R66）。
     */
    private Component refusal(PlacementResolver.Decision decision) {
        return switch (decision) {
            case REFUSE_NO_HALF_CONFIGURED -> MenuTexts.component(plugin.display(),
                    KEY_HALF_NOT_CONFIGURED,
                    "召唤失败：竞技场的半场范围还没配置（arenas.yml 的 half.red.1/2 与 "
                            + "half.blue.1/2 四个角点）—— 已按安全口径拒绝施放。"
                            + "请用 /incl arena 编辑点位后重开一局。",
                    NamedTextColor.RED, warner());
            case REFUSE_NOT_OWN_HALF -> MenuTexts.component(plugin.display(), KEY_NOT_YOUR_HALF,
                    "召唤失败：只能在自己的半场召唤（含边线；桥不归属任何一方）。",
                    NamedTextColor.RED, warner());
            default -> MenuTexts.component(plugin.display(), KEY_SUMMON_REFUSED,
                    "召唤失败：" + decision, Map.of("reason", String.valueOf(decision)),
                    NamedTextColor.RED, warner());
        };
    }
}
