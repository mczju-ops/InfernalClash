package io.mczju.infernalclash.command;

import io.mczju.infernalclash.profile.PlayerProgress;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ★ <b>{@code /incl debug} 里 R69 那几条的子指令解析表</b>（「名字 → 动作」、「参数 → 值」的<b>纯</b>部分）。
 *
 * <h2>为什么再抽一张表（{@code docs/10} §2.1 的同一条要求）</h2>
 * {@code docs/10} §2.1 明写「子命令别名 / 名字映射要在<b>解析表</b>里集中维护（一处映射），
 * 不要散在各分支里」。{@link MenuSubcommands} 是「打开界面」那一张，本类是
 * {@code docs/10} §3 那五条（R69）那一张。它只回答两个问题，<b>不碰服务端</b>：
 * <ol>
 *   <li>这个子指令名是哪一条？（{@link #subcommandFor(String)}）</li>
 *   <li>后面那两个参数（目标玩家 / 数量 / {@code on|off}）合法吗？解析成什么？
 *       （{@link #parse(String, String, String)}）</li>
 * </ol>
 *
 * <h2>★ 它<b>不</b>做的事（刻意划出去，免得长成第二处事实来源）</h2>
 * <ul>
 *   <li><b>不 import Bukkit / MGC</b>：目标玩家的名字 → UUID 由调用方（{@code Bukkit.getPlayerExact}）
 *       解决 ⇒ 本类可在普通 JUnit 里离线断言（{@code DebugSubcommandsTest}）；</li>
 *   <li><b>不读档案、不写档案</b>：变换是 {@link ProgressEdits}，落盘是 {@code profile/ProfileStore}
 *       （★ 唯一的业务写入口）；</li>
 *   <li><b>不打印任何东西</b>：所有失败都只是一个 {@link Failure} 值，由调用方决定怎么说。</li>
 * </ul>
 *
 * <h2>★★ 参数校验的口径（先解析完全部参数，再有任何动作）</h2>
 * 「负数 / 非数字 / 缺参」一律在这里就被挡下并返回 {@link Failure} ⇒ 调用方<b>不可能</b>
 * 在半路拿到一个 {@link Run} 再去写档案。这条是本类最重要的性质：
 * <b>失败 ⇒ 一个字节都不会写</b>（{@code docs/04} §16.11.3「不扣不发」）。
 *
 * <p>★ 数量上限取 {@link Integer#MAX_VALUE}：{@code PlayerProgress} 的字段是 {@code int}
 * ⇒ 超出的数在这里就报「超出 int 范围」，而不是等到 {@code PlayerProgress} 构造器里抛。
 *
 * <h2>★ 名字清单的来源</h2>
 * 五条名字<b>逐字</b>取自 {@code docs/10} §3 的表格（{@code grant-all} / {@code reset-collection} /
 * {@code set-gold} / {@code set-powder} / {@code infinite-xp}）—— <b>不自己编、不加别名</b>：
 * {@code docs/10} §2.1 的别名清单里没有这几条的别名。
 *
 * <h2>★★ 五条的分工（2026-10-15 第二轮裁定后）</h2>
 * 前四条改的是<b>档案</b>（{@link #applyEdit} 走 {@code ProfileStore}）；
 * 第五条 {@value #INFINITE_XP} 改的是<b>对局运行时的一个开关</b>
 * （{@code match/MatchService.setFreeCasting}，作用域 = 发令者当前那一场，按 {@code matchId}）
 * ⇒ 它在本类里只被<b>解析</b>（{@link #parseToggle}），执行在
 * {@code command/InfernalClashCommand} 那条分支。这条分工写在 {@link #applyEdit} 的 javadoc 里。
 */
public final class DebugSubcommands {

    /**
     * {@code docs/10} §3 的第一条：<b>获取全卡</b>（【Q40】「连金色版一起给」）。
     *
     * <p>语法 {@code /incl debug grant-all [玩家]}：目标可省 ⇒ 省略时是发令者自己
     * （与 {@code docs/10} §3 的方括号一致）。
     */
    public static final String GRANT_ALL = "grant-all";

    /**
     * {@code docs/10} §3 的第二条：<b>重置图鉴</b>（⚠️ 破坏性，{@code docs/10} §4）。
     *
     * <p>语法 {@code /incl debug reset-collection [玩家]}（目标可省 ⇒ 默认自己）。
     * ★ 语义见 {@link ProgressEdits#resetCollection}：清 {@code owned} + {@code codex}
     * 里**非基础档**的卡，<b>基础卡保留</b>（★ 2026-10-21 用户裁定「重置卡牌收藏时
     * 不应该删掉我的基础卡」；R44 = 新玩家本来就初始拥有全部基础卡）。
     * （金币 / 尘 / 预设 / 计数器<b>不动</b>）。
     */
    public static final String RESET_COLLECTION = "reset-collection";

    /** {@code docs/10} §3 的第三条：<b>设置金币数</b>，语法 {@code … set-gold <玩家> <数量>}。 */
    public static final String SET_GOLD = "set-gold";

    /** {@code docs/10} §3 的第四条（【Q41】新增）：<b>设置烈焰粉（尘）</b>，语法同 {@code set-gold}。 */
    public static final String SET_POWDER = "set-powder";

    /**
     * {@code docs/10} §3 的第五条：<b>开启无限经验模式</b>，语法 {@code … infinite-xp [on|off]}。
     *
     * <p>★★ <b>本轮（2026-10-15 第二轮裁定）它从「识别 + 中文拒绝」改成了真动作</b> ——
     * 用户原话「<b>打牌不扣经验</b>」（逐字留档 {@code docs/24} §1 第 5 条；落地说明见
     * {@code docs/10} §8 的「2026-10-15（第二轮裁定）」块）：
     * <ul>
     *   <li>开着它 ⇒ <b>出牌这一步不再扣经验</b>（召唤 / 法术的费用校验与扣减<b>整条失效</b>，
     *       「经验不足」这个拒绝理由不再出现）；★ <b>经验本身照常累积</b>；</li>
     *   <li>★ <b>不是</b>「经验不清零」、也<b>不是</b>「经验固定在某个值」（那两个当时列的选项
     *       均未被采纳）；</li>
     *   <li>★ <b>作用域 = 发令者当前那一场对局</b>（按 {@code matchId}；【Q42】＋【Q44】
     *       ⇒ 不许有「全局当前对局」这种静态状态）。</li>
     * </ul>
     *
     * <p>★ <b>本条子指令的落点与前四条不同</b>：它<b>不写档案</b>（不碰 {@code ProfileStore}），
     * 而是改<b>对局运行时</b>的一个开关 ⇒ {@link #applyEdit} 对它返回 {@code null} 并说明，
     * 真正的执行在调用方（{@code InfernalClashCommand} 里的 {@code /incl debug infinite-xp}
     * 那条分支 ⇒ {@link io.mczju.infernalclash.match.MatchService#setFreeCasting(boolean)}）。
     */
    public static final String INFINITE_XP = "infinite-xp";

    /**
     * ★★★ <b>遗留实体清理</b>（2026-10-17 第十二轮新增）——
     * 用户裁定 §A 第 1 条「<b>因为是测试服，所以破格一次，你直接全域清理</b>」。
     *
     * <h2>★ 语法：{@code /incl debug purge-legacy [--yes]}</h2>
     * <ul>
     *   <li><b>不写 {@code --yes}</b> ⇒ <b>默认只报告</b>（把可清的、没带 PDC 的、
     *       跳过的各列一遍，<b>一个都不删</b>）；</li>
     *   <li><b>写 {@code --yes}</b> ⇒ 真的删（只删带本插件 PDC 的那些）。</li>
     * </ul>
     * ★ 用户明确要求「默认只报告不删，加 {@code --yes} 才真删」⇒ 这是**唯一的开关**，
     * 没有"自动跑"、"每次启动跑一次"之类的第二处判据。判据与执行在
     * {@code objective/LegacyPurge}（纯类）与 {@code objective/LegacyPurgeService}（执行层）。
     */
    public static final String PURGE_LEGACY = "purge-legacy";

    /** {@value #PURGE_LEGACY} 的确认参数（**逐字** {@code --yes}，不认 {@code -y} / {@code yes}）。 */
    public static final String CONFIRM_FLAG = "--yes";

    /**
     * 已**真的做完**的六条（写档案的四条 + 改对局开关的 {@value #INFINITE_XP}
     * + 清理遗留实体的 {@value #PURGE_LEGACY}）。
     */
    private static final List<Subcommand> IMPLEMENTED = List.of(
            new Subcommand(GRANT_ALL, true, false, false, false),
            new Subcommand(RESET_COLLECTION, true, false, false, false),
            new Subcommand(SET_GOLD, true, true, false, false),
            new Subcommand(SET_POWDER, true, true, false, false),
            new Subcommand(INFINITE_XP, false, false, true, false),
            new Subcommand(PURGE_LEGACY, false, false, false, true));

    private static final Map<String, Subcommand> BY_NAME = index();

    private DebugSubcommands() {
    }

    /**
     * 一条子指令的语法形状。
     *
     * @param name        规范名（小写，逐字取自 {@code docs/10} §3）
     * @param needsTarget 目标玩家是<b>必填</b>的（{@code set-gold} / {@code set-powder}）；
     *                    {@code false} ⇒ 目标可省（{@code [玩家]}，由调用方用发令者自己）
     * @param needsAmount 需要 {@code <数量>}（{@code set-gold} / {@code set-powder}）
     * @param needsToggle 需要 {@code [on|off]}（只有 {@value #INFINITE_XP}）
     */
    private record Subcommand(String name, boolean needsTarget, boolean needsAmount,
                              boolean needsToggle, boolean needsConfirmFlag) {

        /**
         * 用法片段（{@link #usage()} 与失败消息共用一处措辞）。
         *
         * <p>形状逐字对齐 {@code docs/10} §3 的表格：{@code grant-all [玩家]} /
         * {@code set-gold <玩家> <数量>} / {@code infinite-xp [on|off]}；
         * ★ {@code purge-legacy [--yes]} 是 2026-10-17 第十二轮按用户裁定新增的
         * （{@code docs/10} §3 的表格已同步）。
         */
        String usage() {
            StringBuilder text = new StringBuilder(name);
            if (needsAmount) {
                text.append(" <玩家> <数量>");
            } else if (needsToggle) {
                text.append(" [玩家] [on|off]");
            } else if (needsConfirmFlag) {
                text.append(" [" + CONFIRM_FLAG + "]");
            } else if (needsTarget) {
                text.append(" [玩家]");
            }
            return text.toString();
        }
    }

    private static Map<String, Subcommand> index() {
        Map<String, Subcommand> map = new LinkedHashMap<>();
        for (Subcommand subcommand : allOrdered()) {
            Subcommand previous = map.put(subcommand.name(), subcommand);
            if (previous != null) {
                // 同一个名字挂两条 ⇒ 分发取决于声明顺序，属于接线错误，fail-fast
                throw new IllegalStateException("debug 子指令名重复（接线错误）：" + subcommand.name());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /** 声明顺序 = {@code docs/10} §3 表格顺序（五条**全部**已实现）。 */
    private static List<Subcommand> allOrdered() {
        return List.copyOf(IMPLEMENTED);
    }

    // ================================================================== 名字 → 动作

    /**
     * ★ 全部 {@code debug} 子指令名<b>之一</b>的清单（声明顺序 = {@code docs/10} §3 的表格顺序）。
     *
     * <p>★ <b>它含 {@value #INFINITE_XP}</b>：补全表与解析表必须是同一份声明，
     * 否则会出现「Tab 补得出来、敲下去却是未知子指令」（{@link MenuSubcommands} 的同一条理由）。
     */
    public static List<String> names() {
        return allOrdered().stream().map(Subcommand::name).toList();
    }

    /**
     * ★ <b>真的做完了</b>的五条名字（{@code docs/10} §8 里可以从 ❌ 改成 ✅ 的那些）。
     *
     * <p>单独一个方法是为了让「哪些做完了」这句话在<b>代码里</b>可断言，
     * 而不是只写在 {@code docs/} 的表格里（那份表格曾经与代码不符，见 {@code docs/11} §W W19）。
     *
     * <p>★ 2026-10-15（第二轮裁定）：{@value #INFINITE_XP} <b>加进来了</b> ——
     * 用户给了语义「打牌不扣经验」⇒ 它不再是「只有名字」的那一条。
     */
    public static List<String> implementedNames() {
        return allOrdered().stream().map(Subcommand::name).toList();
    }

    /**
     * ★ 名字 → 动作。只认 {@code docs/10} §3 明写的五个名字（大小写不敏感、两侧空白忽略）。
     *
     * @param subcommand 玩家敲的原始片段（允许 {@code null} / 空串）
     * @return 未知一律 {@link Optional#empty()} ⇒ 调用方走「未知子指令」那条路
     *         （绝不静默当成某一条已知指令）
     */
    public static Optional<String> subcommandFor(String subcommand) {
        if (subcommand == null) {
            return Optional.empty();
        }
        String key = subcommand.trim().toLowerCase(Locale.ROOT);
        return BY_NAME.containsKey(key) ? Optional.of(key) : Optional.empty();
    }

    /**
     * 这条子指令是不是<b>本表认得的</b>那五条之一。
     *
     * <p>★ 2026-10-15 改名说明：它以前叫 {@code isImplemented} 并且对 {@code infinite-xp}
     * 返回 {@code false}（那时它确实<b>没有语义</b>，一敲就明确拒绝）。用户裁定「打牌不扣经验」
     * 之后五条**全部**实现了 ⇒ 那个区分已经没有意义，改名为「认不认得」，
     * 免得下一个人从一个恒为 {@code true} 的方法名里读出「还有没做的」。
     */
    public static boolean recognizes(String subcommand) {
        return subcommandFor(subcommand).isPresent();
    }

    /** 全部子指令的用法串（{@code grant-all [玩家] | reset-collection [玩家] | …}）。 */
    public static String usage() {
        StringBuilder text = new StringBuilder();
        for (Subcommand subcommand : allOrdered()) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(subcommand.usage());
        }
        return text.toString();
    }

    // ================================================================== 参数 → 值

    /**
     * ★ <b>{@code infinite-xp} 的开关取值</b>（{@code docs/10} §3 的语法就是 {@code [on|off]}）。
     *
     * <p>★ 只认这两个词（大小写不敏感）—— {@code 1} / {@code true} / {@code enable} 一律
     * <b>明确失败</b>，绝不「猜一个意思」（{@link #parseToggle}）。
     */
    public enum Toggle {
        /** {@code on} ⇒ 本场「打牌不扣经验」。 */
        ON,
        /** {@code off} ⇒ 恢复正常的费用校验与扣减。 */
        OFF;

        /** 是不是「打开」（只有一个地方做这个判断，避免各处各写一遍）。 */
        public boolean enabled() {
            return this == ON;
        }
    }

    /**
     * 一次解析的<b>成功</b>结果：动作 + 目标玩家名 + 数量 + 开关取值
     * （没这一项的都是 {@code null}）。
     *
     * @param action 规范子指令名（小写，来自 {@link #names()}）
     * @param target 目标玩家的名字；{@code null} = 命令里没写 ⇒ 由调用方用发令者自己（玩家发令）
     *               或明确报「要显式指定玩家名」（控制台发令）
     * @param amount 数量（只有 {@code set-gold} / {@code set-powder} 非 {@code null}；
     *               一定 {@code >= 0} 且 {@code <= Integer.MAX_VALUE}）
     * @param toggle 开关取值（只有 {@code infinite-xp} 非 {@code null}；
     *               {@code docs/10} §3 的 {@code [on|off]}）
     */
    public record Run(String action, String target, Integer amount, Toggle toggle,
                      boolean confirm) {

        /**
         * ★ 兼容构造器：不带开关的那四条（{@code grant-all} / {@code reset-collection} /
         * {@code set-gold} / {@code set-powder}）。
         *
         * <p>保留它有两个理由：① 调用方（{@code InfernalClashCommand} 与测试）不必为了
         * 一个新参数改写全部构造点；② 「写档案的那四条不带开关」这件事在类型上就看得见。
         */
        public Run(String action, String target, Integer amount) {
            this(action, target, amount, null, false);
        }

        /**
         * ★ 兼容构造器：带 {@code on|off} 的 {@code infinite-xp}（第十二轮加的
         * {@code confirm} 对它恒为 {@code false}）。
         */
        public Run(String action, String target, Integer amount, Toggle toggle) {
            this(action, target, amount, toggle, false);
        }

        /** ★ {@value #PURGE_LEGACY}：{@code confirm == true} 才是 {@code --yes}。 */
        public Run(String action, String target, Integer amount, boolean confirm) {
            this(action, target, amount, null, confirm);
        }
    }

    /**
     * 一次解析的<b>失败</b>结果。
     *
     * @param message <b>中文</b>失败原因（调用方原样发给发令者；也用于 assert）
     */
    public record Failure(String message) {
    }

    /**
     * ★ 解析结果 —— 成功是 {@link #run()}，失败是 {@link #failure()}，
     * 两者<b>至多一个有值</b>（这条不变量由 {@link #ok} / {@link #fail} 两个工厂保证）。
     *
     * <p>★ <b>为什么不做成「{@code sealed} 接口 + 两个嵌套 record」</b>：编译期实测，
     * 嵌套的 {@code Result} 声明 {@code permits Run, Failure} 时解析不到<b>同级</b>的那两个嵌套类型
     * （简单名与 {@code DebugSubcommands.Run} 限定名都报 {@code cannot find symbol}），
     * 而调用方只需 {@code instanceof} / 取值 ⇒ 不值得为穷尽性检查改写整份文件结构。
     * 这个形状同样做到「失败与成功在类型上分得开、都不可能被静默当成成功」。
     *
     * <p>两者都<b>不携带任何副作用</b>，也都不 import 任何服务端类型。
     */
    public record ParseResult(Run run, Failure failure) {

        /** 成工厂：{@code run} 非 {@code null} ⇒ {@link #failure()} 必为 {@code null}。 */
        public static ParseResult ok(Run run) {
            if (run == null) {
                throw new IllegalArgumentException("run 不能为 null（成功结果必须带一个动作）");
            }
            return new ParseResult(run, null);
        }

        /** 失败工厂：{@code message} 非空白 ⇒ {@link #run()} 必为 {@code null}。 */
        public static ParseResult fail(String message) {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("失败消息不能为空（失败必须说清为什么）");
            }
            return new ParseResult(null, new Failure(message));
        }

        /** 是不是失败（调用方据此决定是回一句中文还是继续改档案）。 */
        public boolean isFailure() {
            return failure != null;
        }
    }

    /**
     * ★ <b>纯</b>：把 {@code /incl debug <子指令> [目标玩家] [数量|on|off]} 的三个片段解析成动作
     * 或明确失败。
     *
     * <h2>规则（逐条都有测试）</h2>
     * <ol>
     *   <li>名字不认识 ⇒ {@link #fail}（消息里给出 {@link #usage()}）；</li>
     *   <li>{@code grant-all} / {@code reset-collection}：最多一个参数（目标玩家）；
     *       多给一个 ⇒ 失败（宁可报错，也不静默忽略一个看起来生效了的参数）；</li>
     *   <li>{@code set-gold} / {@code set-powder}：<b>目标玩家必填</b>（省略 ⇒ 失败，
     *       因为 {@code docs/10} §3 的语法就是 {@code <玩家> <数量>}），数量必填且必须是
     *       {@code 0 ~ Integer.MAX_VALUE} 的<b>十进制整数</b>；负数 / 非数字 / 超范围 / 缺参
     *       ⇒ 各给一句写明「实际收到什么」的中文；</li>
     *   <li>★ {@value #INFINITE_XP}：第三段是 {@code on|off}（{@code docs/10} §3 的
     *       {@code [on|off]}），目标玩家可省；<b>缺参 / 非法值一律明确失败</b>
     *       （{@link #MSG_INVALID_TOGGLE}，消息里带实际收到的那个词）⇒
     *       绝不「猜成 on」或「猜成 off」（那会让运维以为开关生效了）。</li>
     * </ol>
     *
     * <p>★ 目标玩家的名字<b>不做合法性判断</b>（存在性由调用方查）：本类必须能离线跑，
     * 而「谁在线」是服务端的事实。
     *
     * @param subcommand {@code args[1]}（允许 {@code null} / 空白）
     * @param target     {@code args[2]}（允许 {@code null} / 空白 = 没写）
     * @param amount     {@code args[3]}（允许 {@code null} / 空白 = 没写；
     *                   {@code infinite-xp} 时它是 {@code on|off}）
     */
    public static ParseResult parse(String subcommand, String target, String amount) {
        Optional<String> name = subcommandFor(subcommand);
        if (name.isEmpty()) {
            String shown = subcommand == null ? "" : subcommand.trim();
            return ParseResult.fail(MSG_UNKNOWN + "" + shown + "；本指令只认：" + usage());
        }
        Subcommand spec = BY_NAME.get(name.get());
        String wanted = blankToNull(target);

        // ③.5 ★ purge-legacy（2026-10-17 第十二轮）：第三段是**可选**的 --yes。
        //   判据：只认逐字的 `--yes`（不认 -y / yes / confirm —— 多认一个同义词
        //   就是替用户改指令语法，铁律 11）；**不写 = 只报告**（默认安全的那一侧）。
        if (spec.needsConfirmFlag()) {
            String flag = blankToNull(amount);
            if (flag != null && !CONFIRM_FLAG.equals(flag.trim())) {
                return ParseResult.fail("" + flag.trim() + " 不是 " + spec.name()
                        + " 的参数：它只有 " + CONFIRM_FLAG + " 一个可选参数"
                        + "（不写 = 只报告不删）　用法：" + debugUsage(spec));
            }
            boolean confirm = flag != null;
            return ParseResult.ok(new Run(spec.name(), wanted, null, null, confirm));
        }

        // ④ infinite-xp：第三段是 on|off（缺参 / 非法值都明确失败，绝不猜）
        if (spec.needsToggle()) {
            Toggle toggle;
            try {
                toggle = parseToggle(amount);
            } catch (IllegalArgumentException e) {
                return ParseResult.fail(MSG_INVALID_TOGGLE + e.getMessage()
                        + "　用法：" + debugUsage(spec));
            }
            return ParseResult.ok(new Run(spec.name(), wanted, null, toggle));
        }

        // ③ 需要数量的两条：目标必填 + 数量必填且合法
        if (spec.needsAmount()) {
            if (wanted == null) {
                return ParseResult.fail(MSG_MISSING_TARGET + "用法：" + debugUsage(spec));
            }
            try {
                return ParseResult.ok(new Run(spec.name(), wanted, parseAmount(amount)));
            } catch (IllegalArgumentException e) {
                return ParseResult.fail(MSG_INVALID_AMOUNT + e.getMessage()
                        + "　用法：" + debugUsage(spec));
            }
        }

        // ② 目标可省的两条：多给一个参数就明确失败
        String extra = blankToNull(amount);
        if (extra != null) {
            return ParseResult.fail(MSG_TOO_MANY_ARGS + "" + extra + "；用法：" + debugUsage(spec));
        }
        return ParseResult.ok(new Run(spec.name(), wanted, null));
    }

    /**
     * ★ <b>纯</b>：解析 {@code infinite-xp} 的开关参数（{@code on} / {@code off}）。
     *
     * <ul>
     *   <li>{@code null} / 空白 ⇒ 抛（<b>缺参</b>—— 不知道要开还是要关，绝不猜一个）；</li>
     *   <li>{@code "on"} / {@code " ON "} ⇒ {@link Toggle#ON}（大小写与两侧空白不敏感，
     *       与 {@link #subcommandFor} / {@link #parseAmount} 的口径一致）；</li>
     *   <li>{@code "off"} ⇒ {@link Toggle#OFF}；</li>
     *   <li>{@code "1"} / {@code "true"} / {@code "enable"} / {@code "yes"} / {@code "开"}
     *       ⇒ 抛 —— {@code docs/10} §3 写的是 {@code [on|off]}，<b>只认这两个词</b>
     *       （多认一个同义词就是替用户改指令语法，铁律 11）。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 消息是<b>中文</b>的「实际收到什么」，由调用方拼进提示
     */
    public static Toggle parseToggle(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少 on|off 参数（这条子指令没有默认值："
                    + "不写就不知道该开还是该关）");
        }
        // ★★ 两个变量刻意分开（被测试抓出来的）：
        //   `shown` = **原样**（去掉两侧空白），用于**报错**；`text` = 小写化，用于**比较**。
        //   只留小写化的那份 ⇒ 运维敲 `ONN` 时会看到「`onn` 不合法」，
        //   他没法确认自己究竟敲错了哪个字符（报错必须回显**实际收到的东西**）。
        String shown = raw.trim();
        String text = shown.toLowerCase(Locale.ROOT);
        return switch (text) {
            case "on" -> Toggle.ON;
            case "off" -> Toggle.OFF;
            // ★ 消息里必须**写出合法取值**（`on|off`）：运维看到 `1` 被拒时，
            //   下一句想知道的就是"那能填什么" —— 只说"不合法"等于让人去翻文档。
            default -> throw new IllegalArgumentException("" + shown
                    + " 不合法：合法取值只有 on 与 off 两个"
                    + "（docs/10 §3 的语法就是 [on|off]；本插件不认 1 / true / enable 这类同义词）");
        };
    }

    /** 一条子指令的完整用法（形如 {@code /incl debug set-gold <玩家> <数量>}）。 */
    private static String debugUsage(Subcommand spec) {
        return "/incl debug " + spec.usage();
    }

    /**
     * ★ <b>纯</b>：解析数量参数（{@code set-gold} / {@code set-powder} 共用一处）。
     *
     * <ul>
     *   <li>{@code null} / 空白 ⇒ 抛（<b>缺参</b>不是 0）；</li>
     *   <li>{@code "-1"} ⇒ 抛（负数：调试指令是<b>设置</b>成某个值，不是加减；负的余额无意义）；</li>
     *   <li>{@code "12.5"} / {@code "abc"} / {@code "1e3"} ⇒ 抛（只认十进制整数）；</li>
     *   <li>超过 {@link Integer#MAX_VALUE} ⇒ 抛（进度字段是 {@code int}，在这里就报，别等到构造器）；</li>
     *   <li>{@code " 42 "} ⇒ {@code 42}（两侧空白忽略，与 {@link MenuSubcommands} 的口径一致）；</li>
     *   <li>{@code "-0"} ⇒ {@code 0}（数值上不是负数，不拒绝）。</li>
     * </ul>
     *
     * @throws IllegalArgumentException 消息是<b>中文</b>的「实际收到什么」，由调用方拼进提示
     */
    public static int parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少数量参数（要 0 ~ " + Integer.MAX_VALUE + " 的整数）");
        }
        String text = raw.trim();
        boolean negative = text.startsWith("-");
        String digits = negative || text.startsWith("+") ? text.substring(1) : text;
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("" + text + " 不是十进制整数（要 0 ~ "
                    + Integer.MAX_VALUE + " 的整数）");
        }
        BigInteger value = new BigInteger(digits);
        if (negative && value.signum() != 0) {
            throw new IllegalArgumentException("" + text + " 是负数（调试指令只能把数量设置成 "
                    + "0 ~ " + Integer.MAX_VALUE + "，不能设成负数）");
        }
        if (value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("" + text + " 超出 int 范围（进度字段是 int，最大 "
                    + Integer.MAX_VALUE + "）");
        }
        return value.intValue();
    }

    // ================================================================== 文案（代码常量）

    /**
     * ★ 名字不认识时的前缀。
     *
     * <p>★ 为什么不读 {@code display.yml}：出厂 YAML <b>一个字节都不许改</b>（本轮硬约束），
     * 而对应的键里<b>还没有</b>这一条 ⇒ 按「文案键若缺 ⇒ 用代码里的常量」用常量。
     * 与这几条最接近的既有键是 {@code messages.command.usage} / {@code command.debug-usage}
     * （出厂值仍是 {@code [待填]}，铁律 8 / 11：占位符绝不印给玩家）。
     */
    static final String MSG_UNKNOWN = "未知的 debug 子指令：";

    /**
     * ★ 「要显式指定目标玩家」的提示（{@code set-gold} / {@code set-powder} 缺目标时）。
     *
     * <p>目标键 {@code messages.command.player-not-found}（{@code display.yml:181}）；
     * 出厂值是 {@code [待填]} ⇒ 本轮用代码常量。
     */
    static final String MSG_MISSING_TARGET = "这条子指令必须显式指定目标玩家（控制台发令时更要写）。";

    /**
     * ★ 数量参数非法的前缀（负数 / 非数字 / 缺参 / 超 int 范围）。
     *
     * <p>目标键 {@code messages.command.invalid-amount}（{@code display.yml:183}，注释里逐字写着
     * 「数量参数不是合法数字（如 {@code set-gold} / {@code set-powder}，{@code docs/10} §3）的提示」）；
     * 出厂值是 {@code [待填]} ⇒ 本轮用代码常量（★ 那一句注释正是本类与文档同一件事的证据）。
     */
    static final String MSG_INVALID_AMOUNT = "数量参数不合法：";

    /**
     * ★ 「参数给多了」的前缀（{@code grant-all} / {@code reset-collection} 后面多写了一个片段）。
     */
    static final String MSG_TOO_MANY_ARGS = "参数多了：";

    /**
     * ★ {@code infinite-xp} 的开关参数非法的前缀（{@link #parseToggle}：
     * 缺参 / 不是 {@code on|off}）。
     *
     * <p>★ 为什么要有这一句而不是「沿用 {@link #MSG_INVALID_AMOUNT}」：这一条的第三段
     * <b>不是数量</b>（{@code docs/10} §3 写的是 {@code [on|off]}）——
     * 报「数量参数不合法」会让玩家以为要填一个数字。
     */
    static final String MSG_INVALID_TOGGLE = "on|off 参数不合法：";

    /**
     * ★ 找不到目标玩家时的提示（调用方在 {@code Bukkit.getPlayerExact} 返回 {@code null} 时用）。
     *
     * <p>★ 为什么只认「在线玩家名」：本类<b>不查在线玩家列表</b>（铁律 10 禁 {@code getOnlinePlayers}），
     * 唯一允许的按名字查是 {@code Bukkit.getPlayerExact}（单点查，范围 = 一个名字）。
     * 要改一个<b>不在线</b>的玩家，请用既有的 {@code /incl debug profile … <uuid>} 那条链。
     */
    static final String MSG_PLAYER_NOT_FOUND =
            "找不到这个在线玩家（本指令只按名字查在线玩家；不在线的请用 /incl debug profile show <uuid> 那条链）。";

    /** 空白串当没写（{@code null} / {@code ""} / {@code " "} 一律 {@code null}）。 */
    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    // ================================================================== 落盘那一步（可注入的接缝）

    /**
     * ★ <b>「读 → 变换 → 写」那一步</b>：失败方向与「绝不绕过 {@code ProfileStore}」都收在这里。
     *
     * <h2>为什么单独抽成静态方法 + 两个函数参数</h2>
     * {@code profile/ProfileStore} 是 <b>{@code final class}</b>（没有接口、也没法继承
     * ⇒ 普通 JUnit 里造不出假件，{@code Proxy} 也拒绝：{@code is not an interface}）。
     * 而「未知名 / 非法数量 / 无权限 ⇒ <b>一个字节都不写</b>」这条断言正是本轮最该钉住的性质。
     * ⇒ 把「读」与「写」做成两个<b>函数参数</b>：
     * <ul>
     *   <li>装配层传的就是 {@code store::load} 与 {@code store::save} —— ★ <b>真实路径仍然只有
     *       {@code ProfileStore} 一个业务入口</b>，本方法自己<b>不</b>碰 MGC、不拼 JSON；</li>
     *   <li>离线测试传两个「会留痕 / 会炸」的假函数 ⇒ 就能断言「有没有写」而不用起服务器。</li>
     * </ul>
     *
     * <h2>失败方向</h2>
     * 读失败由 {@code load} 自己按 {@code ProfileStore} 的口径处理（告警 + 返回空白）；
     * 变换失败（{@code ProgressEdits} 拒绝非法卡池）⇒ 这里吃掉、<b>不</b>调 {@code save}、
     * 回一句中文；落盘失败由 {@code save} 自己告警（绝不抛、绝不打断对局）。
     *
     * <h2>★ {@value #INFINITE_XP} 为什么在这里返回 {@code null}</h2>
     * 它<b>不写档案</b>：用户裁定「打牌不扣经验」要改的是<b>对局运行时的一个开关</b>
     * （{@code match/MatchService.setFreeCasting}，作用域 = 发令者当前那一场，按 {@code matchId}）。
     * ⇒ 本方法对它**一个字节都不写**，并明确说「这条不在这里执行」；
     * 真正的执行在 {@code InfernalClashCommand} 那条分支里（★ 刻意**不**塞一个
     * {@code MatchService} 参数进来：那会把「改档案」与「改对局」两条路径搅在一起，
     * 也会让本类不再是纯的）。
     *
     * @param storeLoad 读一份进度（真实路径 = {@code ProfileStore::load}）
     * @param storeSave 写回一份进度（真实路径 = {@code ProfileStore::save}）
     * @param cardPool 卡池里的**全部**卡 id（{@code grant-all} 用；唯一来源 = {@code card/CardCatalog}）
     * @param basicCardPool ★ 卡池里的**基础档**（{@code rarity == BASIC}）卡 id
     *                      （{@code reset-collection} 用）。★ 2026-10-21 用户裁定
     *                      「重置卡牌收藏时不应该删掉我的基础卡」⇒ 重置后要**保留**这一批；
     *                      判据只有 {@code R44BasicGrants#basicCardIds} 一处，本类与
     *                      {@code ProgressEdits} 都不写死卡名。
     * @param onProblem 拒绝原因出口（调用方把它转成玩家可读的一句话 + 一条日志）
     * @return 传给 {@code save} 的那一份（便于调用方打印改前改后）；<b>{@code null} 表示没有写</b>
     */
    public static PlayerProgress applyEdit(Run run, UUID target,
                                           Function<UUID, PlayerProgress> storeLoad,
                                           BiConsumer<UUID, PlayerProgress> storeSave,
                                           Collection<String> cardPool,
                                           Collection<String> basicCardPool,
                                           Consumer<String> onProblem) {
        PlayerProgress before = storeLoad.apply(target);
        PlayerProgress after;
        try {
            after = switch (run.action()) {
                case GRANT_ALL -> ProgressEdits.acceptAll(before, cardPool);
                case RESET_COLLECTION -> ProgressEdits.resetCollection(before, basicCardPool);
                case SET_GOLD -> ProgressEdits.setNumber(before, ProgressEdits.Field.GOLD, run.amount());
                case SET_POWDER -> ProgressEdits.setNumber(before, ProgressEdits.Field.DUST, run.amount());
                // ★ 不写档案的那一条：它是「对局开关」，由 InfernalClashCommand 那条分支执行
                //   （见本方法 javadoc；★ 这里**不静默返回旧档案**，否则会触发一次无意义的落盘）
                case INFINITE_XP -> null;
                default -> null;
            };
        } catch (RuntimeException e) {
            // 纯函数拒绝（卡池里有重复 id / id 为空 …）：输入一字未动，也绝不落到 save
            onProblem.accept("这次改动被拒绝（数据没有变）：" + e.getMessage());
            return null;
        }
        if (after == null) {
            onProblem.accept(run.action().equals(INFINITE_XP)
                    ? "这条子指令不写档案（它改的是「当前那一场对局」的开关），请用 "
                            + "/incl debug infinite-xp [玩家] on|off，由指令层执行"
                    : "未知的 debug 子指令：" + run.action());
            return null;
        }
        storeSave.accept(target, after);
        return after;
    }
}
