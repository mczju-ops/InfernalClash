package io.mczju.infernalclash.match;

/**
 * 阵营。
 *
 * <h2>★ 红/蓝由**加入顺序**决定（2026-10-15 用户裁定）</h2>
 * **第一个进房间的（房主）= 蓝**；后进的 = 红。
 * 这也是信标光束配色（`RED` / `BLUE`）的取值依据 —— 不需要额外配置键。
 *
 * <p>顺序判定的唯一入口是 {@link #byJoinOrder(int)}，**不要**在别处再写一遍 if。
 */
public enum Side {

    /** 红方（后进房间的那位）。 */
    RED,

    /** 蓝方（第一个进房间的，即房主）。 */
    BLUE;

    /**
     * 按房间加入顺序判定阵营。
     *
     * @param index 加入顺序下标（**从 0 开始**；0 = 房主）
     * @return {@code index == 0} → {@link #BLUE}，否则 {@link #RED}
     */
    public static Side byJoinOrder(int index) {
        return index == 0 ? BLUE : RED;
    }

    /** 对方阵营。 */
    public Side other() {
        return this == RED ? BLUE : RED;
    }
}
