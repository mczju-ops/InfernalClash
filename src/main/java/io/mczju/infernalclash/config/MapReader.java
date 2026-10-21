package io.mczju.infernalclash.config;

import java.util.List;
import java.util.Map;

/**
 * 严格读取**列表项**的读取器 —— {@link ConfigReader} 的同胞。
 *
 * <h2>为什么需要它</h2>
 * {@link ConfigReader} 工作在 {@code ConfigurationSection} 上；但 YAML 的
 * **列表项**（如 {@code summon.group} 的每一项 `- entity: ZOMBIE`）在 Bukkit 里
 * 拿到的是普通的 {@code Map}，**不是** {@code ConfigurationSection}。
 * 若为此改用手写 {@code get("count")} + 强转，就正好回到本项目最想避免的
 * 「缺键 → NPE 或静默 0」那条老路。
 *
 * <p>所以这里补一个**同口径**的读取器：一样的「文件 + 完整键路径」报错、
 * 一样的「缺键即抛、不给默认值」，只是节点类型从 section 换成 map。
 *
 * <p>本类只依赖 {@code java.util}（连 Bukkit 都不碰）⇒ 必然可离线单测。
 */
public final class MapReader {

    private MapReader() {
    }

    /** 该键是否存在（用来区分「没写」与「写了空值」）。 */
    public static boolean isSet(Map<?, ?> node, String key) {
        return node != null && node.containsKey(key);
    }

    /** 取原始值的可读形式（只用于报错信息）。 */
    private static String show(Map<?, ?> node, String key) {
        Object value = node.get(key);
        return value == null ? "null" : value.toString();
    }

    /** 读必需的非空字符串。 */
    public static String requireString(String file, Map<?, ?> node, String path, String key) {
        Object value = node == null ? null : node.get(key);
        if (!(value instanceof String text)) {
            throw new ConfigError(file, path + "." + key, isSet(node, key)
                    ? "类型不是字符串（当前值：" + show(node, key) + "）"
                    : "缺少必需的字符串键");
        }
        if (text.isBlank()) {
            throw new ConfigError(file, path + "." + key, "不能为空字符串");
        }
        return text;
    }

    /** 读必需的整数，并校验范围。 */
    public static int requireInt(String file, Map<?, ?> node, String path, String key, int min, int max) {
        Object value = node == null ? null : node.get(key);
        if (!(value instanceof Number number)) {
            throw new ConfigError(file, path + "." + key, isSet(node, key)
                    ? "类型不是整数（当前值：" + show(node, key) + "）"
                    : "缺少必需的整数键");
        }
        int result = number.intValue();
        if (result < min || result > max) {
            throw new ConfigError(file, path + "." + key,
                    "取值 " + result + " 超出允许范围 [" + min + ", " + max + "]");
        }
        return result;
    }

    /** 读必需的布尔值。 */
    public static boolean requireBoolean(String file, Map<?, ?> node, String path, String key) {
        Object value = node == null ? null : node.get(key);
        if (!(value instanceof Boolean flag)) {
            throw new ConfigError(file, path + "." + key, isSet(node, key)
                    ? "类型不是布尔（当前值：" + show(node, key) + "，应写 true / false）"
                    : "缺少必需的布尔键");
        }
        return flag;
    }

    /** 读可选的布尔值：没写返回 {@code null}（**不猜默认值**，由调用方显式决定）。 */
    public static Boolean optionalBoolean(Map<?, ?> node, String key) {
        Object value = node == null ? null : node.get(key);
        return value instanceof Boolean flag ? flag : null;
    }

    /** 读可选的数字：没写返回 {@code null}（用于「不覆写属性」这类语义）。 */
    public static Double optionalDouble(String file, Map<?, ?> node, String path, String key) {
        if (!isSet(node, key)) {
            return null;
        }
        Object value = node.get(key);
        if (!(value instanceof Number number)) {
            throw new ConfigError(file, path + "." + key,
                    "类型不是数字（当前值：" + show(node, key) + "）");
        }
        return number.doubleValue();
    }

    /** 读必需的嵌套节点（列表项里的子对象）。 */
    public static Map<?, ?> requireMap(String file, Map<?, ?> node, String path, String key) {
        Object value = node == null ? null : node.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new ConfigError(file, path + "." + key, isSet(node, key)
                    ? "类型不是对象节点（当前值：" + show(node, key) + "）"
                    : "缺少必需的对象节点");
        }
        return map;
    }

    /**
     * 读必需的**对象列表**（如 {@code abilities} / {@code effects}）。
     *
     * @return 每一项都是一个 {@code Map}（可能为空列表 → 视为缺失，直接报错）
     */
    public static List<Map<?, ?>> requireMapList(String file, Map<?, ?> node, String path, String key) {
        Object value = node == null ? null : node.get(key);
        if (!(value instanceof List<?> list)) {
            throw new ConfigError(file, path + "." + key, isSet(node, key)
                    ? "类型不是列表（应写成逐项 - 键: 值）"
                    : "缺少必需的列表键");
        }
        if (list.isEmpty()) {
            throw new ConfigError(file, path + "." + key, "列表不能为空（至少要有一项）");
        }
        List<Map<?, ?>> result = new java.util.ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                throw new ConfigError(file, path + "." + key + "[" + i + "]",
                        "列表项必须是对象（当前值：" + item + "）");
            }
            result.add(map);
        }
        return List.copyOf(result);
    }
}
