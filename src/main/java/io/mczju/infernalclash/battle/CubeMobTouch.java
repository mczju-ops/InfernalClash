package io.mczju.infernalclash.battle;

/**
 * ★ <b>方块怪（史莱姆 / 岩浆怪）接触伤害的纯判据</b> —— 2026-10-17 批次 3 收尾新增。
 *
 * <h2>为什么需要它（26.2 服务端反编译实测，来自 InfernalGuess 的同名类）</h2>
 * 原版 {@code AbstractCubeMob} 的伤害入口<b>只有两个</b>，而且都排除了普通怪物：
 * <table border="1">
 *   <caption>原版入口</caption>
 *   <tr><th>方法</th><th>触发条件</th><th>能打到谁</th></tr>
 *   <tr><td>{@code playerTouch(Player)}</td><td>玩家碰到它</td><td><b>只有玩家</b></td></tr>
 *   <tr><td>{@code push(Entity)}</td><td>它撞到别的实体</td>
 *       <td>只有 {@code IronGolem}（源码里显式 {@code instanceof IronGolem}）</td></tr>
 * </table>
 * 而看起来像「攻击」的 {@code CubeMobAttackGoal.tick()} 只做两件事：
 * {@code lookAt(target)} + 给移动控制设方向 —— <b>一次伤害都不发</b>。
 * ⇒ 卡表要求的「方块怪能打人」在原版里**根本不存在**，必须我们接管。
 * 受影响的两张卡：<b>熔岩精灵</b>（{@code docs/19} 第 85 行）与
 * <b>烈焰熔炉</b>（第 150 行）—— 两者都是岩浆怪。
 *
 * <h2>照抄过来的四条（数值与判据都<b>不是</b>我定的）</h2>
 * <ol>
 *   <li><b>伤害</b> {@code getAttackDamage()}：属性 {@code ATTACK_DAMAGE} 的值
 *       （{@code setSize} 把它设成体型 1/2/4）；<b>岩浆怪再 +2</b>（{@code MagmaCube} 覆写）；</li>
 *   <li><b>能不能打</b> {@code isDealsDamage()}：史莱姆要求 {@code !isTiny()}（体型 &gt; 1），
 *       岩浆怪覆写成「只看 AI 是否生效」⇒ <b>体型 1 的岩浆怪也能打（3 点）</b>
 *       —— ★ 熔岩精灵正是"最小形态"，所以这一条对它<b>必须</b>成立；</li>
 *   <li><b>够不够近</b> {@code Mob#getMeleeAttackRangeSqr()} = {@code (bbWidth*2)² + 目标宽度}；</li>
 *   <li><b>视线</b> {@code hasLineOfSight(target)}。</li>
 * </ol>
 *
 * <h2>★ 铁律 10</h2>
 * 本类不碰 Bukkit：只认「体型 / 宽度 / 距离 / 视线 / 是否岩浆怪」这几个入参。
 * <b>「打谁」由调用方从账本决定</b>（{@link CubeMobTouchListener}）——
 * 本类从不自己找目标。
 */
public final class CubeMobTouch {

    /**
     * 两次接触伤害之间的最小间隔（tick）。
     *
     * <p>★ 为什么需要节流：原版的接触伤害是**每次碰撞**结算的（服务端每 tick 都可能判），
     * 而我们的实现挂在 {@code runTimer} 上 —— 若每 tick 都造成一次伤害，
     * 一只贴着脸的岩浆怪会变成"绞肉机"（实测体感：0.5 秒就能打死一个召唤物）。
     * <p>★ 取值 20 tick（1 秒）是**我按手感定的**（可被否决，登记在
     * {@code docs/05}）：它让「接触伤害」明显弱于"主动攻击"，
     * 又不至于慢到看不见 —— 岩浆怪 +2 后体型 1 是 3 点/秒，
     * 与一个普通近战单位（约 3~4 点/次、1 秒一次）同一量级。
     */
    public static final int TOUCH_INTERVAL_TICKS = 20;

    private CubeMobTouch() {
    }

    /** 原版 {@code AbstractCubeMob#isTiny()}：{@code getSize() <= 1}。 */
    public static boolean isTiny(int size) {
        return size <= 1;
    }

    /**
     * 原版 {@code isDealsDamage()}：
     * 史莱姆 = {@code !isTiny() && isEffectiveAi()}，岩浆怪 = {@code isEffectiveAi()}。
     *
     * @param magma 是不是岩浆怪（它的覆写把体型条件去掉了）
     */
    public static boolean dealsDamage(int size, boolean magma) {
        return magma || !isTiny(size);
    }

    /**
     * 原版 {@code getAttackDamage()} 的等价物。
     *
     * @param attackDamageAttribute 实体 {@code ATTACK_DAMAGE} 属性的当前值（= 体型）
     * @param magma                 是不是岩浆怪（{@code MagmaCube} 覆写：+2）
     */
    public static double damageOf(double attackDamageAttribute, boolean magma) {
        return magma ? attackDamageAttribute + 2.0 : attackDamageAttribute;
    }

    /**
     * 原版 {@code Mob#getMeleeAttackRangeSqr(LivingEntity)} —— 返回的是<b>平方</b>距离阈值。
     *
     * @param selfWidth   方块怪自己的 {@code getWidth()}（体型越大越宽）
     * @param targetWidth 目标的 {@code getWidth()}
     */
    public static double touchRangeSq(double selfWidth, double targetWidth) {
        double reach = selfWidth * 2.0;
        return reach * reach + targetWidth;
    }

    /**
     * ★★ <b>这一次接触该不该造成伤害</b>（四条判据的合取，逐条可复述）。
     *
     * @param size            方块怪的体型（{@code Slime#getSize()}）
     * @param magma          是不是岩浆怪
     * @param attackDamage    它的 {@code ATTACK_DAMAGE} 当前值
     * @param selfWidth       它的 {@code getWidth()}
     * @param distanceSq      它到目标的**平方**距离
     * @param targetWidth     目标的 {@code getWidth()}
     * @param hasLineOfSight  它能不能看见目标（原版 {@code dealDamage} 同款）
     * @return 该造成的伤害值；{@code <= 0} 表示<b>不该造成伤害</b>
     */
    public static double touchDamage(int size, boolean magma, double attackDamage,
                                     double selfWidth, double distanceSq, double targetWidth,
                                     boolean hasLineOfSight) {
        if (!dealsDamage(size, magma)) {
            return 0.0;   // 体型 1 的史莱姆连玩家都打不动（原版行为）
        }
        if (!hasLineOfSight) {
            return 0.0;
        }
        if (distanceSq > touchRangeSq(selfWidth, targetWidth)) {
            return 0.0;
        }
        double damage = damageOf(attackDamage, magma);
        return damage > 0.0 ? damage : 0.0;
    }

    /** 一行说明（日志与测试失败信息用）。 */
    public static String describe(int size, boolean magma) {
        return (magma ? "岩浆怪" : "史莱姆") + "（体型 " + size + "）"
                + (dealsDamage(size, magma) ? "能造成接触伤害" : "体型过小，原版就不造成伤害");
    }
}
