package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.logging.Logger;

/**
 * ★★★ <b>「自己人不打自己人」的伤害守卫</b>（2026-10-18 新增；用户第 3 条的第二层）。
 *
 * <h2>用户原话（逐字）</h2>
 * 「**我方召唤物还是会有两秒左右发呆的过程：死灵法师甚至去打了两下自家防御塔才开始正常索敌。**」
 * 我追问修法后用户选定「**A. 两层都做**」：
 * <ol>
 *   <li>第一层 = 出生即锁定目标（{@code CombatDirector#syncSummons}，已落地）；</li>
 *   <li>★★ <b>第二层 = 本层</b>：即使原版 AI 在某个缝隙里让召唤物把**同侧**单位
 *       （或目标物）当成了目标，<b>那一下也打不出伤害</b>。</li>
 * </ol>
 *
 * <h2>判据（三条，全部按账本，不按实体类型）</h2>
 * <ol>
 *   <li><b>攻击方阵营</b>：直接打 ⇒ 账本里那个实体的 {@code side}；
 *       弹射物 ⇒ 看它的 {@code shooter}（照样回账本查）。查不到 ⇒ <b>不拦</b>
 *       （别人的怪、服主的动物，不归我们管）；</li>
 *   <li><b>受害方阵营</b>：账本里那个实体的 {@code side}（目标物的 owner 是
 *       {@code side} + {@code player == null}，同样查得到）；查不到 ⇒ 不拦；</li>
 *   <li>两者<b>同侧</b> ⇒ 取消这一下。<b>跨侧</b> ⇒ 放行（正常的战斗）。</li>
 * </ol>
 * ★ 玩家：受害方是玩家时一律放行给 {@code PlayerDamageGuard}（R41 的"一律免伤"在那里），
 * 本类**不**碰玩家（避免两处各自判一次）。
 *
 * <h2>★ 生命周期</h2>
 * 每局一个实例，由 {@code CombatDirector} 构造时注册、{@code onStop} 注销
 * （与 {@code ObjectiveKnockbackGuard} 同一套；写在非 Listener 类上的 {@code @EventHandler}
 * 是死代码 —— 本项目已经踩过一次，见那个类的注释）。
 */
final class AllyDamageGuard implements Listener {

    private final UnitRegistry registry;
    private final Logger logger;

    /** 「同侧误伤」只告警一次（否则一个卡住的原版 goal 会每 0.5 秒刷一行）。 */
    private boolean warned;

    AllyDamageGuard(UnitRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (registry == null || event == null) {
            return;
        }
        Entity victim = event.getEntity();
        if (victim instanceof Player) {
            return;   // 玩家免伤是 PlayerDamageGuard 的事（R41），这里不重复判
        }
        OwnershipTable.Owner victimOwner = registry.table().owner(victim.getUniqueId());
        Side victimSide = victimOwner == null ? null : victimOwner.side();
        if (victimSide == null) {
            return;   // 不是本场实体（别人的怪 / 装饰） ⇒ 不管
        }
        Side damagerSide = sideOfDamager(event.getDamager(), victimOwner);
        if (damagerSide == null || damagerSide != victimSide) {
            return;   // 攻击方来头不明，或跨阵营 ⇒ 正常战斗
        }
        event.setCancelled(true);
        if (!warned) {
            warned = true;
            logger.warning("[IC] 已拦下「自己人打自己人」："
                    + event.getDamager().getType() + "（" + damagerSide + "）→ "
                    + victim.getType() + "（" + victimSide + "）｜"
                    + "这是用户第 3 条的第二层兜底（第一层 = 出生即锁定目标）；"
                    + "同一局只告警这一次，其余静默取消");
        }
    }

    /**
     * 攻击方（含弹射物的发射者、★ 尖牙的主人）的阵营。
     *
     * <p>★★ 2026-10-21（用户裁定「唤魔者的尖牙…**只咬敌人**」）：
     * {@code EvokerFangs} 本身**不在账本里**（它是原版临时实体）⇒ 必须顺着
     * {@code getOwner()} 找它的主人：
     * <ul>
     *   <li>主人是**本场召唤物** ⇒ 直接读账本；</li>
     *   <li>主人是**玩家**（我们的 {@code SPAWN_FANGS} 就是把 owner 设成召唤者）⇒
     *       玩家不进账本，要从**本局名册**反查（{@code MatchService#sideOf}）——
     *       拿不到就按"来头不明"处理（**不拦**，与原版一致）。</li>
     * </ul>
     */
    private Side sideOfDamager(Entity damager, OwnershipTable.Owner victimOwner) {
        if (damager == null) {
            return null;
        }
        Side direct = sideOf(damager);
        if (direct != null) {
            return direct;
        }
        if (damager instanceof Projectile projectile) {
            return sideOf(projectile.getShooter() instanceof Entity shooter ? shooter : null);
        }
        if (damager instanceof org.bukkit.entity.EvokerFangs fangs) {
            LivingEntity owner = fangs.getOwner();
            Side ownerSide = sideOf(owner);
            if (ownerSide != null) {
                return ownerSide;
            }
            if (owner instanceof Player player && victimOwner != null) {
                try {
                    io.mczju.infernalclash.match.MatchService match =
                            io.mczju.infernalclash.match.MatchService.get(victimOwner.matchId());
                    return match == null ? null : match.sideOf(player.getUniqueId());
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 账本里的阵营（查不到 ⇒ {@code null}，**不猜**）。 */
    private Side sideOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        OwnershipTable.Owner owner = registry.table().owner(entity.getUniqueId());
        return owner == null ? null : owner.side();
    }
}
