package io.mczju.infernalclash.objective;

/**
 * ★ 射程判据（<b>纯函数</b>）：25×25 = <b>格数型范围</b>（C15）。
 *
 * <p>判据是「格坐标之差的绝对值 {@code <= halfExtent}」，<b>不是</b>浮点距离 ——
 * {@code docs/04} §16.6.3 明确写着「<b>绝不能写成浮点半径 12.5</b>」。
 * 两者的差别在**边角**上最明显（同一条 halfExtent = 12）：
 * <ul>
 *   <li>{@code (12, 12)}：欧氏距离约 16.97（&gt; 12.5，圆会判不中）⇒ 方形<b>必须命中</b>；</li>
 *   <li>{@code (9, 9)}：欧氏距离约 12.73（&gt; 12.5，圆会判不中）⇒ 方形<b>必须命中</b>；</li>
 *   <li>{@code (13, 12)}：单轴越界 1 格 ⇒ <b>必须不命中</b>（方形不是「对角线更长」的圆）。</li>
 * </ul>
 * 也就是说：方形把整个 25×25 的格子都算进来，但**每条轴都不许超过 12**。
 *
 * <p>本类只做整数比较，不碰 Bukkit / MGC ⇒ 可以离线单测（{@code BeamRangeTest}）。
 * 「塔的光束只打敌方」这类**归属**判定不在这里 —— 那要靠 PDC 阵营，
 * 属于边界层（见 {@code docs/04} §16.6.5 的 scope proof）。
 */
public final class BeamRange {

    /** ★ 25×25 对应的 halfExtent：{@code 25 = 2 × 12 + 1}（含中心格）。 */
    public static final int SQUARE_25_HALF_EXTENT = 12;

    /** 25×25 的边长（格数）—— 用于自检「名字说 25，实际也必须是 25」。 */
    public static final int SQUARE_25_SIDE = 25;

    private BeamRange() {
    }

    /**
     * 方形射程（格）：{@code |dx| <= halfExtent && |dz| <= halfExtent}，<b>闭区间</b>
     * （边线上算命中）。
     *
     * @param targetCellX 目标所在格的 X 坐标（方块格，不是浮点 Location）
     * @param targetCellZ 目标所在格的 Z 坐标
     * @param centerCellX 中心（塔）所在格的 X 坐标
     * @param centerCellZ 中心（塔）所在格的 Z 坐标
     * @param halfExtent  半边长（格）；25×25 ⇒ 12
     */
    public static boolean inSquare(int targetCellX, int targetCellZ,
                                   int centerCellX, int centerCellZ, int halfExtent) {
        return Math.abs(targetCellX - centerCellX) <= halfExtent
                && Math.abs(targetCellZ - centerCellZ) <= halfExtent;
    }

    /** 方形边长（格数）：{@code halfExtent=12 ⇒ 25}（含中心格）。用于日志与自检。 */
    public static int squareSide(int halfExtent) {
        return 2 * halfExtent + 1;
    }

    /**
     * ★ 25×25 必须恰好等于 {@code halfExtent=12}；不满足就抛 {@link IllegalArgumentException}
     * （防「名字说 25、实际 27」—— 射程写大 1 格的后果是「塔能打到河对岸」这类**看不出来**的玩法偏差）。
     */
    public static void requireSquare25(int halfExtent) {
        if (halfExtent == SQUARE_25_HALF_EXTENT) {
            return;
        }
        throw new IllegalArgumentException("25×25 是格数型范围（C15），halfExtent 必须恰好为 "
                + SQUARE_25_HALF_EXTENT + "，实际是 " + halfExtent
                + "（按 squareSide 算出的边长 = " + squareSide(halfExtent) + " 格，不是 25 格）");
    }
}
