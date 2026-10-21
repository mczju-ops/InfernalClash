package io.mczju.infernalclash.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>「谁骑谁」的配对（纯函数）</b> —— 2026-10-17 批次 3 后半新增。
 *
 * <h2>它补的是哪一格</h2>
 * {@code docs/03} §4.6 早就登记过这个缺口：
 * 「<b>载具配对</b>：两个 {@code group} 之间没有骑乘关系 ⇒ <b>P4</b> 加 {@code group[].mount}」，
 * 而 {@code docs/19} 里有 <b>三张卡</b>逐字要求骑乘：
 * <ul>
 *   <li>第 106 行 前储君：「骑<b>僵尸马</b>的持铁矛僵尸」；</li>
 *   <li>第 109 行 炽足兽骑士：「骑<b>炽足兽</b>的持矛僵尸猪灵」；</li>
 *   <li>第 152 行 劫掠堡垒：「<b>骑乘 1 名劲弩手</b>」。</li>
 * </ul>
 * 用户裁定（{@code docs/24} §10.1 第 8 条）：「<b>骑乘要实现</b>（虽然没测到）」。
 *
 * <h2>★★ 为什么配对逻辑要抽成纯函数</h2>
 * 「哪个实体坐上哪个实体」这件事在服务端只有一次机会做对：一旦 {@code addPassenger}
 * 坐错了，现场表现是「僵尸骑在僵尸马上」这种**看起来对但其实错位**的画面，
 * 而那要靠真人盯着看才发现。抽成纯函数后，「乘客组 i 坐进载具组 j」可以在
 * JUnit 里逐格断言（{@code MountPlanTest}）。
 *
 * <h2>口径（三条，都要能被复述）</h2>
 * <ol>
 *   <li>配对按<b>声明顺序</b>：载具组必须<b>先于</b>乘客组出现（{@code docs/19} 的三张卡
 *       都是这个写法）；乘客组写 {@code mount: <载具的实体名>} 指回它。
 *       ★ 「载具先声明」还顺手保证了一件事：<b>载具一定先被生成</b>
 *       ⇒ 挂载时它必然已经存在（{@code SummonSpawner} 是按组顺序生成的）。</li>
 *   <li>同一个载具上的<b>多名乘客按声明顺序依次落座</b>（原版 {@code addPassenger}
 *       自己会往后排座位）；<b>座位满了</b>由 {@code addPassenger} 返回 {@code false}
 *       告诉我们（那时只告警，绝不把乘客删掉）。</li>
 *   <li>写了一个<b>不存在的载具名</b> / 把自己写成了自己的载具
 *       ⇒ 装载期**拒绝启动**（见 {@code CardCatalog#parseGroup}），运行期这里只做兜底跳过。</li>
 * </ol>
 *
 * <h2>★ 铁律 10</h2>
 * 本类不碰服务端（只认组下标与字符串）⇒ 每次召唤各自的实例，没有共享状态、
 * 也没有任何「按类型找附近的实体」那种无边查询。
 */
public final class MountPlan {

    /**
     * 一组召唤物（供配对用）。
     *
     * @param entity 实体类型名（大写，如 {@code STRIDER}）—— 乘客用它指回载具
     * @param mount  ★ 该组要骑上去的<b>实体类型名</b>；{@code null} / 空 = 这组不骑任何东西
     */
    public record Group(String entity, String mount) {

        public boolean ridesSomething() {
            return mount != null && !mount.isBlank();
        }
    }

    /** 一条配对：{@code passenger} 组坐进 {@code vehicle} 组。 */
    public record Pair(int vehicle, int passenger) {
    }

    private MountPlan() {
    }

    /**
     * 算出这次的配对清单（保序、可复述）。
     *
     * @param groups 逐组声明（{@code null} / 空 ⇒ 空结果）
     * @return 配对清单；{@code null} 项与"载具不存在"的那些<b>跳过</b>（不抛 ——
     *         装载期已经拦过，这里是第二道，失败方向是"少坐一个人"而不是"炸掉整次召唤"）
     */
    public static List<Pair> pairs(List<Group> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        // ★ 载具名 → 组下标（**只认第一个**：同名载具组写两份时，乘客坐先声明的那一份）
        Map<String, Integer> vehicles = new LinkedHashMap<>();
        for (int index = 0; index < groups.size(); index++) {
            Group group = groups.get(index);
            if (group == null || group.entity() == null || group.entity().isBlank()) {
                continue;
            }
            vehicles.putIfAbsent(normalize(group.entity()), index);
        }
        List<Pair> pairs = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            Group group = groups.get(index);
            if (group == null || !group.ridesSomething()) {
                continue;
            }
            Integer vehicle = vehicles.get(normalize(group.mount()));
            if (vehicle == null || vehicle == index) {
                continue;   // 载具不存在 / 自己骑自己 ⇒ 跳过（装载期本应已拒绝）
            }
            pairs.add(new Pair(vehicle, index));
        }
        return List.copyOf(pairs);
    }

    /** 一行摘要（日志用）。 */
    public static String describe(List<Group> groups, List<Pair> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return "（没有骑乘配对）";
        }
        StringBuilder builder = new StringBuilder();
        for (Pair pair : pairs) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(entityAt(groups, pair.passenger()))
                    .append(" → 骑上 ")
                    .append(entityAt(groups, pair.vehicle()));
        }
        return builder.toString();
    }

    private static String entityAt(List<Group> groups, int index) {
        if (groups == null || index < 0 || index >= groups.size() || groups.get(index) == null) {
            return "<越界>";
        }
        return groups.get(index).entity();
    }

    /** 实体名归一化（大小写与空白不该让配对失败）。 */
    private static String normalize(String name) {
        return name == null ? "" : name.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
