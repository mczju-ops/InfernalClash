package io.mczju.infernalclash.spell;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>两点之间的直线采样</b> —— 纯函数（2026-10-18 新增）。
 *
 * <h2>它对应哪一条用户反馈（逐字）</h2>
 * 「**防御塔、冰术师这种单体指向性的攻击特效也需要有从施法者到目标的直线粒子特效**」。
 *
 * <h2>为什么单独抽一个纯类</h2>
 * 「从 A 到 B 撒一串粒子」有三处要用（防御塔光束 / 能力攻击 / 将来别的指向性攻击），
 * 而它全是算术 ⇒ 放成纯函数后可以离线逐点断言（等距、落在段上、退化情形不产生 NaN）。
 * ★ 如果每个调用点各写一遍 {@code for (i…) lerp}，第一个漏掉"两端重合"的情形就会
 * 让 {@code spawnParticle} 收到 {@code NaN} 坐标 —— 客户端什么都不画，也**不会**报错
 * （本项目最反感的那种失败形状）。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>返回 {@code points} 个采样点（首点 ≈ 起点、末点 ≈ 终点），坐标是 {@code double[3]}；</li>
 *   <li>{@code points <= 0} ⇒ 空表（调用方自己决定怎么退化）；</li>
 *   <li>★ <b>两端重合</b>（长度 &lt; {@link #EPSILON}）⇒ 返回 {@code points} 个**同一点**
 *       （不除零、不产生 {@code NaN}）；</li>
 *   <li>★ {@code points == 1} ⇒ 只给**终点**（"最后落点"比"起点"更有意义）。</li>
 * </ul>
 */
public final class LineFx {

    /** 小于这个长度（格）就认为两点重合（避免除零）。 */
    public static final double EPSILON = 1.0e-6;

    private LineFx() {
    }

    /** 便捷入口：给坐标分量。 */
    public static List<double[]> line(double fromX, double fromY, double fromZ,
                                      double toX, double toY, double toZ, int points) {
        if (points <= 0) {
            return List.of();
        }
        List<double[]> out = new ArrayList<>(points);
        if (points == 1) {
            out.add(new double[]{toX, toY, toZ});
            return List.copyOf(out);
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        if (lengthSquared < EPSILON * EPSILON) {
            // ★ 两端重合 ⇒ 同一点重复 N 次（绝不除零）
            for (int i = 0; i < points; i++) {
                out.add(new double[]{toX, toY, toZ});
            }
            return List.copyOf(out);
        }
        double last = points - 1;
        for (int i = 0; i < points; i++) {
            double ratio = i / last;
            out.add(new double[]{fromX + dx * ratio, fromY + dy * ratio, fromZ + dz * ratio});
        }
        return List.copyOf(out);
    }

    /**
     * ★ <b>按「步长」算点数</b>：至少 {@code minPoints} 个、最多 {@code maxPoints} 个，
     * 按 {@code step} 格一个点。
     *
     * <p>用途：光束的可视长度随距离变化（远的目标不能只画 4 个点、近的也不该画 200 个）。
     * ★ 失败方向：{@code distance} 是 {@code NaN} / 负数 ⇒ 退回 {@code minPoints}
     * （绝不返回 {@code points <= 0} 那种"什么都不画"的结果）。
     */
    public static int pointsFor(double distance, double step, int minPoints, int maxPoints) {
        int min = Math.max(1, minPoints);
        int max = Math.max(min, maxPoints);
        if (!Double.isFinite(distance) || distance <= 0 || step <= 0) {
            return min;
        }
        int needed = (int) Math.ceil(distance / step);
        return Math.max(min, Math.min(max, needed));
    }

    /** 两点距离（{@code NaN} / 无穷 ⇒ {@code 0}）。 */
    public static double distance(double fromX, double fromY, double fromZ,
                                  double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double squared = dx * dx + dy * dy + dz * dz;
        if (!Double.isFinite(squared) || squared <= 0.0) {
            return 0.0;
        }
        return Math.sqrt(squared);
    }

    /**
     * 这两点之间值不值得画一条线（≙「这次攻击是不是指向性的、且打到了远处」）。
     *
     * <p>★ 判据只有一条：距离 ≥ {@code threshold}。近身攻击（同一个格子里）不画线 ——
     * 那条线在实机上只是一团粒子糊在自己身上，反而看不出"打了谁"。
     */
    public static boolean worthDrawing(double fromX, double fromY, double fromZ,
                                       double toX, double toY, double toZ, double threshold) {
        return distance(fromX, fromY, fromZ, toX, toY, toZ) >= threshold;
    }
}
