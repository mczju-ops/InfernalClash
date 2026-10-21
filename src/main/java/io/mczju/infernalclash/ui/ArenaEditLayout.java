package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.arena.ArenaConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ★ <b>U1「竞技场编辑」界面的纯槽位布局</b>（{@code docs/09-UI设计.md} §2.5 的逐格表）。
 *
 * <h2>为什么是纯类</h2>
 * {@code docs/09} §1「共同纪律」第 4 条：<b>槽位 ↔ 语义的解析抽成静态纯函数</b>，
 * 监听器只调它（InfernalGuess / PocketMine 的一致做法）；
 * {@code docs/04} §16.12.2 也写着「格子布局 → 纯函数（可离线单测）」。
 * ⇒ 本类<b>只依赖 JDK</b>（与同包的 {@link MenuLayout} 同样口径），
 * 不 import 任何 Bukkit / Paper / MGC 类 ⇒ 必然能在普通 JUnit 里离线逐格断言
 * （{@code ui/ArenaEditLayoutTest}）。
 *
 * <h2>★ 权威出处：{@code docs/09} §2.5（27 格 = 3 行 × 9 列，仿 InfernalGuess）</h2>
 * <table border="1">
 *   <caption>点位槽 ↔ 点位键（本类的唯一事实来源就是下面这张表）</caption>
 *   <tr><th>槽位</th><th>点位键</th><th>槽位</th><th>点位键</th></tr>
 *   <tr><td>{@code 0}</td><td>{@code beacon.red}</td><td>{@code 9}</td><td>{@code spectate}</td></tr>
 *   <tr><td>{@code 1}</td><td>{@code beacon.blue}</td><td>{@code 10} / {@code 11}</td><td>{@code half.red.1} / {@code half.red.2}</td></tr>
 *   <tr><td>{@code 2} / {@code 3}</td><td>{@code tower.red.left} / {@code tower.red.right}</td>
 *       <td>{@code 12} / {@code 13}</td><td>{@code half.blue.1} / {@code half.blue.2}</td></tr>
 *   <tr><td>{@code 5} / {@code 6}</td><td>{@code tower.blue.left} / {@code tower.blue.right}</td>
 *       <td>{@code 14}</td><td>{@code river}</td></tr>
 *   <tr><td>{@code 7} / {@code 8}</td><td>{@code spawn.red} / {@code spawn.blue}</td>
 *       <td>{@code 15} / {@code 16}</td><td>{@code bridge.1} / {@code bridge.2}</td></tr>
 * </table>
 * <p>另有四个功能格：{@code 4} 信息 / {@code 18} 清空全部 / {@code 22} 保存 / {@code 26} 关闭；
 * 其余格是填充格（§2.5「其余 —— 填充格」）。
 *
 * <h2>★ 类初始化即对账两件事（接线错误必须当场炸，不许静默错位）</h2>
 * <ol>
 *   <li>16 个槽位<b>互不重复</b>、都落在 {@code 0~26}；</li>
 *   <li>槽位上的键集合<b>恰好等于</b> {@link ArenaConfig#allKeys()} ——
 *       多一个 ⇒ 写进去的键会被 {@code ArenaConfig.from} 拒掉；
 *       少一个 ⇒ 那个点位在界面上**永远采不到**（而且不报错）。</li>
 * </ol>
 * 判据用的是 {@code ArenaConfig.allKeys()} 本身，<b>不抄第二份键表</b>
 * （本项目「第二处白名单」的实测教训）。
 */
public final class ArenaEditLayout {

    /** 界面格数 = <b>27</b> = 3 行 × 9 列（{@code docs/09} §2.5 的标题就是「27 格 = 3 行 × 9 列」）。 */
    public static final int SIZE = 27;

    /** 信息格：已采集 {@code n/16}、缺哪些必需键、世界名、{@code half.*} 是否可用（{@code docs/09} §2.5）。 */
    public static final int INFO_SLOT = 4;

    /** 清空全部点位 —— ★ 要求 <b>Shift + 左键</b>才生效（{@code docs/09} §2.5「潜行 + 左键才生效，防误触」）。 */
    public static final int CLEAR_SLOT = 18;

    /** 保存（写回 {@code arenas.yml}：先备份、后写盘，见 {@code docs/09} §2.6）。 */
    public static final int SAVE_SLOT = 22;

    /** 关闭（改动只在保存时落盘 ⇒ 关闭 = 丢弃内存里的工作副本，与 IG 一致）。 */
    public static final int CLOSE_SLOT = 26;

    /**
     * 点位槽 → 点位键（<b>保序</b>：按槽位升序；★ 不是 {@code Map.copyOf} 的随机序）。
     *
     * <p>迭代它即可渲染全部 16 个点位格；{@link #pointKeyAt(int)} 是它的反向查询。
     */
    public static final Map<Integer, String> POINT_SLOTS;

    /** 反查表：点位键 → 槽位（内部用，保证 {@link #pointKeyAt(int)} 是 O(1) 且与 {@link #POINT_SLOTS} 同源）。 */
    private static final Map<String, Integer> SLOT_OF_KEY;

    static {
        Map<Integer, String> slots = new LinkedHashMap<>();
        // ★ 逐行照抄 docs/09 §2.5 的表：0/1 信标、2/3 红塔、4 信息、5/6 蓝塔、7/8 出生点、
        //   9 观战、10~13 双方半场角点、14 河、15/16 两座桥、18 清空、22 保存、26 关闭。
        slots.put(0, "beacon.red");
        slots.put(1, "beacon.blue");
        slots.put(2, "tower.red.left");
        slots.put(3, "tower.red.right");
        slots.put(5, "tower.blue.left");
        slots.put(6, "tower.blue.right");
        slots.put(7, "spawn.red");
        slots.put(8, "spawn.blue");
        slots.put(9, "spectate");
        slots.put(10, "half.red.1");
        slots.put(11, "half.red.2");
        slots.put(12, "half.blue.1");
        slots.put(13, "half.blue.2");
        slots.put(14, "river.1");
        slots.put(15, "river.2");
        slots.put(16, "bridge.1.a");
        slots.put(17, "bridge.1.b");
        slots.put(19, "bridge.2.a");
        slots.put(20, "bridge.2.b");
        POINT_SLOTS = Collections.unmodifiableMap(slots);

        Map<String, Integer> reverse = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : POINT_SLOTS.entrySet()) {
            // ① 槽位本身必须落在界面内
            //   （「同一个槽位写两次」在 LinkedHashMap 里会静默覆盖 ⇒ 它的表现是下面 ④ 的「缺点位键」，会被抓到）
            if (entry.getKey() < 0 || entry.getKey() >= SIZE) {
                throw new IllegalStateException("U1 布局接线错误：槽位越界 " + entry.getKey()
                        + "（界面只有 " + SIZE + " 格）");
            }
            // ② 反向表：同一个键不许出现在两个槽位上（那会让「一个点位两个按钮」静默生效）
            Integer previous = reverse.put(entry.getValue(), entry.getKey());
            if (previous != null) {
                throw new IllegalStateException("U1 布局接线错误：点位键 " + entry.getValue()
                        + " 同时挂在槽位 " + previous + " 与 " + entry.getKey());
            }
            // ③ 键必须是 ArenaConfig 约定的键（★ 判据只有 allKeys() 一处，不抄第二份）
            if (!ArenaConfig.allKeys().contains(entry.getValue())) {
                throw new IllegalStateException("U1 布局接线错误：槽位 " + entry.getKey() + " 上的点位键 "
                        + entry.getValue() + " 不在 ArenaConfig.allKeys() 里（写进去会被严格校验拒掉）");
            }
        }
        Set<String> missing = new LinkedHashSet<>(ArenaConfig.allKeys());
        missing.removeAll(reverse.keySet());
        if (!missing.isEmpty()) {            throw new IllegalStateException("U1 布局接线错误：这些点位键在布局里没有槽位，界面上永远采不到："
                    + missing);
        }
        SLOT_OF_KEY = Collections.unmodifiableMap(reverse);
    }

    private ArenaEditLayout() {
    }

    /**
     * 该槽位上的点位键。
     *
     * @param slot 点击的原始槽位（可越界 —— 越界一律 {@code null}，{@code docs/09} §1 第 3 条
     *             「越界 {@code getRawSlot()} 直接忽略」）
     * @return 点位键；<b>非点位槽（信息 / 清空 / 保存 / 关闭 / 填充格）与越界槽 ⇒ {@code null}</b>
     */
    public static String pointKeyAt(int slot) {
        return POINT_SLOTS.get(slot);
    }

    /** 该槽位是不是点位格（{@link #pointKeyAt(int)} 的同口径判据）。 */
    public static boolean isPointSlot(int slot) {
        return POINT_SLOTS.containsKey(slot);
    }

    /** 该点位键的槽位（{@link #pointKeyAt(int)} 的逆运算）；不在布局里 ⇒ <b>-1</b>。 */
    public static int slotOfKey(String key) {
        Integer slot = SLOT_OF_KEY.get(key);
        return slot == null ? -1 : slot;
    }

    /** 会不会点在这个界面上（越界槽一律忽略，见 {@code docs/09} §1 第 3 条）。 */
    public static boolean isInside(int slot) {
        return slot >= 0 && slot < SIZE;
    }

    /**
     * 一次点击的<b>语义</b>（{@code docs/09} §2.5 的交互表）。
     *
     * <table border="1">
     *   <caption>交互（每个点位格）</caption>
     *   <tr><th>动作</th><th>语义</th></tr>
     *   <tr><td><b>左键</b></td><td>{@link #BIND}：用「打开界面时站的坐标」绑定该点位</td></tr>
     *   <tr><td><b>右键</b></td><td>{@link #TELEPORT}：传送到该点位已存坐标去查看</td></tr>
     *   <tr><td><b>Shift + 左键</b></td><td>{@link #CLEAR}：清除该点位（与 {@code /incl arena remove} 同语义）</td></tr>
     * </table>
     */
    public enum Click {

        /** 左键：绑定（坐标来自「打开界面时站的位置」）。 */
        BIND,

        /** 右键：传送过去查看（未绑定时提示）。 */
        TELEPORT,

        /** Shift + 左键：清除（{@code CLEAR_SLOT} 上则要求它才生效，防误触）。 */
        CLEAR
    }

    /**
     * 把「右键 / 潜行」两个布尔量翻成一个语义（纯函数 ⇒ 可离线逐组合断言）。
     *
     * <p>★ {@code docs/09} §2.5 只定义了 {@code Shift + 左键 = 清除}，
     * <b>没有</b>定义 {@code Shift + 右键} ⇒ 后者按「右键」解释（{@link Click#TELEPORT}）：
     * 给未定义的手势编一个新含义就是替用户决定交互（铁律 11）。
     *
     * @param rightClick 是不是右键
     * @param shiftClick 是不是潜行点击
     */
    public static Click clickFor(boolean rightClick, boolean shiftClick) {
        if (shiftClick && !rightClick) {
            return Click.CLEAR;
        }
        return rightClick ? Click.TELEPORT : Click.BIND;
    }
}
