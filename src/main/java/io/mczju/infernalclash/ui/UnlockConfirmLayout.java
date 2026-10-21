package io.mczju.infernalclash.ui;

import java.util.List;

/**
 * ★★ <b>U3 解锁确认界面的逐格布局（纯函数）</b> —— 2026-10-17 批次 3 后半新增。
 *
 * <h2>出处（用户逐字裁定，两级）</h2>
 * <ol>
 *   <li>{@code docs/28} 第 33 条，用户答：「确认/取消只要一个简单的 <b>3×9</b>，
 *       第二行<b>第 3、7 格</b>（从 1 数）」；</li>
 *   <li>随后我在 {@code docs/28} §10.2 的 <b>Q4</b> 里指出：那与前一轮
 *       {@code docs/24} §9.5 的 <b>E26</b>（投降菜单「<b>对称排布</b>」）给出的
 *       <b>12 / 14</b> 冲突，问以哪个为准 ⇒ 用户答「<b>b</b>」
 *       ⇒ <b>保持 12 / 14</b>，句中的「3、7」<b>作废</b>。</li>
 * </ol>
 * ⇒ 权威布局 = <b>3 行 × 9 列 = 27 格</b>；<b>确认 = 12</b>／<b>取消 = 14</b>
 * （都在第 2 行，12 是第 2 行第 4 格、14 是第 2 行第 6 格 ⇒ 关于中点 <b>13 左右对称</b>，
 *  这就是那句「对称排布」的落点）。
 *
 * <h2>为什么「卡牌展示格 = 13」不在这份裁定里、却仍然要写下来</h2>
 * 用户给的是**两个按钮**的槽位，没有说卡牌本身的图标放哪。而一个确认界面里
 * 「确认什么」必须看得见 ⇒ 取 <b>13</b>（第二行的中点，两侧按钮对称环绕它），
 * 这是<b>我按已有裁定推出来的唯一自洽解</b>（12 / 14 的中点就是 13），
 * 并登记为**可被否决**（{@code docs/05} E11②的落地记录里写明）。
 *
 * <h2>纯函数</h2>
 * 本类不 import 任何 {@code org.bukkit.*}、不碰菜单实例 ⇒ 逐格断言可以在离线跑
 * （{@code UnlockConfirmLayoutTest}）。
 */
public final class UnlockConfirmLayout {

    /** 行数：{@code docs/28} 第 33 条「一个简单的 3×9」。 */
    public static final int ROWS = 3;

    /** 列数（= 一行的格数）。 */
    public static final int COLUMNS = 9;

    /** 总格数 = 3 × 9 = 27（一个「小箱子」的容量）。 */
    public static final int SIZE = ROWS * COLUMNS;

    /** 确认按钮（第二行第 4 格；由 {@code docs/24} §10.2 的 Q4 答复「b」定为 12）。 */
    public static final int CONFIRM_SLOT = 12;

    /** 取消按钮（第二行第 6 格；同上 ⇒ 与确认关于中点对称）。 */
    public static final int CANCEL_SLOT = 14;

    /**
     * 卡牌展示格（第二行中点）。
     *
     * <p>★ 用户没给这一格（见类 javadoc）⇒ 取 12 / 14 的中点 <b>13</b>，
     * 这是"对称排布"那条裁定下唯一自洽的位置。**可被否决**。
     */
    public static final int CARD_SLOT = 13;

    private UnlockConfirmLayout() {
    }

    /** 三个有东西的格（★ 保序：展示 → 确认 → 取消，便于逐格核对）。 */
    public static List<Integer> usedSlots() {
        return List.of(CARD_SLOT, CONFIRM_SLOT, CANCEL_SLOT);
    }

    /** 这个格是不是本界面会放东西的格。 */
    public static boolean isUsed(int slot) {
        return slot == CARD_SLOT || slot == CONFIRM_SLOT || slot == CANCEL_SLOT;
    }

    /** 是不是确认格（点击动作要用它判分支）。 */
    public static boolean isConfirm(int slot) {
        return slot == CONFIRM_SLOT;
    }

    /** 是不是取消格。 */
    public static boolean isCancel(int slot) {
        return slot == CANCEL_SLOT;
    }

    /**
     * 这个格是第二行的第几格（**从 1 数**，与用户说话的口径一致）。
     *
     * <p>它的用处是把「用户说的 3、7」与「实际用的 12、14」摆在一起可核对：
     * 12 = 第二行第 <b>4</b> 格、14 = 第 <b>6</b> 格 ⇒ 与那句作废的「3、7」差一格，
     * 正是本轮 Q4 澄清掉的那个冲突。
     *
     * @param slot 槽位；不在第二行 ⇒ 返回 {@code 0}（"不属于第二行"，不是第 0 格）
     */
    public static int columnOfSecondRow(int slot) {
        int secondRowStart = COLUMNS;
        int secondRowEnd = 2 * COLUMNS - 1;
        if (slot < secondRowStart || slot > secondRowEnd) {
            return 0;
        }
        return slot - secondRowStart + 1;
    }

    /** 一行 3×9 的摘要（日志与测试失败信息里用）。 */
    public static String describe() {
        return ROWS + "×" + COLUMNS + "（" + SIZE + " 格）：展示 = " + CARD_SLOT
                + "，确认 = " + CONFIRM_SLOT + "（第二行第 " + columnOfSecondRow(CONFIRM_SLOT)
                + " 格），取消 = " + CANCEL_SLOT + "（第二行第 " + columnOfSecondRow(CANCEL_SLOT)
                + " 格）";
    }
}
