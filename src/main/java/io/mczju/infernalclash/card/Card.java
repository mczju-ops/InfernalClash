package io.mczju.infernalclash.card;

import io.mczju.infernalclash.ability.AbilityTargets;
import io.mczju.infernalclash.policy.Rarity;

import java.util.List;
import java.util.Map;

/**
 * 一张卡的完整数据（**已校验**）—— 「一张卡一个 YAML 文件」的内存形态。
 *
 * <p>字段规范见 {@code docs/03} §4.2~§4.4。本类是**纯数据**：不碰服务端、
 * 不做任何 Bukkit 调用 ⇒ 可以在 JUnit 里离线加载全部出厂卡牌
 * （见 {@code ShippedCardsTest}）。
 *
 * <h2>★ 为什么 {@code summon} 与 {@code spell} 二选一而不是继承</h2>
 * 用「一个记录 + 两个可空块」：{@link CardCatalog} 在加载期就保证
 * **SUMMON 必有 summon 且无 spell**，SPELL 反之。继承层次会让
 * 「一张卡既是召唤又是法术」在类型上成为可能，而它在玩法里没有意义。
 *
 * @param id            卡牌 id（**必须等于文件名**）
 * @param type          {@link Type#SUMMON} 或 {@link Type#SPELL}
 * @param displayName   显示名（MiniMessage；颜色随稀有度，R51）
 * @param flavor        风味描述（R71）
 * @param description   机制说明（R57：承担「除 id/攻/血/费用/冷却外」的全部展示）
 * @param icon          ItemCreator 物品 id（R71；约定 = 卡牌 id）
 * @param cost          费用 = 消耗的经验（R19~R22）
 * @param cooldownTicks 冷却 tick（R23、T9）
 * @param rarity        稀有度（R71）
 * @param summon        召唤卡专属块（SPELL 时为 {@code null}）
 * @param spell         法术卡专属块（SUMMON 时为 {@code null}）
 */
public record Card(String id,
                   Type type,
                   String displayName,
                   String flavor,
                   List<String> description,
                   String icon,
                   int cost,
                   int cooldownTicks,
                   Rarity rarity,
                   Summon summon,
                   Spell spell) {

    /** 卡牌类型（R71）。 */
    public enum Type {
        /** 召唤卡：放下一批召唤物。 */
        SUMMON,
        /** 法术卡：对一片区域施加效果。 */
        SPELL
    }

    /**
     * 召唤落点口径（R24/R25）。
     *
     * <p>★ 目前只有一种：**对地面 → 放在那一格；对空气 → 放在自己脚下**。
     * 之所以做成枚举而不是布尔：将来若要加「只能放自己脚下」（例如纯增益单位），
     * 这里是唯一的扩展点。
     */
    public enum Placement {
        /** 地面或自身（R24/R25 原话）。 */
        GROUND_OR_SELF
    }

    /**
     * ★★★ <b>一组的「成形排布」</b>（{@code group[].formation}）—— 2026-10-18 新增。
     *
     * <h2>出处（用户原话，逐字）</h2>
     * 第 4 条我追问「中间那两个『站开一点』是想要 2-1-2、还是别的形状？」之后，用户裁定：
     * 「**可以。我希望这五个猪灵最好能站成一个类似正五边形**」——说的是
     * {@code piglin_squad}（{@code docs/19} 第 85 行「召唤五名手持金剑的猪灵」）。
     *
     * <h2>★ 为什么是枚举而不是自由文本</h2>
     * 排布是**几何**：写错一个字母就"看起来能用、实际站成一坨"。枚举 ⇒ 装载期就报
     * （{@code ConfigError} 会列出允许的取值），而不是等到游戏里数人头才发现。
     * 将来要加「三角 / 菱形 / 楔形」就在这里加一个取值 + {@code GroupOffsets} 一个纯函数。
     *
     * <h2>★ 半径从哪来（这里最容易误解，已写进配置文档）</h2>
     * 取 <b>{@code spread}</b>：<b>在成形排布里它是「外接圆半径」</b>，不是「相邻间距」。
     * {@code spread <= 0} ⇒ {@link GroupOffsets#DEFAULT_POLYGON_RADIUS}。
     */
    public enum Formation {
        /**
         * <b>正五边形</b>（5 个顶点，**一个顶点朝正前方**＝"前头那只"）。
         *
         * <p>★ 顶点数必须恰好 5（{@code count: 5}）—— 装载期校验：
         * 写 {@code PENTAGON} 却 {@code count: 4} 没有任何合理含义，静默按 4 个顶点画会让
         * "五边形"变成"四边形"，而配置上还写着 PENTAGON。
         */
        PENTAGON
    }

    /** 能力触发时机（R73）。★ {@code ON_DEATH} / {@code ON_SUMMON} 是本项目扩展。 */
    public enum Trigger {
        /** 周期触发（每 N tick）。 */
        PERIODIC,
        /** 受伤时。 */
        ON_HURT,
        /** 攻击命中时。 */
        ON_ATTACK,
        /** 死亡时（亡语）——「不朽旗手」。 */
        ON_DEATH,
        /**
         * ★ **刚被召唤出来时**（一次性）——「召唤时速度 II」/「召唤时凋零 I」/
         * 「召唤后获得抗性提升 V」这类效果走它（docs/19 的恐怖分子、沼泽游击队、
         * 哨位、异蛙寄生体、烈焰熔炉、煤矿守卫、海王…）。
         */
        ON_SUMMON
    }

    /**
     * 一组召唤物（【Q24】一张卡可含**一种或多种**实体 + 相对排布）。
     *
     * @param entity     引用 {@code mob-groups.yml} 的组键 —— **等于原版实体类型名**（如 {@code ZOMBIE}）
     * @param count      这一组生成几个
     * @param forward    相对召唤中心的「前 / 后」偏移（格）
     * @param right      相对召唤中心的「左 / 右」偏移（格）
     * @param spread     同组多个单位之间的间距（格；{@code 0} = 由运行期按 count 自动展开）
     * @param attributes 该组的属性覆写 —— ★ 比 {@code summon.attributes} **更细一层**
     *                   （{@code docs/18} §2 的骨架示例就是把 {@code follow-range} 写在组里的；
     *                   {@code docs/03} §4.3 只画了召唤块级的那一份。两处都支持：
     *                   **组级优先，缺了再看召唤块级，再缺用实体组/原版默认**）
     * @param mount      ★★ <b>这一组要骑上去的那个「载具」（{@code group[].mount}）</b>——
     *                   2026-10-17 批次 3 后半新增，见 {@link io.mczju.infernalclash.policy.MountPlan}。
     *                   值是<b>载具组的实体类型名</b>（如 {@code STRIDER}）；{@code null} / 空 =
     *                   这一组不骑任何东西（绝大多数卡）。出处：{@code docs/19} 第 106 / 109 /
     *                   152 行的三张骑乘卡 ＋ 用户第 8 条裁定「骑乘要实现」。
     *                   ★ 用户第 5 条同时裁定「<b>（僵尸马装备）不加</b>」⇒ 本次**只做骑乘本身**，
     *                   不做"载具专属装备"（所以 {@code equipment} 仍然是**逐卡**的）。
     * @param rows       ★★ <b>方阵排布</b>（{@code group[].rows}）—— 2026-10-18 新增。
     *                   出处 = 用户第 4 条逐字：「**虫群的末影螨不应站成一排，而是站成
     *                   3-4-3 的居中对称方阵**」。取值是逐行的人数（{@code [3, 4, 3]}，
     *                   <b>第一行在最前面</b>），行间距 = 同组间距（{@code spread}），每行各自居中。
     *                   ★ 空表（缺省）= 老的「沿右方向站成一排」；51 张里只有虫群写它。
     *                   ★ 写了它之后 {@code count} 仍然必须写、且必须等于各行之和
     *                   （装载期校验：两个数各写一遍，正是为了让「改了方阵忘了改数量」当场报错）。
     * @param formation  ★★ <b>成形排布</b>（{@code group[].formation}）—— 2026-10-18 新增。
     *                   出处 = 用户第 4 条追问后的裁定，逐字：「**可以。我希望这五个猪灵最好能
     *                   站成一个类似正五边形**」（说的是 {@code piglin_squad} 的 5 只猪灵）。
     *                   取值见 {@link Formation}（目前只有 {@link Formation#PENTAGON}）。
     *                   <ul>
     *                     <li><b>{@code null}（缺省）</b>＝ 不按成形排布 —— 走 {@code rows}（方阵）
     *                         或老的「沿右方向站成一排」（50/51 张卡是这一档）；</li>
     *                     <li>写了它 ⇒ <b>半径取 {@code spread}</b>（{@code <= 0} ⇒ 用
     *                         {@link GroupOffsets#DEFAULT_POLYGON_RADIUS}）——
     *                         ★ 这是**唯一**一处 {@code spread} 的含义与"相邻间距"不同，
     *                         所以在 {@link Formation} 的 javadoc 与 {@code docs/03} §4.3 都写明；</li>
     *                     <li>★ 与 {@code rows} <b>互斥</b>：同时写 ⇒ 装载期拒绝启动
     *                         （两种排布叠在一起没有定义，静默选一个只会让人以为"配置没生效"）。</li>
     *                   </ul>
     * @param lifetimeTicks ★★ <b>这一组召唤物的存活 tick（{@code group[].lifetime-ticks}）</b>
     *                   —— 2026-10-18 新增。出处 = {@code docs/03} §7 早就定名的那个键，
     *                   以及 {@code docs/19} 第 153 行悦灵「**召唤后 40s 直接死亡**」。
     *                   <ul>
     *                     <li><b>{@code null}（缺省）</b>＝ 不设存活期（原版行为，50/51 张卡）；</li>
     *                     <li>写了它 ⇒ 生成后 **N tick 自动移除**（并注销账本条目）。
     *                         ★ 任务登记在**本局的 {@link io.mczju.infernalclash.match.LifecycleTasks}** 上
     *                         ⇒ 对局结束一起取消，**不会下一局凭空冒出召唤物**；
     *                         ★ 没有任务表（例如 `/incl debug` 的探针路径）⇒ **只告警、不自灭**，
     *                         绝不把任务挂在无人回收的地方；</li>
     *                     <li>取值 <b>1 ~ 72000</b>（= 1 小时；越界 ⇒ 装载期 {@code ConfigError}）。</li>
     *                   </ul>
     */
    public record Group(String entity, int count, int forward, int right, double spread,
                        Attributes attributes, String mount, java.util.List<Integer> rows,
                        Formation formation, Integer lifetimeTicks) {

        /**
         * 规范构造器：{@code rows} 的 {@code null} 归一成空表。
         *
         * <p>★ 为什么允许 {@code null}：既有卡牌的 5/6 参兼容构造会传 {@code null}，
         * 而归一化放在这里 ⇒ 下游（{@code GroupOffsets} / {@code SummonSpawner}）
         * 永远不需要判空（"两处各判一次空迟早分叉"）。
         */
        public Group {
            rows = rows == null ? java.util.List.of() : java.util.List.copyOf(rows);
        }

        /** 兼容构造（不写骑乘、不写方阵、不写成形排布）：绝大多数卡都是这一档。 */
        public Group(String entity, int count, int forward, int right, double spread,
                     Attributes attributes) {
            this(entity, count, forward, right, spread, attributes, null, null, null, null);
        }

        /** 兼容构造（写骑乘，不写方阵 / 成形排布）。 */
        public Group(String entity, int count, int forward, int right, double spread,
                     Attributes attributes, String mount) {
            this(entity, count, forward, right, spread, attributes, mount, null, null, null);
        }

        /** 兼容构造（写方阵，不写成形排布）—— 2026-10-18 {@code formation} 之前的那一参形态。 */
        public Group(String entity, int count, int forward, int right, double spread,
                     Attributes attributes, String mount, java.util.List<Integer> rows) {
            this(entity, count, forward, right, spread, attributes, mount, rows, null, null);
        }

        /** 兼容构造（写成形排布，不写存活期）—— 2026-10-18 {@code lifetime-ticks} 之前的那一参形态。 */
        public Group(String entity, int count, int forward, int right, double spread,
                     Attributes attributes, String mount, java.util.List<Integer> rows,
                     Formation formation) {
            this(entity, count, forward, right, spread, attributes, mount, rows, formation, null);
        }

        /** 这一组有没有存活期（{@code lifetime-ticks} 非 null）。 */
        public boolean hasLifetime() {
            return lifetimeTicks != null;
        }

        /** 这一组要骑上去吗。 */
        public boolean ridesSomething() {
            return mount != null && !mount.isBlank();
        }

        /** 这一组是不是「按方阵排」的（{@code rows} 非空）。 */
        public boolean hasRows() {
            return !rows.isEmpty();
        }

        /** 这一组是不是「按成形排布」（{@code formation} 非空）。 */
        public boolean hasFormation() {
            return formation != null;
        }

        /** 方阵一共几个人（{@code rows} 为空 ⇒ 0）。 */
        public int rowTotal() {
            int total = 0;
            for (Integer row : rows) {
                total += row == null ? 0 : row;
            }
            return total;
        }
    }

    /**
     * 属性覆写。
     *
     * <p>★★ {@code null} 一律表示**不覆写**（用实体组的基础属性，再退回原版默认值）
     * —— **不是 0**。用 0 表示"没配"会造出「血量 0 的召唤物」，那是最糟的默认值。
     *
     * @param knockbackResistance ★★ <b>击退抗性</b>（2026-10-17 第十二轮新增，用户裁定 §A 第 2 条：
     *        「<b>所有击退抗性都要做。现在几乎所有单位的击退抗性都是缺失的。</b>」）。
     *        <ul>
     *          <li><b>配置键</b>：{@code knockback-resistance}（在 {@code summon.attributes} 或
     *              组级 {@code attributes} 里；★ <b>三处解析必须同键同名</b>：{@code CardCatalog} 的
     *              组级、{@code MobGroupCatalog} 的实体组与召唤块级）；</li>
     *          <li><b>取值</b>：<b>0.0 ~ 1.0</b>（{@code 1.0} = 完全免疫击退；越界 ⇒
     *               装载期 {@code ConfigError} 拒绝启动，**不 clamp、不静默忽略**
     *               —— {@code 0.8} 写成 {@code 8} 会静默变成"完全免疫"，正是要拦的那类）；</li>
     *          <li><b>{@code null}</b> = 不覆写（原版实体自带的击退抗性照旧 ——
     *               例如劫掠兽、铁傀儡本来就有，写 {@code 0} 反而会**削弱**它们）；</li>
     *          <li><b>落到实体</b>：{@code Attribute.KNOCKBACK_RESISTANCE}（★ 26.2 的常量名已
     *               {@code javap} 实测：<b>没有</b> {@code GENERIC_} 前缀），见
     *               {@code card/SummonSpawner#applyAttributes}。</li>
     *        </ul>
     *        ★ 出厂值**只填文档给了的 5 张卡**（出处逐张见 {@code docs/19}）：
     *        铁甲战士 80%（第 56 行）/ 哨位 80%（第 61 行，★ 那一行同时写了「无法击退」⇒ 矛盾，待裁）/
     *        异蛙寄生体「无法击退」⇒ 1.0（第 83 行）/ 烈焰守卫「无法击退」⇒ 1.0（第 112 行）/
     *        烈焰熔炉「无法击退」⇒ 1.0（第 149 行）。
     */
    public record Attributes(Double health,
                             Double attackDamage,
                             Double armor,
                             Double movementSpeed,
                             Double followRange,
                             Double scale,
                             Double knockbackResistance,
                             Integer size) {

        /** 全都不覆写。 */
        public static final Attributes NONE =
                new Attributes(null, null, null, null, null, null, null, null);

        /**
         * 兼容构造（没有「尺寸档位」）—— 2026-10-18 新增 {@code size} 之前的七参形态。
         *
         * <p>★ 保留它有两个作用：① 既有调用点与夹具一字不改；② 让「这一格是新加的」
         * 在代码里看得出来（只有真正需要 {@code size} 的地方才用全参构造）。
         */
        public Attributes(Double health, Double attackDamage, Double armor, Double movementSpeed,
                          Double followRange, Double scale, Double knockbackResistance) {
            this(health, attackDamage, armor, movementSpeed, followRange, scale,
                    knockbackResistance, null);
        }

        /** 是否一个字段都没写。 */
        public boolean isEmpty() {
            return health == null && attackDamage == null && armor == null
                    && movementSpeed == null && followRange == null && scale == null
                    && knockbackResistance == null && size == null;
        }
    }

    /**
     * 特殊能力（R72/R73、【Q47】数据驱动）。
     *
     * @param id             能力 id —— 必须在**能力注册表**中已实现（注册表在 P6 接入）
     * @param trigger        触发时机
     * @param cooldownTicks  该能力自身的冷却（{@code null} = 不限次；缺省即不限）
     * @param affectedByTaunt 是否受嘲讽影响（R73；**默认 false** —— 见 {@code docs/02} §8.2）
     * @param effects        触发后施加的效果
     * @param targets        ★★ <b>作用面</b>（{@code SELF} / {@code ENEMIES} /
     *                       {@code ALLIES} / {@code EVERYONE}）—— 2026-10-17 第十三轮
     *                       <b>批次 3 后半</b>新增，见 {@link AbilityTargets.Alignment}。
     *                       {@code null} ⇒ 按 {@link AbilityTargets.Alignment#SELF} 处理
     *                       （= 本次改动之前的既有行为）；
     * @param radius         ★★ <b>「周围 N 格」的 N</b>（格；{@code null} = <b>没有范围</b>
     *                       ⇒ 只打自己）。★ 与 {@code targets} <b>是两个键、不是两个模式</b>：
     *                       前者说「打谁」，后者说「多远」—— 分开写才能表达
     *                       「周围 N 格的敌人」这种组合；
     * @param includeSelf    范围内的<b>自己</b>算不算目标（{@code targets: SELF} 时无意义）
     * @param center         ★★ <b>范围的中心取在哪</b>（2026-10-17 批次 3 后半新增）：
     *                       {@code SELF}（缺省）= 触发者自己；{@code ATTACK_TARGET} = 它当前
     *                       锁定的目标（出处 = {@code docs/19} 冰术师「对<b>目标及其周围
     *                       1 格内</b>敌人」，见 {@link AbilityTargets.Center}）。
     *                       {@code null} ⇒ 按 {@code SELF} 处理
     */
    public record Ability(String id,
                          Trigger trigger,
                          Integer cooldownTicks,
                          boolean affectedByTaunt,
                          List<Spell.Effect> effects,
                          AbilityTargets.Alignment targets,
                          Integer radius,
                          boolean includeSelf,
                          AbilityTargets.Center center,
                          Fx fx,
                          Integer searchRadius) {

        /**
         * ★★ <b>一条能力的「看得见」部分</b>（{@code abilities[].fx}）——
         * 2026-10-17 第十三轮收尾新增。
         *
         * <h2>为什么需要它（用户原话）</h2>
         * 「**你没有按照卡牌描述里写的各种粒子效果为卡牌创建特效，而是统一使用了雷击特效。
         * 这样让卡牌既没有区分度也看不出范围。并且，冰术师等卡牌的攻击、雷神登场时的伤害效果，
         * 如果不添加特效是看不出来的，这些卡牌的普通攻击也需要添加特效
         * （雷神登场时需要的闪电特效反而没有了）**」
         * （★ 追问后确认：「**雷神**」= {@code sea_king} 海王）
         *
         * <h2>三个键（都可选）</h2>
         * <ul>
         *   <li>{@code particle}：撒在**每个命中目标**身上（以及范围环上）的粒子名
         *       （如 {@code ELECTRIC_SPARK} / {@code SNOWFLAKE}）；</li>
         *   <li>{@code sound}：触发时播一次的音效键（如 {@code entity.lightning_bolt.thunder}）；</li>
         *   <li>{@code lightning}：{@code true} ⇒ 对**每个命中目标**劈一道
         *       <b>纯视觉</b>闪电（{@code strikeLightningEffect}：不引燃、不造成伤害 ——
         *       伤害早由 {@code DAMAGE} 结算过了）。★ 这是海王那一格点名要的。</li>
         * </ul>
         *
         * <h2>★ 缺省（不写 {@code fx}）时是什么表现</h2>
         * <b>半径 ≥ 0 的能力一律画一圈范围环</b>（{@link #DEFAULT_PARTICLE}）——
         * 理由是用户那句「**看不出范围**」：范围是玩法信息，不该要求每张卡都伸手写一遍。
         * 写了 {@code particle} 则用卡自己的，于是既有默认可见性、又有区分度。
         *
         * @param particle 粒子名（{@code org.bukkit.Particle} 的常量名）；{@code null} = 用默认
         * @param sound    音效键；{@code null} = 不播
         * @param lightning 是否对每个命中目标劈纯视觉闪电
         */
        public record Fx(String particle, String sound, boolean lightning, boolean line) {

            /**
             * ★★★ <b>要不要画「我 → 目标」的直线</b>（键 {@code abilities[].fx.line}，缺省 {@code true}）。
             *
             * <p>2026-10-21 用户裁定（逐字）：「**海王不需要指向敌人的射线粒子**」——
             * 那条线是 {@code AbilityRuntime#playAbilityFx} 里「中心离自己远就画一条光束」的默认行为；
             * 它适合"打出去的投射物"（死灵法师的火球），不适合"在目标身上炸开的电击"。
             * ★ 缺省 {@code true} ⇒ **没写这一格的 50 张卡行为一字未变**。
             */
            public Fx {
            }

            /** 兼容构造（2026-10-21 之前的 3 参形态）：{@code line = true} = 既有行为。 */
            public Fx(String particle, String sound, boolean lightning) {
                this(particle, sound, lightning, true);
            }

            /** 没写 {@code fx} 时的默认粒子（★ 观感默认值，可随时改，登记在 docs/05）。 */
            public static final String DEFAULT_PARTICLE = "END_ROD";

            /** 缺省：只有"画环"的默认行为，不作任何额外表现。 */
            public static final Fx NONE = new Fx(null, null, false, true);

            /** 什么都不用做的判据（画环那条**不**算 —— 它由半径决定）。 */
            public boolean quiet() {
                return particle == null && sound == null && !lightning;
            }

            /** 实际用的粒子名（写了用卡的，没写用默认）。 */
            public String particleOrDefault() {
                return particle == null ? DEFAULT_PARTICLE : particle;
            }
        }

        /** 兼容构造：不写任何表现（{@code fx = NONE}）。 */
        public Ability(String id, Trigger trigger, Integer cooldownTicks, boolean affectedByTaunt,
                       List<Spell.Effect> effects, AbilityTargets.Alignment targets, Integer radius,
                       boolean includeSelf, AbilityTargets.Center center) {
            this(id, trigger, cooldownTicks, affectedByTaunt, effects, targets, radius, includeSelf,
                    center, Fx.NONE, null);
        }

        /** 这条能力的表现块（永不为 {@code null}）。 */
        public Fx fxOrNone() {
            return fx == null ? Fx.NONE : fx;
        }

        /**
         * ★ 兼容构造（不写作用面 / 半径 / 中心）：<b>恰好等于</b>加这些字段之前的行为
         * ——「只打自己」。
         *
         * <p>保留它是为了不改动既有调用点与测试夹具的语义：本次改动之前
         * {@code AbilityRuntime} 只会把效果施加到单个目标（召唤物自己，或
         * {@code GRANT_XP} 的召唤者）⇒ 这个重载就是那个口径。
         */
        public Ability(String id, Trigger trigger, Integer cooldownTicks,
                       boolean affectedByTaunt, List<Spell.Effect> effects) {
            this(id, trigger, cooldownTicks, affectedByTaunt, effects, null, null, false, null,
                    Fx.NONE, null);
        }

        /** 兼容构造（写了作用面 / 半径，但不写中心）：中心 = 自己。 */
        public Ability(String id, Trigger trigger, Integer cooldownTicks,
                       boolean affectedByTaunt, List<Spell.Effect> effects,
                       AbilityTargets.Alignment targets, Integer radius, boolean includeSelf) {
            this(id, trigger, cooldownTicks, affectedByTaunt, effects, targets, radius, includeSelf,
                    null, Fx.NONE, null);
        }

        /** 这个能力声明的半径（{@code null} = 没有范围 ⇒ 只打自己）。 */
        public boolean hasRadius() {
            return radius != null;
        }

        /** 范围的中心（{@code null} ⇒ 自己）。 */
        public AbilityTargets.Center centerOrSelf() {
            return center == null ? AbilityTargets.Center.SELF : center;
        }
    }

    /**
     * 召唤卡专属块。
     *
     * @param groups     召唤物分组（≥1 组）
     * @param placement  落点口径
     * @param taunt      是否带嘲讽 —— ★ **布尔，没有等级**（{@code taunt-level} 会拒绝启动）
     * @param affectedByBuilding ★★ <b>是否「优先攻击建筑」</b>（2026-10-19 起；此前叫
     *        「受嘲讽影响」，键 {@code summon.affected-by-taunt}）。
     *        <ul>
     *          <li><b>配置键</b>：{@code summon.affected-by-building}（**与 {@code taunt} 同层**）；
     *              ★ 旧键 {@code affected-by-taunt} 仍然被接受（等价，且启动时告警一次让运维改名）；</li>
     *          <li><b>{@code false}（缺省）</b> = <b>无视建筑</b>：把建筑当作不存在、只比距离
     *              —— 【Q6】裁定的读法 A，也是 <b>R37「绝大部分召唤物无视优先目标」</b>的落地；</li>
     *          <li><b>{@code true}</b> = <b>先打建筑</b>，其中再比距离（R35 / R36）。</li>
     *        </ul>
     *        ★ 与 {@link Ability#affectedByTaunt()} <b>不是同一个东西</b>：那个是「这条能力
     *        受不受优先目标影响」（R73 的能力维度，键在 {@code abilities[]} 里），
     *        而本字段是「<b>这个召唤物选目标时优不优先打建筑</b>」。
     *        ★ 缺省 {@code false} 是**刻意的**：它让所有没写这一格的卡保持
     *        2026-10-17 之前的既有行为（只比距离），不会因为加了这个键而静默改玩法。
     * @param building ★★ <b>这个召唤物算不算「建筑」</b>（{@code summon.building}，缺省 {@code false}）。
     *
     *        <h3>它是什么</h3>
     *        一条<b>纯标签</b>：只有「谁优先打它」（{@link #affectedByBuilding}）与
     *        「谁不能治疗它」两类判据读它。★ <b>它本身不带任何别的效果</b> ——
     *        不加嘲讽、不加「无法移动」、不加「不会死」。
     *
     *        <h3>为什么必须是一条显式标签（不是从 {@code stationary} 推导）</h3>
     *        用户 2026-10-19 裁定：「**新增 yaml 键**」——因为「无法移动」与「是建筑」
     *        本来就不是同一件事（悦灵不动但它是建筑，而将来完全可能有一张不动却不是建筑的卡）。
     *        ⇒ 从别的键推导会让"再加一张卡"时没人知道它到底算不算建筑。
     *
     *        <h3>出厂哪几张打这条标签（与"目标物"一起构成优先集）</h3>
     *        {@code sentry} / {@code blaze_guard} / {@code blaze_furnace} /
     *        {@code frog_parasite} / {@code immortal_banner} / {@code allay}
     *        （＋ 信标与防御塔，它们由 {@code ObjectiveUnit} 打标）。
     *        ★ 这是**零玩法变化**的关键：改名前「优先攻击带嘲讽的单位」的集合
     *        恰好就是这 6 张（其中 5 张本来写了 {@code taunt: true}），
     *        ⇒ 换成「建筑」之后**集合完全相等**（{@code BuildingSetTest} 钉住）。
     *
     *        <h3>建筑不许永久存在</h3>
     *        用户 2026-10-19：「**一定会让他们不能永久存在**」——那由各卡自己的
     *        {@code lifetime-ticks} / 亡语 / 自灭机制承担，**不是**本标签的责任。
     * @param attributes 属性覆写
     * @param abilities  特殊能力
     * @param equipment  ★ 装备（盔甲 / 手持）—— 2026-10-17 第十轮新增，见 {@link Equipment}
     * @param blockRanged ★★ <b>拦掉这个召唤物的远程攻击</b>（{@code summon.block-ranged}）——
     *        2026-10-17 第十三轮批次 3 收尾新增。出处 = 用户原话：
     *        「**不需要摘掉射箭 goal，只需要拦截射箭事件就行。需要它射箭时的走位**」
     *        （说的就是冰术师：它的原版 AI 会拉弓 → 我们既不要它射出去，
     *        又**要保留**它拉弓时那一套「保持距离」的走位）。
     *        <ul>
     *          <li>{@code false}（缺省）= 照常射（<b>51 张卡里 50 张</b>是这一档）；</li>
     *          <li>{@code true} = 它发出的箭会在 {@code EntityShootBowEvent} 上被取消
     *              ⇒ <b>箭不生成、伤害为 0、而拉弓动作与走位一字不动</b>。</li>
     *        </ul>
     *        ★ 与「摘掉射箭 goal」的差别（★ 这一点是用户明确选的）：
     *        摘 goal 会让它**连走位都没有**（原版「保持距离」是射箭 goal 带的），
     *        而拦事件只拿走"射出去"这一半。
     *        ★ 判据只对**本场账本**里的实体生效（铁律 10）：别人的骷髅照常射箭。
     * @param waxed ★★ <b>召唤出来时给它「涂蜡」</b>（{@code summon.waxed}）——
     *        2026-10-17 批次 3 收尾新增。出处 = {@code docs/19} 第 176 行蒸汽精灵的坑那一栏：
     *        「★ 铜傀儡<b>涂蜡防氧化</b>（{@code CopperGolem#setOxidizing}，API 已核实）」。
     *        <ul>
     *          <li>{@code false}（缺省）= 不碰（51 张卡里 50 张是这一档）；</li>
     *          <li>{@code true} = 生成后立刻 {@code setOxidizing(Oxidizing.waxed())}
     *              ⇒ 它不会随时间氧化（氧化会改它的外观与行为）。</li>
     *        </ul>
     *        ★ 与装备 / 纹饰同一条失败方向：设置失败<b>只告警</b>，绝不让召唤物消失。
     *        ★ 名字取 {@code waxed}（不是 {@code prevent-oxidation}）：它照抄的是**游戏里的动作**
     *        （拿蜂巢给它涂蜡），也是 API 上那个枚举值的名字。
     * @param speedRamp ★★ <b>载具跑「速度斜坡」</b>（{@code summon.speed-ramp}）——
     *        2026-10-17 批次 3 收尾新增（用户第 4 条：「做前储君速度曲线」）。
     *        出处 = {@code docs/19} 第 106 行：「<b>未攻击时马每 2s +20% 速度、上限 100%</b>，
     *        攻击到敌人时清除马的速度加成」（★ 用户裁定「打到信标 / 防御塔**也算**」）。
     *        <ul>
     *          <li>{@code false}（缺省）= 不跑斜坡（51 张卡里 50 张是这一档）；</li>
     *          <li>{@code true} = 这匹**载具**每 2 秒 +20% 移动速度、上限 +100%，
     *              骑手攻击到敌人就清零（状态机 = {@code policy/MountSpeedRamp}，
     *              执行层 = {@code battle/MountSpeedRampService}）。</li>
     *        </ul>
     *        ★ 为什么用**单独的键**而不是"看有没有 movement-speed"：
     *        炽足兽骑士那匹马也有静态倍率（×200%）且也带乘客，但文档**没有**要求它随时间变化
     *        ⇒ 两者必须能分开，否则那条静态度量会被斜坡悄悄接管。
     * @param stationary ★★★ <b>站桩：不移动、也不主动射击</b>（{@code summon.stationary}）——
     *        2026-10-18 新增（用户：「**很多「无法移动」的卡牌的效果依旧没有实现
     *        （比如烈焰熔炉、异蛙寄生体）**」）。
     *
     *        <h3>覆盖哪几张（{@code docs/19} 逐字写了「无法移动」的全部）</h3>
     *        {@code sentry} / {@code frog_parasite} / {@code blaze_furnace} / {@code allay} /
     *        {@code blaze_guard}。
     *
     *        <h3>为什么一个键同时管「不移动」与「不射击」</h3>
     *        这 5 张里有 3 张**同时**要求「无法发射火球」（烈焰守卫 / 烈焰熔炉）或「不攻击」（哨位）。
     *        落地用的是 {@code LivingEntity#setAI(false)} ⇒ AI 停了自然不会寻路、也不会射。
     *        <p>★ 代价（如实登记）：它们也**不再注视**玩家、不播闲晃音效。
     *        它们的伤害/效果全部由 {@code abilities} 的周期任务给，**不依赖原版攻击**。
     *        <p>★ 与 {@code knockback-resistance} 的关系：站桩的怪**按理也该抗推**
     *        （用户 2026-10-18 裁定「一并给 1.0」）—— 但那两个键是**分开**的：
     *        {@code stationary} 管"自己会不会走"，{@code knockback-resistance} 管"别人能不能推它"。
     *        ⇒ 卡表里两个都写（5 张都写了）。
     * @param stopWhenTargetWithin ★★★ <b>「锁定后站定」的距离</b>（{@code summon.stop-when-target-within}）——
     *        2026-10-18 新增（用户第 2 条，逐字：
     *        「**死灵法师锁定后应静止（远程怪跑去近战送）**」）。
     *
     *        <h3>口径</h3>
     *        <ul>
     *          <li><b>{@code null}（缺省）</b>= 没有这一格 ⇒ 老行为（会一直走到目标身边），
     *              50/51 张卡都是这一档；</li>
     *          <li><b>N 格</b>= 只要<b>当前目标</b>在 N 格以内（<b>含边线</b>），这个召唤物就
     *              <b>不再移动</b>（每 tick 取消寻路 + 速度归零），但<b>照常索敌、照常攻击</b>
     *              —— 它的伤害来自 {@code abilities} 的周期效果，不需要贴身；</li>
     *          <li>目标跑出 N 格 / 目标死亡 ⇒ 立刻恢复移动（同一个 tick 生效，不留"卡住"状态）。</li>
     *        </ul>
     *        ★ N 取多少<b>不是我的判断</b>：用户答「<b>N 等于它的火球索敌射程</b>」⇒
     *        死灵法师写 {@code 8}（它就是 {@code radius: 8} 的那个火球），见 {@code docs/03} §4.3。
     *        ★ 与 {@code stationary} 的差别：{@code stationary} 是<b>永久</b>不动
     *        （连眩晕/击退都由玩家自己扛），而本键是<b>按目标距离</b>动态站定 ——
     *        目标远了它要自己走过去，这是"远程怪"该有的机动性。
     */
    public record Summon(List<Group> groups,
                         Placement placement,
                         boolean taunt,
                         boolean affectedByBuilding,
                         Attributes attributes,
                         List<Ability> abilities,
                         Equipment equipment,
                         boolean blockRanged,
                         boolean waxed,
                         boolean speedRamp,
                         boolean stationary,
                         io.mczju.infernalclash.policy.TargetPolicy.Mode targeting,
                         Double targetRadius,
                         RampDamage rampDamage,
                         boolean bomber,
                          Double stopWhenTargetWithin,
                         boolean building,
                         Ray ray,
                         boolean fangAttack,
                         boolean oxidized) {

        /**
         * 规范构造器只做一件事：{@code targeting} 缺省补成 {@code SWITCH}。
         *
         * <p>★ 2026-10-18 新增的三个分量（全部见 {@code docs/03} §4.3）：
         * <ul>
         *   <li>{@code targeting}（键 {@code summon.targeting}）—— 选敌模式。
         *       机制早在 {@code policy/TargetPolicy.Mode} 里就实现了，本轮只是**接线**
         *       （过去 {@code CombatDirector} 把它硬编码成 {@code SWITCH}）；</li>
         *   <li>{@code targetRadius}（键 {@code summon.target-radius}）—— 索敌半径（格），
         *       {@code null} = 不限（既有 50 张卡的行为）；</li>
         *   <li>{@code rampDamage}（键 {@code summon.ramping-damage}）—— 递增伤害，
         *       {@code null} = 没有（既有 50 张卡的行为）。</li>
         * </ul>
         */
        public Summon {
            targeting = targeting == null
                    ? io.mczju.infernalclash.policy.TargetPolicy.Mode.SWITCH : targeting;
        }

        /**
         * 兼容构造（不写「是否受建筑影响」）：缺省 = {@code false} = **无视建筑**（R37）。
         *
         * <p>★ 保留它是为了不改动既有调用点与夹具的语义 ——
         * 2026-10-17 之前的实现就是「一律无视优先目标」，所以这个重载
         * <b>恰好等于</b>加这个字段之前的行为。
         */
        public Summon(List<Group> groups, Placement placement, boolean taunt,
                      Attributes attributes, List<Ability> abilities, Equipment equipment) {
            this(groups, placement, taunt, false, attributes, abilities, equipment,
                    false, false, false, false,
                    io.mczju.infernalclash.policy.TargetPolicy.Mode.SWITCH, null, null, false, null,
                    false,
                    null, false, false);
        }

        /**
         * 兼容构造（不写 {@code block-ranged} / {@code waxed} / {@code speed-ramp}）：
         * 缺省 = **照常射** ＋ **不涂蜡** ＋ **不跑速度斜坡**（50/51 张卡是这一档）。
         */
        public Summon(List<Group> groups, Placement placement, boolean taunt,
                      boolean affectedByBuilding, Attributes attributes, List<Ability> abilities,
                      Equipment equipment) {
            this(groups, placement, taunt, affectedByBuilding, attributes, abilities, equipment,
                    false, false, false, false,
                    io.mczju.infernalclash.policy.TargetPolicy.Mode.SWITCH, null, null, false, null,
                    false,
                    null, false, false);
        }

        /** 兼容构造（不写 {@code waxed} / {@code speed-ramp}）。 */
        public Summon(List<Group> groups, Placement placement, boolean taunt,
                      boolean affectedByBuilding, Attributes attributes, List<Ability> abilities,
                      Equipment equipment, boolean blockRanged) {
            this(groups, placement, taunt, affectedByBuilding, attributes, abilities, equipment,
                    blockRanged, false, false, false,
                    io.mczju.infernalclash.policy.TargetPolicy.Mode.SWITCH, null, null, false, null,
                    false,
                    null, false, false);
        }

        /** 兼容构造（不写 {@code speed-ramp}）。 */
        public Summon(List<Group> groups, Placement placement, boolean taunt,
                      boolean affectedByBuilding, Attributes attributes, List<Ability> abilities,
                      Equipment equipment, boolean blockRanged, boolean waxed) {
            this(groups, placement, taunt, affectedByBuilding, attributes, abilities, equipment,
                    blockRanged, waxed, false, false,
                    io.mczju.infernalclash.policy.TargetPolicy.Mode.SWITCH, null, null, false, null,
                    false,
                    null, false, false);
        }

        /** 兼容构造（不写 {@code stationary}）：缺省 = **会走、会主动攻击**（51 张里 46 张是这一档）。 */
        public Summon(List<Group> groups, Placement placement, boolean taunt,
                      boolean affectedByBuilding, Attributes attributes, List<Ability> abilities,
                      Equipment equipment, boolean blockRanged, boolean waxed,
                      boolean speedRamp) {
            this(groups, placement, taunt, affectedByBuilding, attributes, abilities, equipment,
                    blockRanged, waxed, speedRamp, false,
                    io.mczju.infernalclash.policy.TargetPolicy.Mode.SWITCH, null, null, false, null,
                    false,
                    null, false, false);
        }
    }

    /**
     * ★★★ <b>「穿透射线」攻击</b>（键 {@code summon.ray}）—— 2026-10-21 用户裁定。
     *
     * <h2>用户原话（逐字）</h2>
     * 「我发现这些远程怪难以发挥的原因不是走位问题，而是**武器：弓弩三叉戟太容易丢歪了**。
     * 所以现在需要把**劲弩手**也改成死灵法师同款：**拦截射击行为**（不需要给弩附魔了）并
     * **每 0.5 秒发射从本体引向目标的穿透射线，对所有贯穿的敌人造成 2 点伤害，使用紫色射线粒子，
     * 粒子会在射线路径上生成直到碰到方块才停下来**」。
     *
     * <h2>为什么做成"卡级开关"而不是一条 ability</h2>
     * 同一条行为要落在**两张卡**上：{@code crossbowman}（整张卡只有劫掠者）与
     * {@code ravager_fortress}（劫掠兽 + 背上的劫掠者，**只有乘客**该有射线）
     * ⇒ 与 {@code summon.equipment.groups} 同一套做法：本块可以写
     * {@code groups: [PILLAGER]}，**只给那一组**打上射线。
     *
     * <h2>参数（全部来自用户那句话，没有一个是拟的）</h2>
     * <ul>
     *   <li>{@code damage} —— 每根射线对**每个贯穿的敌人**造成多少伤害（劲弩手 = 2）；</li>
     *   <li>{@code interval-ticks} —— 多久射一次（0.5 秒 = <b>10</b> tick）；</li>
     *   <li>{@code particle} —— 射线粒子名（用户说「紫色射线粒子」⇒ {@code PORTAL}，可改）；</li>
     *   <li>{@code groups} —— **可选**：只给这些实体组（不写 = 整张卡的每一组）。</li>
     * </ul>
     *
     * <p>★ 执行层在 {@code battle/RayAttackService}（每 tick 由 {@code CombatDirector} 驱动）；
     * 射线从**眼睛**射向目标**身体中心**，撞到方块就停在方块前
     * （{@code World#rayTraceBlocks}，铁律 10：它是**有界射线**，不是"查附近实体"）。
     */
    public record Ray(double damage, int intervalTicks, String particle, List<String> groups,
                      int range) {

        /**
         * ★★★ <b>射线的最大长度 = "技能射程"</b>（键 {@code ray.range}，缺省 {@value #DEFAULT_RANGE}）。
         *
         * <p>2026-10-21 用户裁定（逐字）：「**这三张卡释放技能的距离都应该是 8 格**」。
         * ★ 为什么它不能做成 `summon.target-radius`（我第一版就是这么做的，结果用户实机报
         * 「**海王现在只会一直站在自家防御塔这里不动，劲弩手也是**」）：`target-radius` 限制的是
         * **候选索敌**，一收紧就"8 格内没有敌人，没有目标，不移动" ⇒ 单位永远站在出生点。
         * 射程该限制的是**能不能打出去**，不是"能不能发现敌人"。
         */
        public static final int DEFAULT_RANGE = 8;

        /** 兼容构造（2026-10-21 第一版的三/四参形态）：射程缺省 = {@value #DEFAULT_RANGE} 格。 */
        public Ray(double damage, int intervalTicks, String particle, List<String> groups) {
            this(damage, intervalTicks, particle, groups, DEFAULT_RANGE);
        }

        public Ray {
            if (range < 1) {
                throw new IllegalArgumentException(
                        "ray.range 必须 ≥ 1（实际 " + range + "）");
            }
            if (!(damage > 0.0) || Double.isInfinite(damage)) {
                throw new IllegalArgumentException(
                        "ray.damage 必须是有限正数（实际 " + damage + "；0 等于不造成伤害）");
            }
            if (intervalTicks <= 0) {
                throw new IllegalArgumentException(
                        "ray.interval-ticks 必须 ≥ 1（实际 " + intervalTicks + "）");
            }
            groups = groups == null ? List.of() : List.copyOf(groups);
        }

        /** ★ 这一组实体要不要打射线（{@code groups} 为空 ⇒ 整张卡都要）。 */
        public boolean appliesTo(String entityKey) {
            return groups.isEmpty() || groups.contains(entityKey);
        }
    }

    /**
     * ★★★ <b>递增伤害</b>（2026-10-18 新增，键 {@code summon.ramping-damage}）。
     *
     * <h2>出处（{@code docs/19} 第 113 行，烈焰守卫，逐字）</h2>
     * 「锁定后对该目标的伤害**每 0.5s +1**（从 **1** 起、**上限每 0.5s 10 点**），
     * **重新索敌时重置**」。
     *
     * <h2>为什么它不能是一张普通的能力</h2>
     * 普通能力（{@code abilities[]}）每次触发都是**同一个数值**（效果参数写死在卡里）；
     * 而这条要求同一张卡**第 1 次 1 点、第 2 次 2 点…第 10 次起 10 点**，
     * 且「换目标就回到 1」—— 那是**每单位的状态机**，不是一次性的效果参数
     * ⇒ 单独做成卡级机制（纯状态机 {@code policy/RampDamageTrack} ＋ 执行层
     * {@code battle/RampDamageService}），与「坐骑速度斜坡」同一套分层。
     *
     * @param base         第一次的伤害（docs 写「从 1 起」）
     * @param step         每次增加多少（docs 写「每 0.5s +1」）
     * @param max          上限（docs 写「上限每 0.5s 10 点」）
     * @param intervalTicks 每多少 tick 打一次（docs 写「每 0.5s」= 10 tick）
     */
    public record RampDamage(int base, int step, int max, int intervalTicks) {

        public RampDamage {
            if (base < 1) {
                throw new IllegalArgumentException("ramping-damage.base 必须 ≥ 1（实际 " + base + "）");
            }
            if (step < 0) {
                throw new IllegalArgumentException("ramping-damage.step 不能为负（实际 " + step + "）");
            }
            if (max < base) {
                throw new IllegalArgumentException("ramping-damage.max 不能小于 base（"
                        + max + " < " + base + "）—— 那等于「永远打第一档」");
            }
            if (intervalTicks < 1) {
                throw new IllegalArgumentException("ramping-damage.interval-ticks 必须 ≥ 1（实际 "
                        + intervalTicks + "）");
            }
        }
    }

    /**
     * ★★ <b>召唤物的装备</b>（2026-10-17 第十轮新增，缺陷 P1-b：
     * 「<b>铁甲战士没有获得盔甲和武器</b>」）。
     *
     * <h2>文档依据（逐字，{@code docs/19-卡牌总表.md} 第 56 行）</h2>
     * 「| 1 | 铁甲战士 | 僵尸 | 3 | 10s (200) | <b>铁套铁剑</b>，**40 血**，尺寸 ×1.2，
     * **80% 击退抗性** | A | 盔甲<b>不提供防御</b> → 清 {@code ATTRIBUTE_MODIFIERS} |」
     * ⇒ 装备清单 = <b>铁套（铁头盔 / 铁胸甲 / 铁护腿 / 铁靴子）+ 铁剑</b>（"铁套铁剑"逐字拆开）；
     * 而"盔甲不提供防御"这一格在 {@code docs/卡牌策划案.txt} 第 10 行有全局口径：
     * 「<b>此盔甲以及下文所有盔甲和马凯均不提供防御和盔甲韧性数值</b>」
     * ⇒ 这不是逐卡开关，而是**所有装备都要遵守的规则**（见 {@code card/EquipmentApplier}）。
     *
     * <h2>★ 文档没写的部分（不编，登记待裁）</h2>
     * <ul>
     *   <li><b>附魔</b>：{@code docs/19} 没写铁甲战士的剑/甲有附魔 ⇒ <b>一个都不加</b>；</li>
     *   <li><b>80% 击退抗性</b>：数值有（80%），但当前的属性模型
     *       （{@link Attributes} 只有 health / attack-damage / armor / movement-speed /
     *       follow-range / scale）**没有这一格** ⇒ 本轮不实现，登记 {@code docs/27} 的 D6
     *       （落地只需加一个 {@code knockback-resistance} 字段 + 走
     *       {@code Attribute.KNOCKBACK_RESISTANCE}，属"加字段"而不是"猜数值"）。</li>
     * </ul>
     *
     * @param helmet    头盔材料名（原版 Material 名，如 {@code IRON_HELMET}）；{@code null} = 不穿
     * @param chestplate 胸甲材料名
     * @param leggings  护腿材料名
     * @param boots     靴子材料名
     * @param mainHand  主手材料名
     * @param enchantments ★★ <b>逐件附魔</b>（2026-10-17 第十二轮新增）——
     *        键 = 槽位名（{@code helmet / chestplate / leggings / boots / main-hand}），
     *        值 = 「附魔名 → 等级」的保序表。{@code docs/19} 的装备列里含附魔的条目
     *        （「力量 II 弓」「手持力量 V 弩」）走这一格。
     *        ★ 空表 = 没有附魔（不是 {@code null}，避免到处判空）。
     * @param trims ★★ <b>逐件盔甲纹饰</b>（2026-10-17 批次 3 后半新增，{@code docs/24} §10.1
     *        第 4 条用户裁定「做」）—— 键 = 槽位名（同 {@code enchantments}），
     *        值 = 「{@code material} → trim 材料名；{@code pattern} → trim 图案名」。
     *        出处 = {@code docs/19} 的装备列里那些「<b>X 纹样</b>」字样
     *        （「金锭沙丘纹样」「钻石海岸纹样」「紫水晶恼鬼纹样」「铁锭海岸纹样」）：
     *        把「纹样」拆成 **trim 材料**（金锭 / 钻石 / 紫水晶 / 铁锭）+ **trim 图案**
     *        （沙丘 / 海岸 / 恼鬼）两组取值。★ 空表 = 没有纹饰。
     * @param color ★★ <b>皮革染色</b>（2026-10-18 新增，键 {@code equipment.color}）——
     *        用户原话：「**皮革盔甲没有染色**」。
     *        出处 = {@code docs/19} 装备列里的「<b>蓝皮革</b>」「<b>白皮革</b>」「<b>黑皮革</b>」字样：
     *        死灵法师 / 冰术师 = 蓝；虫群术师 = 白；不休之魂 = 黑。
     *        <ul>
     *          <li><b>取值</b>：{@code DyeColor} 的常量名（{@code BLUE} / {@code WHITE} / {@code BLACK}…）；</li>
     *          <li><b>作用范围</b>：**所有染色件**（{@code LEATHER_HELMET} 那四件）—— 同一张卡的
     *              皮革件在文档里总是同色（「蓝皮革盔 / 蓝皮革胸 / 蓝皮革腿」）；</li>
     *          <li>{@code null}（缺省）⇒ 不染色（原版皮革的本色）。</li>
     *        </ul>
     *        ★ 只对 {@code LeatherArmorMeta} 生效；写成铁/钻石件是**合法配置但无效**
     *        （那些件没有颜色分量）⇒ 应用时**不告警**（不是错误，只是没东西可染）。
     */
    public record Equipment(String helmet, String chestplate, String leggings, String boots,
                            String mainHand,
                            Map<String, Map<String, Integer>> enchantments,
                            Map<String, Map<String, String>> trims,
                            String color,
                            List<String> groups) {

        public Equipment {
            enchantments = enchantments == null ? Map.of() : Map.copyOf(enchantments);
            trims = trims == null ? Map.of() : Map.copyOf(trims);
            groups = groups == null ? List.of() : List.copyOf(groups);
        }

        /** 兼容构造：只要那 5 件材料名（没有附魔、没有纹饰、不染色、不限组）。 */
        public Equipment(String helmet, String chestplate, String leggings, String boots,
                         String mainHand) {
            this(helmet, chestplate, leggings, boots, mainHand, Map.of(), Map.of(), null, List.of());
        }

        /** 兼容构造：材料名 + 附魔（没有纹饰、不染色、不限组）。 */
        public Equipment(String helmet, String chestplate, String leggings, String boots,
                         String mainHand, Map<String, Map<String, Integer>> enchantments) {
            this(helmet, chestplate, leggings, boots, mainHand, enchantments, Map.of(), null,
                    List.of());
        }

        /** 兼容构造：材料名 + 附魔 + 纹饰（不染色、不限组）。 */
        public Equipment(String helmet, String chestplate, String leggings, String boots,
                         String mainHand, Map<String, Map<String, Integer>> enchantments,
                         Map<String, Map<String, String>> trims) {
            this(helmet, chestplate, leggings, boots, mainHand, enchantments, trims, null, List.of());
        }

        /** 兼容构造：材料名 + 附魔 + 纹饰 + 皮革颜色（不限组）。 */
        public Equipment(String helmet, String chestplate, String leggings, String boots,
                         String mainHand, Map<String, Map<String, Integer>> enchantments,
                         Map<String, Map<String, String>> trims, String color) {
            this(helmet, chestplate, leggings, boots, mainHand, enchantments, trims, color,
                    List.of());
        }

        /** 什么都不穿（绝大多数卡）。 */
        public static final Equipment NONE =
                new Equipment(null, null, null, null, null, Map.of(), Map.of(), null, List.of());

        /**
         * ★★★ <b>这份装备只给哪几类实体穿</b>（2026-10-18 新增；用户裁「只给僵尸加，马铠不加」）。
         *
         * <h2>为什么需要它（一个真实的模型缺口）</h2>
         * `summon.equipment` 是**逐卡**的 —— 一份装备块会套给该卡的**所有 group**。
         * 而前储君那两张卡（僵尸 + 僵尸马）的装备**分属两个实体**：
         * 「僵尸持铁矛、头戴金头盔」「僵尸马穿金色马铠」。
         * 用逐卡装备表达的话，会出现「僵尸穿上马铠」这种语义错误的配置
         * ⇒ 这就是 {@code docs/05} **E23** 登记的模型缺口。
         *
         * <h2>口径</h2>
         * <ul>
         *   <li><b>空表（缺省）</b> = 该卡的**所有 group** 都穿（51 张里 49 张是这一档，**行为一字未变**）；</li>
         *   <li>非空 = 只有 {@code EntityType} 名在这个表里的实体才穿。</li>
         * </ul>
         * ★ 判据用**实体类型**而不是"第几组"：同一个组可能 spawn 出多种实体（`MobGroupCatalog`），
         * 而"马铠只给僵尸马"这件事本来就该按**实体**说。
         *
         * @return 空 = 不限组
         */
        public List<String> groups() {
            return groups;
        }

        /** 这个实体类型要不要穿这份装备（{@code groups} 为空 ⇒ 谁都穿）。 */
        public boolean appliesTo(String entityTypeName) {
            if (groups.isEmpty()) {
                return true;
            }
            return entityTypeName != null && groups.contains(entityTypeName);
        }

        /** 有没有写任何一件。 */
        public boolean isEmpty() {
            return helmet == null && chestplate == null && leggings == null && boots == null
                    && mainHand == null && enchantments.isEmpty() && trims.isEmpty()
                    && color == null && groups.isEmpty();
        }

        /** 盔甲件数（不含主手）—— 日志用（「铁套」是 4 件）。 */
        public int armorPieces() {
            int count = 0;
            for (String piece : List.of(helmet == null ? "" : helmet,
                    chestplate == null ? "" : chestplate,
                    leggings == null ? "" : leggings,
                    boots == null ? "" : boots)) {
                if (!piece.isEmpty()) {
                    count++;
                }
            }
            return count;
        }

        /** 某个槽位的附魔（没有 ⇒ 空表）。 */
        public Map<String, Integer> enchantmentsOf(String slot) {
            Map<String, Integer> value = enchantments.get(slot);
            return value == null ? Map.of() : value;
        }

        /** 某个槽位的纹饰（没有 ⇒ 空表）。键是 {@code material} / {@code pattern}。 */
        public Map<String, String> trimOf(String slot) {
            Map<String, String> value = trims.get(slot);
            return value == null ? Map.of() : value;
        }
    }

    /** 是否召唤卡。 */
    public boolean isSummon() {
        return type == Type.SUMMON;
    }

    /** 日志/排障用的一行描述。 */
    public String describe() {
        return id + "（" + displayName + " / " + rarity + " / " + cost + " 费 / "
                + cooldownTicks + " tick）";
    }
}
