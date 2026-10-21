package io.mczju.infernalclash.spell;

/**
 * ★★ <b>「这道视觉闪电劈在哪」</b> —— 纯函数（2026-10-18 新增）。
 *
 * <h2>它修的是哪一条用户反馈（逐字）</h2>
 * 「**法术和海王在右键空气释放时不会触发特效**」。
 *
 * <h2>根因</h2>
 * 闪电原先**只**劈在「被命中的目标」头上 ⇒ 对着空气放（附近没有敌人）时目标集为空
 * ⇒ <b>屏幕上什么都不会发生</b>。而暴风雨的 `spell.fx` **只有** `lightning: true`
 * （没有粒子、没有音效）⇒ 那张卡在"没打中人"时是**彻底的静默**。
 * 海王登场的闪电同理（本来就常在没有敌人的地方登场）。
 *
 * <h2>口径（三格，逐格可复述）</h2>
 * <ol>
 *   <li>卡没声明要闪电 ⇒ {@link Kind#NONE}；</li>
 *   <li>声明了、但<b>一个目标都没打中</b> ⇒ {@link Kind#AT_ORIGIN}（在落点劈一道）；</li>
 *   <li>声明了、打中了若干 ⇒ {@link Kind#PER_TARGET}（每个目标头上各一道）。</li>
 * </ol>
 * ★ 第 2 格是本轮新增的：语义上也更对 —— <b>落雷法术至少要让人看见雷劈在他指的地方</b>。
 *
 * <p>★ 本类不碰 Bukkit（只吃布尔与计数）⇒ 可离线逐格断言。
 */
public final class LightningPlan {

    /** 这一道闪电该怎么劈。 */
    public enum Kind {
        /** 这张卡没有声明闪电 ⇒ 什么都不做。 */
        NONE,
        /** 没有命中任何目标 ⇒ 在**落点**劈一道（让玩家看见"雷劈在他指的地方"）。 */
        AT_ORIGIN,
        /** 命中了目标 ⇒ **每个目标头上各一道**（"依次落雷"的观感）。 */
        PER_TARGET
    }

    private LightningPlan() {
    }

    /**
     * @param wantsLightning 卡里是否声明了闪电（法术是 {@code spell.fx.lightning}，
     *                       能力是 {@code abilities[].fx.lightning}）
     * @param targetCount    本次命中的目标数（可以是 0）
     */
    public static Kind of(boolean wantsLightning, int targetCount) {
        if (!wantsLightning) {
            return Kind.NONE;
        }
        return targetCount <= 0 ? Kind.AT_ORIGIN : Kind.PER_TARGET;
    }
}
