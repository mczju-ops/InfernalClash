package io.mczju.infernalclash.leaderboard;

/**
 * ★ <b>真人战绩统计的纯核心</b>：{@code 「一个已有的统计 + 一局结果 ⇒ 一个新的统计」}。
 *
 * <h2>为什么是纯的</h2>
 * 这是本阶段<b>规则最多</b>的一段（胜场 / 总场次 / 连胜 / 最高连胜 四个量的联动），
 * 也最容易写错（「最高连胜被顺手清零」这类 bug 在实机上要打几十局才看得出来）。
 * ⇒ 按 {@code AGENTS.md} §3「新算法一律先做成纯函数」：本类<b>只依赖 JDK</b>，
 * 不碰 Bukkit / MGC / 档案，因此<b>全部可离线单测</b>（{@code LeaderboardStatsTest}）。
 *
 * <h2>不可变</h2>
 * 所有字段 {@code final}，{@link #record} <b>返回新实例</b>（不改自己）。
 * 与 {@code profile/PlayerProgress} 同一口径：变更即新对象 ⇒ 测试能断言「原对象没变」，
 * 也便于「先算、确认无误、再落盘」。
 *
 * <h2>口径与出处（★ 一条都不许自行扩展）</h2>
 * <table border="1">
 *   <caption>权威：{@code docs/08-局外成长与商店.md} §7 + §8.3</caption>
 *   <tr><th>情形</th><th>结果</th><th>出处</th></tr>
 *   <tr><td><b>人机局</b>（无论胜 / 负 / 平）</td>
 *       <td><b>拒绝更新</b>（四个字段一个都不动）</td>
 *       <td>§7「只计真人对局胜场（<b>人机局不计</b>）」</td></tr>
 *   <tr><td>真人局 · 胜</td>
 *       <td>{@code realWins+1}、{@code realMatches+1}、{@code realStreak+1}、
 *           {@code realBestStreak = max(旧, 新连胜)}</td>
 *       <td>§8.3「赢 +1」＋「{@code realBestStreak} 只涨不落」</td></tr>
 *   <tr><td>真人局 · 负</td>
 *       <td>{@code realMatches+1}、{@code realStreak = 0}；胜场与最高连胜<b>不变</b></td>
 *       <td>§8.3「输清零」</td></tr>
 *   <tr><td>真人局 · <b>平局</b>（W5 / W6）</td>
 *       <td>★ <b>拒绝更新</b>（{@code applied == false} + 原因）</td>
 *       <td>★ <b>缺，待裁定</b>：§8.3 只写了「赢 +1、输清零」，<b>没有平局这一档</b>；
 *           而平局是<b>真实存在</b>的（{@code docs/02} §4 的 W5/W6 已由 Q2/Q53 裁定）
 *           ⇒ 见 {@code docs/04} §16.13.6 的 <b>G4</b>。<b>不猜</b>，因此不写任何分支</td></tr>
 * </table>
 *
 * <h2>★ 失败方向：拒绝而不是抛</h2>
 * {@code record(...)} <b>不抛异常</b> —— 它可能被对局收尾路径调用，而「平局没裁定」
 * 这种事<b>不该打断一局对局的收尾</b>。⇒ 返回 {@link Update#applied()} 为 {@code false}
 * 的对象，由调用方（{@link LeaderboardWiring}）打一条告警。
 * 只有<b>编程错误</b>（{@code null} 参数）才 fail-fast 抛 {@link IllegalArgumentException}。
 */
public final class LeaderboardStats {

    // ==================================================================================
    // 一、输入的两个维度
    // ==================================================================================

    /** 这局是跟谁打的（{@code docs/08} §7：人机局不计统计）。 */
    public enum MatchKind {

        /** 真人对真人（唯一计入统计的一种）。 */
        VERSUS_PLAYER,

        /** 训练人机（{@code docs/02} §9）。 */
        VERSUS_BOT
    }

    /** 这一局的结果（{@code docs/02} §4 的胜负矩阵：胜 / 负 / 平）。 */
    public enum Outcome {

        /** 该玩家获胜。 */
        WIN,

        /** 该玩家失败。 */
        LOSS,

        /** 平局（W5：加时耗尽塔数仍相等；W6：双方信标同时死亡）。 */
        DRAW
    }

    // ==================================================================================
    // 二、空统计与不可变字段
    // ==================================================================================

    /** 全新玩家（四个字段全 0）。 */
    public static final LeaderboardStats EMPTY = new LeaderboardStats(0, 0, 0, 0);

    private final int realWins;
    private final int realMatches;
    private final int realStreak;
    private final int realBestStreak;

    /**
     * @param realWins       真人局胜场（{@code docs/08} §7 的排名依据）
     * @param realMatches    真人局总场次
     * @param realStreak     当前连胜
     * @param realBestStreak 历史最高连胜
     * @throws IllegalArgumentException 任何一个为负
     *         （★ 负值只可能来自坏档案 ⇒ 由 {@code ArchiveFieldAccess} 那一侧报出来，
     *         本类不做「顺手修成 0」—— 那是第二处判据）
     */
    public LeaderboardStats(int realWins, int realMatches, int realStreak, int realBestStreak) {
        requireNonNegative(realWins, LeaderboardFields.REAL_WINS);
        requireNonNegative(realMatches, LeaderboardFields.REAL_MATCHES);
        requireNonNegative(realStreak, LeaderboardFields.REAL_STREAK);
        requireNonNegative(realBestStreak, LeaderboardFields.REAL_BEST_STREAK);
        this.realWins = realWins;
        this.realMatches = realMatches;
        this.realStreak = realStreak;
        this.realBestStreak = realBestStreak;
    }

    // ==================================================================================
    // 三、核心：吃进一局结果，得到新统计
    // ==================================================================================

    /**
     * 语义化工厂（等价于构造器；命名参数顺序与 {@link LeaderboardFields#ARCHIVE_FIELDS} 一致）。
     *
     * @param realWins       真人局胜场
     * @param realMatches    真人局总场次
     * @param realStreak     当前连胜
     * @param realBestStreak 历史最高连胜
     */
    public static LeaderboardStats of(int realWins, int realMatches, int realStreak, int realBestStreak) {
        return new LeaderboardStats(realWins, realMatches, realStreak, realBestStreak);
    }

    /**
     * ★ <b>纯核心</b>：{@code this}（已有统计）× 一局结果 ⇒ 新统计（{@code this} 不变）。
     *
     * @param kind    跟谁打的
     * @param outcome 结果
     * @return {@link Update}；{@link Update#applied()} 为 {@code false} 时
     *         {@link Update#next()} <b>就是 {@code this}</b>（一个字段都没动），
     *         且 {@link Update#reason()} 写清「为什么没更新 + 出处」
     * @throws IllegalArgumentException 任一参数为 {@code null}（调用方的编程错误）
     */
    public Update record(MatchKind kind, Outcome outcome) {
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为 null（要么 VERSUS_PLAYER，要么 VERSUS_BOT）");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为 null（WIN / LOSS / DRAW）");
        }

        // ★ 第一道闸：人机局不计。★ 放在结果之前 —— 「人机局平局」也该走这一条。
        // ★★ 2026-10-17 第十三轮：**用户当场裁定（G3 结案）**，逐字：
        //   「人机局**不计排行榜**，只给金币和触发第一次游玩的给金币」
        //   ⇒ 与旧口径一致（四个 real* 字段一个都不动），只是从"待确认"升格为"用户裁定值"。
        if (kind == MatchKind.VERSUS_BOT) {
            return new Update(false, this,
                    "人机局不计入排行榜（用户 2026-10-17 裁定「人机局不计排行榜，只给金币和触发"
                            + "第一次游玩的给金币」；docs/24 §10 第 16 条），四个 real* 字段一个都不动"
                            + "（金币奖励不在这里发 —— 它由 profile/奖励那条路走）");
        }

        return switch (outcome) {
            case WIN -> {
                int nextStreak = realStreak + 1;
                LeaderboardStats next = new LeaderboardStats(
                        realWins + 1, realMatches + 1, nextStreak, Math.max(realBestStreak, nextStreak));
                yield new Update(true, next,
                        "真人局胜：胜场 +1、总场次 +1、连胜 " + realStreak + "→" + nextStreak
                                + "、最高连胜只涨不落 " + realBestStreak + "→" + next.realBestStreak());
            }
            case LOSS -> new Update(true,
                    new LeaderboardStats(realWins, realMatches + 1, 0, realBestStreak),
                    "真人局负：总场次 +1、连胜清零（" + realStreak + "→0）；胜场与最高连胜不变");
            // ★★ 2026-10-17 第十三轮：**用户当场裁定（G4 结案）**，逐字：
            //   「记总场次，不计胜场，不断连败」
            //   ⇒ 平局 = 总场次 +1、胜场不变、**连胜保持不变**（既不加也不清）。
            //   ★ 旧实现是「拒绝更新（applied=false）」—— 那是 G4 未裁定时的**保守默认**；
            //     现在有裁定了，就按裁定落数据（不再是"待裁定"）。
            case DRAW -> new Update(true,
                    new LeaderboardStats(realWins, realMatches + 1, realStreak, realBestStreak),
                    "平局：总场次 +1、胜场不变、连胜不断（" + realStreak + "→" + realStreak
                            + "）—— 用户 2026-10-17 裁定「记总场次，不计胜场，不断连败」"
                            + "（docs/24 §10 第 17 条；旧口径 = 拒绝更新，已作废）");
        };
    }

    // ==================================================================================
    // 四、读取
    // ==================================================================================

    /** 真人局胜场（{@code docs/08} §7 的排名依据）。 */
    public int realWins() {
        return realWins;
    }

    /** 真人局总场次。 */
    public int realMatches() {
        return realMatches;
    }

    /** 当前连胜（仅供计算，不上榜）。 */
    public int realStreak() {
        return realStreak;
    }

    /** 历史最高连胜（只涨不落）。 */
    public int realBestStreak() {
        return realBestStreak;
    }

    /**
     * 按字段名取值（★ 名字来自 {@link LeaderboardFields}，供 {@link ArchiveFieldAccess} 复用）。
     *
     * @throws IllegalArgumentException 不是四个 {@code real*} 字段之一
     */
    public int valueOf(String fieldName) {
        if (LeaderboardFields.REAL_WINS.equals(fieldName)) {
            return realWins;
        }
        if (LeaderboardFields.REAL_MATCHES.equals(fieldName)) {
            return realMatches;
        }
        if (LeaderboardFields.REAL_STREAK.equals(fieldName)) {
            return realStreak;
        }
        if (LeaderboardFields.REAL_BEST_STREAK.equals(fieldName)) {
            return realBestStreak;
        }
        throw new IllegalArgumentException("未知的战绩字段 " + fieldName + "；本类只认 "
                + LeaderboardFields.ARCHIVE_FIELDS + "（少一个字段就是「榜恒空」，所以这里 fail-fast）");
    }

    // ==================================================================================
    // 五、一局的结果
    // ==================================================================================

    /**
     * ★ {@link #record} 的返回值：<b>是否真的改了</b>、改成了什么、以及<b>为什么没改</b>。
     *
     * @param applied 是否真的产生了新统计（{@code false} ⇒ {@code next} 与来源<b>同一个对象</b>）
     * @param next    应用后的统计（{@code applied == false} 时 = 来源本身）
     * @param reason  给日志用的一句话（含出处；★ 失败时**必须**非空）
     */
    public record Update(boolean applied, LeaderboardStats next, String reason) {
    }

    // ==================================================================================
    // 六、Object
    // ==================================================================================

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LeaderboardStats stats)) {
            return false;
        }
        return realWins == stats.realWins
                && realMatches == stats.realMatches
                && realStreak == stats.realStreak
                && realBestStreak == stats.realBestStreak;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(realWins, realMatches, realStreak, realBestStreak);
    }

    @Override
    public String toString() {
        return "LeaderboardStats[胜场=" + realWins + ", 总场次=" + realMatches
                + ", 连胜=" + realStreak + ", 最高连胜=" + realBestStreak + "]";
    }

    // ==================================================================================
    // 七、内部
    // ==================================================================================

    private static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException("战绩字段 " + fieldName + " 不能为负，实际是 " + value
                    + "（负值只可能来自坏档案；修复判据只有一处，见 ProgressMapper 的口径）");
        }
    }
}
