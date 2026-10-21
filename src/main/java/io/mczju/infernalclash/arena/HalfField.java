package io.mczju.infernalclash.arena;

/**
 * ★ <b>半场矩形</b>（纯数据 + 纯函数）：由 <b>两个角点</b>构成，判定是 <b>闭区间</b>
 * （边线算己方）。
 *
 * <h2>它解决什么</h2>
 * R28「召唤卡<b>仅己方半场</b>」需要一个可以离线断言的判据（{@code docs/04} §16.7.4）。
 * 落地形态是「两个角点 + 格坐标整数比较」——{@code docs/03} §3.2 的 {@code arena-geometry}
 * 与 2026-10-15 的裁定都是这么定的：
 * <ul>
 *   <li>半场边界 = <b>两角矩形</b>（每方 2 个角 ⇒ {@code arenas.yml} 里 {@code half.<side>.1/2}）；</li>
 *   <li>判定<b>必须用格坐标</b>（{@link Point#cellX()} / {@link Point#cellZ()} 的 {@code floor} 口径），
 *       不是浮点 {@code Location} —— 否则站在格子边缘时判定会抖动；</li>
 *   <li>★ <b>边线算己方</b>（闭区间）—— 用户 2026-10-15 已裁定。</li>
 * </ul>
 *
 * <h2>★ 桥为什么不需要额外判定</h2>
 * {@code bridge-belonging: NONE} 说桥不归任何一方，而两座桥<b>横跨岩浆河</b>、
 * 位于两个半场矩形<b>之外</b> ⇒ 矩形判定天然就把它们排除了
 * （见 {@code docs/04} §16.7.4 的注解）。若将来把半场矩形画得越过河面，
 * 才需要在这里再减去桥的区域 —— <b>那时再加</b>，现在不加没有消费者的分支。
 *
 * <h2>★ 本类只依赖 {@code java.util} 与同包的 {@link Point}</h2>
 * 连 Bukkit 都不碰 ⇒ 必然可离线单测（{@code HalfFieldTest}）。
 * 「世界名对不对」这类需要服务端的问题不在这里 —— {@link ArenaConfig#half} 负责在
 * 配置损坏（两个角点异地）时<b>返回 {@code null}</b>，由调用方走 fail-safe 拒绝施放。
 *
 * @param world 世界名（非空）
 * @param minX  矩形的 X 下界（格坐标；<b>含</b>）
 * @param minZ  矩形的 Z 下界（格坐标；<b>含</b>）
 * @param maxX  矩形的 X 上界（格坐标；<b>含</b>）
 * @param maxZ  矩形的 Z 上界（格坐标；<b>含</b>）
 */
public record HalfField(String world, int minX, int minZ, int maxX, int maxZ) {

    /**
     * 严格校验（照 {@link ArenaConfig} 的风格：坏数据宁可当场炸，也不要静默变成一个「空矩形」）。
     *
     * <p>★ 为什么 {@code min > max} 必须抛：{@link #contains} 会<b>恒为 false</b>，
     * 于是表现为「整张地图都不能召唤」——一个不报错、只让玩法静默失效的状态。
     * 正确构造方式是 {@link #of(Point, Point)}（自动取 min/max，与两点顺序无关）。
     */
    public HalfField {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("半场矩形的世界名不能为空（world 来自 arenas.yml 的点位）");
        }
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("半场矩形的下界不能大于上界：minX=" + minX + " maxX=" + maxX
                    + " / minZ=" + minZ + " maxZ=" + maxZ
                    + "（请用 HalfField.of(a, b) 由两个角点构造，它会自动取 min/max）");
        }
    }

    /**
     * 由任意两个角点构造 —— <b>与两点的顺序无关</b>（自动取 min/max，走 {@code floor} 格坐标口径）。
     *
     * @throws IllegalArgumentException 某个角点为 {@code null}，或两点不在同一个世界
     */
    public static HalfField of(Point a, Point b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("半场矩形的两个角点都不能为 null");
        }
        if (!a.sameWorldAs(b)) {
            throw new IllegalArgumentException("半场矩形的两个角点必须在同一个世界：world="
                    + a.world() + " 与 world=" + b.world() + "（异世界的矩形是配置事故）");
        }
        return new HalfField(a.world(),
                Math.min(a.cellX(), b.cellX()), Math.min(a.cellZ(), b.cellZ()),
                Math.max(a.cellX(), b.cellX()), Math.max(a.cellZ(), b.cellZ()));
    }

    /**
     * 该格是否落在半场内 —— ★ <b>闭区间：四条边线都算在内</b>。
     *
     * <p>用格坐标（{@link Point#cellX()} 的 {@code floor} 口径），<b>不是</b>浮点坐标：
     * 用浮点比较会让「站在边线上」这个状态在客户端坐标抖动时来回翻转。
     */
    public boolean contains(int cellX, int cellZ) {
        return cellX >= minX && cellX <= maxX && cellZ >= minZ && cellZ <= maxZ;
    }

    /** X 方向的格数（<b>含两端</b>）：{@code maxX - minX + 1}。 */
    public int sizeX() {
        return maxX - minX + 1;
    }

    /** Z 方向的格数（<b>含两端</b>）：{@code maxZ - minZ + 1}。 */
    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** 日志用的一行摘要，例如 {@code world [0,0]~[23,31]（24×32 格）}。 */
    public String describe() {
        return world + " [" + minX + "," + minZ + "]~[" + maxX + "," + maxZ + "]（"
                + sizeX() + "×" + sizeZ() + " 格）";
    }
}
