package io.mczju.infernalclash.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 卡组预设的校验 —— **纯函数，可离线单测**。
 *
 * <h2>规则来源：{@code docs/08} §6（R59 / U2 / U3 / U4，【Q12】）</h2>
 * <ul>
 *   <li>一套预设 = **8 张卡的 id 列表**（{@code docs/08} §6【AI 补充】：
 *       「预设内容 = 8 张卡的 id 列表；需要一个『合法预设』判据
 *       （8 张都必须是**玩家已拥有**的卡；卡牌被移除时要降级处理）」）⇒ {@link #DECK_SIZE}；</li>
 *   <li>预设槽上限 = **3**（【Q12】「卡组预设数量上限为3」，{@code docs/05} Q12 /
 *       {@code docs/08} §6）⇒ {@link #PRESET_LIMIT}。</li>
 * </ul>
 *
 * <h2>★ 报"每一条"问题，不是只报第一条</h2>
 * {@link #validate} 返回**全部**问题的中文说明（保序：先结构问题，再逐格的存在性 / 拥有性），
 * 依据 {@code docs/04} §16.11.3：「预设非法（不是 8 张 / 有重复 / 没拥有）
 * ⇒ **拒绝并说清哪一条**」。
 *
 * <h2>★ 本类**不**做的两件事</h2>
 * <ol>
 *   <li><b>不降级</b>：{@code docs/08} §6 说「卡牌被移除时要降级处理」，但**怎么降级文档没写**
 *       ⇒ 本类只把问题报出来，**不替用户决定**（铁律 11：缺口不许"合理补全"）。</li>
 *   <li><b>不猜卡池</b>：判「不存在的卡 id」必须由调用方传**卡池快照**
 *       {@code knownCardIds}（例如 {@code card/CardCatalog} 的全部 id）——
 *       本类不内置卡池，也不把"未知"当成"未拥有"。</li>
 * </ol>
 *
 * <p>⚠️ 纯逻辑：不碰 Bukkit / MGC（两道守卫都会扫它）。
 */
public final class PresetRules {

    /** 一套预设的卡牌张数（{@code docs/08} §6：8 张）。 */
    public static final int DECK_SIZE = 8;

    /** 预设槽上限（【Q12】= 3；{@code docs/08} §6 / {@code docs/09} §5 的三个预设按钮）。 */
    public static final int PRESET_LIMIT = 3;

    private PresetRules() {
    }

    /**
     * **只做结构校验**（张数 / 空 id / 重复），不看玩家拥有关系。
     *
     * <p>它的存在是为了让「8 张卡"这条规则只有一个来源 ——
     * {@link #validate} 与 {@link PlayerProgress#withPreset} 都调它，不各写一份判据。
     *
     * @param cards 预设内容（{@code null} 会被当成"一张都没有"报出来）
     * @return 全部问题的中文说明（保序）；**合法 ⇒ 空列表**
     */
    public static List<String> validateStructure(List<String> cards) {
        List<String> problems = new ArrayList<>();
        if (cards == null) {
            problems.add("卡组不能为 null（期望 " + DECK_SIZE + " 张卡牌的 id）");
            return List.copyOf(problems);
        }
        if (cards.size() != DECK_SIZE) {
            problems.add("卡组必须有 " + DECK_SIZE + " 张卡，实际 " + cards.size() + " 张");
        }

        // 保序记录"这张卡第一次出现在第几格"，用于报重复
        Map<String, Integer> firstSeenAt = new LinkedHashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            String cardId = cards.get(i);
            int slot = i + 1;
            if (cardId == null || cardId.isBlank()) {
                problems.add("第 " + slot + " 格为空（期望一张卡牌的 id，实际 "
                        + (cardId == null ? "null" : "\"" + cardId + "\"") + "）");
                continue;
            }
            Integer first = firstSeenAt.putIfAbsent(cardId, slot);
            if (first != null) {
                problems.add("第 " + slot + " 格与第 " + first + " 格是同一张卡：" + cardId
                        + "（卡组不允许重复的卡）");
            }
        }
        return List.copyOf(problems);
    }

    /**
     * **完整校验**：结构 + 卡池存在性 + 玩家是否已拥有。
     *
     * <p>问题顺序（保序、同输入同结果）：
     * 结构问题 → 逐格（按格子顺序、**每张卡只报一次**）的存在性 / 拥有性。
     * 不在卡池里的卡**只报"不是已知卡牌 id"**，不再重复报"尚未拥有"
     * （否则同一张卡会刷两条，掩盖真正的问题）。
     *
     * @param cards        预设内容
     * @param progress     玩家进度（读 {@link PlayerProgress#owns}）
     * @param knownCardIds 卡池快照（全部已知卡 id；由装配层从 {@code card/CardCatalog} 取）
     * @return 全部问题的中文说明（保序）；**合法 ⇒ 空列表**
     * @throws IllegalArgumentException {@code progress} 或 {@code knownCardIds} 为 null
     *         （那是调用方装配错误，不是玩家输入 —— 玩家输入的问题一律走返回值）
     */
    public static List<String> validate(List<String> cards, PlayerProgress progress,
                                        Set<String> knownCardIds) {
        if (progress == null) {
            throw new IllegalArgumentException("progress 不能为 null"
                    + "（要判『是否已拥有』，必须给出该玩家的进度）");
        }
        if (knownCardIds == null) {
            throw new IllegalArgumentException("knownCardIds 不能为 null"
                    + "（要判『不存在的卡 id』，必须给出卡池快照；没有卡池请传 Set.of()，"
                    + "那会让所有卡都报『不是已知卡牌 id』）");
        }

        List<String> problems = new ArrayList<>(validateStructure(cards));
        if (cards == null) {
            return List.copyOf(problems);
        }

        Set<String> alreadyChecked = new LinkedHashSet<>();
        for (int i = 0; i < cards.size(); i++) {
            String cardId = cards.get(i);
            int slot = i + 1;
            if (cardId == null || cardId.isBlank() || !alreadyChecked.add(cardId)) {
                continue;
            }
            if (!knownCardIds.contains(cardId)) {
                problems.add("第 " + slot + " 格 " + cardId + " 不是已知的卡牌 id"
                        + "（卡池快照里没有它；请核对 cards/ 目录）");
                continue;
            }
            if (!progress.owns(cardId)) {
                problems.add("第 " + slot + " 格 " + cardId + " 尚未拥有"
                        + "（docs/08 §6：预设里 8 张都必须是玩家已拥有的卡）");
            }
        }
        return List.copyOf(problems);
    }

    /**
     * 该玩家现在能不能用这套预设开局。
     *
     * <p>= {@code validate(cards, progress, knownCardIds).isEmpty()}（**共用同一判据**：
     * 空问题 ⇔ 可用，不可能出现"校验通过但用不了"）。
     */
    public static boolean isUsable(List<String> cards, PlayerProgress progress,
                                   Set<String> knownCardIds) {
        return validate(cards, progress, knownCardIds).isEmpty();
    }
}
