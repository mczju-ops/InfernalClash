package io.mczju.infernalclash.arena;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★★ <b>「只绑一个点就能绑定整座竞技场」的几何表</b>（纯函数）—— 2026-10-18 新增。
 *
 * <h2>用户原话（逐字，2026-10-18）</h2>
 * 「**原来的 arena set 太繁琐了，实际需要绑定多个竞技场供玩家同时游玩，按现在的指令
 * 一个一个绑过于繁琐，我需要你帮我重写一个 arena 设置指令，只需要绑定一个点就能成功绑定
 * 其他所有点。**」——随后给了 11 条逐条坐标（{@code docs/24} §14）。
 *
 * <h2>基准点（唯一输入）</h2>
 * 用户：「输入 {@code arena set 【竞技场名】} 绑定竞技场的<b>西南角</b>…
 * 该点坐标假设为 <b>(0, -96)</b>…接下来的坐标描述都是基于该坐标的。」
 * ⇒ 本类把那个角记作 <b>基准格</b> {@code (EXAMPLE_BASE_X, EXAMPLE_BASE_Z)} = {@code (0, -96)}，
 * 其余 18 个点全部是「<b>基准 + 偏移</b>」。
 *
 * <h2>★★ 偏移表（逐条对应你那 11 条，一条不多一条不少）</h2>
 * <table border="1">
 *   <caption>偏移 = 用户给的坐标 − 基准 (0,−96)</caption>
 *   <tr><th>点位键</th><th>你给的坐标</th><th>偏移 (dx, dz)</th><th>说明</th></tr>
 *   <tr><td>{@code beacon.blue}</td><td>(2,−80)(2,−81)(3,−80)(3,−81) 四格正中心交界</td>
 *       <td>(3, 16)</td><td>四格交界线 = x 3.0 / z −80.0</td></tr>
 *   <tr><td>{@code beacon.red}</td><td>(44,−80)(44,−81)(45,−80)(45,−81)</td>
 *       <td>(45, 16)</td><td>同上（x 45.0）</td></tr>
 *   <tr><td>{@code tower.blue.left} / {@code .right}</td><td>(10,−91) / (10,−70)</td>
 *       <td>(10, 5) / (10, 26)</td><td>★ 左/右 = <b>本方视角</b>（蓝方面向红方时的左手边）</td></tr>
 *   <tr><td>{@code tower.red.left} / {@code .right}</td><td>(38,−70) / (38,−91)</td>
 *       <td>(38, 26) / (38, 5)</td><td>★ 镜像对称（红方面向蓝方时的左手边）</td></tr>
 *   <tr><td>{@code spawn.blue} / {@code spawn.red}</td><td>(10,−80)(10,−81) / (38,−80)(38,−81) 两格正中央</td>
 *       <td>(10.5, 16) / (38.5, 16)</td><td>★ 两格交界 = z −80.0、x 取方块中心（10.5 / 38.5）；
 *       朝向 = 面向对岸（蓝 +X / 红 −X）</td></tr>
 *   <tr><td>{@code half.blue.1/2}</td><td>(1,−95) / (21,−66)</td><td>(1, 1) / (21, 30)</td><td>矩形两角</td></tr>
 *   <tr><td>{@code river.1/2}</td><td>(22,−95) / (25,−66)</td><td>(22, 1) / (25, 30)</td><td>矩形两角（河宽 4）</td></tr>
 *   <tr><td>{@code half.red.1/2}</td><td>(26,−95) / (46,−66)</td><td>(26, 1) / (46, 30)</td><td>矩形两角</td></tr>
 *   <tr><td>{@code bridge.1.a/b}</td><td>(22,−92) / (25,−90)</td><td>(22, 4) / (25, 6)</td><td>桥 1 的两角</td></tr>
 *   <tr><td>{@code bridge.2.a/b}</td><td>(22,−71) / (25,−69)</td><td>(22, 25) / (25, 27)</td><td>桥 2 的两角</td></tr>
 * </table>
 *
 * <h2>★ 三件本类**刻意不做**的事（都用"不写"来表达）</h2>
 * <ol>
 *   <li><b>{@code spectate} 不推导</b>：你没给观战点 ⇒ 命令不写它（它不在「对局必需」的 12 个里，
 *       缺了不影响开局）。<b>绝不</b>凭空造一个"竞技场上空"——那是替你决定观感（铁律 8/11）；</li>
 *   <li><b>y 不推导</b>：全部取**基准点的 y**（你只绑一个点，高度只能来自它）。
 *       若某几个点该在不同高度，那是世界状态，需要另外的输入；</li>
 *   <li><b>世界名不推导</b>：全部取基准点的世界（跨世界的竞技场不是本项目支持的形态）。</li>
 * </ol>
 *
 * <p>★ 纯类：不碰 Bukkit / 文件 ⇒ 离线逐点断言（{@code arena/ArenaLayoutTest}）。
 * ★ 偏移表的**顺序** = {@link ArenaConfig#allKeys()} 的顺序（写盘顺序与界面顺序同源）。
 */
public final class ArenaLayout {

    /** 用户例子里的基准格 X（西南角）。 */
    public static final double EXAMPLE_BASE_X = 0.0;

    /** 用户例子里的基准格 Z（西南角）。 */
    public static final double EXAMPLE_BASE_Z = -96.0;

    /**
     * 一个推导出来的点位。
     *
     * @param key 点位键（{@link ArenaConfig#allKeys()} 里的裸键）
     * @param x   相对基准的 X 偏移（格）
     * @param z   相对基准的 Z 偏移（格）
     * @param yaw 朝向（度）；只有出生点有意义，其余为 0
     */
    public record Offset(String key, double x, double z, float yaw) {
    }

    /**
     * 朝向：蓝方面向对岸（+X = 东）⇒ yaw {@code -90}；红方面向对岸（−X = 西）⇒ {@code +90}。
     *
     * <p>★ 这两个数是<b>从偏移表推出来的</b>，不是另立的常量：蓝方的目标在 +X 方向
     * （红方半场 x 更大），红方反之 ⇒ 出门就该朝着那边看。
     */
    public static final float SPAWN_YAW_BLUE = -90.0f;

    /** 红方出生点朝向（面向 −X = 西）。 */
    public static final float SPAWN_YAW_RED = 90.0f;

    /**
     * 偏移表（18 个点位，**保序** = {@link ArenaConfig#allKeys()} 的顺序，缺 {@code spectate}）。
     *
     * <p>★ 这张表就是「你给的 11 条坐标」的唯一落点：改一条 =
     * 改这里 + 改 {@code ArenaLayoutTest} 里对应的那一条断言（两个地方都会立刻变红）。
     */
    private static final List<Offset> OFFSETS = List.of(
            new Offset("beacon.red", 45.0, 16.0, 0.0f),
            new Offset("beacon.blue", 3.0, 16.0, 0.0f),
            new Offset("tower.red.left", 38.0, 26.0, 0.0f),
            new Offset("tower.red.right", 38.0, 5.0, 0.0f),
            new Offset("tower.blue.left", 10.0, 5.0, 0.0f),
            new Offset("tower.blue.right", 10.0, 26.0, 0.0f),
            new Offset("spawn.red", 38.5, 16.0, SPAWN_YAW_RED),
            new Offset("spawn.blue", 10.5, 16.0, SPAWN_YAW_BLUE),
            new Offset("half.red.1", 26.0, 1.0, 0.0f),
            new Offset("half.red.2", 46.0, 30.0, 0.0f),
            new Offset("half.blue.1", 1.0, 1.0, 0.0f),
            new Offset("half.blue.2", 21.0, 30.0, 0.0f),
            new Offset("river.1", 22.0, 1.0, 0.0f),
            new Offset("river.2", 25.0, 30.0, 0.0f),
            new Offset("bridge.1.a", 22.0, 4.0, 0.0f),
            new Offset("bridge.1.b", 25.0, 6.0, 0.0f),
            new Offset("bridge.2.a", 22.0, 25.0, 0.0f),
            new Offset("bridge.2.b", 25.0, 27.0, 0.0f));

    private ArenaLayout() {
    }

    /** 全部偏移（**保序**、不可变）。 */
    public static List<Offset> offsets() {
        return OFFSETS;
    }

    /** 会被推导出来的点位键（**保序**；不含 {@code spectate}）。 */
    public static List<String> derivedKeys() {
        List<String> keys = new ArrayList<>(OFFSETS.size());
        for (Offset offset : OFFSETS) {
            keys.add(offset.key());
        }
        return List.copyOf(keys);
    }

    /**
     * 由**一个基准点**推出整座竞技场的点位表。
     *
     * @param world 基准点所在世界（全部点位都用它）
     * @param baseX 基准格 X（玩家站的那一格的**最小 X 角**，即 {@code floor(player.x)}）
     * @param baseY 基准点 Y（全部点位都用它 —— 见类 javadoc 的第 2 条）
     * @param baseZ 基准格 Z（{@code floor(player.z)}）
     * @return 点位表（**保序**、不可变；键 = {@link #derivedKeys()}）
     * @throws IllegalArgumentException 世界名为 {@code null} / 空
     */
    public static Map<String, Point> derive(String world, double baseX, double baseY, double baseZ) {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("基准点没有世界名，推不出点位表"
                    + "（绝不猜一个世界：那会把竞技场绑到别处）");
        }
        Map<String, Point> points = new LinkedHashMap<>();
        for (Offset offset : OFFSETS) {
            points.put(offset.key(),
                    new Point(world, baseX + offset.x(), baseY, baseZ + offset.z(), offset.yaw(), 0.0f));
        }
        return java.util.Collections.unmodifiableMap(points);
    }

    /**
     * 一个基准点与整张表的<b>自检</b>：所有点是否都落在同一世界、且都在基准点附近
     * 一段合理范围内（防止「站错地方绑了一整座 500 格外的竞技场」）。
     *
     * <p>★ 上限取 {@value #MAX_EXTENT} 格：用户给的这张表最大偏移是 46 格，
     * 200 格已经是"明显站错地方"的量级；超过它 ⇒ 命令**拒绝**并让操作者确认位置。
     *
     * @return 用不上（表本身很小时）⇒ {@code true}
     */
    public static boolean withinReasonableExtent(Map<String, Point> points) {
        if (points == null || points.isEmpty()) {
            return false;
        }
        for (Point point : points.values()) {
            if (point == null) {
                return false;
            }
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (Point point : points.values()) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z());
            maxZ = Math.max(maxZ, point.z());
        }
        return maxX - minX <= MAX_EXTENT && maxZ - minZ <= MAX_EXTENT;
    }

    /** 「一座竞技场最多能有多大」的护栏（格）；见 {@link #withinReasonableExtent}。 */
    public static final double MAX_EXTENT = 200.0;
}
