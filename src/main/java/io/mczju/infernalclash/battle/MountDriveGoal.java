package io.mczju.infernalclash.battle;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

/**
 * ★★★ <b>载具驱动</b>：把「乘客的目标」变成「载具的位移」——
 * 2026-10-17 第十三轮批次 3 收尾新增（用户第 1 条：「<b>请你做载具驱动</b>」）。
 *
 * <h2>为什么必须有它（原版的一条硬规则）</h2>
 * <b>乘客的寻路不会移动载具</b> —— 车由车自己开。而我们的骑乘组合里：
 * <ul>
 *   <li>乘客（僵尸 / 僵尸猪灵）靠 {@link SummonCombat#reassert} 拿到目标、
 *       再靠<b>自己的近战 goal</b>带动移动 —— 但它是乘客，那条寻路<b>对载具毫无作用</b>；</li>
 *   <li>载具（僵尸马 / 炽足兽）自己<b>没有攻击 goal</b> ⇒ 即使给它 {@code setTarget}，
 *       它也只是原地闲逛。</li>
 * </ul>
 * ⇒ 结果是整组「<b>站着不动的活靶</b>」。用户实测到的那句
 * 「僵尸猪灵**没有**骑上炽足兽」修好之后，紧接着暴露的就是这一格：
 * **骑上了，但车不开**。
 *
 * <h2>★★ 移动判据 = 「乘客有没有目标」（用户裁定，逐字）</h2>
 * 用户在三条选项里选了「<b>只在乘客有目标时才移动</b>」，并在范围里又勾了
 * 「<b>载具上也允许被乘客的攻击带动（乘客打谁、载具就往谁走）</b>」——
 * 两句话是同一件事的两面，落成三条：
 * <ol>
 *   <li>载具<b>没有乘客</b> ⇒ 退回「用它自己的目标」（那些"没有任何攻击 goal"的单位，
 *       例如美西螈 —— 它们就是靠这个 goal 才会动，见 {@code DriveGoal}）；</li>
 *   <li>有乘客 ⇒ 取<b>第一个有战斗状态的乘客</b>的目标当目标
 *       ⇒ 「乘客打谁，车就往谁走」；</li>
 *   <li>乘客<b>没有目标</b> ⇒ <b>不动</b>（不自己乱逛、不自己挑敌人）
 *       —— ★ 这一条正是用户选的那一档，也是"车不抢仇恨"的保证。</li>
 * </ol>
 *
 * <h2>★ 与 {@link DriveGoal} 的关系（为什么不是改那一个）</h2>
 * {@code DriveGoal} 只认<b>自己</b>的战斗状态（{@code combat.resolvedTarget()}），
 * 而载具自己的战斗状态是空的（它不选目标 —— 见 {@code CombatDirector} 的 drive 语义）。
 * ⇒ 需要的正是"多一层：先看乘客"。两个类的**其余部分**（优先级 / 停距 / 寻路 API）
 * 完全一致 ⇒ 本类刻意照抄那三条口径，而不是把 {@code DriveGoal} 改造成万能类
 * （那样每个调用点都要多传一个可空的"乘客来源"，可读性更差）。
 *
 * <h2>★ 铁律 10</h2>
 * 本类<b>不选敌人、不查世界</b>：它只读 {@code getPassengers()}（O(1) 的实体自身字段）
 * 与**已经在内存里的** {@link SummonCombat} ⇒ 没有 {@code getNearbyEntities} 那类查询。
 *
 * <h2>★ 一条原版事实（实测，必须写在代码里）</h2>
 * {@code Mob#getPathfinder()} 与 {@code Pathfinder#moveTo(LivingEntity, double)} 在
 * paper-api 26.2 上存在（`javap` 实测，与 IG 的 {@code MountDriveGoal} 同一做法）。
 * ★ 而<b>炽足兽在陆地上本来就几乎不动</b>（{@code docs/19} 第 109 行的坑那一栏写了）
 * —— 那不是本类的缺陷，实机核对时别把它当成"驱动没生效"。
 */
public final class MountDriveGoal implements Goal<Mob> {

    /** 低于一切原版 goal 的优先级（与 {@link DriveGoal} 同一档：只在没人管移动时才动）。 */
    public static final int PRIORITY = 99;

    /** 寻路速度倍率（与 IG 的 {@code MountDriveGoal} 同值：比闲逛快，但不至于跑得乘客打不着）。 */
    private static final double SPEED = 1.25;

    /**
     * 靠到多近就停下（**平方**距离，避免每次开方）。
     *
     * <p>2.5 格 ≈ 大多数生物的近战触及范围之上一点：停得太远乘客够不到，
     * 贴得太近会把敌人挤开、也会挡住乘客的视线。
     * ★ 与 {@link DriveGoal} 的 1.5 格（{@code 2.25}）不同：那是"自己贴上去打"，
     * 这是"把乘客送到能打的位置"，所以留得更远一点。
     */
    private static final double STOP_DISTANCE_SQ = 2.5 * 2.5;

    private final Plugin plugin;
    private final Mob mount;
    private final GoalKey<Mob> key;

    /**
     * 「实体 UUID → 它的战斗状态」的只读视图（**由 {@code CombatDirector} 提供**）。
     *
     * <p>★ 用只读视图而不是整张表：本类只需要「查乘客」这一件事，
     * 拿整张表会让"谁能改这张表"变得不清楚。
     */
    private final Map<UUID, SummonCombat> combats;

    public MountDriveGoal(Plugin plugin, Mob mount, Map<UUID, SummonCombat> combats) {
        this.plugin = plugin;
        this.mount = mount;
        this.combats = combats;
        this.key = GoalKey.of(Mob.class, new NamespacedKey(plugin, "mount_drive"));
    }

    /** 供 `MobGoals.removeGoal(mob, key)` 用（卸载时不必持有 goal 实例）。 */
    public GoalKey<Mob> key() {
        return key;
    }

    @Override
    public boolean shouldActivate() {
        LivingEntity target = driveTarget();
        if (target == null) {
            return false;
        }
        return mount.getLocation().distanceSquared(target.getLocation()) > STOP_DISTANCE_SQ;
    }

    @Override
    public boolean shouldStayActive() {
        LivingEntity target = driveTarget();
        if (target == null) {
            return false;
        }
        // ★ 停下之后**保持激活**（原版会在 stop/start 之间抖动）：
        //   只要还有目标就留在 active 里，靠 tick() 自己判"要不要再走"。
        //   ★ 但若已经进入停距，shouldActivate 会返回 false ⇒ Paper 会把它停掉，
        //     下一 tick 目标一动又会重新激活 —— 这个抖动是可接受的（每次都是 O(1) 判据）。
        return mount.getLocation().distanceSquared(target.getLocation()) > STOP_DISTANCE_SQ;
    }

    @Override
    public void start() {
        drive();
    }

    @Override
    public void tick() {
        drive();
    }

    @Override
    public void stop() {
        try {
            mount.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
            // 实体已卸载等 ⇒ 无事可做（收尾不该因此中断）
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE);
    }

    // ------------------------------------------------------------------ 内部

    private void drive() {
        LivingEntity target = driveTarget();
        if (target == null) {
            return;
        }
        if (mount.getLocation().distanceSquared(target.getLocation()) <= STOP_DISTANCE_SQ) {
            return;   // 已经到位置了：不再重申路径（避免每 tick 重算寻路）
        }
        try {
            mount.getPathfinder().moveTo(target, SPEED);
        } catch (Throwable ignored) {
            // 寻路失败（目标不可达 / 实体异常）⇒ 下一 tick 自然重试
        }
    }

    /**
     * ★★ <b>这一辆"车"此刻该朝谁开</b>（本类的全部玩法都在这一格）。
     *
     * <p>顺序（与用户裁定的三句话一一对应）：
     * <ol>
     *   <li>有乘客 ⇒ 取<b>第一个活着、且能取到战斗状态的乘客</b>的目标；</li>
     *   <li>该乘客<b>没有目标</b> ⇒ 返回 {@code null}（<b>车不动</b>，绝不自己挑敌人）；</li>
     *   <li>没有乘客 ⇒ 退回载具<b>自己</b>的战斗状态（那些"没有攻击 goal 的单位"靠它移动）。</li>
     * </ol>
     * ★ 为什么"第一个有战斗状态的乘客"而不是"随便第一个"：骑乘组合里
     * 载具上可能还坐着别的实体（原版的其他乘客），逐个找第一个**我们管得到的**才稳。
     */
    private LivingEntity driveTarget() {
        if (combats == null) {
            return null;
        }
        boolean hadPassenger = false;
        try {
            for (Entity passenger : mount.getPassengers()) {
                hadPassenger = true;
                SummonCombat combat = combats.get(passenger.getUniqueId());
                if (combat == null) {
                    continue;
                }
                LivingEntity target = combat.resolvedTarget();
                if (target != null) {
                    return target;
                }
                // ★★★ 2026-10-18 修（用户：「**炽足兽骑士现在不会动，有bug**」）：
                //   旧实现在这里 **直接 return null**（"乘客没目标，车不动"）。
                //   而现场日志显示：目标是挂在 **STRIDER 自己** 身上的
                //   （「换目标：STRIDER（BLUE）→ …距离 25.7」），乘客那一格始终为空
                //   ⇒ 载具永远不动，看起来就是"这张卡坏了"。
                //   ⇒ 现在退回同一个判断的**另一半**：乘客没有目标就用**载具自己的**目标
                //     （两者都在本场账本里算出，来源同一套候选，不会"车自己挑人"）。
                break;
            }
        } catch (Throwable ignored) {
            return null;
        }
        SummonCombat self = combats.get(mount.getUniqueId());
        if (self != null && self.resolvedTarget() != null) {
            return self.resolvedTarget();
        }
        return null;
    }
}
