package io.mczju.infernalclash.card;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ★★ <b>给召唤物穿装备</b> —— 2026-10-17 第十轮新增，缺陷 P1-b
 * （用户原话「<b>铁甲战士没有获得盔甲和武器</b>」，{@code docs/24} §7.1）。
 *
 * <h2>文档依据（逐字）</h2>
 * <ul>
 *   <li>{@code docs/19-卡牌总表.md} 第 56 行：「铁甲战士 | 僵尸 | 3 | 10s (200) |
 *       <b>铁套铁剑</b>，40 血，尺寸 ×1.2，80% 击退抗性 | A |
 *       <b>盔甲不提供防御 → 清 {@code ATTRIBUTE_MODIFIERS}</b>」；</li>
 *   <li>{@code docs/卡牌策划案.txt} 第 10 行（全局口径）：「此盔甲<b>以及下文所有盔甲和马凯</b>
 *       均<b>不提供防御和盔甲韧性数值</b>」。</li>
 * </ul>
 * ⇒ 两条规则合起来就是本类做的两件事：<b>穿上那几件</b> ＋ <b>把盔甲件的属性修饰符清掉</b>。
 * ★ 第二条是**全局规则**（不是逐卡开关）：所以本类对所有走 {@code summon.equipment} 的卡
 * 一视同仁地清盔甲修饰符 —— 将来加别的带甲卡时不需要记得再写一次
 * （本项目「第二处白名单」教训：同一条规则散在两处必然分叉）。
 *
 * <h2>★ 只清**盔甲**，不动武器</h2>
 * 策划案那句只点名「盔甲和马凯」⇒ 主手武器（铁剑）的 {@code ATTRIBUTE_MODIFIERS} **保留**。
 * （另注：原版生物的伤害来自它自己的 {@code ATTACK_DAMAGE} 属性，不来自手里的剑 ——
 * 所以「拿不拿剑」对僵尸的伤害没有数值影响，剑在这里是**外观与识别度**。）
 *
 * <h2>失败方向（铁律 10 的失败方向那一问）</h2>
 * 材料名解析不出来 / 该实体没有装备栏 / 设置某一件抛异常
 * ⇒ <b>只对那一件告警并跳过</b>，绝不让整只召唤物消失，也绝不"换一件差不多的"。
 */
public final class EquipmentApplier {

    /** 一次装备的结果（可对账）。 */
    public record Equipped(int pieces, List<String> warnings) {

        public boolean complete() {
            return warnings.isEmpty();
        }
    }

    private EquipmentApplier() {
    }

    /**
     * ★ <b>装备映射（纯数据）</b>：装备块 → 「槽位 → 材料名」（保序：头 / 胸 / 腿 / 脚 / 主手）。
     *
     * <p>单独抽出来的理由：它是**唯一**把卡牌数据与 Bukkit 槽位对上的地方，
     * 而「头盔穿到腿上」这种错在游戏里很难一眼看出 ⇒ 必须能在离线单测里逐格断言。
     */
    public static Map<EquipmentSlot, String> slots(Card.Equipment equipment) {
        Map<EquipmentSlot, String> map = new LinkedHashMap<>();
        if (equipment == null) {
            return Collections.unmodifiableMap(map);
        }
        put(map, EquipmentSlot.HEAD, equipment.helmet());
        put(map, EquipmentSlot.CHEST, equipment.chestplate());
        put(map, EquipmentSlot.LEGS, equipment.leggings());
        put(map, EquipmentSlot.FEET, equipment.boots());
        put(map, EquipmentSlot.HAND, equipment.mainHand());
        // ★★ 1.1.0（美愿轰炸机）：「它会戴着**浅绿色的挽具**登场」——
        //   快乐恶魂的挽具走 {@code EquipmentSlot.BODY}（26.2 `javap` 实测该枚举存在；
        //   挽具材料 = {@code LIME_HARNESS}）。
        put(map, EquipmentSlot.BODY, equipment.body());
        return Collections.unmodifiableMap(map);
    }

    private static void put(Map<EquipmentSlot, String> map, EquipmentSlot slot, String material) {
        if (material != null && !material.isBlank()) {
            map.put(slot, material);
        }
    }

    /**
     * 真的把装备穿上去。
     *
     * @return 穿上了几件 + 每一件的告警（**可对账**：日志里写「装备 N 件」）
     */
    public static Equipped equip(LivingEntity entity, Card.Equipment equipment, Logger logger) {
        Map<EquipmentSlot, String> slots = slots(equipment);
        if (entity == null || slots.isEmpty()) {
            return new Equipped(0, List.of());
        }
        // ★★★ 2026-10-18：**按组限定**（`equipment.groups`，用户裁定「只给僵尸加，马铠不加」）。
        //   前储君的两样装备分属僵尸与僵尸马，而装备块是逐卡的 ⇒ 需要"只给某些实体类型穿"。
        //   ★ 空表（49/51 张）= 不限组 ⇒ 行为与加这个键之前完全一致。
        if (!equipment.appliesTo(entity.getType().name())) {
            return new Equipped(0, List.of("装备只给 " + equipment.groups()
                    + " 穿；这次是 " + entity.getType() + "，跳过（这是配置意图，不是错误）"));
        }
        if (entity.getEquipment() == null) {
            String note = "该实体没有装备栏（" + entity.getType() + "），装备块被忽略";
            warn(logger, "[IC] " + note);
            return new Equipped(0, List.of(note));
        }
        int pieces = 0;
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<EquipmentSlot, String> entry : slots.entrySet()) {
            EquipmentSlot slot = entry.getKey();
            Material material = Material.matchMaterial(entry.getValue());
            if (material == null) {
                String note = "材料 " + entry.getValue() + " 不是合法的原版材料名（" + slot + "）";
                warnings.add(note);
                warn(logger, "[IC] 装备跳过：" + note
                        + " —— 已登记（docs/27 D6），请把这一行连同卡 id 反馈");
                continue;
            }
            try {
                ItemStack stack = armorWithoutDefense(slot, material);
                // ★★ 2026-10-17 第十二轮：逐件附魔（docs/19 的「力量 II 弓」「力量 V 弩」）。
                //   ★ 附魔在「清属性修饰符」之后加 —— 两件事互不影响，但顺序写清免得后人以为有依赖。
                int enchanted = applyEnchantments(stack, equipment.enchantmentsOf(slotName(slot)),
                        slot, logger, warnings);
                // ★★ 2026-10-17 批次 3 后半：逐件盔甲纹饰（docs/19 的「钻石海岸纹样」那类）。
                //   ★ 放在附魔之后：纹饰要写进 **ArmorMeta**，而附魔写的是 ItemMeta 的通用部分
                //     —— 两者都会 setItemMeta，顺序反了不会出错但读起来会以为有依赖。
                boolean trimmed = applyTrim(stack, equipment.trimOf(slotName(slot)),
                        slot, logger, warnings);
                // ★★ 2026-10-18：**皮革染色**（用户「皮革盔甲没有染色」）。
                //   出处 = docs/19 的「蓝皮革 / 白皮革 / 黑皮革」。
                //   ★ 放在纹饰之后、setItem 之前：两者都写 ArmorMeta（trim 是 ArmorTrim、
                //     颜色是 LeatherArmorMeta），各写各的字段、互不覆盖。
                boolean dyed = applyColor(stack, equipment.color(), slot, logger);
                entity.getEquipment().setItem(slot, stack);
                pieces++;
                if (enchanted > 0) {
                    logger.info("[IC] 已给 " + slot + " 的 " + material + " 附上 " + enchanted
                            + " 条附魔｜" + equipment.enchantmentsOf(slotName(slot)));
                }
                if (trimmed) {
                    logger.info("[IC] 已给 " + slot + " 的 " + material + " 上纹饰｜"
                            + equipment.trimOf(slotName(slot)));
                }
                if (dyed) {
                    logger.info("[IC] 已给 " + slot + " 的 " + material + " 染成 "
                            + equipment.color() + "（皮革染色）");
                }
            } catch (Throwable t) {
                String note = "设置 " + slot + " 的 " + material + " 失败：" + t;
                warnings.add(note);
                warn(logger, "[IC] 装备跳过：" + note);
            }
        }
        if (logger != null && pieces > 0) {
            boolean anyArmor = slots.keySet().stream().anyMatch(EquipmentApplier::isArmor);
            logger.info("[IC] 已装备 " + pieces + " 件"
                    + (anyArmor ? "（含盔甲：已按 docs/19 #1 的坑清掉 ATTRIBUTE_MODIFIERS"
                            + "，不提供防御与盔甲韧性，策划案第 10 行）" : "")
                    + "｜清单 " + describe(slots));
        }
        return new Equipped(pieces, List.copyOf(warnings));
    }

    /** 盔甲件（头 / 胸 / 腿 / 脚）—— 只有它们要清属性修饰符。 */
    public static boolean isArmor(EquipmentSlot slot) {
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }
    /**
     * ★ <b>造一件「不提供防御」的盔甲</b>：清掉该槽位的属性修饰符。
     *
     * <p>出处：{@code docs/19} 第 56 行的坑那一栏「盔甲不提供防御 → 清 {@code ATTRIBUTE_MODIFIERS}」
     * ＋ 策划案第 10 行「所有盔甲和马凯均不提供防御和盔甲韧性数值」。
     * 1.21+ 的盔甲防御来自物品的 {@code ATTRIBUTE_MODIFIERS} 组件
     * （{@code Attribute.ARMOR} / {@code ARMOR_TOUGHNESS}）⇒ 按槽位移除即可
     * （{@code ItemMeta#removeAttributeModifier(EquipmentSlot)}，`javap` 实测存在）。
     */
    public static ItemStack armorWithoutDefense(EquipmentSlot slot, Material material) {
        ItemStack stack = new ItemStack(material);
        if (!isArmor(slot)) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.removeAttributeModifier(slot);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static EquipmentSlot slotOf(Map<EquipmentSlot, String> slots, EquipmentSlot slot) {
        return slots.containsKey(slot) ? slot : null;
    }

    /** 一行清单（日志用）。 */
    public static String describe(Map<EquipmentSlot, String> slots) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<EquipmentSlot, String> entry : slots.entrySet()) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return builder.length() == 0 ? "（无）" : builder.toString();
    }

    /**
     * ★ 装备槽位 → 配置里那个键名（★ **只有这一处映射** —— 解析与写实体共用它）。
     *
     * <p>为什么必须有它：{@code Card.Equipment#enchantments} 的键是**配置名**
     * （{@code helmet / main-hand}），而 {@link EquipmentSlot} 是 Bukkit 的枚举
     * （{@code HEAD / HAND}）。两套名字之间的换算如果散在多处，迟早出现
     * 「头盔的附魔加到了主手」这种极难查的错。
     */
    public static String slotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "helmet";
            case CHEST -> "chestplate";
            case LEGS -> "leggings";
            case FEET -> "boots";
            case HAND -> "main-hand";
            default -> slot.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /**
     * ★★ <b>给一件物品附魔</b>（2026-10-17 第十二轮新增）。
     *
     * <h2>为什么用「按名查注册表」+ 失败只告警</h2>
     * {@code docs/19} 的附魔名是中文（「力量」），配置里存的是原版裸键（{@code power}）；
     * 而 {@code Enchantment} 的解析要服务端注册表 ⇒ 出厂卡必须能**离线**加载
     * （{@code ShippedCardsTest} 的硬要求）⇒ 名字不存在时只能在**召唤那一刻**告警并跳过那一条，
     * 绝不因此让整只召唤物消失，也绝不"换一个差不多的附魔"。
     *
     * @return 真的加上去几条
     */
    @SuppressWarnings("deprecation")
    private static int applyEnchantments(ItemStack stack, Map<String, Integer> enchantments,
                                        EquipmentSlot slot, Logger logger,
                                        List<String> warnings) {
        if (enchantments == null || enchantments.isEmpty()) {
            return 0;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return 0;
        }
        int applied = 0;
        for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = resolveEnchantment(entry.getKey());
            if (enchantment == null) {
                String note = "附魔 " + entry.getKey() + " 解析不出来（" + slot
                        + "），跳过这一条，其余照常";
                warnings.add(note);
                warn(logger, "[IC] 附魔跳过：" + note
                        + " —— Enchantment 注册表里没有这个键；请把这一行连同卡 id 反馈");
                continue;
            }
            meta.addEnchant(enchantment, entry.getValue(), true);
            applied++;
        }
        if (applied > 0) {
            stack.setItemMeta(meta);
        }
        return applied;
    }

    /**
     * ★★ <b>给一件盔甲上纹饰</b>（2026-10-17 批次 3 后半新增，{@code docs/24} §10.1 第 4 条
     * 用户裁定「<b>做</b>（盔甲纹饰）」）。
     *
     * <h2>文档依据（逐字）</h2>
     * {@code docs/19} 的装备列里那 5 处「<b>X 纹样</b>」（全部来自 {@code docs/卡牌策划案.txt}
     * L48 / L81 / L107 / L110 / L118）。拆分口径（「材料 + 图案」两组取值）与对照表写在
     * {@code CardCatalog#parseTrims} 的 javadoc 里 —— <b>只有那一处解释</b>，
     * 本方法只负责把两个裸键换成 Bukkit 对象。
     *
     * <h2>★★ 为什么按「注册表键」查，而不是用 {@code TrimMaterial.GOLD} 这类静态常量</h2>
     * `javap` 实测（{@code paper-api-26.2.build.133-stable}）：
     * {@code TrimMaterial} / {@code TrimPattern} 都是 <b>registry-backed 接口</b>，
     * 那些 {@code public static final} 字段是**由服务端注册表填充**的。
     * 而出厂卡必须在**离线**环境加载（{@code ShippedCardsTest} 的硬要求）
     * ⇒ 本方法只在**召唤那一刻**（服务端已就绪）走 {@code Registry.TRIM_*}
     * 查一次，与 {@link #resolveEnchantment} 同一口径。
     *
     * <h2>失败方向</h2>
     * 没写这一槽位的纹饰 / 不是盔甲 / 材料或图案解析不出来 / 该件不是 {@code ArmorMeta}
     * ⇒ <b>只对那一件告警并跳过纹饰</b>，装备照常穿上（纹饰是**纯外观**，绝不影响数值，
     * 更不该让整只召唤物消失）。
     *
     * @return 真的上了纹饰吗
     */
    /**
     * ★★ <b>给皮革件染色</b>（2026-10-18 新增，用户：「皮革盔甲没有染色」）。
     *
     * <h2>只对 {@code LeatherArmorMeta} 生效</h2>
     * 皮革四件（{@code LEATHER_HELMET/CHESTPLATE/LEGGINGS/BOOTS}）的 meta 是
     * {@code LeatherArmorMeta}，它有 {@code setColor(Color)}；
     * 而铁/钻石/金件**没有颜色分量**。
     * <p>★ 所以"铁套 + {@code color: BLUE}"是**合法配置但无效果** —— 这种情况
     * <b>不告警</b>：它不是错误（作者可能给一张卡同时写了铁套与皮革件，只想染皮革那几个）。
     *
     * <p>★ 失败只告警（观感问题绝不该让召唤物消失），与附魔/纹饰同一口径。
     *
     * @return 真的染上了吗（决定要不要打那行日志）
     */
    private static boolean applyColor(ItemStack stack, String color, EquipmentSlot slot,
                                      Logger logger) {
        if (color == null || color.isBlank()) {
            return false;
        }
        ItemMeta raw = stack.getItemMeta();
        if (!(raw instanceof org.bukkit.inventory.meta.LeatherArmorMeta leather)) {
            return false;   // 不是皮革件 ⇒ 没东西可染（✅ 不是错误，见 javadoc）
        }
        try {
            org.bukkit.DyeColor dye = org.bukkit.DyeColor.valueOf(color.toUpperCase(
                    java.util.Locale.ROOT));
            leather.setColor(dye.getColor());
            stack.setItemMeta(leather);
            return true;
        } catch (Throwable t) {
            warn(logger, "[IC] 染色跳过（" + slot + "）：" + t);
            return false;
        }
    }

    /**
     * 给盔甲件上**纹饰**（{@code docs/19} 的「钻石海岸纹样」那类）。
     *
     * @param trim 键 = {@code material} / {@code pattern}；空/null ⇒ 什么都不做
     */
    private static boolean applyTrim(ItemStack stack, Map<String, String> trim, EquipmentSlot slot,
                                     Logger logger, List<String> warnings) {
        if (trim == null || trim.isEmpty()) {
            return false;
        }
        if (!isArmor(slot)) {
            // 装载期已经拒绝主手写 trim；这里是第二道（手工构造的 Equipment 会绕过装载期）
            warn(logger, "[IC] 纹饰跳过：只有盔甲能上纹饰（当前槽位 " + slot + "）");
            return false;
        }
        org.bukkit.inventory.meta.trim.TrimMaterial material =
                resolveTrimMaterial(trim.get("material"));
        org.bukkit.inventory.meta.trim.TrimPattern pattern =
                resolveTrimPattern(trim.get("pattern"));
        if (material == null || pattern == null) {
            String which = material == null ? "材料 " + trim.get("material") + "" : "";
            if (pattern == null) {
                which += (which.isEmpty() ? "" : " 与 ") + "图案 " + trim.get("pattern") + "";
            }
            String note = "纹饰的 " + which + " 解析不出来（" + slot + "），跳过纹饰，装备照常穿上";
            warnings.add(note);
            warn(logger, "[IC] " + note + " —— 请把这一行连同卡 id 反馈");
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.ArmorMeta armorMeta)) {
            String note = "这一件不是 ArmorMeta（" + slot + " / " + stack.getType()
                    + "），上不了纹饰";
            warnings.add(note);
            warn(logger, "[IC] " + note);
            return false;
        }
        try {
            armorMeta.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(material, pattern));
            stack.setItemMeta(armorMeta);
            return true;
        } catch (Throwable t) {
            String note = "上纹饰失败（" + slot + "）：" + t;
            warnings.add(note);
            warn(logger, "[IC] " + note);
            return false;
        }
    }

    /** trim 材料裸键 → {@link org.bukkit.inventory.meta.trim.TrimMaterial}（★ 只有这一处解析）。 */
    private static org.bukkit.inventory.meta.trim.TrimMaterial resolveTrimMaterial(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return org.bukkit.Registry.TRIM_MATERIAL.get(
                    org.bukkit.NamespacedKey.minecraft(key.trim().toLowerCase(Locale.ROOT)));
        } catch (Throwable t) {
            return null;   // 注册表不可用（离线）⇒ 当作解析不出来（只告警，不炸）
        }
    }

    /** trim 图案裸键 → {@link org.bukkit.inventory.meta.trim.TrimPattern}（★ 只有这一处解析）。 */
    private static org.bukkit.inventory.meta.trim.TrimPattern resolveTrimPattern(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return org.bukkit.Registry.TRIM_PATTERN.get(
                    org.bukkit.NamespacedKey.minecraft(key.trim().toLowerCase(Locale.ROOT)));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 附魔键 → {@link Enchantment}（★ 只有这一处解析）。
     *
     * <p>先用命名空间键（{@code minecraft:power}），失败再退回按名查
     * （{@code Enchantment.getByName} 的大小写口径在版本间变过，两条都留着更稳）。
     */
    private static Enchantment resolveEnchantment(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String bare = key.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            Enchantment byKey = org.bukkit.Registry.ENCHANTMENT.get(
                    org.bukkit.NamespacedKey.minecraft(bare));
            if (byKey != null) {
                return byKey;
            }
        } catch (Throwable ignored) {
            // 注册表不可用（离线夹具）⇒ 落到下面按名查
        }
        try {
            return Enchantment.getByName(bare.toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void warn(Logger logger, String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
