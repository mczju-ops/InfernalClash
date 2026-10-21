package io.mczju.infernalclash.card;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ 一组召唤物的相对排布（纯函数）。
 *
 * <p>{@code docs/04} §16.7.5：一组召唤物的位置 = 落点 + <b>相对偏移</b>
 * （{@code forward} / {@code right} 相对玩家朝向）；同组多个按 {@code spread} 展开。
 * 本类把「组中心的相对偏移 + 同组间距」展开成**每个单位各自的相对偏移**，
 * 再落到世界格坐标上 —— 不碰 Bukkit / MGC ⇒ 可离线单测（{@link GroupOffsetsTest}）。
 *
 * <p>「召唤出来站成一排还是挤在一格」是最容易写错、又最难在游戏里数清楚的地方，
 * 所以这一层必须留在纯函数区。
 */
public final class GroupOffsets {

    /** ★ {@code spread <= 0} 时使用的自动间距（{@code docs/03} §4.3：「0 = 由运行期按 count 自动展开」）。 */
    public static final double AUTO_SPREAD = 1.0;

    /**
     * ★★ 成形排布（{@code group[].formation}）在 {@code spread <= 0} 时用的<b>外接圆半径</b>（格）。
     *
     * <p>取值理由（几何而不是口味）：正五边形边长 = {@code 2R·sin(36°) ≈ 1.176R}；
     * 猪灵碰撞箱宽约 0.6 格 ⇒ {@code R = 1.5} 给到约 1.76 格间距，
     * 既不互相挤（挤了会被原版的推挤分开、队形就散了），又不会散到"看起来是五只各走各的"。
     */
    public static final double DEFAULT_POLYGON_RADIUS = 1.5;

    private GroupOffsets() {
    }

    /** 一个相对偏移（格）：{@code forward} = 沿玩家前方向、{@code right} = 沿玩家右手方向。 */
    public record Offset(int forward, int right) {
    }

    /**
     * ★★ <b>半格精度的相对偏移</b>（{@code forward} / {@code right} 可以是 x.5）——
     * 2026-10-18 为「方阵」新增。
     *
     * <h2>为什么必须允许半格</h2>
     * 用户第 4 条要的是「**3-4-3 的居中对称方阵**」。中间那行是 <b>4</b> 只：
     * 若只能落在整格上，它永远会往一边多出一格（{@code -1/0/+1/+2} 或 {@code -2/-1/0/+1}）
     * ⇒ 眼睛看到的是一个"T 字"，而不是对称的方阵。
     * 放在 <b>半格</b>上（{@code -1.5 / -0.5 / +0.5 / +1.5}）才真的居中。
     * <p>★ 只有方阵（{@code rows}）走这条；「站成一排」的 50 张卡仍然走 {@link Offset}（整格，
     * 行为**一字未改**）—— 那条口径钉在 {@code docs/04} §16.7.5，不能顺手改掉。
     */
    public record PreciseOffset(double forward, double right) {
    }

    /**
     * 把「组中心的相对偏移 + 同组间距」展开成<b>每个单位各自的相对偏移</b>。
     *
     * <h2>规则（{@code docs/03} §4.3 的沙漠小队例子是唯一权威样例）</h2>
     * <ol>
     *   <li>{@code count <= 1} ⇒ 只有一个 {@code Offset(forward, right)}（即组中心本身）；</li>
     *   <li>{@code count > 1} ⇒ 以 {@code (forward, right)} 为<b>中心</b>、沿<b>右方向</b>展开：
     *       第 i 个的 right 分量 = {@code right + (i - (count-1)/2.0) * spacing}
     *       （<b>四舍五入到整格</b>），forward 分量全部相同；</li>
     *   <li>★ {@code spread <= 0} ⇒ 用<b>自动间距 {@value #AUTO_SPREAD} 格</b>
     *       （文档原话：「0 = 由运行期按 count 自动展开」）；</li>
     *   <li>间距只沿<b>右</b>方向展开（沙漠小队：焦骸 ×2 站在尸壳<b>后面并排</b>）；
     *       ★ 为什么不是沿前方向：文档只给了「同种多个之间的间距」而没有给轴向，
     *       而「并排」是唯一能同时解释那个例子的读法 —— 本决定已记在 {@code docs/04} §16.7.5
     *       与 {@code docs/05} E3，<b>可否决</b>。</li>
     * </ol>
     *
     * <h2>★ 奇 / 偶 count 的中心对齐方式（本项目钉死的取值，可否决）</h2>
     * 展开量是 {@code (i - (count-1)/2.0) * spacing}，取整用 {@link Math#round(double)}：
     * <ul>
     *   <li><b>奇数 count 天然对称</b>：{@code count=3, spread=1.0} ⇒ right 分量
     *       {@code -1 / 0 / +1}（组中心那一格真的有一个单位）；</li>
     *   <li><b>偶数 count 无法以「整格」对称</b>（中心落在两格之间），
     *       本项目的选择是 <b>{@code count=2, spread=1.0} ⇒ {@code 0 / +1}</b>
     *       —— 即 <b>第一个单位恰好落在组中心格，多出来的那一格落在右端（正方向）</b>。
     *       理由：{@code Math.round(-0.5) == 0}、{@code Math.round(+0.5) == 1}（半数向正方向取整），
     *       而且这样「同一张卡从 1 个改成 2 个」时**原有那个单位的位置不变**（不会整组平移半格）。</li>
     * </ul>
     * ⇒ {@code count=2} <b>不是</b> {@code -1 / 0}（整体偏左）；要改成偏左或允许半格，
     * <b>必须同时改本类与 {@link GroupOffsetsTest}</b>（钉住这条的用例会立刻变红）。
     *
     * @return 长度恰好等于 {@code count} 的列表（<b>保序</b>，索引 0 是展开方向的负端）；
     *         {@code count <= 0} ⇒ 空列表
     */
    public static List<Offset> offsets(int count, int forward, int right, double spread) {
        if (count <= 0) {
            return List.of();
        }
        double spacing = spread > 0 ? spread : AUTO_SPREAD;
        double center = (count - 1) / 2.0;
        List<Offset> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int expandedRight = right + (int) Math.round((i - center) * spacing);
            result.add(new Offset(forward, expandedRight));
        }
        return List.copyOf(result);
    }

    /**
     * ★★★ <b>方阵排布</b>（{@code group[].rows}）—— 2026-10-18 新增。
     *
     * <h2>用户原话（逐字，第 4 条）</h2>
     * 「**虫群的末影螨不应站成一排，而是站成 3-4-3 的居中对称方阵**」
     *
     * <h2>口径（三条，全部可复述）</h2>
     * <ol>
     *   <li>{@code rows} 是<b>逐行的人数</b>，<b>第一行在最前面</b>（{@code forward} 的正方向）；</li>
     *   <li>行间距 = 行内间距 = 同组间距（{@code spread}，{@code <= 0} 用 {@value #AUTO_SPREAD}）——
     *       ★ 刻意<b>不</b>另开一个 {@code row-gap} 键：一张卡里出现两种间距，
     *       在游戏里只会变成"看起来差不多、其实对不齐"；</li>
     *   <li><b>整个方阵以 {@code (forward, right)} 为中心</b>：行偏移 = {@code (行数-1)/2 - 行号}，
     *       行内偏移 = {@code (j - (人数-1)/2)} ⇒ 奇数行、偶数行**都**关于中心对称。
     *       ★ 偶数行因此会落在<b>半格</b>上（{@code -1.5 / -0.5 / +0.5 / +1.5}）——
     *       这是"4 只居中"唯一可能的答案（见 {@link PreciseOffset} 的说明）。</li>
     * </ol>
     *
     * <h2>★ 为什么不能直接复用 {@link #offsets}</h2>
     * 它只沿<b>右</b>方向展开（{@code docs/04} §16.7.5 的沙漠小队口径），
     * 一排 10 只末影螨会变成一条 10 格长的横线 —— 正是用户不满意的那一坨。
     *
     * @param rows    逐行人数（{@code null} / 空 ⇒ 空列表；某一行为 {@code null} 或 {@code <= 0} ⇒ 该行跳过）
     * @param forward 方阵中心的「前 / 后」偏移（格）
     * @param right   方阵中心的「左 / 右」偏移（格）
     * @param spread  行内 / 行间间距（格；{@code <= 0} ⇒ 用 {@value #AUTO_SPREAD}）
     * @return 长度 = 各行人数之和（<b>保序</b>：先第一行从左到右，再第二行……）
     */
    public static List<PreciseOffset> rows(List<Integer> rows, int forward, int right, double spread) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        double spacing = spread > 0 ? spread : AUTO_SPREAD;
        int rowCount = rows.size();
        double rowCenter = (rowCount - 1) / 2.0;
        List<PreciseOffset> result = new ArrayList<>();
        for (int row = 0; row < rowCount; row++) {
            Integer inRow = rows.get(row);
            if (inRow == null || inRow <= 0) {
                continue;   // 空行 = 这一行没人（保留它只为让"3-0-3"这种形状仍然对称）
            }
            // ★ 第一行在最前面 ⇒ 行号越大越靠后（forward 递减）
            double rowForward = forward + (rowCenter - row) * spacing;
            double columnCenter = (inRow - 1) / 2.0;
            for (int column = 0; column < inRow; column++) {
                result.add(new PreciseOffset(rowForward, right + (column - columnCenter) * spacing));
            }
        }
        return List.copyOf(result);
    }

    /**
     * ★★★ <b>成形排布：正多边形</b>（{@code group[].formation: PENTAGON}）—— 2026-10-18 新增。
     *
     * <h2>用户原话（逐字）</h2>
     * 「**可以。我希望这五个猪灵最好能站成一个类似正五边形**」
     * （说的是 {@code piglin_squad} 的 5 只猪灵；上一版是「沿右方向站成一排」）。
     *
     * <h2>几何（三条，全部可复述、可逐点断言）</h2>
     * <ol>
     *   <li><b>顶点 0 在正前方</b>：{@code (forward=R, right=0)} —— 五个人里**前头那只**
     *       朝敌人的方向走，后面两排自然跟上（这正是"能往一个方向推进"的队形）；</li>
     *   <li>其余顶点按 {@code 角度_k = 2πk/n} 依次排开：
     *       {@code forward_k = R·cos θ}、{@code right_k = R·sin θ}
     *       ⇒ 相邻顶点间距<b>全部相等</b>（{@code 2R·sin(π/n)}），是**正**多边形；</li>
     *   <li>★ <b>半径 = {@code spread}</b>（{@code <= 0} ⇒ {@value #DEFAULT_POLYGON_RADIUS}）。
     *       <b>这里 {@code spread} 是「外接圆半径」，不是「相邻间距」</b> —— 这是它与
     *       {@link #offsets} / {@link #rows} 的唯一语义差异，已写进 {@code docs/03} §4.3
     *       与 {@link Card.Formation} 的 javadoc
     *       （不另开 {@code radius} 键：一张卡里出现两个"范围"数，迟早有人改错一个）。</li>
     * </ol>
     *
     * <p>★ 坐标为<b>双精度</b>（{@link PreciseOffset}）：五边形的顶点本来就落在非整格上，
     * 取整会让"正五边形"看起来像"手一抖画歪的五边形"。
     *
     * @param count        顶点数（{@code <= 0} ⇒ 空；{@code 1} ⇒ 只有中心那一点）
     * @param forward      中心的前 / 后偏移（格）
     * @param right        中心的左 / 右偏移（格）
     * @param circumradius 外接圆半径（格；{@code <= 0} ⇒ {@value #DEFAULT_POLYGON_RADIUS}）
     * @return 长度 {@code count} 的列表，索引 0 = 正前方那个顶点（保序）
     */
    public static List<PreciseOffset> polygon(int count, int forward, int right, double circumradius) {
        if (count <= 0) {
            return List.of();
        }
        double radius = circumradius > 0 ? circumradius : DEFAULT_POLYGON_RADIUS;
        if (count == 1) {
            return List.of(new PreciseOffset(forward, right));
        }
        List<PreciseOffset> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;   // 顶点 0 = 正前方
            result.add(new PreciseOffset(forward + radius * Math.cos(angle),
                    right + radius * Math.sin(angle)));
        }
        return List.copyOf(result);
    }

    /**
     * 把**半格精度**的相对偏移落到世界坐标上（与 {@link #toWorldCell} 同一套朝向约定）。
     *
     * @return 长度 2 的数组 {@code {worldX, worldZ}}（★ 已经是**方块中心**口径：
     *         落点格的 {@code +0.5} 已经加进去）
     */
    public static double[] toWorldPoint(int centerCellX, int centerCellZ, PreciseOffset offset,
                                        Facing facing) {
        double x = centerCellX + 0.5 + offset.forward() * facing.forwardX()
                + offset.right() * facing.rightX();
        double z = centerCellZ + 0.5 + offset.forward() * facing.forwardZ()
                + offset.right() * facing.rightZ();
        return new double[] {x, z};
    }

    /**
     * 把相对偏移落到世界格坐标上：{@code 落点格 + forward * 前向量 + right * 右向量}。
     *
     * <p>★ 这里是「朝向写反就会静默出错」的地方：{@code SOUTH} 朝向下
     * {@code Offset(1, 0)} 必须往 <b>+Z</b> 走（正南），{@code Offset(0, 1)} 必须往 <b>-X</b> 走
     * （右手边 = 正西）。整条链由 {@link Facing} 与 {@link GroupOffsetsTest} 双向钉住。
     *
     * @return 长度为 2 的数组 {@code {cellX, cellZ}}
     */
    public static int[] toWorldCell(int centerCellX, int centerCellZ, Offset offset, Facing facing) {
        int cellX = centerCellX + offset.forward() * facing.forwardX() + offset.right() * facing.rightX();
        int cellZ = centerCellZ + offset.forward() * facing.forwardZ() + offset.right() * facing.rightZ();
        return new int[] {cellX, cellZ};
    }
}
