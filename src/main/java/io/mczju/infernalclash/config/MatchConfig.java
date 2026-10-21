package io.mczju.infernalclash.config;

import io.mczju.infernalclash.match.PhaseConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * 对局时间轴配置（{@code config.yml} 的 {@code phases} 段）。
 *
 * <h2>★ 为什么和 {@link GameConfig} 分成两个类，读同一个文件</h2>
 * 它们回答两个完全不同的问题：{@code GameConfig} 是「**怎么把游戏挂进大厅**」
 * （人数 / 展示 / 注册开关，底座期就定了），{@code MatchConfig} 是「**一局怎么走**」
 * （阶段时长 / 经验速率，玩法期才有）。合成一个 record 会让「改玩法数值」
 * 与「改大厅展示」在代码里互相牵连（也把底座期的测试全部拖进来）。
 *
 * <p>两者都从**同一个已解析的根节点**读（{@link #load(ConfigurationSection)}），
 * 所以不会重复读盘。
 *
 * <h2>★ 只有 {@code phases}（P2 只落这一段）</h2>
 * `docs/03` §3.2 还规划了 {@code objectives} / {@code arena-geometry} / {@code combat} /
 * {@code overtime} / {@code solo} —— 它们**随各自的消费者**在 P3/P4/P6 加入。
 * 本项目刻意**不做"没有消费者的配置键"**：那种键一加进去就变成
 * 「配置写了却没生效」，正是 `AGENTS.md` 反复警告的事故类型。
 */
public record MatchConfig(int configVersion, PhaseConfig phases) {

    /** 本配置所在的文件名（`phases` 段在 `config.yml` 里）。 */
    public static final String FILE = GameConfig.FILE;

    private static final int MAX_TICKS = 24 * 60 * 60 * 20;   // 24 小时，纯防手滑

    /** 从已解析的配置根节点加载并校验。 */
    public static MatchConfig load(ConfigurationSection root) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "配置为空（文件不存在或解析失败）");
        }
        int version = ConfigReader.requireInt(FILE, root, "config-version", 1, 1_000_000);
        ConfigurationSection phases = ConfigReader.requireSection(FILE, root, "phases");

        int preparing = ConfigReader.requireInt(FILE, phases, "preparing-ticks", 1, MAX_TICKS);
        int battle = ConfigReader.requireInt(FILE, phases, "battle-ticks", 1, MAX_TICKS);
        // ★ 双倍起点必须落在战斗期内，否则「双倍」要么永不生效、要么在战斗结束后才生效
        int doubleAfter = ConfigReader.requireInt(FILE, phases, "double-xp-after-ticks", 0, battle);
        int overtime = ConfigReader.requireInt(FILE, phases, "overtime-ticks", 1, MAX_TICKS);
        int perLevel = ConfigReader.requireInt(FILE, phases, "xp-ticks-per-level", 1, 10_000);
        int perLevelDouble = ConfigReader.requireInt(FILE, phases, "xp-ticks-per-level-double", 1, 10_000);
        double maxLevel = ConfigReader.requireDouble(FILE, phases, "max-level", 1.0, 100.0);
        // ★★★ 2026-10-18（用户第 8 条）：「**战斗开始时 5 级**」
        //   ⇒ 新键 `phases.xp-at-battle-start`（单位 = 「级」）。
        //   ★ 范围上限刻意用 `maxLevel`：超过上限的部分会被 clamp 掉 ⇒ 那就是
        //     「配置写了却只生效一半」，必须在装载期拒绝。
        double xpAtBattleStart = ConfigReader.requireDouble(FILE, phases,
                "xp-at-battle-start", 0.0, maxLevel);

        return new MatchConfig(version, new PhaseConfig(preparing, battle, doubleAfter,
                overtime, perLevel, perLevelDouble, maxLevel, xpAtBattleStart));
    }

    /** 从磁盘上的 YAML 文件加载（**离线可用**）。 */
    public static MatchConfig load(File file) {
        return load(YamlFile.read(FILE, file));
    }

    /** 启动日志用的一行摘要。 */
    public String describe() {
        return "准备 " + phases.preparingTicks() + " / 战斗 " + phases.battleTicks()
                + "（第 " + phases.doubleXpAfterTicks() + " tick 起双倍）/ 加时 "
                + phases.overtimeTicks() + " tick｜上限 " + phases.maxLevel() + " 级"
                + "｜战斗开始 " + phases.xpAtBattleStart() + " 级"
                + "（每 " + phases.xpTicksPerLevel() + " tick +1 级，双倍期每 "
                + phases.xpTicksPerLevelDouble() + " tick +1 级）";
    }
}
