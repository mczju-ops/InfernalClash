package io.mczju.infernalclash.policy;

import java.util.Map;

/**
 * ★★ <b>远程攻击的三条判据（纯函数）</b> —— 2026-10-17 第十三轮批次 3 收尾新增。
 *
 * <h2>它回答的三个问题（都是用户当场裁定的）</h2>
 * <ol>
 *   <li><b>「冰术师不许射箭」</b> —— 用户原话：「<b>不需要摘掉射箭 goal，只需要拦截射箭事件就行。
 *       需要它射箭时的走位</b>」⇒ 拦的是 {@code EntityShootBowEvent}，<b>不</b>摘 goal
 *       （摘了它就不走位了，而那正是用户要保留的东西）；</li>
 *   <li><b>「女巫的溅射对敌人有效、对友方和玩家无效」</b> —— 用户原话逐字；
 *       ⇒ 判据是「<b>这一瓶有没有打到敌人</b>」，没有就把整瓶作废
 *       （否则女巫会把药水浪费在队友身上）；</li>
 *   <li><b>「凋零 / 中毒这类自我削弱不需要额外处理」</b> —— 用户原话：
 *       「<b>像凋零、中毒这类状态效果队友不会获得，不需要做额外的判断</b>，只需要考虑
 *       对友方和玩家效果无效的，比如女巫的溅射药水」⇒ ★ <b>这一条是"不做"的决定</b>，
 *       本类把它写成一条<b>可断言的注释式常量</b>，免得后人"顺手补上"一个不存在的需求。
 * </ol>
 *
 * <h2>★ 为什么必须是纯函数</h2>
 * 三条判据都要在**事件回调**里逐次调用（射箭、溅射、每 tick），而事件回调里抛异常
 * 会带走整个监听器链 ⇒ 判据本身必须能被离线穷举（{@code RangedAttackRuleTest}），
 * 执行层只做三步：读 PDC → 查卡池 → 调这里。
 *
 * <h2>铁律 10</h2>
 * 本类不 import 任何 {@code org.bukkit.*}：它只认「归属 / 卡数据 / 强度」三组入参。
 */
public final class RangedAttackRule {

    /**
     * ★★ <b>刻意的"不做"</b>：凋零 / 中毒这类**负面状态**不需要按阵营过滤。
     *
     * <p>用户原话：「像凋零、中毒这类状态效果队友不会获得，不需要做额外的判断」——
     * 也就是说，那些卡的文案（「召唤时凋零 I」）本来就是**打自己**的自我削弱，
     * 不存在"误伤队友"的问题。
     * <p>★ 把它写成常量而不是只写注释：将来有人想加"负面效果也要过滤"时，
     * 会先看到这里、再看到 {@code RangedAttackRuleTest} 里那条断言，
     * 从而知道那是**被明确否掉**的需求（而不是"当年忘了做"）。
     */
    public static final boolean STATUS_EFFECTS_NEED_FACTION_FILTER = false;

    /** 溅射的处置（{@link #splashDecision} 的返回值）。 */
    public enum SplashDecision {
        /** 照常结算（本插件不做任何干预）。 */
        ALLOW,
        /** ★ 整瓶作废（`setCancelled(true)`）：**一个敌人都没打到**。 */
        CANCEL_NO_ENEMY,
        /** ★ 只作用于敌人：把友方与玩家的 intensity 清零。 */
        ENEMIES_ONLY
    }

    private RangedAttackRule() {
    }

    /**
     * ★ <b>这个单位的远程攻击是不是被拦掉的</b>（冰术师那一格）。
     *
     * <p>三步入参，每一步的失败方向都是 {@code false}（= 照常射）：
     * <ol>
     *   <li>{@code ownedByThisMatch} —— 这个实体是不是<b>本场账本</b>里的单位。
     *       ★ 这一条是铁律 10 的落地：<b>不是我们的实体，我们一根手指都不碰</b>
     *       （别的插件的怪、服主的骷髅照常射箭）；</li>
     *   <li>{@code cardId} —— 它来自哪张卡（PDC 里读来的，拿不到 ⇒ 不拦）；</li>
     *   <li>{@code blockRanged} —— 那张卡的 {@code summon.block-ranged}（缺省 {@code false}）。</li>
     * </ol>
     *
     * @return {@code true} = 取消这次射击
     */
    public static boolean blocksRangedAttack(boolean ownedByThisMatch, String cardId,
                                             Map<String, Boolean> byCard) {
        if (!ownedByThisMatch || cardId == null || cardId.isBlank() || byCard == null) {
            return false;
        }
        return Boolean.TRUE.equals(byCard.get(cardId));
    }

    /**
     * ★★ <b>一瓶溅射药水该怎么处置</b>（女巫那一格）。
     *
     * <p>用户原话：「<b>每次投掷会对敌人造成影响，而对友方和玩家无效</b>」——
     * 拆成两条可执行的规则：
     * <ol>
     *   <li>范围内<b>一个敌人都没有</b> ⇒ {@link SplashDecision#CANCEL_NO_ENEMY}
     *       （整瓶作废）。★ 这一条是"不浪费"：女巫站在自家前排时，
     *       原版会往最近的生物身上扔 —— 那多半是队友；</li>
     *   <li>有敌人 ⇒ {@link SplashDecision#ENEMIES_ONLY}
     *       （执行层把友方与玩家的 intensity 清零）。</li>
     * </ol>
     *
     * <h2>★ 为什么用「强度」而不是「从受影响列表里删掉」</h2>
     * {@code PotionSplashEvent}（`javap` 实测 26.2）只有
     * {@code getAffectedEntities()} / {@code getIntensity(LivingEntity)} /
     * {@code setIntensity(LivingEntity, double)} / {@code setCancelled(boolean)} ——
     * <b>没有</b>"把某个实体移出列表"的方法。而 {@code setIntensity(e, 0.0)} 的语义正是
     * 「这一位不受影响」（原版按强度算时长，0 ⇒ 不施加效果）⇒ 这是**唯一**能表达
     * 「对友方无效」的官方口径，不是绕过它。
     *
     * @param affectedCount 这一瓶会影响到的实体数（0 ⇒ 不接管，交给原版）
     * @param enemyCount    其中<b>敌方</b>的数量
     */
    public static SplashDecision splashDecision(int affectedCount, int enemyCount) {
        if (affectedCount <= 0) {
            // 空的一瓶：与我们无关（原版自己会处理成"什么都没发生"）
            return SplashDecision.ALLOW;
        }
        if (enemyCount <= 0) {
            return SplashDecision.CANCEL_NO_ENEMY;
        }
        return SplashDecision.ENEMIES_ONLY;
    }

    /** 一行的说明（日志与测试失败信息里用）。 */
    public static String describe(SplashDecision decision) {
        return switch (decision) {
            case ALLOW -> "照常结算（这一瓶没有可接管的受影响者）";
            case CANCEL_NO_ENEMY -> "整瓶作废：范围内一个敌人都没有（绝不浪费在队友身上）";
            case ENEMIES_ONLY -> "只作用于敌人：把友方与玩家的强度清零";
        };
    }
}
