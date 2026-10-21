package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.policy.PrepSession;
import io.mczju.infernalclash.profile.PlayerProgress;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ★★ <b>U4「比赛内选卡」界面</b>（= 准备阶段的选择 / 现搭卡组界面）——
 * 2026-10-16 第八轮新增；逐格布局<b>严格</b>照 {@code docs/09} §5.2（【Q19】定案的 6 行）。
 *
 * <h2>逐格布局（与 {@code docs/09} §5.2 逐格对照）</h2>
 * <table border="1">
 *   <caption>本界面放东西的格（其余格一律不放 = 空）</caption>
 *   <tr><th>槽位</th><th>内容</th><th>{@code docs/09} 出处</th></tr>
 *   <tr><td>{@code 0}</td><td><b>确认卡组</b>按钮</td><td>§5.2 第 1 行第 1 格</td></tr>
 *   <tr><td>{@code 1}~{@code 8}</td><td><b>8 个空卡槽</b>（【Q38】真的空卡槽，当场构建）</td>
 *       <td>§5.2 第 1 行「{@code 1}~{@code 8}」＋ §5.5 读法 B</td></tr>
 *   <tr><td>{@code 11} / {@code 13} / {@code 15}</td>
 *       <td><b>三个预设按钮</b>（每个<b>只显示</b>该预设的第一张卡；【Q12】上限 3）</td>
 *       <td>§5.2 第 2 行 ＋【2026-10-15 用户给定确切栏位：3 / 5 / 7（1 起数）】</td></tr>
 *   <tr><td>{@code 18}~{@code 44}</td><td><b>卡牌图鉴 27 格</b>（3 行 × 9）</td>
 *       <td>§5.2 第 3~5 行（【Q20】：原文「卡牌途径」= 卡牌图鉴）</td></tr>
 *   <tr><td>{@code 45} / {@code 53}</td><td><b>前一页 / 后一页</b></td><td>§5.2 第 6 行最左 / 最右</td></tr>
 * </table>
 * ★ <b>一个格都不多、一个格都不少</b>：槽位算术全部经 {@link MenuLayout}
 * （{@code cardSlot} / {@code presetSlot} / {@code codexSlot} / {@code PREV_PAGE_SLOT} /
 * {@code NEXT_PAGE_SLOT}）—— 界面里没有第二处格号。
 * ★ 第 2 行的其余 6 格（{@code 9} / {@code 10} / {@code 12} / {@code 14} / {@code 16} / {@code 17}）
 * 与第 6 行的 {@code 46}~{@code 52} 在 §5.2 里是「空」⇒ <b>不放任何东西</b>
 * （§5.2 只让 {@code 46}~{@code 52}「可放页码 / 提示」，那是"可"不是"必须"；
 * 本轮用 <b>物品 lore</b> 承载那句操作提示，不动第 6 行）。
 *
 * <h2>★ 交互（照抄文档，不自创）</h2>
 * <ul>
 *   <li>图鉴里点一张卡 ⇒ <b>选中</b>（✅【Q35】「点选中 → 再点卡位」）——
 *       未拥有的卡显示 {@code "???"}（§3.3）且点不动；</li>
 *   <li>点 8 个卡槽之一 ⇒ 把选中的卡放进去（替换空格或已有卡；未选中时提示「先点一张卡」）；</li>
 *   <li>点 <b>确认卡组</b> ⇒ 走 {@link MatchService#confirmDeck}
 *       （<b>三道门</b>在纯状态机 {@link PrepSession} 里：在会话中 / 没确认过 / <b>正好 8 张</b>）
 *       ⇒ 成功则 8 张落到 2~9 号格（{@code CardLoadout.placeDeck}）并关闭界面；</li>
 *   <li>点预设按钮 ⇒ 把该套预设灌进 8 个卡槽（预设只是<b>快捷填充来源</b>，§5.5 读法 B）。</li>
 * </ul>
 *
 * <h2>★★ 只认准备阶段（【Q37】）</h2>
 * 【Q37】原话：「是准备阶段的选择卡组/现搭卡组的界面，<b>只在准备阶段出现</b>。
 * <b>战斗中不允许换卡</b>」⇒ {@link #setup()} 在「不在准备阶段」时<b>什么都不渲染</b>，
 * 并告诉玩家为什么；确认按钮同样会被 {@link MatchService#confirmDeck} 拒掉
 * （纯状态机里确认过就不能再改）。
 * ★ 因此本界面<b>不是</b>「战斗中换卡」的后门。
 *
 * <h2>★ 文案</h2>
 * 标题 / 确认按钮 / 提示行都读 {@code display.yml}（{@code match.select-title} /
 * {@code match.confirm} / {@code match.select-hint}），读不到就回退本类的常量并告警一次。
 *
 * <h2>★ 铁律 10</h2>
 * 只读<b>该玩家自己</b>的进度（{@code ProfileStore}）与<b>本局</b>的选卡会话；
 * 没有 {@code getOnlinePlayers} / {@code getEntities} / {@code getWorlds}。
 */
public final class MatchDeckMenu extends InfernalMenu {

    /** 界面标题的键（{@code display.yml} 的 {@code messages.match.select-title}）。 */
    static final String KEY_TITLE = "match.select-title";

    /** 「确认卡组」按钮名的键（{@code messages.match.confirm}）。 */
    static final String KEY_CONFIRM = "match.confirm";

    /** 操作提示行的键（{@code messages.match.select-hint}）。 */
    static final String KEY_HINT = "match.select-hint";

    /** 标题的**回退常量**（出处：{@code docs/09} §5 的界面名「比赛内选卡」＋ §5.2 的第 1 行）。 */
    public static final String TITLE = "选择卡组";

    /** 确认按钮的**回退常量**（出处：{@code docs/09} §5.2 槽位 {@code 0}「确认卡组」）。 */
    public static final String BUTTON_CONFIRM = "确认卡组";

    /** 操作提示的**回退常量**（出处：§3.3【Q35】点选中→再点卡位 ＋ §5.5【Q38】真的空卡槽）。 */
    public static final String HINT = "图鉴里点一张卡 → 再点上面 8 个空卡槽之一放入；"
            + "放满 8 张后点「确认卡组」";

    /** 没选卡就点卡槽时的提示（★ 与 {@code DeckPresetMenu#HINT_PICK_CARD} 同义，各自持有一份常量）。 */
    public static final String HINT_PICK_CARD = "请先在图鉴里点一张卡，再点这个卡槽。";

    /** 确认时卡组不满 8 张的提示（{@code %d} = 已放几张 / 共 8 张）。 */
    public static final String HINT_NOT_ENOUGH = "还差 %d 张：卡组必须正好 8 张才能确认。";

    /** 确认失败（已经确认过了 / 已不在准备阶段 / 这一方不在会话里）的提示。 */
    public static final String HINT_ALREADY = "这一局你的卡组已经确认过了（战斗中不允许换卡）。";

    /** 不在准备阶段时打开界面的提示（【Q37】）。 */
    public static final String HINT_NOT_PREPARING = "选卡界面只在准备阶段可用（战斗中不允许换卡）。";

    /** 找不到本局运行时 / 拿不到选卡会话时的提示（fail-safe，绝不静默）。 */
    public static final String HINT_NO_MATCH = "找不到本局的选卡会话（界面暂时不可用，原因见控制台日志）。";

    /** 未拥有的卡：点不动（U3 解锁界面仍未实现，见 {@code docs/05} E11 ②）。 */
    /**
     * 未拥有的卡被点时的**兜底**提示（★ 2026-10-20 起语义变了）。
     *
     * <p>以前它是"点击的唯一后果"；现在点击会直接打开 U3 解锁确认界面
     * （{@code MenuRegistry#openUnlockConfirm}），只有**打开失败**时才回这一句
     * ⇒ 文案不再说"U3 未实现"（界面早在，缺的只是入口）。
     */
    public static final String HINT_LOCKED = "这张卡还没有解锁（解锁界面打不开，原因见控制台日志）。";

    /**
     * ★★★ <b>这张卡已经在卡组里了</b>（2026-10-19 用户裁定：「卡组不能出现重复的牌」）。
     *
     * <p>★ 为什么必须拦（不只是"不好看"）：冷却表 {@code policy/CardCooldowns} 是
     * <b>按卡 id</b> 记的 ⇒ 同一张卡放两格 = 两张牌**共用一条冷却**，
     * 打出其中一张另一张也变灰；而 {@code CardItems} 的材质/PDC 也完全相同 ⇒ 玩家根本分不清。
     * ★ 判据本体在 {@link PrepSession}（纯函数、离线可测），本类**不重复实现规则**。
     */
    public static final String HINT_DUPLICATE = "这张卡已经在卡组里了（卡组不能放两张同样的牌）。";

    /** 确认成功后的提示（{@code %d} = 张数）。 */
    public static final String HINT_CONFIRMED = "卡组已确认（%d 张）—— 准备阶段仍在计时，到点自动进战斗。";

    /** 预设按钮的 lore（{@code %d} ×2 = 第几套 / 上限；{@code %s} = 第一张卡的名字或「空」）。 */
    public static final String LORE_PRESET = "第 %d / %d 套｜第一张：%s";

    /** 卡槽的 lore（{@code %d} = 第几格，1 起）。 */
    public static final String LORE_SLOT = "第 %d 个卡槽";

    /** 卡槽为空时的 lore 尾注（【Q38】真的空卡槽）。 */
    public static final String LORE_SLOT_EMPTY = "（空：从下面的图鉴里点一张卡放进来）";

    /** 本局运行时的 matchId（{@code args[0]}；拿不到 ⇒ {@code null} ⇒ 界面按「不可用」渲染）。 */
    private String matchId;

    /** 图鉴当前页（0 起）。 */
    private int page;

    /** 已选中但还没放进卡槽的卡 id（{@code null} = 没选中）。 */
    private String selected;

    /**
     * 是否已经装配过一次 —— {@code Menu.refresh()} 会 {@code clearMenu()} 后**再调一次
     * {@link #setup()}（{@code javap} 实测，与 {@link DeckPresetMenu} 同一个坑）⇒
     * {@code matchId} 只能在第一次解析，翻页不能把它冲掉。
     */
    private boolean initialized;

    /**
     * ★ <b>无副作用构造</b>（MGC 用反射 {@code (Player, Object...)} 造它；装配全部在 {@link #setup()}）。
     *
     * @param player 打开界面的玩家
     * @param args   {@code args[0]} = 本局的 {@code matchId}（{@link MenuRegistry#openDeckPicker} 会传）
     */
    public MatchDeckMenu(Player player, Object... args) {
        super(player, args);
    }

    /** 标题（★ 构造期就会被 MGC 调用 ⇒ 只碰静态单例）。 */
    @Override
    protected String getTitle() {
        return title(displayOrNull(), this::logWarning);
    }

    /** ★ <b>可离线单测的那一半</b>：标题的键 / 回退常量 / 告警出口。 */
    static String title(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_TITLE, TITLE, warner);
    }

    /** ★ <b>可离线单测的那一半</b>：确认按钮名。 */
    static String confirmName(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_CONFIRM, BUTTON_CONFIRM, warner);
    }

    /** ★ <b>可离线单测的那一半</b>：操作提示行。 */
    static String hint(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_HINT, HINT, warner);
    }

    /** 行数（★ 只许返回静态常量）：{@code docs/09} §5.2 定案 6 行（【Q19】）。 */
    @Override
    protected int getRows() {
        return MenuRegistry.matchDeckRows();
    }

    /**
     * ★★ <b>本界面占用的槽位清单</b>（= {@link MenuRegistry#matchDeckItemSlots()}）。
     *
     * <p>★ 为什么界面要暴露它：{@code MenuRegistryTest} 要能离线断言
     * 「界面占的格 == {@code docs/09} §5.2 点名的格」。若界面自己再列一遍，
     * 那就是**第二处事实来源**（迟早与 {@code MenuRegistry} 分叉）。
     * ⇒ 界面与测试读的是**同一张**清单。
     */
    public static List<Integer> occupiedSlots() {
        return MenuRegistry.matchDeckItemSlots();
    }

    @Override
    protected void setup() {
        try {
            if (!initialized) {
                matchId = readMatchId();
                page = readPage();
                initialized = true;
            }
            render();
        } catch (Throwable t) {
            // ★ 绝不让异常冒回 MGC：一次渲染异常会把该事件的所有监听器一起炸掉
            logWarning("装配 " + getClass().getSimpleName() + " 失败（界面会显示为空）：" + t);
        }
    }

    /** 【Q35】补充裁定：关闭界面时取消「尚未放进卡槽的选中卡」。 */
    @Override
    public void handleClose() {
        selected = null;
        super.handleClose();
    }

    // ------------------------------------------------------------------ 参数 / 会话

    /**
     * 读 {@code args[0]} = 本局 matchId。
     *
     * <p>★ 为什么由调用方传而不是「按玩家现查」：一名玩家可能在多场里（【Q44】多场并发），
     * 而「打开的是哪一场」只有打开的那一刻知道 ——
     * {@link MenuRegistry#openDeckPicker} 就是那个调用方。
     */
    private String readMatchId() {
        Object[] args = getArgs();
        if (args == null || args.length == 0 || args[0] == null) {
            logWarning("打开选卡界面时没有传 matchId（args 为空），界面按「不可用」渲染");
            return null;
        }
        return String.valueOf(args[0]).trim();
    }

    /**
     * ★ <b>读「图鉴停在第几页」</b>（{@code args[1]}，2026-10-21 新增）。
     *
     * <p>★ 为什么需要它：U4 里点未拥有的卡也会打开 U3 解锁确认界面，
     * 而回程必须「原地返回」（{@link UnlockReturn#matchDeck(String, int)}）——
     * 页码不复原 ⇒ 玩家解锁完会发现自己被弹回第 1 页。
     * 缺参数（{@code openDeckPicker(player, matchId)} / MGC 通用通道）⇒ 第 1 页。
     */
    private int readPage() {
        Object[] args = getArgs();
        if (args == null || args.length < 2 || args[1] == null) {
            return 0;
        }
        if (args[1] instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        logWarning("选卡界面的图鉴页码不是数字（" + args[1] + "），退回第 1 页");
        return 0;
    }

    /** 本局运行时（没有 ⇒ {@code null}）。 */
    private MatchService match() {
        return matchId == null ? null : MatchService.get(matchId);
    }

    /** 本局的选卡会话（没有 ⇒ {@code null}）。 */
    private PrepSession prep() {
        MatchService service = match();
        return service == null ? null : service.prep();
    }

    /** 本玩家在这一局里的阵营（不在 / 人机那一侧 ⇒ {@code null}）。 */
    private Side side() {
        MatchService service = match();
        return service == null ? null : service.sideOf(player.player().getUniqueId());
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        MatchService service = match();
        PrepSession session = prep();
        Side side = side();
        if (service == null || session == null || side == null || !service.usesDeckPicker(side)) {
            // fail-safe：拿不到本局 / 阵营（或这一方本来就不该用选卡界面）
            logWarning("选卡界面不可用：matchId=" + matchId + "｜picked=" + (side != null)
                    + "｜会话=" + (session != null));
            return;
        }
        if (service.state().phase() != MatchPhase.PREPARING) {
            // 【Q37】只在准备阶段出现；战斗中不允许换卡
            tellError(HINT_NOT_PREPARING);
            return;
        }
        renderConfirm(service, side);
        renderDeckSlots(session, side);
        renderPresets(session, side);
        renderCodexPage();
        renderPageButtons();
    }

    /** 槽位 {@code 0}：确认卡组（{@code docs/09} §5.2 第 1 行第 1 格）。 */
    private void renderConfirm(MatchService service, Side side) {
        boolean confirmed = service.prepConfirmed(side);
        List<String> lore = new ArrayList<>();
        lore.add(hint(displayOrNull(), this::logWarning));
        lore.add(hintPlain(confirmed));
        setSlot(MenuLayout.CONFIRM_DECK_SLOT,
                icon(Material.LIME_CONCRETE, confirmName(displayOrNull(), this::logWarning), lore),
                (who, event) -> confirm(service, side));
    }

    /** 确认按钮的第二行 lore（**纯字符串**，不读配置 —— 它与"当前状态"绑定）。 */
    private String hintPlain(boolean confirmed) {
        return confirmed ? HINT_ALREADY : HINT;
    }

    /** 槽位 {@code 1}~{@code 8}：8 个空卡槽（【Q38】真的空卡槽）。 */
    private void renderDeckSlots(PrepSession session, Side side) {
        List<String> draft = session.draft(side);
        for (int index = 0; index < MenuLayout.DECK_SIZE; index++) {
            int slot = MenuLayout.cardSlot(index);
            String cardId = index < draft.size() ? draft.get(index) : "";
            Card card = cardOf(cardId);
            ItemStack item;
            if (card != null) {
                item = cardIcon(card);
            } else if (cardId != null && !cardId.isBlank()) {
                // 存档 / 会话里允许出现卡池已经没有的 id（如实显示，不静默吞掉）
                item = icon(Material.BARRIER, cardId, null);
            } else {
                // ★【Q38】的同类口径：卡槽可以是**真空格** ⇒ 不放占位物品，只挂点击动作
                item = new ItemStack(Material.AIR);
            }
            final int target = index;
            setSlot(slot, item, (who, event) -> putSelected(session, side, target));
        }
    }

    /** 槽位 {@code 11} / {@code 13} / {@code 15}：三个预设按钮（只显示第一张卡）。 */
    private void renderPresets(PrepSession session, Side side) {
        PlayerProgress progress = progress();
        for (int preset = 0; preset < MenuLayout.PRESET_LIMIT; preset++) {
            int slot = MenuLayout.presetSlot(preset, 0);
            if (slot < 0) {
                continue;   // ★ MenuLayout 对「只显示第一张卡」之外的请求返回 -1（文档未给槽位）
            }
            List<String> cards = preset < progress.presets().size()
                    ? progress.presets().get(preset) : List.of();
            String first = cards.isEmpty() ? "" : cards.get(0);
            Card firstCard = cardOf(first);
            String firstName = firstCard != null
                    ? firstCard.displayName()
                    : (first.isBlank() ? "空" : first);
            List<String> lore = List.of(
                    String.format(LORE_PRESET, preset + 1, MenuLayout.PRESET_LIMIT, firstName));
            ItemStack item = firstCard != null
                    ? cardIcon(firstCard)
                    : icon(Material.GRAY_DYE, "预设 " + (preset + 1), lore);
            final int index = preset;
            setSlot(slot, item, (who, event) -> loadPreset(session, side, index, cards));
        }
    }

    /** 槽位 {@code 18}~{@code 44}：卡牌图鉴 27 格。 */
    private void renderCodexPage() {
        List<Card> cards = allCards();
        int pageSize = MenuRegistry.codexPageSize();
        int pages = MenuRegistry.pageCount(cards.size(), pageSize);
        page = MenuRegistry.clampPage(page, pages);
        int start = MenuRegistry.pageStart(page, pageSize, cards.size());
        int end = MenuRegistry.pageEnd(page, pageSize, cards.size());
        PlayerProgress progress = progress();

        for (int index = start; index < end; index++) {
            Card card = cards.get(index);
            int slot = MenuLayout.codexSlot(index - start);
            String state = progress.codexState(card.id());
            boolean owned = !PlayerProgress.CODEX_NONE.equals(state);
            boolean gold = PlayerProgress.CODEX_GOLD.equals(state);
            ItemStack item = owned ? cardIcon(card, gold) : lockedCardIcon(card);
            if (owned && card.id().equals(selected)) {
                item = cardIcon(card, true);   // 选中的那张用光效标出来（不动文案）
            }
            final String cardId = card.id();
            final int unlockPage = page;
            final String unlockMatch = matchId;
            setSlot(slot, item, (who, event) -> {
                // ★★★ 2026-10-20（用户：「卡牌解锁界面未实现，因此卡牌无法用烈焰粉解锁」）：
                //   以前这里不论拥有与否都走 selectCard，而未拥有的分支只回一句「U3 未实现」。
                //   U3（ui/UnlockConfirmMenu）其实早就有，缺的是**入口** ⇒ 现在直接打开它。
                // ★★★ 2026-10-21：回程目标由本界面注入（U4 + 本局 matchId + 当前页）——
                //   从哪个图鉴进来就回哪个图鉴（见 {@link UnlockReturn}）。
                if (owned) {
                    selectCard(cardId);
                } else if (!MenuRegistry.openUnlockConfirm(who.player(), cardId,
                        UnlockReturn.matchDeck(unlockMatch, unlockPage))) {
                    tell(HINT_LOCKED);
                }
            });
        }
    }

    /** 槽位 {@code 45} / {@code 53}：前一页 / 后一页（与 U2 图鉴共用同一段槽位）。 */
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

    // ------------------------------------------------------------------ 交互

    /**
     * 点图鉴里**已拥有**的一张卡 ⇒ 选中（✅【Q35】）。
     *
     * <p>★ 2026-10-20 起「未拥有」不再走这里：点击处直接打开解锁确认界面 U3
     * （见 {@code renderCodexPage} 的注释）。
     */
    private void selectCard(String cardId) {
        if (prep() == null) {
            tellError(HINT_NO_MATCH);
            return;
        }
        selected = cardId;
        refresh();   // 只为了把「选中」标出来（光效），不动任何文案
    }

    /** 点一个卡槽：把选中的卡放进去（替换空格或已有卡）。 */
    private void putSelected(PrepSession session, Side side, int index) {
        if (session == null) {
            tellError(HINT_NO_MATCH);
            return;
        }
        if (selected == null) {
            tell(HINT_PICK_CARD);
            return;
        }
        if (session.isConfirmed(side)) {
            tellError(HINT_ALREADY);
            return;
        }
        // ★★ 2026-10-19（用户：「卡组不能出现重复的牌」）：判据本体在 PrepSession，
        //   这里只负责**说清是哪一条**（`setDraftCard` 返回 false 的两种原因要分开提示，
        //   否则"点了没反应"会被当成界面坏了）。
        if (session.draftHasElsewhere(side, selected, index)) {
            tellError(HINT_DUPLICATE);
            return;
        }
        if (session.setDraftCard(side, index, selected)) {
            selected = null;
            refresh();
        } else {
            tellError(HINT_ALREADY);
        }
    }

    /** 点一个预设按钮：把该套预设灌进 8 个卡槽（预设只是快捷填充来源，§5.5 读法 B）。 */
    private void loadPreset(PrepSession session, Side side, int presetIndex, List<String> cards) {
        if (session == null) {
            tellError(HINT_NO_MATCH);
            return;
        }
        if (session.isConfirmed(side)) {
            tellError(HINT_ALREADY);
            return;
        }
        for (int index = 0; index < MenuLayout.DECK_SIZE; index++) {
            String cardId = index < cards.size() ? cards.get(index) : "";
            session.setDraftCard(side, index, cardId);
        }
        logInfo("已把第 " + (presetIndex + 1) + " 套预设灌进卡槽（" + cards.size() + " 张）");
        refresh();
    }

    /**
     * ★ <b>确认卡组</b>：唯一入口是 {@link MatchService#confirmDeck}
     * （三道门在纯状态机 {@link PrepSession} 里；发牌在 {@code CardLoadout.onDeckConfirmed}）。
     *
     * <p>成功后<b>关闭界面</b>（用户口径：「玩家确认 ⇒ 关闭界面 + placeDeck 到 2~9」）。
     */
    private void confirm(MatchService service, Side side) {
        List<String> draft = service.prep().draft(side);
        int have = service.prep().draftCount(side);
        if (have < MenuLayout.DECK_SIZE) {
            tellError(String.format(HINT_NOT_ENOUGH, MenuLayout.DECK_SIZE - have));
            return;
        }
        List<Card> deck = new ArrayList<>(MenuLayout.DECK_SIZE);
        for (String cardId : draft) {
            Card card = cardOf(cardId);
            if (card != null) {
                deck.add(card);
            }
        }
        if (deck.size() != MenuLayout.DECK_SIZE) {
            tellError(String.format(HINT_NOT_ENOUGH, MenuLayout.DECK_SIZE - deck.size()));
            return;
        }
        if (!service.confirmDeck(side, deck)) {
            tellError(HINT_ALREADY);
            return;
        }
        tellOk(String.format(HINT_CONFIRMED, deck.size()));
        // 用户口径：「玩家确认 ⇒ 关闭界面」（照 ShopMenu 的既有做法：直接关玩家背包）
        player.player().closeInventory();
    }
}
