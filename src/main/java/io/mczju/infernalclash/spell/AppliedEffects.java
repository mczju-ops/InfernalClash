package io.mczju.infernalclash.spell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ★ <b>一次施放的「账」</b> —— 记录这次施放**已经做过什么**，用于到期回滚与对账
 * （{@code docs/04} §16.9.5：{@code ATTRIBUTE} / {@code STUN} / {@code PUSH} 这类
 * 「改了状态就要还」的效果，必须留下可回滚的记录）。
 *
 * <h2>为什么要有这个类，而不是就地回滚</h2>
 * <ul>
 *   <li><b>回滚需要"原值"</b>：{@code ATTRIBUTE} 到期要恢复的是**改之前**的 base 值。
 *       施放的那一刻拿不到「改之前」，事后就更拿不到 ⇒ 必须先记；</li>
 *   <li><b>回滚只回自己改过的那一项</b>：与「世界设置只写不还」同类风险
 *       （铁律 10 的精神）—— 不能"重置整个实体"；</li>
 *   <li><b>可对账</b>：{@link #size()} 与 {@link #describe()} 让日志能写出
 *       「这次施放改了几项属性 / 标了几个复活 / 推了几个实体」；</li>
 *   <li><b>一次施放一个账本</b>：它由调用方为每次施放新建，**不跨施放复用**，
 *       所以「这次施放干了什么」永远可回答。</li>
 * </ul>
 *
 * <h2>★ 保序（本项目对「同输入同结果」有硬要求）</h2>
 * 内部一律 {@link LinkedHashMap} / {@link LinkedHashSet}：JVM 的 {@code HashMap}
 * 迭代顺序虽然在同一进程内稳定，但**跨重启 / 跨 JDK 会变**；而本项目的验收方式
 * （日志与探针逐条打印）依赖稳定的顺序。§4.1 已把 {@code Map.copyOf} 迭代顺序随机
 * 记为坑，这里不重蹈。
 *
 * <h2>★ 线程与生命周期</h2>
 * 本类**只在主线程**被读写（BUKKIT 施放链全在主线程；回滚任务用的是 Bukkit 调度器）。
 * 它**不持有任何实体引用** —— 只持有 {@link UUID} 与数值，因此一个已经死亡/被卸载的
 * 实体不会因为本账本而被"钉"在内存里。
 */
public final class AppliedEffects {

    /**
     * 一条属性回滚记录。
     *
     * <p>★ 刻意用 {@link UUID} 而不是 {@code LivingEntity} 引用：实体可能在任何时刻
     * 变得无效，而「谁被改过」这件事在事后仍要能打印出来。
     *
     * @param entity        被改过属性的实体 UUID
     * @param attribute     {@code Attribute} 的**名字**（{@code Attribute.name()}）——
     *                      存字符串而不是枚举，是为了让本记录**与 Bukkit 解耦、可离线单测**
     * @param originalValue 改之前的 base 值
     */
    public record AttributeRollback(UUID entity, String attribute, double originalValue) {
    }

    /** 属性回滚记录（**保序**：按登记顺序）。 */
    private final List<AttributeRollback> attributes = new ArrayList<>();

    /** 被标记「复活一次」的实体 → 复活到的血量。 */
    private final Map<UUID, Double> revives = new LinkedHashMap<>();

    /** 正在被持续推动的实体（**保序**）。 */
    private final Set<UUID> pushing = new LinkedHashSet<>();

    /**
     * 记下「这个实体的这个属性被我从 {@code originalValue} 改成了别的值」。
     *
     * <p>★ 调用时机**必须在 {@code setBaseValue} 之前** —— 事后取到的是新值。
     *
     * @throws IllegalArgumentException 实体 / 属性名为空（这是调用方的编码错误，不是运行时状况）
     */
    public void rememberAttribute(UUID entity, String attribute, double originalValue) {
        if (entity == null) {
            throw new IllegalArgumentException("rememberAttribute：实体 UUID 不能为 null"
                    + "（无法回滚一个不知道是谁的属性）");
        }
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("rememberAttribute：属性名不能为空"
                    + "（实体 " + entity + "）");
        }
        attributes.add(new AttributeRollback(entity, attribute, originalValue));
    }

    /** 属性回滚记录（**只读 + 保序**；不含正在被推动的实体，那是 {@link #pushing()}）。 */
    public List<AttributeRollback> attributeRollbacks() {
        return List.copyOf(attributes);
    }

    /**
     * 标记「这个实体还可以被救回一次」。
     *
     * <p>★ <b>本方法只登记，不做任何实体操作</b> —— 真正的「死了再救」由 **P6 的死亡拦截**
     * 消费（它会调 {@link #consumeRevive(UUID)}）。这样「标记」与「消费」分离，
     * 同一次施放里重复标记不会变成多次复活。
     *
     * @param health 复活到的血量；{@code <= 0}（或调用方传"回满"的哨兵值）时由消费方按**回满**处理
     * @throws IllegalArgumentException 实体为 {@code null}
     */
    public void markRevive(UUID entity, double health) {
        if (entity == null) {
            throw new IllegalArgumentException("markRevive：实体 UUID 不能为 null");
        }
        revives.put(entity, health);
    }

    /**
     * ★ <b>消费一次复活标记 —— 只成功一次</b>。
     *
     * <p>消费方是 **P6 的死亡拦截**。第一次调用返回 {@code true} 并把标记**移除**，
     * 之后（无论隔多久、无论谁再调）一律返回 {@code false} ⇒ 「复活次数上限 1」
     * 由**数据结构**保证，而不是靠调用方自觉。
     *
     * @return 这次是否真的消费到了一个复活标记
     */
    public boolean consumeRevive(UUID entity) {
        if (entity == null) {
            return false;
        }
        return revives.remove(entity) != null;
    }

    /** 该实体是否还有一个未被消费的复活标记。 */
    public boolean hasRevive(UUID entity) {
        return entity != null && revives.containsKey(entity);
    }

    /** 被标记过复活、且尚未被消费的实体（**保序 + 只读**）。 */
    public Set<UUID> pendingRevives() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(revives.keySet()));
    }

    /** 记下「这个实体正在被我持续推动」（{@code PUSH} 用；到期时回滚方需知道推过谁）。 */
    public void rememberPush(UUID entity) {
        if (entity == null) {
            throw new IllegalArgumentException("rememberPush：实体 UUID 不能为 null");
        }
        pushing.add(entity);
    }

    /**
     * 正在被持续推动的实体。
     *
     * <p>返回的是**只读视图**（改动会反映进来，但调用方不能借此偷偷加/删）。
     */
    public Set<UUID> pushing() {
        return Collections.unmodifiableSet(pushing);
    }

    /** 停止推动某个实体（到期时由 {@code PUSH} 的定时器调用）。 */
    public boolean forgetPush(UUID entity) {
        return entity != null && pushing.remove(entity);
    }

    /** 这个账本一共记了多少件事（属性项 + 复活标记 + 正在推动的实体）。 */
    public int size() {
        return attributes.size() + revives.size() + pushing.size();
    }

    /** 一行可对账的描述（日志与探针用）。 */
    public String describe() {
        return "属性改动 " + attributes.size() + " 项 / 复活标记 " + revives.size()
                + " 个 / 正在推动 " + pushing.size() + " 个";
    }

    @Override
    public String toString() {
        return "AppliedEffects{" + describe() + "}";
    }
}
