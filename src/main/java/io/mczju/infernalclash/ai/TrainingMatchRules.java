package io.mczju.infernalclash.ai;

import io.mczju.infernalclash.game.StartGate;
import io.mczju.infernalclash.leaderboard.LeaderboardStats;

import java.util.List;

/**
 * ★ <b>训练局与真人局的差异（纯规则）</b> —— 把 {@code docs/02} §9 / {@code docs/08} §7
 * 已经裁定的那几条"算不算 / 一样不一样"收在一处。
 *
 * <h2>★ 权威</h2>
 * <table border="1">
 *   <caption>逐条出处</caption>
 *   <tr><th>问题</th><th>结论</th><th>出处</th></tr>
 *   <tr><td>人机局算不算排行榜统计</td><td><b>不算</b></td>
 *       <td>{@code docs/08} §7 R63「只计真人对局胜场（人机局不计）」</td></tr>
 *   <tr><td>人机什么时候开</td><td><b>由房间里任一玩家敲 {@code /incl bot on} 显式开</b>
 *       （<b>不再</b>"单人开局自动开"）</td>
 *       <td>★★ <b>2026-10-15 用户裁定（逐字）</b>：「<b>需要房间里任何一人敲/incl bot on；
 *           关掉人机需要敲/incl bot off。存在人机时进来第二个人也能正常开战</b>」
 *           —— 留档 {@code docs/24} §5.5；它<b>取代</b> {@code docs/02} §9 的 R62「单人开局自动开人机」</td></tr>
 *   <tr><td>训练局的时间轴 / 胜负条件与真人局一样吗</td><td><b>一样（无例外）</b></td>
 *       <td>{@code docs/02} §9 <b>没有给任何差异</b> —— ★ 这是"没有例外"，
 *           <b>不是</b>我新加的一条规则（见 {@link #phasePlan()}）</td></tr>
 *   <tr><td>人机局的金币</td><td>胜 <b>+100</b> / 败 <b>0</b></td>
 *       <td>{@code docs/02} §10.3（Q55）—— ★ <b>不在本类</b>：金币归
 *           {@code config/EconomyConfig} 的 {@code bot-win-reward}，本类<b>不重复写</b>这个数
 *           （第二处事实来源 = 本项目最反对的事）</td></tr>
 * </table>
 *
 * <h2>★ 纯（{@code AGENTS.md} §3）</h2>
 * 只依赖 JDK + 本插件的纯类 ⇒ <b>离线可单测</b>（{@code TrainingMatchRulesTest}）。
 * ★ 铁律 10：不出现 {@code getOnlinePlayers / getEntities / getLivingEntities / getWorlds}
 * —— 人数是<b>传进来的参数</b>，不是去世界里数的。
 */
public final class TrainingMatchRules {

    /** 一个人开局时自动开人机（R62）所要求的人数。 */
    private static final int SINGLE_PLAYER = 1;

    /** 真人局的最少人数（1v1，R1/R2）；{@code >2} 的情形属 {@code docs/05} 的 <b>E5</b>，不在本类。 */
    private static final int TWO_PLAYERS = 2;

    /**
     * ★★ <b>人机局的"开局窗口"（tick）</b> —— 用户 2026-10-15 裁定要求的
     * 「<b>开启后不能立刻开局</b>」
     * （逐字：「<b>存在人机时进来第二个人也能正常开战</b>」，留档 {@code docs/24} §5.5）。
     *
     * <h2>★★ 这个数的性质：<b>不是玩法数值</b></h2>
     * 它是<b>我为满足上述用户要求所必需的最小可调参数</b>，<b>不是</b>我替用户定的玩法平衡值。
     * 依据与边界如下：
     * <ul>
     *   <li>★ <b>MGC 不自带倒计时</b>（2026-10-15 {@code javap} 实测，见 {@code dev-log/2026-10-15-023}）：
     *       {@code DefaultGameWaitStrategy.tryStart()} 只判人数后<b>立刻</b> {@code startGame()}，
     *       全文没有调度器 / 计时器；{@code AbstractGame} 也没有任何倒计时字段；
     *       MGC 里那个 {@code utils/CountDown} 在 1.1.2 的常量池里<b>只被它自己引用</b>
     *       ⇒ 本类必须自带一个窗口，否则「再来第二个人」这件事在物理上不可能发生；</li>
     *   <li>★ <b>取值与同项目既有口径一致</b>：{@code ai/TrainingBotDriver#CAST_INTERVAL_TICKS}
     *       的 javadoc 就是这么写的（"最小可调参数，等你拍板"），本条<b>同一口径</b>；</li>
     *   <li>★ <b>20 tick = 1 秒</b>（Minecraft 固定 20 tick/秒）—— 与 G11 裁定的「1 秒」节拍同量级，
     *       不引入第二个"手感"数字。</li>
     * </ul>
     *
     * <p>★★ <b>需要用户拍板</b>：这个值我按"够第二个人看到提示并进房"取 1 秒；
     * 要更长/更短<b>改这一个常量</b>即可（{@code TrainingMatchRulesTest} 有一条断言把它钉住，
     * 改它会先红 —— 那是刻意的，防止有人顺手调它）。
     */
    public static final int BOT_START_WINDOW_TICKS = 20;

    /** Minecraft 固定 20 tick/秒 ⇒ 1 tick = 50 毫秒（{@link #BOT_START_WINDOW_TICKS} 的说明用）。 */
    public static final int MILLIS_PER_TICK = 50;

    private TrainingMatchRules() {
    }

    // ==================================================================================
    // 一、统计口径
    // ==================================================================================

    /**
     * ★ 这一局算不算<b>排行榜统计</b>（{@code docs/08} §7：只计真人对局）。
     *
     * <p>★★ 本方法<b>不自己写这份判据</b>：它向纯核心
     * {@link LeaderboardStats#record} 问一句（用一个必然通过的 {@code WIN} 当探针，
     * 因为"人机闸门"在结果判定<b>之前</b>就短路了）。
     * 这样「人机局不计」这句话在项目里<b>只有一处实现</b>
     * —— 复制一遍就是「第二处事实来源」，将来两边会分叉。
     *
     * @param kind 跟谁打的；{@code null} ⇒ 返回 {@code false}（不上榜，宁可保守）
     */
    public static boolean countsForLeaderboard(LeaderboardStats.MatchKind kind) {
        if (kind == null) {
            return false;
        }
        return LeaderboardStats.EMPTY.record(kind, LeaderboardStats.Outcome.WIN).applied();
    }

    // ==================================================================================
    // 二、训练模式
    // ==================================================================================

    /**
     * ⚠️ <b>R62 原语义：「单人开局自动开人机」</b> —— ★★ <b>已被 2026-10-15 裁定取代</b>。
     *
     * <p>用户原话（逐字）：「<b>需要房间里任何一人敲/incl bot on；关掉人机需要敲/incl bot off。
     * 存在人机时进来第二个人也能正常开战</b>」（留档 {@code docs/24} §5.5）⇒
     * 「<b>自动</b>」这件事没有了：人机只由<b>显式</b>的 {@code /incl bot on} 开启。
     * ★ 本重载<b>保留</b>是为了不删历史口径、并让「R62 到底说了什么」可被单独断言；
     * <b>现行规则是两维的 {@link #botEnabledFor(int, boolean)}</b>，
     * 生产代码（{@code game/InfernalClashGame} 的装配路径：
     * {@code ai/TrainingBotAssembly#appendBotToRoster} → 名册注入；
     * {@code InfernalClashGame#createTrainingBotDriver} → 执行层）走的是它。
     *
     * <p>判据就是人数本身：
     * <ul>
     *   <li>{@code humanCount == 1} ⇒ {@code true}（R62，<b>已作废</b>）；</li>
     *   <li>{@code humanCount >= 2} ⇒ {@code false}（两名真人 = 正常对局；多余玩家怎么处置
     *       属 {@code docs/05} 的 <b>E5</b>，<b>不在这里替它决定</b>）；</li>
     *   <li>{@code humanCount <= 0} ⇒ ★ <b>抛</b> {@link IllegalArgumentException}
     *       ——「没有人的对局」是调用方的编程错误（fail-fast，读判据不该猜）。</li>
     * </ul>
     *
     * @param humanCount 真人玩家数（★ 由调用方给出，本类不查世界）
     */
    public static boolean botEnabledFor(int humanCount) {
        if (humanCount < SINGLE_PLAYER) {
            throw new IllegalArgumentException("humanCount 必须是 >= 1 的真人数量，实际是 " + humanCount
                    + "：0 人不成局（docs/02 §9 只规定「单人开局」，不是「无人开局」）");
        }
        return humanCount == SINGLE_PLAYER;
    }

    /**
     * ★★ <b>该不该开人机（现行规则，两维）</b> —— <b>2026-10-15 用户裁定</b>。
     *
     * <h2>权威（逐字）</h2>
     * 「<b>需要房间里任何一人敲/incl bot on；关掉人机需要敲/incl bot off。
     * 存在人机时进来第二个人也能正常开战</b>」（{@code docs/24} §5.5）⇒ 判据是
     * <b>「有人显式请求」＋「房间里只剩 1 名真人」</b>两维，缺一不可。
     *
     * <h2>★ 四条组合的去向（每一格都有测试）</h2>
     * <table border="1">
     *   <caption>人机开关真值表</caption>
     *   <tr><th>真人</th><th>有人敲了 {@code /incl bot on}</th><th>开人机？</th><th>依据</th></tr>
     *   <tr><td>1</td><td>是</td><td><b>开</b></td><td>用户原话（显式开启 ＋ 单人）</td></tr>
     *   <tr><td>1</td><td>否</td><td><b>不开</b></td>
     *       <td>★ 这正是 R62 被取代的那一格：以前会<b>自动</b>开</td></tr>
     *   <tr><td>2</td><td>是</td><td><b>不开</b></td>
     *       <td>★ 「存在人机时进来第二个人也能正常开战」⇒ 2 真人就是正常 1v1，人机<b>不上场</b></td></tr>
     *   <tr><td>≥2</td><td>否</td><td><b>不开</b></td>
     *       <td>R62 只管单人；{@code >2} 人属 {@code docs/05} 的 E5</td></tr>
     * </table>
     *
     * @param humanCount  真人玩家数（★ 由调用方给出，本类不查世界）
     * @param botRequested 这个房间里有没有人敲过 {@code /incl bot on}（且还没 {@code off} /
     *                     还没开局；见 {@code game/InfernalClashGame#botRequested}）
     * @throws IllegalArgumentException {@code humanCount < 1} —— 与一参重载同口径（fail-fast）
     */
    public static boolean botEnabledFor(int humanCount, boolean botRequested) {
        if (humanCount < SINGLE_PLAYER) {
            throw new IllegalArgumentException("humanCount 必须是 >= 1 的真人数量，实际是 " + humanCount
                    + "：0 人不成局（docs/02 §9 只规定「单人开局」，不是「无人开局」）");
        }
        return humanCount == SINGLE_PLAYER && botRequested;
    }

    /**
     * ★★ <b>开局门槛要用的「有效参战人数」</b> —— 纯函数，逐格可测。
     *
     * <h2>为什么门槛要数这一项（用户要求的直接落地）</h2>
     * {@code game.min-players} 保持 <b>2 不动</b>（用户裁定的 B 方案：**不是**把门槛改成 1）。
     * 于是「1 名真人 ＋ 一台人机」要能开局，唯一的办法是：<b>把"已请求的人机"也算成一名参战者</b>。
     *
     * <pre>
     *   effective = humans + (botRequested &amp;&amp; botEnabledFor(humans, true) ? 1 : 0)
     * </pre>
     *
     * <p>★ 第二个加数直接<b>复用</b> {@link #botEnabledFor(int, boolean)}
     * —— 不在这里再写一遍「人数 == 1」的 if（本项目「第二处判据必然分叉」的教训）。
     *
     * <h2>★★ 0 人为什么<b>不抛</b>（与一参重载的唯一差异，刻意的）</h2>
     * 一参 {@link #botEnabledFor(int)} 是"读判据"，0 人是编程错误 ⇒ fail-fast。
     * 而本方法跑在 <b>MGC 的开局回调里</b>（{@code /mgc start} 与我们自己的窗口任务）
     * ⇒ 一旦抛出去，异常会冒到指令分发 / 调度器上，代价是<b>房间里的人卡住且看到一段堆栈</b>。
     * 而它的用途只是<b>喂给门槛</b>：返回 {@code 0} 时
     * {@code game/StartGate#canStart} 必然为 false（分母至少是 1）⇒ 行为与"抛"同样安全，
     * 但房间还能继续等人。★ 这是<b>失败方向</b>的选择，不是漏判。
     *
     * @param humanCount  房间里当前的真人数量（任何值都接受，负数与 0 一律按 0 名参战者算）
     * @param botRequested 有没有人敲过 {@code /incl bot on}
     * @return 门槛要比较的参战人数（★ 只数"会被算作参战者"的一方）
     */
    public static int effectiveCombatants(int humanCount, boolean botRequested) {
        if (humanCount < SINGLE_PLAYER) {
            return 0;   // 见 javadoc：喂养门槛，不抛（0 人 ⇒ 门槛必不通过）
        }
        int humans = humanCount;
        boolean botCounts = botRequested && botEnabledFor(humans, true);
        return humans + (botCounts ? 1 : 0);
    }

    /**
     * ★★ <b>开局该走哪条路</b>（纯函数的三态结果）—— {@link #startPath(int, boolean, int)} 的返回值。
     *
     * <h2>★★ 为什么必须有「两条放行路径」而不是一条</h2>
     * 门槛（{@link StartGate#canStart}）放行<b>不等于</b> MGC 基类放行：
     * {@code DefaultGameWaitStrategy.tryStart()} 在放行前<b>自己又数了一遍真人</b>
     * （{@code javap} 实测 2026-10-16：{@code getPlayers().size()} 与
     * <b>{@code private final int minPlayer}</b> 比较，不够就
     * {@code sender().warn("当前人数不足…")} 然后 <b>{@code return}</b>）。
     * ⇒ 「1 真人 ＋ 已请求的人机」这一种局，<b>交给基类就必然被它拦下</b>，
     * 而它那句拒绝<b>只发到房间内玩家的聊天</b>（{@code AbstractGame.sender} 是
     * {@code GameSender}，<b>不进控制台</b>）⇒ 现场表现就是<b>「控制台什么都没有」</b>。
     * 这正是 2026-10-16 用户实机撞到的那一跤（留档 {@code dev-log/2026-10-16-025}）。
     *
     * <p>★ 于是：真人自己够 ⇒ {@link #SUPER_TRY_START}（原样交给基类）；
     * 真人不够、但因<b>已请求的人机</b>而有效人数够 ⇒ {@link #START_GAME_DIRECTLY}
     * （走 {@code GameWaitStrategy#startGame()} 这个 <b>public</b> 入口，避开基类那次重判）。
     */
    public enum StartPath {
        /** 真人自己就够 ⇒ 原样 {@code super.tryStart()}（{@code /mgc start} 走的也是这条路）。 */
        SUPER_TRY_START,
        /** ★ 真人不够、但已请求的人机把有效人数补到门槛 ⇒ 直接 {@code startGame()}。 */
        START_GAME_DIRECTLY,
        /** 两者都不满足 ⇒ 只警告、房间保留（现状不变）。 */
        REFUSE
    }

    /**
     * ★★ <b>开局该走哪条路（纯函数，逐格可测）</b> —— 2026-10-16 用户实机缺陷的修法核心。
     *
     * <h2>判据（三态，互斥且穷尽）</h2>
     * <pre>
     *   REFUSE               : !canStart(effectiveCombatants(humans, requested), need)
     *   SUPER_TRY_START      :  canStart(humans, need)                  ← 真人自己够
     *   START_GAME_DIRECTLY  :  否则（真人不够，但有效人数够 = 已请求的人机补上了差额）
     * </pre>
     * ⇒ 顺序是刻意的：<b>先</b>问「真人自己够不够」，<b>够就必须走基类</b> ——
     * 这样「2 真人 ＋ 有人敲过 {@code /incl bot on}」仍旧是 {@link #SUPER_TRY_START}
     * （{@link #botEnabledFor(int, boolean)} 为假 ⇒ 人机不上场 ⇒ 正常 1v1，
     * 用户原话「存在人机时进来第二个人也能正常开战」）。
     *
     * <h2>★★ 为什么<b>不</b>用「把传给基类的 {@code minPlayer} 改成 1」这条更省事的路</h2>
     * 那样等于让「基类的开局门槛」与「{@code config.yml} 的 {@code game.min-players}」
     * <b>分叉成两个数</b>：配置文件写 2、基类拿 1 ⇒ 谁在什么情况下说话不一批，
     * 而这正是本项目栽过的「<b>第二处白名单</b>」坑
     * （{@code AGENTS.md} §0 末尾：{@code EffectApplier.allowedKeys} 与 {@code docs/03} §4.4
     * 分叉 ⇒ 出厂卡「能装载、一施放就失败」，离线全绿也查不出）。
     * ★ 而且 {@code minPlayer} 是 {@code private final}（{@code javap} 实测）⇒
     * 只能在构造时传一个假值，运行期<b>没有任何办法</b>让它再与配置对齐。
     * ⇒ 本类<b>保持「门槛只有 {@code game.min-players} 一个数」</b>，
     * 用「有效参战人数」把已请求的人机数进去（{@link #effectiveCombatants}），
     * 再在<b>放行之后</b>分派到正确的那条开局入口。
     *
     * <h2>★ 与 {@link StartGate} 的关系：不复制判据</h2>
     * 两处「够不够」都<b>向 {@link StartGate#canStart} 问</b>，本类不自己写
     * {@code humans >= need} 的比较（本项目「第二处判据必然分叉」的教训）。
     *
     * @param humanCount   房间里当前的真人数量（任何值都接受；{@code < 1} 一律按 0 名参战者算）
     * @param botRequested 有没有人敲过 {@code /incl bot on}（且还没 off / 还没开局）
     * @param minPlayers   配置要求的最少参战人数（{@code game.min-players}，出厂与现场都是 2）
     * @return 三态之一（{@code null} 永不返回）
     */
    public static StartPath startPath(int humanCount, boolean botRequested, int minPlayers) {
        int effective = effectiveCombatants(humanCount, botRequested);
        if (!StartGate.canStart(effective, minPlayers)) {
            return StartPath.REFUSE;
        }
        if (StartGate.canStart(humanCount, minPlayers)) {
            return StartPath.SUPER_TRY_START;
        }
        return StartPath.START_GAME_DIRECTLY;
    }

    /** {@link StartPath} 的中文说明（给日志用，含出处）。 */
    public static String describeStartPath(StartPath path, int humanCount, int minPlayers) {
        if (path == null) {
            return "startPath=null（非法：判据不该返回 null）";
        }
        return switch (path) {
            case SUPER_TRY_START -> "真人 " + humanCount + " 名 ≥ 需要 " + Math.max(1, minPlayers)
                    + " 名，交给 MGC 开局（super.tryStart）";
            case START_GAME_DIRECTLY -> "真人 " + humanCount + " 名 ＜ 需要 " + Math.max(1, minPlayers)
                    + " 名，但已请求人机，有效参战人数满足门槛，直接 startGame()"
                    + "（避开基类按真人数重判，见 TrainingMatchRules.StartPath）";
            case REFUSE -> "真人 " + humanCount + " 名，门槛 " + Math.max(1, minPlayers)
                    + " 名，不开局（等待房间保留）";
        };
    }

    /**
     * ★ <b>人机开局窗口还剩多少 tick</b>（纯函数：只吃"已经过多少 tick"，
     * 时钟由调用方给 ⇒ 普通 JUnit 里就能逐格断言）。
     *
     * @param elapsedTicks 从「敲下 {@code /incl bot on}」到现在已经过的 tick
     *                     （{@code <= 0} = 时钟不可信 / 刚刚敲下 ⇒ 按<b>满窗口</b>算，最保守）
     * @return 剩余 tick；{@code 0} = 窗口已满，可以开局
     */
    public static int botWindowRemainingTicks(int elapsedTicks) {
        return botWindowRemainingTicks(elapsedTicks, false);
    }

    /**
     * ★★★ <b>{@link #botWindowRemainingTicks(int)} ＋ 一个「窗口任务已经到点」的开关</b>
     * （2026-10-19 修「1 真人 ＋ 1 人机 <b>不会</b>自动开局」时新增）。
     *
     * <h2>为什么必须由调用方告诉它"到点了"，而不是靠它自己算</h2>
     * 现场实测（{@code latest.log} 19:41:22 → 19:41:23）：窗口任务按
     * {@code runTaskLater(…, 20)} 排出去，真的到点时 {@code getCurrentTick() - 请求那一刻}
     * 却算出了 <b>19</b> ⇒ 这里返回 **1** ⇒ {@code tryStart()} 走了"还没到"的分支**直接 return**，
     * 而窗口任务是<b>一次性</b>的 ⇒ 之后再没有任何东西推它，房间就一直停着，
     * 直到玩家自己敲 {@code /mgc start}（19:42:31 那一行）。
     *
     * <p>⇒ 语义上「窗口任务已经跑过」<b>就是</b>"窗口已满"，不该再拿两个时钟相减来赌。
     * 时钟相减仍然保留（给"窗口还没到"的中途提示用），但它<b>不再**是唯一判据**。
     *
     * @param elapsedTicks    已过 tick（{@code <= 0} = 不可信 ⇒ 按满窗口算）
     * @param windowTaskFired 一次性窗口任务是否已经跑过（{@code true} ⇒ 恒返回 {@code 0}）
     */
    public static int botWindowRemainingTicks(int elapsedTicks, boolean windowTaskFired) {
        if (windowTaskFired) {
            return 0;
        }
        if (elapsedTicks <= 0) {
            return BOT_START_WINDOW_TICKS;
        }
        return Math.max(0, BOT_START_WINDOW_TICKS - elapsedTicks);
    }

    /** {@code botEnabledFor} 的中文说明（给日志用，含出处）。 */
    public static String describeBotEnablement(int humanCount) {
        if (humanCount == SINGLE_PLAYER) {
            return "单人开局，自动开启训练人机（docs/02 §9 R62）";
        }
        if (humanCount >= TWO_PLAYERS) {
            return humanCount + " 名真人，这是正常对局，不自动开人机"
                    + "（docs/02 §9 R62 只规定单人开局；>2 人属 docs/05 E5）";
        }
        return "humanCount=" + humanCount + " 非法（见 botEnabledFor）";
    }

    /** ★ 现行（两维）规则的中文说明（给日志用，含出处与"谁敲了什么"）。 */
    public static String describeBotEnablement(int humanCount, boolean botRequested) {
        if (humanCount < SINGLE_PLAYER) {
            return "humanCount=" + humanCount + " 非法（见 botEnabledFor）";
        }
        if (botRequested && humanCount == SINGLE_PLAYER) {
            return "1 名真人 ＋ 已敲 /incl bot on，开启训练人机"
                    + "（2026-10-15 用户裁定「需要房间里任何一人敲/incl bot on」；docs/24 §5.5）";
        }
        if (!botRequested && humanCount == SINGLE_PLAYER) {
            return "1 名真人，但没有人敲 /incl bot on，不开人机"
                    + "（这正是 R62「单人自动开」被 2026-10-15 裁定取代的那一格；docs/24 §5.5）";
        }
        return humanCount + " 名真人，正常 1v1 对局，人机不上场"
                + (botRequested
                        ? "（即使有人敲过 /incl bot on：用户原话「存在人机时进来第二个人也能正常开战」）"
                        : "（docs/02 §9 R62 只规定单人开局；>2 人属 docs/05 E5）");
    }

    // ==================================================================================
    // 三、训练局的阶段差异
    // ==================================================================================

    /**
     * 训练局的阶段与胜负条件<b>有没有</b>与真人局不同的地方。
     *
     * @param sameTimeline     时间轴是否与真人局相同
     * @param sameWinConditions 胜负条件是否与真人局相同
     * @param differences      差异清单（★ 空列表 = 没有例外）
     * @param reason           出处说明
     */
    public record PhasePlan(boolean sameTimeline, boolean sameWinConditions,
                            List<String> differences, String reason) {
    }

    /**
     * ★ <b>训练局的阶段计划</b>。
     *
     * <p>★ 结论是「<b>没有差异</b>」，理由是 {@code docs/02} §9 <b>没有给任何例外</b>：
     * 训练局照样走 §2 的时间轴（准备 1200 / 战斗 3600 / 加时 1200）与 §4 的胜负矩阵（W1~W7）。
     *
     * <p>⚠️ 这句话是「<b>没有例外</b>」，<b>不是</b>我新加的一条规则；
     * 但它确实是<b>需要确认</b>的一条（缺口 <b>G13</b>：训练局要不要缩短 / 改胜负）——
     * 若用户要差异，<b>改这一处</b>即可（{@link PhasePlan#differences} 就是给那时候用的）。
     */
    public static PhasePlan phasePlan() {
        return new PhasePlan(true, true, List.of(),
                "docs/02 §9 没有给训练局在时间轴 / 胜负条件上的任何例外，与真人局同一套"
                        + "（§2 时间轴 + §4 胜负矩阵）；若要差异，属缺口 G13，等裁定");
    }

    /** 训练局有没有与真人局不同的地方（= {@link #phasePlan()} 的差异清单是否为空）。 */
    public static boolean matchesNormalGame() {
        PhasePlan plan = phasePlan();
        return plan.sameTimeline() && plan.sameWinConditions() && plan.differences().isEmpty();
    }
}
