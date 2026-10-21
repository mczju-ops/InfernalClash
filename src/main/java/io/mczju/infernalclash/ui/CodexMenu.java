package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.profile.PlayerProgress;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * ★ <b>U5 图鉴（只读）</b>—— 逐格布局照抄 {@code docs/09} §3.2 / §5.2 的「卡牌图鉴 27 格」那一段。
 *
 * <h2>为什么它可以和 {@link DeckPresetMenu} 共用同一段槽位</h2>
 * {@code docs/09} §5.2 明写：「★ 翻页槽 {@code 45} / {@code 53} 与 U2 图鉴界面（§3.2）**完全一致**
 * ⇒ 两个界面可共用同一套 {@code Layout}（纯逻辑分页 + nav 槽常量）」。
 * ⇒ 只读版（本类）与可编辑版（{@link DeckPresetMenu}）**槽位算术完全相同**，
 * 区别只在：可编辑版多出「卡位 + 保存预设」，本类<b>一个写操作都没有</b>。
 *
 * <h2>逐格布局（{@code docs/09} §3.2 / §5.2）</h2>
 * <table border="1">
 *   <caption>本界面放东西的格（其余格一律不放）</caption>
 *   <tr><th>槽位</th><th>内容</th><th>出处</th></tr>
 *   <tr><td>{@code 18}~{@code 44}</td><td>卡牌图鉴 27 格（每页 3 行 × 9）</td>
 *       <td>§3.2 / §5.2「卡牌图鉴 <b>27</b> 格」</td></tr>
 *   <tr><td>{@code 45} / {@code 53}</td><td>前一页 / 后一页</td><td>§3.2 第 6 行最左 / 最右</td></tr>
 * </table>
 *
 * <h2>★ 三态（{@code docs/08} §3.3，【Q31】单条目 + 品质标记）</h2>
 * <ul>
 *   <li>{@code NONE}（未拥有）⇒ <b>黑色混凝土 + 真名 + 真效果</b>（{@code lockedCardIcon}）。
 *       ★ 2026-10-21 更正：这里此前走的是旧的 {@code lockedIcon()}（{@code "???"}），
 *       而 U2 / U4 早已按用户裁定改成「只有图标是黑色混凝土、名字与效果照常显示」
 *       ⇒ 从 U2 解锁完一张卡被弹回本界面时，**一屏全是问号**
 *       （用户原话：「解锁过一张卡后，返回图鉴界面时，所有未解锁卡又会变成”？？？“」）。
 *       现在三个图鉴族界面（U2 / U4 / U5）画未解锁卡**只有一处实现**；
 *   <li>{@code NORMAL} ⇒ 正常卡牌图标（复用 {@code CardItems.cardItem}）；</li>
 *   <li>{@code GOLD}（拥有金色版）⇒ 同一张卡 + <b>附魔光效</b>（R53 的「名称颜色金色」由卡牌配置承担，
 *       这里不造任何文案）。</li>
 * </ul>
 * ★ 三态一律读 {@code PlayerProgress.codexState(cardId)}（唯一的读法，见其 javadoc：「缺席 = NONE」）。
 *
 * <h2>★ 只读（2026-10-17 起：**已拥有**的卡仍然只读）</h2>
 * 已拥有的卡片格**不挂点击动作**（MGC 的门面本来就会取消点击）——
 * 「点已拥有的卡 ⇒ 放进卡组」属于 U2 可编辑那份（{@link DeckPresetMenu}）。
 * ★ 但**未拥有**的卡现在有动作了：点它 ⇒ {@link UnlockConfirmMenu}（U3），
 * 依据是 {@code docs/28} 第 27 条的用户裁定「<b>做</b>」＋ 第 33 条第②问的四条追问
 * （槽位 12 / 14，见 {@link UnlockConfirmLayout}）。
 *
 * <h2>★ 文案（P8「文案接线」已落地）</h2>
 * 标题经 {@link MenuTexts} 读 {@code display.yml} 的 {@code messages.collection.title}，
 * 读不到就回退常量（并告警一次）；翻页名仍是 {@link InfernalMenu#CODEX_BUTTON_PREV} /
 * {@link InfernalMenu#CODEX_BUTTON_NEXT}（它们对应 {@code icons.collection.*.name}，另一组键）。
 */
public final class CodexMenu extends InfernalMenu {

    /** 界面标题的配置键（{@code display.yml} 的 {@code messages.collection.title}）。 */
    static final String KEY_TITLE = "collection.title";

    /**
     * 界面标题的**回退常量**「图鉴与卡组」。
     *
     * <p>出处：{@code docs/09} §3 的界面名「U2 图鉴 + 卡组预设」——
     * 只读图鉴与可编辑那份是**同一个界面的两半**（§5.2「两个界面可共用同一套 Layout」）
     * ⇒ 标题共用同一个键，不另造一个名字。
     * ★ 正常路径读 {@link #KEY_TITLE}（与 {@link DeckPresetMenu#KEY_TITLE} 是同一把键）。
     */
    public static final String TITLE = "图鉴与卡组";

    /** 图鉴当前页（0 起）。 */
    private int page;

    /** 见 {@link DeckPresetMenu} 的同名字段：{@code Menu.refresh()} 会**再调一次 setup()**。 */
    private boolean initialized;

    /**
     * ★ <b>无副作用构造</b>：MGC 用反射 {@code (Player, Object...)} 造它，装配全部在 {@link #setup()}。
     *
     * @param player 打开界面的玩家
     * @param args   本界面当前不用参数
     */
    public CodexMenu(Player player, Object... args) {
        super(player, args);
    }

    /** 标题（★ 构造期就会被 MGC 调用 ⇒ 只碰静态单例；文案来源 = {@code display.yml}）。 */
    @Override
    protected String getTitle() {
        return title(displayOrNull(), this::logWarning);
    }

    /**
     * ★ <b>可离线单测的那一半</b>：标题的键 / 回退常量 / 告警出口都在这里，
     * 界面走的正是它（{@link MenuTexts#resolve}：读不到 ⇒ {@link #TITLE} + 告警一次）。
     */
    static String title(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_TITLE, TITLE, warner);
    }

    /** 行数（★ 同上）：{@code docs/09} §3.2「6 行 = 54 格」。 */
    @Override
    protected int getRows() {
        return MenuLayout.SELECT_ROWS;
    }

    @Override
    protected void setup() {
        try {
            if (!initialized) {
                page = readPage();
                initialized = true;
            }
            renderCodexPage();
            renderPageButtons();
        } catch (Throwable t) {
            // ★ 绝不让异常冒回 MGC
            logWarning("装配 " + getClass().getSimpleName() + " 失败（界面会显示为空）：" + t);
        }
    }

    /**
     * ★ <b>读「图鉴停在第几页」</b>（{@code args[0]}，2026-10-21 新增）。
     *
     * <p>★ 为什么必须有：{@code MenuRegistry#openCodexQuietly(player, page)} 一直在传页码，
     * 而本类此前**根本不读参数**（{@code page} 永远从 0 开始）⇒ 「解锁成功后重开到同一页」
     * 这句注释在 U5 这条路上一直是假的（玩家被弹回第 1 页，看不到刚买的那张卡）。
     * 缺参数 ⇒ 第 1 页；不是数字 ⇒ 第 1 页 + 一条 WARN（构造期绝不抛）。
     */
    private int readPage() {
        Object[] args = getArgs();
        if (args == null || args.length == 0 || args[0] == null) {
            return 0;
        }
        if (args[0] instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        logWarning("图鉴页码不是数字（" + args[0] + "），退回第 1 页");
        return 0;
    }

    private void renderCodexPage() {
        List<Card> cards = allCards();
        int pageSize = MenuRegistry.codexPageSize();
        page = MenuRegistry.clampPage(page, MenuRegistry.pageCount(cards.size(), pageSize));
        int start = MenuRegistry.pageStart(page, pageSize, cards.size());
        int end = MenuRegistry.pageEnd(page, pageSize, cards.size());
        PlayerProgress progress = progress();

        for (int index = start; index < end; index++) {
            Card card = cards.get(index);
            int slot = MenuLayout.codexSlot(index - start);
            String state = progress.codexState(card.id());
            if (PlayerProgress.CODEX_NONE.equals(state)) {
                // ★★★ 2026-10-17 批次 3 后半（U3，docs/28 第 27 条用户裁定「做」）：
                //   点**未拥有**的卡 ⇒ 弹解锁确认界面。
                //   ★ 在此之前这里只回一句「这张卡还没有解锁」（本类旧 javadoc 的那句话），
                //     玩家看得到价格却没有任何地方能花掉那笔烈焰粉。
                //   ★ 2026-10-21：回程目标**由本界面注入**（U5 + 当前页）——
                //     从哪个图鉴进来就回哪个图鉴（见 {@link UnlockReturn}）。
                final int unlockPage = page;
                setSlot(slot, lockedCardIcon(card), (who, event) ->
                        MenuRegistry.openUnlockConfirm(who.player(), card.id(),
                                UnlockReturn.codex(unlockPage)));
            } else {
                // ★★★ 2026-10-19（用户逐字）：「现在游戏内**完全看不到卡牌的风味描述**，
                //   请你在**图鉴界面**加上」⇒ 图鉴格带风味（lore 末尾一行灰色斜体）。
                //   ★ 其它界面（选卡 U4 / 战斗快捷栏）仍是 `cardIcon(card, glint)`（不带风味）。
                setSlot(slot, cardIcon(card, PlayerProgress.CODEX_GOLD.equals(state), true));
            }
        }
    }

    private void renderPageButtons() {
        setSlot(MenuLayout.PREV_PAGE_SLOT,
                icon("collection.prev", Material.ARROW, CODEX_BUTTON_PREV, null),
                (who, event) -> {
                    if (page > 0) {
                        page--;
                        refresh();
                    }
                });
        setSlot(MenuLayout.NEXT_PAGE_SLOT,
                icon("collection.next", Material.ARROW, CODEX_BUTTON_NEXT, null),
                (who, event) -> {
                    int pages = MenuRegistry.pageCount(allCards().size(), MenuRegistry.codexPageSize());
                    if (page < pages - 1) {
                        page++;
                        refresh();
                    }
                });
    }
}
