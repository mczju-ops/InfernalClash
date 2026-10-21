package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.match.Side;

import java.util.Locale;
import java.util.Objects;

/**
 * ★★★ <b>「按阵营给名牌着色」的唯一映射表 ＋ 召唤物显示名的唯一生成点</b>
 * —— 2026-10-17 第十二轮新增。用户原话（逐字）：
 * <blockquote>
 * 「以及，召唤物现在不显示名称，也不会用名称颜色区分所属阵营」
 * </blockquote>
 *
 * <h2>★ 文档依据（逐字，不是我自创的格式）</h2>
 * {@code docs/09-UI设计.md} §6 第 9 项（= {@code docs/05} 的 【C16】）：
 * <ul>
 *   <li>第 430 行：「<b>② 对战中用红 / 蓝名字标记双方召唤物</b> ⇒ 需要<b>按阵营给名牌着色</b>。」</li>
 *   <li>第 431 行：「★ ⇒ 抽成纯函数 <b>{@code SideNameColor.apply(name, side)}</b>，
 *       {@code side} 来自上面那条「加入顺序」。」</li>
 *   <li>★ 第 428 行：「<b>信标光束配色</b>与<b>名牌阵营色</b>都读这同一个值 ——
 *       <b>不要两处各判一次</b>（否则会出现「红方玩家看到蓝光束」）。」</li>
 *   <li>★ 第 434 行（InfernalGuess 实测过的坑）：「<b>MiniMessage 的颜色会持续生效并盖掉后设的颜色</b>
 *       ⇒ <b>生物名前必须重新应用阵营色</b>。」</li>
 *   <li>★ 第 436~437 行：「金卡的 {@code <gold>} 名称与阵营红 / 蓝同处一个名牌 ⇒
 *       必须<b>明确谁最后应用</b>（<b>结论：阵营色最后应用</b>）」</li>
 * </ul>
 * ⇒ 本类就是文档点名的那个纯函数，并且是**全项目唯一**一处颜色映射
 * （目标物 / 召唤物 / 侧边栏共用它 —— 「不要两处各判一次」）。
 *
 * <h2>★★ 「阵营色最后应用」怎么在实现上落地</h2>
 * 卡牌的 {@code display-name} 自带 MiniMessage 颜色（例如 {@code <white>铁甲战士}、
 * 金卡是 {@code <gold>…}）。若直接把卡名加上阵营色前缀，**里面的 {@code <white>} 会把前缀覆盖掉**
 * （MiniMessage 的内层标签优先）⇒ 那正是 IG 踩过的坑。
 * <p>本类的做法：<b>先把卡名里已有的颜色标签剥掉</b>（{@link #stripColorTags}），
 * 再套上阵营色 ⇒ 阵营色**必然**是最后生效的那一层。
 * ★ 剥标签只做「去色」——不改文字内容（测试逐字钉住「剥完还是原来那串汉字」）。
 *
 * <h2>★ 未知 Side ⇒ fail-safe 中性色（不抛异常）</h2>
 * 玩家可见的名牌不该因为一次意外写入就把召唤流程打断 ⇒ {@code side == null} 时用
 * {@link #NEUTRAL}（灰）。★ 这是「显示层的失败方向要安全」的体现。
 */
public final class SideNameColor {

    /**
     * ★ <b>红方的颜色</b>（MiniMessage 标签名）。
     *
     * <p>★ 文档只写了「红 / 蓝名字」，**没给具体色值** ⇒ 取最直白的一对
     * {@code red} / {@code blue}；已登记 {@code docs/05} <b>E24</b> 请用户可否决
     * （改这一处即可，全项目共用它）。
     */
    public static final String RED = "red";

    /** ★ <b>蓝方的颜色</b>（见 {@link #RED} 的说明）。 */
    public static final String BLUE = "blue";

    /** ★ 未知阵营的中性色（fail-safe：不抛异常、也不假装知道）。 */
    public static final String NEUTRAL = "gray";

    /**
     * ★ 召唤物名牌的**固定后缀**（把「这是召唤物」与玩家名牌区分开）。
     *
     * <p>用户只要求「有可见名称 + 按阵营区分颜色」，**没给名字格式** ⇒
     * 本类**不加**任何前缀图标 / 等级后缀 / 卡费后缀这类自创格式（铁律 11）：
     * 名字就是卡名本身，只加颜色。
     */
    private SideNameColor() {
    }

    /**
     * ★★★ <b>按阵营给名牌着色</b>（文档点名的那个纯函数）。
     *
     * @param name 卡牌的显示名（可含 MiniMessage 颜色标签，例如 {@code <white>铁甲战士}）；
     *             {@code null} / 空白 ⇒ 返回中性色的占位（绝不返回 {@code null} ——
     *             调用方拿它去 {@code setCustomName}，null 就是"没名字"）
     * @param side 阵营；{@code null} ⇒ {@link #NEUTRAL}（fail-safe）
     * @return MiniMessage 文本（★ 阵营色**最后应用**：卡名里原有的颜色标签已被剥掉）
     */
    public static String apply(String name, Side side) {
        String text = name == null || name.isBlank() ? "召唤物" : name.trim();
        return "<" + colorOf(side) + ">" + stripColorTags(text);
    }

    /**
     * 阵营 → 颜色标签名（★ <b>全项目唯一</b>的一处映射）。
     *
     * <p>★ {@code docs/09} 第 428 行的要求：「信标光束配色与名牌阵营色都读这同一个值
     * —— 不要两处各判一次」。⇒ 目标物（信标 / 塔）的光束颜色也应该来问这里
     * （{@code objective/ObjectiveService} 的 {@code particle()} 已经用红/蓝区分，
     * 本批把「名字」这一侧统一到本方法）。
     */
    public static String colorOf(Side side) {
        if (side == null) {
            return NEUTRAL;
        }
        return switch (side) {
            case RED -> RED;
            case BLUE -> BLUE;
        };
    }

    /**
     * ★ <b>剥掉 MiniMessage 的颜色 / 样式标签，只留文字</b>。
     *
     * <p>为什么需要它（IG 实测过的坑，见类注释）：卡名里的 {@code <white>} 会盖掉
     * 前面刚加的阵营色 ⇒ 必须**先剥掉**、再加阵营色，阵营色才是最后生效的那一层。
     * <p>★ 只剥「颜色与样式」标签（{@code <red>} / {@code <white>} / {@code <gold>} /
     * {@code <bold>} / {@code </red>} 这类），**不动**文字内容，也不动别的标签
     * （例如将来的 {@code <lang:…>}）—— 那些不是颜色，剥掉会改变显示内容。
     * <p>★ 判据是「标签名本身是一个已知的颜色 / 样式名」而不是「长得像标签」：
     * 否则 {@code <玩家名>} 这种会被误剥。
     */
    public static String stripColorTags(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int open = text.indexOf('<', index);
            if (open < 0) {
                out.append(text, index, text.length());
                break;
            }
            int close = text.indexOf('>', open + 1);
            if (close < 0) {
                out.append(text, index, text.length());
                break;
            }
            String tag = text.substring(open + 1, close);
            if (isColorOrStyleTag(tag)) {
                out.append(text, index, open);          // 标签之前的文字照抄
                index = close + 1;                      // 跳过整个标签
            } else {
                // 不是颜色标签 ⇒ 连尖括号一起照抄（绝不误剥）
                out.append(text, index, close + 1);
                index = close + 1;
            }
        }
        return out.toString();
    }

    /**
     * 这个标签名是不是「颜色 / 样式」（大小写不敏感；{@code /} 前缀记法也算）。
     *
     * <p>★ 白名单是**固定的一小张**（16 个命名色 + reset + 常用样式）。
     * 它不做成"任意英文单词都算颜色"那种宽判据 —— 那会把卡名里的
     * {@code <某个专有名词>} 一起剥掉。
     */
    public static boolean isColorOrStyleTag(String tag) {
        if (tag == null) {
            return false;
        }
        String name = tag.trim().toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            // <color:#ff0000> / <color:red> 这类
            return name.startsWith("color:") || name.startsWith("colour:");
        }
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        return COLOR_OR_STYLE_NAMES.contains(name);
    }

    /** ★ 颜色与样式名（唯一白名单；见 {@link #isColorOrStyleTag}）。 */
    private static final java.util.Set<String> COLOR_OR_STYLE_NAMES = java.util.Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua", "red",
            "light_purple", "yellow", "white",
            "reset", "bold", "b", "italic", "i", "underlined", "u", "strikethrough", "st",
            "obfuscated", "obf");

    /**
     * ★ 召唤物名牌的**可读摘要**（日志 / 探针用；把 MiniMessage 标签去掉）。
     *
     * <p>为什么不直接用 {@link #apply} 的结果做日志：日志里出现
     * {@code <red>铁甲战士} 这种标签对排障没帮助，反而让人以为游戏里会显示尖括号。
     */
    public static String displayName(String cardDisplayName, Side side) {
        Objects.requireNonNull(side, "side 不能为 null（用 colorOf(null) 走中性色的那条路）");
        return stripColorTags(apply(cardDisplayName, side));
    }
}
