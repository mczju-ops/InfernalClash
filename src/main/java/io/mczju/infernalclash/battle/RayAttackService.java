package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.RayMath;
import io.mczju.infernalclash.policy.RayTargetRule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★★ <b>「穿透射线」攻击的执行层</b> —— 2026-10-21 用户裁定。
 *
 * <h2>用户原话（逐字）</h2>
 * 「我发现这些远程怪难以发挥的原因不是走位问题，而是**武器：弓弩三叉戟太容易丢歪了**。
 * 所以现在需要把**劲弩手**也改成死灵法师同款：**拦截射击行为**（不需要给弩附魔了）并
 * **每 0.5 秒发射从本体引向目标的穿透射线，对所有贯穿的敌人造成 2 点伤害，使用紫色射线粒子，
 * 粒子会在射线路径上生成直到碰到方块才停下来**」。
 *
 * <h2>口径（五条，都能对账）</h2>
 * <ol>
 *   <li><b>谁射</b>：卡里写了 {@code summon.ray} 的召唤物（`Card.Ray#appliesTo(实体键)`）——
 *       ★ 本类**按需现查卡池并缓存**，不需要在生成时注册（少一处"忘了注册"的坑）；</li>
 *   <li><b>射向谁</b>：它的**当前目标**（{@code SummonCombat#resolvedTarget()}）；
 *       没有目标 ⇒ 这一拍不射（也不会去射玩家/队友 —— 目标本来就由 {@code CombatDirector} 决定）；</li>
 *   <li><b>起点/终点</b>：从**眼睛**射向目标**身体中心**；</li>
 *   <li><b>打到谁</b>：所有与线段距离 ≤ {@link #HIT_RADIUS} 的**敌方本场单位**（贯穿，可多个），
 *       每个单位**每根射线只结算一次**；★ 候选只来自**账本**（铁律 10：不查世界），玩家不进账本 ⇒ 永不误伤玩家；
 *       ★★ 2026-10-21：「敌方本场单位」= <b>敌方召唤物 ∪ 敌方目标物载体</b>
 *       （判据 = 纯类 {@code policy/RayTargetRule}；目标物靠 {@code UnitRegistry#KEY_OBJECTIVE}
 *       这个**明确标记**认出，不用启发式 —— 理由见那个纯类的 javadoc）；</li>
 *   <li><b>打多远</b>：{@code World#rayTraceBlocks} 撞到方块就停在方块前（用户要求
 *       「粒子沿路径生成直到碰到方块才停下来」）；方块之后的目标**不算命中**。</li>
 * </ol>
 *
 * <p>★ 粒子沿路径每 {@link #PARTICLE_STEP} 格撒一颗（紫色 = 卡里配的 {@code particle}，
 * 劲弩手写 {@code PORTAL}）。粒子纯观感：任何异常都吞掉，绝不影响伤害结算。
 */
final class RayAttackService {

    /** 命中半径（格）：身体中心到线段的距离 ≤ 它就算被贯穿。 */
    static final double HIT_RADIUS = 0.7;

    /** 每一个粒子步撒几颗（2026-10-21 用户：「劲弩手的粒子效果不够明显」⇒ 1 → 3）。 */
    static final int PARTICLES_PER_STEP = 3;

    /** 粒子沿路径的步长（格）。 */
    static final double PARTICLE_STEP = 0.15;

    /** 射线的最大长度（格）；超过就截断（竞技场对角线远小于它，纯防御）。 */
    static final double MAX_RANGE = 64.0;

    private final UnitRegistry registry;
    private final CardCatalog catalog;
    private final Logger logger;

    /** 已经解析过射线的单位（{@code null} = 解析过、没有射线 ⇒ 不再查卡池）。 */
    private final Map<UUID, Card.Ray> resolved = new LinkedHashMap<>();

    /** 下一次可以射的 tick（格挡：让 {@code interval-ticks} 生效）。 */
    private final Map<UUID, Long> nextShot = new LinkedHashMap<>();

    /** 部署期到期 tick（键 = 单位 UUID；只对写了 {@code summon.deploy} 的卡有值）。 */
    private final Map<UUID, Long> deployReady = new LinkedHashMap<>();

    RayAttackService(UnitRegistry registry, CardCatalog catalog, Logger logger) {
        this.registry = registry;
        this.catalog = catalog;
        this.logger = logger;
    }

    /**
     * 每 tick 调一次（由 {@code CombatDirector} 在单位循环之后调）。
     *
     * @param units   本场的召唤物（UUID → 战斗状态）
     * @param nowTick 本局 tick 计数（单调递增；只用来算节拍）
     */
    void onTick(Map<UUID, SummonCombat> units, long nowTick) {
        if (units == null || units.isEmpty() || registry == null) {
            return;
        }
        for (Map.Entry<UUID, SummonCombat> entry : units.entrySet()) {
            UUID id = entry.getKey();
            SummonCombat combat = entry.getValue();
            if (combat == null) {
                continue;
            }
            Mob mob = combat.mob();
            if (mob == null || mob.isDead() || !mob.isValid()) {
                continue;
            }
            Card.Ray ray = rayOf(id, mob);
            if (ray == null) {
                continue;
            }
            LivingEntity target = combat.resolvedTarget();
            if (target == null) {
                continue;   // 没目标 ⇒ 不射（也让"目标一出现立刻射第一根"）
            }
            // ★★★ 1.1.0（用户第 2 条）：「需要能够索敌到敌方的**空中单位**（如恶魂、快乐恶魂等）」。
            //   根因（旧实现）：射程判定用的是**3D 距离**（眼睛 → 目标位置）≤ range，
            //   而恶魂/快乐恶魂飞到目标上方时高度差远远超过 8 ⇒ **永远不开火**。
            //   ⇒ 射程改成**水平距离（X/Z）**：飞在头顶的空中单位落在 8 格水平圈里就能打，
            //     射线本身照常朝目标**身体中心往上**打（`fire()` 里的方向没变）。
            //   ★ 地面目标的行为不变：水平 ≤ 3D，判据只放宽不收紧（原来能打的一定还能打）。
            //   ★ 竖直方向的兜底仍由 `MAX_RANGE`（64 格，射线长度上限）负责。
            double horizontal = horizontalDistance(mob.getEyeLocation(), target.getLocation());
            if (horizontal > ray.range()) {
                continue;
            }
            // ★★★ 1.1.0（远古监视者）：「放置后需要 **4 秒**时间部署，这 4 秒内**没有 ai**」
            //   ⇒ 部署期结束前**不发**射线（判据与音效读同一份 `summon.deploy`）。
            //   ★ 记的是"第一次看到它的 tick + deploy.ticks"，失败方向是"晚一点开火"。
            Long readyAt = deployReadyAt(id, mob, ray, nowTick);
            if (readyAt != null && nowTick < readyAt) {
                continue;
            }
            Long due = nextShot.get(id);
            if (due != null && nowTick < due) {
                continue;
            }
            nextShot.put(id, nowTick + ray.intervalTicks());
            fire(id, mob, target, ray);
        }
    }

    /**
     * ★ 部署期（{@code summon.deploy}）的到期 tick；没有部署期 ⇒ {@code null}。
     *
     * <p>只在**第一次**看到这个单位时算一次（{@code computeIfAbsent}）：那个时刻就是它出生的
     * 那一轮 tick（{@code CombatDirector} 每 tick 都跑），误差 ≤ 1 tick。
     */
    private Long deployReadyAt(UUID id, Mob mob, Card.Ray ray, long nowTick) {
        Card card = cardOf(mob);
        if (card == null || card.summon() == null || card.summon().extra().deploy() == null) {
            return null;
        }
        int ticks = card.summon().extra().deploy().ticks();
        return deployReady.computeIfAbsent(id, key -> nowTick + ticks);
    }

    /** 这个单位的卡（解析失败只告警一次 ⇒ {@code null}）。 */
    private Card cardOf(Mob mob) {
        try {
            String cardId = registry.cardIdOf(mob);
            return cardId == null || catalog == null ? null : catalog.cards().get(cardId);
        } catch (Throwable t) {
            logger.warning("[IC] 解析卡配置失败（实体 " + mob.getUniqueId() + "）：" + t);
            return null;
        }
    }

    /** 两个位置的**水平**（X/Z）距离 —— 空中单位的高度差不参与射程判定（见调用点）。 */
    static double horizontalDistance(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) {
            return Double.MAX_VALUE;
        }
        return horizontalDistance(from.getX(), from.getZ(), to.getX(), to.getZ());
    }

    /**
     * ★ 纯数学版本（可离线单测；{@link Location} 那个只是加了一层"世界必须相同"的守卫）。
     *
     * @return 二维欧氏距离（不是 {@code max(|dx|,|dz|)}）
     */
    static double horizontalDistance(double fromX, double fromZ, double toX, double toZ) {
        return Math.hypot(fromX - toX, fromZ - toZ);
    }

    /** 场上的单位消失 / 对局结束：忘掉缓存（避免 UUID 复用与内存滞留）。 */
    void forget(UUID id) {
        resolved.remove(id);
        nextShot.remove(id);
        deployReady.remove(id);
    }

    /** 对局收尾：整表清空。 */
    void clear() {
        resolved.clear();
        nextShot.clear();
        deployReady.clear();
    }

    /** 这个单位有没有射线（解析一次并缓存；卡池里没有它 ⇒ 缓存 {@code null}）。 */
    private Card.Ray rayOf(UUID id, Mob mob) {
        if (resolved.containsKey(id)) {
            return resolved.get(id);
        }
        Card.Ray ray = null;
        try {
            String cardId = registry.cardIdOf(mob);
            Card card = cardId == null || catalog == null ? null : catalog.cards().get(cardId);
            if (card != null && card.summon() != null && card.summon().ray() != null
                    && card.summon().ray().appliesTo(mob.getType().name())) {
                ray = card.summon().ray();
            }
        } catch (Throwable t) {
            logger.warning("[IC] 解析射线配置失败（实体 " + id + "）：" + t + "，这个单位不打射线");
        }
        resolved.put(id, ray);
        return ray;
    }

    /**
     * 打出一根射线：粒子沿路径（撞到方块就停），并贯穿结算伤害。
     */
    private void fire(UUID shooterId, Mob shooter, LivingEntity target, Card.Ray ray) {
        try {
            Location eye = shooter.getEyeLocation();
            World world = eye.getWorld();
            Location aim = bodyCenter(target);
            if (world == null || aim.getWorld() == null || !world.equals(aim.getWorld())) {
                return;
            }
            Vector toTarget = aim.toVector().subtract(eye.toVector());
            double distance = toTarget.length();
            if (!(distance > 0.0) || Double.isInfinite(distance)) {
                return;
            }
            Vector direction = toTarget.clone().normalize();
            // ★ 射程 = 卡里的 `ray.range`（2026-10-21：三张卡的技能射程都按 8 格）
            double length = Math.min(distance, Math.min(MAX_RANGE, ray.range()));
            // ---- 撞方块就停（方块之后的目标不算命中）----
            try {
                RayTraceResult blockHit = world.rayTraceBlocks(eye, direction, length);
                if (blockHit != null && blockHit.getHitPosition() != null) {
                    length = Math.min(length,
                            blockHit.getHitPosition().distance(eye.toVector()) - 0.05);
                }
            } catch (Throwable ignored) {
                // 射线追踪不可用 ⇒ 只按目标距离（宁可多打一格，也不整根不放）
            }
            if (length <= 0.0) {
                return;
            }
            // ---- 粒子：沿路径直到 length（= 撞墙处）----
            spawnParticles(world, eye, direction, length, ray.particle());
            // ---- 伤害：候选只来自账本，敌方单位；每根射线每个单位只结算一次 ----
            Side mySide = sideOf(shooter);
            if (mySide == null) {
                return;
            }
            Set<UUID> hit = new LinkedHashSet<>();
            for (UUID candidateId : registry.tracked(matchOf(shooter))) {
                if (candidateId == null || candidateId.equals(shooter.getUniqueId())) {
                    continue;
                }
                OwnershipTable.Owner owner = registry.table().owner(candidateId);
                Entity raw = Bukkit.getEntity(candidateId);
                // ★★★ 2026-10-21 缺陷修复（用户：「持有矛、会发射弹射物、以及自定义穿透射线的召唤物
                //   打不动塔」）：判据搬进纯类 `policy/RayTargetRule`，并在"敌方召唤物"之外
                //   **再准入一格：本场的敌方目标物（信标 / 防御塔的载体）**。
                //   ★ 旧写法是 `owner.player() == null ⇒ 跳过`，而目标物的归属玩家按设计就是 null
                //     ⇒ 射线永远打不到塔（近战走原版攻击，不经本判据 ⇒ 现场表现"只有远程不行"）。
                //   ★ 目标物用**明确 PDC 标记**（`UnitRegistry#KEY_OBJECTIVE`，只有载体有）判，
                //     不用「player 为 null ＋ building」那种启发式 —— 展示实体三样全中，但它是无敌的。
                if (!RayTargetRule.hittable(
                        owner != null,
                        owner != null && owner.side() != null && owner.side() != mySide,
                        raw instanceof Player,
                        owner != null && owner.player() != null,
                        registry.isObjective(candidateId))) {
                    continue;
                }
                // ★ 第二道门（与纯判据冗余，本项目一贯的"两层"口径）：
                //   玩家永不吃射线的伤害，实体必须还活着。
                if (!(raw instanceof LivingEntity victim) || victim instanceof Player
                        || victim.isDead() || !victim.isValid()) {
                    continue;
                }
                Location body = bodyCenter(victim);
                double distanceToRay = RayMath.pointToRayDistance(body.getX(), body.getY(),
                        body.getZ(), eye.getX(), eye.getY(), eye.getZ(),
                        direction.getX(), direction.getY(), direction.getZ(), length);
                if (distanceToRay > HIT_RADIUS) {
                    continue;
                }
                if (!hit.add(candidateId)) {
                    continue;
                }
                try {
                    victim.damage(ray.damage(), shooter);
                } catch (Throwable t) {
                    logger.warning("[IC] 射线伤害结算失败（" + victim.getType() + "）：" + t);
                }
            }
        } catch (Throwable t) {
            logger.warning("[IC] 射线攻击失败（" + shooter.getType() + "）：" + t);
        }
    }

    /** 沿射线撒粒子（{@code particle} 解析失败只告警一次，不影响伤害）。 */
    private void spawnParticles(World world, Location eye, Vector direction, double length,
                                String particleName) {
        Particle particle;
        try {
            particle = Particle.valueOf(particleName == null ? "PORTAL"
                    : particleName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            if (this.particleWarned.compareAndSet(false, true)) {
                logger.warning("[IC] 射线粒子 " + particleName + " 解析失败，本场射线不再撒粒子"
                        + "（只影响观感，伤害照常）：" + t);
            }
            return;
        }
        try {
            for (double step = PARTICLE_STEP; step <= length; step += PARTICLE_STEP) {
                Location point = eye.clone().add(direction.clone().multiply(step));
                // ★ 2026-10-21：每步 3 颗 + 一点点散布 ⇒ 明显是一条「光柱」而不是虚线
                world.spawnParticle(particle, point, PARTICLES_PER_STEP, 0.05, 0.05, 0.05, 0.0);
            }
        } catch (Throwable t) {
            if (this.particleWarned.compareAndSet(false, true)) {
                logger.warning("[IC] 射线粒子撒不出去（" + particle + "）：" + t
                        + "，本场不再尝试（只影响观感）");
            }
        }
    }

    /** 目标**身体中心**（脚 + 身高一半）—— 用包围盒高度，不写死 1.8。 */
    private static Location bodyCenter(LivingEntity entity) {
        Location base = entity.getLocation();
        double height = Math.max(0.2, entity.getHeight());
        return base.clone().add(0.0, height / 2.0, 0.0);
    }

    /** 这个单位的阵营（读账本；拿不到 ⇒ {@code null} = 不结算伤害）。 */
    private Side sideOf(Mob shooter) {
        OwnershipTable.Owner owner = registry.table().owner(shooter.getUniqueId());
        return owner == null ? null : owner.side();
    }

    /** 这个单位所属的对局 id（{@code registry.tracked} 需要它）。 */
    private String matchOf(Mob shooter) {
        OwnershipTable.Owner owner = registry.table().owner(shooter.getUniqueId());
        return owner == null ? null : owner.matchId();
    }

    private final java.util.concurrent.atomic.AtomicBoolean particleWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);
}
