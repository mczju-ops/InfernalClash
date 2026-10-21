package io.mczju.infernalclash.policy;

import java.util.Comparator;
import java.util.List;

/**
 * ★★ <b>「护送型单位」的判据</b>（纯函数，可离线单测）—— 2026-10-18 新增。
 *
 * <h2>为什么需要它</h2>
 * 全卡审计发现 <b>蒸汽精灵</b>（{@code docs/19} 第 176 行）写的是
 * 「**只索敌己方目标**，**不攻击**，每 3s 为周围 3 格友方回血 + 抗性」，
 * 而 {@code battle/CombatDirector} 给<b>每一个</b>召唤物都只挑**敌方**目标
 * ⇒ 它会一路朝最近的敌人走（传说卡的"往敌人家门口送"）。
 *
 * <h2>口径</h2>
 * 护送型单位的"目标"不是敌人，而是**最近的同阵营单位**：
 * <ul>
 *   <li>目标只用于**移动**（{@code DriveGoal} 会朝它走）；</li>
 *   <li>**永不**进入攻击流程 —— 它的伤害/治疗全在 abilities 的周期任务里；</li>
 *   <li>{@code reassert}（把原版挑的目标纠正回我们的）对它**跳过**：
 *       它的目标本来就是友方，而 {@code TargetPolicy} 的 {@code CLEAR_ALLY}
 *       会把"友方目标"当成错误清掉。</li>
 * </ul>
 *
 * ★ 与 {@code SummonTraitRule} 一样，这里用**卡 id 表**而不是新增 YAML 键：
 * 眼下只有一张卡需要这个语义，加键要动 {@code Card.Summon} 记录与一批构造点。
 * 将来第二张卡需要时再升格成键（那时改动面反而更清楚）。
 */
public final class EscortRule {

    /** 护送型卡的 id（出厂只有蒸汽精灵）。 */
    private static final java.util.Set<String> ESCORTS = java.util.Set.of("steam_sprite");

    /**
     * 护送半径（格）—— **我取的**：蒸汽精灵的治疗光环是 3 格、雷法师级别的远程射程在 8~12，
     * 取 16 让它"跟着友军走"而不是"黏在一个人身上"；超出这个范围就重新挑最近的那个友军。
     */
    public static final double ESCORT_RADIUS = 16.0;

    private EscortRule() {
    }

    /** 这张卡是不是护送型（只跟友军、不主动打人）？ */
    public static boolean isEscort(String cardId) {
        return cardId != null && ESCORTS.contains(cardId);
    }

    /** 一个"可护送的友军"（纯数据，便于离线断言）。 */
    public record Ally(java.util.UUID id, double distanceSquared) {
    }

    /**
     * 从候选友军里挑**最近**的一个（距离相同按 UUID 稳定排序，保证确定性）。
     *
     * @return 最近的友军；空表 ⇒ {@code null}
     */
    public static Ally nearest(List<Ally> allies) {
        if (allies == null || allies.isEmpty()) {
            return null;
        }
        return allies.stream()
                .filter(a -> a != null && a.id() != null && a.distanceSquared() >= 0.0)
                .min(Comparator.comparingDouble(Ally::distanceSquared)
                        .thenComparing(a -> a.id().toString()))
                .orElse(null);
    }
}
