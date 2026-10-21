package io.mczju.infernalclash.objective;

/**
 * ★★★ <b>「这个目标物实体现在该怎么回收」—— 纯判据</b>
 * （2026-10-21 缺陷修复：用户实机报「<b>对局中对面塔被拆了之后建筑没有移除</b>」）。
 *
 * <h2>它修的是什么</h2>
 * {@code ObjectiveUnit#retire} 原来是四个 `if` 直接写在方法体里，其中**两条是静默的**：
 * <pre>
 *   if (账本不认它)        return SETTLED;   // ← 静默：直接当"已经处理过"
 *   if (有阳性死亡证据)    forget + SETTLED; // ← 静默：同样不删
 * </pre>
 * 这两条对**已经不在世界里的**实体是对的（省一次查询），但对**还立在那里的**实体
 * 就是「<b>永久遗留</b>」：账本一忘，再没有人会去删它 —— 表现就是
 * 「塔被打掉了，铜灯方块还飘在原地」，而且**日志里一个字都没有**。
 *
 * <h2>★★ 判据（按顺序，第一条命中即返回）</h2>
 * <table border="1">
 *   <caption>输入 → 动作</caption>
 *   <tr><th>#</th><th>条件</th><th>动作</th></tr>
 *   <tr><td>1</td><td>PDC 说它**不属于本局**（读不到 / 别的 matchId）</td>
 *       <td>{@link Decision#MISMATCHED} —— **绝不删**（铁律 10 第三问：归属判定靠 PDC）</td></tr>
 *   <tr><td>2</td><td>★ 账本**已经不认它**、但 PDC 说它是本局的、且**实体还在世界里**</td>
 *       <td>{@link Decision#DELETE_LEDGER_LOST} —— **照删** ＋ 告警
 *           （这才是那条「飘着的方块」的正确结局：账本脱节不等于别人家的东西）</td></tr>
 *   <tr><td>3</td><td>账本不认、且实体也解析不到</td><td>{@link Decision#SETTLED_GONE}（无账可对）</td></tr>
 *   <tr><td>4</td><td>★ 有阳性死亡证据、但**实体仍然解析得到**（自相矛盾）</td>
 *       <td>{@link Decision#DELETE_MARKED_BUT_ALIVE} —— **照删** ＋ 告警
 *           （"已经死了"与"还站着"不可能同时为真；宁可按归属删掉我们的东西）</td></tr>
 *   <tr><td>5</td><td>有阳性死亡证据、实体确实解析不到</td><td>{@link Decision#SETTLED_GONE}</td></tr>
 *   <tr><td>6</td><td>账本认、没有死亡证据、实体解析得到</td><td>{@link Decision#DELETE}（正常路径）</td></tr>
 *   <tr><td>7</td><td>账本认、没有死亡证据、实体解析不到（区块未加载）</td>
 *       <td>{@link Decision#PENDING} —— 留在账本里等每秒重试（**绝不谎报成功**）</td></tr>
 * </table>
 *
 * <h2>★ 纯（{@code AGENTS.md} §3）</h2>
 * 只吃四个布尔、只吐一个枚举 ⇒ 七格可以在普通 JUnit 里**逐格**断言
 * （{@code objective/ObjectiveRetireTest}），不需要起服务端、不需要造实体。
 */
public final class ObjectiveRetire {

    /** 回收动作。 */
    public enum Decision {

        /** ★ <b>绝不删</b>：PDC 说它不是本局的（读不到 / 别的对局）。 */
        MISMATCHED(false, false),

        /** ★ 账本**不认它**、但 PDC 认、且实体还在 ⇒ **照删**（＋告警）。 */
        DELETE_LEDGER_LOST(true, true),

        /** 账本不认、实体也解析不到 ⇒ 无账可对（不需要删除）。 */
        SETTLED_GONE(false, false),

        /** ★ 有阳性死亡证据、但实体仍然解析得到 ⇒ **照删**（＋告警）。 */
        DELETE_MARKED_BUT_ALIVE(true, true),

        /** 正常路径：账本认、实体在 ⇒ 删。 */
        DELETE(true, false),

        /** 实体解析不到（区块未加载）⇒ 留在账本里等重试。 */
        PENDING(false, false);

        private final boolean delete;
        private final boolean warn;

        Decision(boolean delete, boolean warn) {
            this.delete = delete;
            this.warn = warn;
        }

        /** 这个动作要不要**真的调** {@code entity.remove()}。 */
        public boolean deletes() {
            return delete;
        }

        /** 这个动作要不要在日志里**告警**（只在两种「自相矛盾」的格子上为真）。 */
        public boolean warns() {
            return warn;
        }

        /** 这个动作算不算「已经落袋」（不需要留在账本里等重试）。 */
        public boolean settled() {
            return this != PENDING;
        }
    }

    private ObjectiveRetire() {
    }

    /**
     * ★ 纯判据（见类注释的表：7 格，按顺序）。
     *
     * @param pdcSaysThisMatch PDC 里记的 matchId 是不是**本局**（读不到 / 别的对局 ⇒ {@code false}）
     * @param ledgerKnows      归属账本里还登记着它吗
     * @param markedDead       有没有**阳性死亡证据**（{@code EntityDeathEvent} 打的标记）
     * @param resolves         现在能从世界里解析到这个实体吗（{@code Bukkit.getEntity != null}）
     */
    public static Decision decide(boolean pdcSaysThisMatch, boolean ledgerKnows,
                                 boolean markedDead, boolean resolves) {
        if (!pdcSaysThisMatch) {
            return Decision.MISMATCHED;      // ① 不是本局的东西 ⇒ 绝不碰
        }
        if (!ledgerKnows) {
            // ②③ 账本脱节：**实体还在就照删**（否则永久遗留），否则无账可对
            return resolves ? Decision.DELETE_LEDGER_LOST : Decision.SETTLED_GONE;
        }
        if (markedDead) {
            // ④⑤ 「已死」与「还站着」不可能同时为真 ⇒ 以归属为准删掉
            return resolves ? Decision.DELETE_MARKED_BUT_ALIVE : Decision.SETTLED_GONE;
        }
        // ⑥⑦ 正常路径
        return resolves ? Decision.DELETE : Decision.PENDING;
    }
}
