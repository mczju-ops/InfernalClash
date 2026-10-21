package io.mczju.infernalclash.battle;

/**
 * ★★★ <b>「玩家造成的伤害，哪些该拦」的判据（纯函数）</b>
 * —— 2026-10-17 第十轮新增，缺陷 P1-a（「火球术和万箭齐发没有伤害」）与 C13 的共同落点。
 *
 * <h2>★★ 它修的是什么（一眼看穿的接线错误）</h2>
 * {@code docs/02} §5（【Q22 + C13 已裁定】）逐字写着：
 * 「★ <b>玩家只能通过卡牌影响战场 —— 不能用手 / 物品 / 弓攻击战场单位</b>」。
 * 而 {@code PlayerDamageGuard} 原先的判据只有一句 {@code event.getDamager() instanceof Player}，
 * 且 {@code EffectApplier#damage} 是 {@code target.damage(amount, caster)} ——
 * <b>卡牌法术的伤害被打上了同一个"玩家造成"的标签</b> ⇒ 于是：
 * <ul>
 *   <li>{@code PlayerDamageGuard} 把<b>法术伤害也拦掉了</b>（现场日志
 *       {@code 施放法术 arrow_rain ⇒ 命中 3 个目标 / 应用 3 次效果} 却没有任何伤害 ——
 *       那 3 次都被取消在本监听器里）；</li>
 *   <li>反过来说，<b>弓箭</b>造成的伤害 {@code getDamager()} 是那支箭（不是玩家）
 *       ⇒ 旧判据**根本没拦住弓**（C13 明写要拦「弓」）。</li>
 * </ul>
 *
 * <h2>判据（三分）</h2>
 * <pre>
 *   不是玩家造成的                        ⇒ ALLOW（召唤物之间、别的插件，一律不碰）
 *   是玩家造成的 **卡牌法术**（MAGIC + 直接实体就是这位玩家）⇒ ALLOW（这正是 C13 允许的那条路）
 *   其余玩家造成的（近战 / 弓 / 三叉戟 / 投掷药水…）        ⇒ CANCEL
 * </pre>
 * ★ 为什么用「伤害类型 = MAGIC **且** 直接实体 = 玩家」两个条件：
 * <ul>
 *   <li>近战是 {@code PLAYER_ATTACK}、弓箭是 {@code ARROW}（直接实体是箭）⇒ 都被拦；</li>
 *   <li>玩家<b>扔出的药水</b>虽然也是 MAGIC，但直接实体是药水瓶（不是玩家）⇒ 照拦
 *       （它不是卡牌）；</li>
 *   <li>我们的卡牌伤害由 {@code EffectApplier} 显式构造成
 *       {@code MAGIC + causingEntity=直接实体=施放者} ⇒ 唯一命中这一格。</li>
 * </ul>
 * ★ 纯类：只吃三个布尔/字符串 ⇒ 可离线逐格断言（含阳性与阴性对照）。
 */
public final class PlayerAttackRule {

    /** 卡牌法术伤害用的伤害类型键（= {@code DamageType.MAGIC} 的 namespaced key）。 */
    public static final String MAGIC_KEY = "magic";

    /** 处置：拦掉 / 放行。 */
    public enum Verdict {
        /** 取消这次伤害（C13：玩家不得用非卡牌手段伤害战场单位）。 */
        CANCEL,
        /** 放行（不是玩家干的，或它就是卡牌法术的伤害）。 */
        ALLOW
    }

    private PlayerAttackRule() {
    }

    /**
     * 该不该取消这次伤害。
     *
     * @param causedByPlayer    伤害的「造成实体」是不是玩家
     *                          （Bukkit：{@code DamageSource#getCausingEntity()}；
     *                          ★ 弓箭的造成者是射手 ⇒ 这条同时覆盖了「弓」）
     * @param directEntityIsPlayer 直接实体是不是玩家（近战 true；弓箭是箭 false；
     *                          卡牌法术由 {@code EffectApplier} 设为 true）
     * @param damageTypeKey     伤害类型的键（如 {@code magic} / {@code player_attack} / {@code arrow}）；
     *                          拿不到 ⇒ {@code null}
     */
    public static Verdict verdict(boolean causedByPlayer, boolean directEntityIsPlayer,
                                  String damageTypeKey) {
        if (!causedByPlayer) {
            return Verdict.ALLOW;
        }
        return isCardSpellDamage(causedByPlayer, directEntityIsPlayer, damageTypeKey)
                ? Verdict.ALLOW : Verdict.CANCEL;
    }

    /** 这是不是「卡牌法术」的伤害（C13 明确允许的那条路）。 */
    public static boolean isCardSpellDamage(boolean causedByPlayer, boolean directEntityIsPlayer,
                                            String damageTypeKey) {
        return causedByPlayer && directEntityIsPlayer && MAGIC_KEY.equals(damageTypeKey);
    }

    /** 中文说明（日志用）。 */
    public static String describe(Verdict verdict, boolean causedByPlayer,
                                  boolean directEntityIsPlayer, String damageTypeKey) {
        if (verdict == Verdict.ALLOW) {
            return isCardSpellDamage(causedByPlayer, directEntityIsPlayer, damageTypeKey)
                    ? "卡牌法术的伤害（放行）" : "不是玩家造成的（放行）";
        }
        return "玩家用卡牌以外的手段攻击战场单位（取消；来源 " + damageTypeKey + "）";
    }
}
