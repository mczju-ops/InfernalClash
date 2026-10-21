package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.profile.PlayerProgress;
import io.mczju.infernalclash.profile.PresetRules;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ★ <b>U2 图鉴 + 卡组预设（可编辑那一份）</b>—— 逐格布局照抄 {@code docs/09} §3.2。
 *
 * <h2>逐格布局（{@code docs/09} §3.2「6 行 = 54 格，刚好到上限」）</h2>
 * <table border="1">
 *   <caption>本界面放东西的格（其余格一律不放）</caption>
 *   <tr><th>槽位</th><th>内容</th><th>出处 / 行为</th></tr>
 *   <tr><td>{@code 0}</td><td><b>保存预设</b>按钮</td>
 *       <td>§3.2 第 1 行第 1 格；点击 ⇒ {@link PresetRules#validate} + {@code ProfileStore.save}</td></tr>
 *   <tr><td>{@code 1}~{@code 8}</td><td><b>8 个预设卡位</b>（当前草稿）</td>
 *       <td>§3.2「8 个预设卡位」；点击 ⇒ 把「图鉴里选中的那张卡」放进这一格（✅【Q35】点选中 → 再点卡位）</td></tr>
 *   <tr><td>{@code 18}~{@code 44}</td><td><b>卡牌图鉴 27 格</b></td>
 *       <td>§3.2「卡牌图鉴 27 格」；点击 ⇒ 选中该卡（未拥有 ⇒ 提示，U3 二级界面未实现）</td></tr>
 *   <tr><td>{@code 45} / {@code 53}</td><td><b>前一页 / 后一页</b></td>
 *       <td>§3.2 第 6 行最左 / 最右</td></tr>
 * </table>
 * <p>★ {@code 9}~{@code 17}（第 2 行整行）与 {@code 46}~{@code 52} 在 §3.2 里是「空」
 * ⇒ <b>不放任何东西</b>。三个预设按钮 {@code 11/13/15} 是 §5.2（比赛内选卡界面）的，
 * 放进来会与 §3.2 的「第 2 行空」直接冲突 ⇒ 不做。
 *
 * <h2>★ 「存 / 取」怎么落地（{@code docs/08} §6 + {@code docs/04} §16.12.3）</h2>
 * <ul>
 *   <li><b>取</b>：打开时按 {@code args[0]}（0 起的预设下标，见 {@link MenuRegistry#openDeck(Player, int)}）
 *       把该套预设读进 {@code 1}~{@code 8} 的草稿 —— <b>只读</b>该玩家自己的 {@code PlayerProgress}；</li>
 *   <li><b>存</b>：点 {@code 0} ⇒ 草稿交给 {@link PresetRules#validate}（**界面里不重复实现规则**），
 *       有问题就**把问题逐条显示给玩家**；没问题才 {@code PlayerProgress.withPreset}
 *       → {@code ProfileStore.save}（同一条业务入口，界面里没有第二套规则）；</li>
 *   <li><b>关闭</b>：{@link #handleClose()} 取消「已选中但还没放进卡位」的那张卡
 *       （✅【Q35】的补充裁定）；未保存的草稿随本实例丢弃（进度一个字节都没动）。</li>
 * </ul>
 *
 * <h2>★ 文案（P8「文案接线」已落地）</h2>
 * 标题与「保存预设」按钮名经 {@link MenuTexts} 读 {@code display.yml} 的
 * {@code messages.collection.title} / {@code messages.deck.save}，读不到就回退常量（并告警一次）。
 * ★ 还没有 {@code display.yml} 键的那几条（{@link #HINT_PICK_CARD} / {@link #HINT_LOCKED} /
 * {@link #SAVE_OK} / {@link #SAVE_FAILED} / {@link #LORE_PRESET_INDEX} / {@link #SEE_LOG_SUFFIX}）
 * 继续用代码常量：**不发明键、也不发明文案**（铁律 8 / 11），等措辞定案时新增键再接线。
 */
public final class DeckPresetMenu extends InfernalMenu {

    /** 界面标题的配置键（{@code display.yml} 的 {@code messages.collection.title}）。 */
    static final String KEY_TITLE = "collection.title";

    /** 「保存预设」按钮名的配置键（{@code display.yml} 的 {@code messages.deck.save}）。 */
    static final String KEY_SAVE = "deck.save";

    /**
     * 「下一个预设」按钮名的配置键（{@code display.yml} 的 {@code messages.deck.next-preset}）。
     *
     * <p>★ 2026-10-17 第十三轮新增（用户裁定：「添加『下一个预设』按钮」）——
     * 措辞来自用户原话，按 R76「所有显示文本可配置」落进 `display.yml`。
     */
    static final String KEY_NEXT_PRESET = "deck.next-preset";

    /**
     * 界面标题的**回退常量**「图鉴与卡组」。
     *
     * <p>出处：{@code docs/09} §3 的界面名「U2 图鉴 + 卡组预设」。
     * ★ 正常路径读 {@link #KEY_TITLE}（与 {@link CodexMenu#KEY_TITLE} 是同一把键）。
     */
    public static final String TITLE = "图鉴与卡组";

    /**
     * 保存按钮名的**回退常量**「保存预设」。
     *
     * <p>出处：{@code docs/09} §3.2「{@code 0} —— **保存预设**按钮」+ R59
     * （{@code docs/08} §6「保存 = 在仓库（图鉴界面）内保存预设」）。
     * ★ 正常路径读 {@link #KEY_SAVE}（无占位符 ⇒ 原样返回）。
     */
    public static final String BUTTON_SAVE = "保存预设";

    /**
     * 没选卡就点卡位时的提示。
     *
     * <p>出处：✅【Q35】「**点选中 → 再点卡位**」（用户原话）—— 顺序不能反。
     * ★ {@code display.yml} 里没有同名键 ⇒ 待 E1 定案时**新增**键（本轮不新增文件内容）。
     */
    public static final String HINT_PICK_CARD = "请先在图鉴里点一张卡，再点这个卡位。";

    /**
     * 点未拥有卡牌时的提示。
     *
     * <p>出处：{@code docs/09} §3.3「图鉴里**未拥有**的卡 ⇒ 弹出**二级界面**（U3）」——
     * <b>U3 没有逐格布局</b>（{@code docs/09} §1 只给了载体，没有格子）⇒ 按纪律不实现，
     * 只提示；裁定台账见 {@code docs/05} E11 ①②。
     * ★ {@code display.yml} 里没有同名键（{@code collection.unlock-confirm} 是 U3 的**确认句**，
     * 不是这句「还没解锁」）⇒ 待 E1 定案时新增键。
     */
    /**
     * 未拥有的卡被点时的**兜底**提示（★ 2026-10-20 起语义变了）。
     *
     * <p>以前它是"点击的唯一后果"（因为 U3 解锁确认界面没有入口）；
     * 现在点击会直接打开 U3（{@code MenuRegistry#openUnlockConfirm}），
     * 只有**打开失败**时才回这一句 —— 所以文案不再说"U3 未实现"
     * （那句话已经与事实不符：界面早就在，缺的只是入口）。
     */
    public static final String HINT_LOCKED = "这张卡还没有解锁（解锁界面打不开，原因见控制台日志）。";

    /**
     * 保存成功提示（{@code %d} ×2 = 第几套预设（1 起）、存了几张卡）。
     *
     * <p>★ {@code display.yml} 里没有同名键 ⇒ 待 E1 定案时新增键。
     * （带数量是为了可对账：{@code docs/04} §16.11.3 的「记『存了哪套』」。）
     */
    public static final String SAVE_OK = "预设 %d 已保存（%d 张卡）。";

    /**
     * 保存失败提示的前缀（后面逐条跟 {@link PresetRules} 报出来的问题）。
     *
     * <p>★ 失败方向取 {@code docs/04} §16.11.3「预设非法 ⇒ **拒绝并说清哪一条**」：
     * 判据只有 {@link PresetRules} 一处，界面只负责把它的原文念给玩家。
     * ★ {@code display.yml} 里没有同名键 ⇒ 待 E1 定案时新增键。
     */
    public static final String SAVE_FAILED = "预设没有保存：";

    /**
     * 保存按钮的 lore（{@code %d} ×2 = 当前第几套（1 起）/ 预设上限）。
     *
     * <p>出处：【Q12】「卡组预设数量上限为3」（{@code docs/08} §6）——
     * 上限**从 {@code PresetRules.PRESET_LIMIT} 取**，不另写一个 3。
     * ★ {@code display.yml} 里没有同名键 ⇒ 待 E1 定案时新增键。
     */
    public static final String LORE_PRESET_INDEX = "第 %d / %d 套";

    /**
     * 「下一个预设」按钮的**名字回退常量**（★ 2026-10-17 第十三轮新增）。
     *
     * <p>出处：**用户原话逐字**——「请在卡组的下一行（应该是第二行）添加『**下一个预设**』按钮」
     * ⇒ 名字直接用他给的那五个字。
     */
    public static final String BUTTON_NEXT_PRESET = "下一个预设";

    /** 「下一个预设」按钮的 lore（说明"切套会丢弃未保存的改动"）。 */
    public static final String LORE_NEXT_PRESET_SWITCH = "点一下切到下一套（未保存的改动会丢弃）";

    /** 切换成功后的提示（{@code %d} = 现在在第几套，1 起）。 */
    public static final String SAVED_HINT_AFTER_SWITCH = "已切到第 %d 套预设（未保存的改动已丢弃）。";

    /**
     * 「保存失败、原因已写进控制台」的后缀。
     *
     * <p>★ 失败方向：给玩家一句短话、给日志一份完整堆栈（绝不把堆栈糊到玩家脸上）。
     * ★ {@code display.yml} 里没有同名键 ⇒ 待 E1 定案时新增键。
     */
    public static final String SEE_LOG_SUFFIX = "（原因见控制台日志）";

    /** 逐条列出校验问题时的行首符号（{@code docs/04} §16.11.3「拒绝并说清哪一条」）。 */
    public static final String PROBLEM_BULLET = " · ";

    /** 草稿：8 格（空串 = 空格），装配期由该玩家自己的进度填。 */
    private final List<String> draft = new ArrayList<>();

    /** 正在编辑第几套预设（0 起；合法性由 {@link MenuRegistry#openDeck(Player, int)} 先判）。 */
    private int presetIndex;

    /** 图鉴当前页（0 起）。 */
    private int page;

    /** 已选中但还没放进卡位的卡 id（{@code null} = 没选中）。 */
    private String selected;

    /**
     * 是否已经装配过一次。
     *
     * <p>★ 必需：{@code Menu.refresh()} 会 {@code clearMenu()} 后**再调一次 {@link #setup()}**
     * （{@code javap} 实测）—— 翻页与放入卡都要走 {@code refresh()}，
     * 若每次都重新读进度，翻页会把刚编辑的草稿与页码一起冲掉。
     */
    private boolean initialized;

    /**
     * ★ <b>无副作用构造</b>：MGC 用反射 {@code (Player, Object...)} 造它，装配全部在 {@link #setup()}。
     *
     * @param player 打开界面的玩家
     * @param args   {@code args[0]} = 预设下标（0 起；缺省 0；越界会被拒绝打开，见
     *               {@link MenuRegistry#openDeck(Player, int)}）
     */
    public DeckPresetMenu(Player player, Object... args) {
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

    /**
     * ★ <b>可离线单测的那一半</b>：保存按钮名（无占位符 ⇒ 原样返回配置里的值）。
     */
    static String saveName(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_SAVE, BUTTON_SAVE, warner);
    }

    /**
     * ★ <b>可离线单测的那一半</b>：「下一个预设」按钮名（{@link #KEY_NEXT_PRESET}）。
     *
     * <p>★ 措辞来自**用户原话**（「添加『下一个预设』按钮」）⇒ 走 `display.yml`，
     * 服主可改；读不到就回退那五个字。
     */
    static String nextPresetName(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_NEXT_PRESET, BUTTON_NEXT_PRESET, warner);
    }

    /** 行数（★ 同上，只许返回静态常量）。 */
    @Override
    protected int getRows() {
        return MenuLayout.SELECT_ROWS;   // docs/09 §3.2：6 行 = 54 格
    }

    @Override
    protected void setup() {
        try {
            if (!initialized) {
                presetIndex = readPresetIndex();
                page = readPage();
                loadDraft();
                initialized = true;
            }
            render();
        } catch (Throwable t) {
            // ★ 绝不让异常冒回 MGC：一次点击的异常会把该事件的所有监听器一起炸掉
            logWarning("装配 " + getClass().getSimpleName() + " 失败（界面会显示为空）：" + t);
        }
    }

    /** ✅【Q35】补充裁定：关闭界面时取消「尚未放进卡位的选中卡」。 */
    @Override
    public void handleClose() {
        selected = null;
        super.handleClose();
    }

    // ------------------------------------------------------------------ 解析参数 / 读进度

    /**
     * 读预设下标：{@code args[0]} 可以是 {@code Integer}（本插件入口）或数字字符串
     * （MGC 的 {@code /menu <id> <player> <args…>} 传下来的是字符串，{@code javap} 实测）。
     * 越界 / 不是数字 ⇒ 退回 0 并告警（构造期绝不抛）。
     */
    private int readPresetIndex() {
        Object[] args = getArgs();
        if (args == null || args.length == 0 || args[0] == null) {
            return 0;
        }
        Object first = args[0];
        int value;
        if (first instanceof Number number) {
            value = number.intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(first).trim());
            } catch (NumberFormatException e) {
                logWarning("预设下标不是数字（" + first + "），退回第 1 套");
                return 0;
            }
        }
        if (!MenuRegistry.isValidPresetIndex(value)) {
            logWarning("预设下标越界（0~" + (PresetRules.PRESET_LIMIT - 1) + "）：" + value + "，退回第 1 套");
            return 0;
        }
        return value;
    }

    /**
     * ★ <b>读「图鉴停在第几页」</b>（{@code args[1]}，2026-10-21 新增）。
     *
     * <p>为什么需要它：解锁确认界面（U3）会把玩家带走再带回来，
     * 而页码是**界面内部状态**（关掉就丢）⇒ 只能由打开方把它传下来
     * （见 {@link MenuRegistry#openDeck(Player, int, int)} 与 {@link UnlockReturn}）。
     * 缺参数（{@code /incl cards} 那条路 / MGC 的通用 {@code /menu} 通道）⇒ 第 1 页，
     * 不是数字 / 负数 ⇒ 同样退回 0 并留一条 WARN（构造期绝不抛）。
     */
    private int readPage() {
        Object[] args = getArgs();
        if (args == null || args.length < 2 || args[1] == null) {
            return 0;
        }
        if (args[1] instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        logWarning("图鉴页码不是数字（" + args[1] + "），退回第 1 页");
        return 0;
    }

    /** 把该玩家**自己**的第 {@code presetIndex} 套预设读进草稿（缺的补空串 ⇒ 永远是 8 格）。 */
    private void loadDraft() {        draft.clear();
        PlayerProgress progress = progress();
        List<String> source = presetIndex < progress.presets().size()
                ? progress.presets().get(presetIndex)
                : List.of();
        for (int index = 0; index < MenuLayout.DECK_SIZE; index++) {
            draft.add(index < source.size() ? source.get(index) : "");
        }
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        renderSaveButton();
        renderDeckSlots();
        renderNextPresetButton();
        renderCodexPage();
        renderPageButtons();
    }

    /**
     * ★★ <b>「下一个预设」按钮</b>（槽位 {@code 9}）—— 2026-10-17 第十三轮新增。
     *
     * <h2>用户原话（逐字）</h2>
     * > 「图鉴里**只能设置一个预设卡组，没法切换预设卡组**，请在卡组的下一行
     * > （应该是第二行）添加『**下一个预设**』按钮」
     *
     * <p>★ 根因：这个界面（U2）本来就有 {@code presetIndex} 维度（{@code args[0]} 带进来的），
     * 但界面里**没有任何地方能改它** ⇒ 玩家只能编辑第 1 套（{@code docs/09} §3.2 原写
     * "第 2 行整行是空的"，于是"切换"这件事在文档里也就不存在）。这个按钮把那一维补上。
     *
     * <p>★ 语义（三条，都对得上【Q12】「上限 3」）：
     * <ol>
     *   <li>点一下 ⇒ {@code presetIndex = (presetIndex + 1) % PRESET_LIMIT}（**回绕**）；</li>
     *   <li>换套时**丢弃未保存的草稿**并重新载入那一套（与"关闭界面丢弃草稿"同一口径，
     *       见 {@link #handleClose()} 的 javadoc）—— ★ 并在聊天栏说清"未保存的改动已丢弃"，
     *       否则玩家会以为它保存了；</li>
     *   <li>按钮 lore 显示「当前第 N / 3 套」⇒ 玩家知道自己在哪一套。</li>
     * </ol>
     */
    private void renderNextPresetButton() {
        List<String> lore = List.of(
                String.format(LORE_PRESET_INDEX, presetIndex + 1, PresetRules.PRESET_LIMIT),
                LORE_NEXT_PRESET_SWITCH);
        setSlot(MenuLayout.DECK_NEXT_PRESET_SLOT,
                icon(Material.ARROW, nextPresetName(displayOrNull(), this::logWarning), lore),
                (who, event) -> nextPreset());
    }

    /** 切到下一套预设（回绕）—— 见 {@link #renderNextPresetButton()} 的三条语义。 */
    private void nextPreset() {
        presetIndex = (presetIndex + 1) % PresetRules.PRESET_LIMIT;
        selected = null;     // 未放入卡位的选中卡也一并取消（与关闭界面同口径）
        loadDraft();         // 重新载入这一套（未保存的草稿**丢弃**）
        tellOk(String.format(SAVED_HINT_AFTER_SWITCH, presetIndex + 1));
        logInfo("已切到第 " + (presetIndex + 1) + " / " + PresetRules.PRESET_LIMIT + " 套预设");
        refresh();
    }

    private void renderSaveButton() {
        List<String> lore = List.of(String.format(LORE_PRESET_INDEX, presetIndex + 1,
                PresetRules.PRESET_LIMIT));
        // ★ 材质是本轮的占位选择：display.yml 的 icons 段**没有** collection.save 这个键
        //   （只有 shop.buy1/10/30/exit 与 collection.prev/next/locked）⇒ 接线时再新增。
        setSlot(MenuRegistry.deckSaveSlot(),
                icon(Material.WRITABLE_BOOK, saveName(displayOrNull(), this::logWarning), lore),
                (who, event) -> saveDraft());
    }

    private void renderDeckSlots() {
        for (int index = 0; index < MenuLayout.DECK_SIZE; index++) {
            int slot = MenuLayout.cardSlot(index);
            String cardId = draft.get(index);
            Card card = cardOf(cardId);
            ItemStack item;
            if (card != null) {
                item = cardIcon(card);
            } else if (cardId != null && !cardId.isBlank()) {
                // 存档里允许留下「卡池里已经没有」的旧卡（docs/08 §6「卡牌被移除时要降级处理」）
                // ⇒ 这里只把它如实显示出来（名字就是那个 id），怎么降级由 PresetRules 在保存时报出来。
                item = icon(Material.BARRIER, cardId, null);
            } else {
                // ✅【Q38】的同类口径：卡位可以是**真空格** ⇒ 不放占位物品（只挂点击动作）
                item = new ItemStack(Material.AIR);
            }
            final int target = index;
            setSlot(slot, item, (who, event) -> putSelected(target));
        }
    }

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
            // 金色版：同一张卡（【Q31】单条目 + 品质标记）⇒ 只有附魔光效这一个区别，不加自造文案
            boolean gold = PlayerProgress.CODEX_GOLD.equals(state);
            // ★★★ 2026-10-19（用户逐字）：「现在游戏内**完全看不到卡牌的风味描述**，
            //   请你在**图鉴界面**加上」⇒ U2 的**图鉴格**（18~44）也带风味；
            //   ★ 上方 8 个**卡位**（1~8）与选卡界面 U4 不带（那里空间紧、且不是"图鉴"）。
            ItemStack item = owned ? cardIcon(card, gold, true) : lockedCardIcon(card);
            if (owned && card.id().equals(selected)) {
                item = cardIcon(card, true, true);   // 选中的那张用光效标出来（不动文案）
            }
            final String cardId = card.id();
            final int unlockPage = page;
            final int unlockPreset = presetIndex;
            setSlot(slot, item, (who, event) -> {
                // ★★★ 2026-10-20（用户：「卡牌解锁界面未实现，因此卡牌无法用烈焰粉解锁」）：
                //   以前这里调 selectCard(cardId, false) ⇒ 只回一句「U3 未实现」；
                //   而 U3（ui/UnlockConfirmMenu）其实早就做好了，只是**入口没接**。
                //   ⇒ 现在未拥有的卡直接打开解锁确认界面。
                // ★★★ 2026-10-21（用户：「取消后应该返回图鉴界面」＋「解锁完其余卡变？？？」）：
                //   回程目标**由本界面注入**（U2 + 当前页 + 当前预设）⇒ 取消与解锁成功都原地返回，
                //   不会再把玩家丢进 U5 只读图鉴（那里当时的未解锁格还是 "???"）。
                if (owned) {
                    selectCard(cardId);
                } else if (!MenuRegistry.openUnlockConfirm(who.player(), cardId,
                        UnlockReturn.deck(unlockPage, unlockPreset))) {
                    tell(HINT_LOCKED);
                }
            });
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

    // ------------------------------------------------------------------ 交互

    /**
     * 点图鉴里**已拥有**的一张卡 ⇒ 选中它（等下一次点卡位时放进去）。
     *
     * <p>★ 2026-10-20 起「未拥有」不再走这里 —— 它在点击处直接打开解锁确认界面 U3
     * （见 {@code renderCodexPage} 里的注释：U3 早就有，缺的是入口）。
     */
    private void selectCard(String cardId) {
        selected = cardId;
        refresh();   // 只为了把「选中」标出来（光效），不动任何文案
    }

    /** 点一个卡位：把选中的卡放进去（替换空位或已有卡，✅【Q35】）。 */
    private void putSelected(int index) {
        if (selected == null) {
            tell(HINT_PICK_CARD);
            return;
        }
        if (index < 0 || index >= draft.size()) {
            logWarning("卡位下标越界：" + index);
            return;
        }
        draft.set(index, selected);
        selected = null;
        refresh();
    }

    /**
     * 存预设：★ <b>走既有业务入口</b>（{@code docs/04} §16.12.3「按钮点击 ⇒ 走同一条业务入口」）——
     * 校验只有 {@link PresetRules} 一处，落盘只有 {@code ProfileStore} 一处。
     */
    private void saveDraft() {
        try {
            PlayerProgress progress = progress();
            List<String> cards = List.copyOf(draft);
            List<String> problems = PresetRules.validate(cards, progress, knownCardIds());
            if (!problems.isEmpty()) {
                tellError(SAVE_FAILED);
                for (String problem : problems) {
                    tellError(PROBLEM_BULLET + problem);
                }
                return;
            }
            InfernalClashPlugin instance = plugin();
            if (instance == null) {
                tellError(SAVE_FAILED + SEE_LOG_SUFFIX);
                logWarning("保存预设失败：插件实例不可用");
                return;
            }
            PlayerProgress next = progress.withPreset(presetIndex, cards);
            instance.profileStore().save(player.player(), next);
            tellOk(String.format(SAVE_OK, presetIndex + 1, cards.size()));
            logInfo("已保存第 " + (presetIndex + 1) + " 套预设（" + cards.size() + " 张卡）");
        } catch (Throwable t) {
            // ★ 存档失败绝不能把异常冒回 MGC（ProfileStore 自己也绝不抛，这里是双保险）
            tellError(SAVE_FAILED + SEE_LOG_SUFFIX);
            logWarning("保存预设出错：" + t);
        }
    }
}
