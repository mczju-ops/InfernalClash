package io.mczju.infernalclash.ui;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import com.github.mczjuops.mczjugamecore.menu.MenuFacade;
import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.profile.PresetRules;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ★ <b>三个界面的注册入口 + 纯布局清单</b>（P8）。
 *
 * <h2>它做什么</h2>
 * <ol>
 *   <li>{@link #register(Plugin)}：把三个界面登记进 MGC 的 {@code MenuFacade}
 *       （{@code docs/04} §16.12.2「界面引擎复用 MGC 的 {@code MenuFacade}」）+ 装一道
 *       {@code InventoryDragEvent} 拦截（{@code docs/09} §1 共同纪律第 2 条）；</li>
 *   <li>{@link #openShop(Player)} / {@link #openDeck(Player, int)} / {@link #openCodex(Player)}：
 *       打开前的**权限 / 进度 / 参数**三道检查，任何一道不过就**给玩家一句可读提示**并返回
 *       {@code false}（绝不把一个半成品界面推给玩家，也不让异常冒到 MGC）；</li>
 *   <li>「该界面该在哪些槽位放东西」的**静态纯函数**（{@link #shopItemSlots()} /
 *       {@link #deckItemSlots()} / {@link #codexItemSlots()} 与分页算术）——
 *       它们只调 {@link MenuLayout}，因此能在普通 JUnit 里离线断言
 *       （{@code MenuRegistryTest}），而「反射注册、真开界面」只能实机看，
 *       见 {@code docs/12-实机冒烟清单.md}。</li>
 * </ol>
 *
 * <h2>★★ 实测（MGC 1.1.2 的 {@code javap -c}）—— 写在这里，免得后人再踩</h2>
 * <table border="1">
 *   <caption>MenuFacade / Menu 的三条实测事实</caption>
 *   <tr><th>事实</th><th>后果</th></tr>
 *   <tr><td>{@code registerMenu(id, clazz)} 对 id {@code toLowerCase()}，<b>重复 id 抛
 *       {@link IllegalArgumentException}</b>，并要求类有公开构造器 {@code (Player, Object...)}</td>
 *       <td>{@link #register(Plugin)} 先查 {@link MenuFacade#getMenuIds()} 再注册，并逐个 try/catch</td></tr>
 *   <tr><td>{@code open(...)} 未注册返回 {@code false}；构造器抛的异常被包成 {@code RuntimeException}</td>
 *       <td>打开入口必须 {@code catch (Throwable)}（否则一次点击就能把事件总线炸掉）</td></tr>
 *   <tr><td>{@code Menu.open()} 在权限不足时<b>静默 return</b>；点击链路按
 *       {@code getRawSlot()} 过滤、按 {@code getSlot()} 查表</td>
 *       <td>权限提示必须自己做；只用顶部箱子（本类的纯函数保证槽位 &lt; 行数 × 9）</td></tr>
 * </table>
 *
 * <h2>★ 铁律 10</h2>
 * 本类只读**该玩家自己**的进度（经 {@code ProfileStore}），不出现
 * {@code getOnlinePlayers / getEntities / getLivingEntities / getWorlds}；
 * 拖动拦截只作用于 {@code getHolder() instanceof InfernalMenu} 的界面
 * （{@code docs/09} 末尾【AI 补充 · 安全】：不许无过滤地 {@code setCancelled(true)}）。
 *
 * <h2>★ 文案纪律</h2>
 * 三个界面的标题 / 按钮名已改读 {@code display.yml}（经 {@code MenuTexts.resolve}，见 P8 文案接线）。
 * 下面的 {@code MSG_*} 常量**仍是代码里的中文常量**：{@link #MSG_NO_PERMISSION} 对应的
 * {@code command.no-permission} 值是占位 {@code [待填]}，另两条**连键都还没有**
 * ⇒ 接线它们要么显示 {@code [待填]}、要么新增文档无依据的键，两者都违反铁律 8 / 11。
 * 逐条的目标键写在常量 javadoc 里；本轮**不新增** {@code display.yml} 键、也不改 {@code DisplayConfig}。
 */
public final class MenuRegistry {

    // ------------------------------------------------------------------ 界面 id

    /**
     * U4 局外商店的界面 id（{@code docs/09} §4，逐格布局 §4.2）。
     *
     * <p>★ 加 {@code infernalclash:} 前缀是为了与 MGC 自带的菜单（{@code main} 等）**不可能撞名**——
     * {@code registerMenu} 撞名会**抛异常**。id 全小写（门面自己会 {@code toLowerCase()}）。
     */
    public static final String MENU_SHOP = "infernalclash:shop";

    /**
     * U2 图鉴 + 卡组预设（可编辑那一份）的界面 id（{@code docs/09} §3，逐格布局 §3.2）。
     */
    public static final String MENU_DECK = "infernalclash:deck";

    /**
     * U5 图鉴（**只读**）的界面 id。
     *
     * <p>§3.2 / §5.2 里「卡牌图鉴 27 格 + 翻页 45/53」是**同一段槽位**
     * （§5.2 明写「两个界面可共用同一套 Layout」）⇒ 只读版与可编辑版共用槽位算术，
     * 区别只在：可编辑版有卡位与保存按钮（{@link #deckItemSlots()}），只读版没有
     * （{@link #codexItemSlots()}）。
     */
    public static final String MENU_CODEX = "infernalclash:codex";

    /**
     * ★★ <b>U4「比赛内选卡」界面的 id</b>（{@code docs/09} §5，逐格布局 §5.2；2026-10-16 第八轮新增）。
     *
     * <p>★ 它是一个<b>带参数</b>的界面：打开时必须传本局的 {@code matchId}
     * （见 {@link #openDeckPicker(Player, String)}）—— 一名玩家可能同时在多场里
     * （【Q44】多场并发），「打开的是哪一场」只有打开的那一刻知道。
     */
    public static final String MENU_MATCH_DECK = "infernalclash:match-deck";

    /**
     * ★★ <b>「投降」界面的 id</b>（{@code docs/02} §6.1 ＋ 【Q9】；
     * 2026-10-17 第十二轮新增，缺陷 {@code docs/27} §D7）。
     *
     * <p>★ 用户 2026-10-17 裁定 E26：「战斗菜单的物品改名『**投降**』，内部只有两个按钮
     * 『确认投降』和『取消』，对称排布」⇒ 界面标题与两个按钮都按这一句；
     * ★ id {@code infernalclash:battle} **不改**（它是**技术 id**，与玩家看到的标题无关 ——
     * 换掉就得同时动注册表、测试与排障口径，而它对玩家不可见）。
     *
     * <p>★ 它同样是一个<b>带参数</b>的界面：打开时传本局的 {@code matchId}
     * （一名玩家可能在多场里 —— 【Q44】多场并发 ⇒ 不能按玩家现查"当前对局"）。
     */
    public static final String MENU_BATTLE = "infernalclash:battle";

    /**
     * ★★ <b>U3 解锁确认界面的 id</b> —— 2026-10-17 批次 3 后半新增
     * （{@code docs/28} 第 27 条用户裁定「<b>做</b>」＋ 第 33 条的四条追问）。
     *
     * <p>★ 它是一个<b>带参数</b>的界面：打开时传「要解锁哪张卡」
     * （见 {@link #openUnlockConfirm(Player, String, UnlockReturn)}）—— 一名玩家可能在多场里、
     * 也可能连开两次不同的卡 ⇒ 不能按玩家现查"他要解锁什么"。
     */
    public static final String MENU_UNLOCK_CONFIRM = "infernalclash:unlock-confirm";

    // ------------------------------------------------------------------ 玩家侧提示（待搬 display.yml）

    /** 玩家缺 {@code infernalclash.play}（{@code docs/10} §5）时的提示。★ 目标键 {@code messages.command.no-permission}。 */
    static final String MSG_NO_PERMISSION = "你没有权限打开这个界面。";

    /**
     * 「插件没就绪 / 拿不到进度档案 / 打开失败」共用的提示。
     *
     * <p>★ {@code display.yml} 里**没有**同名键 ⇒ 待 E1 定案时新增
     * （{@code command.*} 那一批仍是 {@code [待填]}，本轮不读它们的值）。
     * 具体原因一律写进控制台日志，不往玩家脸上糊堆栈。
     */
    static final String MSG_CANNOT_OPEN = "暂时打不开这个界面（原因见控制台日志）。";

    /**
     * 预设编号不合法时的提示（只支持 1~3 套，【Q12】）。
     *
     * <p>★ {@code display.yml} 里没有同名键 ⇒ 待 E1 定案时新增。
     */
    static final String MSG_BAD_PRESET_INDEX = "预设编号不合法：只支持 1~3 套。";

    private MenuRegistry() {
    }

    // ================================================================== 纯函数：槽位清单
    // ★ 下面这些方法**只调 MenuLayout**（纯布局），不碰 Bukkit / MGC ⇒ 可离线单测。
    //   界面代码必须走它们，不许自己写 slot 算术（docs/04 §16.12.2「格子布局 → 纯函数」）。

    /** 商店是不是 4 行（{@code docs/09} §4.2「逐格映射（4 行 = 36 格）」）。 */
    public static int shopRows() {
        return MenuLayout.SHOP_ROWS;
    }

    /** 图鉴 + 卡组预设界面的行数 = 6（{@code docs/09} §3.2「6 行 = 54 格」）。 */
    public static int deckRows() {
        return MenuLayout.SELECT_ROWS;
    }

    /** 只读图鉴的行数 = 6（同 §3.2 的槽位段，§5.2 也说可共用）。 */
    public static int codexRows() {
        return MenuLayout.SELECT_ROWS;
    }

    /**
     * U4 商店的**三个购买按钮**槽位（{@code docs/09} §4.2）：{@code 11} / {@code 13} / {@code 15}。
     *
     * <p>出处：§4.1「第 2 行：**第 3、5、7 格**：购买 1 / 10 / 30 卡牌包」+ §4.2 的逐格映射 +
     * ✅【Q34】「**从 1 数** ⇒ 上表的 {@code 11 / 13 / 15} 与 {@code 27 / 35} **即为最终槽位**」。
     */
    public static List<Integer> shopBuySlots() {
        List<Integer> slots = new ArrayList<>(MenuLayout.SHOP_ITEM_COUNT);
        for (int index = 0; index < MenuLayout.SHOP_ITEM_COUNT; index++) {
            slots.add(MenuLayout.shopSlot(index));
        }
        return List.copyOf(slots);
    }

    /**
     * U4 商店的**全部组件格**（{@code docs/09} §4.2 点名的 5 格）：
     * 三个购买按钮 + 金币展示格 {@code 27} + 退出按钮 {@code 35}。
     *
     * <p>★ 文档**没有**点名的格一律不放东西（§4.2 其余格是「空」/「16~26 空」）。
     */
    public static List<Integer> shopItemSlots() {
        List<Integer> slots = new ArrayList<>(shopBuySlots());
        slots.add(MenuLayout.SHOP_BALANCE_SLOT);
        slots.add(MenuLayout.SHOP_EXIT_SLOT);
        return List.copyOf(slots);
    }

    /**
     * U4 商店：第 {@code index} 个商品卖**几包**（{@code index} 0 起，与 {@link #shopBuySlots()} 同序）。
     *
     * <p>出处：{@code docs/09} §4.1「购买 1 / 10 卡牌包」（第 3、5 格）与 §4.2 的逐格映射
     * （{@code 12} = 买 1、{@code 14} = 买 10 —— ★ 2026-10-20 取消 30 包后**居中**）。
     * ★ 顺序**不是**我排的：它就是文档那一行的顺序。
     * ★★ 2026-10-20 用户裁定取消「连开 30 包」（抽到的卡牌显示不全）＋ 两个按钮居中 ⇒ 第三档（{@code 15} / 30 包）
     * 整条下线：本方法只剩两档，槽位 {@code 15} 从此空着。
     *
     * @throws IllegalArgumentException {@code index} 不在 0~1（接线错误 ⇒ fail-fast，不静默兜底）
     */
    public static int packCount(int index) {
        if (index < 0 || index >= MenuLayout.SHOP_ITEM_COUNT) {
            throw new IllegalArgumentException("packCount：index 越界，期望 0~"
                    + (MenuLayout.SHOP_ITEM_COUNT - 1) + "（docs/09 §4.2 + 2026-10-20 裁定：只有两个商品），"
                    + "实际 index=" + index);
        }
        return switch (index) {
            case 0 -> 1;
            case 1 -> 10;
            // ★ 30 包已下线（2026-10-20 用户裁定）；走到这里说明 SHOP_ITEM_COUNT 被改大却没改本方法
            default -> throw new IllegalArgumentException(
                    "packCount：" + index + " 没有对应的规格（30 包已于 2026-10-20 下线）");
        };
    }

    /**
     * U2 图鉴+卡组预设界面的**全部组件格**（{@code docs/09} §3.2 逐格映射）：
     * <pre>
     *   0        保存预设        第 1 行第 1 格
     *   1~8      8 个预设卡位    第 1 行第 2~9 格
     *   18~44    卡牌图鉴 27 格  第 3~5 行
     *   45 / 53  前一页 / 后一页  第 6 行最左 / 最右
     * </pre>
     * ★ §3.2 的 {@code 9~17}（第 2 行）与 {@code 46~52} 是「空」⇒ **不放任何东西**。
     * ★ 三个预设按钮 {@code 11/13/15} 属于 §5.2（比赛内选卡界面），**不在**这个界面里
     * —— §3.2 明写第 2 行整行是空的，放上去就是与文档冲突。
     */
    public static List<Integer> deckItemSlots() {
        List<Integer> slots = new ArrayList<>();
        slots.add(deckSaveSlot());
        for (int index = 0; index < MenuLayout.DECK_SIZE; index++) {
            slots.add(MenuLayout.cardSlot(index));
        }
        // ★★ 2026-10-17 第十三轮（用户裁定）：「图鉴里只能设置一个预设卡组，没法切换预设卡组，
        //   请在卡组的下一行（应该是第二行）添加『下一个预设』按钮」⇒ 第 2 行第 1 格（槽位 9）。
        slots.add(MenuLayout.DECK_NEXT_PRESET_SLOT);
        for (int index = 0; index < MenuLayout.CODEX_PAGE_SIZE; index++) {
            slots.add(MenuLayout.codexSlot(index));
        }
        slots.add(MenuLayout.PREV_PAGE_SLOT);
        slots.add(MenuLayout.NEXT_PAGE_SLOT);
        return List.copyOf(slots);
    }

    /**
     * 「保存预设」按钮的槽位 = {@code 0}。
     *
     * <p>出处：{@code docs/09} §3.2「{@code 0} —— **保存预设**按钮 —— 第 1 行第 1 格」。
     * （§5.2 里同一个 {@code 0} 是「确认卡组」，两处是同一格的两个名字 —— 本类用 §3.2 的名字。）
     */
    public static int deckSaveSlot() {
        return MenuLayout.CONFIRM_DECK_SLOT;
    }

    /**
     * U5 只读图鉴的**全部组件格**：图鉴 {@code 18~44} + 翻页 {@code 45 / 53}。
     *
     * <p>出处：{@code docs/09} §3.2 / §5.2 的「{@code 18}~{@code 44}：卡牌图鉴 27 格」与
     * 「{@code 45} = 前一页 / {@code 53} = 后一页」。
     */
    public static List<Integer> codexItemSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int index = 0; index < MenuLayout.CODEX_PAGE_SIZE; index++) {
            slots.add(MenuLayout.codexSlot(index));
        }
        slots.add(MenuLayout.PREV_PAGE_SLOT);
        slots.add(MenuLayout.NEXT_PAGE_SLOT);
        return List.copyOf(slots);
    }

    /** ★★ U4 比赛内选卡的行数 = 6（{@code docs/09} §5.2「【Q19】裁定后 = 6 行 / 54 格」）。 */
    public static int matchDeckRows() {
        return MenuLayout.SELECT_ROWS;
    }

    /**
     * ★★ <b>U4 比赛内选卡的「8 个卡槽」槽位</b>（{@code docs/09} §5.2 第 1 行的 {@code 1}~{@code 8}）。
     *
     * <p>出处：§5.2 第 1 行「{@code 1}~{@code 8} —— <b>8 个空卡槽</b>」（✅【Q38】真的空卡槽）
     * ＋ §5.5 读法 B（玩家当场构建卡组）。
     */
    public static List<Integer> matchDeckCardSlots() {
        List<Integer> slots = new ArrayList<>(MenuLayout.DECK_SIZE);
        for (int index = 0; index < MenuLayout.DECK_SIZE; index++) {
            slots.add(MenuLayout.cardSlot(index));
        }
        return List.copyOf(slots);
    }

    /**
     * ★★ <b>U4 的三个预设按钮槽位</b>（{@code docs/09} §5.2 第 2 行的 {@code 11} / {@code 13} / {@code 15}）。
     *
     * <p>出处：§5.2「★ {@code 11} / {@code 13} / {@code 15}（= 行内第 3 / 5 / 7 格，1 起数）
     * —— ✅【Q19】三个预设按钮」＋【2026-10-15 用户给定确切栏位】。
     */
    public static List<Integer> matchDeckPresetSlots() {
        List<Integer> slots = new ArrayList<>(MenuLayout.PRESET_LIMIT);
        for (int preset = 0; preset < MenuLayout.PRESET_LIMIT; preset++) {
            int slot = MenuLayout.presetSlot(preset, 0);
            if (slot >= 0) {
                slots.add(slot);
            }
        }
        return List.copyOf(slots);
    }

    /**
     * ★★ <b>U4 比赛内选卡界面的「全部组件格」</b>（= {@code docs/09} §5.2 逐格布局里被点名的每一格）。
     *
     * <h2>它是干什么用的（不是"顺手列一下"）</h2>
     * 它就是 {@code ui/MatchDeckMenu} 渲染时用的那张清单的<b>纯副本</b>：
     * 界面必须调它（{@code MatchDeckMenu.occupiedSlots()} 直接返回本方法）
     * ⇒ 「界面到底占哪几格」只有一处口径，测试也能离线逐格断言
     * （与 {@link #deckItemSlots()} 对 U2 的作用完全一样）。
     *
     * <pre>
     *   0        确认卡组         §5.2 第 1 行第 1 格
     *   1~8      8 个空卡槽       §5.2 第 1 行（【Q38】真的空卡槽）
     *   11/13/15 三个预设按钮     §5.2 第 2 行（行内第 3/5/7 格）
     *   18~44    卡牌图鉴 27 格   §5.2 第 3~5 行
     *   45 / 53  前一页 / 后一页   §5.2 第 6 行最左 / 最右
     * </pre>
     * ★ §5.2 里**没有点名**的格（第 2 行的 {@code 9}/{@code 10}/{@code 12}/{@code 14}/{@code 16}/{@code 17}
     * 与第 6 行的 {@code 46}~{@code 52}）**一律不放东西** ⇒ 不在这张清单里。
     * （§5.2 让 {@code 46}~{@code 52}「可放页码 / 提示」，那是"可"不是"必须"；
     * 本轮把那句提示放进了 0 号格的 lore，不去动第 6 行 —— 少一格就少一处与文档的偏差。）
     */
    public static List<Integer> matchDeckItemSlots() {
        List<Integer> slots = new ArrayList<>();
        slots.add(MenuLayout.CONFIRM_DECK_SLOT);
        slots.addAll(matchDeckCardSlots());
        slots.addAll(matchDeckPresetSlots());
        for (int index = 0; index < MenuLayout.CODEX_PAGE_SIZE; index++) {
            slots.add(MenuLayout.codexSlot(index));
        }
        slots.add(MenuLayout.PREV_PAGE_SLOT);
        slots.add(MenuLayout.NEXT_PAGE_SLOT);
        return List.copyOf(slots);
    }

    /**
     * ★★ <b>U3 投降界面的行数</b>（= {@link MenuLayout#BATTLE_ROWS}）。
     *
     * <p>★ 2026-10-17 用户裁定 E26：物品改名「**投降**」、内部两个按钮「确认投降」/「取消」、
     * **对称排布**；行数**用户没有否决** ⇒ 保持 3 行。详见 {@link MenuLayout#BATTLE_ROWS}。
     */
    public static int battleRows() {
        return MenuLayout.BATTLE_ROWS;
    }

    /**
     * ★★ <b>U3 投降界面的两个功能格</b>：确认投降（{@code 12}）与取消（{@code 14}）。
     *
     * <p>★ 这是本界面的**唯一槽位事实来源**：界面（{@code ui/BattleMenu}）渲染时调它，
     * 测试也断言它 ⇒ 「界面到底占哪几格」不会有两份口径。
     * ★ 两格关于第 2 行中心列（{@code 13}）**左右镜像** —— 就是用户那句「**对称排布**」。
     */
    public static List<Integer> battleItemSlots() {
        return List.of(MenuLayout.BATTLE_SURRENDER_SLOT, MenuLayout.BATTLE_CANCEL_SLOT);
    }

    // ================================================================== 纯函数：分页算术
    /**
     * 一页能放几张卡 = {@code 27}（{@code docs/09} §3.2 / §5.2「卡牌图鉴 27 格」）。
     */
    public static int codexPageSize() {
        return MenuLayout.CODEX_PAGE_SIZE;
    }

    /**
     * 总共几页（向上取整）。
     *
     * <p>★ {@code totalItems == 0} ⇒ **1**（空图鉴也显示「第 1 页」，否则页码会印成 {@code 0/0}）。
     *
     * @throws IllegalArgumentException {@code pageSize < 1} 或 {@code totalItems < 0}（接线错误）
     */
    public static int pageCount(int totalItems, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageCount：pageSize 必须 >= 1，实际 " + pageSize);
        }
        if (totalItems < 0) {
            throw new IllegalArgumentException("pageCount：totalItems 不能为负，实际 " + totalItems);
        }
        if (totalItems == 0) {
            return 1;
        }
        return (totalItems + pageSize - 1) / pageSize;
    }

    /**
     * 把页码夹进 {@code 0 ~ pageCount-1}。
     *
     * <p>为什么需要它：卡池可能在两次渲染之间变小（热重载换卡池），夹一下总比越界好。
     */
    public static int clampPage(int page, int pageCount) {
        if (pageCount <= 0) {
            return 0;
        }
        if (page < 0) {
            return 0;
        }
        return Math.min(page, pageCount - 1);
    }

    /** 第 {@code page} 页的**第一张卡**在整表里的下标（0 起，已夹进合法页）。 */
    public static int pageStart(int page, int pageSize, int totalItems) {
        return clampPage(page, pageCount(totalItems, pageSize)) * pageSize;
    }

    /** 第 {@code page} 页的**最后一张卡的下一格**（= 半开区间上界，已夹进合法页与总张数）。 */
    public static int pageEnd(int page, int pageSize, int totalItems) {
        return Math.min(totalItems, pageStart(page, pageSize, totalItems) + pageSize);
    }

    /**
     * 预设下标是否合法 —— ★ 判据取自 {@link PresetRules#PRESET_LIMIT}（**不另写一个 3**）。
     *
     * <p>出处：【Q12】「卡组预设数量上限为3」（{@code docs/08} §6 / {@code docs/09} §3.3）。
     */
    public static boolean isValidPresetIndex(int index) {
        return index >= 0 && index < PresetRules.PRESET_LIMIT;
    }

    /** 三个界面的 id（**注册顺序固定**、可离线断言；MGC 侧登记时会自己 {@code toLowerCase()}）。 */
    public static List<String> menuIds() {
        return List.of(MENU_SHOP, MENU_DECK, MENU_CODEX);
    }

    /**
     * ★★ <b>全部界面 id</b>（含 U4 比赛内选卡、U3 战斗菜单）—— 2026-10-16 第八轮新增，
     * 2026-10-17 第十二轮加入 U3。
     *
     * <p>★ 为什么不改 {@link #menuIds()}：那个方法被既有测试当成「三个常驻界面」钉着
     * （{@code MenuRegistryTest}）。U4 与 U3 的**打开时机完全不同**（前者只在准备阶段、
     * 后者只在战斗/加时；两者都必须带 {@code matchId}）⇒ 单列一份，让「界面的种类」
     * 这件事有两处可读的清单，而**注册**走 {@link #register(Plugin)}（五处，见那里）。
     */
    public static List<String> allMenuIds() {
        return List.of(MENU_SHOP, MENU_DECK, MENU_CODEX, MENU_MATCH_DECK, MENU_BATTLE,
                MENU_UNLOCK_CONFIRM);
    }

    // ================================================================== 注册（onEnable 调一次）

    /**
     * ★ <b>注册入口</b>：{@code InfernalClashPlugin.onEnable} 的注册区调它一次。
     *
     * <p>失败方向：**任何一个界面注册失败都不许让 {@code onEnable} 逃出去**
     * —— 少一个界面 ≠ 服务器起不来（与 MGC 注册失败时「不禁用插件」同一口径）。
     * 重复 id（同一 classloader 里 {@code onEnable} 跑了两次）只告警并跳过，
     * 因为 {@code registerMenu} 对重复 id 是**抛异常**的。
     *
     * @param plugin 本插件实例（只用于日志与事件注册；{@code null} ⇒ 什么都不做）
     */
    public static void register(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        Logger log = plugin.getLogger();
        registerOne(log, MENU_SHOP, ShopMenu.class);
        registerOne(log, MENU_DECK, DeckPresetMenu.class);
        registerOne(log, MENU_CODEX, CodexMenu.class);
        // ★★ 2026-10-16 第八轮：U4 比赛内选卡（只在准备阶段、必须带 matchId 打开）
        registerOne(log, MENU_MATCH_DECK, MatchDeckMenu.class);
        // ★★ 2026-10-17 第十二轮：U3 战斗菜单（1 号快捷栏物品右键打开；含投降按钮）
        registerOne(log, MENU_BATTLE, BattleMenu.class);
        registerOne(log, MENU_UNLOCK_CONFIRM, UnlockConfirmMenu.class);
        registerDragGuard(plugin, log);
    }

    /** 注册一个界面（先查重，再逐个 try/catch）。 */
    private static void registerOne(Logger log, String id, Class<? extends Menu> type) {
        if (MenuFacade.getMenuIds().contains(id)) {
            log.warning("界面注册：id " + id + " 已经注册过，跳过（MenuFacade 对重复 id 会抛异常）");
            return;
        }
        try {
            MenuFacade.registerMenu(id, type);
            log.info("界面已注册：" + id + " → " + type.getSimpleName());
        } catch (Throwable t) {
            log.log(Level.WARNING, "界面注册失败：" + id + " → " + type.getName()
                    + "（该界面打不开，其它功能不受影响）：" + t, t);
        }
    }

    /**
     * 装一道拖动拦截（{@code docs/09} §1 共同纪律第 2 条：「**单独拦 {@code InventoryDragEvent}**
     * （只拦 Click 会被拖动绕过）」）。
     *
     * <p>★ 只拦**我们自己**的界面（{@code getHolder() instanceof InfernalMenu}）——
     * 无过滤地 {@code setCancelled(true)} 会打死大厅菜单与别的玩法（{@code docs/09} 末尾的安全补充）。
     */
    private static void registerDragGuard(Plugin plugin, Logger log) {
        try {
            plugin.getServer().getPluginManager().registerEvents(new MenuDragGuard(), plugin);
        } catch (Throwable t) {
            log.log(Level.WARNING, "拖动拦截注册失败（界面仍可点，但拖动不再被拦）：" + t, t);
        }
    }

    /** 只拦本插件菜单上的拖动（{@code docs/09} §1 第 2 条）。 */
    static final class MenuDragGuard implements Listener {

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            try {
                if (event.getView() != null
                        && event.getView().getTopInventory().getHolder() instanceof InfernalMenu) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 拦截失败也绝不让异常冒到事件总线（否则一次拖动就能炸掉别人的监听器）
            }
        }
    }

    // ================================================================== 打开入口（权限 / 进度 / 参数）

    /**
     * 打开 U4 商店（{@code docs/10} §3 的 {@code /incl shop} 将来的落点）。
     *
     * @return 是否真的打开了（{@code false} ⇒ 已给玩家一句可读提示）
     */
    public static boolean openShop(Player player) {
        if (!canOpen(player)) {
            return false;
        }
        return open(MENU_SHOP, player);
    }

    /**
     * 打开 U2 图鉴+卡组预设界面，编辑**第 1 套**预设（= 下标 0）、从**第 1 页**看图鉴。
     *
     * @return 是否真的打开了
     */
    public static boolean openDeck(Player player) {
        return openDeck(player, 0, 0);
    }

    /**
     * 打开 U2 图鉴+卡组预设界面，编辑第 {@code presetIndex} 套预设。
     *
     * <h2>★ 为什么预设下标要作为参数传进来（本轮接线决定，可否决）</h2>
     * {@code docs/09} §3.2 的 U2 布局里**只有一行**卡位（{@code 1}~{@code 8}）与一个
     * 「保存预设」按钮（{@code 0}）—— **没有预设选择器**（三个预设按钮 {@code 11/13/15}
     * 是 §5.2 的 U4 布局）。而【Q12】的预设上限是 3 ⇒「保存到第几套」必须由**调用方**给：
     * 这里就是那个调用方（{@code docs/08} §6「保存 = 在仓库界面内保存预设」，
     * 选哪一套由入口决定，界面自己不发明一套选择规则）。
     *
     * @param presetIndex 0 起的预设下标，合法 {@code 0 ~ PresetRules.PRESET_LIMIT-1}（= 0~2）
     * @return 是否真的打开了（下标不合法 / 权限不足 / 拿不到进度 ⇒ {@code false} + 可读提示）
     */
    public static boolean openDeck(Player player, int presetIndex) {
        return openDeck(player, presetIndex, 0);
    }

    /**
     * ★ <b>打开 U2 图鉴+卡组预设界面，并停在第 {@code page} 页</b>（2026-10-21 新增）。
     *
     * <h2>为什么需要「页码」这个参数（它补的是哪一格）</h2>
     * 图鉴的页码是**界面内部状态**（关掉就丢）。而解锁确认界面（U3）会把玩家从 U2 带走再带回来
     * ⇒ 不带页码的话，玩家会<b>被弹回第 1 页</b>，看不到自己刚花烈焰粉买到的那张卡
     * （用户报的原话是「解锁过一张卡后，返回图鉴界面时……」—— 回程必须"原地返回"）。
     * ★ 与 {@link #openCodexQuietly(Player, int)} 同一条口径，只是目标界面不同。
     *
     * @param presetIndex 0 起的预设下标（合法 {@code 0 ~ PresetRules.PRESET_LIMIT-1}）
     * @param page        图鉴页码（0 起；负数夹到 0 —— 界面自己还会再夹一次）
     * @return 是否真的打开了
     */
    public static boolean openDeck(Player player, int presetIndex, int page) {
        if (!isValidPresetIndex(presetIndex)) {
            tell(player, MSG_BAD_PRESET_INDEX, NamedTextColor.RED);
            return false;
        }
        if (!canOpen(player)) {
            return false;
        }
        return open(MENU_DECK, player, presetIndex, Math.max(0, page));
    }

    /**
     * 打开 U5 只读图鉴（{@code docs/10} §3 的 {@code /incl cards} 将来的落点）。
     *
     * @return 是否真的打开了
     */
    public static boolean openCodex(Player player) {
        if (!canOpen(player)) {
            return false;
        }
        return open(MENU_CODEX, player);
    }

    /**
     * ★★ <b>打开 U4 比赛内选卡界面</b>（{@code docs/09} §5；2026-10-16 第八轮新增）。
     *
     * <h2>两个调用点（都是本轮接的）</h2>
     * <ol>
     *   <li>★ <b>准备阶段一开始自动弹一次</b>（用户裁定「自动弹」，{@code docs/24} §5.8）——
     *       由 {@code InfernalClashGame#openDeckPickerForHumans} 调；</li>
     *   <li>★ <b>手持「选卡菜单」物品右键任意位置</b>（R15 / {@code docs/09} §5.4）——
     *       由 {@code game/CardCastListener} 调。</li>
     * </ol>
     *
     * <h2>★ 与其它三个入口的差别：它必须带 {@code matchId}</h2>
     * 界面本身要从「本局运行时」读选卡会话（谁确认了、草稿是哪 8 张），
     * 而一名玩家可能在多场里（【Q44】多场并发）⇒ {@code matchId} <b>作为界面参数</b>传下去
     * （{@code MatchDeckMenu} 的构造参数）。
     *
     * <p>★ 打开前只做「权限 + 插件就绪」两道（与其它界面同一处 {@link #canOpen}）；
     * 「现在是不是准备阶段 / 这一方要不要选卡」由<b>界面自己</b>在渲染时判
     * —— 那里能把原因说给玩家听，而这里只能给一句「打不开」。
     *
     * @param matchId 本局的 id（= MGC 房间名）；{@code null} / 空白 ⇒ 拒绝并提示
     * @return 是否真的打开了（{@code false} ⇒ 已给玩家一句可读提示 + 控制台一条 WARN）
     */
    public static boolean openDeckPicker(Player player, String matchId) {
        return openDeckPicker(player, matchId, 0);
    }

    /**
     * ★ <b>打开 U4 比赛内选卡界面，并停在第 {@code page} 页</b>（2026-10-21 新增）。
     *
     * <p>为什么需要它：U4 里点未拥有的卡同样会打开 U3 解锁确认界面，
     * 而从 U3 回来必须是「原地返回」（见 {@link UnlockReturn}）—— 页码不复原，
     * 玩家解锁完会发现自己被弹回第 1 页（{@code docs/24} §21.4 的现场缺陷就是这个形状）。
     *
     * @param matchId 本局的 id（= MGC 房间名）；{@code null} / 空白 ⇒ 拒绝并提示
     * @param page    图鉴页码（0 起；负数夹到 0）
     * @return 是否真的打开了
     */
    public static boolean openDeckPicker(Player player, String matchId, int page) {
        if (matchId == null || matchId.isBlank()) {
            tell(player, MSG_CANNOT_OPEN, NamedTextColor.RED);
            Logger.getLogger("InfernalClash")
                    .warning("[IC] 界面：打开选卡界面时没有 matchId，打不开（它是本局的 id）");
            return false;
        }
        if (!canOpen(player)) {
            return false;
        }
        return open(MENU_MATCH_DECK, player, matchId, Math.max(0, page));
    }

    /**
     * ★★ <b>打开「投降」界面</b>（{@code docs/02} §6.1 ＋ 【Q9】；2026-10-17 第十二轮新增）。
     *
     * <h2>唯一的调用点</h2>
     * {@code game/CardCastListener}：玩家手持 1 号格的「**投降**」物品（{@code BATTLE_ITEM_MATERIAL}
     * = <b>{@code CLOCK}</b>，用户指定）右键时。★ 在它之前那段代码只会给玩家一句
     * 「战斗菜单还没做」的提示（{@code match.battle-menu-pending}）—— 那条缺陷就是 {@code docs/27} §D7。
     *
     * <p>★ 与 {@link #openDeckPicker(Player, String)} 同一形状：必须带 {@code matchId}
     * （一名玩家可能在多场里 —— 【Q44】），而「现在能不能投降」由<b>界面自己</b>在渲染时判
     * （那里能把原因说给玩家听）。
     *
     * @param matchId 本局的 id（= MGC 房间名）；{@code null} / 空白 ⇒ 拒绝并提示
     * @return 是否真的打开了（{@code false} ⇒ 已给玩家一句可读提示 + 控制台一条 WARN）
     */
    public static boolean openBattleMenu(Player player, String matchId) {
        if (matchId == null || matchId.isBlank()) {
            tell(player, MSG_CANNOT_OPEN, NamedTextColor.RED);
            Logger.getLogger("InfernalClash")
                    .warning("[IC] 界面：打开投降界面时没有 matchId，打不开（它是本局的 id）");
            return false;
        }
        if (!canOpen(player)) {
            return false;
        }
        return open(MENU_BATTLE, player, matchId);
    }

    /**
     * ★★ <b>打开 U3 解锁确认界面</b>（{@code docs/28} 第 27 / 33 条；2026-10-17 批次 3 后半新增）。
     *
     * <h2>三个调用点（都在图鉴格里点「未拥有」的卡）</h2>
     * <ol>
     *   <li>{@link DeckPresetMenu}（U2「图鉴与卡组」，{@code /incl cards}）——
     *       ★ 玩家真正会用的那一个；</li>
     *   <li>{@link CodexMenu}（U5 只读图鉴，{@code /incl codex}）；</li>
     *   <li>{@link MatchDeckMenu}（U4 对局内选卡，准备阶段）。</li>
     * </ol>
     *
     * <h2>★ 槽位口径（12 / 14）以 Q4 为准</h2>
     * 用户先答「3×9，第二行第 3、7 格」，随后在 Q4 里被追问「这与上一轮 E26 的
     * 『对称排布』（12 / 14）冲突，以哪个为准」⇒ 答「<b>b</b>」⇒ <b>保持 12 / 14</b>，
     * 「3、7」作废。权威来源见 {@link UnlockConfirmLayout}。
     *
     * <h2>★★ 2026-10-21：第三个参数从「页码」换成 {@link UnlockReturn}（回程目标）</h2>
     * 现场两条缺陷（用户逐字）：
     * 「<b>合成卡牌界面点击”取消“后应该返回图鉴界面，而不是直接退出</b>」
     * ＋「<b>我在解锁过一张卡后，返回图鉴界面时，所有未解锁卡又会变成”？？？“</b>」。
     * 根因同一个：**回程写死成 U5 只读图鉴**。而 U5 当时还留着旧的 {@code "???"} 占位画法
     * （U2/U4 早已改成「黑色混凝土 + 真名 + 真效果」）⇒ 玩家一回来就看见一屏问号。
     * ⇒ 「回到哪个界面 + 第几页 + 第几套预设 / 哪一场」由**打开方**一起传下去
     * （判据集中在 {@link UnlockReturn} 一处，界面自己不再猜）。
     *
     * @param player 打开界面的玩家
     * @param cardId 要解锁的卡 id；{@code null} / 空白 ⇒ 拒绝并提示
     * @param target ★ <b>关掉之后回到哪儿</b>（{@link UnlockReturn}）；
     *               {@code null} ⇒ 按 {@link UnlockReturn#codex(int) codex(0)} 兜底
     * @return 是否真的打开了
     */
    public static boolean openUnlockConfirm(Player player, String cardId, UnlockReturn target) {
        if (cardId == null || cardId.isBlank()) {
            tell(player, MSG_CANNOT_OPEN, NamedTextColor.RED);
            Logger.getLogger("InfernalClash")
                    .warning("[IC] 界面：打开解锁确认界面时没有 cardId，打不开（它是「哪张卡」）");
            return false;
        }
        if (!canOpen(player)) {
            return false;
        }
        return open(MENU_UNLOCK_CONFIRM, player, cardId, target == null ? UnlockReturn.codex(0) : target);
    }

    /**
     * ★★★ <b>从解锁确认界面「原地返回」</b>（2026-10-21 新增；取消与确认成功共用这一条路）。
     *
     * <h2>为什么要有一个统一入口，而不是各界面自己拼</h2>
     * 回程要复原 3~4 个参数（哪个界面 / 第几页 / 第几套预设 / 哪一场），
     * 三张图鉴族界面的打开方式各不相同（{@code openDeck} / {@code openCodexQuietly} /
     * {@code openDeckPicker}）。若在界面里各写一份 ⇒ **第二处事实来源**，
     * 迟早出现「从 U2 进来的却回到 U5」这种错位（那就是本次的现场缺陷）。
     * ⇒ 判据只有 {@link UnlockReturn#kind()} 一处，本方法只做分发。
     *
     * <p>★ <b>失败方向</b>：`target == null` ⇒ 回只读图鉴第 1 页（与改动前行为一致）；
     * 权限在两次点击之间被撤掉 ⇒ {@link #canOpen} 会给玩家一句话并返回 {@code false}
     * （★ 绝不静默什么都不发生）。
     *
     * @param player 玩家
     * @param target 回程目标（{@code null} 兜底成只读图鉴第 1 页）
     * @return 是否真的打开了
     */
    public static boolean reopenCodex(Player player, UnlockReturn target) {
        UnlockReturn where = target == null ? UnlockReturn.codex(0) : target;
        return switch (where.kind()) {
            case DECK -> openDeck(player, where.presetIndex(), where.page());
            case MATCH_DECK -> openDeckPicker(player, where.matchId(), where.page());
            case CODEX -> openCodexQuietly(player, where.page());
        };
    }

    /**
     * ★ <b>安静地重新打开图鉴到某一页</b>（解锁成功之后用；失败不打扰玩家）。
     *
     * <p>★ 与 {@link #openCodex(Player)} 的差别只有一点：<b>它不重复做权限/档案检查的提示</b>
     * （那三道刚刚已经过了一遍，再报一次会让玩家看到两句意思相同的话）。
     * 但**仍然**过 {@link #canOpen}：玩家可能在两次点击之间被撤了权限。
     */
    public static boolean openCodexQuietly(Player player, int page) {
        if (!canOpen(player)) {
            return false;
        }
        return open(MENU_CODEX, player, Math.max(0, page));
    }

    /**
     * 打开前的三道检查：**权限 → 插件就绪 → 进度档案拿得到**。
     *
     * <p>顺序刻意是这样：权限是最常见、也最该先说清的一条；
     * 「拿不到进度」用 {@code ProfileStore.ensure} 判（它会为新玩家**当场造**内存档案，
     * 所以正常玩家不会被这句挡住；只有 gameId 未注册 / 没有数据桶时才失败）。
     */
    private static boolean canOpen(Player player) {
        if (player == null) {
            return false;
        }
        if (!player.hasPermission(InfernalMenu.PERM_PLAY)) {
            tell(player, MSG_NO_PERMISSION, NamedTextColor.RED);
            return false;
        }
        InfernalClashPlugin instance = InfernalClashPlugin.getInstance();
        if (instance == null) {
            tell(player, MSG_CANNOT_OPEN, NamedTextColor.RED);
            Logger.getLogger("InfernalClash")
                    .warning("[IC] 界面：插件实例还没就绪（onEnable 未完成？），界面打不开");
            return false;
        }
        try {
            if (!instance.profileStore().ensure(player.getUniqueId(), player.getName())) {
                tell(player, MSG_CANNOT_OPEN, NamedTextColor.RED);
                return false;
            }
        } catch (Throwable t) {
            instance.getLogger().warning("[IC] 界面：检查进度档案时出错，界面打不开：" + t);
            tell(player, MSG_CANNOT_OPEN, NamedTextColor.RED);
            return false;
        }
        return true;
    }

    /**
     * 真正调 MGC 开门面 —— ★ <b>异常绝不外抛</b>。
     *
     * <p>{@code MenuFacade.open} 未注册时返回 {@code false}（不抛），但构造器抛出的异常会被它
     * 包成 {@code RuntimeException}（{@code javap} 实测）⇒ 这里必须自己兜住，
     * 否则「点一下按钮」就足以把该事件的所有监听器一起炸掉。
     */
    private static boolean open(String id, Player player, Object... args) {
        try {
            if (MenuFacade.open(id, player, args)) {
                return true;
            }
            Logger.getLogger("InfernalClash")
                    .warning("[IC] 界面：MenuFacade 说 id " + id + " 没注册，界面打不开");
            tell(player, MSG_CANNOT_OPEN, NamedTextColor.RED);
            return false;
        } catch (Throwable t) {
            Logger.getLogger("InfernalClash")
                    .log(Level.WARNING, "[IC] 界面：打开 " + id + " 失败：" + t, t);
            tell(player, MSG_CANNOT_OPEN, NamedTextColor.RED);
            return false;
        }
    }

    /** 给玩家提示（MiniMessage 解析 + 兜底颜色；绝不外抛）。 */
    private static void tell(Player player, String text, NamedTextColor color) {
        if (player == null) {
            return;
        }
        try {
            player.sendMessage(MiniMessage.miniMessage().deserialize(text).colorIfAbsent(color));
        } catch (Throwable ignored) {
            // 连提示都发不出去时只放弃这次提示，不影响打开流程的返回值
        }
    }
}
