package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.Spell;

/**
 * ★★ <b>「这张卡会不会在死亡时复活」的判据</b>（纯函数，可离线单测）—— 2026-10-18 新增。
 *
 * <h2>为什么需要它</h2>
 * 全卡审计发现美西螈的「假死复活一次」**从来没有生效**：
 * {@code spell/AppliedEffects#consumeRevive} 被写好了、却<b>没有生产调用方</b>；
 * 而 {@code spell/EffectApplier#revive} 只在 {@code REVIVE} 效果被施加时打一个标记，
 * 且那次施加发生在 {@code ON_DEATH} 的派发里 —— 此时实体已经是"将死"状态。
 * ⇒ 3 费稀有卡等于一只白板。
 *
 * <h2>本类的口径</h2>
 * 复活是**卡的能力**，所以判据直接读卡数据：只要这张召唤卡有一条
 * <b>{@code ON_DEATH} 触发器 + 含 {@code REVIVE} 效果</b>的能力，它就该在第一次死亡时被拦下。
 * 次数上限 = 1 由消费方（{@code match/UnitLifecycleListener}）的一次性集合保证。
 *
 * <p>★ 这样判的好处：**不需要**把"复活标记"跨对象传递（那个 {@code AppliedEffects}
 * 在能力运行时里是**每次效果新构造**的，标记本来就会丢）。卡数据本身就是唯一事实来源。
 */
public final class ReviveRule {

    private ReviveRule() {
    }

    /** 这张卡是否「死亡时复活一次」。 */
    public static boolean revivesOnDeath(Card card) {
        if (card == null || card.summon() == null || card.summon().abilities() == null) {
            return false;
        }
        for (Card.Ability ability : card.summon().abilities()) {
            if (ability == null || ability.trigger() != Card.Trigger.ON_DEATH) {
                continue;
            }
            if (ability.effects() == null) {
                continue;
            }
            for (Spell.Effect effect : ability.effects()) {
                if (effect != null && effect.type() == Spell.EffectType.REVIVE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 复活后的血量。
     *
     * <p>★ 用户 2026-10-17 裁定「**美西螈复活应该满血**」⇒ 传 {@code maxHealth} 即可。
     * 这里只做一件事：把非法值兜到 1.0（{@code EntityDeathEvent#setReviveHealth}
     * 对非正数会抛）。
     */
    public static double reviveHealth(double maxHealth) {
        return maxHealth > 0.0 && !Double.isNaN(maxHealth) ? maxHealth : 1.0;
    }
}
