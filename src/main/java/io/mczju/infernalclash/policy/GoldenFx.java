package io.mczju.infernalclash.policy;

/**
 * ★★ <b>金卡（金色版本）的粒子特效参数</b>（2026-10-20 新增；纯常量，可离线断言）。
 *
 * <h2>它补的缺陷</h2>
 * 用户 2026-10-20 逐字：「**金卡召唤/释放时的金色粒子特效没有添加**」。
 * 出处是 {@code docs/08} §3.3 / R52~R55（金色变种 = <b>名称金色 + 打蜡粒子</b>，
 * 「召唤物移动时也有」）；而 2026-10-20 之前金色<b>只体现在名字与附魔光效</b>，
 * 施放时一颗粒子都没有。
 *
 * <h2>★ 为什么参数放在这里（而不是散在两个类里）</h2>
 * 同一套观感参数会被<b>两个</b>生产点用：施放瞬间（{@code game/CardCastListener}）
 * 与召唤物的周期跟随（{@code card/SummonSpawner}）。
 * 写两份一定会分叉（本项目已经因为「第二处白名单」踩过
 * {@code EffectApplier.allowedKeys} 与 {@code docs/03} §4.4 分叉那一坑）
 * ⇒ 只在这里写一份，两个调用点都读它。
 *
 * <h2>★ 粒子为什么是 {@code WAX_ON}</h2>
 * 原版**没有**「金色粒子」这个枚举。{@code docs/08} R52 的原文是「**打蜡粒子**」，
 * 而 {@code WAX_ON} 就是原版涂蜡动作播的那一颗 —— 它是**字面最贴**的、不是"看着像金色"。
 * 2026-10-20 用户已确认这个选择（当时给了三个选项，选它）。
 *
 * <h2>★ 纯</h2>
 * 本类不 import {@code org.bukkit.*}：粒子只以**字符串名**出现（由调用方
 * {@code Particle.valueOf} 解析），这样它能进 {@code PolicyPurityGuardTest} 的纯净扫描，
 * 也能在任何 JVM 里断言取值。
 */
public final class GoldenFx {

    /** 1 tick = 1/20 秒（只用于注释里的换算，不参与计算）。 */
    private GoldenFx() {
    }

    /** 粒子名（{@code org.bukkit.Particle} 的常量名；26.2 已 {@code javap} 核实存在）。 */
    public static final String PARTICLE = "WAX_ON";

    /**
     * ★ <b>施放瞬间</b>的那一簇：粒子数。
     *
     * <p>★ 观感值（文档只给"打蜡粒子"这个语义）⇒ 取 14：足够看出"这一下是金卡"，
     * 又不至于把卡牌落点糊住（火球这类法术本身还有自己的火焰特效）。
     * 要改只改这一行。
     */
    public static final int CAST_BURST_COUNT = 14;

    /** ★ 施放瞬间那一簇的散布半径（格）。 */
    public static final double CAST_BURST_SPREAD = 0.45;

    /**
     * ★★ <b>召唤物跟随</b>的节拍（tick）。
     *
     * <p>用户 2026-10-20 选定「**召唤物每 20 tick 少量**」⇒ 1 秒一次。
     * ★ 为什么不每 tick：那是 20 倍粒子量，纯属烧客户端（{@code docs/04} §12 的性能一节）。
     */
    public static final long GLINT_INTERVAL_TICKS = 20L;

    /** ★ 跟随每拍撒几颗（「少量」= 2）。 */
    public static final int GLINT_COUNT = 2;

    /** ★ 跟随的散布半径（格）—— 小一点，看起来像"在身上泛光"而不是"在冒烟"。 */
    public static final double GLINT_SPREAD = 0.18;

    /**
     * ★ <b>跟随的最长拍数</b>（= 20 tick × 300 = 6000 tick = 5 分钟）。
     *
     * <p>为什么需要：对局最长 3 分钟 + 1 分钟加时 = 240 秒 = 4800 tick，
     * 而一个金卡召唤物可能活到对局结束。任务由 {@code LifecycleTasks} 在收尾时统一取消，
     * 这一条只是**第二道保险**（万一有人把任务登记漏了，它也会在 5 分钟后自己停）。
     */
    public static final int GLINT_MAX_TICKS = 300;

    /** 跟随任务在第几拍启动（= 第一拍立刻，之后每 {@link #GLINT_INTERVAL_TICKS}）。 */
    public static final long GLINT_INITIAL_DELAY_TICKS = GLINT_INTERVAL_TICKS;
}
