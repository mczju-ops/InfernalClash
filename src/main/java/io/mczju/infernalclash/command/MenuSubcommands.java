package io.mczju.infernalclash.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * ★ <b>{@code /incl} 里「打开界面」那几条子指令的纯解析表</b>（P8 接线的落点）。
 *
 * <h2>为什么单独抽一个类</h2>
 * {@code docs/10} §2.1 明写「子命令别名要在<b>解析表</b>里集中维护（一处映射），不要散在各分支里」。
 * 本类就是那张表，它只回答两个问题：
 * <ol>
 *   <li>这个子指令名是「打开界面」吗？是哪一条？（{@link #screenFor(String)}）</li>
 *   <li>一共注册了哪几个名字？（{@link #names()} —— 补全与用法输出都取它，不再各写一份）</li>
 * </ol>
 *
 * <p>★ 它<b>不</b> import Bukkit / MGC、<b>不</b>打开任何界面（那是
 * {@link InfernalClashCommand} 调 {@code ui/MenuRegistry} 的事）⇒ 可在普通 JUnit 里离线断言
 * （{@code MenuSubcommandsTest}）。这正是本项目「映射与判据做成纯函数」的纪律（{@code docs/18} §7）。
 *
 * <h2>★ 中文子命令别名（Q39，2026-10-15 接）</h2>
 * 别名清单<b>照抄 {@code docs/10} §2.1</b>（不自己编）：{@code 图鉴} → {@code cards}、
 * {@code 商店} → {@code shop}、{@code 调试} → {@code debug}。落点如下：
 * <ul>
 *   <li>★ <b>本表认前两条</b>（{@code 商店} / {@code 图鉴}）—— 别名与规范名<b>一起进</b>
 *       {@link #BY_NAME}，仍然只有这<b>一处</b>「名字 → 动作」的事实来源
 *       （别名是声明在枚举常量上的，不是第二张表；重名会在 {@link #index()} 里 fail-fast）；</li>
 *   <li>⚠️ <b>第三条 {@code 调试} 不在本表</b>：它的目标是根分支 {@code debug}，而 {@code debug}
 *       <b>不是</b>三个界面动作之一（{@link Screen} 里没有 debug，也不该有 —— 它不开界面）。
 *       要接它得改 {@code InfernalClashCommand} 的根分发（本轮不在允许改动的文件内）⇒
 *       <b>登记在案、不猜</b>（见 {@code docs/11} §W 的 W18）。</li>
 * </ul>
 *
 * <h2>★★ 为什么这几条指令必须有离线测试钉住（2026-10-15 实测的教训）</h2>
 * {@code docs/12} §4.14 让用户敲 {@code /incl shop} 与 {@code /incl cards}，而实测
 * {@code grep '"shop"'} 与 {@code grep '"cards"'} 在 {@code command/*.java} 里<b>各 0 命中</b>
 * —— 两条指令<b>根本没注册</b>，照着文档敲只会得到一句用法提示
 * （<b>文档与实现不符 = 缺陷</b>，铁律 1）。⇒ 测试同时钉住三件事：
 * ①名字在<b>本表</b>里；②名字在 {@link InfernalClashCommand} 的<b>根补全表</b>里（两条路一起断掉才算真的死）；
 * ③名字对应的<b>界面 id</b> 与 {@code MenuRegistry.menuIds()} 对得上。
 * 任何一条被删，测试先红 —— 文档不会再静默变回「死指令」。
 */
public final class MenuSubcommands {

    /**
     * 「打开界面」各子指令对应的<b>动作</b>（与 {@code ui/MenuRegistry} 的三个打开入口一一对应）。
     *
     * <p>★ 枚举常量<b>只有名字与顺序</b>，不含任何界面 id —— id 的唯一来源是
     * {@code ui/MenuRegistry}（「界面 id 只有一处定义」），本类重复一份就会成为第二个白名单。
     */
    public enum Screen {

        /**
         * {@code /incl shop} ⇒ {@code MenuRegistry.openShop}（局外商店，{@code docs/09} §4）。
         *
         * <p>出处：{@code docs/10} §2「商店 · {@code /incl shop} · 权限 {@code infernalclash.play}」。
         *
         * <p>★ 别名 {@code 商店}：逐字取自 {@code docs/10} §2.1 的别名清单（Q39）。
         */
        SHOP("shop", "商店"),

        /**
         * {@code /incl cards} ⇒ {@code MenuRegistry.openDeck}（<b>图鉴 + 卡组预设</b>，
         * {@code docs/09} §3）。
         *
         * <h2>★ 为什么是 {@code openDeck} 而不是 {@code openCodex}（判断依据）</h2>
         * <ol>
         *   <li>{@code docs/10} §1 把 <b>R68「打开图鉴」</b>的实现列写成
         *       「<b>打开图鉴 / 卡组预设</b>」—— 与 {@code docs/09} §3 的标题
         *       「U2 图鉴 + 卡组预设」逐字对应；</li>
         *   <li>{@code docs/11} §W 的 <b>W2</b> 把 {@code ui/DeckPresetMenu} 命名为
         *       「<b>U2 卡组预设 / 图鉴</b>界面」，而 <b>W4</b> 把 {@code ui/CodexMenu} 命名为
         *       「<b>图鉴（只读）</b>」—— 只有前者同时含「卡组预设」；</li>
         *   <li>{@code ui/MenuRegistryTest} 的断言措辞同口径：{@code MENU_DECK} =「图鉴+卡组 id」、
         *       {@code MENU_CODEX} =「只读图鉴 id」。</li>
         * </ol>
         * ⚠️ 有一处<b>反证</b>，登记在此以免后人再纠结：{@code ui/MenuRegistry.java} 的
         * {@code openCodex} javadoc 写「它是 {@code docs/10} §3 的 {@code /incl cards} 将来的落点」，
         * 但 ①{@code /incl cards} 在 {@code docs/10} 的 <b>§2</b>（§3 是 debug 子命令，章节号引错）；
         * ②同一份 javadoc 把商店叫「U4」、把只读图鉴叫「U5」，而 {@code docs/09} 的
         * U4 = 比赛内选卡 / U5 = 局外商店（编号对不上）⇒ 该注解的<b>引用与编号都不可靠</b>，
         * 故以 {@code docs/09} + {@code docs/11} §W 的命名为准。
         *
         * <p>★ 别名 {@code 图鉴}：逐字取自 {@code docs/10} §2.1 的别名清单（Q39）。
         * {@code §2.1} <b>没有</b>给只读图鉴（{@code codex}）任何别名 ⇒ {@link #CODEX} 的别名表是空的
         * （<b>不自己编</b>）。
         */
        CARDS("cards", "图鉴"),

        /**
         * {@code /incl codex} ⇒ {@code MenuRegistry.openCodex}（<b>只读</b>图鉴，{@code docs/11} §W W4）。
         *
         * <p>⚠️ ★ <b>这一条是「不让 {@code openCodex} 继续零调用点」而补的入口，可否决</b>：
         * Q17 裁定的分支只有「商店 / 图鉴 / debug」，{@code docs/10} §2 的表里<b>没有</b>
         * {@code codex} 这一行。保留它的理由是：只读图鉴是<b>已注册的第三个界面</b>（§W W4），
         * 否则它只能靠 MGC 的通用通道 {@code /menu infernalclash:codex <玩家名>} 打开。
         * <b>若认为多了一个分支</b>：删掉这个常量、{@code MenuSubcommandsTest} 里对应断言与
         * {@code docs/11} §W 的 W17 行即可（删后 {@code openCodex} 又回到零调用点）。
         */
        CODEX("codex");

        private final String commandName;

        /** 该动作的全部别名（目前只有 {@code docs/10} §2.1 的中文别名；没有别名就是空表）。 */
        private final List<String> aliases;

        Screen(String commandName, String... aliases) {
            this.commandName = commandName;
            this.aliases = List.of(aliases);
        }

        /** 玩家敲的那个名字（全小写；解析时大小写不敏感、两侧空白忽略）。 */
        public String commandName() {
            return commandName;
        }

        /**
         * ★ 该动作的别名（<b>与规范名同源</b>：都声明在<b>这个枚举常量</b>上，不另抄一份表）。
         *
         * <p>出处一律是 {@code docs/10} §2.1 的别名清单（Q39）——「需要自己解析」那一条。
         * 别名只影响<b>入口解析</b>（多认几个名字）：动作、界面 id、权限判据一个都不多。
         */
        public List<String> aliases() {
            return aliases;
        }

        /** 这个动作能被敲出来的<b>全部</b>名字 = 规范名 + 别名（顺序：规范名在前）。 */
        public List<String> allNames() {
            List<String> all = new ArrayList<>(1 + aliases.size());
            all.add(commandName);
            all.addAll(aliases);
            return List.copyOf(all);
        }
    }

    /**
     * 名字 → 动作的查表（<b>规范名与别名一起进这一张表</b> ⇒ 仍然只有一处事实来源）。
     *
     * <p>★ 用 {@code LinkedHashMap} 而<b>不是</b> {@code Map.copyOf}：后者的迭代顺序是随机的，
     * 而本项目「凡依赖顺序处必须保序」的教训已有实测记录（{@code Map.copyOf} 跨重启不可复现）。
     */
    private static final Map<String, Screen> BY_NAME = index();

    private static Map<String, Screen> index() {
        Map<String, Screen> map = new LinkedHashMap<>();
        for (Screen screen : Screen.values()) {
            for (String name : screen.allNames()) {
                Screen previous = map.put(name, screen);
                if (previous != null) {
                    // 两个动作（含别名）抢同一个名字 ⇒ 分发结果取决于声明顺序，属于接线错误，fail-fast
                    throw new IllegalStateException("子指令名/别名重复（接线错误）：" + name
                            + " 同时属于 " + previous + " 与 " + screen);
                }
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private MenuSubcommands() {
    }

    /**
     * 全部「打开界面」子指令名：<b>先规范名（英文，按声明顺序），再别名（中文，按声明顺序）</b>。
     *
     * <p>★ <b>它含别名</b>，理由有两条：
     * <ol>
     *   <li>{@code InfernalClashCommand.rootArgs()} 直接由本方法拼出根补全表（那是一处<b>不可</b>
     *       在本轮改动的地方）⇒ 只有把别名放进这里，「Tab 补得出来 ⇔ 敲得通」这条不变式
     *       才在<b>集合层面</b>成立（{@code MenuSubcommandsTest} 正守着它：补全与分发表分叉会出现
     *       「Tab 补得出来却敲不通」这类难查的毛病）；{@code docs/10} §7 也写着
     *       「{@code /incl <TAB>} → shop | cards | debug（+ 中文别名）」；</li>
     *   <li>★ <b>同源</b>：这里<b>不是</b>另抄一份别名表，而是逐个 {@link Screen#allNames()} 拼出来的
     *       —— 别名改一处（枚举常量），补全与解析同时跟着变。</li>
     * </ol>
     * 规范名仍排在前面且一条不少（{@code shop} / {@code cards} / {@code codex} 照旧工作）。
     */
    public static List<String> names() {
        List<String> names = new ArrayList<>(Screen.values().length);
        for (Screen screen : Screen.values()) {
            names.add(screen.commandName());
        }
        for (Screen screen : Screen.values()) {
            names.addAll(screen.aliases());
        }
        return List.copyOf(names);
    }

    /**
     * 解析一个子指令片段（<b>规范名与中文别名走同一条路</b>，见 {@link Screen#aliases()}）。
     *
     * @param subcommand 玩家敲的原始片段（允许 {@code null} / 空串 / 大小写混合 / 两侧带空白）
     * @return 命中的动作；<b>不认识就返回 {@link Optional#empty()}</b> —— 调用方据此走
     *         「未知子指令」那条路（绝不静默当成某一条已知指令，否则敲错一个字就会打开错的界面；
     *         别名也<b>只认 {@code docs/10} §2.1 明写的那几个</b>）
     */
    public static Optional<Screen> screenFor(String subcommand) {
        if (subcommand == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NAME.get(subcommand.trim().toLowerCase(Locale.ROOT)));
    }
}
