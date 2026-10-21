package io.mczju.infernalclash.leaderboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * ★ <b>排行榜的纯算法</b>：过滤 → 排序 → 编号。不碰 Bukkit / MGC，<b>离线可单测</b>。
 *
 * <h2>口径与出处</h2>
 * <table border="1">
 *   <caption>权威：{@code docs/08-局外成长与商店.md} §7 + §8.3</caption>
 *   <tr><th>规则</th><th>做法</th><th>出处</th></tr>
 *   <tr><td>谁上榜</td><td>★ <b>只收 {@code realWins > 0}</b>（零胜不进榜，否则榜尾全是 0）</td>
 *       <td>§7【Q33】「过滤：只收真人胜场 &gt; 0」</td></tr>
 *   <tr><td>怎么排</td><td>{@code realWins} <b>降序</b></td><td>§7「真人胜场（{@code realWins}）降序」</td></tr>
 *   <tr><td>名次</td><td>1 起、连续编号（第 1 行是 {@code rank = 1}）；★ 并列时<b>不共用名次</b></td>
 *       <td>§7 没规定并列名次 ⇒ 见下方 G5（**不猜**，只保证确定性）</td></tr>
 *   <tr><td><b>并列</b>（胜场相等）</td><td>★ <b>按玩家名升序</b></td>
 *       <td>★ <b>缺，待裁定</b>（{@code docs/04} §16.13.6 的 <b>G5</b>）——
 *           这里给的是<b>确定性</b>（同一份数据永远排出同一个顺序），<b>不是</b>排名口径；
 *           口径一出，只改本类<b>一处</b> {@link #ORDER}</td></tr>
 *   <tr><td>名字取不到</td><td>不上榜（榜上没法显示）</td>
 *       <td>参照 IG 的做法（{@code LeaderboardSupport.snapshot} 拿不到名字就 {@code continue}）</td></tr>
 * </table>
 *
 * ★ <b>为什么并列也必须有确定顺序</b>：MGC 每 12000 tick 自动刷一次榜（{@code docs/08} §7），
 * 若同分两人的顺序在两次刷新之间随机跳动，管理员会以为「榜坏了」。
 * 所以「按名字升序」是<b>工程上的确定性</b>，而不是我在替用户定排名规则 —— 两者在本文档里分开写。
 */
public final class LeaderboardRanking {

    private LeaderboardRanking() {
    }

    /**
     * ★ <b>同分时的次序</b>（= 胜场降序，其次玩家名升序）。
     *
     * <p>★★ 2026-10-17 第十三轮：**用户当场裁定（G5 结案）** ——
     * 我反问「并列（胜场相等）怎么排？」，用户答「**按玩家名升序**」
     * ⇒ 与旧口径**完全一致**（旧值只是"为了确定性"的保守默认，现在升格为裁定值）。
     * <p>⚠️ 只此一处。要改口径（例：先看最高连胜）只改这一个常量。
     */
    private static final Comparator<Entry> ORDER = Comparator
            .comparingInt((Entry entry) -> entry.stats().realWins())
            .reversed()
            .thenComparing(Entry::playerName);

    /**
     * 这条统计够不够上榜。
     *
     * @param stats 统计；{@code null} ⇒ 不上榜（读不到档案的玩家）
     */
    public static boolean shouldAppear(LeaderboardStats stats) {
        return stats != null && stats.realWins() > 0;
    }

    /**
     * ★ 把「名字 → 统计」排成榜。
     *
     * <p>步骤：丢掉 {@code null} / 空名字 → 用 {@link #shouldAppear} 过滤 → 按 {@link #ORDER} 排序
     * → 从 1 开始编号。
     *
     * @param entries 候选（可为 {@code null} ⇒ 空榜）；顺序无关紧要（本方法会重排）
     * @return 编号后的行（{@link Row#rank()} 从 1 开始）；★ 空榜返回空列表，<b>不是 null</b>
     */
    public static List<Row> rank(Collection<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<Entry> candidates = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry == null || entry.playerName() == null || entry.playerName().isBlank()) {
                continue;   // 名字取不到 ⇒ 榜上显示不出来
            }
            if (!shouldAppear(entry.stats())) {
                continue;   // ★ docs/08 §7：零胜不进榜
            }
            candidates.add(entry);
        }
        candidates.sort(ORDER);

        List<Row> rows = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Entry entry = candidates.get(i);
            rows.add(new Row(i + 1, entry.playerName(), entry.stats()));
        }
        return List.copyOf(rows);
    }

    /**
     * 一份候选（榜上要显示的那个名字 + 它的统计）。
     *
     * @param playerName 榜上显示的名字（★ MGC 拿这个字符串去 {@code Map.get} 找回另外两个数字，
     *                   所以它必须与 {@code LeaderboardEntry.playerName()} 是**同一个串**）
     * @param stats      统计
     */
    public record Entry(String playerName, LeaderboardStats stats) {
    }

    /**
     * 榜上的一行。
     *
     * @param rank       名次（从 1 开始）
     * @param playerName 榜上显示的名字
     * @param stats      统计（★ 三个要显示的数字都在这里）
     */
    public record Row(int rank, String playerName, LeaderboardStats stats) {
    }
}
