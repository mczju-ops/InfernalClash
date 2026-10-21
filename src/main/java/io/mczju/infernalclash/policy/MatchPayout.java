package io.mczju.infernalclash.policy;

/**
 * ★★★ <b>一局结束后的金币结算</b>（纯函数，2026-10-20 新增）。
 *
 * <h2>它为什么存在（现场缺陷）</h2>
 * 2026-10-20 用户报：「首场胜利给的 3000 金币、每次胜利给的 100 金币，在打赢人机时都不发」。
 * 核查结果比这更严重：{@code economy.yml} 的五个金币键
 * （{@code win-reward} / {@code lose-reward} / {@code bot-win-reward} / {@code bot-lose-reward} /
 * {@code first-game-bonus}）<b>一个读者都没有</b> —— {@link ExchangeMath} 只把它们存着，
 * 生产代码里唯一的 {@code addGold} 调用点是调试指令
 * {@code /incl debug profile grant}。也就是说：<b>任何对局打完都不发金币</b>。
 * ⇒ 本类补的是「胜负 ⇒ 该发多少」这一格判定；发放（读档案 / 加钱 / 落盘）由
 * {@code InfernalClashPlugin#grantMatchReward} 负责。
 *
 * <h2>★ 口径（一条都不是我选的）</h2>
 * <table border="1">
 *   <caption>数值全部来自 {@code economy.yml}（用户给定，Q55）</caption>
 *   <tr><th>情形</th><th>奖励键</th><th>出厂值</th></tr>
 *   <tr><td>真人局 · 胜</td><td>{@code gold.win-reward}</td><td>200</td></tr>
 *   <tr><td>真人局 · 败</td><td>{@code gold.lose-reward}</td><td>100</td></tr>
 *   <tr><td>人机局 · 胜</td><td>{@code gold.bot-win-reward}</td><td>100</td></tr>
 *   <tr><td>人机局 · 败</td><td>{@code gold.bot-lose-reward}</td><td>0</td></tr>
 * </table>
 * ★ <b>首通奖励</b>（{@code gold.first-game-bonus}）= 玩家<b>第一次完成</b>一局对局时额外给一次
 * （2026-10-20 用户逐字确认：「第一次完成，含人机，老玩家不补发」）
 * ⇒ 判据就是 <b>结算前的 {@code gamesPlayed == 0}</b>，本类不另存标记
 * （{@code gamesPlayed} 本来就是「已完成对局数」，见 {@code PlayerProgress}）。
 *
 * <h2>★★ 平局：<b>算双方胜利</b>（2026-10-20 用户裁定）</h2>
 * 用户逐字：「**平局在发金币这块算双方胜利**」⇒
 * {@link Result#DRAW_PVP} 走 {@code gold.win-reward}、{@link Result#DRAW_BOT} 走 {@code gold.bot-win-reward}。
 * ★ 之前这里按「本局 0 金币」的**保守默认**（文档没给、我也不造数值，登记在 {@code docs/05} E28），
 * 用户这句话就是那条裁定的答案 ⇒ <b>E28 结案</b>。
 * ★★ 它**只影响金币**：排行榜仍按 {@code docs/24} §10 第 17 条
 * （平局记总场次、**不计胜场**、不断连败）—— 两件事别混（「发钱算赢」≠「榜上算胜场」）。
 *
 * <h2>★ 纯（可离线单测）</h2>
 * 不碰 Bukkit / MGC / 文件：{@code PolicyPurityGuardTest} 盯着这一条。
 */
public final class MatchPayout {

    /** 一局的结果（把「真人对局 / 人机局」× 「胜 / 负 / 平」压成六档）。 */
    public enum Result {
        /** 真人局 · 胜。 */
        PVP_WIN,
        /** 真人局 · 败。 */
        PVP_LOSS,
        /** 人机局 · 胜（★ 以前不发，就是这条链断掉的地方）。 */
        BOT_WIN,
        /** 人机局 · 败（出厂值 0，但**仍然要结算**：它要 +1 场次）。 */
        BOT_LOSS,
        /** 真人局 · 平（★ 2026-10-20 用户裁定：**平局在发金币这块算双方胜利**）。 */
        DRAW_PVP,
        /** 人机局 · 平（同上：按人机局的**胜**那一档发）。 */
        DRAW_BOT
    }

    /**
     * 一次结算的结果。
     *
     * @param matchGold        本局金币（不含首通）
     * @param firstGameBonus   首通奖励（不是第一局 ⇒ 0）
     * @param gamesPlayedAfter 结算之后的 {@code gamesPlayed}（= 结算前 + 1）
     */
    public record Payout(long matchGold, long firstGameBonus, int gamesPlayedAfter) {

        /** 本局应加的金币总额。 */
        public long totalGold() {
            return matchGold + firstGameBonus;
        }

        /** 这一局是否发了首通奖励。 */
        public boolean firstGame() {
            return firstGameBonus > 0L;
        }
    }

    private MatchPayout() {
    }

    /**
     * 算出一局的结算。
     *
     * @param economy           经济表（数值只能来自 {@code economy.yml}）
     * @param result            本局结果（{@code null} ⇒ 抛：调用方必须先判定）
     * @param gamesPlayedBefore 结算前的「已完成对局数」（{@code >= 0}）
     * @return 结算结果；{@code 0} 金币也是合法结果（人机负）
     * @throws IllegalArgumentException 参数非法（{@code null} / 负的场次 / 场次溢出）
     */
    public static Payout of(ExchangeMath economy, Result result, int gamesPlayedBefore) {
        if (economy == null) {
            throw new IllegalArgumentException("economy 不能为 null（金币数值只能来自 economy.yml）");
        }
        if (result == null) {
            throw new IllegalArgumentException("result 不能为 null：本类只算「已经判定了胜负」的对局"
                    + "（中止的对局不结算，见 InfernalClashGame#recordMatchResult）");
        }
        if (gamesPlayedBefore < 0) {
            throw new IllegalArgumentException("gamesPlayedBefore 不能为负：实际 " + gamesPlayedBefore);
        }
        if (gamesPlayedBefore == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("gamesPlayed 已达 int 上限（" + gamesPlayedBefore
                    + "），拒绝自增（不把回绕写进档案）");
        }

        long matchGold = switch (result) {
            case PVP_WIN -> economy.goldReward(ExchangeMath.Reward.PVP_WIN);
            case PVP_LOSS -> economy.goldReward(ExchangeMath.Reward.PVP_LOSS);
            case BOT_WIN -> economy.goldReward(ExchangeMath.Reward.BOT_WIN);
            case BOT_LOSS -> economy.goldReward(ExchangeMath.Reward.BOT_LOSS);
            // ★ 2026-10-20 用户裁定：「平局在发金币这块算双方胜利」⇒ 按各自口径的**胜**发
            case DRAW_PVP -> economy.goldReward(ExchangeMath.Reward.PVP_WIN);
            case DRAW_BOT -> economy.goldReward(ExchangeMath.Reward.BOT_WIN);
        };

        // ★ 首通 = 「结算前还没打过任何一局」。口径由用户 2026-10-20 逐字确认。
        long bonus = gamesPlayedBefore == 0 ? economy.firstGameBonus() : 0L;

        return new Payout(matchGold, bonus, gamesPlayedBefore + 1);
    }
}
