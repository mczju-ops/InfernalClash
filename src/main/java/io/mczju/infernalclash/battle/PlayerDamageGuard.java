package io.mczju.infernalclash.battle;

import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.ui.MenuTexts;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * ★ <b>「玩家不得攻击召唤物」</b>（`docs/02` §7、R42/T3/**C13**）。
 *
 * <h2>为什么需要它（这条规则是双向的）</h2>
 * 需求里写了两半：
 * <ol>
 *   <li>召唤物**不索敌玩家** —— 这一半由结构保证：候选只从**账本**里来，而玩家从不在账本里；</li>
 *   <li>★ **玩家也不得反过来攻击召唤物** —— 这一半必须**主动拦伤害**，否则玩家可以
 *       站在自己半场把对面刚召唤的兵一个个点掉，整局变成"点杀游戏"。</li>
 * </ol>
 *
 * <h2>★ 范围界定（只拦「玩家 → 本场召唤物」）</h2>
 * <ul>
 *   <li>受害者必须是**本场登记过、且有主人**的实体 —— 也就是**召唤物**；</li>
 *   <li>★★★ <b>2026-10-20 用户裁定（A）：「玩家一律打不动目标物（塔 / 信标）」</b> ——
 *       这一格<b>已改</b>：目标物（{@code owner.player() == null}）**不再**由本类放行，
 *       而是由下面那段 <code>registry.isObjective(victim)</code> 的判据<b>取消</b>伤害
 *       （见 {@link #onPlayerDamage} 里第二条注释）。<br>
 *       ★ <b>旧口径已作废</b>：本类注释曾写「目标物不在此列：它们的 owner 是 null，
 *       玩家必须能打它们（否则 R8/R9 的攻防就无从谈起）」—— 那是**规则未定稿时**的读法。
 *       用户裁定后，「拆塔」的合法途径只有<b>召唤物</b>（玩家只通过卡牌影响战场，C13）；
 *       <br>★ <b>为什么不能只靠 `owner.player() == null` 早退</b>：目标物按设计就是
 *       "owner 为 null + building"，而**展示实体同样是这个形状** ⇒ 早退会连带放行/误拦，
 *       所以判据必须是**明确的 PDC 标记**（{@link UnitRegistry#isObjective}，只有载体带）；</li>
 *   <li>不是我们接管的实体一律不碰（服主的动物、别的插件的怪）。</li>
 * </ul>
 * ⇒ 拿不到归属时**不取消**（宁可少拦一次，也不误拦别人的战斗）。
 *
 * <h2>★ 面向玩家的文案（2026-10-15 接线）</h2>
 * 那一句提示改读 {@code display.yml} 的 {@link #KEY_SUMMONS_CANNOT_BE_ATTACKED}
 * （经 {@link MenuTexts#component} 解析 ＋ 失败时回退到 {@link #FALLBACK_SUMMONS_CANNOT_BE_ATTACKED}）。
 * ★ 显示配置用 {@link Supplier} 注入（而不是在构造时取一份快照）：`/incl debug reload` 会**重建**
 * {@code DisplayConfig}，实时读才能跟着变 —— 这也是本项目 R44 那条「Supplier 而不是快照」的同一口径。
 */
public final class PlayerDamageGuard implements Listener {

    /** 文案键（`display.yml` 的 `messages.errors.summons-cannot-be-attacked`；见 `docs/20` §2.3）。 */
    public static final String KEY_SUMMONS_CANNOT_BE_ATTACKED =
            "errors.summons-cannot-be-attacked";

    /** 取不到配置时的回退常量（= 接线前代码里印的那句话）。 */
    public static final String FALLBACK_SUMMONS_CANNOT_BE_ATTACKED =
            "召唤物不能被玩家攻击（它们只与召唤物交战）。";

    private final UnitRegistry registry;
    private final Logger logger;

    /** 显示配置来源；{@code null} ⇒ 一律走回退常量（行为与接线前一致）。 */
    private final Supplier<DisplayConfig> displaySource;

    /** 兼容老调用点：不注入显示配置（文案走回退常量）。 */
    public PlayerDamageGuard(UnitRegistry registry, Logger logger) {
        this(registry, logger, null);
    }

    /**
     * @param displaySource 显示配置来源（通常是 {@code plugin::display}）；{@code null} 允许
     */
    public PlayerDamageGuard(UnitRegistry registry, Logger logger, Supplier<DisplayConfig> displaySource) {
        this.registry = registry;
        this.logger = logger;
        this.displaySource = displaySource;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();

        // ★★★ 2026-10-17 第十二轮新增（用户裁定：「解决所有未达到设计文档的要求的问题」
        //   ＋ 第十一轮原话「敌方防御塔依旧会攻击玩家……敌方的实体，甚至我方的哨位
        //   （潜影贝）也依旧会锁定玩家」）：
        //   **本局参战玩家受到的任何伤害一律取消**。
        //
        //   依据（文档里写死的规则，不是新玩法）：
        //     · docs/需求.txt 第 20 行：「玩家进入游戏后处于生存模式，但是获得持续的速度II、
        //       夜视以及抗性提升V，**即玩家无法受到任何伤害，也不会死亡**」；
        //     · docs/02 §5 第 268/269 行（出处 R41）：「受伤：无法受到任何伤害」/
        //       「死亡：不会死亡」；
        //     · docs/02 §5 第 273~275 行：「★ **两层防护都要做**：① 药水层：抗性提升 V（R40）
        //       ＋ **伤害拦截兜底**；② 索敌层：显式把玩家从候选目标里剔除」
        //       ⇒ 本条就是那个**伤害拦截兜底**（第十轮只做了药水层，兜底一直缺着）。
        //
        //   ★ 范围（铁律 10 四问）：
        //     范围 = 受害者的 UUID 在**本局名册**里（MatchService.ofPlayer —— 遍历活跃对局索引，
        //     最多几场；**不是** getOnlinePlayers、**不是** getNearbyEntities）；
        //     失败方向 = 不在任何对局里 ⇒ 放行（宁可少拦一次，也绝不拦到游戏外的人）；
        //     归属 = 靠名册 UUID，不靠类型 / 名字 / 半径；
        //     对账 = 每拦一次打一行带玩家名 / 伤害来源 / matchId 的 INFO（同一次伤害只一行）。
        boolean victimIsPlayer = victim instanceof Player;
        MatchService victimMatch = victimIsPlayer ? MatchService.ofPlayer(victim.getUniqueId()) : null;
        if (PlayerDamageIsBlocked.verdictFor(victimIsPlayer, victimMatch != null)
                == PlayerDamageIsBlocked.Verdict.CANCEL) {
            event.setCancelled(true);
            String source = damageKeyOf(event);
            logger.info("[IC] 已拦截对局内玩家的伤害：" + victim.getName()
                    + "（来源 " + source + "｜matchId=" + victimMatch.matchId() + "）"
                    + "｜" + PlayerDamageIsBlocked.describe(PlayerDamageIsBlocked.Verdict.CANCEL,
                            victim.getName(), source));
            return;
        }

        // ★★★ 2026-10-20 用户裁定（A）：「**玩家一律打不动目标物（塔 / 信标）**」。
        //   修之前的真实行为（与本裁定相反）：目标物的归属玩家按设计就是 `null`
        //   ⇒ 下面那句 `owner.player() == null` 会**早退放行**（旧注释甚至写着「目标物允许被打」）
        //   ⇒ 玩家近战能拆塔；本轮把载体改成可碰撞之后，玩家的箭也能打到塔了。
        //   ⇒ 现在明确拦在这里：**造成者是玩家 ⇒ 取消**。
        //   ★ 用 `getCausingEntity()` 而不是 `getDamager()`：弓箭/弩弹/三叉戟/矛的"造成者"是**射手**，
        //     直接实体才是那支箭（这正是 C13「弓从来没被拦过」那条坑的同一个教训）。
        //   ★ 召唤物打塔**不受影响**（造成者是召唤物）—— 那是拆塔的唯一合法途径。
        //
        //   ★★★ 2026-10-21 修正（用户报的真人对局缺陷：「**法术打防御塔没有伤害**（火球、冰晶坠）」）：
        //   上一版把「造成者是玩家」**一律**取消，于是**卡牌法术**也被拦了 —— 现场日志铁证
        //   `已拦截玩家对目标物的伤害：_YamatoMaya_ → 蓝方防御塔（来源 **magic**）`。
        //   而 C13 的口径是「玩家**只能通过卡牌影响战场**」⇒ 卡牌法术**必须**能打建筑。
        //   ⇒ 判据改用**同一个** {@link PlayerAttackRule#isCardSpellDamage}（「魔法 + 直接实体是玩家」），
        //     与「玩家打召唤物」那一格完全同源（不写第二套判断）：
        //       近战 / 弓 / 弩 / 三叉戟 / 矛 ⇒ CANCEL（玩家自己的手段）
        //       卡牌法术（MAGIC）          ⇒ ALLOW（放行 ⇒ 走到下面的 owner 早退，不会被取消）
        Entity pvpCauser = event.getDamageSource() == null ? null
                : event.getDamageSource().getCausingEntity();
        if (registry.isObjective(victim) && pvpCauser instanceof Player attacker) {
            Entity spellDirect = event.getDamageSource() == null ? null
                    : event.getDamageSource().getDirectEntity();
            String spellKey = damageKeyOf(event);
            if (PlayerAttackRule.isCardSpellDamage(true, spellDirect instanceof Player, spellKey)) {
                // 卡牌法术打建筑：**放行**（C13 允许的那条路）。留一行 INFO 便于实机核对
                // 「法术真的造成伤害了」—— 用户这次报的就是"没伤害"。
                logger.info("[IC] 放行卡牌法术对目标物的伤害：" + attacker.getName() + " → "
                        + victim.getName() + "（来源 " + spellKey
                        + "｜C13：卡牌是玩家影响战场的唯一途径，建筑可以被法术打）");
            } else {
                event.setCancelled(true);
                logger.info("[IC] 已拦截玩家对目标物的伤害：" + attacker.getName() + " → " + victim.getName()
                        + "（来源 " + spellKey + "）｜★ 2026-10-20 用户裁定 A：玩家打不动塔/信标，"
                        + "只能靠召唤物拆（玩家只通过卡牌影响战场，C13）");
                return;
            }
        }

        OwnershipTable.Owner owner = registry.table().owner(victim.getUniqueId());
        if (owner == null || owner.player() == null) {
            return;   // 不是召唤物（别人家的实体不归我们管；目标物已由上面那条拦/放行）
        }
        // ★★★ 2026-10-17 第十轮（缺陷 P1-a）：判据从「damager 是不是 Player」换成
        //   纯函数 PlayerAttackRule#verdict —— 三个输入：
        //     ① 造成实体是不是玩家（★ 弓箭的造成者是射手 ⇒ 这才真正拦住了 C13 说的「弓」，
        //        而旧写法看的是 getDamager()，那里是一支箭 ⇒ 弓从来没被拦过）；
        //     ② 直接实体是不是玩家；
        //     ③ 伤害类型的键。
        //   ⇒ **卡牌法术**（MAGIC + 直接实体 = 施放者）被放行，其余玩家伤害一律取消。
        //   旧写法把法术伤害也取消掉了 ⇒ 现场表现为「法术命中 3 个目标却一点伤害都没有」。
        Entity causing = event.getDamageSource() == null ? null
                : event.getDamageSource().getCausingEntity();
        Entity direct = event.getDamageSource() == null ? null
                : event.getDamageSource().getDirectEntity();
        String damageTypeKey = event.getDamageSource() == null
                || event.getDamageSource().getDamageType() == null
                ? null : event.getDamageSource().getDamageType().getKey().getKey();
        boolean causedByPlayer = causing instanceof Player;
        PlayerAttackRule.Verdict verdict = PlayerAttackRule.verdict(causedByPlayer,
                direct instanceof Player, damageTypeKey);
        if (verdict == PlayerAttackRule.Verdict.ALLOW) {
            if (PlayerAttackRule.isCardSpellDamage(causedByPlayer, direct instanceof Player,
                    damageTypeKey)) {
                // ★ 卡牌法术打到战场单位：这是 C13 允许的路（玩家只能通过卡牌影响战场）。
                //   只打一次 info，便于实机核对「法术真的造成伤害了」。
                logger.info("[IC] 放行卡牌法术伤害：" + (causing == null ? "?" : causing.getName())
                        + " → " + victim.getType() + "（matchId=" + owner.matchId()
                        + "，伤害来源 " + damageTypeKey + "｜C13：卡牌是玩家影响战场的唯一途径）");
            }
            return;
        }
        event.setCancelled(true);
        Player player = (Player) causing;
        player.sendMessage(MenuTexts.component(displaySource == null ? null : displaySource.get(),
                KEY_SUMMONS_CANNOT_BE_ATTACKED, FALLBACK_SUMMONS_CANNOT_BE_ATTACKED,
                NamedTextColor.RED, logger::warning));
        logger.info("[IC] 已拦截玩家攻击召唤物：" + player.getName() + " → "
                + victim.getType() + "（matchId=" + owner.matchId() + "，" + owner.side()
                + "｜来源 " + damageTypeKey + "）");
    }

    /** 伤害来源的键（拿不到 ⇒ {@code null}）；日志用。 */
    private static String damageKeyOf(EntityDamageByEntityEvent event) {
        if (event.getDamageSource() == null || event.getDamageSource().getDamageType() == null) {
            return null;
        }
        return event.getDamageSource().getDamageType().getKey().getKey();
    }
}
