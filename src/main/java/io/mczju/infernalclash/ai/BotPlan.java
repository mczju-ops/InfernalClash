package io.mczju.infernalclash.ai;

import io.mczju.infernalclash.arena.HalfField;
import io.mczju.infernalclash.arena.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ★★ <b>训练人机的纯出牌策略</b> —— 只落实用户 2026-10-15 第二轮裁定（E13）与
 * {@code docs/02} §9 已经写明的行为。
 *
 * <h2>★★ 权威（用户原话，逐字）</h2>
 * 「<b>费用允许时，从左到右依次打出手牌，放置在自己信标处</b>」
 * （逐字留档 {@code docs/24} §1 第 8 条；落实表见 {@code docs/02} §9 的
 * 「2026-10-15 第二轮裁定」块）。逐项对应到本类的一个方法：
 *
 * <table border="1">
 *   <caption>裁定 → 代码</caption>
 *   <tr><th>用户给的算法</th><th>本类</th><th>说明</th></tr>
 *   <tr><td><b>从左到右依次</b></td><td>{@link #firstCastableIndex(List)}</td>
 *       <td>★ 按 {@link HandCard#slot()}（手牌槽位 2~9）<b>升序</b>扫 ——
 *           同一手牌 ⇒ 同一个顺序（可复现，比 R60 的「随机打牌」更利于排障）</td></tr>
 *   <tr><td><b>费用允许时</b></td><td>{@link #isCastable(HandCard, double)}</td>
 *       <td>★ <b>2026-10-16 第八轮纠正（用户原话「它付不起不应该跳过，而是等待直到这张牌
 *           可以打出」）</b>：付不起 <b>不跳过</b>，而是<b>等这一张</b>（指针不动）。
 *           旧的「付不起 ⇒ 跳过、继续看下一张」<b>已作废</b>
 *           （留档 {@code docs/24} §5.9；{@code docs/02} §9.1 已整段改写）</td></tr>
 *   <tr><td><b>打出手牌</b></td><td>{@link #isCastable(HandCard, double)}</td>
 *       <td>★★ <b>2026-10-21 用户裁定：人机会放法术</b>（原话「人机现在需要能够出法术牌」，
 *           取代 R60 的「不会放法术」）⇒ 判据只剩<b>费用</b>；法术卡的落点/目标由
 *           {@code ai/BotSpellTargeting} 按 {@code training.spell-rules} 里那张卡的规则算
 *           （见 {@link Placement#SPELL}）</td></tr>
 *   <tr><td><b>放置在自己信标处</b></td><td>{@link #decide} 的 {@code beacon}</td>
 *       <td>落点 = {@code arenaConfig.beacon(自己的阵营)} 那个点（{@link Placement#AT_OWN_BEACON}）</td></tr>
 *   <tr><td>★ 必须遵守游戏规则（召唤只在己方半场）</td><td>{@link #beaconInOwnHalf}</td>
 *       <td>【Q23】＋ R28；信标在自己半场内 ⇒ 天然满足，但这里仍然有<b>可断言的判据</b></td></tr>
 * </table>
 *
 * <h2>★★ 本类<b>不做</b>的事（缺口仍然不猜）</h2>
 * <ul>
 *   <li>★ <b>从哪些卡里抽手牌</b>（缺口 <b>G12</b>：全部 51 张？只召唤卡？谁来发牌？）——
 *       本类只接受<b>调用方给定</b>的一手牌，<b>绝不自己造池子</b>
 *       （以前那条 {@code POOL_REFUSAL} 的实质内容仍然成立）；</li>
 *   <li>★ <b>难度档位</b>（缺口 G10）—— {@code docs/02} §9 只有「训练人机」一种
 *       ⇒ 本类里<b>不存在</b>任何难度参数（{@code BotPolicyTest} 用反射钉住这一条）；</li>
 *   <li>★ <b>出牌节奏</b>（缺口 G11 的<b>数值</b>）：用户给了「依次打」这个<b>顺序</b>，
 *       但没给「隔多久试一次」这个<b>玩法数值</b>（铁律 8）⇒ 本类<b>没有</b>任何 tick / 概率参数；
 *       那个节拍是执行层的一个可调常量，写在 {@code ai/TrainingBotDriver#CAST_INTERVAL_TICKS}
 *       并注明「按裁定实现，数值可调」。</li>
 * </ul>
 *
 * <h2>★ 纯（{@code AGENTS.md} §3）</h2>
 * 只依赖 JDK ＋ 本插件的纯类（{@code arena/Point} / {@code arena/HalfField} /
 * {@code match/Side} —— 它们都是「连 Bukkit 都不碰」的值对象）⇒ <b>离线可单测</b>
 * （{@code BotPolicyTest}，含「顺序写反 ⇒ 断言必须失败」的阳性对照）。
 * ★ 铁律 10：本类<b>不出现</b> {@code getOnlinePlayers / getEntities / getLivingEntities / getWorlds}
 * —— 它连 Bukkit 都不 import，连实体都没见过。
 *
 * @see io.mczju.infernalclash.ai.TrainingBotDriver 执行侧（把决策变成一次真的召唤）
 */
public final class BotPlan {

    private BotPlan() {
    }

    // ==================================================================================
    // 一、输入
    // ==================================================================================

    /**
     * 手牌的**槽位下限**（＝ {@code game/CardLoadout} 的第一张卡所在<b>格号</b>：1 号格是「战斗」物品）。
     */
    public static final int FIRST_HAND_SLOT = 2;

    /**
     * 手牌的**槽位上限**（＝ **格号 9**，即快捷栏最后一格）。
     *
     * <h2>★★ 与 {@code CardLoadout} 的口径必须一致（踩过一次，写在这里）</h2>
     * {@code game/CardLoadout} 的 javadoc 逐字写着「1 号格 = 「战斗」物品，**2~9 号格 = 8 张卡**」，
     * 而它的 {@code placeDeck} 从 Bukkit 的 {@code Inventory} 下标 **1** 递到 **8**
     * （0-based）—— 那正好是**格号 2~9**。
     * ⇒ ★ 本类用的是**格号（1 起）**，不是 Bukkit 的 0-based 下标：
     * <pre>
     *   Bukkit Inventory 下标 = 格号 - 1        （格 2 ⇒ 下标 1 …… 格 9 ⇒ 下标 8）
     * </pre>
     * 初版我把上界写成 8（把「8 张卡」误当成「8 号格」），当场被自己的测试
     * （{@code slot=9} 的夹具）抓住 —— 这正是「同一件事不许有两处口径」的例子。
     */
    public static final int LAST_HAND_SLOT = 9;

    /**
     * 一张手牌的<b>最小抽象</b>：只带裁定里<b>真的有判据</b>的那几位。
     *
     * <p>★ 为什么不直接收 {@code card/Card}：那会把 {@code ai/**} 绑到卡牌目录与装载器上
     * （并让离线夹具必须造一张完整的 {@code Card}）。这里需要的只有
     * 「槽位（决定左右顺序）/ 是不是法术 / 要多少经验」三样
     * ⇒ 保持纯 ⇒ 测试里可以塞任意夹具。
     *
     * @param slot   手牌<b>格号</b>（<b>2~9</b>，与 {@code CardLoadout} 的「2~9 号格」一致；
     *               ★ <b>不是</b> Bukkit 的 0-based 下标 —— 下标 = 格号 − 1；
     *               ★ 「从左到右」就是它的<b>升序</b>）
     * @param cardId 卡牌 id（只用于回传，本类不解释它）
     * @param spell  是不是法术卡
     *               ★ <b>2026-10-21 起人机会放法术</b>（用户裁定取代 R60 那半句）⇒
     *               这个字段不再是「跳过」的依据，而是「走召唤分支还是法术分支」的分岔
     * @param cost   这张卡的经验费用（与 {@code match/MatchService} 的经验池同量纲；
     *               ★ 判「付不付得起」用 {@code policy/XpPool} 的同一口径）
     * @param rule   ★ <b>法术规则</b>（2026-10-21）：法术卡必须是 {@code training.spell-rules}
     *               里配好的那一条；召唤卡恒为 {@code null}
     * @param buildingRule ★ <b>建筑类召唤牌的落点规则</b>（2026-10-21 追加，{@code docs/32}）：
     *               来自 {@code training.building-placements}；**非建筑 / 没配 ⇒ {@code null}**
     *               （= 走普通召唤规则：自己信标）。法术卡恒为 {@code null}
     */
    public record HandCard(int slot, String cardId, boolean spell, int cost,
                           io.mczju.infernalclash.config.BotSpellRule rule,
                           io.mczju.infernalclash.config.BuildingPlacementRule buildingRule) {

        /** 兼容五参构造（没有建筑规则）。 */
        public HandCard(int slot, String cardId, boolean spell, int cost,
                        io.mczju.infernalclash.config.BotSpellRule rule) {
            this(slot, cardId, spell, cost, rule, null);
        }

        /** 兼容旧夹具的四参构造（召唤卡：两条规则都恒为 {@code null}）。 */
        public HandCard(int slot, String cardId, boolean spell, int cost) {
            this(slot, cardId, spell, cost, null, null);
        }

        /** 严格校验：坏夹具（槽位越界 / id 空白 / 负费用 / 规则挂错地方）当场炸。 */
        public HandCard {
            if (slot < FIRST_HAND_SLOT || slot > LAST_HAND_SLOT) {
                throw new IllegalArgumentException("手牌格号必须在 " + FIRST_HAND_SLOT + "~"
                        + LAST_HAND_SLOT + "（与 CardLoadout 的「2~9 号格」一致；"
                        + "这是格号不是 Bukkit 下标，下标 = 格号 - 1），实际是 " + slot);
            }
            if (cardId == null || cardId.isBlank()) {
                throw new IllegalArgumentException("卡牌 id 不能为空（槽位 " + slot + "）");
            }
            if (cost < 0) {
                throw new IllegalArgumentException("卡牌费用不能为负（卡 " + cardId
                        + "，实际 " + cost + "）");
            }
            if (!spell && rule != null) {
                throw new IllegalArgumentException("召唤卡不该带法术规则（卡 " + cardId
                        + "，规则 " + rule + "）—— 规则只对「怎么放法术」有意义，"
                        + "写在召唤卡上是一条永远不生效的配置");
            }
            if (spell && buildingRule != null) {
                throw new IllegalArgumentException("法术卡不该带建筑落点规则（卡 " + cardId
                        + "，规则 " + buildingRule + "）—— 建筑落点只对召唤卡有意义");
            }
        }
    }

    /** 召唤物的落点 / 法术的施放口径（★ 2026-10-21 新增 {@link #SPELL}）。 */
    public enum Placement {

        /**
         * ★ 己方信标处（R60「放置在自己信标处」），且必然在<b>己方半场</b>内
         * （【Q23】＋ R28：人机必须遵守游戏规则，召唤牌只能在己方半场使用）。
         */
        AT_OWN_BEACON,

        /**
         * ★★ <b>法术</b>（2026-10-21）：落点不在这里决定 —— 由
         * {@code ai/BotSpellTargeting} 按这张卡的规则算
         * （敌人的位置 / 随机友方召唤物的位置 / 它的朝向 / 它本人）。
         *
         * <p>★ 为什么它是一个独立的 {@code Placement} 而不是「AT_OWN_BEACON 的特例」：
         * 法术**不受「仅己方半场」限制**（{@code docs/02} §6.4），
         * 并且「己方信标处」这个落点对法术根本不成立 ⇒ 两者必须能分开。
         */
        SPELL
    }

    // ==================================================================================
    // 二、判据（每条都是纯函数）
    // ==================================================================================

    /**
     * 这张牌是不是召唤卡。
     *
     * <p>★ <b>2026-10-21 起它不再是"要不要打"的判据</b>（人机会放法术了）；
     * 现在只剩两个用途：① {@link #chooseCard} 那条**旧口径兼容入口**（「随机打召唤卡」）；
     * ② 读代码时区分两种分支。生产路径判「打不打」看 {@link #isCastable}。
     */
    public static boolean isSummon(HandCard card) {
        return card != null && !card.spell();
    }

    /**
     * ★ <b>这一格现在能不能打</b>：经验付得起 <b>且</b>（法术卡要有规则）。
     *
     * <p>★★ <b>2026-10-21 改口径</b>：旧实现是「只有召唤卡 + 付得起」，因为 R60 写着
     * 「人机不会放法术」。本轮用户裁定取代了那半句 ⇒ 法术卡不再被跳过，
     * 判据只剩<b>费用</b>（法术的「有没有目标」是执行层的事，见
     * {@code ai/TrainingBotDriver} 的「等 5 秒没目标就跳过」）。
     *
     * @param xp 该阵营当前的经验池（{@code MatchService.xp(side)}）
     */
    public static boolean isCastable(HandCard card, double xp) {
        return card != null && affordable(card, xp) && (!card.spell() || card.rule() != null);
    }

    /** 经验够不够付这张卡（口径与 {@code policy/XpPool.canAfford} 一致：够 ⇔ {@code xp >= cost}）。 */
    public static boolean affordable(HandCard card, double xp) {
        return card != null && xp >= card.cost();
    }

    /**
     * ★★ <b>「8 张轮流」的唯一判据</b>（2026-10-16 第八轮裁定；逐字留档 {@code docs/24} §5.9）。
     *
     * <h2>用户原话（两句，第二句是<b>纠正</b>）</h2>
     * <ol>
     *   <li>「<b>人机打牌不会只打一张，而是1-8张轮流打</b>」
     *       ⇒ 取代原先「每次都从最左（1 号）那张重新挑」的字面实现；</li>
     *   <li>★ 「<b>不是，它付不起不应该跳过，而是等待直到这张牌可以打出，
     *       否则人机永远不会打出某些高费牌</b>」
     *       ⇒ 取代「付不起 ⇒ 跳过这张、继续往后看」（那是我第一版读错的取舍，
     *       **已作废**，见 {@code docs/24} §5.9 的纠正块）。</li>
     * </ol>
     *
     * <h2>★ 为什么会有这一改（实机证据）</h2>
     * 旧实现的字面结果是：人机每秒都打同一张 ——
     * {@code E:\MCpaper\logs\latest.log} 17:15:49~17:16:16 **每秒一条**
     * 「训练人机（RED）打出 <b>swamp_guerrillas</b> ⇒ 生成 3 个召唤物 @0,75,-130」，
     * 收尾「本局共打出 <b>180 张牌</b>」—— ★ 180 张**全是同一张**
     * （左起第一张、1 费、永远付得起）⇒ 玩家看不到 8 张牌的差别；
     * ★ 而改成「跳过付不起的」之后，第 2~8 张里那些**高费牌仍可能永远轮不到**
     * （每秒都有便宜的牌可打）—— 这正是用户那句纠正要防的事。
     *
     * <h2>判据（唯一口径）</h2>
     * <ol>
     *   <li><b>指针指向「这一张」</b>（初始 = 槽位 2 那张 = 卡组第 1 张）；</li>
     *   <li><b>只尝试这一张</b>：付得起 ⇒ <b>打出</b>；付不起 / 是法术卡 ⇒ <b>等待</b>
     *       ⇒ 这一 tick <b>不出牌</b>，且指针<b>不动</b>；</li>
     *   <li>打出之后指针<b>前进到下一张</b>（末尾<b>回绕</b>到第 1 张）——
     *       ★ <b>只前进一格</b>；</li>
     *   <li>⇒ 效果：8 张按槽位顺序 1→2→…→8→1 循环，<b>每张都一定会被打出</b>
     *       （用户的顾虑：「否则人机永远不会打出某些高费牌」）。</li>
     * </ol>
     * ★ <b>没有「跳过」这条路</b>：判据里不存在"往后找下一张"的循环
     * （想加回来要先问用户 —— 那是被明确否掉的做法）。
     *
     * <h2>★ 方向与顺序口径（与旧实现一致，不新增约定）</h2>
     * 「这一张 / 下一张」= 按 <b>{@link HandCard#slot()} 格号升序</b>（2~9）——
     * 即 {@link #orderedBySlot(List)} 排出来的那份列表的下标方向；
     * 指针是**这份有序列表**里的下标（<b>不是</b>格号，也不是入参列表的下标）。
     *
     * @param hand    给定的一手牌（{@code null} / 空 ⇒ 没有可打的牌；★ 顺序无关，本方法自己排序）
     * @param xp      该阵营当前的经验池
     * @param pointer 轮到哪一张：有序手牌里的下标（0 起）；★ 越界 / 负数一律夹进合法范围
     * @return 选中项；没轮到时 {@code index == -1} 且 {@code nextPointer == 夹好的 pointer}
     */
    public static SlotPick rotatePick(List<HandCard> hand, double xp, int pointer) {
        List<HandCard> ordered = orderedBySlot(hand);
        int current = normalizePointer(pointer, ordered.size());
        if (ordered.isEmpty()) {
            return new SlotPick(-1, current, -1, null);
        }
        HandCard card = ordered.get(current);
        if (!isCastable(card, xp)) {
            // ★ 等待：付不起（★ 2026-10-21 起这是**唯一**的等待理由 —— 法术卡不再被跳过，
            //   它会走「算目标 → 等 5 秒没目标就跳过」那条路，见 ai/TrainingBotDriver）
            return new SlotPick(-1, current, -1, null);
        }
        // ★ 付得起 ⇒ 打这一张；指针前进**一格**（到末尾回绕到第 1 张）
        return new SlotPick(current, nextPointerOf(current, ordered.size()),
                card.slot(), card.cardId());
    }

    /**
     * 一次「轮流出牌」的选中项。
     *
     * @param index       轮到的那张在**有序手牌**里的下标（{@code -1} = 这一 tick 不出牌）
     * @param nextPointer 下一次的起点（★ 只有<b>真的打出</b>才前进一格；等待时原样不动）
     * @param slot        轮到的那张的<b>格号</b>（2~9；{@code -1} = 没选中）
     * @param cardId      轮到的那张的 id（{@code null} = 没选中）
     */
    public record SlotPick(int index, int nextPointer, int slot, String cardId) {

        /** 这一 tick 选中了吗。 */
        public boolean picked() {
            return index >= 0;
        }
    }

    /**
     * 指针前进一位（到末尾<b>回绕</b>到第 1 张）。
     *
     * @param pointer 当前位置（0 起；★ 会先夹进合法范围）
     * @param size    有序手牌的张数（{@code <= 0} ⇒ 返回 {@code 0}）
     */
    public static int nextPointerOf(int pointer, int size) {
        if (size <= 0) {
            return 0;
        }
        int current = normalizePointer(pointer, size);
        return (current + 1) % size;
    }

    /** 把指针夹进 {@code [0, size)}（{@code size <= 0} ⇒ {@code 0}）。 */
    public static int normalizePointer(int pointer, int size) {
        if (size <= 0) {
            return 0;
        }
        int mod = pointer % size;
        return mod < 0 ? mod + size : mod;
    }

    /**
     * ★★ <b>「能打的牌里最靠左的那张」</b>（<b>不带</b>指针：探针 / 旧口径用的那一半）。
     *
     * <p>⚠️ <b>它不是训练人机的现行规则</b>：现行规则是 {@link #rotatePick} 的
     * <b>8 张轮流、付不起就等这一张</b>（用户 2026-10-16 的两句裁定）。
     * 本方法保留下来是因为它表达的是「能打的牌里最靠左的那张」这一条<b>与指针无关</b>的事实 ——
     * 探针（{@link #castableSlots}）与「入参列表下标 vs 格号」那几条断言仍在用它。
     * ★ 生产路径（{@link #decide}）**不再**调它。
     *
     * <p>★ 两个「下标」别混：{@link HandCard#slot()} 是<b>快捷栏格号</b>（2~9），
     * 而本方法的返回值是 {@code hand} 这个 {@code List} 里的<b>位置</b>（0 起）——
     * 两者只有在「传进来的手牌恰好按格号排好且从 2 号格开始」时才相同
     * （{@code BotPolicyTest} 里有一条专门断言这件事，免得下游把返回值当成格号用）。
     *
     * @param hand 给定的一手牌（{@code null} / 空 ⇒ {@code -1}；列表里的 {@code null} 条目被跳过）
     * @param xp   该阵营当前的经验池
     * @return 能打的那张牌在 {@code hand} 里的<b>列表下标</b>；<b>没有能打的 ⇒ {@code -1}</b>
     */
    public static int firstCastableIndex(List<HandCard> hand, double xp) {
        if (hand == null || hand.isEmpty()) {
            return -1;
        }
        int bestIndex = -1;
        int bestSlot = Integer.MAX_VALUE;
        for (int index = 0; index < hand.size(); index++) {
            HandCard card = hand.get(index);
            if (!isCastable(card, xp)) {
                continue;
            }
            if (card.slot() < bestSlot) {
                bestSlot = card.slot();
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    /** 能打的<b>格号</b>（升序）—— 探针 / 日志用（{@code firstCastableIndex} 是它的第一个元素）。 */
    public static List<Integer> castableSlots(List<HandCard> hand, double xp) {
        List<HandCard> ordered = orderedBySlot(hand);
        List<Integer> slots = new ArrayList<>();
        for (HandCard card : ordered) {
            if (isCastable(card, xp)) {
                slots.add(card.slot());
            }
        }
        return List.copyOf(slots);
    }

    /** 能打的张数（{@code 0} = 这一手没有可打的牌 —— ★ 正常情况，不是故障）。 */
    public static int castableCount(List<HandCard> hand, double xp) {
        return castableSlots(hand, xp).size();
    }

    /**
     * ★ <b>落点是否合法</b>：{@code docs/02} §9【Q23】已经裁定「人机必须遵守游戏规则」，
     * 而 R28 的规则是<b>召唤牌只能在己方半场使用</b>。
     *
     * <p>★ 这里<b>不</b>接受"敌半场也能放"的任何分支 —— 想放宽必须先改这条判据与它的测试
     * （本项目对"范围"一贯如此：两道锁，缺一就会被后人改回去）。
     *
     * @param inOwnHalf 落点是否落在<b>己方半场</b>内（矩形判据在 {@code arena/HalfField}）
     */
    public static boolean isLegalPlacement(boolean inOwnHalf) {
        return inOwnHalf;
    }

    /**
     * ★★ <b>信标真的在自己半场里吗</b>（人机落点的几何断言）——
     * 用户裁定的落点是「自己信标处」，而信标点位与半场矩形是 {@code arenas.yml} 里
     * <b>两处独立采集</b>的数据 ⇒ 「信标在自己半场里」不是靠「信标」这个名字保证的，
     * 必须真的判一次（采集错了就会出现「人机把召唤物放到敌方半场」这种违反 Q23 的行为）。
     *
     * @param beacon 自己阵营的信标点位（{@code arenaConfig.beacon(side)}；{@code null} ⇒ 没采集）
     * @param half   自己阵营的半场矩形（{@code arenaConfig.half(side)}；{@code null} ⇒ 不可用）
     * @return 两者齐备、世界一致、且信标那一格落在半场矩形内（闭区间）
     */
    public static boolean beaconInOwnHalf(Point beacon, HalfField half) {
        return beacon != null && half != null
                && beacon.world() != null && beacon.world().equals(half.world())
                && half.contains(beacon.cellX(), beacon.cellZ());
    }

    // ==================================================================================
    // 三、决策
    // ==================================================================================

    /**
     * 一次决策。
     *
     * @param playable  这一次要不要出牌（{@code false} ⇒ 下面三项为 {@code null}）
     * @param cardId    选中的卡牌 id（{@code playable == true} 时非空）
     * @param placement 落点口径（{@code playable == true} 时 = {@link Placement#AT_OWN_BEACON}）
     * @param beacon    落点坐标 = <b>自己阵营的信标点位</b>
     *                  （★ {@link #decide} 给出的决策<b>一定</b>带它，因为生成实体要用它；
     *                  旧的 {@link #chooseCard} 随机口径只回答"打哪张"⇒ 此处为 {@code null}）
     * @param reason    给人看的一句话（★ 必须说清"为什么打这张 / 为什么不出"，含出处）
     * @param order     打出的这张在**有序手牌**里的下标（0 起；{@code playable == false} ⇒ {@code -1}）
     *                  —— ★ 它是「轮流出牌」那条链的证据（测试逐次断言 0,1,2,…）
     * @param nextPointer 下一次出牌的**起点**（= 本次打出的那张的下一位；到末尾回绕到第 1 张）。
     *                  ★ {@code playable == false} ⇒ 等于「传入的那个指针」（跳过不改变起点）。
     *                  默认 {@code -1} 只为兼容旧仓库里那些手工构造 {@code Decision} 的夹具
     *                  （生产路径永远带真实值，见 {@link #decide}）。
     * @param rule     ★ 法术规则（2026-10-21）：{@code placement == SPELL} 时非空
     *                 （执行层靠它选「以谁为中心 / 对谁用」）；召唤卡恒为 {@code null}
     * @param buildingRule ★ <b>建筑落点规则</b>（2026-10-21 追加）：召唤卡且该卡配了
     *                 {@code training.building-placements} 时非空；执行层靠它算「放塔的哪一侧」
     *                 （{@code null} ⇒ 走普通规则：自己信标）
     */
    public record Decision(boolean playable, String cardId, Placement placement, Point beacon,
                           String reason, int order, int nextPointer,
                           io.mczju.infernalclash.config.BotSpellRule rule,
                           io.mczju.infernalclash.config.BuildingPlacementRule buildingRule) {

        /** 兼容旧构造（两条规则都没有）。 */
        public Decision(boolean playable, String cardId, Placement placement, Point beacon,
                        String reason, int order, int nextPointer,
                        io.mczju.infernalclash.config.BotSpellRule rule) {
            this(playable, cardId, placement, beacon, reason, order, nextPointer, rule, null);
        }

        /** 兼容用的七参构造（无规则；召唤卡与旧夹具）。 */
        public Decision(boolean playable, String cardId, Placement placement, Point beacon,
                        String reason, int order, int nextPointer) {
            this(playable, cardId, placement, beacon, reason, order, nextPointer, null, null);
        }

        /** 兼容用的四参构造：{@code order} / {@code nextPointer} 记为「未知」。 */
        public Decision(boolean playable, String cardId, Placement placement, Point beacon,
                        String reason) {
            this(playable, cardId, placement, beacon, reason, -1, -1, null, null);
        }

        /** 校验「不可出牌 ⇒ 三项全空」「可出牌 ⇒ 至少给得出卡与落点口径」，免得下游拿到半截决策。 */
        public Decision {
            if (playable) {
                if (cardId == null || cardId.isBlank() || placement == null) {
                    throw new IllegalArgumentException("可出牌的决策必须带齐 cardId 与 placement");
                }
                if (placement == Placement.SPELL && rule == null) {
                    throw new IllegalArgumentException("法术决策必须带规则（卡 " + cardId
                            + "）—— 没有规则就算不出落点");
                }
            } else if (cardId != null || placement != null || beacon != null) {
                throw new IllegalArgumentException("不可出牌的决策不许带 cardId / placement / beacon");
            }
        }

        /** 这一次是不是「放法术」（而不是召唤）。 */
        public boolean spell() {
            return placement == Placement.SPELL;
        }
    }

    /**
     * ★★ <b>这一次该打哪张、打到哪</b> —— 用户裁定的那条算法：
     * 「<b>人机打牌不会只打一张，而是 1-8 张轮流打</b>」（2026-10-16 第八轮裁定，
     * 逐字留档 {@code docs/24} §5.9）＋ 更早那条「<b>费用允许时…放置在自己信标处</b>」。
     *
     * <h2>步骤（逐条对应原话）</h2>
     * <ol>
     *   <li><b>8 张轮流</b>：从 {@code pointer} 处往后走（{@link #rotatePick}）；
     *       ★ 指针在 {@link Decision#nextPointer()} 里回传，由执行层在<b>真的打出之后</b>存下来；</li>
     *   <li>★★ <b>法术卡也打</b>（2026-10-21 用户裁定：「人机现在需要能够出法术牌」，
     *       取代 R60 的「不会放法术」）—— 走 {@link Placement#SPELL}，
     *       落点与单体目标由 {@code ai/BotSpellTargeting} 按这张卡的规则算；</li>
     *   <li>★★ <b>费用允许才打</b>；付不起 ⇒ <b>等这一张</b>（指针不动，
     *       下一 tick 还试它）—— ★ 2026-10-16 第八轮用户纠正，旧的
     *       「付不起 ⇒ 跳过这张、继续往后看；绕完一圈都付不起 ⇒ 不出牌」<b>已作废</b>
     *       （留档 {@code docs/24} §5.9）；</li>
     *   <li>★★ <b>「不前进指针」不限于"付不起"</b>（2026-10-16 第九轮确认并钉住，裁定
     *       {@code docs/24} §6.2）：信标没采集 / 不在己方半场、
     *       取不到卡池里的卡、生成失败、法术施放失败 —— <b>这些失败也一律不前进指针</b>。
     *       ★ 判据只有一条：<b>指针只在"真的打出了这一张"之后前进</b>
     *       （执行层 {@code TrainingBotDriver#castOnce} 里 {@code handPointer} 的
     *       <b>唯一</b>赋值点就在真正生成 / 施放成功之后）⇒ 8 张轮流的性质不会被任何一类失败破坏；
     *       ★ <b>唯一例外</b>（2026-10-21 用户裁定）：「**等 5 秒没目标就跳过**」——
     *       法术卡连续 100 tick 算不出合法目标时，执行层会<b>跳过这一张</b>并前进指针
     *       （否则人机可能整局卡死在"治疗却没有友方"那一格）；</li>
     *   <li>召唤卡的落点 = <b>自己信标</b>（{@code beacon}）⇒ 天然在己方半场内
     *       （★ 用 {@link #beaconInOwnHalf} 判一次，采集错了会明确拒绝：见「失败方向」）。</li>
     * </ol>
     *
     * <h2>失败方向（一处都不静默，也不放宽范围）</h2>
     * <ul>
     *   <li>手牌为空 / 全都付不起 ⇒ {@code playable == false} + 说清是哪一种
     *       （★ 这是<b>正常情况</b>，不是故障，所以返回原因而不是抛异常）；</li>
     *   <li>召唤卡：信标没采集 或 信标不在自己半场里 ⇒ {@code playable == false} + 说清原因
     *       —— ★ <b>绝不</b>退化成「放到玩家脚下」「放到敌方半场」或别的兜底落点
     *       （那会同时违反「放置在自己信标处」与【Q23】两条裁定）；
     *       ★ 法术卡<b>不</b>受这条限制（{@code docs/02} §6.4：法术可施于任何区域）；</li>
     *   <li>手牌里有一张牌槽位越界 ⇒ 它的 <b>构造器</b>已经抛过（{@link HandCard}），
     *       所以这里不会再遇到。</li>
     * </ul>
     *
     * @param hand     给定的一手牌（{@code null} 视同空；★ 顺序<b>无关</b>，本方法自己按槽位排序）
     * @param xp       该阵营当前的经验池（{@code MatchService.xp(side)}）
     * @param beacon   该阵营的<b>信标点位</b>（{@code arenaConfig.beacon(side)}，可能为 {@code null}）
     * @param ownHalf  该阵营的<b>半场矩形</b>（{@code arenaConfig.half(side)}，可能为 {@code null}）
     * @param pointer  轮到的起点（有序手牌里的下标，0 起；★ 越界 / 负数会被夹进合法范围）
     */
    public static Decision decide(List<HandCard> hand, double xp, Point beacon, HalfField ownHalf,
                                  int pointer) {
        List<HandCard> ordered = orderedBySlot(hand);
        if (ordered.isEmpty()) {
            return new Decision(false, null, null, null,
                    "手牌为空，不出牌（正常情况，不是故障；手牌的来源属缺口 G12，由调用方决定）",
                    -1, normalizePointer(pointer, 0));
        }

        SlotPick pick = rotatePick(hand, xp, pointer);
        if (!pick.picked()) {
            // ★ 等待（**不是**跳过）：轮到的那张付不起 ⇒ 这一 tick 什么都不做，
            //   指针不动，直到它付得起为止（用户 2026-10-16 的纠正，见 rotatePick 的 javadoc）
            return new Decision(false, null, null, null,
                    waitingReason(ordered, normalizePointer(pointer, ordered.size()), xp),
                    -1, pick.nextPointer());
        }
        HandCard picked = ordered.get(pick.index());

        // ★★ 法术卡：落点由规则决定（敌人的位置 / 友方召唤物的位置 / 朝向 / 它本人），
        //    这里只负责把「轮到的是法术、规则是哪一条」交给执行层。
        if (picked.spell()) {
            if (picked.rule() == null) {
                // 装载期已经拒绝过（法术卡没配规则 = 不可用卡）⇒ 这里是兜底
                return new Decision(false, null, null, null,
                        "轮到第 " + (pick.index() + 1) + " / " + ordered.size() + " 张 "
                                + picked.cardId() + " 是法术卡，但它没有配 training.spell-rules，"
                                + "算不出落点（装载期本应已拒绝启动）", -1, pick.index());
            }
            return new Decision(true, picked.cardId(), Placement.SPELL, null,
                    "8 张轮流：轮到第 " + (pick.index() + 1) + " / " + ordered.size() + " 张，"
                            + "放槽位 " + picked.slot() + " 的法术 " + picked.cardId() + ""
                            + "（费用 " + picked.cost() + " ≤ 经验 " + String.format("%.2f", xp) + "）"
                            + "，规则 " + picked.rule() + "（docs/31 §5）",
                    pick.index(), pick.nextPointer(), picked.rule());
        }

        // ★ 落点 = 自己信标处；但它必须真的在自己半场里（Q23 / R28），否则明确拒绝
        if (beacon == null) {
            return new Decision(false, null, null, null,
                    "本方的信标点位没有采集（arenas.yml 缺 beacon.<side>），不出牌"
                            + "（绝不用别的落点兜底：用户裁定的落点就是「自己信标处」，"
                            + "见 docs/24 §1 第 8 条）", -1, pick.nextPointer());
        }
        if (!beaconInOwnHalf(beacon, ownHalf)) {
            return new Decision(false, null, null, null,
                    "本方信标 " + describe(beacon) + " 不在<b>己方半场</b>内（半场="
                            + (ownHalf == null ? "未采集 / 不可用" : ownHalf.describe())
                            + "），不出牌：Q23 + R28 要求召唤牌只能落在己方半场，"
                            + "放行会让人机违规；请用 /incl arena 核对该点位", -1, pick.nextPointer());
        }

        return new Decision(true, picked.cardId(), Placement.AT_OWN_BEACON, beacon,
                "8 张轮流（docs/24 §5.9「人机打牌不会只打一张，而是1-8张轮流打」）："
                        + "轮到第 " + (pick.index() + 1) + " / " + ordered.size() + " 张，"
                        + "打槽位 " + picked.slot() + " 的 " + picked.cardId() + ""
                        + "（费用 " + picked.cost() + " ≤ 经验 " + String.format("%.2f", xp) + "）"
                        + "，放到自己信标处 " + describe(beacon)
                        + "（R60「放置在自己信标处」+ Q23 己方半场）"
                        + (picked.buildingRule() == null
                        ? "" : "｜★ 建筑落点规则 " + picked.buildingRule()
                                + "（docs/32：执行层会改放到本方防御塔的前/后方）"),
                pick.index(), pick.nextPointer(), null, picked.buildingRule());
    }

    /**
     * ★ <b>兼容旧签名的重载</b>（指针 = 第 1 张）。
     *
     * <p>★ 它只给「只打一次」的调用点与旧夹具用；生产路径（{@code ai/TrainingBotDriver}）
     * 走五参版，因为它要**跨 tick 记住**轮到哪一张（那正是「8 张轮流」的全部含义）。
     */
    public static Decision decide(List<HandCard> hand, double xp, Point beacon, HalfField ownHalf) {
        return decide(hand, xp, beacon, ownHalf, 0);
    }

    /**
     * ★ <b>兼容用的随机重载</b>（保留旧 {@code BotPolicy.chooseCard} 的入口语义）：
     * 在<b>召唤卡</b>里随机取一张（★ 它<b>不</b>判费用 —— 费用那一维属于 {@link #decide}）。
     *
     * <p>⚠️ <b>它不是训练人机的现行规则</b> —— 用户 2026-10-15 的第二轮裁定把
     * 「随机打牌」（R60）收紧成了<b>「从左到右依次」</b>（{@code docs/02} §9 的落实表逐字记着这条）。
     * 保留它只为一件事：给「随机」这条旧口径留一个<b>可复现</b>的入口
     * （必须传种子化的 {@link Random}），将来若用户要改回随机，改的是 {@link #decide} 一行。
     *
     * @param hand   给定的一手牌（来源属缺口 G12 ⇒ 由调用方决定）
     * @param random ★ 必须由调用方给<b>种子化</b>的 {@link Random}：
     *               「同种子 ⇒ 同决策」是本项目对随机的一贯要求（{@code Map.copyOf} 那条教训的同源）
     * @throws IllegalArgumentException {@code random} 为 {@code null}（调用方的编程错误，fail-fast）
     */
    public static Decision chooseCard(List<HandCard> hand, Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random 不能为 null：随机打牌必须可复现"
                    + "（请传一个种子化的 Random，否则「同种子，同结果」这条性质就没了）");
        }
        List<HandCard> castable = new ArrayList<>();
        for (HandCard card : orderedBySlot(hand)) {
            if (isSummon(card)) {
                castable.add(card);
            }
        }
        if (castable.isEmpty()) {
            return new Decision(false, null, null, null,
                    "手牌里没有召唤卡，不出牌（这是「随机打牌」那条旧口径的兼容入口，"
                            + "现行规则是 8 张轮流、召唤与法术都打；这不算故障）");
        }
        HandCard picked = castable.get(random.nextInt(castable.size()));
        return new Decision(true, picked.cardId(), Placement.AT_OWN_BEACON, null,
                "随机打牌（R60 的旧口径，现行裁定是「从左到右依次」）：在 " + castable.size()
                        + " 张召唤卡里随机取一张 " + picked.cardId() + "");
    }

    // ==================================================================================
    // 四、内部
    // ==================================================================================

    /** 按<b>格号</b>升序排一份新列表（跳过 {@code null} 条目；★ 不修改入参）。 */
    private static List<HandCard> orderedBySlot(List<HandCard> hand) {
        List<HandCard> ordered = new ArrayList<>();
        if (hand != null) {
            for (HandCard card : hand) {
                if (card != null) {
                    ordered.add(card);
                }
            }
        }
        ordered.sort(java.util.Comparator.comparingInt(HandCard::slot));
        return List.copyOf(ordered);
    }

    /**
     * ★ <b>「轮到的那张还打不了 ⇒ 等它」的原因</b>（用户 2026-10-16 的纠正）。
     *
     * <p>★★ <b>2026-10-21 起只剩一种情形：付不起</b>。法术卡不再被跳过
     * （人机会放法术了），它走的是执行层那条「算目标 → 等 5 秒没目标就跳过」的路。
     * ★ 文案里必须带「等待 / 不跳过」这几个字：{@code TrainingBotDriver} 靠它做
     * 「原因变了才打一行」的去重，也是现场判断"卡住了还是在等"的唯一线索。
     */
    private static String waitingReason(List<HandCard> ordered, int pointer, double xp) {
        if (ordered.isEmpty()) {
            return "手牌为空，不出牌（正常情况，不是故障；手牌的来源属缺口 G12，由调用方决定）";
        }
        HandCard current = ordered.get(pointer);
        if (current.spell() && current.rule() == null) {
            // ★ 2026-10-21：法术卡没配规则 ⇒ 算不出落点 ⇒ 不出牌。
            //   装载期本应已拒绝启动（TrainingConfig#crossCheckBots），这里是兜底 + 说清去哪儿补。
            return "轮到的第 " + (pointer + 1) + " / " + ordered.size() + " 张 " + current.cardId()
                    + " 是法术卡，但它没有配 training.spell-rules，算不出落点（装载期本应已拒绝启动）"
                    + "｜指针不动";
        }
        return "轮到的第 " + (pointer + 1) + " / " + ordered.size() + " 张 " + current.cardId()
                + " 要 " + current.cost() + " 费、当前经验 " + String.format("%.2f", xp)
                + "，等待这一张（不跳过、指针不动；等它付得起为止）"
                + "：用户裁定「它付不起不应该跳过，而是等待直到这张牌可以打出，"
                + "否则人机永远不会打出某些高费牌」（docs/24 §5.9）";
    }

    private static String describe(Point point) {
        // ★ `arena/Point` 只提供 `cellX()` / `cellZ()`（它**没有** `cellY()` —— 见 Point.java：
        //   格坐标只对 X/Z 有意义，Y 不参与"哪一格"的判定）⇒ 这里的 y 直接取原始值。
        return point.world() + " " + point.cellX() + "," + point.y() + "," + point.cellZ();
    }
}
