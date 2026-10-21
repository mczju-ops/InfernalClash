package io.mczju.infernalclash.card;

import io.mczju.infernalclash.arena.HalfField;

/**
 * ★ <b>卡牌施放落点判定</b>（纯函数，可离线单测）。
 *
 * <h2>规则出处</h2>
 * <ul>
 *   <li>{@code docs/02} §6.4 与 Q27：对<b>地面</b> ⇒ 落点 = <b>瞄准的那一格</b>；
 *       对<b>空气</b> ⇒ 落点 = <b>玩家自身处</b>；</li>
 *   <li>R28：召唤卡 <b>仅己方半场</b> ⇒ 落点必须落在自己的半场矩形内，否则<b>拒绝施放</b>
 *       （{@code docs/04} §16.7.4）；</li>
 *   <li>★★ {@code docs/04} §17 的裁定：<b>点空气且玩家站在敌半场 ⇒ 拒绝</b>。
 *       理由：R28 约束的是「召唤物出现的位置」，而点空气时落点就是玩家脚下，
 *       否则会出现「站在敌半场往自己脚下放」的绕过；</li>
 *   <li>★ 半场矩形<b>未配置 ⇒ 拒绝施放 + 告警</b>（{@code AGENTS.md} 铁律 10 的 fail-safe，
 *       <b>绝不退化成「放行到全场」</b>）。</li>
 * </ul>
 *
 * <h2>★ 本类不碰服务端</h2>
 * 参数全是布尔与整数，{@link HalfField} 也是纯数据 ⇒ 可以在普通 JUnit 里把每条分支钉死
 * （{@code PlacementResolverTest}）。「把结果变成召唤物」属于边界层，不在这里。
 */
public final class PlacementResolver {

    private PlacementResolver() {
    }

    /** 判定结果。 */
    public enum Decision {

        /** 落在瞄准的那一格（点击了地面）。 */
        AT_TARGET,

        /** 落在玩家自身处（点了空气）。 */
        AT_SELF,

        /** 拒绝：目标不在己方半场（R28）。 */
        REFUSE_NOT_OWN_HALF,

        /** 拒绝：半场矩形未配置（铁律 10 fail-safe，绝不放行到全场）。 */
        REFUSE_NO_HALF_CONFIGURED
    }

    /**
     * 判定结果。
     *
     * @param decision 结论
     * @param cellX    落点格的 X（<b>拒绝时无意义，恒为 {@code -1}</b>）
     * @param cellZ    落点格的 Z（<b>拒绝时无意义，恒为 {@code -1}</b>）
     */
    public record Result(Decision decision, int cellX, int cellZ) {

        /** 是否允许施放（{@link Decision#AT_TARGET} / {@link Decision#AT_SELF} 为真）。 */
        public boolean allowed() {
            return decision == Decision.AT_TARGET || decision == Decision.AT_SELF;
        }

        /**
         * 造一个拒绝结果（坐标一律 {@code -1}）。
         *
         * @throws IllegalArgumentException 传进来的不是两个拒绝原因之一
         *                                  （那是接线错误：允许的结论不该带 {@code -1} 落点）
         */
        public static Result refuse(Decision decision) {
            if (decision != Decision.REFUSE_NOT_OWN_HALF
                    && decision != Decision.REFUSE_NO_HALF_CONFIGURED) {
                throw new IllegalArgumentException("Result.refuse 只接受两个拒绝原因，实际是 " + decision);
            }
            return new Result(decision, -1, -1);
        }
    }

    /**
     * 判定落点（判定顺序见 {@code docs/04} §16.7.4 与 §17）。
     *
     * <ol>
     *   <li>{@code ownHalf == null} ⇒ {@link Decision#REFUSE_NO_HALF_CONFIGURED}
     *       —— ★ <b>先判这个</b>，与落点无关（配置缺失时不给任何「碰巧合法」的机会）；</li>
     *   <li>落点 = {@code clickedGround ? (targetCellX, targetCellZ) : (playerCellX, playerCellZ)}
     *       —— ★ 点空气时落点是<b>玩家自身</b>，而玩家<b>可能站在敌半场</b>；</li>
     *   <li>{@code !ownHalf.contains(落点)} ⇒ {@link Decision#REFUSE_NOT_OWN_HALF}；</li>
     *   <li>通过 ⇒ {@link Decision#AT_TARGET} / {@link Decision#AT_SELF}，并带上落点格坐标。</li>
     * </ol>
     *
     * @param clickedGround 是否点在<b>地面</b>（{@code false} = 点了空气）
     * @param targetCellX   瞄准格的 X（点了空气时调用方可传 0，反正不用）
     * @param targetCellZ   瞄准格的 Z
     * @param playerCellX   玩家所在格 X（点空气时的落点）
     * @param playerCellZ   玩家所在格 Z
     * @param ownHalf       己方半场矩形；<b>{@code null} ⇒ {@link Decision#REFUSE_NO_HALF_CONFIGURED}</b>
     */
    public static Result resolve(boolean clickedGround, int targetCellX, int targetCellZ,
                                 int playerCellX, int playerCellZ, HalfField ownHalf) {
        // ① 配置缺失优先：fail-safe 先于一切落点判断（铁律 10）
        if (ownHalf == null) {
            return Result.refuse(Decision.REFUSE_NO_HALF_CONFIGURED);
        }
        // ② 落点：地面 ⇒ 瞄准格；空气 ⇒ 玩家自身（★ 玩家可能在敌半场，见 ③）
        int cellX = clickedGround ? targetCellX : playerCellX;
        int cellZ = clickedGround ? targetCellZ : playerCellZ;
        // ③ 仅己方半场（闭区间；玩家站在敌半场往脚下放也要拒绝 —— docs/04 §17）
        if (!ownHalf.contains(cellX, cellZ)) {
            return Result.refuse(Decision.REFUSE_NOT_OWN_HALF);
        }
        // ④ 通过
        return new Result(clickedGround ? Decision.AT_TARGET : Decision.AT_SELF, cellX, cellZ);
    }
}
