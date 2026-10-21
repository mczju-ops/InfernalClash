package io.mczju.infernalclash.match;

/**
 * 阶段时长与经验曲线的**纯数据**（出厂值 = 用户给定值，照抄）。
 *
 * <p>键名与出处见 {@code docs/03} §3.2 的 {@code phases} 段：
 * {@code preparing-ticks} 1200（R13）/ {@code battle-ticks} 3600（R11）/
 * {@code double-xp-after-ticks} 2400（R22）/ {@code overtime-ticks} 1200（R12）/
 * {@code xp-ticks-per-level} <b>40</b> / {@code xp-ticks-per-level-double} <b>20</b> /
 * {@code max-level} 10 / {@code xp-at-battle-start} <b>5.0</b>。
 *
 * <h2>★★★ 2026-10-18（用户第 8 条，逐字）：「减缓经验恢复速度」</h2>
 * 「**现在改为战斗开始时 5 级，每 2 秒恢复 1 级，双倍时间每秒恢复 1 级**」⇒ 三个数一起变：
 * <ul>
 *   <li>战斗开始时的经验：{@code 0} → <b>{@code 5.0}</b>（新键 {@code xp-at-battle-start}）；</li>
 *   <li>升级耗时：{@code 20} → <b>{@code 40}</b> tick（1 级 / 2 秒）；</li>
 *   <li>双倍期升级耗时：{@code 10} → <b>{@code 20}</b> tick（1 级 / 1 秒）。</li>
 * </ul>
 * ★ 这三条<b>替换</b>了原来的 R21 / R22 口径（那两个出处不再适用，已在 {@code docs/02} §6.2 改写）。
 *
 * <p>★ 为什么本类自己做范围校验，而不是只靠配置层：配置层（{@code config/MatchConfig}）
 * 会给出「文件 + 键路径」的好报错，但**纯类被直接构造时**也必须自证合法
 * —— 这是本项目「两道防线」的一贯做法（与 {@code RarityTable} / {@code ExchangeMath} 相同）。
 *
 * @param preparingTicks      准备阶段时长（tick）
 * @param battleTicks         战斗阶段时长（tick）
 * @param doubleXpAfterTicks  从战斗开始第几 tick 起双倍经验
 * @param overtimeTicks       加时阶段时长（tick）
 * @param xpTicksPerLevel     战斗期升 1 级所需 tick
 * @param xpTicksPerLevelDouble 双倍期升 1 级所需 tick
 * @param maxLevel            等级上限（同时也是卡牌费用的上限）
 * @param xpAtBattleStart     ★ 2026-10-18 新增：**战斗开始时每人有多少经验**（单位 = 「级」）
 */
public record PhaseConfig(int preparingTicks,
                          int battleTicks,
                          int doubleXpAfterTicks,
                          int overtimeTicks,
                          int xpTicksPerLevel,
                          int xpTicksPerLevelDouble,
                          double maxLevel,
                          double xpAtBattleStart) {

    public PhaseConfig {
        if (preparingTicks < 1) {
            throw new IllegalArgumentException("phases.preparing-ticks 必须 >= 1：实际 " + preparingTicks);
        }
        if (battleTicks < 1) {
            throw new IllegalArgumentException("phases.battle-ticks 必须 >= 1：实际 " + battleTicks);
        }
        if (doubleXpAfterTicks < 0 || doubleXpAfterTicks > battleTicks) {
            // ★ 双倍起点必须落在战斗期内：写成负数 → 开局就双倍；写成 > battleTicks → 永不生效。
            //   两者都是「配置写了却没生效」的类型，必须拒绝而不是容忍。
            throw new IllegalArgumentException("phases.double-xp-after-ticks 必须落在 [0, battle-ticks="
                    + battleTicks + "]：实际 " + doubleXpAfterTicks);
        }
        if (overtimeTicks < 1) {
            throw new IllegalArgumentException("phases.overtime-ticks 必须 >= 1：实际 " + overtimeTicks);
        }
        if (xpTicksPerLevel < 1 || xpTicksPerLevelDouble < 1) {
            throw new IllegalArgumentException("xp-ticks-per-level(-double) 必须 >= 1：实际 "
                    + xpTicksPerLevel + " / " + xpTicksPerLevelDouble);
        }
        if (!(maxLevel > 0)) {
            throw new IllegalArgumentException("phases.max-level 必须 > 0：实际 " + maxLevel);
        }
        if (!(xpAtBattleStart >= 0) || !Double.isFinite(xpAtBattleStart)) {
            // ★ 「开局就给负数经验」会让所有人一进场就付不起任何卡 ⇒ 拒绝，
            //   而不是悄悄夹成 0（那会让「配置写了却没生效」再发生一次）。
            throw new IllegalArgumentException(
                    "phases.xp-at-battle-start 必须是 >= 0 的有限数（单位 = 「级」）：实际 " + xpAtBattleStart);
        }
        if (xpAtBattleStart > maxLevel) {
            throw new IllegalArgumentException("phases.xp-at-battle-start 不能超过 max-level=" + maxLevel
                    + "（超出的部分会被 clamp 掉，配置写了却只生效一半）：实际 " + xpAtBattleStart);
        }
    }

    /**
     * 兼容构造（没有「战斗开始时的经验」那一格）—— 2026-10-18 之前的七参形态。
     *
     * <p>★ 缺省 {@code xpAtBattleStart = 0.0} = **旧的「战斗开始时经验清零」口径**
     * （R20）⇒ 既有调用点与夹具的语义**一字未变**，只有真正要"开局 5 级"的地方
     * 才用全参构造（生产路径 = {@link #shipped()} / {@code MatchConfig#load}）。
     */
    public PhaseConfig(int preparingTicks, int battleTicks, int doubleXpAfterTicks,
                       int overtimeTicks, int xpTicksPerLevel, int xpTicksPerLevelDouble,
                       double maxLevel) {
        this(preparingTicks, battleTicks, doubleXpAfterTicks, overtimeTicks,
                xpTicksPerLevel, xpTicksPerLevelDouble, maxLevel, 0.0);
    }

    /**
     * 出厂值（用户给定，照抄；改这里等于改玩法节奏）。
     *
     * <p>★★★ 2026-10-18 用户第 8 条把三个经验数字一起改了（逐字：「**减缓经验恢复速度：
     * 现在改为战斗开始时 5 级，每 2 秒恢复 1 级，双倍时间每秒恢复 1 级**」）
     * ⇒ {@code 40 / 20 tick} ＋ 开局 {@code 5.0} 级。
     */
    public static PhaseConfig shipped() {
        return new PhaseConfig(1200, 3600, 2400, 1200, 40, 20, 10.0, 5.0);
    }

    /** 战斗第 {@code battleTick} tick 时升 1 级所需的 tick（双倍期用双倍速率）。 */
    public int ticksPerLevel(long battleTick) {
        return battleTick >= doubleXpAfterTicks ? xpTicksPerLevelDouble : xpTicksPerLevel;
    }
}
