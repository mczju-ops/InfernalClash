package io.mczju.infernalclash.battle;

import java.util.Objects;

/**
 * ★★★ <b>「这一击会不会落在本局玩家身上」的判据（纯函数）</b>
 * —— 2026-10-17 第十二轮新增，用户裁定 §A 第 1~3 条与「上一轮没解决」的那条清单。
 *
 * <h2>它修的是什么（用户原话，逐字）</h2>
 * 第十一轮之后用户实机仍然看到：
 * <blockquote>
 * 「敌方防御塔依旧会攻击玩家……敌方的实体，甚至我方的哨位（潜影贝）也依旧会锁定玩家」
 * </blockquote>
 * 而 {@code docs/02} §5 的原文（出处 R41 / R42 / T3）是：
 * <ul>
 *   <li>「受伤：<b>无法受到任何伤害</b>」；</li>
 *   <li>「死亡：<b>不会死亡</b>」；</li>
 *   <li>「被索敌：<b>不会成为任何召唤物的目标</b>」。</li>
 * </ul>
 * ⇒ 「本局的参战玩家不会受到伤害」是<b>文档里已经写死的规则</b>，不是本轮新加的玩法
 * （{@code docs/需求.txt} 第 20 行同句：「即玩家无法受到任何伤害，也不会死亡」）。
 *
 * <h2>★ 与 {@link PlayerAttackRule} 的分工（两条相反的规则，各自一个纯类）</h2>
 * <table border="1">
 *   <caption>两个方向的伤害规则</caption>
 *   <tr><th>类</th><th>谁受害</th><th>谁该被拦</th></tr>
 *   <tr><td>{@link PlayerAttackRule}</td><td>战场单位（召唤物）</td>
 *       <td><b>玩家</b>用卡牌以外的手段打它</td></tr>
 *   <tr><td><b>本类</b></td><td><b>玩家</b></td>
 *       <td><b>任何东西</b>打本局参战玩家（R41 的「无法受到任何伤害」）</td></tr>
 * </table>
 * ★ 两条规则方向相反，因此**不能共用一个判据**（写在一起必然有一边被写反）。
 *
 * <h2>★★ 为什么不能只靠「抗性提升 V」那层药水</h2>
 * {@code game/MatchPlayerBuffs}（第十轮）已经每秒补齐抗性提升 V，但那是<b>药水层</b>，
 * 有四个已知的漏口：
 * <ol>
 *   <li>玩家刚重生的那一 tick 效果还没补上；</li>
 *   <li>{@code /effect clear} 与效果到期之间有最长 1 秒的窗口；</li>
 *   <li>维度切换 / 传送会掉效果；</li>
 *   <li>★ <b>抗性提升按比例减伤，不是免疫</b>：抗性 V 是 −100%，但它对
 *       <b>虚空伤害</b>与 {@code /kill} 之外的极端值仍有实现细节差异，且
 *       <b>击退</b>完全不归它管（玩家会被推挤、卡位 —— 正是 {@code docs/02} §5
 *       第 278 行点名的 InfernalGuess 踩过的坑）。</li>
 * </ol>
 * ⇒ {@code docs/02} §5 第 273~275 行明写「★ <b>两层防护都要做</b>：① 药水层……
 * ② 索敌层……」，而<b>伤害拦截兜底</b>这一环原先并不存在（只有 C13 那个反方向的）。
 * 本类就是那个兜底。
 *
 * <h2>★ 判据（三分，逐格可断言）</h2>
 * <pre>
 *   受害者不是本局参战玩家   ⇒ ALLOW（别的插件、服主的动物、游戏外的玩家，一律不碰）
 *   受害者是本局参战玩家     ⇒ CANCEL（无论伤害来自谁：防御塔光束 / 召唤物 / 环境 / 摔落）
 * </pre>
 * ★ 只有两个输入 ⇒ 没有「拿不到某个字段」的模糊格，
 * 因此不存在 {@link PlayerAttackRule} 里那种「保守取消」的分支。
 *
 * <h2>★★ 铁律 10 的四问</h2>
 * <ol>
 *   <li><b>范围界定</b>：判据的输入是一个<b>布尔</b>「受害者是不是本局参战玩家」，
 *       它由调用方用 {@code MatchService.ofPlayer(uuid) != null} 得出 ——
 *       那是<b>遍历活跃对局索引</b>（最多几场），<b>不是</b> {@code getOnlinePlayers()}、
 *       <b>不是</b> {@code getNearbyEntities}。本类是纯函数，连 Bukkit 都不引；</li>
 *   <li><b>失败方向</b>：拿不到对局（不在任何对局里）⇒ {@code false} ⇒ <b>放行</b>。
 *       即「宁可少拦一次，也绝不拦到游戏外的人」（游戏外的玩家不在我们的管辖权内）；</li>
 *   <li><b>归属判定</b>：靠<b>本局名册</b>（{@code MatchService.sides} 的 UUID），
 *       <b>不靠</b>实体类型、不靠名字、不靠半径；</li>
 *   <li><b>可对账</b>：调用方（{@code PlayerDamageGuard}）每拦一次都打一行带
 *       玩家名 / 伤害来源 / matchId 的 INFO。</li>
 * </ol>
 *
 * <h2>★ 副作用（有意为之，写在报告里）</h2>
 * 本局参战玩家在<b>对局期间</b>也不会受到摔落 / 岩浆 / 窒息伤害。
 * 依据 = R41「无法受到任何伤害」＋ {@code C6}「玩家可走桥、也可直接进入岩浆河 ——
 * 玩家免疫伤害，R41」。{@code docs/02} §5 是同一条口径。
 * 对局结束后名册清空（{@code MatchService#stop} 的 {@code sides.clear()}）⇒ 立刻恢复。
 */
public final class PlayerDamageIsBlocked {

    /** 处置：拦掉 / 放行。 */
    public enum Verdict {
        /** 取消这次伤害（R41：本局玩家无法受到任何伤害）。 */
        CANCEL,
        /** 放行（受害者不是本局参战玩家 ⇒ 不归我们管）。 */
        ALLOW
    }

    private PlayerDamageIsBlocked() {
    }

    /**
     * 该不该取消这次伤害。
     *
     * @param victimIsMatchParticipant 受害者是不是<b>本局参战玩家</b>
     *        （调用方口径：{@code MatchService.ofPlayer(victim.getUniqueId()) != null}）
     */
    public static Verdict verdict(boolean victimIsMatchParticipant) {
        return victimIsMatchParticipant ? Verdict.CANCEL : Verdict.ALLOW;
    }

    /**
     * 便捷重载：只有 {@code victim} 是玩家、且他确实在某一局里时才拦。
     *
     * <p>★ 方法名与 {@link #verdict(boolean)} **刻意的不同名**：两个重载的语义
     * （一个是「受害者是不是本局参战玩家」这个**已经合取过**的事实，另一个是
     * 「是玩家」＋「在对局里」两个事实）很容易写反，而写反**编译期不报错**
     * （两个 boolean 而已）。不同名 ⇒ 调用点必须显式选一个，读代码的人也一眼看得出。
     *
     * @param victimIsPlayer {@code event.getEntity() instanceof Player}
     * @param hasActiveMatch 这位玩家在某一局里（{@code MatchService.ofPlayer(...) != null}）
     */
    public static Verdict verdictFor(boolean victimIsPlayer, boolean hasActiveMatch) {
        return verdict(victimIsPlayer && hasActiveMatch);
    }

    /** 中文说明（日志用；★ 与 {@link #verdict(boolean)} 严格一一对应）。 */
    public static String describe(Verdict verdict, String playerName, String sourceKey) {
        Objects.requireNonNull(verdict, "verdict 不能为 null");
        if (verdict == Verdict.ALLOW) {
            return "受害者不是本局参战玩家（放行；不归本插件管）";
        }
        return "本局参战玩家受到伤害，取消（R41「无法受到任何伤害」）｜玩家 "
                + (playerName == null ? "?" : playerName)
                + "｜来源 " + (sourceKey == null ? "未知" : sourceKey)
                + "｜出处 docs/02 §5 R41 + docs/需求.txt 第 20 行";
    }
}
