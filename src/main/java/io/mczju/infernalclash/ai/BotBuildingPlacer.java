package io.mczju.infernalclash.ai;

import io.mczju.infernalclash.arena.HalfField;
import io.mczju.infernalclash.arena.Point;
import io.mczju.infernalclash.card.Facing;
import io.mczju.infernalclash.config.BuildingPlacementRule;

import java.util.Optional;

/**
 * ★★★ <b>建筑类召唤牌「放在哪一格」的纯判据</b>（2026-10-21 用户裁定）。
 *
 * <h2>它回答两个问题</h2>
 * <ol>
 *   <li><b>选哪座塔</b>：血量**低**的那座（{@code BACK} / {@code CENTER}）还是**高**的那座
 *       （{@code FRONT}）；<b>相等 ⇒ 右侧</b>（用户原话「相等时默认放在右侧」）；</li>
 *   <li><b>从那座塔沿「前向」偏移几格</b>：{@code BACK} = −4、{@code CENTER} = +4、
 *       {@code FRONT} = +10（判据在 {@link BuildingPlacementRule#forwardCells()}）。</li>
 * </ol>
 *
 * <h2>★★ 为什么本类必须「纯」（不 import Bukkit 的 Location / World）</h2>
 * 与 {@link BotSpellTargeting} / {@code ShapeMath} / {@code Facing} 同一个理由：
 * 「放到哪一格」在游戏里几乎数不清，只有纯函数才能逐格断言
 * （尤其是「哪座塔的血量更低」这种**两条断言只差一个塔**的东西）。
 * 真实世界里的 {@code Bukkit.getWorld(...)} 与半场判定在 {@code TrainingBotDriver} 里。
 *
 * <h2>★ 铁律 10</h2>
 * 塔点位来自 {@code arenas.yml}（管理员显式采集的数据），血量来自**本局目标物列表** ——
 * 本类不查世界、不查「附近的实体」。
 */
public final class BotBuildingPlacer {

    private BotBuildingPlacer() {
    }

    /** 左塔在血量数组里的下标（{@code ObjectiveService#healthOf} 的顺序：左、右）。 */
    public static final int LEFT = 0;

    /** 右塔的下标（★ 用户裁定「相等时默认放在右侧」⇒ 并列时取它）。 */
    public static final int RIGHT = 1;

    /**
     * ★ <b>选哪座塔</b>（纯函数）。
     *
     * <h2>口径（逐条，全部来自用户原话）</h2>
     * <ul>
     *   <li>{@link BuildingPlacementRule#BACK} / {@link BuildingPlacementRule#CENTER}
     *       ⇒ <b>血量更低</b>的那座（「取决于哪一侧的防御塔血量更低」）；</li>
     *   <li>{@link BuildingPlacementRule#FRONT} ⇒ <b>血量更高</b>的那座（「放在高血量一侧」）；</li>
     *   <li>★ <b>相等 ⇒ 右侧</b>（「相等时默认放在右侧」/「默认放右侧」×2）。</li>
     * </ul>
     *
     * @param rule   规则（{@code null} ⇒ 右侧，调用方本不该传 null）
     * @param health 两座塔的**当前**血量，顺序 = <b>左、右</b>（{@code ObjectiveService} 的口径）；
     *               {@code null} / 长度不足 ⇒ 缺的那座当 0（= 已拆）——
     *               ★ 这也顺带把「血量来源不可用」变成「相等 ⇒ 右侧」，与用户的口径一致
     * @return {@link #LEFT} 或 {@link #RIGHT}
     */
    public static int towerIndex(BuildingPlacementRule rule, double[] health) {
        double left = healthAt(health, LEFT);
        double right = healthAt(health, RIGHT);
        if (rule == null || left == right) {
            return RIGHT;   // ★ 相等（含「读不到血量」）⇒ 右侧
        }
        if (rule.picksLowerHealthTower()) {
            return left < right ? LEFT : RIGHT;
        }
        return left > right ? LEFT : RIGHT;
    }

    /**
     * ★★ <b>算出落点格</b>。
     *
     * <h2>失败方向（返回 {@link Optional#empty()}，由调用方退化成普通规则并告警）</h2>
     * <ul>
     *   <li>规则 / 前向为 {@code null}（配置没给）；</li>
     *   <li>选中的那座塔**点位缺失**（{@code arenas.yml} 没采集）或两点世界不一致；</li>
     *   <li>★ 算出来的格**不在己方半场**（Q23 / R28：召唤牌只能落在己方半场）——
     *       用户裁定「退化成普通规则」；</li>
     *   <li>{@code half} 为 {@code null}（没采集半场 ⇒ 无法确认合法性 ⇒ 放弃）。</li>
     * </ul>
     *
     * @param rule       规则
     * @param towerLeft  左塔点位（{@code ArenaConfig#tower(side, true)}）
     * @param towerRight 右塔点位（{@code ArenaConfig#tower(side, false)}）
     * @param health     两座塔当前血量（左、右；见 {@link #towerIndex}）
     * @param front      ★ 「前向」四方位（{@code training.building-front.<side>}）
     * @param ownHalf    本方半场（用来守住 Q23）
     * @return 落点；算不出来 ⇒ 空
     */
    public static Optional<Point> place(BuildingPlacementRule rule, Point towerLeft, Point towerRight,
                                        double[] health, Facing front, HalfField ownHalf) {
        if (rule == null || front == null || ownHalf == null) {
            return Optional.empty();
        }
        Point tower = towerIndex(rule, health) == LEFT ? towerLeft : towerRight;
        if (tower == null || tower.world() == null) {
            return Optional.empty();
        }
        if (towerLeft != null && towerRight != null && !towerLeft.sameWorldAs(towerRight)) {
            // 两座塔在不同世界 ⇒ 配置坏了；放弃（绝不猜一个）
            return Optional.empty();
        }
        int cells = rule.forwardCells();
        int x = tower.cellX() + front.forwardX() * cells;
        int z = tower.cellZ() + front.forwardZ() * cells;
        if (!ownHalf.contains(x, z)) {
            return Optional.empty();
        }
        if (ownHalf.world() != null && !ownHalf.world().equals(tower.world())) {
            return Optional.empty();
        }
        return Optional.of(new Point(tower.world(), x, tower.y(), z, yawOf(front), 0.0f));
    }

    /**
     * ★ <b>四方位 → Minecraft yaw</b>（与 {@code card/Facing} 的映射一致）。
     *
     * <p>{@code SOUTH = 0}、{@code WEST = 90}、{@code NORTH = 180}、{@code EAST = −90}。
     * 建筑的朝向就用它（朝敌方），**不再**用信标 yaw（现场信标 yaw = 0 ⇒ 朝南，对建筑是错的）。
     */
    public static float yawOf(Facing facing) {
        if (facing == null) {
            return 0.0f;
        }
        return switch (facing) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> -90.0f;
        };
    }

    /** 血量的安全读取（下标越界 / 数组为 null ⇒ 0 = 已拆）。 */
    private static double healthAt(double[] health, int index) {
        if (health == null || index < 0 || index >= health.length) {
            return 0.0;
        }
        double value = health[index];
        return Double.isFinite(value) ? value : 0.0;
    }
}
