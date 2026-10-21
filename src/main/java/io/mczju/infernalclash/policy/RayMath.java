package io.mczju.infernalclash.policy;

/**
 * ★★ <b>射线几何（纯函数）</b> —— 2026-10-21 新增，给「穿透射线」用。
 *
 * <h2>为什么单独抽出来</h2>
 * 「这根射线打到了谁」= 每个敌人的**身体中心到线段的距离**是否小于命中半径 ——
 * 这是一个**纯几何判据**，把它做成纯函数就能离线逐格断言
 * （{@code RayMathTest}），执行层（{@code battle/RayAttackService}）只负责取坐标与结算伤害。
 *
 * <p>★ 为什么用「点到线段的距离」而不是「沿着射线每 0.25 格查一次附近实体」：
 * 后者要遍历世界（**铁律 10 禁止的广域查询**），而且步长会漏掉侧身擦过的单位。
 * 线段距离是**解析解**：与步长无关、可复现、可离线验证。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>线段 = 射线从 {@code (ax, ay, az)} 出发、朝 {@code (bx, by, bz)} 方向、
 *       长度 {@code length}；传 {@code b} 为线段终点即可；</li>
 *   <li>投影落在 {@code [0, length]} 之外 ⇒ 取端点距离（即"在射线身后/超出射程"不算命中）；</li>
 *   <li>退化输入（长度为 0 / NaN / 无穷）⇒ 返回 {@link Double#POSITIVE_INFINITY}
 *       = <b>不算命中</b>（失败方向 = 少打一个，而不是乱打一片）。</li>
 * </ul>
 */
public final class RayMath {

    private RayMath() {
    }

    /**
     * 点到线段（起点 + 方向 + 长度）的距离。
     *
     * @param px/py/pz 点的坐标
     * @param ax/ay/az 射线起点
     * @param dx/dy/dz 射线**方向**（内部会归一化；长度 0 ⇒ 判为不命中）
     * @param length   射线长度（格；必须 &gt; 0）
     * @return 点到线段的距离；任何非法输入 ⇒ {@link Double#POSITIVE_INFINITY}
     */
    public static double pointToRayDistance(double px, double py, double pz,
                                            double ax, double ay, double az,
                                            double dx, double dy, double dz,
                                            double length) {
        if (!finite(px) || !finite(py) || !finite(pz)
                || !finite(ax) || !finite(ay) || !finite(az)
                || !finite(dx) || !finite(dy) || !finite(dz)
                || !finite(length) || length <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double norm = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(norm > 0.0) || Double.isInfinite(norm)) {
            return Double.POSITIVE_INFINITY;
        }
        double ux = dx / norm;
        double uy = dy / norm;
        double uz = dz / norm;
        // 点相对起点的向量
        double vx = px - ax;
        double vy = py - ay;
        double vz = pz - az;
        // 沿射线的投影长度（负数 = 在起点身后）
        double t = vx * ux + vy * uy + vz * uz;
        double clamped = Math.max(0.0, Math.min(length, t));
        double cx = ax + ux * clamped;
        double cy = ay + uy * clamped;
        double cz = az + uz * clamped;
        double ddx = px - cx;
        double ddy = py - cy;
        double ddz = pz - cz;
        double distance = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        return finite(distance) ? distance : Double.POSITIVE_INFINITY;
    }

    /**
     * 点到线段（两端点形态）的距离 —— 只是 {@link #pointToRayDistance} 的糖。
     *
     * @return 距离；两端点重合 / 非法 ⇒ {@link Double#POSITIVE_INFINITY}
     */
    public static double pointToSegmentDistance(double px, double py, double pz,
                                                double ax, double ay, double az,
                                                double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!finite(length) || length <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return pointToRayDistance(px, py, pz, ax, ay, az, dx, dy, dz, length);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
