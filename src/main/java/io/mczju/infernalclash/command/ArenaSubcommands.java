package io.mczju.infernalclash.command;

import io.mczju.infernalclash.arena.ArenaCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * ★ <b>{@code /incl arena} 三条子指令的纯解析表</b> —— 2026-10-18 <b>重写</b>。
 *
 * <h2>用户原话（逐字）与它带来的改动</h2>
 * 「**原来的 arena set 太繁琐了，实际需要绑定多个竞技场供玩家同时游玩，按现在的指令一个一个绑
 * 过于繁琐，我需要你帮我重写一个 arena 设置指令，只需要绑定一个点就能成功绑定其他所有点。**」
 * ＋ 追问「多竞技场做到哪一步」时用户选 <b>A</b>（文件多竞技场 ＋ 一次绑全 ＋ list/remove 按名字）。
 *
 * ⇒ <b>指令的第二个参数从「点位名」变成了「竞技场名」</b>：
 * <ul>
 *   <li>{@code /incl arena set <竞技场名>} —— 站在竞技场西南角敲一次，
 *       <b>18 个点位全部按 {@code ArenaLayout} 的偏移表推出来</b>（{@code spectate} 不推，见那边的 javadoc）；</li>
 *   <li>{@code /incl arena list} —— 列出文件里所有竞技场、各自几个点位、哪个是默认；</li>
 *   <li>{@code /incl arena remove <竞技场名>} —— 整个竞技场一起删（不是删一个点位）。</li>
 * </ul>
 *
 * <h2>★ 名字的合法性判据只有一处</h2>
 * 转发给 {@link ArenaCatalog#isLegalName(String)}（允许字母/数字/_/-/中文，1~32 字）——
 * 这个名字会被原样写成 {@code arenas.yml} 的键 ⇒ 本类<b>绝不</b>折叠大小写、
 * 也绝不悄悄改写玩家输入的名字（那样「他绑的」与「他删的」会不是同一个）。
 *
 * <h2>能离线测</h2>
 * 本类<b>不</b> import Bukkit / MGC、<b>不</b>读写文件、<b>不</b>打印任何东西
 * ⇒ 普通 JUnit 里就能逐条断言（{@code command/ArenaSubcommandsTest}）。
 */
public final class ArenaSubcommands {

    /**
     * {@code /incl arena} 支持的动作 —— 名字逐字沿用用户 2026-10-15 的裁定
     * （{@code set} / {@code list} / {@code remove}），只是参数语义换成了竞技场名。
     */
    public enum Action {

        /**
         * ★★ {@code /incl arena create <竞技场名>}：**新建一座空的竞技场**（2026-10-18 用户追加）。
         *
         * <p>用户原话：「**怎么没有 arena create？现在竟然不能新增竞技场**」——
         * 之前只能靠 {@code set <名字>} "顺带创建"，而 Tab 补全只列**已有的**名字 ⇒
         * 看起来像"不能新增"。★ 现在 {@code create} 是**显式**动作（与 IG 的
         * {@code /ig arena create <名称>} 同名同义）：建一个空条目，随后用
         * {@code set} 一次绑全、或用 U1 界面逐点填。
         */
        CREATE("create"),

        /** {@code /incl arena set <竞技场名>}：用发令者**当前所在位置**（西南角）绑定整座竞技场。 */
        SET("set"),

        /** {@code /incl arena list}：逐条打印每个竞技场的采集状态与默认标记。 */
        LIST("list"),

        /** {@code /incl arena remove <竞技场名>}：从文件里去掉**整座**竞技场。 */
        REMOVE("remove");

        private final String commandName;

        Action(String commandName) {
            this.commandName = commandName;
        }

        /** 玩家敲的那个名字（全小写；解析时大小写不敏感、两侧空白忽略）。 */
        public String commandName() {
            return commandName;
        }
    }

    /** 名字 → 动作的查表（★ 只有<b>这一处</b>事实来源；{@code LinkedHashMap} 保序）。 */
    private static final Map<String, Action> BY_NAME = index();

    /** 用法行（中文；控制台与玩家都看得懂）。 */
    private static final String USAGE =
            "create <竞技场名> ｜ set <竞技场名> ｜ list ｜ remove <竞技场名>";

    /** 缺名字参数时的提示。 */
    static final String MSG_MISSING_NAME = "这条子指令需要一个竞技场名（例如 arena1）。";

    private ArenaSubcommands() {
    }

    private static Map<String, Action> index() {
        Map<String, Action> map = new LinkedHashMap<>();
        for (Action action : Action.values()) {
            Action previous = map.put(action.commandName(), action);
            if (previous != null) {
                throw new IllegalStateException("arena 子指令名重复（接线错误）：" + action.commandName()
                        + " 同时属于 " + previous + " 与 " + action);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /** 全部子指令名（Tab 补全与用法输出<b>同源</b>）。 */
    public static List<String> names() {
        List<String> names = new ArrayList<>(Action.values().length);
        for (Action action : Action.values()) {
            names.add(action.commandName());
        }
        return List.copyOf(names);
    }

    /**
     * 解析一个子指令片段（{@code null} / 空串 / 大小写混合 / 带空白都能吃）。
     *
     * @return 命中的动作；不认识 ⇒ 空（调用方据此走「未知子指令 ⇒ 用法提示」）
     */
    public static Optional<Action> actionFor(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NAME.get(raw.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 归一化一个竞技场名：合法 ⇒ 原样返回（只去掉两侧空白），否则空串。
     *
     * <p>★ 判据转发给 {@link ArenaCatalog#isLegalName(String)}（唯一来源）；
     * <b>不</b>折叠大小写 —— 名字会被原样写进 YAML。
     *
     * @param raw 玩家敲的名字（允许 {@code null} / 空串 / 带空白）
     * @return 合法的名字，或空串（<b>不抛</b>；错误文案用 {@link #unknownNameHint(String)}）
     */
    public static String arenaNameOrEmpty(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return ArenaCatalog.isLegalName(trimmed) ? trimmed : "";
    }

    /** 这个名字合不合法（★ 判据只有 {@link ArenaCatalog#isLegalName(String)} 一处）。 */
    public static boolean isLegalName(String raw) {
        return ArenaCatalog.isLegalName(raw);
    }

    /** 用法行（供 {@code /incl arena} 的提示与 {@code /incl} 的用法块共用一处措辞）。 */
    public static String usage() {
        return USAGE;
    }

    /** 未知 / 不合法的竞技场名提示（★ 说清规则，否则玩家只能去翻文档）。 */
    public static String unknownNameHint(String raw) {
        return "竞技场名不合法：" + raw + " —— 只允许字母 / 数字 / 下划线 / 连字符 / 中文，长度 1~32"
                + "（名字会写进 arenas.yml 的键，所以不能带空格、点、冒号）";
    }

    /** 未知子指令的提示（同样带上合法清单）。 */
    public static String unknownActionHint(String raw) {
        return "未知的 arena 子指令：" + raw + "。用法：" + USAGE;
    }
}
