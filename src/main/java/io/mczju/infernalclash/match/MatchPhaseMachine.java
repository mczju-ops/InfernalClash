package io.mczju.infernalclash.match;

import java.util.Objects;

/**
 * ★ <b>对局阶段状态机</b> —— 纯函数，可离线单测（{@code docs/04} §16.1、§16.5）。
 *
 * <p>唯一入口是 {@link #advance(MatchState, MatchSignal, PhaseConfig)}：推进<b>恰好一个 tick</b>，
 * 输入旧状态 + 本 tick 的外部事实，返回新状态；<b>不碰服务端</b>（无 Bukkit / 无实体 / 无调度器）。
 * ⇒ 整条时间轴（准备 → 战斗 → 加时 → 结算）能在 JUnit 里跑完。
 *
 * <h2>★ 判据顺序（照 {@code docs/02} §4 的 W1~W7，一条都不能少）</h2>
 * <ol>
 *   <li>{@link MatchPhase#PREPARING}：{@code phaseTick+1}；<b>达到</b> {@code preparingTicks}
 *       ⇒ 进 {@link MatchPhase#BATTLE}，两个计时器<b>都归零</b>（R13）；</li>
 *   <li>{@link MatchPhase#BATTLE}：{@code battleTick+1} 且 {@code phaseTick+1}；<b>先判胜负、再判超时</b>：
 *     <ul>
 *       <li><b>W6</b> 双方信标同时死亡 ⇒ {@link MatchOutcome#DRAW}；</li>
 *       <li><b>W1</b> 单方信标死亡 ⇒ 该方失败（{@code beaconDownRed ⇒ BLUE_WINS}）；</li>
 *       <li><b>W2</b> {@code battleTick >= battleTicks} 且塔数不等 ⇒ 多者胜；</li>
 *       <li><b>W3 / W7</b> {@code battleTick >= battleTicks} 且塔数相等（<b>含 0:0</b>）
 *           ⇒ 进 {@link MatchPhase#OVERTIME}；</li>
 *     </ul></li>
 *   <li>{@link MatchPhase#OVERTIME}：{@code phaseTick+1}（{@code battleTick} 也继续增长，见 D2）；同样先判胜负：
 *     <ul>
 *       <li><b>W6 口径</b> 两边同时失去目标物 ⇒ {@link MatchOutcome#DRAW}；</li>
 *       <li><b>W4</b> 单方「防御塔<b>或</b>信标」死亡 ⇒ 该方失败；</li>
 *       <li><b>W5</b> {@code phaseTick >= overtimeTicks} 且塔数仍相等 ⇒ {@link MatchOutcome#DRAW}；
 *           不等 ⇒ 多者胜；</li>
 *     </ul></li>
 *   <li>{@link MatchPhase#ENDED}：<b>原样返回</b>（幂等，任何信号都不会改变任何字段）。</li>
 * </ol>
 *
 * <h2>★ W3 与 W5 的对称性（实现时最容易写反的一条）</h2>
 * 进加时的条件是「<b>战斗</b>耗尽时塔数<b>相等</b>」；判平局的条件是「<b>加时结束</b>时塔数<b>仍</b>相等」
 * —— <b>两次都相等才平局</b>，中间只要掉一塔就分胜负
 * （{@code docs/02} §4 末尾明文提醒）。本类把这两处写成两个独立判据，<b>不共用</b>一个分支。
 *
 * <h2>边界口径</h2>
 * 一律「<b>达到即触发</b>」（{@code >=}）：第 {@code battleTicks} 次推进就超时，不是第
 * {@code battleTicks+1} 次 —— 与 {@code docs/02} §2 的 tick 口径一致。
 *
 * <h2>★ P2 阶段的可验证性</h2>
 * P2 还没有塔与信标 ⇒ {@code signal} 恒为 {@link MatchSignal#NONE}（塔数 0:0）⇒
 * 战斗耗尽<b>必然</b>进加时、加时耗尽<b>必然</b>平局。这让「跑完整条时间轴」在 P2 就能验证；
 * P3 把塔与信标接进来后，胜负矩阵自然生效，<b>本类不用改</b>（{@code docs/04} §16.1）。
 *
 * <h2>没有做 / 不在本类职责内的事</h2>
 * <ul>
 *   <li>「双方确认卡组提前进战斗」：{@link MatchSignal} 里没有「就绪」事实（P2 全是 NONE），
 *       故本类只按计时推进；就绪信号属于 P4 的接线；</li>
 *   <li>经验数值累加走 {@code XpCurve}（P0 纯函数）；本类只提供 {@link #isDoubleXp(long, PhaseConfig)}
 *       与 {@link PhaseConfig#ticksPerLevel(long)} 两个判据；</li>
 *   <li>发奖 / 结束对局 / 释放房间：状态机只给结果，收尾由 {@code MatchService} 负责
 *       （对局 {@code ENDED} 必须走 {@code GameManager.endGame}）。</li>
 * </ul>
 *
 * <p>⚠️ 本类<b>不得</b>出现任何广域调用（{@code getEntities} / {@code getOnlinePlayers} 等）——
 * 它跑在每一 tick 上，{@code BytecodeScopeGuardTest} 已把它纳入守护清单。
 */
public final class MatchPhaseMachine {

    private MatchPhaseMachine() {
        // 纯静态工具类
    }

    /**
     * 推进<b>恰好一个 tick</b>。
     *
     * @param state  旧状态（例如 {@link MatchState#INITIAL}）
     * @param signal 本 tick 的外部事实（P2 阶段传 {@link MatchSignal#NONE}）
     * @param config 阶段时长与经验曲线（出厂值见 {@link PhaseConfig#shipped()}）
     * @return 新状态；{@code state.phase() == ENDED} 时<b>原样返回 {@code state} 本身</b>（幂等）
     */
    public static MatchState advance(MatchState state, MatchSignal signal, PhaseConfig config) {
        Objects.requireNonNull(state, "state 不能为 null（对局起点是 MatchState.INITIAL）");
        Objects.requireNonNull(signal, "signal 不能为 null（无事实请传 MatchSignal.NONE）");
        Objects.requireNonNull(config, "config 不能为 null（出厂值用 PhaseConfig.shipped()）");

        return switch (state.phase()) {
            case ENDED -> state;                        // 幂等：结束就是结束，不再推进也不改字段
            case PREPARING -> advancePreparing(state, config);
            case BATTLE -> advanceBattle(state, signal, config);
            case OVERTIME -> advanceOvertime(state, signal, config);
        };
    }

    /**
     * ★★ <b>战斗阶段的起点状态</b>（两个计时器都从 0 起算，无结果）—— <b>唯一</b>一处口径。
     *
     * <h2>为什么要有这个工厂（而不是让调用方自己 new 一个）</h2>
     * 「进战斗」有<b>两条</b>路：
     * <ol>
     *   <li>准备阶段计时耗尽（{@code R13}：{@link #advancePreparing} 里
     *       {@code phaseTick >= preparingTicks}）；</li>
     *   <li>★★ <b>双方卡组都就绪 ⇒ 立即进战斗</b>（用户 2026-10-16 第九轮裁定
     *       「<b>双方都确认后直接开始</b>」，{@code docs/24} §6.4；
     *       调用方 = {@code MatchService#confirmDeck → enterBattleEarly}）。</li>
     * </ol>
     * 两条路必须产出<b>字节级相同</b>的状态（{@code BATTLE, 0, 0, NONE}），否则
     * 「提前开局」会悄悄改变战斗计时的起点（{@code battleTick} 不从 0 起 ⇒ 双倍经验
     * 与超时判据全部偏移）。⇒ 口径收在这一个工厂里，两条路都调它。
     */
    public static MatchState beginBattle() {
        // R13 到时 ⇒ R11 开始：phaseTick 与 battleTick 都从 0 起算
        return new MatchState(MatchPhase.BATTLE, 0, 0, MatchOutcome.NONE);
    }

    /**
     * 双倍经验是否已生效（R22 / D2）。
     *
     * <p>★ 判据<b>只有这一条</b>：{@code battleTick >= doubleXpAfterTicks}。
     * 加时<b>不引入第三种速率</b>：加时期间 {@code battleTick} 继续增长（见 {@link MatchState}），
     * 于是它自然还是双倍（{@code docs/02} §2 的 D2）。
     *
     * <p>⚠️ 入参是 {@code long} 而配置是 {@code int}：对局 tick 数可能被外部累加器传成更大的数，
     * 这里不做截断（负值按「未生效」处理，与实际语义一致）。
     */
    public static boolean isDoubleXp(long battleTick, PhaseConfig config) {
        Objects.requireNonNull(config, "config 不能为 null（出厂值用 PhaseConfig.shipped()）");
        return battleTick >= config.doubleXpAfterTicks();
    }

    // ------------------------------------------------------------------ 各阶段

    /** 准备阶段：只推进自己的计时；到点进战斗并把两个计时器都归零。 */
    private static MatchState advancePreparing(MatchState state, PhaseConfig config) {
        int phaseTick = state.phaseTick() + 1;
        if (phaseTick >= config.preparingTicks()) {
            // R13 到时 ⇒ R11 开始（起点口径只有 `beginBattle()` 一处：
            // 「计时耗尽」与「双方就绪提前开局」必须产出同一个状态）
            return beginBattle();
        }
        return new MatchState(MatchPhase.PREPARING, phaseTick, state.battleTick(), MatchOutcome.NONE);
    }

    /** 战斗阶段：先判胜负（W6 → W1），再判超时（W2 / W3、W7）。 */
    private static MatchState advanceBattle(MatchState state, MatchSignal signal, PhaseConfig config) {
        int phaseTick = state.phaseTick() + 1;
        int battleTick = state.battleTick() + 1;

        // ---- 先判胜负：本 tick 的信标死亡优先于「同一 tick 恰好时间耗尽」（docs/02 §4 顺序）
        //      战斗阶段掉塔不判负（那是 W4，只在加时生效），塔数变化体现在计数里。
        MatchOutcome decided = outcomeOf(signal.beaconDownRed(), signal.beaconDownBlue());
        if (decided != MatchOutcome.NONE) {
            return new MatchState(MatchPhase.ENDED, phaseTick, battleTick, decided);
        }

        // ---- 再判超时
        if (battleTick >= config.battleTicks()) {
            if (signal.towersRed() != signal.towersBlue()) {
                // W2：塔数不等 ⇒ 多者胜
                return new MatchState(MatchPhase.ENDED, phaseTick, battleTick, higherTowersWin(signal));
            }
            // W3 / W7：塔数相等（含 0:0）⇒ 进加时。
            // ★ battleTick 不归零：加时的双倍判据继续看它（D2）；phaseTick 归零，重新计时 overtimeTicks。
            return new MatchState(MatchPhase.OVERTIME, 0, battleTick, MatchOutcome.NONE);
        }

        return new MatchState(MatchPhase.BATTLE, phaseTick, battleTick, MatchOutcome.NONE);
    }

    /** 加时阶段：先判胜负（W4，含双方同时失去目标物的 W6 口径），再判超时（W5）。 */
    private static MatchState advanceOvertime(MatchState state, MatchSignal signal, PhaseConfig config) {
        int phaseTick = state.phaseTick() + 1;
        // ★ D2：加时期间 battleTick 继续增长（它是「加时仍按双倍」的唯一判据）
        int battleTick = state.battleTick() + 1;

        // ---- 先判胜负：W4 的口径是「该方的**塔或信标**出局」
        //   ★★★ 2026-10-20（用户实机报「加时里掉一座塔却不结束」）：
        //     判据从**纯边沿**改成**边沿 ∪ 电平** ——
        //     ① 信标：`beaconDown*`（本 tick 边沿）**或** `beaconGone*`（现在已不在）；
        //     ② 塔：`towerDown*`（边沿）**或** `towersRed != towersBlue`（**塔数不等**）。
        //   ★ 为什么塔可以直接比计数：进加时的前提就是**塔数相等**（见 advanceBattle 的 W2/W3），
        //     所以加时里一旦不等，必然是**刚刚掉了一座** ⇒ 一定是那一方出局。
        //   ★ 为什么不能只靠边沿：边沿只存在**一个 tick**，追踪器又是幂等的（掉过就不再发）
        //     ⇒ 那一个 tick 的信号只要没送到状态机，W4 就**永久失效**，对局拖到加时耗尽
        //     （现场日志就是这么打的：13:23:46 掉塔、13:24:23 才因 4800 tick 耗尽结束）。
        //     电平判据每个 tick 都为真 ⇒ 下一个 tick 必然补上，不可能再丢。
        boolean redLoses = signal.beaconDownRed() || signal.beaconGoneRed()
                || signal.towerDownRed() || signal.towersRed() < signal.towersBlue();
        boolean blueLoses = signal.beaconDownBlue() || signal.beaconGoneBlue()
                || signal.towerDownBlue() || signal.towersBlue() < signal.towersRed();
        MatchOutcome decided = outcomeOf(redLoses, blueLoses);
        if (decided != MatchOutcome.NONE) {
            return new MatchState(MatchPhase.ENDED, phaseTick, battleTick, decided);
        }

        // ---- 再判超时
        if (phaseTick >= config.overtimeTicks()) {
            // W5：加时结束时**仍**相等才是平局（第二次相等）；不等 ⇒ 多者胜
            MatchOutcome outcome = (signal.towersRed() == signal.towersBlue())
                    ? MatchOutcome.DRAW
                    : higherTowersWin(signal);
            return new MatchState(MatchPhase.ENDED, phaseTick, battleTick, outcome);
        }

        return new MatchState(MatchPhase.OVERTIME, phaseTick, battleTick, MatchOutcome.NONE);
    }

    // ------------------------------------------------------------------ 判据

    /**
     * 「谁失败」→ outcome（两条判据的唯一实现，战斗与加时共用）。
     *
     * <p>★ 顺序即优先级：<b>双方同时失败 ⇒ 平局</b>（W6：双方信标同时死亡；加时中亦可推广为
     * 「两边同时失去目标物」—— 用户口径是「同样先判双方同时死 ⇒ DRAW」）。
     * 顺序反过来会把「同时死亡」误判成一方的胜负，且胜负会取决于 if 的书写顺序（不对称）。
     *
     * @param redLoses  红方失败（战斗：信标死亡；加时：塔<b>或</b>信标死亡）
     * @param blueLoses 蓝方失败（同上）
     */
    private static MatchOutcome outcomeOf(boolean redLoses, boolean blueLoses) {
        if (redLoses && blueLoses) {
            return MatchOutcome.DRAW;       // W6
        }
        if (redLoses) {
            return MatchOutcome.BLUE_WINS;  // W1 / W4
        }
        if (blueLoses) {
            return MatchOutcome.RED_WINS;   // W1 / W4
        }
        return MatchOutcome.NONE;
    }

    /** W2 / W5 后半句：塔数不等时<b>多者胜</b>（调用前必须已确认两数不等）。 */
    private static MatchOutcome higherTowersWin(MatchSignal signal) {
        return signal.towersRed() > signal.towersBlue()
                ? MatchOutcome.RED_WINS : MatchOutcome.BLUE_WINS;
    }
}
