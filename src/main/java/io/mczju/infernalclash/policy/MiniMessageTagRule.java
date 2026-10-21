package io.mczju.infernalclash.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ★★★ <b>MiniMessage 标签合法性校验</b> —— 2026-10-18 新增
 * （用户报「金卡文本随机出错」的**根治**）。
 *
 * <h2>它防的是哪一类事故</h2>
 * 用户原话：「**卡牌是不显示金色/文字变金色但保留 `<red>` 这样的字段的情况似乎完全随机发生**」。
 * 查证结论：出厂卡里有 **10 张**写了 {@code <purple>} ——
 * 而 MiniMessage 的命名色**只有** {@code <light_purple>}，**没有** {@code <purple>}。
 * 未知标签会被 MiniMessage 当**纯文本**保留 ⇒ 那 10 张卡的名字**字面显示**成
 * {@code <purple>灵狐引路}。
 *
 * <p>★ 为什么它看起来「完全随机」：取决于**那张卡的数据**用了哪种标签 ——
 * 用 {@code <gold>} 的显示金色、用 {@code <red>} 的显示红色、用 {@code <purple>} 的字面显示。
 * 与界面、与稀有度、与 {@code grant-all} 都无关。
 *
 * <h2>为什么要在**装载期**拒绝（而不是渲染时兜底）</h2>
 * 渲染期兜底只能"少坏一点"，而这类错误**永远应该在有人的时候暴露**：
 * 卡表是手写的，写错一个字母的代价是「那张卡的名字显示成乱码」，
 * 而玩家看到的是一堆尖括号 —— 没人会去查配置。
 * ⇒ 与 {@code docs/05} 里「判据要在能拒绝的最早时刻拒绝」同一口径。
 *
 * <h2>判据（白名单，不是黑名单）</h2>
 * 一个标签算合法，当且仅当它满足下列**之一**：
 * <ol>
 *   <li>是已知的**颜色 / 样式**名（复用 {@link SideNameColor#isColorOrStyleTag}
 *       —— 与召唤物名牌共用**同一份**白名单，避免第二处事实来源）；</li>
 *   <li>是带参数的**非颜色**标签：{@code color:…} / {@code lang:…} / {@code key:…} /
 *       {@code translate:…} / {@code click:…} / {@code hover:…} / {@code font:…} /
 *       {@code selector:…} / {@code score:…} / {@code nbt:…}。</li>
 * </ol>
 * ★ 不做成"任何英文单词都算"那种宽判据 —— 那会让 {@code <purple>} 混过去，
 * 也就等于这条校验不存在。
 */
public final class MiniMessageTagRule {

    /** 允许的**带参数**标签前缀（不含冒号本身）。 */
    private static final List<String> PARAMETER_TAGS = List.of(
            "color", "colour", "lang", "key", "translate", "click", "hover", "font",
            "selector", "score", "nbt");

    private MiniMessageTagRule() {
    }

    /**
     * ★ 找出文本里**非法**的 MiniMessage 标签。
     *
     * @param text 待检查的文案（可以是 {@code null} / 空）
     * @return 非法标签名（**不含**尖括号、按出现顺序、可能重复）；空列表 = 全部合法
     */
    public static List<String> illegalTags(String text) {
        List<String> bad = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return bad;
        }
        int index = 0;
        while (index < text.length()) {
            int open = text.indexOf('<', index);
            if (open < 0) {
                break;
            }
            int close = text.indexOf('>', open + 1);
            if (close < 0) {
                break;   // 没有闭合 ⇒ 不是标签（当普通文本，MINI_MESSAGE 也不会报错）
            }
            String tag = text.substring(open + 1, close);
            if (!isLegal(tag)) {
                bad.add(tag);
            }
            index = close + 1;
        }
        return bad;
    }

    /** 这一段字面量是不是合法标签（{@code /} 关闭写法与参数标签都按同名处理）。 */
    public static boolean isLegal(String tag) {
        if (tag == null) {
            return true;
        }
        String name = tag.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return true;   // <> 当普通文本
        }
        if (SideNameColor.isColorOrStyleTag(name)) {
            return true;
        }
        int colon = name.indexOf(':');
        if (colon > 0) {
            String prefix = name.substring(0, colon);
            if (prefix.startsWith("/")) {
                prefix = prefix.substring(1);
            }
            return PARAMETER_TAGS.contains(prefix);
        }
        // ★ 带参数标签的**关闭写法**：`<color:#fff>x</color>` 里那个 `</color>`
        //   没有冒号 ⇒ 上面那条走过不去 ⇒ 这里再给一次机会。
        String bare = name.startsWith("/") ? name.substring(1) : name;
        return PARAMETER_TAGS.contains(bare);
    }

    /** 一行说明（{@code ConfigError} 用）：把非法标签与「你是不是想写 X」的建议一起给出来。 */
    public static String describe(List<String> illegal) {
        if (illegal == null || illegal.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String tag : illegal) {
            if (out.length() > 0) {
                out.append("；");
            }
            out.append('<').append(tag).append('>');
            String hint = hintFor(tag);
            if (hint != null) {
                out.append("（是不是想写 ").append(hint).append("？）");
            }
        }
        return out.toString();
    }

    /**
     * 常见写错的温和提示（★ 只是提示，**不会**把它当成合法值 ——
     * 比如 {@code purple} 被指向 {@code light_purple}，但拒绝启动这一点不变）。
     */
    private static String hintFor(String tag) {
        String name = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "purple" -> "<light_purple>";
            case "pink" -> "<light_purple>";
            case "orange" -> "<gold>";
            case "cyan" -> "<dark_aqua> 或 <aqua>";
            case "magenta" -> "<light_purple>";
            case "brown" -> "<dark_red> 或 <gold>";
            case "silver" -> "<gray> 或 <white>";
            case "lime" -> "<green>";
            case "navy" -> "<dark_blue>";
            case "dark_grey" -> "<dark_gray>";
            case "grey" -> "<gray>";
            case "light_gray", "light_grey" -> "<gray>";
            default -> null;
        };
    }
}
