package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.match.Side;

import java.util.Objects;

/**
 * ★★★ <b>敌我判定（纯函数）</b> —— 2026-10-17 第十轮新增，用户实机缺陷 P0-a 的落点。
 *
 * <h2>它修的是什么（现场证据，不是推测）</h2>
 * 用户原话：「<b>防御塔会攻击己方玩家，包括己方的防御塔</b>」（留档 {@code docs/24} §7.1）。
 * 现场 {@code logs/latest.log} 18:21:30 原文：
 * <pre>
 *   [IC] 防御塔光束命中 2 个敌方单位（matchId=default，每 10 tick 一轮，单次 2 点）
 *   YamatoMaya was slain by 蓝方防御塔·左          ← 蓝方玩家被**蓝方**塔打死
 * </pre>
 * 根因在 {@code objective/ObjectiveService#tickBeams}：它只用
 * {@code ownerSideOf(living) == tower.side()} 判「跳过」。而<b>玩家从不进账本</b>
 * （R42/T3 的结构保证）⇒ 玩家的 {@code owner} 是 {@code null} ⇒
 * {@code null == Side.BLUE} 为 <b>false</b> ⇒ <b>己方玩家被当成敌方</b>。
 * 那句 javadoc 原写着「读不到就返回 null（调用方会把 null 当作『不是敌方』而跳过）」——
 * <b>代码从来没做过这件事</b>（这就是"注释与实现不一致"导致的一条真缺陷）。
 *
 * <h2>判据（就这一条，红蓝对称）</h2>
 * <pre>
 *   两侧阵营**都已知**且不同  ⇒ FOE（可以打）
 *   其余一切（同侧 / 有一侧未知 / 自己是 null）⇒ **不许打**
 * </pre>
 * ★ <b>失败方向</b>：「未知归属」一律按<b>不是敌方</b>处理 —— 宁可少打一次，
 * 也绝不再出现「把同侧当敌人」。这正是 {@code docs/05} C13 / {@code docs/11} 那句
 * 「归属判定不能靠类型」的对称面：<b>归属判不出来时也不许猜</b>。
 *
 * <p>★ 纯类：不碰 Bukkit、不碰实体（只吃两个枚举）⇒ 可离线逐格断言，
 * 已登记进 {@code guard/PolicyPurityGuardTest} 与 {@code guard/BytecodeScopeGuardTest}。
 */
public final class SideRule {

    /** 一个目标相对某人（一座塔 / 一个召唤物）的归属三态。 */
    public enum Affiliation {
        /** 归属未知（不在账本里、也不在本局名册里）—— ★ **不是敌方**。 */
        UNKNOWN,
        /** 同侧。 */
        ALLY,
        /** 敌方。 */
        FOE
    }

    private SideRule() {
    }

    /**
     * 目标相对 {@code self} 的归属。
     *
     * @param self  我方阵营（塔的阵营 / 召唤物的阵营）；{@code null} ⇒ 一切皆 {@link Affiliation#UNKNOWN}
     * @param other 目标阵营；{@code null} ⇒ {@link Affiliation#UNKNOWN}
     */
    public static Affiliation affiliation(Side self, Side other) {
        if (self == null || other == null) {
            return Affiliation.UNKNOWN;
        }
        return self == other ? Affiliation.ALLY : Affiliation.FOE;
    }

    /** ★ 是否 **确定** 是敌方（唯一允许开火的判据）。 */
    public static boolean isFoe(Side self, Side other) {
        return affiliation(self, other) == Affiliation.FOE;
    }

    /**
     * ★ 防御塔的光束**是否允许**打这个目标。
     *
     * <p>与 {@link #isFoe} 是同一条判据的两个名字：写成两个方法是为了让
     * 「塔的光束」这个调用点的意图在代码里可读（将来若某张卡允许打友军，
     * 只需要在这里加一格，而不是去改 {@code ObjectiveService} 的循环）。
     */
    public static boolean mayBeamHit(Side towerSide, Side targetSide) {
        return isFoe(towerSide, targetSide);
    }

    /** 归属三态的中文说明（日志用；★ 带「未知 ≠ 敌方」的说明）。 */
    public static String describe(Side self, Side other) {
        Affiliation affiliation = affiliation(self, other);
        return switch (affiliation) {
            case FOE -> "敌方";
            case ALLY -> "同侧";
            case UNKNOWN -> "归属未知（不视为敌方）";
        };
    }

    /** 从账本/名册反查出来的阵营是否为同一对局内可辨认的阵营（自检用）。 */
    public static boolean sameSide(Side a, Side b) {
        return Objects.equals(a, b);
    }
}
