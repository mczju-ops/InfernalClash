package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.match.Side;

/**
 * ★★ <b>召唤物的「特性」判据</b>（纯函数，可离线单测）—— 2026-10-18 新增。
 *
 * <h2>为什么需要它</h2>
 * 2026-10-18 的全卡审计发现三张卡的机制「文档写了、代码一行没有」：
 * <ul>
 *   <li><b>末影人</b>（{@code docs/19} 第 88 行）：「<b>免疫远程伤害</b> + <b>禁止传送</b>」；</li>
 *   <li><b>唤魔者</b>（第 172 行）：「需要接管其召唤的<b>恼鬼</b>」＋坑栏「尖牙是 AoE 且<b>不分阵营</b>」；</li>
 *   <li><b>利剑嗡嗡</b>（第 63 行）：「{@code Vex#setLimitedLifetime(false)}」。</li>
 * </ul>
 * 判据放在这里，执行层（{@code battle/SummonTraitListener}）只做「读 PDC → 问本类 → 写事件」，
 * 这样「哪张卡免疫什么」可以**离线穷举**，不需要起服务器。
 *
 * <h2>★ 为什么用卡 id 而不是新增两个 YAML 键</h2>
 * 本轮的定位是**补欠账**，不是扩 schema：新增 {@code immune-ranged} / {@code no-teleport}
 * 两个键要动 {@code Card.Summon} 记录、{@code CardCatalog}、51 张卡的构造点与一批测试，
 * 而眼下只有**一张卡**需要它们。⇒ 先把语义固定在**纯函数 + 卡 id 表**里（可测、可读），
 * 将来若第二张卡需要同样的特性，再把这张表升格成键（那时改动面反而更清楚）。
 */
public final class SummonTraitRule {

    /** 免疫「投射物」伤害的卡（{@code docs/19} 第 88 行末影人「免疫远程伤害」）。 */
    private static final java.util.Set<String> IMMUNE_TO_PROJECTILES = java.util.Set.of("enderman");

    /** 禁止传送的卡（同一行「禁止传送」）。 */
    private static final java.util.Set<String> NO_TELEPORT = java.util.Set.of("enderman");

    /**
     * 收养恼鬼的最大半径（格）—— **我取的**：原版唤魔者召唤恼鬼就在自己身边 3 格内，
     * 取 16 是为了容忍它先飞出去一圈再被我们接管；★ 超过这个距离的恼鬼**不收养**
     * （宁可漏一只，也不把"别人的恼鬼"认成自己的 —— 铁律 10 第 3 问）。
     */
    public static final double VEX_ADOPT_RADIUS = 16.0;

    private SummonTraitRule() {
    }

    /** 这张卡是否免疫投射物（箭 / 投掷物）伤害？ */
    public static boolean ignoresProjectiles(String cardId) {
        return cardId != null && IMMUNE_TO_PROJECTILES.contains(cardId);
    }

    /** 这张卡是否禁止传送？ */
    public static boolean blocksTeleport(String cardId) {
        return cardId != null && NO_TELEPORT.contains(cardId);
    }

    /**
     * 尖牙（{@code EvokerFangs}）该不该伤到这个目标？
     *
     * <p>{@code docs/19} 第 172 行的坑栏：「尖牙是 AoE 且**不分阵营**」⇒ 我们**接管了的**
     * 唤魔者放出的尖牙，**不能**伤到同阵营的召唤物。
     *
     * @param fangOwnerSide 尖牙的施法者（唤魔者）所属阵营；{@code null} = 不是我们的唤魔者
     * @param victimSide    被打的实体所属阵营；{@code null} = 不是我们的召唤物（玩家 / 目标物照旧）
     * @return {@code true} = 应当**拦下**这次伤害
     */
    public static boolean friendlyFangDamage(Side fangOwnerSide, Side victimSide) {
        return fangOwnerSide != null && victimSide != null && fangOwnerSide == victimSide;
    }

    /**
     * 这只恼鬼该不该被收养？
     *
     * @param distance     与最近一只**已被我们接管的**唤魔者的距离（格）
     * @param alreadyOwned 这只恼鬼是不是已经在账本里
     */
    public static boolean shouldAdoptVex(double distance, boolean alreadyOwned) {
        return !alreadyOwned && distance >= 0.0 && distance <= VEX_ADOPT_RADIUS;
    }
}
