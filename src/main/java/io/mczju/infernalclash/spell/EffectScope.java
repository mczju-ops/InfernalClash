package io.mczju.infernalclash.spell;

import io.mczju.infernalclash.card.Spell;

import java.util.EnumSet;
import java.util.Set;

/**
 * ★★★ <b>「没有实体目标时，这个效果还能不能跑」</b>（纯函数）
 * —— 2026-10-17 第十轮新增，缺陷 P1-a（「火球术和万箭齐发没有效果」）的根因之一。
 *
 * <h2>它修的是什么（现场日志，不是推测）</h2>
 * 现场 {@code logs/latest.log}（18:24:01~18:24:20，玩家 BLUE 连着放火球术）原文：
 * <pre>
 *   [IC] YamatoMaya（BLUE）施放法术 fireball ⇒ 覆盖 13 格 / 命中 0 个目标 / <b>应用 2 次效果</b>
 *   [IC] YamatoMaya（BLUE）施放法术 fireball ⇒ 覆盖 13 格 / 命中 0 个目标 / <b>应用 2 次效果</b>
 *   [IC] YamatoMaya（BLUE）施放法术 arrow_rain ⇒ 覆盖 49 格 / 命中 1 个目标 / 应用 1 次效果
 * </pre>
 * 「命中 0 个目标」却「应用 2 次效果」= 火球术的两条效果（DAMAGE + KNOCKBACK）
 * <b>被打到了施放者自己身上</b>：{@code SpellCaster} 在 {@code targets.isEmpty()} 时
 * 把**施放者当作"目标"**传进 {@code EffectApplier}（那段代码的理由是「召唤类效果也要生效一次」，
 * 而召唤用的是 {@code origin}，不需要目标实体）。
 * ⇒ 后果：对空放一发火球 = <b>打自己 20 点 + 把自己击退</b>；万箭齐发同理（10 点）。
 * 玩家的体验正是「释放后没有特效和伤害」（伤害跑到自己身上、敌人一点没掉）。
 *
 * <h2>判据</h2>
 * 只有**设计上就不需要目标实体**的效果，才允许在「一个目标都没命中」时跑一次：
 * <ul>
 *   <li>{@link Spell.EffectType#SUMMON} / {@link Spell.EffectType#SUMMON_DELAYED}
 *       —— 召唤中心是 {@code origin}（{@code docs/07} §6.4）；</li>
 *   <li>{@link Spell.EffectType#CAST_SPELL} —— 嵌套施放用 {@code origin} 与 {@code depth}；</li>
 *   <li>{@link Spell.EffectType#REFRESH_COOLDOWN} / {@link Spell.EffectType#GRANT_XP}
 *       / {@link Spell.EffectType#COOLDOWN_REDUCTION} —— 作用于"施放者/本卡"，无目标。</li>
 * </ul>
 * 其余（伤害 / 治疗 / 药水 / 属性 / 眩晕 / 点燃 / 击退 / 持续推动 / 假死复活）**必须有目标实体**
 * ⇒ 一个都没命中就<b>什么都不做</b>（并把「跳过了几条」写进施放报告，可对账）。
 *
 * <p>★ 纯类（只依赖卡牌枚举）⇒ 可离线穷举断言；已登记进两处守卫。
 * {@code Spell.EffectType} 将来新增一类时，下面的 {@code switch} 会**编译期**报错
 * （没有 default 分支）⇒ 强制作者先回答"它需不需要目标"，不允许静默漏掉。
 */
public final class EffectScope {

    /** 不需要目标实体、可以只靠 {@code origin} 跑一次的效果。 */
    private static final Set<Spell.EffectType> WITHOUT_TARGET =
            EnumSet.of(Spell.EffectType.SUMMON,
                    Spell.EffectType.SUMMON_DELAYED,
                    Spell.EffectType.SUMMON_PERIODICALLY,
                    Spell.EffectType.CAST_SPELL,
                    Spell.EffectType.REFRESH_COOLDOWN,
                    Spell.EffectType.GRANT_XP,
                    Spell.EffectType.COOLDOWN_REDUCTION,
                    // ★ 2026-10-19「回响」：复读读的是**本局状态**（上一张牌），不需要目标实体。
                    Spell.EffectType.REPLAY_LAST_CARD);

    private EffectScope() {
    }

    /**
     * 这个效果在「一个实体目标都没命中」时，是否还允许执行一次。
     *
     * <p>★ 写成 {@code switch} 而**不写 default**：新增效果类型时编译不过，
     * 逼作者显式回答（本项目「第二处白名单」的教训：静默漏掉最难查）。
     */
    public static boolean runsWithoutTarget(Spell.EffectType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case SUMMON, SUMMON_DELAYED, SUMMON_PERIODICALLY, CAST_SPELL,
                 REFRESH_COOLDOWN, GRANT_XP, COOLDOWN_REDUCTION, REPLAY_LAST_CARD -> true;
            // ★ SPAWN_FANGS 必须有目标（尖牙长在"目标脚下"）⇒ 一个都没命中就什么都不做。
            case DAMAGE, HEAL, POTION, ATTRIBUTE, STUN, IGNITE, KNOCKBACK, PUSH, REVIVE,
                 SPAWN_FANGS -> false;
        };
    }

    /** 只读清单（测试与文档用）。 */
    public static Set<Spell.EffectType> withoutTargetTypes() {
        return Set.copyOf(WITHOUT_TARGET);
    }

    /** 中文说明（施放报告的 {@code note} 里用）。 */
    public static String describeSkipped(Spell.EffectType type) {
        return type + " 需要目标实体，本次一个都没命中，跳过（绝不改打在施放者身上）";
    }
}
