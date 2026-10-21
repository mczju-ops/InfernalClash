package io.mczju.infernalclash.leaderboard;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.game.InfernalClashGame;
import io.mczju.infernalclash.profile.InfernalPlayerData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ★ <b>唯一碰 MGC 的那一层</b>（薄适配层）：注册榜单 / 刷新榜单 / 读档案 / 记一局战绩。
 *
 * <h2>★★ 失败方向（本类最重要的一条规矩）</h2>
 * 本类里<b>每一个</b> public 方法都 {@code catch (Throwable)} 后<b>只告警</b>，
 * <b>绝不把异常抛出去</b>：
 * <ul>
 *   <li>它被 {@code onEnable} 调用 ⇒ 抛出去 = <b>服务器起不来</b>；</li>
 *   <li>它被对局收尾路径调用 ⇒ 抛出去 = <b>一局对局收不了尾</b>。</li>
 * </ul>
 * 「榜单挂了」的代价应该是<b>少一个榜</b>，不是「玩家玩不了」。这条不是风格问题：
 * {@code javap} 实测 MGC 的 {@code registerLeaderboard} 会抛
 * {@code IllegalStateException}（id 重复）/ {@code RuntimeException}（实例化失败），
 * 而 {@code refresh(Class)} 在<b>未注册时也抛</b>（{@code docs/04} §16.13.1）。
 *
 * <h2>★ 本类碰的 MGC 接口（就这三处，没有第四处）</h2>
 * <ol>
 *   <li>{@code MCZJUGameCore.getLeaderboardManager().registerLeaderboard(id, 类)} —— 注册；</li>
 *   <li>{@code MCZJUGameCore.getLeaderboardManager().refresh(类)} —— ★ 每局结束自己刷
 *       （{@code docs/08} §7：MGC 自动刷新是 12000 tick ≈ 10 分钟一轮）；</li>
 *   <li>{@code MCZJUGameCore.getPlayerDataManager().getAllPlayerData(InfernalPlayerData.class)}
 *       —— <b>只读枚举</b>。</li>
 * </ol>
 *
 * <p>⚠️ 第 3 处的<b>偏离说明</b>（写在最显眼处，免得后人以为是疏忽）：
 * {@code profile/ProfileStore} 的类注释写着「其它任何类不得直接调
 * {@code MCZJUGameCore.getPlayerDataManager()}」，而它<b>只提供单个玩家的
 * {@link io.mczju.infernalclash.profile.ProfileStore#raw(UUID)}，没有"枚举所有人"的入口</b>，
 * 榜单又<b>必须</b>枚举所有人 ⇒ 只能在这里多一处<b>只读</b>调用
 * （IG 的 {@code LeaderboardSupport.snapshot()} 也是这么做的）。
 * <b>写</b>档案则仍然走 {@code ProfileStore.raw(...)} 拿到的对象，<b>不再多调一次 manager</b>。
 * 若要把这处也收回去，需要在 {@code profile/**} 加一个枚举门面 —— <b>不在本轮改动范围内</b>。
 *
 * <h2>★ 记录一局：纯核心负责算，本类负责搬</h2>
 * 规则一个都不在本类里（{@link LeaderboardStats#record} 是纯的）；本类只做
 * 「取档案 → 算 → 反射写 → {@code setModified(true)}」。
 * ★ 最后那一步不能省：{@code profile/InfernalPlayerData} 的口径是「改了字段不 {@code setModified}
 * 就不落盘」。
 * ⚠️ <b>刻意不做</b>「立即 {@code savePlayerData}」（A5 落地后仍是这个口径，理由已变，写清楚）：
 * 落盘由 MGC 的自动保存负责（{@code docs/08} §8.3 的纪律就是 {@code setModified} + 自动保存），
 * 而<b>榜的刷新读的是 MGC 内存里的档案对象</b>（{@link #snapshotOfArchives} 走
 * {@code getAllPlayerData}）⇒ 战绩「立刻可见」这件事<b>不需要</b>同步落盘。
 * 为了它去给 {@code ProfileStore} 加一个「保存原始档案」的入口 = 多一条落盘路径
 * （本项目「第二处白名单」教训：两条路径迟早分叉），因此<b>不做</b>。
 */
public final class LeaderboardWiring {

    private static final String TAG = "[IC] ";

    private LeaderboardWiring() {
    }

    // ==================================================================================
    // 一、★ 纯部分：注册计划（可离线单测）
    // ==================================================================================

    /**
     * 注册计划（纯数据）。
     *
     * @param register 是否应当注册
     * @param id       榜单 id（{@code register == false} 时为 {@code null}）
     * @param reason   给日志用的原因
     */
    public record Plan(boolean register, String id, String reason) {

        /** 一行日志。 */
        public String describe() {
            if (register) {
                return "将注册榜单：id=" + id + "，类 " + InfernalClashLeaderboard.class.getSimpleName()
                        + "（" + reason + "）";
            }
            return "不注册榜单：" + reason;
        }
    }

    /**
     * ★ <b>纯函数</b>：{@code id 覆盖值} ⇒ 注册计划。
     *
     * <h2>★ 语义（A4 之后；E12 已由用户裁定为 {@link LeaderboardFields#ID}）</h2>
     * <table border="1">
     *   <caption>三种输入</caption>
     *   <tr><th>入参</th><th>返回</th><th>为什么</th></tr>
     *   <tr><td>{@code null}（属性<b>没给</b>）</td>
     *       <td>{@code register=true}，{@code id=}{@link LeaderboardFields#ID}</td>
     *       <td>用户 2026-10-15 已裁定 id（原话逐字留档 {@code docs/24} §1 第 3 条）⇒
     *           默认就用它注册</td></tr>
     *   <tr><td>非 {@code null} 且 {@link #isLegalId 合法}</td>
     *       <td>{@code register=true}，{@code id=}去掉两侧空白后的值</td>
     *       <td><b>属性优先</b>：运维手段（铁律 5「优先加运行时开关」）；
     *           去空白是为了「同一个 id 只对应一个榜」</td></tr>
     *   <tr><td>非 {@code null} 但<b>非法</b>（空白 / 含非法字符）</td>
     *       <td>{@code register=false}，{@code id=null} + 原因</td>
     *       <td>★ <b>拒绝注册 + 告警</b>（失败方向<b>不变</b>，只是触发条件从"没给"变成"给错了"）——
     *           <b>不</b>退回 {@link LeaderboardFields#ID}：那会把"你写错了"变成"我猜你用默认"，
     *           配置错误就此隐身</td></tr>
     * </table>
     *
     * <p>★ 语义变更出处：2026-10-15 用户裁定（{@code docs/24} §1 第 3 条「{@code incl_top}」/ 第 4 条「补」）
     * ⇒ 旧口径「<b>没给 id 就拒绝注册</b>」（当时 {@code docs/08} §7 标【待填】、
     * {@code docs/03} §5.2 明令「不要发明一个 id」）<b>已被这条裁定取代</b>：
     * 现在"没给"是<b>正常情况</b>，用裁定值；只有"给错了"才拒绝。
     *
     * @param leaderboardId 运行期覆盖值（见 {@link LeaderboardFields#ID_PROPERTY}）；{@code null} = 没给
     */
    public static Plan plan(String leaderboardId) {
        // ★ ① 没给 ⇒ 用**用户裁定值**（不是"我猜的默认值"）
        if (leaderboardId == null) {
            return new Plan(true, LeaderboardFields.ID,
                    "使用用户裁定值 id=" + LeaderboardFields.ID + ""
                            + "（2026-10-15 原话「incl_top」，留档 docs/24 §1 第 3 条；"
                            + "docs/08 §7 的 id 一格已由【待填】改为已裁定）");
        }
        // ★ ② 给了但非法 ⇒ 拒绝（★ 不悄悄退回默认值）
        if (!isLegalId(leaderboardId)) {
            return new Plan(false, null,
                    "运行期覆盖 -D" + LeaderboardFields.ID_PROPERTY + "=「" + leaderboardId
                            + "」不是合法的榜单 id，拒绝注册"
                            + "（不退回裁定值 " + LeaderboardFields.ID + "：那会把写错的配置藏起来）。"
                            + "判据：" + illegalIdReason(leaderboardId)
                            + " 这个 id 会被 MGC 当作目录名用（javap -c 实测："
                            + "registerLeaderboard → loadTextDisplays → Paths.get(数据目录, "
                            + "\"text_displays\", id)），详见 LeaderboardWiring.isLegalId 的 javadoc");
        }
        // ★ ③ 给了且合法 ⇒ 属性优先
        return new Plan(true, leaderboardId.trim(),
                "id 由运行期开关 -D" + LeaderboardFields.ID_PROPERTY + " 覆盖为 "
                        + leaderboardId.trim() + "（默认是用户裁定值 " + LeaderboardFields.ID + "；"
                        + "覆盖是运维手段，不是裁定值）");
    }

    /**
     * ★ <b>纯函数</b>：这个字符串能不能当榜单 id 用？
     *
     * <h2>判据从哪来（★ {@code javap} 实测，不是风格偏好）</h2>
     * MGC 1.1.2 的 {@code LeaderboardManager.registerLeaderboard(String, Class)} 反汇编显示，
     * 它对这个 id 做了两件事：
     * <ol>
     *   <li>当成 {@code leaderboards} / {@code displayMap} 两个 {@code Map} 的键
     *       （重复注册 ⇒ {@code IllegalStateException}）；</li>
     *   <li>最后一句是 {@code loadTextDisplays(id)}，而
     *       {@code JsonTextDisplayLoader.loadAllTextDisplay(id)} 拼的是
     *       {@code Paths.get(MCZJUGameCore.getInstance().getDataFolder().getPath(),
     *       "text_displays", id)} 再 {@code Files.newDirectoryStream(dir, "*.json")}
     *       ⇒ ★ <b>id 会被直接当成一个目录名</b>（落盘位置就是
     *       {@code plugins/MCZJUGameCore/text_displays/<id>/}，见 {@code docs/08} §7）。</li>
     * </ol>
     * ⇒ 于是以下都<b>不行</b>（每条都对应一个真实的坏结果，不是"洁癖"）：
     * <ul>
     *   <li><b>空白 / 空串</b>（例如 {@code -Dinfernalclash.leaderboard-id=}）⇒ 目录变成
     *       {@code text_displays} 本身，与别的榜混在一起；</li>
     *   <li><b>路径分隔符 / 其它非法文件名字符</b>（{@code / \ : * ? " < > |} 与空白）
     *       ⇒ {@code Paths.get} 会拼出嵌套目录或直接抛 {@code InvalidPathException}
     *       （注册时抛 ⇒ 榜单整个用不了）；</li>
     *   <li>{@code "."} / {@code ".."} ⇒ {@code Paths} 的<b>特殊分量</b>，会指到当前/上级目录
     *       （{@code ..} 在极端写法下能越出 {@code text_displays}）；</li>
     *   <li>控制字符 ⇒ 日志与 {@code /mgcop} 指令里根本打不出来，
     *       且 {@code display.yml} 之类的文本处理会出错。</li>
     * </ul>
     * 因此这里取一个<b>保守的白名单</b>：{@code [A-Za-z0-9_.-]}，并<b>额外</b>拒绝
     * {@code "."} / {@code ".."}。长度<b>不设上限</b>（没实测到 MGC 有长度约束，
     * 按铁律 8 不发明数字）。
     *
     * <h2>★ 大小写：允许，但有一条实测要注意</h2>
     * MGC <b>不</b>做大小写归一（{@code Map} 键区分大小写）⇒ {@code INCL_TOP} 与 {@code incl_top}
     * 是<b>两个榜</b>；但 Windows 文件系统<b>不区分</b>大小写 ⇒ 两者的
     * {@code text_displays/} 目录其实是<b>同一个</b>（展示实体会互相抢）。
     * ⇒ 不禁止（禁止了会与"id 原样保留"的口径打架），但在这里写明：<b>别用大小写变体当新榜</b>。
     *
     * @param id 待判定的 id；{@code null} ⇒ {@code false}
     */
    public static boolean isLegalId(String id) {
        return illegalIdReason(id) == null;
    }

    /** {@link #isLegalId} 的判据本体：{@code null} = 合法；否则是中文原因（给告警用）。 */
    private static String illegalIdReason(String id) {
        if (id == null) {
            return "id 为 null（没给，那应该走裁定值那条路，而不是到这里）";
        }
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            return "id 是空白（属性给了但没内容，例如 -D" + LeaderboardFields.ID_PROPERTY + "=）";
        }
        if (".".equals(trimmed) || "..".equals(trimmed)) {
            return "id 是 " + trimmed + " —— 这是路径里的特殊分量，会被当成当前/上级目录";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean asciiLetter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            boolean digit = c >= '0' && c <= '9';
            if (!asciiLetter && !digit && c != '_' && c != '-' && c != '.') {
                return "id 含非法字符 " + c + "（U+" + String.format("%04X", (int) c)
                        + "）—— 只允许 [A-Za-z0-9_.-]（理由见 isLegalId 的 javadoc）";
            }
        }
        return null;
    }

    /**
     * 从系统属性取<b>覆盖值</b>（{@link LeaderboardFields#ID_PROPERTY}）；取不到 / 被安全管理器拒绝 ⇒ {@code null}。
     *
     * <p>★ 返回 {@code null} 现在<b>不代表"拒绝注册"</b>：它只表示"没给覆盖值" ⇒
     * {@link #plan} 会用用户裁定值 {@link LeaderboardFields#ID} 注册。
     */
    public static String leaderboardId() {
        try {
            return System.getProperty(LeaderboardFields.ID_PROPERTY);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================================
    // 二、★ 非纯：档案字段自检（把"榜恒空"从哑变成响）
    // ==================================================================================

    /**
     * ★ <b>档案里哪些战绩字段是「MGC 会静默读成 0」的</b>（空列表 = 全都健康）。
     *
     * <p>这就是「榜恒空」的解药：MGC 读不到只返回 {@code 0.0} 且不吭声，
     * 我们在装配期主动问一句并把结果打进日志。
     */
    public static List<String> unreadableArchiveFields() {
        try {
            return ArchiveFieldAccess.unreadableFields(
                    InfernalPlayerData.class, LeaderboardFields.ARCHIVE_FIELDS);
        } catch (Throwable t) {
            return List.copyOf(LeaderboardFields.ARCHIVE_FIELDS);
        }
    }

    /** 给启动日志用的一段话（字段齐了说"通过"，缺字段时点名缺口出处）。 */
    public static String describeArchiveReadiness() {
        List<String> unreadable = unreadableArchiveFields();
        if (unreadable.isEmpty()) {
            return "档案战绩字段自检通过：" + LeaderboardFields.ARCHIVE_FIELDS
                    + " 在 " + InfernalPlayerData.class.getSimpleName()
                    + " 上全部存在、public 且为数字（MGC 反射读得到，榜可以出数）";
        }
        return "档案缺战绩字段 " + unreadable + "（或不是 public / 不是数字）"
                + "，MGC 反射读到 0.0，排行榜必然是空的，且不会报错。"
                + " 需在 profile/InfernalPlayerData 补上这 " + unreadable.size()
                + " 个 public int 字段（docs/04 §16.13.6 的 G2；"
                + "该清单 MISSING_ARCHIVE_FIELDS 会同时指出来）";
    }

    // ==================================================================================
    // 三、★ 非纯：注册与刷新（都只告警，不抛）
    // ==================================================================================

    /**
     * ★ 装配期调用：注册榜单 + 立即刷一次。
     *
     * @return 是否真的注册成功（{@code false} ⇒ 已告警，调用方<b>什么都不用做</b>）
     */
    public static boolean register(Logger logger) {
        try {
            Plan plan = plan(leaderboardId());
            if (!plan.register()) {
                // ★ 告警（不是 info）：A4 之后**唯一**的拒绝原因是「运行期覆盖值非法」
                //   —— 那是运维写错了配置，必须显眼；「没给 id」已经不再是拒绝理由（用裁定值注册）
                logger.warning(TAG + "排行榜：" + plan.describe());
                return false;
            }
            logger.info(TAG + "排行榜：" + plan.describe());

            // ★ 先自检、再注册：让「榜能不能出数」这件事在启动日志里就有据可查
            //   （★ 可对账：字段齐了打印"通过"，缺字段才告警 —— 见 LeaderboardFieldsTest 的双向锁）
            List<String> unreadable = unreadableArchiveFields();
            if (unreadable.isEmpty()) {
                logger.info(TAG + describeArchiveReadiness());
            } else {
                logger.warning(TAG + describeArchiveReadiness());
            }

            MCZJUGameCore.getLeaderboardManager()
                    .registerLeaderboard(plan.id(), InfernalClashLeaderboard.class);
            logger.info(TAG + "已注册排行榜 id=" + plan.id() + "（展示实体由管理员用 "
                    + "/mgcop leaderboard create " + plan.id() + " <实体id> 创建；代码里不生成实体）");

            // ★ docs/08 §7：MGC 自动刷新是 12000 tick ≈ 10 分钟一轮 ⇒ 注册后立刻刷一次
            refresh(logger);
            return true;
        } catch (Throwable t) {
            logger.warning(TAG + "注册排行榜失败，本次不启用榜单（不影响对局与其它功能）：" + t);
            if (t instanceof IllegalStateException) {
                logger.warning(TAG + " 原因像是「同一个 id 注册了两次」（MGC 会抛 IllegalStateException）"
                        + "—— 检查是否有第二处 registerLeaderboard 调用");
            }
            return false;
        }
    }

    /**
     * ★ 刷一次榜（{@code docs/08} §7：每局结束必须自己刷，否则"破纪录不立马可见"）。
     *
     * <p>⚠️ MGC 的 {@code refresh(Class)} 在<b>未注册时抛 {@code RuntimeException}</b>
     * ⇒ 必须兜住（这就是本方法存在的主要理由）。
     */
    public static boolean refresh(Logger logger) {
        try {
            MCZJUGameCore.getLeaderboardManager().refresh(InfernalClashLeaderboard.class);
            return true;
        } catch (Throwable t) {
            logger.warning(TAG + "刷新排行榜失败，维持上一版内容（不影响对局）：" + t);
            logger.warning(TAG + " 未注册时 MGC 的 refresh(Class) 本来就会抛 —— "
                    + "先看上面那条「注册排行榜」的日志");
            return false;
        }
    }

    // ==================================================================================
    // 四、★ 非纯：读档案（唯一一处"枚举所有人"）
    // ==================================================================================

    /**
     * ★ 把「榜上显示名 → 统计」整份读出来（给 {@link InfernalClashLeaderboard#fetchEntries()} 用）。
     *
     * <p>失败方向：<b>任何异常 ⇒ 空表 + 告警</b>。半份数据比空榜更糟（榜会看起来"少了几个人"），
     * 所以这里宁可空。
     */
    public static Map<String, LeaderboardStats> snapshotOfArchives(Logger logger) {
        Map<String, LeaderboardStats> snapshot = new LinkedHashMap<>();
        try {
            List<InfernalPlayerData> all = MCZJUGameCore.getPlayerDataManager()
                    .getAllPlayerData(InfernalPlayerData.class);
            if (all == null) {
                logger.warning(TAG + "读排行榜数据：MGC 返回 null，本次按空榜处理");
                return snapshot;
            }
            int skipped = 0;
            for (InfernalPlayerData data : all) {
                if (data == null) {
                    skipped++;
                    continue;
                }
                String name = displayNameOf(data.getPlayerID());
                if (name == null || name.isBlank()) {
                    skipped++;   // 名字取不到 ⇒ 榜上显示不出来（IG 同样跳过）
                    continue;
                }
                snapshot.put(name, ArchiveFieldAccess.readStats(data));
            }
            // ★ 带数量的日志（铁律 10 的"可对账"）：收录了几个、跳过了几个
            logger.info(TAG + "排行榜数据：收录 " + snapshot.size() + " 名玩家"
                    + (skipped > 0 ? "，跳过 " + skipped + " 条（档案为 null / 名字取不到）" : ""));
        } catch (Throwable t) {
            logger.warning(TAG + "读排行榜数据失败，本次返回空榜（不影响对局）：" + t);
            snapshot.clear();
        }
        return snapshot;
    }

    /** UUID 字符串 → 显示名（★ 只按 UUID 查<b>一个人</b>，不遍历在线玩家 —— 铁律 10）。 */
    private static String displayNameOf(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(playerId);
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            return player == null ? null : player.getName();
        } catch (IllegalArgumentException | SecurityException e) {
            return null;
        }
    }

    // ==================================================================================
    // 五、★ 非纯：记录一局结果（唯一入口）
    // ==================================================================================

    /**
     * ★ <b>记录一局结果</b>（对局收尾侧唯一应当调用的入口）。
     *
     * <p>顺序：{@link #recordResult} 算 + 写 → 成功才 {@link #refresh}。
     * 两步都失败方向一致：<b>只告警</b>。
     *
     * @param playerId 玩家 UUID（不可为 {@code null}）
     * @param kind     跟谁打的（人机局会被 {@link LeaderboardStats#record} 直接拒绝 —— 不必调用方先过滤）
     * @param outcome  结果
     * @return 战绩是否真的改了并刷新了榜
     */
    public static boolean onMatchFinished(UUID playerId, LeaderboardStats.MatchKind kind,
                                           LeaderboardStats.Outcome outcome, Logger logger) {
        if (!recordResult(playerId, kind, outcome, logger)) {
            return false;
        }
        refresh(logger);
        return true;
    }

    /**
     * ★ <b>只记录、不刷榜</b>（{@link #onMatchFinished} 的下半层；单独暴露是为了可测与可组合）。
     *
     * <p>规则一条都不在这里：算由纯核心 {@link LeaderboardStats#record} 做，
     * 写由 {@link ArchiveFieldAccess#writeStats} 做（它"先验后写"，缺字段就<b>一个字段都不写</b>）。
     *
     * @return 是否真的写进了档案
     */
    public static boolean recordResult(UUID playerId, LeaderboardStats.MatchKind kind,
                                       LeaderboardStats.Outcome outcome, Logger logger) {
        try {
            if (playerId == null) {
                logger.warning(TAG + "记录战绩被拒绝：playerId 为 null");
                return false;
            }
            InfernalClashPlugin plugin = InfernalClashPlugin.getInstance();
            if (plugin == null) {
                logger.warning(TAG + "记录战绩被拒绝：插件实例不可用（离线 / 关服中）");
                return false;
            }
            // ★ 走 ProfileStore（唯一门面）拿档案；raw 是只读的，拿不到 ⇒ null
            InfernalPlayerData data = plugin.profileStore().raw(playerId);
            if (data == null) {
                logger.warning(TAG + "记录战绩被跳过：" + playerId
                        + " 的内存档案不存在（从未上线过 / 未注册 gameId），不为记战绩而造档案");
                return false;
            }

            LeaderboardStats current = ArchiveFieldAccess.readStats(data);
            LeaderboardStats.Update update = current.record(kind, outcome);
            if (!update.applied()) {
                // ★ 「人机局不计 / 平局待裁定」走这里：**INFO 而不是 WARNING**（这不是故障）
                logger.info(TAG + "战绩未更新（" + playerId + "）：" + update.reason());
                return false;
            }

            ArchiveFieldAccess.WriteResult write = ArchiveFieldAccess.writeStats(data, update.next());
            if (!write.applied()) {
                logger.warning(TAG + "战绩写入被拒绝（" + playerId + "）：" + write.reason()
                        + "；缺字段 " + write.missingFields()
                        + "，榜仍是空的，但至少这条日志能说清为什么");
                return false;
            }

            // ★ 缺这一行 ⇒ 上面四个字段的赋值**不落盘**（ProfileStore / InfernalPlayerData 的口径）
            data.setModified(true);
            logger.info(TAG + "战绩已更新（" + playerId + "）：" + current + "，" + update.next()
                    + "；落盘走 MGC 自动保存（本轮不调 savePlayerData，见 LeaderboardWiring 类注释）");
            return true;
        } catch (Throwable t) {
            logger.warning(TAG + "记录战绩失败，忽略（绝不影响对局结束流程）：" + t
                    + "；gameId=" + safeGameId());
            return false;
        }
    }

    /** 取 gameId（异常安全；只用于日志）。 */
    private static String safeGameId() {
        try {
            return InfernalClashGame.ID;
        } catch (Throwable t) {
            return "?";
        }
    }
}
