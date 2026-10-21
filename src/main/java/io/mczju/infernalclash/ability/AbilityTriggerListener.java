package io.mczju.infernalclash.ability;

import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.logging.Logger;

/**
 * ★ <b>能力触发器的监听入口</b>（P6b）：把「受伤 / 攻击 / 死亡」三个 Bukkit 事件转成
 * {@link AbilityRuntime} 的调用。
 *
 * <h2>★ 铁律 10：只处理**本场账本里**的实体</h2>
 * 每个事件进来看的第一件事是 {@link UnitRegistry} 的归属查询：
 * <ul>
 *   <li>拿不到归属（不属于我）⇒ **立刻返回**，连日志都不打（别人家的战斗与我无关）；</li>
 *   <li>拿得到 ⇒ 才转给运行时，并带上它的 `matchId`。</li>
 * </ul>
 * ⇒ 这就是"归属判定不能靠类型、要靠 PDC/追踪表"的落地：**不是本项目接管的实体，一律不碰**。
 *
 * <p>⚠️ 本监听器**不做任何范围查询**（没有 `getNearbyEntities`），也不改世界状态。
 */
public final class AbilityTriggerListener implements Listener {

    private final UnitRegistry registry;
    private final AbilityRuntime runtime;
    private final Logger logger;

    public AbilityTriggerListener(UnitRegistry registry, AbilityRuntime runtime, Logger logger) {
        this.registry = registry;
        this.runtime = runtime;
        this.logger = logger;
    }

    /** 受伤（包含被实体攻击 ⇒ 额外触发 `ON_ATTACK`）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        String matchId = matchOf(victim);
        if (matchId == null) {
            return;
        }
        MatchService match = MatchService.get(matchId);
        if (match != null) {
            runtime.onHurt(victim, matchId, match);
        }
        // 攻击方也是本场实体 ⇒ 触发它的 ON_ATTACK
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            String attackerMatch = matchOf(byEntity.getDamager());
            MatchService attackerService = attackerMatch == null ? null : MatchService.get(attackerMatch);
            if (attackerService != null) {
                runtime.onAttack(byEntity.getDamager(), attackerMatch, attackerService);
            }
        }
    }

    /** 死亡（`ON_DEATH`）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Entity victim = event.getEntity();
        String matchId = matchOf(victim);
        if (matchId == null) {
            return;
        }
        MatchService match = MatchService.get(matchId);
        if (match != null) {
            runtime.onDeath(victim, matchId, match);
        }
    }

    /** ★ 归属查询：只有**本场登记过**的实体才返回 matchId（否则 null ⇒ 一律不碰）。 */
    private String matchOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        OwnershipTable.Owner owner = registry.table().owner(entity.getUniqueId());
        return owner == null ? null : owner.matchId();
    }
}
