package io.mczju.infernalclash.policy;

/**
 * ★★★ <b>「现在是不是被眩晕着」的判据</b> —— 纯函数（2026-10-18 新增）。
 *
 * <h2>为什么需要它（这是最后一条未接线的口径）</h2>
 * {@code docs/19} 第 113 行（烈焰守卫）逐字：「**第一次锁定后不再改变目标**（LOCK_ON）
 * **直到被眩晕或目标死亡**」。
 * ⇒ 目标死亡那一半早就实现了（{@code TargetPolicy.refresh} 的 {@code currentStillValid}），
 * 而「**被眩晕**」这一半一直没接线 —— 因为项目里"眩晕"不是一个可查询的状态。
 *
 * <h2>怎么查（用 STUN 效果的**指纹**，不新增全局表）</h2>
 * 本项目的 {@code STUN} 效果落在两个原版机制上（见 {@code spell/EffectApplier#stun}）：
 * <ol>
 *   <li>{@code SLOWNESS} 药水效果，{@code amplifier} = {@value #STUN_AMPLIFIER}（= 等级 VII）；</li>
 *   <li>{@code Mob#setAware(false)}（停 AI），到期恢复。</li>
 * </ol>
 * ⇒ 「被眩晕」= <b>身上挂着 {@code SLOWNESS} 且 amplifier ≥ {@value #STUN_AMPLIFIER}</b>。
 *
 * <h2>★★ 为什么用一个纯常量而不是到处写字面量 6</h2>
 * 这是本判据**唯一可能静默失效**的地方：如果哪天有人把 {@code stun()} 里的 amplifier
 * 从 6 改成 5，这里却还写着 6 ⇒ <b>「眩晕时解锁」会悄悄失效</b>（而且不会有任何报错）。
 * ⇒ {@code EffectApplier#stun} 也读同一个常量（一处口径，改一处两边一起变）。
 *
 * <p>★ 已知的宽泛：别的插件给单位挂 {@code SLOWNESS VII+} 也会被当成"眩晕"。
 * 本局里没有别的来源会这么做（召唤物只受我们自己的效果影响），故接受；
 * 若将来真的冲突，再改成"显式标记"（代价是每个实体多一格 PDC 与清理逻辑）。
 */
public final class StunRule {

    /** 本项目 {@code STUN} 效果使用的 {@code SLOWNESS} 等级（MC 语义：amplifier = 等级 − 1）。 */
    public static final int STUN_AMPLIFIER = 6;

    private StunRule() {
    }

    /**
     * 这个 {@code SLOWNESS} 等级算不算"被眩晕"。
     *
     * @param slownessAmplifier 实体身上 {@code SLOWNESS} 的 amplifier；**没有这个效果 ⇒ 传 -1**
     * @return {@code true} = 视为眩晕中
     */
    public static boolean isStunned(int slownessAmplifier) {
        return slownessAmplifier >= STUN_AMPLIFIER;
    }

    /**
     * ★ 眩晕时**必须放弃锁定**。
     *
     * <p>把「锁还是不锁」与「有没有被眩晕」合成一格纯判据 —— 调用方（{@code SummonCombat}）
     * 只需要 {@code effectiveMode(mode, stunned())}，不必自己写 if
     * （那种写法在 {@code docs/27} 里已经有过一次「两处判据迟早分叉」的教训）。
     */
    public static TargetPolicy.Mode effectiveMode(TargetPolicy.Mode mode, boolean stunned) {
        if (mode == null) {
            return TargetPolicy.Mode.SWITCH;
        }
        // ★ 眩晕 ⇒ 当成 SWITCH（允许重选）；眩晕结束后 mode 恢复 LOCK_ON，
        //   于是它会锁住"眩晕期间选出来的那个目标"——正是文档要的语义。
        return stunned ? TargetPolicy.Mode.SWITCH : mode;
    }
}
