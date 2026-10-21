package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.arena.ArenaConfig;
import io.mczju.infernalclash.arena.Point;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * ★ <b>U1 竞技场编辑界面的点击处理</b>（{@code docs/09-UI设计.md} §2.5 的交互表）。
 *
 * <h2>交互（每个点位格）</h2>
 * <ul>
 *   <li><b>左键</b> = 用「打开界面时站的位置」{@link ArenaEditLayout.Click#BIND 绑定}；</li>
 *   <li><b>右键</b> = {@link ArenaEditLayout.Click#TELEPORT 传送}过去查看（未采集 ⇒ 一句提示，不传送）；</li>
 *   <li><b>Shift + 左键</b> = {@link ArenaEditLayout.Click#CLEAR 清除}该点位。</li>
 * </ul>
 * 另外三格：{@code 18} 清空全部（★ 必须 Shift + 左键）、{@code 22} 保存、{@code 26} 关闭。
 * <b>语义解析只有一处</b>：{@link ArenaEditLayout#clickFor(boolean, boolean)}（纯函数），
 * 本类只负责「把它接到 Bukkit 事件上」——{@code docs/09} §1 第 4 条的要求。
 *
 * <h2>★ 四条共同纪律（{@code docs/09} §1 + 末尾的安全补充）</h2>
 * <ol>
 *   <li><b>第一件事就是 {@code setCancelled(true)}</b>；</li>
 *   <li><b>单独拦 {@code InventoryDragEvent}</b>（只拦 Click 会被拖动绕过）；</li>
 *   <li>越界的 {@code getRawSlot()} 直接忽略；</li>
 *   <li>★ <b>只拦自己注册的界面</b>（{@code getHolder() instanceof ArenaEditMenu}）——
 *       无过滤地 {@code setCancelled(true)} 会打死大厅菜单与别的玩法（IG 的真实教训）。</li>
 * </ol>
 *
 * <h2>★★ 唯一的「广域动作」= 右键传送 —— 按铁律 10 给 scope proof</h2>
 * <table border="1">
 *   <caption>四问</caption>
 *   <tr><th>问</th><th>答</th></tr>
 *   <tr><td><b>范围界定</b></td><td>只传送<b>点击的那名玩家自己</b>到<b>他点击的那一个</b>点位坐标
 *       （坐标来自 {@code arenas.yml}，由管理员自己采集）—— 不遍历玩家、不遍历实体、不动世界</td></tr>
 *   <tr><td><b>失败方向</b></td><td>点位未采集 ⇒ 提示、不传送；点位所在世界不存在
 *       （{@link ArenaEditMenu#locationOf} 返回 {@code null}）⇒ <b>提示 + 不传送</b>，
 *       绝不退化成「主世界出生点」之类的猜测</td></tr>
 *   <tr><td><b>归属判定</b></td><td>目标是<b>发令玩家自己的 UUID</b>（事件里的 {@code whoClicked}），
 *       目标是<b>本插件自己的数据文件</b>里的键（白名单 = {@code ArenaConfig.allKeys()}）</td></tr>
 *   <tr><td><b>可对账</b></td><td>每次传送 / 绑定 / 清除 / 保存都打一条带<b>数量或坐标</b>的 INFO 日志</td></tr>
 * </table>
 * ⇒ 本类<b>不出现</b> {@code getOnlinePlayers / getEntities / getLivingEntities / getWorlds}
 * （{@code BytecodeScopeGuardTest} 盯着它）。
 */
public final class ArenaEditListener implements Listener {

    private final InfernalClashPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public ArenaEditListener(InfernalClashPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 点击入口：只处理 {@link ArenaEditMenu} 的箱子，其余界面一律不碰。
     *
     * <p>★ {@code ignoreCancelled = true}：别的插件已经取消过的点击我们不再插手
     * （与 IG 的选型一致）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        // ★ 判据取**顶部箱子**的 holder（点击玩家背包时 getInventory() 的语义容易记错；
        //   MenuRegistry 的拖动拦截也是这个口径）
        if (event.getView() == null
                || !(event.getView().getTopInventory().getHolder() instanceof ArenaEditMenu menu)) {
            return;
        }
        // ★ 纪律第 1 条：任何点击都不许改动这个界面（否则玩家能把图标拖进自己背包）
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (!ArenaEditLayout.isInside(slot)) {
            return;   // 纪律第 3 条：点到玩家背包区域 / 越界 ⇒ 已取消，忽略
        }

        try {
            handle(menu, player, slot, event.isRightClick(), event.isShiftClick());
        } catch (Throwable t) {
            // ★ 一次点击的异常绝不能冒到事件总线（那会连带炸掉别人的监听器）
            log(java.util.logging.Level.WARNING, "处理点击时抛了异常（已兜住，slot=" + slot + "）：" + t);
            tell(player, "这次点击出错了（详情见控制台日志）。", NamedTextColor.RED);
        }
    }

    /** 纪律第 2 条：只拦本界面的拖动（{@code docs/09} §1）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView() != null
                && event.getView().getTopInventory().getHolder() instanceof ArenaEditMenu) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ 动作

    private void handle(ArenaEditMenu menu, Player player, int slot, boolean rightClick, boolean shiftClick) {
        String key = ArenaEditLayout.pointKeyAt(slot);
        if (key != null) {
            switch (ArenaEditLayout.clickFor(rightClick, shiftClick)) {
                case BIND -> bind(menu, player, key);
                case TELEPORT -> teleport(menu, player, key);
                case CLEAR -> clear(menu, player, key);
            }
            return;
        }
        if (slot == ArenaEditLayout.SAVE_SLOT) {
            save(menu, player);
            return;
        }
        if (slot == ArenaEditLayout.CLEAR_SLOT) {
            clearAll(menu, player, shiftClick);
            return;
        }
        if (slot == ArenaEditLayout.CLOSE_SLOT) {
            // ★ 改动只在「保存」时落盘 ⇒ 关闭 = 丢弃工作副本（与 IG 一致）
            log(java.util.logging.Level.INFO, "关闭界面（未保存的改动丢弃）：" + player.getName()
                    + "，工作副本 " + menu.size() + " 个点位");
            player.closeInventory();
        }
        // 信息格 / 填充格：什么都不做
    }

    /** 左键：用「打开界面时站的位置」绑定。 */
    private void bind(ArenaEditMenu menu, Player player, String key) {
        Location at = menu.openLocation();
        if (at.getWorld() == null) {
            // ★ 失败方向：世界未知就**不写**（写进去的 `world: ""` 会被严格校验拒掉，
            //   表现为「下次启动竞技场点位全坏」——宁可这次不绑定）
            log(java.util.logging.Level.WARNING,
                    "绑定点位 " + key + " 失败：打开界面时的位置没有世界信息，没有写入");
            tell(player, "绑定点位 " + key + " 失败：位置的世界未知（见控制台日志）。", NamedTextColor.RED);
            return;
        }
        Point point = new Point(at.getWorld().getName(),
                at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        menu.bind(key, point);
        menu.render();
        // ★ 带坐标的日志（铁律 10 第 4 问：可对账）
        log(java.util.logging.Level.INFO, "绑定点位 " + key + " ← " + player.getName()
                + " 打开界面时的位置 " + describe(point) + "（尚未落盘）");
        tell(player, String.format(ArenaEditMenu.MSG_BOUND, key, describe(point)), NamedTextColor.GREEN);
    }

    /** 右键：传送到已存坐标去查看（失败方向见类注释的 scope proof）。 */
    private void teleport(ArenaEditMenu menu, Player player, String key) {
        Point point = menu.point(key);
        if (point == null) {
            tell(player, ArenaEditMenu.MSG_TELEPORT_EMPTY, NamedTextColor.YELLOW);
            return;
        }
        Location target = ArenaEditMenu.locationOf(point);
        if (target == null) {
            // ★ fail-safe：世界不存在 ⇒ 什么都不做（绝不猜一个落点）
            log(java.util.logging.Level.WARNING, "传送失败：点位 " + key + " 的世界 "
                    + point.world() + " 不存在，没有传送（fail-safe）");
            tell(player, String.format(ArenaEditMenu.MSG_TELEPORT_NO_WORLD, point.world()),
                    NamedTextColor.RED);
            return;
        }
        player.teleport(target);
        // 与 IG 一致：传送后关闭界面 —— 玩家是「来看地形」的，看完要重新开界面来绑定
        player.closeInventory();
        log(java.util.logging.Level.INFO, "传送 " + player.getName() + " → 点位 " + key
                + " @ " + describe(point));
        tell(player, "已传送到点位 " + key + " —— 界面已关闭；要把它绑到这里，请站在目标位置重新打开界面。",
                NamedTextColor.AQUA);
    }

    /** Shift + 左键：清除一个点位。 */
    private void clear(ArenaEditMenu menu, Player player, String key) {
        if (!menu.clear(key)) {
            tell(player, "点位 " + key + " 本来就没有采集过。", NamedTextColor.YELLOW);
            return;
        }
        menu.render();
        log(java.util.logging.Level.INFO, "清除点位 " + key + "（由 " + player.getName()
                + " 在界面里操作，尚未落盘）");
        tell(player, String.format(ArenaEditMenu.MSG_CLEARED, key), NamedTextColor.GREEN);
    }

    /** {@code 18}：清空全部 —— ★ 必须 Shift + 左键（docs/09 §2.5「防误触」）。 */
    private void clearAll(ArenaEditMenu menu, Player player, boolean shiftClick) {
        if (!shiftClick) {
            tell(player, ArenaEditMenu.MSG_CLEAR_NEEDS_SHIFT, NamedTextColor.YELLOW);
            return;
        }
        int cleared = menu.clearAll();
        menu.render();
        log(java.util.logging.Level.INFO, "清空全部点位：清掉 " + cleared + " 个（由 " + player.getName()
                + " 在界面里操作，尚未落盘）");
        tell(player, String.format(ArenaEditMenu.MSG_CLEARED_ALL, cleared), NamedTextColor.GREEN);
    }

    /**
     * {@code 22}：保存 —— 走 {@link ArenaConfig#save(java.io.File, ArenaConfig)}（先备份、后写盘）。
     *
     * <h2>★ 失败方向（本任务明令）</h2>
     * 写盘失败 ⇒ <b>只提示 + 告警，绝不抛</b>，而且<b>不丢掉内存里的改动</b>：
     * 工作副本仍在菜单里（玩家可以再点一次保存），并且把它<b>装进内存表</b>
     * （{@code plugin.setArenaConfig}），于是本次会话里其它消费者也看得到这次改动
     * —— 提示里明确写「<b>没有写入磁盘</b>」。
     */
    private void save(ArenaEditMenu menu, Player player) {
        ArenaConfig.SaveOutcome outcome = menu.save();
        for (String warning : outcome.warnings()) {
            log(java.util.logging.Level.WARNING, "保存 arenas.yml：" + warning);
        }
        if (!outcome.changed()) {
            // ★ 内存改动不丢：装进内存表（重启会丢 —— 这正是提示里要说清的）
            installQuietly(menu, player);
            tell(player, String.format(ArenaEditMenu.MSG_SAVE_FAILED, outcome.summary()), NamedTextColor.RED);
            log(java.util.logging.Level.WARNING, "保存失败（内存改动仍生效）：" + outcome.summary());
            return;
        }
        // 成功 ⇒ 立即热载（docs/09 §2.6 的最后一条：写完立刻重新 load，不必重启）
        String describe = reloadQuietly(player);
        menu.render();
        tell(player, String.format(ArenaEditMenu.MSG_SAVE_OK, outcome.summary()), NamedTextColor.GREEN);
        tell(player, "热载后：" + describe, NamedTextColor.GRAY);
        log(java.util.logging.Level.INFO, "保存成功：" + outcome.summary() + "；热载后 " + describe);
    }

    /** 把工作副本装进内存表（★ 只在写盘失败时用；成功路径一律以磁盘为准热载）。 */
    private void installQuietly(ArenaEditMenu menu, Player player) {
        try {
            plugin.setArenaConfig(menu.working());
        } catch (Throwable t) {
            log(java.util.logging.Level.WARNING, "把工作副本装进内存表失败：" + t);
            tell(player, "（内存表也没能更新：见控制台日志）", NamedTextColor.GRAY);
        }
    }

    /** 热载磁盘上的 {@code arenas.yml}（失败时由插件侧保持旧表 + 告警）。 */
    private String reloadQuietly(Player player) {
        try {
            return plugin.reloadArenaConfig();
        } catch (Throwable t) {
            log(java.util.logging.Level.WARNING, "热载 arena 配置失败（沿用旧表）：" + t);
            tell(player, "（热载失败：见控制台日志）", NamedTextColor.GRAY);
            return "热载失败";
        }
    }

    // ------------------------------------------------------------------ 输出

    private static String describe(Point point) {
        return point.world() + " " + String.format("%.1f, %.1f, %.1f", point.x(), point.y(), point.z())
                + "（yaw " + point.yaw() + " / pitch " + point.pitch() + "）";
    }

    private void tell(Player player, String text, NamedTextColor color) {
        try {
            player.sendMessage(mini.deserialize(text).colorIfAbsent(color));
        } catch (Throwable ignored) {
            // 连提示都发不出去时放弃这次提示（不能因此中断点击处理）
        }
    }

    private void log(java.util.logging.Level level, String message) {
        try {
            if (plugin != null) {
                plugin.getLogger().log(level, "[IC] 界面（U1 竞技场编辑）：" + message);
            }
        } catch (Throwable ignored) {
            // 日志失败不能再造出第二个失败
        }
    }
}
