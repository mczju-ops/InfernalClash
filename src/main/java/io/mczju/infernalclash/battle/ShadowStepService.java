package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★★ <b>「墨影窃贼」的 0→1 瞬移</b>（1.1.0）。
 *
 * <h2>出处（{@code docs/1.1卡牌.txt}，逐字）</h2>
 * 「墨影窃贼：增加能力：<b>每当其周围半径 8 格范围内的敌人数量由 0 变为 1 时，
 * 锁定该敌人，并瞬移置该敌人的位置</b>」。
 *
 * <h2>口径（逐条）</h2>
 * <ol>
 *   <li><b>数敌人</b>：只数**本场账本里的敌方实体**（召唤物 ＋ 目标物载体）。
 *       ★ 不查世界（铁律 10）；玩家从不进账本 ⇒ 玩家永远不会触发它；</li>
 *   <li><b>0→1 是"上一 tick 0、这一 tick 1"</b>：所以每个单位要记上一次的数量。
 *       ★ 第一次看到它时<b>只记基线、不触发</b>（否则"出生时身边正好有一个敌人"
 *       会在登场那一 tick 就瞬移，那不是"由 0 变为 1"）；</li>
 *   <li><b>瞬移置敌人的位置</b>：直接落到那个敌人的坐标（保留自己的朝向）；
 *       ★ 放行令牌见 {@code SummonTraitListener#allowNextTeleport}（末影人本来禁止传送）；</li>
 *   <li><b>「锁定」</b>：瞬移之后它和目标贴在一起，下一次 {@code target-refresh}（≤0.5 秒）
 *       的"最近敌人"必然是它 ⇒ 不另造一套锁定状态（那会变成第二个目标来源）；</li>
 *   <li>★ 每张卡是否启用由 {@code summon.shadow-step}（半径）决定 ⇒ 没写的 62 张卡<b>零开销</b>。</li>
 * </ol>
 *
 * <p>★ 失败方向：读不到卡 / 卡池为 {@code null} / 传送抛异常 ⇒ <b>只告警一次</b>，
 * 绝不影响索敌与攻击（瞬移只是位移，不改变战斗结果的上限）。
 */
final class ShadowStepService {

    private final UnitRegistry registry;
    private final CardCatalog catalog;
    private final Logger logger;

    /** 每个单位上一次数到的敌人数量（基线；{@code null} = 还没数过）。 */
    private final Map<UUID, Integer> lastCount = new LinkedHashMap<>();

    /** 解析过的半径（{@code null} = 这张卡没有这条能力）。 */
    private final Map<UUID, Double> radii = new LinkedHashMap<>();

    ShadowStepService(UnitRegistry registry, CardCatalog catalog, Logger logger) {
        this.registry = registry;
        this.catalog = catalog;
        this.logger = logger;
    }

    void onTick(Map<UUID, SummonCombat> units) {
        if (registry == null || catalog == null || units == null || units.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, SummonCombat> entry : units.entrySet()) {
            UUID id = entry.getKey();
            SummonCombat combat = entry.getValue();
            if (combat == null) {
                continue;
            }
            Mob self = combat.mob();
            if (self == null || self.isDead() || !self.isValid()) {
                continue;
            }
            Double radius = radiusOf(id, self);
            if (radius == null) {
                continue;
            }
            List<LivingEntity> enemies = enemiesWithin(combat, self, radius);
            int now = enemies.size();
            Integer before = lastCount.put(id, now);
            if (before == null || before != 0 || now != 1) {
                continue;   // 第一次只记基线；只有「0 → 1」才触发
            }
            LivingEntity target = enemies.get(0);
            teleportTo(self, target);
        }
        lastCount.keySet().retainAll(units.keySet());
        radii.keySet().retainAll(units.keySet());
    }

    /** 这个单位的瞬移半径（没有这条能力 ⇒ {@code null}）。 */
    private Double radiusOf(UUID id, Mob self) {
        if (radii.containsKey(id)) {
            return radii.get(id);
        }
        Double radius = null;
        try {
            String cardId = registry.cardIdOf(self);
            Card card = cardId == null ? null : catalog.cards().get(cardId);
            if (card != null && card.summon() != null) {
                radius = card.summon().extra().shadowStepRadius();
            }
        } catch (Throwable t) {
            logger.warning("[IC] 解析「墨影窃贼」半径失败（实体 " + id + "）：" + t
                    + "，这个单位不瞬移");
        }
        radii.put(id, radius);
        return radius;
    }

    /**
     * 半径内的敌方实体（**只读账本**；保序，最近的不一定排第一 —— 由调用方保证 now==1）。
     */
    private List<LivingEntity> enemiesWithin(SummonCombat combat, Mob self, double radius) {
        List<LivingEntity> found = new ArrayList<>();
        double maxSquared = radius * radius;
        Location here = self.getLocation();
        for (UUID candidateId : registry.tracked(combat.matchId())) {
            if (candidateId == null || candidateId.equals(self.getUniqueId())) {
                continue;
            }
            OwnershipTable.Owner owner = registry.table().owner(candidateId);
            if (owner == null || owner.side() == null || owner.side() == combat.side()) {
                continue;
            }
            Entity raw = Bukkit.getEntity(candidateId);
            if (!(raw instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                continue;
            }
            Location at = living.getLocation();
            if (at.getWorld() == null || !at.getWorld().equals(here.getWorld())) {
                continue;
            }
            if (at.distanceSquared(here) > maxSquared) {
                continue;
            }
            found.add(living);
        }
        return found;
    }

    /** 真的瞬移过去（带一次性放行令牌）。 */
    private void teleportTo(Mob self, LivingEntity target) {
        try {
            Location to = target.getLocation().clone();
            // ★ 落到敌人那一格：保留自己的朝向（yaw/pitch 用目标位置上的，但朝向不是玩法信息）
            to.setYaw(self.getLocation().getYaw());
            to.setPitch(self.getLocation().getPitch());
            SummonTraitListener.allowNextTeleport(self.getUniqueId());
            try {
                self.teleport(to);
            } finally {
                SummonTraitListener.revokeTeleportAllowance(self.getUniqueId());
            }
            logger.info("[IC] 墨影窃贼瞬移：周围 8 格敌人由 0 变 1 ⇒ 锁定的 " + target.getType()
                    + " 在 " + to.getBlockX() + "," + to.getBlockY() + "," + to.getBlockZ());
        } catch (Throwable t) {
            logger.warning("[IC] 墨影窃贼瞬移失败（只影响位移，战斗照常）：" + t);
        }
    }

    /** 对局收尾：清空记账（幂等）。 */
    void clear() {
        lastCount.clear();
        radii.clear();
    }

    /** 已接管的单位数量（探针 / 测试用）。 */
    int trackedCount() {
        return radii.size();
    }

    /** 某个单位当前的基线（探针 / 测试用；没有 ⇒ {@code null}）。 */
    Integer countOf(UUID id) {
        return lastCount.get(id);
    }

    /** 一行摘要（启动日志用）。 */
    static String describe() {
        return "墨影窃贼：半径 8 格内敌人由 0 变 1 ⇒ 瞬移到该敌人位置（一次性放行令牌）";
    }
}
