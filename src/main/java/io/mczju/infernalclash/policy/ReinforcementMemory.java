package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.match.Side;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * ★★★ <b>1.1.0「援军」的状态机：每方「记录到的下一张牌」</b>
 * （纯类，可离线逐格断言；不碰服务端、不查实体）。
 *
 * <h2>卡面原文（{@code docs/1.1卡牌.txt}，逐字）</h2>
 * 「普通 <b>援军</b> 1 费 30s 法术：<b>在对战开始时，以及使用本卡牌后，记录你使用的下一张牌。</b>
 * 使用时，<b>重置记录的卡牌的冷却时间，然后清空记录。</b>」
 *
 * <h2>口径（用户 2026-10-26 逐条确认过）</h2>
 * <ol>
 *   <li><b>对战开始</b> ⇒ 每一方立刻进入「正在记录」状态（{@code watching = true}）；</li>
 *   <li><b>成功打出一张牌</b> ⇒ 若正在记录，就把这张牌记下来、并<b>停止记录</b>
 *       （下一个被记录的，要等援军用过之后才开始找）；</li>
 *   <li><b>打出援军</b> ⇒ 必须**已经有记录**，否则<b>拒绝施放</b>（不扣费、不进冷却）——
 *       用户逐字：「还没有任何记录时不可打出，同『回响』一样不可作为第一张牌打出」；</li>
 *   <li>援军真的打出去 ⇒ <b>先重置那张牌的冷却</b>（由调用方走 {@link CardCooldowns#reset}），
 *       然后清空记录并<b>重新开始记录</b>（回到 ②）；</li>
 *   <li><b>按阵营</b>各记一份（红蓝互不影响，与 {@code CastMemory} 同一口径）。</li>
 * </ol>
 *
 * <h2>为什么单独一个类（不复用 {@code CastMemory}）</h2>
 * {@link CastMemory} 记的是「上一张<b>已经</b>打出的牌」（回响要复读它），语义是**过去**；
 * 本类记的是「<b>下一张</b>要打出的牌」，语义是**未来**，而且带一个"正在看"的状态位。
 * 两者形状相似但判据不同 —— 合到一起就会在"援军重置了回响记录的那张牌"这种交叉场景里
 * 出现没人能说清的行为。
 *
 * <p>★ 任务无关：本类由 {@code MatchService} <b>每局 new 一个</b>持有 ⇒ 不需要显式清零
 * （{@link #clear()} 只留给探针/测试）。
 */
public final class ReinforcementMemory {

    /** 一方的状态：正在记录？记到了哪张？ */
    private static final class State {
        boolean watching;
        String recorded;

        State(boolean watching, String recorded) {
            this.watching = watching;
            this.recorded = recorded;
        }
    }

    private final Map<Side, State> states = new EnumMap<>(Side.class);

    /** 新的一局：两方都立刻进入「记录下一张牌」状态（卡面「对战开始时…记录」）。 */
    public ReinforcementMemory() {
        for (Side side : Side.values()) {
            states.put(side, new State(true, null));
        }
    }

    /**
     * 记一次**成功**的施放（★ 只记真的生效的牌 —— 与 {@code CastMemory} 同一条纪律）。
     *
     * <p>★ 调用方**不要**用它记「援军」自己：援军走 {@link #consumeAndWatch(Side)}，
     * 那一格是"清空 + 重新开始记录"，不是"把援军记成下一张"。
     *
     * @return 这一次真的被记为"下一张牌"了吗（没在记录 / 已经有记录 / 空 id ⇒ false）
     */
    public boolean noteCast(Side side, String cardId) {
        State state = stateOf(side);
        if (state == null || cardId == null || cardId.isBlank()) {
            return false;
        }
        if (!state.watching) {
            return false;   // 已经在等"援军"来兑现，不再改记录
        }
        state.recorded = cardId.trim();
        state.watching = false;
        return true;
    }

    /** 这一方当前记录到的卡（还没记录 ⇒ {@link Optional#empty()}）。 */
    public Optional<String> recordedOf(Side side) {
        State state = stateOf(side);
        return state == null || state.recorded == null
                ? Optional.empty() : Optional.of(state.recorded);
    }

    /** 援军现在能不能打出（= 已经有记录 ⇒ 才有"可以重置冷却"的对象）。 */
    public boolean canCast(Side side) {
        return recordedOf(side).isPresent();
    }

    /** 这一方是不是正在"记录下一张牌"（日志 / 探针用）。 */
    public boolean watching(Side side) {
        State state = stateOf(side);
        return state != null && state.watching;
    }

    /**
     * 打出援军的落点：<b>取走</b>当前记录、清空它，并<b>重新开始记录</b>。
     *
     * <p>★ 调用时机 = 援军**真的扣了费、进了冷却**之后（不是之前）：先取走会让"打了个空"
     * 把记录也吃掉。★ 调用方拿到返回值后自己去 {@code CardCooldowns#reset}。
     *
     * @return 被记录的那张卡的 id（没有记录 ⇒ {@link Optional#empty()}，此时不该调用）
     */
    public Optional<String> consumeAndWatch(Side side) {
        State state = stateOf(side);
        if (state == null || state.recorded == null) {
            return Optional.empty();
        }
        String taken = state.recorded;
        state.recorded = null;
        state.watching = true;
        return Optional.of(taken);
    }

    /** 清空两方状态（★ 正常路径不用：每局一个新实例；留给探针与测试）。 */
    public void clear() {
        for (State state : states.values()) {
            state.watching = false;
            state.recorded = null;
        }
    }

    /** 一行摘要（日志用）。 */
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (Side side : Side.values()) {
            if (!out.isEmpty()) {
                out.append('｜');
            }
            State state = states.get(side);
            out.append(side).append('=')
                    .append(state == null || state.recorded == null
                            ? (state != null && state.watching ? "（等下一张）" : "（空）")
                            : state.recorded);
        }
        return out.toString();
    }

    private State stateOf(Side side) {
        return side == null ? null : states.get(side);
    }
}
