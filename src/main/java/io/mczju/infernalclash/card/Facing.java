package io.mczju.infernalclash.card;

/**
 * 玩家朝向的<b>四方位</b>（纯函数，由 yaw 推出）。
 *
 * <p>{@code docs/04} §16.7.5：一组召唤物的位置 = 落点 + 相对偏移（{@code forward} / {@code right}
 * <b>相对玩家朝向</b>）⇒ 必须先把 {@code player.getLocation().getYaw()} 变成方位，
 * 再把「前 / 右」两个标量变成世界格增量。这一步是**纯函数** ⇒ 可离线单测（{@link FacingTest}），
 * 也是「朝向写反 ⇒ 召唤物跑到身后」这类**在游戏里很难数清楚**的错误的唯一防线。
 *
 * <h2>★ yaw → 方位（逐值钉死，不得改动）</h2>
 * <table border="1">
 *   <caption>Minecraft 的 yaw：0 = 正南（+Z）、90 = 正西（-X）、180 = 正北（-Z）、270 = 正东（+X）</caption>
 *   <tr><th>yaw</th><th>方位</th><th>前向量 (x,z)</th></tr>
 *   <tr><td>0</td><td>{@link #SOUTH}</td><td>(0, +1)</td></tr>
 *   <tr><td>90</td><td>{@link #WEST}</td><td>(-1, 0)</td></tr>
 *   <tr><td>180</td><td>{@link #NORTH}</td><td>(0, -1)</td></tr>
 *   <tr><td>-90 或 270</td><td>{@link #EAST}</td><td>(+1, 0)</td></tr>
 * </table>
 *
 * <p>做法 = <b>先把 yaw 归一化到 {@code [0,360)}，再按 45° 分界就近判定</b>：
 * 四个方位各占 90°，扇区是
 * {@code SOUTH = [0,45] ∪ (315,360)}、{@code WEST = (45,135]}、
 * {@code NORTH = (135,225]}、{@code EAST = (225,315]}。
 *
 * <p>★ <b>恰好落在 45° 整边界上时归「下界」方位</b>（即 45 ⇒ SOUTH、135 ⇒ WEST、
 * 225 ⇒ NORTH、315 ⇒ EAST）。这条是本项目钉死的取值：API 明列的例子
 * 「44 ⇒ SOUTH、46 ⇒ WEST、269 ⇒ EAST、<b>315 ⇒ EAST</b>」只有这一个读法能同时满足。
 * ⚠️ 它与原版 {@code Direction.fromYaw}（{@code floor(yaw/90 + 0.5)} ⇒ 边界归上界，
 * 315 会给 SOUTH）在**恰好整边界**这一个点上不同 —— 实机 yaw 是连续浮点，
 * 命中整边界的概率≈0，而「44/46 分列两侧」两条读法一致 ⇒ 本项目以 API 给的例子为准。
 * 若哪天要改回原版口径，<b>必须同时改 {@link FacingTest} 的边界用例</b>。
 */
public enum Facing {

    /** 正南：yaw 0，前向量 {@code (0, +1)}。 */
    SOUTH(0, 1),

    /** 正西：yaw 90，前向量 {@code (-1, 0)}。 */
    WEST(-1, 0),

    /** 正北：yaw 180，前向量 {@code (0, -1)}。 */
    NORTH(0, -1),

    /** 正东：yaw -90 / 270，前向量 {@code (+1, 0)}。 */
    EAST(1, 0);

    /** 每个方位占据的角度。 */
    private static final double SECTOR_DEGREES = 90.0;

    /** 方位的半宽：扇区边界在中心 ±45° 处。 */
    private static final double HALF_SECTOR_DEGREES = 45.0;

    /** 扇区号 → 方位（{@code 0 = SOUTH}、{@code 1 = WEST}、{@code 2 = NORTH}、{@code 3 = EAST}）。 */
    private static final Facing[] BY_SECTOR = {SOUTH, WEST, NORTH, EAST};

    private final int forwardX;
    private final int forwardZ;

    Facing(int forwardX, int forwardZ) {
        this.forwardX = forwardX;
        this.forwardZ = forwardZ;
    }

    /**
     * ★ Minecraft 的 yaw → 方位（<b>这条映射必须逐值钉死，写反了召唤物会跑到身后</b>）：
     * yaw = 0 ⇒ 正南（+Z）；90 ⇒ 正西（-X）；180 ⇒ 正北（-Z）；-90 或 270 ⇒ 正东（+X）。
     * 采用「就近取整到四个方位」：先把 yaw 归一化到 {@code [0,360)}，再按 45° 分界判定。
     * 例：{@code 44 ⇒ SOUTH}；{@code 46 ⇒ WEST}；{@code 269 ⇒ EAST}；{@code 315 ⇒ EAST}。
     *
     * @param yaw 任意浮点 yaw（负数、超过 360、等于 360 都被归一化；不要求 {@code [-180,180)}）
     */
    public static Facing fromYaw(float yaw) {
        double normalized = yaw % 360.0;
        if (normalized < 0.0) {
            normalized += 360.0;
        }
        // 扇区边界在 ±45° ⇒ 用「向上取整」把边界归给下界方位：
        //   45 ⇒ ceil(0.0) = 0 ⇒ SOUTH；315 ⇒ ceil(3.0) = 3 ⇒ EAST；316 ⇒ ceil(3.011) = 4 ⇒ 0 ⇒ SOUTH
        int sector = ((int) Math.ceil((normalized - HALF_SECTOR_DEGREES) / SECTOR_DEGREES)) & 3;
        return BY_SECTOR[sector];
    }

    /**
     * 单位前向量：{@code SOUTH ⇒ (0, +1)}；{@code WEST ⇒ (-1, 0)}；
     * {@code NORTH ⇒ (0, -1)}；{@code EAST ⇒ (+1, 0)}。
     */
    public int forwardX() {
        return forwardX;
    }

    /** 单位前向量的 Z 分量，见 {@link #forwardX()}。 */
    public int forwardZ() {
        return forwardZ;
    }

    /**
     * 单位右向量（<b>从玩家视角的右手边</b>）：{@code SOUTH ⇒ (-1, 0)}；{@code NORTH ⇒ (+1, 0)}；
     * {@code WEST ⇒ (0, -1)}；{@code EAST ⇒ (0, +1)}。
     *
     * <p>恒等于把前向量在俯视图里顺时针转 90°：{@code right = (-forwardZ, +forwardX)}
     * （面朝 +Z 时右手在 -X，即正西）。
     */
    public int rightX() {
        return -forwardZ;
    }

    /** 单位右向量的 Z 分量，见 {@link #rightX()}。 */
    public int rightZ() {
        return forwardX;
    }
}
