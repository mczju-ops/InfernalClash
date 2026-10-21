package io.mczju.infernalclash.ai;

/**
 * ★ <b>训练模式的开关判定（纯函数）</b>：{@code 「几个人 + 运营有没有显式要人机」⇒ 一局该怎么开}。
 *
 * <h2>★ 权威与边界</h2>
 * {@code docs/02} §9 只给了一条：<b>单人开局自动选择开启人机</b>（R62）。
 * 它<b>没有</b>规定：
 * <ul>
 *   <li>要不要一个<b>显式的运营开关</b>（{@code /incl ... bot on} 之类）—— 缺口 <b>G14</b>；</li>
 *   <li>两名真人 + 显式要人机的组合算什么 —— 缺口 <b>G14</b>（口径未给）；</li>
 *   <li>超过 2 名玩家怎么办 —— 那属 {@code docs/05} 的 <b>E5</b>（不是本类的事）。</li>
 * </ul>
 * ⇒ 本类的做法与 {@code ai/BotPlan} 一致：<b>能按已裁定的推就推，推不出来的就显式拒绝</b>，
 * 并且把「为什么」写进 {@link ModeDecision#reason()}，绝不静默选一个默认值（铁律 8/11）。
 *
 * <h2>★ 纯</h2>
 * 只依赖 JDK ⇒ <b>离线可单测</b>。★ 铁律 10：人数是<b>参数</b>，
 * 不出现 {@code getOnlinePlayers / getEntities / getLivingEntities / getWorlds}。
 */
public final class TrainingModeRequest {

    private TrainingModeRequest() {
    }

    /**
     * 一次开局请求。
     *
     * @param humanCount            真人玩家数（★ 由调用方给，本类不查世界）
     * @param botRequestedByOperator 运营（管理员 / 配置）有没有<b>显式</b>要求开人机
     */
    public record Request(int humanCount, boolean botRequestedByOperator) {
    }

    /**
     * 判定结果。
     *
     * @param trainingMatch 这一局是不是<b>训练局</b>（含人机）
     * @param botEnabled    人机是否启用
     * @param reason        给人看的一句话（★ 含出处；拒绝时说明缺什么裁定）
     */
    public record ModeDecision(boolean trainingMatch, boolean botEnabled, String reason) {
    }

    /**
     * ★ <b>判定训练模式</b>。
     *
     * <table border="1">
     *   <caption>四种组合的去向</caption>
     *   <tr><th>人数</th><th>运营要人机</th><th>结果</th><th>依据</th></tr>
     *   <tr><td>1</td><td>否</td><td>训练局 + 人机</td><td>★ R62（<b>自动</b>，不需要运营开口）</td></tr>
     *   <tr><td>1</td><td>是</td><td>训练局 + 人机</td><td>同上（运营只是把它说明白）</td></tr>
     *   <tr><td>2</td><td>否</td><td>正常对局，无人机</td><td>R62 只管单人；两人 = 1v1（R1/R2）</td></tr>
     *   <tr><td>2</td><td>是</td><td>★ <b>正常对局，无人机</b> + 告警原因</td>
     *       <td>★ <b>缺，待裁定</b>（G14）：§9 没给"两名真人 + 人机"这种局 ⇒
     *           <b>不擅自开</b>（开出一个 2 打 1 的局是替用户改玩法），只把原因说清楚</td></tr>
     * </table>
     *
     * @throws IllegalArgumentException {@code request} 为 {@code null} 或 {@code humanCount < 1}
     *         —— 编程错误，fail-fast（与 {@link TrainingMatchRules#botEnabledFor} 同口径）
     */
    public static ModeDecision decide(Request request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null");
        }
        int humanCount = request.humanCount();
        if (humanCount < 1) {
            throw new IllegalArgumentException("humanCount 必须是 >= 1 的真人数量，实际是 " + humanCount
                    + "：0 人不成局（docs/02 §9 只说「单人开局」，不是「无人开局」）");
        }

        if (humanCount == 1) {
            return new ModeDecision(true, true,
                    "单人开局，自动开启训练人机（docs/02 §9 R62）"
                            + (request.botRequestedByOperator() ? "；运营也显式要求了人机，一致" : ""));
        }

        if (request.botRequestedByOperator()) {
            // ★ 显式拒绝：不擅自把"2 名真人"变成"2 打 1"
            return new ModeDecision(false, false,
                    "检测到「" + humanCount + " 名真人 + 显式要求人机」这一组合，"
                            + "但 docs/02 §9 只规定了单人开局自动开人机，"
                            + "没有规定真人局里再挂一个人机，缺，待裁定（缺口 G14）"
                            + "，按正常真人局开（人机不启用），请裁定后调整");
        }

        return new ModeDecision(false, false,
                "2 名真人，正常 1v1 对局，不启用训练人机（docs/02 §9 R62 只规定单人开局）"
                        + (humanCount > 2
                                ? "；还有 " + (humanCount - 2) + " 名玩家如何处理属 docs/05 的 E5，不在本类决定"
                                : ""));
    }

    /** {@link #decide} 的一行日志（含"要不要人机"与理由）。 */
    public static String describe(Request request) {
        return decide(request).reason();
    }
}
