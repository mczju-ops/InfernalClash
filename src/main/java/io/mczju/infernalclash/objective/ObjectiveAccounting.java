package io.mczju.infernalclash.objective;

import java.util.List;

/**
 * ★★ <b>目标物的「生成 / 登记 / 回收」三处计数口径与对账（纯函数）</b>
 * —— 2026-10-17 第十轮新增，缺陷 P0-c 的落点（用户原话「<b>对局结束后防御塔等东西没有被移除</b>」）。
 *
 * <h2>★★ 为什么必须有这个类（现场日志把口径不一致钉死了）</h2>
 * 第十轮开工前，现场 {@code logs/latest.log} 里同一场对局的三行原文是：
 * <pre>
 *   18:21:01 [IC] 已生成 红方信标（BEACON）@lobby 0,75,-130 …      ← 6 行「已生成」，共 6 个目标物
 *   18:21:01 [IC] 目标物已就位：…｜信标 2 / 防御塔 4｜账本登记 12 个实体（含展示实体）
 *   18:21:41 [IC] 目标物已回收 3 个（结束，matchId=default）        ← ★ 6 变成 3
 *   18:21:41 [IC] 清场完成：matchId=default 已删除 0 / 登记 11（已失效 11，归属不符 0）
 * </pre>
 * 三个数字（<b>6 / 12 / 3</b>）各自都"对"，但它们**口径不同**：
 * <ul>
 *   <li>6 = 目标物个数（载体）；</li>
 *   <li>12 = 账本登记条数 = <b>载体 + 展示实体</b>（每个目标物 2 条）；</li>
 *   <li>3 = <b>只数了收尾那一刻还留在内存列表里的载体</b> —— 另外 3 个在中途被
 *       「当成死了」摘掉了（详见 {@code ObjectiveUnit#isAlive} 的 javadoc：
 *       那时用的是 {@code carrier.isValid()}，区块卸载会把它变成 false）。</li>
 * </ul>
 * ⇒ 本类把这三处口径**写成常量与公式**，并给出一条**恒等式**
 * （{@link Recovery#balanced()}）⇒ 「生成 N ⇒ 回收 N」这件事第一次可以被离线断言。
 *
 * <p>★ 纯类（只吃整数与字符串）⇒ 可离线逐格断言；已登记进两处守卫。
 */
public final class ObjectiveAccounting {

    /**
     * ★ 一个目标物在账本里占几条：<b>载体（村民）+ 展示实体（方块）</b>。
     *
     * <p>出处：{@code objective/ObjectiveUnit#spawn} 里对两者各调一次
     * {@code registry.tag(...)}（只登记载体 = 清场时留下一块飘在空中的方块）。
     */
    public static final int ENTITIES_PER_UNIT = 2;

    private ObjectiveAccounting() {
    }

    /** 目标物个数 ⇒ 账本登记条数（唯一公式，不许在别处再算一遍）。 */
    public static int registeredEntities(int units) {
        if (units < 0) {
            throw new IllegalArgumentException("目标物个数不能是负数：" + units);
        }
        return units * ENTITIES_PER_UNIT;
    }

    /** ★ 「生成 N ⇒ 回收 N」这条对账断言的可调用形式。 */
    public static boolean reconciles(int spawnedUnits, int recoveredUnits) {
        return spawnedUnits == recoveredUnits;
    }

    /**
     * ★★ <b>一次回收的对账结果</b>（把四个桶写全，缺一个就 {@link #balanced()} 为 false）。
     *
     * @param spawnedUnits      本局**生成过**的目标物个数（含中途死亡的那些）
     * @param recoveredUnits    本局**回收掉**（交给 {@code remove}）的目标物个数
     * @param registeredEntities 本局**登记过**的实体条数（{@link #registeredEntities(int)}）
     * @param removed           本次清场**真的删掉**的实体条数
     * @param pending           ★ **没能删掉、留在账本里等重试**的实体条数（区块未加载）
     * @param mismatched        归属不符（已不属于本场 ⇒ 故意不删）的实体条数
     */
    public record Recovery(int spawnedUnits,
                           int recoveredUnits,
                           int registeredEntities,
                           int removed,
                           int pending,
                           int mismatched) {

        public Recovery {
            List<Integer> values = List.of(spawnedUnits, recoveredUnits, registeredEntities,
                    removed, pending, mismatched);
            for (int value : values) {
                if (value < 0) {
                    throw new IllegalArgumentException("对账计数的任何一项都不能是负数：" + values);
                }
            }
        }

        /**
         * ★ 恒等式：<b>登记过的每一条实体，都必须落进恰好一个桶</b>
         * （删掉 / 待回收 / 归属不符）—— 再加上「回收的目标物数 == 生成的目标物数」。
         *
         * <p>这就是本项目的对账断言：任何一条「生成了却没被算进任何一个桶」的实体
         * （= 现场那种"塔还立着但账本已经忘了它"的漏回收）都会让它变 false。
         */
        public boolean balanced() {
            return recoveredUnits == spawnedUnits
                    && removed + pending + mismatched == registeredEntities;
        }

        /** ★ 是不是**一个都不剩**（没有待回收项）。 */
        public boolean fullyRemoved() {
            return pending == 0;
        }

        public String describe() {
            return "生成 " + spawnedUnits + " 个目标物，回收 " + recoveredUnits + " 个"
                    + "｜账本登记 " + registeredEntities + " 个实体"
                    + "（实删 " + removed + "，待回收 " + pending + "，归属不符 " + mismatched + "）";
        }
    }

    /**
     * 生成阶段的一行日志（★ 把「目标物个数」与「账本实体条数」**在同一行里**写清楚 ——
     * 现场那三行口径不一致的日志就是漏回收能藏两轮的原因）。
     */
    public static String spawnLine(String matchId, int units, int registeredEntities) {
        return "[IC] 目标物已就位：matchId=" + matchId + "｜目标物 " + units + " 个"
                + "（" + (units / 2) + " 信标 + " + (units - units / 2) + " 防御塔）"
                + "｜账本登记 " + registeredEntities + " 个实体"
                + "（载体 " + units + " + 展示实体 " + (registeredEntities - units) + "）"
                + "｜口径：目标物个数 ≠ 账本条数（1 个目标物 = " + ENTITIES_PER_UNIT + " 条）";
    }

    /** 收尾阶段的一行日志（带 reason 与 {@link Recovery#describe()}）。 */
    public static String recoveryLine(String matchId, String reason, Recovery recovery) {
        return "[IC] 目标物已回收（" + reason + "，matchId=" + matchId + "）｜" + recovery.describe();
    }
}
