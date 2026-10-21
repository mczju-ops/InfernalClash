package io.mczju.infernalclash.battle;

import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import org.bukkit.entity.Mob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ★★★ <b>「分心 goal」清单（IG 同款，19 条）</b> —— 2026-10-21 用户裁定。
 *
 * <h2>用户原话（逐字）</h2>
 * 「1、对于所有怪：**IG 同款补全（即选 B）**」——
 * 指的是我上一条回答里列的 IG（`MCZJUGuess`）那张表。
 *
 * <h2>出处（不是凭印象，是逐条抄的）</h2>
 * {@code MCZJUGuess/src/main/java/io/mczju/infernalguess/battle/BattleService.java}
 * **L1296~1336** 的 {@code DISTRACTION_KEYS}，共 **19** 条，四族：
 * <table border="1">
 *   <caption>四族</caption>
 *   <tr><th>族</th><th>为什么摘（IG 的实测记录）</th></tr>
 *   <tr><td>{@code PANIC} ×6</td>
 *       <td>持续伤害（IG 是毒雾）⇒ {@code getLastHurtByMob() != null} 一直成立 ⇒
 *           惊恐 goal 一直抢占行为槽（优先级高于索敌）⇒ <b>只会逃跑</b></td></tr>
 *   <tr><td>溺尸找水 ×3 / 躲太阳 · 找水 ×2</td>
 *       <td>竞技场是<b>干地</b> ⇒ 它宁愿满地图找水 / 躲太阳也不打敌人 ⇒「溺尸不攻击」</td></tr>
 *   <tr><td>海龟 ×4</td><td>它的 MOVE goal 全是「回老家 / 下水 / 产卵 / 游荡」，没有一个是"走向敌人"</td></tr>
 *   <tr><td>熊猫 ×4</td><td>★ 机制不同：它们把攻击 goal 的 {@code canPerformAction()} 闸门关掉（躺平/打滚/坐着吃/躲怪）⇒ 攻击 goal 根本不启动</td></tr>
 * </table>
 *
 * <h2>★ 为什么这条清单必须按「类型过滤 + 摘不掉就跳过」来用</h2>
 * 这些 key 的类型是**异构**的（{@code GoalKey<Creature>} / {@code GoalKey<Drowned>} /
 * {@code GoalKey<Turtle>} / {@code GoalKey<Panda>} …）⇒ 先用
 * {@code key.getEntityClass().isInstance(mob)} 过滤，再转型调用；
 * 不匹配 / API 不支持 ⇒ **跳过**（不是每种生物都有这些 goal）。
 *
 * <h2>★★ 我们卡池的实际情况（摘了也不会误伤）</h2>
 * 我们 36 张召唤卡里**没有海龟、没有熊猫、没有兔子**（见 {@code docs/19}）；
 * 但「火焰伤害 / 摔落伤害」同样会让 {@code PANIC}/{@code FLEE_SUN} 生效，
 * 所以这 19 条对我们**不是空转**（尤其僵尸家族在白天会去躲太阳）。
 *
 * ★ 本类**只摘"分心"的 goal**，攻击 goal 一条都不动。
 * ★ 纯执行层（要 {@code MobGoals}），因此放在 {@code battle} 而不是 {@code policy}
 * （{@code policy/**} 不许碰 Bukkit —— {@code PolicyPurityGuardTest} 钉着）。
 */
public final class DistractionGoals {

    /**
     * 要摘的 19 条（顺序与 IG 的 {@code DISTRACTION_KEYS} 一致，便于两边对照）。
     *
     * <p>★ 全部经 26.2 的 {@code javap com.destroystokyo.paper.entity.ai.VanillaGoal} 核实存在。
     */
    public static final List<GoalKey<?>> KEYS = List.of(
            // ---- 惊恐一族（持续伤害 / 挨打就逃）----
            VanillaGoal.PANIC,
            VanillaGoal.TAMABLE_ANIMAL_PANIC,
            VanillaGoal.FOX_PANIC,
            VanillaGoal.RABBIT_PANIC,
            VanillaGoal.PANDA_PANIC,
            VanillaGoal.TURTLE_PANIC,
            // ---- 溺尸专属：找水 / 上岸 / 上浮（竞技场是干地）----
            VanillaGoal.DROWNED_GO_TO_WATER,
            VanillaGoal.DROWNED_GO_TO_BEACH,
            VanillaGoal.DROWNED_SWIM_UP,
            // ---- 僵尸家族：躲太阳 / 找水 ----
            VanillaGoal.FLEE_SUN,
            VanillaGoal.TRY_FIND_WATER,
            // ---- 海龟：回老家 / 下水 / 游荡 / 产卵 ----
            VanillaGoal.TURTLE_GO_TO_WATER,
            VanillaGoal.TURTLE_GO_HOME,
            VanillaGoal.TURTLE_TRAVEL,
            VanillaGoal.TURTLE_LAY_EGG,
            // ---- 熊猫：躺平 / 打滚 / 坐下 / 躲怪（会把攻击 goal 的闸门关上）----
            VanillaGoal.PANDA_LIE_ON_BACK,
            VanillaGoal.PANDA_ROLL,
            VanillaGoal.PANDA_SIT,
            VanillaGoal.PANDA_AVOID);

    private DistractionGoals() {
    }

    /** 19 条 key 的**名字**（`panic` / `flee_sun` …；日志与测试用，避免打印对象）。 */
    public static List<String> names() {
        List<String> out = new ArrayList<>(KEYS.size());
        for (GoalKey<?> key : KEYS) {
            out.add(nameOf(key));
        }
        return List.copyOf(out);
    }

    /**
     * 把这只生物身上**真的挂着**的分心 goal 全摘掉。
     *
     * <p>★ 返回「真的被摘掉的那些名字」：只有把"摘之前它挂了什么"打进日志，
     * 事后才能证明因果关系（IG 2026-10-13 排查「溺尸不攻击」时缺的正是这条证据）。
     *
     * @param goals {@code Bukkit.getMobGoals()}（调用方保证非 null）
     * @param mob   目标生物
     * @return 被摘掉的 key 名（**保序**；空表 = 它本来就没有这些 goal）
     */
    @SuppressWarnings("unchecked")
    public static List<String> removeFrom(MobGoals goals, Mob mob) {
        List<String> removed = new ArrayList<>();
        if (goals == null || mob == null) {
            return removed;
        }
        for (GoalKey<?> key : KEYS) {
            if (key == null || key.getEntityClass() == null
                    || !key.getEntityClass().isInstance(mob)) {
                continue;   // 这种生物类型不可能有这条 goal（PanicGoal 之于溺尸）
            }
            try {
                GoalKey<Mob> typed = (GoalKey<Mob>) (Object) key;
                if (goals.hasGoal(mob, typed)) {
                    goals.removeGoal(mob, typed);
                    removed.add(nameOf(key));
                }
            } catch (Throwable ignored) {
                // 实际不存在 / API 不支持 —— 跳过（不是每种生物都有这些 goal）
            }
        }
        Collections.sort(removed);
        return removed;
    }

    /** 一个 key 的名字（退化路径：拿不到 namespaced key ⇒ 用 {@code toString()}）。 */
    private static String nameOf(GoalKey<?> key) {
        if (key == null) {
            return "?";
        }
        try {
            if (key.getNamespacedKey() != null) {
                return key.getNamespacedKey().getKey();
            }
        } catch (Throwable ignored) {
            // 落到 toString
        }
        return String.valueOf(key);
    }
}
