package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.policy.MountSpeedRamp;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ★★★ <b>「哪几匹马有速度斜坡、各自攒到第几档」的记账本</b> —— 纯类
 * （2026-10-18 从 {@link MountSpeedRampService} 里抽出来，为了让一个真实缺陷可被离线钉住）。
 *
 * <h2>为什么必须抽出来（这是一次真实的缺陷）</h2>
 * 用户 2026-10-18 原话：「前储君不仅没有武器和盔甲也**没有持续加速效果**」。
 * 武器/盔甲那一半已在 2026-10-18 修掉；<b>加速那一半的根因在这里</b>
 * （原来是 {@code MountSpeedRampService#onTick} 的最后两行）：
 * <pre>{@code
 * ramps.keySet().retainAll(units.keySet());
 * lastTargets.keySet().retainAll(units.keySet());
 * }</pre>
 * {@code units} 的键是**骑手**（{@code SummonCombat} 的实体 UUID），
 * 而 {@code ramps} / {@code lastTargets} 的键是**载具**（马）的 UUID ——
 * 两者<b>永远不相等</b> ⇒ 每 tick 都把表清空
 * ⇒ 下一 tick 又 {@code computeIfAbsent(fresh())} 从 {@code ×1.0} 重新开始
 * ⇒ <b>倍率永远停在起点</b>，屏幕上就是「完全没有加速」。
 *
 * <h2>口径（三条）</h2>
 * <ol>
 *   <li>{@link #beginTick()} ⇒ {@link #observe(UUID, UUID)} 若干次 ⇒ {@link #endTick()}；</li>
 *   <li>「攻击到敌人」= <b>这一 tick 看到的目标与此前记下的不是同一个</b>
 *       （判据与历史口径一致，见 {@link MountSpeedRampService} 的 javadoc）；</li>
 *   <li>★ 清理只清「<b>本 tick 没被 {@code observe} 过的</b>」那些键 ——
 *       键空间与写入端**完全相同**（这正是上面那个缺陷的反面）。</li>
 * </ol>
 *
 * <p>★ 本类不碰 Bukkit：只吃 UUID 与 {@link MountSpeedRamp} ⇒ 可离线逐 tick 断言。
 */
final class RampBook {

    /** 一次观察的结果（调用方据此决定"要不要写属性 + 打日志"）。 */
    record Step(MountSpeedRamp before, MountSpeedRamp after, boolean attacked) {

        /** 倍率是否变了（含清零）。★ 只有变了才值得写属性，否则每 tick 都触发属性重算。 */
        boolean changed() {
            return before.multiplier() != after.multiplier();
        }
    }

    private final Map<UUID, MountSpeedRamp> ramps = new LinkedHashMap<>();

    /** 载具 UUID → 上一次看到的"骑手目标"。 */
    private final Map<UUID, UUID> lastTargets = new LinkedHashMap<>();

    /**
     * ★★ 2026-10-18：**外部阳性信号**——"这个载具的骑手这一 tick 真的打到了敌人"。
     *
     * <p>为什么需要它：「攻击到敌人时清除加成」原来只靠"骑手换了目标"推断
     * ⇒ 持续啃同一个目标（例如一直砍信标）时**永不清零**（审计发现的口径缺口）。
     * 现在由伤害事件直接给一个阳性信号（{@link #markAttacked(UUID)}），
     * 下一次 {@link #observe} 就按"攻击到了"处理。
     */
    private final Set<UUID> forcedHits = new LinkedHashSet<>();

    /** 记一次「骑手真的打到了敌人」（下一次 {@link #observe} 生效，一次性）。 */
    void markAttacked(UUID mountId) {
        if (mountId != null) {
            forcedHits.add(mountId);
        }
    }

    /** 本 tick 观察到的载具（{@link #endTick()} 的保留集合）。 */
    private final Set<UUID> seen = new LinkedHashSet<>();

    /** 开一个 tick（幂等：重复调用只会清空 seen）。 */
    void beginTick() {
        seen.clear();
    }

    /**
     * 观察一匹马这一 tick 的状态。
     *
     * @param mountId   载具（马）的 UUID
     * @param targetNow 骑手**这一 tick** 的目标（没有 ⇒ {@code null}）
     * @return 观察结果（{@code before} / {@code after} / 这一 tick 算不算"攻击到敌人"）
     */
    Step observe(UUID mountId, UUID targetNow) {
        seen.add(mountId);
        MountSpeedRamp before = ramps.computeIfAbsent(mountId, id -> MountSpeedRamp.fresh());
        UUID targetBefore = lastTargets.get(mountId);
        // ★ 2026-10-18：两种"攻击到了"——① 外部阳性信号（伤害事件）；② 换了目标（老判据）
        boolean attacked = forcedHits.remove(mountId)
                || (targetNow != null && !targetNow.equals(targetBefore));
        if (attacked) {
            lastTargets.put(mountId, targetNow);
        }
        MountSpeedRamp after = before.tick(attacked);
        ramps.put(mountId, after);
        return new Step(before, after, attacked);
    }

    /**
     * 收尾一个 tick：只留本 tick 观察过的载具。
     *
     * ★ 这就是那个缺陷的修复点 —— 保留集合用 {@link #seen}（{@code observe} 写入的同一批键），
     * <b>不再</b>用调用方的 {@code units} 键集合（那是骑手的键空间，与这里不同）。
     */
    void endTick() {
        retainOnly(seen);
        seen.clear();
    }

    /**
     * 只留这些键（{@link #endTick()} 的唯一实现）。
     *
     * <p>★ 单独拆出来是为了让「<b>用错的键空间清理</b>」这个缺陷能被写成一条断言
     * （{@code RampBookTest#cleaningWithTheWrongKeySpaceIsTheBug} 直接拿骑手的键调它）——
     * 否则那条缺陷只能靠读代码发现。
     */
    void retainOnly(Set<UUID> keys) {
        ramps.keySet().retainAll(keys);
        lastTargets.keySet().retainAll(keys);
    }

    /** 清空（对局收尾，幂等）。 */
    void clear() {
        ramps.clear();
        lastTargets.clear();
        seen.clear();
    }

    /** 当前记着几匹马（对账 / 测试用）。 */
    int size() {
        return ramps.size();
    }

    /** 某匹马当前的倍率（没有 ⇒ {@code null}）。 */
    Double multiplierOf(UUID mountId) {
        MountSpeedRamp state = ramps.get(mountId);
        return state == null ? null : state.multiplier();
    }
}
