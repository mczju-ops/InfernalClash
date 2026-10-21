package io.mczju.infernalclash.leaderboard;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ <b>按 MGC 的同款反射读法读写档案里的战绩字段</b>（纯反射，不碰 Bukkit / MGC 类型）。
 *
 * <h2>★ 为什么是反射，而不是直接 {@code data.realWins}</h2>
 * 因为那两个前提<b>现在都不成立</b>：
 * <ol>
 *   <li>{@code docs/08} §8.3 只是「字段草案」—— 实际的
 *       {@code profile/InfernalPlayerData} 里<b>一个 {@code real*} 字段都没有</b>
 *       （只有 {@code gamesPlayed}）⇒ <b>直接写字段名根本编译不过</b>；</li>
 *   <li>补字段要动 {@code profile/**}，<b>不在本轮允许的改动范围内</b>（缺口 G2）。</li>
 * </ol>
 *
 * <p>⇒ 反射在这里<b>不是"图省事"，而是恰好能把 MGC 那条容易踩的规则搬到离线测试里</b>：
 * 本类<b>逐字复刻</b> {@code PlayerDataLeaderboard.getDoubleValue} 的读法
 * （{@code getClass().getField(name)} → {@code Field.get} → {@code instanceof Number} → 否则 {@code 0.0}，
 * 且三种异常一律吞掉返回 {@code 0.0}）。
 * 于是「字段写成 {@code private} ⇒ 榜恒空」这件事，可以用一个<b>测试夹具类</b>在离线断言
 * （见 {@code LeaderboardFieldsTest}），<b>不需要服务器</b>。
 *
 * <h2>★ 唯一一处"比 MGC 更严"的地方（刻意的）</h2>
 * MGC 读不到就静默 {@code 0.0}。我们也<b>照做</b>（{@link #readDouble}，行为必须一致，
 * 否则「离线测过」与「线上表现」会不一样）——
 * 但<b>额外</b>提供 {@link #unreadableFields}：把「缺哪些字段」<b>说出来</b>，
 * 让装配层可以<b>告警</b>。⇒ <b>读的时候像 MGC，诊断的时候不像 MGC</b>。
 * 这是「榜恒空」唯一的解药：<b>榜还是空的，但至少是响的</b>。
 *
 * <p>★ 本类只依赖 JDK ⇒ <b>离线可单测</b>。
 */
public final class ArchiveFieldAccess {

    private ArchiveFieldAccess() {
    }

    // ==================================================================================
    // 一、读（与 MGC 逐字同款）
    // ==================================================================================

    /**
     * ★ <b>与 MGC {@code getDoubleValue} 同款</b>：读不到（缺字段 / 非 {@code public} /
     * 值不是 {@code Number}）一律返回 {@code 0.0}，<b>不抛、不记日志</b>。
     *
     * <p>⚠️ 这个"静默"是<b>故意保留</b>的：它是 MGC 的真实行为，
     * 换成抛异常会让离线结论与线上表现分叉。要「说出来」请用 {@link #unreadableFields}。
     *
     * @param data      档案对象；{@code null} ⇒ {@code 0.0}（MGC 也是先判 null）
     * @param fieldName 字段名；{@code null} / 空 ⇒ {@code 0.0}（MGC 同样先判）
     */
    public static double readDouble(Object data, String fieldName) {
        if (data == null || fieldName == null || fieldName.isEmpty()) {
            return 0.0d;
        }
        try {
            Field field = findPublicField(data.getClass(), fieldName);
            Object value = field.get(data);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            return 0.0d;
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException e) {
            // ★★ 这里就是「写错一个字 ⇒ 榜恒空且不报错」的位置。别改成抛出。
            return 0.0d;
        }
    }

    /** {@link #readDouble} 的 {@code int} 版本（四个 {@code real*} 字段都是 {@code int}）。 */
    public static int readInt(Object data, String fieldName) {
        return (int) readDouble(data, fieldName);
    }

    /**
     * 把一份档案读成 {@link LeaderboardStats}（四个字段都走 {@link #readDouble}）。
     *
     * <p>★ 字段缺失时读到的就是四个 {@code 0} —— 即 {@link LeaderboardStats#EMPTY}，
     * 也就是「这个玩家暂时不上榜」。**不抛**：一个玩家的坏档案不能毁掉整张榜。
     */
    public static LeaderboardStats readStats(Object data) {
        return new LeaderboardStats(
                readInt(data, LeaderboardFields.REAL_WINS),
                readInt(data, LeaderboardFields.REAL_MATCHES),
                readInt(data, LeaderboardFields.REAL_STREAK),
                readInt(data, LeaderboardFields.REAL_BEST_STREAK));
    }

    // ==================================================================================
    // 二、诊断（MGC 不做的那一半）
    // ==================================================================================

    /**
     * ★ <b>把「MGC 会静默读 0 的字段」逐个列出来</b>（供装配层告警）。
     *
     * <p>判据三条（与 {@link #readDouble} 能读到的条件严格对应）：
     * <ol>
     *   <li>{@code type} 上能 {@code getField(name)} 拿到 —— 即<b>存在且 {@code public}</b>
     *       （{@code getField} 只返回 public 字段）；</li>
     *   <li>它<b>没有被一个同名的非 public 字段遮蔽</b>（否则 MGC 读到的是父类那个，
     *       我们写进去的是子类那个 ⇒ 两边看的是<b>两个字段</b>）；</li>
     *   <li>类型是 {@code int} / {@code long} / {@code double} 这类 {@code Number}。</li>
     * </ol>
     *
     * @param type       档案类（可为 {@code null} ⇒ 全部算"读不到"）
     * @param fieldNames 要检查的字段名（通常是 {@link LeaderboardFields#ARCHIVE_FIELDS}）
     * @return 读不到的字段名（保持 {@code fieldNames} 的顺序；空列表 = 全都读得到）
     */
    public static List<String> unreadableFields(Class<?> type, List<String> fieldNames) {
        List<String> unreadable = new ArrayList<>();
        if (fieldNames == null) {
            return unreadable;
        }
        for (String name : fieldNames) {
            if (type == null) {
                unreadable.add(name);
                continue;
            }
            try {
                Field field = findPublicField(type, name);
                if (!isNumeric(field.getType())) {
                    unreadable.add(name);   // 存在但是字符串之类 ⇒ MGC 读到 0.0
                }
            } catch (NoSuchFieldException | SecurityException e) {
                unreadable.add(name);
            }
        }
        return List.copyOf(unreadable);
    }

    // ==================================================================================
    // 三、写（★ 先验后写：缺字段就拒绝，绝不"以为写成功了"）
    // ==================================================================================

    /**
     * ★ 把一份统计<b>原地</b>写进档案对象的四个字段。
     *
     * <h2>为什么必须"先验后写"</h2>
     * 若在缺字段的情况下逐个 {@code try { set } catch {}} 地写，
     * 结果是「写进去 0 个、返回成功」—— 又一次静默失败。
     * ⇒ 本方法<b>先</b>用 {@link #unreadableFields} 整体验一遍，
     * 只要有<b>任何一个</b>字段读不到就<b>一个字段都不写</b>并返回原因。
     * 这样「战绩有没有落进档案」永远是<b>可判定</b>的。
     *
     * <p>★ 本方法<b>不</b>负责 {@code setModified(true)} / 落盘 —— 那是装配层的事
     * （{@link LeaderboardWiring#recordResult}）。
     *
     * @param data  档案对象
     * @param stats 要写入的统计
     * @return {@link WriteResult}；{@code applied == false} 时 {@code missingFields} 非空或 {@code reason} 说明原因
     */
    public static WriteResult writeStats(Object data, LeaderboardStats stats) {
        if (data == null) {
            return new WriteResult(false, List.copyOf(LeaderboardFields.ARCHIVE_FIELDS), "档案对象为 null");
        }
        if (stats == null) {
            return new WriteResult(false, List.of(), "stats 为 null");
        }
        List<String> unreadable = unreadableFields(data.getClass(), LeaderboardFields.ARCHIVE_FIELDS);
        if (!unreadable.isEmpty()) {
            return new WriteResult(false, unreadable,
                    "档案里这些字段缺失 / 不是 public / 不是数字，拒绝写入（否则会「以为写成功」，"
                            + "而 MGC 反射读到 0.0，榜恒空且不报错）");
        }
        try {
            for (String name : LeaderboardFields.ARCHIVE_FIELDS) {
                setNumber(findPublicField(data.getClass(), name), data, stats.valueOf(name));
            }
            return new WriteResult(true, List.of(), "已写入 " + LeaderboardFields.ARCHIVE_FIELDS);
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException | IllegalArgumentException e) {
            return new WriteResult(false, List.copyOf(LeaderboardFields.ARCHIVE_FIELDS), "反射写入失败：" + e);
        }
    }

    // ==================================================================================
    // 四、结果
    // ==================================================================================

    /**
     * {@link #writeStats} 的结果。
     *
     * @param applied       是否真的写进去了（{@code false} ⇒ <b>一个字段都没动</b>）
     * @param missingFields 读不到的字段名（{@code applied == true} 时为空）
     * @param reason        给日志用的一句话
     */
    public record WriteResult(boolean applied, List<String> missingFields, String reason) {
    }

    // ==================================================================================
    // 五、内部
    // ==================================================================================

    /**
     * ★ 取「<b>MGC 会读到的那一个</b>」字段：{@code Class.getField} 的结果，
     * 外加两条会让人看错字段的检查（非 public、被同名非 public 字段遮蔽）。
     *
     * @throws NoSuchFieldException 不存在 / 不是 public / 被非 public 同名字段遮蔽
     */
    private static Field findPublicField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getField(name);           // ★ MGC 用的就是这一句
        if (!Modifier.isPublic(field.getModifiers())) {
            throw new NoSuchFieldException(name);
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Field declared = declaredOrNull(current, name);
            if (declared != null) {
                if (!Modifier.isPublic(declared.getModifiers())) {
                    // 同名的非 public 字段：MGC 读到的是父类那个 public 的，我们写进去的也是它，
                    // 但**读代码的人**会以为看的是子类那个 ⇒ 直接当成"读不到"，宁可拒绝也不含糊。
                    throw new NoSuchFieldException(name + "（被 " + current.getSimpleName()
                            + " 里同名的非 public 字段遮蔽）");
                }
                return field;
            }
        }
        return field;
    }

    private static Field declaredOrNull(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException | SecurityException e) {
            return null;
        }
    }

    private static boolean isNumeric(Class<?> type) {
        return type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == double.class || type == Double.class
                || type == float.class || type == Float.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class;
    }

    private static void setNumber(Field field, Object target, int value) throws IllegalAccessException {
        Class<?> type = field.getType();
        if (type == int.class || type == Integer.class) {
            field.set(target, value);
        } else if (type == long.class || type == Long.class) {
            field.set(target, (long) value);
        } else if (type == double.class || type == Double.class) {
            field.set(target, (double) value);
        } else if (type == float.class || type == Float.class) {
            field.set(target, (float) value);
        } else if (type == short.class || type == Short.class) {
            field.set(target, (short) value);
        } else if (type == byte.class || type == Byte.class) {
            field.set(target, (byte) value);
        } else {
            throw new IllegalAccessException("字段 " + field.getName() + " 的类型是 "
                    + type.getName() + "，不是数字，不写（MGC 读它也只会得到 0.0）");
        }
    }
}
