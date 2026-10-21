package io.mczju.infernalclash.profile;

import io.mczju.infernalclash.policy.Rarity;

/**
 * ★★ <b>「花烈焰粉解锁一张卡」的判据（纯函数）</b> —— 2026-10-17 批次 3 后半新增（U3）。
 *
 * <h2>为什么先有它、再有界面</h2>
 * U3（解锁确认界面）是 {@code docs/28} 第 27 条的落地物。它上面只有两个按钮，但
 * 「点了确认之后到底该发生什么」有 <b>5 条</b>分支，而且每一条都必须能被玩家看懂：
 * <ol>
 *   <li><b>已经拥有</b>（金色版也算拥有）⇒ 什么都不做，回一句「已经拥有」；</li>
 *   <li><b>基础卡</b>（{@code rarity == BASIC}）⇒ <b>不可合成</b>
 *       —— R44「初始全部拥有」⇒ {@code CraftRules} 对 BASIC 会抛，本方法把它变回一句人话；</li>
 *   <li><b>粉不够</b> ⇒ 什么都不做，回一句「还差多少」；</li>
 *   <li><b>够</b> ⇒ 扣粉 + {@link PlayerProgress#unlock}（★ 唯一解锁入口）；</li>
 *   <li>价格本身取不到（配置缺这一档）⇒ 回一句「价格没配好」，
 *       <b>绝不当成 0 粉白送</b>（那是"凭空产卡"）。</li>
 * </ol>
 * ★★ 把这 5 条收进一个纯函数（不碰 Bukkit、不碰菜单）有三个直接好处：
 * ① 逐条穷举断言可以在离线跑；② 界面只负责"把结果念给玩家"，
 * <b>规则不会在两处各写一份</b>（本项目「第二处白名单」的教训）；
 * ③ 将来加「十连解锁」「批量合成」时，走的是同一个判据。
 *
 * <h2>★ 与既有纯函数的关系</h2>
 * 价格<b>不在这里算</b>：{@link CraftRules#dustToCraft(Rarity, boolean)} 是**唯一**的价目表读法
 * （它自己拒绝金卡与 BASIC）⇒ 本方法只做"状态 + 余额"的判定与组合。
 * 解锁<b>不在这里实现</b>：{@link PlayerProgress#unlock(String)} 是唯一入口。
 */
public final class UnlockRules {

    /** 一次解锁尝试的结果。 */
    public enum Status {
        /** 可以解锁（{@code next} 是扣粉并解锁之后的进度）。 */
        OK,
        /** 已经拥有（金色版也算）——不扣任何东西。 */
        ALREADY_OWNED,
        /** 基础卡不可合成（R44 初始全部拥有）。 */
        NOT_CRAFTABLE,
        /** 粉不够。 */
        NOT_ENOUGH_DUST,
        /** 这一档没有价格（配置缺键）——★ 绝不当成 0。 */
        NO_PRICE
    }

    /**
     * 一次解锁尝试的完整结果（可对账、可原文念给玩家）。
     *
     * @param status   结论
     * @param cost     本次价格（{@link Status#NO_PRICE} 时为 {@code 0}，其余情况为真实价）
     * @param next     ★ 解锁成功时的新进度；其它状态一律 {@code null}（调用方不许拿它去存盘）
     * @param message  给玩家的一句话（界面直接念它，不在界面里再造一套措辞）
     */
    public record Outcome(Status status, int cost, PlayerProgress next, String message) {

        /** 成功吗（界面据此决定要不要存盘 + 刷新）。 */
        public boolean ok() {
            return status == Status.OK;
        }
    }

    private UnlockRules() {
    }

    /**
     * 试着解锁一张卡（纯函数：不改入参，返回**新实例**）。
     *
     * @param progress 当前进度（{@code null} ⇒ 抛：进度缺失是编码错误，不是玩家状况）
     * @param cardId   要解锁的卡 id
     * @param rarity   它的稀有度（{@code null} ⇒ 抛，同上）
     * @return 结论 + 新进度 + 给玩家的一句话
     * @throws IllegalArgumentException {@code progress} / {@code cardId} / {@code rarity} 为 null
     */
    public static Outcome attempt(PlayerProgress progress, String cardId, Rarity rarity) {
        if (progress == null) {
            throw new IllegalArgumentException("UnlockRules.attempt：progress 不能为 null");
        }
        if (cardId == null || cardId.isBlank()) {
            throw new IllegalArgumentException("UnlockRules.attempt：cardId 不能为空");
        }
        if (rarity == null) {
            throw new IllegalArgumentException("UnlockRules.attempt：rarity 不能为 null"
                    + "（卡池里每张卡都有稀有度；拿不到说明调用方没把卡查出来）");
        }
        // ① 已经拥有（金色版也算「拥有」—— Q30 已把普通版自动分解，但 owned 里仍是它）
        if (progress.owns(cardId)) {
            return new Outcome(Status.ALREADY_OWNED, 0, null,
                    "你已经拥有这张卡了（不扣任何东西）");
        }
        // ② 基础卡不可合成
        if (rarity == Rarity.BASIC) {
            return new Outcome(Status.NOT_CRAFTABLE, 0, null,
                    "基础卡不能合成（初始就全部拥有）");
        }
        // ③ 价格（唯一读法；配置缺这一档 ⇒ 一句话，绝不当 0）
        int cost;
        try {
            cost = CraftRules.dustToCraft(rarity, false);
        } catch (RuntimeException e) {
            return new Outcome(Status.NO_PRICE, 0, null,
                    "这一档的合成价格没有配置好（不是你的问题，已记进日志）");
        }
        // ④ 粉不够
        if (progress.dust() < cost) {
            return new Outcome(Status.NOT_ENOUGH_DUST, cost, null,
                    "还差 " + (cost - progress.dust()) + " 烈焰粉（需要 " + cost
                            + "，你有 " + progress.dust() + "）");
        }
        // ⑤ 扣粉 + 解锁（两个入口都是既有的；这里只负责顺序）
        PlayerProgress next = progress.spendDust(cost).unlock(cardId);
        return new Outcome(Status.OK, cost, next,
                "已解锁（花费 " + cost + " 烈焰粉，剩余 " + next.dust() + "）");
    }

    /** 给玩家看的「要不要花这么多」那一句（U3 的确认句，键在 {@code display.yml}）。 */
    public static String confirmPrompt(String template, String cardName, int cost) {
        String text = template == null || template.isBlank()
                ? "是否确认花费 {cost} 烈焰粉解锁 {card}？"
                : template;
        return text.replace("{cost}", String.valueOf(cost))
                .replace("{card}", cardName == null ? "" : cardName);
    }
}
