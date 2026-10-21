package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.match.Side;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>准备阶段的「选卡会话」</b>（纯状态机：谁确认过、谁到点了、每人选了哪 8 张）。
 *
 * <h2>它为什么单独存在（而不是散进 {@code MatchService}）</h2>
 * 「U4 选卡界面」（{@code docs/09} §5）与「到点判负」（{@link PrepDeadline}）要用的是
 * <b>同一份</b>状态：界面往里写 8 张卡、tick 回调读「谁还没确认」。
 * 若把这几个字段摊进 {@code MatchService}，那么
 * <ul>
 *   <li>界面（{@code ui/MatchDeckMenu}）就得直接摸对局运行时的内部字段；</li>
 *   <li>「什么叫做确认」会出现<b>第二处判据</b>（界面判一次、tick 判一次）。</li>
 * </ul>
 * ⇒ 收在这里：<b>本类只认 {@link Side} 与「8 个 card id」</b>，不 import 任何 Bukkit / MGC
 * （{@code PolicyPurityGuardTest} 守着这条），因此「确认 / 到期 / 两方都没确认」这些格子
 * 全部能在普通 JUnit 里逐格断言（{@code PrepSessionTest}）。
 *
 * <h2>★ 时间口径：把「到点」交给 {@link PrepDeadline}，本类只报事实</h2>
 * 本类<b>不自己数秒</b>：它只回答「这一方确认了吗」。
 * 「到点了没有」由调用方把 {@code elapsedTicks} / {@code limitTicks} 交给
 * {@link PrepDeadline#verdict} 判 —— 时间来源只有一处（{@code MatchService} 的 prepTick），
 * 于是「界面里显示的剩余时间」与「判负用的剩余时间」不可能分叉。
 *
 * <h2>★ 人机那一侧</h2>
 * 会话里可以只有一方（1 真人 ＋ 人机那局就是这种情况）：
 * {@link #confirmDeck} 对不在会话里的阵营返回 {@code false}，
 * 于是「人机也被要求确认」这类反例在<b>纯函数层就已经不可能发生</b>。
 */
public final class PrepSession {

    /** 卡组张数 = 8（{@code docs/09} §5.2 的「8 个空卡槽」；【Q38】读法 B）。 */
    public static final int DECK_SIZE = 8;

    /** 阵营 → 是否已确认卡组。 */
    private final Map<Side, Boolean> confirmed = new EnumMap<>(Side.class);

    /**
     * ★★ <b>已经「拿到过选卡机会」的阵营</b>（2026-10-16 第九轮新增）——
     * 生产侧的唯一写点是 {@code InfernalClashGame#openDeckPickerForHumans}。
     *
     * <h2>它修的是什么</h2>
     * 现场撞到过「<b>人没进去（没被传送、没弹 U4）却照样被判负</b>」
     * （{@code E:\MCpaper\logs\latest.log} 17:52:56~17:53:56；
     * 逐字留档 {@code docs/24} §6.0）⇒ 判负之前必须先问一句
     * 「这位玩家<b>真的被给过机会</b>吗」。判据在 {@link PrepDeadline}
     * （{@code SideStatus.offered}），本类只如实记下这个事实。
     */
    private final Set<Side> offered = EnumSet.noneOf(Side.class);

    /** 阵营 → 这一方的 8 张卡 id（空串 = 空格）；★ 只对<b>会话内</b>的阵营有键。 */
    private final Map<Side, List<String>> drafts = new EnumMap<>(Side.class);

    /**
     * @param humanSides 需要在本阶段确认卡组的阵营（★ <b>只有真人</b>；
     *                   人机不进这里 —— 它的牌来自 {@code config.yml} 的 {@code training.bots}）
     */
    public PrepSession(Set<Side> humanSides) {
        if (humanSides != null) {
            for (Side side : humanSides) {
                if (side == null) {
                    continue;
                }
                confirmed.put(side, Boolean.FALSE);
                drafts.put(side, emptyDraft());
            }
        }
    }

    /** 本阶段需要确认的阵营（保序：{@code RED} 在前；不可变快照）。 */
    public Set<Side> humanSides() {
        Set<Side> out = EnumSet.noneOf(Side.class);
        for (Side side : Side.values()) {
            if (confirmed.containsKey(side)) {
                out.add(side);
            }
        }
        return out;
    }

    /** 这一方要不要在本阶段确认卡组（人机 ⇒ {@code false}）。 */
    public boolean requiredFor(Side side) {
        return confirmed.containsKey(side);
    }

    /** 这一方确认过卡组了吗（不在会话里 ⇒ {@code false}）。 */
    public boolean isConfirmed(Side side) {
        return Boolean.TRUE.equals(confirmed.get(side));
    }

    /**
     * ★★ <b>记下「这一方确实拿到了选卡机会」</b>（选卡界面被自动弹过 / 被发过
     * 「选卡菜单」物品）。<b>幂等</b>：重复标记不算第二次。
     *
     * <h2>谁调它 / 为什么只有那一处</h2>
     * 生产侧<b>唯一</b>的调用点是 {@code InfernalClashGame#openDeckPickerForHumans}
     * —— 那是「准备阶段进入」那一刻<b>逐个真人</b>跑过去的唯一一段代码
     * （它同时负责自动弹 U4）。★ 放在别处会出现「两处都在记同一个事实」的老问题。
     *
     * <h2>★ 失败方向</h2>
     * 这一方不在会话里（人机 / 观战）⇒ {@code false}，<b>什么都不记</b>
     * —— 人机本来就不需要确认，也永远不会被判负。
     *
     * @return 是不是<b>这一次</b>才记上的（{@code false} = 会话外 / 之前已经记过）
     */
    public boolean markOffered(Side side) {
        if (side == null || !confirmed.containsKey(side)) {
            return false;
        }
        return offered.add(side);
    }

    /** ★ 这一方拿到过选卡机会吗（会话外 / 没记过 ⇒ {@code false}）。 */
    public boolean wasOffered(Side side) {
        return side != null && offered.contains(side);
    }

    /** 已经拿到过选卡机会的阵营（保序：{@code RED} 在前；不可变快照）。 */
    public Set<Side> offeredSides() {
        Set<Side> out = EnumSet.noneOf(Side.class);
        for (Side side : Side.values()) {
            if (offered.contains(side)) {
                out.add(side);
            }
        }
        return out;
    }

    /** 还有几方没确认（人机不计）。 */
    public int pendingCount() {
        int pending = 0;
        for (Boolean value : confirmed.values()) {
            if (!Boolean.TRUE.equals(value)) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * 是不是<b>所有</b>该确认的阵营都确认了（会话为空 ⇒ {@code true}：没人欠确认）。
     *
     * <h2>★★ 它就是「双方就绪 ⇒ 立即进战斗」的那条判据（用户裁定，{@code docs/24} §6.4）</h2>
     * 用户原话「<b>双方都确认后直接开始</b>」⇒ 一旦它变真，
     * {@code MatchService#confirmDeck} 就<b>立刻</b>结束准备阶段、进战斗
     * （走既有的 {@code onPhaseChanged(PREPARING → BATTLE)}，不另造收尾路径）。
     *
     * <p>★ <b>「人机那侧按不需要确认算已就绪」不是特例，而是构造使然</b>：
     * 本会话是用<b>真人阵营集合</b>构造的（人机<b>不在会话里</b>，
     * 见 {@link #PrepSession(Set)} 与 {@code MatchService.humanSides}）
     * ⇒ 「1 真人 ＋ 人机」那一格里，那唯一的真人在界面里点确认 ⇒ 本方法立刻为真
     * ⇒ <b>立即进战斗</b>。★ 因此这里<b>不能</b>改成「所有阵营都确认」（那会让人机永远欠确认）。
     */
    public boolean allConfirmed() {
        return pendingCount() == 0;
    }

    /**
     * 这一方**当前草稿**（8 个 card id；空串 = 空格）。
     *
     * <p>★ 会话外的阵营 ⇒ 空列表（绝不返回一个"猜出来的"卡组）。
     */
    public List<String> draft(Side side) {
        List<String> current = drafts.get(side);
        return current == null ? List.of() : current;
    }

    /**
     * 改草稿里的<b>一格</b>（U4 界面「点图鉴选中 → 再点卡位」的落点，✅【Q35】）。
     *
     * <p>★ 已确认的阵营<b>不许再改</b>（{@code docs/09} §5.4【Q37】：「战斗中不允许换卡」）
     * ⇒ 返回 {@code false}，界面据此提示。
     *
     * @param cardId 卡 id；{@code null} / 空串 = <b>清空这一格</b>
     * @return 是否真的改了
     * @throws IllegalArgumentException index 越界 / 这一方不在会话里（接线错误 ⇒ fail-fast）
     */
    public boolean setDraftCard(Side side, int index, String cardId) {
        requireMember(side);
        if (index < 0 || index >= DECK_SIZE) {
            throw new IllegalArgumentException("PrepSession.setDraftCard：index 越界，期望 0~"
                    + (DECK_SIZE - 1) + "（docs/09 §5.2 的 8 个卡槽），实际 " + index);
        }
        if (isConfirmed(side)) {
            return false;
        }
        String normalized = cardId == null ? "" : cardId.trim();
        // ★★★ 2026-10-19 用户裁定：「**卡组不能出现重复的牌**」⇒ 这里**硬拒绝**（返回 false，
        //   草稿一字不改）。★ 为什么必须在纯状态机里拦而不是只在界面拦：冷却表按卡 id 记
        //   （`policy/CardCooldowns`）⇒ 同一张卡放两格 = 两张牌共用一条冷却，那是**规则层**的错，
        //   不是界面的错；界面（`ui/MatchDeckMenu`）只负责把原因说清楚。
        if (!normalized.isEmpty() && draftHasElsewhere(side, normalized, index)) {
            return false;
        }
        List<String> next = new ArrayList<>(drafts.get(side));
        next.set(index, normalized);
        drafts.put(side, List.copyOf(next));
        return true;
    }

    /**
     * ★ <b>这张卡是否已经在这个草稿的**别的**格子里</b>（{@code exceptIndex} = 正在改的那一格）。
     *
     * <p>2026-10-19 新增（用户：「卡组不能出现重复的牌」）。两个消费方：
     * {@link #setDraftCard}（硬拒绝）与 {@code ui/MatchDeckMenu#putSelected}（说清原因）。
     * ★ 排除 {@code exceptIndex} 是刻意的：把某一格**重新设成它自己**不算重复（幂等）。
     *
     * @param side        阵营（必须在会话里，否则抛）
     * @param cardId      卡 id（{@code null} / 空白 ⇒ 永远 {@code false}）
     * @param exceptIndex 正在改的那一格（越界也无所谓，只看别的格子）
     */
    public boolean draftHasElsewhere(Side side, String cardId, int exceptIndex) {
        requireMember(side);
        if (cardId == null || cardId.isBlank()) {
            return false;
        }
        String wanted = cardId.trim();
        List<String> current = drafts.get(side);
        for (int i = 0; i < current.size(); i++) {
            if (i == exceptIndex) {
                continue;
            }
            if (wanted.equals(current.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** 这一方草稿里已经放了几张卡（空串不算）。 */
    public int draftCount(Side side) {
        int count = 0;
        for (String cardId : draft(side)) {
            if (cardId != null && !cardId.isBlank()) {
                count++;
            }
        }
        return count;
    }

    /**
     * ★ <b>确认卡组</b>（U4 界面「确认卡组」按钮的落点）。
     *
     * <h2>三道门（顺序即理由）</h2>
     * <ol>
     *   <li>这一方不在会话里（人机 / 观战）⇒ {@code false}；</li>
     *   <li>已经确认过（重复点按钮）⇒ {@code false}（幂等：不许「再确认一次」把卡组换掉）；</li>
     *   <li><b>必须正好 8 张</b>（★ 用户裁定「卡组需要正好 8 张」，{@code docs/24} §5.4 G12；
     *       {@code docs/09} §5.2 的 8 个卡槽）⇒ 不够 / 超出的部分一律拒绝，
     *       并把卡组原样留在草稿里让玩家继续改。</li>
     * </ol>
     *
     * @param deck 玩家排好序的 8 张卡 id（顺序 = U4 界面里的排列，{@code docs/02} §6.2 的 R16/R18）
     * @return 是否确认成功
     */
    public boolean confirmDeck(Side side, List<String> deck) {
        if (!requiredFor(side) || isConfirmed(side)) {
            return false;
        }
        if (!isCompleteDeck(deck)) {
            return false;
        }
        // ★★ 2026-10-19（用户：「卡组不能出现重复的牌」）：第二道闸 —— 即使调用方绕过
        //   `setDraftCard` 直接塞一列 id 进来（`MatchDeckMenu#confirm` 就是这么调的），
        //   重复的卡组也不许确认。★ 与 `setDraftCard` 是**同一口径**，两处都拦 = 改一处也绕不过去。
        if (hasDuplicate(deck)) {
            return false;
        }
        List<String> normalized = new ArrayList<>(DECK_SIZE);
        for (String cardId : deck) {
            normalized.add(cardId == null ? "" : cardId.trim());
        }
        drafts.put(side, List.copyOf(normalized));
        confirmed.put(side, Boolean.TRUE);
        return true;
    }

    /**
     * ★ 这一列卡组里有没有重复的卡（2026-10-19；用户：「卡组不能出现重复的牌」）。
     *
     * <p>与 {@code profile/PresetRules#validateStructure} 的判据同义（那边是**局外预设**的规则，
     * 这边是**局内 U4 草稿**的规则）—— ★ 两处都保留、不合并：一处是存档格式校验、
     * 一处是对局内状态机；合并会让"预设能不能存"与"这一局能不能确认"耦合在一起。
     *
     * @param deck 卡 id 列表；{@code null} ⇒ {@code false}（由 {@code isCompleteDeck} 负责拒空）
     */
    public static boolean hasDuplicate(List<String> deck) {
        if (deck == null) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (String cardId : deck) {
            if (cardId == null || cardId.isBlank()) {
                continue;   // 空格由 isCompleteDeck 负责报
            }
            if (!seen.add(cardId.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * ★ <b>这一 tick 该不该判负</b> —— 判据全部委托给 {@link PrepDeadline}（唯一一处玩法规则）。
     *
     * <p>★★ 它同时把「谁<b>拿到过</b>选卡机会」（{@link #wasOffered}）作为
     * {@code SideStatus.offered} 交给判据 ⇒ 「没给过机会就<b>不判负</b>」这条安全前置
     * 在纯函数层就生效（现场痛点见 {@code docs/24} §6.0）。
     *
     * @param elapsedTicks 准备阶段已经过的 tick
     * @param limitTicks   准备阶段总时长（{@code phases.preparing-ticks}）
     */
    public PrepDeadline.Outcome verdict(int elapsedTicks, int limitTicks) {
        return PrepDeadline.verdict(statusOf(Side.RED), statusOf(Side.BLUE), elapsedTicks, limitTicks);
    }

    /**
     * 某一方的快照：<b>会话内 ⇒ 真人</b>，会话外 ⇒ 人机
     * （1v1 里非会话方只可能是人机：{@code docs/24} §5.5 的人机开局）。
     *
     * <p>★ 这不是"猜"：会话本身就是由「真人阵营集合」构造的
     * （见 {@code MatchService} 里 {@code humanSides} 的取法）。
     */
    private PrepDeadline.SideStatus statusOf(Side side) {
        return confirmed.containsKey(side)
                ? new PrepDeadline.SideStatus(PrepDeadline.Controller.HUMAN, isConfirmed(side),
                        wasOffered(side))
                : new PrepDeadline.SideStatus(PrepDeadline.Controller.BOT, false, false);
    }

    /** 8 张、且没有空格 —— 口径见 {@link #confirmDeck}。 */
    public static boolean isCompleteDeck(List<String> deck) {
        if (deck == null || deck.size() != DECK_SIZE) {
            return false;
        }
        for (String cardId : deck) {
            if (cardId == null || cardId.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** 8 个空格。 */
    private static List<String> emptyDraft() {
        List<String> empty = new ArrayList<>(DECK_SIZE);
        for (int i = 0; i < DECK_SIZE; i++) {
            empty.add("");
        }
        return List.copyOf(empty);
    }

    private void requireMember(Side side) {
        Objects.requireNonNull(side, "side 不能为 null");
        if (!confirmed.containsKey(side)) {
            throw new IllegalArgumentException("PrepSession：这一方（" + side
                    + "）不在准备阶段的会话里（会话内 = 需要确认卡组的真人阵营："
                    + humanSides() + "）");
        }
    }
}
