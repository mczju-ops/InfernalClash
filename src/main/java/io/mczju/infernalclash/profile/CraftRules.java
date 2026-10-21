package io.mczju.infernalclash.profile;

import io.mczju.infernalclash.policy.ExchangeMath;
import io.mczju.infernalclash.policy.Rarity;

/**
 * 分解 / 合成的换算 —— **纯函数，可离线单测**。
 *
 * <h2>唯一来源：{@code docs/08} §4 的兑换表（R25~R30，用户给定，**照抄**）</h2>
 * <pre>
 *   稀有度       分解重复普通版 | 抽到重复金卡的自动转化 | 合成普通版
 *   COMMON         10        |        40              |    40
 *   RARE           40        |       160              |   100
 *   EPIC          100        |       400              |   400
 *   LEGENDARY     400        |      1600              |  1600
 *   BASIC        ——（不在表内：R44「初始全部拥有」，可分解就等于凭空产粉）
 * </pre>
 *
 * <p>★ 本类**不重复写这些数字**（{@code docs/04} §16.11.2「谁是谁的唯一来源」）：
 * 它们已经由 P0 的 {@link ExchangeMath} 承载（出厂值 = 用户给定值）。
 * <b>真正的数值由 {@code config/EconomyConfig}（{@code economy.yml} 的 {@code exchange} 段）
 * 在装配时传入</b> —— 本类的三个静态方法用的是**出厂表** {@code ExchangeMath.shipped()}
 * （即 {@code docs/03} §6 的出厂 {@code economy.yml}，它不是 {@code 【待填】} 值，
 * 所以照抄不违反铁律 8）。
 *
 * <h2>★ 两条容易被写错的语义</h2>
 * <ol>
 *   <li><b>金色卡牌无法分解</b> —— 【Q7】补充裁定（{@code docs/08} §4）：UI 上**不提供**
 *       "分解金卡"的入口，只有**普通版**可手动分解。
 *       兑换表第 2 列的"重复金色卡牌兑换数量"是【Q30】**抽到重复金卡时自动转化**的结算量
 *       （那条通道在 {@link ExchangeMath#duplicateGoldPowder}），
 *       所以 {@link #dustFromDismantle} 传 {@code golden = true} 时**抛异常**而不是返回一个数
 *       —— 返回数字等于凭空造出一个文档禁止的入口。</li>
 *   <li><b>金色卡牌不能用尘合成</b> —— 【Q32】已裁定「不可以，金卡只能抽到」
 *       ⇒ {@link #dustToCraft} 传 {@code golden = true} 时同样抛异常。</li>
 * </ol>
 *
 * <p>⚠️ 本类**不碰** Bukkit / MGC（纯逻辑）：{@code PolicyPurityGuardTest} 与
 * {@code BytecodeScopeGuardTest} 都会扫它。
 */
public final class CraftRules {

    /** 出厂经济表（= 用户给定值；真正的数值由 {@code EconomyConfig} 装配时传入）。 */
    private static final ExchangeMath SHIPPED = ExchangeMath.shipped();

    private CraftRules() {
    }

    /**
     * **分解一张卡**得多少尘（按稀有度）。
     *
     * <p>★ 只覆盖**普通版**：{@code golden = true} ⇒ 抛异常，
     * 因为【Q7】补充裁定「金色卡牌无法兑换」（{@code docs/08} §4）——
     * {@code docs/08} §3.3 给的"金色 = 普通版 4 倍"是【Q30】**自动转化**的量，
     * 走 {@link ExchangeMath#duplicateGoldPowder}，**不是**玩家可触发的手动分解入口。
     * 本方法**不发明**一个金卡分解价。
     *
     * @param rarity 稀有度（{@code docs/08} §1.2 的 5 档；BASIC 不在兑换表内 ⇒ 抛异常）
     * @param golden 是否金色变种（{@code true} ⇒ 抛异常，见上）
     * @return 分解所得尘（= {@code exchange.<档>.duplicate-powder}）
     * @throws IllegalArgumentException {@code rarity} 为 null、{@code golden = true}，
     *         或该档不在兑换表内（BASIC），或配置值超出 {@code int} 范围
     */
    public static int dustFromDismantle(Rarity rarity, boolean golden) {
        requireRarity(rarity, "dustFromDismantle");
        if (golden) {
            throw new IllegalArgumentException("金色卡牌无法分解（【Q7】补充裁定「金色卡牌无法兑换」，"
                    + "docs/08 §4）：稀有度 " + rarity + " 没有「手动分解金卡」的价 —— "
                    + "§4 第 2 列（" + exchangeColumn("duplicate-gold-powder") + "）是【Q30】"
                    + "「抽到已拥有的金卡，自动转成烈焰粉」的结算量，"
                    + "请走 ExchangeMath.duplicateGoldPowder(" + rarity + ")，"
                    + "不要用本方法（本方法不发明金卡分解价）");
        }
        long value = SHIPPED.duplicatePowder(rarity);
        return toInt(value, rarity, "duplicate-powder");
    }

    /**
     * ★★ <b>抽到「已拥有的金卡」时自动转成的尘</b>（{@code docs/08} §4 第 2 列，【Q30】结算量）。
     *
     * <p>★ 与 {@link #dustFromDismantle(Rarity, boolean)} 的区别：那个是**手动分解普通卡**的入口，
     * 而金卡**不可手动分解**（【Q7】补充裁定）⇒ 金卡的金色列只能走本方法
     * （= {@code ExchangeMath.duplicateGoldPowder}）。
     *
     * @param rarity 稀有度（BASIC 不在兑换表内 ⇒ 抛异常）
     */
    public static int dustFromDuplicateGold(Rarity rarity) {
        requireRarity(rarity, "dustFromDuplicateGold");
        long value = SHIPPED.duplicateGoldPowder(rarity);
        return toInt(value, rarity, "duplicate-gold-powder");
    }

    /**
     * **合成（解锁）一张卡**要多少尘（按稀有度）。
     *
     * <p>★ 只能合成**普通版**：{@code golden = true} ⇒ 抛异常
     * （【Q32】已裁定「金卡只能抽到」，{@code docs/08} §4）。
     *
     * @param rarity 稀有度（BASIC 不可合成 ⇒ 抛异常）
     * @param golden 是否金色变种（{@code true} ⇒ 抛异常）
     * @return 合成价格（= {@code exchange.<档>.unlock-price}）
     * @throws IllegalArgumentException {@code rarity} 为 null、{@code golden = true}，
     *         或该档不在兑换表内（BASIC），或配置值超出 {@code int} 范围
     */
    public static int dustToCraft(Rarity rarity, boolean golden) {
        requireRarity(rarity, "dustToCraft");
        if (golden) {
            throw new IllegalArgumentException("金色卡牌不能用尘合成（【Q32】已裁定「不可以，金卡只能抽到」，"
                    + "docs/08 §3.3 / §4）：稀有度 " + rarity + " 没有金卡的合成价，"
                    + "本方法不发明一个价");
        }
        long value = SHIPPED.unlockPrice(rarity);
        return toInt(value, rarity, "unlock-price");
    }

    /**
     * ★ 「分解所得是否 &lt;= 合成所需」——防止**分解再合成**凭空刷尘。
     *
     * <p>逐档比对 {@link #dustFromDismantle}(普通版) 与 {@link #dustToCraft}(普通版)。
     * 出厂表下四档全部成立（10≤40 / 40≤100 / 100≤400 / 400≤1600）。
     *
     * <p>⚠️ {@link Rarity#BASIC} 返回 {@code true}：基础卡**既不可分解也不可合成**
     * （不在兑换表内，R44「初始全部拥有」）⇒ **不存在**这个回路，
     * 判据应当是"回路不存在"而不是"数值不满足"。
     *
     * @throws IllegalArgumentException {@code rarity} 为 null
     */
    public static boolean noDustLoop(Rarity rarity) {
        requireRarity(rarity, "noDustLoop");
        if (!SHIPPED.isExchangeable(rarity)) {
            // BASIC：不在兑换表里 ⇒ 两条路都不存在 ⇒ 无可刷
            return true;
        }
        return dustFromDismantle(rarity, false) <= dustToCraft(rarity, false);
    }

    private static void requireRarity(Rarity rarity, String where) {
        if (rarity == null) {
            throw new IllegalArgumentException(where + " 的 rarity 不能为 null"
                    + "（期望 docs/08 §1.2 的 5 档之一：" + java.util.List.of(Rarity.values()) + "）");
        }
    }

    /** 异常消息里引用"§4 的哪一列"（只在 golden=true 时用，不改数值）。 */
    private static String exchangeColumn(String key) {
        return "exchange.<稀有度>." + key;
    }

    private static int toInt(long value, Rarity rarity, String key) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("economy.yml 的 exchange." + rarity + "." + key
                    + " = " + value + " 不能用 int 表达（期望 0 ~ " + Integer.MAX_VALUE
                    + "）；请改配置，而不是在本类里截断");
        }
        return (int) value;
    }
}
