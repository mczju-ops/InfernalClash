package io.mczju.infernalclash.objective;

import io.mczju.infernalclash.match.OwnershipTable;
import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;

import java.util.logging.Logger;

/**
 * ★ <b>目标物保护</b>：拦住「本插件接管的实体被原版机制变成别的东西」。
 *
 * <h2>为什么必须有</h2>
 * 信标与防御塔的**受击目标是一个村民**（`docs/03` §3.2、【Q15/Q20/Q56】已裁定）。
 * 而村民在**困难难度**会被僵尸化（`Villager#zombify()`，`javap` 实测存在）——
 * 于是现场会出现「信标自己变成了僵尸村民跑掉了」这种**看起来像灵异事件**的缺陷：
 * 玩家打不到目标物、塔数比较得出莫名其妙的结果。
 *
 * <h2>★ 判据是「归属」而不是「类型」</h2>
 * 本监听器只对**账本里登记过的实体**生效（{@link UnitRegistry#table()}）。
 * 不认识的实体（服主自己放的村民、别的插件的实体）**一律不碰** ——
 * 这正是铁律 10 的第 3 问：归属必须可判定，不能靠类型。
 *
 * <p>★ 附带好处：因为按归属判定，本监听器**不需要**取消所有转化事件
 * （那样会连带影响别的插件的玩法）。
 */
public final class ObjectiveProtectionListener implements Listener {

    private final UnitRegistry registry;
    private final Logger logger;

    public ObjectiveProtectionListener(UnitRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        Entity entity = event.getEntity();
        if (entity == null) {
            return;
        }
        OwnershipTable.Owner owner = registry.table().owner(entity.getUniqueId());
        if (owner == null) {
            return;   // 不是我们接管的实体 —— 绝不干预
        }
        event.setCancelled(true);
        logger.info("[IC] 已拦截本插件实体的形态转化："
                + entity.getType() + "（matchId=" + owner.matchId() + "，" + owner.side()
                + "）→ " + event.getTransformReason()
                + "｜原因：目标物必须保持原形态（村民僵尸化会让信标/塔失效）");
    }
}
