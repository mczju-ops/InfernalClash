package io.mczju.infernalclash.card;

import io.mczju.infernalclash.config.ConfigError;
import io.mczju.infernalclash.config.ConfigReader;
import io.mczju.infernalclash.config.MapReader;
import io.mczju.infernalclash.config.YamlFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 召唤物实体组目录（{@code mob-groups.yml}，{@code docs/03} §5.1）。
 *
 * <h2>★ 组键 = 原版实体类型名</h2>
 * 卡牌的 {@code summon.group[].entity} 直接引用**实体类型名**（{@code ZOMBIE} / {@code PARCHED} …）。
 * 这样做的理由：卡牌里写的实体**本来就是**实体类型，多一层自定义组名只会多一处对不上的机会。
 * {@code docs/03} §5.1 的示例用的是自定义名（{@code knight}），那与 §4.3 的
 * 「键名 = 原版实体类型」**不一致** —— 以 §4.3 为准（已同步修文档）。
 *
 * <h2>★ 为什么要校验实体类型存在</h2>
 * 「写错一个实体名」是本项目最可能发生、又最难发现的一类配置事故：
 * 卡照样加载、图鉴照样显示，只是召唤时什么都不出来（或抛到游戏里）。
 * 在这里用 {@link EntityType#valueOf} 把它挡住 —— 这是**枚举查表**，不需要服务端，
 * 因此离线单测里同样有效。
 *
 * @param groups 组键（实体类型名）→ 组定义
 */
public record MobGroupCatalog(Map<String, Group> groups) {

    /** 本文件对应的文件名（报错与迁移都用它）。 */
    public static final String FILE = "mob-groups.yml";

    /** AI 装载方式（{@code docs/03} §5.1）。 */
    public enum AiMode {
        /**
         * 自动判定：扫该实体**实际挂着的 goal** 里有没有攻击类词根。
         *
         * <p>⚠️ **绝不用手写白名单** —— Paper 的 goal key 从 NMS 类名推导
         * （狐狸的近战 goal 是 {@code fox_melee_attack}，不是 {@code melee_attack}），
         * 白名单漏一次就废掉一个物种（InfernalGuess 的狐狸事故）。
         */
        AUTO,
        /** 强制补自己的驱动 goal（用于确认没有自带攻击 goal 的单位）。 */
        FORCE_DRIVE
    }

    /**
     * 一个实体组。
     *
     * @param entity     实体类型名（**必须等于组键**；写出来只为可读与自检）
     * @param attributes 组级基础属性（可被卡牌覆写；{@code null} 字段 = 不覆写）
     * @param aiMode     AI 装载方式
     */
    public record Group(String entity, Card.Attributes attributes, AiMode aiMode) {
    }

    /** 从已解析的配置根节点加载并校验。 */
    public static MobGroupCatalog load(ConfigurationSection root) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "配置为空（文件不存在或解析失败）");
        }
        ConfigurationSection section = ConfigReader.requireSection(FILE, root, "groups");
        Set<String> keys = section.getKeys(false);
        if (keys.isEmpty()) {
            throw new ConfigError(FILE, "groups", "至少要定义一个实体组");
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        for (String key : new TreeSet<>(keys)) {          // 排序 ⇒ 报错顺序稳定、可复现
            String entity = requireEntityType(key, section.getCurrentPath() + "." + key);

            // ★ 自检：如果写了 entity 字段，它必须与组键一致（防止复制粘贴改一半）
            if (section.isSet(key + ".entity")) {
                String declared = ConfigReader.requireString(FILE, section, key + ".entity");
                if (!declared.equals(entity)) {
                    throw new ConfigError(FILE, section.getCurrentPath() + "." + key + ".entity",
                            "与组键不一致（组键 " + entity + " / 字段 " + declared + "）—— "
                                    + "组键就是实体类型名，这个字段只是自检，两者必须相同");
                }
            }

            Card.Attributes attributes = parseAttributes(FILE, section, key);
            AiMode mode = AiMode.AUTO;
            if (section.isSet(key + ".ai.mode")) {
                String raw = ConfigReader.requireString(FILE, section, key + ".ai.mode");
                try {
                    mode = AiMode.valueOf(raw);
                } catch (IllegalArgumentException e) {
                    throw new ConfigError(FILE, section.getCurrentPath() + "." + key + ".ai.mode",
                            "未知的 AI 模式（合法值：" + java.util.List.of(AiMode.values()) + "）");
                }
            }
            groups.put(entity, new Group(entity, attributes, mode));
        }
        return new MobGroupCatalog(Map.copyOf(groups));
    }

    /** 从磁盘上的 YAML 文件加载（**离线可用**）。 */
    public static MobGroupCatalog load(File file) {
        return load(YamlFile.read(FILE, file));
    }

    /** 组键是否存在。 */
    public boolean has(String entity) {
        return groups.containsKey(entity);
    }

    /** 取组；不存在时抛错，并附「你是不是想写 X」的建议。 */
    public Group require(String entity) {
        Group group = groups.get(entity);
        if (group == null) {
            throw new ConfigError(FILE, "groups." + entity,
                    "没有这个实体组" + suggest(entity));
        }
        return group;
    }

    /** 全部组键（升序）。 */
    public Set<String> keys() {
        return new TreeSet<>(groups.keySet());
    }

    /**
     * 给写错的实体名一个建议：先找**忽略大小写**的同名，再找包含关系。
     *
     * <p>InfernalGuess 的同类做法救过不少次配置事故 —— 报错里带一句
     * 「你是不是想写 ZOMBIE」比只报「找不到」有用得多。
     */
    public String suggest(String wrong) {
        if (wrong == null || wrong.isBlank()) {
            return "（可用的组：" + keys() + "）";
        }
        String upper = wrong.trim().toUpperCase();
        for (String key : keys()) {
            if (key.equalsIgnoreCase(upper)) {
                return "；你是不是想写 " + key + "（大小写不一致）？";
            }
        }
        for (String key : keys()) {
            if (key.contains(upper) || upper.contains(key)) {
                return "；你是不是想写 " + key + "？";
            }
        }
        return "（可用的组：" + keys() + "）";
    }

    /** 启动日志用的一行摘要。 */
    public String describe() {
        return groups.size() + " 个实体组";
    }

    // ------------------------------------------------------------------ 内部

    private static String requireEntityType(String key, String path) {
        try {
            EntityType type = EntityType.valueOf(key.trim().toUpperCase());
            if (type == EntityType.UNKNOWN) {
                throw new IllegalArgumentException("UNKNOWN");
            }
            return type.name();
        } catch (IllegalArgumentException e) {
            throw new ConfigError(FILE, path,
                    "不是合法的原版实体类型名（组键必须写成实体类型，如 ZOMBIE / PARCHED）");
        }
    }

    /**
     * 读组级属性：**只写给了的**，没写的保持 {@code null}（= 不覆写，退回原版默认）。
     *
     * <p>★★ 属性键在**三处**解析（本方法 / {@link #readAttributes} / {@code CardCatalog} 的
     * 组级），必须同键同名 —— 分叉的后果是「能装载、但不生效」（「第二处白名单」那类缺陷）。
     * 范围校验统一走 {@link CardCatalog#knockbackResistance(String, ConfigurationSection)}。
     */
    private static Card.Attributes parseAttributes(String file, ConfigurationSection root, String prefix) {
        if (!root.isSet(prefix + ".attributes")) {
            return Card.Attributes.NONE;
        }
        ConfigurationSection attributes = ConfigReader.requireSection(file, root, prefix + ".attributes");
        return new Card.Attributes(
                optionalNumber(file, attributes, "health"),
                optionalNumber(file, attributes, "attack-damage"),
                optionalNumber(file, attributes, "armor"),
                // ★★ 2026-10-17 第十三轮：movement-speed 的量纲改成**倍率** ⇒ 校验也换成
                //   同一个实现（`> 0`，装载期拒绝 0 / 负数）—— 三处解析共用它，不许各写一遍。
                CardCatalog.movementSpeedMultiplier(file, attributes),
                optionalNumber(file, attributes, "follow-range"),
                optionalNumber(file, attributes, "scale"),
                CardCatalog.knockbackResistance(file, attributes),
                // ★ 2026-10-18（用户第 3 条）：方块怪的尺寸档位（三处解析同键同名）
                CardCatalog.size(file, attributes));
    }

    /** 读段落里的可选数字（{@code null} = 没写）。 */
    static Double optionalNumber(String file, ConfigurationSection section, String key) {
        if (!section.isSet(key)) {
            return null;
        }
        return ConfigReader.requireDouble(file, section, key, 0.0, 100_000.0);
    }

    /**
     * 供 {@link CardCatalog} 复用（同一套属性键）。
     *
     * <p>★★ 这就是**召唤块级**（{@code summon.attributes}）那一处解析 —— 三处之一。
     */
    public static Card.Attributes readAttributes(String file, ConfigurationSection section) {
        return new Card.Attributes(
                optionalNumber(file, section, "health"),
                optionalNumber(file, section, "attack-damage"),
                optionalNumber(file, section, "armor"),
                CardCatalog.movementSpeedMultiplier(file, section),
                optionalNumber(file, section, "follow-range"),
                optionalNumber(file, section, "scale"),
                CardCatalog.knockbackResistance(file, section),
                // ★ 2026-10-18（用户第 3 条）：方块怪的尺寸档位（三处解析同键同名）
                CardCatalog.size(file, section));
    }

    /** 供 {@link CardCatalog} 复用（列表项形式的属性键）。 */
    public static double spreadOf(String file, Map<?, ?> group, String path) {
        Double spread = MapReader.optionalDouble(file, group, path, "spread");
        return spread == null ? 0.0 : spread;
    }
}
