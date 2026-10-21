package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.policy.Rarity;
import io.mczju.infernalclash.profile.CraftRules;
import io.mczju.infernalclash.profile.PlayerProgress;
import io.mczju.infernalclash.profile.UnlockRules;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * ★★ <b>U3 解锁确认界面</b> —— 2026-10-17 批次 3 后半新增。
 *
 * <h2>它补的是哪一格</h2>
 * {@code docs/28} 第 27 条（用户裁定：「<b>做</b>（U3 解锁确认界面）『你提出的这个东西很好』」）
 * ＋ 第 33 条的四条追问（用户在 Q4 里把槽位定为 <b>12 / 14</b>）。
 * 在此之前，图鉴里点一张未拥有的卡**只回一句「这张卡还没有解锁」**
 * —— 玩家看得到价格却没有任何地方能花掉那笔烈焰粉。
 *
 * <h2>逐格布局（权威出处见 {@link UnlockConfirmLayout}）</h2>
 * 3 行 × 9 列；<b>卡牌展示 = 13</b>（中点，★ 我推出来的那一格，可被否决）、
 * <b>确认 = 12</b>、<b>取消 = 14</b>（用户 Q4 答复「b」）。
 *
 * <h2>★ 规则与界面严格分工（本项目的硬要求）</h2>
 * <ul>
 *   <li><b>能不能解锁、扣多少、新的进度长什么样</b> ⇒ 全部在
 *       {@link UnlockRules#attempt} 里（纯函数，5 条分支各有单测）；</li>
 *   <li><b>界面只做三件事</b>：把卡和价格画出来、把点击转成一次 {@code attempt}、
 *       把结果<b>原文念给玩家</b>（{@code outcome.message()}）—— 不在界面里再写一套措辞。</li>
 * </ul>
 * ⇒ 将来加「批量合成」，走的是同一个 {@code attempt}，不会出现两套规则。
 *
 * <h2>失败方向</h2>
 * 任何一步抛异常 ⇒ <b>只告警 + 关掉界面</b>，绝不冒回 MGC
 * （{@code ArenaEditMenu} 的同一条口径）；粉不够 / 已拥有 ⇒ 不关界面，
 * 让玩家看到那句话之后自己决定（取消按钮仍在原地）。
 */
public final class UnlockConfirmMenu extends InfernalMenu {

    /** 界面标题的配置键（{@code display.yml} 的 {@code messages.collection.unlock-title}）。 */
    static final String KEY_TITLE = "collection.unlock-title";

    /** 界面标题的**回退常量**。 */
    public static final String TITLE = "确认解锁";

    /** 确认句的配置键（{@code docs/09} §3.3 → {@code display.yml}）。 */
    public static final String KEY_CONFIRM = "collection.unlock-confirm";

    /** 确认按钮名（★ 与 U3 的槽位同批裁定，见 {@link UnlockConfirmLayout} 的 javadoc）。 */
    public static final String CONFIRM_BUTTON = "确认解锁";

    /** 取消按钮名。 */
    public static final String CANCEL_BUTTON = "取消";

    /** 取消按钮的回退材质（display.yml 没写图标时用它）。 */
    private static final Material CANCEL_MATERIAL = Material.RED_CONCRETE;

    /** 要解锁的卡 id（**由打开方注入**，见 {@link MenuRegistry#openUnlockConfirm}）。 */
    private final String cardId;

    /**
     * ★★★ <b>关掉本界面之后回到哪儿</b>（2026-10-21 新增；{@code args[1]}）。
     *
     * <h2>它补的是哪两条现场缺陷（用户逐字）</h2>
     * 「<b>合成卡牌界面点击”取消“后应该返回图鉴界面，而不是直接退出</b>」
     * ＋「<b>我在解锁过一张卡后，返回图鉴界面时，所有未解锁卡又会变成”？？？“</b>」。
     *
     * <p>★ 在这之前本类写死了两件事：取消 ⇒ {@code closeInventory()}（玩家被踢出界面）；
     * 回程 ⇒ {@code openCodexQuietly}（**U5 只读图鉴**）。
     * 而玩家真正在用的是 U2「图鉴与卡组」，且 U5 当时还留着 {@code "???"} 的旧画法
     * ⇒ 「解锁完一张，剩下全变回问号」。
     * ⇒ 现在回程 = **打开方传下来的 {@link UnlockReturn}**（U2 / U4 / U5，含页码与预设下标），
     * 取消与确认成功**共用同一条**回程（见 {@link #backToCodex()}）。
     */
    private final UnlockReturn returnTarget;

    public UnlockConfirmMenu(Player player, Object... args) {
        super(player, args);
        this.cardId = args != null && args.length > 0 && args[0] != null
                ? String.valueOf(args[0]) : null;
        this.returnTarget = UnlockReturn.fromArgs(args);
    }

    @Override
    protected String getTitle() {
        return MenuTexts.resolve(displayOrNull(), KEY_TITLE, TITLE, this::logWarning);
    }

    @Override
    protected int getRows() {
        return UnlockConfirmLayout.ROWS;
    }

    /** 要被解锁的那张卡（未知 id ⇒ {@code null}，界面会画一个「找不到」的格子）。 */
    private Card card() {
        return cardId == null ? null : cardOf(cardId);
    }

    /**
     * 这一档的合成价（拿不到 ⇒ 返回 {@code -1}，界面据此说"价格没配好"）。
     *
     * <p>★ 与 {@link UnlockRules} 用的是**同一个**读法（{@link CraftRules#dustToCraft}）——
     * 界面显示的数与真正扣的数必须同源，否则会出现"写着 40 却扣 100"。
     */
    private int priceOf(Card card) {
        if (card == null || card.rarity() == null) {
            return -1;
        }
        try {
            return CraftRules.dustToCraft(card.rarity(), false);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Override
    protected void setup() {
        try {
            Card card = card();
            int price = priceOf(card);
            renderCard(card);
            renderConfirm(card, price);
            renderCancel();
        } catch (Throwable t) {
            logWarning("装配 " + getClass().getSimpleName() + " 失败（界面会显示为空）：" + t);
        }
    }

    /** 中点的展示格：把「要解锁什么」摆出来（★ 价格写在确认格的 lore 里，那才是决策点）。 */
    private void renderCard(Card card) {
        if (card == null) {
            setSlot(UnlockConfirmLayout.CARD_SLOT,
                    icon(Material.BARRIER, CODEX_NAME_LOCKED, List.of("找不到这张卡（已记进日志）")));
            return;
        }
        // ★ 2026-10-19：这里也带风味 —— 它是"看一张卡"的详情位（图鉴点进来的二级界面），
        //   与图鉴格同一口径（`cardIcon(card, glint, withFlavor)`）。
        setSlot(UnlockConfirmLayout.CARD_SLOT, cardIcon(card, false, true));
    }

    /** 确认格：点击 ⇒ 走 {@link UnlockRules#attempt}（唯一判据）。 */
    private void renderConfirm(Card card, int price) {
        List<String> lore = new java.util.ArrayList<>(3);
        PlayerProgress progress = progress();
        if (card == null) {
            lore.add("这张卡读不出来");
        } else if (progress.owns(card.id())) {
            lore.add("你已经拥有这张卡");
        } else if (price < 0) {
            lore.add("这一档的价格没有配置好");
        } else {
            lore.add("价格：" + price + " 烈焰粉（你有 " + progress.dust() + "）");
            // ★★★ 2026-10-20（用户截图）：确认句里的 `{cost}` / `{card}` **没有被替换**，
            //   界面上直接显示成「是否确认花费 {cost} 烈焰粉解锁 {card}？」。
            //   根因：这里调的是**不带占位符**的那个 resolve 重载。
            //   ⇒ 改用带 Map 的重载（与商店购买按钮同一口径，占位符替换只有 MenuTexts 一处）。
            lore.add(MenuTexts.resolve(displayOrNull(), KEY_CONFIRM,
                    DEFAULT_CONFIRM_PROMPT,
                    java.util.Map.of(
                            "cost", String.valueOf(price),
                            "card", card.displayName()),
                    this::logWarning));
        }
        setSlot(UnlockConfirmLayout.CONFIRM_SLOT,
                icon(Material.LIME_CONCRETE, CONFIRM_BUTTON, lore),
                (who, event) -> confirm());
    }

    /**
     * 取消格：点击 ⇒ **回到打开本界面的那个图鉴界面**（同一页），**不动任何进度**。
     *
     * <p>★ 2026-10-21 用户裁定（逐字）：「**合成卡牌界面点击”取消“后应该返回图鉴界面，
     * 而不是直接退出**」⇒ 之前这里是 {@code closeInventory()}（= 玩家被踢出界面）。
     * <p>★ 动作走 {@link #onCancelClick} 而不是一个内联 lambda：那是一处可以被字节码测试
     * 钉住的接线点（{@code UnlockEntryWiringTest} 断言它与确认成功**共用**同一条回程）。
     */
    private void renderCancel() {
        setSlot(UnlockConfirmLayout.CANCEL_SLOT,
                icon(Material.RED_CONCRETE, CANCEL_BUTTON, List.of(CANCEL_LORE)),
                this::onCancelClick);
    }

    /** 取消按钮的 lore（★ 文案必须与行为一致：它现在**会**做一件事 —— 返回图鉴）。 */
    public static final String CANCEL_LORE = "返回图鉴（不动任何进度）";

    /**
     * 点了取消。
     *
     * <p>★ 只做一件事：回到图鉴（{@link #backToCodex()}）。**不扣粉、不写档案、不改图鉴** ——
     * 「取消 = 什么都不做」这半句仍然成立，只是不再把玩家踢出界面。
     */
    private void onCancelClick(com.github.mczjuops.mczjugamecore.player.PlayerExt who,
                               org.bukkit.event.inventory.InventoryClickEvent event) {
        logInfo("解锁确认：玩家 " + player.player().getName() + " 点了取消（未扣粉、未改档案），"
                + "返回 " + returnTarget.kind());
        backToCodex();
    }

    /** 确认句的**回退常量**（{@code display.yml} 的 {@code collection.unlock-confirm} 读不到时用它）。 */
    public static final String DEFAULT_CONFIRM_PROMPT = "是否确认花费 {cost} 烈焰粉解锁 {card}？";

    /**
     * 点了确认。
     *
     * <p>★ 顺序刻意是「<b>先算 → 再存盘 → 最后刷新</b>」：
     * 存盘失败时进度**没有**落盘，界面照实说「没能保存」，而不是先刷新成"已解锁"的样子。
     */
    private void confirm() {
        try {
            Card card = card();
            if (card == null) {
                tellError("找不到这张卡（已记进日志）");
                player.player().closeInventory();
                return;
            }
            PlayerProgress progress = progress();
            UnlockRules.Outcome outcome = UnlockRules.attempt(progress, card.id(), card.rarity());
            if (outcome.ok()) {
                InfernalClashPlugin instance = plugin();
                if (instance == null) {
                    tellError("插件不可用，没能保存（已记进日志）");
                    logWarning("解锁失败：插件实例不可用（卡 " + card.id() + "）");
                    player.player().closeInventory();
                    return;
                }
                instance.profileStore().save(player.player(), outcome.next());
                tellOk(outcome.message());
                logInfo("已解锁卡 " + card.id() + "（花费 " + outcome.cost() + " 烈焰粉）");
                // ★ 关掉之后**原地返回**打开本界面的那个图鉴（同一页 / 同一套预设）：
                //   玩家刚花掉一笔粉，必须立刻看见那张卡从「未解锁」变成真卡。
                //   ★ 2026-10-21：回程不再写死为 U5 只读图鉴 —— 见 backToCodex()。
                backToCodex();
                return;
            }
            tellError(outcome.message());
            if (outcome.status() == UnlockRules.Status.ALREADY_OWNED
                    || outcome.status() == UnlockRules.Status.NOT_CRAFTABLE) {
                // 已经没有可确认的东西了 ⇒ 关掉，别让玩家对着一个死界面继续点
                player.player().closeInventory();
            }
        } catch (Throwable t) {
            tellError("解锁时出错（已记进日志）");
            logWarning("解锁出错：" + t);
            player.player().closeInventory();
        }
    }

    /**
     * ★★★ <b>关掉本界面并「原地返回」当初打开它的那个图鉴界面</b>（2026-10-21 新增）。
     *
     * <h2>为什么取消与确认成功要共用这一个方法</h2>
     * 两处各写一份 ⇒ **第二处事实来源**。而本次的现场缺陷（「解锁完一张，其余全变问号」）
     * 恰恰就是"回程与打开方不匹配"造成的：从 U2 进来，却回到 U5。
     * ⇒ 回程只有这一处实现，目标由 {@link UnlockReturn} 决定（{@code MenuRegistry#reopenCodex} 分发）。
     *
     * <p>★ <b>顺序</b>：先关（{@code closeInventory}）再开 —— 与改动前的成功路径同形
     * （MGC 的菜单门面在关界面时会走 {@code handleClose}，先开新界面会被这次关闭连带清掉）。
     * ★ <b>失败方向</b>：关不掉 ⇒ 只告警，仍然尝试打开目标界面（玩家至少不会被卡住）。
     */
    private void backToCodex() {
        try {
            player.player().closeInventory();
        } catch (Throwable t) {
            logWarning("关掉解锁确认界面失败（继续尝试回到图鉴）：" + t);
        }
        if (!MenuRegistry.reopenCodex(player.player(), returnTarget)) {
            // 打开失败时 canOpen 已经给过玩家一句话（权限被撤 / 插件未就绪）⇒ 这里只留痕
            logWarning("回到图鉴失败（目标 " + returnTarget.kind() + "，页码 " + returnTarget.page() + "）");
        }
    }
}
