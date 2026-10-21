package io.mczju.infernalclash.config;

/**
 * ★★★ <b>人机放法术的「怎么放」——四个枚举</b>（2026-10-21 用户裁定）。
 *
 * <h2>出处（用户原话，逐字）</h2>
 * <blockquote>
 * 「3、人机现在需要能够出法术牌，打出的规则（可在配置文件里选择下列枚举中的一个）有：<br>
 * 1、选定场上最接近人机的信标位置的一名敌人（相对于人机的敌人），以这名敌人为中心释放；<br>
 * 2、选定一名随机友方召唤物（不选择防御塔和信标），以其为中心使用；<br>
 * 3、选定一名随机友方召唤物（不选择防御塔和信标），以其位置+朝向释放（用于释放狂风推进、
 * 烈焰扇击这类带有方向性的法术）；<br>
 * 4、选定一名随机友方召唤物（不选择防御塔和信标），对其使用（用于释放治疗法术）」
 * </blockquote>
 *
 * <h2>★★ 作用域：**每张法术卡一条全局规则**（不是每个人机一条）</h2>
 * 用户在下一次问答里当场更正过：「<b>法术卡是每张卡全局规则（比如治疗法术全局都是直接
 * 对召唤物使用，每个人机拿到这张卡都会这样用）</b>」
 * ⇒ 规则落在 {@code config.yml} 的 {@code training.spell-rules}（卡 id → 本枚举），
 * <b>不</b>落在人机卡组里 —— 同一条规则对所有拿到这张卡的人机一起生效。
 *
 * <h2>★ 为什么它不放在 {@code ai/**}</h2>
 * 它是<b>配置值的取值域</b>（`ConfigError` 报错要按它解释），
 * 而 {@code config/**} 必须能离线加载出厂文件（不依赖卡池、不依赖 Bukkit 服务端）。
 * 反过来让 {@code config} 依赖 {@code ai} 会破坏那条约定 ⇒ 放在这。
 * <b>执行侧</b>（{@code ai/BotSpellTargeting}）引用本枚举，依赖方向是 ai → config，单向。
 *
 * <h2>★ 名字里的枚举值就是配置里写的字面量</h2>
 * 配置里写 {@code NEAREST_ENEMY} 这类**大写标识符**（与项目既有配置风格一致，
 * 例如 {@code SQUARE_25} / {@code WAXED_COPPER_BULB}）。解析走 {@link #parse(String)}：
 * 失败返回 {@code null}（不抛异常）—— 「抛不抛」由配置层决定，好让本类也能被纯函数测试复用。
 */
public enum BotSpellRule {

    /**
     * ★ 规则 1：<b>以「离本方信标最近的敌方单位」为中心释放</b>。
     *
     * <p>候选 = 本场账本里**不属于人机这一方**的单位（召唤物 ＋ 防御塔 ＋ 信标；
     * 用户裁定「全部敌方单位」）。参照点 = 人机**自己那一方的信标点位**。
     * 量度 = 三维欧氏距离；并列时按实体 UUID 升序取第一个（保证「同输入 ⇒ 同结果」）。
     * 落点 = 该敌人所在方块；<b>不给单体目标</b>（伤害靠法术形状覆盖）。
     */
    NEAREST_ENEMY,

    /**
     * ★ 规则 2：<b>以一名随机友方召唤物为中心释放</b>。
     *
     * <p>候选 = 本场账本里**本方的、不是目标物的**活着的单位
     * （用户原话「不选择防御塔和信标」⇒ 判据是账本上的 {@code objective} 标记，不按实体类型猜）。
     * 落点 = 它所在方块；<b>不给单体目标</b>。
     */
    RANDOM_ALLY_CENTER,

    /**
     * ★ 规则 3：<b>以一名随机友方召唤物的「位置 ＋ 朝向」释放</b>。
     *
     * <p>用户原话点名用途：「用于释放<b>狂风推进</b>、<b>烈焰扇击</b>这类带有<b>方向性</b>的法术」
     * ⇒ 扇形 / 长方形的铺开方向取**那个召唤物自己的 yaw**（不是人机信标的朝向）。
     */
    RANDOM_ALLY_FACING,

    /**
     * ★ 规则 4：<b>对一名随机友方召唤物「单体使用」</b>。
     *
     * <p>用户原话点名用途：「用于释放<b>治疗</b>法术」。
     * ★ 这一档是四条里**唯一**会把「单体目标」显式交给法术管线的 ——
     * 因为 {@code targeting: ALLY_TARGET} 的卡在玩家侧走的是**准星射线**，
     * 而人机没有玩家、也没有准星（见 {@code docs/31} §5.2）。
     */
    RANDOM_ALLY_TARGET;

    /**
     * 配置里的字面量 → 枚举（<b>忽略大小写与首尾空白</b>）。
     *
     * @return 解析不出来 ⇒ {@code null}（**不抛**：抛不抛由配置层决定）
     */
    public static BotSpellRule parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        for (BotSpellRule rule : values()) {
            if (rule.name().equalsIgnoreCase(text)) {
                return rule;
            }
        }
        return null;
    }

    /** 配置里的合法取值（报错文案用）。 */
    public static java.util.List<String> names() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (BotSpellRule rule : values()) {
            names.add(rule.name());
        }
        return java.util.List.copyOf(names);
    }

    /** 这一档要不要「一个具体的友方召唤物」（三条 RANDOM_ALLY_* 要，规则 1 不要）。 */
    public boolean needsAllySummon() {
        return this != NEAREST_ENEMY;
    }

    /** 这一档要不要把那个友方召唤物当作<b>单体目标</b>交下去（只有规则 4）。 */
    public boolean needsSingleAllyTarget() {
        return this == RANDOM_ALLY_TARGET;
    }

    /** 这一档要不要用那个召唤物的<b>朝向</b>（只有规则 3）。 */
    public boolean usesTargetFacing() {
        return this == RANDOM_ALLY_FACING;
    }

    /**
     * ★★ <b>这一档规则与某种 {@code targeting} 是否「能工作」</b>（纯判据，装载期用）。
     *
     * <h2>为什么不在这里直接读 {@code card/Spell.Targeting}</h2>
     * {@code config/**} 刻意不依赖卡牌层（见类注释）⇒ 参数是一个<b>布尔事实</b>：
     * 「这张卡的 {@code targeting} 是不是 {@code ALLY_TARGET}」。装配期从卡对象上取、
     * 传进来。这样判据只有一处，而配置层仍能离线加载。
     *
     * <h2>口径（目前只有一条不兼容）</h2>
     * {@code ALLY_TARGET} 的卡（现只有 {@code healing}）**只能**配
     * {@link #RANDOM_ALLY_TARGET}：另外三条只提供「落点」，
     * 而这类法术的目标来自「准星上的友方」—— 人机那条路上它永远为空
     * ⇒ 配成前三条的表现是「每局白丢一个槽位」，必须在装载期拒绝。
     *
     * @param allySingleTargetCard 这张卡的 {@code targeting == ALLY_TARGET}
     */
    public boolean compatibleWith(boolean allySingleTargetCard) {
        return !allySingleTargetCard || this == RANDOM_ALLY_TARGET;
    }
}
