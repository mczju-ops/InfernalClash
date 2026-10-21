package io.mczju.infernalclash.spell;

import io.mczju.infernalclash.card.Facing;
import io.mczju.infernalclash.card.Spell.ShapeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * ★ 法术**形状**的格集合计算（纯函数）。
 *
 * <p>本类只回答一个问题：「以某格为原点、按某个朝向摆一个形状，**涉及哪些格**」。
 * 它**不查世界、不碰实体** —— 这样才能在 JUnit 里把边界逐格钉死
 * （形状算错的表现是「法术打到了不该打的格子」，在游戏里几乎数不清）。
 *
 * <h2>出处</h2>
 * <ul>
 *   <li>{@code docs/07} §6.3「形状」—— {@code CIRCLE}({@code radius}) /
 *       {@code RECTANGLE}({@code width} × {@code length} + 朝向) /
 *       {@code CONE}({@code radius} + {@code angle}) / {@code SINGLE}(无参数)。</li>
 *   <li>{@code docs/07} §6.4「效果」—— 形状是给效果用的：先按形状取格集合，
 *       再按目标限定筛出实体，最后施加效果（伤害 / 治疗 / 药水 / …）。</li>
 *   <li>{@code docs/04} §16.9（P6 形状格集合设计）。★ 若该节尚未落地，
 *       以本轮任务书的 API 契约与本节 javadoc 为准（实测口径写在下面的「形状语义」里）。</li>
 * </ul>
 *
 * <h2>★ 形状语义（逐条钉死，改就必须同时改 {@code ShapeMathTest}）</h2>
 * <ol>
 *   <li><b>{@code SINGLE}</b>：只有原点那一格，**无论** {@code radius} 等参数给了什么
 *       （单体的语义里没有半径，多给参数不应改变结果）。</li>
 *   <li><b>{@code CIRCLE}</b>：以原点为圆心、半径 {@code radius} 格的**圆**。
 *       判据是**欧氏距离**（含边界）：{@code dx*dx + dz*dz <= radius*radius}；
 *       {@code radius} 允许小数（{@code radius=2.5} ⇒ {@code (2,1)} 的 5 &lt;= 6.25 命中、
 *       {@code (2,2)} 的 8 &gt; 6.25 不命中）。
 *       <br>⚠️ <b>与「格数型方形」不同，两者不可混用</b>：本项目的**防御塔射程**是方形
 *       （{@code objective/BeamRange}，判据 {@code |dx| <= 12 && |dz| <= 12}，
 *       {@code docs/04} §16.6.3 明确写着「绝不能写成浮点半径 12.5」），而**法术圆形**是圆。
 *       同一个 {@code halfExtent}/{@code radius} 下，{@code (12,12)} 在方形里**命中**、
 *       在圆里**不命中** —— 写串了的表现是「塔能打到河对岸」或「法术少打一个角」，
 *       都属于在游戏里数不清的偏差。</li>
 *   <li><b>{@code RECTANGLE}</b>：以**原点那一格为起点**（含原点），沿 {@code facing} 的
 *       前方向铺 {@code length} 格，宽度方向以 {@code facing} 的**右向量**为轴展开 {@code width} 格。
 *       {@code length} 与 {@code width} 必须是 {@code >= 1} 的**整数格**（见下面第 5 条）。
 *       <br>★ <b>奇数 / 偶数宽度的对齐方式（本项目钉死的取值，可否决）</b>：
 *       第 {@code i} 列（{@code i = 0..width-1}）的右向偏移取
 *       {@code Math.round(i - (width-1)/2.0)} —— 与 P4 的 {@code card/GroupOffsets} 的
 *       「{@code Math.round} 半数向正方向取整」口径一致：
 *       <ul>
 *         <li>{@code width=1} ⇒ {@code {0}}；{@code width=3} ⇒ {@code {-1, 0, +1}}
 *             （**奇数天然左右对称**，原点那一格真的在矩形中线上）；</li>
 *         <li>{@code width=2} ⇒ {@code {0, +1}}；{@code width=4} ⇒ {@code {-1, 0, +1, +2}}
 *             —— <b>偶数时多出来的那一格落在**右侧**</b>（{@code Math.round(-0.5) == 0}、
 *             {@code Math.round(+0.5) == 1}），而不是整体向左偏半格。
 *             这样「同一条形状从 1 格宽改成 2 格宽」时**原点那一格不动**。
 *             要改成偏左，**必须同时改本类与 {@code ShapeMathTest}**（钉住这条的用例会立刻变红）。</li>
 *       </ul></li>
 *   <li><b>{@code CONE}</b>：扇形 = 半径 {@code radius} 格**以内**、且与**前向量**的夹角
 *       {@code <= angle/2} 的格。判据（逐格用整数算 {@code dx, dz}，{@code dot} 是前向量点积）：
 *       <pre>
 *       d2   = dx*dx + dz*dz                 // 与 origin 的距离平方（int）
 *       hit  = d2 &lt;= radius*radius           // 先过圆判据
 *           &amp;&amp; (d2 == 0                      // ★ 原点那一格永远命中（防 0 除 / 防 NaN）
 *               || dot + 1e-9 &gt;= cos(angle/2) * sqrt(d2))
 *       </pre>
 *       ★ 边界（恰好 {@code angle/2}，例如 90° 朝向正前方的对角线 {@code dz == |dx|}）
 *       **算命中** —— 因为判据写的是闭区间 {@code <=}；{@code 1e-9} 的容差只为吸收
 *       {@code cos}/{@code sqrt} 的浮点误差（没有它，{@code cos(45°)*sqrt(2)} 会算出
 *       {@code 1.0000000000000002}，把恰好 45° 的格子误判成不命中）。
 *       <br>★ {@code angle=360} 时 {@code cos(180°) == -1.0} ⇒ {@code dot >= -sqrt(d2)}
 *       恒成立 ⇒ **退化成与同 {@code radius} 的 {@code CIRCLE} 完全相同的结果**（见测试）。</li>
 *   <li><b>参数合法性</b>：任何非法组合（{@code type} / {@code radius} / {@code width} /
 *       {@code length} / {@code angle} 为 {@code null} 或越界；需要朝向的形状给了 {@code null} 朝向）
 *       **一律抛 {@link IllegalArgumentException}**，消息里写清是哪个形状的哪个参数、期望什么范围。
 *       ★ <b>绝不允许「静默退化成单格」</b> —— 那会让法术看起来"没生效"，却没有任何报错。
 *       （例：{@code CIRCLE} 缺 {@code radius} ⇒ 抛异常；{@code width=2.5} ⇒ 抛异常，
 *       而不是偷偷取整成 2 或 3。）</li>
 * </ol>
 *
 * <h2>朝向</h2>
 * 前 / 右向量**直接复用** {@link Facing#forwardX()} / {@link Facing#forwardZ()} /
 * {@link Facing#rightX()} / {@link Facing#rightZ()}，本类**不自己重算 yaw**
 * （{@code SOUTH} 前 = {@code +Z}、{@code WEST} 前 = {@code -X}、{@code NORTH} 前 = {@code -Z}、
 * {@code EAST} 前 = {@code +X}；右向量是把前向量在俯视图里顺时针转 90°）。
 * {@code CIRCLE} 与 {@code SINGLE} 与朝向无关 ⇒ 允许 {@code facing} 为 {@code null}。
 *
 * <h2>保序</h2>
 * 返回值**去重 + 按 z 再按 x 升序**（同一组参数跑两次，格的顺序**完全一致**），
 * 便于日志与快照对比；返回的是不可变列表。
 */
public final class ShapeMath {

    /**
     * 保序口径：**先按 z 再按 x**（{@code docs/07} §6.3 的形状语义在格集合上是无序的，
     * 「顺序」纯粹是为了让日志可读、让测试可逐格对比 ⇒ 由本类统一钉死，不交给 {@code HashMap} 的运气）。
     */
    private static final Comparator<Cell> Z_THEN_X =
            Comparator.comparingInt(Cell::z).thenComparingInt(Cell::x);

    /**
     * 角度判据的容差：让**恰好落在 {@code angle/2} 上的格算命中**（判据是闭区间 {@code <=}）。
     * 量级说明：{@code cos}/{@code sqrt} 的误差在 {@code 1e-16} 量级，而相邻两格的角度间隔
     * 至少是 {@code ~1/radius} 弧度 ⇒ {@code 1e-9} 只会吸收误差，不会把"真的在外面"的格拉进来。
     */
    private static final double BOUNDARY_EPSILON = 1e-9;

    private ShapeMath() {
    }

    /** 一个格坐标（**不可变**）。 */
    public record Cell(int x, int z) {
    }

    /**
     * 算出形状覆盖的格集合（**保序**：先按 z 再按 x，便于日志与测试稳定）。
     *
     * @param type    形状类型
     * @param originX 原点格 X（瞄准格 / 玩家格）
     * @param originZ 原点格 Z
     * @param facing  施放者朝向（决定 RECTANGLE 与 CONE 的朝向；CIRCLE / SINGLE 可给 {@code null}）
     * @param radius  CIRCLE / CONE 的半径（格）；其余形状忽略
     * @param width   RECTANGLE 的宽（格，**垂直于朝向**）；其余形状忽略
     * @param length  RECTANGLE 的长（格，**沿朝向**）；其余形状忽略
     * @param angle   CONE 的张角（度）；其余形状忽略
     * @return 覆盖的格；SINGLE ⇒ 只有原点那一格。不可变列表，按 z 再按 x 升序
     * @throws IllegalArgumentException 形状参数非法（凭 {@code null} / 越界 / 需要朝向却没给），
     *                                  **不静默退化**
     */
    public static List<Cell> cells(ShapeType type,
                                   int originX, int originZ,
                                   Facing facing,
                                   Double radius, Double width, Double length, Double angle) {
        if (type == null) {
            throw new IllegalArgumentException("形状类型 type 不能为 null"
                    + "（形状参数非法必须抛异常，不允许静默退化成单格）");
        }
        List<Cell> raw = switch (type) {
            case SINGLE -> List.of(new Cell(originX, originZ));
            case CIRCLE -> circle(originX, originZ, requireRadius(radius, "CIRCLE"));
            case RECTANGLE -> rectangle(originX, originZ, requireFacing(facing, "RECTANGLE"), width, length);
            case CONE -> cone(originX, originZ, requireFacing(facing, "CONE"),
                    requireRadius(radius, "CONE"), requireAngle(angle));
        };
        return sortedDistinct(raw);
    }

    /** 两点是否在同一个格（给调用方筛目标用）。 */
    public static boolean contains(Collection<Cell> cells, int x, int z) {
        if (cells == null) {
            throw new IllegalArgumentException("格集合 cells 不能为 null"
                    + "（要表达「没有格」请传空集合；静默返回 false 会把「忘了算形状」伪装成「没打中」）");
        }
        for (Cell cell : cells) {
            if (cell != null && cell.x() == x && cell.z() == z) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 各形状

    /** 圆：欧氏距离（含边界），{@code dx*dx + dz*dz <= radius*radius}。 */
    private static List<Cell> circle(int originX, int originZ, double radius) {
        int bound = (int) Math.floor(radius);
        double radiusSquared = radius * radius;
        List<Cell> result = new ArrayList<>();
        for (int dz = -bound; dz <= bound; dz++) {
            for (int dx = -bound; dx <= bound; dx++) {
                if ((double) dx * dx + (double) dz * dz <= radiusSquared) {
                    result.add(new Cell(originX + dx, originZ + dz));
                }
            }
        }
        return result;
    }

    /** 扇形：圆判据 + 与前向量的夹角判据；原点那一格永远命中。 */
    private static List<Cell> cone(int originX, int originZ, Facing facing, double radius, double angle) {
        int bound = (int) Math.floor(radius);
        double radiusSquared = radius * radius;
        double cosHalf = Math.cos(Math.toRadians(angle / 2.0));
        int forwardX = facing.forwardX();
        int forwardZ = facing.forwardZ();
        List<Cell> result = new ArrayList<>();
        for (int dz = -bound; dz <= bound; dz++) {
            for (int dx = -bound; dx <= bound; dx++) {
                int distanceSquared = dx * dx + dz * dz;
                if ((double) distanceSquared > radiusSquared) {
                    continue;
                }
                if (distanceSquared == 0) {
                    // ★ 原点那一格永远命中（也是防 0 除 / NaN 的那一条）
                    result.add(new Cell(originX, originZ));
                    continue;
                }
                int dot = dx * forwardX + dz * forwardZ;
                double required = cosHalf * Math.sqrt(distanceSquared);
                if (dot + BOUNDARY_EPSILON >= required) {
                    result.add(new Cell(originX + dx, originZ + dz));
                }
            }
        }
        return result;
    }

    /** 长方形：原点格为起点，沿前方向 {@code length} 格，沿右向量 {@code width} 格（偶宽多出的一格在右侧）。 */
    private static List<Cell> rectangle(int originX, int originZ, Facing facing, Double width, Double length) {
        int columns = requireExtent(width, "width");
        int rows = requireExtent(length, "length");
        int forwardX = facing.forwardX();
        int forwardZ = facing.forwardZ();
        int rightX = facing.rightX();
        int rightZ = facing.rightZ();
        List<Cell> result = new ArrayList<>(rows * columns);
        for (int forward = 0; forward < rows; forward++) {
            for (int i = 0; i < columns; i++) {
                // ★ 奇宽天然对称；偶宽多出的一格落在右侧（Math.round 半数向正方向取整，与 P4 GroupOffsets 同口径）
                int right = (int) Math.round(i - (columns - 1) / 2.0);
                result.add(new Cell(originX + forward * forwardX + right * rightX,
                        originZ + forward * forwardZ + right * rightZ));
            }
        }
        return result;
    }

    // ------------------------------------------------------------ 参数校验

    private static double requireRadius(Double radius, String shape) {
        if (radius == null) {
            throw new IllegalArgumentException(shape + " 需要参数 radius（格），实际是 null"
                    + " —— 半径缺失时不许静默当成单格（那会让法术看起来「没生效」却没有任何报错）");
        }
        if (radius.isNaN() || radius.isInfinite()) {
            throw new IllegalArgumentException(shape + " 的 radius 必须是有限数（格），实际是 " + radius);
        }
        if (radius < 0) {
            throw new IllegalArgumentException(shape + " 的 radius 必须 >= 0（格），实际是 " + radius
                    + "（负半径不是「反向圆」，是配置写错了）");
        }
        return radius;
    }

    private static double requireAngle(Double angle) {
        if (angle == null) {
            throw new IllegalArgumentException("CONE 需要参数 angle（度），实际是 null"
                    + " —— 张角缺失时不许静默当成单格或整圆");
        }
        if (angle.isNaN() || angle.isInfinite() || angle <= 0.0 || angle > 360.0) {
            throw new IllegalArgumentException("CONE 的 angle 必须落在 (0, 360] 度，实际是 " + angle
                    + "（0 或负数没有「扇形」可言；> 360 与 360 等价，写了就说明配置有问题）");
        }
        return angle;
    }

    /**
     * 长方形的 {@code width} / {@code length}：必须是 {@code >= 1} 的**整数格**。
     *
     * <p>为什么拒收小数（本项目钉死的取值，可否决）：格集合本来就是**按整格数**的，
     * 「2.5 格宽」没有对应语义；而静默取整（2 或 3）会让法术范围与配置不符且**没有任何提示**
     * —— 宁可抛异常。想写 2 格宽就写 {@code 2}。
     */
    private static int requireExtent(Double value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("RECTANGLE 需要参数 " + name + "（格），实际是 null"
                    + " —— 缺失时不许静默退化成单格");
        }
        if (value.isNaN() || value.isInfinite()) {
            throw new IllegalArgumentException("RECTANGLE 的 " + name + " 必须是有限整数格，实际是 " + value);
        }
        if (value < 1.0) {
            throw new IllegalArgumentException("RECTANGLE 的 " + name + " 必须 >= 1（格），实际是 " + value
                    + "（0 或负数会让长方形一格都不占）");
        }
        if (value != Math.rint(value)) {
            throw new IllegalArgumentException("RECTANGLE 的 " + name + " 必须是整数格，实际是 " + value
                    + " —— 例如想写 2 格宽就写 2，不要写 2.5（本项目不静默取整）");
        }
        return (int) Math.rint(value);
    }

    private static Facing requireFacing(Facing facing, String shape) {
        if (facing == null) {
            throw new IllegalArgumentException(shape + " 需要施放者朝向 facing（决定前 / 右向量），实际是 null"
                    + " —— 猜一个默认朝向会让整条形状静默转 90°");
        }
        return facing;
    }

    // ------------------------------------------------------------ 保序

    /** 去重 + 按 z 再按 x 升序 + 返回不可变列表（同一组参数 ⇒ 逐格同序）。 */
    private static List<Cell> sortedDistinct(List<Cell> cells) {
        List<Cell> sorted = new ArrayList<>(new LinkedHashSet<>(cells));
        sorted.sort(Z_THEN_X);
        return List.copyOf(sorted);
    }
}
