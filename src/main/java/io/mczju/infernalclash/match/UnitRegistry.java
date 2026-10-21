package io.mczju.infernalclash.match;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★★ <b>召唤物 / 目标物的归属登记（唯一入口）</b> —— 铁律 10 在代码里的落地。
 *
 * <h2>铁律 10 的四问，逐条回答</h2>
 * <ol>
 *   <li><b>范围界定</b>：只对 {@link OwnershipTable} 里**本场登记过的 UUID** 操作。
 *       集合来自我们自己的登记，**不是**「附近的实体」也不是「世界里的实体」。</li>
 *   <li><b>失败方向</b>：账本为空 ⇒ **一个都不删 + 告警**（fail-safe）。
 *       绝不退化成 `world.getEntities()` 那种「按世界清」。</li>
 *   <li><b>归属判定</b>：**PDC 三键**（`match` / `side` / `owner`）+ 账本双保险。
 *       删之前**再核一次 PDC** —— 防 UUID 复用与跨局串场。**绝不靠实体类型**。</li>
 *   <li><b>可对账</b>：清场日志写「已删除 N / 登记 M（失效 K、归属不符 J）」。</li>
 * </ol>
 *
 * <h2>★ 这个类里永远不会出现的调用</h2>
 * {@code getEntities} / {@code getLivingEntities} / {@code getNearbyEntities} /
 * {@code getOnlinePlayers} / {@code getWorlds} ——
 * 已由 {@code BytecodeScopeGuardTest} 用**字节码级**守卫钉住（含阳性对照）。
 * 解析实体只允许走 {@link Bukkit#getEntity(UUID)}：它的范围**就是那个 UUID**。
 *
 * <p>纯逻辑（账本）在 {@link OwnershipTable} 里，可离线单测；本类只做 PDC 读写与实体操作。
 */
public final class UnitRegistry {

    /** PDC 键名（命名空间 = 插件名）。 */
    public static final String KEY_MATCH = "match";
    /** 阵营键。 */
    public static final String KEY_SIDE = "side";
    /** 归属玩家键。 */
    public static final String KEY_OWNER = "owner";
    /**
     * 嘲讽键（**布尔**：有 / 没有，**没有等级** —— 2026-10-15 用户裁定）。
     *
     * <p>★ 为什么要有它：卡牌里的 `taunt: true`（5 张卡）如果不写进 PDC，
     * 索敌时就**读不到** ⇒ 那 5 张卡的嘲讽会是**静默失效**（配置写了却没生效）。
     * 目标物（信标 / 防御塔）的嘲讽由 `objectives` 段保证，也一并写进来，口径统一。
     */
    public static final String KEY_TAUNT = "taunt";

    /**
     * ★★ <b>召唤它的那张卡的 id</b>（2026-10-17 第十二轮新增，缺陷 P1-a 的接线）。
     *
     * <h2>为什么必须落进 PDC（而不是"召唤时记在内存里"）</h2>
     * 「这个召唤物选目标时优不优先打建筑」是**卡牌属性**
     * （{@code Card.Summon#affectedByBuilding}，键 {@code summon.affected-by-building}），
     * 而它要在 {@code battle/CombatDirector} 里读到 —— 那时我们手上**只有实体**，
     * 没有"这张卡"这个对象。三条可选路：
     * <ol>
     *   <li>★ <b>PDC</b>（本实现）：与 {@code match/side/owner/taunt} 同一处写入、同一处读出，
     *       而且<b>扛得住区块卸载与重启</b>（内存表会丢）；</li>
     *   <li>账本里加一列：{@code OwnershipTable.Owner} 是**玩家归属**的载体，
     *       往里塞卡 id 会让"归属"这个概念混进"卡牌数据"；</li>
     *   <li>召唤时把布尔直接写进 PDC：那会变成**第二个事实来源**
     *       （卡 YAML 改一次、已生成的老实体不会跟着变，而两边都"看起来是对的"）。</li>
     * </ol>
     * ⇒ 选 ①：只存 <b>卡 id</b>，语义永远从**卡池**现查（{@code CardCatalog#require}）。
     * ★ 键名与 {@code card/CardItems#KEY_CARD} <b>刻意相同</b>（都是 {@code card}）：
     * 卡牌物品与召唤物身上指的是同一件事（哪张卡）。
     */
    public static final String KEY_CARD = "card";

    /**
     * ★★★ <b>「这个实体算建筑吗」键</b>（**布尔**；2026-10-19 新增）。
     *
     * <h2>它是什么 / 不是什么</h2>
     * 一条<b>纯标签</b>，只被两类判据读：
     * <ol>
     *   <li><b>谁优先打它</b>（{@code Card.Summon#affectedByBuilding} ⇒
     *       {@code policy/TargetPolicy.Candidate#building}）；</li>
     *   <li><b>谁不能治疗它</b>（治愈术 / 蒸汽精灵 ⇒ 建筑不吃治疗）。</li>
     * </ol>
     * ★ <b>它本身不带任何别的效果</b>：不加嘲讽、不加「无法移动」、不加「不会死」。
     * 用户 2026-10-19 逐字：「游戏**完全没有玩法变化**，只是说法变了，
     * 以后出新卡只用加一条『建筑』标签就能实现很多效果」。
     *
     * <h2>为什么写进 PDC（而不是每次现查卡池）</h2>
     * 与 {@link #KEY_TAUNT} 同一条理由，但<b>更强</b>：信标与防御塔**不是召唤卡**，
     * 卡池里根本没有它们的条目 ⇒ 「建筑」这个事实必须有卡池之外的来源。
     * ⇒ 统一落 PDC：召唤卡由 {@code SummonSpawner} 按 {@code summon.building} 写，
     * 目标物由 {@code ObjectiveUnit#spawn} 直接写 {@code true}。
     *
     * <p>★ 读不到 ⇒ {@code false}（fail-safe：老的、别人插件的、还没打标的实体都不算建筑）。
     */
    public static final String KEY_BUILDING = "building";

    /**
     * ★★★ <b>「这个实体是目标物（信标 / 防御塔的载体）」键</b>（**布尔**；2026-10-21 新增）。
     *
     * <h2>为什么需要它（缺陷：召唤物的远程打不动塔）</h2>
     * 自定义穿透射线（{@code battle/RayAttackService}）要判「这个敌人能不能打」，
     * 而过去用的判据是 {@code OwnershipTable.Owner#player() == null} ⇒ <b>把目标物一起剔掉了</b>
     * （目标物的归属玩家按设计就是 {@code null}）⇒ 劲弩手的射线永远打不到塔。
     *
     * <h2>为什么不能拿 {@link #KEY_BUILDING} 或「cardId 为空」代替（启发式，禁止）</h2>
     * 同一个 {@code ObjectiveUnit#spawn} 里，<b>展示实体</b>（{@code BlockDisplay}）也是
     * 「owner 的 player 为 null ＋ {@code building = true} ＋ 没有卡 id」——
     * 它跟载体长得一模一样，但它是**无敌的外观实体**，绝不该被射线当成可伤害目标。
     * ⇒ 必须有**只写在载体上**的明确标记（本键）。
     *
     * <p>★ 写入点**唯一**：{@link #tagObjective}（生产侧只有 {@code ObjectiveUnit#spawn} 调）。
     * ★ 读不到 ⇒ {@code false}（fail-safe：老实体 / 别人的实体 / 展示实体都不算目标物）。
     */
    public static final String KEY_OBJECTIVE = "objective";

    private final NamespacedKey keyMatch;
    private final NamespacedKey keySide;
    private final NamespacedKey keyOwner;
    private final NamespacedKey keyTaunt;
    private final NamespacedKey keyCard;
    private final NamespacedKey keyBuilding;
    private final NamespacedKey keyObjective;
    private final OwnershipTable table = new OwnershipTable();

    /**
     * ★★ <b>「这个人已经不在了」的阳性证据</b>（2026-10-17 第十轮，缺陷 P0-c）——
     * 由 {@code match/UnitLifecycleListener} 在 {@code EntityDeathEvent} 上打标，
     * 由 {@link #cleanup} / {@link #forget} 清标 ⇒ 生命周期被账本条目圈死。
     *
     * <p>为什么要它：判「实体还在不在」时，{@code Bukkit.getEntity(uuid) == null} 有**两种**
     * 含义（区块未加载 / 真的没了），而这两种的处理方式相反
     * （前者要等、后者要立刻落袋）。死亡事件是唯一能把它们分开的**阳性**信号。
     */
    private final Set<UUID> deadEntities = new LinkedHashSet<>();

    public UnitRegistry(Plugin plugin) {
        this.keyMatch = new NamespacedKey(plugin, KEY_MATCH);
        this.keySide = new NamespacedKey(plugin, KEY_SIDE);
        this.keyOwner = new NamespacedKey(plugin, KEY_OWNER);
        this.keyTaunt = new NamespacedKey(plugin, KEY_TAUNT);
        this.keyCard = new NamespacedKey(plugin, KEY_CARD);
        this.keyBuilding = new NamespacedKey(plugin, KEY_BUILDING);
        this.keyObjective = new NamespacedKey(plugin, KEY_OBJECTIVE);
    }

    /** 纯账本（测试与排障用）。 */
    public OwnershipTable table() {
        return table;
    }

    /** 打标 + 登记：**本插件接管一个实体的唯一入口**。 */
    public void tag(Entity entity, OwnershipTable.Owner owner) {
        tag(entity, owner, false, null);
    }

    /**
     * 打标 + 登记（带嘲讽）。
     *
     * @param taunted 是否带嘲讽（**布尔，无等级**）；召唤物默认 {@code false}，
     *                只有卡牌里明确标了 `taunt: true` 的才有；信标 / 防御塔为 {@code true}
     */
    public void tag(Entity entity, OwnershipTable.Owner owner, boolean taunted) {
        tag(entity, owner, taunted, null);
    }

    /**
     * ★★ <b>打标 + 登记（带嘲讽 + 带「是哪张卡」）</b>—— 2026-10-17 第十二轮起的生产入口。
     *
     * @param taunted  是否带嘲讽（**布尔，无等级**；召唤物默认 {@code false}）
     * @param cardId   ★ 召唤它的那张卡的 id（{@code null} = 不写这一格）。
     *                 它只服务于「这个召唤物选目标时优不优先打建筑」
     *                 （{@code summon.affected-by-building}）—— 见 {@link #cardIdOf(Entity)}。
     *                 ★ <b>只存 id、不存布尔</b>：卡 YAML 改了之后，已经站在场上的实体
     *                 也会跟着用新语义（否则就是"两个事实来源"）。
     */
    public void tag(Entity entity, OwnershipTable.Owner owner, boolean taunted, String cardId) {
        tag(entity, owner, taunted, cardId, false);
    }

    /**
     * ★★★ <b>打标 + 登记（带嘲讽 ＋ 带卡 id ＋ 带「算不算建筑」）</b> —— 2026-10-19 起的生产入口。
     *
     * @param building 是否算「建筑」（见 {@link #KEY_BUILDING}）。★ 缺省 {@code false}：
     *                 除出厂那 6 张卡与信标 / 防御塔之外，其余一切都**不是**建筑。
     */
    public void tag(Entity entity, OwnershipTable.Owner owner, boolean taunted, String cardId,
                    boolean building) {
        if (entity == null || owner == null) {
            return;
        }
        entity.getPersistentDataContainer().set(keyMatch, PersistentDataType.STRING, owner.matchId());
        entity.getPersistentDataContainer().set(keySide, PersistentDataType.STRING, owner.side().name());
        entity.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING,
                owner.player() == null ? "" : owner.player().toString());
        entity.getPersistentDataContainer().set(keyTaunt, PersistentDataType.BYTE,
                (byte) (taunted ? 1 : 0));
        entity.getPersistentDataContainer().set(keyBuilding, PersistentDataType.BYTE,
                (byte) (building ? 1 : 0));
        if (cardId != null && !cardId.isBlank()) {
            entity.getPersistentDataContainer().set(keyCard, PersistentDataType.STRING, cardId);
        }
        table.register(entity.getUniqueId(), owner);
    }

    /**
     * ★★ 这个实体算不算「建筑」（读 PDC；读不到 ⇒ {@code false}）。
     *
     * <p>★ 与 {@link #taunted(Entity)} <b>是两个不同的键</b>，别再合并：
     * 2026-10-19 之前「优先打谁」这一维借用了嘲讽标记（{@code KEY_TAUNT}），
     * 而「嘲讽」本身仍是**另一个玩法事实**（塔自己的索敌也读它，
     * 见 {@code objective/ObjectiveService#isTaunt}）⇒ 两件事必须能分开。
     */
    public boolean building(Entity entity) {
        if (entity == null) {
            return false;
        }
        Byte value = entity.getPersistentDataContainer().get(keyBuilding, PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    /** 按 UUID 判「算不算建筑」（实体解析不到 ⇒ {@code false}）。 */
    public boolean building(java.util.UUID entityId) {
        return entityId != null && building(org.bukkit.Bukkit.getEntity(entityId));
    }

    /**
     * ★★★ <b>登记一个「目标物」（信标 / 防御塔的**载体**）</b> —— 2026-10-21 新增。
     *
     * <p>它做三件事，缺一不可：
     * <ol>
     *   <li>与 {@link #tag} 完全相同的登记 ＋ 打标（matchId / side / owner=空 / 嘲讽 / 卡 id）；</li>
     *   <li>{@code building = true}（2026-10-19 用户裁定「信标防御塔都算建筑」）；</li>
     *   <li>★ 额外打上 {@link #KEY_OBJECTIVE}（**只有载体有**，见那个键的 javadoc ——
     *       展示实体绝不能带它，否则射线会去"伤害"一个无敌的外观实体）。</li>
     * </ol>
     *
     * <p>★ 生产侧**唯一**调用点 = {@code objective/ObjectiveUnit#spawn}（载体）；
     * 展示实体仍走 {@link #tag(Entity, OwnershipTable.Owner, boolean, String, boolean)}。
     */
    public void tagObjective(Entity entity, OwnershipTable.Owner owner, boolean taunted) {
        if (entity == null || owner == null) {
            return;
        }
        tag(entity, owner, taunted, null, true);   // 目标物天然是建筑（2026-10-19 裁定）
        entity.getPersistentDataContainer().set(keyObjective, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * ★★ <b>这个实体是目标物（载体）吗</b>（读 PDC；读不到 ⇒ {@code false}）。
     *
     * <p>★ 判据来源是<b>明确的标记</b>，不是启发式：不能用
     * 「{@code cardId == null} ＋ {@code building}」代替（展示实体三者全中，见 {@link #KEY_OBJECTIVE}）。
     */
    public boolean isObjective(Entity entity) {
        if (entity == null) {
            return false;
        }
        Byte value = entity.getPersistentDataContainer().get(keyObjective, PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    /** 按 UUID 判「是不是目标物载体」（实体解析不到 ⇒ {@code false}）。 */
    public boolean isObjective(java.util.UUID entityId) {
        return entityId != null && isObjective(org.bukkit.Bukkit.getEntity(entityId));
    }

    /** 从 PDC 读「是否带嘲讽」（读不到 ⇒ false）。 */
    public boolean taunted(Entity entity) {
        if (entity == null) {
            return false;
        }
        Byte value = entity.getPersistentDataContainer().get(keyTaunt, PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    /**
     * ★★ <b>从 PDC 读「召唤它的那张卡的 id」</b>（读不到 ⇒ {@code null}）——
     * 2026-10-17 第十二轮新增，缺陷 P1-a 的接线（见 {@link #KEY_CARD}）。
     *
     * <p>用途只有一个：{@code battle/CombatDirector} 拿它去卡池现查
     * {@code Card.Summon#affectedByBuilding()}（「这个召唤物选目标时优不优先打建筑」）。
     * <p>★ 读不到 ⇒ {@code null}，由调用方按 <b>false（只看距离）</b> 处理 ——
     * 也就是「没写这一格的卡」与「老实体（加这个键之前生成的）」都保持既有行为。
     */
    public String cardIdOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        String value = entity.getPersistentDataContainer().get(keyCard, PersistentDataType.STRING);
        return value == null || value.isBlank() ? null : value;
    }

    /** 从 PDC 读「召唤它的那张卡的 id」（按 UUID；实体解析不到 ⇒ {@code null}）。 */
    public String cardIdOf(UUID entity) {
        return entity == null ? null : cardIdOf(Bukkit.getEntity(entity));
    }

    /** 从 PDC 读归属（**不查账本**）——用于「删之前再核一次」。 */
    public OwnershipTable.Owner ownerFromPdc(Entity entity) {
        if (entity == null) {
            return null;
        }
        String matchId = entity.getPersistentDataContainer().get(keyMatch, PersistentDataType.STRING);
        if (matchId == null) {
            return null;
        }
        String sideName = entity.getPersistentDataContainer().get(keySide, PersistentDataType.STRING);
        String ownerText = entity.getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING);

        Side side = Side.BLUE;
        if (sideName != null) {
            try {
                side = Side.valueOf(sideName);
            } catch (IllegalArgumentException ignored) {
                // 存量数据里出现了非法阵营：保守当成蓝方，并在下面由「归属不符」拦下
            }
        }
        UUID player = null;
        if (ownerText != null && !ownerText.isBlank()) {
            try {
                player = UUID.fromString(ownerText);
            } catch (IllegalArgumentException ignored) {
                player = null;
            }
        }
        return new OwnershipTable.Owner(matchId, side, player);
    }

    /** 该实体是否被**这一场**登记过（账本 + PDC 都同意才算）。 */
    public boolean isOwnedBy(Entity entity, String matchId) {
        if (entity == null) {
            return false;
        }
        OwnershipTable.Owner fromPdc = ownerFromPdc(entity);
        return fromPdc != null && fromPdc.matchId().equals(matchId)
                && table.isOwnedBy(entity.getUniqueId(), matchId);
    }

    /**
     * ★★ <b>这个实体是不是「某一方的人」</b>（2026-10-17 第十二轮新增，缺陷 P0-d 的判据）。
     *
     * <h2>它服务于哪一格</h2>
     * {@code battle/SummonCombat#reassert} 每 tick 要问一句「实体当下挂着的目标是不是**友方**」
     * —— 是的话必须按 R27 清掉（那就是「哨位会攻击己方召唤物」的修法）。
     * <b>只查账本</b>（不看 PDC、不查世界）：这是每 tick 都会调的高频路径，
     * 而账本本身就是内存里的 {@code LinkedHashMap} 查询。
     *
     * <h2>★ 口径：读不到 ⇒ {@code false}（不是友方）</h2>
     * 「读不到」有两种来源：① 它根本不是我们的实体（别人的怪、服主的动物）；
     * ② 它属于**别的对局**。两种情况都<b>不该**被我们当成友方去清它的目标。
     * ⇒ 保守返回 {@code false}，由调用方按「未知 ⇒ 交给 SET 处理」走。
     *
     * @param entity 要判的实体 UUID
     * @param side   我们自己的阵营
     */
    public boolean isAllyOf(UUID entity, Side side) {
        if (entity == null || side == null) {
            return false;
        }
        OwnershipTable.Owner owner = table.owner(entity);
        return owner != null && owner.side() == side;
    }

    /** 同上，按实体对象（便捷重载）。 */
    public boolean isAllyOf(Entity entity, Side side) {
        return entity != null && isAllyOf(entity.getUniqueId(), side);
    }

    /**
     * ★ <b>这个 UUID 现在还登记在账本里吗</b>（2026-10-17 第十轮新增，缺陷 P0-c 的判据）。
     *
     * <h2>为什么它是「目标物还活着吗」的正确判据</h2>
     * 第十轮之前，{@code ObjectiveUnit#isAlive} 用的是 {@code carrier.isValid()}，
     * 而 <b>区块卸载会把实体引用变成 invalid</b> ⇒ 现场出现两件怪事（`logs/latest.log` 有据）：
     * <ul>
     *   <li>18:21:41 收尾只报「目标物已回收 <b>3</b> 个」（生成的是 6 个）；</li>
     *   <li>{@code 清场完成：已删除 0 / 登记 11（<b>已失效 11</b>）} —— 一个都没删掉，
     *       而账本已经被清空 ⇒ <b>那 6 个目标物永远留在世界里</b>
     *       （现场后续还有一串 {@code 红方防御塔·左 suffocated in a wall}，
     *        正是这些"上一局的塔"在慢慢窒息死掉）。</li>
     * </ul>
     * ⇒ 现在「死了」只能由**阳性证据**得出（{@link #markDead}），
     * 而「账本里还在」= 还活着。
     */
    public boolean isTracked(UUID entity) {
        return entity != null && table.owner(entity) != null;
    }

    /**
     * ★★ <b>阳性死亡证据</b>（2026-10-17 第十轮新增，缺陷 P0-c）。
     *
     * <h2>为什么是「标记」而不是「立刻 forget」</h2>
     * {@code ability/AbilityTriggerListener} 的 {@code ON_DEATH}（亡语）能力是在
     * {@code EntityDeathEvent} 的 {@code MONITOR} 上判断「这个实体是不是我们的」
     * （它查的就是账本）⇒ 若死亡监听器<b>当场把账本条目删掉</b>，
     * 亡语就会随监听器注册顺序时有时无（最糟的那类"偶发"）。
     * ⇒ 死亡只<b>打一个标记</b>；账本条目留到下一次 {@link #cleanup} 再收
     * （那时清场会把标记过的条目当成"已经不在了"直接落袋）。
     *
     * <p>★ 标记的生命周期被 {@link #cleanup} / {@link #forget} 严格圈住
     * ⇒ 它不会跨局增长（上限 = 本局死掉的实体数）。
     */
    public void markDead(UUID entity) {
        if (entity != null && table.owner(entity) != null) {
            deadEntities.add(entity);
        }
    }

    /** 有没有「这个人已经不在了」的阳性证据（见 {@link #markDead}）。 */
    public boolean isMarkedDead(UUID entity) {
        return entity != null && deadEntities.contains(entity);
    }

    /** 本场登记的实体 UUID（保序）。 */
    public Set<UUID> tracked(String matchId) {
        return table.entitiesOf(matchId);
    }

    /** 本场登记数量。 */
    public int sizeOf(String matchId) {
        return table.sizeOf(matchId);
    }

    /** 注销一个实体（实体被别的机制删掉时调用，避免账本越积越大）。 */
    public void forget(UUID entity) {
        deadEntities.remove(entity);
        table.remove(entity);
    }

    /**
     * ★★ <b>清场结果对账</b>（2026-10-17 第十轮新增，缺陷 P0-c 的落点）。
     *
     * @param matchId    哪一场
     * @param registered 清场前账本里登记的条数
     * @param removed    ★ **真的删掉**的条数
     * @param vanished   ★ 有**阳性死亡证据**、实体确实已经不在世界的条数（无需删除，账实相符）
     * @param pending    ★ **没能删掉、留在账本里等重试**的条数（区块未加载 ⇒ 解析不到实体）
     * @param mismatched 归属不符（PDC 说它已不属于本场）⇒ **故意不删**的条数
     */
    public record CleanupReport(String matchId, int registered, int removed, int vanished,
                                int pending, int mismatched) {

        public CleanupReport {
            if (registered < 0 || removed < 0 || vanished < 0 || pending < 0 || mismatched < 0) {
                throw new IllegalArgumentException("清场计数不能是负数");
            }
        }

        /**
         * ★ <b>恒等式</b>：登记的每一条都必须落进恰好一个桶
         * （删掉 / 已确认不在 / 待回收 / 归属不符）。
         * 任何「生成了却谁都没算」的实体都会让它变 false —— 这就是漏回收的报警线。
         */
        public boolean balanced() {
            return removed + vanished + pending + mismatched == registered;
        }

        /** 是不是**一个都不剩**。 */
        public boolean fullyRemoved() {
            return pending == 0;
        }

        public String describe() {
            return "已删除 " + removed + " / 登记 " + registered
                    + "（已确认不在 " + vanished + "，待回收 " + pending
                    + "，归属不符 " + mismatched + "）";
        }
    }

    /**
     * ★ <b>已注销但没能删掉的实体</b>（{@code matchId} → 已经重试过几轮）。
     *
     * <p>它只服务于一件事：把「区块未加载」与「实体已被原版移除」这两种
     * <b>在账本层面长得一模一样</b>的情况分开 —— 前者等得到、后者等不到，
     * 所以必须给一个**有界**的等待（见 {@link OwnershipTable#MAX_CLEANUP_RETRIES}）。
     */
    private final Map<String, Integer> pendingRounds = new LinkedHashMap<>();

    /**
     * ★ <b>清场</b>：只删除本场登记过、且**现在仍然属于本场**的实体。
     *
     * <h2>2026-10-17 第十轮改了什么（缺陷 P0-c）</h2>
     * 旧实现无论删掉几个，最后都无条件 {@code table.removeMatch(matchId)} ⇒
     * 「一个都没删掉」时账本照样被清空 ⇒ 那些实体**永远失去了被删的机会**
     * （现场 18:21:41 的原文就是 {@code 已删除 0 / 登记 11}，而下一局那个坐标上
     * 又冒出一个同名防御塔 ⇒ 越积越多）。
     * ⇒ 现在：<b>删不掉的留在账本里</b>（{@link CleanupReport#pending()}），
     * 由 {@link #retryPendingRemovals} 每秒重试一次，直到删掉或用完重试机会（会大声告警）。
     *
     * @return 对账结果（★ 带数量，见 {@link CleanupReport}）
     */
    public CleanupReport cleanup(String matchId, Logger logger) {
        Set<UUID> uuids = table.entitiesOf(matchId);
        if (uuids.isEmpty()) {
            // ★ fail-safe：账本为空就什么都不做。绝不「顺手按世界清一下」。
            //   用 INFO 而不是 WARNING：**大多数对局本来就没有召唤物**（P2 阶段全部如此），
            //   每次结束都告警会把真正的告警淹掉。消息本身照旧写明这是 fail-safe 分支。
            if (logger != null) {
                logger.info("[IC] 清场跳过：matchId=" + matchId
                        + " 的追踪表为空 —— 按 fail-safe 不做任何删除（绝不退化成按世界清）");
            }
            pendingRounds.remove(matchId);
            return new CleanupReport(matchId, 0, 0, 0, 0, 0);
        }

        int removed = 0;
        int vanished = 0;
        int pending = 0;
        int mismatched = 0;
        for (UUID id : uuids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                // ★★ 解析不到 **≠** 已经没了：
                //   ① 区块没加载时也是这个结果 ⇒ 必须留在账本里等重试
                //      （这是第十轮修掉的那条「永远删不掉」）；
                //   ② 但若我们**有阳性死亡证据**（EntityDeathEvent，见 markDead），
                //      那就确实无需删除 —— 直接落袋并从账本注销，免得每秒白重试。
                if (deadEntities.remove(id)) {
                    table.remove(id);
                    vanished++;
                    continue;
                }
                pending++;
                continue;
            }
            // ★ 删之前再核一次归属：防 UUID 复用 / 跨局串场
            if (!isOwnedBy(entity, matchId)) {
                mismatched++;
                continue;
            }
            entity.remove();
            deadEntities.remove(id);
            table.remove(id);
            removed++;
        }

        if (pending == 0) {
            // 全部落袋 ⇒ 才允许把这一场的账本清掉
            table.removeMatch(matchId);
            pendingRounds.remove(matchId);
        }
        CleanupReport report = new CleanupReport(matchId, uuids.size(), removed, vanished,
                pending, mismatched);
        // ★ 收尾/重试都在删，日志交给调用方（重试时每秒一条会刷屏）
        if (logger != null) {
            logger.info("[IC] 清场完成：matchId=" + matchId + " " + report.describe()
                    + "｜对账" + (report.balanced() ? "通过" : "未通过（见上面的四个桶）")
                    + (pending > 0
                            ? "｜待回收的会在下一 tick 起的每秒重试里删除（区块未加载时删不了，"
                                    + "绝不退化成按世界清）"
                            : ""));
        }
        return report;
    }

    /** 某一场的账本清干净了吗（重试扫描用；没有登记也算干净）。 */
    public boolean isClean(String matchId) {
        return table.sizeOf(matchId) == 0;
    }

    /** 还没清干净、且**不是**进行中对局的 matchId（保序）。 */
    public List<String> pendingMatchIds(Set<String> activeMatchIds) {
        return table.matchIdsNotIn(activeMatchIds);
    }

    /**
     * ★★ <b>把「没能删掉的」再删一遍</b>（2026-10-17 第十轮新增，缺陷 P0-c 的收口）。
     *
     * <h2>铁律 10 的四问（这是唯一一处「会延迟发生的删除」）</h2>
     * <ol>
     *   <li><b>范围</b>：只遍历 {@code pendingMatchIds(active)} —— 即
     *       <b>本插件自己登记过、且对局已经不在进行中</b>的 {@code matchId}；
     *       绝不查世界、绝不按半径；</li>
     *   <li><b>失败方向</b>：解析不到就**不删**（{@code pending}），重试到
     *       {@link OwnershipTable#MAX_CLEANUP_RETRIES} 轮为止；用完就注销 + <b>大声告警</b>
     *       （宁可留一条明确的 WARN，也不留一个无限增长的账本，更不按世界乱删）；</li>
     *   <li><b>归属判定</b>：与 {@link #cleanup} 完全同一条路径（PDC 三键 + 账本双保险，
     *       删之前再核一次）；</li>
     *   <li><b>可对账</b>：每次真的删掉东西就打一行带数量的日志
     *       （「遗留实体 已补删 N 个」）。</li>
     * </ol>
     *
     * @param activeMatchIds 进行中的对局（**必须排除**，否则会把正在打的对局清掉）
     * @return 这一轮真的删掉的实体总数
     */
    public int retryPendingRemovals(Set<String> activeMatchIds, Logger logger) {
        List<String> pending = table.matchIdsNotIn(activeMatchIds);
        if (pending.isEmpty()) {
            pendingRounds.clear();
            return 0;
        }
        int deletedTotal = 0;
        for (String matchId : pending) {
            // ★ 静默清场：重试每秒一次，逐场打印会和别的日志一起刷屏
            CleanupReport report = cleanup(matchId, null);
            deletedTotal += report.removed();
            if (report.removed() > 0) {
                if (logger != null) {
                    logger.info("[IC] 上局遗留实体已补删 " + report.removed() + " 个（matchId=" + matchId
                            + "，" + report.describe() + "）"
                            + (report.fullyRemoved() ? "｜本场遗留已全部回收" : ""));
                }
            }
            if (report.fullyRemoved()) {
                pendingRounds.remove(matchId);
                continue;
            }
            int rounds = pendingRounds.merge(matchId, 1, Integer::sum);
            if (OwnershipTable.shouldGiveUpCleanup(rounds) && logger != null) {
                logger.warning("[IC] 有 " + report.pending() + " 个实体连续 " + rounds
                        + " 轮回收失败（matchId=" + matchId + "），从账本注销，不再重试。"
                        + "原因通常是：它们所在的区块一直没加载（玩家已离开竞技场），"
                        + "或它们已被原版移除（自然消失的召唤物不触发死亡事件）。"
                        + "这件事只能由运维处理：请到 /incl arena 记录的点位附近看一眼，"
                        + "必要时用原版指令清理；本插件绝不按世界/按半径清（铁律 10）");
                table.removeMatch(matchId);
                pendingRounds.remove(matchId);
            }
        }
        return deletedTotal;
    }

    /** 还没清干净的场次数（排障 / 测试用）。 */
    public int pendingMatchCount() {
        return pendingRounds.size();
    }

    /** 只清账本、不碰实体（插件停用时的兜底：此时动实体已不安全）。 */
    public int forgetMatch(String matchId) {
        pendingRounds.remove(matchId);
        for (UUID id : table.entitiesOf(matchId)) {
            deadEntities.remove(id);
        }
        return table.removeMatch(matchId).size();
    }
}
