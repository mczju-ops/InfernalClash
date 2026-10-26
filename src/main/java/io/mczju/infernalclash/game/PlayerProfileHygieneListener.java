package io.mczju.infernalclash.game;

import com.github.mczjuops.mczjugamecore.event.PlayerProfileLoadedEvent;
import io.mczju.infernalclash.card.CardItems;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.logging.Logger;

/**
 * ★★★ <b>「profile 载入之后，清掉上局遗留的本插件物品」</b> —— 2026-10-19 新增。
 *
 * <h2>它修的是什么（用户现场报的）</h2>
 * 用户原话：「**结束对局后再加入房间，上局留下的卡牌会进入物品栏，理应来说物品栏这时应该是空的**」。
 *
 * <h2>根因（不是我们的收尾漏了）</h2>
 * <ol>
 *   <li>我们的 {@code MatchHooks#onStop} 里确实清了快捷栏（{@code CardLoadout#clearForAll}），
 *       它在**终局 5 秒窗口结束时**跑；</li>
 *   <li>但 <b>MGC 的 profile 快照是在那之前抓的</b>
 *       （{@code plugins/MCZJUGameCore/profiles/<uuid>.yml} 的 {@code main:} 里存着当时整条物品栏，
 *       现场读数是 17:26:29 备份、17:26:34 我们才清）；</li>
 *   <li>⇒ 下一次 {@code /mgc join} 切回 {@code infernalclash} profile 时，
 *       MGC 把**带牌的物品栏**又贴回来了（现场日志：`将玩家 … 的 profile 切换为 infernalclash`）。</li>
 * </ol>
 *
 * <h2>为什么监听 {@link PlayerProfileLoadedEvent}</h2>
 * 它是 MGC 在 {@code ProfileCapture#apply}（把快照贴到玩家身上）**之后**发的事件
 * （{@code javap -c ProfileManager} 实测）⇒ 在这里清，一定在"快照贴回来"之后，
 * 不依赖"我们的收尾早于 MGC 的快照"这种时序假设。
 *
 * <h2>★ 边界（铁律 10）</h2>
 * 只删**带本插件 PDC 标记**的物品（卡牌 / 战斗物品 / 选卡菜单物品，见
 * {@link CardLoadout#clearOurItems}）—— MGC 自己的大厅钟、玩家的装备一律不碰。
 */
public final class PlayerProfileHygieneListener implements Listener {

    private final CardItems items;
    private final Logger logger;

    /**
     * ★★★ <b>待还原账本</b>（2026-10-26 安全修复追加）。
     *
     * <p>为什么也挂在这里：{@code PlayerProfileLoadedEvent} 是 MGC 把**快照贴回玩家身上之后**
     * 发的（`javap -c ProfileManager` 实测）⇒ 如果快照里带着上局的
     * {@code invulnerable} / {@code allowFlight} / 无限药水效果，**必须在这个事件之后**再还原一次，
     * 否则会被快照覆盖回去。{@code PlayerJoinEvent} 那条已经覆盖了"没有 profile"的情况。
     */
    private final PlayerStateRestore restore;

    public PlayerProfileHygieneListener(CardItems items, Logger logger) {
        this(items, logger, null);
    }

    public PlayerProfileHygieneListener(CardItems items, Logger logger, PlayerStateRestore restore) {
        this.items = items;
        this.logger = logger;
        this.restore = restore;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProfileLoaded(PlayerProfileLoadedEvent event) {
        Player player = event == null ? null : event.getPlayer();
        if (player == null) {
            return;
        }
        // ★★★ 2026-10-26：**物品清完之后再对一次临时状态的账**
        //   （profile 快照可能把上局的 invulnerable / allowFlight / 无线效果又贴回来了）
        if (restore != null) {
            int restored = restore.restoreIfPending(player, "profile 载入对账");
            if (restored > 0 && logger != null) {
                logger.info("[IC] profile 载入后归还了上局遗留的临时状态（" + player.getName()
                        + "）：还原 " + restored + " 项（无敌 / 飞行 / 常驻效果 / 经验条）");
            }
        }
        int removed = CardLoadout.clearOurItems(player, items);
        if (removed > 0 && logger != null) {
            logger.info("[IC] profile 载入后清掉上局遗留的本插件物品 " + removed + " 件（"
                    + player.getName() + "）｜根因 = MGC 的 profile 快照抓到的是「带牌」的物品栏，"
                    + "见 CardLoadout#clearOurItems 的 javadoc");
        }
    }
}
