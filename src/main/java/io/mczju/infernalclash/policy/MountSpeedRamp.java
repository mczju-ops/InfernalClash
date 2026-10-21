package io.mczju.infernalclash.policy;

/**
 * ★★ <b>「未攻击时每 2 秒 +20% 速度、上限 +100%、命中清零」的状态机（纯函数）</b>
 * —— 2026-10-17 批次 3 收尾新增。
 *
 * <h2>文档依据（逐字）</h2>
 * {@code docs/19-卡牌总表.md} 第 106 行（前储君）：
 * 「骑<b>僵尸马</b>的持铁矛僵尸…；<b>未攻击时马每 2s +20% 速度、上限 100%</b>，
 * <b>攻击到敌人时清除马的速度加成</b>」，坑那一栏补了一句：
 * 「★ <b>打到信标 / 防御塔也算「攻击到敌人」</b>（用户裁定「算」）」。
 *
 * <h2>为什么它必须是一个状态机（而不是一条静态倍率）</h2>
 * 批次 3 后半我把 {@code movement-speed} 的语义改成了**倍率**，但它仍然是
 * **召唤时应用一次的静态值** ⇒ 静态写 2.0 等于「一召出来就满加成」，
 * 与文档写的成长曲线**不是同一件事**（那一轮我因此把它整条留空，登记为缺口）。
 * 这一格补的就是"随时间累加、可被事件清零"那一维。
 *
 * <h2>口径（四条，逐条可复述）</h2>
 * <ol>
 *   <li>起点 = <b>1.0</b>（不加成）；每过 {@code intervalTicks} 若「这段时间内没有攻击」⇒ <b>+0.2</b>；</li>
 *   <li>上限 = <b>2.0</b>（+100%）；到顶之后不再累加（也就不会再"溢出"）；</li>
 *   <li>★ <b>攻击到敌人 ⇒ 立刻回到 1.0</b>（不是"减一档"）；
 *       「敌人」按用户裁定**包含信标与防御塔**；</li>
 *   <li>★ <b>清零点不重置计时</b>：清零之后仍然是"再过 2 秒 +0.2"。
 *       （若把计时也重置，马被打断一次就要重新等满 2 秒才涨 —— 文档没写，
 *        而"不重置"是更保守的读法：它只影响"多久涨一档"，不影响最终上限。）</li>
 * </ol>
 *
 * <h2>★ 为什么 +20% 是**加法**而不是复利</h2>
 * 文档写的是「每 2s +20% 速度，上限 100%」——
 * 若按复利（×1.2 连乘），1.2⁵ ≈ 2.49 就已经超过 100% 了，
 * 而"上限 100%"这句话只有在**加法**（+20% × 5 = +100%）下才自洽
 * ⇒ 加法是文档自己写死的读法，不是我选的。
 *
 * <p>★ 纯类：不碰 Bukkit、不碰实体 ⇒ 逐档位可在 JUnit 里穷举
 * （{@code MountSpeedRampTest}）。执行层只做三件事：
 * 读"这段有没有攻击" → 调 {@link #tick} → 把 {@link #multiplier()} 写到属性上。
 */
public final class MountSpeedRamp {

    /** 一档的加成（+20%）。出处：{@code docs/19} 第 106 行「每 2s +20%」。 */
    public static final double STEP = 0.2;

    /** 起点 / 清零后的值（不加成）。 */
    public static final double BASE = 1.0;

    /** 上限（+100%）。出处：同上「上限 100%」。 */
    public static final double MAX = 2.0;

    /** 涨一档的间隔（tick）。出处：同上「每 2s」。 */
    public static final int INTERVAL_TICKS = 40;

    /** 当前倍率（1.0 ~ 2.0）。 */
    private final double multiplier;

    /** 距离下一档还差多少 tick（到 0 就涨一档并回到 {@link #INTERVAL_TICKS}）。 */
    private final int ticksToNextStep;

    /** 倍率还没有到顶（到顶之后 tick 可以直接短路，省掉无意义的累加）。 */
    private final boolean capped;

    private MountSpeedRamp(double multiplier, int ticksToNextStep, boolean capped) {
        this.multiplier = multiplier;
        this.ticksToNextStep = ticksToNextStep;
        this.capped = capped;
    }

    /** 新的一匹马：倍率 = 1.0，距离下一档还有 {@link #INTERVAL_TICKS}。 */
    public static MountSpeedRamp fresh() {
        return new MountSpeedRamp(BASE, INTERVAL_TICKS, false);
    }

    public double multiplier() {
        return multiplier;
    }

    public int ticksToNextStep() {
        return ticksToNextStep;
    }

    public boolean capped() {
        return capped;
    }

    /**
     * ★ <b>走一 tick</b>（本类的全部逻辑都在这一格）。
     *
     * @param attackedThisTick 这一 tick（或这一档间隔内）**有没有攻击到敌人**。
     *                         ★ 「敌人」按用户裁定包含<b>信标与防御塔</b>；
     *                         判据由执行层给（见 {@code CombatDirector#mountSpeedKey} 的说明）。
     * @return 新的状态（**返回新实例**，不改自己 —— 与 {@code PlayerProgress} 同一风格）
     */
    public MountSpeedRamp tick(boolean attackedThisTick) {
        if (attackedThisTick) {
            // ★ 攻击 ⇒ 立刻清零（倍率回 1.0），但**计时不重置**（见类 javadoc 第 4 条）
            return new MountSpeedRamp(BASE, ticksToNextStep, false);
        }
        if (capped) {
            return this;   // 到顶了：什么都不用做（也不会再溢出）
        }
        int remaining = ticksToNextStep - 1;
        if (remaining > 0) {
            return new MountSpeedRamp(multiplier, remaining, false);
        }
        double next = Math.min(MAX, round2(multiplier + STEP));
        return new MountSpeedRamp(next, INTERVAL_TICKS, next >= MAX);
    }

    /** 便利：连续走 {@code ticks} 次（执行层需要"补 tick"时用；测试也用它）。 */
    public MountSpeedRamp tick(int ticks, boolean attacked) {
        MountSpeedRamp state = this;
        for (int i = 0; i < Math.max(0, ticks); i++) {
            state = state.tick(attacked);
        }
        return state;
    }

    /**
     * ★ 保留两位小数。
     *
     * <p>为什么必须做：{@code 1.0 + 0.2 * 3} 在二进制浮点里是 {@code 1.5999999999999999}
     * ⇒ 「上限 2.0」的判定与日志都会出现难看的尾数，而且
     * 「同输入 ⇒ 同结果」的断言会变得脆（取决于比较方式）。
     */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 一行说明（日志与测试失败信息用）。 */
    public String describe() {
        return "速度 ×" + multiplier + "（" + (capped ? "已到上限"
                : "再 " + ticksToNextStep + " tick 涨一档") + "）";
    }
}
