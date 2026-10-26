package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.BomberFlight;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★★ <b>怨恨轰炸机的飞行执行层</b>（2026-10-18 新增）。
 *
 * <h2>出处（{@code docs/19} 第 110 行，逐字）</h2>
 * 「**无法通过吐火球攻击**，只会**飞行到目标上方、然后垂直向下发射火球**；22 血」。
 * 用户选的是「**最小可用**」：禁原版远程 ＋ 飞到目标上方 ＋ 垂直投火球。
 *
 * <h2>怎么"禁原版远程"（这一格最容易被做错）</h2>
 * 恶魂的吐火球**不是** {@code EntityShootBowEvent}，所以 {@code block-ranged} 那条路拦不到它。
 * ⇒ 本服务对这类单位调一次 <b>{@code Mob#setAI(false)}</b>：原版 goal 全停
 * （不再自己吐、不再乱飞），移动与投弹**全部由我们接管** —— 这就是文档说的"自定义飞行 AI"。
 * ★ 与 {@code summon.stationary} 的区别：那个是"站着不动"，这个是"AI 关掉但我们让它飞"。
 *
 * <h2>每 tick 做什么</h2>
 * <ol>
 *   <li>（一次性）关掉原版 AI；</li>
 *   <li>读目标（{@code SummonCombat#targetId()}，不查世界）；</li>
 *   <li>按纯函数 {@link BomberFlight#plan} 算速度并 {@code setVelocity}；</li>
 *   <li>{@link BomberFlight.State#DROP} 且到了投弹间隔 ⇒ 在**自己正下方**生成一颗
 *       {@code SmallFireball}（方向 (0,−1,0)、{@code yield 0}、不引燃）——
 *       ★ 不引燃 / 不炸方块：伤害由 {@code abilities} 的 DAMAGE 结算，火球只是"落下去的东西"。</li>
 * </ol>
 *
 * ★ 铁律 10：目标只来自账本态（{@code CombatDirector} 的内存），实体只按 UUID 点查；
 * 生成的弹射物**属于我们**（shooter = 这只轰炸机，且它自己在本场账本里）。
 */
final class BomberFlightService {

    /** 投弹间隔（tick）：2 秒一颗（恶魂原版约 3 秒；这里取更快的 2 秒，★ 可改）。 */
    static final int DROP_INTERVAL_TICKS = 40;

    /**
     * ★★★ <b>轰炸机的伤害节拍与数值</b>（2026-10-18 新增，用户第 1 条）。
     *
     * <p>用户原话（逐字，两次确认）：「**怨恨轰炸机似乎现在造成不了伤害了？**」→
     * 「**B，每 0.5s（10 tick）造成 5 点伤害**」。
     *
     * <p>★ 为什么数值落在**代码**而不是卡里的 {@code abilities}：
     * {@code ShippedAbilitiesCoverageTest} 的能力账本是按 {@code docs/19} 的**能力关键词**
     * 建表的，而轰炸机那一行（「无法通过吐火球攻击，只会飞行到目标上方，然后垂直向下发射火球」）
     * 不含关键词 ⇒ 往卡里塞 abilities 会被判成"文档没写却自己加能力"。
     * 这条数值的来源是**用户裁定**，所以直接写在执行层，并在 javadoc 里标明出处（可随时改这两行）。
     */
    static final int DAMAGE_INTERVAL_TICKS = 10;

    /** 每次伤害的数值（用户裁定：0.5 秒 5 点）。 */
    static final double DAMAGE_PER_HIT = 5.0;

    private final UnitRegistry registry;
    private final CardCatalog catalog;
    private final Logger logger;

    /** 已经关过 AI 的单位（幂等：不必每 tick 重复写属性）。 */
    private final Set<UUID> aiDisabled = new LinkedHashSet<>();

    /** 上一次投弹的 tick。 */
    private final Map<UUID, Long> lastDrop = new LinkedHashMap<>();

    BomberFlightService(UnitRegistry registry, CardCatalog catalog, Logger logger) {
        this.registry = registry;
        this.catalog = catalog;
        this.logger = logger;
    }

    /**
     * 每个对局 tick 调一次。
     *
     * @param units   「实体 UUID → 战斗状态」
     * @param nowTick 对局 tick
     */
    void onTick(Map<UUID, SummonCombat> units, long nowTick) {
        if (registry == null || catalog == null || units == null || units.isEmpty()) {
            return;
        }
        for (SummonCombat unit : units.values()) {
            Mob self = unit.mob();
            if (self == null || self.isDead() || !self.isValid()) {
                continue;
            }
            if (!isBomber(self)) {
                continue;   // 非轰炸机卡
            }
            // ★★★ 1.1.0（美愿轰炸机）：伤害 / 节拍 / 尾迹粒子改成**读卡**
            //   （{@code summon.bomber-damage} / {@code bomber-interval-ticks} / {@code bomber-particle}）。
            //   ★ 不写 ⇒ 沿用老的 5 点 / 10 tick / FLAME ⇒ 怨恨轰炸机行为一字未变。
            Card card = cardOf(self);
            Card.Summon.Extra extra = card == null || card.summon() == null
                    ? Card.Summon.Extra.NONE : card.summon().extra();
            double damagePerHit = extra.bomberDamage() == null
                    ? DAMAGE_PER_HIT : extra.bomberDamage();
            int damageInterval = extra.bomberIntervalTicks() == null
                    ? DAMAGE_INTERVAL_TICKS : extra.bomberIntervalTicks();
            org.bukkit.Particle trail = resolveParticle(
                    extra.bomberParticle() == null ? "FLAME" : extra.bomberParticle());
            UUID id = self.getUniqueId();
            if (aiDisabled.add(id)) {
                // ★ 一次性关掉原版 AI（= docs/19 的「无法通过吐火球攻击」）
                try {
                    self.setAI(false);
                    logger.info("[IC] 轰炸机已接管：" + self.getType()
                            + " 关闭原版 AI（不再自己吐火球），改为「飞到目标上方 → 垂直投弹」");
                } catch (Throwable t) {
                    logger.warning("[IC] 关闭轰炸机原版 AI 失败（" + id + "）：" + t);
                }
            }
            UUID targetId = unit.targetId();
            Entity raw = targetId == null ? null : Bukkit.getEntity(targetId);
            if (!(raw instanceof LivingEntity target) || target.isDead() || !target.isValid()) {
                continue;   // 没目标 ⇒ 原地悬停（纯函数 IDLE）
            }
            Location here = self.getLocation();
            Location there = target.getLocation();
            BomberFlight.Step step = BomberFlight.plan(
                    there.getX() - here.getX(), there.getY() - here.getY(),
                    there.getZ() - here.getZ());
            if (step.state() == BomberFlight.State.IDLE) {
                continue;
            }
            // ★★★ 2026-10-18 修（用户第 9 条：「**怨恨轰炸机现在完全无法移动，放出来只会
            //   静止在地上不动**」）：位移改用 **teleport**，不再 setVelocity。
            //
            //   根因（恶魂是飞行生物）：它的位移由原版 `FlyingMoveControl` **每 tick 自己写**
            //   `deltaMovement`；而我们为了禁掉原版火球已经 `setAI(false)` ——
            //   两件事撞在一起的结果是：我们写进去的速度**下一 tick 就被抹掉**。
            //   现场证据：那只恶魂死时仍是 `y=201.00`（与出生高度一字不差）。
            //   ⇒ 现在"我们算位置、我们搬它"：坐标由纯函数
            //     {@link BomberFlight#destination} 给出（有单测），这里只负责写实体。
            //   ★ 每 tick 0.25 格 = 5 格/秒，与地面单位的速度量级相当；客户端会插值，不会跳。
            try {
                double[] next = BomberFlight.destination(here.getX(), here.getY(), here.getZ(), step);
                // ★★★ 2026-10-18（用户第 6 条）：「**也不会朝运动方向转身**」
                //   ⇒ 朝向由**位移方向**算（而不是保持出生时的 yaw）：横向飞就面向飞行方向，
                //     纯下降时保持当前 yaw、pitch 朝下（正在投弹）。
                float yaw = here.getYaw();
                float pitch = here.getPitch();
                double horizontal = Math.hypot(step.vx(), step.vz());
                if (horizontal > 1.0e-6) {
                    yaw = (float) (Math.toDegrees(Math.atan2(-step.vx(), step.vz())));
                    pitch = -20.0f;
                } else if (step.vy() < 0) {
                    pitch = 60.0f;   // 正在下落 / 投弹 ⇒ 低头
                }
                self.teleport(new Location(self.getWorld(), next[0], next[1], next[2], yaw, pitch));
                self.setFallDistance(0.0f);
                // ★ 用户第 4 条：轰炸机的特效 = **火焰**（它没有 abilities，所以这里补一条尾迹）
            //   ★ 1.1.0：粒子名改成读卡（美愿轰炸机 = HEART / TOTEM 之类，怨恨轰炸机仍是 FLAME）
            if (trail != null) {
                self.getWorld().spawnParticle(trail,
                        next[0], next[1] + 0.6, next[2], 3, 0.15, 0.15, 0.15, 0.0);
            }
            } catch (Throwable t) {
                logger.warning("[IC] 轰炸机位移写入失败（" + id + "）：" + t);
                continue;
            }
            if (step.state() != BomberFlight.State.DROP) {
                // ★ 飞行中也在"投弹结算"节拍上：用户的口径是"每 0.5 秒 5 点"，
                //   与那 2 秒一次的视觉火球**是两条独立的节拍**（伤害不等火球）。
                bombDamage(id, target, nowTick, damagePerHit, damageInterval, trail);
                continue;
            }
            bombDamage(id, target, nowTick, damagePerHit, damageInterval, trail);
            long last = lastDrop.getOrDefault(id, Long.MIN_VALUE);
            if (nowTick - last < DROP_INTERVAL_TICKS) {
                continue;
            }
            lastDrop.put(id, nowTick);
            dropFireball(self);
        }
        aiDisabled.retainAll(units.keySet());
        lastDrop.keySet().retainAll(units.keySet());
        nextDamage.keySet().retainAll(units.keySet());
    }

    /**
     * ★ <b>每 {@code interval} tick 对当前目标结算一次伤害</b>（用户第 1 条）。
     *
     * <p>★ 单参 {@code damage(double)}（不带实体源）⇒ 不会有"被它推着走"的附带击退
     * （与防御塔光束、烈焰守卫递增伤害同一口径）。
     * ★ 只在到拍时打（每个轰炸机各记一个下一次的 tick），失败只告警一次不刷屏。
     * ★★ 1.1.0：伤害 / 节拍 / 粒子全部由**调用方按卡**给（怨恨轰炸机仍是 5 / 10 / FLAME）。
     */
    private void bombDamage(UUID id, LivingEntity target, long nowTick, double damagePerHit,
                            int damageInterval, org.bukkit.Particle trail) {
        Long next = nextDamage.get(id);
        if (next == null) {
            next = nowTick;   // 第一次出手不延迟
        }
        if (nowTick < next) {
            return;
        }
        nextDamage.put(id, nowTick + Math.max(1, damageInterval));
        try {
            target.damage(damagePerHit);
            // ★ 用户第 4 条：轰炸机的特效 = 火焰 ⇒ 命中处一簇（1.1.0 起按卡）
            if (trail != null) {
                target.getWorld().spawnParticle(trail,
                        target.getLocation().add(0.0, target.getHeight() * 0.5, 0.0),
                        10, 0.25, 0.35, 0.25, 0.01);
            }
        } catch (Throwable t) {
            logger.warning("[IC] 轰炸机伤害结算失败（" + id + "）：" + t);
        }
    }

    /** 每个轰炸机下一次出手的 tick。 */
    private final Map<UUID, Long> nextDamage = new LinkedHashMap<>();

    /** 在自己正下方生成一颗火球（★ 方向 (0,−1,0)、yield 0、不引燃）。 */
    private void dropFireball(Mob self) {
        try {
            Location from = self.getLocation().add(0.0, 1.0, 0.0);
            org.bukkit.entity.SmallFireball fireball =
                    self.getWorld().spawn(from, org.bukkit.entity.SmallFireball.class);
            fireball.setShooter(self);
            fireball.setDirection(new Vector(0.0, -1.0, 0.0));
            fireball.setIsIncendiary(false);
            fireball.setYield(0.0f);
        } catch (Throwable t) {
            logger.warning("[IC] 轰炸机投弹失败（只影响这一次投弹）：" + t);
        }
    }

    /** 这张卡是不是轰炸机（由卡上的 {@code summon.bomber} 决定）。 */
    private boolean isBomber(Mob self) {
        Card card = cardOf(self);
        return card != null && card.summon() != null && card.summon().bomber();
    }

    /** 这个单位的卡（{@code null} = 不是我们的卡 / 卡池里没有）。 */
    private Card cardOf(Mob self) {
        String cardId = registry.cardIdOf(self);
        if (cardId == null) {
            return null;
        }
        return catalog.cards().get(cardId);
    }

    /**
     * 粒子名 → {@link org.bukkit.Particle}（解析不出来 ⇒ {@code null} 并**只告警一次**）。
     *
     * <p>★ 装载期已经校验过 {@code summon.bomber-particle}（写错直接拒绝启动），
     * 这里只是兜底（例如卡池被热替换）。
     */
    private org.bukkit.Particle resolveParticle(String name) {
        try {
            return org.bukkit.Particle.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            if (particleWarned.compareAndSet(false, true)) {
                logger.warning("[IC] 轰炸机粒子 " + name + " 解析失败，本场改用 FLAME（只影响观感）："
                        + t);
            }
            return org.bukkit.Particle.FLAME;
        }
    }

    /** 「粒子名解析失败」只告警一次。 */
    private final java.util.concurrent.atomic.AtomicBoolean particleWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 对局收尾：清空记账（幂等；AI 已关过就保持关着 —— 实体马上会被回收）。 */
    void clear() {
        aiDisabled.clear();
        lastDrop.clear();
    }

    /** 已接管的轰炸机数量（探针 / 测试用）。 */
    int trackedCount() {
        return aiDisabled.size();
    }

    /** 一行摘要（启动日志用）。 */
    static String describe() {
        return "轰炸机（怨恨轰炸机）：关原版 AI ＋ 飞到目标上方 6 格 ＋ 每 2 秒垂直投一颗火球";
    }
}
