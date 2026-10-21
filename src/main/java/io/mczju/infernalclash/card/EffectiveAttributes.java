package io.mczju.infernalclash.card;

/**
 * ★ <b>属性的四级回退合并</b>（纯函数）。
 *
 * <h2>为什么单独成类</h2>
 * 「召唤物最终是多少血、多少攻击」由**四个来源**叠加而成，顺序在 `docs/03` §4.3 与
 * {@link Card.Group} 的 javadoc 里写得很清楚：
 * <pre>
 *   卡牌的**组级** attributes  →  卡牌的**召唤块级** attributes  →  实体组（mob-groups.yml）  →  原版默认
 * </pre>
 * 而它最容易写错的地方是：**必须逐字段回退**，不是整块覆盖。
 * 本类**逐字段往下问**（{@code pick(...)} 一字段一问：组级 → 召唤块级 → 实体组），
 * 因此组里只写 {@code scale} **不会**影响 {@code health} —— {@code health} 会**继续**落到召唤块级
 * （见 {@code EffectiveAttributesTest.groupGivingOnlyScaleDoesNotWipeHealth}：断言取到 {@code 33.0}，**不是** {@code null}）。
 * <p>★ 原写「组里只写了 {@code scale} ⇒ **血量/攻击全部掉回原版默认**（召唤物变成 20 血的纸片）」
 * —— 那是**「组级只要有一个字段就整块采用」这种错误实现**的症状，**不是当前行为**（2026-10-16 更正）。
 * 反例仍然留住，是因为「整块覆盖」这类错误在游戏里表现为「这张卡怎么这么弱」、极难定位。
 *
 * <p>★ {@code null} 表示**不覆写**（不是 0）—— 见 {@link Card.Attributes} 的 javadoc：
 * 用 0 表示"没配"会造出「血量 0 的召唤物」，那是最糟的默认值。
 */
public final class EffectiveAttributes {

    private EffectiveAttributes() {
    }

    /**
     * 逐字段合并：组级 → 召唤块级 → 实体组；三者都为 {@code null} 的字段保持 {@code null}
     * （= 交给原版默认）。
     *
     * @param groupLevel    卡牌里该组的 attributes（可为 {@code null}）
     * @param summonLevel   卡牌里召唤块的 attributes（可为 {@code null}）
     * @param mobGroupLevel 实体组（mob-groups.yml）的 attributes（可为 {@code null}）
     */
    public static Card.Attributes merge(Card.Attributes groupLevel, Card.Attributes summonLevel,
                                       Card.Attributes mobGroupLevel) {
        return new Card.Attributes(
                pick(groupLevel, summonLevel, mobGroupLevel, Field.HEALTH),
                pick(groupLevel, summonLevel, mobGroupLevel, Field.ATTACK_DAMAGE),
                pick(groupLevel, summonLevel, mobGroupLevel, Field.ARMOR),
                pick(groupLevel, summonLevel, mobGroupLevel, Field.MOVEMENT_SPEED),
                pick(groupLevel, summonLevel, mobGroupLevel, Field.FOLLOW_RANGE),
                pick(groupLevel, summonLevel, mobGroupLevel, Field.SCALE),
                // ★★ 2026-10-17 第十二轮（用户裁定 §A 第 2 条「所有击退抗性都要做」）——
                //   ★ 每加一个字段都必须在这里 + 下面的 enum Field 各加一格：
                //     那个 enum 的注释写着「让『漏了某个字段』在编译期就暴露」。
                pick(groupLevel, summonLevel, mobGroupLevel, Field.KNOCKBACK_RESISTANCE),
                // ★★ 2026-10-18（用户第 3 条「无法控制岩浆怪的尺寸」）：方块怪的尺寸档位。
                //   ★ 它也走**同一条四级回退**（组级 → 召唤块级 → 实体组），
                //     所以「烈焰熔炉的召唤效果里写 size: 1」与「卡里的 summon.attributes.size: 4」
                //     各有各的落点，不会互相串。
                pickInt(groupLevel, summonLevel, mobGroupLevel, Card.Attributes::size));
    }

    /**
     * 逐字段取第一个非 {@code null} 的值（{@code int} 那一档）。
     *
     * <p>★ 单列一个方法而不是把 {@code size} 也塞进 {@link Field}：那个枚举的
     * {@code of(...)} 返回 {@code Double}，{@code size} 是整数
     * ⇒ 硬塞进去要么改成 {@code Number}（丢掉类型信息），要么在调用点拆箱（漏 null）。
     * 两个泛型各走各的通道，反而没有第二种类型转换的地方。
     */
    private static Integer pickInt(Card.Attributes group, Card.Attributes summon,
                                   Card.Attributes mobGroup,
                                   java.util.function.Function<Card.Attributes, Integer> getter) {
        for (Card.Attributes candidate : new Card.Attributes[] {group, summon, mobGroup}) {
            if (candidate == null) {
                continue;
            }
            Integer value = getter.apply(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 逐字段取第一个非 null 的值。 */
    private static Double pick(Card.Attributes group, Card.Attributes summon, Card.Attributes mobGroup,
                               Field field) {
        Double value = field.of(group);
        if (value == null) {
            value = field.of(summon);
        }
        if (value == null) {
            value = field.of(mobGroup);
        }
        return value;
    }

    /** 字段选择器（避免六段重复的 if 链，也让「漏了某个字段」在编译期就暴露）。 */
    private enum Field {
        HEALTH {
            @Override
            Double of(Card.Attributes a) {
                return a == null ? null : a.health();
            }
        },
        ATTACK_DAMAGE {
            @Override
            Double of(Card.Attributes a) {
                return a == null ? null : a.attackDamage();
            }
        },
        ARMOR {
            @Override
            Double of(Card.Attributes a) {
                return a == null ? null : a.armor();
            }
        },
        MOVEMENT_SPEED {
            @Override
            Double of(Card.Attributes a) {
                return a == null ? null : a.movementSpeed();
            }
        },
        FOLLOW_RANGE {
            @Override
            Double of(Card.Attributes a) {
                return a == null ? null : a.followRange();
            }
        },
        SCALE {
            @Override
            Double of(Card.Attributes a) {
                return a == null ? null : a.scale();
            }
        },
        /** ★★ 2026-10-17 第十二轮新增（用户裁定 §A 第 2 条）。 */
        KNOCKBACK_RESISTANCE {
            @Override
            Double of(Card.Attributes a) {
                return a == null ? null : a.knockbackResistance();
            }
        };

        abstract Double of(Card.Attributes attributes);
    }
}
