package io.mczju.infernalclash.ai;

import io.mczju.infernalclash.config.BotSpellRule;
import io.mczju.infernalclash.match.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * ★★★ <b>人机放法术时「选谁 / 选哪」的纯判据</b>（2026-10-21 用户裁定）。
 *
 * <h2>四条规则（用户原话见 {@link BotSpellRule} 的 javadoc）</h2>
 * <table border="1">
 *   <caption>输入 → 输出</caption>
 *   <tr><th>规则</th><th>取什么</th><th>给法术管线的东西</th></tr>
 *   <tr><td>{@link BotSpellRule#NEAREST_ENEMY}</td>
 *       <td>离<b>本方信标</b>最近的敌方单位</td><td>落点 = 它所在方块（<b>不给</b>单体目标）</td></tr>
 *   <tr><td>{@link BotSpellRule#RANDOM_ALLY_CENTER}</td>
 *       <td>随机一名本方召唤物（不含目标物）</td><td>落点 = 它所在方块</td></tr>
 *   <tr><td>{@link BotSpellRule#RANDOM_ALLY_FACING}</td>
 *       <td>同上</td><td>落点 = 它所在方块 ＋ <b>朝向 = 它自己的 yaw</b></td></tr>
 *   <tr><td>{@link BotSpellRule#RANDOM_ALLY_TARGET}</td>
 *       <td>同上</td><td>落点 = 它所在方块 ＋ <b>单体目标 = 它</b></td></tr>
 * </table>
 *
 * <h2>★★ 为什么本类必须「纯」（不 import Bukkit 的 Location / Entity）</h2>
 * 「法术丢到哪一格」是**在游戏里几乎数不清**的那一类判断（与 {@code ShapeMath}、
 * {@code Facing} 同一个理由）：只有把它做成纯函数，才能在 JUnit 里逐格断言「最近的敌人是谁」
 * 「随机挑中的是哪一个」「朝向算出来是多少度」。真实世界里的
 * 账本遍历 / 实体解析 / Location 构造都在执行层（{@code ai/TrainingBotDriver}），
 * 本类只吃 {@link UnitView} 这样的值对象。
 *
 * <h2>★ 铁律 10</h2>
 * 候选来自**调用方传进来的列表**（执行层从本场账本构造），本类<b>不查世界、不查附近</b>。
 */
public final class BotSpellTargeting {

    private BotSpellTargeting() {
    }

    /**
     * 一个「场上单位」的只读快照（执行层从账本 + 实体构造）。
     *
     * @param id        实体 UUID（并列时的打破依据 + 单体目标）
     * @param side      归属阵营
     * @param objective ★ 是不是**目标物**（防御塔 / 信标）—— 用户裁定「友方那三条不选防御塔和信标」；
     *                  判据来自账本上的 {@code objective} 标记，**不按实体类型猜**
     * @param x         实体位置（脚底）X
     * @param y         实体位置 Y
     * @param z         实体位置 Z
     * @param yaw       实体朝向（度；规则 3 用它）
     */
    public record UnitView(UUID id, Side side, boolean objective,
                           double x, double y, double z, float yaw) {

        public UnitView {
            if (id == null) {
                throw new IllegalArgumentException("UnitView 的 id 不能为 null");
            }
        }

        /** 所在方块（落点用格坐标）。 */
        public int cellX() {
            return (int) Math.floor(x);
        }

        /** 所在方块的 Z。 */
        public int cellZ() {
            return (int) Math.floor(z);
        }
    }

    /**
     * 一个落点（纯值对象；= {@code Bukkit Location} 去掉 Bukkit）。
     *
     * @param world 世界名（执行层用它换 {@code World}；换不到 ⇒ 放弃这一次施放）
     * @param x     格坐标 X（整数格）
     * @param y     原始 Y（保留实体脚底高度）
     * @param z     格坐标 Z
     * @param yaw   朝向（度；只有方向性形状会用它）
     */
    public record Spot(String world, int x, double y, int z, float yaw) {
    }

    /**
     * 一次选点结果。
     *
     * @param origin 落点
     * @param target 单体目标（只有 {@link BotSpellRule#RANDOM_ALLY_TARGET} 非空）
     */
    public record Resolution(Spot origin, UUID target) {

        public boolean hasSingleTarget() {
            return target != null;
        }
    }

    /**
     * ★★ <b>算这一次的落点 / 单体目标</b>。
     *
     * @param rule    这张法术卡配的规则（{@code null} ⇒ 空结果：没配规则 = 不可用卡）
     * @param units   场上单位快照（执行层从本场账本构造；{@code null} 视同空）
     * @param botSide 人机这一方（判敌我的唯一依据）
     * @param beacon  ★ 人机**自己那一方的信标**（规则 1 的参照点；{@code null} ⇒ 规则 1 放弃）
     * @param world   本次施放所在世界名（空 ⇒ 放弃）
     * @param random  随机源（{@code null} ⇒ 抛：随机必须可复现）
     * @return 算不出来 ⇒ {@link Optional#empty()}（**这是"这一次没目标"，不是故障** ——
     *         执行层会按用户裁定的「等 5 秒没目标就跳过」处理）
     */
    public static Optional<Resolution> resolve(BotSpellRule rule, List<UnitView> units, Side botSide,
                                               Spot beacon, String world, Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random 不能为 null：人机选点必须可复现（同种子同结果）");
        }
        if (rule == null || botSide == null || world == null || world.isBlank()) {
            return Optional.empty();
        }
        List<UnitView> candidates = rule == BotSpellRule.NEAREST_ENEMY
                ? enemies(units, botSide)
                : allies(units, botSide);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (rule == BotSpellRule.NEAREST_ENEMY) {
            if (beacon == null) {
                // ★ 规则 1 的参照点就是「人机的信标」；没采集 ⇒ **放弃**（绝不换一个参照点）
                return Optional.empty();
            }
            UnitView nearest = nearestTo(beacon, candidates);
            return Optional.of(new Resolution(
                    new Spot(world, nearest.cellX(), nearest.y(), nearest.cellZ(),
                            yawFromTo(beacon.x(), beacon.z(), nearest.x(), nearest.z())),
                    null));
        }
        UnitView picked = candidates.get(random.nextInt(candidates.size()));
        UUID target = rule.needsSingleAllyTarget() ? picked.id() : null;
        return Optional.of(new Resolution(
                new Spot(world, picked.cellX(), picked.y(), picked.cellZ(), picked.yaw()),
                target));
    }

    /** 敌方单位（规则 1 的候选：**全部**敌方单位，含防御塔与信标 —— 用户裁定）。 */
    public static List<UnitView> enemies(List<UnitView> units, Side botSide) {
        List<UnitView> list = new ArrayList<>();
        if (units == null || botSide == null) {
            return list;
        }
        for (UnitView unit : units) {
            if (unit != null && unit.side() != null && unit.side() != botSide) {
                list.add(unit);
            }
        }
        return list;
    }

    /** 友方**召唤物**（规则 2/3/4 的候选：本方的、**不是目标物**的 —— 用户裁定）。 */
    public static List<UnitView> allies(List<UnitView> units, Side botSide) {
        List<UnitView> list = new ArrayList<>();
        if (units == null || botSide == null) {
            return list;
        }
        for (UnitView unit : units) {
            if (unit != null && unit.side() == botSide && !unit.objective()) {
                list.add(unit);
            }
        }
        return list;
    }

    /**
     * ★ <b>离参照点最近的那一个</b>（三维欧氏距离；并列按 UUID 升序 ⇒ 同输入同结果）。
     *
     * <p>★ 失败方向：{@code candidates} 为空 ⇒ 抛（调用方已经判过空）。
     */
    public static UnitView nearestTo(Spot reference, List<UnitView> candidates) {
        if (reference == null || candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("参照点与候选都不能为空（调用方应先判空）");
        }
        double bx = reference.x() + 0.5;
        double by = reference.y();
        double bz = reference.z() + 0.5;
        return candidates.stream()
                .min(Comparator
                        .comparingDouble((UnitView unit) -> squared(unit, bx, by, bz))
                        .thenComparing(UnitView::id))
                .orElseThrow();
    }

    private static double squared(UnitView unit, double x, double y, double z) {
        double dx = unit.x() - x;
        double dy = unit.y() - y;
        double dz = unit.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * ★ <b>从 A 指向 B 的 Minecraft yaw（度）</b>。
     *
     * <p>原版 yaw 的口径（与 {@code card/Facing} 的注释一致）：{@code 0 = 正南(+Z)}、
     * {@code 90 = 正西(-X)}、{@code 180 = 正北(-Z)}、{@code -90/270 = 正东(+X)}
     * ⇒ {@code yaw = -degrees(atan2(dx, dz))}。两点重合 ⇒ {@code 0}（正南，确定性取值）。
     */
    public static float yawFromTo(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (Math.abs(dx) < 1.0e-9 && Math.abs(dz) < 1.0e-9) {
            return 0.0f;
        }
        return (float) -Math.toDegrees(Math.atan2(dx, dz));
    }
}
