package io.mczju.infernalclash.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.game.room.JsonGameRoom;
import com.github.mczjuops.mczjugamecore.game.room.PlayerSelectable;
import io.mczju.infernalclash.InfernalClashPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * 本游戏的房间类（落盘于
 * {@code plugins/MCZJUGameCore/rooms/infernalclash/<房间名>.json}）。
 *
 * <p>MGC 要求每个游戏必须提供房间类（{@code registerGame} 的第二个参数）。
 * 房间是 MGC 的调度壳：{@code /mgc join} 挑一个 {@code READY} 房间来开局，
 * 局末置回 {@code READY} 供复用。序列化、{@code getFilePath()}、{@code deleteRoom()}
 * 全部由 {@link JsonGameRoom} 提供。
 *
 * <h2>★ 字段约定（MGC 1.1.2 实测）</h2>
 * 字段必须是 {@code public}（MGC 通过**反射**读写），且类型只支持
 * {@code Boolean} / {@code String} / {@link Location} / {@code Long} / {@code Float} / {@code Double}
 * —— 依据 {@code RoomParameterEditor.supports()} 的字节码。所以这里两个
 * {@link Location} 字段可以**直接在 MGC 的房间编辑菜单里改**：
 * <pre>
 *   /mgcop room create infernalclash default
 *   /mgcop room edit   infernalclash default
 * </pre>
 *
 * <h2>★ 两个地点各管什么（别混）</h2>
 * <table>
 *   <tr><th>字段</th><th>时机</th><th>未配置时</th></tr>
 *   <tr><td>{@link #waitLocation}</td><td>**加入房间、进入等待阶段**</td>
 *       <td>不传送（玩家留在原地）</td></tr>
 *   <tr><td>{@link #returnLocation}</td><td>**对局结束 / 中止 / 退出**</td>
 *       <td>回退到 {@link #waitLocation}</td></tr>
 * </table>
 *
 * <h2>★★ 为什么标了 {@link PlayerSelectable}（决定主菜单那一格的点击行为）</h2>
 * 这个注解**不是**「能不能加入本游戏」的开关，而是「玩家能不能**自选房间**」的开关。
 * MGC 主菜单里本游戏那一格的行为由**两个**布尔量共同决定 ——
 * {@code MainMenu.lambda$setupGames$1} 的字节码实测真值表
 * （依据 {@code javap -c}，2026-10-16 复核；文档见 {@code docs/06-MGC对接.md} §2.16）：
 *
 * <table border="1">
 *   <caption>左键 / 右键各自走哪个分支</caption>
 *   <tr><th>{@code playerSelectable}<br>（= 房间类有没有本注解）</th>
 *       <th>{@code hasLobby}<br>（= {@code lobbies.json} 里有没有本 gameId）</th>
 *       <th>左键</th><th>右键</th></tr>
 *   <tr><td>{@code true}</td><td>{@code true}</td>
 *       <td><b>传送到游戏大厅</b></td><td><b>打开房间列表</b></td></tr>
 *   <tr><td>{@code true}</td><td>{@code false}</td><td>直接入局</td><td>（无动作）</td></tr>
 *   <tr><td>{@code false}</td><td>{@code true}</td><td>直接入局</td><td>打开房间列表</td></tr>
 *   <tr><td>{@code false}</td><td>{@code false}</td><td>直接入局</td><td>（无动作）</td></tr>
 * </table>
 *
 * <p>⇒ ★ <b>「左键传送到大厅」只有两个条件同时为真才成立</b>：本注解 **在**
 * <b>且</b> {@code lobbies.json} 里配了本游戏的大厅。缺任何一个，左键都退回「直接入局」。
 * 本注解就是其中的第一个条件 —— 没有它，主菜单那一格点下去是**直接开局**，
 * 玩家根本进不到等待房间。
 *
 * <p>⚠️ <b>别把它当成入局开关</b>：{@code /mgc join infernalclash} **不检查**本注解；
 * 受影响的是 {@code /mgc joinroom}（无本注解时报「该游戏无法选择加入的房间」，
 * 且它的游戏名补全也会过滤掉本游戏）。{@code javap} 实测的**全部** 4 处调用点
 * 见 {@code docs/06-MGC对接.md} §2.16。
 *
 * <p>⚠️ 本注解 {@code @Retention(RUNTIME)} + {@code @Target(TYPE)}，MGC 用
 * {@code Class#isAnnotationPresent} 在**注册到该 gameId 的房间类**上读它
 * （{@code DefaultGameManager#playerSelectable} 字节码实测）。
 */
@PlayerSelectable
public final class InfernalClashRoom extends JsonGameRoom {

    /** 加入房间时的集合点。未设置则不传送。 */
    public Location waitLocation;

    /** 对局结束 / 中止 / 退出时的回程点。未设置时回退到 {@link #waitLocation}。 */
    public Location returnLocation;

    /** 取某局绑定的房间（不是本游戏的房间时返回 {@code null}）。 */
    public static InfernalClashRoom of(AbstractGame game) {
        if (game == null) {
            return null;
        }
        return game.getGameRoom() instanceof InfernalClashRoom room ? room : null;
    }

    /** 加入房间 → 传送到集合点（未配置则不传送）。 */
    public static void teleportWait(AbstractGame game, Player player) {
        InfernalClashRoom room = of(game);
        teleport(player, room == null ? null : room.waitLocation);
    }

    /**
     * 回程：把玩家送回大厅。
     *
     * <h2>回退链</h2>
     * {@link #returnLocation} → {@link #waitLocation} → {@code fallback}（玩家**入局前**的位置）
     * → <b>不传送，只告警</b>。
     *
     * <h2>★ 为什么最后一环是「不传送」而不是「主世界 spawn」</h2>
     * MCZJUGuess 的做法是用 {@code Bukkit.getWorlds().get(0).getSpawnLocation()} 兜底，
     * 但那是一条**广域**猜测：一旦房间没配、入局位置也没记上，插件就会把玩家
     * 丢到一个它自己挑的世界里去（Maggoteers 踩过同一个坑）。
     * 本项目的口径是安全规程里的「**中心取不到就跳过，宁可留残留也不放大范围**」：
     * 猜不出落点就什么都不做，并留下带玩家名的告警，让运维能对账。
     *
     * <p>本方法**不做任何删除 / 状态修改**，只有一次传送（且目标必须世界已加载）。
     */
    public static void teleportReturn(AbstractGame game, Player player, Location fallback) {
        if (player == null || !player.isOnline()) {
            return;
        }
        InfernalClashRoom room = of(game);
        Location dest = null;
        if (room != null) {
            if (isUsable(room.returnLocation)) {
                dest = room.returnLocation;
            } else if (isUsable(room.waitLocation)) {
                dest = room.waitLocation;
            }
        }
        if (dest == null && isUsable(fallback)) {
            dest = fallback;
        }
        if (dest == null) {
            warn("[IC] 房间未配置 return/wait，也没有入局前位置记录 → 不传送 "
                    + player.getName() + "（宁可留在原地，也不猜一个落点）");
            return;
        }
        teleport(player, dest);
    }

    /** 记录玩家入局前的位置，供回程兜底用（回退链的第三环）。 */
    public static void rememberJoinLocation(Map<UUID, Location> into, Player player) {
        if (into != null && player != null) {
            into.put(player.getUniqueId(), player.getLocation());
        }
    }

    /**
     * 世界已加载才算可用 —— 反序列化出的 {@code Location} 若世界名无效，
     * {@code getWorld()} 会是 {@code null}，此时跨世界传送会**静默失败**。
     */
    private static boolean isUsable(Location location) {
        return location != null && location.getWorld() != null;
    }

    private static void teleport(Player player, Location dest) {
        if (player == null || !player.isOnline() || dest == null) {
            return;
        }
        if (dest.getWorld() == null) {
            // 静默失败是最难查的一类问题 → 必须留痕
            warn("[IC] 传送目标的世界未加载，已跳过：" + dest);
            return;
        }
        player.teleport(dest);
    }

    private static void warn(String message) {
        InfernalClashPlugin plugin = InfernalClashPlugin.getInstance();
        if (plugin != null) {
            plugin.getLogger().warning(message);
        }
    }
}
