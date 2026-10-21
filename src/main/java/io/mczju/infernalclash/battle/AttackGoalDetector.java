package io.mczju.infernalclash.battle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ★ 判「一个 goal 是不是攻击类」—— **词根按 {@code _} 切词后全等比较，绝不用 {@code contains}**。
 *
 * <h2>为什么必须自己判、不能用手写白名单（狐狸事故）</h2>
 * Paper 的 goal key 是**从 NMS 类名推导**出来的（{@code docs/07} §5.4 实测）：
 * 狐狸的近战 goal 是 {@code Fox$FoxMeleeAttackGoal} ⇒ key = {@code fox_melee_attack}，
 * {@code != melee_attack}。用白名单会把狐狸判成「没有自带攻击 goal」，
 * 于是给它补了 priority 0 的 drive goal —— 而**同一时刻只能跑一个 MOVE goal**
 * ⇒ 狐狸自己的近战 goal 被永久顶掉 ⇒ 「走到敌人面前却不咬人」。
 * ⇒ 判「有没有自带攻击 goal」**必须扫该实体实际挂着的 goal**（{@code docs/04} §16.8.3）。
 *
 * <h2>★★ 为什么是「全等」而不是 {@code contains}</h2>
 * {@code contains("ram")} 会把 {@code program} 误命中 ⇒ 「这个生物自带攻击 goal」
 * 判成 true ⇒ 该补的 drive goal 不补 ⇒ 单位站着不动。
 * InfernalGuess 的 {@code AttackGoalDetectionTest} 专门钉过这一条，
 * 本类照抄其词根表与判据（来源：{@code docs/07} §5.4 的
 * {@code MCZJUGuess:BattleService.ATTACK_KEY_TOKENS}）。
 *
 * <h2>★ 切词：{@code _} / {@code $} / 驼峰 三处都要切</h2>
 * 拿到的路径可能是三种形状，必须都能切出 {@code melee} 这样的**完整词**：
 * <ul>
 *   <li>{@code fox_melee_attack} —— Bukkit 的 goal key（下划线）；</li>
 *   <li>{@code Fox$FoxMeleeAttackGoal} —— NMS 内部类全名（{@code $} + 驼峰）；</li>
 *   <li>{@code MeleeAttackGoal} —— 驼峰类名。</li>
 * </ul>
 * ★ **不可逆的静默失败点**：切词后必须先归一化成小写再比对 ——
 * 否则 {@code FoxMeleeAttackGoal} 切出的 {@code Attack} 与词根表里的 {@code attack} 不相等，
 * 判据会**静默返回 false**（不报错、只是"这个生物没有攻击 goal"）。
 */
public final class AttackGoalDetector {

    private AttackGoalDetector() {
    }

    /** 攻击类词根（来源：{@code docs/07} §5.4 的 {@code MCZJUGuess:BattleService.ATTACK_KEY_TOKENS}，照抄）。 */
    public static final Set<String> ATTACK_KEY_TOKENS = Set.of(
            "attack", "melee", "ram", "swell", "charge", "spit", "shoot",
            "spear", "trident", "pounce", "bite", "sting");

    /**
     * 词边界：下划线、{@code $}、以及「小写/数字 后跟 大写」处都要切。
     *
     * <p>★ 这里**只切词、不判词** —— 判词一律走词根表的**全等**比较（见类注释）。
     */
    private static final Pattern WORD_BOUNDARY = Pattern.compile("[_$]|(?<=[a-z0-9])(?=[A-Z])");

    /**
     * 该 goal key / NMS 类名路径是否为攻击类。null / 空串 / 只有分隔符 ⇒ false。
     *
     * @param path goal key（例：{@code fox_melee_attack}）或 NMS 类名路径
     *             （例：{@code net.minecraft.world.entity.animal.Fox$FoxMeleeAttackGoal}）
     */
    public static boolean isAttackGoalName(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String token : tokenize(path)) {
            // ★ 全等比较；token 已归一化成小写（见 tokenize 的注释）
            if (ATTACK_KEY_TOKENS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 一组 goal key 里**有没有**攻击类（用于 {@code AUTO} 模式：有 ⇒ 绝不补我们的 drive goal）。 */
    public static boolean hasAttackGoal(Collection<String> goalKeys) {
        if (goalKeys == null || goalKeys.isEmpty()) {
            return false;
        }
        for (String key : goalKeys) {
            if (isAttackGoalName(key)) {
                return true;
            }
        }
        return false;
    }

    /** 从一组 goal key 里挑出攻击类的那些（**保序**，null 元素跳过；排障日志用）。 */
    public static List<String> attackGoals(Collection<String> goalKeys) {
        if (goalKeys == null || goalKeys.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String key : goalKeys) {
            if (isAttackGoalName(key)) {
                result.add(key);
            }
        }
        // ★ 不可变副本：调用方可能把它直接塞进日志/返回值，别让人改坏
        return List.copyOf(result);
    }

    /**
     * 把路径切成**小写**词元：按 {@code _} / {@code $} / 驼峰边界切，丢掉空段。
     *
     * <p>切完立刻 {@code toLowerCase(Locale.ROOT)}：词根表全是小写，
     * 且**必须**用 {@code Locale.ROOT}（土耳其语的 {@code I} 会变成无点的 {@code ı}，
     * 让 {@code Bite} 这类词根在特定 JVM 语言环境下匹配失败 —— 这是纯粹的静默失效）。
     */
    private static List<String> tokenize(String path) {
        String[] parts = WORD_BOUNDARY.split(path);
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }
}
