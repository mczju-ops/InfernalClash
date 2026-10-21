package io.mczju.infernalclash.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>{@code /incl bot on|off} 的纯解析表与纯判据</b>（用户逐字裁定的那条指令）。
 *
 * <h2>★★ 用户原话（逐字，权威）</h2>
 * <blockquote>
 * 「<b>B，需要房间里任何一人敲/incl bot on；关掉人机需要敲/incl bot off。
 * 存在人机时进来第二个人也能正常开战</b>」
 * </blockquote>
 * 留档：{@code docs/24-用户裁定落地清单.md} §5.5。它<b>取代</b> {@code docs/02} §9 的
 * R62「单人开局<b>自动</b>开人机」——「自动」没有了，改成<b>显式</b>开关。
 *
 * <h2>本类回答四个问题（别的什么都不做）</h2>
 * <ol>
 *   <li>这个参数是 {@code on} 还是 {@code off}？（{@link #actionFor(String)} ——
 *       <b>逐字</b>只认这两个，大小写 / 两侧空白不敏感）；</li>
 *   <li>参数缺失或是不认识的值怎么办？（{@link #parse(String)} ⇒
 *       <b>明确拒绝</b>＋一句话，<b>绝不</b>默认当成 {@code on}）；
 *   <li>发令者现在处在什么作用域？（{@link #scopeOf(boolean, boolean)} ——
 *       ★ 只用两个布尔，<b>不 import MGC</b>，所以离线就能逐格断言）；</li>
 *   <li>在这个作用域里该接受还是拒绝、拒绝时说什么？（{@link #decide(Action, Scope)}）。</li>
 * </ol>
 *
 * <h2>★ 作用域 = <b>发令者所在的那一间房</b>（不接受房间 id 参数）</h2>
 * 用户原话是「<b>房间里</b>任何一人敲」⇒ 作用域由<b>发令者自己</b>决定，
 * 指令<b>没有也绝不会有</b> {@code /incl bot on <房间名>} 这种形状：
 * 多一个参数就等于多一条"改别人的房间"的路（本项目对「范围由参数放大」一贯拒绝）。
 *
 * <h2>★ 失败方向（一条都不静默）</h2>
 * <ul>
 *   <li>缺参数 / 未知值 ⇒ 拒绝 ＋ 用法（{@link #MSG_MISSING_ARG} / {@link #unknownActionHint(String)}）；</li>
 *   <li>不在任何等待房间（含控制台、含不在对局里）⇒ 拒绝 ＋ 一句话，
 *       <b>什么都不改</b>（{@link #notInRoomRejection()}）；</li>
 *   <li>★ 对局<b>已开始</b>（{@code IN_GAME} / {@code RUNNING}）⇒ 明确拒绝，<b>不许中途换人机</b>
 *       （{@link #alreadyStartedRejection()}）。</li>
 * </ul>
 *
 * <h2>★ 文案与 {@code display.yml}</h2>
 * 这几句都是<b>代码里的中文回退常量</b>（{@code ui/MenuTexts} 的既有形态）——
 * ★ <b>本轮不新增 {@code display.yml} 键</b>：那 27 条文案已定稿，加键要走另一套流程。
 * 面向玩家的两处<b>复用既有键</b>：{@code command.players-only}（控制台）与
 * {@code command.no-permission}（权限不足）。
 *
 * <h2>能离线测</h2>
 * 本类<b>不</b> import Bukkit / MGC、<b>不</b>读写文件、<b>不</b>打印任何东西
 * ⇒ 普通 JUnit 里逐条断言（{@code command/BotSubcommandsTest}）。
 */
public final class BotSubcommands {

    /**
     * {@code /incl bot} 支持的动作 —— <b>名字逐字取自用户裁定</b>
     * （{@code docs/24} §5.5：「敲/incl bot <b>on</b>」「敲/incl bot <b>off</b>」）。
     */
    public enum Action {

        /** {@code /incl bot on}：把这个房间标记为「允许人机」。 */
        ON("on"),

        /** {@code /incl bot off}：取消这个房间的「允许人机」。 */
        OFF("off");

        private final String commandName;

        Action(String commandName) {
            this.commandName = commandName;
        }

        /** 玩家敲的那个名字（全小写；解析时大小写不敏感、两侧空白忽略）。 */
        public String commandName() {
            return commandName;
        }

        /** 反义动作（{@code ON ⇄ OFF}）—— 日志里说"从 X 变成 Y"用，避免写死两条分支。 */
        public Action opposite() {
            return this == ON ? OFF : ON;
        }
    }

    /**
     * ★ <b>一次 {@code /incl bot …} 的作用域</b> —— 只有这三种，穷举。
     *
     * <p>判据（{@link #scopeOf(boolean, boolean)}）只需要两个布尔：
     * 「发令者在不在<b>本游戏</b>的对局里」与「那一场在不在<b>等待</b>阶段」。
     * 于是「对局已开始」这条<b>失败路径</b>不必真的起一局就能离线断言。
     */
    public enum Scope {

        /** 发令者在<b>本游戏</b>的一个等待房间里（{@code WAITING}）⇒ <b>唯一被接受</b>的作用域。 */
        WAITING_ROOM("等待房间"),

        /** 发令者在本游戏的一局里，但那一局<b>已经开局</b>（{@code RUNNING} 等）⇒ 拒绝。 */
        RUNNING_GAME("对局已开始"),

        /** 发令者不在本游戏的任何对局里（含控制台、含在别的游戏里）⇒ 拒绝。 */
        NOT_IN_GAME("不在任何房间");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        /** 作用域的中文名（写进日志，便于实机对账）。 */
        public String label() {
            return label;
        }
    }

    /**
     * 解析结果（{@link #parse(String)}）。
     *
     * @param action  解析出的动作；<b>失败时为 {@code null}</b>
     * @param failure 失败原因（一句中文，直接发给玩家）；成功时为 {@code null}
     */
    public record ParseResult(Action action, String failure) {

        /** 是否解析失败（★ 失败时 {@link #action()} 一定是 {@code null}，调用方别去用它）。 */
        public boolean isFailure() {
            return action == null;
        }
    }

    /**
     * 判定结果（{@link #decide(Action, Scope)}）。
     *
     * @param accepted  该不该执行
     * @param rejection 拒绝原因（发给玩家的一句中文）；<b>接受时为 {@code null}</b>
     */
    public record Decision(boolean accepted, String rejection) {
    }

    /**
     * 名字 → 动作的查表（★ 只有<b>这一处</b>「名字 → 动作」的事实来源）。
     *
     * <p>用 {@code LinkedHashMap} 而不是 {@code Map.copyOf}：后者迭代顺序按 JVM 运行加盐，
     * 而本项目「凡依赖顺序处必须保序」已有实测记录（{@code AGENTS.md} §4.2）。
     */
    private static final Map<String, Action> BY_NAME = index();

    /** 用法行（中文；控制台与玩家都看得懂）。 */
    private static final String USAGE = "on ｜ off";

    /** 缺参数时的提示。 */
    static final String MSG_MISSING_ARG = "这条子指令需要一个参数：on（开人机）或 off（关人机）。";

    private BotSubcommands() {
    }

    private static Map<String, Action> index() {
        Map<String, Action> map = new LinkedHashMap<>();
        for (Action action : Action.values()) {
            Action previous = map.put(action.commandName(), action);
            if (previous != null) {
                // 两条动作抢同一个名字 ⇒ 分发结果取决于声明顺序，属于接线错误，fail-fast
                throw new IllegalStateException("bot 子指令名重复（接线错误）：" + action.commandName()
                        + " 同时属于 " + previous + " 与 " + action);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /** 全部动作名（Tab 补全与用法输出<b>同源</b>；顺序 = 声明顺序 = {@code on / off}）。 */
    public static List<String> names() {
        List<String> names = new ArrayList<>(Action.values().length);
        for (Action action : Action.values()) {
            names.add(action.commandName());
        }
        return List.copyOf(names);
    }

    /** 用法行（供 {@code /incl} 的用法块与拒绝提示共用一处措辞）。 */
    public static String usage() {
        return USAGE;
    }

    /**
     * 解析一个参数片段。
     *
     * @param raw 玩家敲的原始片段（允许 {@code null} / 空串 / 大小写混合 / 两侧带空白）
     * @return 命中的动作；<b>不认识就返回 {@link Optional#empty()}</b> ——
     *         ★ 绝不静默当成 {@code on}（那会在玩家敲错时**替他把人机打开**）
     */
    public static Optional<Action> actionFor(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NAME.get(raw.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * ★ <b>把指令的第二个片段解析成"要做的事"或"一句拒绝"</b>（结果二者必居其一）。
     *
     * <p>缺参数与未知值走<b>同一张失败面</b>：调用方只需判 {@link ParseResult#isFailure()}，
     * 失败时直接把那句话发给玩家、<b>什么都不改</b>（所以「失败 ⇒ 零副作用」是结构上的保证）。
     */
    public static ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult(null, MSG_MISSING_ARG);
        }
        Optional<Action> action = actionFor(raw);
        if (action.isEmpty()) {
            return new ParseResult(null, unknownActionHint(raw));
        }
        return new ParseResult(action.get(), null);
    }

    /**
     * ★ <b>纯映射：发令者在本游戏里"在不在"＋"是不是等待阶段" ⇒ 作用域</b>。
     *
     * <p>刻意只用两个布尔：这样「对局已开始 ⇒ 拒绝」这条失败路径<b>离线就能断言</b>
     * （生产侧那两个布尔由 {@code command/InfernalClashCommand#botScopeOf} 从 MGC 读，
     * 读的是<b>发令者自己那一场</b>，不遍历世界、不遍历在线玩家 —— 铁律 10）。
     *
     * @param inOurGame 发令者在不在<b>本游戏</b>的某一局里
     * @param waiting   那一局在不在等待阶段（{@code WAITING}）；{@code inOurGame == false} 时本参数无意义
     */
    public static Scope scopeOf(boolean inOurGame, boolean waiting) {
        if (!inOurGame) {
            return Scope.NOT_IN_GAME;
        }
        return waiting ? Scope.WAITING_ROOM : Scope.RUNNING_GAME;
    }

    /**
     * ★ <b>纯判据：这一次 {@code /incl bot on|off} 该不该被接受。</b>
     *
     * <table border="1">
     *   <caption>作用域 × 动作</caption>
     *   <tr><th>作用域</th><th>结果</th><th>依据</th></tr>
     *   <tr><td>{@link Scope#WAITING_ROOM}</td><td><b>接受</b></td>
     *       <td>用户原话「房间里任何一人敲」</td></tr>
     *   <tr><td>{@link Scope#RUNNING_GAME}</td><td>拒绝</td>
     *       <td>★ 「不许中途换人机」—— 开局后再挂一台人机是改玩法（铁律 8/11）</td></tr>
     *   <tr><td>{@link Scope#NOT_IN_GAME}</td><td>拒绝</td>
     *       <td>★ fail-safe：没有作用对象就<b>什么都不改</b>（也绝不偷偷改"别的那一局"）</td></tr>
     * </table>
     *
     * <p>★ {@code on} 与 {@code off} 的<b>接受条件完全相同</b>（都是"等待房间"）：
     * 关掉一个本来就没开的人机是<b>幂等</b>的（调用方只需如实说"本来就是关着的"），
     * 而"开局后不许关"与"开局后不许开"同一条理由 —— 作用域已经不由你决定了。
     *
     * @throws IllegalArgumentException {@code action} 或 {@code scope} 为 {@code null}（编程错误，fail-fast）
     */
    public static Decision decide(Action action, Scope scope) {
        if (action == null) {
            throw new IllegalArgumentException("action 不能为 null（先走 parse(...)，失败时不该调 decide）");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为 null");
        }
        return switch (scope) {
            case WAITING_ROOM -> new Decision(true, null);
            case RUNNING_GAME -> new Decision(false, alreadyStartedRejection());
            case NOT_IN_GAME -> new Decision(false, notInRoomRejection());
        };
    }

    /** 未知参数的提示（★ 带上合法值清单，否则玩家只能去翻文档）。 */
    public static String unknownActionHint(String raw) {
        return "未知的 bot 参数：" + raw + "。只认 on / off（用法：" + USAGE + "）。";
    }

    /** 不在任何等待房间时的拒绝（一句人话 ＋ 说明作用域）。 */
    public static String notInRoomRejection() {
        return "你不在本游戏的等待房间里，人机开关没有作用对象，什么都没有改。"
                + "（这条指令只作用于「你所在的那一个房间」，不接受房间 id 参数；"
                + "先 /mgc join infernalclash 进房再敲。）";
    }

    /** 对局已开始时的拒绝（★ 不许中途换人机）。 */
    public static String alreadyStartedRejection() {
        return "这一局已经开始了，不能再改人机设置（不许中途换人机）。"
                + "请等这一局结束、回到等待房间再敲 /incl bot on|off。什么都没有改。";
    }
}
