package io.mczju.infernalclash.leaderboard;

import java.util.List;

/**
 * ★★ 排行榜「<b>字段名 + 顺序</b>」的<b>唯一来源</b> —— 本项目「榜恒空」的<b>唯一防线</b>。
 *
 * <h2>★ 为什么这个类必须存在（{@code javap} 实测的机制，不是风格问题）</h2>
 * MGC 的排行榜是<b>反射读字段</b>的。对 MGC 1.1.2 反汇编
 * {@code score.leaderboard.PlayerDataLeaderboard} 得到的<b>权威读法</b>：
 *
 * <pre>
 *   private static double getDoubleValue(Object o, String fieldName) {
 *       if (o == null || fieldName == null || fieldName.isEmpty()) return 0.0;   // dconst_0
 *       try {
 *           return ((Number) o.getClass().getField(fieldName).get(o)).doubleValue();
 *       } catch (NoSuchFieldException | IllegalAccessException | SecurityException e) {
 *           return 0.0;                       // ★★ 三种异常全部被吞掉
 *       }
 *   }
 * </pre>
 *
 * 三条结论（每一条都会让榜<b>静默</b>变空）：
 * <ol>
 *   <li>{@code Class.getField(String)} <b>只认 {@code public} 字段</b>（含继承来的）
 *       ⇒ 字段写成 {@code private} 或名字差一个字 ⇒ {@code NoSuchFieldException}；</li>
 *   <li>该异常被<b>吞掉并返回 {@code 0.0}</b> ⇒ <b>不抛、不记日志</b>，
 *       现场表现就是「<b>榜恒空</b>」而没有任何线索（{@code AGENTS.md} §4.2 记着这条）；</li>
 *   <li>值必须 {@code instanceof Number}，否则也是 {@code 0.0}。</li>
 * </ol>
 *
 * ⇒ 所以「字段名」在这里**不是一个字符串字面量，而是一份契约**：
 * 它必须①只有一处、②被测试逐字钉死、③被反射断言 {@code public}（见 {@code LeaderboardFieldsTest}）。
 *
 * <h2>字段清单与出处（★ 不许自行扩展，也不许"顺手"改名）</h2>
 * <table border="1">
 *   <caption>权威：{@code docs/08-局外成长与商店.md} §7【Q33】+ §8.3 字段草案</caption>
 *   <tr><th>字段</th><th>用途</th></tr>
 *   <tr><td>{@link #REAL_WINS}</td><td><b>排名依据</b>（降序）＋ 一行里的第 1 个数字</td></tr>
 *   <tr><td>{@link #REAL_MATCHES}</td><td>一行里的第 2 个数字（总场次）</td></tr>
 *   <tr><td>{@link #REAL_STREAK}</td><td><b>仅供计算</b>，不上榜</td></tr>
 *   <tr><td>{@link #REAL_BEST_STREAK}</td><td>一行里的第 3 个数字（历史最高连胜，只涨不落）</td></tr>
 * </table>
 *
 * <p>★ 四个字段全部带 {@code real} 前缀 + {@code docs/08} §7「只计真人对局胜场（人机局不计）」
 * ⇒ <b>人机局四个字段一个都不动</b>（实现见 {@link LeaderboardStats#record}）。
 *
 * <h2>★ 本类的依赖</h2>
 * 只依赖 JDK（{@link java.util.List}）⇒ 离线可单测，<b>不碰 Bukkit / MGC</b>。
 */
public final class LeaderboardFields {

    private LeaderboardFields() {
    }

    // ==================================================================================
    // 一、档案字段名（★ 一个字都不许差 —— 差一个字就是「榜恒空」）
    // ==================================================================================

    /** 真人局胜场（★ {@code docs/08} §7【Q33】：**排名依据**）。 */
    public static final String REAL_WINS = "realWins";

    /** 真人局总场次（榜单第 2 个数字）。 */
    public static final String REAL_MATCHES = "realMatches";

    /** 当前连胜（赢 +1、输清零；**仅供计算，不上榜**）。 */
    public static final String REAL_STREAK = "realStreak";

    /** 历史最高连胜，**只涨不落**（榜单第 3 个数字）。 */
    public static final String REAL_BEST_STREAK = "realBestStreak";

    /**
     * ★ <b>排名依据</b>：{@code docs/08} §7 明写「真人胜场（{@code realWins}）<b>降序</b>」。
     *
     * <p>单列一个常量而不是直接用 {@link #REAL_WINS}：让「改排名依据」这件事只发生在一处，
     * 且{@code LeaderboardRanking} 与测试都引用它。
     */
    public static final String RANK_FIELD = REAL_WINS;

    /**
     * ★★ <b>一行三个数字的顺序</b>（{@code docs/08} §7【Q33】：「胜场 / 总场次 / 最高连胜」）。
     *
     * <p>★ <b>顺序不许改</b>：{@link InfernalClashLeaderboard#formatLine} 按这个顺序取值，
     * {@code LeaderboardFieldsTest} 按这个顺序断言。改顺序 = 改用户看到的榜。
     */
    public static final List<String> LINE_FIELDS = List.of(REAL_WINS, REAL_MATCHES, REAL_BEST_STREAK);

    /**
     * 档案里<b>必须存在且必须 {@code public}</b> 的字段全集（= {@link #LINE_FIELDS} + 只供计算的
     * {@link #REAL_STREAK}）。
     */
    public static final List<String> ARCHIVE_FIELDS =
            List.of(REAL_WINS, REAL_MATCHES, REAL_STREAK, REAL_BEST_STREAK);

    /**
     * ★★ <b>缺口登记（{@code docs/04} §16.13.6 的 G2）—— ★ 现在已清空</b>。
     *
     * <h2>它在历史上是什么</h2>
     * 2026-10-15 的第一轮里，{@code profile/InfernalPlayerData} 里这 <b>4 个 {@code real*}</b>
     * 字段<b>一个都不存在</b>（{@code docs/08} §8.3 当时只是「字段草案」，实际类只有
     * {@code gamesPlayed}）⇒ MGC 反射读不到 ⇒ <b>榜必然是空的</b>。
     * 这份清单当时的作用是<b>把"榜必然是空的"从"哑"变成"响"</b>：
     * {@link ArchiveFieldAccess#unreadableFields} 用它做诊断，装配层据此<b>告警</b>；
     * {@code LeaderboardFieldsTest} 断言它<b>恰好等于</b>当前真实缺失的字段集
     * —— 于是「谁把字段补上了、谁把清单忘在这里」会在测试里立刻暴露。
     *
     * <h2>★ 现在为什么是空的</h2>
     * 2026-10-15 <b>第二轮用户裁定</b>对 G2 答「<b>补</b>」
     * （原话逐字留档于 {@code docs/24-用户裁定落地清单.md} §1 第 4 条；
     * 授权说明在 {@code docs/08} §8.3）⇒ 四个 {@code public int} 字段已落进
     * {@code profile/InfernalPlayerData} ⇒ <b>没有缺口了</b>。
     *
     * <p>★ <b>保留这个常量（而不是删掉它）的理由</b>：它是「<b>缺口为空</b>」这件事的载体 ——
     * {@code LeaderboardFieldsTest} 断言它<b>现在就是空的</b>，并且断言
     * {@link #ARCHIVE_FIELDS} 里<b>每一个</b>字段在档案类上真的存在、{@code public}、类型是数字
     * （这正是 MGC 反射读得到的三条要求）。于是「谁把字段删了 / 改成 {@code private} /
     * 改错了一个字母」，测试会<b>立刻</b>红，而不是等到榜恒空却毫无线索。
     *
     * <p>★ 若将来又出现缺口：<b>往这个清单里加回字段名</b>并在测试里钉住它，
     * <b>不要</b>把那条断言删掉来"让它变绿"。
     */
    public static final List<String> MISSING_ARCHIVE_FIELDS = List.of();

    // ==================================================================================
    // 二、榜单身份与文案（★ 给了出处的才写，没给的标缺口）
    // ==================================================================================

    /**
     * ★★ <b>榜单技术 id = {@code incl_top}</b>（用户 2026-10-15 裁定 ⇒ 缺口 E12 结案）。
     *
     * <p>用户原话「<b>{@code incl_top}</b>」逐字留档于
     * {@code docs/24-用户裁定落地清单.md} §1 第 3 条；{@code docs/08} §7 的表格里
     * 「榜单 id」一格也已从【待填】改成「✅ 已裁定 = {@code incl_top}」
     * （我之前建议的 {@code infernalclash_wins} <b>未被采纳</b>）。
     *
     * <h2>★ 为什么这个值必须只有一处</h2>
     * 同一个字符串会出现在三个地方，<b>必须是同一个值</b>：
     * <ol>
     *   <li>MGC 的注册表键（{@code registerLeaderboard(id, 类)}，
     *       {@code javap} 实测签名见 {@link LeaderboardWiring} 的类注释）；</li>
     *   <li>{@code plugins/MCZJUGameCore/text_displays/<id>/} —— ★ {@code javap -c} 实测：
     *       {@code registerLeaderboard} 的最后一句是 {@code loadTextDisplays(id)}，
     *       而它拼的路径是 {@code Paths.get(数据目录, "text_displays", id)}
     *       ⇒ <b>这个 id 会被直接当作目录名用</b>（{@code docs/08} §7 的「数据落盘」一行）；</li>
     *   <li>管理员建展示实体时敲的 {@code /mgcop leaderboard create <id> <实体id>}
     *       （{@code docs/08} §7：<b>代码里不生成展示实体</b>）。</li>
     * </ol>
     * ⇒ 合法取值（字符集 / 大小写 / 路径分量）的判据只有一处：
     * {@link LeaderboardWiring#isLegalId(String)}（含实测依据）。
     */
    public static final String ID = "incl_top";

    /**
     * ★ <b>榜单 id 的「运行期覆盖」开关</b>（系统属性名）：
     * {@code -Dinfernalclash.leaderboard-id=xxx}。
     *
     * <h2>★ 口径（A4 之后）</h2>
     * <ul>
     *   <li><b>没给</b>（属性不存在 / 取不到）⇒ 用裁定值 {@link #ID}（{@code incl_top}）注册；
     *       ★ 这不是回退到"看起来合理的猜值"，而是用<b>用户裁定过的值</b>；</li>
     *   <li><b>给了且合法</b> ⇒ <b>属性优先</b>（换个 id 做对照实验 / 临时改名不必改代码，
     *       {@code AGENTS.md} 铁律 5 的「优先加运行时开关，不改文件」）；</li>
     *   <li><b>给了但非法</b>（空白 / 含非法字符）⇒ {@link LeaderboardWiring#plan} 返回
     *       <b>拒绝注册 + 告警</b>，<b>绝不</b>悄悄退回 {@link #ID}
     *       ——「你给了但我当没看见」会把配置错误藏起来（失败方向见
     *       {@link LeaderboardWiring} 的类注释）。</li>
     * </ul>
     * 合法性判据只有一处：{@link LeaderboardWiring#isLegalId(String)}。
     *
     * <p>用系统属性而不是配置文件的理由：加配置键要动 {@code config.yml} / {@code config/**}，
     * 而 {@code AGENTS.md} 铁律 5 的口径本来就是「<b>优先加运行时开关，不改文件</b>」。
     */
    public static final String ID_PROPERTY = "infernalclash.leaderboard-id";

    /** {@code display.yml} 里<b>已裁定</b>的榜名键（2026-10-15-J：「炒鸡战争排行榜」）。 */
    public static final String TITLE_KEY = "leaderboard.title";

    /**
     * 榜头副标题：★ <b>原样复用 IG 的字符串</b>（缺口 <b>G7</b>）。
     *
     * <p>出处：{@code docs/08} §7 的【复用】表（「复用 InfernalGuess 的排行榜实现」）+
     * 【Q33】「可以仿照 IG 的对决排行榜」。{@code docs/08} 没给措辞，
     * 且 {@code display.yml} 里<b>只有</b> {@code leaderboard.title} 一个键
     * ⇒ 不另造一句，直接用 IG 在正式服跑着的原文。
     *
     * <p>★★ 2026-10-17 第十三轮：**用户裁定（G7 + G8 结案）**，逐字：
     * 「**标题、副标题进**（`display.yml`），**其余不进**」
     * ⇒ 副标题有了自己的键 {@link #SUBTITLE_KEY}（本常量降级为**回退值**）；
     * ★ 「其余不进」= {@link #LINE_FORMAT}（行文案）**继续留在代码里**，不进配置。
     */
    public static final String SUBTITLE = "<gray>胜场 / 总场次 / 最高连胜";

    /**
     * 副标题在 {@code display.yml} 里的键（{@code messages.leaderboard.subtitle}）——
     * 2026-10-17 第十三轮新增（用户裁定「副标题进」）。
     */
    public static final String SUBTITLE_KEY = "leaderboard.subtitle";

    /**
     * 行格式：★ <b>原样复用 IG 的 {@code formatLine} 格式串</b>（缺口 <b>G8</b>）。
     *
     * <p>参数顺序 = {@code rank, name, realWins, realMatches, realBestStreak}；
     * 后三个数字的顺序就是 {@link #LINE_FIELDS}（{@code docs/08} §7【Q33】）。
     *
     * <p>★ 只有<b>名次前缀</b> {@code %d.} 不是照抄 IG 的：IG 用的是它自己的
     * {@code LeaderboardSupport.rank(int)}（带奖牌样式），而 MGC 基类
     * {@code AbstractLeaderboard.renderLine} 的默认前缀就是 {@code %d.}
     * （{@code javap} 实测 {@code <yellow>%d.</yellow>}）⇒ 取<b>框架</b>的写法，不自己发明样式。
     */
    public static final String LINE_FORMAT =
            "%d. <green>%s</green> <dark_gray>▏</dark_gray> <white>胜 %d</white>"
                    + " <gray>场 %d</gray> <dark_gray>▏</dark_gray> <aqua>连胜 %d</aqua>";
}
