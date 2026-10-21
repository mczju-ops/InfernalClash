package io.mczju.infernalclash.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ <b>四个界面的「格子算术」</b>（纯函数）：第几行第几列 ↔ 箱子 slot。
 *
 * <h2>为什么是纯函数</h2>
 * {@code docs/09-UI设计.md} §1「共同纪律」第 4 条：<b>槽位 ↔ 语义的解析抽成静态纯函数</b>
 * （InfernalGuess / PocketMine 的一致做法），监听器只调它。
 * {@code docs/04} §16.12.2 也写着「格子布局 → 纯函数（『第 i 张卡在第几行第几列』可离线单测）」。
 * ⇒ 本类<b>只依赖 {@code java.util}</b>，不 import 任何 Bukkit / Paper / MGC 类，
 * 因此必定能在普通 JUnit 里离线断言（见 {@code MenuLayoutTest}）。
 *
 * <h2>★ 权威出处：{@code docs/09-UI设计.md} 的逐格布局</h2>
 * <table border="1">
 *   <caption>本类读取的四个界面</caption>
 *   <tr><th>界面</th><th>docs/09 出处</th><th>行数 / 格数</th></tr>
 *   <tr><td>U2 图鉴 + 卡组预设</td><td>§3.2 逐格映射</td><td>6 行 / 54 格</td></tr>
 *   <tr><td>U4 比赛内选卡（准备阶段）</td><td>§5.2 最终逐格布局（【Q19】定案）</td><td>6 行 / 54 格</td></tr>
 *   <tr><td>U5 局外商店</td><td>§4.2 逐格映射</td><td>4 行 / 36 格</td></tr>
 *   <tr><td>U1 竞技场编辑</td><td>§2 —— ★ <b>文档只给交互模型，没有逐格布局</b></td><td>未给出</td></tr>
 * </table>
 *
 * <h2>★ 纪律「不许发明布局」</h2>
 * 每个方法的 javadoc 都写明<b>出处</b>（§几 + 那一节怎么说的）。
 * {@code docs/09} 里<b>确实没写清</b>的地方一律<b>不猜</b>：
 * <ul>
 *   <li>越界 ⇒ {@link IllegalArgumentException}，消息含<b>期望范围与实际值</b>；</li>
 *   <li>「按文档能给的部分实现 + 明写 ⚠️ 文档未给出」的地方，见
 *       {@link #presetSlot(int, int)}（超出「只显示第一张卡」⇒ -1）与
 *       {@link #borderSlots(int)}（只有 4 行与 6 行两种布局有文档）。</li>
 * </ul>
 *
 * <h2>★ 两处命名口径的说明（避免读者被 U 编号误导）</h2>
 * <ul>
 *   <li>{@code docs/09} 的 <b>U1 是「竞技场编辑」</b>（§2）、<b>U4 才是「比赛内选卡」</b>（§5）；
 *       <b>U3 是「解锁确认二级界面」</b>（§1 的载体表），<b>图鉴在 U2/U4 里</b>；
 *       <b>U5 是局外商店</b>（§4）。本类一律以 {@code docs/09} 的编号为准。</li>
 *   <li>「图鉴」在 U2（§3.2）与 U4（§5.2）里是<b>同一段槽位 {@code 18}~{@code 44}</b>，
 *       §5.2 明写「两个界面可共用同一套 Layout」⇒ {@link #codexSlot(int)} 两边通用。</li>
 * </ul>
 *
 * <p>⚠️ <b>本类不含任何面向玩家的文本</b>（R76：文案一律走 {@code display.yml}）——
 * 它只算格子号。
 */
public final class MenuLayout {

    private MenuLayout() {
    }

    // ------------------------------------------------------------------ 箱子网格
    /**
     * 箱子每行 9 格。
     *
     * <p>出处：{@code docs/09} 开头「原版箱子最多 6 行 = 54 格」；§3.2 的逐格映射
     * （{@code 0}~{@code 53}）也是按 9 格一行编号的。
     */
    public static final int ROW_SIZE = 9;

    /**
     * 箱子最多 6 行。
     *
     * <p>出处：{@code docs/09} 开头表 —— 「原版 {@code InventoryType.CHEST} 上限」+
     * 「MGC 的 {@code Menu#getRows()} 只允许 1~6」（{@code javap} 实测）。
     */
    public static final int MAX_ROWS = 6;

    /** 单个箱子能表达的格数上限：6 × 9 = 54（{@code docs/09} 开头）。 */
    public static final int MAX_SLOTS = MAX_ROWS * ROW_SIZE;

    /**
     * {@link #slot(int, int)}：第 {@code row} 行（<b>0 起</b>）第 {@code column} 列（<b>0 起</b>）的 slot。
     *
     * <p>口径 = <b>行主序</b>（{@code row * 9 + column}），与 {@code docs/09} §3.2 / §4.2 / §5.2
     * 的「槽位」编号完全一致（那些表格里的 {@code 0} / {@code 11} / {@code 45} / {@code 53}
     * 就是本方法算出来的值）。
     *
     * <p>★ 文档自己的「行内第几格」是 <b>1 起数</b>（§5.2：【Q34】「从 1 数」），
     * 换算关系 {@code 全局 0 起 = 行首槽位(9) + 行内 1 起 − 1} —— 本方法收的是 <b>0 起</b>的列号。
     *
     * @param row    行号（0 起，合法 0~5 —— 箱子最多 6 行）
     * @param column 列号（0 起，合法 0~8 —— 每行 9 格）
     * @return {@code row * 9 + column}
     * @throws IllegalArgumentException 行或列越界（消息含期望范围与实际值）
     */
    public static int slot(int row, int column) {
        if (row < 0 || row >= MAX_ROWS) {
            throw new IllegalArgumentException("slot：行号越界，期望 0~" + (MAX_ROWS - 1)
                    + "（箱子最多 6 行，docs/09 开头；MGC Menu#getRows() 只允许 1~6），实际 row=" + row);
        }
        if (column < 0 || column >= ROW_SIZE) {
            throw new IllegalArgumentException("slot：列号越界，期望 0~" + (ROW_SIZE - 1)
                    + "（箱子每行 9 格，docs/09 开头），实际 column=" + column);
        }
        return row * ROW_SIZE + column;
    }

    /**
     * slot 所在的行号（0 起）—— {@link #slot(int, int)} 的逆运算。
     *
     * @param slot 全局槽位（0 起，合法 0~53）
     * @throws IllegalArgumentException slot 越界（消息含期望范围与实际值）
     */
    public static int rowOf(int slot) {
        requireSlot("rowOf", slot);
        return slot / ROW_SIZE;
    }

    /**
     * slot 所在的列号（0 起）—— {@link #slot(int, int)} 的逆运算。
     *
     * @param slot 全局槽位（0 起，合法 0~53）
     * @throws IllegalArgumentException slot 越界（消息含期望范围与实际值）
     */
    public static int columnOf(int slot) {
        requireSlot("columnOf", slot);
        return slot % ROW_SIZE;
    }

    // ------------------------------------------------------------------ U4 第 1 行：8 个卡槽
    /**
     * 确认卡组按钮的槽位 = {@code 0}（第 1 行第 1 格）。
     *
     * <p>出处：{@code docs/09} §5.2 表格第 1 行「{@code 0} —— <b>确认卡组</b>按钮」。
     */
    public static final int CONFIRM_DECK_SLOT = 0;

    /**
     * 8 个卡槽的第一个槽位 = {@code 1}（第 1 行第 2 格）。
     *
     * <p>出处：{@code docs/09} §5.2 表格第 1 行「{@code 1}~{@code 8} —— 8 个空卡槽」；
     * 同段 §5.5【Q38】裁定「<b>真的空卡槽</b>，让玩家当场构建卡组（读法 B）」。
     */
    public static final int DECK_FIRST_SLOT = 1;

    /**
     * 卡槽数量 = 8（一张卡组 8 张卡）。
     *
     * <p>出处：{@code docs/09} §3.1 / §3.2 的「8 个卡位」、§5.2 的「8 个空卡槽」（✅【Q38】读法 B）。
     * 两处都是 8 ⇒ 本常量同时服务 U2 与 U4。
     */
    public static final int DECK_SIZE = 8;

    /**
     * U4 选卡界面：第 {@code index} 张卡（<b>0 起</b>）放在哪个 slot。
     *
     * <p>出处：{@code docs/09} <b>§5.2</b> 第 1 行「{@code 1}~{@code 8} —— <b>8 个空卡槽</b>
     * —— ✅【Q38】真空槽，让玩家当场构建卡组」（§3.2 里同一段的写法是「{@code 1}~{@code 8}：
     * 8 个预设卡位」）。
     *
     * <p>★ 命名口径：{@code docs/09} 的 <b>U4</b> 才是「比赛内选卡界面」（§5），
     * <b>U1 是竞技场编辑</b>（§2，文档未给逐格布局）—— 本方法按 §5.2 实现。
     *
     * @param index 卡在卡组里的序号（0 起）
     * @return {@code 1 + index}；{@code index >= 8}（界面容量已满）⇒ <b>-1</b>
     *         （调用方据此拒绝放入 / 提示卡组已满）
     * @throws IllegalArgumentException {@code index < 0}
     */
    public static int cardSlot(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("cardSlot：index 不能为负（本方法收 0 起的序号），实际 index="
                    + index);
        }
        if (index >= DECK_SIZE) {
            return -1;   // 容量之外：docs/09 §5.2 只给了 8 个卡槽
        }
        return DECK_FIRST_SLOT + index;
    }

    /**
     * U4 选卡界面能放几张卡 = <b>8</b>。
     *
     * <p>出处：{@code docs/09} §5.2 第 1 行的「8 个空卡槽」（✅【Q38】读法 B：真的空卡槽）。
     * ★ 这个数<b>不是</b>我选的：它就是文档里那一段的格子数。
     */
    public static int cardCapacity() {
        return DECK_SIZE;
    }

    // ------------------------------------------------------------------ U4 第 2 行：三个预设按钮
    /**
     * 预设按钮的第一个槽位 = {@code 11}（第 2 行<b>行内第 3 格</b>，1 起数）。
     *
     * <p>出处：{@code docs/09} §5.2 表格第 2 行「★ {@code 11} / {@code 13} / {@code 15}
     * （= 行内第 3 / 5 / 7 格，1 起数）—— ✅【Q19】三个预设按钮」，
     * 并【2026-10-15 用户给定确切栏位】：「三个预设按钮分别在第二行 3、5、7 号栏位（1 开始数）」。
     */
    public static final int PRESET_FIRST_SLOT = 11;

    /** 预设按钮之间的步长 = 2（{@code 11 → 13 → 15}，{@code docs/09} §5.2 的 {@code ··■·■·■··}）。 */
    public static final int PRESET_STEP = 2;

    /**
     * 预设套数上限 = 3。
     *
     * <p>出处：{@code docs/09} §3.3「✅【Q12】已裁定：<b>预设数量上限 = 3</b>
     * （与 U4 第 2 行的三个预设按钮对应）」，§5.2 表格里的 {@code 11}/{@code 13}/{@code 15} 也正好 3 个。
     */
    public static final int PRESET_LIMIT = 3;

    /**
     * U2/U4 的「第 {@code presetIndex} 套预设」按钮在哪个 slot。
     *
     * <p>出处：{@code docs/09} <b>§5.2</b> 第 2 行「{@code 11} / {@code 13} / {@code 15}
     * = 三个预设按钮（预设 1 / 2 / 3），<b>每个只显示该预设的第一张卡</b>」（✅【Q19】+ ✅【Q12】）。
     * 这是 {@code docs/09} 里<b>唯一</b>给出「三套预设 × 各自槽位」的地方。
     *
     * <h2>⚠️ 文档未给出的部分（不猜）</h2>
     * <ul>
     *   <li>{@code cardIndex != 0} ⇒ <b>返回 -1</b>：§5.2 明写「<b>只显示其中的第一张卡，不展示其他卡</b>」
     *       ⇒ 第 2 张及以后<b>没有槽位</b>。这是有意的「文档未给出」，不是遗漏。</li>
     *   <li>{@code docs/09} §3.2（U2）那一行的 {@code 1}~{@code 8} 是<b>单套</b>卡位
     *       （只有「保存预设」按钮 0，没有预设选择器）⇒ 那里<b>没有</b> {@code presetIndex} 维度；
     *       若调用方要的是「U2 里第 c 张卡的位置」，应改用 {@link #cardSlot(int)}。</li>
     * </ul>
     *
     * @param presetIndex 第几套预设（0 起，合法 0~2 —— 【Q12】上限 3）
     * @param cardIndex   该套里的第几张卡（0 起；★ 只有 0 有槽位）
     * @return {@code 11 + 2 * presetIndex}；{@code cardIndex != 0} ⇒ <b>-1</b>
     * @throws IllegalArgumentException {@code presetIndex} 越界或 {@code cardIndex < 0}
     */
    public static int presetSlot(int presetIndex, int cardIndex) {
        if (presetIndex < 0 || presetIndex >= PRESET_LIMIT) {
            throw new IllegalArgumentException("presetSlot：presetIndex 越界，期望 0~"
                    + (PRESET_LIMIT - 1) + "（docs/09 §3.3【Q12】：预设上限 3），实际 presetIndex="
                    + presetIndex);
        }
        if (cardIndex < 0) {
            throw new IllegalArgumentException("presetSlot：cardIndex 不能为负（本方法收 0 起的序号），实际 cardIndex="
                    + cardIndex);
        }
        if (cardIndex != 0) {
            // ⚠️ docs/09 §5.2（【Q19】）：预设按钮只显示第一张卡 ⇒ 第 2 张起没有槽位。
            return -1;
        }
        return PRESET_FIRST_SLOT + presetIndex * PRESET_STEP;
    }

    // ------------------------------------------------------------------ U2 / U4 共用：图鉴
    /**
     * 图鉴（卡牌列表）的第一个槽位 = {@code 18}（第 3 行第 1 格）。
     *
     * <p>出处：{@code docs/09} §3.2「{@code 18}~{@code 44} —— <b>卡牌图鉴 27 格</b>（第 3~5 行）」
     * 与 §5.2「{@code 18}~{@code 44} —— 卡牌图鉴 27 格（3 行 × 9）」。
     */
    public static final int CODEX_FIRST_SLOT = 18;

    /**
     * 图鉴每页 27 格（3 行 × 9）。
     *
     * <p>出处：{@code docs/09} §3.2 / §5.2 的「卡牌图鉴 <b>27</b> 格」；
     * §3.2 的注解还把它与 PocketMine 的 {@code PokedexLayout} 对齐（那边 {@code PAGE_SIZE=36}）。
     */
    public static final int CODEX_PAGE_SIZE = 27;

    /**
     * 图鉴：第 {@code index} 张卡（<b>0 起</b>）在当前页的哪个 slot。
     *
     * <p>出处：{@code docs/09} §3.2 / §5.2 的「{@code 18}~{@code 44}：卡牌图鉴 27 格」。
     * ★ 两个界面<b>共用同一段槽位</b>（§5.2 明写「两个界面可共用同一套 Layout」）
     * ⇒ 本方法对 U2 与 U4 都成立。
     *
     * <p>⚠️ 命名口径：{@code docs/09} 的 <b>U3 是「解锁确认二级界面」</b>（§1 载体表），
     * <b>图鉴在 U2/U4 里</b>（§3、§5）—— 本方法按 §3.2 / §5.2 实现。
     *
     * @param index 当前页里的第几张卡（0 起）
     * @return {@code 18 + index}；{@code index >= 27}（本页放满）⇒ <b>-1</b>
     *         （调用方据此翻页；翻页按钮见 {@link #PREV_PAGE_SLOT} / {@link #NEXT_PAGE_SLOT}）
     * @throws IllegalArgumentException {@code index < 0}
     */
    public static int codexSlot(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("codexSlot：index 不能为负（本方法收 0 起的序号），实际 index="
                    + index);
        }
        if (index >= CODEX_PAGE_SIZE) {
            return -1;   // 本页容量之外：docs/09 §3.2 / §5.2 只给了 18~44 这 27 格
        }
        return CODEX_FIRST_SLOT + index;
    }

    /**
     * 前一页按钮 = {@code 45}（第 6 行最左）。
     *
     * <p>出处：{@code docs/09} §3.2「{@code 45} —— <b>前一页</b> —— 第 6 行最左」；
     * §5.2 第 6 行同样是 {@code 45}（两界面共用，且与 PocketMine 的 {@code PREV_SLOT=45} 一致）。
     */
    public static final int PREV_PAGE_SLOT = 45;

    /**
     * 后一页按钮 = {@code 53}（第 6 行最右）。
     *
     * <p>出处：{@code docs/09} §3.2「{@code 53} —— <b>后一页</b> —— 第 6 行最右」；§5.2 第 6 行同。
     */
    public static final int NEXT_PAGE_SLOT = 53;

    // ------------------------------------------------------------------ U5 商店
    /**
     * U5 商店的行数 = 4（36 格）。
     *
     * <p>出处：{@code docs/09} §4.1「原文布局（照抄）」—— 第 1 行空 / 第 2 行三个购买按钮 /
     * 第 3 行空 / 第 4 行金币数与退出；§4.2 标题即「逐格映射（<b>4 行 = 36 格</b>）」。
     */
    public static final int SHOP_ROWS = 4;

    /**
     * 商店第一个商品的槽位 = {@code 12}（第 2 行<b>行内第 4 格</b>，1 起数）。
     *
     * <h2>★★ 为什么从 {@code 11} 改成 {@code 12}（2026-10-20 用户反馈）</h2>
     * 用户逐字：「**买卡包的商店里两个选项没有居中**」。
     * 取消「连开 30 包」之后只剩两个按钮，而它们仍占着原三个按钮的**左边两个**（{@code 11} / {@code 13}）
     * ⇒ 第 2 行（{@code 9}~{@code 17}）的中心列是 {@code 13}，两个按钮却在 {@code 11}/{@code 13}
     * ⇒ 视觉上整组偏左一格。
     * <p>居中 = 关于中心列**对称**的一对 ⇒ <b>{@code 12} / {@code 14}</b>
     * （{@code 12 + 14 = 26 = 2 × 13}；这条恒等式有测试钉住，见
     * {@code MenuLayoutTest#shopBuyButtonsAreCentred}）。
     * ★ 原来【Q34】那句「从 1 数 ⇒ {@code 11/13/15} 即为最终槽位」只对**三个**商品成立；
     * 商品数变了就要重算 —— 这是排版，**不是【Q34】被推翻**。
     */
    public static final int SHOP_FIRST_SLOT = 12;

    /** 商店商品的槽位步长 = 2（{@code 12 → 14}；原三档时是 {@code 11 → 13 → 15}）。 */
    public static final int SHOP_STEP = 2;

    /**
     * 商店的商品数 = <b>2</b>（买 1 / 10 个卡牌包）。
     *
     * <p>出处：{@code docs/09} §4.1 / §4.2 的原稿是「买 1 / 10 / 30」；★ <b>2026-10-20 用户裁定
     * 取消「连开 30 包」</b>（逐字「取消『连开30包』的商店选项：抽到的卡牌显示不全」）
     * ⇒ 商品数 3 → 2。
     * ★ 槽位随之**居中**：{@code 11}/{@code 13} → <b>{@code 12}/{@code 14}</b>
     * （用户同一天第二条反馈：「买卡包的商店里两个选项没有居中」）。
     * ★ 连带：{@code economy.yml} 的 {@code pack.price.30} 也一起去掉
     * （不许留「没有消费者的配置键」）；现场文件里那条走
     * {@code DisplayConfig} 的退役图标键通道（{@code icons.shop.buy30}）。
     */
    public static final int SHOP_ITEM_COUNT = 2;

    /**
     * 「你的金币数」展示格 = {@code 27}（第 4 行第 1 格，左下）。
     *
     * <p>出处：{@code docs/09} §4.2「{@code 27} —— <b>你的金币数</b>（展示用，左下）—— 第 4 行第 1 格」。
     */
    public static final int SHOP_BALANCE_SLOT = 27;

    /**
     * 「退出」按钮 = {@code 35}（第 4 行第 9 格，右下）。
     *
     * <p>出处：{@code docs/09} §4.2「{@code 35} —— <b>退出</b>（右下）—— 第 4 行第 9 格」。
     */
    public static final int SHOP_EXIT_SLOT = 35;

    /**
     * U5 局外商店：第 {@code index} 个商品在哪个 slot。
     *
     * <p>出处：{@code docs/09} §4.2 逐格映射（✅【Q34】「从 1 数 ⇒ {@code 11}/{@code 13}/{@code 15}
     * 即为最终槽位」）：{@code 11} = 购买 1 卡牌包、{@code 13} = 购买 10 卡牌包、
     * {@code 15} = 购买 30 卡牌包。
     *
     * <p>★ 命名口径：{@code docs/09} 的 <b>U5</b> 才是「局外商店」（§4），
     * <b>U4 是比赛内选卡</b>（§5）—— 本方法按 §4.2 实现。
     *
     * @param index 第几个商品（0 起：0 = 1 包 / 1 = 10 包 / 2 = 30 包）
     * @return {@code 11 + 2 * index}；{@code index >= 3} ⇒ <b>-1</b>
     * @throws IllegalArgumentException {@code index < 0}
     */
    public static int shopSlot(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("shopSlot：index 不能为负（本方法收 0 起的序号），实际 index="
                    + index);
        }
        if (index >= SHOP_ITEM_COUNT) {
            return -1;   // 文档只给了 3 个商品（docs/09 §4.2）
        }
        return SHOP_FIRST_SLOT + index * SHOP_STEP;
    }

    // ------------------------------------------------------------------ U3 战斗菜单（投降）
    /**
     * ★★ <b>U3「投降」界面（1 号快捷栏那个物品右键打开的界面）的行数 = 3</b>
     * （2026-10-17 第十二轮新增，缺陷 {@code docs/27} §D7；★ 用户当场裁定了 E26）。
     *
     * <h2>用户 2026-10-17 裁定（E26 结案，逐字）</h2>
     * > 「战斗菜单的物品改名『**投降**』，内部只有两个按钮『**确认投降**』和『**取消**』，**对称排布**」
     * ⇒ ① 界面标题 = **投降**（`match.surrender`）；
     * ② 两个按钮 = **确认投降**（{@link #BATTLE_SURRENDER_SLOT}）与 **取消**（{@link #BATTLE_CANCEL_SLOT}）；
     * ③ **对称** = 关于第 2 行中心列（{@code 13}）左右镜像。
     *
     * <h2>★ 这个行数**文档里没有**（如实说明）</h2>
     * 文档对它的全部规定只有两句：
     * <ul>
     *   <li>{@code docs/02} §6.1：「{@code 1} —— 「<b>战斗</b>」物品 = <b>战斗菜单</b>
     *       （内含<b>投降按钮</b>；「将来可能会加入功能」）」；</li>
     *   <li>{@code docs/09} §1：「U3 解锁确认（二级界面）—— 同上（<b>小箱子</b>）」——
     *       ★ 那一行说的是「解锁确认」，而 {@code docs/09} <b>通篇没有</b>这个界面的逐格布局。</li>
     * </ul>
     * ⇒ 取 <b>3 行（27 格）</b>：它是「<b>小</b>箱子」最直接的读法（1 行太窄、容易点到背包区；
     * 3 行是同类二级确认界面的常见尺寸），且两个按钮都落在第 2 行 —— **用户没有否决行数**
     * （他只指定了名字与"对称"），所以这一格**保持不变**（改起来只动本常量）。
     */
    public static final int BATTLE_ROWS = 3;

    /**
     * U3 投降菜单里「<b>确认投降</b>」按钮的槽位 = {@code 12}（第 2 行<b>行内第 4 格</b>）。
     *
     * <h2>★★ 2026-10-17 用户裁定（E26 结案）：「内部只有两个按钮『确认投降』和『取消』，对称排布」</h2>
     * 用户原话（逐字）：「战斗菜单的物品改名『**投降**』，内部只有两个按钮『**确认投降**』和『**取消**』，
     * **对称排布**」。
     *
     * <p>★ <b>「对称」在这里 = 关于本行中心列（第 5 列）左右镜像</b>：
     * 27 格箱子的第 2 行是 {@code 9}~{@code 17}，中心列 = {@code 13} ⇒
     * 两个按钮取 <b>{@code 12} 与 {@code 14}</b>（各距中心 1 格）。
     * <table border="1">
     *   <caption>为什么不是 {@code 11}/{@code 15}（我上一版取的）</caption>
     *   <tr><th>取法</th><th>距中心列 13</th><th>对称？</th><th>其余空位</th></tr>
     *   <tr><td>{@code 11} / {@code 15}</td><td>2 / 2</td><td>○ 也对称，但中间空了 <b>3</b> 格</td>
     *       <td>两侧各留 2 格</td></tr>
     *   <tr><td>★ {@code 12} / {@code 14}</td><td><b>1</b> / <b>1</b></td>
     *       <td>✅ <b>紧贴中心、镜像距离最小</b></td><td>左侧 3 格（9/10/11）、右侧 3 格（15/16/17）</td></tr>
     * </table>
     * ★ 用户既然点明了「对称排布」，取**紧贴中心**的那一版（也最符合"两个按钮、箱子正中"的直觉）。
     * ⚠️ 改这一格只动本常量与 {@link #BATTLE_CANCEL_SLOT}（＋逐格测试里的两行断言）。
     */
    public static final int BATTLE_SURRENDER_SLOT = 12;

    /**
     * U3 投降菜单里「<b>取消</b>」按钮的槽位 = {@code 14}（第 2 行<b>行内第 6 格</b>）。
     *
     * <p>★ 与 {@link #BATTLE_SURRENDER_SLOT}（{@code 12}）关于中心列 {@code 13} **左右镜像**
     * —— 这就是用户那句「**对称排布**」的落地。见上面那一格的说明（含"为什么不是 11/15"）。
     */
    public static final int BATTLE_CANCEL_SLOT = 14;

    /**
     * ★★ <b>U2（图鉴 + 卡组预设）里「下一个预设」按钮的槽位 = {@code 9}</b>
     * —— 2026-10-17 第十三轮新增，用户裁定。
     *
     * <h2>用户原话（逐字，{@code docs/24} §10 第 33 条之后的追加）</h2>
     * > 「图鉴里**只能设置一个预设卡组，没法切换预设卡组**，请在卡组的下一行
     * > （**应该是第二行**）添加『**下一个预设**』按钮」
     *
     * <h2>★ 与 {@code docs/09} §3.2 的冲突（如实说明）</h2>
     * {@code docs/09} §3.2 明写 U2 的**第 2 行整行是空的**（{@code 9}~{@code 17}），
     * 所以这个按钮**不是**文档里的东西 —— 它是**用户当场的追加裁定**
     * （我在反问 Q5 里建议放这一格，用户答「可以」）⇒ 以用户原话为准，
     * {@code docs/09} §3.2 已同步补记（★ 这是本项目唯一一处"第 2 行有东西"的 U2 格）。
     *
     * <p>★ 为什么是**第 2 行第 1 格**（而不是中间）：它与第 1 行第 1 格的「保存预设」（槽位 {@code 0}）
     * **同列**（都是行内第 1 格）⇒ 视觉上"左侧一列是操作按钮"，图鉴与卡位不受影响。
     */
    public static final int DECK_NEXT_PRESET_SLOT = 9;

    // ------------------------------------------------------------------ 边框 / 装饰位
    /**
     * 6 行界面的行数（U2 §3.2 与 U4 §5.2 都是 6 行）。
     *
     * <p>出处：{@code docs/09} §3.2「逐格映射（<b>6 行</b> = 54 格，刚好到上限）」、
     * §5.2「✅ 最终逐格布局（【Q19】裁定后 = <b>6 行 / 54 格</b>）」。
     */
    public static final int SELECT_ROWS = 6;

    /**
     * 该行数下**所有**已定义界面里都**不被任何组件占用**的 slot（升序、不可变）。
     *
     * <h2>为什么是「交集」—— 一个必须说清的口径</h2>
     * {@code docs/09} 里 <b>6 行</b>的界面有<b>两个</b>，而它们的空位集合<b>不同</b>：
     * <ul>
     *   <li>U2（§3.2）：{@code 9}~{@code 17}（第 2 行整行）+ {@code 46}~{@code 52} —— 16 格；</li>
     *   <li>U4（§5.2）：第 2 行只空 {@code 9}、{@code 10}、{@code 12}、{@code 14}、{@code 16}、{@code 17}
     *       （{@code 11}/{@code 13}/{@code 15} 是三个预设按钮）+ {@code 46}~{@code 52} —— 13 格。</li>
     * </ul>
     * 本方法的签名<b>只收行数</b>（收不到「是哪个界面」），所以取<b>交集</b>：
     * 这样无论调用方打开的是哪一个 6 行界面，落在这些 slot 上的点击都<b>不可能是功能格</b>
     * （反过来说，{@code 11}/{@code 13}/{@code 15} 在 U4 里是预设按钮 ⇒ <b>不算边框</b>，
     * 否则 {@link #isBorder(int, int)} 会把 U4 的按钮误判成装饰位）。
     */
    public static final List<Integer> SIX_ROW_BORDER_SLOTS;

    /**
     * 4 行（{@code docs/09} §4.2 U5 商店）界面的非功能格：{@code 36} − 5 = <b>31</b> 格。
     *
     * <p>依据：§4.1 的原文布局（第 1 行空 / 第 2 行三个购买按钮 / 第 3 行空 /
     * 第 4 行金币数与退出）+ §4.2 的三个商品槽 {@code 11}/{@code 13}/{@code 15}、
     * 金币格 {@code 27}、退出 {@code 35} ⇒ 其余格都不是组件。
     */
    public static final List<Integer> SHOP_BORDER_SLOTS;

    /**
     * 每个界面的**装饰 / 空位** slot（= 该行数下所有已定义布局的交集，升序、不可变）。
     *
     * <h2>★ 口径（为什么是这个数）</h2>
     * 「边框」= <b>不被任何组件占用的格</b>：{@code docs/09} 逐格映射里写作「空（可放页码 / 提示）」
     * 的那些格，加上 4 行商店里由 §4.1「整行空」推得的其余格。
     * <b>按钮不算边框</b>：它们是<b>功能格</b>（{@link #isBorder(int, int)} 必须为 {@code false}），
     * 否则「边框不与元素重叠」这条性质会把按钮自己判成装饰位。
     *
     * <h2>⚠️ 文档未给出的部分（不猜，直接抛）</h2>
     * {@code docs/09} 只画了 <b>4 行</b>（§4.2 U5）与 <b>6 行</b>（§3.2 U2 / §5.2 U4）两种布局。
     * 其余行数（1 / 2 / 3 / 5 行）<b>没有逐格布局</b> ⇒ 抛 {@link IllegalArgumentException}
     * （而不是返回一个我编出来的集合）。
     *
     * @param rows 界面行数；只支持 4（§4.2）与 6（§3.2 / §5.2）
     * @return 该行数下所有已定义布局都未占用的 slot（升序、不可变）
     * @throws IllegalArgumentException {@code rows} 不在 1~6，或该行数没有文档布局
     */
    public static List<Integer> borderSlots(int rows) {
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("borderSlots：rows 越界，期望 1~" + MAX_ROWS
                    + "（箱子最多 6 行，docs/09 开头；MGC Menu#getRows() 只允许 1~6），实际 rows=" + rows);
        }
        if (rows == SHOP_ROWS) {
            return SHOP_BORDER_SLOTS;
        }
        if (rows == SELECT_ROWS) {
            return SIX_ROW_BORDER_SLOTS;
        }
        // ⚠️ docs/09 未给出该行数的逐格布局 ⇒ 不猜。
        throw new IllegalArgumentException("borderSlots：⚠️ docs/09 未给出 " + rows
                + " 行的逐格布局（文档只写了 §4.2 的 4 行＝U5 商店，与 §3.2 / §5.2 的 6 行＝U2 / U4）"
                + "，按纪律不猜，直接抛；实际 rows=" + rows);
    }

    /**
     * 该 slot 在 {@code rows} 行的界面里是不是装饰 / 空位（{@link #borderSlots(int)} 的判据）。
     *
     * <p>依据：{@code docs/09} §1「共同纪律」第 3 条「越界 {@code getRawSlot()} 直接忽略」
     * —— 越界的 slot 在这里也是硬失败（配置 / 接线的错不该被静默吞掉）。
     *
     * @param slot 待判定的全局槽位（0 起）
     * @param rows 界面行数；只支持 4 与 6（见 {@link #borderSlots(int)}）
     * @throws IllegalArgumentException slot 越出 {@code rows * 9}，或该行数没有文档布局
     */
    public static boolean isBorder(int slot, int rows) {
        List<Integer> borders = borderSlots(rows);   // 先校验 rows（消息里带上行数的问题）
        int total = rows * ROW_SIZE;
        if (slot < 0 || slot >= total) {
            throw new IllegalArgumentException("isBorder：slot 越界，期望 0~" + (total - 1)
                    + "（" + rows + " 行 × 9 格 = " + total + " 格），实际 slot=" + slot);
        }
        return borders.contains(slot);
    }

    // ------------------------------------------------------------------ 内部
    /** slot 的范围校验（{@link #MAX_SLOTS} 格以内）。 */
    private static void requireSlot(String who, int slot) {
        if (slot < 0 || slot >= MAX_SLOTS) {
            throw new IllegalArgumentException(who + "：slot 越界，期望 0~" + (MAX_SLOTS - 1)
                    + "（6 行 × 9 格 = " + MAX_SLOTS + " 格，docs/09 开头），实际 slot=" + slot);
        }
    }

    /** {@code rows * 9} 格以内、且不在 {@code occupied} 里的 slot（升序）。 */
    private static List<Integer> complementOf(int rows, List<Integer> occupied) {
        List<Integer> out = new ArrayList<>();
        int total = rows * ROW_SIZE;
        for (int slot = 0; slot < total; slot++) {
            if (!occupied.contains(slot)) {
                out.add(slot);
            }
        }
        return List.copyOf(out);
    }

    /** 两个升序列表的交集（保序 —— 不依赖任何哈希迭代顺序）。 */
    private static List<Integer> intersect(List<Integer> left, List<Integer> right) {
        List<Integer> out = new ArrayList<>();
        for (int slot : left) {
            if (right.contains(slot)) {
                out.add(slot);
            }
        }
        return List.copyOf(out);
    }

    static {
        // U5（docs/09 §4.2）：组件格 = 购买按钮 × SHOP_ITEM_COUNT + 27（金币）+ 35（退出）
        // ★★ 2026-10-20：原来这里**硬编码三个**槽（`FIRST`、`+STEP`、`+2*STEP`）——
        //   取消「连开 30 包」后商品数变成 2，那第三格就成了一个"不存在的按钮占位"，
        //   会让「边框格」少算一格（11 被当成空位、16 被当成按钮）。
        //   ⇒ 改成**按 SHOP_ITEM_COUNT 循环**：以后商品数再变，这里不用跟着改。
        List<Integer> shopOccupied = new ArrayList<>();
        for (int i = 0; i < SHOP_ITEM_COUNT; i++) {
            shopOccupied.add(SHOP_FIRST_SLOT + i * SHOP_STEP);
        }
        shopOccupied.add(SHOP_BALANCE_SLOT);
        shopOccupied.add(SHOP_EXIT_SLOT);
        SHOP_BORDER_SLOTS = complementOf(SHOP_ROWS, shopOccupied);

        // U2（docs/09 §3.2）：0 保存预设 + 1~8 卡位 + 18~44 图鉴 + 45/53 翻页
        List<Integer> u2Occupied = new ArrayList<>();
        u2Occupied.add(CONFIRM_DECK_SLOT);
        for (int i = 0; i < DECK_SIZE; i++) {
            u2Occupied.add(DECK_FIRST_SLOT + i);
        }
        // ★★ 2026-10-17 第十三轮：U2 多了「下一个预设」按钮（用户裁定）⇒ 槽位 9 从此**不是**空位。
        //   ★ 这一步是必须的：`borderSlots(6)` 是「U2 与 U4 空位的交集」，
        //     而 9 在 U4 里本来就是空的（§5.2 第 2 行只点名 11/13/15）
        //     ⇒ 若这里不加 9，`isBorder(9, 6)` 仍会返回 true，
        //     于是「界面元素不该落在边框上」那条断言会把**我们自己新加的按钮**判成违规。
        u2Occupied.add(DECK_NEXT_PRESET_SLOT);
        for (int i = 0; i < CODEX_PAGE_SIZE; i++) {
            u2Occupied.add(CODEX_FIRST_SLOT + i);
        }
        u2Occupied.add(PREV_PAGE_SLOT);
        u2Occupied.add(NEXT_PAGE_SLOT);

        // U4（docs/09 §5.2）：U2 的全部 + 第 2 行的三个预设按钮 11/13/15
        List<Integer> u4Occupied = new ArrayList<>(u2Occupied);
        for (int preset = 0; preset < PRESET_LIMIT; preset++) {
            u4Occupied.add(PRESET_FIRST_SLOT + preset * PRESET_STEP);
        }

        // ★ 只收行数 ⇒ 取两个 6 行布局的交集（见 SIX_ROW_BORDER_SLOTS 的 javadoc）
        SIX_ROW_BORDER_SLOTS = intersect(
                complementOf(SELECT_ROWS, u2Occupied),
                complementOf(SELECT_ROWS, u4Occupied));
    }
}
