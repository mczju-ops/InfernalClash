package io.mczju.infernalclash.config;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * {@code config.yml} 的 {@code combat} 段（P5 索敌与战斗）。
 *
 * <h2>★ 本段目前**只有一个键**</h2>
 * {@code combat.target-refresh-ticks} —— 每多少个 tick 为每个召唤物重算一次目标
 * （**出厂 10 tick = 0.5 秒**，R34 + 2026-10-15 用户裁定，{@code docs/02} §8.1 / {@code docs/03} §3.2）。
 *
 * <p>这个间隔**是 T4 修正方案的一部分**：InfernalGuess 的 {@code EnemyTargetGoal}
 * 只在目标失效时才重选 ⇒「生物 A 追着 B 打，更近的 C 出现也不换」（用户 R36 抱怨的行为）。
 * 本项目每个刷新周期重算，所以**这个键写错了，T4 修复就等于没做**。
 *
 * <h2>★★ 本段必须**拒绝** {@code taunt-mode}（它不是"未知键"，是"已作废的键"）</h2>
 * {@code docs/03} §3.2 明确：2026-10-15 用户裁定「**嘲讽没有等级**，只有有/无嘲讽的区别」
 * ⇒ 原 {@code taunt-mode}（嘲讽等级语义的选择键）**已作废**，嘲讽改成
 * {@code cards} 里的**布尔** {@code taunt}（信标 / 塔为 {@code taunt: true}）。
 *
 * <p>对作废键**必须拒绝启动**，因为静默忽略它的后果是运维以为「嘲讽模式配好了」——
 * 而实际生效的是另一套语义（{@code TargetPolicy} 只排序布尔，与"等级"无关）。
 * 这类「配置写了却没生效」正是 {@code AGENTS.md} §4.1 反复警告的事故类型。
 * ⇒ 本类给该键一条**专门的**报错（说清"为什么作废 + 现在该改哪里"），
 * 其余未知键走 {@link #rejectUnknownKeys} 的通用报错。
 *
 * <h2>★ 与 {@link MatchConfig} / {@link ObjectiveConfig} 的分工</h2>
 * 三者读**同一个已解析的根节点**（{@link #load(ConfigurationSection)}），不重复读盘；
 * 分开是因为它们回答三个不同的问题：一个是「一局怎么走」（时间轴）、
 * 一个是「目标物是什么」（塔与信标）、本类是「战斗时怎么索敌」。
 * 合并会让改战斗参数与改玩法数值在代码里互相牵连。
 */
public record CombatConfig(int targetRefreshTicks, boolean playerTargetBan) {

    /** 本配置所在的文件名（{@code combat} 段在 {@code config.yml} 里）。 */
    public static final String FILE = "config.yml";

    /** 键路径前缀（报错一律给**从根算起**的完整键路径，见 {@code AGENTS.md} §3）。 */
    private static final String SECTION_PATH = "combat";

    /** 目标刷新间隔的键名（相对 {@code combat} 段）。 */
    public static final String KEY_TARGET_REFRESH_TICKS = "target-refresh-ticks";

    /**
     * ★★★ <b>「把玩家设成无敌 ⇒ 怪物不索敌玩家」的总开关</b>（{@code combat.player-target-ban}）。
     *
     * <h2>为什么它是配置而不是常量（用户 2026-10-26 逐字）</h2>
     * 「`PlayerTargetBan.ENABLED` 现在是硬编码常量，建议提成 `config.yml` 开关，
     * 方便切换 PvE 场景。」
     *
     * <h2>★ 关掉它会怎样（必须说清，否则运维会误解）</h2>
     * 关掉 ⇒ **不再**给玩家 {@code setInvulnerable(true)} ⇒
     * 怪物（含猪灵这类 brain 型）会**照常把玩家当目标**；
     * 伤害仍然被 {@code battle/PlayerDamageGuard} 拦掉（第二层还在），
     * 但"怪追着玩家跑"会回来 —— 这正是 PvE 场景想要的。
     * ★ 默认 <b>true</b>（保持既有玩法），并且**与账本无关**：
     * 无论开关怎么变，账本都会把"我们改过的那一位"还原掉。
     */
    public static final String KEY_PLAYER_TARGET_BAN = "player-target-ban";

    /** 已作废的键（{@code docs/03} §3.2，2026-10-15 裁定）。 */
    public static final String KEY_DEPRECATED_TAUNT_MODE = "taunt-mode";

    /**
     * 刷新间隔的允许范围：**1~200 tick**（0.05 s ~ 10 s）。
     *
     * <p>下界 1 是语义下界（0 或负数等于「不刷新」/「倒着刷新」，纯属手滑）；
     * 上界 200 是**防手滑**的安全阀 —— 写 {@code 1000} 会让「更近的敌人」十几秒才被发现，
     * 又变回 T4 的症状，且**没有任何报错**，所以宁可拒绝启动。
     */
    public static final int MIN_TARGET_REFRESH_TICKS = 1;
    public static final int MAX_TARGET_REFRESH_TICKS = 200;

    /** {@code combat} 段允许出现的全部键（★ 多一个都拒绝，见 {@link #rejectUnknownKeys}）。 */
    private static final List<String> COMBAT_KEYS = List.of(KEY_TARGET_REFRESH_TICKS, KEY_PLAYER_TARGET_BAN);

    /** 出厂默认：玩家索敌排除**开**（与 {@code PlayerTargetBan.DEFAULT_ENABLED} 同一口径）。 */
    public static final boolean DEFAULT_PLAYER_TARGET_BAN = true;

    /**
     * 兼容构造（1.1.0 的 {@code player-target-ban} 之前的单参形态）：开关取出厂值。
     *
     * <p>★ 保留它 ⇒ 既有夹具与测试一字不改；★ 也让"只有刷新间隔"的老调用点不必关心新键。
     */
    public CombatConfig(int targetRefreshTicks) {
        this(targetRefreshTicks, DEFAULT_PLAYER_TARGET_BAN);
    }

    /** ★ 第二道防线：绕过配置层直接 new 也要挡住（纯类自证）。 */
    public CombatConfig {
        if (targetRefreshTicks < MIN_TARGET_REFRESH_TICKS || targetRefreshTicks > MAX_TARGET_REFRESH_TICKS) {
            throw new IllegalArgumentException(SECTION_PATH + "." + KEY_TARGET_REFRESH_TICKS
                    + " 必须在 [" + MIN_TARGET_REFRESH_TICKS + ", " + MAX_TARGET_REFRESH_TICKS
                    + "] 内：实际 " + targetRefreshTicks);
        }
    }

    /**
     * 从已解析的配置根节点加载并校验 {@code combat} 段。
     *
     * <p>校验顺序：段存在 → {@code target-refresh-ticks} 存在/类型/范围 →
     * 已作废的 {@code taunt-mode} → 其它未知键（见方法体里的注释）。
     *
     * @throws ConfigError 段缺失 / {@code target-refresh-ticks} 缺失、类型不符、越界 /
     *                     出现已作废的 {@code taunt-mode} / 出现其它未知键
     */
    public static CombatConfig load(ConfigurationSection root) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "配置为空（文件不存在或解析失败）");
        }
        ConfigurationSection section = ConfigReader.requireSection(FILE, root, SECTION_PATH);

        // ★ 顺序：先读必需键，再抓未知键/作废键。
        //   为什么把"缺键"排在前面：拼错的键（未知键）与"键根本没写"是两种不同的运维问题，
        //   而**段里唯一那个必需键缺失**时会先被 requireInt 抓住 —— 报错直接指向
        //   `combat.target-refresh-ticks`，比笼统的"存在未知键"更有指向性。
        //   ⚠️ 唯一测不出来的情形：整段只剩 `combat:` 一行时，Bukkit 会把该段解析成 null
        //   ⇒ 报的是"缺 combat 段"。这是**单键段落固有的歧义**（"段没写"与"唯一那个键没写"
        //   解析后不可区分），不是缺陷；两种情形都**拒绝启动**。
        int ticks = ConfigReader.requireInt(FILE, section, KEY_TARGET_REFRESH_TICKS,
                MIN_TARGET_REFRESH_TICKS, MAX_TARGET_REFRESH_TICKS);
        // ★ 1.1.0（用户 2026-10-26）：本来硬编码在 PlayerTargetBan.ENABLED 的开关提到这里。
        //   ★ **可选键、缺省 true**：旧现场文件没这一行也能起（符合本项目「只增不改」的现场口径），
        //     而 ConfigMigrator 会在启动时把它补进现场文件。
        boolean playerTargetBan = !section.isSet(KEY_PLAYER_TARGET_BAN)
                || ConfigReader.requireBoolean(FILE, section, KEY_PLAYER_TARGET_BAN);
        rejectDeprecatedAndUnknownKeys(section);
        return new CombatConfig(ticks, playerTargetBan);
    }

    /** 从磁盘上的 YAML 文件加载（**离线可用**，走 {@link YamlFile#read}）。 */
    public static CombatConfig load(File file) {
        return load(YamlFile.read(FILE, file));
    }

    /** 启动日志用的一行摘要。 */
    public String describe() {
        return "索敌｜每 " + targetRefreshTicks + " tick（" + (targetRefreshTicks / 20.0)
                + " 秒）重算一次目标｜玩家无敌索敌排除=" + (playerTargetBan ? "开" : "关");
    }

    /**
     * 先抓**已作废**的键（给专门报错），再抓其余未知键。
     *
     * <p>顺序有意义：{@code taunt-mode} 也是"未知键"，但它值得一条说清缘由的报错
     * ——「该键已作废」比「未知键」对运维有用得多（前者直接告诉他该去 {@code cards} 改布尔
     * {@code taunt}）。
     */
    private static void rejectDeprecatedAndUnknownKeys(ConfigurationSection section) {
        if (section.isSet(KEY_DEPRECATED_TAUNT_MODE)) {
            throw new ConfigError(FILE, section.getCurrentPath() + "." + KEY_DEPRECATED_TAUNT_MODE,
                    "该键已作废：嘲讽没有等级，改用 cards 的布尔 taunt"
                            + "（2026-10-15 用户裁定 —— 只有「有嘲讽 / 无嘲讽」，"
                            + "不存在「嘲讽等级」这一维；见 docs/03 §3.2、docs/07 §5.2）");
        }
        rejectUnknownKeys(section);
    }

    /**
     * 出现**未知键**就抛 {@link ConfigError}。
     *
     * <p>★ 为什么必须拒绝而不是忽略：拼错的键（{@code target-refresh-tick} 少个 {@code s}）
     * 被静默忽略的后果是「配置写了却没生效」且**没有任何报错** —— 刷怪节奏照旧按出厂值跑，
     * 运维只会觉得"改了没用"。
     *
     * <p>键路径指向**那个拼错的键**并从根算起（例：{@code combat.target-refresh-tick}），
     * 消息里同时列出全部未知键与本段允许的键。
     */
    private static void rejectUnknownKeys(ConfigurationSection section) {
        List<String> unknown = new ArrayList<>();
        for (String key : new TreeSet<>(section.getKeys(false))) {   // 排序 ⇒ 报错顺序稳定、可复现
            if (!COMBAT_KEYS.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        String base = section.getCurrentPath();
        throw new ConfigError(FILE, base + "." + unknown.get(0),
                "存在未知键 " + unknown + "（" + base + " 段只接受 " + COMBAT_KEYS + "）—— "
                        + "拼错的键必须被当场抓住，否则会变成「配置写了却没生效」");
    }
}
