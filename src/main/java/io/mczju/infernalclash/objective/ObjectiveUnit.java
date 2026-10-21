package io.mczju.infernalclash.objective;

import io.mczju.infernalclash.arena.Point;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★ <b>一个目标物</b>（信标 / 防御塔）= 「**盔甲架**载体 + 方块展示实体」。
 *
 * <h2>为什么是「载体 + 展示实体」而不是别的</h2>
 * 需求要的是「看起来是一个信标/铜灯，但要能被打死、被索敌」。Bukkit 里没有「可击杀的方块」，
 * 所以本项目的口径（`docs/03` §3.2、【Q15/Q20/Q56】已裁定）是：
 * <ul>
 *   <li><b>受击目标 = 盔甲架</b>（{@code ArmorStand}，隐形、无重力、无底板）；</li>
 *   <li><b>外观 = 方块展示实体</b>（`BlockDisplay`：信标 = `BEACON`，塔 = `WAXED_COPPER_BULB[lit=true]`）；</li>
 *   <li>载体死亡 ⇒ **同步删除展示实体**（否则会留下一块飘在空中的方块）。</li>
 * </ul>
 *
 * <h2>★★★ 为什么载体从「村民」换成了「盔甲架」（2026-10-21 用户裁定）</h2>
 * 用户原话（逐字）：「**我发现无论如何消除原版 goal，自家信标与防御塔里的村民总会影响
 * 僵尸和劫掠者的目标，所以需要把防御塔里的生物换成一个完全不会被任何原版村民索敌的
 * living entity**」。
 * <p>★ 根因（`javap -c` 对 26.2 服务端实测）：
 * <ul>
 *   <li>原版索敌 goal 的候选过滤最终落在
 *       {@code LivingEntity#canBeSeenAsEnemy()} = {@code !isInvulnerable() && !isSpectator() && isAlive()}
 *       ⇒ **只要"活着且非无敌"，村民就是"可被看见的敌人"**；</li>
 *   <li>而僵尸/劫掠者/卫道士那一族挂的是 {@code NearestAttackableTargetGoal<Villager>}
 *       ⇒ 村子里只要有村民，它们就会优先转火 —— 摘 goal 也没用（摘了还会被
 *       `HurtByTargetGoal` / 别的同类 goal 重新选中，且原版 goal 一被摘，
 *       我们自己的攻击 goal 也跟着废掉）；</li>
 *   <li>★ 全 jar 扫描（`NearestAttackableTargetGoal` 与 `decoration/ArmorStand` 同现）：
 *       **只有 {@code Wolf}** 命中，而它那处是 {@code canAttack} 里的**显式排除**
 *       （{@code target instanceof ArmorStand ⇒ false}）⇒
 *       <b>没有任何原版索敌 goal 以 ArmorStand 为目标类型</b>。</li>
 * </ul>
 * ⇒ 换成 {@code ArmorStand} 后：原版所有索敌 goal **结构上**都不可能选中它
 * （不是"摘掉别人的 goal"，而是"不在任何一张目标清单里"），而它仍然是
 * 一个**可受伤的 LivingEntity**（`ArmorStand#createAttributes()` 实测 =
 * {@code createLivingAttributes()}` ⇒ **有 MAX_HEALTH**，320/240 血照常设）。
 *
 * <h2>★ 属性设置逐条的理由（都不是随手加的）</h2>
 * <table border="1">
 *   <caption>为什么每个开关都要显式设</caption>
 *   <tr><th>设置</th><th>不设会怎样</th></tr>
 *   <tr><td>{@code setInvisible(true)}</td><td>塔里会多出一具白骨骸（外观是展示方块，载体不该看见）</td></tr>
 *   <tr><td>{@code setGravity(false)}</td><td>点位在半空时会掉下去（点位是数据，不能假设有地面）</td></tr>
 *   <tr><td>{@code setPersistent(true)} + {@code setRemoveWhenFarAway(false)}</td>
 *       <td>玩家走远后实体被卸载 ⇒ 目标物凭空消失</td></tr>
 *   <tr><td>{@code setSilent(true)}</td><td>载体不停发出声音</td></tr>
 *   <tr><td>{@code setMarker(false)} + {@code setBasePlate(false)} + {@code setArms(false)}</td>
 *       <td>底板/手臂会露出来；★ **marker 必须为 false**：marker 没有碰撞箱 ⇒ 近战/箭都打不中 ⇒ 塔变成无敌</td></tr>
 *   <tr><td>{@code setInvulnerable(false)}</td><td>（显式声明）**必须可被击杀** —— 这是目标物的全部意义；
 *       ★ 同时也**不能**设成 true：{@code canBeSeenAsEnemy()} 会因为无敌直接返回 false ⇒ 连我们自己的单位都打不动它</td></tr>
 *   <tr><td>展示实体 {@code setInvulnerable(true)} + {@code setGravity(false)}</td>
 *       <td>展示实体被打 / 掉落（它只是外观，不该参与战斗）</td></tr>
 *   <tr><td>{@code MAX_HEALTH} 属性 + {@code setHealth}</td>
 *       <td>默认血太少 ⇒ 信标 320 / 塔 240 全都变成默认值</td></tr>
 * </table>
 *
 * <h2>★ 村民僵尸化那条已随之失效</h2>
 * 旧载体是村民时，困难难度会变成僵尸村民（{@code Villager#zombify()}）⇒
 * {@link ObjectiveProtectionListener} 拦 {@code EntityTransformEvent}。
 * ★ 现在是盔甲架 ⇒ **那条路结构上不可能发生**；监听器保留（无害，且将来若改回村民仍需要它）。
 *
 * <p>★ 本类**只**操作自己创建的这两个实体（UUID 由我们自己持有），
 * 不做任何「按范围/按类型」的世界查询。
 */
public final class ObjectiveUnit {

    /**
     * ★ <b>「防御塔」载体相对点位的水平偏移</b>（格）—— 2026-10-18 新增；2026-10-21 起**只给防御塔**用。
     *
     * <p>用户原话（逐字，第 7 条）：「**村民的位置也请考虑偏移量 0.5 格，让它们站在方块
     * 正中央而不是交接处**」。当时目标物的载体**就是村民** ⇒ 点位是**方块角**（整数坐标），
     * 加 0.5 才是方块正中央。
     *
     * <p>★★★ <b>2026-10-21 用户裁定收窄（逐字）</b>：「**双方信标的位置有偏移：文档要求在
     * 4 个方块的交界处，而不是一个方块的正中央**」⇒ <b>信标回到交界线（偏移 0）</b>，
     * 防御塔**保持** 0.5（方块正中央）。
     * 出处：[`docs/03`](../03-配置规范.md) §8.3 的点位偏移表逐行写着
     * 「{@code beacon.blue} = (2,−80)(2,−81)(3,−80)(3,−81) **四格正中心交界线** ⇒ 格线交点 x 3.0 / z −80.0」，
     * 而 {@code tower.*} 只给了方块坐标（它们的观感正是"站在那一块上"）。
     * ★ 判据只有一处：{@link #horizontalOffsetOf(ObjectiveKind)}。
     */
    public static final double CARRIER_CENTER_OFFSET = 0.5;

    /**
     * ★★★ <b>载体该怎么落在点位上</b>（按目标物种类，2026-10-21 新增）。
     *
     * <table border="1">
     *   <caption>两类目标物的水平偏移（出处：{@code docs/03} §8.3）</caption>
     *   <tr><th>种类</th><th>偏移</th><th>为什么</th></tr>
     *   <tr><td>{@link ObjectiveKind#BEACON}</td><td><b>{@code 0.0}</b></td>
     *       <td>点位是**四格交界线**（格线交点，整数 x/z）⇒ 加 0.5 会把它挪到某个方块的正中央，
     *           用户实测看到的正是这个偏移</td></tr>
     *   <tr><td>{@link ObjectiveKind#TOWER}</td><td><b>{@code 0.5}</b></td>
     *       <td>点位是**一个方块** ⇒ 载体站在该方块正中央（用户 2026-10-18 第 7 条的原话）</td></tr>
     * </table>
     *
     * <p>★ 纯函数（只吃枚举）⇒ 可离线逐格断言（{@code ObjectiveOffsetTest}）。
     */
    public static double horizontalOffsetOf(ObjectiveKind kind) {
        return kind == ObjectiveKind.BEACON ? 0.0 : CARRIER_CENTER_OFFSET;
    }

    /**
     * ★★★ <b>载体必须「可碰撞」（{@code setCollidable(true)}）</b> —— 2026-10-21 缺陷：
     * 「**持有矛、会发射弹射物、以及自定义穿透射线的召唤物打不动塔**」。
     *
     * <h2>为什么 {@code collidable} 决定「远程能不能打到塔」（26.2 服务端 {@code javap -c} 实测）</h2>
     * <pre>
     *   CraftLivingEntity#setCollidable(boolean)   →  this.collides = value      ← 就这一个字段
     *   LivingEntity#isPickable()                  →  !isRemoved() &amp;&amp; this.collides
     *   Entity#canBeHitByProjectile()              →  isAlive() &amp;&amp; isPickable()
     *   Projectile#canHitEntity(entity)            →  ... if (!entity.canBeHitByProjectile()) return false
     *   PiercingWeapon#canHitEntity(attacker, t)   →  ... &amp;&amp; t.canBeHitByProjectile()   ← 矛的「动能武器」也是这条
     * </pre>
     * ⇒ 一旦 {@code collides = false}，这个实体对**一切弹射物**（箭 / 弩弹 / 三叉戟 / 潜影贝子弹）
     * 与**矛的贯穿攻击**都**不存在** —— 它们连命中的事件都不会产生，
     * 而<b>近战</b>走的是另一条路（{@code LivingEntity#attackable()} = {@code true}，Mannequin 没有改写它）
     * ⇒ 现场表现正是「近战召唤物能打塔掉血，远程打不掉」。
     *
     * <p>★★ 所以这个常量必须保持 {@code true}：它不是观感项，而是<b>目标物唯一的"可被远程命中"开关</b>。
     * 想「让载体不挡路」千万别再动 {@code setCollidable}（那会静默地把塔变成远程免疫），
     * 位置冻结由 {@code setImmovable(true)} 负责、击退免疫由 {@code KNOCKBACK_RESISTANCE = 1.0} 负责。
     * ★ 已由 {@code battle/SummonRangedVsObjectiveTest} 钉住（纯断言 + 接线断言）。
     */
    public static final boolean CARRIER_COLLIDABLE = true;

    /**
     * ★ <b>展示方块放大到几倍</b>（用户第 7 条：「**请放大至 2.0**」）。
     *
     * <p>★ 为什么是一个常量而不是配置键：用户给的是一个**确定的观感值**（2.0），
     * 把它做成配置键只会让"改了没生效"再多一条路径（铁律 13 每次都要同步现场）。
     * ⇒ 想改只改这一处。
     */
    public static final float DISPLAY_SCALE = 2.0f;

    /**
     * ★★ <b>展示方块的变换</b>（放大 {@value #DISPLAY_SCALE} 倍并**重新居中**）。
     *
     * <h2>为什么要平移，而不是只设 scale</h2>
     * {@code BlockDisplay} 的方块在局部坐标里占 {@code [0,1]³}，原点在它的**角**上
     * ⇒ 直接乘 2 会变成 {@code [0,2]³}：方块往 {@code +X/+Y/+Z} 各长出去一格，
     * 看起来是"方块歪了、还飘在半空"。
     * ⇒ 平移 {@code (-1,-1,-1)} 之后它占 {@code [-1,1]³}，**中心正好落在展示实体的位置**；
     * 而展示实体被放在载体脚上方 <b>1 格</b> ⇒ 放大后的方块正好"立在地上"（脚下 0 格到头顶 2 格）。
     *
     * <p>★ 变换矩阵是 {@code T · R · S}（平移在**缩放之后**生效）⇒ 这里的 {@code -1} 是**世界格**，
     * 不是"方块单位的 1 格"。
     * <p>★ 每次生成都**新建**一个（而不是共享同一个 static 常量）：{@code Vector3f} 是**可变**的，
     * 共享一份意味着"任何人拿到 {@code getTranslation()} 改一下，全世界所有目标物一起变形"。
     */
    public static org.bukkit.util.Transformation displayTransformation() {
        return new org.bukkit.util.Transformation(
                new org.joml.Vector3f(-DISPLAY_SCALE / 2.0f, -DISPLAY_SCALE / 2.0f,
                        -DISPLAY_SCALE / 2.0f),
                new org.joml.Quaternionf(),
                new org.joml.Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE),
                new org.joml.Quaternionf());
    }

    private final ObjectiveKind kind;
    private final Side side;
    private final Mannequin carrier;
    private final BlockDisplay display;
    private final double maxHealth;

    /**
     * ★ <b>本局 id</b>（2026-10-21 新增）—— 回收时用来复核 PDC 里记的 matchId
     * （见 {@link ObjectiveRetire}：账本脱节时**以 PDC 为第二证据**照删，否则会永久遗留）。
     */
    private final String matchId;

    /** 日志出口（装配期传进来；{@code null} = 不打）。★ 只在"判据自相矛盾"那一格用。 */
    private final Logger logger;

    /** ★ 登记点（生成时的坐标，含载体 +0.5 偏移）—— 「免疫浮空」的归位目标（2026-10-18）。 */
    private final Location anchor;

    /**
     * ★ <b>阳性死亡证据</b>（2026-10-17 第十轮新增，缺陷 P0-c）——
     * 只有死亡事件 / 显式回收才把它置位；**再也不用 {@code isValid()} 猜死活**
     * （区块卸载会让 {@code isValid()} 变 false，那就是现场「莫名奇妙胜利」的根因）。
     */
    private boolean dead;

    /** 判死的原因（排障用：{@code /incl debug} 与收尾日志里能看出是谁判的）。 */
    private String deathReason;

    /**
     * ★★★ <b>光束当前锁定的目标</b>（2026-10-18 新增）。
     *
     * <h2>用户原话（逐字）</h2>
     * 「**防御塔攻击不应该带有击退效果，否则将永控某些敌人；并且，不应该能够同时攻击范围内
     * 所有目标，而是只应该锁定第一个进入范围的目标，或是在有多个目标时锁定最近的目标
     * （烈焰守卫相同索敌规则）**」＋ 我追问后用户答：「**3，但是需要嘲讽优先**」
     * ⇒ 语义 = <b>锁定第一个进入范围的（其中嘲讽单位优先）；锁定期间不因出现更近/更新的目标而切换；
     * 只有「目标失效（死亡/消失）或离开射程」才重新选</b>。
     *
     * <h2>为什么放在目标物上而不是每次重算</h2>
     * 「锁定」这个词本身就要求**跨 tick 保存** —— 每次重算就退化成"每次打最近的"，
     * 那正是用户不要的那一半（他会看到塔在几个人之间来回切）。
     * ★ 与 {@code policy/TargetPolicy.Mode.LOCK_ON} 的区别：那个是烈焰守卫的
     * 「**只有被眩晕或目标死亡才换**」；本字段是「**离开射程也换**」（塔要能改打新进入的人）。
     */
    private java.util.UUID lockedTarget;

    /** 判死的原因（排障用）—— 见 {@link #deathReason}。 */
    public java.util.UUID lockedTarget() {
        return lockedTarget;
    }

    /**
     * ★★★ <b>「免疫浮空」</b>（2026-10-18 新增；用户第 5 条，逐字：
     * 「**防御塔莫名其妙被浮空，然后停在空中。防御塔应该免疫浮空**」
     * ＋ 追问后「**应该是潜影贝子弹误伤造成的**」）。
     *
     * <h2>根因</h2>
     * 潜影贝子弹（{@code ShulkerBullet}）命中会给目标挂 **漂浮（LEVITATION）**
     * —— 现场的 {@code sentry}（哨位）就是潜影贝，子弹打到塔上 ⇒ 塔被抬起来；
     * 而目标物的载体是 {@code setGravity(false)} 的村民 ⇒ <b>抬起来之后永远不会落回去</b>
     * （这就是"停在空中"）。
     *
     * <h2>口径（三层，任一漏掉都还会飘）</h2>
     * <ol>
     *   <li>摘掉 {@code LEVITATION}（断掉"继续上升"的源头）；</li>
     *   <li>速度清零（断掉"惯性"）；</li>
     *   <li>位置**归位**到登记点（已经飘起来的那一段必须拉回来 —— 重力关着时它自己回不来）。
     *       ★ 容差 {@value #ANCHOR_TOLERANCE} 格：避免每 tick 都发一次 teleport 包。</li>
     * </ol>
     * ★ 这个方法**不是**"把塔钉死"的通用手段，它只做"免疫浮空"这一件事：
     * 目标物本来就是不动的（点位由 {@code arenas.yml} 决定）。
     */
    public void immuneToFloating() {
        if (dead || carrier == null) {
            return;
        }
        try {
            if (carrier.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) {
                carrier.removePotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION);
            }
            org.bukkit.util.Vector velocity = carrier.getVelocity();
            if (velocity.getY() != 0.0 || velocity.getX() != 0.0 || velocity.getZ() != 0.0) {
                carrier.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            }
            Location anchor = anchorLocation();
            Location now = carrier.getLocation();
            if (anchor != null && now.getWorld() != null && now.getWorld().equals(anchor.getWorld())
                    && now.distanceSquared(anchor) > ANCHOR_TOLERANCE * ANCHOR_TOLERANCE) {
                carrier.teleport(anchor);
                carrier.setFallDistance(0.0f);
            }
        } catch (Throwable ignored) {
            // 观感/位置问题绝不影响对局结算（下一 tick 还会再试一次）
        }
    }

    /** 归位容差（格）：小于它的漂移不纠正（避免每 tick 一次 teleport）。 */
    public static final double ANCHOR_TOLERANCE = 0.05;

    /** 登记点（生成时的那个点 + 载体中心偏移）；世界已不可用 ⇒ {@code null}。 */
    private Location anchorLocation() {
        if (anchor == null) {
            return null;
        }
        World world = anchor.getWorld();
        return world == null ? null : anchor;
    }

    /** 记下锁定目标（{@code null} = 清空，下次重新选）。 */
    public void lockTarget(java.util.UUID target) {
        this.lockedTarget = target;
    }

    private ObjectiveUnit(String matchId, ObjectiveKind kind, Side side, Mannequin carrier,
                          BlockDisplay display, double maxHealth, Location anchor, Logger logger) {
        this.matchId = matchId;
        this.kind = kind;
        this.side = side;
        this.carrier = carrier;
        this.display = display;
        this.maxHealth = maxHealth;
        this.anchor = anchor == null ? null : anchor.clone();
        this.logger = logger;
    }

    /** 本局 id（回收时复核 PDC 用）。 */
    public String matchId() {
        return matchId;
    }

    /**
     * 生成一个目标物并**立刻登记进归属账本**（铁律 10）。
     *
     * @return 生成好的目标物；点位所在世界不存在 / 材料名非法 ⇒ {@code null}（调用方告警并跳过）
     */
    public static ObjectiveUnit spawn(Plugin plugin, UnitRegistry registry, String matchId,
                                      ObjectiveKind kind, Side side, Point point,
                                      String displayBlock, boolean lit, double health,
                                      boolean taunt, String sideLabel, Logger logger) {
        World world = point.world() == null ? null : Bukkit.getWorld(point.world());
        if (world == null) {
            logger.warning("[IC] 目标物生成跳过：" + kind + "/" + side
                    + " 的点位世界 " + point.world() + " 不存在（fail-safe：不猜位置）");
            return null;
        }
        Material material = Material.matchMaterial(displayBlock);
        if (material == null) {
            logger.warning("[IC] 目标物生成跳过：" + kind + "/" + side
                    + " 的外观材料 " + displayBlock + " 不是合法 Material");
            return null;
        }
        BlockData blockData;
        try {
            blockData = lit ? material.createBlockData("[lit=true]") : material.createBlockData();
        } catch (IllegalArgumentException e) {
            // 例如给一个没有 lit 属性的方块写了 lit:true —— 明确报出来，而不是静默用默认外观
            logger.warning("[IC] 目标物生成跳过：" + kind + "/" + side + " 的外观 " + displayBlock
                    + "" + (lit ? " 不支持 lit 属性" : " 无法构造 BlockData") + "：" + e.getMessage());
            return null;
        }

        // ★★★ 2026-10-18（用户第 7 条，逐字）：「**村民的位置也请考虑偏移量 0.5 格，
        //   让它们站在方块正中央而不是交接处**」⇒ 点位是**方块角**（整数坐标），
        //   载体必须落在 (x+0.5, z+0.5) 才是"方块正中央"。
        //   ★★★ 2026-10-21 用户裁定**收窄**（逐字）：「**双方信标的位置有偏移：文档要求在
        //   4 个方块的交界处，而不是一个方块的正中央**」⇒ 偏移**按种类**取：
        //     信标 = 0.0（四格交界线，docs/03 §8.3 的点位表逐字写着"四格正中心交界线"）；
        //     防御塔 = 0.5（方块正中央，用户第 7 条原话）。
        //   判据只有一处：{@link #horizontalOffsetOf(ObjectiveKind)}（纯函数，有单测）。
        //   ★ 展示实体也用同一个 base ⇒ 方块与载体自动对齐（不会出现"方块偏半格"）。
        double offset = horizontalOffsetOf(kind);
        Location base = new Location(world, point.x() + offset, point.y(),
                point.z() + offset, point.yaw(), point.pitch());

        // ★★★ 2026-10-21（用户裁定）：「**把防御塔里的生物换成一个完全不会被任何原版村民索敌的
        //   living entity**」⇒ `Villager` → `ArmorStand`。
        //   ★ 依据（26.2 服务端 `javap` 实测，见类 javadoc）：原版索敌的候选过滤最终是
        //     `canBeSeenAsEnemy()`（只要活着且非无敌就为真），**没有**任何原版 goal 以
        //     `ArmorStand` 为目标类型 ⇒ 换类型才是"结构上不可能被索敌"，摘 goal 做不到。
        //   ★ `ArmorStand#createAttributes()` = `createLivingAttributes()` ⇒ **有 MAX_HEALTH**，
        //     320/240 血照常设（这一步已实测，不是推断）。
        // ★★★ 2026-10-21（用户追问「盔甲架有血量吗？它的血量能和其他生物一样正常计算吗？
        //   不如试试换成 mannequin 呢」⇒ 实测后**换成 Mannequin**）：
        //   实测（纸服 26.2 控制台探针，`/summon` + `/attribute` + `/damage` + `/data get`）：
        //   · Mannequin：`max_health` = 20、打 5 点后 `Health` = **15.0** ⇒ 伤害与血量**正常结算**；
        //   · Armor Stand：`max_health` = 20，`/damage … 5` 控制台回「Applied 5.0 damage」，
        //     但 `Health` **仍是 20.0** ⇒ **伤害被吞**（`ArmorStand#hurtServer` 有 `mobGriefing` 门控
        //     与"散架"专用路径，且 `attackable()` 返回 false）⇒ 塔会变成"打不掉的装饰"。
        //   ⇒ 载体改用 `Mannequin`（`extends LivingEntity`，**没有**重写 hurtServer/attackable），
        //     并用 `setImmovable(true)` 冻结它（不用 `setAware`，它本来就没有 AI）。
        //   ★ 索敌：全 jar 扫描「`NearestAttackableTargetGoal` 与 `decoration/Mannequin` 同现」= **0 处**
        //     ⇒ 没有任何原版索敌 goal 会选中它（僵尸/劫掠者挂的是 `<Villager>`/`<Player>`）。
        Mannequin carrier = world.spawn(base, Mannequin.class, mannequin -> {
            mannequin.setInvisible(true);        // 外观是展示方块，载体不该被看见
            mannequin.setImmovable(true);        // ★ 冻结位置（没有 AI，这一格只是双保险）
            mannequin.setSilent(true);
            mannequin.setPersistent(true);
            mannequin.setRemoveWhenFarAway(false);
            mannequin.setGravity(false);
            mannequin.setInvulnerable(false);    // ★ 必须可被击杀（同时也不能设 true：无敌 ⇒ canBeSeenAsEnemy=false）
            // ★★★ 2026-10-21 缺陷修复：这里**曾经**是 `setCollidable(false)`（想让它"不挡路"），
            //   而 `collides` 正是 `LivingEntity#isPickable()` 的第二个操作数
            //   ⇒ `canBeHitByProjectile()` 恒为 false ⇒ **一切弹射物与矛的贯穿攻击都打不到塔**。
            //   判据与整条调用链见 {@link #CARRIER_COLLIDABLE} 的 javadoc（26.2 `javap -c` 实测）。
            mannequin.setCollidable(CARRIER_COLLIDABLE);
            mannequin.customName(net.kyori.adventure.text.Component.text(sideLabel));
            mannequin.setCustomNameVisible(false);
        });
        AttributeInstance attribute = carrier.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null) {
            attribute.setBaseValue(health);
        }
        // ★★★ 2026-10-17 第十三轮收尾：**目标物完全免疫击退**
        //   （用户原话：「击退抗性依然没有实装，实测防御塔被「狂风推进」法术吹飞很远」
        //    ＋ 我问「塔的击退抗性要多少」⇒ 用户裁定「**完全免疫（knockback-resistance = 1.0）**」）。
        //   ★ 这一道防的是**原版**击退（被怪打、爆炸）；
        //     ★★ 我们自己的 PUSH/KNOCKBACK 是 setVelocity，属性对它们**无效**
        //     ⇒ 那一条由 `EffectApplier#immuneToDisplacement` 读同一个属性值来拦（两道都要有）。
        //   ★ 信标与防御塔共用本方法 ⇒ 两者一起生效，不需要各写一遍。
        AttributeInstance knockback = carrier.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.setBaseValue(1.0);
        } else {
            // 失败方向：读不到属性不阻断开局，但要把话说清楚（否则「塔被吹走」会再次无迹可查）
            logger.warning("[IC] 目标物 " + kind + " 没有 KNOCKBACK_RESISTANCE 属性，"
                    + "只能靠 EffectApplier 的效果判据拦「狂风推进」，原版击退拦不住");
        }
        carrier.setHealth(Math.min(health, carrier.getMaxHealth()));

        // 展示实体放在载体脚上方 1 格（见 DISPLAY_SCALE 的说明：放大后要重新对齐）
        BlockDisplay display = world.spawn(base.clone().add(0.0, 1.0, 0.0), BlockDisplay.class, entity -> {
            entity.setBlock(blockData);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setPersistent(true);
            entity.setTeleportDuration(0);
            // ★★★ 2026-10-18（用户第 7 条）：「**作为展示实体的信标、铜灯方块请放大至 2.0**」
            entity.setTransformation(displayTransformation());
        });

        ObjectiveUnit unit = new ObjectiveUnit(matchId, kind, side, carrier, display, health, base,
                logger);
        // ★ 载体与展示实体**都**登记：清场时两个都要删（只删载体 = 留下漂浮方块）
        //   嘲讽（布尔）写在**载体**上（它才是受击 / 被索敌的目标）；展示实体只是个外观
        OwnershipTable.Owner owner = new OwnershipTable.Owner(matchId, side, null);
        // ★★★ 2026-10-19 用户裁定（逐字）：「**信标防御塔都算建筑**」
        //   ⇒ 载体打上 `building = true`（纯标签，见 UnitRegistry#KEY_BUILDING）。
        //   ★ 嘲讽标记照旧（塔自己的索敌读它）—— 两个键**不是**同一件事，别再合并。
        // ★★★ 2026-10-21 新增「这是目标物」的**明确 PDC 标记**（`UnitRegistry#KEY_OBJECTIVE`）：
        //   自定义穿透射线要区分「目标物」与「别的没有归属玩家的账本实体」——
        //   ★ 后者真实存在：紧跟着的 `display` 也是 owner 的 player 为 null ＋ building=true
        //     ⇒ 用「cardId 为空 + building」这种启发式会把展示方块也当成目标物（它是无敌的）。
        //   ⇒ 只有**载体**带这个标记，判据在 `policy/RayTargetRule`（纯函数）里。
        registry.tagObjective(carrier, owner, taunt);
        registry.tag(display, owner, false, null, true);
        logger.info("[IC] 已生成 " + sideLabel + "（" + kind + "）@" + world.getName()
                + " " + base.getBlockX() + "," + base.getBlockY() + "," + base.getBlockZ()
                + "｜血量 " + (int) health + "｜嘲讽 " + taunt);
        return unit;
    }

    public ObjectiveKind kind() {
        return kind;
    }

    public Side side() {
        return side;
    }

    /** 载体 UUID —— 目标物的身份（追踪器只认它）。 */
    public UUID id() {
        return carrier.getUniqueId();
    }

    public Mannequin carrier() {
        return carrier;
    }

    public BlockDisplay display() {
        return display;
    }

    public double maxHealth() {
        return maxHealth;
    }

    /**
     * ★★ <b>这个目标物还活着吗</b>（2026-10-17 第十轮重写 —— 缺陷 P0-c 的核心）。
     *
     * <h2>★★★ 旧实现错在哪（现场证据）</h2>
     * 旧实现是 {@code carrier.isValid() && !carrier.isDead()}。而
     * <b>区块卸载会把实体引用变成 invalid</b>（实体被从世界的实体表里摘掉、
     * 由 {@code RemovalReason.UNLOADED_TO_CHUNK} 标记）⇒
     * 「玩家死了 / 走远了 ⇒ 竞技场区块卸载」这件事会被读成
     * <b>「信标与防御塔全死了」</b>。现场 {@code logs/latest.log} 把后果写得很清楚：
     * <pre>
     *   18:21:30  YamatoMaya was slain by 蓝方防御塔·左        ← 玩家阵亡（随后重生到很远的地方）
     *   18:21:41  [IC] 目标物已回收 3 个（结束）              ← 6 个里少了 3 个（被当成死了）
     *   18:21:41  [IC] 清场完成：已删除 0 / 登记 11（已失效 11）← 一个都没删掉，却把账本清了
     *   18:22:33  …红方防御塔·左 suffocated in a wall         ← 上一局的塔还立在原地
     * </pre>
     * 用户看到的就是「<b>莫名奇妙胜利了，即使双方都没有防御塔死亡</b>」＋
     * 「<b>对局结束后防御塔等东西没有被移除</b>」（{@code docs/24} §7.1）。
     *
     * <h2>现在的判据：只认阳性证据</h2>
     * <ol>
     *   <li>{@link #dead} 已被显式置位（{@link #markDead}：死亡事件 / 已回收）；</li>
     *   <li>或者<b>账本已经不认它了</b>（{@code match/UnitLifecycleListener} 收到
     *       {@code EntityDeathEvent} 时会 {@code forget} 掉它）——
     *       ★ 注意「账本里还在」= 还活着，**不再看 {@code isValid()}**；</li>
     *   <li>账本不在、但世界查询**确实解析到一个活着的实体** ⇒ 仍然算活
     *       （这是"账本与实体不同步"的自检，会告警；宁可多活一个 tick，也不误判死亡）。</li>
     * </ol>
     * ⇒ 「区块卸载」不再等于「死亡」⇒ 不会再有凭空的 W1 胜利，也不会再丢回收。
     */
    public boolean isAlive(UnitRegistry registry) {
        if (dead) {
            return false;
        }
        if (registry == null) {
            return true;   // 没有账本可比对 ⇒ 保守按"活着"（fail-safe：绝不误判死亡）
        }
        if (registry.isMarkedDead(id())) {
            markDead("死亡事件（UnitLifecycleListener 阳性标记）");
            return false;
        }
        if (registry.isTracked(id())) {
            return true;
        }
        // 账本里已经没有它了 —— 可能是死了（死亡事件注销），也可能是被回收了。
        // ★ 但账本与实体脱节也是一种可能（记账 bug）⇒ 世界查询复核一次。
        Entity live = Bukkit.getEntity(id());
        if (live instanceof LivingEntity living && living.isValid() && !living.isDead()) {
            return true;
        }
        markDead("账本已注销（死亡或已回收）");
        return false;
    }

    /** 是否已经被判定为「不在了」。 */
    public boolean isDead() {
        return dead;
    }

    /**
     * ★★ <b>当前血量</b>（2026-10-17 第十三轮新增 —— 用户裁定侧边栏要显示「塔1 / 塔2 / 信标」的血量）。
     *
     * <p>三条口径（每条都有理由）：
     * <ul>
     *   <li>已被判死（{@link #isDead()}）⇒ 返回 <b>0</b>（侧边栏要显示"掉完了"，而不是旧血量）；</li>
     *   <li>载体解析不到 / 区块卸载（{@code getHealth()} 拿不到）⇒ 返回 <b>0</b>
     *       —— ★ 失败方向取"显示 0"而不是"显示满血"：显示 0 会让人去查，
     *       显示满血会**掩盖**一次真的掉血（本项目「宁可少显示，也不谎报」的一贯口径）；</li>
     *   <li>血量超过上限（原版理论上不会）⇒ 夹到上限，免得进度条越界。</li>
     * </ul>
     * ★ 本方法**只读该实体自己**（不查世界、不查附近）⇒ 与铁律 10 相符。
     */
    public double currentHealth() {
        if (dead) {
            return 0.0;
        }
        try {
            double health = carrier.getHealth();
            if (!(health > 0)) {
                return 0.0;
            }
            return Math.min(health, maxHealth);
        } catch (Throwable t) {
            return 0.0;   // 区块卸载 / 实体已回收 ⇒ 0（见 javadoc 的失败方向）
        }
    }

    /** 判死的原因（日志/排障用）。 */
    public String deathReason() {
        return deathReason;
    }

    /** ★ 唯一的判死入口（阳性证据）：由死亡监听器或回收路径调用。 */
    public void markDead(String reason) {
        this.dead = true;
        this.deathReason = reason == null ? "（未说明）" : reason;
    }

    /** 一个实体的回收结局。 */
    public enum Bucket {
        /** 已经不在了（删掉了 / 账本本来就不认它）—— 账实相符。 */
        SETTLED,
        /** 解析不到实体（多半是区块没加载）⇒ **留在账本里等重试**。 */
        PENDING,
        /** 归属不符（PDC 说它不属于本场）⇒ **故意不删**。 */
        MISMATCHED
    }

    /**
     * 一次回收的结果（两个实体各一个桶）。
     *
     * @param carrier 载体（村民）
     * @param display 展示实体（方块）
     */
    public record Removal(Bucket carrier, Bucket display) {

        public int settled() {
            return count(Bucket.SETTLED);
        }

        public int pending() {
            return count(Bucket.PENDING);
        }

        public int mismatched() {
            return count(Bucket.MISMATCHED);
        }

        private int count(Bucket bucket) {
            int total = 0;
            if (carrier == bucket) {
                total++;
            }
            if (display == bucket) {
                total++;
            }
            return total;
        }

        /** 两个实体都落袋了吗。 */
        public boolean complete() {
            return pending() == 0 && mismatched() == 0;
        }
    }

    /**
     * ★★ <b>回收这个目标物</b>（先删展示实体，再删载体；**幂等**）。
     *
     * <h2>顺序有意为之</h2>
     * 万一第二步抛异常，至少不会留下「看不见的受击目标」
     * （那种状态玩家完全无法理解，比少一个外观严重得多）。
     *
     * <h2>★★ 2026-10-17 第十轮：删不掉的**不许**从账本里抹掉（缺陷 P0-c）</h2>
     * 旧实现无论删没删成功都调 {@code registry.forget(...)} ⇒ 实体一旦因
     * 「区块未加载」而解析不到，账本就把它的线索丢掉 ⇒ <b>它永远留在世界里</b>
     * （现场那串 {@code 红方防御塔·左 suffocated in a wall} 就是这么来的）。
     * ⇒ 现在每个实体单独算桶：
     * <ul>
     *   <li>账本本来不认它 ⇒ {@link Bucket#SETTLED}（死亡事件已经注销过）；</li>
     *   <li>PDC 说它不属于本场 ⇒ {@link Bucket#MISMATCHED}（**绝不删别人的东西**）；</li>
     *   <li>{@code Bukkit.getEntity(uuid) == null}（区块未加载）⇒ {@link Bucket#PENDING}
     *       （★ 留在账本里，由 {@code UnitRegistry#retryPendingRemovals} 每秒重试）；</li>
     *   <li>删成功 ⇒ {@link Bucket#SETTLED} + {@code forget}。</li>
     * </ul>
     *
     * @return 两个实体各自的结局（**可对账**：生成 N 个目标物 ⇒ 回收 N×2 个实体）
     */
    public Removal remove(UnitRegistry registry) {
        markDead("已回收");
        Bucket displayBucket = retire(display, registry);
        Bucket carrierBucket = retire(carrier, registry);
        return new Removal(carrierBucket, displayBucket);
    }

    /** 单个实体的回收（**判据在纯类 {@link ObjectiveRetire}**，这里只做世界操作）。 */
    private Bucket retire(Entity entity, UnitRegistry registry) {
        if (entity == null) {
            return Bucket.SETTLED;      // 没建过 ⇒ 无账可对
        }
        UUID id = entity.getUniqueId();
        // ★★ 2026-10-21 缺陷修复（用户：「对局中对面塔被拆了之后建筑没有移除」）：
        //   判据抽成纯函数 ObjectiveRetire#decide（7 格可逐格断言），这里只负责"照它做"。
        //   ★ 关键变化：旧实现有**两条静默的 return SETTLED**（账本不认它 / 有死亡证据），
        //     而它们对"还立在世界里"的实体就是**永久遗留**（账本一忘，再没人删它，
        //     现场表现正是"塔被打掉了，铜灯方块还在原地"）。现在这两条改成：
        //     只要 PDC 认它是本局的、且实体还解析得到 ⇒ **照删并告警**。
        OwnershipTable.Owner fromPdc = registry == null ? null : registry.ownerFromPdc(entity);
        boolean pdcSaysThisMatch = fromPdc != null && matchId != null
                && matchId.equals(fromPdc.matchId());
        boolean ledgerKnows = registry != null && registry.table().owner(id) != null;
        boolean markedDead = registry != null && registry.isMarkedDead(id);
        Entity live = Bukkit.getEntity(id);
        ObjectiveRetire.Decision decision = ObjectiveRetire.decide(
                pdcSaysThisMatch, ledgerKnows, markedDead, live != null);

        if (decision.warns() && logger != null) {
            logger.warning("[IC] 目标物实体回收：判据自相矛盾，按**归属**（PDC）删掉它 —— "
                    + kind + "/" + side + "｜实体 " + id + "（" + entity.getType() + "）"
                    + "｜账本认得=" + ledgerKnows + "｜有死亡证据=" + markedDead
                    + "｜世界里还在=" + (live != null)
                    + "｜★ 若它不属于我们，下面这一步绝不会发生（PDC 三键先过）");
        }
        if (!decision.deletes()) {
            if (decision == ObjectiveRetire.Decision.PENDING) {
                return Bucket.PENDING;      // 区块未加载 ⇒ 留在账本里等重试（绝不谎报成功）
            }
            if (registry != null) {
                registry.forget(id);        // 落袋（GONE / MISMATCHED 都不需要再重试）
            }
            return decision == ObjectiveRetire.Decision.MISMATCHED
                    ? Bucket.MISMATCHED : Bucket.SETTLED;
        }
        try {
            live.remove();
        } catch (Throwable t) {
            return Bucket.PENDING;          // 删失败 ⇒ 留在账本里等重试（绝不谎报成功）
        }
        if (registry != null) {
            registry.forget(id);
        }
        return Bucket.SETTLED;
    }
}
