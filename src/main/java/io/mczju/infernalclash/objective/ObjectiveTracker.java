package io.mczju.infernalclash.objective;

import io.mczju.infernalclash.match.MatchSignal;
import io.mczju.infernalclash.match.Side;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ★ <b>目标物存活追踪器</b>（纯类，可离线单测）—— 把「世界里的死活」翻译成状态机的 {@link MatchSignal}。
 *
 * <h2>为什么必须单独成纯类</h2>
 * 状态机（P2）只认 {@link MatchSignal}，而 `MatchSignal` 里的两个「死亡」字段是**边沿**语义
 * （「本 tick 刚刚死亡」），另外两个是**电平**语义（「现在还剩几座」）。
 * 把边沿算错的表现是：
 * <ul>
 *   <li>把边沿当电平 ⇒ 信标死后**每一 tick 都判负**（结果一样，但重复触发结算）；</li>
 *   <li>把电平当边沿 ⇒ 信标死后**只在那一个 tick 判负**，之后塔数照常比较 ⇒ 可能平局。</li>
 * </ul>
 * 这类错误在服务器里极难观察（结算结果偶尔不对），所以判据必须能在 JUnit 里逐 tick 断言。
 *
 * <h2>★★ 死活必须记在「每一个单位」上，不是记在「一方有没有掉过」上</h2>
 * 这里踩过一次真坑（被本类的单测当场抓住）：最初把「红方有塔掉过」做成**一方一个布尔**，
 * 于是红方两座塔里掉了一座之后，`aliveCount(红, TOWER)` 会把**两座都算成死的**
 * ⇒ 塔数恒为 0:0 ⇒ **W2 / W5 永远判平局**（对局再也分不出胜负）。
 * ⇒ 现在每个单位各自记 `down`；「一方有没有掉过」是由逐单位状态**推导**出来的。
 *
 * <h2>★ 关键设计：没生成的目标物**不登记**</h2>
 * 竞技场没配齐时，本类里**根本不会有**那个目标物 ⇒ 永远不产生「死亡」边沿 ⇒
 * 对局退化成 P2 的纯时间轴（最后平局），而**不会**因为「信标不存在」被判负。
 * 这正是铁律 10 的 fail-safe：缺配置 ⇒ 少做事，而不是猜一个默认行为。
 */
public final class ObjectiveTracker {

    /** 一个已登记的目标物的状态（`down` 可变：它记录的是「已经掉过」这个事实）。 */
    private static final class Entry {

        private final ObjectiveKind kind;
        private final Side side;
        private boolean down;

        private Entry(ObjectiveKind kind, Side side) {
            this.kind = kind;
            this.side = side;
        }
    }

    private final Map<UUID, Entry> units = new LinkedHashMap<>();

    /** 上一次 {@link #update} 产出的信号（边沿 + 当前计数）。 */
    private MatchSignal lastSignal = MatchSignal.NONE;

    /** 登记一个**已经真的生成出来**的目标物。 */
    public void register(UUID id, ObjectiveKind kind, Side side) {
        if (id == null || kind == null || side == null) {
            throw new IllegalArgumentException("目标物登记不接受 null（id / kind / side 都要给）");
        }
        units.put(id, new Entry(kind, side));
    }

    /** 注销（目标物被清场 / 插件停用时调用，避免账本越积越大）。 */
    public void unregister(UUID id) {
        units.remove(id);
    }

    /**
     * 推进一 tick：输入「现在还活着」的 UUID 集合，产出新的 {@link MatchSignal}。
     *
     * <p>★ **边沿**：还没掉过、而这一 tick 不在集合里 ⇒ 本 tick 的死亡边沿成立。
     * 已经掉过的目标物不会再产生边沿（幂等）。
     *
     * <p>★ 加时里的 W4 也需要「塔的死亡边沿」—— 本类一视同仁地给出，不区分阶段
     * （阶段判据属于状态机，追踪器只管事实）。
     */
    public void update(Collection<UUID> aliveNow) {
        boolean beaconEdgeRed = false;
        boolean beaconEdgeBlue = false;
        boolean towerEdgeRed = false;
        boolean towerEdgeBlue = false;

        for (Map.Entry<UUID, Entry> entry : units.entrySet()) {
            Entry value = entry.getValue();
            boolean alive = aliveNow != null && aliveNow.contains(entry.getKey());
            if (alive || value.down) {
                continue;   // 活着的、或已经掉过的 ⇒ 本 tick 没有新事实
            }
            value.down = true;
            switch (value.kind) {
                case BEACON -> {
                    if (value.side == Side.RED) {
                        beaconEdgeRed = true;
                    } else {
                        beaconEdgeBlue = true;
                    }
                }
                case TOWER -> {
                    if (value.side == Side.RED) {
                        towerEdgeRed = true;
                    } else {
                        towerEdgeBlue = true;
                    }
                }
            }
        }

        lastSignal = new MatchSignal(beaconEdgeRed, beaconEdgeBlue, towerEdgeRed, towerEdgeBlue,
                towerCount(Side.RED), towerCount(Side.BLUE),
                // ★★ 2026-10-20：加时的 W4 改成**电平**判据之后，这里必须同时给出
                //   「信标现在还在不在」（不是"这一 tick 刚死没死"）。
                beaconDown(Side.RED), beaconDown(Side.BLUE));
    }

    /** 上一次 {@link #update} 产出的信号（供 `MatchService` 喂给状态机）。 */
    public MatchSignal signal() {
        return lastSignal;
    }

    /** 某一方某一种类**当前存活**的数量（逐单位统计）。 */
    public int aliveCount(Side side, ObjectiveKind kind) {
        int count = 0;
        for (Entry value : units.values()) {
            if (value.side == side && value.kind == kind && !value.down) {
                count++;
            }
        }
        return count;
    }

    /** 存活塔数（喂给 W2 / W5）。 */
    public int towerCount(Side side) {
        return aliveCount(side, ObjectiveKind.TOWER);
    }

    /** 该方是否**已经有**信标掉过（电平，由逐单位状态推导）。 */
    public boolean beaconDown(Side side) {
        return anyDown(side, ObjectiveKind.BEACON);
    }

    /** 该方是否**已经有**塔掉过（电平）。注意：不等于「塔全没了」。 */
    public boolean towerDown(Side side) {
        return anyDown(side, ObjectiveKind.TOWER);
    }

    private boolean anyDown(Side side, ObjectiveKind kind) {
        for (Entry value : units.values()) {
            if (value.side == side && value.kind == kind && value.down) {
                return true;
            }
        }
        return false;
    }

    /** 登记总数（含已掉的）。 */
    public int size() {
        return units.size();
    }

    /** 只读快照（排障用）。 */
    public Map<UUID, String> snapshot() {
        Map<UUID, String> view = new LinkedHashMap<>();
        units.forEach((id, entry) -> view.put(id,
                entry.side + "/" + entry.kind + (entry.down ? "(已毁)" : "(存活)")));
        return Collections.unmodifiableMap(view);
    }
}
