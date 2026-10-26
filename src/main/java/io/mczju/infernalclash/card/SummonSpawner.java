package io.mczju.infernalclash.card;

import io.mczju.infernalclash.match.LifecycleTasks;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.MountPlan;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★ <b>召唤卡的落地</b>：把一张卡的全部组按相对排布生成到世界里，并**立刻登记归属**。
 *
 * <h2>铁律 10：这个类只做三件有边界的事</h2>
 * <ol>
 *   <li><b>范围</b>：只在那张卡自己声明的格偏移上生成（偏移来自卡牌 YAML，经
 *       {@link GroupOffsets} 展开）—— 没有一处「附近的地面」之类的查询；</li>
 *   <li><b>归属</b>：生成后**立刻** {@link UnitRegistry#tag} 打 PDC（matchId + side + owner）
 *       ⇒ 清场只可能作用于这些 UUID；</li>
 *   <li><b>对账</b>：返回生成数量，调用方把「这张卡生成了几个」写进日志。</li>
 * </ol>
 *
 * <h2>失败方向</h2>
 * 实体类型解析不了 / 世界拿不到 / 生成抛异常 ⇒ **跳过那一组并告警**，绝不放宽范围去别处生成。
 *
 * <h2>属性</h2>
 * 四级回退见 {@link EffectiveAttributes}（**逐字段**，不是整块覆盖）。
 * ★ AI 装载（`mob-groups.yml` 的 `ai-mode`）**不在本阶段**：那属于 P5（索敌与战斗）。
 */
public final class SummonSpawner {

    private final Plugin plugin;
    private final MobGroupCatalog groups;
    private final UnitRegistry registry;
    private final Logger logger;

    public SummonSpawner(Plugin plugin, MobGroupCatalog groups, UnitRegistry registry) {
        this.plugin = plugin;
        this.groups = groups;
        this.registry = registry;
        this.logger = plugin.getLogger();
    }

    /**
     * ★★ <b>本对局的归属账本</b>（1.1.0 新增，给 {@code spell/EffectApplier} 用）。
     *
     * <p>为什么需要暴露它：1.1.0 的「地壳震击」要判**目标是不是建筑**
     * （防御塔 / 信标 / 6 张打了 {@code building} 的召唤卡），而那个标记在实体 PDC 上、
     * 只有 {@link UnitRegistry#building(Entity)} 读得到；「凋灵领域」还要按账本**每拍重新收集**
     * 范围内的敌人（这样施放后才走进来的也挨打）。两者都需要账本
     * ⇒ 由已经全局安装的 {@code SummonSpawner} 转手给出，**不新增第二个静态入口**。
     *
     * @return 账本（本类构造时必传，不会为 null）
     */
    public UnitRegistry registry() {
        return registry;
    }

    /**
     * 在落点召唤这张卡的全部组。
     *
     * @param center 召唤**落点**（由 {@code PlacementResolver} 判定后的那一格）
     * @param facing 施放者的朝向（相对排布的基准）
     * @return 实际生成的实体数量
     */
    /**
     * 生成后的回调（P6b：能力运行时用它挂 `ON_SUMMON` / `PERIODIC`）。
     *
     * <p>★ 用**可空钩子**而不是构造器注入：`AbilityRuntime` 依赖 `SummonSpawner`（要它来生成
     * 分裂体之类），构造器互相注入会形成环 ⇒ 而且 P4 就踩过「先建 A 后建 B ⇒ A 持有 null」
     * 那个**编译不报错、只在运行期静默失效**的坑（`dev-log/2026-10-15-010`）。
     * 钩子为空时**只是没有能力**，召唤本身照常工作。
     */
    public interface SpawnHook {
        /**
         * @param extraAbilities ★★ 1.1.0：**这个组**独有的能力（{@code group[].abilities}）——
         *        与卡级 {@code summon.abilities} 合并后才是它真正该有的能力清单。
         *        ★ 为什么必须从生成层传下去：能力运行时只拿到实体与卡，**分不清它属于哪一组**。
         */
        void afterSpawn(Entity entity, Card card, String matchId, Side side, java.util.UUID owner,
                        java.util.List<Card.Ability> extraAbilities);
    }

    private SpawnHook spawnHook;

    /** 接线用（由插件在两者都建好之后调用）。 */
    public void setSpawnHook(SpawnHook hook) {
        this.spawnHook = hook;
    }

    /**
     * ★★ 「实体 UUID → 它**未经本插件修改**的 {@code MOVEMENT_SPEED} 基础值」
     * （2026-10-17 批次 3 收尾新增，供 {@link #applySpeedScale} 用）。
     *
     * <p>★ 为什么需要记住原始值：前储君的速度是**会变的倍率**（每 2s 涨一档、命中清零）。
     * 若每次都"读当前基础值再乘"，倍率会**复利累加**（1.0 → 1.2 → 1.68 → 2.69…）。
     * 记住原始值 ⇒ 每次都是 `原始值 × 当前倍率`，才是文档写的**加法**曲线。
     *
     * <p>★ 生命周期：由 {@link #forgetSpeedBase} / {@link #clearSpeedBases} 清；
     * 不清的话这张表会随对局长大（每个召唤物一条）。
     */
    private final Map<UUID, Double> speedBases = new java.util.LinkedHashMap<>();

    /** 对局收尾：清掉全部原始速度记忆（**幂等**）。 */
    public void clearSpeedBases() {
        speedBases.clear();
    }

    public int spawn(Card card, Location center, Facing facing, String matchId, Side side, UUID owner) {
        return spawn(card, center, facing, matchId, side, owner, null);
    }

    /**
     * ★★ 带**本局生命周期任务表**的生成入口 —— 只有这条路径能兑现
     * {@code group[].lifetime-ticks}（2026-10-18 新增，悦灵「40 秒自灭」）。
     *
     * <p>★ 为什么任务表要**按调用传入**而不是在构造器里存一份：{@code SummonSpawner} 是
     * 插件级单例（`new SummonSpawner(plugin, …)`），而 {@link LifecycleTasks} 是**每局一份**
     * （{@code MatchService#tasks()}）⇒ 存单例里就会串局。调试探针等"没有对局"的路径传
     * {@code null}，此时**只告警、不自灭**（绝不把任务挂在无人回收的地方）。
     */
    public int spawn(Card card, Location center, Facing facing, String matchId, Side side, UUID owner,
                     LifecycleTasks tasks) {
        return spawn(card, center, facing, matchId, side, owner, tasks, false);
    }

    /**
     * ★★★ <b>带「这张卡是金卡」的生成入口</b>（2026-10-20 新增）。
     *
     * <p>用户 2026-10-20 逐字：「**金卡召唤/释放时的金色粒子特效没有添加**」。
     * 金卡在本项目里的定义是 {@code docs/08} §3.3 / R52~R55：
     * <b>名称金色 + 打蜡粒子（召唤物移动时也有）</b>。
     * 名字与光效早就有了，粒子一直没有 ⇒ 这条路径补两处：
     * <ol>
     *   <li>生成瞬间在每一只脚下撒一簇（{@link GoldenFx#CAST_BURST_COUNT}）；</li>
     *   <li>之后每 {@link GoldenFx#GLINT_INTERVAL_TICKS} tick 少量跟随
     *       （任务登记在**本局**的 {@link LifecycleTasks} 上，收尾统一取消 ——
     *       绝不出现"实体没了任务还在跑"，那正是 {@code LifecycleTasks} 类注释点名的坑）。</li>
     * </ol>
     *
     * <p>★ 失败方向：粒子抛异常 / 拿不到任务表 ⇒ <b>只告警，绝不影响召唤本身</b>
     * （观感是锦上添花，召唤物能不能上场是玩法）。
     *
     * @param golden 这张卡是不是金色版本（由 {@code CardItems#isGolden} 从 PDC 读出）
     */
    public int spawn(Card card, Location center, Facing facing, String matchId, Side side, UUID owner,
                     LifecycleTasks tasks, boolean golden) {
        if (card == null || !card.isSummon() || card.summon() == null || center == null) {
            return 0;
        }
        World world = center.getWorld();
        if (world == null) {
            logger.warning("[IC] 召唤跳过：落点没有世界（matchId=" + matchId + "，卡 " + card.id() + "）");
            return 0;
        }

        int spawned = 0;
        // ★★ 2026-10-17 批次 3 后半（骑乘）：按**组下标**把生成出来的实体记下来 ——
        //   挂载必须在**所有**组都生成完之后做（载具组先声明、先由上面这段循环生成，
        //   但把挂载集中到最后一步读起来才不会让人以为"顺序无所谓"）。
        List<List<Entity>> byGroup = new ArrayList<>();
        for (Card.Group group : card.summon().groups()) {
            List<Entity> spawnedHere = new ArrayList<>();
            byGroup.add(spawnedHere);
            MobGroupCatalog.Group definition;
            try {
                definition = groups.require(group.entity());
            } catch (RuntimeException e) {
                logger.warning("[IC] 召唤跳过：实体组 " + group.entity() + " 不存在（卡 "
                        + card.id() + "）—— 这张卡在加载期本应被拒绝，请报告这个不一致");
                continue;
            }
            EntityType type;
            try {
                type = EntityType.valueOf(group.entity());
            } catch (IllegalArgumentException e) {
                logger.warning("[IC] 召唤跳过：" + group.entity() + " 不是合法实体类型（卡 " + card.id() + "）");
                continue;
            }
            Class<? extends Entity> entityClass = type.getEntityClass();
            if (entityClass == null) {
                logger.warning("[IC] 召唤跳过：" + group.entity() + " 没有可生成的实体类型（卡 " + card.id() + "）");
                continue;
            }

            Card.Attributes effective = EffectiveAttributes.merge(
                    group.attributes(), card.summon().attributes(), definition.attributes());

            // ★★★ 2026-10-18（用户第 4 条）：这一组要是写了 `rows`，就按**方阵**排
            //   （允许半格，4 只才真的居中）；没写 ⇒ 仍是老的「沿右方向站成一排」
            //   （48/51 张卡走这条，行为一字未改）。
            //   ★ 两种排布在**这里**就都折成"要生成的世界坐标"⇒ 下面那段生成逻辑只有一份
            //     （旧实现把坐标算在循环里，加第二种排布时必定写第二份生成代码，那就分叉了）。
            List<Location> spots = spawnLocations(group, center, facing, world);

            for (Location location : spots) {
                try {
                    Entity entity = world.spawn(location, entityClass);
                    // ★★★ 2026-10-17 第十三轮（用户实机报的缺陷 ②：「召唤物刚被召唤时仍有几秒会
                    //   索敌玩家」）——**把归属登记提到最前面**，理由是一个真实的时序窗口：
                    //   `CombatDirector` 只认**账本里的**实体（`Registry.tracked`），而它每
                    //   `target-refresh-ticks`（默认 10 tick = 0.5s）才扫一遍账本 ⇒
                    //   旧顺序（先属性/名牌/装备、最后登记）让召唤物在**登记之前**就活了
                    //   ⇒ 那段时间里**没有任何东西在管它的目标**，原版 AI 自己挑一个
                    //   （`polar_bear_attack_players` / `shulker_defense_attack` …）
                    //   ⇒ 用户看到的「刚召出来会锁玩家」正是它（他观测到 0.5~1s，与此吻合）。
                    //   ⇒ 现在的顺序：**登记 → 关掉原版会惹事的两个开关 → 属性 → 名牌 → 装备 → 能力**。
                    registry.tag(entity, new OwnershipTable.Owner(matchId, side, owner),
                            card.summon().taunt(), card.id(),
                            // ★★★ 1.1.0（巫术壁垒）：「**只有史莱**被算作建筑」⇒ 组级覆盖卡级。
                            //   没写的组一律跟随卡级（其余 62 张卡行为一字未变）。
                            group.buildingOr(card.summon().building()));
                    // ★ 同一个缺陷的**第二道**：把「会自己捡东西 / 会自然消失」关掉。
                    //   ① `setCanPickupItems(false)`：否则它会捡走地上的装备/掉落物
                    //      （`docs/19` #18 末影人那条「必做 setCanPickupItems(false)」是同一个理由）；
                    //   ② `setRemoveWhenFarAway(false)`：否则玩家走远时它会静默消失
                    //      （本项目头号投诉形状：「莫名奇妙不见了」）。
                    if (entity instanceof org.bukkit.entity.Mob mob) {
                        mob.setCanPickupItems(false);
                        mob.setRemoveWhenFarAway(false);
                    }
                    // ★★ 2026-10-18（全卡审计）：恼鬼的**有限寿命**必须关掉 ——
                    //   `docs/19` 第 63 行逐字写了「⚠️ `Vex#setLimitedLifetime(false)`」，
                    //   而在此之前全仓**一次都没调用**过 ⇒ 利剑嗡嗡的两只恼鬼约 60 秒后
                    //   凭空消失（卡面却写着"不会自行消失"）。
                    //   ★ 单独 try：这一步失败绝不该把后面的名牌/装备/能力一起拖掉。
                    if (entity instanceof org.bukkit.entity.Vex vex) {
                        try {
                            vex.setLimitedLifetime(false);
                        } catch (Throwable t) {
                            logger.warning("[IC] 关闭恼鬼有限寿命失败（卡 " + card.id() + "）：" + t);
                        }
                    }
                    // ★★★ 2026-10-17 第十二轮（用户：「召唤物现在不显示名称，也不会用名称颜色
                    //   区分所属阵营」）：给召唤物一个**可见的、按阵营着色的**名牌。
                    //   出处 docs/09 §6 第 9 项（【C16】）第 430~437 行 —— 它点名的纯函数就是
                    //   policy/SideNameColor#apply(name, side)，而「阵营色最后应用」那条
                    //   （IG 实测坑）由那个纯函数里的「先剥颜色标签」保证。
                    //   ★★ 第十三轮调序：**名牌排在属性之前**（原来属性在名牌前）。
                    //     理由与上面那条同一个：属性的应用会去查 `Attribute`（那个枚举在离线时
                    //     初始化不了），万一它抛了，**名牌就永远设不上**——而名牌恰恰是最显眼的观感；
                    //     现在「登记 → 开关 → 名牌」三步全部排在"可能抛"的属性/装备之前
                    //     ⇒ 任何后续步骤出问题都不会让召唤物变成一个"没名字、还可能回头锁玩家"的幽灵。
                    applyNameTag(entity, card, side);
                    // ★★★ 2026-10-18（用户第 1 条）：「猪灵确实不追我了，但它们的武器一段时间后会消失
                    //   （可能是由于颤抖的原因），请你参照 IG 的代码彻底禁止掉猪灵的转化」
                    //   ⇒ 这道开关必须排在属性/装备之前：转化一旦开始，武器会在转化流程里被丢掉。
                    preventZombification(entity, card, side);
                    // ★★ 2026-10-18（全卡审计）：溺尸的「找水 / 上岸」goal 要摘掉（docs/19 第 173 行海王）
                    // ★★★ 2026-10-21：升级成 **IG 同款 19 条分心 goal**（用户裁定「对于所有怪：IG 同款补全」）
                    //   —— 溺尸那 3 条已包含在这 19 条里，所以原来那个只摘溺尸的方法被它取代了。
                    stripDistractionGoals(entity, card, side);
                    // ★★★ 2026-10-20（用户：「劲弩手频繁回头看防御塔的村民和玩家」）：
                    //   摘掉「看玩家 / 东张西望」两条**只影响头的朝向**的 goal。
                    //   ★ 紧跟在"分心 goal"之后、属性/装备之前：与那几条同一个道理 ——
                    //     摘 goal 只改变观感/走位，失败也绝不该让后面的属性/装备/能力跟着失效。
                    stripLookGoals(entity, card, side);
                    // ★★★ 2026-10-21（用户：「唤魔者…**拦截召唤尖牙行为**」）：
                    //   卡里写了 `fang-attack: true` ⇒ 摘掉原版唤魔者的**尖牙施法 goal**，
                    //   改由卡里的 abilities（眩晕 1 秒 + SPAWN_FANGS）驱动。
                    stripVanillaFangSpell(entity, card, side);
                    applyAttributes(entity, effective);
                    // ★★ 2026-10-17 批次 3 收尾：**铜傀儡涂蜡防氧化**
                    //   （docs/19 第 176 行蒸汽精灵的坑那一栏：「★ 铜傀儡涂蜡防氧化
                    //   （CopperGolem#setOxidizing，API 已核实）」）——
                    //   卡里写 `waxed: true` 才做；失败只告警（绝不因此让召唤物消失）。
                    applyWax(entity, card, side);
                    // ★★★ 2026-10-18：**站桩**（用户：「很多『无法移动』的卡牌的效果依旧没有实现
                    //   （比如烈焰熔炉、异蛙寄生体）」）。
                    //   出处 = docs/19 逐字写了「无法移动」的 5 张：哨位 / 异蛙寄生体 /
                    //   烈焰熔炉 / 悦灵 / 烈焰守卫。
                    //   ★ 用 `LivingEntity#setAI(false)`：它**同时**解决"无法移动"与"无法发射火球"
                    //     （后两条正是烈焰守卫/烈焰熔炉的要求），因为 AI 停了就不会主动寻路或射击。
                    //   ★ 它们的伤害/效果由 abilities 的周期任务给，不依赖原版攻击。
                    applyStationary(entity, card, side);
                    // ★★ 2026-10-17 第十轮（缺陷 P1-b）：装备（铁套铁剑…）——
                    //   出处 docs/19 第 56 行 + 策划案第 10 行（所有盔甲不提供防御）
                    if (entity instanceof LivingEntity living) {
                        EquipmentApplier.equip(living, card.summon().equipment(), logger);
                    }
                    // ★★★ 1.1.0（机动核弹）：「召唤一只**闪电**苦力怕」⇒ 上电。
                    //   ★ 只对苦力怕生效；别的实体写了这一格只告警一次（配置写错不该让召唤物消失）。
                    applyPowered(entity, card, side);
                    // ★★★ 1.1.0（远古监视者）：「会浮在距离地面 **1** 格的位置」
                    //   ⇒ 关重力 + 抬高。★ 抬高放在装备之后：装备不受位置影响。
                    applyHover(entity, card, side);
                    // ★★★ 1.1.0（远古监视者）：「这 4 秒内没有 ai，但是会**播放蓄力的音效**」
                    //   ⇒ 部署期每 1 秒响一次；"这 4 秒内不能打人"由
                    //     `battle/RayAttackService` 按同一份 `summon.deploy` 拦（同一来源）。
                    applyDeploy(entity, card, side, tasks);
                    // ★ P6b：能力（ON_SUMMON 立刻施加、PERIODIC 起周期任务）——
                    //   放在**登记之后**：能力的效果可能要打自己/查归属，先登记才看得见它
                    if (spawnHook != null) {
                        try {
                            spawnHook.afterSpawn(entity, card, matchId, side, owner,
                                    group.abilities());
                        } catch (Throwable t) {
                            logger.warning("[IC] 能力接线回调失败（卡 " + card.id() + "）：" + t);
                        }
                    }
                    spawned++;
                    spawnedHere.add(entity);
                    // ★★ 2026-10-18：存活期（`group[].lifetime-ticks`）—— 悦灵的「40 秒后自行消失」。
                    //   放在**能力接线之后**：到期只是移除实体本身，与能力是否成功挂上没有关系。
                    //   ★ 判据用 `group.hasLifetime()`（Card.Group 的方法）：这是**调用点独有**的记号，
                    //     于是"把这一行删掉"会让常量池里再也没有它 ⇒ 接线测试能真的红
                    //     （若只查 `lifetimeTicks` / `runLater`，删掉调用后它们仍留在私有方法里，
                    //      测试就成了一条永远为真的空断言）。
                    if (group.hasLifetime()) {
                        applyLifetime(entity, group, card, matchId, tasks);
                    }
                    // ★★★ 2026-10-20（用户：「金卡召唤/释放时的金色粒子特效没有添加」）：
                    //   金卡的"打蜡粒子"（docs/08 R52~R55）—— 生成瞬间一簇 + 之后每秒少量跟随。
                    //   ★ 放在**最后**：前面任何一步抛异常都不该让召唤物少掉实体，
                    //     而粒子纯属观感 ⇒ 它排最后，失败也绝不回滚召唤。
                    applyGoldenGlint(entity, golden, tasks, card);
                } catch (Throwable t) {
                    logger.warning("[IC] 召唤失败：卡 " + card.id() + " 在 "
                            + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
                            + " 生成 " + group.entity() + " 时出错：" + t);
                }
            }
        }
        // ★★ 骑乘（`group[].mount`）：把所有组都生成完之后再挂载
        mountPassengers(card, byGroup);
        return spawned;
    }

    /**
     * ★★★ <b>摘掉原版唤魔者的「尖牙施法」goal</b>（{@code VanillaGoal.EVOKER_ATTACK_SPELL}）——
     * 2026-10-21 用户裁定。
     *
     * <h2>用户原话（逐字）</h2>
     * 「唤魔者（原版的尖牙很容易空）：**拦截召唤尖牙行为**，每 3 秒先使目标眩晕 1 秒，
     * 同时在目标处召唤尖牙（最好能调用原版唤魔者召唤尖牙的行为）」。
     *
     * <h2>为什么"摘 goal"就是"拦截"</h2>
     * 原版尖牙的落点由 {@code EvokerAttackSpellGoal} **提前预测**目标走位 —— 预测一旦落空，
     * 尖牙就长在空气里（这就是用户说的"很容易空"）。
     * 那条行为挂在 {@code VanillaGoal.EVOKER_ATTACK_SPELL} 上（26.2 `javap` 实测存在）
     * ⇒ 摘掉它，原版就不会再自己放尖牙；改由卡里的 abilities **先眩晕、再在目标脚下放**。
     *
     * <p>★ 只在卡里写了 {@code summon.fang-attack: true} 时执行（出厂只有唤魔者）。
     * ★ 失败只告警：摘不掉就照原版跑（会多放几根自己预测的尖牙，不会更糟）。
     * ★ 其它施法（召唤恼鬼 / 羊毛诅咒）**一条不动** —— 用户只说了尖牙。
     */
    private void stripVanillaFangSpell(Entity entity, Card card, Side side) {
        if (card == null || card.summon() == null || !card.summon().fangAttack()) {
            return;
        }
        if (!(entity instanceof org.bukkit.entity.Evoker evoker)) {
            logger.warning("[IC] 卡 " + card.id() + " 写了 fang-attack: true，但这一组召唤出来的是 "
                    + entity.getType() + "（不是唤魔者），跳过摘尖牙施法");
            return;
        }
        try {
            // ★ 这个 key 是 `GoalKey<Evoker>`（javap 实测）⇒ 必须用 Evoker 类型的实体去摘
            org.bukkit.Bukkit.getMobGoals().removeGoal(evoker,
                    com.destroystokyo.paper.entity.ai.VanillaGoal.EVOKER_ATTACK_SPELL);
            logger.info("[IC] 已摘掉原版唤魔者的尖牙施法 goal（卡 " + card.id()
                    + "，阵营 " + side + "）—— 尖牙改由 abilities 驱动（眩晕后落在目标脚下）");
        } catch (Throwable t) {
            logger.warning("[IC] 摘尖牙施法 goal 失败（卡 " + card.id() + "，不影响召唤）：" + t);
        }
    }

    // ------------------------------------------------------------------ ★★ 分心 goal（IG 同款 19 条）
    /**
     * ★★★ <b>摘掉「分心 goal」（IG 同款 19 条）</b> —— 2026-10-21 用户裁定。
     *
     * <h2>用户原话（逐字）</h2>
     * 「1、对于**所有怪**：**IG 同款补全（即选 B）**」。
     *
     * <h2>摘什么（清单在 {@code battle/DistractionGoals}）</h2>
     * 惊恐一族（挨打就逃）／溺尸找水（竞技场是干地）／躲太阳·找水／海龟／熊猫 ——
     * 共 **19** 条，逐条抄自 {@code MCZJUGuess: BattleService#DISTRACTION_KEYS}（L1296~1336）。
     * ★ 它们要么**抢 MOVE 槽**（优先级高于攻击 ⇒ 只会逃跑），要么**把攻击 goal 的闸门关上**
     * （熊猫的 {@code canPerformAction()}）⇒ 症状都是「站着 / 乱走，不打人」。
     *
     * <h2>★ 为什么写在"生成时"</h2>
     * 这几个 goal 是原版**出生时就挂上**的（不随状态增删）⇒ 生成那一刻摘一次即可；
     * 每 tick 重摘既浪费也会刷日志。
     *
     * <p>★ 失败只告警：摘不掉就照原版跑，绝不影响召唤本身。
     * ★ 攻击 goal **一条不动**。
     */
    private void stripDistractionGoals(Entity entity, Card card, Side side) {
        if (!(entity instanceof org.bukkit.entity.Mob mob)) {
            return;   // 非 Mob（展示实体类召唤）没有 goal
        }
        try {
            var goals = org.bukkit.Bukkit.getMobGoals();
            if (goals == null) {
                logger.warning("[IC] 摘分心 goal 跳过：MobGoals 服务不可用（卡 " + card.id() + "）");
                return;
            }
            java.util.List<String> removed =
                    io.mczju.infernalclash.battle.DistractionGoals.removeFrom(goals, mob);
            if (removed.isEmpty()) {
                return;   // 它本来就没有这些 goal（绝大多数怪都这样）⇒ 不打日志，别刷屏
            }
            logger.info("[IC] 已摘掉分心 goal（卡 " + card.id() + "，" + mob.getType()
                    + "，阵营 " + side + "）：" + removed
                    + " —— IG 同款清单，见 battle/DistractionGoals");
        } catch (Throwable t) {
            logger.warning("[IC] 摘分心 goal 失败（卡 " + card.id() + "，不影响召唤）：" + t);
        }
    }

    // ------------------------------------------------------------------ ★★ 分心 goal：看玩家 / 东张西望

    /**
     * ★★★ <b>摘掉「看玩家」与「东张西望」两条分心 goal</b> —— 2026-10-20 新增。
     *
     * <h2>用户实机原话（逐字）</h2>
     * 「并且它（劲弩手）**频繁回头看防御塔的村民和玩家**，索敌系统还是有小问题」。
     *
     * <h2>★ 查证结论：这不是"索敌"错了，是**头的朝向**被原版 look goal 拽走了</h2>
     * <ul>
     *   <li>索敌只发生在 {@code battle/CombatDirector} 里（候选只从账本取、玩家被显式剔除 R42），
     *       而每条 {@code SummonCombat#reassert()} 每 tick 把非我方目标清掉 ⇒
     *       **目标**本身不会跑到玩家身上（`PlayerTargetExclusionTest` 一直钉着这条）。</li>
     *   <li>但**头的朝向**是原版 goal 管的：{@code VanillaGoal.LOOK_AT_PLAYER}
     *       （任何 {@code Mob} 都有）会让它**扭头盯着最近的玩家**，
     *       而 {@code RANDOM_LOOK_AROUND} 让它没事东张西望 —— 两者都与"在打谁"无关
     *       ⇒ 玩家看到的正是「频繁回头看玩家」。（村民同理：塔的载体就是村民，
     *       它作为**敌方建筑**本来就在候选里；头扭过去的观感与"锁玩家"一样令人误解。）</li>
     * </ul>
     *
     * <h2>修法（只动观感，不动玩法）</h2>
     * 对本插件接管的**每一只**召唤物摘掉这两条 goal；"看向真正的目标"由
     * {@code battle/SummonCombat#faceTarget()} 每 tick 负责（目标为空时什么都不做）。
     * ★ 攻击 goal **一条不动**（摘了它们，弩手照样会瞄准并射击它的目标）。
     * ★ 失败只告警：摘不掉就照原版跑，绝不影响召唤本身（观感问题不该让单位消失）。
     */
    private void stripLookGoals(Entity entity, Card card, Side side) {
        if (!(entity instanceof org.bukkit.entity.Mob mob)) {
            return;   // 非 Mob（掉落物类召唤）没有 goal
        }
        try {
            var goals = org.bukkit.Bukkit.getMobGoals();
            goals.removeGoal(mob, com.destroystokyo.paper.entity.ai.VanillaGoal.LOOK_AT_PLAYER);
            goals.removeGoal(mob, com.destroystokyo.paper.entity.ai.VanillaGoal.RANDOM_LOOK_AROUND);
            logger.info("[IC] 已摘掉「看玩家 / 东张西望」goal（卡 " + card.id()
                    + "，阵营 " + side + "）—— 头的朝向只认真正的目标");
        } catch (Throwable t) {
            logger.warning("[IC] 摘掉 look goal 失败（卡 " + card.id() + "，不影响召唤）：" + t);
        }
    }

    // ------------------------------------------------------------------ ★★ 存活期（lifetime-ticks）

    /**
     * ★★ <b>给这一只召唤物登记「N tick 后自行消失」</b>（{@code group[].lifetime-ticks}）。
     *
     * <p>出处：{@code docs/19} 第 153 行悦灵「**召唤后 40s 直接死亡**」；
     * 键名早就在 {@code docs/03} §7 定名，但一直**没有可写的位置**（卡 YAML 里自己注释承认了）。
     *
     * <h2>★ 失败方向（两条，都不许猜）</h2>
     * <ul>
     *   <li><b>没有任务表</b>（{@code tasks == null}，例如 {@code /incl debug} 的探针路径）⇒
     *       <b>只告警、实体照常生成、不自灭</b> —— 绝不把任务挂在无人回收的地方；</li>
     *   <li>到期时实体<b>已经不在</b>（被别的机制删了 / 已死亡）⇒ 只注销账本条目，
     *       不做任何"补偿性"操作。</li>
     * </ul>
     *
     * <p>★ 归属判定：到期移除前先问 {@link UnitRegistry#isOwnedBy(Entity, String)}
     * （{@code 铁律 10} 第 3 问）—— 绝不因为"UUID 相同"就删一个不属于本局的实体。
     */
    private void applyLifetime(Entity entity, Card.Group group, Card card, String matchId,
                               LifecycleTasks tasks) {
        Integer ticks = group.lifetimeTicks();
        if (ticks == null) {
            return;
        }
        if (tasks == null) {
            logger.warning("[IC] 存活期未登记：卡 " + card.id() + " 的 group 写了 lifetime-ticks=" + ticks
                    + "，但这次召唤没有本局任务表（调试探针？），这一只不会自灭"
                    + "（matchId=" + matchId + "，实体 " + entity.getUniqueId() + "）");
            return;
        }
        final UUID id = entity.getUniqueId();
        tasks.runLater(plugin, () -> expireLifetime(entity, id, card.id(), ticks, matchId), ticks);
    }

    // ------------------------------------------------------------------ ★★ 金卡粒子（2026-10-20）

    /**
     * ★★ <b>金卡的「打蜡粒子」</b>（用户 2026-10-20：「金卡召唤/释放时的金色粒子特效没有添加」）。
     *
     * <p>两段（参数全在 {@link io.mczju.infernalclash.policy.GoldenFx}，不在这里写第二份）：
     * <ol>
     *   <li><b>生成瞬间</b>：在每只召唤物身上撒一簇（{@code CAST_BURST_COUNT}）；</li>
     *   <li><b>之后跟随</b>：每 {@code GLINT_INTERVAL_TICKS}（20 tick = 1 秒）少量（{@code GLINT_COUNT}），
     *       直到实体消失 / 对局收尾 / 到 {@code GLINT_MAX_TICKS} 拍。</li>
     * </ol>
     *
     * <h2>★ 为什么任务必须挂在 {@link LifecycleTasks} 上</h2>
     * {@code LifecycleTasks} 自己的类注释点名的第二个事故就是「<b>金色粒子跟随（R55）
     * 的任务若不在实体死亡时取消 ⇒ 实体没了任务还在跑</b>」——
     * 本方法正是那条要求的落点：① 实体没了就在下一拍自己 cancel；
     * ② 对局收尾时 {@code cancelAll()} 兜底；③ 还有 {@code GLINT_MAX_TICKS} 的固定上限。
     *
     * <p>★ 失败方向：**只告警**。粒子是观感，召唤物能不能上场是玩法 ——
     * 这里抛出去会把 {@code spawn(...)} 的这一次循环打断（调用点在 try 里，但会少一只怪）。
     */
    private void applyGoldenGlint(Entity entity, boolean golden, LifecycleTasks tasks, Card card) {
        if (!golden || entity == null) {
            return;
        }
        try {
            org.bukkit.Particle particle = org.bukkit.Particle
                    .valueOf(io.mczju.infernalclash.policy.GoldenFx.PARTICLE);
            spawnGoldenParticles(entity, particle,
                    io.mczju.infernalclash.policy.GoldenFx.CAST_BURST_COUNT,
                    io.mczju.infernalclash.policy.GoldenFx.CAST_BURST_SPREAD);

            if (tasks == null) {
                // 调试探针（没有对局）⇒ 只撒生成那一簇，不起跟随任务（绝不挂在无人回收的地方）
                return;
            }
            final UUID id = entity.getUniqueId();
            final int[] beats = {0};
            final org.bukkit.scheduler.BukkitTask[] handle = new org.bukkit.scheduler.BukkitTask[1];
            handle[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                beats[0]++;
                if (beats[0] > io.mczju.infernalclash.policy.GoldenFx.GLINT_MAX_TICKS) {
                    // 第二道保险：登记漏了也不会永远跑下去
                    if (handle[0] != null) {
                        handle[0].cancel();
                    }
                    return;
                }
                Entity current = org.bukkit.Bukkit.getEntity(id);
                if (current == null || !current.isValid() || current.isDead()) {
                    if (handle[0] != null) {
                        handle[0].cancel();   // 实体没了 ⇒ 任务自己收掉（LifecycleTasks 的第二个事故）
                    }
                    return;
                }
                spawnGoldenParticles(current, particle,
                        io.mczju.infernalclash.policy.GoldenFx.GLINT_COUNT,
                        io.mczju.infernalclash.policy.GoldenFx.GLINT_SPREAD);
            },
                    io.mczju.infernalclash.policy.GoldenFx.GLINT_INITIAL_DELAY_TICKS,
                    io.mczju.infernalclash.policy.GoldenFx.GLINT_INTERVAL_TICKS);
            tasks.track(handle[0]);
        } catch (Throwable t) {
            logger.warning("[IC] 金卡粒子未能启动（卡 " + card.id() + "，不影响召唤）：" + t);
        }
    }

    /** 在实体「身体中部」撒一簇粒子（纯表现；参数由调用方给）。 */
    private void spawnGoldenParticles(Entity entity, org.bukkit.Particle particle,
                                      int count, double spread) {
        World world = entity.getWorld();
        Location at = entity.getLocation().clone();
        at.add(0.0, Math.max(0.5, entity.getHeight() * 0.5), 0.0);
        world.spawnParticle(particle, at, count, spread, spread * 0.8, spread, 0.0);
    }

    /** 存活期到期：按归属判定移除实体，并注销账本条目（**不触发亡语** —— 它不是被打死的）。 */
    private void expireLifetime(Entity entity, UUID id, String cardId, int ticks, String matchId) {
        boolean removed = false;
        try {
            if (entity != null && entity.isValid() && registry.isOwnedBy(entity, matchId)) {
                entity.remove();
                removed = true;
            }
        } catch (Throwable t) {
            logger.warning("[IC] 存活期到期移除失败（卡 " + cardId + "，实体 " + id + "）：" + t);
        }
        try {
            registry.forget(id);
        } catch (Throwable t) {
            logger.warning("[IC] 存活期到期注销账本失败（实体 " + id + "）：" + t);
        }
        logger.info("[IC] 召唤物到期：卡 " + cardId + " 存活 " + ticks + " tick，"
                + "移除=" + (removed ? "是" : "否（已不在或不属于本局）")
                + "（matchId=" + matchId + "）");
    }

    /**
     * ★★ <b>把乘客挂到载具上</b>（{@code group[].mount}，2026-10-17 批次 3 后半新增）。
     *
     * <h2>配对口径</h2>
     * 交给纯函数 {@link MountPlan#pairs(List)}（有逐格单测）：<b>载具组必须先声明</b>，
     * 乘客组用<b>实体类型名</b>指回去；同名载具写两份时坐**先声明**的那一份。
     *
     * <h2>★ 失败方向</h2>
     * 载具那组没生成出来（生成失败 / 数量 0）/ 座位已满（{@code addPassenger} 返回 false）
     * / 抛异常 ⇒ <b>只告警，绝不删掉乘客</b> —— 一个站在地上的乘客仍然是一只正常的召唤物，
     * 而"把它删掉"会让玩家付了费却什么都没得到。
     *
     * <h2>★ 数量口径</h2>
     * 按<b>下标一一对应</b>挂载（第 i 个乘客坐第 i 个载具）；乘客比载具多时多出来的那些
     * 站在地上（并告警一次）。{@code docs/19} 的三张骑乘卡都是 1 对 1，所以这条口径
     * 在实机上只会走"一一对应"那一条路。
     */
    private void mountPassengers(Card card, List<List<Entity>> byGroup) {
        List<MountPlan.Group> declared = new ArrayList<>(card.summon().groups().size());
        for (Card.Group group : card.summon().groups()) {
            declared.add(new MountPlan.Group(group.entity(), group.mount()));
        }
        List<MountPlan.Pair> pairs = MountPlan.pairs(declared);
        if (pairs.isEmpty()) {
            return;
        }
        for (MountPlan.Pair pair : pairs) {
            List<Entity> vehicles = byGroup.get(pair.vehicle());
            List<Entity> passengers = byGroup.get(pair.passenger());
            if (vehicles.isEmpty() || passengers.isEmpty()) {
                logger.warning("[IC] 骑乘跳过：" + declared.get(pair.passenger()).entity()
                        + " 要骑 " + declared.get(pair.vehicle()).entity()
                        + "，但其中一方这次没有生成出来"
                        + "（载具 " + vehicles.size() + " 个 / 乘客 " + passengers.size()
                        + " 个，卡 " + card.id() + "）");
                continue;
            }
            int mounted = 0;
            for (int index = 0; index < passengers.size(); index++) {
                Entity passenger = passengers.get(index);
                // ★ 乘客比载具多 ⇒ 用取模轮流坐（1 对 1 时等价于一一对应）
                Entity vehicle = vehicles.get(index % vehicles.size());
                if (passenger == null || vehicle == null
                        || passenger.getUniqueId().equals(vehicle.getUniqueId())) {
                    continue;
                }
                try {
                    if (vehicle.addPassenger(passenger)) {
                        mounted++;
                    } else {
                        logger.warning("[IC] 骑乘失败：" + passenger.getType() + " 没能坐上 "
                                + vehicle.getType() + "（座位已满 / 原版拒绝，卡 " + card.id()
                                + "）—— 它仍然作为一只正常召唤物留在地上");
                    }
                } catch (Throwable t) {
                    logger.warning("[IC] 骑乘失败（卡 " + card.id() + "）：" + t
                            + " —— 乘客仍然留在地上，不会被删掉");
                }
            }
            if (mounted > 0) {
                logger.info("[IC] 骑乘：" + mounted + " 名 " + declared.get(pair.passenger()).entity()
                        + " 坐上了 " + declared.get(pair.vehicle()).entity()
                        + "（卡 " + card.id() + "）");
            }
        }
    }

    /**
     * ★★ <b>这一组要生成在哪些坐标上</b>（三种排布的唯一分岔点）。
     *
     * <ul>
     *   <li>{@code group.formation} 非空 ⇒ <b>成形排布</b>（{@link GroupOffsets#polygon}，
     *       正五边形；允许非整格）—— 2026-10-18 新增（用户第 4 条：「**我希望这五个猪灵
     *       最好能站成一个类似正五边形**」）；</li>
     *   <li>{@code group.rows} 非空 ⇒ <b>方阵</b>（{@link GroupOffsets#rows}，允许半格）；</li>
     *   <li>否则 ⇒ <b>沿右方向站成一排</b>（{@link GroupOffsets#offsets}，整格；既有 47 张卡）。</li>
     * </ul>
     * ★ 三条路都在这里折成 {@link Location} ⇒ 生成逻辑（登记 / 名牌 / 属性 / 装备 / 能力）
     * 只有一份。旧实现把坐标算在生成循环里，那意味着"加第二种排布 = 复制一份生成代码"，
     * 而复制出来的那份迟早会漏掉登记或名牌。
     * ★ {@code formation} 与 {@code rows} <b>互斥</b>（装载期就拒绝），所以这里的先后
     * 只是"读起来顺"，不是"哪条优先"。
     */
    private List<Location> spawnLocations(Card.Group group, Location center, Facing facing, World world) {
        List<Location> spots = new ArrayList<>();
        if (group.hasFormation()) {
            for (GroupOffsets.PreciseOffset offset : GroupOffsets.polygon(
                    group.count(), group.forward(), group.right(), group.spread())) {
                double[] point = GroupOffsets.toWorldPoint(center.getBlockX(), center.getBlockZ(),
                        offset, facing);
                spots.add(safeSpawnLocation(world, point[0], point[1], center.getBlockY()));
            }
            return spots;
        }
        if (group.hasRows()) {
            for (GroupOffsets.PreciseOffset offset : GroupOffsets.rows(
                    group.rows(), group.forward(), group.right(), group.spread())) {
                double[] point = GroupOffsets.toWorldPoint(center.getBlockX(), center.getBlockZ(),
                        offset, facing);
                spots.add(safeSpawnLocation(world, point[0], point[1], center.getBlockY()));
            }
            return spots;
        }
        for (GroupOffsets.Offset offset : GroupOffsets.offsets(
                group.count(), group.forward(), group.right(), group.spread())) {
            int[] cell = GroupOffsets.toWorldCell(center.getBlockX(), center.getBlockZ(),
                    offset, facing);
            spots.add(safeSpawnLocation(world, cell[0] + 0.5, cell[1] + 0.5, center.getBlockY()));
        }
        return spots;
    }

    /**
     * 找一个「站得住」的落点：以参照高度为基准，向下 3 格 / 向上 2 格内找**第一个可站立的方块顶面**。
     *
     * <p>★ 为什么要这么做而不是直接用落点坐标：卡牌给的是**格**，而召唤物的脚必须落在
     * 实体方块的上表面 —— 直接把实体生成在方块内部会让它窒息或弹出来。
     * 扫描范围**固定 6 格**（不是「找附近的地面」那种开放搜索），找不到就用落点原样（并告警）。
     *
     * @param x 世界 X（**可以是半格**：方阵里 4 只那一行会落在格边界上）
     * @param z 世界 Z
     */
    private Location safeSpawnLocation(World world, double x, double z, int referenceY) {
        int cellX = (int) Math.floor(x);
        int cellZ = (int) Math.floor(z);
        for (int y = referenceY + 2; y >= referenceY - 3; y--) {
            Block block = world.getBlockAt(cellX, y, cellZ);
            if (!block.isPassable()) {
                Block above = block.getRelative(0, 1, 0);
                if (above.isPassable()) {
                    return new Location(world, x, above.getY(), z);
                }
            }
        }
        logger.warning("[IC] 落点 " + cellX + "," + referenceY + "," + cellZ
                + " 附近 6 格内没有可站立的方块，按原坐标生成（可能卡在方块里）");
        return new Location(world, x, referenceY, z);
    }

    /**
     * ★★★ <b>给召唤物挂上「可见的、按阵营着色的」名牌</b>
     * （2026-10-17 第十二轮新增，用户：「召唤物现在不显示名称，也不会用名称颜色区分所属阵营」）。
     *
     * <h2>文档依据（逐字）</h2>
     * {@code docs/09-UI设计.md} §6 第 9 项（【C16】）：
     * 「<b>对战中用红 / 蓝名字标记双方召唤物</b> ⇒ 需要按阵营给名牌着色」
     * ＋ 第 431 行「抽成纯函数 {@code SideNameColor.apply(name, side)}」
     * ＋ 第 434/437 行「MiniMessage 颜色会盖掉后设色 ⇒ <b>生物名前必须重新应用阵营色</b>」
     * 「金卡 {@code <gold>} 与阵营红/蓝同处一个名牌 ⇒ <b>阵营色最后应用</b>」。
     *
     * <h2>★ 两件事，两个唯一落点</h2>
     * <ol>
     *   <li>「名字长什么样、什么颜色」⇒ 纯函数 {@link io.mczju.infernalclash.policy.SideNameColor}
     *       （★ <b>全项目唯一</b>的颜色映射；目标物也该来问它）；</li>
     *   <li>「写进实体」⇒ 本方法（{@code setCustomName} + {@code setCustomNameVisible(true)}）。</li>
     * </ol>
     *
     * <h2>★ 失败方向</h2>
     * 实体不支持自定义名（少数非 {@code LivingEntity} / 名字组件解析失败）
     * ⇒ <b>只跳过名牌</b>，绝不让整只召唤物消失、也绝不改成一个假名字。
     */
    /**
     * ★★ <b>铜傀儡涂蜡防氧化</b>（2026-10-17 批次 3 收尾新增）。
     *
     * <h2>文档依据（逐字）</h2>
     * {@code docs/19-卡牌总表.md} 第 176 行蒸汽精灵的坑那一栏：
     * 「★ 铜傀儡<b>涂蜡防氧化</b>（{@code CopperGolem#setOxidizing}，API 已核实）」。
     * 用户 2026-10-17 的裁定也点名了它（「做掉」清单里的 B 类块之一）。
     *
     * <h2>★ API（`javap` 实测 26.2）</h2>
     * {@code CopperGolem#setOxidizing(Oxidizing)}；
     * {@code Oxidizing} 是一个 <b>registry-backed 接口</b>，取值由静态工厂给：
     * {@code Oxidizing.waxed()} / {@code unset()} / {@code atTime(long)}
     * ⇒ 「涂蜡」= {@code setOxidizing(Oxidizing.waxed())}。
     * ★ 这里用的是<b>静态工厂而不是常量字段</b>：它不需要服务端注册表就能构造，
     * 而且语义更明确（"涂过蜡"而不是"某个时间点"）。
     *
     * <h2>★ 失败方向</h2>
     * 卡里没写 {@code waxed: true} / 实体不是铜傀儡 / 设置抛异常
     * ⇒ <b>什么都不做（或只告警）</b>：氧化只是外观与行为的渐变，
     * 绝不因为它失败而让整只召唤物消失，也绝不去"顺手换一种铜傀儡"。
     */
    private void applyWax(Entity entity, Card card, Side side) {
        if (card == null || card.summon() == null || !card.summon().waxed()) {
            return;   // 绝大多数卡：不碰
        }
        // ★ 2026-10-21（蒸汽精灵）：`summon.oxidized: true` ⇒ 先把风化状态设成 OXIDIZED，再涂蜡
        //   （顺序不能反：涂蜡之后风化状态就不再变化，先设状态才不会被打蜡"锁"在未氧化）。
        if (card.summon().oxidized()) {
            if (entity instanceof org.bukkit.entity.CopperGolem copperGolem) {
                try {
                    copperGolem.setWeatheringState(io.papermc.paper.world.WeatheringCopperState.OXIDIZED);
                    logger.info("[IC] 已设为锈蚀外观：" + entity.getType() + "（卡 " + card.id() + "）");
                } catch (Throwable t) {
                    logger.warning("[IC] 设锈蚀外观失败（卡 " + card.id() + "，只影响外观）：" + t);
                }
            } else {
                logger.warning("[IC] 卡 " + card.id() + " 写了 oxidized: true，但召唤物不是铜傀儡（"
                        + entity.getType() + "），跳过");
            }
        }
        if (!(entity instanceof org.bukkit.entity.CopperGolem golem)) {
            logger.warning("[IC] 卡 " + card.id() + " 写了 waxed: true，但这一组召唤出来的是 "
                    + entity.getType() + "（不是铜傀儡），跳过涂蜡");
            return;
        }
        try {
            golem.setOxidizing(org.bukkit.entity.CopperGolem.Oxidizing.waxed());
            logger.info("[IC] 已涂蜡防氧化：" + entity.getType() + "（卡 " + card.id()
                    + "，阵营 " + side + "）—— docs/19 第 176 行的坑那一栏");
        } catch (Throwable t) {
            logger.warning("[IC] 涂蜡失败（卡 " + card.id() + "）：" + t
                    + "，只跳过涂蜡，单位照常存在（它只是会随时间氧化）");
        }
    }

    /**
     * ★★★ <b>「闪电苦力怕」</b>（1.1.0 机动核弹，{@code summon.powered}）。
     *
     * <p>出处（{@code docs/1.1卡牌.txt}）：「召唤一只<b>闪电苦力怕</b>，且召唤时其获得 5s 抗性提升 V」。
     * 后半句由卡里的 {@code abilities}（{@code ON_SUMMON} ＋ {@code POTION RESISTANCE}）承担，
     * 本方法只负责"上电"。
     *
     * <p>★ 失败方向：实体不是苦力怕 ⇒ <b>只告警</b>（配置写错不该让召唤物消失）；
     * {@code setPowered} 抛异常同理。
     */
    private void applyPowered(Entity entity, Card card, Side side) {
        if (card == null || card.summon() == null || !card.summon().extra().powered()) {
            return;
        }
        if (!(entity instanceof org.bukkit.entity.Creeper creeper)) {
            logger.warning("[IC] 卡 " + card.id() + " 写了 powered: true，但这一组召唤出来的是 "
                    + entity.getType() + "（不是苦力怕），跳过上电");
            return;
        }
        try {
            creeper.setPowered(true);
            logger.info("[IC] 已上电（闪电苦力怕）：" + entity.getType() + "（卡 " + card.id()
                    + "，阵营 " + side + "）");
        } catch (Throwable t) {
            logger.warning("[IC] 给苦力怕上电失败（卡 " + card.id() + "）：" + t
                    + "，它会以普通苦力怕登场");
        }
    }

    /**
     * ★★★ <b>悬浮</b>（1.1.0 远古监视者，{@code summon.hover}）。
     *
     * <p>出处（{@code docs/1.1卡牌.txt}）：「召唤的远古守卫者会<b>浮在距离地面 1 格的位置</b>，
     * 且<b>不会因为在空气中而窒息</b>」。前半句 = 关重力 ＋ 抬高；后半句由卡里的
     * {@code POTION WATER_BREATHING} 承担（不写死在代码里，卡可以自己决定）。
     *
     * <p>★ 为什么必须关重力：只抬高 1 格的话，下一 tick 它就掉回地面 ——
     * 玩家看到的会是"抖一下"而不是"浮着"。
     * ★ 失败只告警：浮不起来只会让它贴地（能打、能被打），不影响对局。
     */
    private void applyHover(Entity entity, Card card, Side side) {
        if (card == null || card.summon() == null || card.summon().extra().hover() == null) {
            return;
        }
        double hover = card.summon().extra().hover();
        if (!(entity instanceof org.bukkit.entity.LivingEntity living)
                || entity.getWorld() == null) {
            logger.warning("[IC] 卡 " + card.id() + " 写了 hover，但这一组不是生物（"
                    + entity.getType() + "），跳过悬浮");
            return;
        }
        try {
            living.setGravity(false);
            org.bukkit.Location from = living.getLocation();
            living.teleport(from.clone().add(0.0, hover, 0.0));
            logger.info("[IC] 已悬浮 " + hover + " 格：" + entity.getType() + "（卡 " + card.id()
                    + "，阵营 " + side + "）");
        } catch (Throwable t) {
            logger.warning("[IC] 悬浮失败（卡 " + card.id() + "）：" + t + "，它会贴地登场");
        }
    }

    /**
     * ★★★ <b>部署期</b>（1.1.0 远古监视者，{@code summon.deploy}）。
     *
     * <p>出处（{@code docs/1.1卡牌.txt}）：「该单位放置后需要 <b>4 秒</b>时间部署，
     * 这 4 秒内<b>没有 ai</b>，但是会<b>播放蓄力的音效</b>」。
     *
     * <h2>拆成两半，两半读同一份配置</h2>
     * <ol>
     *   <li><b>音效</b>（本方法）：每 {@link Card.Summon.Deploy#SOUND_PERIOD_TICKS} tick 播一次，
     *       共播 {@code ticks / PERIOD} 次（4 秒 ⇒ 4 次）。任务登记在**本局**
     *       {@link LifecycleTasks} 上（没有任务表 ⇒ 只告警，绝不挂在无人回收的地方）；</li>
     *   <li><b>不能攻击</b>：{@code battle/RayAttackService} 读同一份
     *       {@code summon.deploy} ⇒ 部署期结束前不发射线（判据只有一处）。</li>
     * </ol>
     * ★ "没有 ai" 由卡上的 {@code stationary: true} 承担（部署完成后也不需要走动 ——
     * 远古监视者是建筑）。★ 失败只告警：听不到蓄力声不影响它 4 秒后能不能开火。
     */
    private void applyDeploy(Entity entity, Card card, Side side, LifecycleTasks tasks) {
        if (card == null || card.summon() == null || card.summon().extra().deploy() == null) {
            return;
        }
        Card.Summon.Deploy deploy = card.summon().extra().deploy();
        if (!deploy.hasSound()) {
            return;
        }
        if (tasks == null) {
            logger.warning("[IC] 卡 " + card.id() + " 写了 deploy.sound，但没有可用的生命周期任务表"
                    + "（探针路径？）⇒ 不播蓄力音效（部署期本身仍由射线执行层拦）");
            return;
        }
        int period = Card.Summon.Deploy.SOUND_PERIOD_TICKS;
        int beats = Math.max(1, deploy.ticks() / period);
        for (int i = 0; i < beats; i++) {
            final int beat = i + 1;
            tasks.runLater(plugin, () -> {
                try {
                    if (entity instanceof org.bukkit.entity.LivingEntity living
                            && living.isValid() && !living.isDead()) {
                        living.getWorld().playSound(living.getLocation(), deploy.sound(), 1.0f, 1.0f);
                    }
                } catch (Throwable t) {
                    logger.warning("[IC] 蓄力音效播放失败（卡 " + card.id() + "，第 " + beat
                            + " 拍，只影响观感）：" + t);
                }
            }, (long) beat * period);
        }
        logger.info("[IC] 部署期开始：" + entity.getType() + "（卡 " + card.id() + "）—— "
                + deploy.ticks() + " tick 内不能攻击，蓄力音效 " + beats + " 拍（每 "
                + period + " tick 一拍）");
    }

    /**
     * ★★★ <b>站桩：关掉 AI ⇒ 不移动、也不主动射击</b>
     * （2026-10-18 新增；用户：「**很多「无法移动」的卡牌的效果依旧没有实现
     * （比如烈焰熔炉、异蛙寄生体）**」）。
     *
     * <h2>覆盖哪几张（`docs/19` 逐字写了「无法移动」的全部）</h2>
     * {@code sentry} 哨位 / {@code frog_parasite} 异蛙寄生体 / {@code blaze_furnace} 烈焰熔炉 /
     * {@code allay} 悦灵 / {@code blaze_guard} 烈焰守卫。
     *
     * <h2>为什么用 {@code setAI(false)} 而"只摘移动 goal"</h2>
     * 这 5 张里有 3 张**同时**要求「无法发射火球」（烈焰守卫 / 烈焰熔炉）或「不攻击」（哨位）。
     * {@code setAI(false)} **一个动作解决两件事**；而"只摘移动 goal"要枚举每一类怪的所有移动 goal，
     * 漏一个就"看起来站桩、其实会走"—— 那正是本次报的缺陷形状。
     * ★ 代价：它们也**不再注视**玩家、不播闲晃音效。它们的伤害/效果全部由
     *   {@code abilities} 的周期任务给 ⇒ 不依赖原版攻击。
     * ★ 失败只告警：站不住是**观感/行为**问题，不该让召唤物消失。
     */
    private void applyStationary(Entity entity, Card card, Side side) {
        if (card == null || card.summon() == null || !card.summon().stationary()) {
            return;   // 绝大多数卡：不碰
        }
        if (!(entity instanceof org.bukkit.entity.Mob mob)) {
            logger.warning("[IC] 卡 " + card.id() + " 写了 stationary: true，但这一组召唤出来的是 "
                    + entity.getType() + "（不是 Mob），跳过「无法移动」");
            return;
        }
        try {
            mob.setAI(false);
            logger.info("[IC] 已设为站桩（setAI(false)，不移动、不主动射击）："
                    + entity.getType() + "（卡 " + card.id() + "，阵营 " + side + "）");
        } catch (Throwable t) {
            logger.warning("[IC] 设站桩失败（卡 " + card.id() + "）：" + t
                    + "，它会照常移动");
        }
    }


    private void applyNameTag(Entity entity, Card card, Side side) {
        try {
            if (entity.customName() != null) {
                return;   // 已经有名字的（将来若某个接管模块自己起了名）⇒ 不覆盖
            }
            String miniMessage = io.mczju.infernalclash.policy.SideNameColor.apply(
                    card.displayName(), side);
            Component name = MiniMessage.miniMessage().deserialize(miniMessage);
            entity.customName(name);
            entity.setCustomNameVisible(true);
        } catch (Throwable t) {
            logger.warning("[IC] 召唤物名牌设置失败（卡 " + card.id() + "，阵营 " + side + "）：" + t
                    + "，只跳过名牌，单位照常存在（绝不改成假名字）");
        }
    }

    /** 应用四级回退后的属性；{@code null} 字段一律不动（= 原版默认）。 */
    private void applyAttributes(Entity entity, Card.Attributes attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        // ★★★ 2026-10-18（用户第 3 条「无法控制岩浆怪的尺寸」）：**尺寸档位必须排在血量之前**。
        //   根因（API 事实）：`Slime#setSize(int)` 内部会 `setHealth(getMaxHealth())`
        //   —— 而岩浆怪/史莱姆的最大血量 = 尺寸² ⇒ 先写血、再改尺寸会把血**重置**成 size²
        //   （卡里写的 40 血会静默变回 16）。这一条顺序错了不会有任何报错，只会"血量不对"。
        if (attributes.size() != null) {
            applySize(entity, attributes.size());
        }
        if (attributes.health() != null) {
            setAttribute(entity, Attribute.MAX_HEALTH, attributes.health());
            if (entity instanceof LivingEntity living) {
                // 先抬上限再回血，否则 setHealth 会被旧上限截断
                living.setHealth(Math.min(living.getMaxHealth(), attributes.health()));
            }
        }
        if (attributes.attackDamage() != null) {
            setAttribute(entity, Attribute.ATTACK_DAMAGE, attributes.attackDamage());
        }
        if (attributes.armor() != null) {
            setAttribute(entity, Attribute.ARMOR, attributes.armor());
        }
        if (attributes.movementSpeed() != null) {
            // ★★★ 2026-10-17 第十三轮：**`movement-speed` 的语义改成「倍率」**（用户裁定，逐字：
            //   「是**倍率**（+20% 这种）」，docs/24 §10 第 12 条）。
            //
            //   ★ 旧实现直接 `setBaseValue(value)` ⇒ 那是**绝对值**语义：写 1.2 会得到一个
            //     **比玩家还快**的怪（原版僵尸的 MOVEMENT_SPEED 基础值是 0.23，
            //     玩家的约 0.1）—— 而卡表里写的「×200%」「+20% 尺寸」全是**相对**说法。
            //   ⇒ 现在读**实体自己的基础值**再乘：`新基础值 = 原基础值 × 倍率`。
            //   ★ 为什么读 `getBaseValue()` 而不是 `getValue()`：后者含药水/装备的临时修饰，
            //     乘上去会**叠加两次**（本次改动 + 药水效果），而且下次再乘会滚雪球。
            //   ★ 失败方向：这个实体**没有** MOVEMENT_SPEED 属性（例如展示实体）⇒
            //     `getAttribute` 返回 null ⇒ **什么都不做 + 一行 WARN**（绝不猜一个绝对值填上去）。
            applySpeedMultiplier(entity, attributes.movementSpeed());
        }
        if (attributes.followRange() != null) {
            setAttribute(entity, Attribute.FOLLOW_RANGE, attributes.followRange());
        } else {
            // ★★★ 2026-10-18：**默认索敌/追击半径 64 格**（用户原话：
            //   「**需要把所有怪物的默认索敌范围调整到 64 格，否则它们可能不会向对方防御塔移动**」）。
            //
            //   根因（已查证）：`CombatDirector` 选目标**没有半径**（`docs/02` R34~R38 只给"选最近的"），
            //   但**移动**受原版 `Attribute.FOLLOW_RANGE` 限制 —— 僵尸约 35、骷髅约 16。
            //   目标在 40 格外时 `moveTo` 直接放弃 ⇒ 召唤物站着不动、永远走不到对面塔。
            //
            //   ★ 卡里写了 `follow-range` ⇒ **用卡的值**（用户裁定「所有召唤物默认 64，卡可覆写」）。
            //   ★ 64 这个数取自用户；它大约等于竞技场对角线的量级，足够覆盖"从自家走到对面塔"。
            setAttribute(entity, Attribute.FOLLOW_RANGE, 64.0);
        }
        if (attributes.scale() != null) {
            setAttribute(entity, Attribute.SCALE, attributes.scale());
        }
        // ★★ 2026-10-17 第十二轮（用户裁定 §A 第 2 条「所有击退抗性都要做。
        //   现在几乎所有单位的击退抗性都是缺失的。」）——
        //   键 knockback-resistance（0.0~1.0），落到 Attribute.KNOCKBACK_RESISTANCE。
        //   ★ 常量名已 javap 实测：26.2 里**没有** GENERIC_ 前缀
        //     （`public static final org.bukkit.attribute.Attribute KNOCKBACK_RESISTANCE;`）。
        //   ★ null = 不覆写（原版实体自带的抗性照旧 —— 劫掠兽 / 铁傀儡本来就有，
        //     写 0 反而会削弱它们 ⇒ 这就是"没写的卡不要填 0"的理由）。
        if (attributes.knockbackResistance() != null) {
            setAttribute(entity, Attribute.KNOCKBACK_RESISTANCE, attributes.knockbackResistance());
        }
    }

    /**
     * ★★★ <b>方块怪的「尺寸档位」</b>（{@code summon.attributes.size}，2026-10-18 新增）。
     *
     * <h2>出处（用户第 3 条，逐字）</h2>
     * 「**无法控制岩浆怪的尺寸：熔炉需要一定是本体最大号 + 召唤最小号、烈焰精灵需要本体
     * 一定是最小号**」。我追问后用户选定「**最大号 = 4、最小号 = 1**」（原版三档 1 / 2 / 4，
     * 血 = 尺寸²）。
     *
     * <h2>为什么走 {@code Slime#setSize} 而不是 {@code Attribute.SCALE}</h2>
     * 用户说的是「本体最大号」——那是原版的**尺寸档位**（改血量 / 碰撞箱 / 方块数），
     * 而 {@code SCALE} 只是外观倍率（烈焰熔炉**两个都要**：size 4 + scale 1.2）。
     *
     * <p>★ 失败方向：实体不是史莱姆/岩浆怪（例如将来有人把这一格写到僵尸上）⇒
     * <b>只告警一次</b>并跳过，绝不让召唤物消失；{@code setSize} 抛异常同理。
     */
    private void applySize(Entity entity, int size) {
        int value = Math.max(1, Math.min(127, size));
        if (value != size) {
            logger.warning("[IC] size = " + size + " 超出原版可接受范围 1~127，按 " + value
                    + " 处理（卡 " + entity.getType() + "）");
        }
        // ★★★ 2026-10-18 修（用户第 2 条：「**烈焰熔炉和熔岩精灵的尺寸依旧是随机的**」）：
        //   根因 = **26.2 里 `MagmaCube` 不再继承 `Slime`**（javap 实测）：
        //     Slime     extends AbstractCubeMob
        //     MagmaCube extends AbstractCubeMob      ← 两者是**并列**接口
        //   旧写法 `instanceof Slime` 对岩浆怪**恒为 false** ⇒ 一直走"跳过"分支
        //   （现场日志原文：「写了 attributes.size，但这一组召唤出来的是 MAGMA_CUBE
        //     ⇒ 跳过尺寸设置」）。
        //   ⇒ 判据改成共同父接口 `AbstractCubeMob`（它才有 getSize/setSize）。
        //   ★ 与隔壁 Maggoteers 的 `MobFactory#ensureCombatSlimeSize` 同一口径：
        //     他们也是「Slime 一支 + AbstractCubeMob 一支」两分支写。
        if (!(entity instanceof org.bukkit.entity.AbstractCubeMob cube)) {
            if (!sizeWarned) {
                sizeWarned = true;
                logger.warning("[IC] 写了 attributes.size，但这一组召唤出来的是 "
                        + entity.getType() + "（不是史莱姆 / 岩浆怪），跳过尺寸设置"
                        + "（只影响「大小」，不影响归属与清场）");
            }
            return;
        }
        try {
            // ★ 这一步会把血量重置成 size² ⇒ 调用方必须保证「先尺寸、后血量」
            //   （Maggoteers 在 setSize 之后也是**重设 MAX_HEALTH**，同一个原因）
            cube.setSize(value);
            if (cube.getSize() != value) {
                logger.warning("[IC] 尺寸设置未生效：期望 " + value + "，实际 " + cube.getSize()
                        + "（卡 " + entity.getType() + "）—— 请报告这一行");
            } else {
                logger.info("[IC] 已设置尺寸档位 size=" + value + "：" + entity.getType());
            }
        } catch (Throwable t) {
            logger.warning("[IC] 设置尺寸失败（" + entity.getType() + " size=" + value + "）：" + t
                    + "，只跳过尺寸（它会保持原版随机档位）");
        }
    }

    /**
     * ★★★ <b>彻底禁止「主世界僵尸化」</b>（2026-10-18 新增；用户第 1 条，逐字：
     * 「**猪灵确实不追我了，但它们的武器一段时间后会消失（可能是由于颤抖的原因），
     * 请参照 IG 的代码彻底禁止掉猪灵的转化**」）。
     *
     * <h2>为什么现在这道「取消转化事件」不够（IG 踩过，注释就写着）</h2>
     * 我们原先在 {@code UnitLifecycleListener} 里取消 {@code EntityTransformEvent}
     * （现场日志：{@code 已拦截本插件实体的形态转化：PIGLIN_BRUTE → PIGLIN_ZOMBIFIED}）。
     * 但那个事件在<b>转化流程走完时</b>才触发 ⇒ <b>倒计时不会停</b>：
     * 「颤抖（shivering）」照旧播放，而武器在这一整套流程里被丢掉
     * —— 这正是用户看到的「武器一段时间后会消失」。
     *
     * <h2>真正的开关（IG 源码的同一条，`javap` 实测 26.2 存在）</h2>
     * {@code org.bukkit.entity.PiglinAbstract#setImmuneToZombification(boolean)} 与
     * {@code org.bukkit.entity.Hoglin#setImmuneToZombification(boolean)}
     * —— 这是原版自己用的那个标志（{@code isConverting() = !isImmuneToZombification() && …}）⇒
     * 设成 true 之后<b>连颤抖都不会开始</b>。
     *
     * <p>★ 失败方向：不是猪灵/疣猪兽、或设置抛异常 ⇒ <b>什么都不做</b>（转化事件那道兜底还在），
     * 绝不因此让召唤物消失。
     */
    private void preventZombification(Entity entity, Card card, Side side) {
        try {
            String what = null;
            if (entity instanceof org.bukkit.entity.PiglinAbstract piglin) {
                piglin.setImmuneToZombification(true);
                what = "isImmuneToZombification=" + piglin.isImmuneToZombification()
                        + " / isConverting=" + piglin.isConverting();
            } else if (entity instanceof org.bukkit.entity.Hoglin hoglin) {
                hoglin.setImmuneToZombification(true);
                what = "isImmuneToZombification=" + hoglin.isImmuneToZombification()
                        + " / isConverting=" + hoglin.isConverting();
            }
            if (what == null) {
                return;   // 绝大多数卡：不碰
            }
            logger.info("[IC] 已禁止主世界僵尸化：" + entity.getType() + "（卡 " + card.id()
                    + "，阵营 " + side + "），" + what
                    + "｜依据 = 用户 2026-10-18 第 1 条 ＋ IG 的同一做法"
                    + "（PiglinAbstract#setImmuneToZombification：拦终点停不下倒计时，"
                    + "而颤抖/转化会把武器丢掉）");
        } catch (Throwable t) {
            logger.warning("[IC] 禁止僵尸化失败（卡 " + card.id() + "）：" + t
                    + "，退回「取消转化事件」那道兜底（仍可能颤抖并掉武器）");
        }
    }

    /** 「不是方块怪却写了 size」只告警一次（一张卡里可能出现两次同款）。 */
    private boolean sizeWarned;

    /**
     * ★★ <b>把 {@code movement-speed} 当**倍率**用</b>（用户 2026-10-17 裁定：「是倍率」）。
     *
     * <h2>为什么不能直接 setBaseValue（旧实现的缺陷）</h2>
     * 卡表里的写法全是**相对**的（`×200%` / 「+20%」），而 `setBaseValue(2.0)` 是**绝对**语义：
     * 原版僵尸的 `MOVEMENT_SPEED` 基础值 ≈ **0.23**、玩家 ≈ **0.1**
     * ⇒ 直接写 2.0 会造出一只**比玩家快 20 倍**的怪。那种缺陷在游戏里表现为
     * 「这张卡怎么这么离谱」，而**离线单测全绿**（因为断言只看了"值被写进去了"）。
     *
     * <h2>判据（三条，逐条有理由）</h2>
     * <ol>
     *   <li>读 {@code getBaseValue()}（**不是** {@code getValue()}）：后者含药水/装备的临时修饰，
     *       乘上去会叠加两次（本次改动 + 药水），下次再乘还会滚雪球；</li>
     *   <li>结果**不设上限**：卡表给多少就乘多少（本项目一贯口径 —— 数值由用户定，
     *       我们不悄悄夹紧；真要限制应该在卡表里写小一点）；</li>
     *   <li>失败方向：实体**没有**这个属性（展示实体 / 非生物）⇒ 什么都不做 + 一行 WARN，
     *       <b>绝不</b>退化成"当成绝对值填上去"。</li>
     * </ol>
     *
     * @param multiplier 倍率（1.0 = 不变）；★ 非正数**在装载期就被拒**（见三处解析的校验）
     */
    private void applySpeedMultiplier(Entity entity, double multiplier) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        AttributeInstance instance = living.getAttribute(Attribute.MOVEMENT_SPEED);
        if (instance == null) {
            logger.warning("[IC] 召唤物 " + entity.getType()
                    + " 没有 MOVEMENT_SPEED 属性，跳过「速度 ×" + multiplier + "」"
                    + "（绝不退化成「把这个数当绝对值填上去」）");
            return;
        }
        // ★ 2026-10-21：这里原先有一行「速度倍率已应用：X ×0.5 → 0.25（原 0.5）」的排查用日志。
        //   用户实机确认减速生效后**已按用户要求删掉**（它只是用来回答"attribute 有没有接线"）。
        //   ★ 失败告警（拿不到 MOVEMENT_SPEED）**保留** —— 那是真异常，不是排查日志。
        instance.setBaseValue(scaledSpeed(instance.getBaseValue(), multiplier));
    }

    /**
     * ★★ <b>倍率换算的唯一实现</b>（纯函数、可离线断言）。
     *
     * <p>单列出来的理由与 `AbilityRuntime#periodicCooldownSufficient` 完全一样：
     * 这才是**唯一会写错的地方**（除法的方向 / 该乘还是该加），而它藏在
     * `applySpeedMultiplier` 里就没法离线测（那需要真实的 `AttributeInstance`）。
     *
     * @param base       实体**自己的**基础速度（如原版僵尸 ≈ 0.23）
     * @param multiplier 倍率（1.0 = 不变；1.2 = 快 20%）
     * @return 新的基础速度
     */
    public static double scaledSpeed(double base, double multiplier) {
        return base * multiplier;
    }

    /**
     * ★★ <b>按"原始基础速度 × 倍率"设置移动速度</b>（2026-10-17 批次 3 收尾新增）。
     *
     * <h2>为什么需要它（而不是再调一次 {@link #applySpeedMultiplier}）</h2>
     * {@code applySpeedMultiplier} 读的是实体**当前**的 {@code getBaseValue()} 再乘 ——
     * 那对"召唤时应用一次"是对的，但对<b>会变的倍率</b>（前储君的速度斜坡）就会
     * <b>复利累加</b>：1.0 → ×1.2 = 1.2 → ×1.4 = 1.68 → ×1.6 = 2.69 …（几分钟后爆表）。
     * ⇒ 上坡/清零必须走这一条：<b>记住原始基础值，每次都从它乘起</b>。
     *
     * <p>★ 这正是"加法而不是复利"那条口径（见 {@code policy/MountSpeedRamp} 的 javadoc）
     * 在**执行层**的落点 —— 判据在纯类里，而"别把倍率乘到自己头上"这件事只能在写属性时保证。
     *
     * <h2>★ 记忆表</h2>
     * 键 = 实体 UUID，值 = 这个实体**未经本插件修改**的 {@code MOVEMENT_SPEED} 基础值。
     * 第一次调用时记下来；{@code forgetSpeedBase} 在实体消失/对局收尾时清掉
     * （不记的话表会随对局长大）。
     *
     * @param multiplier 倍率（1.0 = 恢复原速）
     * @return 真的写进去了吗（实体没有该属性 ⇒ {@code false} + 一行 WARN，绝不退化成绝对值）
     */
    public boolean applySpeedScale(Entity entity, double multiplier) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        AttributeInstance instance = living.getAttribute(Attribute.MOVEMENT_SPEED);
        if (instance == null) {
            logger.warning("[IC] 召唤物 " + entity.getType()
                    + " 没有 MOVEMENT_SPEED 属性，跳过「速度 ×" + multiplier + "」"
                    + "（绝不退化成「把这个数当绝对值填上去」）");
            return false;
        }
        double base = speedBaseOf(living, instance);
        instance.setBaseValue(scaledSpeed(base, multiplier));
        return true;
    }

    /** 记住（或取回）某个实体的**原始**移动速度基础值。 */
    private double speedBaseOf(LivingEntity living, AttributeInstance instance) {
        Double remembered = speedBases.get(living.getUniqueId());
        if (remembered != null) {
            return remembered;
        }
        double base = instance.getBaseValue();
        speedBases.put(living.getUniqueId(), base);
        return base;
    }

    /** 实体消失 / 对局收尾时清掉它的原始速度记忆（**幂等**）。 */
    public void forgetSpeedBase(UUID entity) {
        speedBases.remove(entity);
    }

    /** 当前记着多少个实体的原始速度（测试 / 探针用）。 */
    public int speedBaseCount() {
        return speedBases.size();
    }

    private void setAttribute(Entity entity, Attribute attribute, double value) {
        if (!(entity instanceof LivingEntity living)) {
            return;   // 非生物实体没有属性（例如展示实体）
        }
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }
}
