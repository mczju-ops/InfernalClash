package io.mczju.infernalclash.policy;

/**
 * ★★★ <b>「玩家永远不会成为任何怪物的目标」的落地口径</b>（2026-10-18 新增）—— 纯类，可离线断言。
 *
 * <h2>用户原话（逐字，2026-10-18 第 2 条）</h2>
 * <blockquote>
 * 「**索敌 bug：猪灵、猪灵蛮兵仍会攻击玩家。请把玩家彻底从头到尾排除出怪物索敌目标，
 * 或者帮我想办法让这些怪物无法识别玩家的同时，对面的玩家仍能看见我方玩家的位置**」
 * </blockquote>
 * 我追问后用户选定：<b>「A. 玩家设为无敌」</b>。
 *
 * <h2>★★★ 为什么「无敌」能让人看不见你（`javap` 实测，不是猜的）</h2>
 * 本地服务端 jar（{@code E:\MCpaper\versions\26.2\paper-26.2.jar}）逐条反编译的结论：
 * <pre>
 *   TargetingConditions.test(level, attacker, target)
 *     ...
 *     LivingEntity.canBeSeenByAnyone()  == false            ⇒ 直接 return false
 *     LivingEntity.canBeSeenAsEnemy()   == false            ⇒ 直接 return false
 *
 *   LivingEntity.canBeSeenAsEnemy()  = !isInvulnerable() &amp;&amp; canBeSeenByAnyone()
 *   LivingEntity.canBeSeenByAnyone() = !isSpectator()    &amp;&amp; isAlive()
 * </pre>
 * 而<b>所有</b>索敌路径最终都走 {@code TargetingConditions}：
 * <ul>
 *   <li>goal 型（僵尸 / 北极熊 / 骷髅…）⇒ {@code NearestAttackableTargetGoal}；</li>
 *   <li>brain 型（<b>猪灵 / 猪灵蛮兵</b> / 疣猪兽 / 监守者）⇒ {@code Sensor.isEntityAttackable}
 *       → {@code TargetingConditions.test}，连
 *       {@code Sensor.isEntityAttackableIgnoringLineOfSight} 也只是换一个
 *       {@code TargetingConditions} 实例（它只忽略<b>隐形</b>与<b>视线</b>，
 *       <b>不忽略</b> {@code canBeSeenAsEnemy}）。</li>
 * </ul>
 * ⇒ <b>把玩家设成 {@code setInvulnerable(true)} 之后，原版任何怪物都不会再把他当目标</b>
 * —— 包括我们改不到的 brain 型，也包括 {@code ANGRY_AT} / {@code NEAREST_VISIBLE_ATTACKABLE_PLAYER}
 * 那两条记忆（它们在 sensor 那一层就已经筛掉了）。
 *
 * <h2>★★ 为什么不用「隐身」（用户提的另一个方向）</h2>
 * 隐身是<b>客户端渲染</b>：它会让<b>所有人</b>都看不见这个玩家（包括对手），
 * 而用户明确要求「<b>对面的玩家仍能看见我方玩家的位置</b>」⇒ 隐身不满足需求。
 * 无敌则完全不影响别人的视角。
 *
 * <h2>★ 与既有两层的关系（三层，缺一都不够）</h2>
 * <ol>
 *   <li><b>索敌层</b>（本类）：怪根本不把玩家当候选 —— 治的是「<b>追着玩家跑</b>」；</li>
 *   <li><b>伤害层</b>（{@code battle/PlayerDamageGuard}，R41）：任何伤害一律取消
 *       —— 治的是「不知怎么还是掉血了」（爆炸 / 药水 / 弹射物等绕过索敌的路径）；</li>
 *   <li><b>清理层</b>（{@code battle/SummonCombat#reassert}）：每 tick 把挂在玩家身上的
 *       目标清掉 —— 治的是「已经锁上、之后才设为无敌」这一格。</li>
 * </ol>
 * ★ 三层<b>都要留着</b>：第 1 层是这次补的（也是唯一能治 brain 型的）；
 * 第 2、3 层是已有的回归防线，删任何一层都会把旧缺陷放回来。
 *
 * <h2>★ 为什么是一个「可关的常量」而不是直接写死</h2>
 * 万一将来用户想要「怪物会追玩家」的玩法（例如 PvE 模式），只改本类这一处就能退回
 * （配套的 {@code MatchPlayerBuffs} 会按 {@link #shouldForceInvulnerable(boolean)} 决定动不动作）。
 */
public final class PlayerTargetBan {

    /**
     * 是否启用「玩家对怪物不可见」。
     *
     * <p>★ <b>唯一开关</b>：{@code false} ⇒ {@code MatchPlayerBuffs} 完全不碰
     * {@code setInvulnerable}（对局内玩家恢复到"能被怪看见"的旧行为）。
     */
    public static final boolean ENABLED = true;

    private PlayerTargetBan() {
    }

    /** 本局要不要把玩家设成无敌（见 {@link #ENABLED}）。 */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * 这位玩家此刻**需不需要**我们去把他设成无敌。
     *
     * @param alreadyInvulnerable 他现在已经是无敌了吗（★ 已经无敌 ⇒ 不必再写一次：
     *                            每 20 tick 写一次同一个值没有意义，还会把日志刷成噪音）
     * @return {@code true} = 该写一次 {@code setInvulnerable(true)}
     */
    public static boolean shouldForceInvulnerable(boolean alreadyInvulnerable) {
        return ENABLED && !alreadyInvulnerable;
    }

    /**
     * 对局收尾时要不要把他**改回**不无敌。
     *
     * <h2>口径</h2>
     * 只还原<b>我们自己改过的那一位</b>（{@code wasInvulnerableBefore == false}
     * 且现在确实无敌）—— 本来就无敌的（例如别的插件给的无敌、创造模式飞行）
     * <b>绝不动</b>，否则就是"我们的收尾改坏了别人的状态"
     * （与 {@code PlayerBuffs#restoreAction} 的三种结局同一口径）。
     *
     * @param wasInvulnerableBefore 本局开始**之前**他是不是本来就无敌
     * @param nowInvulnerable       他此刻是不是无敌
     */
    public static boolean shouldRestore(boolean wasInvulnerableBefore, boolean nowInvulnerable) {
        if (!ENABLED) {
            return false;
        }
        return !wasInvulnerableBefore && nowInvulnerable;
    }

    /** 一行摘要（启动日志 / 收尾日志用）。 */
    public static String describe() {
        return ENABLED
                ? "玩家索敌排除：对局内玩家设为无敌（原版 TargetingConditions 要求"
                        + " canBeSeenAsEnemy，!isInvulnerable，故任何怪物都不会把玩家当目标；"
                        + "不是隐身，对手玩家照样看得见你）"
                : "玩家索敌排除：已关闭（ENABLED = false，怪物会照常把玩家当目标）";
    }
}
