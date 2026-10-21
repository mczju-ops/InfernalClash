package io.mczju.infernalclash.objective;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ★★★ <b>遗留实体清理的判据（纯函数）</b> —— 2026-10-17 第十二轮，
 * 用户裁定 §A 第 1 条「<b>因为是测试服，所以破格一次，你直接全域清理</b>」。
 *
 * <h2>★★ 即使"全域"，本判据也守四条边界（用户原话是"全域清理"，但边界由代码兜住）</h2>
 * <ol>
 *   <li><b>归属只能靠 PDC</b>（{@code match} / {@code side} / {@code owner} / {@code taunt} 四键），
 *       <b>绝不</b>按"实体类型全清"、<b>绝不</b>碰"整个世界所有实体"。
 *       用户当年给 InfernalGuess 定的原话是「本游戏的清场只能清除<b>所有本插件接管</b>的实体」；</li>
 *   <li><b>连带半径是有限的、写死在纯类里</b>（{@link #LEGACY_RADIUS_CELLS}，<b>不做配置项</b> ——
 *       配置项就是放大器）。半径只用于圈定"我们自己的点位周围"，不是"整个世界"；</li>
 *   <li><b>失败方向</b>：点位缺失 / 世界取不到 ⇒ <b>一个都不删</b>（{@link Decision#SKIP_NO_CENTER}）；</li>
 *   <li><b>可对账</b>：{@link Plan} 带四类计数（PDC 命中 / 未带 PDC / 跳过 / 总数）。</li>
 * </ol>
 *
 * <h2>★ 为什么"未带 PDC 的遗留物"只报告不删</h2>
 * 旧版本（第十轮之前）生成的实体可能根本不带 PDC —— 本插件的新代码<b>认不出它们</b>。
 * 那种情况下唯一安全的动作是：
 * <ol>
 *   <li>把它们<b>连同坐标</b>列出来给用户；</li>
 *   <li>给一条<b>单独的有界指令</b>让用户自己决定（例如原版 {@code /kill @e[type=villager,...]}
 *       带精确坐标与半径）；</li>
 *   <li>★ <b>绝不</b>因为"它长得像我们的塔"就顺手扩大白名单 ——
 *       那正是当年把玩家假人、盔甲架、别的插件的怪一起删掉的那条路。</li>
 * </ol>
 *
 * <h2>★ 与 {@code UnitRegistry#cleanup} 的分工</h2>
 * <ul>
 *   <li>{@code cleanup} —— <b>账本还在</b>时的正常清场（按 UUID，无半径，最安全）；</li>
 *   <li><b>本类</b> —— <b>账本已经丢了</b>的旧账（第十轮之前那批「已删除 0 / 登记 11」的现场）
 *       ⇒ 只能靠 PDC + 有限半径重新把它们找回来。</li>
 * </ul>
 */
public final class LegacyPurge {

    /**
     * ★ <b>连带半径（格）</b> —— 以本插件自己的点位（信标 / 塔 / 出生点 / 河道 / 桥）为圆心。
     *
     * <p>为什么是 <b>64</b>：用户当年给 InfernalGuess 定的口径就是「以竞技场的玩家出生点为
     * 中心，仅清除 64 格内的相关实体」⇒ 沿用同一个数，不自己发明。
     * ★ <b>写死在纯类里</b>：做成配置项就等于给"放大范围"开了一个后门。
     */
    public static final int LEGACY_RADIUS_CELLS = 64;

    /** 单次清理的**硬上限**（防止误把范围配成全世界时一次性删掉几万个实体）。 */
    public static final int MAX_PURGE_PER_RUN = 512;

    /** 判据的三种结果。 */
    public enum Decision {
        /** ★ 带 PDC 且属于本插件 ⇒ **可以删**（{@code --yes} 时才真删）。 */
        PURGE,
        /** ★ 没带 PDC ⇒ **只报告**（连坐标一起给用户；绝不擅自扩大白名单）。 */
        REPORT_ONLY,
        /** 点位/世界缺失 ⇒ **跳过**（fail-safe：宁可留残留，绝不放宽范围）。 */
        SKIP_NO_CENTER
    }

    private LegacyPurge() {
    }

    /**
     * ★★ <b>一个实体该被怎么处置</b>（纯判据，可离线逐格断言）。
     *
     * @param hasOurPdc    该实体的 PDC 里有没有我们的归属键（{@code match} 等）
     * @param hasCenter    清理中心点是否可用（点位齐 / 世界取得到）
     * @param withinRadius 它在不在中心点的半径内
     */
    public static Decision decide(boolean hasOurPdc, boolean hasCenter, boolean withinRadius) {
        if (!hasCenter || !withinRadius) {
            return Decision.SKIP_NO_CENTER;
        }
        return hasOurPdc ? Decision.PURGE : Decision.REPORT_ONLY;
    }

    /**
     * 一个待处置的实体（**已经由调用方按有限范围采集好** —— 本类自己不做任何世界查询）。
     *
     * @param uuid       实体 UUID
     * @param type       实体类型名（报告用）
     * @param matchId    PDC 里的 {@code match} 值（没有 ⇒ {@code null}）
     * @param world      所在世界名（报告用）
     * @param x/y/z      坐标（报告用 —— 未带 PDC 的那批必须给用户**精确坐标**）
     * @param live       现在还活着 / 有效吗
     */
    public record Candidate(java.util.UUID uuid, String type, String matchId,
                            String world, double x, double y, double z, boolean live) {

        public Candidate {
            Objects.requireNonNull(uuid, "uuid 不能为空");
        }

        /** 有没有我们的 PDC 归属键。 */
        public boolean hasOurPdc() {
            return matchId != null && !matchId.isBlank();
        }

        /** 一行人类可读的描述（报告用）。 */
        public String describe() {
            return type + " @" + world + " (" + Math.round(x) + ", " + Math.round(y) + ", "
                    + Math.round(z) + ")" + (matchId == null ? "" : "｜matchId=" + matchId);
        }
    }

    /**
     * ★★★ <b>把「属于进行中对局的实体」滤掉</b>（2026-10-20 新增；这是一次**真实事故**的补丁）。
     *
     * <h2>事故（必须记住）</h2>
     * 把「开局前的遗留清理」挂到「准备阶段传送之后」时，顺序是
     * <b>生成目标物 → 进准备阶段 → 传送 → 清理</b> ⇒ 清理**把刚生成的 12 个目标物当成"遗留"删掉了**。
     * 现场表现：进对局后侧边栏 `塔左 0/240 塔右 0/240 信标 0/320`、场上一个目标物都没有；
     * 日志铁证：`目标物已就位 … 账本登记 12 个实体` 紧接着 `遗留清理完成：**已删除 12 个**`。
     *
     * <h2>规则</h2>
     * 「遗留」的**定义**是「不属于**任何进行中**的对局」⇒ 删之前必须再问一次这个 `matchId` 还活着吗。
     * 活着 ⇒ <b>既不删、也不当成"没带 PDC"报告</b>（那会刷屏）。
     *
     * @param all          候选（已按范围采集好）
     * @param isActiveMatch {@code matchId → 是否属于进行中的对局}（{@code null} ⇒ 一个都不滤）
     * @return 保序的新列表，只保留**不属于任何进行中对局**的那些
     */
    public static List<Candidate> excludingActiveMatches(List<Candidate> all,
                                                        java.util.function.Predicate<String> isActiveMatch) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (isActiveMatch == null) {
            return List.copyOf(all);
        }
        List<Candidate> kept = new ArrayList<>(all.size());
        for (Candidate candidate : all) {
            if (candidate.matchId() != null && isActiveMatch.test(candidate.matchId())) {
                continue;   // ★ 它是**正在进行**那一局的东西 —— 绝不碰
            }
            kept.add(candidate);
        }
        return List.copyOf(kept);
    }

    /**
     * 一次清理的**计划与对账**（纯数据）。
     *
     * @param purgeable   带 PDC、可以删的
     * @param reportOnly  没带 PDC、只报告的（★ 必须连坐标一起给用户）
     * @param skipped     范围外 / 中心点不可用的
     */
    public record Plan(List<Candidate> purgeable, List<Candidate> reportOnly,
                       List<Candidate> skipped) {

        public Plan {
            purgeable = List.copyOf(purgeable == null ? List.of() : purgeable);
            reportOnly = List.copyOf(reportOnly == null ? List.of() : reportOnly);
            skipped = List.copyOf(skipped == null ? List.of() : skipped);
        }

        /** ★ 恒等式：三类之和 == 扫到的总数（任何"扫到了却谁都没算"的都会让它 false）。 */
        public boolean balanced(int scanned) {
            return purgeable.size() + reportOnly.size() + skipped.size() == scanned;
        }

        /** 一页对账文字（**带数量**，铁律 10 第四问）。 */
        public String describe(int scanned) {
            return "扫到 " + scanned + " 个实体，可清（带本插件 PDC）" + purgeable.size()
                    + " 个 / 只报告（没带 PDC）" + reportOnly.size()
                    + " 个 / 跳过（范围外或中心点不可用）" + skipped.size()
                    + " 个｜对账" + (balanced(scanned) ? "通过" : "未通过");
        }

        /** 本次真正要删的数量（受 {@link #MAX_PURGE_PER_RUN} 限制）。 */
        public int purgeCount() {
            return Math.min(purgeable.size(), MAX_PURGE_PER_RUN);
        }

        /** 有没有被硬上限截断（截断了就必须明说，绝不能"删一半装作删完了"）。 */
        public boolean truncated() {
            return purgeable.size() > MAX_PURGE_PER_RUN;
        }
    }

    /**
     * 把候选实体分类成一个计划。
     *
     * @param candidates 候选（**由调用方在有限范围内采集**）
     * @param hasCenter  清理中心点可用吗
     * @param withinRadius 判定「在不在半径内」的函数（由调用方实现 —— 本类不碰 Bukkit）
     */
    public static Plan plan(List<Candidate> candidates, boolean hasCenter,
                            java.util.function.Predicate<Candidate> withinRadius) {
        List<Candidate> purgeable = new ArrayList<>();
        List<Candidate> reportOnly = new ArrayList<>();
        List<Candidate> skipped = new ArrayList<>();
        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            if (!candidate.live()) {
                continue;   // 已经不在了 ⇒ 三类都不进（它不需要任何人处理）
            }
            Decision decision = decide(candidate.hasOurPdc(), hasCenter,
                    hasCenter && withinRadius != null && withinRadius.test(candidate));
            switch (decision) {
                case PURGE -> purgeable.add(candidate);
                case REPORT_ONLY -> reportOnly.add(candidate);
                case SKIP_NO_CENTER -> skipped.add(candidate);
            }
        }
        return new Plan(purgeable, reportOnly, skipped);
    }

    /**
     * ★ <b>给"未带 PDC 的遗留物"准备一条单独的有界指令</b>（用户手动执行的草稿）。
     *
     * <p>★ 本方法**只生成文本**，绝不执行 —— 这是"不擅自扩大白名单"的落地形态：
     * 我们把精确坐标与类型整理好，决定权在用户手里。
     */
    public static List<String> suggestManualCommands(List<Candidate> reportOnly, int limit) {
        List<String> commands = new ArrayList<>();
        int count = 0;
        for (Candidate candidate : reportOnly == null ? List.<Candidate>of() : reportOnly) {
            if (count >= limit) {
                commands.add("…（还有 " + (reportOnly.size() - count) + " 个，见上面的清单）");
                break;
            }
            commands.add("/execute in " + candidate.world() + " run kill @e[type="
                    + candidate.type().toLowerCase(java.util.Locale.ROOT)
                    + ",x=" + Math.round(candidate.x()) + ",y=" + Math.round(candidate.y())
                    + ",z=" + Math.round(candidate.z()) + ",distance=..3]");
            count++;
        }
        return List.copyOf(commands);
    }

    /** 半径 64 格的方形判据（与 {@code objective/BeamRange} 同一口径：**格数型，不是浮点半径**）。 */
    public static boolean inSquare(double x, double z, double centerX, double centerZ, int halfExtent) {
        return Math.abs(x - centerX) <= halfExtent && Math.abs(z - centerZ) <= halfExtent;
    }

    /**
     * ★★★ <b>「删之前再核一次」的判据（纯函数）</b> —— 2026-10-21 新增，修的是一条**让整个清理形同虚设**的缺陷。
     *
     * <h2>用户看到的现象</h2>
     * 同一处地方立着**两个信标**（截图）：一个是本局刚生成的，另一个是**上一局没删掉的**。
     *
     * <h2>根因（日志铁证）</h2>
     * <pre>
     *   [IC] 遗留清理完成：**已删除 0 个**｜归属不符（PDC 复核未过）故意不删 32 个｜清理后范围内仍带本插件 PDC 的实体 448 个
     * </pre>
     * 执行层的「删前复核」原来调的是 {@code UnitRegistry#isOwnedBy(entity, matchId)}，
     * 而那个方法要求 <b>PDC 同意 <u>并且账本里也登记过</u></b>
     * （{@code table.isOwnedBy(uuid, matchId)}）。<br>
     * ★★ 但<b>遗留清理处理的就是"账本已经没了"的场景</b>（重启 / 关服后账本归零）⇒
     * 账本永远说"不认识它" ⇒ **每一个真·遗留物都被判成「归属不符」，一个都删不掉**。
     * 这与本类头部的口径（「归属判定 = PDC（match/side/owner/taunt）」）**直接矛盾** ——
     * 也就是"文档说按 PDC，代码却偷偷要求账本"。
     *
     * <h2>现在的判据</h2>
     * 只比 <b>PDC 里的 matchId</b>：两边都非空且相等 ⇒ 可以删；否则 ⇒ 故意不删（并计数）。
     * ★ 仍然保留的三道保险：① 候选必须先过「带我们的 PDC」（{@link Candidate#hasOurPdc()}）；
     * ② 必须先过「不属于任何**进行中**的对局」（{@link #excludingActiveMatches}）；
     * ③ 必须先过「在点位 64 格方形内」（{@link #inSquare}）。
     * ⇒ 放宽的只是"账本也要认"这一条**在遗留场景下必然为假**的条件，范围与归属判据一个字没动。
     *
     * @param pdcMatchId       实体 PDC 里记的 matchId（读不到 ⇒ {@code null}）
     * @param candidateMatchId 采集时记下的 matchId（{@code null} ⇒ 不删）
     * @return {@code true} = 复核通过，可以删
     */
    public static boolean stillOurs(String pdcMatchId, String candidateMatchId) {
        if (pdcMatchId == null || pdcMatchId.isBlank()) {
            return false;   // ★ PDC 复核未过：绝不删（那是"归属不符"，要计数并告警）
        }
        if (candidateMatchId == null || candidateMatchId.isBlank()) {
            return false;   // 采集时就没有归属 ⇒ 同样不删
        }
        return pdcMatchId.equals(candidateMatchId);
    }

    /** 供报告用的一行「范围界定」说明（把四问里的第一问答清楚）。 */
    public static String scopeProof() {
        return "范围 = 本插件 6 个目标物点位 ∪ 双方出生点 ∪ 河道/桥，各 " + LEGACY_RADIUS_CELLS
                + " 格方形；归属判定 = PDC（match/side/owner/taunt）"
                + "；没带 PDC 的只报告不删；点位缺失，一个都不删";
    }
}
