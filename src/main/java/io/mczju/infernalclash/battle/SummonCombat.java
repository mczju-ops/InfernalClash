package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.TargetPolicy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.memory.MemoryKey;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ★ <b>一个召唤物的战斗状态</b>：当前目标 + 把目标应用到实体上。
 *
 * <h2>为什么要把「算目标」与「用目标」放在一个类里</h2>
 * `docs/07` §5.3 的教训：InfernalGuess 的选敌逻辑被**四处**调用
 * （目标 goal / 驱动 goal / 山羊冲撞 / brain 型纠正），只改一处就会重新制造「走 C 打 B」。
 * ⇒ 本项目**只有一个地方算目标**（{@link #retarget}），结果存在这里；
 * TARGET 层（{@code setTarget}）和 MOVE 层（{@link DriveGoal}）**都读同一个字段**
 * ⇒ 「两层一致」由**结构**保证，而不是靠两处代码写得一样。
 *
 * <h2>三个已知坑的处理</h2>
 * <ul>
 *   <li>★ <b>brain 型的 {@code setTarget} 是空操作</b>（猪灵 / 猪灵蛮兵 / 疣猪兽 / 监守者）：
 *       它们的 {@code getTarget()} 读 brain，而 Bukkit 的 {@code MemoryKey} 里
 *       <b>没有</b>{@code ATTACK_TARGET}（`javap` 实测：只有 {@code ANGRY_AT} 与
 *       {@code ATTACK_TARGET_COOLDOWN}）⇒ 这些走 {@link MemoryKey#ANGRY_AT} 通道。</li>
 *   <li>★ <b>骷髅类的 {@code getTarget()} 恒为空</b>（精密猎手 / 冰术师 / 不休之魂 / 沙漠小队的焦骸）：
 *       我们**每轮都重新设一次**，天然就是那个兜底；另外 {@link #reassert()} 处理
 *       「两轮之间被清空」的情况。</li>
 *   <li>★ <b>玩家永不成为候选</b>：候选由调用方从**账本**里构造，而玩家从不在账本里
 *       ⇒ 这一条是**结构保证**，不是靠这里判一下。</li>
 * </ul>
 */
public final class SummonCombat {

    /**
     * ★ brain 型实体（{@code setTarget} 无效）—— 名单来自 `docs/07` §5.4 的实测，
     * **不是**我猜的。疣猪兽（HOGLIN）也在列，所以卡池已按 C1 把「攻城兽」换成北极熊。
     */
    private static final Set<EntityType> BRAIN_TYPES = Set.of(
            EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.HOGLIN,
            EntityType.ZOGLIN, EntityType.WARDEN);

    /**
     * ★★★ <b>本项目**每个召唤物各自**是否「受嘲讽影响」</b>
     * （2026-10-17 第十二轮新增，缺陷 P1-a「攻城兽不去打带嘲讽的塔，追着恼鬼跑」）。
     *
     * <h2>旧实现错在哪（一行硬编码）</h2>
     * 旧版本这里是一条常量 {@code AFFECTED_BY_TAUNT = false}，注释写着
     * 「『受嘲讽影响』的逐卡键目前不存在于任何卡牌 ⇒ 现在一律 false」——
     * 而 {@code docs/19} 第 60 行**明写**攻城兽「<b>优先攻击带嘲讽的单位</b>」
     * ⇒ 结果就是：**卡表要求"这张卡优先打建筑"，配置里却没有这一格，代码里还把它写死成 false**
     * ⇒ 建筑维度被**完全跳过**（{@link TargetPolicy} 的 {@code BUILDING_THEN_DISTANCE} 根本用不上）
     * ⇒ 攻城兽"谁近打谁"，天上那只恼鬼永远赢过地上的塔。
     *
     * <h2>现在的口径（三条，逐条有出处）</h2>
     * <ul>
     *   <li><b>缺省 {@code false}</b> = 不看建筑、只比距离 —— 【Q6】裁定的读法 A
     *       （用户原话「<b>无视嘲讽即嘲讽不存在，直接比距离</b>」）＋
     *       R37「<b>绝大部分</b>召唤物不受优先目标影响」；</li>
     *   <li><b>{@code true}</b> = 先打建筑、其中再比距离（R35 / R36）；</li>
     *   <li>值来自<b>卡牌</b>（{@code summon.affected-by-building}；2026-10-19 之前叫
     *       {@code affected-by-taunt}），由 {@code CombatDirector} 从实体 PDC 读卡 id 后
     *       **现查卡池**得到（见 {@code UnitRegistry#KEY_CARD} 的 javadoc：只存 id、不存布尔）。</li>
     * </ul>
     * ★ 出厂只有 <b>2 张</b>卡写 {@code true}（攻城兽 / 炽足兽骑士 —— {@code docs/19} 里
     * 逐字写了「优先攻击建筑」的那两张），其余 49 张**一字未变**。
     */
    private final boolean affectedByBuilding;

    /**
     * ★★ <b>归属账本</b>（2026-10-17 第十二轮新增）——
     * {@link #isAlly(LivingEntity)} 要用它判「当下挂着的目标是不是友方」。
     *
     * <p>★ 可以为 {@code null}（离线夹具与老调用点）：那时 {@code isAlly} 恒为 {@code false}
     * ⇒ 行为退化成「不知道来头 ⇒ 交给 SET 处理」，<b>绝不</b>因为拿不到账本就乱清。
     * 生产路径由 {@link CombatDirector} 传入（它自己就持有同一个账本）。
     */
    private final UnitRegistry ownership;

    private final Mob mob;
    private final String matchId;
    private final Side side;
    private final UUID owner;
    private final TargetPolicy.Mode mode;

    private TargetPolicy.Candidate target;
    private LivingEntity resolvedTarget;
    private boolean driveInstalled;

    /** ★ 索敌半径（格，2026-10-18）；{@code null} = 不限（既有 50 张卡）。 */
    private final Double targetRadius;

    /**
     * ★★★ <b>「锁定后站定」的距离</b>（{@code summon.stop-when-target-within}）——
     * 2026-10-18 新增（用户第 2 条：「**死灵法师锁定后应静止（远程怪跑去近战送）**」）。
     *
     * <p>{@code null} = 没写这一格 ⇒ 老行为（会一直走到目标身边），50/51 张卡是这一档。
     * ★ 判据本体在纯类 {@link io.mczju.infernalclash.policy.HoldDistance}（离线逐格可测），
     * 本类只负责"把判据算出来"并"真的让它停下"。
     * ★★ 2026-10-21（用户裁定）：**远程单位**在卡上没写这一格时，会由
     * {@link #applyRangedStopDistance(Double)} 注入一个默认值（8.0，死灵法师同款）
     * ⇒ 所以这个字段**不再是 final**。
     */
    private Double stopWhenTargetWithin;

    /**
     * 上一次 tick 是不是正在站定 —— ★ 只为了<b>在进入 / 退出时各打一行日志</b>：
     * 每 tick 打日志会把控制台淹掉，而"从来没站定过"与"站定了"在实机上几乎分不出
     * （用户看到的都是"它站着"）。{@code null} = 还没有过第一 tick。
     */
    private Boolean holding;   

    /**
     * ★★ 2026-10-18：这个单位是不是<b>护送型</b>（目标 = 友军、不攻击；出厂只有蒸汽精灵）。
     *
     * <p>由 {@link CombatDirector} 每 tick 按卡 id 设置（{@code policy/EscortRule}）。
     * 它只影响一件事：{@link #reassert()} 直接返回 —— 否则 `CLEAR_ALLY` 会把"友方目标"
     * 当成错误清掉，护送型单位就永远走不到友军身边。
     */
    private boolean escort;

    /** 上一次「无目标站定」的状态（{@code null} = 还没有过第一 tick）；见 {@link #idleStand(boolean)}。 */
    private Boolean standingIdle;

    /** 设置/清除「护送型」标记（幂等；由 {@link CombatDirector} 每 tick 调）。 */
    public void setEscort(boolean escort) {
        this.escort = escort;
    }

    /** 当前是不是护送型（探针 / 测试用）。 */
    public boolean isEscort() {
        return escort;
    }

    /**
     * ★★ <b>护送型单位「没有目标时站定，不许漫游」</b>（2026-10-19 新增）。
     *
     * <h2>出处（用户裁定，逐字）</h2>
     * 蒸汽精灵那一条：「（铜傀儡）**无目标时站定**（不漫游）」——铜傀儡的原版 AI
     * 会自己在场上溜达，而它「只索敌己方目标、不攻击」⇒ 没有友军可跟时，
     * 它唯一该做的事就是**站在原地等**。
     *
     * <h2>怎么实现（为什么是 {@code setAI} 而不是"取消寻路"）</h2>
     * 与 {@code SummonSpawner#applyStationary} 同一条理由：只取消寻路的话，
     * 原版 goal 下一 tick 立刻重新下发一条 —— 那正是"看起来站住了、其实一直在走"。
     * {@code setAI(false)} 是唯一一刀切干净的动作。
     *
     * <p>★ <b>只在"该不该站"发生变化时动 AI</b>（{@code standingIdle} 记状态）：
     * 每 tick 调 {@code setAI} 会让单位在两帧之间反复启停，肉眼可见地抽搐。
     * ★ 失败只告警：站不住是观感问题，绝不影响治疗能力（那由 abilities 的周期任务给）。
     * ★ 非护送型单位调用本方法是 <b>no-op</b> —— 它不该因为这一格而改变任何行为。
     *
     * @param wanted {@code true} = 现在没有目标，应当站定
     */
    public void idleStand(boolean wanted) {
        if (!escort) {
            return;
        }
        if (standingIdle != null && standingIdle == wanted) {
            return;
        }
        standingIdle = wanted;
        try {
            mob.setAI(!wanted);
            org.bukkit.Bukkit.getLogger().info("[IC] 护送型单位"
                    + (wanted ? "无友军可跟，站定（setAI(false)）" : "有了友军目标，恢复移动（setAI(true)）")
                    + "：" + mob.getType() + "（" + side + "）");
        } catch (Throwable t) {
            org.bukkit.Bukkit.getLogger().warning("[IC] 护送型单位站定失败（" + mob.getType() + "）：" + t
                    + "，它会照常漫游（观感问题，不影响治疗）");
        }
    }

    /**
     * 兼容构造（不传账本、不传「优先打建筑」）：{@code isAlly} 恒为 {@code false}、
     * {@code affectedByBuilding = false}（= 只看距离，R37 的绝大多数）。
     *
     * <p>★ 生产路径**不要**用它 —— 少传账本 ⇒ 友方判定永远为 false ⇒
     * 「原版捡了同侧召唤物」这一格会退化成 {@code SET}（写回我们的目标，
     * 若我们恰好没有目标则仍是 CLEAR，但理由与日志会丢）。
     * 已由 {@code PlayerTargetExclusionTest} 的字节码断言钉住「CombatDirector 传了账本」。
     */
    public SummonCombat(Mob mob, String matchId, Side side, UUID owner, TargetPolicy.Mode mode) {
        this(mob, matchId, side, owner, mode, null);
    }

    /**
     * @param ownership 归属账本（判「当下挂着的目标是不是友方」用）；{@code null} 允许（见字段注释）
     */
    public SummonCombat(Mob mob, String matchId, Side side, UUID owner, TargetPolicy.Mode mode,
                        UnitRegistry ownership) {
        this(mob, matchId, side, owner, mode, ownership, false);
    }

    /**
     * ★★ <b>完整构造</b>（2026-10-17 第十二轮新增）：多一个「优先打建筑」。
     *
     * @param affectedByBuilding 这个召唤物选目标时**优不优先打建筑**（见字段 javadoc）；
     *                           {@code false} = 只看距离（【Q6】读法 A，绝大多数卡）
     */
    public SummonCombat(Mob mob, String matchId, Side side, UUID owner, TargetPolicy.Mode mode,
                        UnitRegistry ownership, boolean affectedByBuilding) {
        this(mob, matchId, side, owner, mode, ownership, affectedByBuilding, null);
    }

    /**
     * ★★ <b>完整构造</b>（2026-10-18 再 +1 格）：多一个「索敌半径」。
     *
     * @param targetRadius 索敌半径（格）；{@code null} = 不限（50 张卡的既有行为）。
     *                     出处 = {@code docs/19} 第 113 行烈焰守卫「索敌为**半径 12 的圆形**」
     *                     （键 {@code summon.target-radius}）。
     */
    public SummonCombat(Mob mob, String matchId, Side side, UUID owner, TargetPolicy.Mode mode,
                        UnitRegistry ownership, boolean affectedByBuilding, Double targetRadius) {
        this(mob, matchId, side, owner, mode, ownership, affectedByBuilding, targetRadius, null);
    }

    /**
     * ★★ <b>完整构造</b>（2026-10-18 再 +1 格）：多一个「锁定后站定的距离」。
     *
     * @param stopWhenTargetWithin 目标进 N 格（含边线）就站定（键
     *        {@code summon.stop-when-target-within}）；{@code null} = 没写（走到目标身边）。
     *        出处 = 用户第 2 条「死灵法师锁定后应静止」＋ 追问后的裁定「N 等于它的火球索敌射程」。
     */
    public SummonCombat(Mob mob, String matchId, Side side, UUID owner, TargetPolicy.Mode mode,
                        UnitRegistry ownership, boolean affectedByBuilding, Double targetRadius,
                        Double stopWhenTargetWithin) {
        this.mob = mob;
        this.matchId = matchId;
        this.side = side;
        this.owner = owner;
        this.mode = mode == null ? TargetPolicy.Mode.SWITCH : mode;
        this.ownership = ownership;
        this.affectedByBuilding = affectedByBuilding;
        this.targetRadius = targetRadius;
        this.stopWhenTargetWithin = stopWhenTargetWithin;
    }

    /** 索敌半径（格）；{@code null} = 不限。判据在 {@code CombatDirector#candidatesFor}。 */
    public Double targetRadius() {
        return targetRadius;
    }

    /** 这个召唤物选目标时**优不优先打建筑**（{@code false} = 只看距离）。 */
    public boolean affectedByBuilding() {
        return affectedByBuilding;
    }

    public Mob mob() {
        return mob;
    }

    public String matchId() {
        return matchId;
    }

    public Side side() {
        return side;
    }

    public UUID owner() {
        return owner;
    }

    /** 当前目标的候选视图（没有目标 ⇒ {@code null}）。 */
    public TargetPolicy.Candidate target() {
        return target;
    }

    /** 当前目标的 UUID（没有目标 ⇒ {@code null}）。 */
    public UUID targetId() {
        return target == null ? null : target.id();
    }

    /** 当前目标实体（没有 / 已失效 ⇒ {@code null}）。供 {@link DriveGoal} 读。 */
    public LivingEntity resolvedTarget() {
        if (resolvedTarget == null || resolvedTarget.isDead() || !resolvedTarget.isValid()) {
            return null;
        }
        return resolvedTarget;
    }

    public boolean isBrainType() {
        return BRAIN_TYPES.contains(mob.getType());
    }

    public boolean driveInstalled() {
        return driveInstalled;
    }

    public void markDriveInstalled(boolean installed) {
        this.driveInstalled = installed;
    }

    /**
     * 用敌方候选重算目标并**立刻应用**到实体。
     *
     * @param enemies 可攻击目标（由 `CombatDirector` 从**账本**构造；已排除玩家与友方）
     * @param resolve UUID → 实体（本轮统一解析一次，避免每个召唤物各查一遍）
     * @return 目标是否**变了**（用于「只在变化时打日志」，避免刷屏）
     */
    public boolean retarget(Collection<TargetPolicy.Candidate> enemies, Map<UUID, LivingEntity> resolve) {
        UUID before = targetId();
        boolean stillValid = resolvedTarget() != null;
        // ★★★ 2026-10-18：**被眩晕 ⇒ 放弃锁定**（docs/19 第 113 行「直到被眩晕或目标死亡」）。
        //   判据在纯类 `policy/StunRule`（`effectiveMode` 把 LOCK_ON 降成 SWITCH）；
        //   这里只负责"这一 tick 它是不是眩晕着"（读 STUN 的药水指纹）。
        boolean stunned = stunned();
        Optional<TargetPolicy.Candidate> next = TargetPolicy.refresh(
                target, stillValid, enemies, affectedByBuilding,
                io.mczju.infernalclash.policy.StunRule.effectiveMode(mode, stunned));
        target = next.orElse(null);
        resolvedTarget = target == null ? null : resolve.get(target.id());
        // ★ 刷新路径的语义**永远**是 SET（把新算出来的目标写进去）——
        //   见 apply(action) 的 javadoc：这一格与 reassert 的四种处置是**不同**的语义
        apply(TargetPolicy.Apply.SET);
        return !Objects.equals(before, targetId());
    }

    /**
     * ★★★ <b>这个单位此刻是不是被眩晕着</b>（2026-10-18 新增）。
     *
     * <p>判据 = 身上挂着 {@code SLOWNESS} 且 {@code amplifier ≥ StunRule.STUN_AMPLIFIER}
     * ——那正是本项目的 {@code STUN} 效果留下的指纹（见 {@code spell/EffectApplier#stun}）。
     * ★ 不新增任何全局表：眩晕是**实体自己的状态**，读它就够了
     * （全局表要按对局清理，漏清就会让下一局"平白无故解锁"）。
     *
     * <p>★ 失败方向：读药水效果抛异常 / 拿不到类型 ⇒ {@code false}（当"没眩晕"处理）
     * —— 「没解锁」比「平白解锁」安全：前者只是少了一条玩法细节，后者会让烈焰守卫
     * 变成"谁近打谁"，那就回到了缺陷 D4 的形状。
     */
    public boolean stunned() {
        try {
            int amplifier = -1;
            for (org.bukkit.potion.PotionEffect effect : mob.getActivePotionEffects()) {
                if (effect == null || effect.getType() == null) {
                    continue;
                }
                String key = effect.getType().getKey().getKey();
                if ("slowness".equals(key) && effect.getAmplifier() > amplifier) {
                    amplifier = effect.getAmplifier();
                }
            }
            return io.mczju.infernalclash.policy.StunRule.isStunned(amplifier);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★★★ <b>「锁定后站定」</b>（2026-10-18 新增；用户第 2 条逐字：
     * 「**死灵法师锁定后应静止（远程怪跑去近战送）**」）。
     *
     * <h2>为什么远程怪会"跑去近战送"</h2>
     * 死灵法师的伤害<b>不来自原版攻击</b>，而是 {@code abilities} 里那条
     * 「每 6 秒、对 8 格内目标放一颗 20 点火球」的周期效果（{@code cards/necromancer.yml}）。
     * 但它的原版 AI 是<b>僵尸</b>：{@code ZombieAttackGoal} 每 tick 都朝目标
     * {@code moveTo} ⇒ 它会一路走到目标脸上（然后被近战秒掉），而那颗火球根本不需要贴身。
     * 我们既不能摘掉它的攻击 goal（会被 {@code DriveGoal} 顶成"走到跟前却不咬人"，
     * 即 {@code docs/07} §5.4 的<u>狐狸事故</u>），也不能 {@code setAI(false)}
     * （那连站定之后要做的"面向目标"都没了，而且它是**动态**的：目标远了还得走）。
     * ⇒ 唯一安全的做法是：<b>每 tick 判一次"该不该站定"，该站定就把这一 tick 的移动抹掉</b>。
     *
     * <h2>★ 为什么每 tick 都要调（而不是只在状态切换时）</h2>
     * 原版 goal 是<b>每 tick</b>重新下发寻路的（{@code moveTo}），我们只拦一次的话
     * 下一 tick 它又走了 ⇒ 必须每 tick 抹一次。这也是本方法刻意<b>不打日志</b>的原因
     * （只在状态切换打一行）。
     *
     * <h2>★ 抹掉移动的三个动作（缺一不可）</h2>
     * <ol>
     *   <li>{@code getPathfinder().stopPathfinding()} —— 取消已经算好的路径；</li>
     *   <li>{@code setVelocity(Vector(0,0,0))} —— 已经在滑行的动量归零
     *       （★ 只归零**水平**? 不 —— 竖直也归零：它本来就该站在地上，
     *       而"站定"期间被击退的位移属于正常反应，由原版自己处理）；</li>
     *   <li>★ <b>不传送回拉</b>：位移牵扯到原版击退 / 推挤 / 坐骑，
     *       强行 {@code teleport} 会让它在被推时"瞬移回原位"，看起来像作弊。
     *       ⇒ 如实登记这条取舍：极端情况下（连续被推）它可能被推开几格，
     *       下一 tick 判据仍生效（目标还在 N 格内 ⇒ 继续站着）。</li>
     * </ol>
     *
     * <p>★ 失败方向：{@code stopPathfinding} / {@code setVelocity} 抛异常（实体卸载中）
     * ⇒ <b>只告警一次并放弃这一 tick</b>，绝不让整个战斗循环挂掉（那会让所有单位停止索敌）。
     *
     * @return 这一 tick 是否真的站定了（探针 / 日志用）
     */
    public boolean holdGround() {
        boolean hold = shouldHoldGround();
        try {
            if (hold) {
                mob.getPathfinder().stopPathfinding();
                mob.setVelocity(new org.bukkit.util.Vector(0.0, 0.0, 0.0));
            }
            noteHoldChange(hold);
        } catch (Throwable t) {
            if (holdWarned.compareAndSet(false, true)) {
                org.bukkit.Bukkit.getLogger().warning("[IC] 「锁定后站定」失败（" + mob.getType()
                        + "）：" + t + "，这一 tick 放弃站定（它可能照常朝目标走）");
            }
            return false;
        }
        return hold;
    }

    /**
     * 这一 tick <b>该不该</b>站定（判据全在纯类 {@link io.mczju.infernalclash.policy.HoldDistance}）。
     *
     * <p>★ 距离用 {@code distanceSquared} 先开方再比：与卡里写的"格"是同一个量纲，
     * 而且纯类只认距离 ⇒ 判据不在这里再写一遍（"两处各判一次迟早分叉"）。
     */
    public boolean shouldHoldGround() {
        if (stopWhenTargetWithin == null) {
            return false;
        }
        LivingEntity target = resolvedTarget();
        double distance;
        try {
            distance = target == null ? Double.NaN
                    : Math.sqrt(mob.getLocation().distanceSquared(target.getLocation()));
        } catch (Throwable t) {
            return false;   // 跨世界 / 实体卸载中 ⇒ 当"没有有效距离"处理（不站定）
        }
        return io.mczju.infernalclash.policy.HoldDistance.shouldHold(
                stopWhenTargetWithin, target != null, distance);
    }

    /** 站定状态<b>变化</b>时打一行日志（每 tick 都打会把控制台淹掉）。 */
    private void noteHoldChange(boolean now) {
        if (holding != null && holding == now) {
            return;
        }
        holding = now;
        if (now) {
            org.bukkit.Bukkit.getLogger().info("[IC] 已站定（锁定后静止）：" + mob.getType()
                    + "（" + side + "）← 目标进入 " + trim(stopWhenTargetWithin) + " 格"
                    + "｜依据 = 用户第 2 条「死灵法师锁定后应静止（远程怪跑去近战送）」");
        } else if (stopWhenTargetWithin != null) {
            org.bukkit.Bukkit.getLogger().info("[IC] 恢复移动（目标已离开 " + trim(stopWhenTargetWithin)
                    + " 格或已消失）：" + mob.getType() + "（" + side + "）");
        }
    }

    /** 「8.0」而不是「8.0E0」这种日志里的怪数（日志要能直接读）。 */
    private static String trim(Double value) {
        if (value == null) {
            return "—";
        }
        return value == Math.rint(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    /** 「站定失败」只告警一次（每 tick 都失败会刷屏）。 */
    private final java.util.concurrent.atomic.AtomicBoolean holdWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 这个召唤物声明的「锁定后站定」距离（{@code null} = 没写 / 不站定）。 */
    public Double stopWhenTargetWithin() {
        return stopWhenTargetWithin;
    }

    /**
     * ★★★ <b>装载期之后的「远程单位默认站定距离」注入</b>（2026-10-21 用户裁定）。
     *
     * <h2>为什么不能只在构造器里定</h2>
     * 判「它是不是远程」要扫**它实际挂着的 goal**（{@code ranged_bow_attack} 这种），
     * 而那件事发生在 {@code CombatDirector#loadAi} —— <b>构造之后</b>。
     * ⇒ 构造器保持现状（12 个参数、{@code PlayerTargetWiringTest} 钉着那个形状），
     * 由这里在装载 AI 时补上。
     *
     * <p>★ 只在**卡上没写**的时候才会被调用（见 {@code policy/RangedStopRule#stopDistanceFor}）
     * ⇒ 死灵法师那种显式写 8 的卡**一个字节都不变**。
     * ★ 幂等：重复调用同值不重复打日志。
     */
    public void applyRangedStopDistance(Double distance) {
        if (distance == null || distance.equals(stopWhenTargetWithin)) {
            return;
        }
        this.stopWhenTargetWithin = distance;
        org.bukkit.Bukkit.getLogger().info("[IC] 远程单位按「敌人进射程站定」处理："
                + mob.getType() + "（" + side + "）← " + trim(distance) + " 格"
                + "｜死灵法师同款规则（policy/RangedStopRule，用户 2026-10-21 裁定）");
    }

    /**
     * ★★★ <b>心跳兜底（2026-10-17 第十轮重写，第十一轮再重写；缺陷 P0-a「哨位会攻击己方召唤物」
     * ＋ P0-d「任何单位都不许锁定玩家」）</b>。
     *
     * <h2>旧实现错在哪（第十轮之前的版本）</h2>
     * 旧代码只在 {@code mob.getTarget() == null} 时才补一次目标
     * ⇒ <b>原版 AI 自己挑了一个（可能是同侧的）目标时，我们不会纠正它</b>。
     * 而原版的目标 goal 一直都在跑（日志里 {@code SHULKER 自带 shulker_defense_attack /
     * shulker_nearest_attack} 就是证据 —— 我们刻意不摘它们，怕重演「狐狸事故」）
     * ⇒ 两次刷新之间它可以把附近的<b>任何</b>生物当敌人：同伴召唤物、甚至玩家（违反 R42）。
     *
     * <h2>★★ 第十一轮为什么还不够（用户实机仍能看到）</h2>
     * 第十轮的判据只看「一致 / 不一致」，于是「原版捡了一个<b>玩家</b>」这一格走的是
     * {@code SET} 那段 —— <b>而 {@code SET} 只在刷新周期（默认每 10 tick）发生</b>
     * ⇒ 两次刷新之间有最多 0.5 秒，实体的目标是玩家；更要命的是：<b>若我们算出来的目标
     * 恰好就是那个玩家</b>（不可能，候选里没有玩家）—— 真正的漏口在别处：
     * <ol>
     *   <li>原版 goal <b>每 tick</b> 都会重挑目标，我们<b>每 10 tick</b> 才纠正一次；
     *       被清掉之后下一 tick 它可以立刻再捡回来；</li>
     *   <li>我们自己的 {@link #apply()} 在 {@code target == null} 时会写
     *       {@code setTarget(null)}，但<b>原版 goal 排在它后面</b>再写一次，
     *       于是那一 tick 结束时的目标仍然是玩家。</li>
     * </ol>
     * ⇒ 光靠「写一次 null」是赢不了的。<b>必须每 tick 都判一次、每 tick 都把玩家目标清掉</b>
     * （见 {@link CombatDirector#onTick} 的 else 分支：非刷新 tick 上就是本方法在跑）。
     *
     * <h2>现在（三条判据，全在纯类 {@link TargetPolicy#applyAction} 里）</h2>
     * <ul>
     *   <li>一致 ⇒ {@code KEEP}（不做写操作，别每 tick 干扰原版行为）；</li>
     *   <li>我们这边**没有**可打的目标 ⇒ {@code CLEAR}；</li>
     *   <li>★★ 实体当前挂着的是**玩家**（任何一方、任何单位类型）⇒ {@code CLEAR_PROTECTED}
     *       —— R42/`docs/02` §5「玩家不会成为任何召唤物的目标」，用户 2026-10-17 原话
     *       「<b>敌方的实体，甚至我方的哨位（潜影贝）也依旧会锁定玩家</b>」；</li>
     *   <li>实体当前挂着的是**友方** ⇒ {@code CLEAR_ALLY}（R27）；</li>
     *   <li>其余 ⇒ {@code SET}（写回我们的目标）。</li>
     * </ul>
     * ★ 这就是「任何本插件接管的单位都不得把同侧目标或玩家当作敌人」的落地：
     * 选敌层（{@code CombatDirector} 的候选只从账本 + 敌对方来）
     * ＋ <b>执行层每 tick 把结果重新钉一次</b>（两层，缺一不可 —— 只做前者就是第十轮之前的实现）。
     *
     * <p>刻意**不做**任何日志：它每 tick 都可能被调用。
     * ★ 需要观测时走 {@code /incl debug combat probe}（它会打印每个单位的目标与处置）。
     */
    public void reassert() {
        // ★★★ 2026-10-20（用户：「劲弩手**频繁回头看防御塔的村民和玩家**」）：
        //   先把**头的朝向**钉到真正的目标上，再处理"目标该不该写"。
        //   ★ 为什么必须在**所有 early-return 之前**：下面 `KEEP` 那条是常见分支，
        //     把它放在函数末尾就等于「目标稳定的单位永远不转头」——
        //     而那正是玩家看到的"一直回头"（原版 look goal 被摘掉之后没人管的空档）。
        //   ★ 它是**纯观感**：失败一律吞掉，绝不影响目标写入（写目标才是玩法）。
        faceTarget();
        // ★★ 2026-10-18：**护送型单位直接跳过**（蒸汽精灵）。
        //   它这一 tick 的目标本来就是**友军**（见 CombatDirector 的 escort 分支），
        //   而下面那条 `CLEAR_ALLY` 会把"友方目标"当成错误清掉 ⇒ 不跳过的话
        //   它每 tick 被清一次、永远走不到友军身边。
        //   ★ 为什么放在**函数体里**而不是调用点：`CombatDirectorReassertWiringTest`
        //     用字节码钉住「reassert() 必须在分支外面每 tick 都调」——那条形状是
        //     「召唤物刚被召唤时会索敌玩家」缺陷的修复，不能为了这张卡重新打洞。
        if (escort) {
            return;
        }
        LivingEntity current = mob.getTarget();
        UUID currentId = current == null ? null : current.getUniqueId();
        // ★★ 两条「当下挂着的目标是什么来头」的判据，只有这里算一次
        //    （纯类拿不到实体 ⇒ 必须由执行层判定后传进去，见 TargetPolicy#applyAction 的 javadoc）
        boolean currentIsPlayer = current instanceof Player;
        boolean currentIsAlly = !currentIsPlayer && current != null && isAlly(current);
        TargetPolicy.Apply action = TargetPolicy.applyAction(currentId, targetId(),
                currentIsAlly, currentIsPlayer);
        if (action == TargetPolicy.Apply.KEEP) {
            return;
        }
        apply(action);
    }

    /**
     * ★★★ <b>把头的朝向钉到「真正在打的那个目标」上</b>（2026-10-20 新增）。
     *
     * <h2>出处（用户逐字）</h2>
     * 「并且它（劲弩手）**频繁回头看防御塔的村民和玩家**，索敌系统还是有小问题」。
     *
     * <h2>查证：索敌没坏，坏的是"头的朝向"</h2>
     * 玩家看到的是**转头**，而转头由原版 goal 管：`LOOK_AT_PLAYER`（任何 Mob 都有，
     * 会扭头盯着最近的玩家）与 `RANDOM_LOOK_AROUND`（东张西望）——
     * 两者都与"在打谁"无关。⇒ 两条修法一起上：
     * <ol>
     *   <li>生成时摘掉那两条 goal（{@code card/SummonSpawner#stripLookGoals}）；</li>
     *   <li>本方法每 tick 把它**转向真正的目标**（目标为空 ⇒ 什么都不做，保持原朝向）。</li>
     * </ol>
     *
     * <p>★ 失败方向：任何异常都吞掉 —— 转头是观感，绝不能因为它让目标写入失败。
     * ★ 幂等：同一 tick 多次调用没有副作用（{@code lookAt} 只是设置朝向）。
     */
    public void faceTarget() {
        if (mob.isDead() || !mob.isValid()) {
            return;
        }
        LivingEntity target = resolvedTarget();
        if (target == null) {
            return;   // 没有目标 ⇒ 不强行摆头（也不去看玩家/村民）
        }
        try {
            mob.lookAt(target);
        } catch (Throwable ignored) {
            // 朝向失败无所谓：目标照常打、攻击 goal 自己也会转向
        }
    }

    /**
     * 这个实体是不是**我方**（同侧）的单位。
     *
     * <h2>判据（★ 刻意保守：读不到就不算友方）</h2>
     * 拿不到 {@code ownership} 时返回 {@code false} —— 也就是「未知」会被交给
     * {@link TargetPolicy.Apply#SET} 处理（写回我们的目标），而<b>不是</b>被当成友方清掉。
     * ★ 为什么：读不到归属可能是「它不是我们的实体」（别的插件的怪、服主的动物）
     * —— 那种情况下我们连"它是不是友方"都不知道，绝不该动手清它的目标。
     * 而「玩家」那一条是另一条判据（{@code instanceof Player}），**不依赖**本方法
     * （R42 的原文是「任何」，与在不在账本里无关）。
     */
    private boolean isAlly(LivingEntity entity) {
        return ownership != null && ownership.isAllyOf(entity.getUniqueId(), side);
    }

    /**
     * 把当前目标写进实体（brain 型走 {@code ANGRY_AT}，其余走 {@code setTarget}）。
     *
     * <h2>★★ 为什么必须把 {@code action} 传进来（这是第十二轮自查抓到的一个真缺陷）</h2>
     * 本方法的<b>两条入口</b>语义不同：
     * <ul>
     *   <li>{@link #retarget} —— 刷新路径，语义永远是 {@link TargetPolicy.Apply#SET}
     *       （把新算出来的目标写进去）；</li>
     *   <li>{@link #reassert} —— 心跳路径，语义由<b>纯判据</b>给出：
     *       {@code SET} / {@code CLEAR} / {@code CLEAR_ALLY} / {@code CLEAR_PROTECTED}。</li>
     * </ul>
     * ★ 若两条入口都写成「无参 {@code apply()}」，那么「原版捡了一个玩家当目标、
     * 我们算出来的目标**不是 null**」这一格（{@code CLEAR_PROTECTED}）会掉进
     * 「目标有效 ⇒ 写回我们自己的目标」的分支 ⇒ <b>玩家被顶掉、但实体并没有被清空</b>，
     * 而且更要命的是：判据说「清掉了」，实体却在打我们的目标。
     * 这个缺陷是 {@code PlayerTargetExclusionTest} 的**行为断言**抓到的
     * （字节码断言完全看不出来 —— 它只看到"调了 apply"）。
     *
     * @param action 本次写实体的语义（{@link TargetPolicy.Apply#KEEP} 不该走到这里）
     */
    private void apply(TargetPolicy.Apply action) {
        if (mob.isDead() || !mob.isValid()) {
            return;
        }
        if (action == null || action == TargetPolicy.Apply.KEEP) {
            // 前置条件：调用方必须已经判过（KEEP 不该调本方法）。这里只是防御 ——
            // 但**不抛异常**（对局里抛异常会把整个战斗循环带走，代价远大于一次白写）
            return;
        }
        boolean clear = action != TargetPolicy.Apply.SET;
        // ★★★ 2026-10-17 第十二轮（缺陷 P0-d）：**最后一道门** ——
        //   即使上游算出了一个玩家目标（BUG / 未来的改法 / 账本里被塞进了玩家 UUID），
        //   这里也绝不许把它写进实体。R42 的原文是「玩家不会成为**任何**实体的目标」。
        //   ★ 之所以要这道门而不是只靠 reassert：apply() 有**两条**入口
        //     （retarget 的刷新路径 + reassert 的心跳路径）⇒ 只在 reassert 里判
        //     就是本项目最反复的那类「只守了一处」的缺陷。
        boolean targetIsForbiddenPlayer = resolvedTarget instanceof Player;
        if (clear || target == null || resolvedTarget == null || resolvedTarget.isDead()
                || !resolvedTarget.isValid() || targetIsForbiddenPlayer) {
            if (targetIsForbiddenPlayer || clear) {
                // 把内存里的目标也一起清掉，否则下一 tick 的 reassert 会算出 KEEP
                // （currentTargetId == targetId()）⇒ 实体被清空之后我们又会把它写回去。
                target = null;
                resolvedTarget = null;
            }
            if (!isBrainType()) {
                mob.setTarget(null);
            } else {
                mob.setMemory(MemoryKey.ANGRY_AT, null);
            }
            return;
        }
        if (isBrainType()) {
            // ★ brain 型的 setTarget 是空操作（docs/07 §5.4 实测）
            mob.setMemory(MemoryKey.ANGRY_AT, resolvedTarget.getUniqueId());
        } else {
            mob.setTarget(resolvedTarget);
        }
    }
}
