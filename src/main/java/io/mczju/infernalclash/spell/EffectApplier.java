package io.mczju.infernalclash.spell;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.card.CardCatalog;
import io.mczju.infernalclash.card.Facing;
import io.mczju.infernalclash.card.MobGroupCatalog;
import io.mczju.infernalclash.card.Spell;
import io.mczju.infernalclash.card.SummonSpawner;
import io.mczju.infernalclash.config.MapReader;
import io.mczju.infernalclash.match.LifecycleTasks;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.Side;
import io.mczju.infernalclash.match.UnitRegistry;
import io.mczju.infernalclash.policy.Rarity;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ★★ <b>把一类效果「作用到一个目标上」</b>（{@code docs/07} §6.4 的 15 类，逐类口径见
 * {@code docs/04} §16.9.4 / §16.9.5）。
 *
 * <h2>职责边界（很重要）</h2>
 * 本类<b>只做「对一个目标施加一个效果」</b>：
 * <ul>
 *   <li>「选哪些目标」是形状 + 目标系统的活 —— <b>本类不查世界</b>：
 *       没有任何「按世界 / 按半径枚举实体或玩家」的调用，也没有世界级设置
 *       （{@code BytecodeScopeGuardTest} 的 8 个记号在本类里一个都不出现；铁律 10）；</li>
 *   <li>「什么时候施放」是施放链的活（{@link SpellCaster}）；本类只回答「对这个目标做成了吗」。</li>
 * </ul>
 * 所以：<b>它不是广域操作</b> —— 每一个被操作的目标都是调用方逐个递进来的
 * （唯一的例外是「按 UUID 找回实体」：{@link Bukkit#getEntity(UUID)} 是 O(1) 点查，
 * 不是"枚举整个世界再过滤"）。
 *
 * <h2>★ 绝不静默忽略（本项目的硬要求）</h2>
 * <ul>
 *   <li>缺必需键 / 键的类型不对 / 非法枚举值 ⇒ <b>抛 {@link IllegalArgumentException}</b>
 *       （消息由 {@link EffectParams} 负责写出键名与期望值）；</li>
 *   <li>「目标不适用」（给亡灵治疗、施放者不在线…）⇒ <b>告警并返回 {@code false}</b>；</li>
 *   <li>本类里<b>没有</b>「不适用就什么都不做也不说」的分支。</li>
 * </ul>
 *
 * <h2>失败方向</h2>
 * 单个目标上的一次效果失败，<b>不该炸掉整场施放</b>：{@link #apply} 内部对
 * {@link Throwable} 兜底并告警。★ 但参数错误（{@link IllegalArgumentException}）
 * <b>照常抛出</b> —— 那是卡牌数据的错误，必须让人看见（调用方可以决定是否中断整次施放）。
 *
 * <h2>依赖注入（只有 {@code SUMMON} / {@code SUMMON_DELAYED} 需要）</h2>
 * 召唤必须走 {@link SummonSpawner}（归属登记的唯一入口，铁律 10）⇒ 需要一个实例：
 * <pre>{@code
 * // 插件 onEnable 里注册一次：
 * EffectApplier.install(new SummonSpawner(plugin, mobGroups, unitRegistry));
 * }</pre>
 * 没装的话，{@code SUMMON*} 会<b>告警并返回 false</b>（而不是"悄悄不召唤"）。
 *
 * <h2>调用约定（与接线方对齐的三条）</h2>
 * <ol>
 *   <li>★ {@code SUMMON*} 的召唤中心是传进来的 {@code origin}（瞄格子时的落点），
 *       <b>不是 {@code target} 的位置</b>。只有当 {@code origin == null} 时才退回
 *       {@code target.getLocation()}。原因：「瞄远处地面召唤」时 {@code target} 很可能
 *       只是施放者本人（没有实体目标时调用方传 caster，而不是 {@code null}）。</li>
 *   <li>{@code target} 为 {@code null} 或已失效 ⇒ 除 {@code SUMMON*} 外一律返回 {@code false}；
 *       {@code SUMMON*} 仍会按 {@code origin} 召唤（那正是"没有实体目标"的常见情况）。</li>
 *   <li>{@code context.depth()} 由调用方维护：{@code CAST_SPELL} 拒绝 {@code depth >= 1}
 *       （【Q26】深度限 1），并通过注入的 {@code nestCaster} 以 {@code depth + 1} 重走整条链。</li>
 * </ol>
 */
public final class EffectApplier {

    /** 单次效果允许的最长持续时间（tick）：1 小时。超过就截断并告警（防止卡里写出天文数字）。 */
    private static final int MAX_DURATION_TICKS = 72_000;

    /** 小于这个距离（格）时，{@code origin → target} 的方向没有意义。 */
    private static final double MIN_DIRECTION_DISTANCE = 1.0e-4;

    /** {@code group} 段允许的键（键名与 {@code docs/03} §4.3 的召唤卡一致）。 */
    private static final Set<String> GROUP_KEYS =
            Set.of("entity", "count", "offset", "spread", "attributes", "taunt", "equipment");

    /** 各类效果**允许**的参数键 —— 用于「未知键 ⇒ 报错」（{@code taunt-level} 那类静默失效的克星）。 */
    private static final Map<Spell.EffectType, Set<String>> ALLOWED = allowedKeys();

    /** 同一条提示只打一次（避免每 tick 刷屏把真正的告警淹掉）。 */
    private static final Set<String> WARNED_ONCE = ConcurrentHashMap.newKeySet();

    /**
     * ★ <b>冷却缩减窗口表</b>（{@code COOLDOWN_REDUCTION}，【Q10】通道①）。
     *
     * <p>【AI 补充】<b>可否决</b>：原文只有「10s 内本玩家卡牌冷却 −50%」，<b>没说</b>
     * 已有的冷却算不算。本实现取「<b>只影响窗口内「新施加」的冷却</b>」——
     * 因为原版遮罩一旦施加就无法按比例缩短（只能整条清除），"改变已有冷却"实际上会
     * 变成"清掉冷却"，语义更强、也更容易被抱怨。
     *
     * <p>键 = 玩家 UUID；值 = {@code {缩减比例, 窗口到期的毫秒时刻}}（保序 {@link LinkedHashMap}）。
     * 到期判断在 {@link #activeReduction(UUID)} / {@link #purgeExpiredWindows(long)} 里做，
     * 并用 {@link MatchService#tasks()} 挂一个到点清除的任务 ⇒ <b>到点自动失效</b>。
     * ⚠️ 只允许在主线程读写（施放链与 Bukkit 定时器都在主线程）。
     */
    private static final Map<UUID, Window> REDUCTION_WINDOWS = new LinkedHashMap<>();

    /** 一个冷却缩减窗口。 */
    private record Window(double ratio, long expireAtMillis) {
    }

    /** 全局召唤登记入口（由 {@link #install(SummonSpawner)} 设置）。 */
    private static volatile SummonSpawner spawner;

    private EffectApplier() {
    }

    /** ★ 注册全局召唤登记入口（插件 {@code onEnable} 里调一次）。 */
    public static void install(SummonSpawner summonSpawner) {
        spawner = summonSpawner;
    }

    /** 当前的全局召唤登记入口（没装则 {@code null}）。 */
    public static SummonSpawner installed() {
        return spawner;
    }

    /** 清空冷却缩减窗口表（插件停用 / 单测用）—— 否则重载插件会留下"幽灵窗口"。 */
    public static void clearReductionWindows() {
        REDUCTION_WINDOWS.clear();
    }

    /** 清空「只告警一次」的记录（插件停用 / 单测用）。 */
    public static void clearWarnOnce() {
        WARNED_ONCE.clear();
    }

    /**
     * ★ <b>该玩家当前生效的冷却缩减比例</b>（窗口外一律返回 {@code 0}）。
     *
     * <p>接线方（施加卡牌冷却的那一处）在算实际冷却时读它：
     * <pre>{@code
     * double ratio = EffectApplier.activeReduction(playerId);              // 0.0 ~ 1.0
     * int ticks = (int) Math.round(card.cooldownTicks() * (1.0 - ratio));
     * player.setCooldown(Key.key("infernalclash:" + card.id()), ticks);
     * }</pre>
     *
     * <p>★ 只影响窗口内**新施加**的冷却（【AI 补充】可否决，理由见 {@link #REDUCTION_WINDOWS}）。
     *
     * @return 比例（{@code 0.5} = 冷却减半）；没有窗口 / 已过期 / 玩家为 {@code null} ⇒ {@code 0}
     */
    public static double activeReduction(UUID player) {
        return activeReduction(player, System.currentTimeMillis());
    }

    /** 同上，但由调用方给「现在」（便于单测与日志对齐）。 */
    public static double activeReduction(UUID player, long nowMillis) {
        if (player == null) {
            return 0.0;
        }
        Window window = REDUCTION_WINDOWS.get(player);
        if (window == null || nowMillis >= window.expireAtMillis()) {
            return 0.0;
        }
        return window.ratio();
    }

    /** 清掉所有已过期的窗口，返回清掉的个数（可对账；也防止表随时间只增不减）。 */
    public static int purgeExpiredWindows(long nowMillis) {
        int removed = 0;
        var iterator = REDUCTION_WINDOWS.entrySet().iterator();
        while (iterator.hasNext()) {
            if (nowMillis >= iterator.next().getValue().expireAtMillis()) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /** 该效果类型**允许**的参数键（供接线方做装载期校验；只读）。 */
    public static Set<String> allowedKeys(Spell.EffectType type) {
        return ALLOWED.getOrDefault(type, Set.of());
    }

    /** {@code group} 段允许的键（供接线方做装载期校验；只读）。 */
    public static Set<String> groupKeys() {
        return GROUP_KEYS;
    }

    // ------------------------------------------------------------------ 候选筛选

    /**
     * 能应用到这类实体吗（用于候选筛选）。
     *
     * <p>规则刻意**保守**：只有「确定不适用」才返回 {@code false}，其余一律 {@code true}
     * 并把最终判断交给 {@link #apply}（它会给出带理由的告警）。这样不会出现
     * 「候选阶段悄悄筛掉 ⇒ 效果看起来没生效」。
     *
     * <ul>
     *   <li>非 {@link LivingEntity}（展示实体 / 掉落物 / 弹射物）⇒ {@code false}；</li>
     *   <li>其余一律 {@code true}（★ 包括亡灵 —— 见下方那条 2026-10-18 更正）。</li>
     * </ul>
     *
     * <h2>★★★ 2026-10-18 更正：{@code HEAL} 不再排除亡灵</h2>
     * 原来这里（以及 {@code heal(...)} 里）写着「{@code HEAL} 对亡灵 ⇒ {@code false}：
     * 原版里治疗会伤害亡灵」。<b>那条规则对的是原版的 {@code INSTANT_HEALTH} 药水效果</b>，
     * 而本项目的 {@code HEAL} 是 {@code LivingEntity#setHealth(...)}（直接改生命值）
     * ⇒ 对亡灵的语义**就是治疗**，没有任何"反过来伤害"的问题。
     *
     * <p>它造成的真实后果：本项目召唤物**大多数是亡灵**（僵尸 / 骷髅 / 尸壳 / 凋灵骷髅…），
     * 而治愈术是唯一的治疗手段 ⇒ 玩家对着自己的僵尸放治愈术，候选在
     * {@link #appliesTo} 这一格就被<b>静默</b>筛掉（连一行日志都没有）
     * ⇒ 现场表现就是用户报的「**治愈术根本没有效果**」。
     *
     * <p>★ 失败方向：宁可治疗一个亡灵，也不要让"治疗"在最需要它的单位上静默失效。
     * ★ 反过来说：将来若真要做「原版 {@code INSTANT_HEALTH} 那种反伤亡灵」的法术，
     * 它应当是**另一个效果类型**（伤害），而不是把这条错误规则搬回来。
     */
    public static boolean appliesTo(Spell.EffectType type, Entity target) {
        if (type == null || !(target instanceof LivingEntity)) {
            return false;
        }
        return true;
    }

    // ★ 2026-10-18：原来的 `isUndead(...)` 已随「HEAL 不再排除亡灵」一起删除 ——
    //   它是那条错误规则的唯一用途。将来若要判亡灵，用 `Tag.ENTITY_TYPES_UNDEAD` 现判即可，
    //   而**不要**把它接回治疗路径（理由见 appliesTo 的 javadoc）。

    // ------------------------------------------------------------------ 应用

    /**
     * 对**一个**目标应用一个效果（★ 主入口）。
     *
     * @param effect     效果（来自卡牌 YAML）
     * @param context    施放上下文（谁放的 / 哪张卡 / 哪一方 / 嵌套深度 / 本次施放的账本）
     * @param target     目标实体；{@code null} 时除 {@code SUMMON*} 外一律不做
     * @param origin     效果源点（瞄格子时的落点）；★ {@code SUMMON*} 的召唤中心就是它
     * @param nestCaster 嵌套施放器（{@code CAST_SPELL} 用；由调用方注入，会用 {@code depth + 1}
     *                   重走整条施放链）。{@code null} ⇒ 嵌套被拒绝并告警（不是静默跳过）
     * @return 是否真的做了事（{@code false} = 该目标不适用，例如给亡灵治疗）
     */
    public static boolean apply(Spell.Effect effect, Context context, LivingEntity target,
                                Location origin, Runnable nestCaster) {
        return apply(effect, context, target, origin, nestCaster, null);
    }

    /**
     * 同上，并显式提供**本次施放的生命周期任务登记表**（延时/周期任务必须挂它，否则
     * 下一局会凭空冒出召唤物 —— {@code docs/07} §6.4）。
     *
     * <p>没有显式提供时，本方法会用 {@code context.matchId()} 去
     * {@link MatchService#get(String)} 找；再找不到 ⇒ 告警并<b>不调度</b>
     * （宁可不做，也不把任务挂在无人回收的地方）。
     */
    public static boolean apply(Spell.Effect effect, Context context, LivingEntity target,
                                Location origin, Runnable nestCaster, LifecycleTasks explicitTasks) {
        if (effect == null || effect.type() == null) {
            throw new IllegalArgumentException("EffectApplier.apply：效果或效果类型为 null"
                    + "（这属于编码错误，不是卡牌数据错误）");
        }
        if (context == null) {
            throw new IllegalArgumentException("EffectApplier.apply：施放上下文为 null（无法确定归属）");
        }
        Logger log = loggerOf(context.plugin());

        try {
            // ★ 未知键一律报错（指名道姓），而不是让 `taunt-level` 那类键一路静默到运行期
            EffectParams.rejectUnknown(effect, ALLOWED.getOrDefault(effect.type(), Set.of()));

            return switch (effect.type()) {
                case DAMAGE -> damage(effect, context, target, log);
                case HEAL -> heal(effect, target, log);
                case POTION -> potion(effect, target, log);
                case ATTRIBUTE -> attribute(effect, context, target, explicitTasks, log);
                case SUMMON -> summon(effect, context, target, origin, false, explicitTasks, log);
                case SUMMON_DELAYED -> summon(effect, context, target, origin, true, explicitTasks, log);
                case SUMMON_PERIODICALLY ->
                        summonPeriodically(effect, context, target, origin, explicitTasks, log);
                case CAST_SPELL -> castSpell(effect, context, nestCaster, log);
                case STUN -> stun(effect, context, target, explicitTasks, log);
                case IGNITE -> ignite(effect, target, log);
                case KNOCKBACK -> knockback(effect, target, origin, log);
                case PUSH -> push(effect, context, target, origin, explicitTasks, log);
                case REFRESH_COOLDOWN -> refreshCooldown(context, log);
                case GRANT_XP -> grantXp(effect, context, log);
                case REVIVE -> revive(effect, context, target, log);
                case COOLDOWN_REDUCTION -> cooldownReduction(effect, context, explicitTasks, log);
                // ★★★ 2026-10-21（唤魔者）：在**目标脚下**召唤原版「尖牙」实体。
                case SPAWN_FANGS -> spawnFangs(context, target, origin, log);
                // ★★★ 1.1.0（大旋风）：朝 origin 牵引 + 每秒伤害（**不读击退抗性**）。
                case PULL -> pull(effect, context, target, origin, explicitTasks, log);
                // ★★★ 1.1.0（凋灵领域）：持续区域伤害（一次施放只跑一份任务）。
                case AREA_DAMAGE -> areaDamage(effect, context, origin, explicitTasks, log);
                // ★★★ 1.1.0（囚笼编织者）：在目标脚下结网（到期 / 收尾都会还原）。
                case PLACE_WEB -> placeWeb(effect, context, target, explicitTasks, log);
                // ★★ 1.1.0「援军」：与回响同一个套路 —— 状态在 CardCastListener。
                case REINFORCEMENT -> {
                    log.warning("[IC] 效果 REINFORCEMENT 只能由 CardCastListener 处理"
                            + "（它要读 CardCooldowns 并清客户端遮罩），卡 " + context.cardId()
                            + " 的这条效果被跳过。用法：给这张卡的 YAML 写"
                            + " spell.effects: [{ type: REINFORCEMENT }] 并让它走卡牌施放入口，"
                            + "不要在 abilities 里用它。");
                    yield false;
                }
                // ★★ 2026-10-19「回响」：复读**不在这一层结算**（它要过落点 / 半场 / 召唤生成，
                //   那些都在 `game/CardCastListener`）。`CardCastListener` 会在**进法术管线之前**
                //   就把它接住 ⇒ 正常路径永远到不了这里。
                //   ★ 真到了这里（有人给别的卡写了这个效果）⇒ **明确告警 + 跳过**，
                //     绝不静默（静默 = "配了却没生效"，本项目最反感的形态）。
                case REPLAY_LAST_CARD -> {
                    log.warning("[IC] 效果 REPLAY_LAST_CARD 只能由 CardCastListener 处理"
                            + "（它需要落点与召唤生成），卡 " + context.cardId()
                            + " 的这条效果被跳过。用法：给这张卡的 YAML 写"
                            + " spell.effects: [{ type: REPLAY_LAST_CARD }] 并让它走卡牌施放入口，"
                            + "不要在 abilities 里用它。");
                    yield false;
                }
            };
        } catch (IllegalArgumentException badData) {
            // ★ 卡牌数据的错误必须让人看见（绝不吞）—— 由调用方决定是否中断整次施放
            throw badData;
        } catch (Throwable t) {
            // 一次效果失败不该炸掉整场施放
            log.warning("[IC] 效果 " + effect.type() + " 应用失败（目标 "
                    + describeEntity(target) + "，卡 " + context.cardId() + "）：" + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 逐类

    /** {@code DAMAGE}：{@code target.damage(amount, caster 实体)}；{@code damage-type} 不支持但会警告一次。 */
    private static boolean damage(Spell.Effect effect, Context context, LivingEntity target, Logger log) {
        double amount = EffectParams.requireNumber(effect, "amount");
        amount = adjustedDamage(effect, target, amount, log);
        if (!alive(target)) {
            log.warning("[IC] 伤害跳过：目标已失效/已死亡（卡 " + context.cardId() + "）");
            return false;
        }
        // ★ docs/04 §16.9.6：Bukkit 不能直接指定 DamageCause ⇒ 接受该键但不实现，
        //   并且**必须说一次**（静默忽略是最糟的失败方式）
        if (containsKey(effect, "damage-type")) {
            warnOnce("damage-type", log, "[IC] damage-type 暂不支持（Bukkit 不能指定 DamageCause）"
                    + " —— 该键被接受但不会生效，伤害按默认 DamageCause 结算");
        }
        Player caster = resolveCasterPlayer(context);
        if (caster == null) {
            target.damage(amount);
            return true;
        }
        // ★★★ 2026-10-17 第十轮（缺陷 P1-a）：卡牌法术的伤害必须**能被认出来**。
        //   旧写法 target.damage(amount, caster) 在 Bukkit 里等于「玩家造成的伤害」
        //   ⇒ battle/PlayerDamageGuard（C13：玩家不得攻击战场单位）把**法术伤害也拦掉了**
        //      ⇒ 现场表现：`施放法术 arrow_rain ⇒ 命中 3 个目标 / 应用 3 次效果` 却一点伤害都没有。
        //   ⇒ 现在显式构造 DamageSource：MAGIC + 造成实体=直接实体=施放者。
        //     PlayerDamageGuard 用纯函数 battle/PlayerAttackRule 认出这一格并**放行**
        //     （而近战 PLAYER_ATTACK / 弓箭 ARROW / 投掷药水都照旧被拦）。
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.MAGIC)
                .withCausingEntity(caster)
                .withDirectEntity(caster)
                .build();
        // ★★ 2026-10-17 批次 3 收尾：`projectile: FIREBALL` —— **先飞一颗火球，再结算伤害**
        //   （出处 = 用户裁定「死灵法师改成 20 点的火球，但攻击间隔变为原来的两倍」）。
        //   ★ 只对**玩家施放者**生效（能力侧没有 Player 上下文 ⇒ 那条路自己处理，
        //     见 `AbilityRuntime#launchFireballForAbility`）。
        if (wantsProjectile(effect)) {
            launchProjectile(caster, target, log);
        }
        target.damage(amount, source);
        return true;
    }

    /**
     * ★★★ <b>{@code PULL}：把目标朝 {@code origin} 牵引并周期造成伤害</b>
     * （1.1.0「大旋风」）。
     *
     * <h2>出处（{@code docs/1.1卡牌.txt}，逐字）</h2>
     * 「将半径 6 格内的所有敌人向法术释放的位置牵引，<b>每秒造成 2 点伤害，持续 2 秒</b>」＋
     * 「<b>牵引需要能够作用于带击退抗性的召唤物</b>」。
     *
     * <h2>★ 为什么**不**调 {@code immuneToDisplacement}</h2>
     * 那正是用户点名要绕开的判据（他要求"带击退抗性的也能拉动"）。
     * 目标物（防御塔 / 信标）在配置层就被排除（{@code spell.scope: SUMMONS}），
     * 玩家另有"玩家不进账本"的结构保证 ⇒ 这里只留一道**防御性**拦玩家（C13）。
     *
     * <h2>★ 为什么每 tick 重申速度</h2>
     * 与 {@code PUSH} 同一个理由：原版每 tick 把速度往 0 收，只给一次速度推不动。
     * ★ 只写**水平**分量（保留目标自己的 Y 速度）：把飞行单位硬拽下来属于"额外玩法"，
     * 用户没要求。
     */
    private static boolean pull(Spell.Effect effect, Context context, LivingEntity target,
                                Location origin, LifecycleTasks explicitTasks, Logger log) {
        int duration = clampDuration(EffectParams.requireInt(effect, "duration-ticks"),
                "PULL 的 duration-ticks", log);
        int interval = Math.max(1, EffectParams.optionalInt(effect, "interval-ticks", 20));
        double amount = EffectParams.optionalNumber(effect, "amount", 0.0);
        double strength = EffectParams.optionalNumber(effect, "strength", 0.35);
        if (!alive(target)) {
            log.warning("[IC] 牵引跳过：目标已失效/已死亡（卡 " + context.cardId() + "）");
            return false;
        }
        if (target instanceof Player) {
            log.warning("[IC] 牵引跳过：目标是玩家（C13 / R42，卡 " + context.cardId() + "）");
            return false;
        }
        if (origin == null || origin.getWorld() == null || target.getWorld() == null
                || !origin.getWorld().equals(target.getWorld())) {
            log.warning("[IC] 牵引跳过：源点与目标不在同一个世界（卡 " + context.cardId() + "）");
            return false;
        }
        LifecycleTasks tasks = explicitTasks != null ? explicitTasks : tasksOf(context);
        Plugin plugin = context.plugin();
        if (tasks == null || plugin == null) {
            log.warning("[IC] 牵引跳过：没有可用的生命周期任务表/插件实例（卡 "
                    + context.cardId() + "）—— 周期任务绝不允许挂在无人回收的地方");
            return false;
        }
        final UUID entityId = target.getUniqueId();
        final Location center = snapshot(origin, log);
        if (center == null) {
            return false;
        }
        final int[] elapsed = {0};
        final int[] nextDamage = {interval};
        final BukkitTask[] handle = new BukkitTask[1];
        handle[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (elapsed[0]++ >= duration) {
                    handle[0].cancel();
                    return;
                }
                if (!(resolveEntity(entityId) instanceof LivingEntity current)
                        || current.isDead() || !current.isValid()) {
                    handle[0].cancel();
                    return;
                }
                Vector towards = center.toVector().subtract(current.getLocation().toVector());
                Vector horizontal = new Vector(towards.getX(), 0.0, towards.getZ());
                if (horizontal.lengthSquared() > 1.0e-6) {
                    horizontal.normalize().multiply(strength);
                    Vector velocity = current.getVelocity();
                    current.setVelocity(new Vector(horizontal.getX(), velocity.getY(),
                            horizontal.getZ()));
                }
                if (amount > 0.0 && elapsed[0] >= nextDamage[0]) {
                    nextDamage[0] += interval;
                    current.damage(amount);
                }
            } catch (Throwable t) {
                warnOnce("pull-run:" + entityId, log, "[IC] 牵引执行失败（" + entityId
                        + "）：" + t + "，这一条牵引提前结束（不影响别的目标）");
                try {
                    handle[0].cancel();
                } catch (Throwable ignored) {
                    // 已取消 ⇒ 无所谓
                }
            }
        }, 0L, 1L);
        tasks.track(handle[0]);
        return true;
    }

    /**
     * ★★★ <b>{@code AREA_DAMAGE}：持续区域伤害</b>（1.1.0「凋灵领域」）。
     *
     * <h2>出处（{@code docs/1.1卡牌.txt}，逐字）</h2>
     * 「持续 <b>8</b> 秒，<b>每秒</b>对半径 <b>5</b> 格范围内所有召唤物造成 <b>5</b> 点伤害」＋
     * 「<b>不需要任何药水效果</b>。范围使用凋零粒子环＋范围内持续生成凋零药水云，
     * 但<b>不会为敌人挂上凋零效果</b>，只造成伤害」。
     *
     * <h2>★ 为什么是"一份任务、每拍从账本重收目标"</h2>
     * 这才能表达"领域"：施放后才走进圈里的单位也会挨打。
     * 若改成"施放时圈里每个人各跑一份任务"，那就变成"只打当时那些人"，
     * 而且同一个 5 人圈会跑 5 份、伤害翻 5 倍（见 {@code EffectScope#runsOncePerCast}）。
     *
     * <h2>范围口径</h2>
     * 与其它地面法术一致：<b>水平距离（X/Z）</b>≤ {@code radius}。这样飞在头顶的空中单位
     * 只要投影在圈里就会挨打（与射线那条同一口径）。
     */
    private static boolean areaDamage(Spell.Effect effect, Context context, Location origin,
                                      LifecycleTasks explicitTasks, Logger log) {
        int duration = clampDuration(EffectParams.requireInt(effect, "duration-ticks"),
                "AREA_DAMAGE 的 duration-ticks", log);
        int interval = clampDuration(EffectParams.requireInt(effect, "interval-ticks"),
                "AREA_DAMAGE 的 interval-ticks", log);
        double amount = EffectParams.requireNumber(effect, "amount");
        int radius = EffectParams.requireInt(effect, "radius");
        UnitRegistry registry = registryOrNull();
        if (registry == null) {
            log.warning("[IC] 区域伤害跳过：没有可用的归属账本（卡 " + context.cardId()
                    + "）—— 铁律 10：绝不查世界凑候选");
            return false;
        }
        if (origin == null || origin.getWorld() == null) {
            log.warning("[IC] 区域伤害跳过：源点没有世界（卡 " + context.cardId() + "）");
            return false;
        }
        LifecycleTasks tasks = explicitTasks != null ? explicitTasks : tasksOf(context);
        Plugin plugin = context.plugin();
        if (tasks == null || plugin == null) {
            log.warning("[IC] 区域伤害跳过：没有可用的生命周期任务表/插件实例（卡 "
                    + context.cardId() + "）");
            return false;
        }
        final Location center = snapshot(origin, log);
        if (center == null) {
            return false;
        }
        final String matchId = context.matchId();
        final Side side = context.side();
        final int[] elapsed = {0};
        final BukkitTask[] handle = new BukkitTask[1];
        final java.util.Random random = new java.util.Random();
        handle[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (elapsed[0]++ >= duration) {
                    handle[0].cancel();
                    return;
                }
                spawnWitherFieldFx(center, radius, elapsed[0] % interval == 0, random, log);
                if (elapsed[0] % interval != 0) {
                    return;
                }
                int hit = 0;
                for (UUID id : registry.tracked(matchId)) {
                    OwnershipTable.Owner owner = registry.table().owner(id);
                    if (owner == null || owner.side() == null || owner.side() == side) {
                        continue;
                    }
                    Entity raw = Bukkit.getEntity(id);
                    if (!(raw instanceof LivingEntity living) || living.isDead()
                            || !living.isValid()) {
                        continue;
                    }
                    Location at = living.getLocation();
                    if (at.getWorld() == null || !at.getWorld().equals(center.getWorld())) {
                        continue;
                    }
                    double horizontal = Math.hypot(at.getX() - center.getX(),
                            at.getZ() - center.getZ());
                    if (horizontal > radius) {
                        continue;
                    }
                    living.damage(amount);
                    hit++;
                }
                log.info("[IC] 区域伤害结算（" + context.cardId() + "）：半径 " + radius
                        + " 格内命中 " + hit + " 个敌方单位，各 " + amount + " 点");
            } catch (Throwable t) {
                warnOnce("area-run:" + context.cardId(), log, "[IC] 区域伤害执行失败（卡 "
                        + context.cardId() + "）：" + t + "，这一次结算跳过");
            }
        }, 0L, 1L);
        tasks.track(handle[0]);
        return true;
    }

    /** 凋灵领域的观感：脚下药水云 + 每秒一圈白灰（纯观感，任何异常都吞掉）。 */
    private static void spawnWitherFieldFx(Location center, int radius, boolean ringBeat,
                                           java.util.Random random, Logger log) {
        try {
            org.bukkit.World world = center.getWorld();
            if (world == null) {
                return;
            }
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            world.spawnParticle(org.bukkit.Particle.NOXIOUS_GAS_CLOUD,
                    center.getX() + Math.cos(angle) * distance,
                    center.getY() + 0.4 + random.nextDouble() * 1.2,
                    center.getZ() + Math.sin(angle) * distance,
                    3, 0.2, 0.2, 0.2, 0.0);
            if (!ringBeat) {
                return;
            }
            for (int i = 0; i < 48; i++) {
                double a = (Math.PI * 2.0 / 48) * i;
                world.spawnParticle(org.bukkit.Particle.WHITE_ASH,
                        center.getX() + Math.cos(a) * radius,
                        center.getY() + 0.3,
                        center.getZ() + Math.sin(a) * radius,
                        2, 0.05, 0.05, 0.05, 0.0);
            }
        } catch (Throwable t) {
            warnOnce("area-fx", log, "[IC] 凋灵领域的粒子撒不出去（只影响观感）：" + t);
        }
    }

    /**
     * ★★★ <b>{@code PLACE_WEB}：在目标脚下结蜘蛛网</b>（1.1.0「囚笼编织者」）。
     *
     * <p>实际动作全部在 {@link WebField}（生成 → 登记 → 到期还原 / 收尾强制还原），
     * 判据（能不能替换这一格）在纯类 {@code policy/WebBlockRule}。
     */
    private static boolean placeWeb(Spell.Effect effect, Context context, LivingEntity target,
                                    LifecycleTasks explicitTasks, Logger log) {
        int duration = clampDuration(EffectParams.requireInt(effect, "duration-ticks"),
                "PLACE_WEB 的 duration-ticks", log);
        if (target == null || target.getLocation().getWorld() == null) {
            log.warning("[IC] 结网跳过：没有目标（卡 " + context.cardId() + "）");
            return false;
        }
        return WebField.place(context.matchId(), target, duration,
                explicitTasks != null ? explicitTasks : tasksOf(context), context.plugin(), log);
    }

    /**
     * ★★★ <b>伤害的两条 1.1.0 修正</b>（{@code amount} 之外的两个可选键）。
     *
     * <h2>① {@code bonus-vs-type} + {@code bonus-vs-amount}（海王 · 海龟）</h2>
     * 出处（{@code docs/1.1卡牌.txt}）：「<b>对海龟伤害 +1000 点（还原原版雷暴特性）</b>」。
     * 原版特性 = **闪电劈到海龟直接秒杀**（Wiki：{@code Thunderstorm#Turtle}）⇒
     * 我们不做"特殊逻辑"，只把伤害加上去，于是"海龟被海王打到就死"。
     * ★ 用户 2026-10-26 追问后裁定：<b>海王的所有伤害</b>（登场 AoE ＋ 周期雷击）都算
     * ⇒ 两个 {@code DAMAGE} 效果各写一份这两个键（那不是分叉，是同一张卡的两条伤害）。
     *
     * <h2>② {@code building-multiplier}（地壳震击）</h2>
     * 出处：「对 5 格范围内的目标造成 15 点伤害，<b>对建筑造成双倍伤害</b>」＋
     * 用户追加说明：「建筑指所有建筑卡牌；<b>防御塔、信标都算建筑</b>」。
     * 判据 = 账本上的 {@link UnitRegistry#building(Entity)}（它同时覆盖目标物与 6 张建筑卡），
     * <b>不在这里另起一个"是不是塔"的分支</b>。
     *
     * <p>★ 失败方向：读不到账本（离线夹具 / 探针）⇒ <b>不翻倍</b>（= 只按 {@code amount}），
     * 绝不凭空翻倍；实体类型名对不上 ⇒ 不加成（同样是"少做"而不是"多做"）。
     */
    private static double adjustedDamage(Spell.Effect effect, LivingEntity target, double amount,
                                         Logger log) {
        double value = amount;
        String bonusType = EffectParams.optionalText(effect, "bonus-vs-type", null);
        if (bonusType != null && !bonusType.isBlank() && target != null
                && target.getType().name().equalsIgnoreCase(bonusType.trim())) {
            double bonus = EffectParams.optionalNumber(effect, "bonus-vs-amount", 0.0);
            if (bonus > 0.0) {
                value += bonus;
                log.info("[IC] 伤害加成：对 " + target.getType() + " 额外 " + bonus
                        + " 点（" + amount + " → " + value + "，bonus-vs-type=" + bonusType + "）");
            }
        }
        Double multiplier = containsKey(effect, "building-multiplier")
                ? EffectParams.optionalNumber(effect, "building-multiplier", 1.0) : null;
        if (multiplier != null && multiplier != 1.0 && target != null) {
            UnitRegistry registry = registryOrNull();
            boolean building;
            try {
                building = registry != null && registry.building(target);
            } catch (Throwable t) {
                building = false;   // 读不到 ⇒ 不翻倍（失败方向 = 少做）
            }
            if (building) {
                value *= multiplier;
                log.info("[IC] 建筑加伤：目标是建筑 ⇒ " + amount + " × " + multiplier
                        + " = " + value + "（" + target.getType() + "）");
            }
        }
        return value;
    }

    /** 全局安装的召唤入口手里的账本（没装 ⇒ {@code null}）。 */
    private static UnitRegistry registryOrNull() {
        SummonSpawner installed = spawner;
        return installed == null ? null : installed.registry();
    }

    /** 这条效果要不要"先飞一颗火球"（`projectile: FIREBALL`）。 */
    private static boolean wantsProjectile(Spell.Effect effect) {        String kind = EffectParams.optionalText(effect, "projectile", null);
        return kind != null && kind.equalsIgnoreCase("FIREBALL");
    }

    /**
     * ★ <b>放一颗「纯视觉火球」</b>（{@code projectile: FIREBALL}）。
     *
     * <h2>★ 这是一颗"不造成伤害"的火球（`javap` 实测的 API）</h2>
     * {@code LargeFireball} 在 26.2 上是 {@code Fireball} → {@code Explosive}：
     * <ul>
     *   <li>{@code setYield(float)} —— 爆炸威力；<b>我们设 0</b>
     *       ⇒ 它落到地上**不炸方块、不炸人**（伤害由上面那条 {@code target.damage} 结算）；</li>
     *   <li>{@code setIsIncendiary(false)} —— 不引燃方块；</li>
     *   <li>{@code setDirection(Vector)} —— 朝目标飞。</li>
     * </ul>
     * ★ 为什么不做"用火球自己的爆炸来结算伤害"：那会让伤害值由**原版爆炸公式**决定
     * （与卡里写的 {@code amount} 不是一回事），而卡表写的是明确的点数
     * （死灵法师 20 点）⇒ 观感与数值**分开**：火球负责像不像，{@code amount} 负责打多少。
     */
    private static void launchProjectile(Player caster, LivingEntity target, Logger log) {
        try {
            org.bukkit.Location from = caster.getEyeLocation();
            if (from.getWorld() == null || target.getWorld() == null
                    || !from.getWorld().equals(target.getWorld())) {
                return;
            }
            org.bukkit.util.Vector direction =
                    target.getEyeLocation().toVector().subtract(from.toVector());
            if (direction.lengthSquared() < 1.0e-6) {
                return;
            }
            // ★★★ 2026-10-18（用户第 2 条）：玩家侧也统一成 `SmallFireball`（烈焰人火球）
            //   —— 与召唤物侧（AbilityRuntime#launchFireball）保持**同一种火球**，
            //   否则同一张卡在两条入口上会看到两种大小。仍是纯视觉（yield 0）。
            org.bukkit.entity.SmallFireball fireball =
                    from.getWorld().spawn(from, org.bukkit.entity.SmallFireball.class);
            caster.getWorld().playSound(from, "entity.blaze.shoot", 1.0f, 1.0f);
            fireball.setShooter(caster);
            fireball.setIsIncendiary(false);
            fireball.setYield(0.0f);          // ★ 不炸（伤害由 amount 结算，见方法 javadoc）
            fireball.setDirection(direction.normalize());
        } catch (Throwable t) {
            // ★ 观感失败绝不能让这次施放失效（伤害马上就会结算）
            log.warning("[IC] 火球弹射物发射失败（只影响观感，伤害照常结算）：" + t);
        }
    }

    /**
     * {@code HEAL}：{@code full: true} ⇒ 回满；否则 {@code amount}。★ 目标已死亡 ⇒ 跳过。
     *
     * <p>★★ <b>2026-10-18：亡灵也治</b>。见 {@link #appliesTo} 的说明 ——
     * 「原版治疗会伤害亡灵」针对的是 {@code INSTANT_HEALTH} 药水效果，
     * 而这里是 {@code setHealth()}。本项目召唤物大多是亡灵，
     * 保留那条过滤器就等于「治愈术对自己的僵尸没有任何效果」（用户报的就是这个）。
     */
    private static boolean heal(Spell.Effect effect, LivingEntity target, Logger log) {
        boolean full = EffectParams.optionalFlag(effect, "full", false);
        boolean hasAmount = containsKey(effect, "amount");
        if (!full && !hasAmount) {
            throw new IllegalArgumentException("效果 HEAL 必须给 amount 或 full: true 之一"
                    + "（docs/07 §6.4：治愈术原文是「恢复全部血量」，没有数值可抄，用 full）");
        }
        if (!alive(target)) {
            log.warning("[IC] 治疗跳过：目标已失效/已死亡");
            return false;
        }
        double max = target.getMaxHealth();
        double next = full
                ? max
                : Math.min(max, target.getHealth() + EffectParams.requireNumber(effect, "amount"));
        target.setHealth(Math.max(0.0, next));
        return true;
    }

    /** {@code POTION}：{@code effect} + {@code duration-ticks} + 可选 {@code amplifier}（MC 语义：等级−1）。 */
    private static boolean potion(Spell.Effect effect, LivingEntity target, Logger log) {
        String name = EffectParams.requireText(effect, "effect");
        int duration = clampDuration(EffectParams.requireInt(effect, "duration-ticks"),
                "POTION 的 duration-ticks", log);
        int amplifier = EffectParams.optionalInt(effect, "amplifier", 0);
        if (amplifier < 0) {
            throw new IllegalArgumentException("效果 POTION 的参数 amplifier 不能为负"
                    + "（MC 语义 = 等级 − 1：等级 I 写 0；当前值 " + amplifier + "）");
        }
        if (!alive(target)) {
            log.warning("[IC] 药水效果跳过：目标已失效/已死亡（effect=" + name + "）");
            return false;
        }
        PotionEffectType type = resolvePotionType(name);
        if (type == null) {
            throw new IllegalArgumentException("效果 POTION 的参数 effect 不是合法的药水效果："
                    + name + "（期望 PotionEffectType 的名字，例如 SLOWNESS / SPEED / WEAKNESS）");
        }
        // ★ amplifier 直接用卡里的值：卡牌 YAML 写的本来就是 MC 语义的「等级-1」
        target.addPotionEffect(new PotionEffect(type, duration, amplifier));
        return true;
    }

    /**
     * {@code ATTRIBUTE}：先记原值 → 设 base 值 → {@code duration-ticks} 后**只恢复那一项**。
     *
     * <p>★ 属性名非法 ⇒ 告警并返回 {@code false}（一次非法属性不该炸掉整次施放）；
     * 但 {@code amount} / {@code duration-ticks} 缺失仍然抛（缺必需键是数据错误）。
     */
    private static boolean attribute(Spell.Effect effect, Context context, LivingEntity target,
                                     LifecycleTasks explicitTasks, Logger log) {
        String name = EffectParams.requireText(effect, "attribute");
        double amount = EffectParams.requireNumber(effect, "amount");
        int duration = clampDuration(EffectParams.requireInt(effect, "duration-ticks"),
                "ATTRIBUTE 的 duration-ticks", log);

        Attribute attribute = resolveAttribute(name);
        if (attribute == null) {
            log.warning("[IC] 属性改变跳过：attribute: " + name + " 不是合法的 Attribute 名"
                    + "（例如 MAX_HEALTH / ATTACK_DAMAGE / MOVEMENT_SPEED / ARMOR）—— 卡 "
                    + context.cardId() + " 不会因此中断整次施放");
            return false;
        }
        if (!alive(target)) {
            log.warning("[IC] 属性改变跳过：目标已失效/已死亡（attribute=" + name + "）");
            return false;
        }
        AttributeInstance instance = target.getAttribute(attribute);
        if (instance == null) {
            log.warning("[IC] 属性改变跳过：目标 " + target.getType() + " 没有属性 " + name);
            return false;
        }
        double original = instance.getBaseValue();
        // ★ 顺序不能反：记原值必须在 setBaseValue 之前
        if (context.applied() != null) {
            context.applied().rememberAttribute(target.getUniqueId(), name, original);
        }
        instance.setBaseValue(amount);

        final UUID entityId = target.getUniqueId();
        final double restoreTo = original;
        final Attribute toRestore = attribute;
        final String label = name;
        schedule(explicitTasks, context, log, duration, () -> {
            Entity entity = resolveEntity(entityId);
            if (!(entity instanceof LivingEntity living) || !living.isValid()) {
                log.info("[IC] 属性恢复跳过：实体已不在（" + label + "，实体 " + entityId + "）");
                return;
            }
            AttributeInstance current = living.getAttribute(toRestore);
            if (current == null) {
                log.warning("[IC] 属性恢复失败：实体没有属性 " + label + "（可能是换型/重载）");
                return;
            }
            current.setBaseValue(restoreTo);
            log.info("[IC] 属性恢复：" + label + " → " + restoreTo + "（实体 " + entityId + "）");
        }, "ATTRIBUTE 恢复");
        return true;
    }

    /**
     * {@code SUMMON} / {@code SUMMON_DELAYED}：从 {@code group} 段读召唤块，
     * <b>必须走 {@link SummonSpawner}</b>（归属登记唯一入口，铁律 10）。
     *
     * <p>★ 召唤中心 = 传进来的 {@code origin}；{@code target} 只是"这次施放命中的实体"，
     * <b>不用它的位置</b>（否则「瞄远处地面召唤」会变成"在自己脚下召唤"）。
     * 只有 {@code origin == null} 时才退回 {@code target.getLocation()}。
     */
    private static boolean summon(Spell.Effect effect, Context context, LivingEntity target, Location origin,
                                  boolean delayed, LifecycleTasks explicitTasks, Logger log) {
        ConfigurationSection group = groupSection(effect);
        if (group == null) {
            throw new IllegalArgumentException("效果 " + effect.type() + " 缺少必需的 group"
                    + "（两种写法都接受：group: ZOMBIE ＋ 可选兄弟键 count: 1，"
                    + "或段落 group: { entity: ZOMBIE, count: 1 }）");
        }
        int delay = delayed
                ? clampDuration(EffectParams.requireInt(effect, "delay-ticks"),
                        "SUMMON_DELAYED 的 delay-ticks", log)
                : 0;

        Location center = origin != null ? origin : (target == null ? null : target.getLocation());
        if (center == null) {
            log.warning("[IC] 召唤跳过：既没有 origin 也没有 target，不知道召唤在哪"
                    + "（卡 " + context.cardId() + "）");
            return false;
        }
        // ★ 立刻快照：延时任务跑起来时 origin 对象可能已经被调用方改写
        Location snapshot = snapshot(center, log);
        if (snapshot == null) {
            return false;
        }
        Card card = syntheticSummonCard(effect, context, group, log);
        if (card == null) {
            return false;
        }
        SummonSpawner summoner = spawner;
        if (summoner == null) {
            log.warning("[IC] 召唤跳过：没有可用的 SummonSpawner（归属登记的唯一入口）"
                    + " —— 这是接线缺失，不是卡牌问题。请调用 EffectApplier.install(...)");
            return false;
        }
        UUID owner = context.caster();
        Facing facing = facingFrom(context, snapshot);
        String matchId = context.matchId();
        Side side = context.side();
        // ★ 2026-10-18：法术召唤也要带上本局任务表（否则 `lifetime-ticks` 无法兑现）
        LifecycleTasks summonTasks = explicitTasks != null ? explicitTasks : tasksOf(context);

        Runnable spawn = () -> {
            try {
                int spawned = summoner.spawn(card, snapshot, facing, matchId, side, owner, summonTasks);
                log.info("[IC] 效果 " + effect.type() + " 召唤：" + spawned + " 个实体，中心 "
                        + snapshot.getBlockX() + "," + snapshot.getBlockY() + "," + snapshot.getBlockZ()
                        + "（matchId=" + matchId + "，归属=" + side + "/" + owner + "）");
            } catch (Throwable t) {
                log.warning("[IC] 效果召唤失败（matchId=" + matchId + "）：" + t);
            }
        };

        if (delay <= 0) {
            spawn.run();
            return true;
        }
        boolean scheduled = schedule(explicitTasks, context, log, delay, spawn,
                "SUMMON_DELAYED 的召唤");
        if (!scheduled) {
            log.warning("[IC] 延时召唤放弃：调度失败（没有可用的生命周期任务表）——"
                    + " 宁可不召唤，也不让召唤物在下一局凭空冒出来");
        }
        return scheduled;
    }

    /**
     * ★★ <b>{@code SUMMON_PERIODICALLY}：持续期间按固定间隔反复召唤</b>
     * （2026-10-18 新增）。
     *
     * <h2>出处（用户原话）</h2>
     * 「**信息素需要的就是在范围内持续进行随机位置的周期召唤。
     * 如果难以实现可以降级成在释放坐标处持续召唤**」
     * ＋ {@code docs/19} 第 194 行：「半径 5 格，持续 20 秒；期间每秒随机选一格，召唤一只蠹虫」。
     *
     * <h2>口径</h2>
     * <ol>
     *   <li><b>次数 = {@code duration-ticks / interval-ticks}</b>（向下取整，至少 1 次）。
     *       ★ 刻意**不**另给一个 `count` 来表示次数 —— 那个键已经是"每次几只"，
     *       同一张卡上两个 `count` 会让人再也没法确认哪个是哪个（上一轮就吃过这个亏）；</li>
     *   <li><b>位置</b>：写了 {@code radius} ⇒ 每次在落点周围**随机**取一格（水平方向）；
     *       没写或 0 ⇒ 每次都在落点（★ 用户给的降级方案）；</li>
     *   <li>★ 每一次都走 {@link SummonSpawner}（归属登记的唯一入口，铁律 10）；</li>
     *   <li>★ 每一次都**重新快照**落点：任务跑 20 秒，期间调用方的 Location 对象可能被改；</li>
     *   <li>★ 全部登记在 {@link LifecycleTasks} 上 ⇒ 对局结束/实体消失时统一取消，
     *       绝不留一个"对局结束后还在召唤"的定时器（{@code docs/07} §6.4）。</li>
     * </ol>
     *
     * <p>★ 没有生命周期任务表 ⇒ **不召唤**并告警（与本类其它周期任务同一口径：
     * 宁可不做，也不让召唤物在下一局凭空冒出来）。
     */
    private static boolean summonPeriodically(Spell.Effect effect, Context context,
                                              LivingEntity target, Location origin,
                                              LifecycleTasks explicitTasks, Logger log) {
        ConfigurationSection group = groupSection(effect);
        if (group == null) {
            throw new IllegalArgumentException("效果 SUMMON_PERIODICALLY 缺少必需的 group"
                    + "（group: SILVERFISH ＋ 兄弟键 count，或段落写法）");
        }
        int interval = clampDuration(EffectParams.requireInt(effect, "interval-ticks"),
                "SUMMON_PERIODICALLY 的 interval-ticks", log);
        int duration = clampDuration(EffectParams.requireInt(effect, "duration-ticks"),
                "SUMMON_PERIODICALLY 的 duration-ticks", log);
        // ★ radius 可选：不写 = 0 = 每次都在落点（降级方案）
        int radius = Math.max(0, (int) EffectParams.optionalNumber(effect, "radius", 0));
        if (radius > 64) {
            radius = 64;   // 与 abilities 的 radius 上限一致（防手滑写出天大的数）
        }
        Location center = origin != null ? origin : (target == null ? null : target.getLocation());
        if (center == null) {
            log.warning("[IC] 周期召唤跳过：既没有 origin 也没有 target，不知道召唤在哪"
                    + "（卡 " + context.cardId() + "）");
            return false;
        }
        Location snapshot = snapshot(center, log);
        if (snapshot == null) {
            return false;
        }
        Card card = syntheticSummonCard(effect, context, group, log);
        if (card == null) {
            return false;
        }
        SummonSpawner summoner = spawner;
        if (summoner == null) {
            log.warning("[IC] 周期召唤跳过：没有可用的 SummonSpawner（归属登记的唯一入口）"
                    + " —— 这是接线缺失，不是卡牌问题。请调用 EffectApplier.install(...)");
            return false;
        }
        LifecycleTasks tasks = explicitTasks != null ? explicitTasks : tasksOf(context);
        Plugin plugin = context.plugin();
        if (tasks == null || plugin == null) {
            log.warning("[IC] 周期召唤跳过：没有可用的生命周期任务表/插件实例（卡 "
                    + context.cardId() + "）—— 周期任务绝不允许挂在无人回收的地方");
            return false;
        }

        // ★ 次数 = 持续 / 间隔（向下取整，至少 1 次：哪怕 duration < interval 也该召唤一次）
        final int times = Math.max(1, duration / interval);
        final int tickInterval = interval;
        final int spread = radius;
        final UUID owner = context.caster();
        final Facing facing = facingFrom(context, snapshot);
        final String matchId = context.matchId();
        final Side side = context.side();
        final int[] done = {0};
        final BukkitTask[] handle = new BukkitTask[1];
        // ★ 生成位置：radius > 0 ⇒ 以落点为中心随机偏移（水平）；否则就是落点本身
        final java.util.Random random = new java.util.Random();

        Runnable spawn = () -> {
            if (done[0] >= times) {
                if (handle[0] != null && !handle[0].isCancelled()) {
                    handle[0].cancel();
                }
                log.info("[IC] 周期召唤完成：" + times + " 次（卡 " + context.cardId()
                        + "｜matchId=" + matchId + "）");
                return;
            }
            done[0]++;
            Location where = snapshot;
            if (spread > 0) {
                double angle = random.nextDouble() * 2.0 * Math.PI;
                double distance = Math.sqrt(random.nextDouble()) * spread;
                where = snapshot.clone().add(Math.cos(angle) * distance, 0.0,
                        Math.sin(angle) * distance);
            }
            try {
                int spawned = summoner.spawn(card, where, facing, matchId, side, owner, tasks);
                log.info("[IC] 周期召唤 第 " + done[0] + "/" + times + " 次：" + spawned
                        + " 个实体 @" + where.getBlockX() + "," + where.getBlockY() + ","
                        + where.getBlockZ() + "（卡 " + context.cardId()
                        + (spread > 0 ? "｜随机半径 " + spread : "｜固定落点") + "）");
            } catch (Throwable t) {
                log.warning("[IC] 周期召唤第 " + done[0] + " 次失败（卡 " + context.cardId()
                        + "）：" + t);
            }
        };

        // ★ 第一次立刻（间隔之后再来），共 times 次；最后一次跑完自己 cancel
        handle[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, spawn,
                tickInterval, tickInterval);
        tasks.track(handle[0]);
        log.info("[IC] 周期召唤开始：" + times + " 次，每 " + tickInterval + " tick 一次，共 "
                + duration + " tick｜" + (spread > 0 ? "随机半径 " + spread : "固定落点")
                + "（卡 " + context.cardId() + "）");
        return true;
    }

    /**
     * {@code CAST_SPELL}：嵌套施放。
     *
     * <p>★ <b>深度限 1（【Q26 已裁定】用户原话「可以。深度应该限 1」）</b>：
     * {@code context.depth() >= 1} 时<b>拒绝 + 告警</b>（不是静默不嵌套）。
     */
    private static boolean castSpell(Spell.Effect effect, Context context, Runnable nestCaster, Logger log) {
        String spell = EffectParams.requireText(effect, "spell");
        String at = EffectParams.optionalText(effect, "at", "IMPACT");
        if (context.depth() >= 1) {
            log.warning("[IC] 嵌套施放被拒绝：深度已达上限 1（【】）—— 卡 "
                    + context.cardId() + " 想再施放 " + spell + "（at=" + at + "）");
            return false;
        }
        if (nestCaster == null) {
            log.warning("[IC] 嵌套施放被拒绝：调用方没有注入 nestCaster（接线缺失）—— 卡 "
                    + context.cardId() + " 想施放 " + spell + "");
            return false;
        }
        nestCaster.run();
        return true;
    }

    /**
     * {@code STUN}：原版没有眩晕 ⇒ 两件事近似。
     *
     * <p>【AI 补充】<b>可否决</b>（{@code docs/04} §16.9.5）：① {@code SLOWNESS}
     * amplifier <b>6</b>（= 等级 VII，接近不能动）；② {@code target instanceof Mob}
     * ⇒ {@code setAware(false)}（真正停 AI 与移动），{@code duration-ticks} 后用
     * {@link MatchService#tasks()} **恢复 {@code setAware(true)}**。
     *
     * <p>⚠️ 已知不完美（记在报告的"拿不准"里）：{@code setAware(false)} 会**一并停掉**
     * 该单位的索敌/攻击 —— 这正是"眩晕"想要的；但若将来要「眩晕期间仍能挨打但不动」，
     * 需要更细的通道（不是本层能决定的）。
     */
    private static boolean stun(Spell.Effect effect, Context context, LivingEntity target,
                                LifecycleTasks explicitTasks, Logger log) {
        int duration = clampDuration(EffectParams.requireInt(effect, "duration-ticks"),
                "STUN 的 duration-ticks", log);
        if (!alive(target)) {
            log.warning("[IC] 眩晕跳过：目标已失效/已死亡（卡 " + context.cardId() + "）");
            return false;
        }
        PotionEffectType slowness = resolvePotionType("SLOWNESS");
        if (slowness == null) {
            throw new IllegalArgumentException("找不到 PotionEffectType SLOWNESS"
                    + " —— 服务端版本与 paper-api 不匹配（STUN 依赖它实现「接近不能动」）");
        }
        // amplifier 6 = 等级 VII（MC 语义：amplifier = 等级 − 1）
        // ★★ 2026-10-18：改读常量 `policy/StunRule#STUN_AMPLIFIER` —— 那个类用同一个数值
        //   反判「这个单位现在是不是被眩晕」（烈焰守卫「被眩晕时解锁锁定」那一格）。
        //   两处写字面量 6 的话，将来只改一处 ⇒ 解锁会**静默失效**。
        target.addPotionEffect(new PotionEffect(slowness, duration,
                io.mczju.infernalclash.policy.StunRule.STUN_AMPLIFIER));
        // ★★★ 2026-10-19 用户裁定（逐字）：「海王的眩晕**已有但要补可见反馈**」
        //   ⇒ 眩晕落地的那一刻在目标头顶撒一圈电火花：玩家能**看出来**谁被晕了。
        //   ★ 只影响观感（任何异常都吞掉）：眩晕本身已经写进药水效果，绝不因为粒子失败而回滚。
        //   ★ 粒子名取 `ELECTRIC_SPARK`（与海王能力 fx 的粒子同一个，观感统一，可随时改）。
        spawnStunMarker(target, duration, log);
        if (!(target instanceof Mob mob)) {
            log.info("[IC] 眩晕：目标不是 Mob（" + target.getType() + "），只加 SLOWNESS VII");
            return true;
        }
        mob.setAware(false);
        final UUID entityId = mob.getUniqueId();
        boolean scheduled = schedule(explicitTasks, context, log, duration, () -> {
            Entity entity = resolveEntity(entityId);
            if (!(entity instanceof Mob stunned)) {
                log.info("[IC] 眩晕恢复跳过：实体已不在（" + entityId + "）");
                return;
            }
            try {
                stunned.setAware(true);
                log.info("[IC] 眩晕结束：恢复 setAware(true)（实体 " + entityId + "）");
            } catch (Throwable t) {
                log.warning("[IC] 眩晕恢复失败（实体 " + entityId + "）：" + t);
            }
        }, "STUN 的 setAware 恢复");
        if (!scheduled) {
            // ★ 失败方向：宁可不眩晕，也不留下"永不醒来"的单位
            log.warning("[IC] 眩晕放弃：没有可用的任务登记表，立刻回滚 setAware(true)"
                    + "（否则这个单位永远不会再动）");
            try {
                mob.setAware(true);
            } catch (Throwable t) {
                log.warning("[IC] 眩晕回滚失败：" + t);
            }
            return false;
        }
        return true;
    }

    /**
     * ★★ <b>眩晕的可见标记</b>（2026-10-19 新增）—— 在目标头顶撒一圈电火花。
     *
     * <p>出处（用户逐字）：「**海王的眩晕已有但要补可见反馈**」。
     * 眩晕的机制本体是「SLOWNESS VII ＋ {@code setAware(false)}」，两者都**看不见**
     * ⇒ 玩家只看到"它站着不动"，与"卡住了 / bug 了"分不出来。
     *
     * <p>★ 位置：目标**眼睛高度**上方一点点（{@code getLocation().add(0, 1.6, 0)}），
     * 免得粒子被埋在身上。★ 密度随手：12 颗、散布 0.35 —— 观感值，可随时改（铁律 8）。
     * ★ 失败只吞掉：这是纯观感，绝不能让一条已经生效的眩晕被判失败。
     */
    private static void spawnStunMarker(LivingEntity target, int durationTicks, Logger log) {
        try {
            org.bukkit.World world = target.getWorld();
            world.spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK,
                    target.getLocation().add(0.0, 1.6, 0.0), 12, 0.35, 0.25, 0.35, 0.02);
        } catch (Throwable t) {
            log.warning("[IC] 眩晕的可见标记没能播出来（只影响观感，眩晕本身已生效，"
                    + "时长 " + durationTicks + " tick）：" + t);
        }
    }

    /** {@code IGNITE}：{@code setFireTicks(duration-ticks)}；缺省 80（4 秒，原版「着火」的常见时长）。 */    private static boolean ignite(Spell.Effect effect, LivingEntity target, Logger log) {
        int duration = EffectParams.optionalInt(effect, "duration-ticks", 80);
        if (duration <= 0) {
            throw new IllegalArgumentException("效果 IGNITE 的参数 duration-ticks 必须为正"
                    + "（当前值 " + duration + "；0 等于不点燃，那是静默失败）");
        }
        if (!alive(target)) {
            log.warning("[IC] 点燃跳过：目标已失效/已死亡");
            return false;
        }
        target.setFireTicks(duration);
        return true;
    }

    /**
     * {@code KNOCKBACK}：从 {@code origin} 指向目标的**水平**方向给一次速度；{@code strength} 缺省 0.4。
     *
     * <p>竖直分量**保持原值**（只改水平 —— 否则会把站在地上的目标按进地里或弹上天）。
     */
    private static boolean knockback(Spell.Effect effect, LivingEntity target, Location origin, Logger log) {
        double strength = EffectParams.optionalNumber(effect, "strength", 0.4);
        if (!alive(target)) {
            log.warning("[IC] 击退跳过：目标已失效/已死亡");
            return false;
        }
        if (immuneToDisplacement(target, "击退", log)) {
            return false;
        }
        Vector direction = horizontalDirection(origin, target);
        if (direction == null) {
            log.warning("[IC] 击退跳过：源点缺失或与目标重合，方向无从谈起");
            return false;
        }
        Vector velocity = target.getVelocity();
        target.setVelocity(new Vector(direction.getX() * strength, velocity.getY(),
                direction.getZ() * strength));
        return true;
    }

    /**
     * {@code PUSH}（「狂风推进」）：{@code duration-ticks} 内**每 tick 重申**水平速度；
     * {@code strength} 缺省 0.3。
     *
     * <p>★ 记进 {@link AppliedEffects#rememberPush(UUID)}，到期停止（并摘掉该 UUID）。
     * 每 tick 重申是必须的：原版每 tick 都会把速度往 0 收敛，只给一次速度推不动几格。
     */
    private static boolean push(Spell.Effect effect, Context context, LivingEntity target, Location origin,
                                LifecycleTasks explicitTasks, Logger log) {
        int duration = clampDuration(EffectParams.requireInt(effect, "duration-ticks"),
                "PUSH 的 duration-ticks", log);
        double strength = EffectParams.optionalNumber(effect, "strength", 0.3);
        if (!alive(target)) {
            log.warning("[IC] 推动跳过：目标已失效/已死亡（卡 " + context.cardId() + "）");
            return false;
        }
        // ★★★ 1.1.0（用户逐字）：「**狂风推进不再影响带有击退抗性的召唤物**」
        //   ⇒ 判据从 `immuneToDisplacement`（只有满 1.0 免疫）换成 `DisplacementRule#pushable`
        //     （**带一点点抗性就不推**）。★ 只收紧 PUSH —— 火球击退（KNOCKBACK）仍按老口径，
        //     因为用户只点了狂风推进（见 DisplacementRule#pushable 的 javadoc）。
        if (immuneToPush(target, log)) {
            return false;
        }
        Vector direction = horizontalDirection(origin, target);
        if (direction == null) {
            log.warning("[IC] 推动跳过：源点缺失或与目标重合，方向无从谈起（卡 "
                    + context.cardId() + "）");
            return false;
        }
        final Vector push = direction.multiply(strength);
        final UUID entityId = target.getUniqueId();

        LifecycleTasks tasks = explicitTasks != null ? explicitTasks : tasksOf(context);
        Plugin plugin = context.plugin();
        if (tasks == null || plugin == null) {
            log.warning("[IC] 推动跳过：没有可用的生命周期任务表/插件实例（卡 " + context.cardId()
                    + "）—— 周期任务绝不允许挂在无人回收的地方");
            return false;
        }
        if (context.applied() != null) {
            context.applied().rememberPush(entityId);
        }

        // 用「tick 计数」在任务体里判断结束，再 cancel() 自己 ⇒ 不需要异常控制流。
        final int[] elapsed = {0};
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                elapsed[0]++;
                if (elapsed[0] > duration) {
                    stopPush(context, entityId, handle[0], log, "到期");
                    return;
                }
                Entity entity = resolveEntity(entityId);
                if (!(entity instanceof LivingEntity living) || !alive(living)) {
                    stopPush(context, entityId, handle[0], log, "实体已不在");
                    return;
                }
                // ★★ 每 tick 也要判一次「免疫位移」：目标可能在被推的过程中才变成免疫
                //   （例如它刚被施加了击退抗性修饰符，或者塔在第 3 tick 才被发现是目标物）。
                //   ★ 命中免疫 ⇒ **就地停止**并记一条 info（不静默 —— 这条以前是"吹飞塔"的现场）。
                if (immuneToDisplacement(living, "推动", log)) {
                    stopPush(context, entityId, handle[0], log, "目标免疫位移");
                    return;
                }
                // ★ 每 tick 把水平速度**重申**一次（竖直分量不动：只做"推进"）
                Vector current = living.getVelocity();
                living.setVelocity(new Vector(push.getX(), current.getY(), push.getZ()));
            } catch (Throwable t) {
                log.warning("[IC] 推动出错，已停止（实体 " + entityId + "）：" + t);
                stopPush(context, entityId, handle[0], log, "出错");
            }
        }, 1L, 1L);
        tasks.track(handle[0]);
        log.info("[IC] 推动开始：实体 " + entityId + " 共 " + duration + " tick，速度 ("
                + round(push.getX()) + ", " + round(push.getZ()) + ")（卡 " + context.cardId() + "）");
        return true;
    }

    /** 停止推动：摘掉推动记录 + 取消周期任务（幂等）。 */
    private static void stopPush(Context context, UUID entityId, BukkitTask task, Logger log, String reason) {
        if (context.applied() != null) {
            context.applied().forgetPush(entityId);
        }
        try {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        } catch (Throwable t) {
            log.warning("[IC] 取消推动任务失败（实体 " + entityId + "）：" + t);
        }
        log.info("[IC] 推动结束（" + reason + "）：实体 " + entityId);
    }

    /**
     * {@code REFRESH_COOLDOWN}：刷新**本卡**的冷却。
     *
     * <h2>★★★ 2026-10-18 修（用户第 5 条，逐字）</h2>
     * 「**场上任意一个「不休之魂」死亡后都不会重置这张卡的 cd**」
     *
     * <h2>根因（两层，缺一都表现为"没刷新"）</h2>
     * <ol>
     *   <li>★★ <b>改错了表</b>：本方法原来只做 {@code player.setCooldown(Key, 0)} ——
     *       而 2026-10-18 起卡牌冷却的<b>真正判据</b>是 {@code policy/CardCooldowns}
     *       （{@code MatchService} 的字段，按卡共享、时钟 = {@code battleTick}）。
     *       那个 {@link Key} 只是<b>旧实现的残骸</b>：没有任何地方读回它 ⇒
     *       清它等于**什么都没做**（也就是用户看到的现象）；</li>
     *   <li>★ <b>施放者不在线就整条跳过</b>：亡语发生时召唤者可能已经退出 / 阵亡掉线，
     *       而旧实现第一步就是「拿不到玩家 ⇒ 告警 + return」
     *       ⇒ 连那张表都不会去清。</li>
     * </ol>
     *
     * <h2>现在的顺序（两张表 + 遮罩，各管一件事）</h2>
     * <ol>
     *   <li>★★ <b>服务端冷却表</b>：{@code match.cooldowns().cancel(cardId)} ——
     *       <b>这才是拦得住"再打一张"的那一个</b>。★ <b>不依赖玩家在线</b>
     *       （亡语就是"单位死了也生效"，用户原话是"场上<b>任意一个</b>不休之魂死亡"）；</li>
     *   <li><b>客户端遮罩</b>：{@code player.setCooldown(material, 0)}（材质走
     *       {@code CardItems#materialOf}）—— 玩家不在线就跳过（没有客户端可清）；</li>
     *   <li>旧的 {@link Key} 也一起清（**无害**，且万一将来有别的链路读它，语义一致）。</li>
     * </ol>
     *
     * <p>★ 返回值 = **冷却表真的被清了吗**（{@code REFRESH_COOLDOWN} 的成功判据是"表变了"，
     * 而不是"玩家在线"—— 后者只是遮罩能不能刷新的前提）。
     * ★ 找不到本局运行时（{@code MatchService.get == null}，例如离线夹具 / 对局已收尾）
     * ⇒ 只告警并返回 {@code false}，绝不去猜一个"将要开始"的 tick。
     */
    private static boolean refreshCooldown(Context context, Logger log) {
        String cardId = context.cardId();
        // ---- ① 服务端冷却表（唯一能拦住连发的那一个）----
        MatchService service = MatchService.get(context.matchId());
        boolean tableCleared = false;
        if (service == null) {
            log.warning("[IC] 刷新冷却跳过：找不到本局运行时（matchId=" + context.matchId()
                    + "，卡 " + cardId + "）—— 服务端冷却表无处可清");
        } else if (cardId == null || cardId.isBlank()) {
            log.warning("[IC] 刷新冷却跳过：上下文没有卡 id（matchId=" + context.matchId() + "）"
                    + " —— 不知道要刷新哪张卡，绝不猜");
        } else {
            // ★★★ 2026-10-21：冷却表**按阵营分**（修「两人共享 cd」）⇒ 必须带 side。
            //   ★ 拿不到阵营 ⇒ 只告警、不清（绝不去猜"清哪一张表"：清错表等于把对手的冷却也清了）。
            if (context.side() == null) {
                log.warning("[IC] 刷新冷却跳过：上下文没有阵营（matchId=" + context.matchId()
                        + "，卡 " + cardId + "）—— 冷却表按阵营分，猜不出清哪一张");
            } else {
                service.cooldowns().cancel(context.side(), cardId);
                tableCleared = true;
                log.info("[IC] 刷新冷却（本卡）：" + cardId + "（阵营 " + context.side()
                        + "），服务端冷却表已清"
                        + "（时钟 " + service.battleTick() + " tick）"
                        + "｜出处 = 用户第 5 条「不休之魂死亡后重置这张卡的 cd」");
            }
        }
        // ---- ② 客户端遮罩 + ③ 旧的 Key（都只在施放者在线时才有意义）----
        Player caster = resolveCasterPlayer(context);
        if (caster == null) {
            return tableCleared;
        }
        try {
            Key key = cooldownKey(cardId, log);
            if (key != null) {
                caster.setCooldown(key, 0);
            }
        } catch (Throwable t) {
            log.warning("[IC] 刷新冷却：旧的 Key 冷却没清掉（不影响服务端冷却表）：" + t);
        }
        try {
            Card card = cardOf(context, cardId);
            caster.setCooldown(io.mczju.infernalclash.card.CardItems.materialOf(card), 0);
        } catch (Throwable t) {
            log.warning("[IC] 刷新冷却：客户端遮罩没清掉（服务端冷却表已清，实际可以再用了）：" + t);
        }
        return tableCleared;
    }

    /**
     * 按 {@code cardId} 现查卡定义（只为拿 {@code icon} 算遮罩材质）。
     *
     * <p>★ 查不到就返回 {@code null}：{@link io.mczju.infernalclash.card.CardItems#materialOf}
     * 对 {@code null} 有兜底材质，**不会**因此抛异常，也不会因此去猜一张卡。
     */
    private static Card cardOf(Context context, String cardId) {
        if (cardId == null) {
            return null;
        }
        if (context.plugin() instanceof io.mczju.infernalclash.InfernalClashPlugin plugin) {
            CardCatalog catalog = plugin.cardCatalog();
            if (catalog != null) {
                return catalog.cards().get(cardId);
            }
        }
        return null;
    }

    /**
     * {@code GRANT_XP}：给**施放者所属的那一方**加经验（受等级上限约束）。
     *
     * <p>★ 调的是 {@link MatchService#addXp(Side, int)}（**不是** {@code refundXp}）：
     * 前者是「效果给的经验」、后者是「打牌失败的退还」，**语义不同**；
     * 两者都受等级上限约束 ⇒ 都不会被当成刷经验的漏洞。
     */
    private static boolean grantXp(Spell.Effect effect, Context context, Logger log) {
        double amount = EffectParams.requireNumber(effect, "amount");
        if (context.side() == null) {
            log.warning("[IC] 给经验跳过：上下文没有阵营（卡 " + context.cardId()
                    + "，matchId=" + context.matchId() + "）");
            return false;
        }
        MatchService service = MatchService.get(context.matchId());
        if (service == null) {
            log.warning("[IC] 给经验跳过：找不到对局运行时（matchId=" + context.matchId()
                    + "，卡 " + context.cardId() + "）");
            return false;
        }
        int granted = (int) Math.round(amount);
        if (granted <= 0) {
            log.warning("[IC] 给经验跳过：amount=" + amount + " 取整后为 " + granted
                    + "（卡 " + context.cardId() + "）");
            return false;
        }
        // ★ 用 addXp（「效果给的经验」），不是 refundXp（「打牌失败的退还」）—— 语义不同
        service.addXp(context.side(), granted);
        log.info("[IC] 给经验：" + context.side() + " +" + granted
                + "（卡 " + context.cardId() + "）");
        return true;
    }

    /**
     * ★★★ {@code SPAWN_FANGS}：在**目标脚下**召唤一个原版「尖牙」实体
     * （{@code org.bukkit.entity.EvokerFangs}）—— 2026-10-21 用户裁定。
     *
     * <h2>用户原话（逐字）</h2>
     * 「唤魔者（原版的尖牙很容易空）：**拦截召唤尖牙行为**，每 3 秒先使目标眩晕 1 秒，
     * **同时在目标处召唤尖牙**（最好能调用原版唤魔者召唤尖牙的行为）」。
     *
     * <h2>为什么这样就是"调用原版行为"</h2>
     * Bukkit API 里**真的有** {@code EvokerFangs}（26.2 `javap` 实测：`getOwner/setOwner/
     * getAttackDelay/setAttackDelay`）⇒ 我们 spawn 的就是**原版那个实体**，
     * 咬人时机、动画、音效、伤害全部由原版逻辑负责；我们只做两件事：
     * <ol>
     *   <li>决定**长在哪**（= 目标脚下 ⇒ 配合 STUN，目标走不掉 ⇒ 尖牙不会空）；</li>
     *   <li>把 {@code owner} 指向**召唤者**（原版用它排除主人 / 结算归属；
     *       阵营过滤在 {@code battle/AllyDamageGuard} 里按 owner 的阵营做）。</li>
     * </ol>
     *
     * <p>★ 失败只告警：尖牙是输出手段，长不出来不该让整条能力炸掉。
     * ★ 不登记账本：尖牙是**原版临时实体**（~22 tick 自行消失），不属于本插件接管的单位；
     * 对局收尾时它们早就没了。
     */
    private static boolean spawnFangs(Context context, LivingEntity target, Location origin, Logger log) {
        Location where = target != null && target.getLocation().getWorld() != null
                ? target.getLocation()
                : origin;
        if (where == null || where.getWorld() == null) {
            log.warning("[IC] 尖牙跳过：既没有目标也没有落点（卡 " + context.cardId() + "）");
            return false;
        }
        // ★★★ 2026-10-21（用户实机报：「**我虽然从来没有攻击过敌人，却一直在弹
        //   『你的力量不足以伤害到召唤物』**」——现场日志铁证：
        //   `已拦截玩家攻击召唤物：_YamatoMaya_ → POLAR_BEAR/DROWNED/…（来源 indirect_magic）` 每 3 秒一条）：
        //   **根因 = 我把尖牙的 owner 设成了"召唤者玩家"** ⇒
        //   `battle/PlayerDamageGuard`（C13「玩家不能打召唤物」）把每一口尖牙都当成"玩家在攻击召唤物"
        //   ⇒ 伤害被取消（尖牙其实**一点伤害都没打出去**）＋ 每条都推一句提示。
        //   ⇒ 正确归属：owner = **施法的那个唤魔者（本场单位）**，这样伤害是"单位打单位"，
        //     C13 不适用，而"尖牙只咬敌人"那条阵营过滤（`battle/AllyDamageGuard` 读 owner 的阵营）照旧成立。
        //   ★ 取谁：本场内、同阵营、离落点最近的那只**唤魔者**（找不到就退"同阵营最近的单位"，
        //     再找不到 ⇒ owner 为 null：伤害照打，只是没有阵营过滤，并告警一次）。
        LivingEntity owner = resolveFangOwner(context, origin);
        if (owner == null && FANG_OWNER_WARNED.compareAndSet(false, true)) {
            // 只在第一次记：此后每次都会安静地照打（owner=null ⇒ 没有阵营过滤）
            log.warning("[IC] 尖牙找不到「主人」（卡 " + context.cardId()
                    + "，matchId=" + context.matchId() + "，阵营 " + context.side()
                    + "），这一根 owner=null：伤害照常结算，但失去「只咬敌人」的过滤。"
                    + "常见原因：唤魔者在施法那一刻已经死了 / 被移出本场。");
        }
        try {
            where.getWorld().spawn(where, org.bukkit.entity.EvokerFangs.class, fangs -> {
                if (owner != null) {
                    fangs.setOwner(owner);
                }
                fangs.setAttackDelay(0);
            });
            return true;
        } catch (Throwable t) {
            log.warning("[IC] 召唤尖牙失败（卡 " + context.cardId() + "，落点 "
                    + where.getBlockX() + "," + where.getBlockY() + "," + where.getBlockZ()
                    + "）：" + t);
            return false;
        }
    }

    /**
     * ★★★ <b>尖牙的"主人"是谁</b>（2026-10-21，修「尖牙伤害被 C13 拦掉」）。
     *
     * <p>只从**本场账本**里找（铁律 10：不查世界）：
     * <ol>
     *   <li>同阵营、`Evoker`、离 {@code origin} 最近的 ⇒ 优先；</li>
     *   <li>退一步：同阵营、任何单位、离 {@code origin} 最近的（≤ {@value #FANG_OWNER_RADIUS} 格）；</li>
     *   <li>都找不到 ⇒ {@code null}（伤害照常结算，只是失去"只咬敌人"的过滤）＋ 告警一次。</li>
     * </ol>
     *
     * <p>★ 为什么必须是**单位**而不是玩家：玩家当 owner 时伤害来源是 {@code indirect_magic}，
     * 会被 {@code PlayerDamageGuard} 判成"玩家在攻击召唤物"⇒ 取消 + 弹提示（现场那条刷屏）。
     */
    private static LivingEntity resolveFangOwner(Context context, Location origin) {
        try {
            MatchService match = MatchService.get(context.matchId());
            if (match == null || match.units() == null || origin == null
                    || origin.getWorld() == null) {
                return null;
            }
            LivingEntity bestAny = null;
            double bestAnyDistance = Double.MAX_VALUE;
            LivingEntity bestEvoker = null;
            double bestEvokerDistance = Double.MAX_VALUE;
            for (java.util.UUID id : match.units().tracked(context.matchId())) {
                Entity raw = org.bukkit.Bukkit.getEntity(id);
                if (!(raw instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                    continue;
                }
                io.mczju.infernalclash.match.OwnershipTable.Owner owner =
                        match.units().table().owner(id);
                if (owner == null || owner.side() != context.side()
                        || !living.getWorld().equals(origin.getWorld())) {
                    continue;
                }
                double distance = living.getLocation().distance(origin);
                if (distance > FANG_OWNER_RADIUS) {
                    continue;
                }
                if (distance < bestAnyDistance) {
                    bestAnyDistance = distance;
                    bestAny = living;
                }
                if (living instanceof org.bukkit.entity.Evoker && distance < bestEvokerDistance) {
                    bestEvokerDistance = distance;
                    bestEvoker = living;
                }
            }
            if (bestEvoker != null) {
                return bestEvoker;
            }
            if (bestAny != null) {
                return bestAny;
            }
        } catch (Throwable ignored) {
            // 落到下面的告警
        }
        return null;
    }

    /** 找尖牙主人时的最大距离（格）：够覆盖"唤魔者在目标 8 格内施法"这一档。 */
    private static final double FANG_OWNER_RADIUS = 16.0;

    /** 「找不到尖牙主人」只告警一次（每 tick 都会刷的话控制台会被淹）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean FANG_OWNER_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * {@code REVIVE}：<b>只登记「复活一次」的标记</b>，不做任何实体操作。
     *
     * <p>★ <b>消费方是 P6 的死亡拦截</b> —— 它会调
     * {@link AppliedEffects#consumeRevive(UUID)}，而该调用<b>只成功一次</b>（上限 1）。
     *
     * <p>{@code health} 没写（或给了非正值）⇒ 记成「回满」（用负数当哨兵，由消费方解释）。
     */
    private static boolean revive(Spell.Effect effect, Context context, LivingEntity target, Logger log) {
        // ★★ 2026-10-18：这里**不能**用 `alive(target)`（它要求 `!isDead()`）——
        //   `REVIVE` 是 `ON_DEATH` 触发器上的效果，派发时实体**正是"将死"状态**
        //   ⇒ 旧判据会把每一次复活标记都跳过（审计发现的"美西螈复活从不生效"的半个根因）。
        //   权威消费方已是 `match/UnitLifecycleListener`（按**卡数据**判，不依赖这个标记）；
        //   这里保留标记只为将来"法术给别的单位上复活"这条通道，因此判据放宽到"实体有效"。
        if (target == null || !target.isValid()) {
            log.warning("[IC] 复活标记跳过：目标已失效（卡 " + context.cardId() + "）");
            return false;
        }
        if (context.applied() == null) {
            log.warning("[IC] 复活标记跳过：这次施放没有账本（AppliedEffects），标记无处可存"
                    + "（卡 " + context.cardId() + "）");
            return false;
        }
        double health = EffectParams.optionalNumber(effect, "health", -1.0);
        context.applied().markRevive(target.getUniqueId(), health <= 0 ? -1.0 : health);
        log.info("[IC] 复活标记：" + target.getUniqueId() + " → "
                + (health <= 0 ? "回满" : health) + "（消费方 = P6 的死亡拦截）");
        return true;
    }

    /**
     * {@code COOLDOWN_REDUCTION}：把「该玩家在窗口内的冷却缩短比例」记进静态窗口表；
     * 窗口到点自动失效（并挂在 {@link MatchService#tasks()} 上做清理）。
     *
     * <p>★ 口径：<b>只影响窗口内「新施加」的冷却</b>（原文没说已有冷却算不算
     * ⇒ 【AI 补充】可否决）。接线方在算冷却时读 {@link #activeReduction(UUID)}。
     *
     * <p>★ {@code percent} 的读法**只有一种**：<b>百分数</b>（{@code 1..100}）。
     * 校验与换算全在 {@link #parsePercent(double)} —— 纯静态、可离线单测。
     * 旧实现是「双读法」（{@code 50} 与 {@code 0.5} 都当 50%），那是**猜玩家的意图**，
     * 本项目不允许（【Q12】已收敛）；{@code 0.5} 现在会被**专门**拒绝，
     * 绝不会静默变成 0.5%（语义翻转比报错危险得多）。
     */
    private static boolean cooldownReduction(Spell.Effect effect, Context context,
                                             LifecycleTasks explicitTasks, Logger log) {
        double percent = EffectParams.requireNumber(effect, "percent");
        int duration = clampDuration(EffectParams.requireInt(effect, "duration-ticks"),
                "COOLDOWN_REDUCTION 的 duration-ticks", log);
        final double ratio = parsePercent(percent);
        Player caster = resolveCasterPlayer(context);
        if (caster == null) {
            log.warning("[IC] 冷却缩减跳过：施放者不在线或不是玩家（卡 " + context.cardId() + "）");
            return false;
        }
        final long now = System.currentTimeMillis();
        final long expireAt = now + duration * 50L;
        purgeExpiredWindows(now);
        REDUCTION_WINDOWS.put(caster.getUniqueId(), new Window(ratio, expireAt));

        boolean scheduled = schedule(explicitTasks, context, log, duration, () ->
                purgeExpiredWindows(System.currentTimeMillis()), "COOLDOWN_REDUCTION 到期");
        if (!scheduled) {
            // 没有登记表也安全：activeReduction 自己判断到期时间（窗口外返回 0，只是表里留个死条目）
            log.warning("[IC] 冷却缩减：没有可用的任务登记表，只靠到期时间判断"
                    + "（activeReduction 在窗口外仍会返回 0）");
        }
        log.info("[IC] 冷却缩减窗口：" + caster.getName() + " 减 " + Math.round(ratio * 100)
                + "% 持续 " + duration + " tick（卡 " + context.cardId() + "）");
        return true;
    }

    /**
     * ★★ <b>{@code COOLDOWN_REDUCTION.percent} 的单一读法</b>：只认<b>百分数</b>
     * （{@code 1..100}），返回冷却缩减**比例**（{@code 50 ⇒ 0.5}）——【Q12 已收敛】。
     *
     * <h2>为什么必须"专门"拒绝 {@code 0 < percent < 1}</h2>
     * 旧实现是双读法：{@code percent > 1.0 ? percent / 100.0 : percent}
     * ⇒ {@code 50} 与 {@code 0.5} 都被当成 50%（**猜测意图**）。
     * 若只是简单删掉双读法，{@code 0.5} 会**静默**变成 {@code 0.5}%
     * —— 想减一半、实际减 1/200，是**语义翻转**，比报错危险得多。
     * ⇒ 小数写法单独报错，并直接告诉运维该写什么（{@code 0.5} ⇒ 写 {@code 50}）。
     *
     * <p>本方法是 {@code public static} 且不碰 Bukkit ⇒ 可直接离线单测
     * （不必为了验一条参数范围去起服务器）。
     *
     * @param percent 卡牌 YAML 里写的百分数
     * @return 缩减比例（{@code 0.01 ~ 1.0}）
     * @throws IllegalArgumentException {@code percent} 为 {@code NaN} / {@code <= 0} / {@code > 100}，
     *                                  或落在小数写法区间 {@code (0, 1)}
     */
    public static double parsePercent(double percent) {
        // ★ 顺序很重要：小数写法必须先判，否则它会掉进下面那条"范围"错误里，
        //   报出来的话（"必须在 1~100 内"）说不清"你写的是比例、该 ×100"。
        if (percent > 0.0 && percent < 1.0) {
            long asPercent = Math.round(percent * 100.0);
            throw new IllegalArgumentException("效果 COOLDOWN_REDUCTION 的参数 percent 只接受百分数"
                    + "（1~100）；" + percent + " 是小数写法，本项目不猜你的意图 ——"
                    + " 想表达 " + asPercent + "% 就写 " + asPercent + "");
        }
        if (Double.isNaN(percent) || percent <= 0.0 || percent > 100.0) {
            throw new IllegalArgumentException("效果 COOLDOWN_REDUCTION 的参数 percent 是百分数 1~100"
                    + "（例如 percent: 50 = 冷却减半），当前值 " + percent
                    + " —— 0 等于没减、超过 100 会把冷却算成负数，本项目不静默截断也不猜比例写法");
        }
        return percent / 100.0;
    }

    // ------------------------------------------------------------------ 调度

    /**
     * 把一个「到期要做的事」挂到本局的生命周期上。
     *
     * <p>★ 延时任务必须挂 {@link MatchService#tasks()}（{@code docs/07} §6.4）：
     * 否则对局结束后任务仍在跑 ⇒ 下一局凭空冒出召唤物 / 属性永远不恢复。
     *
     * @return 是否真的调度了（{@code false} = 找不到登记表，调用方必须决定"回滚"还是"放弃"）
     */
    private static boolean schedule(LifecycleTasks explicitTasks, Context context, Logger log,
                                    int delayTicks, Runnable action, String label) {
        if (delayTicks <= 0) {
            return false;
        }
        LifecycleTasks tasks = explicitTasks != null ? explicitTasks : tasksOf(context);
        if (tasks == null || context.plugin() == null) {
            log.warning("[IC] 无法调度 " + label + "：找不到本局的生命周期任务表"
                    + "（matchId=" + context.matchId() + "，卡 " + context.cardId() + "）"
                    + " —— 延时任务不允许挂在无人回收的地方");
            return false;
        }
        tasks.runLater(context.plugin(), action, delayTicks);
        return true;
    }

    /** 按 {@code matchId} 找本局的任务登记表（找不到 ⇒ {@code null}）。 */
    private static LifecycleTasks tasksOf(Context context) {
        MatchService service = MatchService.get(context.matchId());
        return service == null ? null : service.tasks();
    }

    // ------------------------------------------------------------------ 解析与工具

    /** 解析施放者玩家（离线 / 不存在 / 不是玩家 ⇒ {@code null}）。★ 按 UUID 点查，不枚举全服。 */
    private static Player resolveCasterPlayer(Context context) {
        UUID caster = context.caster();
        if (caster == null) {
            return null;
        }
        Player player = context.plugin() == null
                ? Bukkit.getPlayer(caster)
                : context.plugin().getServer().getPlayer(caster);
        return player == null || !player.isOnline() ? null : player;
    }

    /**
     * 按 UUID 找回实体 —— ★ <b>不查世界</b>：{@link Bukkit#getEntity(UUID)} 是 O(1) 点查，
     * 不是"枚举整个世界再过滤"（铁律 10）。
     */
    private static Entity resolveEntity(UUID id) {
        return Bukkit.getEntity(id);
    }

    /** 效果的某个段落键（{@code ConfigurationSection} 或 {@code Map} 两种形态都接受）。 */
    private static ConfigurationSection section(Spell.Effect effect, String key) {
        Object value = effect.params() == null ? null : effect.params().get(key);
        if (value instanceof ConfigurationSection section) {
            return section;
        }
        if (value instanceof Map<?, ?> map) {
            // 列表项/嵌套对象在读出来时可能只是普通 Map ⇒ 借一个空段落把它包成
            // ConfigurationSection，这样下游（MapReader / MobGroupCatalog）只需要一套读取口径。
            return new MemoryConfiguration().createSection(key, map);
        }
        return null;
    }

    /**
     * ★★★ <b>{@code SUMMON*} 的 {@code group} —— <b>两种写法都接受</b></b>
     * （2026-10-17 第十三轮收尾修复，用户实机报「灵狐引路 / 信息素 / 掘墓者只扣费不召唤」）。
     *
     * <h2>修的是哪一类缺陷（本项目最反感的那一类）</h2>
     * 出厂卡写的是<b>字符串</b>形式：
     * <pre>{@code
     * - type: SUMMON
     *   group: FOX          # ← 字符串（mob-groups.yml 的实体组键）+ 兄弟键 count
     * }</pre>
     * 而原先这里只认 {@code ConfigurationSection} / {@code Map} ⇒ 字符串得到 {@code null}
     * ⇒ 抛「缺少必需的段落键 group」⇒ <b>能装载、一施放就失败</b>
     * （装载期只查<b>键名</b>、不查<b>类型</b> —— 与 {@code docs/05} 里那条「第二处白名单」同源）。
     *
     * <h2>口径（三条）</h2>
     * <ol>
     *   <li>{@code group} 是<b>字符串</b> ⇒ 当成实体组键，等价于
     *       {@code group: { entity: <那个字符串> }}；</li>
     *   <li>效果条目上的兄弟键 {@code count} ⇒ <b>覆盖</b>组段里的 {@code count}
     *       （语义 = 「这一次一共召唤几只」）。★ 原先 {@code SUMMON_DELAYED} 的白名单放行了
     *       {@code count} 却<b>没有任何代码读它</b> ⇒ 静默失效（{@code pheromone} 的
     *       {@code count: 20} 就是这么被吃掉的）；</li>
     *   <li>段落写法原样保留 ⇒ 既有的 {@code frog_parasite} 等卡<b>行为一字未变</b>。</li>
     * </ol>
     *
     * @return 合并后的段落；{@code group} 缺失或类型不支持 ⇒ {@code null}
     * @apiNote ★ <b>package-private 而非 private</b>：本方法是「能装载、一施放就炸」那一类
     *          缺陷的唯一防线，而它<b>只能在离线测</b>（{@code summon()} 需要真的
     *          {@code SummonSpawner}）。同包的 {@code SummonGroupSyntaxTest} 直接调它，
     *          并用一条**出厂卡交叉断言**钉住「51 张卡里每个 {@code SUMMON*} 都能被这里接受」。
     */
    static ConfigurationSection groupSection(Spell.Effect effect) {
        Object value = effect.params() == null ? null : effect.params().get("group");
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (value instanceof ConfigurationSection section) {
            merged.putAll(section.getValues(false));
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                merged.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else if (value instanceof String text && !text.isBlank()) {
            merged.put("entity", text.trim());
        } else {
            return null;
        }
        Object siblingCount = effect.params() == null ? null : effect.params().get("count");
        if (siblingCount != null) {
            merged.put("count", siblingCount);
        }
        // ★★ 2026-10-18：把**Map 形态的子段**（`attributes` / `offset` / `equipment` …）
        //   递归转成真正的段落。
        //   根因：Bukkit 的 `ConfigurationSection#get(path)` 对"值是一个 Map"的键返回那个 Map，
        //   而 `getConfigurationSection(path)` **只认 ConfigurationSection**（Map ⇒ null）
        //   ⇒ 段落里写 `attributes: {...}` 时，`isSet` 为 true、`getConfigurationSection` 却是 null
        //   ⇒ 报「必须是对象节点」，而它明明就是个对象。
        //   现场（YAML 卡）本来就是真段落、行为一字未变；这条只为**程序化构造的 group**
        //   与 `summon.equipment` 这一类新增读取路径兜底。
        ConfigurationSection result = new MemoryConfiguration().createSection("group");
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            Object child = entry.getValue();
            if (child instanceof ConfigurationSection || child instanceof Map<?, ?>) {
                fillSection(result.createSection(entry.getKey()), child);
            } else {
                result.set(entry.getKey(), child);
            }
        }
        return result;
    }

    /** 把 {@code Map} / 段落的值递归灌进一个段（见 {@link #groupSection} 的 2026-10-18 注）。 */
    private static void fillSection(ConfigurationSection target, Object source) {
        if (source instanceof ConfigurationSection section) {
            fillSection(target, section.getValues(false));
            return;
        }
        if (!(source instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object child = entry.getValue();
            if (child instanceof ConfigurationSection || child instanceof Map<?, ?>) {
                fillSection(target.createSection(key), child);
            } else {
                target.set(key, child);
            }
        }
    }

    private static boolean containsKey(Spell.Effect effect, String key) {
        return effect.params() != null && effect.params().containsKey(key);
    }

    /**
     * 把效果的 {@code group} 段变成一张**合成召唤卡**（只为喂给 {@link SummonSpawner}）。
     *
     * <p>合成卡的费用/冷却/稀有度都是占位值 —— {@code SummonSpawner} 只读
     * {@code summon.groups()} / {@code summon.equipment()} / {@code summon.taunt()}，不读它们。
     *
     * <h2>★★ 2026-10-18：{@code group.equipment}（掘墓者的铁镐 + 南瓜灯）</h2>
     * {@code docs/19} 第 183~185 行对掘墓者的要求是「召唤一名<b>持铁镐、戴南瓜灯</b>的僵尸」
     * ＋「这名僵尸有 <b>40</b> 血」；而原先的合成卡把装备<b>硬编码成
     * {@link Card.Equipment#NONE}</b> ⇒ 装备列写了也<b>永远不会被读</b>
     * （`zombie.attributes.health: 40` 那条反而能生效，因为属性走的是 {@link Card.Group}）。
     *
     * <p>★ 键与召唤卡**完全相同**（用户裁定：「**认可：两者用同一套键**」）⇒
     * 直接调 {@link CardCatalog#parseEquipment}，不复制第二份解析器
     * （那正是本项目「第二处白名单」事故的形状：两份键表各自演化，没人发现不一致）。
     *
     * <p>★ 失败方向：装备块写错 ⇒ <b>整次召唤放弃</b>并告警（与 {@code group} 非法同一口径）——
     * 宁可"什么都没出来"，也不要"出来一个光着身子、看起来像装备没实现的僵尸"
     * （后者会让人再报一次同一张卡的同一个缺陷）。
     */
    private static Card syntheticSummonCard(Spell.Effect effect, Context context,
                                            ConfigurationSection group, Logger log) {
        List<Card.Group> groups = new ArrayList<>(1);
        try {
            groups.add(parseGroup(group, "效果 " + effect.type() + " 的 group"));
        } catch (RuntimeException e) {
            log.warning("[IC] 召唤跳过：group 段不合法 —— " + e.getMessage());
            return null;
        }
        Card.Equipment equipment = Card.Equipment.NONE;
        if (group.isSet("equipment")) {
            try {
                equipment = CardCatalog.parseEquipment(groupFile(context), group);
            } catch (RuntimeException e) {
                log.warning("[IC] 召唤跳过：group.equipment 段不合法 —— " + e.getMessage());
                return null;
            }
        }
        // ★★★ 2026-10-19（用户裁定）：「熔炉召出的小岩浆怪要**头顶名牌『熔岩精灵』**、
        //   **速度 V**、**点燃敌人**」—— 这三样必须写在**效果召唤的 group 段**里。
        //   ⇒ 两个键（与召唤卡**同一套键**，解析器也只有一份）：
        //     · `group.name`      —— 头顶名牌；
        //     · `group.abilities` —— 与 `summon.abilities` 完全同一个解析器
        //       （{@link CardCatalog#parseAbilities}）⇒ 速度 V / 点燃走既有触发器运行时。
        //   ★ 失败方向与 `equipment` 同一口径：`abilities` 写错 ⇒ **整次召唤放弃**并告警
        //     （宁可什么都没出来，也不要出来一只"看起来没给速度"的岩浆怪）。
        List<Card.Ability> abilities;
        try {
            abilities = CardCatalog.parseAbilities(groupFile(context), group);
            io.mczju.infernalclash.ability.AbilityCatalog.validate(
                    context.cardId() == null ? "spell-summon" : context.cardId(), abilities);
        } catch (RuntimeException e) {
            log.warning("[IC] 召唤跳过：group.abilities 段不合法 —— " + e.getMessage());
            return null;
        }
        // ★★★ 2026-10-19（用户实机报的缺陷）：**名牌不再恒为「（效果召唤）」**。
        //   那句话活在代码里四个版本，结果成了所有法术/能力召唤物的头顶名字
        //   （用户逐字：「灵狐引路召唤的三只狐狸名字居然叫『效果召唤』」）。
        //   ⇒ 取值规则见 {@link io.mczju.infernalclash.card.SummonNames}：
        //     `group.name` → 实体自己的本地化名字 → 实体键本身（**永不**回占位符）。
        String explicitName = group.isSet("name")
                ? MapReader.requireString(groupFile(context), group.getValues(false),
                        "效果 " + effect.type() + " 的 group", "name")
                : null;
        String displayName = io.mczju.infernalclash.card.SummonNames.forGroup(explicitName,
                group.getString("entity"));
        boolean taunt = Boolean.TRUE.equals(MapReader.optionalBoolean(group.getValues(false), "taunt"));
        Card.Summon summon = new Card.Summon(List.copyOf(groups), Card.Placement.GROUND_OR_SELF,
                taunt, Card.Attributes.NONE, abilities, equipment);
        return new Card(context.cardId() == null ? "spell-summon" : context.cardId(),
                Card.Type.SUMMON, displayName, "", List.of(), "RECOVERY_COMPASS", 0, 0,
                Rarity.COMMON, summon, null);
    }

    /**
     * 给 {@code group} 段报错时用的「文件名」（只影响告警/异常里的路径前缀）。
     *
     * <p>★ 法术效果本身<b>不知道自己来自哪个文件</b>（{@code Card} 不带来源路径）⇒
     * 按本项目的命名约定拼出 {@code cards/<cardId>.yml}。绝不退回
     * {@code MobGroupCatalog.FILE}（那会让"卡里的装备写错了"看起来像
     * {@code mob-groups.yml} 出了问题，是本项目最反感的那种误导）。
     */
    private static String groupFile(Context context) {
        String id = context.cardId();
        return "cards/" + (id == null || id.isBlank() ? "?" : id) + ".yml";
    }

    /** 按**与召唤卡完全相同的键**读一组召唤物（`entity` / `count` / `offset` / `spread` / `attributes`）。 */
    private static Card.Group parseGroup(ConfigurationSection group, String path) {
        Map<String, Object> values = group.getValues(false);
        String entity = MapReader.requireString(MobGroupCatalog.FILE, values, path, "entity").toUpperCase();
        int count = MapReader.requireInt(MobGroupCatalog.FILE, values, path, "count", 1, 64);
        int forward = 0;
        int right = 0;
        if (group.isSet("offset")) {
            ConfigurationSection offset = group.getConfigurationSection("offset");
            if (offset == null) {
                throw new IllegalArgumentException(path + ".offset 必须是对象节点"
                        + "（例如 offset: { forward: 2, right: 0 }）");
            }
            forward = MapReader.requireInt(MobGroupCatalog.FILE, offset.getValues(false),
                    path + ".offset", "forward", -16, 16);
            right = MapReader.requireInt(MobGroupCatalog.FILE, offset.getValues(false),
                    path + ".offset", "right", -16, 16);
        }
        double spread = 0.0;
        if (group.isSet("spread")) {
            spread = MapReader.requireInt(MobGroupCatalog.FILE, values, path, "spread", 0, 16);
        }
        Card.Attributes attributes = Card.Attributes.NONE;
        if (group.isSet("attributes")) {
            ConfigurationSection attributesSection = group.getConfigurationSection("attributes");
            if (attributesSection == null) {
                throw new IllegalArgumentException(path + ".attributes 必须是对象节点");
            }
            attributes = MobGroupCatalog.readAttributes(MobGroupCatalog.FILE, attributesSection);
        }
        return new Card.Group(entity, count, forward, right, spread, attributes);
    }

    /**
     * 相对排布的基准朝向 = **从施放者指向召唤中心**的水平方向
     * （等价于召唤链里用的 {@code player.getLocation().getYaw()}）。
     *
     * <p>施放者不在线 ⇒ 退回 {@link Facing#SOUTH}（不猜）；与中心重合 ⇒ 用施放者自己的 yaw。
     */
    private static Facing facingFrom(Context context, Location center) {
        Player caster = resolveCasterPlayer(context);
        if (caster == null) {
            return Facing.SOUTH;
        }
        double dx = center.getX() - caster.getLocation().getX();
        double dz = center.getZ() - caster.getLocation().getZ();
        if (Math.abs(dx) < MIN_DIRECTION_DISTANCE && Math.abs(dz) < MIN_DIRECTION_DISTANCE) {
            return Facing.fromYaw(caster.getLocation().getYaw());
        }
        // MC 的 yaw：0 = 正南（+Z）、90 = 正西（−X）⇒ yaw = atan2(−dx, dz)
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return Facing.fromYaw(yaw);
    }

    /** 从 {@code origin} 指向目标的**水平单位向量**；源点缺失或与目标重合 ⇒ {@code null}。 */
    private static Vector horizontalDirection(Location origin, LivingEntity target) {
        if (origin == null || target == null) {
            return null;
        }
        Location to = target.getLocation();
        Vector raw = new Vector(to.getX() - origin.getX(), 0.0, to.getZ() - origin.getZ());
        if (raw.lengthSquared() < MIN_DIRECTION_DISTANCE * MIN_DIRECTION_DISTANCE) {
            return null;
        }
        return raw.normalize();
    }

    /** 位置快照（延时任务跑起来时原对象可能已被调用方改写）。 */
    private static Location snapshot(Location location, Logger log) {
        if (location.getWorld() == null) {
            log.warning("[IC] 召唤跳过：落点没有世界（world 为 null）");
            return null;
        }
        return new Location(location.getWorld(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    /** 卡牌冷却组：★ 按 {@code Key}，不按 {@code Material}（{@code docs/07} §7.2）。 */
    private static Key cooldownKey(String cardId, Logger log) {
        if (cardId == null || cardId.isBlank()) {
            log.warning("[IC] 冷却操作跳过：卡牌 id 为空，算不出冷却组（infernalclash:<cardId>）");
            return null;
        }
        return Key.key("infernalclash:" + cardId);
    }

    private static boolean alive(LivingEntity target) {
        return target != null && target.isValid() && !target.isDead();
    }

    /**
     * ★★★ <b>「狂风推进」推不推得动它</b>（1.1.0 收紧口径，见
     * {@link io.mczju.infernalclash.policy.DisplacementRule#pushable}）。
     *
     * <p>与 {@link #immuneToDisplacement} 的差别只有阈值：本方法在
     * <b>击退抗性 &gt; 0</b> 时就判"不推"（用户逐字：「<b>狂风推进不再影响带有击退抗性的召唤物</b>」），
     * 而不是等到满 1.0。★ 读不到属性 ⇒ 按"能推"处理（失败方向 = 照常生效）。
     */
    private static boolean immuneToPush(LivingEntity target, Logger log) {
        AttributeInstance instance;
        try {
            instance = target.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        } catch (Throwable t) {
            return false;
        }
        if (instance == null) {
            return false;
        }
        double resistance = instance.getValue();
        if (io.mczju.infernalclash.policy.DisplacementRule.pushable(resistance)) {
            return false;
        }
        log.info("[IC] 推动跳过：" + target.getType() + " 带有击退抗性（" + resistance
                + "）—— 1.1.0 起「狂风推进不再影响带有击退抗性的召唤物」");
        return true;
    }

    /**
     * ★★ <b>目标完全免疫位移吗</b>（推动 / 击退）—— 2026-10-17 第十三轮收尾新增。
     *
     * <h2>为什么需要这一格（而不是只把塔的属性设成 1.0）</h2>
     * 用户实机原话：「**击退抗性依然没有实装，实测防御塔被「狂风推进」法术吹飞很远**」。
     * 查证后发现 {@code PUSH} / {@code KNOCKBACK} 是
     * {@link LivingEntity#setVelocity} <b>自己设的速度</b>，而
     * {@code Attribute.KNOCKBACK_RESISTANCE} <b>只影响原版击退</b>
     * （{@code damage()} 引起的、{@code knockback()} 方法给的）
     * ⇒ <b>把属性的基值设成 1.0 一样推得动</b>。
     * ⇒ 「抗击退」必须落成我们自己效果里的判据（判据本体在纯类
     * {@link io.mczju.infernalclash.policy.DisplacementRule}）。
     *
     * <p>★ 判据读的是<b>属性当前值</b>（含修饰符，不是 base）——
     * 这样「临时加抗击退」的药水/属性修饰符也会生效。
     * ★ 读不到属性（展示实体 / 非生物）⇒ 按「能推」处理（失败方向 = 照常生效，
     * 与 {@code docs/05} 里那条「不改变既有行为」一致）。
     *
     * @param what 日志用（「推动」/「击退」）
     * @return {@code true} = 完全免疫、本次应跳过
     */
    private static boolean immuneToDisplacement(LivingEntity target, String what, Logger log) {
        AttributeInstance instance;
        try {
            instance = target.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        } catch (Throwable t) {
            return false;
        }
        if (instance == null) {
            return false;
        }
        double resistance = instance.getValue();
        if (!io.mczju.infernalclash.policy.DisplacementRule.immune(resistance)) {
            return false;
        }
        log.info("[IC] " + what + "跳过：" + target.getType() + " 完全免疫位移"
                + "（击退抗性 " + resistance + "）—— 目标物（防御塔 / 信标）是固定物，"
                + "本来就不该被吹走");
        return true;
    }

    /** 把时长夹到 {@code [1, MAX_DURATION_TICKS]}；截断时告警（不静默改数）。 */
    private static int clampDuration(int ticks, String key, Logger log) {
        if (ticks <= 0) {
            throw new IllegalArgumentException("参数 " + key + " 必须为正（当前值 " + ticks
                    + "）—— 0 或负数等于「这个效果不生效」，那是静默失败");
        }
        if (ticks > MAX_DURATION_TICKS) {
            log.warning("[IC] " + key + " = " + ticks + " 超过上限 " + MAX_DURATION_TICKS
                    + " tick，截断到上限（对局本身不会超过这个量级）");
            return MAX_DURATION_TICKS;
        }
        return ticks;
    }

    /**
     * 药水效果解析：★ 用注册表（{@code PotionEffectType.getByName}）。
     *
     * <p>26.2 的 {@code PotionEffectType} 已是**注册表对象**（不是枚举）⇒ 没有
     * {@code valueOf(String)}；`getByName` 是它给出的按名查找入口。
     */
    private static PotionEffectType resolvePotionType(String name) {
        if (name == null) {
            return null;
        }
        return PotionEffectType.getByName(name);
    }

    /** 属性解析：{@code Attribute.valueOf}（非法名返回 {@code null} ⇒ 调用方告警 + 返回 false）。 */
    private static Attribute resolveAttribute(String name) {
        try {
            return Attribute.valueOf(name.toUpperCase());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Logger loggerOf(Plugin plugin) {
        return plugin == null ? Logger.getLogger("InfernalClash") : plugin.getLogger();
    }

    private static String describeEntity(Entity entity) {
        return entity == null ? "<null>" : entity.getType() + "/" + entity.getUniqueId();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 同一条提示只打一次（防止每 tick 刷屏）。 */
    private static void warnOnce(String token, Logger log, String message) {
        if (WARNED_ONCE.add(token)) {
            log.warning(message);
        }
    }

    // ------------------------------------------------------------------ 允许键表

    private static Map<Spell.EffectType, Set<String>> allowedKeys() {
        Map<Spell.EffectType, Set<String>> map = new EnumMap<>(Spell.EffectType.class);
        map.put(Spell.EffectType.DAMAGE, Set.of("amount", "damage-type", "projectile",
                // ★ 1.1.0（海王）：对某一种原版生物额外加伤（「对海龟伤害 +1000」）
                "bonus-vs-type", "bonus-vs-amount",
                // ★ 1.1.0（地壳震击）：对建筑（含防御塔 / 信标）翻倍
                "building-multiplier"));
        map.put(Spell.EffectType.HEAL, Set.of("amount", "full"));
        map.put(Spell.EffectType.POTION, Set.of("effect", "amplifier", "duration-ticks"));
        map.put(Spell.EffectType.ATTRIBUTE, Set.of("attribute", "amount", "duration-ticks"));
        // ★★ 2026-10-17 第十三轮收尾：`SUMMON` 也加 `count`。
        //   原先只有 `SUMMON_DELAYED` 有它 ⇒ 出厂 `fox_guide.yml`（「召唤 3 只灵狐刺客」）
        //   **没有任何键能表达"3 只"**（它的注释原话：「§4.4 的 SUMMON 效果只有 group、没有 count
        //   ⇒「3 只」写不进配置」）。现在 `count` 与组段同义、覆盖组段的 `count`。
        map.put(Spell.EffectType.SUMMON, Set.of("group", "count"));
        // ★ 2026-10-15 修：这里原来少了 `count` —— 与 docs/03 §4.4 / docs/07 §6.4 /
        //   Spell.EffectType 的 javadoc（group + count + delay-ticks）不一致，而出厂卡
        //   grave_digger.yml（count: 1）与 pheromone.yml（count: 20）**真的写了它**
        //   ⇒ 修之前那两张卡是「能装载、一施放就失败」（施放期 rejectUnknown 抛）。
        //   ★ 教训：这份白名单是**第二处事实来源**，与文档分叉时表现为「延迟到施放才炸」。
        map.put(Spell.EffectType.SUMMON_DELAYED, Set.of("group", "count", "delay-ticks"));
        // ★★ 2026-10-18：周期召唤（用户「信息素要范围内持续随机位置的周期召唤」）
        map.put(Spell.EffectType.SUMMON_PERIODICALLY,
                Set.of("group", "count", "interval-ticks", "duration-ticks", "radius"));
        map.put(Spell.EffectType.CAST_SPELL, Set.of("spell", "at"));
        // ★ 2026-10-19「回响」：复读**无参数**（读的是本局状态，不是配置）。
        map.put(Spell.EffectType.REPLAY_LAST_CARD, Set.of());
        map.put(Spell.EffectType.STUN, Set.of("duration-ticks"));
        map.put(Spell.EffectType.IGNITE, Set.of("duration-ticks"));
        map.put(Spell.EffectType.KNOCKBACK, Set.of("strength"));
        map.put(Spell.EffectType.PUSH, Set.of("duration-ticks", "strength"));
        map.put(Spell.EffectType.REFRESH_COOLDOWN, Set.of());
        map.put(Spell.EffectType.GRANT_XP, Set.of("amount"));
        map.put(Spell.EffectType.REVIVE, Set.of("health"));
        map.put(Spell.EffectType.COOLDOWN_REDUCTION, Set.of("percent", "duration-ticks"));
        // ★ 2026-10-21「尖牙」：无参数（尖牙长在目标脚下；个数 = 目标个数）
        map.put(Spell.EffectType.SPAWN_FANGS, Set.of());
        // ★★★ 1.1.0（大旋风）：牵引 + 每秒伤害
        map.put(Spell.EffectType.PULL, Set.of("duration-ticks", "amount", "interval-ticks",
                "strength"));
        // ★★★ 1.1.0（凋灵领域）：持续区域伤害（一次施放一份任务）
        map.put(Spell.EffectType.AREA_DAMAGE, Set.of("duration-ticks", "interval-ticks", "amount",
                "radius"));
        // ★★★ 1.1.0（囚笼编织者）：在目标脚下结网
        map.put(Spell.EffectType.PLACE_WEB, Set.of("duration-ticks"));
        // ★★★ 1.1.0（援军）：无参数（状态在 CardCastListener / ReinforcementMemory）
        map.put(Spell.EffectType.REINFORCEMENT, Set.of());
        return Collections.unmodifiableMap(map);
    }

    /**
     * 施放上下文（谁放的 / 哪张卡 / 哪个对局 / 哪一方 / 嵌套深度 / 本次施放的账本）。
     *
     * @param plugin  插件实例（取 logger / 调度器）
     * @param matchId 对局 id（找 {@link MatchService} 与生命周期任务表）
     * @param side    施放者所属阵营（{@code GRANT_XP} 要它）
     * @param caster  施放者 UUID
     * @param cardId  正在施放的卡牌 id（{@code REFRESH_COOLDOWN} 要它算冷却组）
     * @param depth   嵌套深度（★ {@code docs/07} §6.2【Q26】上限 1）
     * @param applied 本次施放的账本（可为 {@code null}；为 null 时 {@code ATTRIBUTE} 不回滚、
     *                {@code REVIVE} 会告警并返回 false）
     */
    public record Context(Plugin plugin, String matchId, Side side, UUID caster,
                          String cardId, int depth, AppliedEffects applied) {
    }
}
