package io.mczju.infernalclash.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ★ 一个玩家的**局外进度**（金币 / 尘 / 已拥有卡 / 图鉴 / 卡组预设 / 计数器）。
 *
 * <h2>★ 不可变值对象（唯一入口，绝不改原对象）</h2>
 * 所有变更方法（{@link #spendGold} / {@link #addGold} / {@link #addDust} / {@link #spendDust} /
 * {@link #unlock} / {@link #withPreset}）都**返回新实例**，原对象一个字段都不动。
 * 依据 {@code docs/04} §16.11.2：「玩家拥有的东西 = ★ 纯值对象（**不可变**），
 * 所有变更返回**新实例**（便于测试与回滚）」。
 *
 * <h2>★ 纯逻辑（两道守卫都盯这里）</h2>
 * 本类**不得**出现 {@code org.bukkit.*} / {@code io.mczju.mczjugamecore.*} ——
 * 它必须能在普通 JUnit 里跑，不需要服务器（{@code PolicyPurityGuardTest} +
 * {@code docs/04} §16.11.1「A. 纯模型 … ✅ 全部可单测（不碰 Bukkit/MGC）」）。
 *
 * <h2>★ 保序（同输入 ⇒ 同结果）</h2>
 * {@link #owned} / {@link #codex} / {@link #presets} 一律是**保序**的不可变副本
 * （{@code LinkedHashSet} / {@code LinkedHashMap} / {@code List}）。
 * ⚠️ 传入的 {@code Set} **其迭代顺序就是保存顺序** ⇒ 调用方必须传保序集合
 * （{@code LinkedHashSet}）；传 {@code HashSet} 时顺序本来就不确定，本类无法"修好"它。
 * 不许用 {@code Set.copyOf} / {@code Map.copyOf} —— 它们的迭代顺序是随机的
 * （见 {@code AGENTS.md} §4.1「{@code Map.copyOf} 迭代顺序随机」）。
 *
 * <h2>数值来源（不猜）</h2>
 * 金币单价、奖励、兑换表等**数值**一律不写在本类里：
 * 真正的数值由 {@code config/EconomyConfig}（{@code economy.yml}）在**装配时**传入算法层，
 * 本类只负责"存多少、够不够"。
 *
 * <h2>★ 本类**不**负责的事（别在这里补）</h2>
 * <ul>
 *   <li><b>开局送基础卡</b>（R44「玩家初始拥有所有基础品质的卡牌」）：
 *       {@link #fresh} 只给**空白**进度 —— 「哪些卡是基础档」只有卡池
 *       （{@code card/CardCatalog}）知道 ⇒ 由装配层用卡池里的 BASIC 卡 id 逐个
 *       {@link #unlock} 补上。**本类不猜**。</li>
 *   <li><b>开包与重复卡自动转尘</b>（{@code docs/08} §3.2、【Q30】）：抽卡必须复用
 *       P0 的 {@code policy/PackRoller}（**绝不另写一套随机**），
 *       重复卡的结算按 {@code docs/08} §4 的兑换表走——那需要稀有度与配置，
 *       属于**开包流程**（P8），不在本类里。</li>
 *   <li><b>预设的合法性</b>（8 张、不重复、必须已拥有）：见 {@link PresetRules}。
 *       本类只保证"结构合法"（{@link #withPreset} 会拦下张数/重复/空 id），
 *       <b>是否已拥有</b>要在使用时由 {@link PresetRules#validate} 判
 *       —— 因为卡牌可能被移除，存档里允许留下**需要降级处理**的旧预设
 *       （{@code docs/08} §6：卡牌被移除时要降级处理）。</li>
 * </ul>
 *
 * @param playerId    玩家 UUID（不可为 {@code null}）
 * @param gold        金币（≥ 0；R47 的虚拟值）
 * @param dust        尘 = 分解重复卡所得的烈焰粉（≥ 0；R46、{@code docs/08} §2）
 * @param owned       已拥有的卡 id（★ 保序、不可变）
 * @param codex       卡 id → 图鉴状态（★ 保序、不可变；取值 {@link #CODEX_NONE} /
 *                    {@link #CODEX_NORMAL} / {@link #CODEX_GOLD}，见 {@code docs/08} §3.3）
 * @param presets     卡组预设（★ 保序、不可变；每套 8 张卡 id，容量上限见
 *                    {@link PresetRules#PRESET_LIMIT}）
 * @param packsOpened 已开卡包数（≥ 0）
 * @param gamesPlayed 已完成对局数（≥ 0）
 */
public record PlayerProgress(
        UUID playerId,
        int gold,
        int dust,
        Set<String> owned,
        Map<String, String> codex,
        List<List<String>> presets,
        int packsOpened,
        int gamesPlayed) {

    /** 图鉴状态：未拥有（{@code docs/08} §3.3 的状态表）。 */
    public static final String CODEX_NONE = "NONE";

    /** 图鉴状态：只有普通版（{@code docs/08} §3.3）。 */
    public static final String CODEX_NORMAL = "NORMAL";

    /** 图鉴状态：拥有金色版 —— ★ 此时**不再**存在普通版（【Q30】已把它自动分解）。 */
    public static final String CODEX_GOLD = "GOLD";

    /** 图鉴状态的合法取值（★ 保序：报错消息里的顺序稳定 ⇒ 同输入同消息）。 */
    private static final List<String> CODEX_STATES =
            List.of(CODEX_NONE, CODEX_NORMAL, CODEX_GOLD);

    /**
     * ★ 规范构造器：做**防御性拷贝**（保序 + 不可变）并校验不变量。
     *
     * @throws IllegalArgumentException 任一参数非法；消息写清**哪个参数、期望什么、实际什么**
     */
    public PlayerProgress {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId 不能为 null（进度必须能归属到一个玩家）");
        }
        gold = requireNonNegative(gold, "gold", "金币");
        dust = requireNonNegative(dust, "dust", "尘（烈焰粉）");
        packsOpened = requireNonNegative(packsOpened, "packsOpened", "已开卡包数");
        gamesPlayed = requireNonNegative(gamesPlayed, "gamesPlayed", "已完成对局数");

        owned = copyOwned(owned);
        codex = copyCodex(codex);
        presets = copyPresets(presets);
        requireCodexMatchesOwned(owned, codex);
    }

    // ------------------------------------------------------------------ 工厂

    /**
     * 空白进度（新玩家）：金币 0 / 尘 0 / 无卡 / 无图鉴 / 无预设 / 计数全 0。
     *
     * <p>⚠️ ★ 这里**不发**基础卡（R44「玩家初始拥有所有基础品质的卡牌」）——
     * 「哪些卡属于基础档」只有卡池（{@code card/CardCatalog}）知道，
     * 本纯类猜不出来 ⇒ 由装配层用卡池里的 BASIC 卡 id 调 {@link #unlock} 补上。
     */
    public static PlayerProgress fresh(UUID playerId) {
        return new PlayerProgress(playerId, 0, 0, Set.of(), Map.of(), List.of(), 0, 0);
    }

    // ------------------------------------------------------------------ 查询

    /** 是否已拥有该卡（{@code docs/08} §8.3 的 {@code cards} 字段 = 本类的 {@link #owned}）。 */
    public boolean owns(String cardId) {
        requireCardId(cardId, "cardId", "owns");
        return owned.contains(cardId);
    }

    /**
     * 图鉴状态：没有记录时按 {@link #CODEX_NONE} 处理
     * （{@code docs/08} §3.3 的三态；"缺席 = NONE" 是本类唯一的读法）。
     */
    public String codexState(String cardId) {
        requireCardId(cardId, "cardId", "codexState");
        return codex.getOrDefault(cardId, CODEX_NONE);
    }

    /** 金币够不够。{@code goldCost} 为负 ⇒ 抛异常（负价不是"免费"）。 */
    public boolean canAfford(int goldCost) {
        if (goldCost < 0) {
            throw new IllegalArgumentException("goldCost 不能为负：实际 " + goldCost
                    + "（负价不是「免费」，请传 0 表示不要钱）");
        }
        return gold >= goldCost;
    }

    // ------------------------------------------------------------------ 变更（返回新实例）

    /**
     * 扣金币。
     *
     * @throws IllegalArgumentException 不够（消息含**还差多少**）或 {@code amount} 为负
     */
    public PlayerProgress spendGold(int amount) {
        requireAmount(amount, "amount", "spendGold");
        if (amount > gold) {
            throw new IllegalArgumentException("金币不足：需要 " + amount + "，当前 " + gold
                    + "，还差 " + (amount - gold)
                    + "（扣款被拒绝 —— docs/04 §16.11.3：钱不够就「不扣不发」，绝不先扣后补）");
        }
        return copy(gold - amount, dust, owned, codex, presets, packsOpened, gamesPlayed);
    }

    /** 加金币（{@code amount >= 0}；负数请用 {@link #spendGold}）。 */
    public PlayerProgress addGold(int amount) {
        requireAmount(amount, "amount", "addGold");
        return copy(checkedAdd(gold, amount, "金币"), dust, owned, codex, presets,
                packsOpened, gamesPlayed);
    }

    /** 加尘（{@code amount >= 0}；负数请用 {@link #spendDust}）。 */
    public PlayerProgress addDust(int amount) {
        requireAmount(amount, "amount", "addDust");
        return copy(gold, checkedAdd(dust, amount, "尘"), owned, codex, presets,
                packsOpened, gamesPlayed);
    }

    /**
     * 扣尘。
     *
     * @throws IllegalArgumentException 不够（消息含**还差多少**）或 {@code amount} 为负
     */
    public PlayerProgress spendDust(int amount) {
        requireAmount(amount, "amount", "spendDust");
        if (amount > dust) {
            throw new IllegalArgumentException("尘不足：需要 " + amount + "，当前 " + dust
                    + "，还差 " + (amount - dust)
                    + "（扣款被拒绝 —— docs/04 §16.11.3：不够就「不扣不发」）");
        }
        return copy(gold, dust - amount, owned, codex, presets, packsOpened, gamesPlayed);
    }

    /**
     * 解锁（合成）一张卡：加入 {@link #owned}，并把图鉴状态置为 {@link #CODEX_NORMAL}。
     *
     * <p>★ **已拥有 ⇒ 原样返回 {@code this}**（不改变 {@code owned} 的大小）。
     * <b>重复卡的转尘不在这里做</b> —— {@code docs/08} §3.2 /【Q30】的「抽到已拥有的卡
     * ⇒ 自动转成烈焰粉」属于**开包流程**的结算（要做需要稀有度 + 兑换表，见
     * {@link CraftRules}），本方法只管"拥有关系"。
     *
     * @throws IllegalArgumentException {@code cardId} 为 null / 空白
     */
    public PlayerProgress unlock(String cardId) {
        requireCardId(cardId, "cardId", "unlock");
        if (owned.contains(cardId)) {
            return this;
        }
        Set<String> nextOwned = new LinkedHashSet<>(owned);
        nextOwned.add(cardId);
        Map<String, String> nextCodex = new LinkedHashMap<>(codex);
        nextCodex.put(cardId, CODEX_NORMAL);
        return copy(gold, dust, nextOwned, nextCodex, presets, packsOpened, gamesPlayed);
    }

    /**
     * ★★★ <b>解锁一张卡的<b>金色版本</b></b>（2026-10-19 开包接入新增）。
     *
     * <p>口径（{@code docs/08} §3.3 + 【Q30】/【Q31】）：
     * <ul>
     *   <li>金卡**只能靠抽包获得**（【Q32】：不可手动合成）；</li>
     *   <li>抽到金卡时**不再存在普通版** —— 普通版会被自动分解并折算成烈焰粉，
     *       那是**开包流程**的结算（{@link PackOpening}），本方法只负责"拥有关系 + 图鉴状态"；</li>
     *   <li>已经拥有金卡 ⇒ 原样返回 {@code this}（重复金卡的转尘同样在 {@link PackOpening}）。</li>
     * </ul>
     *
     * @throws IllegalArgumentException {@code cardId} 为 null / 空白
     */
    public PlayerProgress unlockGold(String cardId) {
        requireCardId(cardId, "cardId", "unlockGold");
        if (CODEX_GOLD.equals(codex.get(cardId))) {
            return this;
        }
        Set<String> nextOwned = new LinkedHashSet<>(owned);
        nextOwned.add(cardId);
        Map<String, String> nextCodex = new LinkedHashMap<>(codex);
        nextCodex.put(cardId, CODEX_GOLD);
        return copy(gold, dust, nextOwned, nextCodex, presets, packsOpened, gamesPlayed);
    }

    /**
     * ★ <b>开包计数 +1</b>（{@code docs/08} §8.3 的 {@code packsOpened}）。
     *
     * <p>只做统计，不参与任何判据 —— 放在这里是为了让"开过的包数"与进度**同一次落盘**。
     *
     * @throws IllegalStateException 计数溢出（不把负值/回绕写进档案）
     */
    public PlayerProgress withPackOpened() {
        return copy(gold, dust, owned, codex, presets,
                checkedAdd(packsOpened, 1, "已开卡包数"), gamesPlayed);
    }

    /**
     * ★★★ <b>对局场次 +1</b>（{@code docs/08} §8.3 的 {@code gamesPlayed}，2026-10-20 新增）。
     *
     * <p>语义（{@code docs/24} §10 第 24 条用户裁定）：「一共打了多少局」——
     * <b>含人机局</b>、含平局、含败局；<b>不含</b>中止/关服那种没分出胜负的对局
     * （调用方只在 {@code phase == ENDED} 时调它）。
     *
     * <p>★ 它同时是**首通奖励的判据**：{@code MatchPayout} 看「结算前 {@code gamesPlayed == 0}」
     * 决定要不要发 {@code gold.first-game-bonus} ⇒ <b>本方法与金币必须同一次落盘</b>，
     * 否则会出现「发了首通、场次没加」⇒ 下一局再发一次。
     *
     * @throws IllegalStateException 计数溢出（不把负值 / 回绕写进档案）
     */
    public PlayerProgress withGamePlayed() {
        return copy(gold, dust, owned, codex, presets, packsOpened,
                checkedAdd(gamesPlayed, 1, "已完成对局数"));
    }

    /**
     * ★ **替换**第 {@code index} 套预设（0 基；**不是追加**）。
     *
     * <p>预设槽数量上限为 {@link PresetRules#PRESET_LIMIT}（= 3，【Q12】/ {@code docs/08} §6）。
     * 槽位不足时用**空列表**补齐（"空列表 = 该槽尚未设置"；文档未规定"未设置"的表示法，
     * 这是本类的数据形状约定）。
     *
     * <p>结构非法（不是 8 张 / 有空 id / 同一张卡重复）⇒ 抛异常，
     * 消息列出**每一条**问题（复用 {@link PresetRules#validateStructure}，判据只有一处）。
     * <b>是否已拥有</b>仍由 {@link PresetRules#validate} 在使用时判。
     *
     * @throws IllegalArgumentException 下标越界，或 {@code cards} 结构非法
     */
    public PlayerProgress withPreset(int index, List<String> cards) {
        if (index < 0 || index >= PresetRules.PRESET_LIMIT) {
            throw new IllegalArgumentException("预设下标超出范围：index = " + index
                    + "，期望 0 ~ " + (PresetRules.PRESET_LIMIT - 1)
                    + "（预设数量上限 " + PresetRules.PRESET_LIMIT + "，【Q12】/ docs/08 §6）");
        }
        List<String> problems = PresetRules.validateStructure(cards);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("第 " + (index + 1) + " 套预设内容非法："
                    + String.join("；", problems));
        }

        List<List<String>> next = new ArrayList<>(presets);
        while (next.size() <= index) {
            next.add(List.of());
        }
        next.set(index, List.copyOf(cards));
        return copy(gold, dust, owned, codex, next, packsOpened, gamesPlayed);
    }

    /** 一行摘要（日志 / 指令用）——含金币 / 尘 / 拥有数 / 预设数 / 计数。 */
    public String describe() {
        return "玩家 " + playerId + "：金币 " + gold + " / 尘 " + dust
                + " / 已拥有 " + owned.size() + " 张 / 图鉴 " + codex.size() + " 条"
                + " / 预设 " + presets.size() + " 套"
                + " / 已开包 " + packsOpened + " 次 / 对局 " + gamesPlayed + " 场";
    }

    // ------------------------------------------------------------------ 内部

    /** 用同一玩家 + 新的字段值构造新实例（字段拷贝与校验都在规范构造器里）。 */
    private PlayerProgress copy(int gold, int dust, Set<String> owned, Map<String, String> codex,
                               List<List<String>> presets, int packsOpened, int gamesPlayed) {
        return new PlayerProgress(playerId, gold, dust, owned, codex, presets, packsOpened, gamesPlayed);
    }

    private static int requireNonNegative(int value, String key, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(key + "（" + label + "）不能为负：实际 " + value);
        }
        return value;
    }

    private static void requireAmount(int amount, String key, String where) {
        if (amount < 0) {
            throw new IllegalArgumentException(where + " 的 " + key + " 不能为负：实际 " + amount
                    + "（要扣钱 / 扣尘请用 spendGold / spendDust）");
        }
    }

    private static int checkedAdd(int current, int amount, String label) {
        long sum = (long) current + amount;
        if (sum > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + "溢出 int 上限：当前 " + current
                    + " + " + amount + " = " + sum + "（进度字段是 int；请在配置层限制单次增量）");
        }
        return (int) sum;
    }

    private static String requireCardId(String cardId, String key, String where) {
        if (cardId == null) {
            throw new IllegalArgumentException(where + " 的 " + key + " 不能为 null（期望一张卡的 id）");
        }
        if (cardId.isBlank()) {
            throw new IllegalArgumentException(where + " 的 " + key
                    + " 不能是空白字符串：实际 \"" + cardId + "\"（期望一张卡的 id）");
        }
        return cardId;
    }

    private static Set<String> copyOwned(Set<String> raw) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "owned 不能为 null（新玩家请用 PlayerProgress.fresh(playerId)）");
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String cardId : raw) {
            copy.add(requireCardId(cardId, "元素", "owned"));
        }
        // ★ 保序：LinkedHashSet + 不可变包装（Set.copyOf 的迭代顺序是随机的，不许用）
        return Collections.unmodifiableSet(copy);
    }

    private static Map<String, String> copyCodex(Map<String, String> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("codex 不能为 null（没有记录请传 Map.of()）");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String cardId = requireCardId(entry.getKey(), "键", "codex");
            String state = entry.getValue();
            if (state == null || !CODEX_STATES.contains(state)) {
                throw new IllegalArgumentException("codex[" + cardId + "] 的图鉴状态非法：实际 " + state
                        + "，期望 " + CODEX_STATES + " 之一（docs/08 §3.3 的三态）");
            }
            copy.put(cardId, state);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<List<String>> copyPresets(List<List<String>> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("presets 不能为 null（没有预设请传 List.of()）");
        }
        List<List<String>> copy = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            List<String> preset = raw.get(i);
            if (preset == null) {
                throw new IllegalArgumentException("presets 第 " + (i + 1)
                        + " 套为 null（未设置的槽请写空列表 List.of()）");
            }
            for (int j = 0; j < preset.size(); j++) {
                if (preset.get(j) == null) {
                    throw new IllegalArgumentException("presets 第 " + (i + 1) + " 套第 " + (j + 1)
                            + " 格为 null（期望一张卡的 id）");
                }
            }
            // ⚠️ 这里**不**校验"恰好 8 张 / 已拥有"：存档里允许存在需要降级处理的旧预设
            //    （docs/08 §6「卡牌被移除时要降级处理」）⇒ 由 PresetRules.validate 在使用时报问题。
            copy.add(List.copyOf(preset));
        }
        return List.copyOf(copy);
    }

    private static void requireCodexMatchesOwned(Set<String> owned, Map<String, String> codex) {
        for (Map.Entry<String, String> entry : codex.entrySet()) {
            String cardId = entry.getKey();
            String state = entry.getValue();
            boolean has = owned.contains(cardId);
            if (CODEX_NONE.equals(state) && has) {
                throw new IllegalArgumentException("codex[" + cardId
                        + "] 是 NONE（未拥有），但 owned 里有它 —— 图鉴与仓库必须对得上"
                        + "（docs/08 §3.3：图鉴 / 仓库一律按卡 id 索引一条记录）");
            }
            if (!CODEX_NONE.equals(state) && !has) {
                throw new IllegalArgumentException("codex[" + cardId + "] 是 " + state
                        + "（已拥有），但 owned 里没有它 —— 图鉴与仓库必须对得上"
                        + "（docs/08 §3.3：金色版代替普通版的位置，是同一张卡）");
            }
        }
    }
}
