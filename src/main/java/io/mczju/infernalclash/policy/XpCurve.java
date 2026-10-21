package io.mczju.infernalclash.policy;

import java.util.OptionalDouble;

/**
 * 经验（费用）曲线 —— **纯函数，可离线单测**，不碰服务端。
 *
 * <h2>口径（全部是用户给定值，照抄，不得改动）</h2>
 * <table border="1">
 *   <caption>来源见 {@code docs/02} §6.2 与 §10.1；
 *          ★ 2026-10-18 用户第 8 条把前两行一起改了（逐字：「**减缓经验恢复速度：现在改为
 *          战斗开始时 5 级，每 2 秒恢复 1 级，双倍时间每秒恢复 1 级**」）</caption>
 *   <tr><th>项</th><th>值</th><th>出处</th></tr>
 *   <tr><td>战斗开始时经验</td><td><b>5 级</b>（旧值 0）</td><td>2026-10-18 用户裁定</td></tr>
 *   <tr><td>升 1 级耗时（战斗前 2 分钟）</td><td><b>40 tick</b>（旧值 20）</td><td>2026-10-18 用户裁定</td></tr>
 *   <tr><td>升 1 级耗时（第 2400 tick 起 / 双倍）</td><td><b>20 tick</b>（旧值 10）</td><td>2026-10-18 用户裁定</td></tr>
 *   <tr><td>等级上限</td><td><b>10 级</b></td><td>R21</td></tr>
 *   <tr><td>满级后是否继续累积</td><td><b>不累积</b>；<b>扣费后可以继续涨回 10</b></td><td>2026-10-15 裁定</td></tr>
 * </table>
 *
 * <h2>★ 三个必须钉死的边界（{@code docs/02} §6.2 明文要求，见 {@code XpCurveTest}）</h2>
 * <ol>
 *   <li><b>封顶后不增长</b>：已满 10 级时继续 tick，值不变；</li>
 *   <li><b>扣费后能恢复</b>：从 10 级扣掉费用后能再涨回 10，且**不会越过 10**；</li>
 *   <li><b>双倍切换点连续</b>：第 2400 tick 从 20 tick/级 切到 10 tick/级 时，
 *       <b>不跳变、不回退</b>。</li>
 * </ol>
 *
 * <h2>★ 加时（D2，2026-10-15 用户裁定）</h2>
 * 加时段（第 3600 tick 之后的 1200 tick）**继续按双倍速度**增长。
 * ⇒ 本类**没有阶段参数**：判据只有一条 —— {@code battleTick >= doubleXpAfterTicks}
 * 即为双倍；加时只是 {@code battleTick} 继续增长。
 *
 * <h2>为什么是「连续累加」而不是「离散跳级」</h2>
 * 【Q4 已裁定】用户原话理由：「这样能让玩家通过<b>经验条长度</b>判断下次升级需要多久」。
 * ⇒ 值域是 {@code [0, maxLevel]} 上的<b>实数</b>；打牌扣费后**允许带小数**
 * （「允许扣出具小数部分」）。原先的「读法 A（每 20 tick 离散跳级）」**已作废**。
 *
 * <h2>单位</h2>
 * <b>内部单位 = 「级」</b>（{@code 1.0} = 满 1 级、{@code maxLevel} = 满级）⇒
 * 经验条长度直接是 {@code current / maxLevel}，UI 侧不需要换算。
 *
 * <p>⚠️ 用 {@code double} 累加必然有**浮点尾差**（每 tick 加 0.05 无法精确表示）。
 * 本项目接受它：值域只有 {@code [0, 10]}、单局 tick 数在数千量级，尾差约 1e-13，
 * 且**每次写入都经过 {@link #clamp}** ⇒ 不会越界。测试里的比较一律带容差。
 * 若哪天有人要把它换成定点整数，**必须先改 {@code XpCurveTest} 的容差**。
 */
public final class XpCurve {

    /** 出厂值：升 1 级 **40** tick（= 2 秒；2026-10-18 用户裁定，配置键 {@code phases.xp-ticks-per-level}）。 */
    public static final int SHIPPED_TICKS_PER_LEVEL = 40;

    /** 出厂值：双倍期升 1 级 **20** tick（= 1 秒，配置键 {@code phases.xp-ticks-per-level-double}）。 */
    public static final int SHIPPED_TICKS_PER_LEVEL_DOUBLE = 20;

    /** 出厂值：第 2400 tick 起双倍（R22，配置键 {@code phases.double-xp-after-ticks}）。 */
    public static final int SHIPPED_DOUBLE_XP_AFTER_TICKS = 2400;

    /** 出厂值：等级上限 10（R21，配置键 {@code phases.max-level}）。 */
    public static final double SHIPPED_MAX_LEVEL = 10.0;

    private final int ticksPerLevel;
    private final int ticksPerLevelDouble;
    private final int doubleXpAfterTicks;
    private final double maxLevel;

    /** 出厂曲线（全部为用户给定值）。 */
    public static XpCurve shipped() {
        return new XpCurve(SHIPPED_TICKS_PER_LEVEL, SHIPPED_TICKS_PER_LEVEL_DOUBLE,
                SHIPPED_DOUBLE_XP_AFTER_TICKS, SHIPPED_MAX_LEVEL);
    }

    /**
     * @param ticksPerLevel       战斗期升 1 级所需 tick（&gt;= 1）
     * @param ticksPerLevelDouble 双倍期升 1 级所需 tick（&gt;= 1）
     * @param doubleXpAfterTicks  从战斗开始第几个 tick 起双倍（&gt;= 0）
     * @param maxLevel            等级上限（&gt; 0）
     */
    public XpCurve(int ticksPerLevel, int ticksPerLevelDouble, int doubleXpAfterTicks, double maxLevel) {
        if (ticksPerLevel < 1) {
            throw new IllegalArgumentException("phases.xp-ticks-per-level 必须 >= 1：实际 " + ticksPerLevel);
        }
        if (ticksPerLevelDouble < 1) {
            throw new IllegalArgumentException(
                    "phases.xp-ticks-per-level-double 必须 >= 1：实际 " + ticksPerLevelDouble);
        }
        if (doubleXpAfterTicks < 0) {
            throw new IllegalArgumentException(
                    "phases.double-xp-after-ticks 必须 >= 0：实际 " + doubleXpAfterTicks);
        }
        if (!(maxLevel > 0)) {
            throw new IllegalArgumentException("phases.max-level 必须 > 0：实际 " + maxLevel);
        }
        this.ticksPerLevel = ticksPerLevel;
        this.ticksPerLevelDouble = ticksPerLevelDouble;
        this.doubleXpAfterTicks = doubleXpAfterTicks;
        this.maxLevel = maxLevel;
    }

    /** 等级上限。 */
    public double maxLevel() {
        return maxLevel;
    }

    /** 战斗第 {@code battleTick} tick 是否已进入双倍期。 */
    public boolean isDoubled(long battleTick) {
        return battleTick >= doubleXpAfterTicks;
    }

    /** 当前速率下升 1 级所需的 tick（战斗期 20 / 双倍期 10）。 */
    public int ticksPerLevel(long battleTick) {
        return isDoubled(battleTick) ? ticksPerLevelDouble : ticksPerLevel;
    }

    /** 单个 tick 获得的经验（单位 = 「级」）。 */
    public double gainPerTick(long battleTick) {
        return 1.0 / ticksPerLevel(battleTick);
    }

    /** 把任意值收进 {@code [0, maxLevel]}。所有写入都必须经过它。 */
    public double clamp(double current) {
        if (!(current > 0)) {
            return 0.0; // 含 NaN 与负数：NaN 比较恒 false ⇒ 落到这里
        }
        return Math.min(current, maxLevel);
    }

    /**
     * 推进**一个** tick。
     *
     * <p>★ 封顶后**不增长**（满级时直接返回 {@code maxLevel}）——
     * 但**不是**"关闭增长"：扣费后（值降到 maxLevel 以下）再 tick 会正常恢复。
     */
    public double tick(double current, long battleTick) {
        double value = clamp(current);
        if (value >= maxLevel) {
            return maxLevel;
        }
        return clamp(value + gainPerTick(battleTick));
    }

    /**
     * 从 {@code fromTick} 起连续推进 {@code ticks} 个 tick。
     *
     * <p>★ **逐 tick 累加**（不是"速率 × 时长"的闭式）—— 只有这样才能保证
     * 跨过双倍切换点时**不跳变、不回退**（第三号边界用例）。
     */
    public double gain(double current, long fromTick, int ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks 不能为负：实际 " + ticks);
        }
        double value = clamp(current);
        for (int i = 0; i < ticks; i++) {
            value = tick(value, fromTick + i);
        }
        return value;
    }

    /** 当前经验是否够付 {@code cost}（费用 = 整数「级」）。 */
    public boolean canAfford(double current, int cost) {
        if (cost < 0) {
            throw new IllegalArgumentException("卡牌费用不能为负：实际 " + cost);
        }
        return clamp(current) >= cost;
    }

    /**
     * 尝试扣费。
     *
     * <p>★ 返回 {@link OptionalDouble#empty()} 表示**经验不足、不能出牌**
     * （不是"扣成负数"）；成功时结果**保留小数部分**（「允许扣出具小数部分」）。
     */
    public OptionalDouble trySpend(double current, int cost) {
        if (cost < 0) {
            throw new IllegalArgumentException("卡牌费用不能为负：实际 " + cost);
        }
        double value = clamp(current);
        if (value < cost) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(clamp(value - cost));
    }
}
