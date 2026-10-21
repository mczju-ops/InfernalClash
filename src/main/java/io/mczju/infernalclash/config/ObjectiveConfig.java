package io.mczju.infernalclash.config;

import io.mczju.infernalclash.objective.BeamRange;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * {@code config.yml} 的 {@code objectives} 段（<b>严格校验</b>，P3）。
 *
 * <p>出处：{@code docs/03} §3.2 与 {@code docs/04} §16.6.2 / §16.6.3
 * （<b>所有数值都是用户给定的，照抄，不得改动</b> —— 铁律 8）。
 * 键与值的对应关系：
 * <table border="1">
 *   <caption>逐键出处</caption>
 *   <tr><th>键</th><th>出厂值</th><th>出处</th></tr>
 *   <tr><td>{@code beacon.display-block}</td><td>{@code BEACON}</td><td>R7</td></tr>
 *   <tr><td>{@code beacon.health}</td><td>{@code 320}</td><td>2026-10-15 裁定</td></tr>
 *   <tr><td>{@code beacon.taunt}</td><td>{@code true}（<b>布尔，无等级</b>）</td><td>2026-10-15 裁定</td></tr>
 *   <tr><td>{@code beacon.deals-damage}</td><td>{@code false}（信标不攻击）</td><td>C7</td></tr>
 *   <tr><td>{@code beacon.beam-colors}</td><td>{@code [RED, BLUE]}（纯视觉）</td><td>C7 / C16</td></tr>
 *   <tr><td>{@code tower.display-block}</td><td>{@code WAXED_COPPER_BULB}</td><td>Q56</td></tr>
 *   <tr><td>{@code tower.display-block-state.lit}</td><td>{@code true}</td><td>Q56</td></tr>
 *   <tr><td>{@code tower.health}</td><td>{@code 240}</td><td>2026-10-15 裁定</td></tr>
 *   <tr><td>{@code tower.taunt}</td><td>{@code true}</td><td>2026-10-15 裁定</td></tr>
 *   <tr><td>{@code tower.disable-vanilla-attack}</td><td>{@code true}</td><td>C7</td></tr>
 *   <tr><td>{@code tower.beam-damage}</td><td>{@code 2}</td><td>已给定</td></tr>
 *   <tr><td>{@code tower.beam-interval-ticks}</td><td>{@code 10}（0.5s）</td><td>已给定</td></tr>
 *   <tr><td>{@code tower.beam-particle}</td><td>{@code END_ROD}</td><td>已给定</td></tr>
 *   <tr><td>{@code tower.range-shape}</td><td>{@code SQUARE_25}</td><td>C15</td></tr>
 *   <tr><td>{@code tower.half-extent-cells}</td><td>{@code 12}</td><td>C15</td></tr>
 * </table>
 *
 * <h2>★ 为什么和 {@link MatchConfig} 分成两个类，读同一个文件</h2>
 * 与 {@code phases} 同理：{@code MatchConfig} 回答「一局怎么走」，
 * 本类回答「场地里放什么、塔怎么打」。两者都从**同一个已解析的根节点**读
 * （{@link #load(ConfigurationSection)}），所以不重复读盘，
 * 也各自有独立的出厂文件测试（{@code ShippedMatchConfigTest} / {@code ShippedObjectiveConfigTest}）。
 *
 * <h2>★ 两道防线（与 {@code PhaseConfig} 一致）</h2>
 * <ol>
 *   <li><b>配置层</b>（本类的 {@link #load(ConfigurationSection)}）：缺键 / 类型不符 / 越界 /
 *       <b>未知键</b>一律给「文件 + <b>从根算起</b>的键路径」的好报错（{@link ConfigError}）；</li>
 *   <li><b>纯数据层</b>（{@link Beacon#validate()} / {@link Tower#validate()}）：
 *       被直接 {@code new} 出来时同样必须自证合法，抛 {@link IllegalArgumentException}，
 *       消息里写清「哪个键、期望什么」。</li>
 * </ol>
 *
 * @param beacon 信标（R7 / R8）
 * @param tower  防御塔（R9 / C7 / C15）
 */
public record ObjectiveConfig(Beacon beacon, Tower tower) {

    /** 本配置所在的文件名（`objectives` 段在 `config.yml` 里，与 {@link GameConfig#FILE} 同值）。 */
    public static final String FILE = "config.yml";

    /** `objectives` 段本身允许的键 —— 多一个都算拼错。 */
    private static final List<String> OBJECTIVE_KEYS = List.of("beacon", "tower");

    /** `objectives.beacon` 允许的键。 */
    private static final List<String> BEACON_KEYS = List.of(
            "display-block", "health", "taunt", "deals-damage", "beam-colors");

    /** `objectives.tower` 允许的键。 */
    private static final List<String> TOWER_KEYS = List.of(
            "display-block", "display-block-state", "health", "taunt", "disable-vanilla-attack",
            "beam-damage", "beam-interval-ticks", "beam-particle", "range-shape", "half-extent-cells");

    /** `objectives.tower.display-block-state` 允许的键（只有 `lit` 一个）。 */
    private static final List<String> DISPLAY_STATE_KEYS = List.of("lit");

    /** ★ 射程形状的**唯一**合法值（C15：25×25 是格数型范围，不是浮点半径 12.5）。 */
    public static final String RANGE_SHAPE_SQUARE_25 = "SQUARE_25";

    /** 血量的上限（纯防手滑；原版 max-health 属性本身也有上限）。 */
    private static final int MAX_HEALTH = 100_000;

    /** 单道光束伤害的上限（纯防手滑）。 */
    private static final int MAX_BEAM_DAMAGE = 1_000;

    /** 光束间隔的上限：24 小时（纯防手滑，与 {@code MatchConfig} 同为「排除手滑」而非玩法判定）。 */
    private static final int MAX_TICKS = 24 * 60 * 60 * 20;

    /** {@code half-extent-cells} 的粗范围（**不等于**合法性判据，真正的判据在 {@link Tower#validate()}）。 */
    private static final int MAX_HALF_EXTENT = 1_000;

    /**
     * 信标（R7 / R8）。
     *
     * <p>受击目标是**村民载体**，本 record 只描述「放什么、多少血、打不打、什么颜色」；
     * 载体的生成 / PDC 归属 / 僵尸化拦截属于边界层（{@code docs/04} §16.6.2）。
     *
     * @param displayBlock 展示实体的方块外观（原版 Material 名）
     * @param health       血量（用户给定 320）
     * @param taunt        是否带嘲讽（★ <b>布尔，无等级</b>；P5 的 {@code TargetPolicy} 读它）
     * @param dealsDamage  是否造成伤害（C7：信标 <b>不攻击</b> ⇒ false）
     * @param beamColors   光束配色（纯视觉；恰好 2 个且不相同 ⇒ 红 / 蓝）
     */
    public record Beacon(String displayBlock,
                         int health,
                         boolean taunt,
                         boolean dealsDamage,
                         List<String> beamColors) {

        /**
         * 就绪自检：外观必须是合法 Material、血量 &gt; 0、{@code beamColors} 恰好 2 个
         * 且不等于同一值（红 / 蓝是两个不同的取值）。
         *
         * @throws IllegalArgumentException 任一条件不满足（消息里写明键名与期望）
         */
        public void validate() {
            if (Material.matchMaterial(displayBlock) == null) {
                throw new IllegalArgumentException("objectives.beacon.display-block 不是合法的原版 "
                        + "Material 名：" + displayBlock + "（例：BEACON）");
            }
            if (health <= 0) {
                throw new IllegalArgumentException("objectives.beacon.health 必须 > 0：实际 " + health);
            }
            if (beamColors == null || beamColors.size() != 2) {
                throw new IllegalArgumentException("objectives.beacon.beam-colors 必须恰好 2 个"
                        + "（红 / 蓝各一个，C7 / C16）：实际 "
                        + (beamColors == null ? "null" : beamColors.size() + " 个 " + beamColors));
            }
            for (String color : beamColors) {
                if (color == null || color.isBlank()) {
                    throw new IllegalArgumentException("objectives.beacon.beam-colors 不能有空字符串："
                            + "实际 " + beamColors);
                }
            }
            if (beamColors.get(0).equals(beamColors.get(1))) {
                throw new IllegalArgumentException("objectives.beacon.beam-colors 的两个颜色不能相同："
                        + "实际 " + beamColors + "（红 / 蓝是「双方各一色」的视觉区分）");
            }
        }
    }

    /**
     * 防御塔（R9 / C7 / C15）。
     *
     * <p>★ 射程是**格数型**的 25×25：{@code range-shape: SQUARE_25} +
     * {@code half-extent-cells: 12} ⇒ 判据 {@code |dx| <= 12 && |dz| <= 12}
     * （见 {@link BeamRange#inSquare}）。**不是**浮点半径 12.5（C15）。
     *
     * @param displayBlock         展示实体的方块外观（原版 Material 名）
     * @param displayLit           展示实体的 {@code lit} 状态（点亮）
     * @param health               血量（用户给定 240）
     * @param taunt                是否带嘲讽（布尔，无等级；与信标同等）
     * @param disableVanillaAttack 是否禁用原版攻击（C7：载体已改成村民，仍要禁 AI 目标）
     * @param beamDamage           每道光束的伤害（已给定 2）
     * @param beamIntervalTicks    光束间隔（tick；已给定 10 = 0.5s）
     * @param beamParticle         光束粒子名（已给定 END_ROD）
     * @param rangeShape           射程形状（只接受 {@code SQUARE_25}）
     * @param halfExtentCells      半边长（格；必须为 12 ⇒ 25×25）
     */
    public record Tower(String displayBlock,
                        boolean displayLit,
                        int health,
                        boolean taunt,
                        boolean disableVanillaAttack,
                        int beamDamage,
                        int beamIntervalTicks,
                        String beamParticle,
                        String rangeShape,
                        int halfExtentCells) {

        /**
         * ★ 必须做的自检：
         * <ol>
         *   <li>{@code range-shape} 只接受 {@code SQUARE_25}
         *       （其它值 ⇒ 抛，并说明「本项目的射程是格数型的 25×25」）；</li>
         *   <li>{@code half-extent-cells} 必须 == 12（调 {@link BeamRange#requireSquare25(int)}）；</li>
         *   <li>{@code health > 0}、{@code beam-damage > 0}、{@code beam-interval-ticks >= 1}；</li>
         *   <li>{@code display-block} / {@code beam-particle} 非空。</li>
         * </ol>
         *
         * @throws IllegalArgumentException 任一条件不满足（消息里写明键名与期望）
         */
        public void validate() {
            if (!RANGE_SHAPE_SQUARE_25.equals(rangeShape)) {
                throw new IllegalArgumentException("objectives.tower.range-shape 只接受 \""
                        + RANGE_SHAPE_SQUARE_25 + "\"：本项目的射程是格数型的 25×25"
                        + "（不是浮点半径 12.5，C15）—— 实际 " + rangeShape);
            }
            try {
                BeamRange.requireSquare25(halfExtentCells);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "objectives.tower.half-extent-cells —— " + e.getMessage());
            }
            if (Material.matchMaterial(displayBlock) == null) {
                throw new IllegalArgumentException("objectives.tower.display-block 不是合法的原版 "
                        + "Material 名：" + displayBlock + "（例：WAXED_COPPER_BULB）");
            }
            if (health <= 0) {
                throw new IllegalArgumentException("objectives.tower.health 必须 > 0：实际 " + health);
            }
            if (beamDamage <= 0) {
                throw new IllegalArgumentException("objectives.tower.beam-damage 必须 > 0：实际 " + beamDamage);
            }
            if (beamIntervalTicks < 1) {
                throw new IllegalArgumentException("objectives.tower.beam-interval-ticks 必须 >= 1：实际 "
                        + beamIntervalTicks);
            }
            if (beamParticle == null || beamParticle.isBlank()) {
                throw new IllegalArgumentException("objectives.tower.beam-particle 不能为空"
                        + "（面板里的粒子名，例：END_ROD）");
            }
        }
    }

    /**
     * 从已解析的配置根节点加载并校验 `objectives` 段。
     *
     * @throws ConfigError 段缺失 / 任一必需键缺失、类型不符、越界 / 出现未知键
     * @throws IllegalArgumentException 语义自检不通过（射程形状、halfExtent、血量、beam-colors…）
     */
    public static ObjectiveConfig load(ConfigurationSection root) {
        if (root == null) {
            throw new ConfigError(FILE, "<root>", "配置为空（文件不存在或解析失败）");
        }
        ConfigurationSection section = ConfigReader.requireSection(FILE, root, "objectives");
        rejectUnknownKeys(section, OBJECTIVE_KEYS);

        ConfigurationSection beaconSection = ConfigReader.requireSection(FILE, section, "beacon");
        rejectUnknownKeys(beaconSection, BEACON_KEYS);
        Beacon beacon = new Beacon(
                ConfigReader.requireString(FILE, beaconSection, "display-block"),
                ConfigReader.requireInt(FILE, beaconSection, "health", 1, MAX_HEALTH),
                ConfigReader.requireBoolean(FILE, beaconSection, "taunt"),
                ConfigReader.requireBoolean(FILE, beaconSection, "deals-damage"),
                ConfigReader.requireStringList(FILE, beaconSection, "beam-colors"));

        ConfigurationSection towerSection = ConfigReader.requireSection(FILE, section, "tower");
        rejectUnknownKeys(towerSection, TOWER_KEYS);
        Tower tower = new Tower(
                ConfigReader.requireString(FILE, towerSection, "display-block"),
                displayLit(towerSection),
                ConfigReader.requireInt(FILE, towerSection, "health", 1, MAX_HEALTH),
                ConfigReader.requireBoolean(FILE, towerSection, "taunt"),
                ConfigReader.requireBoolean(FILE, towerSection, "disable-vanilla-attack"),
                ConfigReader.requireInt(FILE, towerSection, "beam-damage", 1, MAX_BEAM_DAMAGE),
                ConfigReader.requireInt(FILE, towerSection, "beam-interval-ticks", 1, MAX_TICKS),
                ConfigReader.requireString(FILE, towerSection, "beam-particle"),
                ConfigReader.requireString(FILE, towerSection, "range-shape"),
                ConfigReader.requireInt(FILE, towerSection, "half-extent-cells", 0, MAX_HALF_EXTENT));

        // ★ 第二道防线：配置层已经把「缺键 / 类型 / 未知键」挡住，
        //   这里再让纯类自证一次语义（射程形状、halfExtent=12、beam-colors 恰好 2 个…）
        beacon.validate();
        tower.validate();
        return new ObjectiveConfig(beacon, tower);
    }

    /** 从磁盘 YAML 加载（**离线可用**；用 {@link YamlFile#read} 而不是 {@code YamlConfiguration.loadConfiguration}）。 */
    public static ObjectiveConfig load(File file) {
        return load(YamlFile.read(FILE, file));
    }

    /** 启动日志一行摘要（含血量 / 射程 / 光束间隔）。 */
    public String describe() {
        int side = BeamRange.squareSide(tower.halfExtentCells());
        return "目标物｜信标 " + beacon.health() + " 血（" + beacon.displayBlock()
                + "，嘲讽 " + beacon.taunt() + "，光束配色 " + String.join("/", beacon.beamColors())
                + "）｜防御塔 " + tower.health() + " 血（" + tower.displayBlock()
                + "，射程 " + tower.rangeShape() + " = " + side + "×" + side + " 格，每 "
                + tower.beamIntervalTicks() + " tick 一道光束 " + tower.beamDamage() + " 点伤害，粒子 "
                + tower.beamParticle() + "）";
    }

    /**
     * 读 `tower.display-block-state` 子段里的 `lit`。
     *
     * <p>★ 这里的「缺省 false」是本类**唯一**允许的默认值，理由与 {@code ArenaConfig} 的
     * {@code yaw}/{@code pitch} 缺省 0 相同：它表示「没点亮」这个**无意义之外的确定语义**，
     * 而不是「猜一个有意义但未知的数值」。整段没写 ⇒ 不点亮；写了段但没写 {@code lit}
     * ⇒ 同样不点亮；写了段且写了别的键 ⇒ 未知键报错。
     */
    private static boolean displayLit(ConfigurationSection towerSection) {
        if (!towerSection.isSet("display-block-state")) {
            return false;
        }
        ConfigurationSection state = ConfigReader.requireSection(FILE, towerSection, "display-block-state");
        rejectUnknownKeys(state, DISPLAY_STATE_KEYS);
        if (!state.isSet("lit")) {
            return false;
        }
        return ConfigReader.requireBoolean(FILE, state, "lit");
    }

    /**
     * 出现**未知键**就抛 {@link ConfigError}。
     *
     * <p>★ 为什么必须拒绝而不是忽略：拼错的键（{@code beam-damage} 写成 {@code beam-damagee}）
     * 被静默忽略的后果是「配置写了却没生效」且**没有任何报错** ——
     * 这是本项目反复警告的事故类型（{@code AGENTS.md} §4.1）。
     *
     * <p>键路径指向**那个拼错的键**、并从根算起（例：{@code objectives.tower.beam-damagee}），
     * 消息里同时列出全部未知键与本段允许的键。
     */
    private static void rejectUnknownKeys(ConfigurationSection section, List<String> allowed) {
        List<String> unknown = new ArrayList<>();
        for (String key : new TreeSet<>(section.getKeys(false))) {   // 排序 ⇒ 报错顺序稳定、可复现
            if (!allowed.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        String base = section.getCurrentPath();
        throw new ConfigError(FILE, base + "." + unknown.get(0),
                "存在未知键 " + unknown + "（" + base + " 段只接受 " + allowed + "）—— "
                        + "拼错的键必须被当场抓住，否则会变成「配置写了却没生效」");
    }
}
