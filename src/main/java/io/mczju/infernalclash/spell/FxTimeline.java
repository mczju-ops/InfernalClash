package io.mczju.infernalclash.spell;

import io.mczju.infernalclash.card.Spell;

/**
 * ★★ <b>法术特效的时间轴</b>（2026-10-18 新增）—— 纯函数，可离线单测。
 *
 * <h2>它修的两条用户反馈（逐字）</h2>
 * <ol>
 *   <li>「**法术的环状特效不够明显，请你加粗并延长持续时间**」
 *       ⇒ 环不再是"撒一次就没了"：每 {@value #PERIOD_TICKS} tick 补一次脉冲，
 *       并且每次脉冲每个格撒 {@value #PARTICLES_PER_POINT} 点（加粗）；</li>
 *   <li>「**信息素这种持续召唤的法术更是需要一直显示粒子效果直到法术效果结束**」
 *       ＋ 「**掘墓人 …召唤前持续放泥土粒子**」
 *       ⇒ 特效的<b>持续期</b>由卡自己声明的效果时长决定：{@code SUMMON_PERIODICALLY}
 *       读 {@code duration-ticks}、{@code SUMMON_DELAYED} 读 {@code delay-ticks}。</li>
 * </ol>
 *
 * <h2>口径（三条，全部可复述）</h2>
 * <ul>
 *   <li><b>持续期</b>（{@link #persistTicks}）= 所有「有时长的召唤类效果」里
 *       <b>最长的那个</b>；一次施放只播一条时间轴，而不是每条效果各播一条
 *       （否则信息素这种"落点 + 周期召唤"会叠出两层不一样的环）；</li>
 *   <li><b>脉冲次数</b>（{@link #beats}）= ⌈持续期 / {@value #PERIOD_TICKS}⌉，
 *       并夹在 [{@value #MIN_BEATS}, {@value #MAX_BEATS}] 之间 ——
 *       ★ 下限保证"即使法术没有任何时长（火球术）也亮够 2 秒"（这就是"延长持续时间"）；
 *       ★ 上限是<b>护栏</b>：卡里把 {@code duration-ticks} 写成天文数字时，
 *       视觉任务必须有界（一个跑一小时的粒子任务会把主线程拖住）。</li>
 *   <li><b>只认「自己声明了时长」的这几类效果</b>：{@code SUMMON_DELAYED} /
     *       {@code SUMMON_PERIODICALLY} / ★ 1.1.0 新增的 {@code AREA_DAMAGE} 与 {@code PULL}
 *       ★ 刻意<b>不</b>扫 {@code ATTRIBUTE} / {@code POTION} 的 {@code duration-ticks} ——
 *       那会把"加速药水 10 秒"也变成一圈亮 10 秒的环，而用户要的是
 *       「持续<b>召唤</b>的法术一直显示」，不是"所有有时长的法术都一直显示"。</li>
 * </ul>
 *
 * <h2>本类不碰 Bukkit</h2>
 * 只读 {@link Spell.Effect} 的参数表（经 {@link EffectParams}）⇒ 必然可离线单测。
 */
public final class FxTimeline {

    /** 两次脉冲之间的间隔（tick）。5 tick = 0.25 秒 ⇒ 肉眼是"连续在亮"而不是"闪了几下"。 */
    public static final int PERIOD_TICKS = 5;

    /** 每个边界格每次脉冲撒几点（加粗用）。 */
    public static final int PARTICLES_PER_POINT = 3;

    /** 最少脉冲次数：一次性法术也要亮够 8 × 5 = 40 tick = 2 秒。 */
    public static final int MIN_BEATS = 8;

    /** 最多脉冲次数（护栏）：400 × 5 tick = 2000 tick ≈ 100 秒。 */
    public static final int MAX_BEATS = 400;

    private FxTimeline() {
    }

    /**
     * 这次施放的特效应该<b>持续多少 tick</b>（{@code 0} = 没有持续性效果，只有一次性脉冲）。
     *
     * <p>★ 读 {@code SUMMON_DELAYED.delay-ticks} 与 {@code SUMMON_PERIODICALLY.duration-ticks}，
     * 取最大值。缺键 / 不是数字 ⇒ 按 {@code 0} 处理（**绝不抛**：这是观感层，
     * 一个坏参数不该让法术施放失败）。
     */
    public static int persistTicks(Spell spell) {
        if (spell == null || spell.effects() == null) {
            return 0;
        }
        int longest = 0;
        for (Spell.Effect effect : spell.effects()) {
            if (effect == null) {
                continue;
            }
            int ticks = switch (effect.type()) {
                case SUMMON_DELAYED -> intParam(effect, "delay-ticks");
                case SUMMON_PERIODICALLY -> intParam(effect, "duration-ticks");
                // ★★★ 1.1.0（用户实机）：「**凋灵领域的粒子效果并没有持续 8 秒**」——
                //   它是一次施放就铺开、之后按 duration 每拍结算的"领域"，特效必须亮满整段。
                //   ★ 同一口径也适用于「大旋风」的牵引（40 tick = 2 秒的拉扯过程）。
                //   ★ 判据仍是"效果自己声明了时长"，不是"法术有伤害就亮很久"（后一条已被否决过）。
                case AREA_DAMAGE, PULL -> intParam(effect, "duration-ticks");
                default -> 0;
            };
            if (ticks > longest) {
                longest = ticks;
            }
        }
        return longest;
    }

    /**
     * 要跑几次脉冲。
     *
     * @param persistTicks {@link #persistTicks} 的结果（{@code 0} = 没有持续性效果）
     * @return 夹在 [{@value #MIN_BEATS}, {@value #MAX_BEATS}] 之间的次数
     */
    public static int beats(int persistTicks) {
        int needed = persistTicks <= 0 ? 0 : (persistTicks + PERIOD_TICKS - 1) / PERIOD_TICKS;
        if (needed < MIN_BEATS) {
            return MIN_BEATS;
        }
        return Math.min(needed, MAX_BEATS);
    }

    /** 便捷入口：直接按法术算脉冲次数。 */
    public static int beats(Spell spell) {
        return beats(persistTicks(spell));
    }

    /**
     * ★★★ <b>「按一条能力的冷却算出该重画几帧」</b>（2026-10-18 新增）—— 纯函数。
     *
     * <h2>为什么必须有它（这是现场一条真实的空指针）</h2>
     * 能力的范围环是**持续重画**的（用户：「蒸汽精灵的友方 buff 光环也需要实刻显示光环范围」），
     * 帧数按这条能力自己的 {@code cooldown-ticks} 算 —— 而
     * <b>{@code Card.Ability#cooldownTicks()} 是可空的</b>（{@code ON_SUMMON} 这类
     * 一次性能力根本没有冷却）。旧实现直接 {@code Math.max(0, ability.cooldownTicks())}
     * ⇒ 对 <b>null</b> 拆箱 ⇒ NPE ⇒ 整段表现层被 try/catch 吞掉
     * ⇒ <b>海王登场的闪电、粒子、音效全都没了</b>（现场日志原文：
     * {@code 能力的表现层失败 … Cannot invoke "java.lang.Integer.intValue()" because the return
     * value of "Card$Ability.cooldownTicks()" is null}）。
     *
     * <p>⇒ 判据搬到这里（纯函数、可离线逐格断言），渲染层只负责"按它去排任务"。
     *
     * @param cooldownTicks 能力的冷却 tick（<b>可空</b>：{@code null} = 没有冷却 ⇒ 只画 1 帧）
     * @param periodTicks   重画间隔（tick；{@code <= 0} ⇒ 只画 1 帧）
     * @param maxBeats      护栏上限（{@code <= 0} ⇒ 只画 1 帧）
     * @return 帧数（<b>恒 ≥ 1</b>：任何情况下至少画一帧，绝不"什么都不画"）
     */
    public static int beatsFor(Integer cooldownTicks, int periodTicks, int maxBeats) {
        if (periodTicks <= 0 || maxBeats <= 0) {
            return 1;
        }
        int cooldown = cooldownTicks == null ? 0 : Math.max(0, cooldownTicks);
        if (cooldown <= 0) {
            return 1;   // 没有冷却 ⇒ 只闪一下（★ 不是 0 帧）
        }
        long beats = ((long) cooldown + periodTicks - 1) / periodTicks;   // ⌈cooldown / period⌉
        return (int) Math.max(1, Math.min(maxBeats, beats));
    }

    /** 这次施放的特效总共亮多少 tick（{@code = beats × PERIOD_TICKS}）。 */
    public static int totalTicks(Spell spell) {
        return beats(spell) * PERIOD_TICKS;
    }

    /** 这条法术的特效是不是"要一直亮到效果结束"（信息素 / 掘墓人这一档）。 */
    public static boolean persists(Spell spell) {
        return persistTicks(spell) > 0;
    }

    /** 读一个整数参数；缺失 / 非数字 / 非有限值 ⇒ {@code 0}（观感层绝不抛）。 */
    private static int intParam(Spell.Effect effect, String key) {
        double value;
        try {
            value = EffectParams.optionalNumber(effect, key, 0);
        } catch (RuntimeException e) {
            return 0;
        }
        if (!Double.isFinite(value) || value <= 0) {
            return 0;
        }
        return (int) Math.min(value, (double) Integer.MAX_VALUE);
    }
}
