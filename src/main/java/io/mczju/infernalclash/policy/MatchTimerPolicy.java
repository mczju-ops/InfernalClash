package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.match.MatchOutcome;
import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.PhaseConfig;
import io.mczju.infernalclash.match.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>对局时间条（BossBar）的进度</b> —— 纯函数，可离线逐格断言
 * （2026-10-17 第十二轮，用户裁定 §A 第 4 条）。
 *
 * <h2>用户原话（逐字）</h2>
 * <blockquote>
 * 「Bossbar 显示对局时间，非加时显示一条<b>匀速下降</b>的 boss 条，加时时使用<b>另一条加时 boss 条</b>，
 * <b>从满的重新扣</b>，一旦对局结束就<b>封锁</b>这个 boss 条不再下降」
 * </blockquote>
 * ★ 用户还明确说了「什么叫否决？否决什么？」—— 见 {@code docs/24} 的答复：
 * 那是**我**把不确定的观感常量标成"可否决"，不是文档的裁定值。
 *
 * <h2>★★ 三条判据（逐条对应用户的三句话）</h2>
 * <ol>
 *   <li><b>非加时「匀速下降」</b>：进度 = 该阶段**剩余 tick / 该阶段总时长**。
 *       因为每 tick 减 1/总时长，所以是<b>严格线性</b>的（不是按秒跳）。</li>
 *   <li><b>加时用另一条、从满的重新扣</b>：加时是 {@link MatchPhase#OVERTIME}，
 *       它有自己的 {@code phaseTick}（进加时时归零，{@code javap} 实测见
 *       {@code MatchPhaseMachine#advanceBattle}）⇒ 天然就是"从满的重新扣"。
 *       ★ 而**非加时那条要锁在 0**（不是消失、也不回满）—— 它就是"此前的进度"的证据。</li>
 *   <li><b>对局结束就封锁、不再下降</b>：{@link MatchPhase#ENDED} 时两条都**冻结**
 *       —— 冻结 = 保持上一个可见状态（非加时条停在 0，加时条停在其最后值），
 *       <b>绝不</b>把进度清成 0（那是"清空"不是"封锁"，用户明确区分了这两个词）。
 *       ★ 由于 {@code MatchService.tick} 在 ENDED 时直接 return，
 *       {@code phaseTick}/{@code battleTick} 本身也不再增长 ⇒ 本类返回的值自然恒定；
 *       本类仍显式处理 ENDED（不依赖调用方"恰好不再推进"这种隐含前提）。</li>
 * </ol>
 *
 * <h2>阶段 → 哪一条</h2>
 * <table border="1">
 *   <caption>两个阶段的归属</caption>
 *   <tr><th>阶段</th><th>{@code regular}（第一条）</th><th>{@code overtime}（加时条）</th></tr>
 *   <tr><td>PREPARING</td><td>准备阶段剩余时间</td><td>不显示（{@code visible=false}）</td></tr>
 *   <tr><td>BATTLE</td><td>战斗剩余时间</td><td>不显示</td></tr>
 *   <tr><td>OVERTIME</td><td>★ 锁在 <b>0</b>（此前那条走完了）</td><td>★ 加时剩余时间</td></tr>
 *   <tr><td>ENDED</td><td>★ 冻结在 0</td><td>★ 冻结在上一个值（或不可见）</td></tr>
 * </table>
 */
public final class MatchTimerPolicy {

    /**
     * 一条时间条的状态。
     *
     * @param visible  这条条此刻**该不该显示**（不显示 ⇒ 调用方必须<b>移除</b>它，
     *                 否则会留下一条永远 0% 的悬挂条）
     * @param progress 进度（<b>已经 clamp 到 {@code [0,1]} 并四舍五入到 1e-6</b>，
     *                 方便逐格断言；浮点尾差见 {@code XpCurve} 的同类说明）
     * @param remainingTicks 剩余 tick（负数按 0；纯粹给文案用，不参与判定）
     * @param totalTicks 该阶段总时长（0 表示这一格没有"时长"概念）
     */
    public record BarState(boolean visible, double progress, int remainingTicks, int totalTicks) {

        /** 不显示的那一格（调用方据此移除 boss 条）。 */
        public static final BarState HIDDEN = new BarState(false, 0.0, 0, 0);

        public BarState {
            if (!(progress >= 0.0) || progress > 1.0) {
                throw new IllegalArgumentException("progress 必须落在 [0,1]：实际 " + progress);
            }
            if (remainingTicks < 0) {
                throw new IllegalArgumentException("remainingTicks 不能为负：实际 " + remainingTicks);
            }
            if (totalTicks < 0) {
                throw new IllegalArgumentException("totalTicks 不能为负：实际 " + totalTicks);
            }
        }

        /** 百分比整数（文案用；四舍五入）。 */
        public int percent() {
            return (int) Math.round(progress * 100.0);
        }
    }

    /** 两条条的合并结果（调用方按它更新两个 boss 条）。 */
    public record TimerState(BarState regular, BarState overtime) {
    }

    private MatchTimerPolicy() {
    }

    /**
     * 算一次时间条。
     *
     * @param phase      当前阶段
     * @param phaseTick  当前阶段内已过的 tick（{@code MatchState#phaseTick()}）
     * @param config     阶段时长（唯一口径 —— 本类**不重复定义任何时长**）
     */
    public static TimerState of(MatchPhase phase, int phaseTick, PhaseConfig config) {
        return of(phase, phaseTick, config, false);
    }

    /**
     * 算一次时间条（带「这一局是否已经结束过」的标记）。
     *
     * <h2>★★ {@code wasEnded} 这个参数为什么必须存在</h2>
     * 两种情况的 {@link MatchPhase} 都是 {@code ENDED}：
     * <ol>
     *   <li><b>真的打完了</b>（进过战斗、可能还进过加时）⇒ 用户要求
     *       「一旦对局结束就<b>封锁</b>这个 boss 条不再下降」⇒ 条**留着**，
     *       冻结在它走完时的样子（这样玩家看得到"时间真的走完了"）；</li>
     *   <li><b>还没开打就被判掉</b>（准备阶段双方都没确认 ⇒ 平局，
     *       见 {@code docs/24} §6.1）⇒ 此时战斗**从来没发生过**，
     *       非加时那条的进度是"还没开始"而不是"走完了" ⇒ 应当**移除**，
     *       而不是留一条 0% 的条让人以为"战斗打完了"。</li>
     * </ol>
     * ⇒ 调用方（{@code ui/MatchHud}）把「本局是否进入过战斗或加时」传进来
     * （它就是"真的打过"的阳性证据）。★ 缺省 {@code false} = 没打过 ⇒ 移除。
     *
     * @param wasEnded 这一局的阶段机**曾经**到过 BATTLE 或 OVERTIME 吗
     */
    public static TimerState of(MatchPhase phase, int phaseTick, PhaseConfig config,
                               boolean wasEnded) {
        Objects.requireNonNull(phase, "phase 不能为 null（用 MatchState.INITIAL.phase()）");
        Objects.requireNonNull(config, "config 不能为 null（出厂值用 PhaseConfig.shipped()）");
        return switch (phase) {
            case PREPARING -> new TimerState(
                    bar(phaseTick, config.preparingTicks()), BarState.HIDDEN);
            case BATTLE -> new TimerState(
                    bar(phaseTick, config.battleTicks()), BarState.HIDDEN);
            case OVERTIME -> new TimerState(
                    // ★★ 用户：「加时时使用**另一条**加时 boss 条，从满的重新扣」
                    //   ⇒ 非加时那条**锁在 0**（走完了），加时条从 1200 tick 满格重新扣。
                    //   ★ 非加时那条在这里 visible=true 且 progress=0 —— 它是"走完了"的证据，
                    //     不是"还在跑"（进度 0 就说明跑完了）。
                    new BarState(true, 0.0, 0, config.battleTicks()),
                    bar(phaseTick, config.overtimeTicks()));   // ★ 变异自测 5 已还原
            case ENDED -> wasEnded
                    ? new TimerState(
                            // ★ 用户：「一旦对局结束就封锁这个 boss 条不再下降」
                            //   ⇒ 冻结在「走完」的样子：非加时条 0，加时条 0（进过加时它当然也走完了）
                            new BarState(true, 0.0, 0, config.battleTicks()),
                            new BarState(true, 0.0, 0, config.overtimeTicks()))
                    : new TimerState(BarState.HIDDEN, BarState.HIDDEN);
        };
    }

    /**
     * 一条「剩余时间 / 总时长」的条。
     *
     * <p>★ <b>匀速下降</b>：{@code phaseTick} 每 tick +1 ⇒ 分子每 tick −1
     * ⇒ 进度严格线性。**不是**按秒跳（那会让"匀速"变成台阶）。
     */
    private static BarState bar(int phaseTick, int totalTicks) {
        if (totalTicks <= 0) {
            return BarState.HIDDEN;
        }
        int tick = Math.max(0, phaseTick);
        int remaining = Math.max(0, totalTicks - tick);
        double progress = (double) remaining / (double) totalTicks;
        // ★ 四舍五入到 1e-6：把浮点尾差挡在断言之外（本项目 XpCurve 的同类口径）
        double rounded = Math.round(Math.min(1.0, Math.max(0.0, progress)) * 1_000_000.0) / 1_000_000.0;
        return new BarState(true, rounded, remaining, totalTicks);
    }

    // ==================================================================================
    // 侧边栏（战斗状态面板）
    // ==================================================================================

    /**
     * ★★★ <b>侧边栏要显示什么</b> —— 数据源（纯）。
     *
     * <h2>★★ 2026-10-17 第十三轮：用户当场裁定（E19 结案）</h2>
     * 用户原话：「侧边栏只需写：**玩家名称、防御塔1血量、防御塔2血量、信标血量**
     * （以上四个每个玩家一行），**对局阶段**？**准备：战斗：双倍经验：加时**」
     * 并回答了我的三条反问：**左：1、右：2** · **剩余时间不显示了**（boss 条代替）·
     * **「我的经验」保留**。
     *
     * <p>★★★ <b>2026-10-18 用户当场推翻了上面最后一条</b>（逐字）：
     * 「**另外，不需要显示玩家已有经验**」＋「**这个侧边栏太臃肿了，不要全部放到一行**」，
     * 形态选定「**按「名字 / 塔 / 信标」拆成每方几行**」⇒ 见 {@link #sidebarLines} 的新版说明。
     * {@link #ownXp()} / {@link #ownSide()} 两个字段**保留**（调用点与日志仍在用），
     * 但**不再渲染进侧边栏**。
     *
     * <h2>★ 与本 record 字段的对应关系（一处一处对得上）</h2>
     * <table border="1">
     *   <caption>用户那一行 ASCII 的每一段，数据从哪来</caption>
     *   <tr><th>显示</th><th>字段</th></tr>
     *   <tr><td>{@code 蓝方 · YamatoMaya}</td><td>{@link #nameOf()}（阵营 → 玩家名；来自本局名册）</td></tr>
     *   <tr><td>塔1 / 塔2 血量</td><td>{@link #towersOf()}（{@code [当前, 上限]} × 2，**左、右**顺序）</td></tr>
     *   <tr><td>信标血量</td><td>{@link #beaconOf()}（{@code [当前, 上限]}）</td></tr>
     *   <tr><td>阶段行</td><td>{@link #phase()} ＋ {@link #battleTick()}（双倍判据看它，见 D2）</td></tr>
     *   <tr><td>行尾「我的经验」</td><td><b>★ 2026-10-18 用户裁定删除</b>
     *       （「不需要显示玩家已有经验」）⇒ {@link #ownXp()} 只保留为字段，不再渲染</td></tr>
     * </table>
     * ★ <b>无玩家侧泄漏</b>：{@code ownXp} 只有一份，且 {@code sidebarLines} 只把它印在
     * {@code ownSide} 那一行上 ⇒ 结构上不可能把对面的经验印到你这边来。
     */
    public record SidebarSource(MatchPhase phase, int battleTick,
                               Map<Side, java.util.List<double[]>> towersOf,
                               Map<Side, double[]> beaconOf,
                               Map<Side, String> nameOf, double ownXp, Side ownSide,
                               PhaseConfig config, MatchOutcome outcome) {

        /**
         * ★ 规范构造器（**不是**紧凑构造器里的字段改写）：这里只做非空校验。
         *
         * <p>★ 为什么不在紧凑构造器里把 {@code null} 的 Map 换成空表并包不可变视图：
         * 对**泛型分量**赋值会踩到「参数化类型不可具体化」那条限制
         * （实测：一屏 `无法推断类型参数` 的编译错误）。
         * ⇒ 失败方向改为**渲染端容忍 null**：{@link #sidebarLines} 读表时用
         * {@link #healthOf(Map, Side)} / {@link #towersOf(Map, Side)} 两个 null-safe 取值器。
         */
        public SidebarSource {
            Objects.requireNonNull(phase, "phase 不能为 null");
            Objects.requireNonNull(config, "config 不能为 null");
        }

        /** 兼容构造（旧调用点：只给塔数、不给血量/名字）——★ 仅测试与过渡期用，生产走全参构造。 */
        public SidebarSource(MatchPhase phase, int phaseTick, int battleTick,
                             int towersRed, int towersBlue, double ownXp, Side ownSide,
                             PhaseConfig config, MatchOutcome outcome) {
            this(phase, battleTick,
                    Map.of(Side.RED, java.util.List.of(towerCountAsHealth(towersRed)),
                            Side.BLUE, java.util.List.of(towerCountAsHealth(towersBlue))),
                    Map.of(), Map.of(), ownXp, ownSide, config, outcome);
        }

        /**
         * 把「还剩几座活塔」表示成血量数组：活塔按满血显示（上限 = 1，显示成 {@code 1/1}）。
         *
         * <p>★ 只用于**兼容构造**（旧调用点）—— 血量口径的权威在
         * {@code ObjectiveService#healthOf}。这样旧调用点也能渲染出"还剩几座"，
         * 不会因为字段换了而显示成空的。
         */
        private static double[] towerCountAsHealth(int count) {
            int alive = Math.max(0, count);
            double[] values = new double[alive * 2];
            for (int i = 0; i < alive; i++) {
                values[i * 2] = 1.0;
                values[i * 2 + 1] = 1.0;
            }
            return values;
        }

        /**
         * 「阶段内已过的 tick」—— ★ 第十三轮起**渲染不再用它**（剩余时间已按用户裁定删除），
         * 保留这个 accessor 只为兼容既有测试夹具，恒返回 {@code 0}。
         */
        public int phaseTick() {
            return 0;
        }
    }

    /**
     * ★★★ <b>侧边栏的一行 = 文本 + 色调</b>（2026-10-18 新增，用户第 7 条）。
     *
     * <h2>用户原话（逐字）</h2>
     * 「**侧边栏的文字加颜色：按照红、蓝给双方防御塔血量上色，并给红方信标颜色更深的红色，
     * 蓝方信标血量颜色更深的蓝色**」；我追问「玩家名那一行要不要也上色」⇒ 用户答
     * 「**名字用金色**」。
     *
     * <h2>★ 为什么是「文本 + 一个色调枚举」，而不是把 {@code §c} 拼进字符串</h2>
     * ① 颜色码混进文本之后，断言只能比字符串（"某一行是不是红的"变成"这一行里有没有
     *    §c"），而且长度、缩进、取整这些真正容易错的格会被颜色码盖住；
     * ② 本类在 {@code policy} 包里，**刻意不 import Adventure**（那个包只放判据）；
     *    ⇒ 色调用本项目自己的枚举，渲染层（{@code ui/MatchHud}）负责映射成
     *    {@code NamedTextColor}。这样"什么颜色"与"怎么画"各只有一处。
     */
    public record SidebarLine(String text, Tone tone) {

        /** 这一行该用什么颜色（渲染层映射成 {@code NamedTextColor}）。 */
        public enum Tone {
            /** 默认前景色（阶段行 / 结果行）。 */
            PLAIN,
            /** 金色：双方名字那一行（用户裁定「名字用金色」）。 */
            GOLD,
            /** 红色：红方的塔血量。 */
            RED,
            /** 蓝色：蓝方的塔血量。 */
            BLUE,
            /** 深红：红方的**信标**血量（用户：「红方信标颜色更深的红色」）。 */
            DARK_RED,
            /** 深蓝：蓝方的**信标**血量（用户：「蓝方信标血量颜色更深的蓝色」）。 */
            DARK_BLUE
        }

        /** 便捷构造：默认色。 */
        public static SidebarLine plain(String text) {
            return new SidebarLine(text, Tone.PLAIN);
        }
    }

    /** 某一方塔血量的色调（红方红、蓝方蓝）。 */
    public static SidebarLine.Tone towerTone(Side side) {
        return side == Side.RED ? SidebarLine.Tone.RED : SidebarLine.Tone.BLUE;
    }

    /** 某一方**信标**血量的色调（红方深红、蓝方深蓝）。 */
    public static SidebarLine.Tone beaconTone(Side side) {
        return side == Side.RED ? SidebarLine.Tone.DARK_RED : SidebarLine.Tone.DARK_BLUE;
    }

    /**
     * ★★★ <b>生成侧边栏的**行列表**</b>（纯函数：同输入 ⇒ 同输出、顺序稳定）。
     *
     * <h2>★★★ 2026-10-18：用户裁定「**太臃肿了，不要全部放到一行**」＋「**不需要显示玩家已有经验**」</h2>
     * 用户原话（逐字）：「**1、图片中这个侧边栏太臃肿了，不要全部放到一行；
     * 另外，不需要显示玩家已有经验**」，并选定形态：「**按「名字 / 塔 / 信标」拆成每方几行**」。
     *
     * <p>⇒ 每一方从 **1 行挤 3 个数** 变成 **4 行**：
     * <pre>
     *  红方 · Alex            ← 金色（2026-10-18 用户：「名字用金色」）
     *    塔左 240/240         ← 红
     *    塔右 200/240         ← 红
     *    信标 320/320         ← 深红
     *  蓝方 · YamatoMaya      ← 金色
     *    塔左 240/240         ← 蓝
     *    塔右 240/240         ← 蓝
     *    信标 300/320         ← 深蓝
     *  战斗中
     * </pre>
     * ★ 塔的左右口径不变（用户第十三轮裁定「**左：1、右：2**」）；
     * ★ 「剩余时间」仍然不显示（boss 条代替，第十三轮裁定）；
     * ★ 「**我的经验**」整段删除 —— 注意 {@link SidebarSource#ownXp()} 这个**字段保留**
     * （它有调用点、也有日志价值），只是**不再印到侧边栏上**。
     *
     * <h2>★ 为什么行序是「先双方各自成块、再阶段行」</h2>
     * 用户给的形态就是"每方几行" ⇒ 一方的四行必须**连续**，否则又变回"所有东西摊在一屏里"。
     * <p>★ 渲染交给调用方 ——「显示什么」与「怎么画」分开，前者可离线逐行断言。
     */
    public static List<SidebarLine> sidebarLines(SidebarSource source) {
        Objects.requireNonNull(source, "source 不能为 null");
        List<SidebarLine> lines = new ArrayList<>();

        // ① 双方各自成块（★ 顺序 = 红、蓝，与 Side 枚举一致）：
        //    标题行「阵营 · 玩家名」（金色），随后三条数各自一行（塔左 / 塔右 / 信标）
        for (Side side : Side.values()) {
            lines.add(new SidebarLine(sideHeader(side, source), SidebarLine.Tone.GOLD));
            lines.add(new SidebarLine(indent(SLOT_TOWER_LEFT, pair(towerAt(source, side, 0))),
                    towerTone(side)));
            lines.add(new SidebarLine(indent(SLOT_TOWER_RIGHT, pair(towerAt(source, side, 1))),
                    towerTone(side)));
            lines.add(new SidebarLine(indent(SLOT_BEACON, pair(healthOf(source, side))),
                    beaconTone(side)));
        }

        // ② 阶段行（★ 四选一：准备阶段 / 战斗中 / 双倍经验 / 加时）
        //    ★ 用户原话：「战斗中（这个地方可选：准备阶段、战斗中、双倍经验、加时）」
        //    ★ 对局结束 ⇒ 显示结果（不再显示阶段 —— 没有阶段可言）
        if (source.phase() == MatchPhase.ENDED) {
            lines.add(SidebarLine.plain("结果：" + outcomeName(source.outcome())));
        } else {
            lines.add(SidebarLine.plain(phaseLine(source.phase(), source.battleTick(), source.config())));
        }

        return List.copyOf(lines);
    }

    /** 「塔左」这一段的前缀（★ 与 {@code docs/24} §10.3 的措辞一致；右塔是第 2 座）。 */
    public static final String SLOT_TOWER_LEFT = "塔左 ";

    /** 「塔右」这一段的前缀。 */
    public static final String SLOT_TOWER_RIGHT = "塔右 ";

    /** 「信标」这一段的前缀。 */
    public static final String SLOT_BEACON = "信标 ";

    /** 每方块内三条数的缩进（两个半角空格 —— 侧边栏会原样保留行首空格）。 */
    static final String INDENT = "  ";

    /** 一条数一行的缩进形态。 */
    private static String indent(String label, String value) {
        return INDENT + label + value;
    }

    /**
     * ★ <b>一方的标题行</b>：{@code 蓝方 · YamatoMaya}。
     *
     * <p>★ 名册里没有这一方（例如人机那一侧没名字）⇒ 印 {@code ——}，**不印 0**
     * （0 会被读成"塔全掉了"，而实际是"这一方没人"）。
     */
    private static String sideHeader(Side side, SidebarSource source) {
        String name = nameOf(source, side);
        return sideLabel(side) + " · " + (name == null ? "——" : name);
    }

    /** 某一方的第 {@code index} 座塔（0 = 左、1 = 右）；没有 ⇒ {@code null}。 */
    private static double[] towerAt(SidebarSource source, Side side, int index) {
        java.util.List<double[]> towers = towersOf(source, side);
        return index < towers.size() ? towers.get(index) : null;
    }

    /** 阵营 → 玩家名（★ null-safe：名册可能是 null / 缺这一方）。 */
    private static String nameOf(SidebarSource source, Side side) {
        Map<Side, String> names = source.nameOf();
        return names == null ? null : names.get(side);
    }

    /** 阵营 → 信标血量（★ null-safe）。 */
    private static double[] healthOf(SidebarSource source, Side side) {
        Map<Side, double[]> beacons = source.beaconOf();
        return beacons == null ? null : beacons.get(side);
    }

    /** 阵营 → 两座塔的血量（★ null-safe：没有这一方 ⇒ 空表）。 */
    private static java.util.List<double[]> towersOf(SidebarSource source, Side side) {
        Map<Side, java.util.List<double[]>> towers = source.towersOf();
        if (towers == null) {
            return java.util.List.of();
        }
        java.util.List<double[]> value = towers.get(side);
        return value == null ? java.util.List.of() : value;
    }

    /** 一对血量的显示形态：{@code 240/240}；缺 ⇒ {@code ——}。 */
    private static String pair(double[] health) {
        if (health == null || health.length < 2) {
            return "——";
        }
        return Math.round(health[0]) + "/" + Math.round(health[1]);
    }

    /** 阶段行（★ 四选一，用户裁定）；★ **不再含时间**（boss 条承担）。 */
    private static String phaseLine(MatchPhase phase, int battleTick, PhaseConfig config) {
        // ★ 「双倍经验」优先于「战斗中」：用户把那四个词并列写出来，意思就是"当前处于哪一种状态"
        //   ⇒ 进入双倍期之后要能一眼看出（而不是"战斗中"糊过去）。
        if ((phase == MatchPhase.BATTLE || phase == MatchPhase.OVERTIME)
                && isDoubleXp(battleTick, config)) {
            return "双倍经验";
        }
        return switch (phase) {
            case PREPARING -> "准备阶段";
            case BATTLE -> "战斗中";
            case OVERTIME -> "加时";
            case ENDED -> "已结束";
        };
    }

    /** 阵营的显示名（★ **只有这一处映射**：侧边栏 / 目标物名牌 / 播报共用）。 */
    public static String sideLabel(Side side) {
        return side == Side.RED ? "红方" : "蓝方";
    }

    /** 是否已进入双倍经验（判据只有这一条 —— 与 {@code MatchPhaseMachine#isDoubleXp} 同口径）。 */
    public static boolean isDoubleXp(long battleTick, PhaseConfig config) {
        return battleTick >= config.doubleXpAfterTicks();
    }

    /** 阶段中文名（★ **只有这一处映射** —— boss 条 / 侧边栏 / 聊天播报共用它）。 */
    public static String phaseName(MatchPhase phase) {
        return switch (phase) {
            case PREPARING -> "准备阶段";
            case BATTLE -> "战斗阶段";
            case OVERTIME -> "加时阶段";
            case ENDED -> "已结束";
        };
    }

    /** 结果中文名（★ 只有这一处映射）。 */
    public static String outcomeName(MatchOutcome outcome) {
        return switch (outcome) {
            case RED_WINS -> "红方获胜";
            case BLUE_WINS -> "蓝方获胜";
            case DRAW -> "平局";
            case NONE -> "未分胜负";
        };
    }

    /** tick → {@code m:ss}（MC 每秒 20 tick）。 */
    public static String formatTicks(int ticks) {
        int totalSeconds = Math.max(0, ticks) / 20;
        return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
    }

    /** 经验 → 一位小数（与卡牌费用的展示口径一致）。 */
    public static String formatXp(double xp) {
        return String.format("%.1f", Math.max(0.0, xp));
    }
}
