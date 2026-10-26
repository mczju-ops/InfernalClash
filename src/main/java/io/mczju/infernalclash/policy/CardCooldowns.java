package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.match.Side;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★★ <b>卡牌冷却状态机（纯类）</b> —— 2026-10-18 新增
 * （用户：「**一直遗留的非常重要的一点：冷却系统完全没有实现；每一张卡都可以随时连续使用**」）。
 *
 * <h2>为什么必须是一张显式的表</h2>
 * 原来的实现只有一行 {@code player.setCooldown(Key.key("infernalclash:" + card.id()), ticks)}，
 * 而 <b>没有任何地方读回它</b> ⇒ 冷却设了等于没设，可以连发。
 * 更糟的是那个 {@link net.kyori.adventure.key.Key} <b>不对应任何真实物品</b>
 * （卡牌物品的身份在 PDC 上）⇒ 连"客户端变暗"的遮罩都不会出现。
 *
 * <h2>★★ 口径（用户 2026-10-18 裁定：「按卡共享 + 每局开始清零」）</h2>
 * <ol>
 *   <li><b>按卡共享</b>：<b>同一名玩家</b>手里的同一张卡的多个副本共用一个冷却
 *       （键 = {@code 阵营 + cardId}，不是槽位）；</li>
 *   <li><b>每局开始清零</b>：本类是 {@code MatchService} 的字段 ⇒ **新对局 = 新实例 = 天然清零**
 *       （不需要额外的 reset 调用，也就没有"忘了清"这一格）；</li>
 *   <li>时钟 = {@code MatchState#battleTick()}（★ 跨进加时**继续增长**，不会在阶段切换时归零）；</li>
 *   <li>★ 判据是"<b>什么时候可以再用</b>"（{@code readyAt}），不是"上次何时用的"
 *       —— 前者让 {@link #remaining} 一眼就能算出来，也不用存 cooldownTicks。</li>
 * </ol>
 *
 * <h2>★★★ 2026-10-21 修：**冷却必须按阵营分表**（用户报的真人对局缺陷）</h2>
 * 用户原话：「**两人的卡牌共享了 cd：蓝方用完"火球"后，红方的火球虽然没有显示冷却遮罩
 * 也没有被红方使用，但使用时依旧无法释放且显示"正在冷却"**」。
 *
 * <p>根因：这一张表原来<b>只按 {@code cardId} 索引</b>，而它是<b>整场对局共享</b>的一个字段
 * ⇒ 蓝方打出火球，红方的火球一起进入冷却；而<b>客户端遮罩</b>是按玩家发的
 * （{@code Player#setCooldown}）⇒ 红方看不到任何变暗，却打不出来 —— 与用户描述逐字吻合。
 *
 * <p>★ 裁定原文里的「按卡共享」指的是<b>同一名玩家手里的多张同名卡</b>（不是"两个玩家共享"）：
 * 一张卡在同一套牌里只占一格、但快捷栏里的副本的冷却必须一致。
 * ⇒ 现在键 = <b>{@code 阵营 + cardId}</b>，红蓝两张表**互不影响**（{@link #remaining} 等全部收 {@link Side}）。
 *
 * <p>★ 纯类：不碰 Bukkit、不碰玩家 ⇒ 可以离线穷举（{@code CardCooldownsTest}）。
 * 客户端那个"物品变暗 + 倒计时"的遮罩由调用方用 {@code Player#setCooldown(Material, ticks)}
 * 单独做（★ 现在每张卡有**唯一材质**，所以遮罩才是每卡独立的）。
 */
public final class CardCooldowns {

    /**
     * {@code 阵营 → (cardId → 可以再次使用的 battleTick)}。
     *
     * <p>★ 两层：外层是**阵营**（2026-10-21 修「两人共享 cd」），内层是**卡**
     * （用户 2026-10-18 裁定的「同一名玩家手里的同名卡共享一条冷却」）。
     * ★ 外层用 {@link LinkedHashMap}（保序、便于日志与对账）；★ 不用 {@code EnumMap}
     * 是因为 {@link #remaining} 允许 {@code side == null}（见那里的说明），而 EnumMap 拒 null 键。
     */
    private final Map<Side, Map<String, Integer>> readyAt = new LinkedHashMap<>();

    /**
     * ★ 还要等多少 tick 才能再用这张卡。
     *
     * @param side       哪一方的冷却表（★ 红蓝各一张；{@code null} ⇒ 视为"没有这条记录"，
     *                   见类注释的失败方向）
     * @param cardId     卡 id
     * @param battleTick 当前战斗 tick
     * @return {@code 0} = **可以用了**；{@code > 0} = 还要等这么多 tick
     */
    public int remaining(Side side, String cardId, int battleTick) {
        if (side == null || cardId == null) {
            return 0;
        }
        Map<String, Integer> table = readyAt.get(side);
        if (table == null) {
            return 0;
        }
        Integer at = table.get(cardId);
        if (at == null) {
            return 0;
        }
        int left = at - battleTick;
        return Math.max(0, left);
    }

    /** ★ 现在能不能用这张卡（某一方）。 */
    public boolean ready(Side side, String cardId, int battleTick) {
        return remaining(side, cardId, battleTick) <= 0;
    }

    /**
     * ★★ <b>把「冷却缩减比例」折进一个 tick 数</b>（纯函数）—— 2026-10-18 新增。
     *
     * <h2>为什么要有它</h2>
     * 「战术冷却」这张卡（{@code docs/19} #19）的语义是「**10 秒内本玩家卡牌冷却 −50%**」，
     * 而在此之前 {@code spell/EffectApplier} 只把比例**记进窗口表**（{@code activeReduction}），
     * **没有任何生产代码读它** ⇒ 2 费 45 秒冷却换来 0 效果（审计 2026-10-18 发现的缺口）。
     * 现在由 {@code game/CardCastListener#startCooldown} 在**登记冷却的那一刻**折进来
     * —— 这正是那个窗口表 javadoc 早就写明的口径：「只影响窗口内**新施加**的冷却」。
     *
     * @param baseTicks 卡面冷却（{@code > 0}）
     * @param ratio     缩减比例（0.0 = 不减；0.5 = 减半；1.0 = 全部减掉）
     * @return 折完的 tick 数，**至少 1**（0 会让"刚打的牌立刻又能打"，等于消除了冷却）
     */
    public static int withReduction(int baseTicks, double ratio) {
        if (baseTicks <= 0) {
            return baseTicks;
        }
        if (Double.isNaN(ratio) || ratio <= 0.0) {
            return baseTicks;
        }
        double clamped = Math.min(1.0, ratio);
        return Math.max(1, (int) Math.round(baseTicks * (1.0 - clamped)));
    }

    /**
     * ★ 登记一次使用（**只在真的出牌成功后调用**）。
     *
     * @param side          哪一方在用（★ 红蓝各一张表 —— 2026-10-21 修「两人共享 cd」）
     * @param cooldownTicks 卡上的冷却；{@code <= 0} ⇒ 不登记（零冷却的卡永远可用）
     */
    public void start(Side side, String cardId, int battleTick, int cooldownTicks) {
        if (side == null || cardId == null || cooldownTicks <= 0) {
            return;
        }
        readyAt.computeIfAbsent(side, key -> new LinkedHashMap<>())
                .put(cardId, battleTick + cooldownTicks);
    }

    /**
     * ★ 撤销一次登记（出牌失败退费时用）。
     *
     * <p>★ 为什么失败要撤：用户明明没打出效果却要等冷却，是比"扣费"更难受的一类缺陷。
     * 与 {@code MatchService#refundXp} 同一个时机、同一个理由。
     *
     * <p>★ 只撤**该阵营**那一条：撤错阵营会把对手的冷却清掉（那是反方向的同一个 bug）。
     */
    public void cancel(Side side, String cardId) {
        if (side == null || cardId == null) {
            return;
        }
        Map<String, Integer> table = readyAt.get(side);
        if (table == null) {
            return;
        }
        table.remove(cardId);
        if (table.isEmpty()) {
            readyAt.remove(side);   // 空表不留痕（size() 与 snapshot 更干净）
        }
    }

    /**
     * ★★★ <b>立刻把一张卡的冷却清掉</b>（1.1.0「援军」的落点）。
     *
     * <h2>出处（{@code docs/1.1卡牌.txt}，逐字）</h2>
     * 「使用时，<b>重置记录的卡牌的冷却时间</b>，然后清空记录」。
     *
     * <h2>为什么不能复用 {@link #cancel}</h2>
     * 两者<b>此刻</b>的实现一样（都是删掉那一行），但**语义不同**：
     * {@code cancel} 是"这次出牌失败、当作没发生"（配套退费），
     * 本方法是"这次出牌成功，且它按规则让另一张牌立刻可用"。
     * 若写成同一个方法，将来 {@code cancel} 加上"退费/计数"之类的副作用时，
     * 援军会**静默**跟着变 —— 那正是本项目最反感的「一处语义被另一处借走」。
     * ⇒ 两个入口、同一个存储操作，注释互相指认。
     *
     * @return 真的清掉了一条吗（没有那一条 ⇒ {@code false}，调用方可据此写日志）
     */
    public boolean reset(Side side, String cardId) {
        if (side == null || cardId == null || cardId.isBlank()) {
            return false;
        }
        Map<String, Integer> table = readyAt.get(side);
        if (table == null || !table.containsKey(cardId)) {
            return false;
        }
        table.remove(cardId);
        if (table.isEmpty()) {
            readyAt.remove(side);
        }
        return true;
    }

    /** 清空（★ 正常情况下用不到 —— 新对局是新实例；留给 `/incl debug` 与测试）。 */
    public void clear() {
        readyAt.clear();
    }

    /** 当前记着多少条冷却（**两个阵营合计**；日志 / 探针用）。 */
    public int size() {
        int total = 0;
        for (Map<String, Integer> table : readyAt.values()) {
            total += table.size();
        }
        return total;
    }

    /** 某一方记着多少条冷却（日志 / 测试用）。 */
    public int size(Side side) {
        Map<String, Integer> table = side == null ? null : readyAt.get(side);
        return table == null ? 0 : table.size();
    }

    /** 某一方的只读快照（{@code cardId → readyAt}；日志与测试用）。 */
    public Map<String, Integer> snapshot(Side side) {
        Map<String, Integer> table = side == null ? null : readyAt.get(side);
        return table == null ? Map.of() : Map.copyOf(table);
    }

    /** 一行说明（日志用）：某一方某张卡的冷却状态。 */
    public String describe(Side side, String cardId, int battleTick) {
        int left = remaining(side, cardId, battleTick);
        return left <= 0 ? "就绪" : "冷却中（还要 " + seconds(left) + "s）";
    }

    /**
     * ★ tick → 秒的**唯一**一处换算（保留一位小数）。
     *
     * <p>为什么抽出来：拒绝提示（{@code errors.card-on-cooldown}）与日志都要说"还有几秒"，
     * 两处各写一次 {@code ticks / 20.0} 早晚会出现一个用整除、一个用浮点。
     */
    public static String seconds(int ticks) {
        return String.format(java.util.Locale.ROOT, "%.1f", Math.max(0, ticks) / 20.0);
    }
}
