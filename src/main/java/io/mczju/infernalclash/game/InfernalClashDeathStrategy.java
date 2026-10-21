package io.mczju.infernalclash.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import com.github.mczjuops.mczjugamecore.player.PlayerExt;
import com.github.mczjuops.mczjugamecore.player.strategy.AbstractPlayerDeathStrategy;
import io.mczju.infernalclash.InfernalClashPlugin;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * 玩家死亡策略 —— <b>底座阶段的占位实现</b>。
 *
 * <h2>它现在做什么</h2>
 * 只打一行日志。**刻意不做任何事**：
 * <ul>
 *   <li>不 {@code setCancelled(true)}（无过滤地取消事件会打死别的玩法）</li>
 *   <li>不改玩家状态、不传送、不发道具</li>
 * </ul>
 * 玩法定稿后再决定「死亡 = 出局 / 重生 / 观战」，届时替换本类。
 *
 * <p>之所以现在就把覆写点占住，是为了让 {@code AbstractGame#getPlayerDeathStrategy()}
 * 这条接线是**显式**的：默认实现的行为未经本项目实测，
 * 显式覆写能避免「不知道谁在处理死亡」。
 */
public final class InfernalClashDeathStrategy extends AbstractPlayerDeathStrategy {

    public InfernalClashDeathStrategy(AbstractGame game) {
        super(game);
    }

    @Override
    public void onPlayerDeath(PlayerExt player, PlayerDeathEvent event) {
        InfernalClashPlugin plugin = InfernalClashPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        plugin.getLogger().info("[IC] 玩家死亡：" + player.getName()
                + "（底座阶段：不做出局/重生处理，玩法定稿后替换本策略）");
    }
}
