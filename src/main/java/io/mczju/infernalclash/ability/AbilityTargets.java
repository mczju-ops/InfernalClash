package io.mczju.infernalclash.ability;

import io.mczju.infernalclash.card.Spell;
import io.mczju.infernalclash.match.Side;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ★★★ <b>「一个能力的效果要打到哪些实体」</b>（纯函数）—— 2026-10-17 第十三轮<b>批次 3 后半</b>新增。
 *
 * <h2>它补的是哪一格（不是新玩法，是文档早就写了、schema 表达不出来的那一格）</h2>
 * {@code docs/19} 里有一整类能力写的是「<b>周围 N 格</b>」，而在此之前
 * {@link AbilityRuntime} <b>只会把效果施加到单个目标</b>（召唤物自己 / 召唤者）：
 * <ul>
 *   <li>{@code docs/19} 第 173 行「海王：<b>召唤时眩晕自身 2 格半径内所有敌人</b>并造成 8 点伤害」；</li>
 *   <li>{@code docs/19} 第 178 行「不朽旗手：亡语：<b>死亡时对周围 3 格敌人</b>造成 8 点伤害」；</li>
 *   <li>{@code docs/19} 第 176 行「蒸汽精灵：每 3s 使<b>周围 3 格内友方单位</b>恢复 8 点生命」；</li>
 *   <li>{@code docs/19} 第 174 行「冰术师：每 2s 对<b>目标及其周围 1 格内敌人</b>造成 4 点范围伤害 + 缓慢 II」。</li>
 * </ul>
 * ⇒ 这批卡在 {@link AbilityCatalog} 里被标成「未接线（卡里先别用）」，因为
 * <b>运行期拿不到"周围 N 格"这个目标集</b>。
 *
 * <h2>★★ 目标从哪来：账本，不是世界（铁律 10 的第一问）</h2>
 * 本类<b>不查世界</b>。候选由调用方（{@link AbilityRuntime}）从
 * {@code UnitRegistry#tracked(matchId)} —— <b>本场登记过的那一份 UUID 名单</b> ——
 * 解析实体后逐个喂进来。这与索敌（P5，{@code battle/CombatDirector}）和法术
 * （{@code spell/SpellCaster#collectTargets}）<b>是同一套归属口径</b>：
 * 「谁在范围内」=「<b>我账本里的</b>单位里谁在范围内」。
 * ⇒ 本类不出现 {@code getNearbyEntities} / {@code getEntities} / {@code getWorlds}
 * 任何一个记号（{@code guard/BytecodeScopeGuardTest} 拦着；本类也<b>不引</b> {@code org.bukkit}）。
 *
 * <h2>★ 半径口径</h2>
 * <ul>
 *   <li>单位 = <b>格</b>（方块），量的是 {@code origin} 到候选者位置的
 *       <b>3D 欧氏距离</b>（含 Y）—— 理由：竞技场是平的，两种量法在实机上等价，
 *       但含 Y 更不容易在"单位站在信标底座上"这种高度差里出现反直觉的命中；</li>
 *   <li>比较用<b>平方</b>距离（{@code d² <= r²}）⇒ 不开方、无浮点漂移
 *       —— 「同输入 ⇒ 同结果」是本项目的硬要求；</li>
 *   <li>★ <b>{@code radius: 0} 是合法值</b>：含义是「<b>只有和源点同一格</b>的那些单位」，
 *       与「不含自己」组合起来就是"不含自己的同格"。本类<b>不</b>把 0 当成非法
 *       （拒绝非正数是<b>装载期</b>的事，见 {@code CardCatalog} —— 它拒绝 {@code <= 0}）；</li>
 *   <li>★ 源点自己<b>不</b>因为"距离 0"而被自动排除：是否含自己由
 *       {@code includeSelf} <b>显式</b>决定。「海王登场眩晕周围敌人」当然不含自己，
 *       但将来若有「登场时给周围所有单位（含自己）加速」的能力，那时它必须由配置说了算。</li>
 * </ul>
 *
 * <h2>★ 失败方向</h2>
 * {@code radius == null} ⇒ <b>只打自己</b>（= 本项目在本次改动之前的既有行为，
 * 所以给老卡补 {@code targets} 不会静默改变任何一张没写它的卡）。
 * 候选者 UUID 为 {@code null} / 侧别为 {@code null} / 坐标为 {@code null} ⇒ <b>逐个跳过</b>，
 * 绝不因此抛异常（能力是对局中途的自动触发，抛出去会把整场炸掉）。
 *
 * <h2>可离线测试</h2>
 * 全类只依赖 {@link Side} 与 {@link UUID} ⇒ 逐条边界（半径内 / 外 / 正好等于、
 * 三个 {@code targets} 取值、含/不含自己）都能在 JUnit 里穷举断言。
 * ★ 真正"把效果打到活实体上"的那一半仍然只能实机验证（{@code docs/12} §4.28.13）。
 */
public final class AbilityTargets {

    /**
     * <b>哪些阵营算"目标"</b>（本能力的作用面）。
     *
     * <p>取值来自 {@code docs/19} 的逐卡原文，<b>不是</b>我按"敌方/友方"顺手一刀切：
     * <ul>
     *   <li>{@link #SELF} —— 只打自己（<b>缺省</b>；「煤矿守卫登场获得抗性 V」）；</li>
     *   <li>{@link #ENEMIES} —— 「周围 N 格<b>敌人</b>」（海王 / 不朽旗手 / 冰术师）；</li>
     *   <li>{@link #ALLIES} —— 「周围 N 格<b>友方单位</b>」（蒸汽精灵；★ 含召唤者自己，
     *       因为召唤物与召唤者同阵营）；</li>
     *   <li>{@link #EVERYONE} —— 敌方与友方都算（目前没有卡用；留着是因为
     *       「不分阵营的 AoE」在本项目里是<b>已知会咬人的形状</b>（见 {@code docs/19}
     *       第 149 行「溅射药水不区分阵营」），有名字才能被测试与文档点名）。</li>
     * </ul>
     */
    public enum Alignment {
        /** 只打自己（缺省行为，等价于本次改动之前的实现）。 */
        SELF,
        /** 只打源点所属阵营<b>以外</b>的单位。 */
        ENEMIES,
        /** 只打源点所属阵营<b>相同</b>的单位。 */
        ALLIES,
        /** 不分阵营（★ 需要使用者自己写清理由）。 */
        EVERYONE
    }

    /**
     * ★★ <b>范围的「中心」取在哪</b>（2026-10-17 批次 3 后半新增）。
     *
     * <p>出处是 {@code docs/19} 第 174 行对冰术师的原文：
     * 「每 2s 对<b>目标及其周围 1 格内敌人</b>造成 4 点范围伤害 + 缓慢 II」
     * —— 注意那个「<b>目标</b>」：中心<b>不是</b>冰术师自己，而是<b>它当前锁定的那个敌人</b>。
     * 少了这一维，「目标及其周围 1 格」就只能被近似成「自己周围 1 格」（打空）。
     *
     * <p>★ 取值与「有没有目标」的失败方向：
     * <ul>
     *   <li>{@link #SELF}（缺省）—— 中心 = 触发这个能力的单位自己
     *       （海王「自身 2 格半径」/ 不朽旗手「周围 3 格」/ 蒸汽精灵「周围 3 格」都是它）；</li>
     *   <li>{@link #ATTACK_TARGET} —— 中心 = 该单位<b>当前锁定的目标</b>。
     *       ★ <b>没有目标 / 目标已死 ⇒ 整条能力什么都不做</b>（不是"退回自己周围"）：
     *       冰术师的设计是「打它正在瞄的那个敌人」，把范围搬到自己脚下会变成另一种能力。</li>
     *   <li>{@link #NEAREST_ENEMY} —— 中心 = <b>半径内离它最近的那个敌人</b>
     *       （死灵法师「8 格内目标处」）。★ 它<b>不依赖原版选敌</b>：
     *       只看我们账本里的敌方候选、按距离取最近。</li>
     * </ul>
     */
    public enum Center {
        /** 以触发者自己为中心（缺省）。 */
        SELF,
        /** 以触发者当前锁定的目标为中心（没有目标 ⇒ 整条能力不做）。 */
        ATTACK_TARGET,
        /**
         * ★★ <b>以「半径内最近的那个敌人」为中心</b>（2026-10-17 批次 3 收尾新增）。
         *
         * <p>出处 = {@code docs/19} 第 87 行（死灵法师）：「每 3s 在<b>8 格内</b>目标处放一次
         * 火球术」—— 「8 格内」是<b>搜索半径</b>，目标本身是<b>一个</b>（最近的那个）。
         * ★ 与 {@link #ATTACK_TARGET} 的差别是关键的：那个要求它<b>已经被</b>原版 AI 锁了目标
         * （僵尸会锁，但那是"谁打它 / 谁最近"的原版口径，还可能被原版锁到队友身上），
         * 而这个只看<b>我们自己的账本</b> ⇒ 候选里结构上就没有队友与玩家。
         * ★ 失败方向：半径内没有敌人 ⇒ <b>整条能力不做</b>（不退回"自己周围"）。
         */
        NEAREST_ENEMY
    }

    /** 能力里能写的 targeting 键（供装载期拒绝未知键；与 {@code CardCatalog} 同源使用）。 */
    public static final Set<String> KEYS = Set.of("side", "include-self", "radius", "center");

    private AbilityTargets() {
    }

    /**
     * 一个候选者（由调用方从账本解析出来的一个实体）。
     *
     * @param id   实体 UUID
     * @param side 该实体的阵营（{@code null} = 未知 ⇒ 除 {@link Alignment#EVERYONE} 外一律不命中）
     * @param x    当前 X
     * @param y    当前 Y
     * @param z    当前 Z
     */
    public record Candidate(UUID id, Side side, double x, double y, double z) {

        /** 便利构造：坐标由三个 double 给。 */
        public static Candidate of(UUID id, Side side, double x, double y, double z) {
            return new Candidate(id, side, x, y, z);
        }
    }

    /**
     * ★★ <b>解析本次触发真正要吃这个效果的目标集</b>（纯函数）。
     *
     * <p>顺序 = <b>候选清单的顺序</b>（调用方给的是账本的保序名单，
     * {@code UnitRegistry} 用 {@link java.util.LinkedHashMap}）⇒「同输入 ⇒ 同结果」。
     * 结果里<b>不会有重复</b>（同一个 UUID 出现两次也只算一次）。
     *
     * @param alignment   作用面（{@code null} ⇒ 按 {@link Alignment#SELF}）
     * @param includeSelf 攻击者自己是否算目标（{@link Alignment#SELF} 时忽略 —— 那种情况下它必然算）
     * @param radius      ★ 半径（格）；{@code null} ⇒ <b>只看自己</b>（既有行为，不静默扩范围）
     * @param center      ★ 范围中心（{@code null} ⇒ 判不出范围，退化成"只看自己"）；
     *                    ★★ <b>{@code null} 与「中心 = 攻击者的当前目标」是两件事</b>：
     *                    后者由调用方传一个真实坐标进来（它拿不到目标时<b>不该调本方法</b>，
     *                    见 {@link Center#ATTACK_TARGET} 的失败方向）
     * @param attackerId  触发这个能力的单位 UUID（召唤物自己）
     * @param attackerSide 它的阵营（{@code null} = 未知）
     * @param candidates  候选者（本场账本解析出来的单位；{@code null} ⇒ 空）
     * @return 目标 UUID 列表（保序、去重、可能为空）
     */
    public static List<UUID> resolve(Alignment alignment, boolean includeSelf, Integer radius,
                                     UUID attackerId, Side attackerSide, double[] center,
                                     List<Candidate> candidates) {
        Alignment side = alignment == null ? Alignment.SELF : alignment;
        List<UUID> out = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();

        // ---- 自己：SELF 一律算；其它作用面看 includeSelf ----
        boolean selfWanted = side == Alignment.SELF || includeSelf;
        if (selfWanted && attackerId != null) {
            add(out, seen, attackerId);
        }

        // ---- 范围：没有半径 ⇒ 到此为止（= 本次改动之前的既有行为）----
        if (radius == null || center == null || candidates == null || candidates.isEmpty()) {
            return Collections.unmodifiableList(out);
        }
        double r2 = (double) radius * (double) radius;
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.id() == null) {
                continue;   // 逐个跳过（绝不因为一条脏数据炸掉整场）
            }
            if (!inRange(candidate, center, r2)) {
                continue;
            }
            if (!sideMatches(side, attackerSide, candidate.side())) {
                continue;
            }
            add(out, seen, candidate.id());
        }
        return Collections.unmodifiableList(out);
    }

    /** ★ 距离判定：用**平方**比较（不开方 ⇒ 无浮点漂移）。 */
    private static boolean inRange(Candidate candidate, double[] center, double r2) {
        double dx = candidate.x() - center[0];
        double dy = candidate.y() - center[1];
        double dz = candidate.z() - center[2];
        return dx * dx + dy * dy + dz * dz <= r2;
    }

    /**
     * 阵营判定（★ 未知阵营的处理是<b>刻意的</b>）。
     *
     * <p>「两侧都已知且不同 ⇒ 敌；都已知且相同 ⇒ 友」—— 与
     * {@code policy/SideRule}（那条修「防御塔打己方玩家」的纯函数）<b>同一口径</b>：
     * <b>未知不算敌人</b>。否则一个阵营查不出来的单位会被"周围 N 格"顺手打到，
     * 而那正是第十轮那次事故的形状（未知被当成敌方）。
     */
    private static boolean sideMatches(Alignment alignment, Side attackerSide, Side candidateSide) {
        return switch (alignment) {
            case SELF -> false;               // 自己那一份已经在上面处理过
            case EVERYONE -> true;
            case ENEMIES -> attackerSide != null && candidateSide != null
                    && attackerSide != candidateSide;
            case ALLIES -> attackerSide != null && candidateSide != null
                    && attackerSide == candidateSide;
        };
    }

    private static void add(List<UUID> out, Set<UUID> seen, UUID id) {
        if (seen.add(id)) {
            out.add(id);
        }
    }

    /**
     * ★★ <b>「周围 N 格」这个范围该不该摊给这一条效果</b>。
     *
     * <p>有一条<b>结构上</b>的边界必须先划清：{@code GRANT_XP} / {@code REFRESH_COOLDOWN} /
     * {@code COOLDOWN_REDUCTION} 三类效果作用于「<b>本卡 / 施放者</b>」，
     * <b>不看目标实体</b>（出处：{@code spell/EffectScope#runsWithoutTarget} 把这三类
     * 与召唤/嵌套并列为"不需要目标实体"的效果）。
     * ⇒ 把「周围 3 格」摊给它们，等于给"召唤者/本卡"重复施加三次同一件事
     * （{@code GRANT_XP} 还会把经验加到自己身上 —— 那是 {@code docs/07} §6.4 明确否掉的读法）。
     * <b>所以这三类即使写了 {@code radius}，也仍然只作用一次。</b>
     * ★ 这不是"忽略配置"，而是那三类效果<b>本来就没有"目标"这一维</b>；
     * 运行期为此<b>不</b>告警（告警会把"每 5s 一次"的周期能力刷屏），
     * 判据本身有单测逐类钉住。
     *
     * <p>★ 与 {@code EffectScope} 的分工：那边回答「<b>一个目标都没有时还能不能跑</b>」，
     * 这边回答「<b>范围要不要摊开</b>」—— 同一个三分法，但问的是不同的问题，
     * 所以两边各有一份、各有一条单测；{@code AllEffectTypesKeySetPinnedTest} 之外，
     * 本判据还有一条<b>双向</b>断言（{@code AbilityTargetsTest}）。
     */
    public static boolean rangeApplies(Spell.EffectType type) {
        return type != null && !SELF_ONLY_TYPES.contains(type);
    }

    /**
     * ★ <b>「只作用于本卡 / 施放者」的效果类型</b>（{@link #rangeApplies} 的数据来源）。
     *
     * <p>原文出处：{@code spell/EffectScope} 把这几类与召唤/嵌套一起列为
     * 「不需要目标实体」—— 因为它们的语义锚点是<b>施放者</b>或<b>这张卡本身</b>，
     * 不是"被打到的那个实体"。
     */
    private static final Set<Spell.EffectType> SELF_ONLY_TYPES = java.util.EnumSet.of(
            Spell.EffectType.GRANT_XP,
            Spell.EffectType.REFRESH_COOLDOWN,
            Spell.EffectType.COOLDOWN_REDUCTION);

    /** ★ 那些「只作用于本卡 / 施放者」、因而不吃范围的效果类型（供测试与文档引用）。 */
    public static Set<Spell.EffectType> selfOnlyTypes() {
        return SELF_ONLY_TYPES;
    }

    /**
     * ★★ <b>「半径内最近的那个敌人」</b>（纯粹给 {@link Center#NEAREST_ENEMY} 用）。
     *
     * <p>与 {@link #resolve} 的差别只有一条：它<b>只返回一个</b>（最近的），
     * 而 {@code resolve} 返回范围内全部。
     * <ul>
     *   <li>候选仍然只从<b>账本</b>来（调用方给），所以队友与玩家<b>结构上</b>不在里面；</li>
     *   <li>阵营判据与 {@link #resolve} <b>同一个</b>（未知不算敌人）；</li>
     *   <li>并列（距离相同）⇒ 按候选清单顺序先到先得（保序 ⇒ 同输入同结果）。</li>
     * </ul>
     *
     * @return 最近的那个敌人 UUID；没有 ⇒ 空清单
     */
    public static List<UUID> resolveNearestEnemy(Side attackerSide, double[] center, double radius,
                                                 List<Candidate> candidates) {
        if (center == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        double r2 = radius * radius;
        Candidate best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.id() == null) {
                continue;
            }
            if (!sideMatches(Alignment.ENEMIES, attackerSide, candidate.side())) {
                continue;
            }
            double dx = candidate.x() - center[0];
            double dy = candidate.y() - center[1];
            double dz = candidate.z() - center[2];
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance > r2) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best == null ? List.of() : List.of(best.id());
    }

    /** 中文说明（装载期报错与告警里用）。 */
    public static String describe(Alignment alignment, Integer radius, boolean includeSelf) {
        return describe(alignment, radius, includeSelf, null);
    }

    /** 同上，并把「中心取在哪」也说清楚（现场日志要能一眼看懂这条能力打的是谁周围）。 */
    public static String describe(Alignment alignment, Integer radius, boolean includeSelf,
                                  Center center) {
        Alignment side = alignment == null ? Alignment.SELF : alignment;
        String who = switch (side) {
            case SELF -> "自己";
            case ENEMIES -> "周围敌人";
            case ALLIES -> "周围友方";
            case EVERYONE -> "周围所有单位（不分阵营）";
        };
        if (side == Alignment.SELF && (center == null || center == Center.SELF)) {
            return who;
        }
        String where = switch (center) {
            case ATTACK_TARGET -> "锁定目标";
            case NEAREST_ENEMY -> "最近的敌人";
            case null, default -> "自己";
        };
        return who + "（以" + where + "为中心，半径 " + radius + " 格"
                + (includeSelf ? "，含自己" : "，不含自己") + "）";
    }
}
