package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.match.Side;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>每场每方「上一张**成功**打出的牌」</b> —— 「回响」的落点（2026-10-19）。
 *
 * <h2>它回答的问题</h2>
 * 用户给的两个备选里选了方案 1（逐字：「再一次使用本场对战上次使用的卡（在对战中不能被当成
 * 第一张牌打出）」）；落点 = 用户追加裁定「**(b)**」（用玩家**当前瞄的那一格**）。
 * ⇒ 本类只需要记住<b>是哪一张</b>；落点在施放那一刻由 {@code CardCastListener} 重新算
 * （所以这里**不存坐标** —— 存了就会变成第二处落点来源，而 (b) 的口径正是"以现在的瞄准为准"）。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>只记成功的</b>：扣了费却召唤失败 / 法术失败的，调用方**不许**调用 {@link #remember}
 *       （否则回响会复读一张"其实没打出去"的牌）；</li>
 *   <li><b>按阵营分开</b>：红方打过的牌不影响蓝方的回响（{@link EnumMap} 天然一 key 一条）；</li>
 *   <li><b>每局清零</b>：本类由 {@code MatchService} <b>每局 new 一个</b>持有 ⇒ 不需要显式
 *       {@link #clear()}；{@code clear()} 只留给"想过早清"的场景（目前没有调用点）。
 *       ★ 这也保证了「不能当第一张牌打出」：新的一局里 {@link #lastOf} 立刻是空。</li>
 * </ol>
 *
 * <p>★ 纯类（只依赖 {@link Side} 枚举）⇒ 离线可测；不碰服务端、不查实体（铁律 10）。
 */
public final class CastMemory {

    /** 阵营 → 那一方最近一次成功打出的卡 id。 */
    private final Map<Side, String> lastCardId = new EnumMap<>(Side.class);

    /**
     * 记一次**成功**的施放。
     *
     * @param side   哪一方
     * @param cardId 打出的卡 id（{@code null} / 空白 ⇒ **忽略**，不覆盖已有记录）
     * @return 是否真的记下了
     */
    public boolean remember(Side side, String cardId) {
        if (side == null || cardId == null || cardId.isBlank()) {
            return false;
        }
        lastCardId.put(side, cardId.trim());
        return true;
    }

    /** 这一方上一张打出的牌（没打过 ⇒ {@link Optional#empty()}）。 */
    public Optional<String> lastOf(Side side) {
        if (side == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastCardId.get(side));
    }

    /** 这一方打过牌没有（= 回响能不能打出）。 */
    public boolean has(Side side) {
        return lastOf(side).isPresent();
    }

    /** 清空（★ 正常路径不用：每局一个新的 {@code MatchService}）。 */
    public void clear() {
        lastCardId.clear();
    }

    /** 日志用一行摘要。 */
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (Side side : Side.values()) {
            if (!out.isEmpty()) {
                out.append("｜");
            }
            out.append(side).append('=').append(lastCardId.getOrDefault(side, "（还没出牌）"));
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return "CastMemory{" + describe() + "}";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CastMemory memory && Objects.equals(lastCardId, memory.lastCardId);
    }

    @Override
    public int hashCode() {
        return lastCardId.hashCode();
    }
}
