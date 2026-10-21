package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.RangedAttackRule;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ★★ <b>远程攻击的两条拦截</b> —— 2026-10-17 第十三轮批次 3 收尾新增。
 *
 * <h2>① 冰术师「手持弓但不射箭」（用户原话，逐字）</h2>
 * 「<b>不需要摘掉射箭 goal，只需要拦截射箭事件就行。需要它射箭时的走位</b>」
 * <ul>
 *   <li><b>拦什么</b>：{@code EntityShootBowEvent} ⇒
 *       {@code setCancelled(true)} ⇒ <b>箭根本不生成</b>（伤害为 0）；</li>
 *   <li><b>不拦什么</b>：<b>不摘原版射箭 goal</b> —— 摘了它连"保持距离"的走位都没了，
 *       而那正是用户点名要保留的东西（{@code docs/18} §5 第 12 项当年写的是"摘掉 goal"，
 *       <b>已被这一条取代</b>）；</li>
 *   <li><b>只对本场账本里的单位生效</b>（铁律 10）：别人的骷髅 / 服主的怪照常射箭；</li>
 *   <li>判据在纯类 {@link RangedAttackRule#blocksRangedAttack} 里（有逐条单测）。</li>
 * </ul>
 *
 * <h2>② 女巫的溅射「对敌人有效、对友方和玩家无效」（用户原话，逐字）</h2>
 * 原版女巫的溅射药水<b>不区分阵营</b>（{@code docs/19} 第 149 行把它列为坑）。
 * 用户裁定：「<b>每次投掷会对敌人造成影响，而对友方和玩家无效</b>」⇒
 * <ol>
 *   <li>范围内<b>一个敌人都没有</b> ⇒ <b>整瓶作废</b>（{@code setCancelled(true)}）
 *       —— 女巫站在自家前排时原版会往最近的生物扔，那多半是队友，纯浪费；</li>
 *   <li>有敌人 ⇒ 把<b>友方与玩家</b>的 {@code intensity} 清零。
 *       ★ 这是**唯一**的官方口径：{@code PotionSplashEvent}（`javap` 实测 26.2）
 *       只有 {@code getAffectedEntities / getIntensity / setIntensity / setCancelled}，
 *       <b>没有</b>"移出列表"的方法，而 intensity = 0 的语义正是"这一位不受影响"。</li>
 * </ol>
 *
 * <h2>★ 判据用的「敌 / 友」是谁的阵营</h2>
 * 拿**药水的主人**（{@code projectile.getShooter()}）的阵营当基准 ——
 * 这是「谁扔的，就不许伤到谁的人」的直接读法，也是女主人的意图。
 * ★ 拿不到主人的阵营 ⇒ <b>什么都不做</b>（{@code ALLOW}）：
 * 宁可让原版照旧，也不去猜一个阵营（猜错就是在替玩家改玩法）。
 *
 * <h2>★ 铁律 10 的四问</h2>
 * <ol>
 *   <li><b>范围界定</b>：只处理 {@link UnitRegistry} 账本里、且<b>属于本场</b>的实体；</li>
 *   <li><b>失败方向</b>：任何一步拿不到（拿不到卡池 / 拿不到主人 / 拿不到阵营）
 *       ⇒ <b>不干预</b>，绝不退化成"取消一切远程攻击"；</li>
 *   <li><b>归属判定</b>：走 {@link UnitRegistry#ownerFromPdc} / {@link OwnershipTable}，
 *       <b>不看类型</b>；</li>
 *   <li><b>可对账</b>：每次真的拦了都留一行日志（含数量），便于实机核对。</li>
 * </ol>
 */
public final class RangedAttackInterceptor implements Listener {

    private final UnitRegistry registry;
    private final Logger logger;

    /** 卡 id → {@code summon.block-ranged}（**只读快照**，每次事件现查卡池）。 */
    private final CardCatalog catalog;

    public RangedAttackInterceptor(UnitRegistry registry, CardCatalog catalog, Logger logger) {
        this.registry = registry;
        this.catalog = catalog;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ ① 射箭

    /**
     * 拉弓完成、**箭即将生成**的那一刻。
     *
     * <p>★ 优先级刻意用 {@code HIGHEST}：要在别的插件（与 MGC）看到它之前就决定掉，
     * 免得出现"别人已经按箭打了一轮"的顺序问题。
     * ★ {@code ignoreCancelled = false}（默认）：**已经被取消的也要看一眼**吗？不需要 ——
     * 我们只会<b>加</b>一个取消，所以别人先取消掉也无所谓。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShootBow(EntityShootBowEvent event) {
        try {
            Entity shooter = event.getEntity();
            if (!shouldBlockRanged(shooter)) {
                return;
            }
            event.setCancelled(true);
            logger.info("[IC] 拦下远程攻击（" + shooter.getType()
                    + " 按卡里的 block-ranged 不射箭；保留它的走位 —— 用户裁定"
                    + "「不需要摘掉射箭 goal，只需要拦截射箭事件」）");
        } catch (Throwable t) {
            // ★ 事件回调里抛异常会带走整条监听器链 ⇒ 只告警
            logger.warning("[IC] 拦截射箭时出错（本次放行）：" + t);
        }
    }

    /**
     * ★★★ <b>「弹射物真的被生成了」那一刻的**第二道**拦截</b>（2026-10-21 用户实机报的缺陷）。
     *
     * <h2>用户原话（逐字）</h2>
     * 「**并且它们的发射/丢三叉戟仍未被拦截**」——
     * 也就是说第一道（{@link #onShootBow} 的 {@code EntityShootBowEvent}）**没拦住**：
     * 劫掠者的弩与溺尸的三叉戟**不经过**那条事件（与恶魂吐火球同理）。
     *
     * <h2>为什么这条能兜住</h2>
     * {@code ProjectileLaunchEvent} 是**任何弹射物真正生成**时都会发的事件
     * （箭 / 弩弹 / 三叉戟 / 雪球 …）⇒ 用**同一套判据**（{@link #shouldBlockRanged}：
     * `block-ranged: true` 或卡里有适用的 {@code summon.ray}）取消它。
     * ★ 判据只有一份 ⇒ 不会出现"拦了箭但没拦三叉戟"这种分叉。
     *
     * <p>★ 去重告警：这两个事件对同一只单位可能**同时**触发 ⇒ 用 {@code warned} 集合，
     * 每只单位只打一行（否则刷屏）。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileLaunch(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        try {
            if (event.getEntity() == null
                    || !(event.getEntity().getShooter() instanceof Entity shooter)) {
                return;
            }
            if (!shouldBlockRanged(shooter)) {
                return;
            }
            event.setCancelled(true);
            if (reported.add(shooter.getUniqueId())) {
                logger.info("[IC] 拦下弹射物（" + shooter.getType() + " 的 " + event.getEntityType()
                        + "）—— 这只单位按卡配置不该发射任何东西"
                        + "（block-ranged: true 或 summon.ray；判据见 RangedAttackInterceptor）");
            }
        } catch (Throwable t) {
            logger.warning("[IC] 拦截弹射物时出错（本次放行）：" + t);
        }
    }

    /** 已打过「拦下弹射物」日志的单位（每只单位一次，防刷屏）。 */
    private final java.util.Set<java.util.UUID> reported = new java.util.HashSet<>();

    /** 这个实体是不是「按卡配置不许射箭」的本场单位。 */
    private boolean shouldBlockRanged(Entity entity) {
        if (!(entity instanceof LivingEntity) || registry == null) {
            return false;
        }
        OwnershipTable.Owner owner = registry.ownerFromPdc(entity);
        if (owner == null || owner.player() == null) {
            return false;   // 不是本场召唤物（含目标物 —— 它们 player 为 null）
        }
        String cardId = registry.cardIdOf(entity);
        if (cardId == null) {
            return false;
        }
        // ★ 现查卡池（而不是把布尔写进 PDC）：卡是数据，热重载后以**卡池**为准
        Card card = catalog == null ? null : catalog.cards().get(cardId);
        if (card == null || card.summon() == null) {
            return false;
        }
        // ★★★ 2026-10-21 用户裁定（逐字）：「劲弩手…**拦截射击行为**（不需要给弩附魔了）」
        //   ⇒ 凡是被 `summon.ray` 打上射线的单位，**它的箭/弩弹一律不让生成**
        //   （否则会出现"箭被拦掉但射线照样打"之外的第二种表现：箭也飞出去、双份伤害）。
        //   ★ 判据与射线执行层**同一个**（`Card.Ray#appliesTo`）⇒ 不会出现
        //     "拦了但射线不生效"或"射线生效但没拦"的分叉。
        Card.Ray ray = card.summon().ray();
        if (ray != null && ray.appliesTo(entity.getType().name())) {
            return true;
        }
        return RangedAttackRule.blocksRangedAttack(true, cardId,
                Map.of(cardId, card.summon().blockRanged()));
    }

    // ------------------------------------------------------------------ ② 溅射

    /**
     * 溅射药水落地、**效果尚未施加**的那一刻。
     *
     * <p>★ 见类 javadoc 的两条规则（没有敌人 ⇒ 整瓶作废；有敌人 ⇒ 只清友方与玩家）。
     * ★ 优先级 {@code HIGHEST}：要赶在其它监听器读 intensity 之前改完。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPotionSplash(PotionSplashEvent event) {
        try {
            Side shooterSide = sideOfShooter(event);
            String matchId = matchIdOfShooter(event);
            if (shooterSide == null || matchId == null) {
                return;   // ★ 拿不到主人 / 阵营 ⇒ 不干预（绝不猜）
            }
            List<LivingEntity> affected = new ArrayList<>(event.getAffectedEntities());
            int enemies = 0;
            for (LivingEntity target : affected) {
                if (isEnemyOf(target, matchId, shooterSide)) {
                    enemies++;
                }
            }
            RangedAttackRule.SplashDecision decision =
                    RangedAttackRule.splashDecision(affected.size(), enemies);
            if (decision == RangedAttackRule.SplashDecision.ALLOW) {
                return;
            }
            if (decision == RangedAttackRule.SplashDecision.CANCEL_NO_ENEMY) {
                event.setCancelled(true);
                logger.info("[IC] 溅射药水作废：范围内 " + affected.size()
                        + " 个受影响者里一个敌人都没有（用户裁定「对友方和玩家无效」"
                        + "，绝不浪费在队友身上）｜matchId=" + matchId);
                return;
            }
            int cleared = 0;
            for (LivingEntity target : affected) {
                if (isEnemyOf(target, matchId, shooterSide)) {
                    continue;
                }
                event.setIntensity(target, 0.0);
                cleared++;
            }
            logger.info("[IC] 溅射药水只作用于敌人：清掉 " + cleared + " 个友方/玩家的强度"
                    + "（打到 " + enemies + " 个敌人）｜matchId=" + matchId);
        } catch (Throwable t) {
            logger.warning("[IC] 拦截溅射药水时出错（本次放行）：" + t);
        }
    }

    /** 药水的主人（不是本场单位 / 拿不到 ⇒ {@code null}）。 */
    private Entity shooterOf(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Projectile projectile)) {
            return null;
        }
        return projectile.getShooter() instanceof Entity shooter ? shooter : null;
    }

    /** 药水主人的阵营（拿不到 ⇒ {@code null} ⇒ 本监听器不干预）。 */
    private Side sideOfShooter(ProjectileHitEvent event) {
        Entity shooter = shooterOf(event);
        if (shooter == null || registry == null) {
            return null;
        }
        OwnershipTable.Owner owner = registry.ownerFromPdc(shooter);
        return owner == null ? null : owner.side();
    }

    /** 药水主人在哪一场（拿不到 ⇒ {@code null}）。 */
    private String matchIdOfShooter(ProjectileHitEvent event) {
        Entity shooter = shooterOf(event);
        if (shooter == null || registry == null) {
            return null;
        }
        OwnershipTable.Owner owner = registry.ownerFromPdc(shooter);
        return owner == null ? null : owner.matchId();
    }

    /**
     * 这个受影响者是不是「主人那一方的敌人」。
     *
     * <p>口径（★ 与 {@code policy/SideRule} 同一套）：<b>只有两侧都已知且不同才算敌人</b>。
     * ★ 玩家这里<b>不算敌人</b>（本项目的 R42：玩家不参与召唤物之间的战斗；
     * 而且用户对掉落物的口径也是「玩家不可能死亡」）⇒ 玩家一律走"清强度"那一支。
     */
    private boolean isEnemyOf(LivingEntity target, String matchId, Side shooterSide) {
        if (target instanceof Player) {
            return false;
        }
        if (registry == null || target == null) {
            return false;
        }
        OwnershipTable.Owner owner = registry.table().owner(target.getUniqueId());
        if (owner == null || !matchId.equals(owner.matchId())) {
            return false;   // 不在本场账本里 ⇒ 不是我们能判定的敌人 ⇒ 按"非敌人"处理（会被清强度）
        }
        return owner.side() != null && owner.side() != shooterSide;
    }

    /** 一行摘要（启动日志用）。 */
    public static String describe() {
        return "远程攻击拦截：① block-ranged 的卡不射箭（保留走位）；"
                + "② 女巫溅射只作用于敌人（无敌人则整瓶作废）";
    }
}
