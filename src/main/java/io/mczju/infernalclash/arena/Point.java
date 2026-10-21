package io.mczju.infernalclash.arena;

import java.util.Objects;

/**
 * 一个世界内的坐标点（纯数据）。
 *
 * <h2>为什么是一个泛用坐标，而不是「信标 / 塔」等各自的类型</h2>
 * 见 {@code docs/09-UI设计.md} §2.2：InfernalGuess 的点位是「硬编码 3 个 + left/right 二分」，
 * 这个假设联动在五处；本项目改成**通用命名点位表** {@code Map<String, Point>}，
 * 加点位就不必改五处代码。
 *
 * <h2>★ 本类只依赖 {@code java.util}（连 Bukkit 都不碰）</h2>
 * 因此必然可离线单测，也能安全地被 UI 层、指令层、对局层共用
 * （{@code docs/03-配置规范.md} 的纪律：除写入路径外全部不碰服务端）。
 *
 * <h2>★ 朝向（yaw/pitch）是「点位属性」，不是「显示细节」</h2>
 * 出生点/观战落点需要玩家**面朝竞技场**，所以朝向与坐标一起存。
 * 不关心朝向的场景（如信标、塔、半场边界）用 {@link #of(String, double, double, double)}。
 *
 * @param world 世界名（非空；实际取值由 {@link ArenaConfig} 的校验保证）
 * @param x     X 坐标
 * @param y     Y 坐标
 * @param z     Z 坐标
 * @param yaw   水平朝向（度；{@link #of} 置 0）
 * @param pitch 俯仰（度；{@link #of} 置 0）
 */
public record Point(String world, double x, double y, double z, float yaw, float pitch) {

    /** 只有世界与坐标，朝向归零。 */
    public static Point of(String world, double x, double y, double z) {
        return new Point(world, x, y, z, 0.0f, 0.0f);
    }

    /**
     * ★ <b>格坐标 = {@code floor}，不是强制转 {@code int} 截断</b>。
     *
     * <p>两者在正半轴一致（{@code 0.9 → 0}），但在负半轴**完全不同**：
     * 截断把 {@code -0.5} 变成 {@code 0}（跑到了隔壁格），而 {@code floor} 得 {@code -1}。
     * 竞技场可能坐落在负坐标区，用截断会让「这一格属于哪个区域」在 x=0 与 z=0 两条线上错位，
     * 表现为「岩浆河/半场的判定莫名其妙偏移一格」——这类缺陷不报错、只在跨零轴时出现。
     *
     * <p>另外 {@link Math#floor} 顺带处理了「坐标巨大到 {@code int} 溢出」这个隐患：
     * 截断会静默绕回一个随机格，{@code floor} 后强转同样会绕回，但**至少取值方向是可推的**。
     */
    public int cellX() {
        return (int) Math.floor(x);
    }

    /** ★ 与 {@link #cellX()} 同一口径（{@code floor}，负数往负无穷取整）。 */
    public int cellZ() {
        return (int) Math.floor(z);
    }

    /** 是否与另一点在同一个世界（{@code other == null} → {@code false}）。 */
    public boolean sameWorldAs(Point other) {
        return other != null && Objects.equals(world, other.world());
    }

    /**
     * 与另一点的**距离平方**（不比较不同世界的点）。
     *
     * <h2>★ 为什么返回平方而不是开方</h2>
     * 索敌/范围判定只需要比较大小，{@code sqrt} 既慢又引入浮点误差
     * （{@code docs/07-卡牌系统设计.md} §5 的目标系统就按距离平方排序）。
     *
     * <h2>★ 为什么不同世界是抛异常而不是返回 {@code Double.MAX_VALUE}</h2>
     * 返回极大值是一种**静默失败**：本意是「跳过它」，实际可能被下游的
     * {@code min(...)} / 排序吸收成一个看起来正常的结果，于是「拿两个世界的点算距离」
     * 这种接线错误（例如把红方信标与蓝方塔配到一起，而两边配置写的是不同世界）
     * 会一路静默到游戏里才表现为「AI 行为古怪」。这里直接抛，让接线错误在第一次运行时炸出来。
     *
     * @throws IllegalArgumentException 两点不在同一个世界
     */
    public double distanceSquared(Point other) {
        if (other == null) {
            throw new IllegalArgumentException("不能对 null 点求距离");
        }
        if (!sameWorldAs(other)) {
            throw new IllegalArgumentException(
                    "不能跨世界求距离：world=" + world + " 与 world=" + other.world() + "");
        }
        double dx = x - other.x();
        double dy = y - other.y();
        double dz = z - other.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
