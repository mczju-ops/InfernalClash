package io.mczju.infernalclash.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ★ <b>纯</b>转换层：MGC 档案里的原始字段 ↔ 纯值对象 {@link PlayerProgress}。
 *
 * <h2>★ 为什么这里**不出现** MGC 的类型（选 (b)：中性载体）</h2>
 * MGC 有<b>两套</b>持久化，只有一套能用（{@code docs/08} §8.1、【铁律 3】`javap` 实测）：
 * <ol>
 *   <li>❌ {@code profile.ProfileStorageManager} / {@code ProfileData} / {@code PlayerSnapshot}
 *       —— 只存<b>装备栏 + 经验</b>（{@code PlayerSnapshot(byte[] main, byte[] armor, byte[] offhand,
 *       byte[] ender, int xpLevel, float xpProgress)}），<b>存不下</b>金币 / 卡牌 / 预设 ⇒ 本插件不用它；</li>
 *   <li>✅ {@code player.data.PlayerDataManager} + {@code AbstractPlayerData} 子类
 *       —— 「按 gameId 注册、每玩家一个 JSON 落盘」的通用系统 ⇒ 本插件走这条
 *       （见 {@link InfernalPlayerData}）。</li>
 * </ol>
 *
 * <p>如果本类直接以 {@link InfernalPlayerData} 为参数，它的字节码里就会出现
 * {@code mczjugamecore} 记号 ⇒ 它<b>不再纯净</b>（{@code PolicyPurityGuardTest} 的判据就是
 * 「该类的常量池里有没有 {@code org/bukkit} / {@code io/papermc} / {@code mczjugamecore}」）。
 * ⇒ 本类只认自己的<b>中性载体</b> {@link Fields}（一堆 int / List / Map，没有任何 MGC 类型），
 * 由 MGC 侧的 {@link InfernalPlayerData#toFields()} / {@link InfernalPlayerData#applyFields}
 * 做一次<b>无脑搬运</b>。
 *
 * <h2>★ 依赖方向（只能是这一个方向）</h2>
 * <pre>
 *   InfernalPlayerData（MGC 侧，不纯）  ──引用──▶  ProgressMapper.Fields（纯）
 *   InfernalPlayerData（MGC 侧，不纯）  ──引用──▶  ProgressMapper.FORMAT_VERSION（纯）
 * </pre>
 * 纯侧<b>从不</b>引用 MGC 侧 —— 所以「格式版本」的唯一定义在
 * {@link #FORMAT_VERSION}，{@link InfernalPlayerData} 反过来读它（反过来写就会破坏纯度）。
 *
 * <h2>★ 修复（坏数据）策略：一处、且只有一处</h2>
 * 读档案是唯一会碰到「手工改坏的 JSON / 旧版本残留」的地方，本类的策略是
 * <b>修成一致的（repair-to-consistency），绝不抛异常</b>（{@code docs/04} §16.11.3 的失败方向：
 * 「档案读不出来 ⇒ 不扣不发」，而不是让玩家进不了游戏）：
 *
 * <table border="1">
 *   <caption>逐类处置</caption>
 *   <tr><th>坏数据</th><th>处置</th><th>理由</th></tr>
 *   <tr>
 *     <td>{@code codex} 里有、{@code owned} 里没有的卡</td>
 *     <td><b>丢弃该图鉴条目</b>（不写进 owned）</td>
 *     <td>★ {@code owned} 是「拥有」的<b>唯一真相</b>；{@code codex} 只是它的品质标注
 *         （{@code docs/08} §3.3 /【Q31】：金色与普通是<b>同一张卡</b>、单条目 + 品质标记）。
 *         反过来「补进 owned」= <b>凭空发卡</b> —— 发放是不可逆的，失败方向必须偏保守</td>
 *   </tr>
 *   <tr>
 *     <td>{@code owned} 里有、{@code codex} 里没有的卡</td>
 *     <td><b>补一条 {@code NORMAL}</b></td>
 *     <td>同一张卡的品质标注缺省就是普通版；不补的话 {@link PlayerProgress#codexState}
 *         会读成 {@code NONE}（"未拥有"）而 {@link PlayerProgress#owns} 又是 {@code true} ⇒
 *         自相矛盾，且 {@link PlayerProgress} 的构造器虽不拦它、图鉴界面会显示错</td>
 *   </tr>
 *   <tr>
 *     <td>{@code codex[id] = NONE} 但 {@code owned} 里有 {@code id}</td>
 *     <td><b>修成 {@code NORMAL}</b></td>
 *     <td>{@link PlayerProgress} 的构造器对此<b>直接抛异常</b>（"图鉴与仓库必须对得上"）
 *         ⇒ 不修就等于玩家进不了游戏</td>
 *   </tr>
 *   <tr>
 *     <td>品质标记既不是 NONE / NORMAL / GOLD（例如 {@code "LEGENDARY"}）</td>
 *     <td>该卡<b>在 owned 里</b> ⇒ 修成 {@code NORMAL}；<b>不在</b> ⇒ 按上一条丢弃</td>
 *     <td>同上：先看 owned 定"有没有"，再看品质</td>
 *   </tr>
 *   <tr>
 *     <td>负数（{@code gold} / {@code dust} / 计数）</td>
 *     <td><b>夹到 0</b></td>
 *     <td>{@link PlayerProgress} 要求非负；读档案时抛异常 = 玩家整个进度打不开</td>
 *   </tr>
 *   <tr>
 *     <td>{@code owned} / {@code codex} / {@code presets} 里的 null、空白 id、重复项</td>
 *     <td>null / 空白 ⇒ <b>丢弃该条目</b>；重复 ⇒ 保留第一次出现（保序）</td>
 *     <td>{@link PlayerProgress} 的构造器对 null / 空白 key 抛异常；重复项无声地让"拥有数"偏大</td>
 *   </tr>
 *   <tr>
 *     <td>整套预设是 {@code null}（或字段缺失）</td>
 *     <td>当成<b>空槽</b>（{@code List.of()}）</td>
 *     <td>{@link PlayerProgress#withPreset} 的约定："空列表 = 该槽尚未设置"</td>
 *   </tr>
 * </table>
 *
 * <p>★ 每一步修复都写进 {@link ReadResult#notes()}（保序的中文说明）；
 * 本类<b>没有</b>日志器（纯类不许碰 Bukkit / MGC），装配层拿 {@link #read} 把这些说明打进日志。
 *
 * <p>★ <b>本类不校验预设的合法性</b>（恰好 8 张 / 不重复 / 必须已拥有）——
 * 那是 {@link PresetRules#validate} 在使用时判的事，存档里允许留下<b>需要降级处理</b>的旧预设
 * （{@code docs/08} §6）。
 *
 * <p>★ <b>本类不猜数值</b>（金币初始值、售价、兑换表……）：{@code docs/04} §16.11.2 /
 * 【铁律 8】——数值只在 {@code config/EconomyConfig}（{@code economy.yml}）里，装配时传进算法层。
 */
public final class ProgressMapper {

    /**
     * ★ 档案格式版本（**唯一定义在这里**，{@link InfernalPlayerData} 反过来读它）。
     *
     * <p>将来字段变了（改名 / 改类型 / 拆结构）就<b>加这个数</b>，并在 {@link #read} 里按
     * 档案里的 {@code formatVersion} 做迁移；缺省 {@code 0} = 比 v1 还老的档案。
     */
    public static final int FORMAT_VERSION = 1;

    /** 品质标记的合法取值（复用 {@link PlayerProgress} 的三个常量，判据只有一处）。 */
    private static final List<String> CODEX_STATES = List.of(
            PlayerProgress.CODEX_NONE,
            PlayerProgress.CODEX_NORMAL,
            PlayerProgress.CODEX_GOLD);

    private ProgressMapper() {
    }

    // ------------------------------------------------------------------ 中性载体

    /**
     * ★ <b>中性载体</b>：{@link ProgressMapper} 与 MGC 档案（{@link InfernalPlayerData}）
     * 之间的**唯一**交换形态 —— 全是 JDK 类型，没有任何 MGC / Bukkit 类型。
     *
     * <p>字段与 {@link InfernalPlayerData} 的 public 字段**一一对应**（就是"无脑搬运"的搬运面）；
     * 两边都允许为 {@code null}（旧档案缺字段 / JSON 里写了 {@code null}）——
     * <b>修复全部集中在 {@link #read}</b>，本载体自己不做任何判断。
     */
    public static final class Fields {

        /** 档案格式版本（缺省 = 当前版本 {@link ProgressMapper#FORMAT_VERSION}）。 */
        public int formatVersion = FORMAT_VERSION;

        /** 金币（R47 的虚拟值）。 */
        public int gold;

        /** 尘 = 分解重复卡所得的烈焰粉（R46）。 */
        public int dust;

        /** 已拥有的卡 id（★ 保序 = 落盘顺序）。 */
        public List<String> owned = new ArrayList<>();

        /** 卡 id → 图鉴品质标记（★ 保序）。 */
        public Map<String, String> codex = new LinkedHashMap<>();

        /** 卡组预设（每套一串卡 id；★ 保序）。 */
        public List<List<String>> presets = new ArrayList<>();

        /** 已开卡包数。 */
        public int packsOpened;

        /** 已完成对局数。 */
        public int gamesPlayed;
    }

    /**
     * 一次读取的结果：进度 + **保序**的中文说明（修复 / 兼容备注；空列表 = 档案本来就一致）。
     *
     * @param progress 读出来的纯值对象（★ 已经与 {@link PlayerProgress} 的不变量一致）
     * @param notes    ★ 逐条修复说明（装配层应把它打进日志；纯类自己没有日志器）
     */
    public record ReadResult(PlayerProgress progress, List<String> notes) {

        public ReadResult {
            notes = List.copyOf(notes);
        }

        /** 这次读取有没有动过档案里的值（{@code false} = 原样读出）。 */
        public boolean wasRepaired() {
            return !notes.isEmpty();
        }
    }

    // ------------------------------------------------------------------ 读

    /**
     * 把中性载体读成纯值对象（**防御性拷贝**：读完之后再改载体，已读出的进度不受影响 ——
     * 拷贝由 {@link PlayerProgress} 的规范构造器完成）。
     *
     * <p>坏数据按类注释里的策略<b>修成一致的</b>，绝不抛异常（{@code null} 载体 ⇒ 空白进度）。
     * 需要看修复说明请用 {@link #read}（本方法 = {@code read(...).progress()}）。
     *
     * @param playerId 玩家 UUID（不可为 {@code null}）
     * @param fields   中性载体；{@code null} ⇒ 等价于 {@link PlayerProgress#fresh}
     * @throws IllegalArgumentException {@code playerId} 为 {@code null}
     */
    public static PlayerProgress toProgress(UUID playerId, Fields fields) {
        return read(playerId, fields).progress();
    }

    /**
     * 同 {@link #toProgress}，但**连修复说明一起返回**。
     *
     * <p>★ 存在的理由：纯类不许碰 Bukkit / MGC，所以"告警"只能交回给装配层去记日志；
     * 没有这个方法的话，"修好了但没人知道"就成了一条隐形的数据损坏通道。
     *
     * @param playerId 玩家 UUID（不可为 {@code null}）
     * @param fields   中性载体；{@code null} ⇒ 空白进度 + 一条说明
     * @throws IllegalArgumentException {@code playerId} 为 {@code null}
     */
    public static ReadResult read(UUID playerId, Fields fields) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId 不能为 null（进度必须能归属到一个玩家）");
        }
        if (fields == null) {
            return new ReadResult(PlayerProgress.fresh(playerId),
                    List.of("档案为 null（玩家第一次进来 / 还没落过盘），按新玩家的空白进度处理"));
        }

        List<String> notes = new ArrayList<>();

        if (fields.formatVersion > FORMAT_VERSION) {
            notes.add("档案版本 v" + fields.formatVersion + " 高于本插件认识的 v" + FORMAT_VERSION
                    + "，仍按 v" + FORMAT_VERSION + " 的字段读，不认识的字段会被丢掉"
                    + "（降级服务器前先备份玩家档案）");
        }

        int gold = clampNonNegative(fields.gold, "gold", "金币", notes);
        int dust = clampNonNegative(fields.dust, "dust", "尘（烈焰粉）", notes);
        int packsOpened = clampNonNegative(fields.packsOpened, "packsOpened", "已开卡包数", notes);
        int gamesPlayed = clampNonNegative(fields.gamesPlayed, "gamesPlayed", "已完成对局数", notes);

        Set<String> owned = readOwned(fields.owned, notes);
        Map<String, String> codex = readCodex(fields.codex, owned, notes);
        List<List<String>> presets = readPresets(fields.presets, notes);

        // ★ 防御性拷贝与不变量校验都在 PlayerProgress 的规范构造器里（判据只有一处）
        return new ReadResult(
                new PlayerProgress(playerId, gold, dust, owned, codex, presets, packsOpened, gamesPlayed),
                notes);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 把纯值对象写回中性载体（**原地写**：同一个 {@link Fields} 实例的字段被逐个覆盖）。
     *
     * <p>★ 一律<b>赋新集合</b>，绝不 {@code clear()} / 就地改传入的集合 ——
     * 这样"传出档案对象"和"写回档案对象"不会互相踩（{@link InfernalPlayerData#toFields()}
     * 递过来的引用即使被赋值也不会反向改到档案）。
     *
     * <p>★ 同时把载体的 {@code formatVersion} 抬到 {@link #FORMAT_VERSION}
     * （写入即升级：迁移只需要在 {@link #read} 一处做）。
     *
     * @param progress 纯值对象（不可为 {@code null}；新玩家用 {@link PlayerProgress#fresh}）
     * @param data     目标载体（不可为 {@code null}）
     * @throws IllegalArgumentException 任一参数为 {@code null}
     */
    public static void apply(PlayerProgress progress, Fields data) {
        if (data == null) {
            throw new IllegalArgumentException("data（中性载体 ProgressMapper.Fields）不能为 null："
                    + "apply 是原地写，没有目标就写不进去；"
                    + "新建档案请用 new ProgressMapper.Fields() 或 InfernalPlayerData.toFields()");
        }
        if (progress == null) {
            throw new IllegalArgumentException("progress 不能为 null：没有源值就写不出档案"
                    + "（新玩家请用 PlayerProgress.fresh(playerId)）");
        }

        data.formatVersion = FORMAT_VERSION;
        data.gold = progress.gold();
        data.dust = progress.dust();
        data.owned = new ArrayList<>(progress.owned());
        data.codex = new LinkedHashMap<>(progress.codex());

        List<List<String>> presets = new ArrayList<>(progress.presets().size());
        for (List<String> preset : progress.presets()) {
            presets.add(new ArrayList<>(preset));
        }
        data.presets = presets;

        data.packsOpened = progress.packsOpened();
        data.gamesPlayed = progress.gamesPlayed();
    }

    // ------------------------------------------------------------------ 内部（修复逻辑，只有这一处）

    /** 负数夹到 0（理由见类注释：读档案时抛异常 = 玩家整个进度打不开）。 */
    private static int clampNonNegative(int value, String key, String label, List<String> notes) {
        if (value < 0) {
            notes.add(key + "（" + label + "）是负数：实际 " + value + "，夹到 0"
                    + "（PlayerProgress 要求非负；档案在落盘后被手工改过？）");
            return 0;
        }
        return value;
    }

    /** 已拥有卡：丢 null / 空白，重复的保留第一次出现（★ 保序）。 */
    private static Set<String> readOwned(List<String> raw, List<String> notes) {
        Set<String> owned = new LinkedHashSet<>();
        if (raw == null) {
            notes.add("owned 字段缺失（旧档案 / 被手工删掉），当成「一张卡都没有」");
            return owned;
        }
        for (int i = 0; i < raw.size(); i++) {
            String cardId = raw.get(i);
            if (cardId == null) {
                notes.add("owned 第 " + (i + 1) + " 格是 null，丢弃这一格（期望一张卡的 id）");
                continue;
            }
            if (cardId.isBlank()) {
                notes.add("owned 第 " + (i + 1) + " 格是空白字符串，丢弃这一格（期望一张卡的 id）");
                continue;
            }
            if (!owned.add(cardId)) {
                notes.add("owned 里「" + cardId + "」重复出现，只保留第一次出现的位置（保持顺序）");
            }
        }
        return owned;
    }

    /**
     * 图鉴：★ <b>以 {@code owned} 为唯一真相</b> —— 先丢掉"没有这张卡"的记录，
     * 再把品质标记修成合法值，最后给缺记录的已拥有卡补 {@code NORMAL}。
     */
    private static Map<String, String> readCodex(Map<String, String> raw, Set<String> owned,
                                                List<String> notes) {
        Map<String, String> codex = new LinkedHashMap<>();

        if (raw == null) {
            notes.add("codex 字段缺失（旧档案 / 被手工删掉），当成「一条图鉴记录都没有」");
        } else {
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                String cardId = entry.getKey();
                String state = entry.getValue();

                if (cardId == null || cardId.isBlank()) {
                    notes.add("codex 里有一条键为" + (cardId == null ? " null" : "空白字符串")
                            + " 的记录，丢弃这一条（期望一张卡的 id）");
                    continue;
                }
                if (!owned.contains(cardId)) {
                    notes.add("codex[" + cardId + "] 有记录（" + state + "）但 owned 里没有这张卡"
                            + "，丢弃该图鉴条目（以 owned 为唯一真相，绝不凭空发卡）");
                    continue;
                }
                if (!CODEX_STATES.contains(state)) {
                    notes.add("codex[" + cardId + "] 的品质标记非法：实际 " + state
                            + "，期望 " + CODEX_STATES + " 之一，修成 " + PlayerProgress.CODEX_NORMAL);
                    codex.put(cardId, PlayerProgress.CODEX_NORMAL);
                    continue;
                }
                if (PlayerProgress.CODEX_NONE.equals(state)) {
                    notes.add("codex[" + cardId + "] 是 NONE（未拥有）但 owned 里有它，修成 "
                            + PlayerProgress.CODEX_NORMAL);
                    codex.put(cardId, PlayerProgress.CODEX_NORMAL);
                    continue;
                }
                codex.put(cardId, state);
            }
        }

        for (String cardId : owned) {
            if (!codex.containsKey(cardId)) {
                notes.add("owned 里的「" + cardId + "」缺图鉴记录，补一条 " + PlayerProgress.CODEX_NORMAL);
                codex.put(cardId, PlayerProgress.CODEX_NORMAL);
            }
        }
        return codex;
    }

    /**
     * 预设：只丢 null / 空白格，整套 null ⇒ 空槽。
     *
     * <p>★ 这里<b>不</b>校验"恰好 8 张 / 不重复 / 已拥有"—— 那是 {@link PresetRules#validate}
     * 在使用时报的事（{@code docs/08} §6：卡牌被移除时要降级处理，旧存档允许留下有问题的预设）。
     */
    private static List<List<String>> readPresets(List<List<String>> raw, List<String> notes) {
        List<List<String>> presets = new ArrayList<>();
        if (raw == null) {
            notes.add("presets 字段缺失（旧档案 / 被手工删掉），当成「一套预设都没有」");
            return presets;
        }
        for (int i = 0; i < raw.size(); i++) {
            List<String> preset = raw.get(i);
            if (preset == null) {
                notes.add("presets 第 " + (i + 1) + " 套是 null，当成空槽（空列表 = 该槽尚未设置）");
                presets.add(List.of());
                continue;
            }
            List<String> clean = new ArrayList<>(preset.size());
            for (int j = 0; j < preset.size(); j++) {
                String cardId = preset.get(j);
                if (cardId == null) {
                    notes.add("presets 第 " + (i + 1) + " 套第 " + (j + 1)
                            + " 格是 null，丢弃这一格（期望一张卡的 id）");
                    continue;
                }
                if (cardId.isBlank()) {
                    notes.add("presets 第 " + (i + 1) + " 套第 " + (j + 1)
                            + " 格是空白字符串，丢弃这一格（期望一张卡的 id）");
                    continue;
                }
                clean.add(cardId);
            }
            presets.add(clean);
        }
        return presets;
    }
}
