package io.mczju.infernalclash.match;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.policy.ReviveRule;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ★★★ <b>「本插件接管的实体死了」的唯一阳性信号</b>
 * —— 2026-10-17 第十轮新增，缺陷 P0-c（「对局结束后防御塔等东西没有被移除」＋
 * 「莫名奇妙胜利了，即使双方都没有防御塔死亡」）的判据来源。
 *
 * <h2>为什么必须有它（现场证据）</h2>
 * 判「目标物/召唤物还在不在」时，第十轮之前的写法是
 * {@code carrier.isValid()}（{@code ObjectiveUnit#isAlive}）与
 * {@code Bukkit.getEntity(uuid) == null}（{@code UnitRegistry#cleanup}）。
 * 两者都把<b>「区块没加载」</b>当成「已经没了」（Bukkit 在实体随区块卸载时会把引用标成
 * invalid，{@code getEntity} 也查不到）—— 于是同一场对局里同时出现：
 * <pre>
 *   logs/latest.log 18:21:41  [IC] 目标物已回收 3 个（结束）            ← 生成的 6 个里少了 3 个
 *   logs/latest.log 18:21:41  [IC] 清场完成：已删除 0 / 登记 11（已失效 11） ← 一个都没删掉
 *   logs/latest.log 18:22:33  …红方防御塔·左 suffocated in a wall        ← 上一局的塔还立在那儿
 *   logs/latest.log 18:24:43  [IC] 对局结束：… BLUE_WINS                ← 双方都没掉塔却分了胜负
 * </pre>
 * ⇒ 「死了」这件事必须有**阳性证据**：这一条 {@code EntityDeathEvent}。
 *
 * <h2>★★ 为什么只打标、不立刻注销账本</h2>
 * {@code ability/AbilityTriggerListener} 的 <b>亡语（{@code ON_DEATH}）</b>能力在同一个事件上
 * 判断「这个实体是不是我们的」（它查的是账本）。若这里当场 {@code forget}，
 * 亡语就会随监听器注册顺序时有时无（最糟的那类"偶发"）。
 * ⇒ 只调 {@link UnitRegistry#markDead(java.util.UUID)}；账本条目留到下一次
 * {@code UnitRegistry#cleanup} 再落袋（那时它有阳性证据，不需要"等区块加载"）。
 *
 * <h2>★ 范围（铁律 10 的第三问）</h2>
 * 只对<b>账本里登记过的</b>实体打标（{@link UnitRegistry#markDead} 内部还会再核一次）
 * —— 服主的动物、别的插件的怪**一律不碰**。归属判定靠账本，**不靠实体类型**。
 *
 * <p>日志刻意只走 {@code FINE}：一场对局会死掉上百个召唤物，
 * 每个都打 INFO 会把真正的告警淹掉（{@code AGENTS.md} 记着同样的教训）。
 *
 * <h2>★★ 2026-10-18 新增：死亡时复活一次（美西螈）</h2>
 * 判据在纯类 {@link ReviveRule}（读卡数据：{@code ON_DEATH} + {@code REVIVE} 效果）。
 * 命中时：<b>取消这次死亡</b>（{@code EntityDeathEvent#setCancelled}）＋
 * {@code setReviveHealth(满血)}（用户 2026-10-17 裁定「美西螈复活应该满血」），
 * 并**不**打"已死亡"标记（它没死）。次数上限 = 1 由 {@link #revivedOnce} 保证。
 *
 * <p>★ 为什么放在这里而不是 {@code AbilityRuntime}：能力运行时的
 * {@code AppliedEffects} 是**每次效果新构造**的（复活标记会当场丢掉），
 * 而死亡事件里能直接读到<b>卡数据</b>——那才是唯一事实来源。
 */
public final class UnitLifecycleListener implements Listener {

    private final UnitRegistry registry;
    private final CardCatalog catalog;
    private final Logger logger;

    /** 已经复活过一次的实体（上限 1；第二次真死时移出）。 */
    private final Set<UUID> revivedOnce = ConcurrentHashMap.newKeySet();

    public UnitLifecycleListener(UnitRegistry registry, CardCatalog catalog, Logger logger) {
        this.registry = registry;
        this.catalog = catalog;
        this.logger = logger;
    }

    /**
     * ★ 只记账、不取消（★ {@code MONITOR} 优先级 = 在别人的处理之后跑，
     * 保证亡语能力已经看过账本了）。
     *
     * <p>★ 唯一的例外是「复活一次」（见类注释）：那一条会<b>取消</b>本次死亡。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity == null) {
            return;
        }
        UUID id = entity.getUniqueId();
        if (!registry.isTracked(id)) {
            return;   // 不是我们接管的实体 —— 绝不干预、也绝不记账
        }
        if (tryRevive(entity, event)) {
            return;   // 它没死 ⇒ 不打"已死亡"标记
        }
        revivedOnce.remove(id);
        registry.markDead(id);
        if (logger != null) {
            logger.fine("[IC] 本插件实体死亡（已打阳性标记，下一次清场直接落袋）："
                    + entity.getType() + "｜uuid=" + id);
        }
    }

    /** 死亡拦截：这张卡有「死亡时复活」且还没用过 ⇒ 取消死亡并回血。返回是否真的复活了。 */
    private boolean tryRevive(Entity entity, EntityDeathEvent event) {
        UUID id = entity.getUniqueId();
        if (revivedOnce.contains(id)) {
            return false;
        }
        Card card = cardOf(entity);
        if (!ReviveRule.revivesOnDeath(card)) {
            return false;
        }
        double maxHealth = 20.0;
        if (entity instanceof LivingEntity living) {
            AttributeInstance attribute = living.getAttribute(Attribute.MAX_HEALTH);
            if (attribute != null) {
                maxHealth = attribute.getValue();
            }
        }
        revivedOnce.add(id);
        event.setCancelled(true);
        event.setReviveHealth(ReviveRule.reviveHealth(maxHealth));
        if (logger != null) {
            logger.info("[IC] 假死复活：" + (card == null ? "?" : card.id()) + " 回满 "
                    + String.format("%.1f", ReviveRule.reviveHealth(maxHealth))
                    + " 血（上限 1 次，uuid=" + id + "）");
        }
        return true;
    }

    /** 从账本 PDC 取卡 id，再从卡池取卡；任一缺失都返回 null（不猜）。 */
    private Card cardOf(Entity entity) {
        if (catalog == null) {
            return null;
        }
        String cardId = registry.cardIdOf(entity);
        return cardId == null ? null : catalog.cards().get(cardId);
    }
}
