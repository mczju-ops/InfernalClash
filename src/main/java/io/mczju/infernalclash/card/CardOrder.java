package io.mczju.infernalclash.card;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ★★★ <b>图鉴里卡牌的排列顺序</b>（2026-10-20 用户裁定；纯函数，可离线断言）。
 *
 * <h2>用户原话（逐字）</h2>
 * > 「**能不能让卡牌在图鉴里按照基础-普通-稀有-史诗-传说的顺序排列？（排列无视金卡）**」
 *
 * <h2>口径</h2>
 * <ol>
 *   <li>**主键 = 稀有度**，顺序固定为 {@link Rarity} 的声明顺序
 *       （`BASIC → COMMON → RARE → EPIC → LEGENDARY`，即"基础-普通-稀有-史诗-传说"）；</li>
 *   <li>**同档内保持卡池的原顺序**（= {@code cards/*.yml} 的装载顺序）。
 *       ★ 用**稳定排序**（{@link List#sort} 的 TimSort）+ 只比稀有度 ⇒ 同档内绝不打乱；
 *       不按 id / 名字再排——那会变成"我替你决定同档怎么排"（铁律 8）；</li>
 *   <li>**无视金色**：金卡与普通卡是同一张卡（【Q31】"同一张卡 + 品质标记"），
 *       传进来的 `Card` 里根本没有金色位 ⇒ 本排序天然不受金卡影响。</li>
 * </ol>
 *
 * <p>★ 纯（不碰 Bukkit）：`Card` 是纯数据，`Rarity` 是枚举 ⇒ 任意 JVM 里可断言。
 */
public final class CardOrder {

    private CardOrder() {
    }

    /** 稀有度顺序：`BASIC → COMMON → RARE → EPIC → LEGENDARY`（= 枚举声明顺序，不另写一份表）。 */
    public static final Comparator<Card> BY_RARITY_THEN_ORIGINAL =
            Comparator.comparingInt(card -> card.rarity() == null
                    ? Integer.MAX_VALUE          // 缺稀有度 ⇒ 排最后（不猜它属于哪一档）
                    : card.rarity().ordinal());

    /**
     * 按稀有度排序（稳定；同档保持原顺序）。
     *
     * @param cards 原顺序（可为 {@code null} / 空）
     * @return **新的**不可变列表（原列表不动；**不会**返回 {@code null}）
     */
    public static List<Card> codexOrder(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return List.of();
        }
        List<Card> sorted = new ArrayList<>(cards);
        sorted.sort(BY_RARITY_THEN_ORIGINAL);   // ★ TimSort 稳定 ⇒ 同档内顺序不变
        return List.copyOf(sorted);
    }
}
