package io.mczju.infernalclash.policy;

/**
 * ★★ <b>「飞到目标上方、然后垂直向下投火球」的飞行判据</b> —— 纯函数（2026-10-18 新增）。
 *
 * <h2>出处（{@code docs/19} 第 110 行，怨恨轰炸机，逐字）</h2>
 * 「**无法通过吐火球攻击**，只会**飞行到目标上方、然后垂直向下发射火球**；22 血」。
 * 用户当时选的是「**最小可用**」：禁原版远程 ＋ 飞到目标上方 ＋ 垂直投火球。
 *
 * <h2>为什么先做纯函数</h2>
 * 「它在不在目标正上方 / 该不该投」只有真人看得见，而它的错法很安静：
 * 斜着投（看起来像普通恶魂）、永远不投（看起来像卡住）、或者一路贴地飞。
 * ⇒ 三档状态与速度上限全部离线可断言（{@code BomberFlightTest}）。
 *
 * <h2>口径（四条）</h2>
 * <ol>
 *   <li><b>水平</b>：与目标的水平距离 &gt; {@code tolerance} ⇒ 朝目标加速（速度封顶
 *       {@link #MAX_HORIZONTAL}）；否则<b>不再水平移动</b>（对正上方）；</li>
 *   <li><b>高度</b>：目标脚上方 {@code hoverHeight} 格是巡航高度 ——
 *       低于它 ⇒ 爬升，高于它 + 1 格 ⇒ 下降，其余不动；</li>
 *   <li>{@link State#DROP}（可以投弹）= <b>水平对正</b> ＋ <b>高度足够</b>；
 *       ★ 只在 DROP 状态才允许投，其它状态**一律不投**（否则就是"边飞边吐"的普通恶魂）；</li>
 *   <li>★ 所有速度都封顶，且**没有目标时返回 {@link State#IDLE}**（不移动、不投）——
 *       失败方向是"原地悬停"，绝不让它乱飞或往自己脚下投。</li>
 * </ol>
 *
 * <p>★ 本类不碰 Bukkit（只吃几个 double）⇒ 必然可离线单测。
 */
public final class BomberFlight {

    /** 水平速度上限（格/tick）：恶魂原本就慢，0.25 ≈ 5 格/秒。 */
    public static final double MAX_HORIZONTAL = 0.25;

    /** 垂直速度上限（格/tick）。 */
    public static final double MAX_VERTICAL = 0.2;

    /** 「对正上方」的水平容差（格）：小于它就算已经在正上方。 */
    public static final double DEFAULT_TOLERANCE = 1.2;

    /** 巡航高度（目标脚下往上多少格）：恶魂的爆炸半径是 1，6 格足够安全。 */
    public static final double DEFAULT_HOVER_HEIGHT = 6.0;

    /** 这一 tick 该干什么。 */
    public enum State {
        /** 没有目标 ⇒ 不动、不投（失败方向）。 */
        IDLE,
        /** 还没到正上方 / 高度还不够 ⇒ 移动。 */
        MOVE,
        /** 已经到正上方且高度足够 ⇒ **这一 tick 可以投弹**。 */
        DROP
    }

    /**
     * 一次飞行决策。
     *
     * @param state 该干什么
     * @param vx    水平 X 速度（格/tick，封顶后）
     * @param vy    垂直 Y 速度（格/tick，封顶后）
     * @param vz    水平 Z 速度（格/tick，封顶后）
     */
    public record Step(State state, double vx, double vy, double vz) {

        /** 不动。 */
        public static final Step IDLE = new Step(State.IDLE, 0.0, 0.0, 0.0);
    }

    private BomberFlight() {
    }

    /**
     * 算一次飞行。
     *
     * @param dx           目标 X − 自己 X
     * @param dy           目标 Y − 自己 Y（正 = 目标在自己上方）
     * @param dz           目标 Z − 自己 Z
     * @param tolerance    水平容差（格）
     * @param hoverHeight  巡航高度（自己应当在目标上方多少格）
     */
    public static Step plan(double dx, double dy, double dz,
                            double tolerance, double hoverHeight) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            return Step.IDLE;
        }
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // ① 水平：朝目标，速度封顶
        double vx = 0.0;
        double vz = 0.0;
        if (horizontal > tolerance && horizontal > 1.0e-6) {
            double scale = Math.min(MAX_HORIZONTAL, horizontal) / horizontal;
            vx = dx * scale;
            vz = dz * scale;
        }
        // ② 高度：目标上方 `hoverHeight` 格是巡航高度
        //    `dy` = 目标Y − 自己Y ⇒ 想达到的高度差 = dy + hoverHeight
        //    （自己正好在目标上方 hoverHeight 格时它等于 0）
        double wantedRise = dy + hoverHeight;   // 还需要爬升多少格（正 = 要往上）
        double vy = 0.0;
        if (wantedRise > 1.0) {
            vy = Math.min(MAX_VERTICAL, wantedRise);
        } else if (wantedRise < -1.0) {
            vy = Math.max(-MAX_VERTICAL, wantedRise);
        }
        // ③ 只有「水平已对正 + 高度已足够」才允许投
        boolean aligned = horizontal <= tolerance;
        boolean highEnough = wantedRise <= 1.0;
        if (aligned && highEnough) {
            return new Step(State.DROP, 0.0, vy, 0.0);
        }
        return new Step(State.MOVE, vx, vy, vz);
    }

    /** 便捷重载：用出厂容差与巡航高度。 */
    public static Step plan(double dx, double dy, double dz) {
        return plan(dx, dy, dz, DEFAULT_TOLERANCE, DEFAULT_HOVER_HEIGHT);
    }

    /**
     * ★★★ <b>迈出这一步之后应该在哪</b>（纯函数）—— 2026-10-18 新增。
     *
     * <h2>为什么轰炸机不用 {@code setVelocity} 而是"算好位置再 teleport"</h2>
     * 用户第 9 条：「**怨恨轰炸机现在完全无法移动，放出来只会静止在地上不动**」。
     * 根因 = 恶魂是<b>飞行生物</b>：它的位移由 {@code FlyingMoveControl} 每 tick
     * 自己重写 {@code deltaMovement}，而我们为了禁掉原版火球已经 {@code setAI(false)}
     * ⇒ 两件事撞在一起，我们写的速度**下一 tick 就被原版的移动控制抹掉**
     * （现成证据：现场日志里那只恶魂死时仍是 {@code y=201.00}，与出生高度一模一样）。
     *
     * <p>⇒ 改成<b>由我们决定位置</b>：{@code destination(...)} 给出下一 tick 的坐标，
     * 执行层 {@code teleport} 过去。这与人形单位"用速度让它走"是两条路
     * （它们的地面移动本来就归我们管），所以这里只做坐标加法 —— 没有 Bukkit、可离线断言。
     *
     * @param x    当前 X
     * @param y    当前 Y
     * @param z    当前 Z
     * @param step {@link #plan} 的结果
     * @return 长度 3 的数组 {@code {x, y, z}}；{@code step == null} 或 {@link Step#IDLE} ⇒ 原地
     */
    public static double[] destination(double x, double y, double z, Step step) {
        if (step == null || step.state() == State.IDLE) {
            return new double[] {x, y, z};
        }
        return new double[] {x + step.vx(), y + step.vy(), z + step.vz()};
    }
}
