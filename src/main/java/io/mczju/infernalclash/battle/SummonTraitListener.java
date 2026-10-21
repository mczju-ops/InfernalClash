package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.SummonTraitRule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTeleportEvent;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★ <b>召唤物「特性」的执行层</b> —— 2026-10-18 新增（全卡审计的三条欠账）。
 *
 * <h2>它做的四件事（判据全在纯类 {@link SummonTraitRule}，本类只读 PDC + 写事件）</h2>
 * <ol>
 *   <li><b>末影人免疫远程</b>：{@code DamageCause.PROJECTILE} + 本卡 ⇒ 取消伤害
 *       （{@code docs/19} 第 88 行）。</li>
 *   <li><b>末影人禁止传送</b>：{@code EntityTeleportEvent} + 本卡 ⇒ 取消
 *       （同一行；26.2 里 {@code EntityTeleportEvent} 确实会为末影人触发）。</li>
 *   <li><b>唤魔者的恼鬼收养</b>：{@code docs/19} 第 172 行「需要接管其召唤的恼鬼」——
 *       原版召出的恼鬼**不在账本里** ⇒ 不会被清场、目标由原版管、收尾对不上账。
 *       ⇒ 新生成的恼鬼若落在**一只已被我们接管的唤魔者** {@code VEX_ADOPT_RADIUS} 格内，
 *       就按那只唤魔者的归属登记进账本（并取消恼鬼的有限寿命）。</li>
 *   <li><b>尖牙不分阵营</b>：同行的坑栏「尖牙是 AoE 且不分阵营」⇒ 我们接管的唤魔者放出的
 *       尖牙**不得伤到同阵营的召唤物**（按施法者的 PDC 阵营判，不按"附近有没有人"猜）。</li>
 * </ol>
 *
 * <h2>★ 边界（铁律 10）</h2>
 * <ul>
 *   <li>只碰<b>账本里认得的</b>实体：脏数据/别人的怪/玩家的一律不动；</li>
 *   <li>收养**宁可漏、不可错**：找不到自己人的唤魔者 ⇒ 什么都不做（原版行为照旧）；</li>
 *   <li>全部操作按**单个实体**，没有任何"按世界/全服"的查询。</li>
 * </ul>
 */
public final class SummonTraitListener implements Listener {

    private final UnitRegistry registry;
    private final Logger logger;

    public SummonTraitListener(UnitRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ 末影人

    /** 末影人免疫投射物伤害（箭 / 投掷物）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        String cardId = registry.cardIdOf(victim);
        if (!SummonTraitRule.ignoresProjectiles(cardId)) {
            return;
        }
        event.setCancelled(true);
        logger.fine("[IC] 末影人免疫远程伤害（" + event.getCause() + "，" + victim.getUniqueId() + "）");
    }

    /** 末影人禁止传送。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        String cardId = registry.cardIdOf(event.getEntity());
        if (!SummonTraitRule.blocksTeleport(cardId)) {
            return;
        }
        event.setCancelled(true);
        logger.fine("[IC] 末影人禁止传送（" + event.getEntity().getUniqueId() + "）");
    }

    // ------------------------------------------------------------------ 唤魔者

    /** 收养唤魔者召出的恼鬼：按**最近的那只已被接管的唤魔者**登记归属。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVexSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.VEX) {
            return;
        }
        if (!(event.getEntity() instanceof Vex vex)) {
            return;
        }
        if (registry.isTracked(vex.getUniqueId())) {
            return;
        }
        Evoker summoner = nearestAdoptableEvoker(vex);
        if (summoner == null) {
            return;   // 没有我们接管的唤魔者在附近 ⇒ 不收养（宁可漏，不可错）
        }
        OwnershipTable.Owner owner = registry.ownerFromPdc(summoner);
        if (owner == null) {
            return;
        }
        registry.tag(vex, owner);
        // ★ 与利剑嗡嗡同一条：NoAI 之外唯一能保证它不"自己到点消失"的开关
        vex.setLimitedLifetime(false);
        logger.info("[IC] 恼鬼已收养：唤魔者 " + summoner.getUniqueId() + " → 恼鬼 "
                + vex.getUniqueId() + "（matchId=" + owner.matchId() + "，side=" + owner.side() + "）");
    }

    /**
     * ★★ 2026-10-18：<b>骑手打到了敌人</b> ⇒ 转给本局的钩子链（前储君的坐骑速度清零）。
     *
     * <p>为什么在这里而不在能力监听器里：本监听器已经持有 {@link UnitRegistry} 与
     * 「哪个实体属于哪一局」的读法；而清洗逻辑属于 {@code CombatDirector}（它才是
     * {@code MountSpeedRampService} 的持有者）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiderHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager == null || !registry.isTracked(damager.getUniqueId())) {
            return;
        }
        OwnershipTable.Owner owner = registry.ownerFromPdc(damager);
        if (owner == null) {
            return;
        }
        MatchService match = MatchService.get(owner.matchId());
        if (match != null) {
            match.notifyRiderHit(damager);
        }
    }

    /** 尖牙不得伤到同阵营的召唤物（按施法者的 PDC 阵营判）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFangDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof EvokerFangs fangs)) {
            return;
        }
        LivingEntity caster = fangs.getOwner();
        if (caster == null) {
            return;
        }
        OwnershipTable.Owner casterOwner = registry.ownerFromPdc(caster);
        if (casterOwner == null) {
            return;   // 不是我们的唤魔者 ⇒ 原版行为照旧
        }
        OwnershipTable.Owner victimOwner = registry.ownerFromPdc(event.getEntity());
        if (SummonTraitRule.friendlyFangDamage(casterOwner.side(),
                victimOwner == null ? null : victimOwner.side())) {
            event.setCancelled(true);
        }
    }

    /** 找最近的、已被我们接管的唤魔者（只扫**活跃对局的账本**；找不到 ⇒ null）。 */
    private Evoker nearestAdoptableEvoker(Vex vex) {
        Evoker nearest = null;
        double best = Double.MAX_VALUE;
        for (MatchService match : MatchService.all().values()) {
            for (UUID id : match.units().tracked(match.matchId())) {
                Entity candidate = Bukkit.getEntity(id);
                if (!(candidate instanceof Evoker evoker)) {
                    continue;
                }
                if (evoker.getWorld() != vex.getWorld() || !evoker.isValid()) {
                    continue;
                }
                double distance = evoker.getLocation().distance(vex.getLocation());
                if (SummonTraitRule.shouldAdoptVex(distance, false) && distance < best) {
                    best = distance;
                    nearest = evoker;
                }
            }
        }
        return nearest;
    }
}
