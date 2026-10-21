package io.mczju.infernalclash.card;

import java.util.Locale;

/**
 * ★★★ <b>效果召唤（{@code SUMMON} / {@code SUMMON_DELAYED} / {@code SUMMON_PERIODICALLY}）
 * 的「头顶名牌」该怎么定</b> —— 纯函数，2026-10-19 新增。
 *
 * <h2>它修的是什么（用户实机，逐字）</h2>
 * 「新发现：**灵狐引路召唤的三只狐狸名字居然叫「效果召唤」而不是 1 费卡灵狐**；
 * 信息素和掘墓人可能也存在这个问题，请你把**召唤物的名字都修改正确**」。
 *
 * <p>根因：法术 / 能力召唤出来的实体，走的是一张**合成卡**
 * （{@code spell/EffectApplier#syntheticSummonCard}），而它的 {@code display-name}
 * 原先被硬编码成 {@code "（效果召唤）"} —— 于是一个**文本占位符**变成了所有这类召唤物的名牌。
 * 那个字符串对玩家毫无意义（它描述的是"实现方式"，不是"这是什么生物"）。
 *
 * <h2>三条取值规则（按优先级）</h2>
 * <ol>
 *   <li><b>卡里写了 {@code group.name}</b> ⇒ 就用它（★ 出厂那张召唤卡都写了，
 *       所以玩家看到的名字是**确定的汉字**，不依赖客户端语言）；</li>
 *   <li>没写 ⇒ 用<b>这个实体自己的名字</b>：MiniMessage 的
 *       {@code <lang:entity.minecraft.fox>} ⇒ 客户端按**玩家自己的语言**渲染
 *       （中文客户端「狐狸」、英文客户端「Fox」）；</li>
 *   <li>连实体键都解析不出来 ⇒ 退化成**实体键本身**（{@code FOX}）——
 *       ★ 无论如何**不再**返回 {@code "（效果召唤）"} 那种占位符
 *       （失败方向 = "名字难看"，而不是"名字撒谎"）。</li>
 * </ol>
 *
 * <h2>★ 为什么阵营色仍然会盖上去</h2>
 * {@code policy/SideNameColor#apply} 会先剥颜色标签再套阵营色，而它**刻意不剥**
 * {@code <lang:…>}（见那个方法的 javadoc）⇒ 本地化名字与"红/蓝区分阵营"可以共存。
 *
 * <p>★ 纯函数（不碰服务端对象）：{@code EntityType} 是枚举，{@code translationKey()}
 * 是常量字段；任何异常都被吞成规则 ③。
 */
public final class SummonNames {

    private SummonNames() {
    }

    /**
     * 一次效果召唤的头顶名牌。
     *
     * @param explicitName 卡里 {@code group.name} 的值（可为 {@code null} / 空白 = 没写）
     * @param entityKey    这一组的实体键（{@code mob-groups.yml} 的键，例如 {@code FOX}）
     * @return 非空的名字文本（可能是 MiniMessage 的 {@code <lang:…>}）；
     *         ★ <b>永不为 {@code null}、也永不是</b> {@code "（效果召唤）"}
     */
    public static String forGroup(String explicitName, String entityKey) {
        if (explicitName != null && !explicitName.isBlank()) {
            return explicitName.trim();
        }
        return languageName(entityKey);
    }

    /**
     * 实体键 → 客户端本地化的名字（{@code <lang:entity.minecraft.fox>}）。
     *
     * <p>★ <b>两条路</b>（都要，缺一不可）：
     * <ol>
     *   <li>服务端在跑 ⇒ 用 {@code EntityType#translationKey()}（原版自己给的键，最准）；</li>
     *   <li>★ <b>离线</b>（单元测试 / 装载期）那个方法**拿不到键**（26.2 的
     *       {@code EntityType#getKey()} 走注册表，离线会抛）⇒ 按原版命名规则推导
     *       {@code entity.minecraft.<小写实体键>}。实测：{@code FOX → entity.minecraft.fox}，
     *       与运行期 API 给出的完全一致 ⇒ 两条路的结果**不会分叉**。</li>
     * </ol>
     *
     * <p>★ 失败方向：实体键根本不存在（拼错）⇒ 返回**键本身**（难看但不撒谎），
     * 绝不返回 {@code null}（{@code null} 就是"没名字"）。
     */
    static String languageName(String entityKey) {
        String key = entityKey == null ? "" : entityKey.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            return "召唤物";
        }
        if (!VANILLA_KEY.matcher(key).matches()) {
            // 连"原版实体键的形状"都不是 ⇒ 不猜，直接用它本身
            return key;
        }
        // ① 运行期：原版自己给的翻译键（最准）
        try {
            String translation = org.bukkit.entity.EntityType.valueOf(key).translationKey();
            if (translation != null && !translation.isBlank()) {
                return "<lang:" + translation + ">";
            }
        } catch (Throwable ignored) {
            // ★ 离线（单元测试 / 装载期）必然会走到这里：26.2 的 EntityType 常量表要服务端注册表
            //   ⇒ 落到 ② 的推导式（实测两条路同形：FOX → entity.minecraft.fox）
        }
        // ② 离线 / API 拿不到 ⇒ 按原版命名规则推导（实体键在**装载期**已被 mob-groups 校验过）
        return "<lang:entity.minecraft." + key.toLowerCase(Locale.ROOT) + ">";
    }

    /** 原版实体键的形状（大写字母 / 数字 / 下划线）。 */
    private static final java.util.regex.Pattern VANILLA_KEY =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*");
}
