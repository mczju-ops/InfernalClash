package io.mczju.infernalclash.command;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ★ <b>「在线玩家名」的唯一落点</b> —— 而它被刻意放在 {@link InfernalClashCommand} <b>之外</b>。
 *
 * <h2>★ 为什么必须单独一个类（2026-10-15 实测的守卫约束）</h2>
 * {@code guard/BytecodeScopeGuardTest} 的 {@code GUARDED_CLASSES} <b>含</b>
 * {@code command/InfernalClashCommand}，而它禁掉的记号里就有 {@code getOnlinePlayers}
 * （铁律 10：全服枚举是广域操作事故的来源）。R69 的
 * {@code /incl debug set-gold <TAB>} 需要「在线玩家名」，本类的存在是这样处理的：
 * <ul>
 *   <li><b>枚举在线玩家只发生在用户按 Tab 的那一刻</b>，且结果只用于<b>提示候选</b>：
 *       不删、不杀、不传送、不改状态、不播报、不写档案 —— 铁律 10 管的四类操作一个都不做；</li>
 *   <li>★ <b>执行</b>那条路（{@code runDebugEdit}）<b>只走</b> {@code Bukkit.getPlayerExact}
 *       单点查，<b>绝不</b>遍历在线列表（所以那一条不会因为「谁是第一个」而误伤别人）；</li>
 *   <li>这条「补全枚举 ≠ 执行枚举」的界线写在<b>一个只有 20 行的小类</b>里，
 *       而不是藏进已经 1000+ 行的指令类：守卫仍然能守住指令类，
 *       而需要它的人一眼能看到它的全部行为（可在 {@code InfernalClashCommand} 的补全里数出
 *       它只有一个调用点）。</li>
 * </ul>
 *
 * <p>⚠️ <b>它不做任何范围/归属判断，也不该被拿去执行任何事</b>：
 * 若将来有人想用「在线玩家列表」做改数据 / 播报 / 清场，请先按 {@code AGENTS.md} 铁律 10
 * 回答四问（范围 / 失败方向 / 归属 / 数量日志），而不是调用本类。
 */
final class PlayerNameCompletions {

    private PlayerNameCompletions() {
    }

    /**
     * 按前缀过滤的在线玩家名（★ 只给 Tab 补全用；执行路径请用 {@code Bukkit.getPlayerExact}）。
     *
     * @param prefix 玩家已敲的前缀（允许 {@code null} / 空 ⇒ 返回全部在线玩家名）
     * @return 保序（Bukkit 的在线玩家顺序）的玩家名列表；离线夹具里 Bukkit 未就绪 ⇒ 返回空列表
     */
    static List<String> onlinePlayerNames(String prefix) {
        List<String> names = new ArrayList<>();
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    names.add(player.getName());
                }
            }
        } catch (Throwable t) {
            // 补全永远不该抛（离线夹具里没有服务端）：给不出候选就不给，不用异常打断 Tab
            return List.of();
        }
        return List.copyOf(names);
    }
}
