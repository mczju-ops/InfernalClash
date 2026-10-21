package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.RampDamageTrack;
import io.mczju.infernalclash.spell.LineFx;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★★ <b>递增伤害的执行层</b>（2026-10-18 新增，烈焰守卫的第三格）。
 *
 * <h2>出处（{@code docs/19} 第 113 行，逐字）</h2>
 * 「锁定后对该目标的伤害**每 0.5s +1**（从 **1** 起、**上限每 0.5s 10 点**），
 * **重新索敌时重置**」。
 *
 * <h2>分层（与「坐骑速度斜坡」同一套）</h2>
 * <ul>
 *   <li>判据 = 纯状态机 {@link RampDamageTrack}（逐档可离线断言）；</li>
 *   <li>本类只做三件事：<b>数拍子</b>（每 {@code interval-ticks}）、
 *       <b>读目标</b>（{@code SummonCombat#targetId()}，不查世界）、
 *       <b>造成伤害</b>（{@code LivingEntity#damage(double)}，单参 —— 与防御塔同一口径：
 *       带实体源会附带一次方向击退，而这张卡没有任何"击退"的文档依据）。</li>
 * </ul>
 *
 * <h2>★ 铁律 10</h2>
 * 候选与目标全部来自 {@code CombatDirector} 的内存状态；实体只按 UUID 点查
 * （{@link Bukkit#getEntity(UUID)}，O(1)）⇒ 没有「附近实体」那种无边查询。
 *
 * <h2>★ 失败方向</h2>
 * 卡查不到 / 目标已失效 / 目标不是生物 ⇒ <b>只跳过这一拍</b>，不清档、不告警刷屏
 * （烈焰守卫每 0.5 秒一拍，刷屏会把真正的告警淹掉）。
 */
final class RampDamageService {

    private final UnitRegistry registry;
    private final CardCatalog catalog;
    private final Logger logger;

    /** 召唤物 UUID → 它自己的递增状态。 */
    private final Map<UUID, RampDamageTrack> tracks = new LinkedHashMap<>();

    /** 召唤物 UUID → 下一次出手的 tick（数拍子；避免每 tick 都打）。 */
    private final Map<UUID, Long> nextTick = new LinkedHashMap<>();

    RampDamageService(UnitRegistry registry, CardCatalog catalog, Logger logger) {
        this.registry = registry;
        this.catalog = catalog;
        this.logger = logger;
    }

    /**
     * 每个对局 tick 调一次。
     *
     * @param units    「实体 UUID → 战斗状态」的只读视图（由 {@link CombatDirector} 给）
     * @param nowTick  对局 tick（时钟与其它机制一致）
     */
    void onTick(Map<UUID, SummonCombat> units, long nowTick) {
        if (registry == null || catalog == null || units == null || units.isEmpty()) {
            return;
        }
        for (SummonCombat unit : units.values()) {
            LivingEntity self = unit.mob();
            if (self == null || self.isDead() || !self.isValid()) {
                continue;
            }
            Card.RampDamage spec = specOf(self);
            if (spec == null) {
                continue;   // 这张卡没有递增伤害（50/51 张）
            }
            UUID id = self.getUniqueId();
            long due = nextTick.getOrDefault(id, nowTick);
            if (nowTick < due) {
                continue;   // 还没到下一拍
            }
            nextTick.put(id, nowTick + spec.intervalTicks());

            RampDamageTrack track = tracks.computeIfAbsent(id, key -> RampDamageTrack.fresh());
            UUID targetId = unit.targetId();
            // ★★★ 顺序要求（见 RampDamageTrack 的 javadoc）：**先 focus 再读伤害** ——
            //   换目标那一拍必须按第一档结算，否则会用上一个目标攒出来的档位打（"越打越疼却打错人"）。
            track = track.focus(targetId);
            tracks.put(id, track);
            if (targetId == null) {
                continue;   // 没目标 ⇒ 这一拍不打（focus 已经记下"目标丢失"，档位不清空）
            }
            Entity raw = Bukkit.getEntity(targetId);
            if (!(raw instanceof LivingEntity target) || target.isDead() || !target.isValid()) {
                continue;   // 目标已失效 ⇒ 这一拍跳过（下一拍 focus 会看到新目标并归零）
            }
            int damage = track.damageOf(spec);
            if (damage <= 0) {
                continue;
            }
            try {
                // ★ 单参 `damage(double)`：不带实体源 ⇒ 不会有"被它推着走"的附带击退
                target.damage(damage);
            } catch (Throwable t) {
                logger.warning("[IC] 递增伤害结算失败（实体 " + id + "）：" + t);
                continue;
            }
            tracks.put(id, track.hit(spec));
            // ★★★ 2026-10-18（用户第 5 条：「**烈焰守卫是没有伤害？还是没有加攻击特效？
            //   （类似防御塔/冰术师的直线特效）**」）：
            //   答案 = **有伤害、没有特效** —— 它每 0.5 秒按时结算（上面的 `target.damage`
            //   就是证据，日志里的「递增伤害开始 … 本次 1 点」也是它打的），
            //   但屏幕上一根线都没有 ⇒ 在实机上就是"它站在那儿什么都不干"。
            //   ⇒ 每一拍都从它自己到目标画一条直线粒子（与防御塔 / 冰术师同一套几何
            //     {@link LineFx}），并在目标身上补一小簇"命中感"。
            drawHitLine(self, target);
            if (damage == spec.base()) {
                // ★ 只在**第一档**打一行日志：它同时是"这张卡真的开始工作了"的阳性证据，
                //   而且每换一次目标会再打一次（正好能看出"重新索敌"有没有发生）
                logger.info("[IC] 递增伤害开始：" + self.getType() + " 锁定 " + target.getType()
                        + "，" + track.describe(spec));
            }
        }
        // ★ 表不会只增不减：只留还在场的召唤物
        tracks.keySet().retainAll(units.keySet());
        nextTick.keySet().retainAll(units.keySet());
    }

    /** 直线特效的最小距离（格）：贴脸时不画线（那一团会糊住双方），只在目标身上补一簇。 */
    private static final double LINE_MIN_DISTANCE = 0.8;

    /** 直线采样的步长（格）：0.6 格一个点 ⇒ 肉眼是连续的。 */
    private static final double LINE_STEP = 0.6;

    /** 直线最多画几个点（护栏）。 */
    private static final int LINE_MAX_POINTS = 48;

    /**
     * ★★★ <b>「这一下打到了谁」的直线特效</b>（2026-10-18 新增，用户第 5 条）。
     *
     * <h2>口径</h2>
     * <ul>
     *   <li>起点 = 烈焰守卫的<b>眼睛</b>（它是"盯着你"的那种攻击，不是从脚底）；</li>
     *   <li>终点 = 目标的<b>身体中段</b>（{@code getHeight() * 0.5}）；</li>
     *   <li>点数按距离算（{@link LineFx#pointsFor}）—— 远的不稀疏、近的不浪费；</li>
     *   <li>目标身上再补一小簇（命中感）。</li>
     * </ul>
     *
     * <p>★ 几何全在纯类 {@link LineFx}（等距 / 退化 / 步长都有单测），这里只喂坐标。
     * ★ <b>整段只影响观感</b>：任何异常都静默（这条路径每 0.5 秒一拍，
     * 一行 WARN 会把真正的告警淹掉）。
     */
    private void drawHitLine(LivingEntity self, LivingEntity target) {
        try {
            Location eye = self.getEyeLocation();
            Location to = target.getLocation().add(0.0, target.getHeight() * 0.5, 0.0);
            World world = eye.getWorld();
            if (world == null || to.getWorld() == null || !world.equals(to.getWorld())) {
                return;
            }
            double distance = LineFx.distance(eye.getX(), eye.getY(), eye.getZ(),
                    to.getX(), to.getY(), to.getZ());
            if (distance >= LINE_MIN_DISTANCE) {
                int points = LineFx.pointsFor(distance, LINE_STEP, 4, LINE_MAX_POINTS);
                for (double[] point : LineFx.line(eye.getX(), eye.getY(), eye.getZ(),
                        to.getX(), to.getY(), to.getZ(), points)) {
                    world.spawnParticle(org.bukkit.Particle.FLAME,
                            point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0);
                }
            }
            // 命中感：目标身上一小簇火焰
            world.spawnParticle(org.bukkit.Particle.FLAME, to, 8, 0.25, 0.3, 0.25, 0.01);
        } catch (Throwable ignored) {
            // 观感失败绝不影响已经结算完的伤害（这条路径每 0.5 秒跑一次）
        }
    }

    /** 这个实体对应的卡上的递增伤害参数（没有 ⇒ {@code null}）。 */
    private Card.RampDamage specOf(LivingEntity entity) {        String cardId = registry.cardIdOf(entity);
        if (cardId == null) {
            return null;
        }
        Card card = catalog.cards().get(cardId);
        if (card == null || card.summon() == null) {
            return null;
        }
        return card.summon().rampDamage();
    }

    /** 对局收尾：清空记账（幂等）。 */
    void clear() {
        tracks.clear();
        nextTick.clear();
    }

    /** 当前记着几个单位（测试 / 探针用）。 */
    int trackedCount() {
        return tracks.size();
    }

    /** 某个单位当前的档位（探针用；没有 ⇒ {@code null}）。 */
    Integer hitsOf(UUID id) {
        RampDamageTrack track = tracks.get(id);
        return track == null ? null : track.hits();
    }

    /** 一行摘要（启动日志用）。 */
    static String describe() {
        return "递增伤害（烈焰守卫）：锁定同一目标每 0.5s +1（从 1 起、上限 10），换目标即重置";
    }
}
