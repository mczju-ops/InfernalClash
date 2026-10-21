package io.mczju.infernalclash.objective;

import io.mczju.infernalclash.arena.Point;

import java.util.List;

/**
 * ★★★ <b>对局收尾时清理「留在战场上的弹射物」</b>（2026-10-18 新增）。
 *
 * <h2>用户原话（逐字，第 7 条）</h2>
 * 「**弹射物在游戏结束后不会清理**」；我追问口径后用户选定
 * 「**B. 收尾时按「竞技场范围内 + 无主」扫一遍**」。
 *
 * <h2>为什么需要它（现状）</h2>
 * 归属账本只登记<b>召唤物本体</b> ⇒ 箭 / 火球 / 潜影贝子弹这些「第三方实体」不在账本里，
 * 收尾时按账本清场自然清不到它们 ⇒ 对局结束之后它们还在飞、还会落地/插在方块上。
 *
 * <h2>★★ 铁律 10 的四问（这是"按范围删东西"，所以必须逐条回答）</h2>
 * <ol>
 *   <li><b>范围界定</b>：不是整个世界，而是<b>本场 6 个目标物点位的外接球</b>
 *       ＋ {@value #MARGIN} 格余量（见 {@link #bounds(List)}）。点位来自
 *       {@code arenas.yml}（本局真实战场），不是"主世界"、不是 lobby；</li>
 *   <li><b>失败方向</b>：点位不全 / 世界不存在 / 算不出球 ⇒ <b>一个都不删 + 告警</b>
 *       （绝不退化成"按世界清"）；</li>
 *   <li><b>归属判定</b>：只删<b>弹射物</b>（{@code Projectile} / {@code AbstractArrow} /
 *       {@code Fireball} / {@code ShulkerBullet} 等），并且<b>跳过本场账本里登记过的实体</b>
 *       （那是目标物或召唤物本身，由既有对账路径负责）；玩家永远不在范围内；</li>
 *   <li><b>可对账</b>：删了几个、跳过几个（账本内 / 非弹射物）都打一行带数量的日志。</li>
 * </ol>
 *
 * <p>★ 本类<b>不碰 Bukkit</b>（只算球心与半径）⇒ 可离线单测。
 */
public final class ProjectileSweep {

    /** 外接球余量（格）：目标物点位之外的箭通常就落在这一圈里。 */
    public static final double MARGIN = 16.0;

    /** 半径护栏（格）：点位写坏时也不许一次吞掉半个世界。 */
    public static final double MAX_RADIUS = 256.0;

    private ProjectileSweep() {
    }

    /**
     * 本局战场的外接球。
     *
     * @param points 本场目标物点位（信标 ×2 + 塔 ×4）
     * @return 球心 / 半径 / 世界名；点位为空或世界不一致 ⇒ {@code null}（fail-safe：什么都不删）
     */
    public static Bounds bounds(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        String world = null;
        double sx = 0;
        double sy = 0;
        double sz = 0;
        int count = 0;
        for (Point point : points) {
            if (point == null) {
                continue;
            }
            if (world == null) {
                world = point.world();
            } else if (world == null || !world.equals(point.world())) {
                return null;   // 跨世界 ⇒ 算不出一个球（fail-safe）
            }
            sx += point.x();
            sy += point.y();
            sz += point.z();
            count++;
        }
        if (count == 0 || world == null) {
            return null;
        }
        double cx = sx / count;
        double cy = sy / count;
        double cz = sz / count;
        double max = 0;
        for (Point point : points) {
            if (point == null) {
                continue;
            }
            double dx = point.x() - cx;
            double dy = point.y() - cy;
            double dz = point.z() - cz;
            max = Math.max(max, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        double radius = Math.min(MAX_RADIUS, Math.max(1.0, max + MARGIN));
        return new Bounds(cx, cy, cz, radius, world);
    }

    /**
     * 一个「要按它扫」的球。
     *
     * @param x/y/z  球心
     * @param radius 半径（格）
     * @param world  世界名（{@code null} 不会出现 —— {@link #bounds} 已过滤）
     */
    public record Bounds(double x, double y, double z, double radius, String world) {
    }
}
