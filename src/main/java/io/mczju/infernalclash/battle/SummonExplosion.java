package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * ★★ <b>「我们自己的爆炸」怎么结算</b>（纯判据 + 一个薄执行）——
 * 2026-10-17 第十三轮，配合 {@link SummonLifecycleGuard} 处理用户报的
 * 「苦力怕甚至可能直接爆炸」。
 *
 * <h2>为什么需要它（原版爆炸做不到我们想要的三件事）</h2>
 * 原版爆炸是**一整套动作**：伤害 ＋ 破坏方块 ＋ 推挤（击退）＋ 点火。
 * 而用户的要求把其中两件事**去掉**、把"不区分阵营"**留下**（{@code docs/19} 第 58 行的设计）：
 * <table border="1">
 *   <caption>四件事各自的归属</caption>
 *   <tr><th>动作</th><th>要不要</th><th>依据</th></tr>
 *   <tr><td>对**敌方**单位造成伤害</td><td>✅ 要</td><td>{@code docs/19} #3 恐怖分子（苦力怕）</td></tr>
 *   <tr><td>破坏方块</td><td>❌ 不要</td><td>{@code C17}：方块破坏已由 lobby 环境解决</td></tr>
 *   <tr><td>推挤</td><td>❌ 不要</td><td>★ 用户 2026-10-17 裁定：「**禁掉**」</td></tr>
 *   <tr><td>伤到玩家</td><td>❌ 不要</td><td>R41（玩家无法受到任何伤害）—— 本来就该是 0，但"不让爆炸发生"更保险</td></tr>
 * </table>
 * ⇒ 只有**自己结算**才能把这四件事拆开（原版一旦爆炸，四件事同时发生、事后无法撤回）。
 *
 * <h2>★ 铁律 10：候选只从账本里来</h2>
 * 本类**不查世界**（没有 {@code getNearbyEntities} / {@code getEntities}）——
 * 遍历 {@link UnitRegistry#tracked(String)}（本场登记过的 UUID），逐个解析、按归属与阵营筛。
 * 失败方向：账本拿不到 / 没有本场 ⇒ **一个都不打**并告警（绝不退化成"打周围所有人"）。
 */
public final class SummonExplosion {

    /** 恐怖分子（苦力怕）的爆炸威力 —— ★ 照原版取，不自造数值。 */
    public static final double POWER = 3.0;

    /** 自爆伤害 —— ★ 原版苦力怕贴身伤害约 **49**（普通难度、无甲）；这里取整为 **40**（不易秒掉满血召唤物）。 */
    public static final double SELF_DAMAGE = 40.0;

    private SummonExplosion() {
    }

    /**
     * ★ 按账本对**范围内的敌方单位**造成一次爆炸伤害。
     *
     * <p>判据三条（缺一不可）：
     * <ol>
     *   <li>在**本场**账本里（{@code owner.matchId() == matchId}）—— 否则与我们无关；</li>
     *   <li>与爆源**不同阵营**（友方不掉血 —— 原版"不区分阵营"指的是**不挑**敌方目标，
     *       而不是"连自己人一起炸"；★ 这一格**未实机确认**，用户否决只需改这一行）；</li>
     *   <li>与爆源距离 &lt;= {@link SummonLifecycleGuard#CREEPER_EXPLOSION_RADIUS}。</li>
     * </ol>
     * ★ 目标物（塔 / 信标）**也在账本里** ⇒ 会被炸到；它们的 {@code owner.player() == null}
     * 但 {@code side} 是有的，所以阵营判据照样成立（与光束索敌同一口径）。
     *
     * @return 真的打到了几个单位（对账用）
     */
    public static int damageEnemiesInRange(UnitRegistry registry, String matchId, Entity source,
                                           Logger logger) {
        return damageEnemiesInRange(registry, matchId, source, logger, Bukkit::getEntity);
    }

    /**
     * ★★ <b>可离线测试的重载</b>：把「UUID → 实体」的解析做成参数。
     *
     * <p>为什么需要它：{@link Bukkit#getEntity} 在离线（JUnit）里是没法用的
     * ⇒ 「只打敌方、不打友方 / 不打玩家 / 不打范围外」这三条判据就**没法离线断言**。
     * 而这三条恰恰是最容易写错、也最贵的地方（打错阵营 = 用自己人的苦力怕炸自己人）。
     * ★ 生产路径仍然走 {@link Bukkit#getEntity}（本类的公开入口不变）。
     */
    public static int damageEnemiesInRange(UnitRegistry registry, String matchId, Entity source,
                                           Logger logger,
                                           java.util.function.Function<java.util.UUID, Entity> resolver) {
        if (registry == null || matchId == null || source == null || !source.isValid()) {
            if (logger != null) {
                logger.warning("[IC] 爆炸结算跳过：账本或 matchId 拿不到（按 fail-safe 一个都不打）");
            }
            return 0;
        }
        OwnershipTable.Owner sourceOwner = registry.table().owner(source.getUniqueId());
        if (sourceOwner == null) {
            return 0;   // 爆源不在账本里（调用方本不该走到这里）
        }
        Side sourceSide = sourceOwner.side();
        double radiusSquared = SummonLifecycleGuard.CREEPER_EXPLOSION_RADIUS
                * SummonLifecycleGuard.CREEPER_EXPLOSION_RADIUS;

        int hit = 0;
        for (java.util.UUID id : registry.tracked(matchId)) {
            if (id.equals(source.getUniqueId())) {
                continue;
            }
            Entity entity = resolver.apply(id);
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                continue;
            }
            if (!shouldHit(registry, matchId, id, living, sourceSide, source, radiusSquared)) {
                continue;
            }
            living.damage(SELF_DAMAGE, source);
            hit++;
        }
        return hit;
    }

    /**
     * ★★ <b>「这个单位该不该吃这一次爆炸」的纯判据</b>（不碰 Bukkit，可离线逐格断言）。
     *
     * <p>三条（缺一不可，逐条有理由）：
     * <ol>
     *   <li>它在**本场**账本里（{@code owner != null && owner.matchId().equals(matchId)}）
     *       —— 别人的怪、上一局的遗留物一律不打；</li>
     *   <li>★ 它**不是玩家**：R42「玩家不会成为任何实体的目标」—— 玩家本来也免伤（R41），
     *       但这里是**多一道门**（越靠近伤害的地方越要拦）；</li>
     *   <li>★ 它与爆源**不同阵营**：友方不掉血。★ 这一格**未实机确认**（{@code docs/19} 第 58 行
     *       只写「爆炸**不区分阵营**」，没说"连自己人也炸"）⇒ 我按"只打敌方"实现，
     *       用户若要"连自己人也炸"，改这一行即可。</li>
     * </ol>
     * ★ 距离由调用方算好后传入（{@code distanceSquared}），本方法不碰 {@code Location}。
     */
    static boolean shouldHit(UnitRegistry registry, String matchId, java.util.UUID candidateId,
                             LivingEntity candidate, Side sourceSide, Entity source,
                             double radiusSquared) {
        if (candidate instanceof Player) {
            return false;   // R42 / R41：玩家永不成为目标
        }
        OwnershipTable.Owner owner = registry.table().owner(candidateId);
        if (owner == null || !matchId.equals(owner.matchId())) {
            return false;   // 不在本场账本里
        }
        if (owner.side() == sourceSide) {
            return false;   // 友方不掉血
        }
        return candidate.getLocation().distanceSquared(source.getLocation()) <= radiusSquared;
    }

    /**
     * ★ <b>这次伤害是不是「我们自己的爆炸」造成的</b>（给未来用的纯判据）。
     *
     * <p>目前只在 {@link SummonLifecycleGuard} 的日志里用；留成公开方法是为了让
     * 「爆炸伤害归谁」这件事**只有一处判据**（本项目最反复的教训：判据散在两处必然分叉）。
     */
    public static boolean isOurExplosion(UnitRegistry registry, Entity damager) {
        return registry != null && damager != null
                && registry.isTracked(damager.getUniqueId());
    }
}
