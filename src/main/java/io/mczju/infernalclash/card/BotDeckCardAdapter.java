package io.mczju.infernalclash.card;

import io.mczju.infernalclash.config.TrainingConfig;

/**
 * ★ <b>{@code card/Card} 在 {@link TrainingConfig.DeckCard} 适配器后面的实现</b>
 * —— 让配置层不必 import 卡牌层（见那个接口的 javadoc）。
 *
 * <h2>★ 为什么单独一个类，而不是写在 {@code InfernalClashPlugin} 里的静态方法</h2>
 * 它有两个调用点，而其中一个是<b>测试</b>：
 * <ol>
 *   <li>装配期的交叉校验（{@code InfernalClashPlugin#requireBotDeckCardsExist}）；</li>
 *   <li>对局里建手牌（{@code game/InfernalClashGame#trainingBotHand}）；</li>
 *   <li>★ 离线测试（{@code config/TrainingDeckCrossCheckTest} /
 *       {@code ai/TrainingBotDriverTest}）——它必须能<b>不起服务端</b>就把真卡池摊成手牌。</li>
 * </ol>
 * 放在 {@code InfernalClashPlugin} 里的话，第 3 点会去**加载那个类**
 * ⇒ 触发 {@code JavaPlugin} 的实例初始化逻辑（需要 Bukkit 服务端）⇒ 离线测试直接炸。
 * ⇒ 所以它放在这里：**只依赖 {@link Card} 与 {@link TrainingConfig.DeckCard}**，纯值语义、无状态。
 *
 * <p>★ 唯一的实现就是它 —— 两个装配点必须用**同一个**适配器，
 * 否则「校验时取的判据」与「运行时取的判据」会分叉（本项目的「第二处白名单」教训）。
 */
public final class BotDeckCardAdapter implements TrainingConfig.DeckCard {

    /** 无状态 ⇒ 一个共享实例即可（无需每次 new）。 */
    public static final BotDeckCardAdapter INSTANCE = new BotDeckCardAdapter();

    private BotDeckCardAdapter() {
    }

    @Override
    public boolean summonOf(Object card) {
        return card instanceof Card c && c.isSummon();
    }

    @Override
    public int costOf(Object card) {
        return card instanceof Card c ? c.cost() : 0;
    }

    @Override
    public String describeOf(Object card) {
        return card instanceof Card c ? c.describe() : String.valueOf(card);
    }

    @Override
    public boolean spellOf(Object card) {
        // ★ 覆写默认实现：直接问卡自己（Card 的不变量保证 isSpell == !isSummon，
        //   但这里写成显式判据，读起来与 Card 的字段一一对应）
        return card instanceof Card c && !c.isSummon();
    }

    /**
     * ★ 这张卡的 {@code spell.targeting} 是不是 {@code ALLY_TARGET}
     * （2026-10-21 新增：`training.spell-rules` 的装载期兼容性判据要用它）。
     *
     * <p>★ 配置层不认识 {@link Spell.Targeting} ⇒ 适配器把它翻译成**一个布尔事实**。
     */
    @Override
    public boolean needsSingleAllyTarget(Object card) {
        return card instanceof Card c && c.spell() != null
                && c.spell().targeting() == Spell.Targeting.ALLY_TARGET;
    }

    /** 报错措辞用：{@code targeting} 的枚举名（读不到 ⇒ 空串）。 */
    @Override
    public String targetingOf(Object card) {
        return card instanceof Card c && c.spell() != null && c.spell().targeting() != null
                ? c.spell().targeting().name()
                : "";
    }

    /**
     * ★ <b>这张卡是不是建筑</b>（2026-10-21 新增）—— 判据 = {@code Card.Summon#building()}。
     *
     * <p>用途：`training.building-placements` 的装载期交叉校验（给非建筑配塔附近落点
     * 是一条永远不生效的配置）。★ 法术卡没有 summon 块 ⇒ 恒 {@code false}。
     */
    @Override
    public boolean buildingOf(Object card) {
        return card instanceof Card c && c.summon() != null && c.summon().building();
    }
}
