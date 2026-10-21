package io.mczju.infernalclash.profile;

import com.github.mczjuops.mczjugamecore.player.data.JsonPlayerData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ MGC 档案里的**本插件数据**（金币 / 尘 / 已拥有卡 / 图鉴 / 预设 / 计数器 / **真人战绩**）。
 *
 * <h2>★ MGC 有<b>两套</b>持久化，只有一套能用（【铁律 3】{@code javap} 实测）</h2>
 * <ol>
 *   <li>❌ {@code profile.ProfileStorageManager} / {@code ProfileData} / {@code PlayerSnapshot}
 *       —— 只存<b>装备栏 + 经验</b>：
 *       {@code PlayerSnapshot(byte[] mainContents, byte[] armorContents, byte[] offhand,
 *       byte[] enderChest, int xpLevel, float xpProgress)}
 *       ⇒ <b>存不下</b>本插件的金币 / 卡牌 / 预设。★ <b>不要</b>用它。</li>
 *   <li>✅ {@code player.data.PlayerDataManager} + {@code AbstractPlayerData} 子类
 *       —— 「<b>按游戏 id 注册、每玩家一个 JSON 落盘</b>」的通用系统。本类就走这条
 *       （依据 {@code docs/08} §8.1；落盘位置
 *       {@code plugins/MCZJUGameCore/player_data/infernalclash/<uuid>.json}）。</li>
 * </ol>
 *
 * <h2>★ 字段必须 {@code public}（否则 Gson <b>静默</b>不落盘）</h2>
 * MGC 用 <b>Gson 反射</b>读写本类的字段（{@code JsonPlayerData} 实现
 * {@code serialize.JsonMGCSerializable}，序列化 / 落盘是它的 default 方法）。
 * Gson 只认 {@code public} 字段 ⇒ 把字段写成 {@code private}、
 * 或加了 {@code transient}、或改了字段名，表现都是
 * <b>「不报错、不抛异常，只是重启后进度没了」</b>。
 * ⇒ 纪律与 MGC 排行榜那条一样（{@code docs/08} §7、【铁律 3】）：
 * <b>字段一律 {@code public}，名字不许随手改</b>（改名 = 老档案里那个字段被丢掉）。
 *
 * <h2>★ 本类只做一件事：搬运（不做判断、不猜数值）</h2>
 * <ul>
 *   <li><b>不许写"默认数值"</b>（金币初始值、开卡包价格、兑换表…）——那是装配层 /
 *       {@code config/EconomyConfig}（{@code economy.yml}）的事（【铁律 8】：数值由用户拍板）。
 *       本类的字段初值只有「空」，没有「送多少」。</li>
 *   <li><b>修复逻辑不在本类</b>：坏数据（图鉴与仓库对不上 / 负数 / null 条目）的处置
 *       只有一处 —— 纯类 {@link ProgressMapper#read}（策略见其类注释）。
 *       {@link #toFields()} / {@link #applyFields} 是<b>无脑搬运</b>，一个判断都不做，
 *       否则同一件事就有两份判据（本项目「第二处白名单」教训）。</li>
 *   <li><b>不设 {@code playerID} / {@code gameID}</b>：{@code AbstractPlayerData} 的
 *       {@code setPlayerID} / {@code setGameID} 是 <b>package-private</b>（{@code javap} 实测），
 *       子插件<b>不能</b>自己设 —— 由 MGC 的加载器负责。</li>
 * </ul>
 *
 * <h2>★ 注册与落盘（装配层的事，本类不碰）</h2>
 * <pre>{@code
 * // onEnable
 * MCZJUGameCore.getPlayerDataManager()
 *         .registerPlayerData("infernalclash", InfernalPlayerData.class);
 * MCZJUGameCore.getPlayerDataManager()
 *         .loadAllPlayerData("infernalclash", InfernalPlayerData.class);
 * }</pre>
 *
 * <h2>★ 写入必须走 {@link #applyFields}（它内部会 {@code setModified(true)}）</h2>
 * 直接改 public 字段<b>不会</b>落盘：MGC 靠 {@code isModified()} 决定要不要写这个玩家的 JSON。
 * 这与 {@code docs/08} §8.3 的警告同源（InfernalGuess 的已知坑：
 * 「所有写入必须走唯一入口并 {@code setModified(true)}，否则不落盘」）。
 */
public class InfernalPlayerData extends JsonPlayerData {

    /** 金币（R47 的虚拟值）。 */
    public int gold;

    /** 尘 = 分解重复卡所得的烈焰粉（R46；{@code docs/08} §2）。 */
    public int dust;

    /** 已拥有的卡 id（★ 保序 = 落盘顺序；品质标记见 {@link #codex}）。 */
    public List<String> owned = new ArrayList<>();

    /** 卡 id → 图鉴品质标记（{@code NONE} / {@code NORMAL} / {@code GOLD}；★ 保序）。 */
    public Map<String, String> codex = new LinkedHashMap<>();

    /** 卡组预设（每套一串卡 id；★ 保序）。 */
    public List<List<String>> presets = new ArrayList<>();

    /** 已开卡包数。 */
    public int packsOpened;

    /** 已完成对局数。 */
    public int gamesPlayed;

    // ------------------------------------------------------------------ ★ 真人战绩（排行榜反射读的那四个字段）

    /*
     * ★★ 这一段与上面的字段**性质不同**，别"顺手统一"：
     *
     * ① 字段名**不是**这里的自由命名 —— 它们是 MGC 排行榜**反射读取的契约**：
     *    权威名字在 leaderboard/LeaderboardFields 的常量里
     *    （realWins / realMatches / realStreak / realBestStreak），本类只是它的镜像。
     *    ★ 写错一个字母的后果：MGC 的 PlayerDataLeaderboard.getDoubleValue 里
     *    `getClass().getField(name)` 抛 NoSuchFieldException，而**三种异常全被吞掉并返回 0.0**
     *    ⇒ 榜恒空、不抛、不记日志（机制逐字写在 LeaderboardFields 的类注释里；
     *    AGENTS.md §4.2 记着这条）。⇒ 所以 LeaderboardFieldsTest 有一条**双向锁**：
     *    既断言常量列表的名字，又断言本类上**真的存在**这些 public 字段。
     *
     * ② 必须是 `public` 且是**基本类型 int**：
     *    · MGC 用 `Class.getField(name)`（**只认 public**，含继承来的）+ `instanceof Number`；
     *    · Gson 也只序列化 public 字段（本类继承 JsonPlayerData，落盘机制见类注释）。
     *
     * ③ ★ 它们**故意不参与** toFields() / applyFields()（那是"局外成长"的搬运面）：
     *    写入只有一条路 —— 对局收尾 → LeaderboardWiring.recordResult →
     *    ArchiveFieldAccess.writeStats（反射写这四个字段）→ setModified(true)；
     *    规则在**纯类** leaderboard/LeaderboardStats 里（人机局四个字段一个都不动，
     *    docs/08 §7 R63）。⇒ **不要**把它们加进 ProgressMapper.Fields：
     *    那会变成第二套记账（本项目「第二处白名单」教训：两处判据迟早分叉）。
     */

    /** ★ 真人局胜场（**排行榜的排名依据**，降序；{@code docs/08} §7【Q33】）。 */
    public int realWins;

    /** ★ 真人局总场次（榜单一行里的第 2 个数字）。 */
    public int realMatches;

    /** ★ 当前连胜（赢 +1、输清零；**仅供计算，不上榜**）。 */
    public int realStreak;

    /** ★ 历史最高连胜，**只涨不落**（榜单一行里的第 3 个数字）。 */
    public int realBestStreak;

    /**
     * ★ 档案格式版本：将来字段变了靠它做迁移（{@code 0} = 比 v1 还老的档案）。
     *
     * <p>常量的<b>唯一定义</b>在纯侧 {@link ProgressMapper#FORMAT_VERSION}
     * —— 依赖方向只能是「MGC 侧读纯侧」，反过来会破坏 {@link ProgressMapper} 的纯度。
     */
    public int formatVersion = ProgressMapper.FORMAT_VERSION;

    /** ★ 必须有无参构造：MGC 靠反射实例化这个类。 */
    public InfernalPlayerData() {
    }

    // ------------------------------------------------------------------ 搬运（无脑，不做判断）

    /**
     * 把本档案的字段搬进中性载体（给纯类 {@link ProgressMapper} 用）。
     *
     * <p>★ 这是<b>读</b>操作：不 {@code setModified}（{@link #isModified()} 保持原样），
     * 否则每次开界面都会触发一次无意义的落盘。
     *
     * <p>{@code null} 字段<b>原样传出去</b>（不在这里「顺手修成空集合」）——
     * 修复只有一处（{@link ProgressMapper#read}）。
     */
    public ProgressMapper.Fields toFields() {
        ProgressMapper.Fields fields = new ProgressMapper.Fields();
        fields.formatVersion = formatVersion;
        fields.gold = gold;
        fields.dust = dust;
        fields.owned = owned == null ? null : new ArrayList<>(owned);
        fields.codex = codex == null ? null : new LinkedHashMap<>(codex);
        fields.presets = presets == null ? null : copyPresets(presets);
        fields.packsOpened = packsOpened;
        fields.gamesPlayed = gamesPlayed;
        return fields;
    }

    /**
     * 把中性载体的值搬回本档案（★ <b>原地写</b>同一个实例 —— MGC 只认它注册过的那一个对象）
     * 并 {@code setModified(true)} 让 MGC 落盘。
     *
     * <p>★ 这里<b>不做任何校验 / 修复</b>：传进来的值应该已经过
     * {@link ProgressMapper#apply} + {@link PlayerProgress} 的不变量检查；
     * 真要读坏档案，走 {@link ProgressMapper#read}。
     *
     * @param fields 来源载体（不可为 {@code null}）
     * @throws IllegalArgumentException {@code fields} 为 {@code null}
     */
    public void applyFields(ProgressMapper.Fields fields) {
        if (fields == null) {
            throw new IllegalArgumentException("fields 不能为 null：applyFields 是原地写，"
                    + "没有来源就搬不进来（新玩家请先 PlayerProgress.fresh(playerId) 再 ProgressMapper.apply）");
        }

        this.formatVersion = fields.formatVersion;
        this.gold = fields.gold;
        this.dust = fields.dust;
        this.owned = fields.owned == null ? new ArrayList<>() : new ArrayList<>(fields.owned);
        this.codex = fields.codex == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields.codex);
        this.presets = fields.presets == null ? new ArrayList<>() : copyPresets(fields.presets);
        this.packsOpened = fields.packsOpened;
        this.gamesPlayed = fields.gamesPlayed;

        // ★ 少了这一行 ⇒ 上面所有赋值都不落盘（重启后进度没了，且不报错）
        setModified(true);
    }

    // ------------------------------------------------------------------ 摘要

    /**
     * 一行摘要（日志 / 指令用）——含关键数字，且对 {@code null} 字段容错。
     *
     * <p>★ 真人战绩那四个数字也印出来：A5 之后「榜有没有数」这件事要能在
     * {@code /incl debug profile show} 这类指令里<b>直接对账</b>（写没写进去、是不是还是 0）。
     *
     * <p>⚠️ 这里<b>不</b>调 {@code getFilePath()}：它内部会走
     * {@code MCZJUGameCore.getInstance()}（{@code javap} 实测），离线调用会炸。
     */
    public String describe() {
        return "本插件档案 v" + formatVersion + "：金币 " + gold + " / 尘 " + dust
                + " / 已拥有 " + size(owned) + " 张 / 图鉴 " + size(codex) + " 条"
                + " / 预设 " + size(presets) + " 套"
                + " / 已开包 " + packsOpened + " 次 / 对局 " + gamesPlayed + " 场"
                + " / 真人战绩 " + realWins + " 胜 " + realMatches + " 场"
                + "（连胜 " + realStreak + "、最高 " + realBestStreak + "）";
    }

    // ------------------------------------------------------------------ 内部

    private static int size(Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }

    private static int size(Map<?, ?> map) {
        return map == null ? 0 : map.size();
    }

    /** 逐套复制（外层 + 内层都新建；{@code null} 整套保持 {@code null}，交给纯侧去修）。 */
    private static List<List<String>> copyPresets(List<List<String>> raw) {
        List<List<String>> copy = new ArrayList<>(raw.size());
        for (List<String> preset : raw) {
            copy.add(preset == null ? null : new ArrayList<>(preset));
        }
        return copy;
    }
}
