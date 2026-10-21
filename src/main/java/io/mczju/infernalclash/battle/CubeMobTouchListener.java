package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★ <b>方块怪接触伤害的执行层</b> —— 2026-10-17 批次 3 收尾新增。
 *
 * <h2>它补的是哪一格</h2>
 * {@code docs/19} 第 85 行（熔岩精灵）与第 150 行（烈焰熔炉）都要求这两只岩浆怪
 * <b>能打到东西</b>，而原版方块怪<b>对非玩家 0 输出</b>
 * （判据与数值见纯类 {@link CubeMobTouch} 的 javadoc，来自服务端反编译实测）。
 *
 * <h2>★★ 它怎么挂上去（为什么不是 Goal、也不是监听器）</h2>
 * 本类<b>不注册 Bukkit 事件</b>，而是由 {@code InfernalClashGame} 的对局心跳
 * （{@link MatchTicker}）每 tick 调一次 {@link #onTick}：
 * <ul>
 *   <li>接触伤害本质是"<b>位置关系</b>"，而位置变化<b>没有</b>一个便宜的事件可听
 *       （{@code EntityMoveEvent} 每 tick 每个实体都会触发，比我们直接算还贵）；</li>
 *   <li>用 Goal 做不到"打账本里的敌人"——Goal 只知道 {@code setTarget} 写进去的那个目标，
 *       而我们的目标由 {@link CombatDirector} 算（TARGET 层与 MOVE 层必须同一个目标，
 *       见 {@code docs/07} §5.3）；</li>
 *   <li>心跳里我们已经有了「<b>这一轮从账本解析出来的实体表</b>」——
 *       复用它可以做到<b>既不查世界、也不重复解析</b>。</li>
 * </ul>
 *
 * <h2>★ 铁律 10 的四问</h2>
 * <ol>
 *   <li><b>范围界定</b>：目标只从 {@link #onTick} 收到的<b>本场账本实体表</b>里挑
 *       —— 没有 {@code getNearbyEntities}，也没有按类型扫世界；</li>
 *   <li><b>失败方向</b>：拿不到攻击力属性 / 拿不到宽度 / 距离算不出来
 *       ⇒ <b>这一 tick 什么都不做</b>（绝不退化成"打所有人"）；</li>
 *   <li><b>归属判定</b>：走 {@link UnitRegistry} 的账本与 {@link OwnershipTable} 的阵营，
 *       <b>不看类型</b>；★ <b>玩家一律不打</b>（R42：玩家不会成为任何召唤物的目标）；</li>
 *   <li><b>可对账</b>：每只方块怪每次真的造成伤害都留一行日志（含伤害与目标）。</li>
 * </ol>
 *
 * <h2>★ 与「掉落 / 爆炸守卫」的关系</h2>
 * 这一格与 {@code battle/SummonLifecycleGuard}（掉落与苦力怕爆炸）是**互补的**：
 * 那个管"不要发生什么"，这个管"要发生什么"。两者都只认账本。
 */
public final class CubeMobTouchListener {

    private final UnitRegistry registry;
    private final Logger logger;

    /**
     * 每只方块怪上一次接触伤害是在哪个对局 tick（节流用）。
     *
     * <p>★ 用"对局 tick"而不是"墙上时间"：本项目对「同输入 ⇒ 同结果」有硬要求，
     * 而对局 tick 是确定的（重放同一场会得到同样的伤害序列）。
     */
    private final Map<UUID, Integer> lastTouchTick = new LinkedHashMap<>();

    public CubeMobTouchListener(UnitRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    /**
     * 每个对局 tick 调一次。
     *
     * @param matchId    本场 id
     * @param battleTick 当前对局 tick（节流基准）
     * @param phase      当前阶段（只有战斗/加时才动手）
     */
    public void onTick(String matchId, int battleTick, MatchPhase phase) {
        if (phase != MatchPhase.BATTLE && phase != MatchPhase.OVERTIME) {
            return;   // 准备阶段不打
        }
        if (registry == null) {
            return;
        }
        Map<UUID, LivingEntity> living = resolveLedger(matchId);
        if (living.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, LivingEntity> entry : living.entrySet()) {
            LivingEntity entity = entry.getValue();
            if (!(entity instanceof Slime slime) || entity.isDead() || !entity.isValid()) {
                continue;
            }
            OwnershipTable.Owner owner = registry.table().owner(entry.getKey());
            if (owner == null || owner.player() == null) {
                continue;   // 目标物（塔 / 信标）不是方块怪；owner.player == null 的一律跳过
            }
            Integer last = lastTouchTick.get(entry.getKey());
            if (last != null && battleTick - last < CubeMobTouch.TOUCH_INTERVAL_TICKS) {
                continue;   // 节流：还没到下一次
            }
            LivingEntity victim = nearestEnemy(slime, owner, living);
            if (victim == null) {
                continue;
            }
            double damage = damageAgainst(slime, victim);
            if (damage <= 0.0) {
                continue;
            }
            try {
                victim.damage(damage, slime);
                lastTouchTick.put(entry.getKey(), battleTick);
                logger.info("[IC] 方块怪接触伤害：" + slime.getType() + "（体型 "
                        + slime.getSize() + "）→ " + victim.getType()
                        + " 造成 " + String.format("%.1f", damage) + " 点"
                        + "（原版对方块怪以外的目标 0 输出，由本类接管）｜matchId=" + matchId);
            } catch (Throwable t) {
                logger.warning("[IC] 方块怪接触伤害失败（" + slime.getType() + "）：" + t);
            }
        }
        // ★ 表随时间只增不减会漏内存：只保留还在场上的那些（对局结束由 clear 清空）
        lastTouchTick.keySet().retainAll(living.keySet());
    }

    /** 对局收尾：清空节流表（**幂等**）。 */
    public void clear() {
        lastTouchTick.clear();
    }

    /** 当前记着几只方块怪（测试 / 探针用）。 */
    public int trackedCount() {
        return lastTouchTick.size();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 这只方块怪此刻该打谁（**从账本实体表里挑，不查世界**）。
     *
     * <p>口径：<b>敌方 + 不在同一载具上 + 距离最近</b>。
     * ★ 玩家被<b>结构性</b>排除：{@code living} 来自账本，而玩家从不在账本里
     * （{@link UnitRegistry#tracked} 只回召唤物与目标物）—— 这就是 R42「玩家不会成为
     * 任何召唤物的目标」的落地方式：**不靠这里判一下**，而是候选里根本就没有玩家。
     */
    private LivingEntity nearestEnemy(Slime self, OwnershipTable.Owner owner,
                                      Map<UUID, LivingEntity> living) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        Side mySide = owner.side();
        String matchId = owner.matchId();
        for (Map.Entry<UUID, LivingEntity> entry : living.entrySet()) {
            LivingEntity candidate = entry.getValue();
            if (candidate == null || candidate == self || candidate.isDead() || !candidate.isValid()) {
                continue;
            }
            if (candidate instanceof Player) {
                continue;   // ★ 双保险（结构上账本里本来就没有玩家）
            }
            OwnershipTable.Owner targetOwner = registry.table().owner(entry.getKey());
            if (targetOwner == null || !matchId.equals(targetOwner.matchId())) {
                continue;
            }
            if (targetOwner.side() == null || targetOwner.side() == mySide) {
                continue;   // 未知 / 同阵营一律不打（policy/SideRule 同一口径）
            }
            if (self.getVehicle() != null && self.getVehicle().equals(candidate)) {
                continue;   // 不打卡在自己身下的载具
            }
            double distance;
            try {
                distance = self.getLocation().distanceSquared(candidate.getLocation());
            } catch (Throwable t) {
                continue;   // 跨世界等 ⇒ 这一位跳过
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** 这一次接触该造成多少伤害（判据全在纯类里，这里只负责取属性）。 */
    private double damageAgainst(Slime self, LivingEntity victim) {
        double attackDamage = 0.0;
        try {
            var instance = self.getAttribute(Attribute.ATTACK_DAMAGE);
            if (instance != null) {
                attackDamage = instance.getValue();
            }
        } catch (Throwable t) {
            logger.warning("[IC] 读方块怪的 ATTACK_DAMAGE 失败，本次不造成接触伤害：" + t);
            return 0.0;
        }
        boolean magma = self instanceof MagmaCube;
        // ★ 视线：非 Mob / 读不出来 ⇒ 按"看得见"处理
        //   （否则贴脸的怪永远打不到；原版 dealDamage 也要求视线，这里是保守方向）
        boolean sight = true;
        try {
            if (self instanceof Mob mob) {
                sight = mob.hasLineOfSight(victim);
            }
        } catch (Throwable ignored) {
            sight = true;
        }
        double distanceSq;
        try {
            distanceSq = self.getLocation().distanceSquared(victim.getLocation());
        } catch (Throwable t) {
            return 0.0;
        }
        return CubeMobTouch.touchDamage(self.getSize(), magma, attackDamage,
                self.getWidth(), distanceSq, victim.getWidth(), sight);
    }

    /** 一行摘要（启动日志用）。 */
    public static String describe() {
        return "方块怪接触伤害：熔岩精灵 / 烈焰熔炉的岩浆怪（原版对非玩家 0 输出）"
                + "每 " + CubeMobTouch.TOUCH_INTERVAL_TICKS + " tick 一次，只打账本里的敌方";
    }

    /**
     * 账本 → 实体（★ 与 {@code CombatDirector#resolveLedger} 同一口径：
     * 只遍历 {@link UnitRegistry#tracked(String)}，<b>不查世界</b>）。
     *
     * <p>★ 为什么这里自己解析一遍而不是复用 {@code CombatDirector} 的那一张：
     * 那张表是它的<b>内部状态</b>（`private`），为它开一个 public 访问器会让
     * 「谁可以改战斗状态」变得含糊。这一遍的代价是每次 {@code getEntity(UUID)} 的 O(1) 点查，
     * 而方块怪在同一时刻通常只有个位数 ⇒ 值得用一点重复换一层清晰的边界。
     */
    private Map<UUID, LivingEntity> resolveLedger(String matchId) {
        Map<UUID, LivingEntity> living = new LinkedHashMap<>();
        for (UUID id : registry.tracked(matchId)) {
            Entity entity = resolveEntity(id);
            if (entity instanceof LivingEntity livingEntity && livingEntity.isValid()
                    && !livingEntity.isDead()) {
                living.put(id, livingEntity);
            }
        }
        return living;
    }

    /** 按 UUID 点查实体（`O(1)`，**不是**枚举世界 —— 铁律 10）。 */
    private static Entity resolveEntity(UUID id) {
        return Bukkit.getEntity(id);
    }
}
