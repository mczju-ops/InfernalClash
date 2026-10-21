package io.mczju.infernalclash.leaderboard;

import com.github.mczjuops.mczjugamecore.score.leaderboard.AbstractLeaderboard;
import com.github.mczjuops.mczjugamecore.score.leaderboard.LeaderboardEntry;
import com.github.mczjuops.mczjugamecore.score.leaderboard.SortOrder;
import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.config.DisplayConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ★ <b>「炒鸡战争排行榜」</b>（{@code docs/02} §10.3 的 2026-10-15-J 裁定的显示名）。
 *
 * <h2>为什么继承 {@link AbstractLeaderboard} 而不是 {@code PlayerDataLeaderboard}</h2>
 * {@code docs/08} §7 已经定了这条路，理由是<b>结构性</b>的（{@code javap} 实测）：
 * {@code PlayerDataLeaderboard} 每行只反射读<b>一个</b>字段、一行一个数，
 * 而 {@code docs/08} §7【Q33】要求一行显示<b>三个</b>数字（胜场 / 总场次 / 最高连胜）。
 * ⇒ 自己实现 {@code fetchEntries} + {@code renderLine}，并用一份
 * 「显示名 → 统计」的<b>快照</b>把另外两个数字捞回来
 * （IG 的 {@code InfernalGuessRankedLeaderboard} 就是这么做的，{@code docs/08} §7 明写【复用】它）。
 *
 * <h2>★ 三条"不许动"（动了就是 bug，不是风格）</h2>
 * <ol>
 *   <li><b>类必须 {@code public} 且有无参构造</b> —— MGC 用
 *       {@code getDeclaredConstructor().newInstance()} 实例化（{@code javap} 实测），
 *       跨包非 public 会直接失败；</li>
 *   <li><b>字段名全部来自 {@link LeaderboardFields}</b> —— 关于「写错一个字 ⇒ 榜恒空」
 *       的机制见该类的类注释，<b>不许</b>在本类里出现任何裸的字段名字符串；</li>
 *   <li><b>{@link LeaderboardEntry} 的 {@code value} 必须是 {@link LeaderboardFields#RANK_FIELD}</b>
 *       —— MGC 拿它排序（{@link #getSortOrder()} = {@code DESCENDING}），
 *       填成总场次会让榜按"打了多少局"排。</li>
 * </ol>
 *
 * <h2>★ 刻意<b>不覆盖</b>基类的两个方法（= 不自造数值）</h2>
 * <table border="1">
 *   <caption>{@code javap} 实测的 {@code AbstractLeaderboard} 默认值</caption>
 *   <tr><th>方法</th><th>基类默认</th><th>为什么不覆盖</th></tr>
 *   <tr><td>{@code getDisplayCount()}</td><td>{@code 12}（字节码 {@code bipush 12}）</td>
 *       <td>{@code docs/08} §7 <b>没给显示行数</b>（缺口 G6）⇒ 用框架默认，<b>不自造一个数</b></td></tr>
 *   <tr><td>{@code renderEmpty()}</td><td>{@code <gray>暂无数据</gray>}</td>
 *       <td>{@code docs/08} §7 没给空榜文案（缺口 G7 同类）⇒ 同样用框架默认</td></tr>
 * </table>
 *
 * <h2>★ 失败方向</h2>
 * {@link #fetchEntries()} 走 {@link LeaderboardWiring#snapshotOfArchives}，
 * 它<b>已经</b>兜住所有异常 ⇒ 本类<b>拿不到数据时得到空表</b>（榜显示基类的空榜文案），
 * 绝不把异常抛回 MGC 的刷新路径。
 */
public final class InfernalClashLeaderboard extends AbstractLeaderboard {

    /** ★ 快照：榜上显示名 → 统计（{@link #renderLine} 靠它取另外两个数字）。 */
    private Map<String, LeaderboardStats> snapshot = Map.of();

    /** ★ 必须有无参构造（MGC 反射实例化；{@code javap} 实测，见类注释）。 */
    public InfernalClashLeaderboard() {
    }

    // ==================================================================================
    // 一、榜头
    // ==================================================================================

    /**
     * 榜名：★ 取 {@code display.yml} 的 {@link LeaderboardFields#TITLE_KEY}
     * （2026-10-15-J 裁定的「炒鸡战争排行榜」）——<b>不在代码里硬编码面向玩家的中文</b>（R76）。
     *
     * <p>取不到（插件实例不可用 / 离线）⇒ 返回 {@link DisplayConfig#PLACEHOLDER}，
     * ★ <b>不</b>回退到某个我编的中文名。
     */
    @Override
    public String getTitle() {
        try {
            InfernalClashPlugin plugin = InfernalClashPlugin.getInstance();
            if (plugin != null && plugin.display() != null) {
                return plugin.display().text(LeaderboardFields.TITLE_KEY);
            }
        } catch (Throwable ignored) {
            // 落到下面的占位（榜名取不到不该让刷新失败）
        }
        return DisplayConfig.PLACEHOLDER;
    }

    /**
     * 副标题（★ 2026-10-17 第十三轮：用户裁定「**标题、副标题进**（`display.yml`），其余不进」）。
     *
     * <p>正常路径读 {@code display.yml} 的 {@code messages.leaderboard.subtitle}
     * （{@link LeaderboardFields#SUBTITLE_KEY}）；读不到 ⇒ 回退
     * {@link LeaderboardFields#SUBTITLE}（复用 IG 的原文）。
     * ★ 与 {@link #getTitle()} 同一形状：**取不到也不许让刷新失败**（榜是只读展示）。
     */
    @Override
    public String getSubtitle() {
        try {
            InfernalClashPlugin plugin = InfernalClashPlugin.getInstance();
            if (plugin != null && plugin.display() != null) {
                return plugin.display().text(LeaderboardFields.SUBTITLE_KEY);
            }
        } catch (Throwable ignored) {
            // 落到下面的回退常量（副标题取不到不该让刷新失败）
        }
        return LeaderboardFields.SUBTITLE;
    }

    /** ★ {@code docs/08} §7：真人胜场<b>降序</b>（与基类默认一致，仍显式写出以标明出处）。 */
    @Override
    public SortOrder getSortOrder() {
        return SortOrder.DESCENDING;
    }

    // ==================================================================================
    // 二、取数
    // ==================================================================================

    /**
     * ★ 取整张榜：读全部档案 → 留快照 → 过滤零胜 → 按胜场降序 → 交给 MGC 渲染。
     *
     * <p>{@link LeaderboardEntry#value()} 填的是 {@link LeaderboardFields#RANK_FIELD}
     * （= {@code realWins}），因为 MGC 用它排序。
     */
    @Override
    public List<LeaderboardEntry> fetchEntries() {
        Map<String, LeaderboardStats> fresh = LeaderboardWiring.snapshotOfArchives(logger());
        this.snapshot = fresh;

        List<LeaderboardRanking.Entry> candidates = new ArrayList<>(fresh.size());
        for (Map.Entry<String, LeaderboardStats> entry : fresh.entrySet()) {
            candidates.add(new LeaderboardRanking.Entry(entry.getKey(), entry.getValue()));
        }

        List<LeaderboardRanking.Row> rows = LeaderboardRanking.rank(candidates);
        List<LeaderboardEntry> entries = new ArrayList<>(rows.size());
        for (LeaderboardRanking.Row row : rows) {
            entries.add(new LeaderboardEntry(row.playerName(), row.stats().valueOf(LeaderboardFields.RANK_FIELD)));
        }
        return entries;
    }

    // ==================================================================================
    // 三、渲染
    // ==================================================================================

    /**
     * 渲染一行：★ <b>三个</b>数字（{@code docs/08} §7【Q33】：胜场 / 总场次 / 最高连胜）。
     *
     * <p>{@code value} 是 MGC 从 {@link LeaderboardEntry} 里带过来的胜场；
     * 另外两个数字从 {@link #snapshot} 按<b>显示名</b>取回（拿不到就按 0 显示 ——
     * 与 IG 的处理一致：宁可显示 0，也不要因为一个玩家的快照缺失而让整次刷新失败）。
     */
    @Override
    public String renderLine(int rank, String playerName, double value) {
        LeaderboardStats stats = snapshot.get(playerName);
        int wins = stats == null ? (int) value : stats.realWins();
        int matches = stats == null ? 0 : stats.realMatches();
        int bestStreak = stats == null ? 0 : stats.realBestStreak();
        return formatLine(rank, playerName, wins, matches, bestStreak);
    }

    /**
     * ★ <b>纯</b>行格式化（静态 + 不碰任何实例状态 ⇒ 可离线单测，见 {@code InfernalClashLeaderboardTest}）。
     *
     * <p>参数顺序 = {@code rank, name, realWins, realMatches, realBestStreak}
     * —— 后三个就是 {@link LeaderboardFields#LINE_FIELDS} 的顺序。
     * 格式串见 {@link LeaderboardFields#LINE_FORMAT}（★ 复用 IG 的写法，出处在那里）。
     */
    static String formatLine(int rank, String playerName, int wins, int matches, int bestStreak) {
        return LeaderboardFields.LINE_FORMAT.formatted(rank, playerName, wins, matches, bestStreak);
    }

    // ==================================================================================
    // 四、内部
    // ==================================================================================

    /**
     * 本插件日志器；插件实例不可用时退回 JDK 日志器（{@code java.util.logging}，<b>不碰 Bukkit</b>）。
     *
     * <p>为什么不直接用 {@code Bukkit.getLogger()}：那在离线单测里会 NPE
     * （{@code AGENTS.md} §4.1 那条「{@code Bukkit.server == null}」的同类坑）。
     */
    private static Logger logger() {
        try {
            InfernalClashPlugin plugin = InfernalClashPlugin.getInstance();
            if (plugin != null && plugin.getLogger() != null) {
                return plugin.getLogger();
            }
        } catch (Throwable ignored) {
            // 落到 JDK 日志器
        }
        return Logger.getLogger("InfernalClash");
    }
}
