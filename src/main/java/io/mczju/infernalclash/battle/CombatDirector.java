package io.mczju.infernalclash.battle;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.MobGoals;
import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.card.MobGroupCatalog;
import io.mczju.infernalclash.card.SummonSpawner;
import io.mczju.infernalclash.match.MatchHooks;
import io.mczju.infernalclash.match.MatchPhase;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.TargetPolicy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★ <b>一场对局的战斗指挥</b>（P5）：唯一算目标的地方，并负责 AI 装载。
 *
 * <h2>T4 的修正落点</h2>
 * InfernalGuess 的缺陷是「生物 A 追着 B 时，更近的 C 出现也不会换」（`docs/07` §5.1 精确到 3 行）。
 * 本项目每 {@code combat.target-refresh-ticks}（默认 **10** = 0.5s）**重算一次**，
 * 并且选敌走 P0 就写好、有 20 项单测的 {@link TargetPolicy}
 * ⇒ 「更近的敌人出现 ⇒ 最多 0.5 秒内切换」是**算出来的**，不是靠忠实地追着旧目标。
 *
 * <h2>★ 铁律 10：候选集合来自账本，不查世界</h2>
 * 遍历 {@link UnitRegistry#tracked(String)}（本场登记过的 UUID）→ 解析实体 → 按 PDC 阵营筛
 * ⇒ 玩家**不可能**成为候选（玩家从不在账本里，R42 由结构保证）、友方也不会（R27）。
 * 没有 {@code getNearbyEntities} / {@code getEntities}。
 *
 * <h2>AI 装载（`docs/07` §5.4 的狐狸事故）</h2>
 * 扫该实体**实际挂着的 goal**（{@code MobGoals.getAllGoals}）用词根**全等**匹配判断
 * 有没有攻击类（{@link AttackGoalDetector}）：
 * <ul>
 *   <li><b>有</b> ⇒ **绝不**补我们自己的 drive goal（补了会顶掉它的近战 goal，
 *       变成「走到敌人面前却不咬人」——这就是狐狸事故）；</li>
 *   <li><b>没有</b> ⇒ 补一个**最低优先级**的 {@link DriveGoal}；</li>
 *   <li>{@code mob-groups.yml} 的 {@code ai-mode: FORCE_DRIVE} ⇒ 强制补（用于确认没有攻击 goal 的单位）。</li>
 * </ul>
 */
public final class CombatDirector implements MatchHooks {

    private final Plugin plugin;
    private final String matchId;
    private final UnitRegistry registry;
    private final MobGroupCatalog groups;
    private final int refreshTicks;
    private final Logger logger;

    /**
     * ★★ <b>卡池</b>（2026-10-17 第十二轮新增，缺陷 P1-a 的接线）——
     * 用来把「实体 PDC 里的卡 id」翻成「这个召唤物选目标时优不优先打建筑」
     * （{@code Card.Summon#affectedByBuilding}，键 {@code summon.affected-by-building}）。
     *
     * <p>★ 为什么是**查卡池**而不是"召唤时把布尔写进 PDC"：后者是**第二个事实来源**
     * （卡 YAML 改了、场上的老实体不跟着变，而两边都看起来是对的）。
     * 只存 id ⇒ 语义永远只有一处（见 {@code UnitRegistry#KEY_CARD} 的 javadoc）。
     * <p>★ 可以为 {@code null}（离线夹具）：那时一律按 {@code false}
     * （= 无视嘲讽、只比距离），也就是这个字段存在之前的行为。
     */
    private final CardCatalog catalog;

    private final Map<UUID, SummonCombat> units = new LinkedHashMap<>();
    private final Map<UUID, DriveGoal> driveGoals = new LinkedHashMap<>();

    /**
     * ★★★ <b>载具驱动 goal 的记账</b>（2026-10-17 批次 3 收尾新增）——
     * 键 = **载具**（僵尸马 / 炽足兽）的 UUID，值 = 装在它身上的 {@link MountDriveGoal}。
     *
     * <p>★ 与 {@link #driveGoals} 分开记的理由：两者的**判据完全不同**
     * （前者读自己/乘客的目标、后者读自己的目标），而且同一个实体会不会同时出现在两张表里
     * 是一个**要被测试钉住**的问题 —— 载具自己没有攻击 goal ⇒ 它只该进这张表。
     * 分开记之后，{@code onStop} 的卸载数量也能分别对账。
     */
    private final Map<UUID, MountDriveGoal> mountGoals = new LinkedHashMap<>();

    /**
     * ★★ <b>坐骑速度斜坡</b>（前储君「未攻击时每 2s +20%、上限 +100%、命中清零」）——
     * 2026-10-17 批次 3 收尾新增（用户第 4 条）。
     *
     * <p>★ 它需要两样东西才能跑：①「乘客的战斗状态」（判"这一 tick 有没有攻击到敌人"）
     * ②「写实体的移动速度」（走 {@code SummonSpawner#applySpeedScale}）。
     * 两者在本类里都有 ⇒ 由本类持有并在 {@link #syncSummons} 的末尾调一次。
     * <p>★ 可以为 {@code null}（离线夹具 / 没接 spawner 的调用点）：那时不跑斜坡，
     * <b>其它一切照常</b>（斜坡是"锦上添花"，不该让它挡住战斗指挥）。
     */
    private final MountSpeedRampService speedRamps;

    /**
     * ★★★ <b>递增伤害</b>（2026-10-18 新增，烈焰守卫的第三格）。
     *
     * <p>判据在纯状态机 {@code policy/RampDamageTrack}；本类只负责每 tick 喂一次。
     * ★ 可以为 {@code null}（没有卡池的离线夹具）：那时这条机制不跑，其余一切照常
     * （与 {@link #speedRamps} 同一口径）。
     */
    private final RampDamageService rampDamage;

    /** ★★★ 轰炸机的飞行接管（2026-10-18 新增，怨恨轰炸机）；{@code null} = 没有卡池（离线夹具）。 */
    private final BomberFlightService bombers;

    /** ★★★ 穿透射线（2026-10-21 新增，劲弩手）；{@code null} = 没有卡池（离线夹具）。 */
    private final RayAttackService rays;

    private int tickCounter;
    private int lastLoggedUnitCount = -1;

    public CombatDirector(Plugin plugin, String matchId, UnitRegistry registry,
                          MobGroupCatalog groups, int refreshTicks) {
        this(plugin, matchId, registry, groups, refreshTicks, null);
    }

    /**
     * @param catalog 卡池（读「这张卡受不受嘲讽影响」用）；{@code null} 允许 ⇒ 一律按 {@code false}
     */
    public CombatDirector(Plugin plugin, String matchId, UnitRegistry registry,
                          MobGroupCatalog groups, int refreshTicks, CardCatalog catalog) {
        this(plugin, matchId, registry, groups, refreshTicks, catalog, null);
    }

    /**
     * ★★ <b>完整构造</b>（2026-10-17 批次 3 收尾新增）——
     * 多传一个 {@link SummonSpawner}，用来给「坐骑速度斜坡」写 {@code MOVEMENT_SPEED}。
     *
     * @param catalog 卡池（读「受不受嘲讽影响」/「要不要跑速度斜坡」）
     * @param spawner ★ 召唤器；{@code null} ⇒ <b>不跑速度斜坡</b>（其余一切照常 ——
     *                斜坡是锦上添花，不该让它挡住战斗指挥）
     */
    public CombatDirector(Plugin plugin, String matchId, UnitRegistry registry,
                          MobGroupCatalog groups, int refreshTicks, CardCatalog catalog,
                          SummonSpawner spawner) {
        this.plugin = plugin;
        this.matchId = matchId;
        this.registry = registry;
        this.groups = groups;
        this.refreshTicks = Math.max(1, refreshTicks);
        this.catalog = catalog;
        this.logger = plugin.getLogger();
        this.speedRamps = spawner == null ? null
                : new MountSpeedRampService(registry, spawner, catalog, logger);
        // ★ 只在有卡池时建（没有卡池就查不到 `summon.ramping-damage`）
        this.rampDamage = catalog == null ? null
                : new RampDamageService(registry, catalog, logger);
        this.bombers = catalog == null ? null
                : new BomberFlightService(registry, catalog, logger);
        // ★★★ 2026-10-21（用户裁定）：穿透射线的执行层（劲弩手 / 劫掠堡垒的乘客）。
        //   ★ 只在有卡池时建（射线是**卡级配置** `summon.ray`）⇒ 离线夹具传 null 时这条机制不跑。
        this.rays = catalog == null ? null : new RayAttackService(registry, catalog, logger);
        // ★★★ 2026-10-18（用户第 3 条的第二层）：每局一个「自己人不打自己人」的伤害守卫
        //   ⇒ 即使原版 AI 在某个缝隙里让召唤物把同侧单位/目标物当成目标，那一下也打不出伤害。
        //   ★ 必须在这里 `registerEvents`：本类**不是 Listener**，写在它上面的 @EventHandler
        //     是死代码（本项目已经踩过一次，见 ObjectiveService 的同类注释）。
        this.allyDamageGuard = new AllyDamageGuard(registry, logger);
        try {
            plugin.getServer().getPluginManager().registerEvents(allyDamageGuard, plugin);
        } catch (Throwable t) {
            logger.warning("[IC] 「自己人不打自己人」守卫注册失败，同侧误伤仍可能造成伤害："
                    + t + "（只影响这一条兜底，不影响索敌）");
        }
    }

    /** ★ 同侧伤害守卫（每局一个实例，见构造器的注册与 {@link #onStop} 的注销）。 */
    private final AllyDamageGuard allyDamageGuard;

    @Override
    public void onTick(MatchPhase phase, int battleTick) {
        // 只有战斗期才打架：准备阶段不该有人开始追杀
        if (phase != MatchPhase.BATTLE && phase != MatchPhase.OVERTIME) {
            return;
        }
        Map<UUID, LivingEntity> living = resolveLedger();
        syncSummons(living);
        if (units.isEmpty()) {
            return;
        }

        boolean refresh = (++tickCounter % refreshTicks) == 0;
        for (SummonCombat unit : units.values()) {
            if (unit.mob().isDead() || !unit.mob().isValid()) {
                continue;
            }
            // ★★ 2026-10-18（全卡审计）：**护送型单位**（蒸汽精灵）的目标是**友军**，不是敌人。
            //   docs/19 第 176 行「只索敌己方目标、不攻击」——旧实现给它挑敌方目标
            //   ⇒ 它会一路朝敌人走（传说卡往敌人家门口送）。
            boolean escort = io.mczju.infernalclash.policy.EscortRule.isEscort(
                    registry == null ? null : registry.cardIdOf(unit.mob()));
            unit.setEscort(escort);
            if (refresh) {
                List<TargetPolicy.Candidate> candidates = escort
                        ? allyCandidatesFor(unit, living)
                        : candidatesFor(unit, living);
                if (unit.retarget(candidates, living)) {
                    logger.info("[IC] 换目标：" + unit.mob().getType() + "（" + unit.side() + "）→ "
                            + (unit.targetId() == null ? "无（没有可打的目标）"
                            : unit.targetId() + "｜距离 " + String.format("%.1f", distance(unit)))
                            + (escort ? "｜护送型（目标是友军）" : ""));
                }
            }
            // ★★★ 2026-10-17 第十三轮（用户实机报的缺陷 ②：「**召唤物刚被召唤时仍有几秒
            //   会索敌玩家**」）：`reassert()` 从「只在非刷新 tick 跑」改成 **每 tick 都跑**。
            //
            //   旧实现是 `else { unit.reassert(); }` —— 也就是说**刷新那一 tick 不兜底**：
            //   而刷新那一 tick 恰好是最危险的一 tick（`retarget` 刚把我们的目标写进去，
            //   原版的目标 goal 紧接着又跑一次、可能把**玩家**捡回来）
            //   ⇒ 那一 tick 结束时实体挂着的就是玩家，而且要等到**下一个**非刷新 tick 才纠正
            //   ⇒ 用户观测到的「0.5~1s 的窗口」正好是这个跨度的上界（刷新周期 10 tick = 0.5s）。
            //
            //   ★ 每 tick 都调它的代价很低：`reassert` 只做「读当前目标 → 一次纯判据 →
            //     不一致才写实体」，一致时**什么都不写**（KEEP）⇒ 不会干扰原版行为。
            //   ★★ 2026-10-18：**护送型单位**（蒸汽精灵）的函数体自己会跳过 —— 见
            //      `SummonCombat#setEscort`。这里**保持无条件调用**：那正是
            //      `CombatDirectorReassertWiringTest` 用字节码钉住的形状（兜底必须在分支外面），
            //      否则「召唤物刚被召唤时会索敌玩家」那个缺陷会以另一种方式回来。
            unit.reassert();
            // ★★★ 2026-10-18（用户第 2 条）：「**死灵法师锁定后应静止（远程怪跑去近战送）**」。
            //   为什么放在 `reassert()` **之后**、而且**每 tick**：
            //   ① 之后 —— 这一 tick 的目标刚刚被钉好，站定判据要读的是**这一 tick 的目标**；
            //   ② 每 tick —— 原版攻击 goal 是每 tick 重新下发寻路的，只拦一次等于没拦。
            //   ★ 判据在纯类 `policy/HoldDistance`（离线逐格可测），这里只负责调用。
            //   ★ 没写这一格的 50 张卡 ⇒ `shouldHoldGround()` 恒 false ⇒ **零额外调用**
            //     （`holdGround()` 会在第一 tick 记一次"未站定"，不打日志）。
            unit.holdGround();
            // ★★ 2026-10-19 用户裁定：「铜傀儡…**无目标时站定（不漫游）**」。
            //   ★ 放在**循环体最末**（reassert / holdGround 之后），两个理由：
            //     ① 「站定」必须读**这一 tick 已经钉好的目标**（否则会用上一拍的）；
            //     ② ★ 不能放在 `reassert()` **前面**：那条 `escort && …` 会造出一个
            //        "恰好跳过 reassert" 的候选跳转目标 ⇒ `CombatDirectorReassertWiringTest`
            //        的**相对跳转**判据假红（实测过：callAt=325、跳转目标 328）。
            //   ★ 非护送型单位是 no-op —— 其余召唤卡一行行为都不变。
            unit.idleStand(escort && unit.targetId() == null);
        }
    }

    /**
     * ★★ 2026-10-18：<b>骑手真的打到了敌人</b> ⇒ 该载具的速度加成清零
     * （{@code docs/19} 第 106 行「攻击到敌人时清除该加成」）。
     *
     * <p>与老的"换目标"判据并存：换目标仍然算（信标/防御塔在账本里，所以打塔也算），
     * 这里补的是**持续攻击同一个目标**那一格（旧判据永远等不到）。
     *
     * <p>★ 只认账本里的实体；只有"这个实体确实是乘客（{@code getVehicle() != null}）"才算，
     * 因此普通召唤物打人不会误触发任何东西。
     */
    @Override
    public void onRiderHit(Entity damager) {
        if (speedRamps == null || registry == null || damager == null) {
            return;
        }
        if (!registry.isTracked(damager.getUniqueId())) {
            return;
        }
        Entity vehicle = damager.getVehicle();
        if (vehicle instanceof LivingEntity mount) {
            speedRamps.onAttacked(mount.getUniqueId());
        }
    }

    @Override
    public void onStop(String reason) {        // ★ 注销同侧伤害守卫（与构造器里的 registerEvents 成对；不注销 = 每局留一个 Listener）
        try {
            org.bukkit.event.HandlerList.unregisterAll(allyDamageGuard);
        } catch (Throwable ignored) {
            // 已注销 / 插件停用中 ⇒ 收尾不因此中断
        }
        int removed = 0;
        removed += uninstall(driveGoals, reason);
        removed += uninstallMountGoals(reason);
        units.clear();
        driveGoals.clear();
        mountGoals.clear();
        if (speedRamps != null) {
            speedRamps.clear();
        }
        if (rampDamage != null) {
            rampDamage.clear();   // ★ 递增伤害的记账也一起清（幂等）
        }
        if (bombers != null) {
            bombers.clear();      // ★ 轰炸机的记账（`aiDisabled` / `lastDrop`）
        }
        if (removed > 0) {
            logger.info("[IC] 已卸载本场自建的 drive goal " + removed + " 个（" + reason
                    + "，matchId=" + matchId + "）");
        }
    }

    /** 卸掉一批 self-drive goal（返回真的摘掉几个）。 */
    private int uninstall(Map<UUID, DriveGoal> goals, String reason) {
        int removed = 0;
        for (Map.Entry<UUID, DriveGoal> entry : goals.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof Mob mob && mob.isValid()) {
                try {
                    MobGoals mobGoals = Bukkit.getMobGoals();
                    if (mobGoals != null) {
                        mobGoals.removeGoal(mob, entry.getValue().key());
                        removed++;
                    }
                } catch (Throwable ignored) {
                    // 实体已卸载时移除会抛 —— 收尾不因此中断
                }
            }
        }
        return removed;
    }

    /** 卸掉载具驱动（与 {@link #uninstall} 同一形状，只是句柄类型不同）。 */
    private int uninstallMountGoals(String reason) {
        int removed = 0;
        for (Map.Entry<UUID, MountDriveGoal> entry : mountGoals.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof Mob mob && mob.isValid()) {
                try {
                    MobGoals mobGoals = Bukkit.getMobGoals();
                    if (mobGoals != null) {
                        mobGoals.removeGoal(mob, entry.getValue().key());
                        removed++;
                    }
                } catch (Throwable ignored) {
                    // 同上
                }
            }
        }
        return removed;
    }

    /** 已装载载具驱动的数量（日志 / 探针 / 测试用）。 */
    public int mountGoalCount() {
        return mountGoals.size();
    }

    /** 已跑速度斜坡的载具数（探针 / 测试用；没接 spawner ⇒ 恒为 0）。 */
    public int speedRampCount() {
        return speedRamps == null ? 0 : speedRamps.trackedCount();
    }

    /** 某个载具当前的斜坡倍率（探针用；没有 ⇒ {@code null}）。 */
    public Double speedRampMultiplier(UUID mountId) {
        return speedRamps == null ? null : speedRamps.multiplierOf(mountId);
    }

    /** 已接管的召唤物数量（日志 / 探针用）。 */
    public int unitCount() {
        return units.size();
    }

    /** 取某个召唤物的战斗状态（探针用）。 */
    public SummonCombat unit(UUID id) {
        return units.get(id);
    }

    /** 账本 → 实体（解析一次，整轮复用）。 */
    private Map<UUID, LivingEntity> resolveLedger() {
        Map<UUID, LivingEntity> living = new LinkedHashMap<>();
        for (UUID id : registry.tracked(matchId)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity livingEntity && livingEntity.isValid()
                    && !livingEntity.isDead()) {
                living.put(id, livingEntity);
            }
        }
        return living;
    }

    /** 账本里新增的、**有主人**（= 召唤物）的可移动实体 ⇒ 建立战斗状态 + AI 装载。 */
    private void syncSummons(Map<UUID, LivingEntity> living) {
        for (Map.Entry<UUID, LivingEntity> entry : living.entrySet()) {
            if (units.containsKey(entry.getKey()) || !(entry.getValue() instanceof Mob mob)) {
                continue;
            }
            OwnershipTable.Owner owner = registry.table().owner(entry.getKey());
            if (owner == null || owner.player() == null) {
                continue;   // 目标物（塔 / 信标）的 owner 是 null —— 它们不主动打架
            }
            SummonCombat combat = new SummonCombat(mob, matchId, owner.side(), owner.player(),
                    // ★★★ 2026-10-18：**选敌模式改成读卡**（过去硬编码 SWITCH）。
                    //   出处 = docs/19 第 113 行烈焰守卫「第一次锁定后不再改变目标（LOCK_ON）」；
                    //   机制本身（`policy/TargetPolicy.Mode.LOCK_ON`）早就实现了，缺的只是这一行接线。
                    targetingModeOf(entry.getKey()),
                    // ★ 2026-10-17 第十二轮：把账本传进去 —— reassert 要靠它判
                    //   「当下挂着的目标是不是友方」（见 SummonCombat#isAlly）
                    registry,
                    // ★★ 2026-10-17 第十二轮（缺陷 P1-a 的落点）：**这个召唤物**选目标时
                    //   优不优先打建筑 —— 由实体 PDC 里的卡 id 去卡池现查
                    //   （`summon.affected-by-building`，出厂只有攻城兽 / 炽足兽骑士为 true）
                    affectedByBuildingOf(entry.getKey(), mob),
                    // ★★★ 2026-10-18：**索敌半径**（docs/19 第 113 行「半径 12 的圆形」）
                    targetRadiusOf(entry.getKey()),
                    // ★★★ 2026-10-18（用户第 2 条「死灵法师锁定后应静止」）：
                    //   **锁定后站定的距离**（键 `summon.stop-when-target-within`）——
                    //   目标进 N 格就每 tick 抹掉这一 tick 的移动，但照常索敌/攻击。
                    stopWithinOf(entry.getKey()));
            units.put(entry.getKey(), combat);
            loadAi(combat);
            // ★★★ 2026-10-18（用户第 3 条：「**我方召唤物还是会有两秒左右发呆的过程**」）：
            //   新单位**在出生的同一 tick 就把目标算好并写进实体**，不再等下一次
            //   `target-refresh-ticks` 刷新（旧实现最多要等 10 tick，那段时间里
            //   原版 AI 会自己挑一个目标 —— 包括**自家防御塔**）。
            //   ★ 这一段只对"刚建出来的"单位跑（`units.containsKey` 已经过滤），
            //     所以不会把刷新节拍打乱。
            try {
                if (combat.retarget(candidatesFor(combat, living), living)) {
                    logger.info("[IC] 换目标（出生即锁定）：" + mob.getType() + "（" + owner.side()
                            + "）→ " + (combat.targetId() == null ? "无（没有可打的目标）"
                            : combat.targetId() + "｜距离 " + String.format("%.1f", distance(combat))));
                }
            } catch (Throwable t) {
                logger.warning("[IC] 出生即锁定失败（" + mob.getType() + "）：" + t
                        + "，退回下一个刷新周期再算");
            }
        }
        if (units.size() != lastLoggedUnitCount) {
            lastLoggedUnitCount = units.size();
            logger.info("[IC] 战斗指挥已接管 " + units.size() + " 个召唤物（matchId=" + matchId + "）");
        }
        // ★★★ 载具驱动：**必须等所有乘客都建好战斗状态之后**再装（见方法 javadoc）
        installMountGoals();
        // ★★★ 坐骑速度斜坡（前储君）：同一时机 —— 它也要读乘客的战斗状态（判"有没有攻击"）
        if (speedRamps != null) {
            speedRamps.onTick(units);
        }
        // ★★★ 递增伤害（烈焰守卫）：同一时机 —— 它也要读「这一 tick 的目标」
        if (rampDamage != null) {
            rampDamage.onTick(units, tickCounter);
        }
        // ★★★ 2026-10-18：轰炸机的飞行接管（关原版 AI ＋ 飞到目标上方 ＋ 垂直投弹）
        if (bombers != null) {
            bombers.onTick(units, tickCounter);
        }
        // ★★★ 2026-10-21：穿透射线（劲弩手 / 劫掠堡垒的乘客）——
        //   同一时机：它要读「这一 tick 的目标」（`SummonCombat#resolvedTarget`）。
        //   ★ 射击行为本身已由 `RangedAttackInterceptor` 拦掉（它按同一份 `summon.ray` 判），
        //     所以这里只管"打出去的那一根"。
        if (rays != null) {
            rays.onTick(units, tickCounter);
        }
    }

    /**
     * ★★★ <b>给「载着本场乘客」的载具装上 {@link MountDriveGoal}</b>
     * （2026-10-17 批次 3 收尾新增，用户第 1 条「请你做载具驱动」）。
     *
     * <h2>为什么必须放在 {@link #syncSummons} 的**最后一步**（而不是装载 passenger 时顺手装）</h2>
     * 原版有一条硬规则：<b>乘客的寻路不会移动载具</b> ⇒ 车必须自己开。
     * 而本方法要读的是「<b>乘客的战斗状态</b>」——
     * {@code SummonSpawner} 按 <b>组顺序</b>生成（载具组先声明），但
     * {@code syncSummons} 遍历的是<b>账本顺序</b>（{@link java.util.LinkedHashMap}）：
     * 先建谁的战斗状态**没有保证** ⇒ 若在装载乘客的那一刻顺手去读它自己的战斗状态，
     * 可能读到 {@code null}（它自己还没登记）。
     * ⇒ 放到"这一轮的所有召唤物都建好之后"再统一装：**结构上**不可能读到 null。
     *
     * <h2>归属与幂等</h2>
     * <ul>
     *   <li>只处理<b>载着本场单位</b>的载具（乘客能在 {@link #units} 里查到）；</li>
     *   <li>同一个载具只装一个（{@code mountGoals.containsKey} 保证幂等，
     *       重连 / 重载场景不会叠 second goal —— 叠了会互相顶掉寻路）；</li>
     *   <li>★ 若载具自己也在 {@link #driveGoals} 里（理论上不该：载具没有攻击 goal ⇒
     *       它会进 driveGoals；这里**不冲突**，因为两个 goal 的类型都是 MOVE
     *       ⇒ Paper 只会跑一个 —— 所以下面会先把它从 driveGoals 里摘掉再装载具驱动）。
     *       ★ 这一格是**刻意处理**的：两个 MOVE goal 抢同一条寻路的表现是
     *       「车一会儿朝乘客的目标走、一会儿朝自己的目标走」，实机上看起来像"抽搐"。</li>
     * </ul>
     */
    private void installMountGoals() {
        for (SummonCombat unit : units.values()) {
            Mob passenger = unit.mob();
            if (passenger == null || passenger.isDead() || !passenger.isValid()) {
                continue;
            }
            Entity vehicle = passenger.getVehicle();
            if (!(vehicle instanceof Mob mount) || mount.isDead() || !mount.isValid()) {
                continue;   // 这个单位不是乘客（绝大多数）
            }
            if (mountGoals.containsKey(mount.getUniqueId())) {
                continue;   // 幂等
            }
            // ★ 载具自己**同时**被装了 self-drive ⇒ 先摘掉它（两个 MOVE goal 会互相顶掉寻路）
            DriveGoal stale = driveGoals.remove(mount.getUniqueId());
            try {
                MobGoals mobGoals = Bukkit.getMobGoals();
                if (mobGoals == null) {
                    logger.warning("[IC] 无法装载载具驱动：MobGoals 服务不可用（matchId=" + matchId + "）");
                    return;
                }
                if (stale != null) {
                    mobGoals.removeGoal(mount, stale.key());
                    logger.info("[IC] 载具驱动替换自身驱动：" + mount.getType()
                            + " 上原来那条 self-drive 已摘掉（两个 MOVE goal 会互相顶掉寻路）");
                }
                MountDriveGoal goal = new MountDriveGoal(plugin, mount, Map.copyOf(units));
                mobGoals.removeGoal(mount, goal.key());   // 幂等的第二道
                mobGoals.addGoal(mount, MountDriveGoal.PRIORITY, goal);
                mountGoals.put(mount.getUniqueId(), goal);
                logger.info("[IC] 已装载载具驱动（优先级 " + MountDriveGoal.PRIORITY + "）："
                        + mount.getType() + " ← 乘客 " + passenger.getType()
                        + "（移动判据 = 乘客有没有目标 —— 用户裁定"
                        + "「只在乘客有目标时才移动 / 乘客打谁、载具就往谁走」）");
            } catch (Throwable t) {
                // ★ 装载失败不能让整轮 tick 挂掉（那会让所有单位停止索敌）
                logger.warning("[IC] 装载载具驱动失败（" + mount.getType() + "）：" + t);
            }
        }
    }

    /**
     * ★★ <b>这个召唤物选目标时优不优先打建筑</b>（2026-10-17 第十二轮新增，缺陷 P1-a；
     * 2026-10-19 随「建筑」改名一起更新）。
     *
     * <h2>三步，每一步的失败方向都是「按 false（只看距离）处理」</h2>
     * <ol>
     *   <li>实体 PDC 里没有卡 id（老实体 / 非召唤卡生成的单位）⇒ {@code false}；</li>
     *   <li>卡池拿不到，或卡池里没有这个 id（卡被删了 / 热重载换了卡池）⇒ {@code false}
     *       ＋ <b>一行 WARN</b>（这是"配置与场上实体分叉"的信号，不该静默）；</li>
     *   <li>否则取 {@code Card.Summon#affectedByBuilding()}。</li>
     * </ol>
     * ★ 为什么失败方向必须是 {@code false}：{@code false} = 【Q6】裁定的读法 A
     * （「无视嘲讽即嘲讽不存在，直接比距离」）＝ <b>R37 的绝大多数</b>，
     * 也是「没写这一格的卡」的行为 ⇒ 出任何问题都只退化成"常态"，绝不凭空多出一条玩法。
     *
     * @param id  召唤物 UUID（只用于日志）
     * @param mob 召唤物实体（读 PDC 用）
     */
    boolean affectedByBuildingOf(UUID id, Mob mob) {
        String cardId = registry == null ? null : registry.cardIdOf(mob);
        if (cardId == null) {
            return false;
        }
        if (catalog == null) {
            return false;
        }
        Card card = catalog.cards().get(cardId);
        if (card == null || card.summon() == null) {
            logger.warning("[IC] 召唤物上的卡 id " + cardId + " 在卡池里查不到（实体 "
                    + id + "），「优先攻击建筑」按 false（只看距离）处理。"
                    + "常见原因：那张卡被删了，或热重载换过卡池");
            return false;
        }
        return card.summon().affectedByBuilding();
    }

    /**
     * ★★ <b>这个召唤物的选敌模式</b>（2026-10-18 新增，键 {@code summon.targeting}）。
     *
     * <p>出处 = {@code docs/19} 第 113 行烈焰守卫「**第一次锁定后不再改变目标**（LOCK_ON）
     * 直到被眩晕或目标死亡」。★ 机制本身（{@link TargetPolicy.Mode#LOCK_ON}）早在 P0 就实现了
     * —— 缺的一直是这行接线（过去这里硬编码 {@code Mode.SWITCH}）。
     *
     * <p>★ 失败方向：查不到卡 / 卡池为 {@code null} ⇒ {@code SWITCH}（= 既有行为，绝不凭空多玩法）。
     *
     * @param id 召唤物 UUID（只用于日志）
     */
    TargetPolicy.Mode targetingModeOf(UUID id) {
        Card card = cardOf(id, "选敌模式");
        if (card == null || card.summon() == null) {
            return TargetPolicy.Mode.SWITCH;
        }
        return card.summon().targeting();
    }

    /**
     * ★★ <b>这个召唤物的索敌半径</b>（2026-10-18 新增，键 {@code summon.target-radius}）。
     *
     * <p>出处 = {@code docs/19} 第 113 行「索敌为**半径 12 的圆形**」。
     * ★ 失败方向：查不到 ⇒ {@code null} = 不限（既有行为）。
     */
    Double targetRadiusOf(UUID id) {
        Card card = cardOf(id, "索敌半径");
        if (card == null || card.summon() == null) {
            return null;
        }
        return card.summon().targetRadius();
    }

    /**
     * ★★★ <b>这个召唤物「锁定后站定」的距离</b>（2026-10-18 新增，键
     * {@code summon.stop-when-target-within}）。
     *
     * <p>出处 = 用户第 2 条「**死灵法师锁定后应静止（远程怪跑去近战送）**」，
     * 以及我追问「N 取多少」时用户的裁定「**N 等于它的火球索敌射程**」⇒ 死灵法师 = 8。
     * ★ 失败方向：查不到卡 / 卡池为 {@code null} ⇒ {@code null} = **不站定**（既有行为，
     * 绝不凭空多出一条玩法）。
     */
    Double stopWithinOf(UUID id) {
        Card card = cardOf(id, "锁定后站定");
        if (card == null || card.summon() == null) {
            return null;
        }
        return card.summon().stopWhenTargetWithin();
    }

    /**
     * 按实体 PDC 里的卡 id 反查卡定义（{@code null} ⇒ 已告警）。
     *
     * <p>★ 三个「卡上这一格」的查询（受嘲讽 / 选敌模式 / 索敌半径）共用它 ——
     * 分成三份会各写一遍"卡池 null / 卡被删了"的告警，迟早分叉。
     */
    private Card cardOf(UUID id, String whatFor) {
        String cardId = registry == null ? null : registry.cardIdOf(Bukkit.getEntity(id));
        if (cardId == null || catalog == null) {
            return null;
        }
        Card card = catalog.cards().get(cardId);
        if (card == null) {
            logger.warning("[IC] 召唤物上的卡 id " + cardId + " 在卡池里查不到（实体 "
                    + id + "），「" + whatFor + "」按缺省处理。"
                    + "常见原因：那张卡被删了，或热重载换过卡池");
            return null;
        }
        return card;
    }

    /**
     * AI 装载：判「它自己有没有攻击 goal」，决定要不要补我们的 drive goal。
     *
     * <p>★ 判据是**扫实际挂着的 goal**（不是白名单）：Paper 的 goal key 从 NMS 类名推导，
     * 狐狸的近战 goal 是 {@code fox_melee_attack} 而不是 {@code melee_attack}。
     */
    private void loadAi(SummonCombat combat) {
        Mob mob = combat.mob();
        MobGroupCatalog.AiMode mode = aiModeOf(mob);
        List<String> keys = goalKeys(mob);
        boolean hasAttack = AttackGoalDetector.hasAttackGoal(keys);

        // ★★★ 2026-10-21（用户裁定，逐字）：「对于**劫掠者以及其他所有远程召唤物
        //   （使用弓、弩的怪，冰术师等）**，应用**死灵法师同款**『敌人进射程站定不动』的 ai」。
        //   ★ 判据是**扫它实际挂着的 goal**（`ranged_bow_attack` / `ranged_crossbow_attack` /
        //     `drowned_trident_attack`），不是实体白名单 —— 与上面那条"狐狸事故"同一个道理。
        //   ★ 卡上自己写了 `summon.stop-when-target-within` 的（死灵法师）**以卡为准**，
        //     这里只在"没写"时才补默认值 8.0（见 `policy/RangedStopRule`）。
        Double stopWithin = io.mczju.infernalclash.policy.RangedStopRule
                .stopDistanceFor(combat.stopWhenTargetWithin(),
                        io.mczju.infernalclash.policy.RangedStopRule.isRanged(keys));
        combat.applyRangedStopDistance(stopWithin);

        if (mode == MobGroupCatalog.AiMode.AUTO && hasAttack) {
            logger.info("[IC] 不补 drive goal：" + mob.getType()
                    + " 自带攻击类 goal " + AttackGoalDetector.attackGoals(keys)
                    + "（补了会顶掉它自己的近战 goal —— 狐狸事故）");
            return;
        }
        installDriveGoal(combat, mode, keys, hasAttack);
    }

    private void installDriveGoal(SummonCombat combat, MobGroupCatalog.AiMode mode,
                                  List<String> keys, boolean hasAttack) {
        Mob mob = combat.mob();
        try {
            MobGoals mobGoals = Bukkit.getMobGoals();
            if (mobGoals == null) {
                logger.warning("[IC] 无法装载 AI：MobGoals 服务不可用（matchId=" + matchId + "）");
                return;
            }
            DriveGoal goal = new DriveGoal(plugin, combat);
            // 幂等：先卸掉我们自己上一次装的（重载 / 重连场景）
            mobGoals.removeGoal(mob, goal.key());
            mobGoals.addGoal(mob, DriveGoal.PRIORITY, goal);
            driveGoals.put(mob.getUniqueId(), goal);
            combat.markDriveInstalled(true);
            logger.info("[IC] 已补 drive goal（优先级 " + DriveGoal.PRIORITY + "）：" + mob.getType()
                    + "｜ai-mode=" + mode + "｜实际挂着的 goal=" + keys
                    + (hasAttack ? "（其中有攻击类，但 ai-mode 强制要求自建）" : "（无攻击类）"));
        } catch (Throwable t) {
            logger.warning("[IC] 装载 drive goal 失败（" + mob.getType() + "）：" + t);
        }
    }

    /** 该实体在 `mob-groups.yml` 里的 ai-mode（没登记 ⇒ {@code AUTO}）。 */
    private MobGroupCatalog.AiMode aiModeOf(Mob mob) {
        String name = mob.getType().name();
        if (groups != null && groups.has(name)) {
            return groups.require(name).aiMode();
        }
        return MobGroupCatalog.AiMode.AUTO;
    }

    /** 该实体**实际挂着**的 goal key（用于词根扫描）。 */
    private List<String> goalKeys(Mob mob) {
        List<String> keys = new ArrayList<>();
        try {
            MobGoals mobGoals = Bukkit.getMobGoals();
            if (mobGoals == null) {
                return keys;
            }
            for (Goal<Mob> goal : mobGoals.getAllGoals(mob)) {
                if (goal != null && goal.getKey() != null && goal.getKey().getNamespacedKey() != null) {
                    keys.add(goal.getKey().getNamespacedKey().getKey());
                }
            }
        } catch (Throwable ignored) {
            // 取不到就当"没有攻击 goal"（保守：补上自己的 drive，至少能动）
        }
        return keys;
    }

    /**
     * 构造某个召唤物的候选：**本场账本里、敌方、活着的**实体。
     *
     * <h2>★★ 三条剔除，逐条有出处（2026-10-17 第十二轮把判据抽成纯函数）</h2>
     * <ol>
     *   <li><b>不在账本里</b>（{@code owner == null}）⇒ 剔除 —— 玩家**从不进账本**，
     *       所以这一条就是 R42/T3「玩家不是合法目标」的**结构保证**；</li>
     *   <li>★ <b>玩家 UUID</b> ⇒ 剔除（**冗余的第二道保险**）。为什么结构保证之外还要显式判一次：
     *       用户 2026-10-17 实机仍然看到「敌方的实体，甚至我方的哨位（潜影贝）也依旧会锁定玩家」
     *       —— 说明「玩家不在账本里」这个前提**曾经被破坏过**（旧版本登记过玩家 / 别的路径塞过 UUID）。
     *       现在把「候选里不许出现玩家 UUID」变成一条**逐格可断言的纯函数判据 + 阴阳对照**
     *       （{@code battle/PlayerTargetExclusionTest}），即使前提被破坏也拦得住；</li>
     *   <li><b>友方</b>（{@code owner.side() == unit.side()}）⇒ 剔除（R27）。</li>
     * </ol>
     *
     * <p>★ <b>没有索敌半径</b>：R34~R38 只说「始终选择最近的」，没给半径 ⇒ 不自己发明一个数。
     * ★ 构造**只读账本**，不查世界（铁律 10）—— 已由 {@code BytecodeScopeGuardTest} 钉住。
     *
     * <p>★★ 判据抽成静态纯函数 {@link #buildCandidates}（而不是留在方法体里）的理由：
     * 这样「候选里不许出现玩家/友方」可以在**离线 JUnit** 里逐格断言（阴阳对照），
     * 而这一条恰恰是最贵的一类缺陷（上一轮就是"只在刷新周期纠一次"漏掉的）。
     */
    private List<TargetPolicy.Candidate> candidatesFor(SummonCombat unit, Map<UUID, LivingEntity> living) {
        List<TargetPolicy.Candidate> candidates = new ArrayList<>();
        // ★★ 2026-10-18：**索敌半径**（键 `summon.target-radius`；doc 出处 = 烈焰守卫「半径 12 的圆形」）。
        //   `null` = 不限（50 张卡的既有行为，一字未变）。
        //   ★ 判据写在平方距离上（不开放，边界判定不受浮点漂移影响）：
        //     `d² > r²` ⇒ 不算候选。**等于 r 仍然算**（"半径 12"含边线）。
        Double radius = unit.targetRadius();
        double radiusSquared = radius == null ? 0.0 : radius * radius;
        for (Map.Entry<UUID, LivingEntity> entry : living.entrySet()) {
            OwnershipTable.Owner owner = registry.table().owner(entry.getKey());
            LivingEntity entity = entry.getValue();
            if (!isLegalCandidate(owner, unit.side(), entry.getKey(), entity)) {
                continue;   // 不在本场 / 是玩家 / 是友方
            }
            double distanceSquared = unit.mob().getLocation().distanceSquared(entity.getLocation());
            if (radius != null && distanceSquared > radiusSquared) {
                continue;   // 超出这张卡的索敌半径
            }
            candidates.add(new TargetPolicy.Candidate(entry.getKey(), distanceSquared,
                    // ★★★ 2026-10-19：这一维从「有嘲讽」改成「是建筑」——
                    //   判据形状一字未变，只换取值来源（信标 / 防御塔由 ObjectiveUnit 打标，
                    //   6 张召唤卡由 `summon.building` 打标 ⇒ 集合与改名前**完全相等**）。
                    registry.building(entity)));
        }
        return candidates;
    }

    /**
     * ★★ 2026-10-18：<b>护送型单位的"目标" = 最近的同阵营单位</b>
     * （{@code docs/19} 第 176 行蒸汽精灵「**只索敌己方目标**、不攻击」）。
     *
     * <p>它与 {@link #candidatesFor} 的差别只有三处，都是刻意的：
     * <ol>
     *   <li>阵营判据反过来：<b>只要同阵营</b>（敌人一个都不收）；</li>
     *   <li>排除自己（否则它会"护送自己"，原地不动）；</li>
     *   <li>半径用 {@link io.mczju.infernalclash.policy.EscortRule#ESCORT_RADIUS}
     *       （文档没给"跟随距离"，这个数是**我取的**，理由写在那条常量上）。</li>
     * </ol>
     * ★ 仍然只读账本、不查世界（铁律 10）。
     */
    private List<TargetPolicy.Candidate> allyCandidatesFor(SummonCombat unit,
                                                           Map<UUID, LivingEntity> living) {
        List<TargetPolicy.Candidate> candidates = new ArrayList<>();
        if (living == null || unit == null || unit.mob() == null) {
            return candidates;
        }
        UUID self = unit.mob().getUniqueId();
        double maxSquared = io.mczju.infernalclash.policy.EscortRule.ESCORT_RADIUS
                * io.mczju.infernalclash.policy.EscortRule.ESCORT_RADIUS;
        for (Map.Entry<UUID, LivingEntity> entry : living.entrySet()) {
            if (entry.getKey().equals(self)) {
                continue;
            }
            OwnershipTable.Owner owner = registry.table().owner(entry.getKey());
            if (owner == null || owner.side() != unit.side()) {
                continue;   // 只护送自己人
            }
            // ★★★ 2026-10-19 用户裁定：「铜傀儡**不跟随防御塔**」⇒ 建筑不是护送对象。
            //   理由（玩法口径）：建筑自己是**不动的目标物**，跟着它等于站在原地等死；
            //   而"建筑"这个标签是**唯一**能同时覆盖信标 / 防御塔与那 6 张召唤卡的判据
            //   （见 `UnitRegistry#KEY_BUILDING`）⇒ 不另起一个"是不是目标物"的分支。
            if (registry.building(entry.getKey())) {
                continue;
            }
            double distanceSquared = unit.mob().getLocation()
                    .distanceSquared(entry.getValue().getLocation());
            if (distanceSquared > maxSquared) {
                continue;
            }
            candidates.add(new TargetPolicy.Candidate(entry.getKey(), distanceSquared, false));
        }
        return candidates;
    }

    /**
     * ★★ <b>「这个实体能不能当候选」的纯判据</b>（无 Bukkit 状态依赖，只吃三个事实）。
     *
     * @param owner       账本里的归属（{@code null} = 不是本场的实体）
     * @param ownSide      要选敌的那个单位的阵营
     * @param candidateId 候选的 UUID
     * @param candidate    候选实体（{@code null} 允许 —— 只用于判它是不是玩家）
     * @return 合法 ⇒ true
     */
    public static boolean isLegalCandidate(OwnershipTable.Owner owner, Side ownSide,
                                           UUID candidateId, Entity candidate) {
        if (candidateId == null || owner == null) {
            return false;   // 不在本场账本里（玩家从来不在 ⇒ R42/T3 的结构保证）
        }
        if (candidate instanceof Player) {
            return false;   // ★★ R42：任何一方、任何单位类型，玩家都不是合法目标
        }
        return owner.side() != ownSide;   // R27：友方永不成为候选
    }

    /**
     * 从「UUID → 实体」的视图里造候选集（纯函数版，可离线逐格断言）。
     *
     * <p>★ 与 {@link #candidatesFor} 的分工：本方法**不含任何 Bukkit 调用顺序的假设**
     * （距离与嘲讽由调用方以函数注入）⇒ 测试里可以用假实体与固定距离穷举
     * 「任何一方、任何单位类型的候选里都不许出现玩家 UUID」。
     *
     * @param living      本轮解析出来的「账本 UUID → 实体」（保序）
     * @param ownSide     选敌方的阵营
     * @param ownerOf     取某个 UUID 的归属
     * @param distanceSq  取某个 UUID 与自己的平方距离
     * @param buildingOf  取某个 UUID 算不算建筑
     */
    public static List<TargetPolicy.Candidate> buildCandidates(
                                                              Map<UUID, ? extends Entity> living,
                                                              Side ownSide,
                                                              java.util.function.Function<UUID, OwnershipTable.Owner> ownerOf,
                                                              java.util.function.ToDoubleFunction<UUID> distanceSq,
                                                              java.util.function.Predicate<UUID> buildingOf) {
        List<TargetPolicy.Candidate> candidates = new ArrayList<>();
        if (living == null || ownSide == null) {
            return candidates;
        }
        for (Map.Entry<UUID, ? extends Entity> entry : living.entrySet()) {
            if (!isLegalCandidate(ownerOf.apply(entry.getKey()), ownSide, entry.getKey(),
                    entry.getValue())) {
                continue;
            }
            candidates.add(new TargetPolicy.Candidate(entry.getKey(),
                    distanceSq.applyAsDouble(entry.getKey()), buildingOf.test(entry.getKey())));
        }
        return candidates;
    }

    private double distance(SummonCombat unit) {
        LivingEntity target = unit.resolvedTarget();
        return target == null ? 0.0 : Math.sqrt(unit.mob().getLocation().distanceSquared(target.getLocation()));
    }
}
