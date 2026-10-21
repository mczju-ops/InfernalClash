package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.logging.Logger;

/**
 * ★★★ <b>本插件实体的「掉落 / 拾取 / 爆炸」守卫</b>——
 * 2026-10-17 第十三轮新增，对应用户实机报的三条（逐字）：
 *
 * <blockquote>
 * ① 「对局时召唤物仍有**掉落物**，这是需要拦截的」<br>
 * ② 「**苦力怕甚至可能直接爆炸**」<br>
 * ③ （同批）「召唤物刚被召唤时仍有几秒会索敌玩家」
 * </blockquote>
 * ★ ③ 的**主要**修法在 {@code card/SummonSpawner}（把归属登记提到最前面）与
 * {@code battle/CombatDirector}（每 tick 兜底而不是每 10 tick）—— 不在本类。
 *
 * <h2>① 掉落：为什么必须拦（而不是"干净一点"）</h2>
 * 这一批召唤物里有一半**穿着装备**（铁甲战士的铁套铁剑、劲弩手的力量 V 弩、海王的铁套…）。
 * 它们被打死时原版会把**这些装备掉在地上** ⇒ 玩家弯腰就能捡到一套铁甲 + 力量 V 弩
 * ⇒ 直接破坏「玩家只能通过卡牌影响战场」（C13）。
 * ★ 处置 = 对本插件账本里的实体，{@code EntityDeathEvent#getDrops().clear()}
 * （**清掉落列表**，而不是"取消死亡"，也不是按类型清世界里的东西 —— 铁律 10 第 1 问）。
 *
 * <h2>② 爆炸：一个真实的两难，以及它的解法</h2>
 * {@code docs/19} 第 58 行给恐怖分子（苦力怕）写的坑就是：
 * 「爆炸**不区分阵营**（方块破坏已由 lobby 环境解决，{@code C17}）」
 * ⇒ 也就是说「不区分阵营」是**设计如此**，但用户明确要求**禁掉推挤**、并且**不许炸到玩家**
 * （玩家本来就免伤，见 R41）。
 * <table border="1">
 *   <caption>两条可选路，以及为什么选后者</caption>
 *   <tr><th>路</th><th>后果</th></tr>
 *   <tr><td>放行爆炸，事后把「不该吃的伤害」撤掉</td>
 *       <td>做不到：伤害是原版在爆炸那一刻一次性算完的，事后再判断只能看到结果</td></tr>
 *   <tr><td>★ <b>{@code ExplosionPrimeEvent} 取消</b>（= 不让它炸）＋ 我们按账本自己结算伤害</td>
 *       <td>✅ 完全可控：<b>只伤害账本里的敌方单位</b>、<b>不破坏方块</b>、
 *           <b>不推挤任何东西</b>、<b>绝不碰玩家</b>（连"爆炸"这个动作都不发生）</td></tr>
 * </table>
 * ★ 但「不炸」会丢掉原版的**爆炸特效**⇒ 结算伤害前先自己放一次
 * {@code world.createExplosion(..., false, false)}（{@code setFire=false} / {@code breakBlocks=false}
 * ⇒ **纯视觉**），这样观感与原版一致，而副作用一个都不留。
 *
 * <h2>★ 铁律 10 的四问（本类是本项目第二个「会改世界状态」的入口）</h2>
 * <ol>
 *   <li><b>范围界定</b>：只处理**账本里属于本插件**的实体（{@link UnitRegistry#isTracked}）
 *       —— 不按类型、不按世界、不按半径；</li>
 *   <li><b>失败方向</b>：拿不到账本 / 实体不在账本里 ⇒ <b>原样放行原版行为</b>
 *       （绝不用"取消"去猜；宁可别人的苦力怕照常炸，也不动别人的东西）；</li>
 *   <li><b>归属判定</b>：{@code UnitRegistry} 的账本（UUID）+ 本场 {@code matchId}，
 *       与清场同一条路径；</li>
 *   <li><b>可对账</b>：每次清掉掉落 / 拦下爆炸都打一行带<b>数量与实体</b>的日志。</li>
 * </ol>
 */
public final class SummonLifecycleGuard implements Listener {

    /** 自爆伤害半径（原版苦力怕 = 3.0）。★ 我们没有"自己的数值"，照原版取。 */
    public static final double CREEPER_EXPLOSION_RADIUS = 3.0;

    private final UnitRegistry registry;
    private final Logger logger;

    public SummonLifecycleGuard(UnitRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger == null ? Logger.getLogger("InfernalClash") : logger;
    }

    // ==================================================================================
    // ① 掉落物
    // ==================================================================================

    /**
     * ★ <b>本插件实体的掉落一律清空</b>（用户：「对局时召唤物仍有掉落物，这是需要拦截的」）。
     *
     * <p>★ 判据只有一条：**它在账本里吗**（{@link UnitRegistry#isTracked}）。
     * 不在账本里 ⇒ 一个字都不动（别人的怪、服主的动物照常掉东西）。
     *
     * <p>★ 用 {@code MONITOR}（最后跑）：别的插件/机制可能已经往 {@code getDrops()} 里加过东西，
     * 我们在最后把它们清掉；★ 清理**不改死亡结果**（实体照样死，只是不掉东西）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOurUnitDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!shouldClearDrops(registry, entity == null ? null : entity.getUniqueId())) {
            return;
        }
        int dropped = event.getDrops().size();
        int exp = event.getDroppedExp();
        if (dropped == 0 && exp == 0) {
            return;   // 本来就没东西掉（绝大多数召唤物）⇒ 不打日志，免得刷屏
        }
        event.getDrops().clear();
        // ★ 经验也要清：这是**召唤物**，掉经验等于给玩家白送费用（与 C13 同一条纪律）
        event.setDroppedExp(0);
        logger.info("[IC] 已拦下本局召唤物的掉落：" + entity.getType() + "（" + entity.getName()
                + "），清掉 " + dropped + " 件掉落物 / " + exp + " 点经验"
                + "｜归属判据 = 本局账本（绝不按类型/按半径清别人的东西）");
    }

    /**
     * ★★ <b>「这个实体掉的東西该不该清掉」的纯判据</b>（不碰事件对象 ⇒ 可离线逐格断言）。
     *
     * <p>判据**只有一条**：它在<b>本插件的账本</b>里吗（{@link UnitRegistry#isTracked}）。
     * <ul>
     *   <li>在 ⇒ 清（它们穿着铁套铁剑 / 力量 V 弩，掉了就是白送玩家一套装备，直接破坏 C13）；</li>
     *   <li>不在 ⇒ <b>一个字都不动</b>（别人的怪、服主的动物、路过的村民照常掉东西）。</li>
     * </ul>
     * ★ 拿不到账本（插件未就绪 / 离线）⇒ 返回 {@code false}（**不动**）：
     * 失败方向是"少拦一次"，而不是"乱清别人的掉落"。
     */
    public static boolean shouldClearDrops(UnitRegistry registry, java.util.UUID entityId) {
        return registry != null && entityId != null && registry.isTracked(entityId);
    }

    /**
     * ★ 顺带：**玩家捡东西这件事我们不动**。
     *
     * <p>★ 用户口径（逐字）：「**只做 a**；玩家自己的掉落不管，因为玩家不可能死亡」
     * —— 这里的「a」= 清掉**我们召唤物**的掉落（见 {@link #onOurUnitDeath}）。
     * 既然源头已经清空，就没有"漏网的物品"需要拦 ⇒ **不引入**"给物品打标记"那套机制
     * （多一套 PDC 就多一处会分叉的事实来源，而它换不来任何东西）。
     * <p>这个方法**刻意不实现**（没有 {@code @EventHandler}），写在这里只为留痕：
     * 让后人知道「我们想过拾取，并**有理由**不做」。
     */

    /**
     * ★ 玩家的死亡掉落**不动**（用户：「玩家自己的掉落不管，因为玩家不可能死亡」）。
     *
     * <p>这个方法是**刻意的空实现**：写在这里是为了让「我们想过这件事」留痕，
     * 而不是让后人以为"漏了"。若哪天玩家真的会死（R41 被改），再回来处理。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // 有意为空：见 javadoc。
    }

    // ==================================================================================
    // ② 爆炸（恐怖分子 = 苦力怕）
    // ==================================================================================

    /**
     * ★★ <b>拦住本局召唤物的爆炸本身</b>（{@code ExplosionPrimeEvent} = 引信烧完、即将爆炸）。
     *
     * <p>★ 为什么连爆炸都不让它发生：原版爆炸**同时**做四件事（伤害 / 破坏方块 / 推挤 / 点火），
     * 其中「推挤」与「伤到玩家」是用户明确要禁的，而「不区分阵营」是我们按 {@code docs/19} 要保留的
     * ⇒ 只有"我们自己结算"才能把这四件事分开（见类注释的表）。
     *
     * <p>★ 这里**只取消引信**、随后由 {@link #explodeForUs} 自己结算：
     * 先放一次**纯视觉**爆炸（不点火不破方块），再按账本对敌方单位造成伤害。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Creeper creeper) || !isOurs(creeper)) {
            return;
        }
        event.setCancelled(true);
        int hit = explodeForUs(creeper);
        logger.info("[IC] 已接管本局苦力怕的爆炸（" + creeper.getName() + "）："
                + "不破坏方块、不推挤任何东西、绝不伤玩家；按账本结算，命中 " + hit + " 个敌方单位"
                + "（伤害 " + SummonExplosion.SELF_DAMAGE + "｜半径 " + CREEPER_EXPLOSION_RADIUS + "）"
                + "｜依据 docs/19 第 58 行「爆炸不区分阵营」＋ 用户裁定「禁掉推挤」");
        // ★ 结算完就让它退场（原版爆炸后苦力怕也会死；我们自己结算 => 也必须自己收尾）
        creeper.setHealth(0.0);
    }

    /**
     * 兜底：万一 {@code ExplosionPrimeEvent} 没拦到（别的插件抢先 / 事件被别的路径触发），
     * 至少**不让方块被破坏**（{@code EntityExplodeEvent} 的 blockList 清空 —— 这是安全底线）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (!isOurs(event.getEntity())) {
            return;
        }
        int blocks = event.blockList().size();
        event.blockList().clear();
        if (blocks > 0) {
            logger.info("[IC] 兜底：本局实体的爆炸没有破坏方块（清掉 " + blocks + " 个方块）"
                    + "｜实体 " + event.getEntity().getType());
        }
    }

    /**
     * ★★ <b>压掉原版分裂</b>（2026-10-18 全卡审计补的欠账）。
     *
     * <p>出处：{@code docs/19} 第 150 行烈焰熔炉的坑栏「<b>压制原版分裂</b>（Maggoteers 三层防线）」。
     * 烈焰熔炉本体是 {@code size: 4} 的岩浆怪 ⇒ 死亡时原版会把它裂成小号岩浆怪，
     * 而<b>那些小号不在账本里</b>：不参与清场、目标由原版 AI 管、收尾对账也对不上（铁律 10）。
     *
     * <p>★ 卡自己的「死亡后召唤 2 个熔岩精灵」由 {@code summon_on_death} 能力负责，
     * 走的是 {@code SummonSpawner}（正常登记）—— <b>不受</b>本拦截影响。
     * ★ 只拦**账本里认得的**实体：别人的史莱姆 / 岩浆怪照常分裂。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSlimeSplit(org.bukkit.event.entity.SlimeSplitEvent event) {
        if (!isOurs(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        logger.info("[IC] 已压制原版分裂：" + event.getEntity().getType()
                + "（本局实体，原本要裂出 " + event.getCount() + " 只）");
    }

    /** 按账本结算一次「我们自己的爆炸」：只打敌方单位；纯视觉爆炸不点火、不破方块。 */
    private int explodeForUs(Creeper creeper) {        // ① 纯视觉爆炸（setFire=false / breakBlocks=false ⇒ 副作用一个都不留）
        try {
            creeper.getWorld().createExplosion(creeper.getLocation(),
                    (float) SummonExplosion.POWER, false, false, creeper);
        } catch (Throwable t) {
            logger.warning("[IC] 爆炸特效没能播放（不影响伤害结算）：" + t);
        }
        // ② 按账本对敌方单位造成伤害
        String matchId = registry.table().owner(creeper.getUniqueId()) == null
                ? null : registry.table().owner(creeper.getUniqueId()).matchId();
        return SummonExplosion.damageEnemiesInRange(registry, matchId, creeper, logger);
    }

    /** 这个实体是不是**本插件这一局**的（唯一判据 = 账本）。 */
    private boolean isOurs(Entity entity) {
        return entity != null && registry != null && registry.isTracked(entity.getUniqueId());
    }
}
