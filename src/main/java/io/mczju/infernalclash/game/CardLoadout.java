package io.mczju.infernalclash.game;

import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardItems;
import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.match.MatchHooks;
import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.profile.PlayerProgress;
import io.mczju.infernalclash.ui.MenuTexts;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ★ <b>快捷栏装备发放</b>（R18、Q9）：1 号格 = 「战斗」物品，2~9 号格 = 8 张卡。
 *
 * <h2>本阶段做什么 / 不做什么</h2>
 * <ul>
 *   <li>✅ 战斗开始时给每人 1 号格「战斗」物品；</li>
 *   <li>✅ 提供「把 8 张卡放进 2~9 号格」的能力（供 `/incl debug card fill` 用）；</li>
 *   <li>✅ 对局结束时**清掉这 9 格**（`onStop`）；</li>
 *   <li>❌ **不**实现「选卡界面」⇒ **没有牌组来源**。真牌组在 **P7（局外成长）/ P8（UI）**；
 *       本阶段用**调试指令**把手牌填上 —— ★ 编一套假牌组等于替用户决定玩法（铁律 8）；</li>
 *   <li>❌ **不**做「玩家原物品的保存与归还」（那是完整的局内物品方案，属于 P7/P8 的档案与 UI 工作）
 *       ⇒ 已记在 `docs/04` §17，**不是忘了**。</li>
 * </ul>
 */
public final class CardLoadout implements MatchHooks {

    /** 快捷栏里属于本插件的格数（1 号战斗物品 + 2~9 号卡牌）。 */
    public static final int HOTBAR_SLOTS = 9;

    /** 卡组张数 = 8（= 2~9 号格；{@code docs/09} §5.2 的 8 个卡槽）。 */
    public static final int DECK_SIZE = HOTBAR_SLOTS - 1;

    /** 「选卡菜单」物品显示名的文案键（{@code display.yml} 的 {@code messages.match.prep-menu}）。 */
    public static final String KEY_PREP_MENU = "match.prep-menu";

    /** 「选卡菜单」物品 lore 的文案键（{@code messages.match.prep-menu-lore}，多行）。 */
    public static final String KEY_PREP_MENU_LORE = "match.prep-menu-lore";

    /**
     * ★★★ 「投降」物品 lore 的文案键（{@code messages.match.surrender-hint}）。
     *
     * <p>2026-10-21 新增：这是全项目**最后一处**硬编码在代码里的玩家可见文本
     * （原来写在 {@code CardItems#battleItem} 里）⇒ 现在也走配置
     * （R76：玩家可见文本一律可被 {@code display.yml} 覆盖）。
     */
    public static final String KEY_SURRENDER_HINT = "match.surrender-hint";

    private final InfernalClashPlugin plugin;
    private final Map<Side, UUID> players;

    /**
     * ★ 人机那一侧（没有 ⇒ {@code null}）——2026-10-16 第八轮新增。
     *
     * <p>用途只有一个：<b>准备阶段的「选卡菜单」物品不发给人机</b>
     * （它没有 U4 界面，8 张牌来自 {@code config.yml} 的 {@code training.bots}，
     * 实机已证每秒出牌 ⇒ 给它一个点不动的物品纯属噪声）。
     * ★ 判据是<b>身份比对</b>（UUID 等于
     * {@code TrainingBotAssembly#botIdFor(matchId, side)}），不是「名字像机器人」。
     */
    private Side botSide;

    public CardLoadout(InfernalClashPlugin plugin, Map<Side, UUID> players) {
        this.plugin = plugin;
        this.players = Map.copyOf(players);
    }

    /**
     * ★ 告诉发牌器「哪一侧是人机」（{@code null} = 两边都是真人）。
     *
     * <p>为什么用 setter 而不是构造参数：{@code CardLoadout} 在 {@code onGameStart()} 里
     * <b>先于</b>人机名册装配建立（见 {@code InfernalClashGame} 的注释），
     * 而「人机是哪一侧」是装配的返回值 ⇒ 拿到之后再补一句，装配顺序不用动。
     */
    public void markBotSide(Side side) {
        this.botSide = side;
    }

    @Override
    public void onPhaseChanged(MatchPhase from, MatchPhase to) {
        if (to == MatchPhase.BATTLE) {
            giveToAll();
        }
    }

    /**
     * ★★ <b>准备阶段开始</b>（2026-10-16 第八轮）：给每位<b>真人</b>发「选卡菜单」物品。
     *
     * <p>出处：{@code docs/02} §2 的 R15 / {@code docs/09} §5.4 ——
     * 「准备时间内玩家持有『选卡菜单』物品，<b>对任何位置右键</b>可以再次打开该 UI」。
     * ★ 只发 1 号格（2~9 留给确认后的 8 张卡；{@code docs/02} §6.2 的 R16/R18）。
     * ★ 人机不发（见 {@link #botSide}）。
     *
     * <p>★ 本方法<b>不</b>打开界面、也<b>不</b>传送 —— 那两件事分别在
     * {@code InfernalClashGame#onPrepPhaseStarted}（自动弹一次 U4）与
     * {@code ObjectiveService#teleportHumansToSpawns}（送到出生点）。
     * 拆开是为了让每一件事都只有一处落点（清单见 {@code MatchHooks#onPrepPhaseStarted}）。
     */
    @Override
    public void onPrepPhaseStarted() {
        int given = 0;
        for (Map.Entry<Side, UUID> entry : players.entrySet()) {
            if (entry.getKey() == botSide) {
                continue;   // ★ 人机不发（它没有 U4）
            }
            Player player = Bukkit.getPlayer(entry.getValue());
            if (player != null && player.isOnline()) {
                givePrepMenuItem(player);
                given++;
            }
        }
        if (given > 0) {
            plugin.getLogger().info("[IC] 准备阶段：已给 " + given
                    + " 名玩家发放「选卡菜单」物品（1 号格，右键任意位置重开 U4 选卡界面）");
        }
    }

    /**
     * ★★ <b>某一方确认了卡组 ⇒ 把 8 张按界面顺序放进 2~9 号格</b>（2026-10-16 第八轮）。
     *
     * <p>出处：{@code docs/02} §6.2 的 R16/R18「2~9 号格 = 选好的 8 张卡牌
     * （顺序 = 玩家在选卡 UI 里的排列）」。
     *
     * <p>★ 发牌实现只有 {@link #placeDeck} 一处（本方法只是它的对局侧调用点），
     * 所以「发到哪几格」不可能有两套口径。
     */
    @Override
    public void onDeckConfirmed(Side side, List<Card> deck) {
        UUID id = players.get(side);
        if (id == null) {
            plugin.getLogger().warning("[IC] 确认卡组：本局名册里没有这一方（" + side
                    + "），不发牌（fail-safe）");
            return;
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) {
            plugin.getLogger().warning("[IC] 确认卡组：" + side + " 的玩家不在线，不发牌（fail-safe）");
            return;
        }
        int placed = placeDeck(plugin, player, deck);
        plugin.getLogger().info("[IC] 准备阶段：" + player.getName() + "（" + side
                + "）已确认卡组，8 张卡放进 2~9 号格（实际放入 " + placed + " 张）"
                + "｜顺序 = 选卡界面里的排列（docs/02 §6.2 R16/R18）");
    }

    @Override
    public void onStop(String reason) {
        // ★★★ 2026-10-20（用户：「投降后 5 秒内 MGC 已经把大厅菜单发到手上，传送回来时又没了」）：
        //   收尾**只清我们自己的 PDC 物品**。旧实现无条件清空 0~8 格，
        //   而 MGC 的 `endGame → solveGameEnd → 切回大厅 profile` 在**结算那一刻**就把大厅钟
        //   （固定 inventory[8]）发出去了 ⇒ 5 秒后这一清就把它删了。
        clearOurItemsForAll(reason);
    }

    /** 给本局所有人发 1 号格「战斗」物品。 */
    public void giveToAll() {
        int given = 0;
        for (UUID id : players.values()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                giveBattleItem(player);
                given++;
            }
        }
        if (given > 0) {
            plugin.getLogger().info("[IC] 战斗开始：已给 " + given + " 名玩家发放「战斗」物品（1 号格）");
        }
    }

    /** 给一名玩家的 1 号格放「投降」物品（名字与 lore 都可由 {@code display.yml} 提供）。 */
    public void giveBattleItem(Player player) {
        player.getInventory().setItem(0, plugin.cardItems().battleItem(
                CardItems.battleItemName(),
                MenuTexts.resolveLore(displayOrNull(), KEY_SURRENDER_HINT,
                        CardItems.battleItemLore(), warner())));
    }

    /**
     * ★ 给一名玩家的 1 号格放「选卡菜单」物品（{@code docs/02} §2 的 R15）。
     *
     * <p>★ 名字与 lore 都走 {@code display.yml}（经
     * {@link MenuTexts#resolve}，取不到就回退 {@link CardItems} 里的常量并告警一次）
     * —— 本方法不硬编码任何玩家可见文本（R76）。
     */
    public void givePrepMenuItem(Player player) {
        player.getInventory().setItem(0, plugin.cardItems().prepMenuItem(
                MenuTexts.resolve(displayOrNull(), KEY_PREP_MENU, CardItems.prepMenuItemName(),
                        warner()),
                MenuTexts.resolveLore(displayOrNull(), KEY_PREP_MENU_LORE,
                        CardItems.prepMenuItemLore(), warner())));
    }

    /** {@code display.yml}（拿不到 ⇒ {@code null}，文案自动回退常量）。 */
    private DisplayConfig displayOrNull() {
        try {
            return plugin.display();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 文案取不到时的告警出口（{@link MenuTexts} 按键去重）。 */
    private java.util.function.Consumer<String> warner() {
        return msg -> plugin.getLogger().warning(msg);
    }

    /**
     * 把给定卡牌依次放进 2~9 号格（最多 8 张）。
     *
     * @return 实际放入的张数（超过 8 张的部分被忽略 —— 快捷栏只有 8 格）
     */
    public int giveDeck(Player player, List<Card> deck) {
        return placeDeck(plugin, player, deck);
    }

    /**
     * ★ 静态版本：**不依赖对局实例**（调试指令要能在没有对局时也把牌放进去）。
     *
     * <p>实例方法委托到它，保证「放牌」只有一处实现 —— 两份实现迟早会分叉。
     *
     * <h2>★ 2026-10-16 第八轮起它有两个生产调用点</h2>
     * <ol>
     *   <li>{@code /incl debug card fill}（调试发牌，第七轮起就有）；</li>
     *   <li>{@link #onDeckConfirmed} —— <b>玩家在 U4 里确认卡组</b> ⇒ 8 张落到 2~9 号格
     *       （{@code docs/02} §6.2 的 R16/R18）。</li>
     * </ol>
     * 两个调用点<b>共用这一处实现</b>：格号口径（2~9 = slot 1~8）不会分叉。
     */
    public static int placeDeck(InfernalClashPlugin plugin, Player player, List<Card> deck) {
        int slot = 1;
        int given = 0;
        // ★★ 2026-10-20（用户：「金卡召唤/释放时的金色粒子特效没有添加」）：
        //   金卡的**身份**以前只体现在名字与光效上，而这里固定走 `cardItem(card)`（= 普通卡）
        //   ⇒ 金卡一进快捷栏就变回普通卡，施放时自然也就没有金色粒子。
        //   ⇒ 这里按档案里的图鉴状态决定 `golden`（唯一读法：PlayerProgress.codexState）。
        //   ★ 失败方向：档案读不到 ⇒ 全部按**普通卡**发，绝不影响发牌（金卡只是少一层光效）。
        PlayerProgress progress = null;
        try {
            progress = plugin.profileStore().load(player.getUniqueId());
        } catch (Throwable t) {
            plugin.getLogger().warning("[IC] 发牌时读不到档案（本次按普通卡发，金卡外观会缺一层）：" + t);
        }
        for (Card card : deck == null ? List.<Card>of() : deck) {
            if (slot >= HOTBAR_SLOTS) {
                break;
            }
            boolean golden = progress != null
                    && PlayerProgress.CODEX_GOLD.equals(progress.codexState(card.id()));
            player.getInventory().setItem(slot, plugin.cardItems().cardItem(card, golden));
            // ★★ 2026-10-18：**清掉客户端残留的冷却遮罩**。
            //   服务端那张冷却表是 `MatchService` 的字段（新对局天然是空表），
            //   但 `Player#setCooldown(Material, ticks)` 的遮罩是**跨对局留在玩家身上**的
            //   ⇒ 上一局结尾还在转的卡，这一局一开局就会显示成"灰的"（看起来像冷却没清零）。
            //   ★ 每张卡一个唯一材质（2026-10-18 起），所以这里能精确到卡。
            try {
                // ★ 2026-10-18：判据收到 `CardItems#materialOf` 一处（原先这里也自己 match 一遍）
                player.setCooldown(io.mczju.infernalclash.card.CardItems.materialOf(card), 0);
            } catch (Throwable ignored) {
                // 清不掉遮罩不至于挡住发牌
            }
            slot++;
            given++;
        }
        return given;
    }

    /**
     * ★★★ <b>对局收尾：只清「本插件自己的」物品</b>（2026-10-20 修正；这是**现场缺陷**的落点）。
     *
     * <h2>用户原话（逐字）</h2>
     * > 「玩家投降后，在那等待的 5 秒内，mgc 的大厅菜单就已经发到了玩家手中，
     * > 但之后传送返回大厅时，这个大厅菜单又消失了」
     *
     * <h2>根因：收尾把**整条快捷栏 9 格**清空了</h2>
     * 终局序列是「先结算 → 5 秒后传送 + 清场」，而 MGC 在**结算那一刻**就已经
     * `endGame → solveGameEnd → 切回大厅 profile` ⇒ **大厅菜单钟（固定写在 `inventory[8]`）当场就发到手上**；
     * 5 秒后我们的 {@code onStop} 才跑 —— 旧的 {@code clearForAll()} 无条件
     * `setItem(0..8, null)` ⇒ **把 MGC 刚发的大厅钟一起删了**。
     *
     * <h2>现在的口径</h2>
     * 只清**带我们 PDC** 的（卡牌 / 战斗物品 / 选卡菜单物品），见 {@link #clearOurItems}：
     * 判据不靠材质、不靠格子位置 ⇒ MGC 的大厅钟、玩家自己的装备一律不碰。
     * ★ 旧的「9 格清空」两个方法已**删除**（而不是留着不用）——
     * 留着就会被后人当成"收尾的正确写法"再叫回来（本项目踩过"第二处白名单"的坑）。
     *
     * @param reason 收尾原因（只写日志）
     * @return 实际清掉的件数
     */
    public int clearOurItemsForAll(String reason) {
        CardItems items = plugin.cardItems();
        int removed = 0;
        int touched = 0;
        for (UUID id : players.values()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            int n = clearOurItems(player, items);
            if (n > 0) {
                touched++;
                removed += n;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("[IC] 收尾清物品（" + reason + "）：从 " + touched
                    + " 名玩家身上清掉本插件的物品 " + removed + " 件"
                    + "｜★ 只清带 PDC 的（MGC 的大厅钟 / 玩家装备一律不碰）");
        }
        return removed;
    }

    /**
     * ★★★ <b>只清「本插件自己的」物品</b>（卡牌 / 战斗物品 / 选卡菜单物品）—— 2026-10-19 新增。
     *
     * <h2>为什么需要它（现场缺陷）</h2>
     * 用户 2026-10-19 报：「**结束对局后再加入房间，上局留下的卡牌会进入物品栏**」。
     * 根因**不在我们的收尾**：{@code MatchHooks#onStop} 里的清物品确实跑了
     * （终局 5 秒窗口结束时），但 MGC 的 **profile 快照**是在那之前抓的
     * （{@code plugins/MCZJUGameCore/profiles/<uuid>.yml} 的 {@code main:} 里存着当时整条物品栏）
     * ⇒ 下一次 {@code /mgc join} 切回 {@code infernalclash} profile 时，它把**带牌的物品栏**又贴回来了。
     *
     * <h2>为什么按 PDC 逐件清、而不是把 9 格清空</h2>
     * <ul>
     *   <li>MGC 的 profile 快照里还有**它自己的**物品（例如大厅菜单钟）；
     *       整个清空会把别人的东西一起删掉（铁律 10 的"只动属于自己的"）；</li>
     *   <li>判据用 {@link CardItems} 的 PDC 标记（卡牌 id / battle / prep_menu），
     *       **不靠材质**（材质会被资源包/别的插件共用）。</li>
     * </ul>
     *
     * @return 实际清掉的件数（0 ⇒ 什么都没做，调用方不必打日志）
     */
    public static int clearOurItems(Player player, CardItems items) {
        if (player == null || items == null) {
            return 0;
        }
        int removed = 0;
        org.bukkit.inventory.ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            org.bukkit.inventory.ItemStack stack = contents[slot];
            if (stack == null) {
                continue;
            }
            if (items.cardIdOf(stack) != null || items.isBattleItem(stack) || items.isPrepMenuItem(stack)) {
                player.getInventory().setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }
}
