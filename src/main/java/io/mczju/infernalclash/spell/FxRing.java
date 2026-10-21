package io.mczju.infernalclash.spell;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ★ <b>粒子「环」的格集合（纯函数）</b> —— 2026-10-17 第十轮新增（缺陷 P1-a 的「无特效」）。
 *
 * <h2>为什么环 = 形状的「边界」</h2>
 * {@code docs/19-卡牌总表.md} 对法术特效的措辞是「<b>火焰粒子环</b>」「<b>打击粒子环</b>」
 * （第 69 / 70 行）—— 一句话同时说了两件事：<b>粒子</b>与<b>环</b>。
 * 「环」= 形状的最外圈，而不是把整个范围铺满（铺满会糊成一片、也看不出范围大小）。
 * <p>★ 判据取的是**与形状无关**的那一种定义：<b>有一个四邻格不在集合里的格</b>。
 * 好处：圆形 / 长方形 / 扇形 / 单体四种形状**共用同一条判据**
 * （圆形取「半径那一圈」、长方形取「边框」、扇形取「弧与两条边」），
 * 不需要为每种形状各写一遍（那种写法迟早分叉）。
 *
 * <p>★ 纯类：只吃 (x,z) 整数格，不碰 Bukkit ⇒ 可离线逐格断言。
 */
public final class FxRing {

    private FxRing() {
    }

    /**
     * 取格集合的**边界**（保序，与输入顺序一致）。
     *
     * @param cells 形状覆盖的格（来自 {@link ShapeMath#cells}）；{@code null} / 空 ⇒ 空
     * @return 边界的格（★ 单格集合的边界就是它自己）
     */
    public static List<ShapeMath.Cell> boundary(List<ShapeMath.Cell> cells) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        Set<Long> present = new LinkedHashSet<>();
        for (ShapeMath.Cell cell : cells) {
            present.add(key(cell.x(), cell.z()));
        }
        List<ShapeMath.Cell> result = new ArrayList<>();
        for (ShapeMath.Cell cell : cells) {
            if (isBoundary(cell.x(), cell.z(), present)) {
                result.add(cell);
            }
        }
        return List.copyOf(result);
    }

    // ==================================================================================
    // ★★★ 2026-10-18（用户第 1 条）：「环特效依然不明显，建议除了平面一圈环外加几圈
    //     在 y 轴向上偏移一些的」⇒ 环从「地面上的一圈」变成「竖直摞起来的几圈」
    //     （视觉上像一个半透明的圆柱），第一人称低头看也能看见。
    //
    //   为什么要做成纯函数：环的**几何**是「最容易写错、又最难在游戏里数清楚」的东西
    //   （层数、层间距、点数、闭合性），所以一律留在这里逐格断言
    //   （{@code FxRingTest}）。渲染层只负责把坐标喂给 {@code spawnParticle}。
    // ==================================================================================

    /** 默认为一个环摞几层（★ 观感常量，用户 2026-10-18 要求「几圈」；要改只改这一处）。 */
    public static final int DEFAULT_LEVELS = 4;

    /** 默认层间距（格）：4 层 × 0.75 ⇒ 最上面那一圈在 +2.25 格。 */
    public static final double DEFAULT_LEVEL_SPACING = 0.75;

    /** 层数护栏：卡里/将来若把层数调大，粒子量也必须有界。 */
    public static final int MAX_LEVELS = 8;

    /** 一圈最少几个点（半径再小也不许出现"一个点都不撒"）。 */
    public static final int MIN_POINTS = 16;

    /** 每 1 格半径撒几个点（半径 6 ⇒ 48 点，肉眼是连续的一圈）。 */
    public static final double POINTS_PER_BLOCK = 8.0;

    /** 一圈最多几个点（护栏：半径调到 100 时也别一次撒几千点）。 */
    public static final int MAX_POINTS = 256;

    /**
     * 一个环要摞几层、每层往上偏多少格（<b>纯函数</b>）。
     *
     * <p>★ 返回值**保序**且第 0 项恒为 {@code 0.0}（= 贴地那一圈）——
     * 渲染层直接按它循环即可，不需要自己判"要不要画底层"。
     *
     * @param levels  层数（{@code <= 0} ⇒ 按 1 处理；上限 {@value #MAX_LEVELS}）
     * @param spacing 层间距（格；{@code <= 0} 或非有限值 ⇒ 用 {@value #DEFAULT_LEVEL_SPACING}）
     */
    public static List<Double> levelOffsets(int levels, double spacing) {
        int count = Math.min(MAX_LEVELS, Math.max(1, levels));
        double step = (spacing > 0 && Double.isFinite(spacing)) ? spacing : DEFAULT_LEVEL_SPACING;
        List<Double> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(i * step);
        }
        return List.copyOf(result);
    }

    /** 默认层偏移（4 层 / 0.75 格）—— 生产路径全走这一条。 */
    public static List<Double> levelOffsets() {
        return levelOffsets(DEFAULT_LEVELS, DEFAULT_LEVEL_SPACING);
    }

    /**
     * 半径为 {@code radius} 的一圈点在 (x, z) 平面上的<b>偏移</b>（纯函数）。
     *
     * <p>★ 返回的每一项都是 {@code {xOffset, zOffset}}（相对圆心），
     * 且<b>首尾不重复</b>（第 0 个点与最后一个点角度不同 ⇒ 不会在一个位置撒两点）。
     *
     * @param radius 半径（格；{@code <= 0} ⇒ 空列表 —— 半径 0 没有"环"可言，由调用方改撒一团）
     * @param points 点数（{@code <= 0} ⇒ 按 {@link #pointsFor(double)} 自动算）
     */
    public static List<double[]> circle(double radius, int points) {
        if (!(radius > 0) || !Double.isFinite(radius)) {
            return List.of();
        }
        int count = points > 0 ? Math.min(MAX_POINTS, points) : pointsFor(radius);
        List<double[]> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            result.add(new double[] {Math.cos(angle) * radius, Math.sin(angle) * radius});
        }
        return List.copyOf(result);
    }

    /** 半径 → 这一圈该撒几个点（夹在 [{@value #MIN_POINTS}, {@value #MAX_POINTS}]）。 */
    public static int pointsFor(double radius) {
        if (!(radius > 0) || !Double.isFinite(radius)) {
            return MIN_POINTS;
        }
        int points = (int) Math.round(radius * POINTS_PER_BLOCK);
        return Math.min(MAX_POINTS, Math.max(MIN_POINTS, points));
    }

    /** 四邻里至少有一个不在集合里 ⇒ 它在边界上（四邻 = 上下左右，不含对角）。 */
    private static boolean isBoundary(int x, int z, Set<Long> present) {
        return !present.contains(key(x + 1, z))
                || !present.contains(key(x - 1, z))
                || !present.contains(key(x, z + 1))
                || !present.contains(key(x, z - 1));
    }

    /** 与 {@code SpellCaster} 同一套打包口径：把 (x,z) 压成一个 long。 */
    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
