package io.mczju.infernalclash.policy;

/**
 * ★★★ <b>「穿透射线能打中谁」的判据（纯函数）</b> —— 2026-10-21 缺陷：
 * 「**持有矛、会发射弹射物、以及自定义穿透射线的召唤物打不动塔**」。
 *
 * <h2>缺陷：旧判据把目标物一起剔掉了</h2>
 * 旧实现（{@code battle/RayAttackService#fire}）是：
 * <pre>
 *   if (owner == null || owner.side() == mySide || owner.player() == null) {
 *       continue;   // 敌人之外一律跳过（玩家 / 目标物 / 非本场）
 *   }
 * </pre>
 * 而<b>目标物的归属玩家按设计就是 {@code null}</b>
 * （{@code objective/ObjectiveUnit} 里 {@code new Owner(matchId, side, null)}）
 * ⇒ 劲弩手 / 劫掠堡垒乘客的射线<b>永远打不到敌方塔与信标</b>，
 * 而近战召唤物能打（那条走原版攻击，不经本判据）—— 这正是用户看到的"只有远程打不动塔"。
 *
 * <h2>判据（三票否决 ＋ 两格准入）</h2>
 * <pre>
 *   否决：不在账本里（owner == null） / 目标是玩家 / 不是敌方阵营
 *   准入：归属玩家非空（= 召唤物） **或** 明确标记为目标物（{@code UnitRegistry#KEY_OBJECTIVE}）
 * </pre>
 *
 * <h2>★★ 为什么"目标物"必须是**明确标记**而不是启发式</h2>
 * 「{@code owner.player() == null}」这一格在本项目里<b>不止目标物</b>：
 * 同一个 {@code ObjectiveUnit#spawn} 还会给<b>展示实体</b>（{@code BlockDisplay}）打标 ——
 * 它同样是「player 为 null ＋ building = true ＋ 没有卡 id」，但它是**无敌的外观实体**，
 * 拿射线去"伤害"它纯属浪费帧（而且一旦哪天它不无敌就成了灵异伤害）。
 * ⇒ 只有载体带 {@code KEY_OBJECTIVE}（见 {@code UnitRegistry#tagObjective} 的 javadoc）。
 * ★ 所以「cardId 为空 + building」这种组合判据在这里是**错**的，不许用。
 *
 * <h2>收紧方向（铁律 10 的口径：宁可少打，绝不多打）</h2>
 * <ul>
 *   <li>玩家：<b>永不</b>可被射线命中（R42：任何一方、任何单位类型都不索敌玩家）；</li>
 *   <li>友方：永不（R27）；</li>
 *   <li>不在本场账本里的实体（别人的怪、服主的动物、展示实体）：永不 —— 判据只看账本 ＋ 明确标记，
 *       不查世界、不按类型猜；</li>
 *   <li>阵营未知（{@code side == null}）⇒ 不算敌方（与 {@code policy/SideRule} 同一口径：未知不开火）。</li>
 * </ul>
 *
 * <p>★ 纯类（只吃布尔）⇒ 可离线穷举断言（{@code policy/RayTargetRuleTest}）。
 * ★ 本类不许 import Bukkit（{@code PolicyPurityGuardTest} 钉着 policy 包的纯净）。
 */
public final class RayTargetRule {

    private RayTargetRule() {
    }

    /**
     * 这根射线能不能命中这个实体。
     *
     * @param inLedger       它在本场账本里吗（{@code OwnershipTable.Owner != null}）
     * @param enemySide      两侧阵营都已知且不同吗（未知一律算"不是敌人"）
     * @param isPlayer       目标是玩家吗
     * @param hasPlayerOwner 它的归属玩家非空吗（{@code true} = 召唤物；目标物与展示实体为 {@code false}）
     * @param isObjective    ★ 它带 {@code UnitRegistry#KEY_OBJECTIVE} 标记吗（只有目标物**载体**带）
     */
    public static boolean hittable(boolean inLedger, boolean enemySide, boolean isPlayer,
                                   boolean hasPlayerOwner, boolean isObjective) {
        if (!inLedger || isPlayer || !enemySide) {
            return false;
        }
        return hasPlayerOwner || isObjective;
    }

    /** 中文说明（排障 / 日志用：为什么这一格没被打）。 */
    public static String describe(boolean inLedger, boolean enemySide, boolean isPlayer,
                                  boolean hasPlayerOwner, boolean isObjective) {
        if (hittable(inLedger, enemySide, isPlayer, hasPlayerOwner, isObjective)) {
            return isObjective ? "敌方目标物（载体，明确标记）" : "敌方召唤物";
        }
        if (!inLedger) {
            return "不在本场账本里（不是我们的实体 / 展示实体）";
        }
        if (isPlayer) {
            return "玩家永不成为目标（R42）";
        }
        if (!enemySide) {
            return "不是敌方阵营（友方或阵营未知）";
        }
        return "既不是召唤物也不是目标物载体（例如展示实体）";
    }
}
