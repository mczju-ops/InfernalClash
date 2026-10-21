package io.mczju.infernalclash.spell;

import io.mczju.infernalclash.card.Spell;

import java.util.Set;
import java.util.TreeSet;

/**
 * ★ <b>纯参数读取</b>：把 {@link Spell.Effect} 的 {@code Map} 参数取出来并**校验**
 * （缺键 / 类型不符 ⇒ 抛 {@link IllegalArgumentException}，**消息里必须写出键名**）。
 *
 * <h2>为什么要单独一层，而不是到处 {@code (Double) params.get(...)}</h2>
 * 本项目最想避免的失败方式不是"报错"，而是「<b>静默什么都不做</b>」：
 * <ul>
 *   <li>{@code (Double) params.get("amount")} 在键缺失时给 {@code null} ⇒ 下游 NPE
 *       或悄悄按 0 处理（伤害 0 = 法术"看起来没生效"）；</li>
 *   <li>YAML 里把 {@code duration-ticks} 写成 {@code "2s"} ⇒ 强转 {@code ClassCastException}，
 *       堆栈里只有 {@code String cannot be cast to Double}，**看不出是哪个键**；</li>
 *   <li>把 {@code taunt-level} 这类"写了却没实现"的键留在卡里 ⇒ 谁也不知道它没生效。</li>
 * </ul>
 * 所以这里提供的每一个方法，**要么给出值，要么给出一个能直接照着改 YAML 的报错**。
 *
 * <h2>本类不碰 Bukkit</h2>
 * 只依赖 {@code java.util} 与 {@link Spell.Effect}（一个纯数据 record）⇒ 必然可离线单测。
 *
 * <h2>边界</h2>
 * 这里**只做「取得出来 + 类型对」**，不做玩法校验（例如「伤害必须 > 0」）；
 * 玩法边界由调用方在拿到值之后判断并给出更具体的报错 —— 那才写得清"允许范围是多少"。
 */
public final class EffectParams {

    private EffectParams() {
    }

    // ------------------------------------------------------------------ 必需

    /**
     * 读**必需**的数字（YAML 的整数与浮点都接受）。
     *
     * @throws IllegalArgumentException 效果为 {@code null}、键缺失、或值不是数字
     *                                  （消息含键名与当前值）
     */
    public static double requireNumber(Spell.Effect effect, String key) {
        Object value = raw(effect, key);
        if (value == null) {
            throw new IllegalArgumentException(missing(effect, key, "数字", "例如 " + key + ": 3"));
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException(wrongType(effect, key, "数字", value));
    }

    /**
     * 读**必需**的整数。
     *
     * <p>★ 与 {@link #requireNumber} 的区别：这里**拒绝小数**（{@code 2.5} 不是整数）。
     * YAML 把 {@code 2.5} 写成 {@code 2.5} 通常是笔误，静默取整会让「2.5 秒」变成「2 秒」
     * 而没人发现。
     */
    public static int requireInt(Spell.Effect effect, String key) {
        double value = requireNumber(effect, key);
        if (value != Math.rint(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("效果 " + typeName(effect) + " 的参数 " + key + " 必须是整数，"
                    + "当前值 " + value + "（要小数请改用浮点语义的键，或在卡里取整）");
        }
        return (int) value;
    }

    /**
     * 读**必需**的非空字符串。
     *
     * @throws IllegalArgumentException 效果为 {@code null}、键缺失、值不是字符串、或字符串为空
     */
    public static String requireText(Spell.Effect effect, String key) {
        Object value = raw(effect, key);
        if (value == null) {
            throw new IllegalArgumentException(missing(effect, key, "文本", "例如 " + key + ": SLOWNESS"));
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(wrongType(effect, key, "文本", value));
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("效果 " + typeName(effect) + " 的参数 " + key + " 不能是空字符串"
                    + "（留空等于没写，但加载期不会拦，运行期更不该猜）");
        }
        return text;
    }

    // ------------------------------------------------------------------ 可选

    /** 读**可选**的数字：没写返回 {@code fallback}（写了但类型不符仍然抛）。 */
    public static double optionalNumber(Spell.Effect effect, String key, double fallback) {
        if (!isSet(effect, key)) {
            return fallback;
        }
        return requireNumber(effect, key);
    }

    /** 读**可选**的整数：没写返回 {@code fallback}（写了但类型不符/是小数仍然抛）。 */
    public static int optionalInt(Spell.Effect effect, String key, int fallback) {
        if (!isSet(effect, key)) {
            return fallback;
        }
        return requireInt(effect, key);
    }

    /** 读**可选**的字符串：没写（或写了空值）返回 {@code fallback}（写了但类型不符仍然抛）。 */
    public static String optionalText(Spell.Effect effect, String key, String fallback) {
        if (!isSet(effect, key)) {
            return fallback;
        }
        Object value = effect.params().get(key);
        if (value == null) {
            // YAML 里写了 `damage-type:`（留空）会给出 **null 值** —— 那是"没指定"，
            // 不是"类型不符"（CardCatalog 也特意为这个留了注释）。这里按缺省处理。
            return fallback;
        }
        return requireText(effect, key);
    }

    /** 读**可选**的布尔：没写返回 {@code fallback}（写了但类型不符仍然抛）。 */
    public static boolean optionalFlag(Spell.Effect effect, String key, boolean fallback) {
        Object value = raw(effect, key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(wrongType(effect, key, "布尔", value)
                + "（应写 true / false）");
    }

    // ------------------------------------------------------------------ 未知键

    /**
     * 断言该效果**没有**这些未知键（防止 {@code taunt-level} 那类「写了却没生效」）。
     *
     * <p>★ 为什么这条很重要：卡牌 YAML 里写错的键**不会让加载失败**（{@code CardCatalog}
     * 只校验必需键在不在），于是它会一路静默到运行期，表现为「这张卡的效果怪怪的」。
     * 每个效果类型在应用前调一次本方法，就能把这类错误变成一条<b>指名道姓</b>的报错。
     *
     * <p>未知键会**排序后列出**（{@link TreeSet}）—— 报错信息稳定，便于比对与写测试。
     *
     * @param allowed 该效果类型**允许**的全部键
     * @throws IllegalArgumentException 出现任何不在 {@code allowed} 里的键
     */
    public static void rejectUnknown(Spell.Effect effect, Set<String> allowed) {
        if (effect == null || effect.params() == null || effect.params().isEmpty()) {
            return;
        }
        Set<String> unknown = new TreeSet<>();
        for (String key : effect.params().keySet()) {
            if (key == null) {
                unknown.add("<null>");
                continue;
            }
            // `type` 是**列表项自身的键**（写 `- type: DAMAGE` 时它也在这一层），
            // 它不是效果的参数，任何效果都不该因为它报错。
            if ("type".equals(key)) {
                continue;
            }
            if (allowed == null || !allowed.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            Set<String> sorted = new TreeSet<>(allowed == null ? Set.of() : allowed);
            throw new IllegalArgumentException("效果 " + typeName(effect) + " 出现了未知参数 "
                    + unknown + "；该效果只认 " + sorted
                    + " —— 请核对卡牌 YAML（写了却没生效的键会一路静默到运行期）");
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 该键是否**真的写了**（用来区分「没写」与「写了空值」）。 */
    private static boolean isSet(Spell.Effect effect, String key) {
        return effect != null && effect.params() != null && effect.params().containsKey(key);
    }

    /** 取原始值；键没写返回 {@code null}。 */
    private static Object raw(Spell.Effect effect, String key) {
        if (key == null) {
            throw new IllegalArgumentException("EffectParams：键名不能为 null（调用方的编码错误）");
        }
        if (effect == null) {
            return null;
        }
        return effect.params() == null ? null : effect.params().get(key);
    }

    private static String typeName(Spell.Effect effect) {
        return effect == null || effect.type() == null ? "<空效果>" : effect.type().name();
    }

    private static String missing(Spell.Effect effect, String key, String kind, String example) {
        if (effect == null) {
            return "效果为 null，无法读取参数 " + key + "（期望一个" + kind + "，"
                    + example + "）";
        }
        return "效果 " + typeName(effect) + " 缺少必需的" + kind + "参数 " + key + "（" + example + "）";
    }

    private static String wrongType(Spell.Effect effect, String key, String kind, Object value) {
        return "效果 " + typeName(effect) + " 的参数 " + key + " 类型不是" + kind
                + "（当前值：" + value + "，" + value.getClass().getSimpleName() + "）";
    }
}
