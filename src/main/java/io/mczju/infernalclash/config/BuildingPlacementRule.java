package io.mczju.infernalclash.config;

/**
 * ★★★ <b>建筑类召唤牌「放在哪」—— 三个枚举</b>（2026-10-21 用户裁定）。
 *
 * <h2>出处（用户原话，逐字）</h2>
 * <blockquote>
 * 「1、**后置**，即放在一侧防御塔的后方，距离防御塔4格的位置，放置的一侧取决于哪一侧的
 * 防御塔血量更低，相等时默认放在右侧<br>
 * 2、**中置**，即放在战场的防御塔正前方4格，放在哪一座塔前方同样取决于塔的血量，
 * 放在低血量一侧，默认放右侧<br>
 * 3、**前置**，放在防御塔前方第10格，逼近桥头，放在哪一座塔前方同样取决于塔的血量，
 * 放在高血量一侧，默认放右侧」
 * </blockquote>
 *
 * <h2>★★ 为什么是三个（用户写的是「两种」）</h2>
 * 需求原文说「枚举有两种」，但下面列了 1/2/3 三条 ⇒ 开工前问过，用户裁定
 * 「**按你列的三条做，共 3 个枚举**」。★ 「前置」目前**没有任何卡使用**（用户：「前置暂不分配建筑」），
 * 但枚举保留 —— 将来分配只要在配置里写一个 {@code FRONT}。
 *
 * <h2>★ 作用域：**每张卡一条全局规则**（与法术规则同形）</h2>
 * 规则落在 {@code config.yml} 的 {@code training.building-placements}（卡 id → 本枚举），
 * <b>只对人机生效</b>（用户裁定；玩家侧召唤落点一字未改）。
 *
 * <p>「前 / 后」这条轴**不在这里** —— 它是 {@code training.building-front.<side>} 里的
 * 四方位（`card/Facing`）。理由见 {@code docs/32} §2.1：竞技场是可编辑数据，方向写死会在
 * 换场地后静默放错一侧。
 */
public enum BuildingPlacementRule {

    /**
     * ★ <b>后置</b>：放在<b>血量较低</b>那座防御塔的<b>后方 4 格</b>（朝自己家方向）。
     *
     * <p>相等时按**右侧**（用户原话「相等时默认放在右侧」）。
     */
    BACK,

    /**
     * ★ <b>中置</b>：放在<b>血量较低</b>那座防御塔的<b>正前方 4 格</b>。
     *
     * <p>相等时按**右侧**（用户原话「默认放右侧」）。
     */
    CENTER,

    /**
     * ★ <b>前置</b>：放在<b>血量较高</b>那座防御塔的<b>前方 10 格</b>，逼近桥头。
     *
     * <p>★ 注意这一档选的是**高**血量那座（后置/中置选的是**低**血量）—— 用户原话逐字如此。
     * 相等时仍按**右侧**（用户原话「默认放右侧」）。
     * <p>★ 出厂**没有任何卡**使用这一档（用户：「前置暂不分配建筑」）。
     */
    FRONT;

    /**
     * 配置里的字面量 → 枚举（<b>忽略大小写与首尾空白</b>）。
     *
     * @return 解析不出来 ⇒ {@code null}（**不抛**：抛不抛由配置层决定）
     */
    public static BuildingPlacementRule parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        for (BuildingPlacementRule rule : values()) {
            if (rule.name().equalsIgnoreCase(text)) {
                return rule;
            }
        }
        return null;
    }

    /** 配置里的合法取值（报错文案用）。 */
    public static java.util.List<String> names() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (BuildingPlacementRule rule : values()) {
            names.add(rule.name());
        }
        return java.util.List.copyOf(names);
    }

    /**
     * ★ 这一档选**血量低**的那座塔，还是**血量高**的那座。
     *
     * <p>{@link #BACK} / {@link #CENTER} ⇒ 低（用户原话「取决于哪一侧的防御塔血量更低」）；
     * {@link #FRONT} ⇒ **高**（用户原话「放在高血量一侧」）。
     *
     * <p>★ 把这条判据做成一个方法而不是散在 `switch` 里：它是**唯一**决定「选哪座塔」的地方，
     * 测试可以穷举三个枚举逐条断言（写反了会立刻红）。
     */
    public boolean picksLowerHealthTower() {
        return this != FRONT;
    }

    /**
     * ★ <b>沿「前向」的偏移格数</b>（正数 = 朝敌方 / 前方，负数 = 朝自己家 / 后方）。
     *
     * <p>{@link #BACK} ⇒ <b>−4</b>；{@link #CENTER} ⇒ <b>+4</b>；{@link #FRONT} ⇒ <b>+10</b>。
     * ★ 与用户原话逐条对应：「后方，距离防御塔4格」/「正前方4格」/「前方第10格」。
     */
    public int forwardCells() {
        return switch (this) {
            case BACK -> -4;
            case CENTER -> 4;
            case FRONT -> 10;
        };
    }
}
