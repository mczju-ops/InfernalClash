package io.mczju.infernalclash.arena;

import io.mczju.infernalclash.config.ConfigError;
import io.mczju.infernalclash.config.YamlFile;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ★★★ <b>{@code arenas.yml} 的「多竞技场」形态</b> —— 2026-10-18 新增。
 *
 * <h2>用户原话（逐字）</h2>
 * 「**实际需要绑定多个竞技场供玩家同时游玩，按现在的指令一个一个绑过于繁琐，
 * 我需要你帮我重写一个 arena 设置指令，只需要绑定一个点就能成功绑定其他所有点。**」
 * ＋ 我追问「多竞技场做到哪一步」时用户选 <b>A</b>：
 * 「文件多竞技场 ＋ 一次绑全 ＋ {@code list/remove} 按名字；对局用<b>默认竞技场</b>」。
 *
 * <h2>文件形态（★ 与旧格式兼容）</h2>
 * <pre>
 * arenas:
 *   arena1:
 *     points:
 *       beacon:
 *         red: { world: lobby, x: 45.0, y: 201.0, z: -80.0 }
 *       ...
 * </pre>
 * <ul>
 *   <li><b>新格式</b>：{@code arenas.<名字>.points.<点位键>}；</li>
 *   <li>★★ <b>旧格式</b>（顶层直接 {@code points:}，2026-10-18 之前所有现场文件都是这样）
 *       ⇒ 读成<b>一个名叫 {@value #LEGACY_NAME} 的竞技场</b>。★ 这是<b>必须</b>的：
 *       现场文件是用户在用的数据，不能因为换了结构就"读不到"（那等于把他的竞技场弄丢了）。</li>
 * </ul>
 *
 * <h2>★ 谁是「默认竞技场」（对局实际用的那一个）</h2>
 * 由 {@code config.yml} 的 {@code arena.default} 指定；没写 / 写了但不存在 ⇒
 * <b>文件里的第一个</b>（并告警）。★ 「写了但不存在」不拒绝启动：
 * 那是「管理员删了一个竞技场、忘了改默认值」，把整台服务器拦住不成比例。
 *
 * <p>★ 纯文本 + 纯数据（不碰 Bukkit 实体/世界）⇒ 可离线逐条断言（{@code arena/ArenaCatalogTest}）。
 */
public final class ArenaCatalog {

    /** 顶层段名：{@code arenas:}。 */
    private static final String SECTION = "arenas";

    /** 旧格式（顶层 {@code points:}）被读成这个竞技场名。 */
    public static final String LEGACY_NAME = "default";

    /**
     * 竞技场名的合法形态：1~32 个「字母 / 数字 / 下划线 / 连字符 / 中日韩字符」。
     *
     * <p>★ 为什么限制：竞技场名会成为 YAML 的**键**（{@code arenas.<名字>:}），
     * 含空格 / 点 / 冒号的名字会被 YAML 语法吃掉（点还会被 Bukkit 拆成嵌套段落），
     * 表现是「绑的时候说成功、重启后这个竞技场不见了」。
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{1,32}");

    /** 名字 → 该竞技场的点位表（**保序**：写入顺序 = 文件顺序 = 用户绑定的顺序）。 */
    private final Map<String, ArenaConfig> arenas;

    private ArenaCatalog(Map<String, ArenaConfig> arenas) {
        this.arenas = Collections.unmodifiableMap(new LinkedHashMap<>(arenas));
    }

    // ------------------------------------------------------------------ 构造

    /** 空的目录（一个竞技场都没有）。 */
    public static ArenaCatalog empty() {
        return new ArenaCatalog(new LinkedHashMap<>());
    }

    /** 这个名字合不合法（命令 / Tab 补全 / 载入器**共用这一处**判据）。 */
    public static boolean isLegalName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * 名字的合法化（去两侧空白；不合法 ⇒ {@code null}）。
     *
     * <p>★ 刻意**不做**大小写折叠：竞技场名会被原样写成 YAML 键，
     * 悄悄改写用户输入的名字等于「他绑的和我删的不是同一个」。
     */
    public static String normalizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return isLegalName(trimmed) ? trimmed : null;
    }

    // ------------------------------------------------------------------ 访问

    /** 全部竞技场名（**保序**）。 */
    public List<String> names() {
        return List.copyOf(arenas.keySet());
    }

    /** 取某个竞技场（没有 ⇒ {@code null}）。 */
    public ArenaConfig arena(String name) {
        return name == null ? null : arenas.get(name);
    }

    /** 有没有这个名字。 */
    public boolean has(String name) {
        return name != null && arenas.containsKey(name);
    }

    /** 竞技场个数。 */
    public int size() {
        return arenas.size();
    }

    /** 空的目录？（没有任何竞技场 ⇒ 一条提示都不该是"已就绪"） */
    public boolean isEmpty() {
        return arenas.isEmpty();
    }

    /** 加一个 / 覆盖一个（返回新实例 —— 本类不可变）。 */
    public ArenaCatalog with(String name, ArenaConfig config) {
        if (!isLegalName(name)) {
            throw new IllegalArgumentException("竞技场名不合法：" + name + "（允许字母/数字/_/-/中文，1~32 字）");
        }
        Map<String, ArenaConfig> next = new LinkedHashMap<>(arenas);
        next.put(name, config == null ? ArenaConfig.empty() : config);
        return new ArenaCatalog(next);
    }

    /** 去掉一个（返回新实例；不存在 ⇒ 原样）。 */
    public ArenaCatalog without(String name) {
        if (!has(name)) {
            return this;
        }
        Map<String, ArenaConfig> next = new LinkedHashMap<>(arenas);
        next.remove(name);
        return new ArenaCatalog(next);
    }

    /**
     * ★ <b>解析「默认竞技场」的名字</b>（{@code config.yml} 的 {@code arena.default}）。
     *
     * @param configured 配置里写的名字（可 {@code null} / 空白）
     * @return 真的存在的那个名字；一个竞技场都没有 ⇒ {@code null}
     */
    public String resolveDefault(String configured) {
        if (arenas.isEmpty()) {
            return null;
        }
        String wanted = normalizeName(configured);
        if (wanted != null && arenas.containsKey(wanted)) {
            return wanted;
        }
        return arenas.keySet().iterator().next();
    }

    /** 默认竞技场名解析失败的原因（{@code null} = 没出问题）—— 供调用方打告警。 */
    public String defaultProblem(String configured) {
        if (arenas.isEmpty()) {
            return null;   // 一个都没有 ⇒ 由调用方说"还没有竞技场"，不是配置错
        }
        String wanted = normalizeName(configured);
        if (wanted == null && configured != null && !configured.isBlank()) {
            return "config.yml 的 arena.default=" + configured + " 不是合法的竞技场名，改用文件里的第一个";
        }
        if (wanted != null && !arenas.containsKey(wanted)) {
            return "config.yml 的 arena.default=" + wanted + " 在 " + ArenaConfig.FILE
                    + " 里不存在，改用文件里的第一个（现有：" + names() + "）";
        }
        return null;
    }

    // ------------------------------------------------------------------ 读盘

    /** 从磁盘读（文件不存在 ⇒ 空目录；★ 不报错：「还没绑过竞技场」是正常状态）。 */
    public static ArenaCatalog load(File file) {
        return load(file, null);
    }

    /**
     * 同上，并把**作废键**之类的警告收进 {@code warnings}（可为 {@code null}）。
     *
     * <p>★ 2026-10-18 现场事故之后新增：作废键（旧 {@code river}/{@code bridge.N}）
     * 必须只警告、不拒绝启动（详见 {@code ArenaConfig#OBSOLETE_KEYS} 的 javadoc）。
     */
    public static ArenaCatalog load(File file, List<String> warnings) {
        if (file == null || !file.isFile()) {
            return empty();
        }
        return from(YamlFile.read(ArenaConfig.FILE, file), warnings);
    }

    /**
     * 从已解析的根节点读（★ 同时认新格式与旧格式）。
     *
     * @throws ConfigError 文件里既有 {@code arenas:} 又有顶层 {@code points:}（两种格式混在一起，
     *                     不知道该信哪个）；或某个竞技场段落坏了
     */
    public static ArenaCatalog from(ConfigurationSection root) {
        return from(root, null);
    }

    /** 同上，并收集**作废点位键**的警告（{@code null} ⇒ 丢弃）。 */
    public static ArenaCatalog from(ConfigurationSection root, List<String> warnings) {
        if (root == null) {
            throw new ConfigError(ArenaConfig.FILE, "<root>", "根节点为 null（文件不存在或解析失败）");
        }
        Map<String, ArenaConfig> arenas = new LinkedHashMap<>();
        boolean hasNew = root.isSet(SECTION);
        boolean hasLegacy = root.isSet("points");
        if (hasNew && hasLegacy) {
            throw new ConfigError(ArenaConfig.FILE, SECTION,
                    "文件里同时有 arenas: 与顶层 points: —— 两种格式混在一起，不知道该信哪个。"
                            + "请把顶层 points: 整段删掉（或把它挪进 arenas.<名字>.points）");
        }
        if (hasLegacy) {
            // ★ 旧格式：整份文件 = 一个名叫 default 的竞技场
            arenas.put(LEGACY_NAME, ArenaConfig.from(root, warnings));
            return new ArenaCatalog(arenas);
        }
        if (!hasNew) {
            return empty();
        }
        ConfigurationSection section = root.getConfigurationSection(SECTION);
        if (section == null) {
            throw new ConfigError(ArenaConfig.FILE, SECTION,
                    "该键存在但不是对象节点（应写成 arenas: 后换行缩进）");
        }
        for (String name : section.getKeys(false)) {
            if (!isLegalName(name)) {
                throw new ConfigError(ArenaConfig.FILE, SECTION + "." + name,
                        "竞技场名不合法（允许字母/数字/_/-/中文，1~32 字）—— 这个名字写不进 YAML 的键");
            }
            ConfigurationSection child = section.getConfigurationSection(name);
            if (child == null) {
                throw new ConfigError(ArenaConfig.FILE, SECTION + "." + name,
                        "该竞技场不是对象节点（应写成 <名字>: 后换行缩进 points）");
            }
            if (!child.isSet("points")) {
                throw new ConfigError(ArenaConfig.FILE, SECTION + "." + name + ".points",
                        "缺少 points 段（一个竞技场至少要有 points:；还没绑过就跑一次 "
                                + "/incl arena set <名字>）");
            }
            arenas.put(name, ArenaConfig.from(child, warnings));
        }
        return new ArenaCatalog(arenas);
    }

    // ------------------------------------------------------------------ 写盘

    /**
     * 生成 {@code arenas.yml} 的完整文本（多竞技场形态）。
     *
     * <p>★ 写盘顺序 = {@link #names()} 的顺序（= 绑定的先后顺序），
     * 每个竞技场里的点位顺序 = {@link ArenaConfig#allKeys()}（复用 {@code ArenaConfig} 的唯一写入实现）。
     */
    public static String toYaml(ArenaCatalog catalog) {
        ArenaCatalog value = catalog == null ? empty() : catalog;
        StringBuilder out = new StringBuilder(4096);
        for (String line : ArenaConfig.header()) {
            out.append(line).append('\n');
        }
        out.append("# 2026-10-18 起：本文件是多竞技场结构 —— arenas.<名字>.points.<点位键>。\n");
        out.append("# 旧格式（顶层 points:）仍然能读，会被当成名叫 " + LEGACY_NAME + " 的竞技场。\n");
        out.append("# 对局实际使用哪一个由 config.yml 的 arena.default 决定（不写 = 文件里的第一个）。\n");
        if (value.isEmpty()) {
            out.append("arenas: {}\n");
            return out.toString();
        }
        out.append("arenas:\n");
        for (Map.Entry<String, ArenaConfig> entry : value.arenas.entrySet()) {
            out.append("  ").append(entry.getKey()).append(":\n");
            ArenaConfig.appendPoints(out, entry.getValue(), 2);
        }
        return out.toString();
    }

    /** 一次写盘的结果（形状与 {@link ArenaConfig.SaveOutcome} 一致）。 */
    public record SaveOutcome(boolean changed, String backupName, String summary,
                              List<String> warnings) {
    }

    /**
     * ★ <b>{@code arenas.yml} 的落盘入口</b>（先备份、后写，与 {@code ArenaConfig.save} 同一口径）。
     *
     * <p>★ 失败只告警、绝不抛：这条路径同时被**指令**与**界面**调用。
     *
     * @param file    目标文件（通常是 {@code plugins/InfernalClash/arenas.yml}）
     * @param catalog 要写入的目录
     */
    public static SaveOutcome save(File file, ArenaCatalog catalog) {
        List<String> warnings = new ArrayList<>();
        if (file == null) {
            warnings.add("目标文件为 null，一个字节都没写");
            return new SaveOutcome(false, null, ArenaConfig.FILE + "：未写入磁盘（目标文件为 null）",
                    List.copyOf(warnings));
        }
        if (catalog == null) {
            warnings.add("竞技场目录为 null，拒绝写盘（否则会用一个空目录覆盖现场文件）");
            return new SaveOutcome(false, null,
                    file.getName() + "：未写入磁盘（竞技场目录为 null）", List.copyOf(warnings));
        }
        String text = toYaml(catalog);
        String backupName = null;
        try {
            if (file.isFile()) {
                backupName = file.getName() + ".bak";
                Files.copy(file.toPath(), file.toPath().resolveSibling(backupName),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            return new SaveOutcome(true, backupName,
                    file.getName() + "：已写入 " + catalog.size() + " 个竞技场（"
                            + namesWithPoints(catalog) + "）"
                            + (backupName == null ? "（原文件不存在，未生成备份）"
                            : "（旧文件已备份为 " + backupName + "）"),
                    List.copyOf(warnings));
        } catch (java.lang.Exception e) {
            warnings.add("写盘失败（" + e + "），磁盘上的文件保持原样"
                    + (backupName == null ? "" : "（旧内容已先备份为 " + backupName + "）"));
            return new SaveOutcome(false, null,
                    file.getName() + "：未写入磁盘（内存里的改动仍然有效）", List.copyOf(warnings));
        }
    }

    private static String namesWithPoints(ArenaCatalog catalog) {
        StringBuilder out = new StringBuilder();
        for (String name : catalog.names()) {
            ArenaConfig arena = catalog.arena(name);
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(name).append(" ").append(arena == null ? 0 : arena.size()).append(" 点");
        }
        return out.toString();
    }

    /** 一行摘要（启动日志 / 探针用）。 */
    public String describe(String defaultName) {
        if (isEmpty()) {
            return ArenaConfig.FILE + "：还没有任何竞技场（用 /incl arena set <名字> 绑一个点即可）";
        }
        return ArenaConfig.FILE + "：共 " + size() + " 个竞技场 " + names()
                + "；默认 = " + (defaultName == null ? "（未定）" : defaultName);
    }
}
