package io.mczju.infernalclash.ability;

import io.mczju.infernalclash.card.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>能力注册表</b>：卡牌 YAML 里 {@code abilities[].id} 必须引用这里的 id
 * （{@code docs/03} §4.3 / §4.5、【Q47】数据驱动）。
 *
 * <h2>★★ 这 5 个是「机制 id」，不是「玩法 id」</h2>
 * 本注册表首批只有 <b>5 条</b>，且与 {@link Card.Trigger} 的 5 个取值 <b>一一对应</b>：
 * <table border="1">
 *   <caption>首批注册项</caption>
 *   <tr><th>id</th><th>触发器</th><th>语义（唯一的语义）</th></tr>
 *   <tr><td>{@code periodic}</td><td>{@link Card.Trigger#PERIODIC}</td>
 *       <td>每 {@code cooldown-ticks} 施加一次 {@code effects}</td></tr>
 *   <tr><td>{@code on_hurt}</td><td>{@link Card.Trigger#ON_HURT}</td><td>受伤时施加</td></tr>
 *   <tr><td>{@code on_attack}</td><td>{@link Card.Trigger#ON_ATTACK}</td><td>攻击命中时施加</td></tr>
 *   <tr><td>{@code on_death}</td><td>{@link Card.Trigger#ON_DEATH}</td><td>死亡时施加（亡语）</td></tr>
 *   <tr><td>{@code on_summon}</td><td>{@link Card.Trigger#ON_SUMMON}</td>
 *       <td>刚被召唤出来时施加一次</td></tr>
 * </table>
 * 它们的语义可以一句话概括：<b>「在声明的 trigger 上，把声明的 effects 施加出去」</b>
 * —— 也就是说，<b>这 5 个 id 并没有携带任何玩法信息</b>，它们只是把
 * 「触发器」这一维从枚举搬进了注册表，好让 {@code abilities[].id} 这个键有东西可查。
 * 真正决定行为的是同一张卡里的 {@code trigger} + {@code effects} 两个字段。
 *
 * <h2>★ 为什么没有「玩法级能力」（吸血 / 分裂 / 每 5s 召唤 1 只蠹虫…）</h2>
 * 那种 id 一旦注册，就意味着<b>某段接管代码</b>已经在仓库里存在并被接线
 * （例如「虫群术师每 5s 按权重抽实体召唤」需要 {@code SUMMON} 的 {@code table:} 支持，
 * 见 {@code docs/03} §4.6）。截至本轮：
 * <ul>
 *   <li>51 张卡的 {@code abilities} <b>一条都没有真正启用</b>（全是文件头部的
 *       {@code 【未写 abilities】… 待 P6 补齐：} 注释候选块）；</li>
 *   <li>{@code docs/11} §Q9 记的正是这件事：<b>「没有权威清单可抄」</b>。</li>
 * </ul>
 * ⇒ 按<b>铁律 11</b>（需求里的缺口由用户裁定，不自行拍板）与<b>铁律 8</b>（数值/观感不擅自拍板），
 * 本类<b>故意不注册</b>任何「凭猜的玩法 id」（例如 {@code lifesteal} / {@code split}）——
 * {@code docs/03} §4.3 的示例里那个 {@code id: lifesteal} 只是<b>格式示例</b>，
 * 不是权威清单。将来要加，必须先把该 id 的<b>行为与作用范围</b>落到文档里，再进这里。
 *
 * <h2>★ 为什么 id 必须与 trigger 一致</h2>
 * {@code id} 与 {@code trigger} 是同一条 {@link Card.Ability} 上的两个字段。
 * 若允许 {@code id: periodic} + {@code trigger: ON_DEATH}，这张卡就<b>自相矛盾</b>：
 * 读 id 的人以为是周期能力，读 trigger 的人以为是亡语 —— 而且是<b>静默</b>的矛盾
 * （两个字段互不校验，运行时只认其中一个）。{@link #validate} 因此把它列为硬失败。
 *
 * <h2>纯数据、可离线</h2>
 * 本类不碰服务端（不 import 任何 {@code org.bukkit.*}）⇒ 全程可在 JUnit 里断言。
 * 顺序一律用 {@link LinkedHashMap} / {@link List} 保住（本项目的硬要求：
 * <b>同输入 → 同结果</b>，不许靠 {@code HashMap} 的运气）。
 */
public final class AbilityCatalog {

    /**
     * 首批注册项 —— <b>5 个「机制 id」，与 {@link Card.Trigger} 的 5 个取值一一对应</b>。
     *
     * <p>★ 顺序即 {@link #ids()} / {@link #entries()} 的顺序，也是启动日志的顺序。
     */
    private static final List<Entry> ENTRIES = List.of(
            new Entry("periodic", Card.Trigger.PERIODIC,
                    "周期触发：每 cooldown-ticks tick 施加一次 effects（cooldown-ticks 即周期；"
                            + "装载期不校验该键、不拒绝 —— 缺 / 非正时运行期告警一次并跳过这一个能力，"
                            + "不猜缺省、也不连累整局；原写「拒绝启动」系失真，2026-10-15 更正）"),
            new Entry("on_hurt", Card.Trigger.ON_HURT,
                    "受伤时施加 effects（目标 = 受伤的那个召唤物自己）"),
            new Entry("on_attack", Card.Trigger.ON_ATTACK,
                    "攻击命中时施加 effects（目标 = 攻击的那个召唤物自己）"),
            new Entry("on_death", Card.Trigger.ON_DEATH,
                    "死亡时施加 effects（亡语；origin = 死亡位置）"),
            new Entry("on_summon", Card.Trigger.ON_SUMMON,
                    "刚被召唤出来时施加一次 effects（目标 = 这个召唤物自己）"),

            // ==================================================================================
            // ★★★ 2026-10-17 第十三轮（批次 3）新增的 **12 条**「玩法级 id」
            //
            // 用户第 32 条裁定（留档 docs/24 §10）：
            //   「**授权我从 docs/19 提取候选能力 id 给他审**」
            // ⇒ 这些 id 的**语义**全部照抄 docs/19 的「能力」列与 docs/卡牌策划案.txt，
            //   一个字都没有自己发明（每条都在 description 里点名出处）。
            //
            // ★★ 批次 3 前半（11 条）里的 `poison_on_summon` / `stun_on_summon` /
            //   `deathrattle_damage` 当时被标成「未接线（卡里先别用）」—— 因为
            //   「对周围 N 格」缺一个**效果目标集**。批次 3 后半补上了
            //   `ability/AbilityTargets`（`targets` / `radius` / `include-self` / `center`
            //   四个键）⇒ 那三条**已接线**，标记已撤销，对应的卡也都落了数据。
            //   ★ 新增的第 12 条 `area_damage_periodically` 就是冰术师那张（需要
            //     `center: ATTACK_TARGET`）。
            //
            // ★★ 为什么现在可以加、以前不行（这条最容易被后人误解）：
            //   旧 javadoc 写「不注册玩法级 id，因为那意味着某段接管代码已经在仓库里」——
            //   而**接管代码早就有了**：`ability/AbilityRuntime` 在 **5 个触发器上全都接线完毕**
            //   （ON_SUMMON / PERIODIC / ON_HURT / ON_ATTACK / ON_DEATH），
            //   `spell/EffectApplier` 也已支持**全部 15 种效果类型**
            //   （DAMAGE / HEAL / POTION / SUMMON / STUN / REVIVE / GRANT_XP / REFRESH_COOLDOWN …）。
            //   ⇒ 这些 id 的**行为完全由卡里的 `trigger` + `effects` + `targets` 决定**，
            //     注册表只提供一个**可校验的名字**（`id` 与 `trigger` 必须一致，写错就在装载期拒绝）。
            //   ⇒ 与那 5 条「机制 id」是同一档东西：**注册表里没有第二份玩法**。
            //
            // ★ 顺序 = 启动日志顺序 = 这里的书写顺序（按触发器归类，便于人读）。
            // ==================================================================================
            new Entry("gain_xp_periodically", Card.Trigger.PERIODIC,
                    "周期给召唤者加经验；出处 docs/19 的 allay「每 5s 给召唤者 1 级」"
                            + "（GRANT_XP 的目标是召唤者而不是召唤物自己，见 AbilityRuntime 的说明）"),
            new Entry("revive_once", Card.Trigger.ON_DEATH,
                    "假死复活一次（次数上限 = 1）；出处 docs/19 的 axolotl「阵亡时复活一次」"
                            + "（出自 docs/07 §6.4 新增的 REVIVE 效果）"),
            new Entry("refresh_card_cooldown", Card.Trigger.ON_DEATH,
                    "死亡时刷新本卡的冷却；出处 docs/19 的 restless_soul「阵亡时刷新卡牌冷却」"
                            + "（出自 docs/07 §6.4 新增的 REFRESH_COOLDOWN 效果）"),
            new Entry("poison_on_summon", Card.Trigger.ON_SUMMON,
                    "已接线（2026-10-17 批次 3 后半）：出处 docs/19 的 sentry / blaze_guard /"
                            + " blaze_furnace / frog_parasite「召唤时凋零 I」＋ sea_king 那一类"
                            + "「登场时对周围 N 格」的卡，用 targets + radius 表达作用面。"
                            + "「凋零」= 原版 WITHER 药水效果（不是 POISON：文案与文档"
                            + "用的都是「凋零」）；amplifier 0 = 等级 I。它不需要 radius"
                            + "（哨位 / 烈焰守卫都是打自己）—— radius 是给「周围 N 格」那几张的"),
            new Entry("resistance_on_summon", Card.Trigger.ON_SUMMON,
                    "登场时给自己抗性；出处 docs/19 的 coal_guard「登场时自身抗性 V」"
                            + "（不需要新目标集：目标就是召唤物自己）"),
            new Entry("speed_on_summon", Card.Trigger.ON_SUMMON,
                    "登场时给自己速度；出处 docs/19 的 terrorist「登场时速度 II」"
                            + "与 swamp_guerrillas「召唤时速度 II」"
                            + "（这一条正是过去写「无『登场时』触发位，未写」的那两格）"),
            new Entry("stun_on_summon", Card.Trigger.ON_SUMMON,
                    "已接线（2026-10-17 批次 3 后半）：出处 docs/19 的 sea_king"
                            + "「登场时眩晕自身 2 格半径内的所有敌人并造成 8 点伤害」"
                            + "，targets: ENEMIES + radius: 2 + 两条效果 {DAMAGE} {STUN}"),
            new Entry("stun_on_attack", Card.Trigger.ON_ATTACK,
                    "攻击命中时眩晕目标；出处 docs/19 的 sea_king「攻击命中时眩晕 1 秒」"
                            + "（这一条的目标就是「这次攻击命中的那个实体」，运行期天然有它）"),
            new Entry("deathrattle_damage", Card.Trigger.ON_DEATH,
                    "已接线（2026-10-17 批次 3 后半）：出处 docs/19 的 immortal_banner"
                            + "「亡语：死亡时对周围 3 格敌人造成 8 点伤害」"
                            + "，targets: ENEMIES + radius: 3（origin = 死亡位置）"),
            new Entry("summon_on_death", Card.Trigger.ON_DEATH,
                    "亡语：在自己倒下的位置召唤一批实体；出处 docs/19 的 frog_parasite"
                            + "「死亡生成 4 只蠹虫」与 blaze_furnace「死亡后生成岩浆怪」"
                            + "（数量走 SUMMON 的 group.count；origin = 死亡位置，"
                            + "ON_DEATH 触发时运行期会把它传进来 —— 这条不缺目标集）"),
            new Entry("fang_barrage", Card.Trigger.PERIODIC,
                    "已接线（2026-10-21）：周期「先眩晕目标、再在目标脚下召唤尖牙」；"
                            + "出处 docs/19 的 evoker（用户逐字：「唤魔者（原版的尖牙很容易空）："
                            + "拦截召唤尖牙行为，每 3 秒先使目标眩晕 1 秒，同时在目标处召唤尖牙」）"
                            + "，center: ATTACK_TARGET + targets: ENEMIES + radius: 0"
                            + "（= 只有目标那一格）＋ 效果 {STUN 20} ＋ {SPAWN_FANGS}"),
            new Entry("deathrattle_cast_spell", Card.Trigger.ON_DEATH,
                    "已接线（2026-10-19）：亡语 = 在自己倒下的位置施放一次法术卡"
                            + "（effects: [{ type: CAST_SPELL, spell: <法术卡 id> }]）；"
                            + "出处 docs/19 的 grudge_bomber「死亡时会爆炸」"
                            + "（用户逐字：「可以用已有的嵌套执行在死亡处释放法术」）"
                            + "施放者 = 召唤者，不扣他的费用与冷却；深度限 1（【Q26】）"),
            new Entry("summon_periodically", Card.Trigger.PERIODIC,
                    "周期在自己脚下召唤实体；出处 docs/19 的 frog_parasite「每 5s 召唤 1 只蠹虫」/"
                            + "blaze_furnace「每 5s 生成岩浆怪」/ swarm_shaman「每 5s 召唤蠹虫」"),
            new Entry("area_damage_periodically", Card.Trigger.PERIODIC,
                    "周期对一片范围造成伤害 + 附带药水效果；出处 docs/19 的 ice_mage"
                            + "「每 2s 对目标及其周围 1 格内敌人造成 4 点范围伤害 + 缓慢 II」"
                            + "（这一条正是需要 center: ATTACK_TARGET 的那张卡：范围中心是它"
                            + "锁定的那个敌人，不是它自己）"),
            new Entry("heal_allies_periodically", Card.Trigger.PERIODIC,
                    "周期给周围的友方单位回血 + 附加药水效果；出处 docs/19 的 steam_sprite"
                            + "「每 3s 使周围 3 格内友方单位恢复 8 点生命 + 获得 3s 抗性提升 I」"
                            + "（与上面那条恰好是镜像：一个打周围敌人、一个治周围友方，"
                            + "两条都靠 targets + radius 表达）"),
            new Entry("fireball_periodically", Card.Trigger.PERIODIC,
                    "周期朝半径内最近的敌人放一颗火球并结算伤害；出处 docs/19 的 necromancer"
                            + "「每 3s 在 8 格内目标处放一次火球术」—— 用户 2026-10-17 改成"
                            + "「20 点的火球，但攻击间隔变为原来的两倍」，本卡落的是"
                            + " cooldown-ticks: 120（6 秒）+ amount: 20 + projectile: FIREBALL"
                            + "（火球只负责观感，伤害由 amount 结算）"),
            // ==================================================================================
            // ★★★ 1.1.0（2026-10-26）新增的 2 条「玩法级 id」
            //   出处全部来自 docs/1.1卡牌.txt（逐字），没有一条是我发明的。
            // ==================================================================================
            new Entry("web_on_attack", Card.Trigger.ON_ATTACK,
                    "攻击命中时在**目标脚下**结一张蜘蛛网（effects: [{ type: PLACE_WEB,"
                            + " duration-ticks: 160 }]）；出处 docs/1.1卡牌.txt 的"
                            + "「囚笼编织者：该蜘蛛会在攻击目标处结网，网会存在 8 秒」。"
                            + "★ 能不能替换那一格由纯类 policy/WebBlockRule 判（只碰空气与软方块），"
                            + "生成/接管/还原在 spell/WebField"),
            new Entry("deathrattle_grant_xp", Card.Trigger.ON_DEATH,
                    "亡语 = 给**召唤者**加经验（effects: [{ type: GRANT_XP, amount: N }]）；"
                            + "出处 docs/1.1卡牌.txt 的「美愿轰炸机：死亡时返还 3 点经验值」。"
                            + "★ 目标与 gain_xp_periodically 同一条通道（GRANT_XP 打给召唤者，"
                            + "不是打给尸体）—— 所以它不需要 radius / targets 这些作用面")
    );

    /** id → 注册项（**保序**：与 {@link #ENTRIES} 同序）。 */
    private static final Map<String, Entry> BY_ID = index();

    private AbilityCatalog() {
    }

    /**
     * 一条注册项。
     *
     * @param id          能力 id（卡牌 YAML 里 {@code abilities[].id} 写的就是它）
     * @param trigger     ★ 该 id <b>唯一允许</b>搭配的触发器（id 与 trigger 必须一致）
     * @param description 一行语义说明（启动日志用；**不承载玩法**，玩法在卡里）
     */
    public record Entry(String id, Card.Trigger trigger, String description) {
    }

    /** 全部已注册的 id（**保序**）。 */
    public static List<String> ids() {
        List<String> ids = new ArrayList<>(ENTRIES.size());
        for (Entry entry : ENTRIES) {
            ids.add(entry.id());
        }
        return Collections.unmodifiableList(ids);
    }

    /** 全部注册项（**保序**、只读）。 */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** 这个 id 注册过吗（{@code null} / 空串 ⇒ {@code false}）。 */
    public static boolean has(String id) {
        return id != null && BY_ID.containsKey(id);
    }

    /**
     * 取一条注册项。
     *
     * @throws IllegalArgumentException 未知 id（消息里<b>列出全部已知 id</b>，
     *                                  否则运维只知道"写错了"，不知道能写什么）
     */
    public static Entry require(String id) {
        Entry entry = id == null ? null : BY_ID.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("未知的能力 id：" + id + ""
                    + "（abilities[].id 必须引用能力注册表里的 id；已知 id = " + ids() + "）");
        }
        return entry;
    }

    /**
     * ★ <b>校验一张卡的 abilities</b>（{@code docs/03} §4.5 的「能力注册表」那一行）。
     *
     * <p>两条硬失败：
     * <ol>
     *   <li>{@code id} 未注册 ⇒ 抛（消息列出全部已知 id）；</li>
     *   <li>{@code id} 与 {@code trigger} <b>不一致</b> ⇒ 抛
     *       （{@code periodic} 只能配 {@code PERIODIC}，否则两个字段互相矛盾）。</li>
     * </ol>
     * 「{@code abilities} 缺省 / 空」是<b>合法</b>的（51 张卡现在全都是这种情况）
     * —— 本方法不因此报错，因为「没有特殊能力」本来就是一个正常状态。
     *
     * @param cardId    卡牌 id（只用于报错时指出是哪张卡）
     * @param abilities 该卡的 abilities（{@code null} 视为空）
     * @throws IllegalArgumentException 未知 id / id 与 trigger 不匹配
     */
    public static void validate(String cardId, List<Card.Ability> abilities) {
        if (abilities == null || abilities.isEmpty()) {
            return;
        }
        String card = cardId == null || cardId.isBlank() ? "<未知卡>" : cardId;
        for (int index = 0; index < abilities.size(); index++) {
            Card.Ability ability = abilities.get(index);
            // 本条能力的报错前缀：卡 id + 第几条（运维要能直接定位到那一行）
            String where = "卡 " + card + " 的 abilities[" + index + "]";
            if (ability == null) {
                throw new IllegalArgumentException(where + " 为 null（这是一条不存在的注册项）");
            }
            if (!has(ability.id())) {
                // ★ 报错必须写清「哪张卡 + 第几条」—— 只报 id 的话，51 张卡里根本不知道去哪找
                throw new IllegalArgumentException(where + " 引用了未注册的能力 id：" + ability.id()
                        + "（abilities[].id 必须引用能力注册表里的 id；已知 id = " + ids() + "）");
            }
            Entry entry = require(ability.id());
            Card.Trigger expected = entry.trigger();
            if (ability.trigger() != expected) {
                throw new IllegalArgumentException("卡 " + card + " 的 abilities[" + index
                        + "]：id 与 trigger 不一致 —— id 是 " + ability.id() + "，"
                        + "它的 trigger 只能是 " + expected + "，但这条写的是 " + ability.trigger()
                        + "（两个字段互相矛盾；要么改 trigger 为 " + expected
                        + "，要么换成与该 trigger 对应的 id）");
            }
        }
    }

    /**
     * 一行摘要（启动日志用）。
     *
     * <p>★★ 2026-10-17 第十三轮改了措辞：注册表里现在**两类都有**（前 5 条机制 id ＋
     * 其后 11 条玩法级 id）⇒ 旧措辞「N 条机制 id（不是玩法 id）」已经**失真**
     * （读日志的人会以为表里没有玩法级 id）。
     * ★ 新措辞同时点明**这件最容易误解的事**：**表里有名字 ≠ 该能力已接线**
     * —— 有 3 条玩法级 id 的 description 明写「未接线（卡里先别用）」。
     */
    public static String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append(ENTRIES.size()).append(" 条能力 id（注册表里有名字 ≠ 已经接线：")
                .append("有少数几条的说明里明写「未接线」，用它之前先读那一条）：");
        for (Entry entry : ENTRIES) {
            builder.append(' ').append(entry.id()).append('→').append(entry.trigger()).append('；');
        }
        builder.setLength(builder.length() - 1);
        return builder.toString();
    }

    private static Map<String, Entry> index() {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry entry : ENTRIES) {
            Entry previous = map.put(entry.id(), entry);
            if (previous != null) {
                // 注册表自己重复了：这是编码错误，启动就该炸（不是运行期状况）
                throw new IllegalStateException("AbilityCatalog 里有重复的 id：" + entry.id() + "");
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
