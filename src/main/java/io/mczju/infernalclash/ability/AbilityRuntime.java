package io.mczju.infernalclash.ability;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.card.Spell;
import io.mczju.infernalclash.match.LifecycleTasks;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.spell.AppliedEffects;
import io.mczju.infernalclash.spell.EffectApplier;
import io.mczju.infernalclash.spell.FxRing;
import io.mczju.infernalclash.spell.LightningPlan;
import io.mczju.infernalclash.spell.LineFx;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ★★ <b>把卡牌里声明式的 {@code abilities} 真的跑起来</b>（消费者侧）。
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li><b>本类只做「触发编排」</b>：「什么时候触发」「效果该打到谁身上」「周期任务起几个」；</li>
 *   <li><b>「一个效果怎么作用到一个目标上」一律交给
 *       {@link EffectApplier#apply(Spell.Effect, EffectApplier.Context, LivingEntity, Location, Runnable)}</b>
 *       —— <b>绝不重造</b>（{@code docs/07} §6.4 的 15 类效果只有一份实现）；</li>
 *   <li><b>本类不查世界</b>：没有任何 {@code getNearbyEntities / getEntities / getLivingEntities /
 *       getOnlinePlayers / getWorlds} 调用（{@code BytecodeScopeGuardTest} 的 8 个记号一个都不出现；
 *       铁律 10）。唯一「按 id 找回来」的调用是 {@link Bukkit#getEntity(UUID)} 与
 *       {@link Bukkit#getPlayer(UUID)}，都是 O(1) 点查，不是"枚举整个世界再过滤"。</li>
 * </ul>
 *
 * <h2>触发口径（★ 与 {@code docs/03} §4.3 / {@code docs/07} §6.4 对齐）</h2>
 * <table border="1">
 *   <caption>逐触发器</caption>
 *   <tr><th>触发器</th><th>怎么触发</th><th>效果打到谁</th></tr>
 *   <tr><td>{@code ON_SUMMON}</td><td>召唤物刚生成 ⇒ 立刻一次（{@link #onSummonSpawn}）</td>
 *       <td>这个召唤物<b>自己</b>（例：煤矿守卫的「抗性提升 V」）</td></tr>
 *   <tr><td>{@code PERIODIC}</td><td>起周期任务（{@link #onSummonSpawn}）</td>
 *       <td>这个召唤物<b>自己</b></td></tr>
 *   <tr><td>{@code ON_HURT}</td><td>接线方在监听器里调 {@link #onHurt}</td>
 *       <td>受伤的那个实体<b>自己</b></td></tr>
 *   <tr><td>{@code ON_ATTACK}</td><td>接线方在监听器里调 {@link #onHurt}
 *       （同一个入口，见该方法 javadoc）</td><td>攻击的那个实体<b>自己</b></td></tr>
 *   <tr><td>{@code ON_DEATH}</td><td>接线方在监听器里调 {@link #onDeath}</td>
 *       <td>死亡的那个实体<b>自己</b>；{@code origin} = <b>死亡位置</b></td></tr>
 * </table>
 *
 * <h2>★★ {@code GRANT_XP} 的特例：目标是<b>召唤者</b>，不是召唤物自己</h2>
 * {@code docs/07} §6.4 的效果表原文写的是「★ <b>给召唤者加经验</b> | {@code GRANT_XP} |
 * {@code amount}（「悦灵」：每 5s 给召唤者 1 级）」—— 即「悦灵」每 5 秒给的是
 * <b>召唤它的那名玩家</b>的经验，不是悦灵自己的。
 * <ul>
 *   <li><b>出厂证据</b>：{@code cards/allay.yml} 头部候选块
 *       {@code · trigger: PERIODIC, cooldown-ticks: 100, effects: [{type: GRANT_XP, amount: 1}]
 *       # 每 5s 给召唤者 1 级}；同一张卡的展示文案也是
 *       「每 <b>5</b> 秒为<b>召唤者</b>提供 <b>1</b> 级经验」；</li>
 *   <li><b>实现</b>：{@link #effectTargetFor} 在施加前判断 —— {@code GRANT_XP} ⇒ 返回召唤者
 *       （用 {@link Bukkit#getPlayer(UUID)} 找；<b>不在线就跳过</b>，返回 {@code null}），
 *       <b>其它效果一律返回召唤物自己</b>。</li>
 * </ul>
 *
 * <h2>★★ 同一个能力不重复触发</h2>
 * <ul>
 *   <li>{@code ON_SUMMON}：由 {@code SummonSpawner} 对**每一个**新生成的实体各调一次
 *       {@link #onSummonSpawn} ⇒ 每次调用只跑一次，不自己再登记；</li>
 *   <li>{@code PERIODIC}：<b>每个（召唤物, 能力名）只保留一个任务</b> ——
 *       用 {@link Schedule}（{@link LinkedHashMap} 记账）保证；重入时<b>先取消旧的</b>，
 *       所以「同一个召唤物被召唤两次」不会变成两个周期任务在跑；</li>
 *   <li>{@link #stop()} 取消全部并清空（<b>幂等</b>：`stop()` 之后再 `stop()` 取消 0 个）。</li>
 * </ul>
 *
 * <h2>★ 周期任务必须挂对局生命周期（{@code docs/07} §6.4 的硬要求）</h2>
 * 一律走 {@link MatchService#tasks()} 的
 * {@link LifecycleTasks#runTimer(Plugin, Runnable, long, long)}：
 * <b>不挂生命周期 ⇒ 对局结束后任务还在跑 ⇒ 下一局凭空冒出召唤物</b>。
 * 本类自己**不**调度任务，只把 handle 交给登记表（并额外记一份，好在 {@link #stop()} 里取消）。
 *
 * <h2>★ 失败方向：告警并继续</h2>
 * 任何一步失败（实体已死 / 召唤者不在线 / 卡里的效果参数非法 / 效果抛异常）都
 * <b>绝不打断整场</b>：
 * <ul>
 *   <li>单条 {@code effects} 失败 ⇒ 告警，<b>继续下一条</b>；</li>
 *   <li>整条能力失败（含 {@link IllegalArgumentException} 这类"卡牌数据错误"）⇒ 告警，
 *       <b>继续下一条能力</b>。⚠️ 这与 {@link EffectApplier} 的口径不同：那里数据错误照常抛出，
 *       <b>因为那一次施放是玩家主动打出的牌</b>（要让人看见）；而能力是<b>对局中途的自动触发</b>，
 *       让它把整场炸掉是更糟的失败方向。代价是「卡写错了」只出现在告警里 ⇒
 *       上线前应先用 {@link AbilityCatalog#validate} 在装载期拦下来；</li>
 *   <li>同一（实体, 能力）的告警<b>只打一次</b>，避免周期能力每 tick 刷屏把真正的告警淹掉。</li>
 * </ul>
 *
 * <h2>★ 周期缺省值：文档没给 ⇒ 不猜（铁律 8 / 铁律 11）；处置在<b>运行期</b>，不在装载期</h2>
 * {@code docs/03} §4.3 把 {@code cooldown-ticks} 标成【待填】，<b>没有给任何缺省值</b>；
 * {@link Card.Ability} 的 javadoc 也只说「{@code null} = 不限次」——
 * 而 {@code PERIODIC} 的周期是<b>必需</b>的（没周期就没有"周期"）。
 * ⇒ 本类<b>不替用户编一个默认周期</b>。
 *
 * <p>★★ <b>但处置分两段，不要把两段混成一句</b>：
 * <ol>
 *   <li><b>装载期<b>不</b>拒绝</b>：{@code CardCatalog.parseAbilities} 把
 *       {@code cooldown-ticks} 做成<b>可选</b>（{@code isSet ? requireInt : null}）
 *       ⇒ 缺这个键的卡<b>装得进去、插件照常启动</b>；</li>
 *   <li><b>真正处置在运行期</b>：召唤时发现该能力的 {@code cooldown-ticks} 缺失或非正
 *       ⇒ {@link #warnOnce} <b>告警一次</b>（不是每次触发都刷）并<b>跳过这一个能力</b>
 *       —— <b>别的能力照跑、整局照打、插件照启动</b>。
 *       判定收在一处：{@link #periodicCooldownSufficient}。</li>
 * </ol>
 *
 * <p>★ <b>为什么不在装载期直接拒绝</b>：卡是<b>数据</b>，缺一个键<b>不是玩家的错</b>
 * ⇒ 不该连累整局、更不该连累整个插件启动；让<b>那一个能力</b>不生效，
 * 并把「缺了什么」写进告警，失败方向最小。
 *
 * <p>★ <b>原写「拒绝启动」系失真，2026-10-15 更正</b>（本轮只校准措辞，<b>不改行为</b>）：
 * 同源的 {@link AbilityCatalog} 的 {@code periodic} 注册项描述此前也这么写，两处一并更正。
 *
 * <h2>可离线测试</h2>
 * 「触发编排」里能离线断言的都抽成了纯逻辑 / 不碰服务端的小类：
 * {@link #effectTargetFor}、{@link #castAbilityTarget}、{@link Schedule}。
 * 真正「起任务并作用到活实体上」的部分<b>只能实机验证</b>（见测试类的说明）。
 */
public final class AbilityRuntime {

    /** 插件实例（取 logger 与调度器）。 */
    private final Plugin plugin;

    /** 卡牌目录（排障时可反查卡 id ⇒ 目前用于日志与将来的装载期校验）。 */
    private final CardCatalog catalog;

    /** 效果施加入口占位（★ 实际施加走 {@link EffectApplier} 的静态入口；本字段为将来加实例态保留）。 */
    private final EffectApplier applier;

    /** ★ 周期任务记账（保序）。 */
    private final Schedule schedule = new Schedule();

    /** 「同一（实体, 能力）只告警一次」的记录（防止周期能力刷屏）。 */
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

    /**
     * @param plugin  插件实例（取 logger；调度时交给 {@link LifecycleTasks}）
     * @param catalog 卡牌目录（可 {@code null} —— 本类不依赖它做判定）
     * @param applier 效果施加入口占位（可 {@code null}；实际施加走 {@link EffectApplier} 的静态方法）
     */
    public AbilityRuntime(Plugin plugin, CardCatalog catalog, EffectApplier applier) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.applier = applier;
    }

    /** 卡牌目录（只读，排障用）。 */
    public CardCatalog catalog() {
        return catalog;
    }

    /** 效果施加入口占位（只读）。 */
    public EffectApplier applier() {
        return applier;
    }

    // ================================================================== 触发入口

    /**
     * <b>一个召唤物被生成时</b>调用（接线方在 {@code SummonSpawner} 里接）——
     * {@code SummonSpawner} 对<b>每一个</b>新生成的实体各调一次（不是每张卡一次）。
     *
     * <p>本方法负责两类触发器：
     * <ul>
     *   <li>{@code ON_SUMMON} ⇒ <b>立刻</b>把 {@code effects} 施加到<b>这个召唤物自己</b>身上
     *       （唯一的例外是 {@code GRANT_XP}，见类 javadoc）；</li>
     *   <li>{@code PERIODIC} ⇒ 用 {@code match.tasks().runTimer(plugin, action,
     *       cooldownTicks, cooldownTicks)} 起周期任务（{@code cooldown-ticks} 就是周期），
     *       每次触发对<b>这个召唤物自己</b>施加 effects。★ {@code cooldown-ticks}
     *       <b>缺失或非正 ⇒ 不启动这一个能力</b>：<b>装载期不拒绝</b>（该键在
     *       {@code CardCatalog} 里是<b>可选</b>的），运行期在这里<b>告警一次并跳过</b>
     *       —— 判定见 {@link #periodicCooldownSufficient}，理由见类 javadoc
     *       「周期缺省值」一节。<b>★ 原写「拒绝启动」系失真，2026-10-15 更正。</b></li>
     * </ul>
     * 其余触发器（{@code ON_HURT} / {@code ON_ATTACK} / {@code ON_DEATH}）在本方法里
     * <b>什么都不做</b> —— 它们由监听器在事件发生时调 {@link #onHurt} / {@link #onDeath}。
     *
     * @param summon  刚生成的那个召唤物（{@code null} / 已失效 ⇒ 告警后返回）
     * @param card    它来自哪张卡（召唤卡；{@code spell() == null} 也没有 abilities ⇒ 直接返回）
     * @param matchId 对局 id（找 {@link MatchService#tasks()} 与效果上下文都用它）
     * @param side    召唤物所属阵营（可为 {@code null}；效果上下文会再用 {@code owner} 兜一次）
     * @param owner   召唤者玩家 UUID（{@code GRANT_XP} 要它；{@code null} ⇒ 该效果跳过）
     * @param match   本场对局运行时（{@code null} / 已收尾 ⇒ 只做一次性触发的部分）
     */
    public void onSummonSpawn(Entity summon, Card card, String matchId, Side side,
                              UUID owner, MatchService match) {
        if (summon == null || !summon.isValid()) {
            warnOnce("spawn-invalid", "[IC] 能力触发跳过：召唤物为 null 或已失效"
                    + "（卡 " + cardId(card) + "，matchId=" + matchId + "）");
            return;
        }
        List<Card.Ability> abilities = abilitiesOf(card);
        if (abilities.isEmpty()) {
            return;
        }
        UUID summonId = summon.getUniqueId();
        LivingEntity living = summon instanceof LivingEntity entity ? entity : null;
        // ★ 报备：ON_HURT / ON_ATTACK / ON_DEATH 在事件回调里只拿得到实体，
        //   能力清单必须在**召唤的这一刻**记下来（PDC 里没有卡 id，反查不了）。
        remember(summon, card, matchId, abilities);

        for (int index = 0; index < abilities.size(); index++) {
            Card.Ability ability = abilities.get(index);
            try {
                if (!castAbilityTarget(cardId(card), index, ability, Card.Trigger.ON_SUMMON)) {
                    continue;
                }
                if (living == null) {
                    warnOnce("spawn-notliving:" + summonId,
                            "[IC] 能力 " + ability.id() + " 跳过：召唤物 " + summon.getType()
                                    + " 不是生物（效果只能作用在生物上）");
                    continue;
                }
                // ★ ON_SUMMON：一次性，不登记任何东西 ⇒ 不可能重复触发
                runAbility(ability, cardId(card), matchId, side, owner, living,
                        living.getLocation());
            } catch (Throwable t) {
                warnOnce("spawn:" + summonId + ":" + abilityName(ability),
                        "[IC] 能力 " + abilityName(ability) + " 在召唤时失败（卡 " + cardId(card)
                                + "，实体 " + summonId + "）：" + t);
            }
        }

        // ---- PERIODIC：每个（召唤物, 能力名）只起一个任务
        for (int index = 0; index < abilities.size(); index++) {
            Card.Ability ability = abilities.get(index);
            try {
                if (!castAbilityTarget(cardId(card), index, ability, Card.Trigger.PERIODIC)) {
                    continue;
                }
                if (living == null) {
                    warnOnce("periodic-notliving:" + summonId,
                            "[IC] 周期能力 " + ability.id() + " 跳过：召唤物 " + summon.getType()
                                    + " 不是生物");
                    continue;
                }
                startPeriodic(ability, cardId(card), summonId, living, matchId, side, owner, match);
            } catch (Throwable t) {
                warnOnce("periodic:" + summonId + ":" + abilityName(ability),
                        "[IC] 周期能力 " + abilityName(ability) + " 启动失败（卡 " + cardId(card)
                                + "，实体 " + summonId + "）：" + t);
            }
        }
    }

    /**
     * <b>一个本场实体受伤时</b>调用（接线方在伤害监听器里接）
     * —— 施加 {@code ON_HURT} 的 effects 到<b>该实体自己</b>。
     *
     * <p>传入的实体<b>不属于本场</b>（不在本运行时的登记里）⇒ 直接跳过
     * —— 本类只对「本场召唤出来的、由 {@link #onSummonSpawn} 报备过」的单位生效。
     *
     * <p>★ 攻击命中请用 {@link #onAttack}（触发时机不同，混用会让 {@code ON_ATTACK}
     * 永远打不出来）。
     *
     * @param victim  受伤的那个实体
     * @param matchId 对局 id
     * @param match   本场对局运行时（可为 {@code null}；阵营 / 召唤者会退回登记里的快照）
     */
    public void onHurt(Entity victim, String matchId, MatchService match) {
        if (victim == null) {
            warnOnce("hurt-null", "[IC] 受伤能力跳过：实体为 null（matchId=" + matchId + "）");
            return;
        }
        dispatch(victim, matchId, match, Card.Trigger.ON_HURT, "受伤", null);
    }

    /**
     * ★ <b>一个本场召唤物攻击命中时</b>调用（接线方在 {@code EntityDamageByEntityEvent}
     * 的 <b>damager</b> 一侧接）—— 施加 {@code ON_ATTACK} 的 effects 到<b>该实体自己</b>。
     *
     * <p>【AI 补充，可否决】任务书给的固定签名里只有 {@code onHurt} / {@code onDeath}
     * 两个事件入口，但 {@link Card.Trigger} 有 5 个取值，其中
     * {@code ON_ATTACK}（「海王」命中眩晕 1 秒）必须有地方触发。
     * ⇒ 本方法就是把「同一个实体自己 + 同一套编排」的第三个入口显式列出来，
     * 免得它被悄悄塞进 {@link #onHurt}（那会让「受伤」与「命中」两种时机混在一起，
     * 读代码的人无法判断哪条能力在什么时机跑）。<b>不传 damager 就不会触发</b>。
     *
     * @param attacker 发起攻击的那个本场召唤物
     * @param matchId  对局 id
     * @param match    本场对局运行时（可为 {@code null}）
     */
    public void onAttack(Entity attacker, String matchId, MatchService match) {
        if (attacker == null) {
            warnOnce("attack-null", "[IC] 攻击能力跳过：实体为 null（matchId=" + matchId + "）");
            return;
        }
        dispatch(attacker, matchId, match, Card.Trigger.ON_ATTACK, "攻击命中", null);
    }

    /**
     * <b>一个本场实体死亡时</b>调用（接线方在死亡监听器里接）——
     * 施加 {@code ON_DEATH} 的 effects 到<b>该实体自己</b>，
     * ★ {@code origin} 用<b>死亡位置</b>（亡语常常是"在这里炸一下 / 召唤点东西"）。
     *
     * <p>⚠️ 在死亡事件里实体<b>可能已经被移除</b>（{@code isValid()} 为 false）⇒
     * 本方法<b>不</b>用 {@code isValid()} 当门槛：只要 {@code getLocation()} 还拿得到
     * （纸面位置在死亡时仍可读），就把 effect 交出去；真拿不到位置就退回
     * 「没有 origin」（{@link EffectApplier} 会按自己的口径处理并告警）。
     */
    public void onDeath(Entity victim, String matchId, MatchService match) {
        if (victim == null) {
            warnOnce("death-null", "[IC] 死亡能力跳过：实体为 null（matchId=" + matchId + "）");
            return;
        }
        if (!(victim instanceof LivingEntity living)) {
            warnOnce("death-notliving:" + victim.getUniqueId(),
                    "[IC] 死亡能力跳过：" + victim.getType() + " 不是生物");
            return;
        }
        UUID entityId = victim.getUniqueId();
        Location where = null;
        try {
            // ★ 先取位置再派发：派发过程中效果可能（间接）让实体彻底消失
            where = living.getLocation();
        } catch (Throwable t) {
            warnOnce("death-loc:" + entityId,
                    "[IC] 取死亡位置失败（实体 " + entityId + "）：" + t + "，按「没有 origin」处理");
        }
        dispatch(victim, matchId, match, Card.Trigger.ON_DEATH, "死亡", where);
        // 亡语是一次性的 ⇒ 派发完就把登记摘掉（否则每死一次还留着一条无用登记）
        forget(entityId);
    }

    /**
     * <b>对局收尾：取消全部周期任务</b>（★ <b>幂等</b> —— 再调一次取消 0 个，不抛）。
     *
     * <p>接线方应在对局结束时调它。⚠️ 即使忘了调，{@link MatchService#stop} 里的
     * {@link LifecycleTasks#cancelAll()} 也会兜底取消（周期任务都登记在那张表上）
     * —— 「两条命」，因为「下一局凭空冒出召唤物」是本项目最贵的一类事故。
     *
     * <p>本方法<b>只</b>取消本类起的任务（<b>不</b>动 {@link LifecycleTasks} 里别人的任务：
     * 那是对局的收尾职责，不是本类的）。
     */
    public void stop() {
        int cancelled = schedule.cancelAll();
        int forgotten = registry.size();
        registry.clear();
        warned.clear();
        log().info("[IC] 能力运行时收尾：取消周期任务 " + cancelled + " 个；"
                + "清空单位登记 " + forgotten + " 条（幂等：重复 stop() 取消 0 个）");
    }

    /** 当前正在跑的周期任务数（测试与日志用）。 */
    public int activePeriodicCount() {
        return schedule.activeCount();
    }

    /** 当前报备过能力的单位数（测试与日志用）。 */
    public int registeredCount() {
        return registry.size();
    }

    /** 一行摘要（启动 / 排障日志用）。 */
    public String describe() {
        return "AbilityRuntime（卡牌目录 " + (catalog == null ? "未接" : catalog.size() + " 张卡")
                + "｜周期任务 " + schedule.activeCount() + " 个）";
    }

    // ================================================================== 纯逻辑（可离线断言）

    /**
     * ★★ <b>这个效果该打到谁</b>（纯函数，全程不碰服务端 ⇒ 可离线逐类断言）。
     *
     * <p>口径（出处见类 javadoc 的「{@code GRANT_XP} 的特例」）：
     * <ul>
     *   <li>{@code GRANT_XP} ⇒ <b>召唤者</b>（{@code owner}）；{@code owner == null}
     *       ⇒ 返回 {@code null}，表示<b>跳过这条效果</b>（召唤者已离线 / 未知）；</li>
     *   <li>其它一切效果（含 {@code null} 类型）⇒ <b>召唤物自己</b>（{@code summon}）。</li>
     * </ul>
     *
     * <p>★★ <b>2026-10-17 批次 3 后半：本方法不再是"完整的目标口径"</b> ——
     * 它现在是<b>单目标兜底</b>（没有 {@code radius} 时的那一档）。
     * 「周围 N 格」由 {@link AbilityTargets#resolve} 决定，两者的分工写在那里。
     * ★ 本方法<b>保留</b>（而不是删掉）：它是 {@code GRANT_XP} 特例的<b>唯一可离线复述点</b>，
     * 且 {@code AbilityRuntimeTest} 用 15 类效果逐类钉着它。
     */
    public static UUID effectTargetFor(Spell.EffectType type, UUID summon, UUID owner) {
        if (type == Spell.EffectType.GRANT_XP) {
            // ★ docs/07 §6.4：「给召唤者加经验」。召唤者不在线 / 未知 ⇒ null（跳过，不猜目标）
            return owner;
        }
        return summon;
    }

    /**
     * ★★★ <b>本次触发真正要吃这条效果的目标集</b>（2026-10-17 批次 3 后半新增）。
     *
     * <h2>三种形状，逐条对应 {@code docs/19} 的原文</h2>
     * <ol>
     *   <li><b>没有 {@code radius}</b>（{@code radius == null}）⇒ <b>单个目标</b>，
     *       口径 = {@link #effectTargetFor}（{@code GRANT_XP} 给召唤者，其余给自己）
     *       —— 这是本次改动之前的行为，一字未改；</li>
     *   <li><b>有 {@code radius}</b> 且效果类型是 {@code GRANT_XP} / {@code REFRESH_COOLDOWN}
     *       / {@code COOLDOWN_REDUCTION} ⇒ <b>仍然只打自己</b>（或被跳过）：
     *       这三类效果<b>结构上只作用于"本卡 / 施放者"</b>（见 {@code spell/EffectScope}），
     *       给自己以外的人施加它们在语义上没有意义 ⇒ <b>不</b>把它们摊给范围（不发明语义）；</li>
     *   <li><b>有 {@code radius}</b> 且是其余效果 ⇒ 走 {@link AbilityTargets#resolve}：
     *       候选 = <b>本场账本里的单位</b>（铁律 10：不查世界），
     *       按 {@code targets} / {@code radius} / {@code include-self} 三条键筛。
     *       ★ 这一档<b>不含"召唤者"</b>：范围是"召唤物周围 N 格"，
     *       而召唤者在不在范围里由他的坐标与阵营自然决定（他就是同阵营的一个单位，
     *       但他的阵营 UUID 记在账本里的是"玩家"，玩家不进账本 ⇒ 目前玩家不会被范围命中，
     *       与 R42「召唤物不索敌玩家」同一口径 —— 要改这条必须同时改索敌与法术）。</li>
     * </ol>
     *
     * <h2>★ 为什么这一格必须是"运行时算"而不是"装载期定"</h2>
     * 「周围 3 格有谁」只有触发那一刻知道（单位会移动、会死）⇒ 装载期只能校验
     * <b>键合法</b>（{@code CardCatalog} 已做），目标集必须每次触发重算。
     */
    private List<LivingEntity> effectTargetsFor(Card.Ability ability, Spell.Effect effect,
                                                LivingEntity summon, UUID owner,
                                                String matchId, MatchService match,
                                                Location origin, LivingEntity attackTarget) {
        Spell.EffectType type = effect.type();
        // ★★★ 2026-10-19（怨怼轰炸机亡语 = 在**死亡处**施放「定点爆破」）：
        //   `CAST_SPELL` **不需要"打谁"** —— 落点由 `origin`（死亡位置）给，
        //   而 ON_DEATH 触发的那一刻，这个实体本身**可能已经不可解析**
        //   （`Bukkit.getEntity` 返回 null / 已 dead）⇒ 若走下面"单目标 = 自己"那条，
        //   整条效果会被判成"目标已不在"而静默跳过。
        //   ⇒ 这一档**直接给自己**（`EffectApplier` 不看 target），
        //   `EffectScope.runsWithoutTarget(CAST_SPELL) == true` 也是同一个事实的另一处表述。
        if (type == Spell.EffectType.CAST_SPELL) {
            return List.of(summon);
        }
        // ---- 第 1 档：没有范围 ⇒ 单个目标（既有行为） ----
        if (!ability.hasRadius()) {
            UUID targetId = effectTargetFor(type, summon.getUniqueId(), owner);
            if (targetId == null) {
                warnOnce("target-null:" + type + ":" + summon.getUniqueId(),
                        "[IC] 能力 " + abilityName(ability) + " 的 " + type
                                + " 跳过：它的目标是召唤者，而召唤者已离线 / 未知（卡 "
                                + cardIdFor(summon) + "）");
                return List.of();
            }
            LivingEntity target = resolveTarget(targetId, summon);
            if (target == null) {
                warnOnce("target-gone:" + targetId,
                        "[IC] 能力 " + abilityName(ability) + " 的 " + type
                                + " 跳过：目标 " + targetId + " 已不在");
                return List.of();
            }
            return List.of(target);
        }
        // ---- 第 2 档：范围 + 「只作用于本卡 / 施放者」的效果 ⇒ 仍然只打自己 ----
        if (!AbilityTargets.rangeApplies(type)) {
            return List.of(summon);
        }
        // ---- 第 3 档：真·范围 ----
        List<LivingEntity> ranged = resolveRangeTargets(ability, summon, matchId, match, origin,
                attackTarget);
        // ★★★ 2026-10-19 用户裁定（逐字）：「（铜傀儡）`heal_allies_periodically` 的目标
        //   **排除目标物**（不治疗防御塔）」⇒ **HEAL 不吃建筑**。
        //   ★ 只对 HEAL 这一档过滤：其它效果（DAMAGE / STUN / 伤害型亡语）对建筑的判定
        //     与改动前**完全一致**（它们的候选从来就包含建筑）⇒ 零玩法变化。
        if (type == Spell.EffectType.HEAL) {
            return dropBuildings(ranged, match);
        }
        return ranged;
    }

    /**
     * ★★ <b>把建筑从候选里剔掉</b>（2026-10-19 新增；判据 = 账本上的建筑标记）。
     *
     * <p>为什么必须读标记而不是按实体类型判：信标 / 防御塔是**村民载体**，
     * 而"村民"本来是一个合法的治疗对象；同时将来任何一张写了
     * {@code summon.building: true} 的召唤卡也要跟着一起被排除。
     *
     * <p>★ 失败方向：拿不到账本 ⇒ 原样返回（宁可不排除，也不要因为读不到归属
     * 而把整条治疗能力变成 no-op）。
     */
    private static List<LivingEntity> dropBuildings(List<LivingEntity> candidates, MatchService match) {
        if (candidates == null || candidates.isEmpty()
                || match == null || match.units() == null) {
            return candidates;
        }
        List<LivingEntity> kept = new ArrayList<>(candidates.size());
        for (LivingEntity entity : candidates) {
            if (!match.units().building(entity)) {
                kept.add(entity);
            }
        }
        return kept;
    }

    /**
     * 把「周围 N 格」解析成实体清单。
     *
     * <p>★ 候选<b>只</b>来自 {@link UnitRegistry#tracked(String)}（本场登记过的 UUID）
     * + 按 UUID 点查 {@link Bukkit#getEntity(UUID)} —— <b>没有任何世界范围查询</b>
     * （铁律 10；{@code guard/BytecodeScopeGuardTest} 的 8 个记号一个都不出现）。
     * 这就是"范围由归属界定"的落地：与本场账本无关的实体<b>永远</b>不会被这条能力碰到。
     *
     * <p>★★ <b>中心取哪</b>（2026-10-17 批次 3 后半新增，{@link AbilityTargets.Center}）：
     * <ul>
     *   <li>{@code SELF}（缺省）⇒ 用传进来的 {@code origin}（触发者的位置）；</li>
     *   <li>{@code ATTACK_TARGET} ⇒ 用 {@code attackTarget} 的位置。
     *       ★ <b>拿不到当前目标 ⇒ 整条能力什么都不做</b>（不是"退回自己周围"）——
     *       冰术师「对<b>目标</b>及其周围 1 格」把范围搬到自己脚下会变成另一种能力；</li>
     *   <li>★★ {@code NEAREST_ENEMY} ⇒ <b>先用半径筛出敌方候选，取最近的那个当中心</b>
     *       （死灵法师「8 格内目标处」）。★ 它<b>不看原版选敌</b>：候选只来自账本，
     *       所以"原版把队友当目标"影响不到它。半径内没有敌人 ⇒ 整条能力不做。</li>
     * </ul>
     */
    private List<LivingEntity> resolveRangeTargets(Card.Ability ability, LivingEntity summon,
                                                   String matchId, MatchService match,
                                                   Location origin, LivingEntity attackTarget) {
        if (match == null || match.units() == null) {
            warnOnce("range-noregistry:" + summon.getUniqueId() + ":" + abilityName(ability),
                    "[IC] 范围能力 " + abilityName(ability) + " 跳过：拿不到本场账本"
                            + "（matchId=" + matchId + "）—— 范围只能由账本界定，"
                            + "绝不用「附近的实体」那种无边查询（铁律 10）");
            return List.of();
        }
        AbilityTargets.Center centerKind = ability.centerOrSelf();
        Location center = origin;
        if (centerKind == AbilityTargets.Center.ATTACK_TARGET) {
            if (attackTarget == null || !attackTarget.isValid() || attackTarget.isDead()) {
                // ★ 中心取不到 ⇒ 不做（也不告警刷屏：冰术师每 2s 触发一次，
                //   而"这一刻没有目标"在实机上是常态）
                return List.of();
            }
            center = attackTarget.getLocation();
        }
        if (center == null || center.getWorld() == null) {
            // fail-safe：判不出范围就**什么都不做**（绝不退化成"打全场"）
            warnOnce("range-noorigin:" + summon.getUniqueId() + ":" + abilityName(ability),
                    "[IC] 范围能力 " + abilityName(ability) + " 跳过：拿不到范围中心"
                            + "（实体 " + summon.getUniqueId() + "）");
            return List.of();
        }
        OwnershipTable table = match.units().table();
        Side attackerSide = sideOf(summon, matchId, match);
        // ★★ `NEAREST_ENEMY`（死灵法师「8 格内目标处」）：**先按半径挑出最近的那个敌人，
        //   再把它当中心**，然后再建一次候选表。
        //   ★ 顺序刻意是「建候选 → 定中心 → **重建**候选 → 统一解析」：
        //     ① 若反过来（先解析再挑）会得到"范围内的全部" —— 那就变成 AoE 了；
        //     ② 若"建一次、只换中心坐标"则不重建也能跑，但那份候选表的**世界过滤**
        //        是按旧中心做的 ⇒ 跨世界/失效的过滤理由会变得说不清。
        //        多建一次是 O(本场单位数)，而方块怪那一批实测只有个位数到几十。
        // ★★★ 2026-10-18（用户更正）：`NEAREST_ENEMY` 的语义是
        //   「**在目标处使用一次这张卡的效果**」—— 死灵法师 = 在 8 格内最近的敌人处
        //   放一次「火球术」，而火球术**本身就是半径 2 的 AoE**（docs/19 第 71 行）。
        //   ⇒ 找目标用 `search-radius`（8），炸圈用 `radius`（2）—— 两个数、两个键。
        boolean nearestCenter = centerKind == AbilityTargets.Center.NEAREST_ENEMY;
        List<AbilityTargets.Candidate> candidates = buildCandidates(match, matchId, summon, center);
        int skipped = 0;   // 只用于日志里的"另有 N 个已失效"，由 buildCandidates 旁边那次统计承担
        if (nearestCenter) {
            // ★ search-radius = 找目标的距离；没写则退回 radius（老卡行为不变）
            Integer search = ability.searchRadius() != null ? ability.searchRadius() : ability.radius();
            List<UUID> nearest = AbilityTargets.resolveNearestEnemy(attackerSide,
                    new double[] { center.getX(), center.getY(), center.getZ() },
                    search == null ? 0 : search, candidates);
            if (nearest.isEmpty()) {
                return List.of();   // 半径内没有敌人 ⇒ 不做（也不告警刷屏）
            }
            LivingEntity living = resolveTarget(nearest.get(0), summon);
            if (living == null) {
                return List.of();
            }
            // ★★★ 2026-10-18（用户第 2 条：「死灵法师在释放火球术时会发射几个火球，
            //   这显然不在计划中」＋「范围明显比原版火球术这张卡大」）：
            //   旧实现把"最近的敌人"当成**范围中心**，然后再解析一次「以它为中心、半径 8 的圆」
            //   ⇒ 命中**一圈人** ⇒ 每个目标各放一颗火球（`launchFireball` 在目标循环里）
            //   ⇒ 用户看到"一次射出好几个火球"，而且伤害范围是 8 格（火球术那张卡只有 2 格）。
            //   ★ 正确语义（`docs/19` 第 87 行「8 格内**目标**处放一次火球术」）：
            //     **radius 是搜索半径，目标只有那一个敌人**。
            //   ⇒ 直接返回这一个目标；同时**不画范围环**（它不是 AoE），只保留
            //     「自己 → 目标」的直线与命中处火焰（见 playAbilityFx 的 singleTarget 参数）。
            center = living.getLocation();
            candidates = buildCandidates(match, matchId, summon, center);
        }
        // ★★★ 2026-10-21（用户实机报的缺陷）：「这个海王现在只会一直站在自家防御塔这里不动，
        //   劲弩手也是」—— 根因是**我把"技能射程"错做成了"索敌半径"**（`summon.target-radius: 8`）：
        //   索敌半径一收紧，8 格内没敌人 ⇒ **根本没有目标** ⇒ `CombatDirector` 不给目标 ⇒
        //   **没有理由移动** ⇒ 永远站在出生点/塔边。射程应该限制的是**施法**这一步，
        //   不是"能不能发现敌人"。
        //   ⇒ 判据落在这一处：`abilities[].search-radius` = **本次施法允许的最大距离**
        //   （从施法者到范围中心）。超过 ⇒ 这一拍**不施放**（返回空表；不消耗任何东西，
        //   下一个周期照常判一次 —— 单位还在往目标走，走近了自然就放）。
        //   ★ 对 `NEAREST_ENEMY`（死灵法师）**行为不变**：它的中心本来就是 8 格内挑出来的。
        Integer castRange = ability.searchRadius();
        if (castRange != null && castRange > 0) {
            double dx = center.getX() - summon.getLocation().getX();
            double dy = center.getY() - summon.getLocation().getY();
            double dz = center.getZ() - summon.getLocation().getZ();
            if (dx * dx + dy * dy + dz * dz > (double) castRange * castRange) {
                return List.of();   // 目标不在施法距离内 ⇒ 这一拍不做（不是"打全场"，也不是"跳过整条能力"）
            }
        }
        List<UUID> ids = AbilityTargets.resolve(ability.targets(), ability.includeSelf(),
                ability.radius(), summon.getUniqueId(), attackerSide,
                new double[] { center.getX(), center.getY(), center.getZ() }, candidates);
        List<LivingEntity> targets = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            if (id.equals(summon.getUniqueId())) {
                targets.add(summon);
                continue;
            }
            LivingEntity living = resolveTarget(id, summon);
            if (living != null && !living.isDead()) {
                targets.add(living);
            }
        }
        if (targets.isEmpty()) {
            // ★ 可对账：范围能力"一个人都没打到"是正常情况（对面不在附近），但必须留一行
            //   日志，否则实机上分不清"没打到"与"根本没触发"
            log().info("[IC] 范围能力 " + abilityName(ability) + " 本次命中 0 个单位"
                    + "（" + AbilityTargets.describe(ability.targets(), ability.radius(),
                    ability.includeSelf(), ability.centerOrSelf()) + "｜中心 "
                    + String.format("%.1f,%.1f,%.1f", center.getX(), center.getY(), center.getZ())
                    + "｜候选 " + candidates.size() + " 个，另有 " + skipped
                    + " 个已失效 / 不在同一世界）");
        }
        // ★★★ 2026-10-17 第十三轮收尾：**能力也要看得见**（用户：「冰术师等卡牌的攻击…
        //   如果不添加特效是看不出来的」＋「雷神（= 海王）登场时需要的闪电特效反而没有了」）。
        //   ★ 即使 targets 为空也画环 —— 那正是用户说的「看不出范围」：
        //     "放空了"和"没触发"必须能靠眼睛分开。
        playAbilityFx(ability, summon, center, targets);
        return targets;
    }

    /**
     * ★★ <b>播一条能力的表现</b>（{@code abilities[].fx} ＋ 半径默认环）
     * —— 2026-10-17 第十三轮收尾新增；2026-10-18 加「直线 + 光环持续」。
     *
     * <h2>四层（从"永远有"到"卡自己说"）</h2>
     * <ol>
     *   <li><b>半径 ≥ 0 ⇒ 画一圈范围环</b>（{@code FxRing} 的边界格，与法术同一个实现）。
     *       用户原话「**看不出范围**」就是这一格的缺失 ⇒ 默认就该有，不该要求每张卡写；
     *       ★★★ 2026-10-18：环现在是<b>按能力周期持续重画</b>的（见
     *       {@link #drawRingPersistently}）—— 用户原话
     *       「**蒸汽精灵的友方buff光环也需要实刻显示光环范围**」：
     *       只画一帧的话，3 秒一次触发之间玩家几乎看不到那个圈；</li>
     *   <li><b>中心离自己远 ⇒ 画一条从自己到中心的直线</b>（2026-10-18 新增）。
     *       用户原话：「**防御塔、冰术师这种单体指向性的攻击特效也需要有从施法者到目标的
     *       直线粒子特效**」—— 冰术师的能力是 {@code center: ATTACK_TARGET}，
     *       "它打了谁"必须能靠眼睛看出来；</li>
     *   <li><b>{@code fx.particle} ⇒ 撒在每个命中目标身上</b>（没写就不撒，避免所有能力炸成一片）；</li>
     *   <li><b>{@code fx.lightning} ⇒ 对每个命中目标劈一道纯视觉闪电</b>
     *       （海王登场就是这一档）。</li>
     * </ol>
     *
     * <p>★★ <b>整个方法包在 try/catch 里，失败只告警一次</b>：表现绝不允许影响玩法
     * （与 {@code MooncakeEffect} 的「按 ID 找不到就 warn 一次并啥也不做」同一口径）。
     *
     * @param caster 触发这条能力的单位（画直线要用它的位置；★ 不是法术的"施放者玩家"）
     */
    private void playAbilityFx(Card.Ability ability, LivingEntity caster, Location center,
                               List<LivingEntity> targets) {
        playAbilityFx(ability, caster, center, targets, true);
    }

    /**
     * 同上，并可**关掉范围环**（{@code drawRing = false}）。
     *
     * <p>★★ 2026-10-18：`center: NEAREST_ENEMY` 的能力（死灵法师）里，`radius` 是
     * <b>搜索半径</b>而不是伤害范围 ⇒ 画一个半径 8 的环会让玩家以为那是 AoE
     * （用户原话：「它释放的火球术**范围**明显比原版火球术这张卡大」）。
     * ⇒ 单体能力只画「自己 → 目标」的直线 + 命中处火焰。
     */
    private void playAbilityFx(Card.Ability ability, LivingEntity caster, Location center,
                               List<LivingEntity> targets, boolean drawRing) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        Card.Ability.Fx fx = ability.fxOrNone();
        // ★★★ 2026-10-18：**每一步各自 try**（旧实现是整段一个 try）——
        //   现场那条 NPE 就是反例：环的排期抛了 null 拆箱，于是**它后面的闪电 / 粒子 / 音效
        //   一并没执行**，而日志只有一句"表现层失败"，看起来像"闪电没写"。
        //   拆成 5 步之后，任何一步坏掉只损失它自己（并且各留一条不同的告警键）。
        try {
            // ① 范围环（半径存在就画，包括 0 格 = 原地一圈）；★ 单体能力不画（见重载的 javadoc）
            if (drawRing && ability.radius() != null) {
                Particle particle = particleOf(fx.particleOrDefault(), ability);
                if (particle != null) {
                    drawRingPersistently(ability, center, ability.radius(), particle);
                }
            }
        } catch (Throwable t) {
            fxFail(ability, "ring", t);
        }
        try {
            // ⓪ ★★ 直线（2026-10-18）：中心离自己远 ⇒ 画一条「我 → 那里」的光束
            //   ★★★ 2026-10-21 用户裁定（逐字）：「**海王不需要指向敌人的射线粒子**」
            //   ⇒ 由 `fx.line`（缺省 true）控制；海王卡里写 `line: false`。
            //   ★ 没写这一格的其它能力**行为一字未变**。
            if (fx.line() && caster != null && caster.getWorld() != null) {
                Particle particle = particleOf(fx.particleOrDefault(), ability);
                if (particle != null) {
                    drawLine(caster, center, particle);
                }
            }
        } catch (Throwable t) {
            fxFail(ability, "line", t);
        }
        try {
            // ② 每个命中目标身上撒粒子（没写 fx.particle 时**不**撒，避免所有能力都炸成一片）
            if (fx.particle() != null && !targets.isEmpty()) {
                Particle particle = particleOf(fx.particle(), ability);
                if (particle != null) {
                    for (LivingEntity target : targets) {
                        Location where = target.getLocation().add(0.0, target.getHeight() * 0.5, 0.0);
                        target.getWorld().spawnParticle(particle, where, 12, 0.3, 0.4, 0.3, 0.02);
                    }
                }
            }
        } catch (Throwable t) {
            fxFail(ability, "particle", t);
        }
        try {
            // ③ 音效
            if (fx.sound() != null) {
                center.getWorld().playSound(center, fx.sound(), 1.0f, 1.0f);
            }
        } catch (Throwable t) {
            fxFail(ability, "sound", t);
        }
        try {
            // ④ 纯视觉闪电（海王登场：用户点名）
            if (fx.lightning()) {
                // ★★★ 2026-10-18 补（用户：「**法术和海王在右键空气释放时不会触发特效**」）：
                //   判据与法术侧**同一个纯类** {@link LightningPlan} ——
                //   **一个都没打中 ⇒ 在中心劈一道**。海王登场时四周没有敌人是常态
                //   （尤其是"右键空气，落点在自己脚下"这一档），而闪电原本只劈在
                //   命中目标头上 ⇒ 屏幕上什么都不会发生。登场雷至少要看得见。
                if (LightningPlan.of(true, targets.size()) == LightningPlan.Kind.AT_ORIGIN) {
                    center.getWorld().strikeLightningEffect(center.clone().add(0.0, 1.0, 0.0));
                }
                for (LivingEntity target : targets) {
                    if (target.getWorld() != null) {
                        target.getWorld().strikeLightningEffect(target.getLocation());
                    }
                }
            }
        } catch (Throwable t) {
            fxFail(ability, "lightning", t);
        }
    }

    /** 表现层的某一步失败：**按步骤**各告警一次（同一步只喊一次，别刷屏）。 */
    private void fxFail(Card.Ability ability, String step, Throwable t) {
        warnOnce("fx:" + step + ":" + abilityName(ability),
                "[IC] 能力的表现层「" + step + "」这一步失败（只影响观感，伤害与状态照常结算，"
                        + "其它步骤照跑）：" + t);
    }

    /** 直线特效的最小距离（格）：低于它就不画（贴身攻击画线只会糊住双方）。 */
    private static final double ABILITY_LINE_MIN_DISTANCE = 1.5;

    /** 直线特效的采样步长（格）。 */
    private static final double ABILITY_LINE_STEP = 0.6;

    /** 直线特效最多画几个点（护栏）。 */
    private static final int ABILITY_LINE_MAX_POINTS = 48;

    /** 光环重画的间隔（tick）：0.5 秒一次 ⇒ 肉眼是连续的。 */
    private static final int RING_PERIOD_TICKS = 10;

    /** 光环最多重画几次（护栏）：60 × 10 = 600 tick ≈ 30 秒。 */
    private static final int RING_MAX_BEATS = 60;

    /**
     * ★★ <b>「我打到了谁」的直线</b>（2026-10-18 新增）。
     *
     * <p>用户原话：「防御塔、冰术师这种单体指向性的攻击特效也需要有从施法者到目标的直线粒子特效」。
     * 几何全在纯类 {@link LineFx}（等距 / 退化 / 步长都有单测），这里只喂坐标。
     * ★ 失败方向：任何异常只告警一次（观感不该影响能力结算）。
     */
    private void drawLine(LivingEntity caster, Location center, Particle particle) {
        try {
            Location eye = caster.getEyeLocation();
            Location to = center.clone().add(0.0, 1.0, 0.0);
            if (eye.getWorld() == null || to.getWorld() == null
                    || !eye.getWorld().equals(to.getWorld())) {
                return;
            }
            double distance = LineFx.distance(eye.getX(), eye.getY(), eye.getZ(),
                    to.getX(), to.getY(), to.getZ());
            if (distance < ABILITY_LINE_MIN_DISTANCE) {
                return;
            }
            int points = LineFx.pointsFor(distance, ABILITY_LINE_STEP, 4, ABILITY_LINE_MAX_POINTS);
            World world = eye.getWorld();
            for (double[] point : LineFx.line(eye.getX(), eye.getY(), eye.getZ(),
                    to.getX(), to.getY(), to.getZ(), points)) {
                world.spawnParticle(particle, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0);
            }
        } catch (Throwable t) {
            warnOnce("fx-line", "[IC] 能力的直线特效失败（只影响观感）：" + t);
        }
    }

    /**
     * ★★★ <b>持续重画范围环</b>（2026-10-18 新增；用户：「**蒸汽精灵的友方buff光环也需要实刻
     * 显示光环范围**」——"实刻"即"实时"）。
     *
     * <h2>为什么不能只画一帧</h2>
     * 蒸汽精灵的能力每 {@code cooldown-ticks = 60}（3 秒）触发一次，而环只在触发那一刻闪一下 ⇒
     * 玩家 99% 的时间看不到那个圈，"光环范围"实际上无从判断。
     *
     * <h2>口径（★ 有界、自取消 —— 不占 {@code LifecycleTasks}）</h2>
     * 从触发那一刻起，每 {@value #RING_PERIOD_TICKS} tick 重画一次，
     * 共 {@code min(上限, ⌈cooldown / 周期⌉)} 次 ⇒ 正好覆盖到下一次触发，
     * 然后自己 {@code cancel()}。★ 帧数上限 {@value #RING_MAX_BEATS} 是护栏
     * （卡里把 {@code cooldown-ticks} 写成天文数字时，视觉任务也必须有界）。
     * ★ 它不召唤、不改状态 ⇒ 即使对局提前结束，最坏只是多亮几帧粒子。
     * ★ 调度器拿不到 ⇒ 退化成只画一帧（绝不静默没有环）。
     */
    private void drawRingPersistently(Card.Ability ability, Location center, int radius,
                                      Particle particle) {
        // ★★★ 2026-10-18 修（现场空指针）：`cooldownTicks()` 对 ON_SUMMON 这类能力是 **null**
        //   ⇒ 旧实现 `Math.max(0, ability.cooldownTicks())` 拆箱 NPE ⇒ 整段表现层被吞
        //   ⇒ 海王登场的闪电/粒子/音效全没了（日志：`Cannot invoke "java.lang.Integer.intValue()"
        //   because the return value of "Card$Ability.cooldownTicks()" is null`）。
        //   判据现在在纯类 {@code FxTimeline#beatsFor}（有单测：null ⇒ 1 帧，绝不 0 帧）。
        int beats = io.mczju.infernalclash.spell.FxTimeline.beatsFor(
                ability.cooldownTicks(), RING_PERIOD_TICKS, RING_MAX_BEATS);
        if (beats <= 1 || plugin == null || !plugin.isEnabled()) {
            drawRing(center, radius, particle);
            return;
        }
        final Location snapshot = center.clone();
        final int total = beats;
        final int ringRadius = radius;
        final Particle ringParticle = particle;
        final int[] done = {0};
        final org.bukkit.scheduler.BukkitTask[] handle = new org.bukkit.scheduler.BukkitTask[1];
        Runnable step = () -> {
            if (done[0] >= total) {
                if (handle[0] != null) {
                    handle[0].cancel();
                }
                return;
            }
            done[0]++;
            try {
                drawRing(snapshot, ringRadius, ringParticle);
            } catch (Throwable t) {
                if (handle[0] != null) {
                    handle[0].cancel();
                }
            }
        };
        try {
            handle[0] = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, step, 0L, RING_PERIOD_TICKS);
        } catch (Throwable t) {
            drawRing(center, radius, particle);   // 排不上就至少画一帧
        }
    }

    /** 粒子名 → {@link Particle}；解析失败**只告警一次**并返回 {@code null}（不静默、不崩）。 */
    private Particle particleOf(String name, Card.Ability ability) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException e) {
            warnOnce("particle:" + name, "[IC] 未知粒子名 " + name + "（能力 "
                    + abilityName(ability) + "），本条能力不画环/不撒粒子"
                    + "（只告警一次；写法应为 org.bukkit.Particle 的常量名）");
            return null;
        }
    }

    /**
     * 沿半径画一圈粒子（与 {@code SpellCaster#emitFx} 同一几何：
     * 「<b>边界格</b>」而不是实心填充 —— 环只是提示范围，填满会挡视线）。
     *
     * <p>★★★ 2026-10-18（用户第 1 条）：「**环特效依然不明显，建议除了平面一圈环外加几圈
     * 在 y 轴向上偏移一些的**」⇒ 现在**每个环都摞成几层**（层数 / 间距在纯类
     * {@link FxRing#levelOffsets()}，半径 → 点数在 {@link FxRing#pointsFor}）。
     * 这里只负责把坐标喂给 {@code spawnParticle} —— 几何绝不在这里另算一遍
     * （两处各算一份正是"法术那个加粗了、能力那个还是细的"这类分叉的来源）。
     */
    private void drawRing(Location center, int radius, Particle particle) {
        int r = Math.max(0, radius);
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        double baseY = center.getY() + 0.6;
        if (r == 0) {
            // 半径 0 = 原地一团（没有"环"可言），仍然按层重复，让它看起来是"一团立起来的东西"
            for (double levelOffset : FxRing.levelOffsets()) {
                world.spawnParticle(particle, center.getX(), baseY + levelOffset, center.getZ(),
                        8, 0.2, 0.2, 0.2, 0.0);
            }
            return;
        }
        java.util.List<double[]> circle = FxRing.circle(r, FxRing.pointsFor(r));
        for (double levelOffset : FxRing.levelOffsets()) {
            double y = baseY + levelOffset;
            for (double[] offset : circle) {
                world.spawnParticle(particle, center.getX() + offset[0], y,
                        center.getZ() + offset[1], 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }


    /**
     * ★ <b>按「范围中心」从账本建一次候选表</b>（{@link #resolveRangeTargets} 的唯一建表口）。
     *
     * <p>★ 抽成一个方法而不是内联两次：{@code NEAREST_ENEMY} 需要"以新中心<b>重建</b>"
     * （见那里的注释），而两处一旦各写一份，"世界过滤按哪个中心做"就会分叉。
     *
     * <p>★ 候选<b>只</b>来自 {@link UnitRegistry#tracked(String)}（本场登记过的 UUID）
     * + 按 UUID 点查 {@link Bukkit#getEntity(UUID)} —— <b>没有任何世界范围查询</b>（铁律 10）。
     */
    private List<AbilityTargets.Candidate> buildCandidates(MatchService match, String matchId,
                                                           LivingEntity summon, Location center) {
        List<AbilityTargets.Candidate> candidates = new ArrayList<>();
        OwnershipTable table = match.units().table();
        for (UUID id : match.units().tracked(matchId)) {
            if (id == null || id.equals(summon.getUniqueId())) {
                continue;   // 自己由 include-self 决定，不靠"距离 0"顺带命中
            }
            OwnershipTable.Owner owner = table.owner(id);
            Entity entity = resolveEntity(id);
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                continue;
            }
            Location where = living.getLocation();
            if (where.getWorld() == null || !where.getWorld().equals(center.getWorld())) {
                continue;
            }
            candidates.add(new AbilityTargets.Candidate(id,
                    owner == null ? null : owner.side(),
                    where.getX(), where.getY(), where.getZ()));
        }
        return candidates;
    }

    /**
     * ★ <b>能不能在声明的触发器上跑这条能力</b>（纯函数）。
     *
     * <p>三种 false：
     * <ol>
     *   <li>{@code ability == null}；</li>
     *   <li>{@code ability.trigger() != trigger}（不是这个时机的事）；</li>
     *   <li>{@code effects} 为空（没有效果可施加 —— 「什么都不做的能力」是配置错误，
     *       但运行期只告警不炸）。</li>
     * </ol>
     *
     * <p>★ 另外，{@code id} 与 {@code trigger} 的一致性由
     * {@link AbilityCatalog#validate} 在<b>装载期</b>负责（那一层才该硬失败）。
     * 运行期不再抛：对局已经开打了，此时把整场炸掉是更糟的失败方向。
     */
    public static boolean castAbilityTarget(String cardId, int index, Card.Ability ability,
                                            Card.Trigger trigger) {
        // ★ effects 为 null 也要判 false：手工构造的记录绕过了 CardCatalog 的校验，
        //   这里若直接解引用就会让事件监听器抛 NPE（那是"炸掉整场"的方向）
        return ability != null && ability.trigger() == trigger
                && ability.effects() != null && !ability.effects().isEmpty();
    }

    /**
     * ★ <b>周期性能力的 {@code cooldown-ticks} 够不够格启动</b>（纯函数，离线可断言）。
     *
     * <p>{@code PERIODIC} 的周期是<b>必需</b>的（没周期就没有"周期"），而
     * {@code docs/03} §4.3 把该键标成【待填】、<b>没有给任何缺省值</b>
     * ⇒ 本类<b>不替用户猜一个周期</b>（铁律 8 / 铁律 11）。
     *
     * <p><b>为什么判定要单独抽出来</b>：这条「什么时候不启动」的判据是
     * {@code docs/11} §R6 那条断言的<b>唯一落点</b>；抽成纯函数后可以在 JUnit 里
     * 逐条钉住（缺失 / {@code 0} / 负数 / 正当值四条路径），否则「跳过」这一支
     * 只能靠实机日志观察 —— 没被验过的防线等于没有防线。
     * ★ <b>它不改变行为</b>：{@link #startPeriodic} 用的就是同一个判据。
     *
     * <p>口径：{@code null}（键缺失）或 {@code <= 0} ⇒ 不足格；
     * {@code > 0} ⇒ 够格（此时 {@code cooldown-ticks} 就是那个周期）。
     *
     * @param ability 一条能力（{@code null} ⇒ 不足格：连能力都没有，谈不上周期）
     * @return {@code true} = 可以起周期任务；{@code false} = 告警一次并跳过这一个能力
     */
    static boolean periodicCooldownSufficient(Card.Ability ability) {
        Integer cooldown = ability == null ? null : ability.cooldownTicks();
        return cooldown != null && cooldown > 0;
    }

    // ================================================================== 内部：施加

    /**
     * 起一个周期任务（★ 同一（召唤物, 能力名）只保留一个，重入先取消旧的）。
     *
     * <p>顺序刻意是 <b>「先取消旧的 → 再起新的 → 再登记」</b>：
     * 反过来的话，两次 {@link #onSummonSpawn} 之间会短暂存在两个周期任务
     * （效果翻倍），而「只起一个」正是这条口径要保证的事。
     */
    private void startPeriodic(Card.Ability ability, String cardId, UUID summonId,
                               LivingEntity summon, String matchId, Side side, UUID owner,
                               MatchService match) {
        Integer cooldown = ability.cooldownTicks();
        if (!periodicCooldownSufficient(ability)) {
            // ★ 判定见 periodicCooldownSufficient（纯函数、有测试钉住）。
            //   装载期**不**拒绝缺这个键的卡 ⇒ 处置就在这一处：告警一次 + 跳过**这一个**能力，
            //   不猜周期、不连累整局、也不连累插件启动（理由见类 javadoc 的「周期缺省值」）
            warnOnce("periodic-nocooldown:" + summonId + ":" + abilityName(ability),
                    "[IC] 周期能力 " + abilityName(ability) + " 不启动：cooldown-ticks 缺失或非正（当前 "
                            + cooldown + "）。装载期不拒绝该键（卡照常装载、插件照常启动）："
                            + "CardCatalog 把它做成可选，处置在这里 —— 只跳过这一个能力，"
                            + "别的能力照跑、整局照打（卡是数据，缺键不是玩家的错）。docs/03 §4.3 把该键"
                            + "标成【待填】、没有给缺省值，不替用户猜一个周期（铁律 8）。请在该能力的"
                            + "abilities 项里显式写 cooldown-ticks: <周期 tick>（卡 " + cardId + "）");
            return;
        }
        if (match == null || match.tasks() == null) {
            warnOnce("periodic-notasks:" + summonId + ":" + abilityName(ability),
                    "[IC] 周期能力 " + abilityName(ability) + " 不启动：找不到本局的生命周期任务表"
                            + "（matchId=" + matchId + "，卡 " + cardId + "）"
                            + " —— 周期任务绝不允许挂在无人回收的地方");
            return;
        }
        LifecycleTasks tasks = match.tasks();
        String abilityId = ability.id();
        // ★ 先取消旧的（重入 ⇒ 旧任务被替换，绝不让同一个召唤物跑两个周期任务）
        if (schedule.cancel(summonId, abilityId)) {
            log().info("[IC] 周期能力重入：旧任务已取消（实体 " + summonId + "，能力 "
                    + abilityId + "）—— 同一个召唤物只允许一个周期任务");
        }
        UUID ownerSnapshot = owner;
        Side sideSnapshot = side;
        // ★ 生命周期任务表在触发时就取好（周期回调里 match 可能已经收尾 ⇒ 不再重新查）
        LifecycleTasks periodicTasks = match == null ? null : match.tasks();
        Runnable action = () -> {
            try {
                LivingEntity current = livingOf(summonId);
                if (current == null) {
                    // 实体已不在 / 不是生物 ⇒ 取消这个任务（不留在表里空转）
                    warnOnce("periodic-gone:" + summonId + ":" + abilityId,
                            "[IC] 周期能力 " + abilityId + " 停止：实体 " + summonId
                                    + " 已不在（卡 " + cardId + "）");
                    schedule.cancel(summonId, abilityId);
                    return;
                }
                runAbilityWithTasks(ability, cardId, matchId, sideSnapshot, ownerSnapshot, current,
                        current.getLocation(), periodicTasks, attackTargetOf(current));
            } catch (Throwable t) {
                warnOnce("periodic-run:" + summonId + ":" + abilityId,
                        "[IC] 周期能力 " + abilityId + " 一次触发失败（实体 " + summonId + "）：" + t);
            }
        };
        // ★ 登记表（LifecycleTasks）负责"对局结束"；本类的 Schedule 负责
        //   "同一召唤物只起一个" + stop() 的显式取消。
        tasks.runTimer(plugin, action, cooldown, cooldown);
        schedule.schedule(summonId, abilityId, action);
        log().info("[IC] 周期能力启动：" + abilityId + "｜实体 " + summonId + "｜每 "
                + cooldown + " tick｜卡 " + cardId + "（已登记到对局生命周期）");
    }

    /**
     * 把一个能力的全部 effects 施加出去（单条失败 ⇒ 告警并继续下一条）。
     *
     * <p>★ 2026-10-17 批次 3 后半：目标由 {@link #effectTargetsFor} 解析成
     * <b>一个集合</b>（没有 {@code radius} 时集合里就一个元素）⇒ 同一个效果会被
     * 逐个施加到集合里的每一个目标上。返回值 = <b>是否至少成功作用于一个目标</b>。
     */
    private boolean runAbility(Card.Ability ability, String cardId, String matchId, Side side,
                               UUID owner, LivingEntity summon, Location origin) {
        return runAbilityWithTasks(ability, cardId, matchId, side, owner, summon, origin, null, null);
    }

    /** 同上，并可显式提供本局的生命周期任务表（{@code SUMMON_DELAYED} / {@code PUSH} 要用）。 */
    private boolean runAbilityWithTasks(Card.Ability ability, String cardId, String matchId, Side side,
                                       UUID owner, LivingEntity summon, Location origin,
                                       LifecycleTasks explicitTasks, LivingEntity attackTarget) {
        List<Spell.Effect> effects = ability.effects();
        Side resolved = resolveSide(side, owner, matchId);
        // ★ 本场对局运行时：范围候选要从它的账本里取（铁律 10）
        MatchService match = MatchService.get(matchId);
        boolean anyApplied = false;
        for (Spell.Effect effect : effects) {
            if (effect == null || effect.type() == null) {
                warnOnce("effect-null:" + summon.getUniqueId() + ":" + abilityName(ability),
                        "[IC] 能力 " + abilityName(ability) + " 里有一条效果为 null / 类型为 null"
                                + "（卡 " + cardId + "），跳过这一条");
                continue;
            }
            List<LivingEntity> targets = effectTargetsFor(ability, effect, summon, owner,
                    matchId, match, origin, attackTarget);
            if (targets.isEmpty()) {
                continue;   // effectTargetsFor 已经按失败方向告警过（不在这里重复刷屏）
            }
            for (LivingEntity target : targets) {
                if (!EffectApplier.appliesTo(effect.type(), target)) {
                    continue;   // 例如给亡灵治疗：候选阶段就筛掉，不告警（EffectApplier 的口径）
                }
                try {
                    EffectApplier.Context context = new EffectApplier.Context(plugin, matchId, resolved,
                            owner, cardId, 0, new AppliedEffects());
                    // ★★★ 2026-10-18（用户第 2 条更正）：**能力侧不再"发射火球实体"**。
                    //   死灵法师那条能力的正解是「在目标处使用一次火球术」——
                    //   火球术本身是**半径 2 的 AoE**（docs/19 第 71 行），
                    //   根本没有「召唤一颗火球飞过去」这一步。
                    //   ⇒ 旧实现（projectile: FIREBALL → `launchFireball`）已删除；
                    //     卡里若还写着 `projectile`，只告警一次（不再静默「看起来有火球」）。
                    if (isFireballProjectile(effect)) {
                        warnOnce("ability-projectile:" + summon.getUniqueId() + ":" + abilityName(ability),
                                "[IC] 能力 " + abilityName(ability) + " 的 DAMAGE 上写了 projectile: "
                                        + "FIREBALL，但能力侧不支持发射弹射物"
                                        + "（2026-10-18 起删除；「在目标处放一次火球术」是 AoE，"
                                        + "不是飞一颗火球），这一格被忽略（伤害照常按 amount 结算）");
                    }
                    // ★★★ 2026-10-19（怨怼轰炸机亡语）：**能力侧现在真的能嵌套施放**了。
                    //   过去这里写死 `null`，注释原话是「abilities 里的 CAST_SPELL 会被
                    //   EffectApplier 告警拒绝（嵌套施放需要整条施放链，能力侧没有）」——
                    //   而施放链其实**就在**插件上（`plugin.spellCaster()`），缺的只是这一行接线。
                    //   ★ 用户原话：「现在已经有嵌套执行的实现（类似 execute 指令），
                    //     可以在死亡处释放法术。最初版的回响法术就是用来测试这个的」。
                    //   ★ 施放者 = 这个召唤物的**召唤者**（仍然活着的那位玩家）；
                    //     召唤者下线 / 未知 ⇒ `nestCaster` 为 null ⇒ EffectApplier 照旧
                    //     告警并拒绝（**绝不**退回"打自己"那种替身行为）。
                    Runnable nestCaster = nestCasterFor(effect, owner, matchId, resolved, origin);
                    if (EffectApplier.apply(effect, context, target, origin, nestCaster, explicitTasks)) {
                        anyApplied = true;
                    }
                } catch (IllegalArgumentException badData) {
                    // 卡牌数据的错误：告警一次，不打断整场（理由见类 javadoc 的失败方向）
                    warnOnce("data:" + summon.getUniqueId() + ":" + abilityName(ability)
                                    + ":" + effect.type(),
                            "[IC] 能力 " + abilityName(ability) + " 的 " + effect.type()
                                    + " 参数非法，已跳过（卡 " + cardId + "）：" + badData.getMessage());
                } catch (Throwable t) {
                    warnOnce("apply:" + summon.getUniqueId() + ":" + abilityName(ability)
                                    + ":" + effect.type(),
                            "[IC] 能力 " + abilityName(ability) + " 的 " + effect.type()
                                    + " 施加失败，已跳过（卡 " + cardId + "）：" + t);
                }
            }
        }
        return anyApplied;
    }

    // ================================================================== 内部：编排

    /**
     * ★★★ <b>能力侧的嵌套施放器</b>（`abilities[].effects[].type: CAST_SPELL`）——
     * 2026-10-19 新增。
     *
     * <h2>它解决什么</h2>
     * 怨怼轰炸机的亡语 = 「**在死亡处**施放一次『定点爆破』」。实现上就是
     * `trigger: ON_DEATH` ＋ `effects: [{ type: CAST_SPELL, spell: demolition }]`；
     * 而 {@link EffectApplier#castSpell} 只做三件事：查深度、查有没有施放器、调用它。
     * 过去这里传的是 {@code null} ⇒ 每条 CAST_SPELL 都被**告警拒绝**（能装载、不生效）。
     *
     * <h2>口径（三条，都要能对账）</h2>
     * <ol>
     *   <li><b>施放者 = 召唤者</b>（{@code owner}）。施放链是玩家维度的
     *       （{@code SpellCaster#cast(Player …)}：要玩家朝向、要扣费口径之外的上下文），
     *       ★ 而<b>不扣这个玩家的费用/冷却</b> —— 亡语是卡牌自带的，不是他打出的牌；</li>
     *   <li><b>落点 = 触发位置</b>（{@code origin}；ON_DEATH 时由
     *       {@link #dispatch} 传死亡位置）⇒ 「在死亡处释放」；</li>
     *   <li><b>召唤者不在线 / 拿不到施放链 ⇒ 返回 {@code null}</b>，
     *       于是 {@code EffectApplier} 照旧告警并拒绝。★ 绝不退回"用施放者自己当落点"
     *       那种替身行为（那正是 2026-10-17 修掉的火球打自己）。</li>
     * </ol>
     * ★ 深度限 1（【Q26】）仍然由 {@code EffectApplier#castSpell} 守着：这里传的
     * {@code depth} 是**顶层**（0），被嵌套的那次施放以 depth+1 走完整条链。
     */
    private Runnable nestCasterFor(Spell.Effect effect, UUID owner, String matchId, Side side,
                                   Location origin) {
        if (effect == null || effect.type() != Spell.EffectType.CAST_SPELL) {
            return null;   // 只有 CAST_SPELL 用得上（其余效果传 null 与以前一模一样）
        }
        // ★ 施放链挂在**插件门面**上（`InfernalClashPlugin#spellCaster()`）；本类只持有
        //   {@link Plugin}（为了让离线夹具能塞假插件）⇒ 这里显式判一次类型。
        //   ★ 拿不到（离线夹具 / 别的插件实例）⇒ 返回 null，让 EffectApplier 告警拒绝 ——
        //     **绝不**自己 new 一个 SpellCaster（它要注册表与召唤器，构造错了更糟）。
        io.mczju.infernalclash.spell.SpellCaster caster =
                plugin instanceof io.mczju.infernalclash.InfernalClashPlugin facade
                        ? facade.spellCaster() : null;
        if (caster == null) {
            return null;   // 没接线 ⇒ 让 EffectApplier 告警（不假装支持）
        }
        return () -> {
            Player summoner = owner == null ? null : Bukkit.getPlayer(owner);
            if (summoner == null) {
                log().warning("[IC] 能力里的嵌套施放跳过：召唤者 " + owner + " 不在线"
                        + "（matchId=" + matchId + "）—— 不会退回到别人身上施放");
                return;
            }
            caster.castNested(effect, summoner, matchId, side, origin, 0);
        };
    }

    /**
     * ★ <b>这条效果是不是「先飞一颗火球」</b>（{@code projectile: FIREBALL}）。
     *
     * <p>与 {@code EffectApplier#wantsProjectile} 同一个键名 ——
     * ★ 两处都判它，因为**两条入口的施放者不同**：
     * 玩家施放由 {@code EffectApplier} 放（那里有 {@code Player} 上下文），
     * 能力触发由本类放（这里只有召唤物实体，没有玩家）。
     * ⇒ 若将来加第三种投射物，两处都要看一遍（这条注释就是提醒）。
     */
    private static boolean isFireballProjectile(Spell.Effect effect) {
        if (effect == null || effect.params() == null) {
            return false;
        }
        Object value = effect.params().get("projectile");
        return value instanceof String text && text.equalsIgnoreCase("FIREBALL");
    }

    /**
     * ★ <b>本运行时的单位登记</b>：召唤物 UUID → 它的能力块（保序 {@link LinkedHashMap}）。
     *
     * <h2>为什么必须有它</h2>
     * {@link #onHurt} / {@link #onAttack} / {@link #onDeath} 只拿到一个
     * {@link Entity}，而「这个实体声明了哪些 abilities」只有召唤它的那一刻知道
     * （{@link #onSummonSpawn} 手上有 {@link Card}）。
     * {@link UnitRegistry} 的 PDC 里目前只写 matchId / side / owner / taunt，
     * <b>没有卡 id</b> ⇒ 事件回调侧无法反查 ⇒ 这几条触发器会静默失效。
     * <p>⇒ 本类自己留一份「召唤时报备」的索引；它同时充当**归属判据**
     * （只对报备过的实体生效，从不查世界）。
     * 生命周期：{@link #onDeath} 摘一条，{@link #stop} 清空全表。
     *
     * <p>⚠️ 已知边界：实体被对局清场（{@link UnitRegistry#cleanup}）删除时
     * <b>没有</b>回调本类 ⇒ 表里会留下少量死条目，直到 {@link #stop}。
     * 这是有意的取舍：宁可留一条无用登记，也不去查世界做"清理"（铁律 10）。
     */
    private final Map<UUID, Registered> registry = new LinkedHashMap<>();

    /** 一条报备：这张卡（在哪个对局）为这个实体声明了这些能力。 */
    private record Registered(String matchId, String cardId, List<Card.Ability> abilities) {
    }

    /**
     * 报备「这个召唤物身上有这些能力」（由 {@link #onSummonSpawn} 调用）。
     *
     * <p>★ 同一个实体重复报备 ⇒ <b>覆盖</b>（后一次是权威的：卡没变、表也不该变长）。
     */
    private void remember(Entity summon, Card card, String matchId, List<Card.Ability> abilities) {
        if (summon == null || abilities.isEmpty()) {
            return;
        }
        registry.put(summon.getUniqueId(),
                new Registered(matchId, cardId(card), List.copyOf(abilities)));
    }

    /** 摘掉一个实体的报备（{@link #onDeath} 用）。 */
    private void forget(UUID entityId) {
        registry.remove(entityId);
    }

    /** 该实体在本场报备过的能力（没有 ⇒ 空表，<b>不猜</b>）。 */
    private List<Card.Ability> abilitiesFor(Entity entity, String matchId) {
        if (entity == null) {
            return List.of();
        }
        Registered registered = registry.get(entity.getUniqueId());
        if (registered == null) {
            return List.of();
        }
        if (matchId != null && !matchId.equals(registered.matchId())) {
            // ★ 不属于本场 ⇒ 跳过（本类只对已报备且属于本场的单位生效）
            return List.of();
        }
        return registered.abilities();
    }

    /** 该实体报备的卡 id（没有 ⇒ {@code null}）。 */
    private String cardIdFor(Entity entity) {
        Registered registered = entity == null ? null : registry.get(entity.getUniqueId());
        return registered == null ? null : registered.cardId();
    }

    /** 一张召唤卡声明的 abilities（无 ⇒ 空表，绝不因此抛）。 */
    private static List<Card.Ability> abilitiesOf(Card card) {
        if (card == null || card.summon() == null || card.summon().abilities() == null) {
            return List.of();
        }
        return card.summon().abilities();
    }

    /**
     * 事件触发（受伤 / 攻击命中 / 死亡）的统一派发：把 {@code trigger} 对应的 effects
     * 施加到<b>该实体自己</b>。
     *
     * @param origin 效果源点（{@code null} ⇒ 用实体当前位置；死亡时由调用方给死亡位置）
     */
    private void dispatch(Entity entity, String matchId, MatchService match,
                          Card.Trigger trigger, String label, Location origin) {
        if (!(entity instanceof LivingEntity living)) {
            warnOnce(label + "-notliving:" + entity.getUniqueId(),
                    "[IC] " + label + "能力跳过：" + entity.getType() + " 不是生物");
            return;
        }
        UUID entityId = entity.getUniqueId();
        List<Card.Ability> abilities = abilitiesFor(entity, matchId);
        if (abilities.isEmpty()) {
            return;   // 不是本场报备过的单位 ⇒ 静默跳过（这是常态，不该刷告警）
        }
        String cardId = cardIdFor(entity);
        String label2 = cardId == null ? "<未报备卡>" : cardId;
        // ★ 这个实体此刻锁定的目标（可能为 null）—— `center: ATTACK_TARGET` 要用它。
        //   只有 ON_ATTACK 才取：那时候"刚刚命中的那个"就是它当前的目标；
        //   受伤 / 死亡时取它会得到"打我的人"或 null，两种都不是这两类能力的语义。
        //   ★ 只在需要时才读（`getTarget` 是实体属性读取，对每种 Mob 都成立）。
        LivingEntity attackTarget = trigger == Card.Trigger.ON_ATTACK ? attackTargetOf(living) : null;
        for (int index = 0; index < abilities.size(); index++) {
            Card.Ability ability = abilities.get(index);
            try {
                if (!castAbilityTarget(label2, index, ability, trigger)) {
                    continue;
                }
                Location where = origin != null ? origin : living.getLocation();
                runAbilityWithTasks(ability, label2, matchId, sideOf(entity, matchId, match),
                        ownerOf(entity, matchId, match), living, where, null, attackTarget);
            } catch (Throwable t) {
                warnOnce(label + ":" + entityId + ":" + abilityName(ability),
                        "[IC] " + label + "能力 " + abilityName(ability) + " 失败（实体 "
                                + entityId + "）：" + t);
            }
        }
    }

    private Side sideOf(Entity entity, String matchId, MatchService match) {
        UUID owner = ownerOf(entity, matchId, match);
        if (owner != null && match != null) {
            try {
                Side side = match.sideOf(owner);
                if (side != null) {
                    return side;
                }
            } catch (Throwable t) {
                warnOnce("side:" + entity.getUniqueId(),
                        "[IC] 查阵营失败（实体 " + entity.getUniqueId() + "）：" + t);
            }
        }
        return null;
    }

    /** 召唤者 UUID：优先问账本的 PDC（权威），拿不到就 {@code null}（不猜）。 */
    private UUID ownerOf(Entity entity, String matchId, MatchService match) {
        UnitRegistry units = match == null ? null : match.units();
        if (units == null || entity == null) {
            return null;
        }
        try {
            OwnershipTable.Owner owner = units.ownerFromPdc(entity);
            if (owner == null) {
                return null;
            }
            if (matchId != null && !matchId.equals(owner.matchId())) {
                return null;   // ★ PDC 里的归属与本场不一致 ⇒ 不认
            }
            return owner.player();
        } catch (Throwable t) {
            warnOnce("owner:" + entity.getUniqueId(),
                    "[IC] 读归属失败（实体 " + entity.getUniqueId() + "）：" + t);
            return null;
        }
    }

    /** 效果上下文里的阵营：调用方给的与账本查到的互补（都没有 ⇒ {@code null}，由 EffectApplier 告警）。 */
    private static Side resolveSide(Side side, UUID owner, String matchId) {
        if (side != null) {
            return side;
        }
        if (owner == null) {
            return null;
        }
        MatchService service = MatchService.get(matchId);
        return service == null ? null : service.sideOf(owner);
    }

    /** 目标解析：召唤者用 {@link Bukkit#getPlayer(UUID)}（不在线 ⇒ {@code null} ⇒ 跳过）。 */
    private static LivingEntity resolveTarget(UUID targetId, LivingEntity summon) {
        if (targetId == null) {
            return null;
        }
        if (summon != null && targetId.equals(summon.getUniqueId())) {
            return summon;   // 自己：直接用传进来的引用（不绕一圈点查）
        }
        Player player = Bukkit.getPlayer(targetId);
        if (player != null && player.isOnline()) {
            return player;
        }
        Entity entity = resolveEntity(targetId);
        return entity instanceof LivingEntity living ? living : null;
    }

    /** ★ 按 UUID 找回实体 —— O(1) 点查，不是"枚举整个世界再过滤"（铁律 10）。 */
    private static Entity resolveEntity(UUID id) {
        return Bukkit.getEntity(id);
    }

    /** 按 id 找回生物（已失效 / 不是生物 ⇒ {@code null}）。 */
    private static LivingEntity livingOf(UUID id) {
        Entity entity = resolveEntity(id);
        if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
            return living;
        }
        return null;
    }

    /**
     * ★★ <b>这个单位此刻锁定的目标</b>（拿不到 / 不是 {@code Mob} / 已失效 ⇒ {@code null}）。
     *
     * <h2>为什么必须有这一格（而且只能是它）</h2>
     * {@code docs/19} 第 174 行对冰术师写的是「每 2s 对<b>目标</b>及其周围 1 格内敌人造成 4 点
     * 范围伤害 + 缓慢 II」—— 那个「目标」<b>不是</b>冰术师自己，而是<b>它当前瞄的那个敌人</b>。
     * 少了这一格，「目标及其周围 1 格」就只能被近似成「自己周围 1 格」（= 打空，因为冰术师
     * 站在自家前排，对面根本不在 1 格内）。
     *
     * <h2>★ 用 API 实测确认过，不是猜的</h2>
     * {@code javap -classpath paper-api-26.2.build.133-stable.jar org.bukkit.entity.Mob} 实测：
     * {@code public abstract org.bukkit.entity.LivingEntity getTarget();} /
     * {@code setTarget(LivingEntity)} —— 在 {@code Mob} 上（<b>不在</b>
     * {@code LivingEntity} 上，所以这里必须先 {@code instanceof Mob}）。
     *
     * <h2>★ 铁律 10</h2>
     * 这只是读一个实体自己的字段（{@code O(1)}），<b>不是</b>
     * {@code getNearbyEntities} / {@code getEntities} 那种范围枚举。
     * 而且它只被 {@code center: ATTACK_TARGET} 那一档用到 ⇒ 不写这个键的卡连读都不读。
     */
    private static LivingEntity attackTargetOf(LivingEntity entity) {
        if (!(entity instanceof org.bukkit.entity.Mob mob)) {
            return null;
        }
        try {
            LivingEntity target = mob.getTarget();
            if (target == null || !target.isValid() || target.isDead()) {
                return null;
            }
            return target;
        } catch (Throwable ignored) {
            // 某些自定义实体实现可能不支持 ⇒ 当作"没有目标"（能力静默不做，不炸整场）
            return null;
        }
    }

    private static String cardId(Card card) {
        return card == null ? "<未知卡>" : card.id();
    }

    private static String abilityName(Card.Ability ability) {
        return ability == null || ability.id() == null ? "<未知能力>" : ability.id();
    }

    private Logger log() {
        return plugin == null ? Logger.getLogger("InfernalClash") : plugin.getLogger();
    }

    /** 同一条提示只打一次（周期能力每 tick 触发，不这么做会把真正的告警淹掉）。 */
    private void warnOnce(String token, String message) {
        if (warned.putIfAbsent(token, Boolean.TRUE) == null) {
            log().warning(message);
        }
    }

    // ================================================================== 周期任务记账

    /**
     * ★ <b>周期任务记账</b>：键 = （实体 UUID, 能力 id），值 = 该任务的动作。
     *
     * <p>两条口径：
     * <ol>
     *   <li><b>同一（实体, 能力）只保留一个</b>：{@link #schedule} 重入时把旧的
     *       取消并返回（"返回旧任务被替换"）—— 否则「同一个召唤物被召唤两次」会变成
     *       两个周期任务在跑，效果翻倍；</li>
     *   <li><b>保序</b>（{@link LinkedHashMap}）：本项目对「同输入 → 同结果」有硬要求，
     *       不许靠 {@code HashMap} 的迭代运气。</li>
     * </ol>
     *
     * <p>★ 本类<b>不碰服务端</b>（{@link BukkitTask} 只是可能为 {@code null} 的句柄）
     * ⇒ 「重入先取消旧的」这条口径可以在 JUnit 里离线断言
     * （真正起任务的那一半只能实机验证）。
     */
    public static final class Schedule {

        /** 一条被登记的动作。 */
        private record Entry(Runnable action, BukkitTask task) {
        }

        private final Map<String, Entry> entries = new LinkedHashMap<>();

        /**
         * 登记（或<b>替换</b>）一个周期任务。
         *
         * @return 被替换掉的<b>旧任务句柄</b>（没有旧的 ⇒ {@code null}）；
         *         旧任务会被本方法<b>取消</b>（幂等：已取消的不再取消）
         */
        public BukkitTask schedule(UUID entity, String abilityId, Runnable action) {
            String key = key(entity, abilityId);
            Entry previous = entries.put(key, new Entry(action, null));
            if (previous == null) {
                return null;
            }
            cancelQuietly(previous.task());
            return previous.task();
        }

        /** 把已登记的动作接到一个任务句柄上（调度成功后调用）。 */
        public void attach(UUID entity, String abilityId, BukkitTask task) {
            String key = key(entity, abilityId);
            Entry current = entries.get(key);
            if (current == null) {
                return;
            }
            entries.put(key, new Entry(current.action(), task));
        }

        /** 这个（实体, 能力）当前登记的动作（没有 ⇒ {@code null}）；测试用。 */
        public Runnable action(UUID entity, String abilityId) {
            Entry entry = entries.get(key(entity, abilityId));
            return entry == null ? null : entry.action();
        }

        /** 这个（实体, 能力）登记过吗。 */
        public boolean contains(UUID entity, String abilityId) {
            return entries.containsKey(key(entity, abilityId));
        }

        /** 取消并摘掉一个（实体, 能力）。 */
        public boolean cancel(UUID entity, String abilityId) {
            Entry removed = entries.remove(key(entity, abilityId));
            if (removed == null) {
                return false;
            }
            cancelQuietly(removed.task());
            return true;
        }

        /** 当前登记数（测试与日志用）。 */
        public int activeCount() {
            return entries.size();
        }

        /**
         * 取消全部并清空（<b>幂等</b>）。
         *
         * @return 实际取消掉的数量（可对账；空表 ⇒ {@code 0}）
         */
        public int cancelAll() {
            int cancelled = 0;
            for (Entry entry : entries.values()) {
                if (cancelQuietly(entry.task())) {
                    cancelled++;
                }
            }
            int size = entries.size();
            entries.clear();
            return Math.max(cancelled, size == 0 ? 0 : cancelled);
        }

        /** 取一个不依赖 UUID 的稳定键（保序表也用它）。 */
        private static String key(UUID entity, String abilityId) {
            return (entity == null ? "<null>" : entity.toString()) + "|"
                    + (abilityId == null ? "<null>" : abilityId);
        }

        private static boolean cancelQuietly(BukkitTask task) {
            if (task == null) {
                return false;
            }
            try {
                if (!task.isCancelled()) {
                    task.cancel();
                    return true;
                }
            } catch (Throwable ignored) {
                // 关服流程里取消任务可能抛（插件已 disabled）—— 记数不记异常，绝不因此中断收尾
            }
            return false;
        }
    }
}
