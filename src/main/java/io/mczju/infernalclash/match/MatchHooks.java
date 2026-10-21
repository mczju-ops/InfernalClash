package io.mczju.infernalclash.match;

/**
 * ★ <b>对局的可插拔钩子</b> —— 让 P3 的目标物系统接入 P2 的时间轴，而**不把两者写死在一起**。
 *
 * <h2>为什么要这层接口</h2>
 * {@link MatchService} 是「时间轴 + 账目」，它**不应该知道**信标长什么样、光束怎么打。
 * 它只需要每 tick 问一句「现在有什么事实」（{@link #signal()}），
 * 以及在阶段变化时通知一声（{@link #onPhaseChanged}）。
 * <ul>
 *   <li>P3：由 {@code objective/ObjectiveService} 实现（真实的目标物）；</li>
 *   <li>P4/P5：召唤物与战斗系统可以再实现一个（或组合成链）；</li>
 *   <li>★ 单测：可以直接塞一个**假实现**，逐 tick 喂信号 —— 状态机的接线因此也能被断言。</li>
 * </ul>
 *
 * <p>默认实现是「什么都不知道」：信号恒为空、回调什么都不做 ⇒ 不传钩子时退化成 P2 的纯时间轴。
 */
public interface MatchHooks {

    /** 本 tick 的外部事实（信标/塔的死亡边沿与当前塔数）。 */
    default MatchSignal signal() {
        return MatchSignal.NONE;
    }

    /**
     * 每 tick 的推进回调（**在取 {@link #signal()} 之前**调用）。
     *
     * <p>★ 为什么要有它、而不是把逻辑塞进 {@link #signal()}：`signal()` 语义是**只读**的
     * （状态机每 tick 问一次事实），在里面做伤害/粒子/传送这类副作用会让「读一下状态」
     * 变成危险操作。⇒ 副作用放这里，`signal()` 保持纯读。
     *
     * @param phase      当前阶段（**推进前**的，即本 tick 开始时的事实）
     * @param battleTick 战斗内 tick（P2 起加时也继续增长，见 D2）
     */
    default void onTick(MatchPhase phase, int battleTick) {
    }

    /** 阶段发生变化（PREPARING → BATTLE → OVERTIME → ENDED）。 */
    default void onPhaseChanged(MatchPhase from, MatchPhase to) {
    }

    /**
     * ★★ <b>准备阶段开始</b>（{@code to == PREPARING} 的那一刻）——
     * 2026-10-16 第八轮为「对局开局之后的玩家侧链路」新增。
     *
     * <h2>为什么单独一个回调，而不是让实现方自己看 {@link #onPhaseChanged}</h2>
     * 「准备阶段开始时该做的事」是一份<b>清单</b>（{@code docs/02} §2 / {@code docs/09} §5.4）：
     * 把玩家送到出生点 ＋ 发「选卡菜单」物品 ＋ <b>自动弹一次 U4</b>。
     * 若每个实现方各自在 {@code onPhaseChanged} 里判一次 {@code to == PREPARING}，
     * 就会出现「三件事分散在三处、漏掉一件也不报错」——本项目最反复的那类事故。
     * ⇒ 由 {@code MatchService} <b>集中</b>在阶段切换那一刻调一次，实现方只回答
     * 「我要做什么」。
     *
     * <p>★ 默认实现什么都不做 ⇒ 既有实现（目标物 / 战斗 / 人机）一个字都不用改。
     */
    default void onPrepPhaseStarted() {
    }

    /**
     * ★★ <b>某一方确认了卡组</b>（U4 界面的「确认卡组」按钮，{@code docs/09} §5.2 的槽位 0）。
     *
     * <h2>为什么要经过这里，而不是界面直接发牌</h2>
     * 「确认 ⇒ 8 张按界面顺序落到 2~9 号格」是<b>对局侧</b>的事
     * （{@code game/CardLoadout#placeDeck} 是唯一的发牌实现）。
     * 界面直接调它，就等于让 UI 摸快捷栏 —— 而「发到哪几格」的口径
     * （{@code CardLoadout.HOTBAR_SLOTS = 9}，2~9 共 8 格）必须只有一处。
     *
     * @param side 哪一方确认了（会话外的阵营永远不会走到这里）
     * @param deck 玩家排好序的 8 张卡（顺序 = 界面里的排列，{@code docs/02} §6.2 的 R16/R18）
     */
    default void onDeckConfirmed(Side side, java.util.List<io.mczju.infernalclash.card.Card> deck) {
    }

    /** 对局停止（收尾）：实现方在这里回收自己拥有的一切（实体、任务、账本）。 */
    default void onStop(String reason) {
    }

    /**
     * ★★ <b>「骑手真的打到了敌人」的阳性信号</b>（2026-10-18 新增）。
     *
     * <p>起因：{@code docs/19} 第 106 行前储君要求「**攻击到敌人时清除**速度加成」，
     * 而在此之前 {@code battle/RampBook} 只能从「骑手**换了目标**」反推
     * ⇒ 持续啃同一个目标（例如一直砍信标）时永不清零。
     * 伤害事件是**唯一**能证明"真的打到了"的地方，由监听器转给本钩子。
     *
     * @param damager 造成伤害的实体（调用方已确认它**在本局账本里**；骑手 / 载具都可能）
     */
    default void onRiderHit(org.bukkit.entity.Entity damager) {
    }

    /**
     * ★ 把多个钩子**串成一个**（按给定顺序依次调用）。
     *
     * <h2>为什么要组合而不是让实现方互相继承</h2>
     * P3 的目标物、P4 的装备发放、P5 的战斗循环、P6 的法术……各自关心不同的事，
     * 让它们互为父子会把"谁先谁后"埋进继承链里，且**任一处抛异常会带走全部后续钩子**。
     * ⇒ 本方法按顺序调用，并**逐个兜住异常**（一处失败不影响其余），
     * 这样「新增一个系统」= 往组合里加一个实现，改动面最小。
     */
    static MatchHooks combine(MatchHooks... hooks) {
        MatchHooks[] parts = hooks == null ? new MatchHooks[0] : hooks.clone();
        return new MatchHooks() {
            @Override
            public MatchSignal signal() {
                for (MatchHooks part : parts) {
                    if (part == null) {
                        continue;
                    }
                    try {
                        MatchSignal signal = part.signal();
                        if (signal != null && signal != MatchSignal.NONE) {
                            return signal;   // 第一个给出事实的钩子说了算（当前只有目标物会给）
                        }
                    } catch (Throwable ignored) {
                        // 单个钩子出错不能影响其它钩子；下一 tick 还会再问一次
                    }
                }
                return MatchSignal.NONE;
            }

            @Override
            public void onTick(MatchPhase phase, int battleTick) {
                forEach(part -> part.onTick(phase, battleTick), parts);
            }

            @Override
            public void onPhaseChanged(MatchPhase from, MatchPhase to) {
                forEach(part -> part.onPhaseChanged(from, to), parts);
            }

            // ★★ 2026-10-16 第八轮新增的两个回调也必须**逐个转发**：
            //    组合器漏掉一个 = 该回调在生产路径上永远不触发（而编译、既有测试都不会发现）
            @Override
            public void onPrepPhaseStarted() {
                forEach(MatchHooks::onPrepPhaseStarted, parts);
            }

            @Override
            public void onDeckConfirmed(Side side,
                                        java.util.List<io.mczju.infernalclash.card.Card> deck) {
                forEach(part -> part.onDeckConfirmed(side, deck), parts);
            }

            @Override
            public void onStop(String reason) {
                forEach(part -> part.onStop(reason), parts);
            }
        };
    }

    /** 逐个调用并兜异常（见 {@link #combine} 的说明）。 */
    private static void forEach(java.util.function.Consumer<MatchHooks> action, MatchHooks... parts) {
        for (MatchHooks part : parts) {
            if (part == null) {
                continue;
            }
            try {
                action.accept(part);
            } catch (Throwable ignored) {
                // 同上：一处失败不阻断其余
            }
        }
    }
}
