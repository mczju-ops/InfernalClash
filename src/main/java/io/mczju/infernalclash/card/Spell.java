package io.mczju.infernalclash.card;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Map;

/**
 * 法术卡的专属块 —— **三层结构：目标 → 形状 → 效果**（{@code docs/07} §6.1）。
 *
 * <p>为什么拆三层：R74a 的「圆形 / 长方形 / 扇形」是**形状**，R74b 的
 * 「敌方召唤物 / 我方召唤物 / 敌方召唤师 / 自身」是**目标限定**，R32 的
 * 「伤害 / 强化 / 治疗 / 持续召唤」是**效果** —— 三者**正交**
 * （一个扇形范围可以带治疗，也可以带伤害），拆开后**配置可复用、校验可穷举**。
 *
 * @param targeting 怎么选目标（R30/R31/R74b）
 * @param shape     作用范围（R74a）
 * @param effects   施加什么（R32/R73）
 * @param fx        ★ 观感（粒子 / 音效）；{@code null} = 不播——见 {@link Fx}
 * @param select    ★★ <b>形状已覆盖的那些目标里，再挑哪几个</b>（2026-10-17 批次 3 收尾新增）；
 *                  {@code null} = 全都要（{@link Select#ALL}，既有行为）
 */
public record Spell(Targeting targeting, Shape shape, List<Effect> effects, Fx fx,
                    Select select, Scope scope) {

    /**
     * ★★★ <b>「形状覆盖到的敌方实体里，准哪几类进来」</b> —— 1.1.0 新增（大旋风 / 凋灵领域）。
     *
     * <h2>为什么需要它（两张 1.1.0 法术的判据不一样）</h2>
     * 「形状覆盖的格」里的敌方实体在本项目里有**两类**：①召唤物（含建筑类）
     * ②<b>目标物载体</b>（防御塔 / 信标，见 {@code UnitRegistry#KEY_OBJECTIVE}）。
     * 而 {@code docs/1.1卡牌.txt} 对这两张新卡的写法不同：
     * <ul>
     *   <li><b>凋灵领域</b>：用户 2026-10-26 明确裁定「<b>敌方一切：召唤物 + 建筑类召唤物 +
     *       防御塔 + 信标</b>」⇒ {@link #ALL}（默认）；</li>
     *   <li><b>大旋风</b>：用户裁定「<b>敌方召唤物（含建筑类召唤物），不含防御塔/信标，不含玩家</b>」
     *       ⇒ {@link #SUMMONS}。</li>
     * </ul>
     *
     * <h2>为什么做在「法术层」而不是「效果层」</h2>
     * 与 {@link Select} 同一条理由：同一次施放的**所有**效果（伤害 / 牵引 / 药水）必须打
     * <b>同一批</b>目标，否则会出现"被拉走却没掉血"这种没人能查的错。
     *
     * <p>★ 缺省 {@link #ALL} ⇒ 既有 15 张法术卡行为**一字未变**（它们从来没写过这个键）。
     */
    public enum Scope {
        /** 形状内的**所有**敌方实体（召唤物 ＋ 目标物载体）—— 既有行为。 */
        ALL,
        /** 只要**敌方召唤物**（排除防御塔 / 信标本体）—— 1.1.0「大旋风」。 */
        SUMMONS
    }

    /** 兼容构造（1.1.0 的 {@code spell.scope} 之前的五参形态）：缺省 {@link Scope#ALL}。 */
    public Spell(Targeting targeting, Shape shape, List<Effect> effects, Fx fx, Select select) {
        this(targeting, shape, effects, fx, select, Scope.ALL);
    }

    /** 规范构造器：{@code scope} 缺省补成 {@link Scope#ALL}。 */
    public Spell {
        scope = scope == null ? Scope.ALL : scope;
    }

    /** 这一档要不要把「目标物载体」（防御塔 / 信标）算进来。 */
    public boolean includesObjectives() {
        return scope == Scope.ALL;
    }

    /**
     * ★★ <b>「范围内全都要」之外的那一档：按规则再挑</b> —— 2026-10-17 批次 3 收尾新增。
     *
     * <h2>它补的是哪一格</h2>
     * {@code docs/19} 第 136 行（暴风雨）原文：
     * 「取范围内<b>血量最高的三名</b>敌人，<b>依次</b>造成三次雷击，各 28 点伤害 + 眩晕 2s」。
     * 这是一条<b>选敌规则</b>，而在此之前 {@code SpellCaster} 只会把形状覆盖到的
     * <b>所有</b>目标都打一遍 ⇒ 那张卡的注释里写着「在 schema 里无对应键 ⇒ 未写」。
     *
     * <h2>★ 为什么做在「法术层」而不是「效果层」</h2>
     * 因为它是<b>同一次施放</b>里对所有效果都生效的选择：
     * 暴风雨的两条效果（{@code DAMAGE} + {@code STUN}）打的是<b>同一批</b>目标。
     * 若做成效果参数，就得在每条效果上各写一次，两条一旦不一致
     * （一边挑前三、一边打全部）就会出现「第四名也挨了眩晕但没掉血」这种没人能查的错
     * —— 那正是本项目最反感的「第二处事实来源」形状。
     *
     * @param rule  规则（目前只有 {@link Rule#HIGHEST_HEALTH}）
     * @param count 取前几名（≥ 1）
     */
    public record Select(Rule rule, int count) {

        /** 选敌规则。 */
        public enum Rule {
            /**
             * 按<b>当前血量从高到低</b>排序取前 {@code count} 名。
             *
             * <p>★ 「血量」= 实体**当前**生命值（不是最大生命值）——
             * 文档原文是「血量最高的三名敌人」，而它出现在一条"补刀/清场"式的法术上
             * （打最肥的那几个）⇒ 取当前值。
             */
            HIGHEST_HEALTH
        }
    }

    /** 兼容构造（不写 `select`）：一律 {@code ALL} = **全都要**（既有行为）。 */
    public Spell(Targeting targeting, Shape shape, List<Effect> effects, Fx fx) {
        this(targeting, shape, effects, fx, null);
    }

    /** 这一档选择是不是「全都要」（{@code null} / {@code ALL} 都算）。 */
    public boolean selectsAll() {
        return select == null;
    }

    /**
     * ★★ <b>法术的观感</b>（2026-10-17 第十轮新增，缺陷 P1-a 的「无特效」那一半）。
     *
     * <h2>为什么必须补上它（文档要求 vs 实现）</h2>
     * {@code docs/19-卡牌总表.md} 给**每一张法术卡**都写了「特效」一列，逐字举例：
     * <ul>
     *   <li>第 69 行（万箭齐发）：「<b>打击粒子环 + 箭矢命中声</b>」；</li>
     *   <li>第 70 行（火球术）：「<b>火焰粒子环 + 烈焰人火球声</b>」。</li>
     * </ul>
     * 而第十轮之前，出厂 {@code cards/fireball.yml} 的注释自己写着
     * 「火焰粒子环 / 烈焰人火球声是纯观感，<b>没有字段可写</b>」⇒ 于是玩家看到的是
     * 「释放后没有特效」（用户原话，{@code docs/24} §7.1）。本记录就是那个缺失的字段。
     *
     * <h2>★ 具体取值是我按文档字面取的（可否决，已登记）</h2>
     * {@code docs/19} 只给了**语义**（「火焰粒子」「打击粒子」「烈焰人火球声」「箭矢命中声」），
     * 没给原版常量名 ⇒ 我按字面取了最贴切的原版常量
     * （{@code FLAME} / {@code ENCHANTED_HIT} / {@code ENTITY_BLAZE_SHOOT} / {@code ENTITY_ARROW_HIT}），
     * 并登记 {@code docs/05}（待裁定）与 {@code docs/27}（台账）。
     *
     * @param particle 原版粒子名（{@code Particle} 的常量名，如 {@code FLAME}）；
     *                 ★ 装载期用 {@code Particle.valueOf} 校验（26.2 里它仍是枚举 ⇒ 离线也能校验）
     * @param sound    原版音效键（如 {@code entity.blaze.shoot}）；{@code null} / 空白 = 不播音效。
     *                 ★ **装载期只做格式校验**（注册表要有服务端才能查）⇒ 见 {@code CardCatalog}
     * @param block ★★ <b>方块粒子</b>（2026-10-18 新增；用户：「**掘墓人依旧缺失特效**」）——
     *        原版方块材质名（如 {@code DIRT} / {@code GRAVEL}）。
     *
     *        <h3>为什么必须有它（一次真实的疏漏）</h3>
     *        上一轮给 `grave_digger` 写的是 {@code particle: ITEM_SNOWBALL}（想表示"碎屑"），
     *        但 {@code ITEM_*} 与 {@code BLOCK} 这类粒子**需要 data 参数**
     *        （{@code spawnParticle(Particle, x,y,z, count, dx,dy,dz, speed, data)}）
     *        —— 不传 data 时**客户端根本不渲染**。
     *        ⇒ 那行配置"写对了、装得进去、也不报错，但屏幕上什么都没有"。
     *        ★ 这正是"看起来实现了、实际没效果"的那一类，只能靠实机发现。
     *
     *        <h3>口径</h3>
     *        写了 {@code block} ⇒ 用 {@code Particle.BLOCK} + 这个方块的 {@code BlockData} 撒粒子
     *        （★ 此时 {@code particle} **不再使用**）；不写 ⇒ 沿用 {@code particle} 那条路。
     *        出处 = {@code docs/19} 第 183 行：「召唤前**持续**放**泥土**与实体破碎粒子」。
     *        ★ 注意别在这里写「**加粗**／斜杠」那种连写：`*` `*` `/` 会被当成注释结束符。
     */
    public record Fx(String particle, String sound, String block, boolean lightning,
                     String pillar, int pillarTicks) {

        /**
         * 兼容构造（2026-10-21 之前的四参形态）：没有发射光柱。
         */
        public Fx(String particle, String sound, String block, boolean lightning) {
            this(particle, sound, block, lightning, null, 0);
        }

        /** 兼容构造：只有粒子与音效（绝大多数卡）。 */
        public Fx(String particle, String sound) {
            this(particle, sound, null, false);
        }

        /** 兼容构造：粒子 / 音效 / 方块粒子（2026-10-18 之前的三参形态）。 */
        public Fx(String particle, String sound, String block) {
            this(particle, sound, block, false);
        }

        /**
         * 有没有方块粒子。
         *
         * <p>★ 只写 {@code block} ⇒ 方块粒子沿范围**边界**撒，{@code particle} 不使用
         * （掘墓者的既有行为）；★★ 1.1.0 起**两者可同时写** ⇒ 方块粒子铺**环内区域**、
         * {@code particle} 画**边界环**（地壳震击：「火焰粒子环 + 在环内区域持续产生泥土破碎粒子」）。
         */
        public boolean hasBlockParticle() {
            return block != null && !block.isBlank();
        }

        /**
         * ★★★ <b>要不要在目标头上劈一道「纯视觉闪电」</b>
         * （2026-10-18 新增，键 {@code spell.fx.lightning}）。
         *
         * <h2>出处（用户裁定，逐字）</h2>
         * 「**暴风雨的落雷+海王的落泪（雷）；其他全去掉**」。
         *
         * <h2>它替掉了什么（一个真实的分叉）</h2>
         * 2026-10-17 第十三轮把判据写成「<b>这张法术有 {@code DAMAGE} 效果</b>就劈闪电」，
         * 理由是"将来再加落雷类法术时它自动就有闪电"。实机结果是
         * <b>14 张伤害法术全都在劈闪电</b> —— 火球术、万箭齐发、落雷之外的每一张都带着
         * 一道没有出处的闪电，用户的原话就是「**法术牌依旧保留着雷击特效，不需要这个**」。
         *
         * <p>⇒ 改回<b>数据驱动</b>：闪电是**卡自己声明的观感**（`fx.lightning: true`），
         * 而不是从"有伤害"推测出来的。默认 {@code false} ⇒ 不写这个键的卡**行为不变地没有闪电**。
         * ★ 「海王的落泪」那条走的是**能力**级 {@code abilities[].fx.lightning}（另一条链路），
         * 不经过这里 —— 所以本字段只需要暴风雨一张卡写 {@code true}。
         */
        public boolean hasLightning() {
            return lightning;
        }
    }

    /** 目标选择模式（{@code docs/07} §6.2）。 */
    public enum Targeting {
        /** 地面指向（不需要准星）。 */
        GROUND,
        /** 自身（不需要准星）。 */
        SELF,
        /** 我方召唤物 / 单体（不需要准星）。 */
        ALLY,
        /**
         * ★★★ <b>我方单体，但**必须用准星指着**</b>（2026-10-18 新增；治愈术专用）。
         *
         * <h2>出处（用户裁定，逐字）</h2>
         * 「**必须用准星指着友方，如果没有选到目标就不使用，不消耗费用和冷却**」。
         *
         * <h2>为什么不能直接用 {@link #ALLY}</h2>
         * {@code ALLY} 的口径是「**形状覆盖的格里**所有同阵营单位」（来自账本）——
         * 它不读准星。治愈术写着 {@code ALLY} + `origin: TARGET` 时的实际行为是：
         * 玩家对着**空气**右键 ⇒ 落点退化成自己脚下 ⇒ 自己脚下没有友方召唤物 ⇒
         * <b>花费照扣、冷却照上、屏幕上什么都没有</b>
         * （这就是用户报的「治愈术根本没有效果」的一半；另一半见
         * {@code spell/EffectApplier#heal} 那条「亡灵不治」的错继承）。
         * ⇒ 新增这一档：<b>准星上必须有友方单位</b>，否则施放**在扣费之前**就被拒。
         */
        ALLY_TARGET,
        /** 敌方单体（**需要准星**）。 */
        ENEMY_TARGET,
        /** 任意单体（**需要准星**）。 */
        ANY_TARGET,
        /** 无目标。 */
        NONE;

        /**
         * ★ 这一档是不是「必须有准星目标」。
         *
         * <p>★ 只回答"要不要准星"，<b>不回答</b>"准星上必须是友方还是敌方" —— 后者是
         * {@link #ALLY_TARGET} 独有的（判据在纯类 {@code spell/AimRule}）。
         */
        public boolean needsCrosshair() {
            return this == ALLY_TARGET || this == ENEMY_TARGET || this == ANY_TARGET;
        }
    }

    /** 形状类型（R74a）。 */
    public enum ShapeType {
        /** 圆形：`radius`（格）。 */
        CIRCLE,
        /** 长方形：`width` × `length`。 */
        RECTANGLE,
        /** 扇形：`radius` + `angle`（度）。 */
        CONE,
        /** 单体：无参数。 */
        SINGLE
    }

    /**
     * 形状原点（【Q27 已裁定】用户原话：「玩家对空气使用时落点就是玩家处，
     * 瞄准格子时就是瞄准的那一格」）。
     */
    public enum Origin {
        /** 对空气 → 玩家处。 */
        SELF,
        /** 瞄格子 → 瞄准的那一格。 */
        IMPACT,
        /** 以单体目标为源点（保留给嵌套法术）。 */
        TARGET
    }

    /**
     * 形状。
     *
     * @param type   形状类型
     * @param radius 圆/扇形的半径（格）；其余为 {@code null}
     * @param width  长方形宽度；其余为 {@code null}
     * @param length 长方形长度；其余为 {@code null}
     * @param angle  扇形张角（度）；其余为 {@code null}
     * @param origin 源点（【Q27】）
     */
    public record Shape(ShapeType type,
                        Double radius,
                        Double width,
                        Double length,
                        Double angle,
                        Origin origin) {
    }

    /**
     * 效果类型。
     *
     * <p>前七项来自 {@code docs/07} §6.4（R32 + R73 合并）；**后七项是 P1 落地 51 张卡时
     * 补出来的** —— 因为真实的卡表里有眩晕、点燃、击退、持续推动、刷新冷却、给经验、
     * 假死复活这些语义，原来的七类覆盖不了（这是「先文档再代码」在本轮暴露出的缺口，
     * 已同步进 {@code docs/07} §6.4 与 {@code docs/03} §4.4）。
     */
    public enum EffectType {
        /** 伤害：`amount`（+ 可选 `damage-type`）。 */
        DAMAGE,
        /** 治疗：`amount`（R32）。 */
        HEAL,
        /** 药水效果：`effect` + `amplifier` + `duration-ticks`（R73）。 */
        POTION,
        /** 属性改变：`attribute` + `amount` + `duration-ticks`（R74a）。 */
        ATTRIBUTE,
        /** 立即召唤（等同召唤卡的 `summon:` 块）。 */
        SUMMON,
        /** ★ 延时召唤（R74c：「x 秒后召唤」）：`group` + `count` + `delay-ticks`。 */
        SUMMON_DELAYED,
        /**
         * ★★ <b>周期召唤</b>（2026-10-18 新增）：
         * {@code group} + {@code count} + {@code interval-ticks} + {@code duration-ticks}
         * + 可选 {@code radius}。
         *
         * <h2>出处（用户原话）</h2>
         * 「**信息素需要的就是在范围内持续进行随机位置的周期召唤。
         * 如果难以实现可以降级成在释放坐标处持续召唤**」
         * ＋ {@code docs/19} 第 194 行：「以自身或准星所指的一格为中心，半径 <b>5</b> 格，
         * 持续 <b>20</b> 秒；期间<b>每秒随机选一格</b>，召唤一只 <b>2 点血量</b>的蠹虫」。
         *
         * <h2>为什么必须是新类型（而不是 `SUMMON_DELAYED` 加参数）</h2>
         * {@code SUMMON_DELAYED} 的语义是「<b>等 N tick 后召唤一次</b>」——
         * 它的 {@code count} 是"这一次几只"。而本类型是「<b>每隔 N tick 召唤一次，共持续 M tick</b>」，
         * 两个 `count` 含义完全不同。混在一个类型里，卡表写 `count: 20` 就再也说不清
         * 是"一次 20 只"还是"20 次"—— 而这两种解释我在上一轮**都见过**
         * （`pheromone` 的注释写的是"20 秒 × 每秒 1 只"，而代码读的是"一次 20 只"）。
         * <p>★ 键的语义（逐条）：
         * <ul>
         *   <li>{@code count} —— <b>每一次</b>召唤几只（通常是 1）；</li>
         *   <li>{@code interval-ticks} —— 每隔多少 tick 召唤一次；</li>
         *   <li>{@code duration-ticks} —— 总共持续多久（次数 = {@code duration / interval}）；</li>
         *   <li>{@code radius} —— <b>可选</b>：每次在落点周围这个半径内**随机**选位置；
         *       不写（或 0）= 每次都召唤在落点原地（★ 那就是用户给的降级方案）。</li>
         * </ul>
         */
        SUMMON_PERIODICALLY,
        /** 法术嵌套（R74b 的 execute 式；【Q26】**深度上限 1**）：`spell` + `at`。 */
        CAST_SPELL,
        /**
         * ★★★ <b>复读：把本局「这一方上一张打出的牌」再打一次</b>（「回响」，2026-10-19）。
         *
         * <p>无参数。★ <b>它不在 {@code EffectApplier} 里结算</b> —— 语义是"再施放一张卡"，
         * 而"施放一张卡"要过落点 / 半场 / 召唤生成（{@code SummonSpawner}），
         * 那些都在 {@code game/CardCastListener} 手上，{@code EffectApplier} 拿不到。
         * ⇒ 本效果的作用是**给施放入口一个可判定的标记**（`spell.effects` 里出现它 ⇒ 走复读分支），
         * 由 {@code CardCastListener} 在**进法术管线之前**接住（与 {@code CAST_SPELL} 的注入式不同）。
         *
         * <p>★ 为什么不用"一个没有效果的 {@code spell}"当标记：`parseEffects` 要求
         * `effects` **至少一项**（写空列表在装载期就拒绝）—— 与其为一张卡放松那条校验，
         * 不如让标记本身是一个**列在效果类型表里**的项。
         */
        REPLAY_LAST_CARD,
        /** ★ 眩晕：`duration-ticks`（冰晶坠 / 暴风雨 / 海王）。 */
        STUN,
        /** ★ 点燃：`duration-ticks`（烈焰扇击 / 熔岩精灵）。 */
        IGNITE,
        /** ★ 击退：`strength`（火球术 / 定点爆破）。 */
        KNOCKBACK,
        /** ★ 持续推动：`duration-ticks`（+ 可选 `strength`）——「狂风推进」。 */
        PUSH,
        /** ★ 刷新**本卡**冷却：无参数（「不休之魂」的死亡刷新）。 */
        REFRESH_COOLDOWN,
        /** ★ 给召唤者加经验：`amount`（「悦灵」每 5s 给 1 级）。 */
        GRANT_XP,
        /** ★ 假死复活一次：可选 `health`（「美西螈」）。 */
        REVIVE,
        /** ★ 玩家卡牌冷却减少：`percent` + `duration-ticks`（「战术冷却」；【Q10】的通道①）。 */
        COOLDOWN_REDUCTION,
        /**
         * ★★★ <b>在目标处召唤原版「尖牙」实体</b>（{@code EvokerFangs}）—— 2026-10-21 用户裁定。
         *
         * <p>出处（逐字）：「唤魔者（原版的尖牙很容易空）：**拦截召唤尖牙行为**，
         * 每 3 秒先使目标眩晕 1 秒，**同时在目标处召唤尖牙**（最好能调用原版唤魔者召唤尖牙的行为）」。
         *
         * <p>★ 「原版尖牙」= {@code org.bukkit.entity.EvokerFangs}（26.2 的 Bukkit API 真的有，
         * `javap` 实测：`getOwner/setOwner/getAttackDelay/setAttackDelay`）⇒
         * 我们**不自己做伤害模型**，只 spawn 那个原版实体、把 owner 指向召唤者，
         * 咬人的时机/动画/音效/伤害全部沿用原版。
         *
         * <p>★ 无参数（{@code count} 由"目标个数"决定：一个目标一根尖牙）。
         */
        SPAWN_FANGS,
        /**
         * ★★★ <b>把范围内的敌人往中心「牵引」</b>（1.1.0「大旋风」）。
         *
         * <p>出处（{@code docs/1.1卡牌.txt}）：「将半径 <b>6</b> 格内的所有敌人向法术释放的位置
         * 牵引，<b>每秒造成 2 点伤害，持续 2 秒</b>」＋「<b>牵引需要能够作用于带击退抗性的召唤物</b>」。
         *
         * <p>参数：{@code duration-ticks}（牵引时长，40）/
         * {@code amount}（每次伤害）/ {@code interval-ticks}（伤害节拍，20 = 每秒一次）/
         * 可选 {@code strength}（每 tick 朝中心的速度，缺省 0.35）。
         * <ul>
         *   <li>★ <b>每 tick</b>重申朝中心的速度（与 {@code PUSH} 同一个理由：原版每 tick 把速度往 0 收）；</li>
         *   <li>★★ <b>刻意不读击退抗性</b> —— 用户点名要能拉动「带击退抗性的召唤物」；
         *       目标物（防御塔 / 信标）不在这张卡的目标集里（{@code spell.scope: SUMMONS}），
         *       所以"拉不拉得动塔"这个问题在配置层就不存在；</li>
         *   <li>★ 伤害不是"每秒一次全体"而是<b>按目标各记一条节拍</b>（每个被拉住的单位自己数）。</li>
         * </ul>
         */
        PULL,
        /**
         * ★★★ <b>持续区域伤害</b>（1.1.0「凋灵领域」）。
         *
         * <p>出处（{@code docs/1.1卡牌.txt}）：「持续 <b>8</b> 秒，<b>每秒</b>对半径 <b>5</b> 格范围内
         * 所有召唤物造成 <b>5</b> 点伤害」＋「不<b>需要任何药水效果</b>…只造成伤害」。
         *
         * <p>参数：{@code duration-ticks}（160）/ {@code interval-ticks}（20）/
         * {@code amount}（5）/ {@code radius}（5）。
         * <ul>
         *   <li>★★ <b>一次施放只跑一份任务</b>（不是"每个目标各跑一份"）：它每拍从**账本**重新
         *       收集范围内的敌方实体 ⇒ 施放后才走进来的单位也会挨打（这是"领域"该有的行为）；</li>
         *   <li>★ 不含任何 {@code POTION} 效果（用户点名"只用粒子云表现凋零，不挂凋零效果"）；</li>
         *   <li>★ 归属判据与其它法术一致：只打<b>敌方</b>；要不要打目标物由 {@code spell.scope} 决定。</li>
         * </ul>
         */
        AREA_DAMAGE,
        /**
         * ★★ <b>在目标脚下结蜘蛛网</b>（1.1.0「囚笼编织者」）。
         *
         * <p>出处：「该蜘蛛会在<b>攻击目标处结网</b>，网会存在 <b>8 秒</b>」。
         * 参数：{@code duration-ticks}（160）。★ 能不能替换某一格由纯类
         * {@code policy/WebBlockRule} 判（只碰空气与软方块，岩浆/石头/地板不碰）。
         * 执行层在 {@code spell/WebField}（生成 → 登记 → 到期还原 / 对局收尾强制还原）。
         */
        PLACE_WEB,
        /**
         * ★★★ <b>「援军」的标记</b>（1.1.0）—— 与 {@link #REPLAY_LAST_CARD} 同一个套路。
         *
         * <p>出处（{@code docs/1.1卡牌.txt}）：「在对战开始时，以及使用本卡牌后，记录你使用的下一张牌。
         * 使用时，<b>重置记录的卡牌的冷却时间</b>，然后清空记录」。
         *
         * <p>★ <b>它不在 {@code EffectApplier} 里结算</b>：重置冷却要读 {@code CardCooldowns}、
         * 清遮罩要读玩家手上的卡物品 —— 那些都在 {@code game/CardCastListener} 手上。
         * ⇒ 本效果只是给施放入口一个<b>可判定的标记</b>（与回响完全同构），
         * 由 {@code CardCastListener} 在进法术管线**之前**接住。
         */
        REINFORCEMENT
    }

    /**
     * 一个效果。
     *
     * <p>★ 参数保留为**原始键值对**（{@link ConfigurationSection#getValues(boolean)}），
     * 因为七类效果各有各的键；逐类解构会造出七个只为装载而存在的类。
     * **必需键已在加载期逐类校验过**（见 {@code CardCatalog}），所以这里
     * 只负责「取值」，不负责"键在不在"。
     *
     * @param type   效果类型
     * @param params 原始参数（嵌套段落会是 {@link ConfigurationSection}）
     */
    public record Effect(EffectType type, Map<String, Object> params) {

        /** 取数字参数（YAML 的整数与浮点都接受）；缺失或类型不符返回 {@code null}。 */
        public Double number(String key) {
            Object value = params.get(key);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            return null;
        }

        /** 取字符串参数；缺失返回 {@code null}。 */
        public String text(String key) {
            Object value = params.get(key);
            return value instanceof String text ? text : null;
        }

        /** 取嵌套段落（如 `SUMMON` 的召唤块）；缺失返回 {@code null}。 */
        public ConfigurationSection section(String key) {
            Object value = params.get(key);
            return value instanceof ConfigurationSection section ? section : null;
        }
    }
}
