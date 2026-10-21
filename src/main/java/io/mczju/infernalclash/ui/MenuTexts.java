package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.config.DisplayConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * ★ <b>界面文案的唯一读取入口</b>（P8 的「文案接线」）—— 把 {@code display.yml} 的
 * {@code messages.*} 变成界面里那一句话。
 *
 * <h2>为什么需要它（不自己解析、不自己替换占位符）</h2>
 * 解析与占位符替换**只有一处实现**：{@link DisplayConfig}（{@code format} / {@code rawText} /
 * {@code PLACEHOLDER}）。本类<b>不</b>重新实现 MiniMessage、也不自己写一套 {@code {gold}}
 * 替换（那是本项目最反感的「第二处事实来源」）—— 它只负责
 * <b>「读哪把键、读不到怎么办」</b>这一层，然后把结果原样交回调用方。
 *
 * <h2>★★ 失败方向：一律 fail-safe 回退，绝不把坏东西印给玩家</h2>
 * <table border="1">
 *   <caption>四种失败与它的处置（每一种都有测试）</caption>
 *   <tr><th>情形</th><th>处置</th></tr>
 *   <tr><td>{@code display == null}（插件未就绪 / 配置没加载）</td>
 *       <td>回退调用方给的常量 + <b>告警一次</b></td></tr>
 *   <tr><td>键缺失 / 形状不对（多行键当单条取）</td>
 *       <td>{@link DisplayConfig#format} 抛 {@code ConfigError} ⇒ 回退 + 告警一次</td></tr>
 *   <tr><td>占位符没给全（{@code {gold}} 没值）</td>
 *       <td>同上（{@code ConfigError}）⇒ 回退 + 告警一次</td></tr>
 *   <tr><td>★ 值仍是 {@code [待填]}（措辞未拍板）</td>
 *       <td>回退 + 告警一次 —— <b>{@code [待填]} 绝不出现在界面上</b>（铁律 8 / 11）</td></tr>
 * </table>
 *
 * <p>★ <b>「回退到常量」而不是「显示空串」</b>：界面少一句话比显示一句错的更难查；
 * 而 {@code [待填]} 是<b>给开发者看的记号</b>，不是给玩家看的文案。
 *
 * <p>★ <b>告警去重</b>：同一个键只告警<b>一次</b>（用 {@link #WARNED} 记账）——
 * 界面可能被反复打开（翻页 / 重开），每次都刷一条会把真正的告警淹掉
 * （{@code AGENTS.md} 里「{@code saveResource} 刷 54 条 WARN」就是这么坏的）。
 * 某个键恢复正常后从账上去掉，将来再坏时会<b>重新</b>告警一次。
 *
 * <p>★ <b>{@code [待填]} 用「包含」而不是「恰好等于」判</b>：措辞未定的条目完全可能被写成
 * {@code "[待填] 卡牌商店"} 这种带说明的形式，恰等判据会把它漏到玩家脸上。
 *
 * <h2>能离线测</h2>
 * 本类只依赖 {@link DisplayConfig}（纯数据对象，见 {@code ShippedDisplayTest}）
 * 与一个 {@code Consumer} 告警出口 ⇒ 普通 JUnit 里就能逐条断言四种失败与占位符替换
 * （{@code MenuTextsTest}）。
 */
public final class MenuTexts {

    /** 已告警过的键（去重账；并发安全，因为榜 / 界面可能不在同一线程刷新）。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private MenuTexts() {
    }

    /**
     * 取一条**没有占位符**的界面文案。
     *
     * @param display 已校验的 {@code display.yml}；{@code null} = 拿不到
     * @param key     {@code messages} 段的键（如 {@code shop.title}）
     * @param fallback 取不到时的回退文案（★ 调用方**代码里的中文常量**）—— 不得为 {@code null}
     * @param warner  告警出口（每条键最多调一次）；{@code null} = 只回退不告警
     * @return 配置里的值（占位符已替换），或 {@code fallback}
     */
    public static String resolve(DisplayConfig display, String key, String fallback,
                                 Consumer<String> warner) {
        return resolve(display, key, fallback, Map.of(), warner);
    }

    /**
     * 取一条界面文案并替换占位符（{@code {gold}} / {@code {count}} …）。
     *
     * @param placeholders 占位符名（**不含花括号**）→ 替换值；本类<b>不做</b>任何占位符推导
     *                     —— 少给一个由 {@link DisplayConfig#format} 报错，然后在这里回退
     * @return 配置里的值（占位符已替换），或 {@code fallback}
     */
    public static String resolve(DisplayConfig display, String key, String fallback,
                                 Map<String, String> placeholders, Consumer<String> warner) {
        if (display == null) {
            warnOnce(key, warner, "display.yml 还没加载（插件未就绪 / 配置加载失败）");
            return fallback;
        }
        try {
            // ★ 解析 + 占位符替换只有这一条路（DisplayConfig.format）：
            //   缺键 / 形状不对 / 占位符没给全，都在这里变成 ConfigError
            String text = display.format(key, placeholders);
            if (text.contains(DisplayConfig.PLACEHOLDER)) {
                warnOnce(key, warner, "值仍是占位 " + DisplayConfig.PLACEHOLDER + "（措辞未拍板）");
                return fallback;
            }
            WARNED.remove(key);   // 这条键恢复正常 ⇒ 将来再坏时重新告警一次
            return text;
        } catch (Throwable t) {
            warnOnce(key, warner, "取文案失败：" + t);
            return fallback;
        }
    }

    /**
     * ★ <b>取一条多行文案（lore）</b>—— 与 {@link #resolve} 同一套失败方向。
     *
     * <h2>为什么必须有它（2026-10-16 第八轮新增）</h2>
     * 物品的 lore 是<b>列表</b>（{@code display.yml} 里写成 YAML 列表，例
     * {@code shop.buy-pack-lore}）。若调用方自己去 {@code display.lore(...)} 再自己
     * try/catch，那就是「同一个动作在 N 处各写一遍」——本项目最反感的形状。
     * ⇒ 与 {@link #resolve} 并排放在这里，回退到调用方给的常量列表。
     *
     * <p>★ 失败方向完全一致：拿不到 / 键不是列表 / 形状不对 ⇒ 回退 {@code fallback}
     * ＋ 每条键只告警一次，**绝不抛**。
     */
    public static List<String> resolveLore(DisplayConfig display, String key, List<String> fallback,
                                           Consumer<String> warner) {
        if (display == null) {
            warnOnce(key, warner, "display.yml 还没加载（插件未就绪 / 配置加载失败）");
            return fallback;
        }
        try {
            List<String> lines = display.lore(key);
            if (lines == null || lines.isEmpty()) {
                warnOnce(key, warner, "键是空列表");
                return fallback;
            }
            for (String line : lines) {
                if (line != null && line.contains(DisplayConfig.PLACEHOLDER)) {
                    warnOnce(key, warner, "值仍是占位 " + DisplayConfig.PLACEHOLDER + "（措辞未拍板）");
                    return fallback;
                }
            }
            WARNED.remove(key);
            return lines;
        } catch (Throwable t) {
            warnOnce(key, warner, "取 lore 失败：" + t);
            return fallback;
        }
    }

    /** 该键当前是不是「已经告过警」（对账 / 测试用；生产路径不需要读它）。 */
    public static boolean hasWarned(String key) {
        return WARNED.contains(key);
    }

    /**
     * ★★ <b>这条文案是不是被玩家留空了</b>（= 不要显示这一行；2026-10-19）。
     *
     * <p>用户会把 {@code display.yml} 里不想要的那一行改成 {@code ""}（现场已经这么干过一次：
     * {@code command.bot-off-hint}）。那时**不能**发一个空 {@link Component}
     * —— 聊天栏会多出一条空行，看起来像"点了没反应"⇒ 发送侧先问这里，**直接跳过**。
     *
     * <p>★ 失败方向：{@code display == null}（插件未就绪）/ 键取不到 / 是多行键 ⇒
     * 一律 {@code false}（= 照常发送，走各自既有的回退常量），绝不因为这条判据少发一句话。
     */
    public static boolean isSilent(DisplayConfig display, String key) {
        if (display == null || key == null) {
            return false;
        }
        try {
            return display.isBlank(key);
        } catch (Throwable t) {
            return false;   // 键不存在 / 是多行键 ⇒ 不是"留空"，交给调用方的回退路径
        }
    }

    /**
     * ★★ <b>把一条文案变成可直接发给玩家的 {@link Component}</b>（MiniMessage 解析 ＋ 无色时补缺省色）。
     *
     * <h2>为什么必须有这个方法（而不是让每个调用点自己解析）</h2>
     * {@link #resolve} 只给 String。如果**每一个发给玩家的地方**都自己写一遍
     * 「MiniMessage 解析 ＋ `colorIfAbsent` ＋ 解析失败兜底」，那就是同一个动作在 N 处各写一遍 ——
     * 本项目最反感的那种形状：**第一处写对、第二处忘了 try/catch**，于是「配置里写坏一个标签」
     * 就从「少一句话」变成「玩家眼前弹异常」。⇒ 这里把它收成**一处**。
     *
     * <p>★ <b>失败方向与 {@link #resolve} 完全一致</b>：取不到 / 值是占位 / 解析失败
     * ⇒ **回退到调用方给的 {@code fallback}**（代码里的中文常量），并且**绝不抛**。
     *
     * @param colorIfAbsent 文本**自己没带颜色**时补的颜色（配置里写 `<red>…` 时以配置为准，不会被覆盖）
     */
    public static Component component(DisplayConfig display, String key, String fallback,
                                      Map<String, String> placeholders, TextColor colorIfAbsent,
                                      Consumer<String> warner) {
        String text = resolve(display, key, fallback, placeholders, warner);
        try {
            return MiniMessage.miniMessage().deserialize(text).colorIfAbsent(colorIfAbsent);
        } catch (Throwable t) {
            // ★ 解析失败也不能把异常抛到玩家脸上：回退常量（连颜色一起给上）
            warnOnce(key, warner, "解析 MiniMessage 失败：" + t);
            return Component.text(fallback).colorIfAbsent(colorIfAbsent);
        }
    }

    /** {@link #component} 的无占位符版本。 */
    public static Component component(DisplayConfig display, String key, String fallback,
                                      TextColor colorIfAbsent, Consumer<String> warner) {
        return component(display, key, fallback, Map.of(), colorIfAbsent, warner);
    }

    /**
     * 清空告警去重账。
     *
     * <p>★ 只给测试用（每个用例的告警计数必须从零开始）；生产路径**永远不用**调它。
     */
    static void clearWarnings() {
        WARNED.clear();
    }

    /** 每条键只告警一次（去重失败或告警出口本身失败都不许再造出第二个失败）。 */
    private static void warnOnce(String key, Consumer<String> warner, String reason) {
        if (!WARNED.add(key)) {
            return;
        }
        if (warner == null) {
            return;
        }
        try {
            warner.accept("文案键 " + key + " 取不到，回退到代码里的常量（" + reason + "）");
        } catch (Throwable ignored) {
            // 告警失败不能再造出第二个失败
        }
    }
}
