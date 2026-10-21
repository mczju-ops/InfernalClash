package io.mczju.infernalclash.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * 严格配置读取器：**缺键 / 类型不符 / 越界一律抛 {@link ConfigError}**。
 *
 * <p>刻意不提供「带默认值的读取」—— 默认值是静默失效的温床
 * （Maggoteers 的已知缺陷：缺键读成 {@code 0.0}，配置写了却没生效且不报错）。
 * 需要默认值的地方，请把默认值写进**出厂 {@code config.yml}**，让用户看得见。
 *
 * <p>本类只依赖 {@code ConfigurationSection}（普通的 Bukkit 配置接口，
 * **不需要服务端**），因此可以在 JUnit 里离线加载出厂文件做校验。
 *
 * <h2>★ 报出的键路径一律是「从根算起」的完整路径</h2>
 * 调用方传的是**相对当前节点**的键名（例：{@code min-players}），
 * 但报错里必须是 {@code game.min-players} —— 否则运维拿着
 * 「{@code min-players} 缺少必需的整数键」根本不知道该去哪个段落里找。
 */
public final class ConfigReader {

    private ConfigReader() {
    }

    /**
     * 拼出从根节点算起的完整键路径。
     *
     * <p>{@code getCurrentPath()} 对根节点返回空串，所以根下的键就是它自己。
     */
    private static String fullPath(ConfigurationSection section, String path) {
        String base = section == null ? null : section.getCurrentPath();
        return (base == null || base.isEmpty()) ? path : base + "." + path;
    }

    /** 读必需的对象节点。 */
    public static ConfigurationSection requireSection(String file, ConfigurationSection root, String path) {
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section == null) {
            throw new ConfigError(file, fullPath(root, path), root.isSet(path)
                    ? "该键存在但不是对象节点（应写成 键: 后换行缩进）"
                    : "缺少必需的对象节点");
        }
        return section;
    }

    /**
     * 读必需的整数，并校验范围。
     *
     * @param min 允许的最小值（含）
     * @param max 允许的最大值（含）
     */
    public static int requireInt(String file, ConfigurationSection root, String path, int min, int max) {
        if (!root.isInt(path)) {
            throw new ConfigError(file, fullPath(root, path), root.isSet(path)
                    ? "类型不是整数（当前值：" + root.get(path) + "）"
                    : "缺少必需的整数键");
        }
        int value = root.getInt(path);
        if (value < min || value > max) {
            throw new ConfigError(file, fullPath(root, path),
                    "取值 " + value + " 超出允许范围 [" + min + ", " + max + "]");
        }
        return value;
    }

    /** 读必需的布尔值。 */
    public static boolean requireBoolean(String file, ConfigurationSection root, String path) {
        if (!root.isBoolean(path)) {
            throw new ConfigError(file, fullPath(root, path), root.isSet(path)
                    ? "类型不是布尔（当前值：" + root.get(path) + "，应写 true / false）"
                    : "缺少必需的布尔键");
        }
        return root.getBoolean(path);
    }

    /** 读必需的浮点数，并校验范围（含边界）。 */
    public static double requireDouble(String file, ConfigurationSection root, String path,
                                       double min, double max) {
        // ★ 接受 isInt：YAML 里写 `gold-chance: 0` 会被解析成整数，而它的语义确实是 0.0
        if (!root.isDouble(path) && !root.isInt(path)) {
            throw new ConfigError(file, fullPath(root, path), root.isSet(path)
                    ? "类型不是数字（当前值：" + root.get(path) + "）"
                    : "缺少必需的数字键");
        }
        double value = root.getDouble(path);
        // ★ 写成 `!(value >= min)` 而不是 `value < min`：NaN 会让后者为 false 从而漏过校验
        if (!(value >= min) || value > max) {
            throw new ConfigError(file, fullPath(root, path),
                    "取值 " + value + " 超出允许范围 [" + min + ", " + max + "]");
        }
        return value;
    }

    /**
     * 读必需的长整数，并校验范围。
     *
     * <p>货币量一律用 {@code long}（金币 / 烈焰粉都可能被加成放大），
     * 而 YAML 的小整数会被解析成 {@code Integer} ⇒ 必须**同时**接受两种。
     */
    public static long requireLong(String file, ConfigurationSection root, String path,
                                   long min, long max) {
        if (!root.isLong(path) && !root.isInt(path)) {
            throw new ConfigError(file, fullPath(root, path), root.isSet(path)
                    ? "类型不是整数（当前值：" + root.get(path) + "）"
                    : "缺少必需的整数键");
        }
        long value = root.getLong(path);
        if (value < min || value > max) {
            throw new ConfigError(file, fullPath(root, path),
                    "取值 " + value + " 超出允许范围 [" + min + ", " + max + "]");
        }
        return value;
    }

    /** 读必需的非空字符串。 */
    public static String requireString(String file, ConfigurationSection root, String path) {
        if (!root.isString(path)) {
            throw new ConfigError(file, fullPath(root, path), root.isSet(path)
                    ? "类型不是字符串（当前值：" + root.get(path) + "）"
                    : "缺少必需的字符串键");
        }
        String value = root.getString(path, "");
        if (value.isBlank()) {
            throw new ConfigError(file, fullPath(root, path), "不能为空字符串");
        }
        return value;
    }

    /** 读必需的非空字符串列表。 */
    public static List<String> requireStringList(String file, ConfigurationSection root, String path) {
        if (!root.isList(path)) {
            throw new ConfigError(file, fullPath(root, path), root.isSet(path)
                    ? "类型不是列表（应写成逐行 - 文本）"
                    : "缺少必需的列表键");
        }
        List<String> value = root.getStringList(path);
        if (value.isEmpty()) {
            throw new ConfigError(file, fullPath(root, path), "列表不能为空（至少要有一条）");
        }
        // ★ 返回不可变副本：配置对象会被多方读取，别让调用方改坏它
        return List.copyOf(value);
    }
}
