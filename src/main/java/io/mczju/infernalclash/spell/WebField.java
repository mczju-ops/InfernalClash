package io.mczju.infernalclash.spell;

import io.mczju.infernalclash.match.LifecycleTasks;
import io.mczju.infernalclash.policy.WebBlockRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ★★★ <b>「结网」的生成 / 接管 / 删除</b>（1.1.0「囚笼编织者」）。
 *
 * <h2>出处（{@code docs/1.1卡牌.txt}）</h2>
 * 「召唤一只蜘蛛，该蜘蛛会在<b>攻击目标处结网</b>，网会存在 <b>8 秒</b>」＋
 * 「织网仿照 IG 的『webber』词条，写<b>生成、接管和删除</b>蜘蛛网的逻辑。
 * 场地和 ig 一样是大平地不用担心替换方块，<b>除了岩浆，不要替换岩浆</b>」。
 * ★ 用户 2026-10-26 追问后把口径收得更严：<b>只替换空气与软方块</b>
 * （判据 = 纯类 {@link WebBlockRule}）。
 *
 * <h2>三段（每段都能对账）</h2>
 * <ol>
 *   <li><b>生成</b>：在目标**脚下那一格**放 {@code COBWEB}，但先存下原方块的 {@link BlockData}；</li>
 *   <li><b>接管</b>：登记进本对局的表（键 = 方块坐标）。★ 每局上限
 *       {@value #MAX_WEBS_PER_MATCH} 格 —— 蜘蛛可以无限攻击，没有上限就是"整张地图被网覆盖"；</li>
 *   <li><b>删除</b>：①到期（{@code durationTicks}）还原；②<b>对局收尾强制还原</b>
 *       （{@link LifecycleTasks#onCancel}）—— 否则"对局在 8 秒内结束"会让网永久留在地板上；
 *       ③ 只还原**还是蜘蛛网**的那一格（玩家/别的插件改过就不碰，铁律 10：宁可少做）。</li>
 * </ol>
 *
 * <h2>为什么用静态表</h2>
 * 方块没有 PDC、也没有"归属"字段（不像实体能打标记），而本机制由**能力效果**触发
 * （{@code EffectApplier} 是静态入口，拿不到每局的服务实例）。⇒ 与
 * {@code EffectApplier.REDUCTION_WINDOWS} 同一套做法：按 {@code matchId} 分表、收尾清空。
 * ★ 表里只有坐标与"原来是什么方块"，没有任何实体引用 ⇒ 不会拖住实体、不会跨局串。
 */
public final class WebField {

    /** 每局最多同时存在多少格蜘蛛网（防御性上限，防止"整张图被网覆盖"）。 */
    public static final int MAX_WEBS_PER_MATCH = 200;

    /** {@code matchId → (方块坐标 → 原方块数据)}。 */
    private static final Map<String, Map<Location, BlockData>> PLACED = new LinkedHashMap<>();

    /** {@code matchId → 已登记过的收尾回调}（{@code LifecycleTasks#onCancel} 按对象去重）。 */
    private static final Map<String, Runnable> HOOKS = new LinkedHashMap<>();

    private WebField() {
    }

    /**
     * 在目标脚下结一张网。
     *
     * @param matchId   对局 id（分表键）
     * @param target    被攻击的目标（网长在它脚下那一格）
     * @param duration  存在 tick 数
     * @param tasks     ★ 本局的生命周期任务表；{@code null} ⇒ <b>不结网</b>
     *                  （没有任务表就没有"到期还原"，宁可少一张网也不留永久遗迹）
     * @param plugin    调度器来源
     */
    public static boolean place(String matchId, LivingEntity target, int duration,
                                LifecycleTasks tasks, Plugin plugin, Logger log) {
        if (matchId == null || target == null || target.getLocation().getWorld() == null) {
            return false;
        }
        if (tasks == null || plugin == null) {
            log.warning("[IC] 结网跳过：没有可用的生命周期任务表/插件实例 —— "
                    + "没有它就无法保证 8 秒后还原，宁可不结网");
            return false;
        }
        Map<Location, BlockData> table = PLACED.computeIfAbsent(matchId,
                key -> new LinkedHashMap<>());
        if (table.size() >= MAX_WEBS_PER_MATCH) {
            log.warning("[IC] 结网跳过：本局蜘蛛网已达上限 " + MAX_WEBS_PER_MATCH
                    + " 格（防御性上限，避免整张地图被网覆盖）");
            return false;
        }
        Block block;
        try {
            block = target.getLocation().getBlock();
        } catch (Throwable t) {
            log.warning("[IC] 结网跳过：取不到目标脚下的方块（实体已卸载？）：" + t);
            return false;
        }
        Material type;
        try {
            type = block.getType();
        } catch (Throwable t) {
            log.warning("[IC] 结网跳过：读不到方块类型：" + t);
            return false;
        }
        if (!WebBlockRule.replaceable(type.isSolid(), type.name())) {
            log.fine("[IC] 结网跳过：" + WebBlockRule.describe(type.isSolid(), type.name()));
            return false;
        }
        Location key = block.getLocation();
        if (table.containsKey(key)) {
            // 已经有一张网在这里 ⇒ 只续期，不再记一遍原方块（否则到期会还原成"上一张网"）
            try {
                block.setType(Material.COBWEB, false);
            } catch (Throwable t) {
                log.warning("[IC] 结网失败（续期）：" + t);
                return false;
            }
            scheduleRestore(matchId, key, duration, tasks, plugin, log);
            return true;
        }
        try {
            BlockData previous = block.getBlockData();
            block.setType(Material.COBWEB, false);
            table.put(key, previous);
        } catch (Throwable t) {
            log.warning("[IC] 结网失败（只影响这一张网，不影响伤害结算）：" + t);
            return false;
        }
        log.info("[IC] 已结网 @" + key.getBlockX() + "," + key.getBlockY() + ","
                + key.getBlockZ() + "（" + duration + " tick 后还原为 "
                + describe(table.get(key)) + "）");
        scheduleRestore(matchId, key, duration, tasks, plugin, log);
        // ★ 收尾强制还原：对局可能在这 8 秒内结束（那时到期任务会被 cancelAll 掉）
        Runnable hook = HOOKS.computeIfAbsent(matchId, id -> () -> restoreAll(id, log));
        tasks.onCancel(hook);
        return true;
    }

    /** 到期还原（{@code runLater} 一次；失败只告警）。 */
    private static void scheduleRestore(String matchId, Location key, int duration,
                                        LifecycleTasks tasks, Plugin plugin, Logger log) {
        tasks.runLater(plugin, () -> restoreOne(matchId, key, log), duration);
    }

    /** 还原一格：**只有它还是蜘蛛网时**才还原（别人改过就不碰）。 */
    private static void restoreOne(String matchId, Location key, Logger log) {
        Map<Location, BlockData> table = PLACED.get(matchId);
        if (table == null) {
            return;
        }
        BlockData previous = table.remove(key);
        if (previous == null) {
            return;
        }
        try {
            World world = key.getWorld();
            if (world == null) {
                return;
            }
            Block block = key.getBlock();
            if (block.getType() != Material.COBWEB) {
                log.fine("[IC] 蜘蛛网那一格已被改动（现在是 " + block.getType()
                        + "）⇒ 不还原（铁律 10：宁可少做）");
                return;
            }
            block.setBlockData(previous, false);
        } catch (Throwable t) {
            log.warning("[IC] 蜘蛛网还原失败（这一格会留在世界里，需要人工清理）：" + t);
        }
    }

    /** 对局收尾：把本局**所有**还留着的网还原（带数量日志，可对账）。 */
    public static int restoreAll(String matchId, Logger log) {
        Map<Location, BlockData> table = PLACED.remove(matchId);
        HOOKS.remove(matchId);
        if (table == null || table.isEmpty()) {
            return 0;
        }
        int restored = 0;
        for (Map.Entry<Location, BlockData> entry : table.entrySet()) {
            try {
                Location key = entry.getKey();
                World world = key == null ? null : key.getWorld();
                if (world == null) {
                    continue;
                }
                Block block = key.getBlock();
                if (block.getType() != Material.COBWEB) {
                    continue;
                }
                block.setBlockData(entry.getValue(), false);
                restored++;
            } catch (Throwable t) {
                log.warning("[IC] 收尾还原蜘蛛网失败（那一格需要人工清理）：" + t);
            }
        }
        log.info("[IC] 对局收尾：已还原 " + restored + " 格蜘蛛网（本局共登记 " + table.size()
                + " 格｜matchId=" + matchId + "）");
        return restored;
    }

    /** 本局当前还登记着多少格网（探针 / 测试用）。 */
    public static int tracked(String matchId) {
        Map<Location, BlockData> table = matchId == null ? null : PLACED.get(matchId);
        return table == null ? 0 : table.size();
    }

    /** 清空全部（插件停用 / 单测用）。 */
    public static void clear() {
        PLACED.clear();
        HOOKS.clear();
    }

    private static String describe(BlockData data) {
        return data == null ? "?" : data.getMaterial().name();
    }
}
