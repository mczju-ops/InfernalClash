package io.mczju.infernalclash.policy;

/**
 * ★ <b>稀有度的中文名</b> —— 纯函数，一个 {@code Rarity} 进来、一个中文词出去。
 *
 * <h2>它为什么存在（用户逐字）</h2>
 * > 「抽卡结果显示的稀有度应该是”<b>普通</b>“、”<b>史诗</b>“而不是”<b>common</b>“、”<b>rare</b>“」
 *
 * <p>{@code ui/ShopMenu} 的开包结果行原本直接印 {@code grant.rarity().name()}
 * ⇒ 玩家看到的是枚举常量名（{@code COMMON} / {@code EPIC}）—— 那是**给开发者看的标识符**，
 * 不是文案。本类把它换成 5 个中文词，且**只有这一处映射**（界面里不再各写一遍 {@code switch}）。
 *
 * <h2>★ 措辞出处</h2>
 * 这 5 个词不是新造的：{@code docs/02} / {@code docs/19} 的卡牌总表与
 * {@code policy/ExchangeMath} 的兑换表头用的就是
 * <b>基础 / 普通 / 稀有 / 史诗 / 传说</b>（用户 2026-10-20 的裁定原话也是
 * 「按照<b>基础-普通-稀有-史诗-传说</b>的顺序排列」）。
 * ★ 所以这里的名字与图鉴排序口径的**同一个读法**一致，不是第二套叫法。
 *
 * <h2>★ 为什么不进 {@code economy.yml} / {@code display.yml}</h2>
 * 稀有度是**枚举**（5 个，编译期就定死，{@link RarityTable} 与 {@code economy.yml} 的
 * {@code rarities} 段一一对应且**必须**一一对应）。给它做可配置的中文名会引出
 * 「配置里写了第 6 档 / 少写一档 ⇒ 显示空串还是回退」这类问题，
 * 而用户这一条要的只是「别印英文枚举名」。
 * ⇒ 先按用户给的两个词把 5 档补齐；★ 如果他要改措辞，改的就是本表（一处）。
 *
 * <p>★ 本类<b>不碰服务端</b>（只 import {@code java.*} 与同包的 {@link Rarity}）
 * ⇒ 能在普通 JUnit 里逐个断言（{@code RarityNamesTest}），
 * 也符合本项目「`policy/**` 只放纯函数」的纪律。
 */
public final class RarityNames {

    /** 读不出稀有度时的兜底词（★ 绝不给玩家看 {@code null} 或空白）。 */
    public static final String UNKNOWN = "未知";

    private RarityNames() {
    }

    /**
     * 稀有度 → 中文名。
     *
     * @param rarity 稀有度（允许 {@code null} = 数据缺失 / 脏卡池）
     * @return 中文名；{@code null} ⇒ {@link #UNKNOWN}（★ 绝不抛，也绝不返回英文枚举名）
     */
    public static String zh(Rarity rarity) {
        if (rarity == null) {
            return UNKNOWN;
        }
        return switch (rarity) {
            case BASIC -> "基础";
            case COMMON -> "普通";
            case RARE -> "稀有";
            case EPIC -> "史诗";
            case LEGENDARY -> "传说";
        };
    }
}
