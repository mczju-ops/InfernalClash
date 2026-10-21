package io.mczju.infernalclash.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/**
 * 已校验的主配置（{@code config.yml}）。
 *
 * <p><b>纯数据对象</b>：只依赖 {@code ConfigurationSection}，不碰服务端，
 * 因此 {@link #load(File)} 能在普通 JUnit 里加载**出厂 {@code config.yml}**
 * 并断言它自己合法（见 {@code ShippedConfigTest}）。
 *
 * <p>⚠️ 底座阶段**刻意只有骨架项**：玩法数值等用户拍板后再加，
 * 不在这里「先猜一个默认值」（观感与数值由用户定）。
 *
 * @param configVersion 配置结构版本号（做破坏性迁移时靠它判断）
 * @param minPlayers    开局所需最少**真人**玩家数
 * @param maxPlayers    房间人数上限
 * @param registerGame  是否把本游戏注册到 MGC 大厅
 * @param meta          大厅展示信息（名称 / 图标 / 作者 / 描述）
 * @param purgeLegacyOnStart ★ 启动（以及"没有对局时"的周期扫描）是否自动清理**本插件自己的**
 *        遗留实体（{@code mgc.purge-legacy-on-start}，缺省 {@code true}）。
 *        ★ 2026-10-20 用户报「abort 后重启，上局实体一直残留清不掉」⇒ 清理不能再只靠手动指令。
 *        范围与归属判据见 {@code objective/LegacyPurge}（本插件点位 ±64 格 + PDC，绝不按类型）
 */
public record GameConfig(int configVersion,
                         int minPlayers,
                         int maxPlayers,
                         boolean registerGame,
                         Meta meta,
                         boolean purgeLegacyOnStart) {

    /** 本配置对应的文件名（报错与迁移都用它）。 */
    public static final String FILE = "config.yml";

    /** 允许的最大房间人数上限。 */
    public static final int PLAYER_CAP = 100;

    /**
     * 大厅展示信息。
     *
     * <p>放在配置里而不是写死在代码里：游戏名/图标/描述属于**观感**，
     * 按项目口径由用户拍板，不该由实现顺手决定。
     *
     * @param name        显示名（MiniMessage 文本）
     * @param icon        原版 Material 名（非法时启动期告警并回退）
     * @param author      作者署名
     * @param description 描述行（每行一条 MiniMessage 文本）
     */
    public record Meta(String name, String icon, String author, List<String> description) {
    }

    /**
     * 从已解析的配置根节点加载并校验。
     *
     * @throws ConfigError 任一必需键缺失、类型不符或越界
     */
    public static GameConfig load(ConfigurationSection root) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "配置为空（文件不存在或解析失败）");
        }
        int version = ConfigReader.requireInt(FILE, root, "config-version", 1, 1_000_000);

        ConfigurationSection metaSection = ConfigReader.requireSection(FILE, root, "meta");
        Meta meta = new Meta(
                ConfigReader.requireString(FILE, metaSection, "name"),
                ConfigReader.requireString(FILE, metaSection, "icon"),
                ConfigReader.requireString(FILE, metaSection, "author"),
                ConfigReader.requireStringList(FILE, metaSection, "description"));

        ConfigurationSection game = ConfigReader.requireSection(FILE, root, "game");
        int min = ConfigReader.requireInt(FILE, game, "min-players", 1, PLAYER_CAP);
        // ★ max 的下界是 min：允许 "min 5 / max 3" 这种自相矛盾的配置存在是没有意义的
        int max = ConfigReader.requireInt(FILE, game, "max-players", min, PLAYER_CAP);

        ConfigurationSection mgc = ConfigReader.requireSection(FILE, root, "mgc");
        boolean register = ConfigReader.requireBoolean(FILE, mgc, "register-game");

        // ★★ 2026-10-20（用户报「abort 后重启，上局实体一直残留清不掉」）：
        //   自动清理遗留实体的开关。**缺省开启**，且「有就按严格校验读、没有就走默认」
        //   —— 为什么不是 requireBoolean：现场 config.yml 是"只增不改"，升级那一刻它**还没有**
        //   这个键；若强制要求，用户会因为"少一个与玩法无关的键"直接开不了服。
        //   ★ 它只影响**本插件自己带 PDC 的实体**（见 objective/LegacyPurge：范围 = 本插件点位 ±64 格、
        //     归属 = PDC），所以默认开启的爆炸半径就是"我们自己的遗留物"。
        boolean purgeLegacy = mgc.isSet("purge-legacy-on-start")
                ? ConfigReader.requireBoolean(FILE, mgc, "purge-legacy-on-start")
                : true;

        return new GameConfig(version, min, max, register, meta, purgeLegacy);
    }

    /**
     * 从磁盘上的 YAML 文件加载（**离线可用**，不依赖服务端）。
     *
     * @throws ConfigError 文件不存在或内容非法
     */
    public static GameConfig load(File file) {
        // ★ 走 YamlFile：loadConfiguration(File) 在解析失败时会调 Bukkit.getLogger() ⇒ 离线 NPE
        return load(YamlFile.read(FILE, file));
    }

    /** 启动日志用的一行摘要。 */
    public String describe() {
        return minPlayers() + "~" + maxPlayers() + " 人"
                + " / 注册到大厅 " + (registerGame() ? "是" : "否")
                + " / 启动时自动清遗留实体 " + (purgeLegacyOnStart() ? "开" : "关")
                + " / 结构版本 " + configVersion();
    }
}
