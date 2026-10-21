package io.mczju.infernalclash.battle;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;

/**
 * ★ <b>自己补的「走向目标」goal</b> —— 只给**确认没有自带攻击 goal** 的召唤物用。
 *
 * <h2>为什么它是必要且危险的</h2>
 * 从 InfernalGuess 的**狐狸事故**来的教训（`docs/07` §5.4）：
 * 它的驱动 goal 用 priority 0 补进去，而「同一时刻只能跑一个 MOVE goal」
 * ⇒ **狐狸自己的近战 goal 被永久顶掉**，表现是「走到敌人面前却不咬人」。
 *
 * ⇒ 本项目两条铁律：
 * <ol>
 *   <li>**只有**扫描确认该实体没有任何攻击类 goal 时才补这个 goal
 *       （`AttackGoalDetector`，词根全等匹配，**绝不用白名单**）；</li>
 *   <li>它的优先级是**最低的**（数值最大），永远不跟原版行为抢。</li>
 * </ol>
 *
 * <p>★ 它**不自己选敌**：目标由 {@code CombatDirector} 算好写进 {@link SummonCombat}，
 * 本 goal 只负责「朝那个目标走」。这正是「TARGET 层与 MOVE 层必须选出同一个目标」的落地方式
 * （`docs/07` §5.3）。
 */
public final class DriveGoal implements Goal<Mob> {

    /** 低于一切原版 goal 的优先级（越大越靠后）。 */
    public static final int PRIORITY = 99;

    private static final double DEFAULT_SPEED = 1.0;

    private final NamespacedKey namespacedKey;
    private final GoalKey<Mob> key;
    private final SummonCombat combat;

    public DriveGoal(Plugin plugin, SummonCombat combat) {
        this.namespacedKey = new NamespacedKey(plugin, "drive");
        this.key = GoalKey.of(Mob.class, namespacedKey);
        this.combat = combat;
    }

    /** 供 `MobGoals.removeGoal(mob, key)` 用（卸载时不必持有 goal 实例）。 */
    public GoalKey<Mob> key() {
        return key;
    }

    @Override
    public boolean shouldActivate() {
        LivingEntity target = combat.resolvedTarget();
        if (target == null || target.isDead() || !target.isValid()) {
            return false;
        }
        // 已经贴脸了就别再抢 MOVE（把出手机会留给攻击 goal / 原版逻辑）
        return combat.mob().getLocation().distanceSquared(target.getLocation()) > 2.25;
    }

    @Override
    public boolean shouldStayActive() {
        return shouldActivate();
    }

    @Override
    public void stop() {
        try {
            combat.mob().getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
            // 实体已卸载时 stopPathfinding 可能抛 —— 收尾不该因此中断
        }
    }

    @Override
    public void tick() {
        LivingEntity target = combat.resolvedTarget();
        if (target == null) {
            return;
        }
        combat.mob().getPathfinder().moveTo(target, DEFAULT_SPEED);
    }

    @Override
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
    }
}
