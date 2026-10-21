package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.card.SummonSpawner;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.MountSpeedRamp;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★ <b>「未攻击时每 2s +20% 速度、上限 +100%、命中清零」的执行层</b>
 * —— 2026-10-17 批次 3 收尾新增（用户第 4 条：「做前储君速度曲线」）。
 *
 * <h2>判据在纯类里，这里只做三件事</h2>
 * <ol>
 *   <li><b>读</b>「这匹马这一 tick 有没有攻击到敌人」；</li>
 *   <li>调 {@link MountSpeedRamp#tick(boolean)}；</li>
 *   <li>把 {@link MountSpeedRamp#multiplier()} 写到 {@code MOVEMENT_SPEED} 上
 *       （走 {@link SummonSpawner#applySpeedScale} —— ★ 它从**原始**基础值乘起，
 *        否则会复利累加，见那里的 javadoc）。</li>
 * </ol>
 *
 * <h2>★★ 「有没有攻击到敌人」怎么判（★ 这一格是我定的，要点名）</h2>
 * 本类**不听伤害事件**，而是每 tick 比较一次：
 * <blockquote>
 * 「这匹马的<b>乘客</b>（骑手）的战斗状态里那个目标，与上一次记下的**是不是同一个**」
 * </blockquote>
 * <ul>
 *   <li>换了目标 ⇒ 说明它<b>刚打死了 / 刚转火</b> ⇒ 算「攻击到敌人」⇒ 清零；</li>
 *   <li>目标没变 ⇒ 没有"新的攻击" ⇒ 继续累加。</li>
 * </ul>
 * ★ 为什么不用 {@code EntityDamageByEntityEvent}：
 * ① 那个事件每 tick 都可能来，而本功能只需要"2 秒一档"的粒度；
 * ② 骑手打的是**账本里的**目标，而"目标有没有变"这个信号**只在我们的战斗状态里**
 *   （原版的 {@code getTarget} 会被原版 goal 反复改写）；
 * ③ 用户裁定「<b>打到信标 / 防御塔也算「攻击到敌人」</b>」——
 *   信标与防御塔**就在账本里**（它们是候选），所以"换目标"这条判据自动把它们算进去，
 *   <b>不需要</b>另外判一次"打的是不是目标物"。
 * <p>★ 局限（**2026-10-18 已修**）：如果骑手**一直**打同一个目标（比如一直啃信标），
 * "换目标"就不会发生 ⇒ 旧实现永不清零，与文档「攻击到敌人时清除」不符。
 * 现在多了一条**阳性信号**：{@link #onAttacked(UUID)}（伤害事件 ⇒
 * {@code MatchHooks#onRiderHit} ⇒ {@code CombatDirector#onRiderHit}）——
 * 真的打到了就清零，**不管目标换没换**。两条判据并存，换目标仍然是其中一条。
 *
 * <h2>★ 铁律 10</h2>
 * 不查世界、不建实体：候选与目标都来自 {@link CombatDirector} 的内存状态
 * （{@code SummonCombat}），实体只按 UUID 点查。
 */
final class MountSpeedRampService {

    private final UnitRegistry registry;
    private final SummonSpawner spawner;
    private final CardCatalog catalog;
    private final Logger logger;

    /**
     * ★★★ <b>记账本</b>（2026-10-18 从本类抽成纯类 {@link RampBook}）。
     *
     * <p>抽出去的理由很具体：原来的两行 {@code retainAll(units.keySet())} 把
     * 「骑手的键空间」当成「载具的键空间」⇒ 每 tick 清空 ⇒ 倍率永远回到起点
     * （= 用户报的「没有持续加速效果」）。那种缺陷**只能在纯类里逐 tick 钉住**，
     * 放在这个需要真实体/真账本的执行层里是测不到的。
     */
    private final RampBook book = new RampBook();

    MountSpeedRampService(UnitRegistry registry, SummonSpawner spawner, CardCatalog catalog,
                          Logger logger) {
        this.registry = registry;
        this.spawner = spawner;
        this.catalog = catalog;
        this.logger = logger;
    }

    /**
     * 每个对局 tick 调一次。
     *
     * @param units 「实体 UUID → 战斗状态」的只读视图（由 {@link CombatDirector} 给）
     */
    void onTick(Map<UUID, SummonCombat> units) {
        if (registry == null || spawner == null || units == null || units.isEmpty()) {
            return;
        }
        // ★★★ 2026-10-18 修（用户：「前储君…**没有持续加速效果**」的根因就在原来这两行）：
        //   旧实现每 tick 末尾做 `ramps.keySet().retainAll(units.keySet())`，
        //   而 `units` 的键是**骑手** UUID、`ramps` 的键是**载具（马）** UUID
        //   ⇒ 永远不相等 ⇒ 表每 tick 被清空 ⇒ 下一 tick 从 ×1.0 重新开始
        //   ⇒ 倍率永远停在起点（屏幕表现 = "完全没有加速"）。
        //   ⇒ 现在把记账搬进纯类 {@link RampBook}，保留集合 = **本 tick 真正观察到的载具**
        //     （与写入端同一个键空间）。`RampBookTest` 逐 tick 钉住这条。
        RampBook book = this.book;
        book.beginTick();
        for (SummonCombat unit : units.values()) {
            Mob rider = unit.mob();
            if (rider == null || rider.isDead() || !rider.isValid()) {
                continue;
            }
            Entity vehicle = rider.getVehicle();
            if (!(vehicle instanceof LivingEntity mount) || mount.isDead() || !mount.isValid()) {
                continue;   // 这个单位不是乘客（绝大多数）
            }
            // ★ 只有**卡里声明了速度曲线**的载具才管（见 rampAppliesTo）
            if (!rampAppliesTo(mount)) {
                continue;
            }
            UUID mountId = mount.getUniqueId();
            RampBook.Step step = book.observe(mountId, unit.targetId());
            if (step.changed() || (step.attacked() && step.before().multiplier() != MountSpeedRamp.BASE)) {
                // ★ 只在**倍率真的变了**（或清零）时写属性 + 打日志：
                //   每 tick 都写会不停地触发属性重算，而且日志会淹掉真正的告警
                boolean applied = spawner.applySpeedScale(mount, step.after().multiplier());
                if (applied) {
                    logger.info("[IC] 坐骑速度" + (step.attacked() ? "清零（刚攻击到敌人）" : "提升")
                            + "：" + mount.getType() + " → " + step.after().describe()
                            + "（前储君：未攻击时每 " + (MountSpeedRamp.INTERVAL_TICKS / 20)
                            + " 秒 +20%，上限 +100%）");
                }
            }
        }
        // ★ 清理只清"本 tick 没观察到的"那些载具（键空间与 observe 一致）
        book.endTick();
    }

    /**
     * 这个载具要不要跑速度曲线。
     *
     * <p>判据 = 「**这张卡**声明了速度曲线」——
     * 用「{@code summon.attributes.movement-speed} 存在**且**该载具带乘客」是不够的：
     * 炽足兽骑士那匹马也带乘客、也有静态倍率（×200%），但文档<b>没有</b>要求它随时间变化
     * ⇒ 两者必须能分开。
     * <p>★ 所以走的是**卡数据**：{@code summon.speed-ramp: true}（默认 false）。
     * 这样"哪几张卡有斜坡"是一份可对账的清单（出厂只有前储君一张）。
     */
    private boolean rampAppliesTo(LivingEntity mount) {
        if (catalog == null) {
            return false;
        }
        String cardId = registry.cardIdOf(mount);
        if (cardId == null) {
            return false;
        }
        Card card = catalog.cards().get(cardId);
        return card != null && card.summon() != null && card.summon().speedRamp();
    }

    /** 对局收尾：清空记账本并让 spawner 忘掉原始速度（**幂等**）。 */
    void clear() {
        book.clear();
        if (spawner != null) {
            spawner.clearSpeedBases();
        }
    }

    /**
     * ★★ 2026-10-18：**骑手真的打到了敌人**（由伤害事件给出的阳性信号）。
     *
     * <p>修的是审计发现的口径缺口：旧判据只看"骑手换了目标" ⇒ 持续啃同一个目标
     * （例如一直砍信标）时，加成永远不清零，与 {@code docs/19} 第 106 行的
     * 「攻击到敌人时清除该加成」不符。
     *
     * <p>★ 只记一个"待处理"的标记，真正的属性写入仍由 {@link #onTick} 完成
     * （那里才知道载具是否仍在本局、倍率是否真的变化）。
     */
    void onAttacked(UUID mountId) {
        book.markAttacked(mountId);
    }

    /** 当前记着几匹马（测试 / 探针用）。 */
    int trackedCount() {
        return book.size();
    }

    /** 某个载具当前的倍率（探针用；没有 ⇒ {@code null}）。 */
    Double multiplierOf(UUID mountId) {
        return book.multiplierOf(mountId);
    }

    /** 一行摘要（启动日志用）。 */
    static String describe() {
        return "坐骑速度曲线（前储君）：未攻击时每 " + (MountSpeedRamp.INTERVAL_TICKS / 20)
                + " 秒 +20%，上限 +100%，攻击到敌人（含信标 / 防御塔）立刻清零";
    }
}
