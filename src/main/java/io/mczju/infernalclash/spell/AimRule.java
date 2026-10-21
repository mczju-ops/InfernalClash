package io.mczju.infernalclash.spell;

import io.mczju.infernalclash.card.Spell;

/**
 * ★★★ <b>「准星上瞄到了什么」以及「这次施放该不该放行」</b> —— 纯函数
 * （2026-10-18 新增，治愈术的落点）。
 *
 * <h2>出处（用户裁定，逐字）</h2>
 * 「**必须用准星指着友方，如果没有选到目标就不使用，不消耗费用和冷却**」。
 *
 * <h2>为什么这件事必须放在**扣费之前**</h2>
 * 用户的三个要求是<b>同一条规则的三面</b>：
 * 不消耗费用 · 不进冷却 · 没有目标就什么都不做。
 * 而项目里「扣费」在 {@code game/CardCastListener} 的最前面，冷却紧随其后
 * ⇒ 若判据放在 {@code SpellCaster#cast}（扣费之后），只能做到"退费"，
 * 那仍然会闪一次冷却遮罩、并留下一条"施放失败"日志 —— 与用户要的"不使用"不是一回事。
 * ⇒ 判据做成**纯函数**，由 {@code CardCastListener} 在扣费**之前**调用。
 *
 * <h2>为什么纯（不碰 {@code getTargetEntity}）</h2>
 * 准星 Raycast 必须真服务端（离线拿不到世界与实体）⇒ 本类只回答
 * 「<b>给定瞄到了什么 + 这一档需不需要友方</b>，该不该放行」，
 * 而"瞄到了什么"由 {@link SpellCaster#classifyAim} 采集。
 * 这样整张判定表都能离线逐格断言（{@code AimRuleTest}）。
 *
 * <h2>★★ 只收紧 {@link Spell.Targeting#ALLY_TARGET} 这一档</h2>
 * 其它需要准星的档位（{@code ENEMY_TARGET} / {@code ANY_TARGET}）**行为一字未变** ——
 * 用户只对治愈术提出了这条要求，改成全局收紧会顺带改变 4 张卡的手感，
 * 那不是被授权的改动范围。
 */
public final class AimRule {

    /** 准星上**实际**有什么（由 {@link SpellCaster#classifyAim} 采集）。 */
    public enum Aimed {
        /** 什么都没瞄到（射线 32 格内没有实体）。 */
        NOTHING,
        /** 瞄到了一个非生物（掉落物 / 展示实体 / 弹射物…）。 */
        NOT_LIVING,
        /** 瞄到了一个生物，但它已经失效 / 死亡。 */
        DEAD,
        /** 瞄到了一个**不是本场友方**的生物（敌方召唤物 / 塔 / 路人）。 */
        NOT_ALLY,
        /**
         * ★ 瞄到了一个本场友方**建筑**（信标 / 防御塔 / 打了 {@code building} 的召唤物）。
         *
         * <p>★★ 2026-10-19 用户裁定：「治愈术…**瞄准/目标过滤排除防御塔**」。
         * 建筑不是合法治疗目标 ⇒ 这一档**与敌方一样被拒绝**，但提示语要说清原因
         * （玩家瞄着自家塔按右键，最想知道的是"为什么不行"，不是"你没瞄到人"）。
         */
        BUILDING,
        /** 瞄到了一个本场**友方**生物（✓ 唯一放行的情形）。 */
        ALLY;

        /** 面向玩家的中文说明（拼进提示语，必须是玩家看得懂的话）。 */
        public String label() {
            return switch (this) {
                case NOTHING -> "准星上没有目标";
                case NOT_LIVING -> "准星上的不是生物";
                case DEAD -> "准星上的单位已经死亡";
                case NOT_ALLY -> "准星上的是敌方单位";
                case BUILDING -> "建筑不能被治疗";
                case ALLY -> "友方单位";
            };
        }
    }

    /** 判定结果。 */
    public enum Decision {
        /** 这一档不需要准星（或不需要友方）⇒ 放行，行为与以前一致。 */
        PROCEED,
        /** 需要友方准星，且确实瞄到了友方 ⇒ 放行。 */
        PROCEED_AIMED_ALLY,
        /** ★ 拒绝：没有瞄到友方。**调用方必须在扣费与上冷却之前返回**。 */
        REFUSE_NO_ALLY_TARGET;

        /** 是否放行。 */
        public boolean allowed() {
            return this != REFUSE_NO_ALLY_TARGET;
        }
    }

    private AimRule() {
    }

    /**
     * 判定表（只有一张，逐格可复述）。
     *
     * <ul>
     *   <li>{@code targeting} 不是 {@link Spell.Targeting#ALLY_TARGET}
     *       ⇒ {@link Decision#PROCEED}（<b>不</b>因为这次改动收紧别的卡）；</li>
     *   <li>{@code targeting} 是 {@code ALLY_TARGET} 且 {@link Aimed#ALLY}
     *       ⇒ {@link Decision#PROCEED_AIMED_ALLY}；</li>
     *   <li>其余一切（没有 / 非生物 / 已死 / 敌方 / ★ **建筑**）⇒ {@link Decision#REFUSE_NO_ALLY_TARGET}。</li>
     * </ul>
     *
     * ★ {@code targeting} 为 {@code null}（卡坏了）⇒ {@link Decision#PROCEED}：
     * 这种"配置读不出来"的情形不该由观感/手感层拦，{@code SpellCaster} 自己会拒绝并说明。
     */
    public static Decision decide(Spell.Targeting targeting, Aimed aimed) {
        if (targeting != Spell.Targeting.ALLY_TARGET) {
            return Decision.PROCEED;
        }
        if (aimed == Aimed.ALLY) {
            return Decision.PROCEED_AIMED_ALLY;
        }
        return Decision.REFUSE_NO_ALLY_TARGET;
    }

    /** 便捷判定（只要布尔）。 */
    public static boolean allowed(Spell.Targeting targeting, Aimed aimed) {
        return decide(targeting, aimed).allowed();
    }

    /**
     * 拒绝时给玩家看的那句话里的「实际瞄到了什么」。
     *
     * <p>★ {@code null}（调用方没采集）⇒ 「准星上没有目标」（失败方向 = 最保守的说法，
     * 绝不谎称"是敌方"）。
     */
    public static String aimedLabel(Aimed aimed) {
        return aimed == null ? Aimed.NOTHING.label() : aimed.label();
    }
}
