package io.mczju.infernalclash.match;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ <b>对局生命周期任务登记表</b> —— 所有「延时 / 周期」任务的唯一登记处。
 *
 * <h2>为什么必须有它</h2>
 * 本项目已知的事故类型（`docs/04` §12.3 逐条列过）：
 * <ul>
 *   <li><b>延时召唤</b>（R74c：x 秒后召唤）的任务若不在对局结束时取消 ⇒
 *       **下一局凭空冒出召唤物**；</li>
 *   <li><b>金色粒子跟随</b>（R55）的任务若不在实体死亡时取消 ⇒ 实体没了任务还在跑；</li>
 *   <li>玩家**退出**时若不取消他自己的挂起任务 ⇒ 他人在大厅，几秒后收到一个召唤物。</li>
 * </ul>
 * 这三条的共同根因是「任务散落在各处、没人负责取消」。
 * ⇒ 本项目规定：**对局内一切定时任务必须登记在这里**，`stop()` 时统一取消并**报出数量**（可对账）。
 *
 * <p>⚠️ 本类**不做**「按世界/全服」的任何事，也不持有实体引用 —— 它只持有任务句柄。
 */
public final class LifecycleTasks {

    private final List<BukkitTask> tasks = new ArrayList<>();

    /**
     * ★★★ <b>「收尾时要顺带做的事」</b>（1.1.0 新增，囚笼编织者的蜘蛛网）。
     *
     * <h2>为什么需要它</h2>
     * 有的机制**不是任务**，但同样必须在对局结束时清理：
     * 囚笼编织者把方块换成蜘蛛网（8 秒后自己还原），而<b>对局可能在 8 秒之内结束</b> ——
     * 那时它的还原任务会被 {@link #cancelAll()} 取消，蜘蛛网就<b>永久留在竞技场</b>
     * （下一局的地板上到处是网）。这正是 {@code docs/04} §12.3 点名的那类"跨局泄漏"，
     * 只不过泄漏的是<b>方块</b>而不是实体。
     *
     * <h2>口径</h2>
     * <ul>
     *   <li>回调在 {@code cancelAll()} 里、<b>所有任务取消之后</b>执行（先停手，再还原）；</li>
     *   <li>回调本身抛异常 ⇒ <b>吞掉并继续</b>（收尾绝不能因为一个回调中断）；</li>
     *   <li>回调清空 ⇒ 重复调用 {@code cancelAll()} 不会重复执行（幂等）。</li>
     * </ul>
     */
    private final List<Runnable> cancelHooks = new ArrayList<>();

    /** 登记一个「对局收尾时执行一次」的回调（幂等：同一个回调对象只登记一次）。 */
    public void onCancel(Runnable hook) {
        if (hook != null && !cancelHooks.contains(hook)) {
            cancelHooks.add(hook);
        }
    }

    /** 已登记多少条收尾回调（测试用）。 */
    public int cancelHookCount() {
        return cancelHooks.size();
    }

    /** 登记一个已提交的任务。 */
    public void track(BukkitTask task) {
        if (task != null) {
            tasks.add(task);
        }
    }

    /** 提交一个**延时**任务并登记。 */
    public void runLater(Plugin plugin, Runnable action, long delayTicks) {
        track(plugin.getServer().getScheduler().runTaskLater(plugin, action, Math.max(0L, delayTicks)));
    }

    /** 提交一个**周期**任务并登记。 */
    public void runTimer(Plugin plugin, Runnable action, long delayTicks, long periodTicks) {
        track(plugin.getServer().getScheduler()
                .runTaskTimer(plugin, action, Math.max(0L, delayTicks), Math.max(1L, periodTicks)));
    }

    /** 当前登记的任务数（测试与日志用）。 */
    public int size() {
        return tasks.size();
    }

    /**
     * 取消**全部**已登记任务，返回实际取消的数量。
     *
     * <p>★ 返回数量的用途是**可对账**：日志里必须写出「取消了 N 个任务」，
     * 否则「任务泄漏」这种问题只能靠肉眼盯游戏表现。
     */
    public int cancelAll() {
        int cancelled = 0;
        for (BukkitTask task : tasks) {
            try {
                if (task != null && !task.isCancelled()) {
                    task.cancel();
                    cancelled++;
                }
            } catch (Throwable ignored) {
                // 关服流程里取消任务可能抛（插件已 disabled）—— 记数不记异常，绝不因此中断收尾
            }
        }
        tasks.clear();
        // ★★★ 1.1.0：任务停完之后再跑「还原类」回调（例如把蜘蛛网换回原方块）。
        //   ★ 逐个 try：一条回调失败不能挡住后面的（收尾必须做完）。
        for (Runnable hook : cancelHooks) {
            try {
                hook.run();
            } catch (Throwable ignored) {
                // 同上：收尾不因一条回调中断
            }
        }
        cancelHooks.clear();
        return cancelled;
    }
}
