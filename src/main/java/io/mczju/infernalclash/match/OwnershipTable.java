package io.mczju.infernalclash.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ★ <b>归属账本</b>（纯类，可离线单测）—— 铁律 10「归属判定」的可测内核。
 *
 * <h2>为什么账本是纯类</h2>
 * 「谁属于我」这个判断如果混在实体操作里，就只能起服务器才测得了；
 * 而它恰恰是**最容易出错、后果最严重**的一环（隔壁项目把别人的史莱姆收编后删掉了）。
 * ⇒ 账本单独成类：登记 / 查询 / 按对局清空**全部可离线断言**，
 * {@link UnitRegistry} 只负责把它和 PDC 绑起来。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>一个实体在账本里**只能有一个归属**（重复登记 ⇒ 覆盖并告警，由调用方决定）；</li>
 *   <li>按 {@code matchId} 取出的 UUID 集合**保序**（`LinkedHashMap`）——
 *       「同输入同结果」在本项目是硬要求（`AGENTS.md` §4.2 的 `Map.copyOf` 坑）。</li>
 * </ul>
 */
public final class OwnershipTable {

    /**
     * 一个实体的归属。
     *
     * @param matchId 所属对局（= MGC 房间名）
     * @param side    阵营
     * @param player  召唤者 / 归属玩家（目标物用 `null` 表示「不属于任何玩家」）
     */
    public record Owner(String matchId, Side side, UUID player) {
    }

    /** 实体 UUID → 归属（保序）。 */
    private final Map<UUID, Owner> byEntity = new LinkedHashMap<>();

    /** 登记归属；返回被覆盖的旧归属（没有则 {@code null}）。 */
    public Owner register(UUID entity, Owner owner) {
        if (entity == null || owner == null) {
            throw new IllegalArgumentException("实体 UUID 与归属都不能为空");
        }
        return byEntity.put(entity, owner);
    }

    /** 取归属；未登记返回 {@code null}。 */
    public Owner owner(UUID entity) {
        return entity == null ? null : byEntity.get(entity);
    }

    /** 该实体是否登记在**这一场**里。 */
    public boolean isOwnedBy(UUID entity, String matchId) {
        Owner owner = owner(entity);
        return owner != null && owner.matchId().equals(matchId);
    }

    /**
     * 取某一场登记的全部实体 UUID（**保序、不可变**）。
     *
     * <p>★ 这就是「范围界定」：清场只可能作用于这个集合，**永远不可能是整个世界**。
     */
    public Set<UUID> entitiesOf(String matchId) {
        Set<UUID> result = new LinkedHashSet<>();
        for (Map.Entry<UUID, Owner> entry : byEntity.entrySet()) {
            if (entry.getValue().matchId().equals(matchId)) {
                result.add(entry.getKey());
            }
        }
        // ★ 用 LinkedHashSet 而不是 Set.copyOf：后者的迭代顺序未定义，
        //   会让「清理顺序 / 日志顺序」每次都不同（AGENTS.md §4.2 的 Map.copyOf 同类坑）
        return Collections.unmodifiableSet(result);
    }

    /** 某一场登记的数量。 */
    public int sizeOf(String matchId) {
        int count = 0;
        for (Owner owner : byEntity.values()) {
            if (owner.matchId().equals(matchId)) {
                count++;
            }
        }
        return count;
    }

    /** 账本总条数。 */
    public int size() {
        return byEntity.size();
    }

    /** 注销一个实体；返回它原来的归属（没有则 {@code null}）。 */
    public Owner remove(UUID entity) {
        return entity == null ? null : byEntity.remove(entity);
    }

    /**
     * 注销某一场的全部登记，返回被注销的 UUID 列表（保序）。
     *
     * <p>★ **必须在实体操作之后调用**：先按它去删实体（那时还查得到归属），再清账本。
     */
    public List<UUID> removeMatch(String matchId) {
        List<UUID> removed = new ArrayList<>();
        byEntity.entrySet().removeIf(entry -> {
            if (entry.getValue().matchId().equals(matchId)) {
                removed.add(entry.getKey());
                return true;
            }
            return false;
        });
        return List.copyOf(removed);
    }

    /** 清空账本（对局中止 / 插件停用时的兜底）。 */
    public void clear() {
        byEntity.clear();
    }

    /**
     * ★ 本表里出现过的全部 {@code matchId}（**保序、去重**）。
     *
     * <p>2026-10-17 第十轮新增（缺陷 P0-c）：清场时<b>没能真正删掉</b>的实体要留在账本里等重试，
     * 重试方需要知道「哪些对局的账本还没清干净」—— 这就是那个查询。
     * ★ 只返回 {@code matchId}（不含实体），且顺序稳定 ⇒ 可离线断言。
     */
    public List<String> matchIds() {
        Set<String> seen = new LinkedHashSet<>();
        for (Owner owner : byEntity.values()) {
            seen.add(owner.matchId());
        }
        return List.copyOf(seen);
    }

    /**
     * ★ 「还没清干净、且**不是**进行中的对局」的 {@code matchId}（保序）——
     * 清场重试只允许作用于这些对局。
     *
     * <p>★★ 为什么必须排除进行中的对局：进行中的对局账本里**当然**有实体
     * （塔、信标、召唤物都活着）⇒ 若重试扫描不去排除它们，就会把**正在打的对局**
     * 当成"遗留物"清掉（那是最严重的一类事故）。⇒ 判据放在这个纯函数里，
     * 由调用方把「活跃对局集合」传进来，可离线逐格断言。
     */
    public List<String> matchIdsNotIn(Set<String> activeMatchIds) {
        List<String> result = new ArrayList<>();
        for (String matchId : matchIds()) {
            if (activeMatchIds != null && activeMatchIds.contains(matchId)) {
                continue;
            }
            result.add(matchId);
        }
        return List.copyOf(result);
    }

    /**
     * ★ <b>清场重试给几次机会</b>（2026-10-17 第十轮，缺陷 P0-c）。
     *
     * <p>背景：清场只删「**能解析到**的自己登记的实体」。解析不到有两种原因：
     * <ol>
     *   <li>它所在的区块没加载（玩家已离开竞技场）⇒ <b>应该等</b> —— 区块一旦加载就能删掉；</li>
     *   <li>它已经被原版移除了（例如召唤物自然消失：原版 {@code despawn} **不触发**
     *       {@code EntityDeathEvent}）⇒ <b>永远等不到</b>。</li>
     * </ol>
     * 两者在账本层面长得一模一样（{@code Bukkit.getEntity(uuid) == null}）⇒
     * 不可能靠一次判断分开。⇒ 取「<b>等一小段时间再放弃</b>」：
     * 每次重试 1 秒 1 轮，{@code MAX_CLEANUP_RETRIES} 轮之后**注销并大声告警**
     * （★ 宁可留一条明确的 WARN，也不让账本无限增长、也不按世界乱删）。
     */
    public static final int MAX_CLEANUP_RETRIES = 60;

    /** 是否已经用完了重试机会（用于「注销 + 告警」的那一格）。 */
    public static boolean shouldGiveUpCleanup(int attempts) {
        return attempts >= MAX_CLEANUP_RETRIES;
    }

    /** 只读快照（排障与测试用）。 */
    public Map<UUID, Owner> snapshot() {
        return Map.copyOf(byEntity);
    }
}
