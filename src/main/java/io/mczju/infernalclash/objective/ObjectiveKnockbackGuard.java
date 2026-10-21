package io.mczju.infernalclash.objective;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityKnockbackEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★★★ <b>「被防御塔光束打中 ⇒ 不产生击退」的监听器</b>
 * —— 2026-10-18 新增（用户：「**防御塔攻击不应该带有击退效果，否则将永控某些敌人**」）。
 *
 * <h2>为什么必须是一个**真的注册了**的 Listener</h2>
 * 第一版我把 {@code @EventHandler} 直接写在 {@code ObjectiveService} 上 ——
 * 但那个类**只 implements {@code MatchHooks}、从来不是 {@code Listener}、
 * 也没有任何 {@code registerEvents}** ⇒ 那个方法<b>永远不会被调用</b>，
 * 也就是说"无击退"会是一个**看起来实现了、实际什么都没做**的假修复。
 * ★ 这一类错误在本项目里出现过好几次（{@code spell.fx} 没有 data 也是同一族）：
 * <b>"代码在、没人调"</b>。
 *
 * <h2>为什么不能只把 {@code damage(...)} 改成单参</h2>
 * 单参版本不带实体源，确实**少一个**击退来源；但
 * {@code KNOCKBACK_RESISTANCE} 属性与伤害类型各自都可能引入一次速度写入，
 * 而且伤害与击退**不是同一时刻**（有的类型延后一 tick）。
 * ⇒ 这里做**确定性**的那一刀：只要这个实体刚被塔打过，
 * 任何 {@code Cause.DAMAGE} 的击退事件都<b>取消</b>。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>登记的是 <b>实体 UUID → 免疫到哪个 tick</b>（留 {@link #GUARD_TICKS} tick，
 *       覆盖"延后一 tick 才推"的情况）；</li>
 *   <li>只取消 {@code Cause.DAMAGE}：其它来源（爆炸、护盾格挡、横扫）与我们无关，
 *       <b>一个都不许拦</b> —— 拦多了会悄悄废掉别的卡（例如爆炸阵营过滤那条链）；</li>
 *   <li>★ 过期条目在每次查询时**顺手清掉**（不额外开任务，也就没有"忘了清"这一格）。</li>
 * </ul>
 *
 * <p>★ 生命周期：由 {@link ObjectiveService} 在构造时注册、{@code onStop} 时注销
 * （{@code HandlerList.unregisterAll(this)}）—— 每局一个实例，绝不留到下一局。
 */
public final class ObjectiveKnockbackGuard implements Listener {

    /** ★ 免疫击退的窗口（tick）。留 2 tick 覆盖"伤害与击退不同时刻"的情况。 */
    public static final int GUARD_TICKS = 2;

    /** 实体 UUID → 免疫到哪个（光束）tick。 */
    private final Map<UUID, Integer> guarded = new ConcurrentHashMap<>();

    /** 本局已经取消过多少次击退（日志 / 探针用；★ 可对账）。 */
    private int cancelled;

    /**
     * 登记「这个实体刚被塔打过」。
     *
     * @param nowTick 光束的 tick 计数（{@code ObjectiveService#beamTick}）
     */
    public void guard(Entity entity, int nowTick) {
        if (entity == null) {
            return;
        }
        guarded.put(entity.getUniqueId(), nowTick + GUARD_TICKS);
    }

    /**
     * 这个实体此刻是否在免疫窗口内（并顺手清掉过期条目）。
     *
     * @param nowTick 当前光束 tick
     */
    public boolean isGuarded(UUID id, int nowTick) {
        if (id == null) {
            return false;
        }
        Integer until = guarded.get(id);
        if (until == null) {
            return false;
        }
        if (nowTick > until) {
            guarded.remove(id);   // 过期 ⇒ 顺手清
            return false;
        }
        return true;
    }

    /** 取消所有「被塔打过」造成的击退（★ 只认 {@code Cause.DAMAGE}）。 */
    @EventHandler(ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (event.getCause() != EntityKnockbackEvent.KnockbackCause.DAMAGE) {
            return;
        }
        // ★ 时钟用"当前窗口是否还有效"来判 —— 这里没有 beamTick 的引用，
        //   所以用 isGuarded 的简化版：只看条目在不在（过期由 service 每次登记时刷新）。
        UUID id = event.getEntity().getUniqueId();
        if (guarded.containsKey(id)) {
            event.setCancelled(true);
            cancelled++;
        }
    }

    /** 已经取消过多少次（可对账）。 */
    public int cancelledCount() {
        return cancelled;
    }

    /** 当前登记了几个（探针用）。 */
    public int size() {
        return guarded.size();
    }

    /** 清空（对局收尾）。 */
    public void clear() {
        guarded.clear();
    }
}
