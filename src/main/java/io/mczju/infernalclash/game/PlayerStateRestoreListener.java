package io.mczju.infernalclash.game;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Logger;

/**
 * ★★★ <b>玩家上线/退出时的「临时状态对账」</b> —— 2026-10-26 安全修复。
 *
 * <h2>它补的是哪两格（用户逐字）</h2>
 * <blockquote>
 * 「`restoreTargetBan` 不能只依赖"玩家在线"：把「本局设过无敌的 UUID ＋ 进局前原值」
 * 持久化成待还原名单，<b>玩家下次上线或插件 onEnable 时对账还原</b>。」
 * </blockquote>
 *
 * <h2>为什么"退出时还"和"上线时还"两个都要</h2>
 * <ul>
 *   <li><b>退出时就还</b>（{@link PlayerQuitEvent}）：玩家一离开就把
 *       {@code invulnerable} / {@code allowFlight} / 无限药水效果 / 经验条收回去
 *       ⇒ <b>玩家身上的存档状态从"离开的那一刻"就是干净的</b>。
 *       ★ 这是最根本的一格：即使之后服务器崩了、账本丢了，玩家也不会带着无敌下线；</li>
 *   <li><b>上线时再对账</b>（{@link PlayerJoinEvent}）：兜住"退出时那一下没跑到"
 *       （被硬杀 / 崩服 / 退出事件被别的插件取消 / 旧版本留下的账）——
 *       账本里若还欠着，上线立刻还。</li>
 * </ul>
 *
 * <h2>★ 为什么不用 MONITOR 之后的自定义时机</h2>
 * {@code MONITOR} = 在别人的处理**之后**跑：{@code QuitRules} 那条"退出 = 投降"会先跑，
 * 对局随之收尾；我们再还账 ⇒ 顺序天然正确，不需要任何时序假设。
 *
 * <h2>★ 边界（铁律 10）</h2>
 * 只处理<b>账本里记着的那个 UUID</b>；不遍历在线列表、不碰别人的状态、
 * 也不给"没欠账"的玩家写任何东西。
 */
public final class PlayerStateRestoreListener implements Listener {

    private final PlayerStateRestore restore;
    private final Logger logger;
    private final org.bukkit.plugin.Plugin plugin;

    public PlayerStateRestoreListener(PlayerStateRestore restore, Logger logger,
                                      org.bukkit.plugin.Plugin plugin) {
        this.restore = restore;
        this.logger = logger;
        this.plugin = plugin;
    }

    /** ★ 上线对账：上次欠着的（无敌 / 飞行 / 效果 / 经验条）在这里还掉。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (restore == null || event == null || event.getPlayer() == null) {
            return;
        }
        Integer before = restore.pendingCount();
        int restored = restore.restoreIfPending(event.getPlayer(), "玩家上线对账");
        warnAboutLegacyLeak(event.getPlayer());
        if (restored > 0 && logger != null) {
            logger.info("[IC] 玩家上线对账完成：" + event.getPlayer().getName()
                    + " 身上还欠着上局的临时状态，已还原 " + restored + " 项"
                    + "（账本剩余 " + restore.pendingCount() + " 名，此前 " + before + " 名）"
                    + "｜这是 2026-10-26「玩家无敌外泄」事故的防复发路径");
        }
    }

    /**
     * ★★★ <b>退出立刻还账</b>（最关键的一格）。
     *
     * <p>玩家退出时实体还在线（{@code quit} 事件里 {@code getPlayer()} 仍然有效），
     * ⇒ 这是**最后一次**能安全改他状态的机会。旧实现把这件事全押在"对局收尾"上，
     * 而收尾时他已经离线 ⇒ 无敌就漏到下一次上线了。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (restore == null || event == null || event.getPlayer() == null) {
            return;
        }
        int restored = restore.restoreIfPending(event.getPlayer(), "玩家退出");
        warnAboutLegacyLeak(event.getPlayer());
        if (restored > 0 && logger != null) {
            logger.info("[IC] 玩家退出时已归还临时状态：" + event.getPlayer().getName()
                    + "（还原 " + restored + " 项：无敌 / 飞行 / 常驻效果 / 经验条）"
                    + "｜账本剩余 " + restore.pendingCount() + " 名");
        }
    }
    /**
     * ★★★ <b>「历史遗留的永久无敌」检测</b>（2026-10-26 事故善后）——**只告警，不自动清**。
     *
     * <h2>为什么要单独有这一格</h2>
     * 1.0.0 / 1.1.0（修复前）漏还原的玩家，`invulnerable` 已经写进他们的 `playerdata`，
     * 而当时**没有账本**（旧代码只存内存）⇒ 修复后的账本救不了他们。
     *
     * <h2>★ 为什么不敢自动清</h2>
     * `invulnerable` 也可能是**别的插件 / 创造模式 / 服主自己给的** ⇒
     * 自动清属"改别人的状态"，违反本项目的边界纪律。⇒ 只**点名告警**，
     * 并给出可直接复制的原版修法（`/data merge entity … {Invulnerable:0b}`），由人决定。
     *
     * <h2>判据（三条同时成立才告警）</h2>
     * ① 他**不在**任何进行中的对局里；② 他此刻是 {@code invulnerable}；
     * ③ 账本里**没有**他的记录（= 不是本版本留下的、我们能负责的那一笔）。
     */
    private void warnAboutLegacyLeak(org.bukkit.entity.Player player) {
        if (player == null || logger == null) {
            return;
        }
        try {
            if (restore != null && restore.hasPending(player.getUniqueId())) {
                return;   // 本版本记着账 ⇒ 上面已经还原过了，不是遗留
            }
            if (!player.isInvulnerable()) {
                return;
            }
            if (io.mczju.infernalclash.match.MatchService.ofPlayer(player.getUniqueId()) != null) {
                return;   // 正在对局里 ⇒ 这是本局正常的索敌排除
            }
            logger.warning("[IC] ⚠ 玩家 " + player.getName()
                    + " 处于 invulnerable 状态，但他不在对局里、账本里也没有记录"
                    + " ⇒ 很可能是 1.1.0 之前「玩家无敌外泄」事故的遗留（当时没有账本）。"
                    + " 如需清除（仅当他本该能被打）：以管理员身份执行 "
                    + "/data merge entity " + player.getName() + " {Invulnerable:0b} ；"
                    + " ★ 如果是别的插件/创造模式给的无敌，请忽略本条");
        } catch (Throwable ignored) {
            // 检测是"尽力而为"的善后：任何异常都不该影响上线/退出
        }
    }}
