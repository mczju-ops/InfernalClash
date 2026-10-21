package io.mczju.infernalclash.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerQuitStrategy;
import com.github.mczjuops.mczjugamecore.player.strategy.PlayerQuitReason;
import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.policy.QuitRules;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 玩家退出策略（{@code /mgc leave}、掉线、加入失败都会走到这里）。
 *
 * <h2>★ 为什么子插件必须自己收尾（MGC 1.1.2 实测）</h2>
 * {@code DefaultPlayerManager.leaveGame} 的执行顺序是：
 * <ol>
 *   <li>{@code playerGameMap.remove(player)} —— <b>先把玩家摘掉</b></li>
 *   <li>{@code TabManager.onPlayerLeave}</li>
 *   <li>{@code WAITING} → {@code getGameWaitStrategy().onPlayerLeave(player)}；
 *       否则 → {@code getPlayerQuitStrategy().onPlayerQuit(player, reason)} ← <b>本类</b></li>
 *   <li>{@code switchProfile(null)}、{@code playerListName(null)}</li>
 * </ol>
 * 也就是说 MGC **只在 WAITING 阶段**处理「人跑光了」
 * （{@code DefaultGameWaitStrategy.onPlayerLeave} 里判空 → {@code GameManager.cancelGame}）；
 * <b>RUNNING 阶段它什么都不做</b>。若子插件不自己收尾，对局会带着 0 名参与者继续跑完，
 * 房间一直停在 {@code IN_GAME}，之后 {@code /mgc join} 会报「没有空闲的房间」。
 *
 * <p>退出回调发生在第 1 步<b>之后</b> ⇒ 这里读到的 {@code game.getPlayers()}
 * 已经是「**剩下**的玩家」。
 */
public final class InfernalClashQuitStrategy extends AbstractPlayerQuitStrategy {

    public InfernalClashQuitStrategy(AbstractGame game) {
        super(game);
    }

    @Override
    public void onPlayerQuit(PlayerExt player, PlayerQuitReason reason) {
        InfernalClashPlugin plugin = InfernalClashPlugin.getInstance();

        // ★ 关服护栏：MGC 的 onDisable → forceAbortGame → solveGameEnd
        //   → PlayerManager.removeAllPlayer(game) → **本回调**，
        //   而此时我们的插件**早已 disabled** —— 任何 getScheduler() 调用都会抛
        //   IllegalPluginAccessException: Plugin attempted to register task while disabled。
        //   关服时玩家反正要掉线，收尾毫无意义 → 直接跳过。
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }

        plugin.getLogger().info("[IC] 玩家退出：" + player.getName() + "（原因 " + reason + "）");

        Player bukkitPlayer = player.player();
        if (bukkitPlayer != null) {
            // 回程：房间 return → 房间 wait → 入局前位置 → 不传送（见 InfernalClashRoom）
            try {
                Location fallback = game instanceof InfernalClashGame clash
                        ? clash.joinLocationOf(player.getUniqueId()) : null;
                InfernalClashRoom.teleportReturn(game, bukkitPlayer, fallback);
            } catch (Throwable t) {
                plugin.getLogger().warning("[IC] 传送 " + player.getName() + " 回程失败：" + t);
            }
        }

        // ★★★ 2026-10-20（用户裁定：「掉线/leave=投降」）：
        //   判据全部收在纯类 policy/QuitRules（三条顺序不能换，见它的类注释）。
        //   以前这里只有「一个真人都没有 ⇒ 中止」一条 ⇒ 1v1 里一方掉线时
        //   退出方**不记负**、对面要干等 3~4 分钟（与 docs/05 Q28 / docs/07 §9 的「退出即判负」不符）。
        try {
            if (game instanceof InfernalClashGame clash) {
                int remaining = clash.getPlayers().size();
                java.util.UUID leaver = player.getUniqueId();
                boolean onRoster = clash.sideOfParticipant(leaver) != null;
                boolean running = clash.isMatchRunning();
                QuitRules.Action action = QuitRules.decide(remaining, running, onRoster);
                plugin.getLogger().info("[IC] 退出处置 = " + action + "（剩余真人 " + remaining
                        + "｜对局在运行 " + running + "｜退出者在名册 " + onRoster + "）");
                switch (action) {
                    case ABORT_MATCH -> clash.abortIfNoPlayersLeft("最后一名玩家离开了对局");
                    // ★ 走下注那条**既有**收尾路径（MatchService#surrender ⇒ finishWith ⇒ finish），
                    //   不新造一条绕开对局收尾的路。
                    case FORFEIT -> clash.forfeitOnQuit(leaver, "掉线/离开对局");
                    case NONE -> {
                        // 对局已结束 / 退出者不是本局参与者 ⇒ 什么都不做（失败方向 = 不判）
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[IC] 退出处置失败（对局收尾可能受影响，已记录）：" + t);
        }
    }

    /**
     * 玩家离开后是否应当**立即中止整局**。
     *
     * <p>抽成静态纯函数是为了能**离线单测**（{@code QuitAbortTest}）：
     * 若有人把它改成 {@code <= 1}（「只剩一人就结束」），测试会立刻变红。
     *
     * <p>★ 2026-10-20：它现在**只是** {@link QuitRules#decide} 的第一条
     * （{@code remaining <= 0} ⇒ {@code ABORT_MATCH}）；
     * 「只剩一人 ⇒ 给退出方判负」那条**不在这里**（它在 {@code QuitRules} 的第 2/3 条之后）。
     * 保留本方法是为了不破坏既有测试的语义（{@code QuitAbortTest} 仍钉着"不是 <= 1"）。
     *
     * @param remainingRealPlayers 仍留在对局内的**真人**数量（人机不算 —— 它们不是 MGC 玩家）
     */
    static boolean shouldAbortGame(int remainingRealPlayers) {
        return QuitRules.decide(Math.max(0, remainingRealPlayers), true, false)
                == QuitRules.Action.ABORT_MATCH;
    }
}
