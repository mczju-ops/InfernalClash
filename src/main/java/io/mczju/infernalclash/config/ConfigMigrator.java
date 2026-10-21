package io.mczju.infernalclash.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★ <b>配置迁移（「只增不改」补齐缺失键）</b> —— {@code docs/03} §7 承诺的行为，P2 才真正实现。
 *
 * <h2>为什么必须有它（这是本轮实机自检抓到的真问题）</h2>
 * 底座期与 P1 的 {@code saveResource(name, false)} **只补「文件不存在」**，**不补「键不存在」**。
 * 于是 P2 往出厂 {@code config.yml} 里加了 {@code phases} 段之后：
 * <ul>
 *   <li>全新安装 → 正常（出厂文件里就有）；</li>
 *   <li><b>已经跑过一次的服务器</b> → 现场 {@code config.yml} 里**没有** {@code phases}
 *       ⇒ 严格校验判定「缺必需段落」⇒ **插件拒绝启动**。</li>
 * </ul>
 * 而 {@code docs/03} §7 明确承诺「升级只补齐缺失键、绝不覆盖服主改过的值」。
 * ⇒ 本类把那条承诺补上：**缺什么就补什么，已有的一个字节都不动**。
 *
 * <h2>★ 为什么是「文本级插入」而不是「YamlConfiguration.save()」</h2>
 * {@code YamlConfiguration.save()} 会把**注释全部丢掉** —— 而本项目的出厂配置里，
 * 注释承担了「这个键是什么、为什么是这个值、改错了会怎样」的全部说明
 * （见 {@code config.yml} 的头部说明）。用 save() 做迁移等于每次升级都把说明书撕掉。
 * ⇒ 迁移只做**文本级插入**：
 * <ul>
 *   <li>顶层段落整个缺失 ⇒ 把出厂文件里那一段的**原文**（含注释）追加到文件末尾；</li>
 *   <li>段落里的**子键**缺失 ⇒ 把出厂文件里那棵子树的**原文**（含注释）插到**它父段落的末尾**，
 *       并按父段落在现场的实际缩进**重排缩进**（现场用 4 空格也不用改回来）；</li>
 *   <li>现场已有的键（无论值是否被服主改过）**碰都不碰**。</li>
 * </ul>
 *
 * <h2>★ 覆盖范围（P8 硬前置，{@code docs/04} §16.12.1）</h2>
 * P2~P4 只补「顶层段落」，于是往 {@code display.yml} 的 {@code messages:} 里加一条文案
 * ⇒ **已有安装不会自动补齐** ⇒ {@code DisplayConfig} 的严格校验**拒绝启动**。
 * 本次把迁移扩展到**子键**（递归、保序、可对账），口径仍是**只增不改**。
 *
 * <h2>边界（明确说清做不到什么）</h2>
 * <ul>
 *   <li>✅ 顶层段落整个缺失 ⇒ 追加（升级加新功能时最常见的情形）；</li>
 *   <li>✅ 段落里的子键缺失 ⇒ 递归插入（含「段落 + 子键两层都缺」）；</li>
 *   <li>✅ 现场多的键（服主自己加的）⇒ **一律保留**；</li>
 *   <li>✅ 拿不到出厂副本 / 出厂副本内容非法 ⇒ **跳过并告警**，**绝不**为了「补齐」而覆盖整份文件
 *       （宁可少补，不可写坏 —— 铁律 10 的失败方向）；</li>
 *   <li>❌ 键被**改名 / 作废** ⇒ 不自动处理（属于破坏性迁移，要人读 {@code docs/03} §7）；</li>
 *   <li>❌ 现场某个键**类型与出厂不符**（出厂是段落、现场是字符串）⇒ 不递归、只告警，
 *       交给人处理（硬塞只会写坏）；</li>
 *   <li>❌ 流式写法（`{ ... }` / `[ ... ]`）**内部**的键不单独补（整行已存在即视为存在）。</li>
 * </ul>
 *
 * <h2>可测性</h2>
 * 全部判据都是**纯函数**（不改文件、不碰服务端、不打印日志）：
 * {@link #topLevelSections(String)} / {@link #missingSections(String, Set)} /
 * {@link #sectionBlock(String, String)} / {@link #subKeyPaths(String)} /
 * {@link #plan(String, String)}。IO（读资源 / 备份 / 写回 / 打日志）在调用方，
 * 由 {@link MigrationResult} 的 {@code text / added* / warnings / summary()} 驱动。
 */
public final class ConfigMigrator {

    private ConfigMigrator() {
    }

    /**
     * 取文本里的**顶层段落名**（列 0 的 `键:`），保序去重。
     *
     * <p>只认列 0：缩进的键属于某个段落内部，不是顶层段落。
     * 注释行（`#`）与纯空行不参与判定。
     */
    public static List<String> topLevelSections(String yamlText) {
        Set<String> sections = new LinkedHashSet<>();
        if (yamlText == null) {
            return List.of();
        }
        for (String line : yamlText.split("\\R", -1)) {
            if (line.isEmpty() || line.charAt(0) == '#' || Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            if (!key.isEmpty() && key.chars().allMatch(c -> Character.isLetterOrDigit(c)
                    || c == '-' || c == '_')) {
                sections.add(key);
            }
        }
        return List.copyOf(sections);
    }

    /** 出厂有、现场没有的顶层段落（保序）。 */
    public static List<String> missingSections(String shippedText, Set<String> liveSections) {
        List<String> missing = new ArrayList<>();
        for (String section : topLevelSections(shippedText)) {
            if (liveSections == null || !liveSections.contains(section)) {
                missing.add(section);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * 从出厂文本里取出某个顶层段落的**原文块**（含它上面的注释行）。
     *
     * <p>「含它上面的注释」很重要：本项目每个段落前面都有一段说明（为什么是这个值、
     * 改了会怎样），补键时必须一起带过去，否则服主看到的是一个没有解释的裸配置。
     */
    public static String sectionBlock(String yamlText, String section) {
        if (yamlText == null || section == null) {
            return "";
        }
        String[] lines = yamlText.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String key = topLevelKeyOf(lines[i]);
            if (key == null || !key.equals(section)) {
                continue;
            }
            int[] range = blockRange(lines, i, 0, 0);
            StringBuilder block = new StringBuilder();
            for (int j = range[0]; j < range[1]; j++) {
                block.append(lines[j]).append('\n');
            }
            return block.toString();
        }
        return "";
    }

    // ==================================================================================
    // 子键：解析（保序树）、缺失判定、文本级插入计划
    // ==================================================================================

    /**
     * 文本里**所有子键**（段落内部的键，递归）的点号路径，保序。
     *
     * <p>只作**诊断 / 测试**用：键名本身可能含点（本项目的 `messages.shop.title` 就是一个键），
     * 所以这个字符串不代表「YAML 的真实层级」。真正定位键用的是缩进树。
     */
    public static List<String> subKeyPaths(String yamlText) {
        if (yamlText == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (Node root : parse(yamlText).roots.values()) {
            collectPaths(root, "", paths);
        }
        return List.copyOf(paths);
    }

    private static void collectPaths(Node node, String prefix, List<String> out) {
        String path = prefix.isEmpty() ? node.key : prefix + "." + node.key;
        if (!prefix.isEmpty()) {
            out.add(path);
        }
        for (Node child : node.children.values()) {
            collectPaths(child, path, out);
        }
    }

    /**
     * ★ <b>迁移计划</b>：出厂文本 × 现场文本 ⇒ 补齐后的**新文本** + 可对账的数量 + 告警。
     *
     * <p><b>纯函数</b>：不读盘、不写盘、不备份、不打印。调用方的义务：
     * <ol>
     *   <li>{@link MigrationResult#changed()} 为 false ⇒ <b>不要写文件</b>（一个字节都别动）；</li>
     *   <li>要写 ⇒ <b>先备份</b> {@code <文件名>.bak}（升级动作必须可回退）；</li>
     *   <li>写完打一条日志，内容用 {@link MigrationResult#summary()}（★ 带数量，便于对账）。</li>
     * </ol>
     *
     * <p>失败方向（铁律 10）：出厂副本缺失 / 为空 / 解析不出任何键 / 缩进用了 TAB（YAML 非法）
     * ⇒ <b>原样返回现场文本 + 一条 warning</b>，绝不「猜一份出厂内容」去覆盖现场。
     */
    public static MigrationResult plan(String shippedText, String liveText) {
        List<String> warnings = new ArrayList<>();
        if (liveText == null) {
            warnings.add("现场文本不可读（null）");
            return new MigrationResult("", List.of(), List.of(), List.of(), List.copyOf(warnings));
        }
        String shippedIssue = shippedIssue(shippedText);
        if (shippedIssue != null) {
            warnings.add(shippedIssue);
            return new MigrationResult(liveText, List.of(), List.of(), List.of(), List.copyOf(warnings));
        }

        Doc shipped = parse(shippedText);
        Doc live = parse(liveText);
        List<Insert> inserts = new ArrayList<>();
        List<String> addedSections = new ArrayList<>();
        List<String> addedSubKeys = new ArrayList<>();
        List<String> existingSubKeys = new ArrayList<>();

        for (Node section : shipped.roots.values()) {
            Node liveNode = live.roots.get(section.key);
            if (liveNode == null) {
                // 顶层的键（段落或 `config-version` 这类叶子）整个缺失 ⇒ 原文追加到文件末尾
                int[] range = blockRange(shipped.lines, section.lineIndex, section.indent, 0);
                inserts.add(new Insert(endOfText(live.lines), slice(shipped.lines, range)));
                addedSections.add(section.key);
                continue;
            }
            if (section.section) {
                collect(shipped, live, section, liveNode, section.key,
                        inserts, addedSubKeys, existingSubKeys, warnings);
            }
        }

        if (addedSections.isEmpty() && addedSubKeys.isEmpty()) {
            return new MigrationResult(liveText, List.of(), List.of(),
                    List.copyOf(existingSubKeys), List.copyOf(warnings));
        }
        String newText = materialize(liveText, live.lines, inserts);
        return new MigrationResult(newText, List.copyOf(addedSections), List.copyOf(addedSubKeys),
                List.copyOf(existingSubKeys), List.copyOf(warnings));
    }

    /** 递归比对：出厂段落 vs 现场同名段落，缺的子键按**原文**插入到现场该段落的末尾。 */
    private static void collect(Doc shipped, Doc live, Node shippedNode, Node liveNode, String prefix,
                                List<Insert> inserts, List<String> addedSubKeys,
                                List<String> existingSubKeys, List<String> warnings) {
        if (!liveNode.section) {
            warnings.add("现场 " + describe(prefix, liveNode.key) + " 不是段落（是个值），"
                    + "它下面的出厂子键一律不补 —— 交给人处理，绝不覆盖");
            return;
        }
        int at = insertionIndex(live.lines, liveNode);
        for (Node child : shippedNode.children.values()) {
            String path = prefix.isEmpty() ? child.key : prefix + "." + child.key;
            Node liveChild = liveNode.children.get(child.key);
            if (liveChild != null) {
                // ★ 已存在的键：**值一个字都不动**（哪怕它与出厂不同 —— 那可能是服主改的）
                if (!child.section) {
                    existingSubKeys.add(path);
                } else if (!liveChild.section) {
                    warnings.add("现场 " + path + " 是单个值、而出厂是段落，"
                            + "其下的出厂子键不补 —— 交给人处理");
                } else {
                    collect(shipped, live, child, liveChild, path,
                            inserts, addedSubKeys, existingSubKeys, warnings);
                }
                continue;
            }
            // 缺失 ⇒ 把出厂里这棵子树（含它上方的注释）整块插进现场
            int targetIndent = childIndentInLive(liveNode, shippedNode, child);
            int[] range = blockRange(shipped.lines, child.lineIndex, child.indent, shippedNode.lineIndex);
            inserts.add(new Insert(at, reindent(slice(shipped.lines, range),
                    targetIndent - child.indent)));
            addPaths(child, path, addedSubKeys);
        }
    }

    /** 现场该段落里「子键应该顶在哪一列」：优先照现场已有子键的实际缩进，没有则按出厂相对深度推。 */
    private static int childIndentInLive(Node liveNode, Node shippedNode, Node shippedChild) {
        for (Node liveChild : liveNode.children.values()) {
            return liveChild.indent;
        }
        return liveNode.indent + (shippedChild.indent - shippedNode.indent);
    }

    private static void addPaths(Node node, String path, List<String> out) {
        out.add(path);
        for (Node child : node.children.values()) {
            addPaths(child, path + "." + child.key, out);
        }
    }

    private static String describe(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    /** 段落末尾的插入行号（把属于下一段落的空行 / 注释让出去）。 */
    private static int insertionIndex(String[] lines, Node node) {
        int end = node.endLine;
        while (end > node.lineIndex + 1 && isCaption(lines[end - 1])) {
            end--;
        }
        return end;
    }

    /** 文件末尾（去掉尾部空行），顶层缺失段落追加在这里。 */
    private static int endOfText(String[] lines) {
        int end = lines.length;
        while (end > 0 && lines[end - 1].isBlank()) {
            end--;
        }
        return end;
    }

    /** 把插入计划落到文本上。★ 同一插入点内的顺序 = 出厂顺序（稳定排序）；换行风格跟随现场。 */
    private static String materialize(String liveText, String[] lines, List<Insert> inserts) {
        String newline = liveText.contains("\r\n") ? "\r\n" : "\n";
        List<Insert> ordered = new ArrayList<>(inserts);
        ordered.sort(Comparator.comparingInt(Insert::lineIndex));
        List<String> out = new ArrayList<>(lines.length + 16);
        int cursor = 0;
        int i = 0;
        while (i < ordered.size()) {
            int at = ordered.get(i).lineIndex();
            while (cursor < at && cursor < lines.length) {
                out.add(lines[cursor++]);
            }
            boolean needBlank = !out.isEmpty() && !out.get(out.size() - 1).isBlank();
            while (i < ordered.size() && ordered.get(i).lineIndex() == at) {
                if (needBlank) {
                    out.add("");
                    needBlank = false;
                }
                out.addAll(ordered.get(i).lines());
                i++;
            }
        }
        while (cursor < lines.length) {
            out.add(lines[cursor++]);
        }
        return String.join(newline, out);
    }

    /** 出厂副本的健全性检查；返回 null 表示可用，否则返回「为什么不能用」。 */
    private static String shippedIssue(String shippedText) {
        if (shippedText == null || shippedText.isBlank()) {
            return "拿不到出厂副本（内容为空），跳过迁移，不改现场文件";
        }
        if (topLevelSections(shippedText).isEmpty()) {
            return "出厂副本解析不出任何顶层键（内容非法？），跳过迁移，不改现场文件";
        }
        for (String line : shippedText.split("\\R", -1)) {
            String lead = line.substring(0, Math.min(indentOf(line), line.length()));
            if (lead.indexOf('\t') >= 0) {
                return "出厂副本的缩进里有 TAB（YAML 非法），跳过迁移，不改现场文件";
            }
        }
        return null;
    }

    // ----------------------------------------------------------------------------------
    // 文本解析：缩进树（保序）
    // ----------------------------------------------------------------------------------

    /** 一个键：缩进、行号、是不是段落（冒号后没内容）、以及它的子键（**保序**）。 */
    private static final class Node {
        final String key;
        final int indent;
        final int lineIndex;
        final boolean section;
        final Map<String, Node> children = new LinkedHashMap<>();
        int endLine;

        Node(String key, int indent, int lineIndex, boolean section) {
            this.key = key;
            this.indent = indent;
            this.lineIndex = lineIndex;
            this.section = section;
        }
    }

    /** 解析结果：原文行 + 顶层键（保序）。 */
    private static final class Doc {
        final String[] lines;
        final Map<String, Node> roots = new LinkedHashMap<>();

        Doc(String[] lines) {
            this.lines = lines;
        }
    }

    /**
     * 把文本解析成**缩进树**（保序）。
     *
     * <p>刻意只认 YAML 里本项目用到的形状：`键:` / `键: 值` / `- 序列项` / `# 注释`。
     * 序列项不建节点（它们只是延长父节点的范围）；流式写法（`{ ... }` / `[ ... ]`）算**叶子**。
     * 「键名里带点」不拆（`messages.shop.title` 就是一个键，见 {@code AGENTS.md} §4.2 实测）。
     */
    private static Doc parse(String yamlText) {
        String[] lines = yamlText.split("\\R", -1);
        Doc doc = new Doc(lines);
        List<Node> stack = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // ★ 2026-10-15 修：这里原来是 `firstNonBlank(line) == '#'` ——
            //   `firstNonBlank` 返回的是**下标**（int），拿它去比字符 `'#'`（= 35）
            //   ⇒ 只有"首个非空字符恰好在第 35 列"的注释才会被跳过，其余缩进注释**全被当成子键**。
            //   后果：带半角冒号的注释会变成**幽灵键**（如 `messages.# 代码出处：game/A.java`），
            //   于是 `plan()` 把它当"缺键"⇒ **往用户的现场文件里注入注释行**。
            //   ⇒ 正确写法是取那个下标的**字符**再比。
            int firstNonBlank = firstNonBlank(line);
            if (line.isBlank() || (firstNonBlank < line.length() && line.charAt(firstNonBlank) == '#')) {
                continue;
            }
            String body = line.trim();
            if (body.startsWith("-")) {
                continue;   // 序列项：不建节点，留给父节点当范围
            }
            int colon = keyColon(body);
            if (colon <= 0) {
                continue;
            }
            String key = body.substring(0, colon).trim();
            if (key.isEmpty()) {
                continue;
            }
            String tail = body.substring(colon + 1).trim();
            boolean section = tail.isEmpty() || tail.charAt(0) == '#';
            int indent = indentOf(line);
            Node node = new Node(key, indent, i, section);
            while (!stack.isEmpty() && stack.get(stack.size() - 1).indent >= indent) {
                stack.remove(stack.size() - 1).endLine = i;
            }
            if (stack.isEmpty()) {
                doc.roots.putIfAbsent(key, node);
            } else {
                stack.get(stack.size() - 1).children.putIfAbsent(key, node);
            }
            stack.add(node);
        }
        while (!stack.isEmpty()) {
            stack.remove(stack.size() - 1).endLine = lines.length;
        }
        return doc;
    }

    /**
     * 某个键的**原文块**行区间 {@code [start, end)}：含它上方的注释（当且仅当那些注释缩进不浅于它），
     * 到「下一个缩进不深于它的键」为止，并让出尾部属于下一段的空行 / 注释。
     *
     * @param lowerBound 不许越过这个行号（子键场景传父键的行号，免得吃掉上一段的内容）
     */
    private static int[] blockRange(String[] lines, int keyLine, int keyIndent, int lowerBound) {
        int start = keyLine;
        while (start > lowerBound && isCaption(lines[start - 1])
                && indentOf(lines[start - 1]) >= keyIndent) {
            start--;
        }
        // ★ 再把**行首的空行**去掉：段落块应当以它的注释标题开头，
        //   否则追加到现场文件时会出现一个来路不明的空行（被自己的单测抓出来过）
        while (start < keyLine && lines[start].isBlank()) {
            start++;
        }
        int end = keyLine + 1;
        while (end < lines.length) {
            if (isKeyLine(lines[end]) && indentOf(lines[end]) <= keyIndent) {
                break;
            }
            end++;
        }
        while (end > start && isCaption(lines[end - 1])) {
            end--;
        }
        return new int[]{start, end};
    }

    private static List<String> slice(String[] lines, int[] range) {
        List<String> out = new ArrayList<>(range[1] - range[0]);
        for (int i = range[0]; i < range[1]; i++) {
            out.add(lines[i]);
        }
        return out;
    }

    /** 按现场缩进把出厂原文块整体平移（空行不动；缩进不会被压到负数）。 */
    private static List<String> reindent(List<String> block, int delta) {
        if (delta == 0) {
            return block;
        }
        List<String> out = new ArrayList<>(block.size());
        for (String line : block) {
            if (line.isEmpty()) {
                out.add(line);
                continue;
            }
            int indent = indentOf(line);
            out.add(" ".repeat(Math.max(0, indent + delta)) + line.substring(indent));
        }
        return out;
    }

    /** 该行若是「列 0 的 `键:`」，返回键名；否则 null。 */
    private static String topLevelKeyOf(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) == '#' || Character.isWhitespace(line.charAt(0))) {
            return null;
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String key = line.substring(0, colon).trim();
        return key.isEmpty() ? null : key;
    }

    /** 这一行是不是「键行」（不是空行 / 注释 / 序列项，且冒号前有键名）。 */
    private static boolean isKeyLine(String line) {
        if (line == null || line.isBlank() || firstNonBlank(line) == '#') {
            return false;
        }
        String body = line.trim();
        return !body.startsWith("-") && keyColon(body) > 0;
    }

    /** 冒号的位置（键名必须非空）。 */
    private static int keyColon(String body) {
        int colon = body.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        return body.substring(0, colon).trim().isEmpty() ? -1 : colon;
    }

    /** 缩进列数（空格 / TAB 都按 1 算；出厂副本里的 TAB 会被 {@link #shippedIssue} 拦掉）。 */
    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int firstNonBlank(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * 注释行或空行 —— 它们属于**紧接着的**那个段落。
     *
     * <p>★ 必须认**缩进的**注释：段落内部的注释是缩进写的（出厂 {@code display.yml} 里
     * {@code messages:} 下面的注释就在缩进 2 的位置）。只认列 0 的 {@code #} 会让
     * 「子键的原文块」把**下一条子键的注释**也吞进来（被 {@code missingSubKeyIsAdded} 抓出来过）。
     */
    private static boolean isCaption(String line) {
        if (line == null || line.isEmpty()) {
            return true;
        }
        int i = firstNonBlank(line);
        return i >= line.length() || line.charAt(i) == '#';
    }

    /** 一次文本插入：在 {@code lineIndex} 之前插入若干行。 */
    private record Insert(int lineIndex, List<String> lines) {
    }

    /**
     * 迁移计划的结果（★ 纯数据，便于断言与打日志）。
     *
     * @param text            补齐后的新文本；{@code changed()} 为 false 时**等于现场原文**
     * @param addedSections   补上的顶层键（段落，或 `config-version` 这类顶层叶子）
     * @param addedSubKeys    补上的子键路径（**含被整块带进来的子孙**，保序）
     * @param existingSubKeys 出厂有、现场也有的**叶子**子键路径（= 为对账而说的「跳过了几个」）
     * @param warnings        跳过 / 不处理的原因（调用方应原样打进日志）
     */
    public record MigrationResult(String text,
                                  List<String> addedSections,
                                  List<String> addedSubKeys,
                                  List<String> existingSubKeys,
                                  List<String> warnings) {

        public boolean changed() {
            return !addedSections.isEmpty() || !addedSubKeys.isEmpty();
        }

        /** ★ 给日志用的一句话（**带数量**，便于对账）。 */
        public String summary() {
            if (!changed()) {
                if (!warnings.isEmpty()) {
                    return "配置迁移：已跳过，未改动文件（" + String.join("；", warnings) + "）";
                }
                return "配置迁移：无缺失键（出厂子键 " + existingSubKeys.size()
                        + " 个在现场都存在），未改动文件";
            }
            return "配置迁移：补齐 " + addedSubKeys.size() + " 个缺失子键（跳过 "
                    + existingSubKeys.size() + " 个已存在）、"
                    + addedSections.size() + " 个缺失顶层段落，原有内容一个字节都没改";
        }
    }
}
