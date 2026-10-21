package io.mczju.infernalclash.profile;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import com.github.mczjuops.mczjugamecore.player.data.AbstractPlayerData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * ★ 把 MGC 档案读写包成一个小门面（**唯一**碰 MGC playerData 的地方）。
 *
 * <h2>它为什么存在</h2>
 * 局外成长（金币 / 尘 / 已拥有卡 / 预设）要跨重启保留，唯一可用的通道是
 * MGC 的 {@code player.data.PlayerDataManager} + {@link InfernalPlayerData}
 * （另一套 {@code ProfileStorageManager} 只存装备栏与经验，见 {@link InfernalPlayerData} 类注释）。
 * 但那条通道有一串**容易写错且写错了不报错**的细节（参数顺序、{@code isModified}、
 * 立即落盘、拿不到对象时怎么办）—— 全部收敛到这一个类里，
 * 其它任何类**不得**直接调 {@code MCZJUGameCore.getPlayerDataManager()}。
 *
 * <h2>★★ 实测：{@code getPlayerData} 的两个重载<b>参数顺序不一样</b>（照抄会写反）</h2>
 * 用 {@code javap -p -v} 读 MGC 1.1.2 的 {@code LocalVariableTable}（jar 里保留了调试信息）
 * 得到的是**权威签名**：
 * <pre>
 *   public AbstractPlayerData getPlayerData(String gameId, String playerId)   ← ★ gameId 在前！
 *   public &lt;T&gt; T            getPlayerData(String playerId, Class&lt;T&gt; type)     ← 这个 playerId 在前
 * </pre>
 * 依据（{@code javap -p -c} 反汇编）：
 * <ul>
 *   <li>{@code getPlayerData(String, String)} 第一句是 {@code pDataMap.get(arg1)}，
 *       而 {@code pDataMap} 的键是 <b>gameId</b>（{@code addEmptyData(gameId)} /
 *       {@code addPlayerData(gameId, playerId, data)} 都按它建桶）⇒ <b>arg1 只能是 gameId</b>；</li>
 *   <li>{@code getPlayerData(String, Class)} 找到 gameId 之后，调的是
 *       {@code getPlayerData(gameId, playerId)}（字节码 offset 80~83：{@code aload_3, aload_1}）。</li>
 * </ul>
 * ⇒ 本类一律用 {@code getPlayerData(gameId, playerId.toString())}。
 * 写成 {@code (playerId, gameId)} 的**现场表现**是每次都抛
 * {@code RuntimeException}（{@code pDataMap.get(uuid字符串)} 为 null），
 * 被 {@link #load(UUID)} 吃掉后表现成「进度永远是 0」—— 不报错、不崩，只是数据没了。
 *
 * <h2>★★ 实测：{@code ensure} 到底能不能为「从没上线过的 UUID」造出档案？—— <b>能</b></h2>
 * 依据（{@code javap -p -c} 反汇编 {@code PlayerDataManager.getPlayerData(String, String)}）：
 * <pre>
 *   map = pDataMap.get(gameId);
 *   if (map == null) throw new RuntimeException("…未注册…");        // ← 桶不存在时才抛
 *   data = map.get(playerId);
 *   if (data == null) {
 *       Class c = registeredPlayerData.get(gameId);                 // ← 注册过就一定非 null
 *       if (c == null) return null;                                 // ← 只有「没注册」才返回 null
 *       data = c.getDeclaredConstructor().newInstance();            // ★ 反射 new 一个空的
 *       data.setGameID(gameId); data.setPlayerID(playerId);         // ← ★ 唯一能调这两个
 *                                                                   //   package-private setter 的地方
 *       map.put(playerId, data);
 *   }
 *   return data;
 * </pre>
 * 也就是说：<b>只要本插件对 gameId 调过 {@code registerPlayerData}，MGC 就会为任意
 * playerId 字符串（含从未登录过的 UUID）反射造一个档案对象并放进内存</b> ——
 * 这正是「不需要真人客户端就能验证跨重启」的基础。
 * 而且这个对象的 {@code playerID / gameID} 是 MGC 自己设的（{@code setPlayerID} 是
 * package-private，子插件根本调不了）⇒ 它的 {@code getFilePath()} 是对的：
 * {@code plugins/MCZJUGameCore/player_data/<gameId>/<playerId>.json}。
 * <p>⚠️ 两个**实测到的**前提，缺一个 {@code ensure} 就会失败（本类会告警 + 返回 false，
 * 绝不假装成功）：
 * <ol>
 *   <li>gameId 必须已经 {@code registerPlayerData} 过（没注册 ⇒ {@code registeredPlayerData.get}
 *       是 null ⇒ MGC 返回 null）；</li>
 *   <li>{@code pDataMap} 里必须有这个 gameId 的**桶**。桶由注册时的
 *       {@code loadAllPlayerData → JsonPlayerDataLoader} 建：
 *       <b>目录不存在</b> ⇒ {@code addEmptyData(gameId)} 建桶；
 *       <b>目录里至少有 1 个 .json</b> ⇒ 逐个 {@code addPlayerData} 建桶；
 *       ★ 但「目录存在且<b>空</b>」时它既不建桶也不报错 ⇒ 第一次 {@code getPlayerData}
 *       会抛 {@code RuntimeException}（MGC 的这条边界，本类只能告警 + 返回 fresh）。</li>
 * </ol>
 *
 * <h2>★ 拿不到档案时<b>绝不自造</b>一个 {@code AbstractPlayerData} 塞进 MGC</h2>
 * {@code AbstractPlayerData.setPlayerID / setGameID} 是 <b>package-private</b>（{@code javap} 实测），
 * 子插件硬塞进去的对象 playerID / gameID 都是 {@code null} ⇒ {@code save()} 时算出
 * {@code .../player_data/null/null.json} 之类的垃圾路径，
 * 或者干脆永远不进 MGC 的落盘循环（{@code savePlayerData} 是按
 * {@code pDataMap} 的桶去找人的）—— 那是**最糟的失败方式**：
 * 它看起来成功了，重启后数据全没了。⇒ 本类的失败方向是
 * <b>返回 fresh + 一条 warning</b>（不扣不发，也绝不假装写成功）。
 *
 * <h2>★ 写回必须「{@code applyFields} + 立即 {@code savePlayerData}」</h2>
 * <ul>
 *   <li>直接改 public 字段<b>不会</b>落盘 —— MGC 靠 {@code isModified()} 决定要不要写盘
 *       （{@link InfernalPlayerData#applyFields} 内部会 {@code setModified(true)}）；</li>
 *   <li>MGC 有 12000 tick 级的自动保存，但**跨重启验证**不能等它 ⇒ 每次写回都显式
 *       {@code savePlayerData(playerId)} 立即落盘（实测 {@code savePlayerData} 只写
 *       {@code isModified()} 为真的对象，所以两步缺一不可）。</li>
 * </ul>
 * <p>⚠️ {@code JsonMGCSerializable.save()} 在 IO 失败时<b>抛 {@code RuntimeException}</b>
 * （{@code javap -c} 实测）⇒ {@link #save(UUID, PlayerProgress)} 必须
 * {@code catch (Throwable)}：<b>存档失败绝不能打断对局</b>，只告警 + 返回。
 *
 * <h2>★ 读失败 / 写失败的失败方向（刻意不对称，别"顺手统一"）</h2>
 * <table border="1">
 *   <caption>失败方向</caption>
 *   <tr><th>方法</th><th>参数非法 / 档案拿不到</th><th>为什么</th></tr>
 *   <tr><td>{@link #load(UUID)}</td><td>{@code playerId} 为 {@code null} ⇒ <b>抛</b>
 *       {@code IllegalArgumentException}；档案缺失/读异常 ⇒ <b>返回 fresh + 告警</b></td>
 *       <td>前者是调用方的编程错误（fail-fast，读不会写坏数据）；后者是运行时现实
 *       （新玩家 / 坏档案），必须让玩家进得了游戏（{@code docs/04} §16.11.3）</td></tr>
 *   <tr><td>{@link #save(UUID, PlayerProgress)}</td><td>任何问题（含 {@code null}）
 *       ⇒ <b>告警 + 返回，绝不抛</b></td>
 *       <td>存档失败不能打断对局；而且写坏数据的代价比读不到高得多</td></tr>
 * </table>
 *
 * <h2>★ R44「基础档初始全部拥有」的接线（{@code docs/21} §1 的那条缺口）</h2>
 * <b>哪里发</b>：{@link #load(UUID)} 读出一份档案之后、返回之前 —— 那是本项目<b>所有</b>
 * 读取玩家的唯一收口（{@code ProgressMapper.read} 在 {@code src/main} 里只有这一个调用点），
 * 所以「第一次进服读到空白档案」这件事无论被谁触发（开界面 / 组卡组 / 调试指令）都会经过它。
 * <p><b>发什么</b>：{@link R44BasicGrants#grantBasicIfBlank}（纯函数）拿到"卡池里
 * {@code rarity == BASIC} 的卡 id"逐个 {@code unlock}；<b>装哪份清单由装配层注入</b>
 * （{@link #bindBasicCardIds(Supplier)}），本类<b>不</b> import 卡池、更不硬编码卡名 ——
 * 「哪些卡是基础档」的事实来源只有 {@code card/CardCatalog} 一处。
 * <p><b>只在真的发放时写盘</b>：{@link R44BasicGrants#grantBasicIfBlank} 没有变化时返回
 * <b>同一个实例</b> ⇒ 这里用 {@code ==} 判，不放发就不调 {@link #save}（不做"每次进服都写盘"
 * 那种无谓 IO，也就不会把只读的进服流程变成写操作）。
 * <p><b>失败方向（★ 绝不抛）</b>：没注入清单 / 清单为空 / {@code source.get()} 抛异常 /
 * 卡 id 非法 / 写盘失败 ⇒ <b>跳过 + 一条告警，返回原样的进度</b>。
 * 「拿不到卡池」时<b>绝不</b>写一份硬编码的基础卡名兜底（那是第二处事实来源）。
 * 跳过是<b>可自愈</b>的：档案保持空白 ⇒ 下一次读到它还会再试一次。
 *
 * <h2>★ 不查世界（铁律 10）</h2>
 * 本类<b>只</b>按 UUID 读写档案，不出现
 * {@code getOnlinePlayers / getEntities / getLivingEntities / getWorlds} ——
 * 「给谁发/查什么」一律由调用方把 UUID 递进来。
 *
 * <h2>★ 离线可测的部分</h2>
 * 真正读写 MGC 的路径<b>需要实机</b>（要一个起着的 MGC 与数据目录）⇒
 * <b>实机验证见 {@code docs/12} 与 {@code docs/11} §S6</b>「跨重启保留」。
 * 能离线断言的部分已做成静态纯函数：{@link #parseOwned(String)}（含 ≥6 项断言的
 * {@code ProfileStoreTest}）、{@link #withOwned(PlayerProgress, String)}、
 * {@link #validateSave(UUID, PlayerProgress)}，以及构造器的参数校验。
 */
public final class ProfileStore {

    /** MGC 游戏 id（本插件固定的 {@code infernalclash}，由装配层传进来）。 */
    private final String gameId;

    /** 只用来打日志（告警必须能落到服主看得见的地方，绝不静默）。 */
    private final Plugin plugin;

    /**
     * ★ R44 基础档卡 id 的**来源**（装配层注入；见类注释的同名小节）。
     *
     * <p>{@code null} = 还没接线 ⇒ 拿不到清单 ⇒ 跳过 + 告警（<b>绝不</b>猜卡名）。
     * 之所以是 {@link Supplier} 而不是一份快照：{@code /incl debug reload} 会重建卡池，
     * 每次读实时取才不会拿着一份过期的清单发卡。
     */
    private Supplier<Collection<String>> basicCardIds;

    /** 「拿不到基础档清单」的告警只打一次 —— 否则新玩家每开一次界面就刷一条（真告警会被淹掉）。 */
    private boolean basicListWarned;

    /**
     * ★ 先查 {@code gameId}、再查 {@code plugin}：两条判据的顺序**故意**这样排
     * （两个都是非法入参，谁先报都行）—— 好处是「{@code plugin} 为 null」这条也能在
     * **没有 {@code Plugin} 实例**的离线单测里断言到（铁律：不伪造 Bukkit）。
     *
     * @param plugin 本插件实例（只用于 {@code getLogger()}；不可为 {@code null}）
     * @param gameId MGC 的游戏 id（不可为 {@code null} / 空白）
     * @throws IllegalArgumentException 任一参数非法（把错误停在装配期，而不是第一次存档时）
     */
    public ProfileStore(Plugin plugin, String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为 null / 空白：实际 \"" + gameId
                    + "\"（它必须是 registerPlayerData 用过的那个 id）");
        }
        if (plugin == null) {
            throw new IllegalArgumentException("plugin 不能为 null（告警要写进它的日志，"
                    + "静默的存档失败等于没有存档）");
        }
        this.plugin = plugin;
        this.gameId = gameId;
    }

    // ================================================================== 接线（R44）

    /**
     * ★ <b>装配层注入「基础档卡 id 的来源」</b>（R44；{@code docs/21} §1）。
     *
     * <p>由<b>装配层</b>（{@code InfernalClashPlugin} 的 {@code onEnable}）在卡池加载成功后调用，
     * 传的就是「卡池里 {@code rarity == BASIC} 的卡 id」这个实时读数。★ 实际接线长这样
     * （{@code InfernalClashPlugin#basicCardIdsFromPool}：<b>每次调用都重新读 {@code cardCatalog} 字段</b>，
     * 挑 BASIC 那一步是纯函数 {@link R44BasicGrants#basicCardIds(java.util.Collection)}）：
     * <pre>{@code
     * store.bindBasicCardIds(() -> R44BasicGrants.basicCardIds(cardCatalog.cards().values()));
     * }</pre>
     * ★ 为什么清单<b>不能</b>由本类自己取：{@code ProfileStore} 不认识卡池（那是第二处事实来源的
     * 起点），而「哪些卡是基础档」只有 {@code card/CardCatalog} 知道 ——
     * 与 {@link PlayerProgress#fresh} 的注释「由装配层用卡池里的 BASIC 卡 id 补上」是同一条口径。
     *
     * <p>★ 用 {@link Supplier} 而非快照：{@code /incl debug reload} 会重建卡池，
     * 每次读时实时取才不会拿着一份过期的清单发卡。
     *
     * <p>★ <b>不抛</b>：{@code source == null} 只告警并<b>保持原绑定不变</b>
     * （「装配层手滑写了个 null」不该让 {@code onEnable} 崩掉；而"没接线"这件事会在
     * 第一次读到空白档案时以另一条告警现形）。
     *
     * @param source 返回基础档卡 id 的来源；{@code null} ⇒ 告警 + 忽略
     */
    public void bindBasicCardIds(Supplier<Collection<String>> source) {
        if (source == null) {
            warn("bindBasicCardIds(null) 被忽略：基础档卡 id 的来源不能为 null"
                    + "（要解绑请显式说明理由；R44 的发放会因此被跳过）");
            return;
        }
        this.basicCardIds = source;
        // ★ 接线成功也把「读到几张」一起印出来：与本项目的「卡池校验通过：… BASIC 11 / …」
        //   那行直接对账（对不上就说明接线接错了或卡池变了）
        info("R44 基础档来源已注入：" + describeBasicSource(source)
                + "；空白档案（第一次进服）会按它发放基础卡");
    }

    // ================================================================== 读

    /**
     * 读某玩家的进度。
     *
     * <p>★ <b>档案不存在 / 读取异常 ⇒ 返回 {@link PlayerProgress#fresh(UUID)}（并告警，
     * 绝不抛给调用方）</b>。档案不在内存时先 {@link #ensure} 一次再取
     * （MGC 会为没上线过的 UUID 反射造对象 —— 见类注释的实测结论）。
     *
     * <p>★ <b>R44：读到的档案若是空白的（第一次进服）⇒ 本次读取会顺带"拥有"基础档全部卡
     * 并立即落盘</b>（见类注释的同名小节）。这是本插件<b>唯一</b>的读取收口，所以
     * 「谁先读到」不影响结果；已存在的档案在这里<b>一个字段都不会被改</b>。
     *
     * @param playerId 玩家 UUID（不可为 {@code null}）
     * @throws IllegalArgumentException {@code playerId} 为 {@code null}（调用方 bug，fail-fast）
     */
    public PlayerProgress load(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId 不能为 null（进度必须能归属到一个玩家）");
        }
        InfernalPlayerData data = dataOrEnsure(playerId, null);
        if (data == null) {
            warn("拿不到 " + playerId + " 的档案（MGC 里没有这个 gameId 的数据桶，例如"
                    + " register-game=false / 目录存在但为空），本次按新玩家的空白进度处理；"
                    + "此时任何修改都不会落盘");
            // ★ 这里**不**发放基础卡：发了也落不了盘（本方法上面那句已经说了）⇒
            //   那是"看起来成功了、重启后什么都没了"的最糟失败方式。档案仍是空白的
            //   ⇒ 下次真的拿到档案时会照常发放（自愈）。
            return PlayerProgress.fresh(playerId);
        }

        PlayerProgress progress;
        try {
            ProgressMapper.ReadResult result = ProgressMapper.read(playerId, data.toFields());
            if (result.wasRepaired()) {
                // 坏档案被修成一致的：必须说出来，否则"修好了但没人知道"是一条隐形损坏通道
                warn(playerId + " 的档案被读时修复（" + result.notes().size() + " 处）：");
                for (String note : result.notes()) {
                    warn(" · " + note);
                }
            }
            progress = result.progress();
        } catch (Throwable t) {
            warn("读取 " + playerId + " 的档案失败，本次按新玩家的空白进度处理（进度不会被清零）：" + t);
            return PlayerProgress.fresh(playerId);
        }

        // ★ R44：空白档案（第一次进服）⇒ 自动"拥有"基础档的全部卡并立即落盘。
        //   ⚠️ 刻意放在上面那个 try 之外：发放失败绝不能把一份**读到了的**档案降级成空白。
        return grantBasicIfBlank(playerId, progress);
    }

    /** 同 {@link #load(UUID)}，入参是玩家实体（只读它的 UUID）。 */
    public PlayerProgress load(Player player) {
        if (player == null) {
            throw new IllegalArgumentException("player 不能为 null（要读谁请给一个在线玩家实体）");
        }
        return load(player.getUniqueId());
    }

    /**
     * ★ R44：{@code progress} 是<b>空白档案</b> ⇒ 发放基础档卡并<b>立即落盘</b>；
     * 否则（已存在的档案 / 没接线 / 出任何问题）<b>原样返回</b>。
     *
     * <h2>★ 只剩"要不要写盘"这一件事留在这里</h2>
     * 判据（{@link R44BasicGrants#isBlankProfile}）与发放（
     * {@link R44BasicGrants#grantBasicIfBlank}）都是<b>纯函数</b>，能离线单测；
     * 这个方法只做三件边界的事：取清单 → 调纯函数 → <b>真的发了才</b> {@link #save}。
     *
     * <h2>★ 失败方向：跳过 + 告警，绝不抛（也绝不拿硬编码卡名兜底）</h2>
     * <ul>
     *   <li>没注入清单（装配层没调 {@link #bindBasicCardIds}）⇒ 跳过；</li>
     *   <li>清单为 {@code null} / 空 ⇒ 跳过（"卡池里一张基础卡都没有"是运行时现实）；</li>
     *   <li>清单里有非法卡 id ⇒ 纯函数抛，这里吃掉 ⇒ 跳过（★ 输入一字未动，不会"发了一半"）；</li>
     *   <li>写盘失败 ⇒ {@link #save} 自己只告警（本方法照常返回内存里的发放结果）；</li>
     *   <li>任何其它异常 ⇒ 跳过。<b>跳过是可自愈的</b>：档案仍是空白 ⇒ 下次读到它再试。</li>
     * </ul>
     *
     * @return 发放后的进度；<b>没有发放就返回传进来的那个实例</b>（{@code ==} 可判）
     */
    private PlayerProgress grantBasicIfBlank(UUID playerId, PlayerProgress progress) {
        try {
            // ★ 判据只有一处（R44BasicGrants）：已存在的档案在这里就被挡下，一个字段都不动
            if (!R44BasicGrants.isBlankProfile(progress)) {
                return progress;
            }
            Supplier<Collection<String>> source = this.basicCardIds;
            if (source == null) {
                warnBasicListOnce("R44 基础档未接线：装配层没有注入卡 id 来源，本次跳过发放"
                        + "（新玩家会一张卡都没有）。修法：在 onEnable 里调 profileStore"
                        + ".bindBasicCardIds(() -> R44BasicGrants.basicCardIds("
                        + "cardCatalog.cards().values()))（见 docs/21 §1）");
                return progress;
            }
            Collection<String> ids = source.get();
            if (ids == null || ids.isEmpty()) {
                warnBasicListOnce("R44 基础档清单为空（卡池里没有 rarity == BASIC 的卡？）"
                        + "，本次跳过发放；绝不写一份硬编码的卡名兜底（那是第二处事实来源）");
                return progress;
            }
            PlayerProgress granted = R44BasicGrants.grantBasicIfBlank(progress, ids);
            if (granted == progress) {
                // 纯函数说"没有变化" ⇒ 不写盘（既省 IO，也不把只读的读档案流程变成写操作）
                return progress;
            }
            save(playerId, granted);
            info("R44：为 " + playerId + " 发放基础档 " + granted.owned().size()
                    + " 张卡（空白档案，第一次进服；来源 = 装配层注入的卡池 BASIC 档）");
            return granted;
        } catch (Throwable t) {
            warn("R44 基础档发放失败，本次跳过（这份档案一字未改，下次读到它还会再试）：" + t);
            return progress;
        }
    }

    /** 「拿不到基础档清单」的告警只打一次（否则新玩家每开一次界面就刷一条，真告警会被淹掉）。 */
    private void warnBasicListOnce(String message) {
        if (basicListWarned) {
            return;
        }
        basicListWarned = true;
        warn(message);
    }

    // ================================================================== 写

    /**
     * 写回并落盘（★ <b>立即</b> {@code savePlayerData}，不等自动保存 —— 跨重启验证要靠它）。
     *
     * <p>★ 任何失败（MGC 拿不到对象 / {@code save()} 抛 IO 异常 / 参数非法）
     * ⇒ <b>告警 + 返回，绝不抛</b>：存档失败不能打断对局。
     *
     * @param playerId 玩家 UUID（不可为 {@code null}）
     * @param progress 要写回的进度（不可为 {@code null}，且 {@code playerId} 必须与它一致）
     */
    public void save(UUID playerId, PlayerProgress progress) {
        String problem = validateSave(playerId, progress);
        if (problem != null) {
            warn("写回被拒绝：" + problem);
            return;
        }
        try {
            InfernalPlayerData data = dataOrEnsure(playerId, null);
            if (data == null) {
                warn("写回 " + playerId + " 失败：MGC 里拿不到可写的数据对象，"
                        + " 这次修改没有落盘（绝不硬塞一个永远不保存的对象进 MGC）："
                        + progress.describe());
                return;
            }
            ProgressMapper.Fields fields = data.toFields();
            ProgressMapper.apply(progress, fields);
            data.applyFields(fields);   // ★ 内部 setModified(true)，少了它下面那行不会写盘

            MCZJUGameCore.getPlayerDataManager().savePlayerData(playerId.toString());
        } catch (Throwable t) {
            warn("写回 " + playerId + " 的档案失败（不影响对局，但这次修改没有落盘）：" + t);
        }
    }

    /** 同 {@link #save(UUID, PlayerProgress)}，入参是玩家实体。 */
    public void save(Player player, PlayerProgress progress) {
        if (player == null) {
            warn("写回被拒绝：player 不能为 null");
            return;
        }
        save(player.getUniqueId(), progress);
    }

    // ================================================================== 裸对象 / 造档案

    /**
     * 把 MGC 的数据对象取出来（<b>只读</b>：拿不到 ⇒ 返回 {@code null}）。
     *
     * <p>★ 用 {@code getAllPlayerData(InfernalPlayerData.class)} 按 {@code playerID} 找，
     * <b>不</b>用 {@code getPlayerData(...)} —— 后者会**顺手把档案造出来**
     * （见类注释的实测），那会让「档案到底存不存在」这个问题永远答"存在"。
     *
     * <p>调试指令要用的就是「存不存在」：{@code /incl debug profile show <uuid>}。
     */
    public InfernalPlayerData raw(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        return inMemory(playerId);
    }

    /**
     * ★ 给「还没上线过」的 UUID 造一份档案（调试 / 验证用；返回是否成功）。
     *
     * <p><b>实测结论：能</b> —— 见类注释（{@code PlayerDataManager.getPlayerData(gameId, playerId)}
     * 在 gameId 已注册、数据桶已存在时，会反射 {@code newInstance()} 并
     * {@code setGameID / setPlayerID} 后放进内存）。这也是「不需要真人客户端就能验跨重启」的基础：
     * 先 {@code ensure}，再 {@link #save}，重启后用 {@code show} 看值还在不在。
     *
     * <p>★ 造出来的是**空档案且 {@code isModified()==false}** ⇒ 它自己不会被写盘；
     * 要落盘必须走 {@link #save}（那才会 {@code applyFields} → {@code setModified(true)}）。
     *
     * <p>★ 失败（未注册 / 没有数据桶 / 反射失败）⇒ 告警 + 返回 {@code false}，
     * <b>绝不自造对象硬塞进 MGC</b>（理由见类注释）。
     *
     * @param playerId   玩家 UUID（不可为 {@code null}）
     * @param playerName 只用于日志的名字，可为 {@code null} / 空
     *                   （★ 档案里**没有**名字字段：{@link InfernalPlayerData} 的字段是定死的，
     *                   不许为了"顺手存个名字"改它）
     */
    public boolean ensure(UUID playerId, String playerName) {
        if (playerId == null) {
            warn("ensure 被拒绝：playerId 不能为 null");
            return false;
        }
        if (inMemory(playerId) != null) {
            return true;   // 已在内存（含注册时从磁盘 load 进来的）
        }
        String who = (playerName == null || playerName.isBlank())
                ? playerId.toString() : playerName + "（" + playerId + "）";
        try {
            AbstractPlayerData created = MCZJUGameCore.getPlayerDataManager()
                    .getPlayerData(gameId, playerId.toString());
            if (created == null) {
                warn("ensure 失败：" + who + " 在 MGC 里造不出档案 —— gameId=" + gameId
                        + " 没有注册过（检查 register-game / registerPlayerData 是否真的执行了）");
                return false;
            }
            if (!(created instanceof InfernalPlayerData)) {
                warn("ensure 拿到的对象类型不对：" + created.getClass().getName()
                        + "（期望 " + InfernalPlayerData.class.getName() + "），注册的是别的类？");
                return false;
            }
            info("已为 " + who + " 造出内存档案（MGC 反射实例化；尚未落盘，"
                    + "写回请走 save —— 它会 setModified + 立即 savePlayerData）");
            return true;
        } catch (Throwable t) {
            warn("ensure 失败：" + who + "，" + t
                    + "（常见原因：gameId 未注册，或 player_data/" + gameId
                    + "/ 目录存在但为空，MGC 没建数据桶）");
            return false;
        }
    }

    /** 一行摘要（日志 / 指令用）。 */
    public String describe() {
        return "MGC 档案门面：gameId=" + gameId
                + "，注册类=" + InfernalPlayerData.class.getSimpleName()
                + "，落盘 plugins/MCZJUGameCore/player_data/" + gameId + "/<uuid>.json"
                + "（写入 = applyFields(setModified) + savePlayerData 立即落盘）"
                + "，R44 基础档来源=" + describeBasicSource(this.basicCardIds);
    }

    /**
     * ★ <b>纯</b>：把「R44 基础档来源」<b>当前的真实状态</b>写成半句话
     * （启动日志那句 {@code R44 基础档来源=…} 的取值就在这里）。
     *
     * <h2>为什么必须实时读、而不是写死「已注入」</h2>
     * 2026-10-15 的实机日志打的是 {@code R44 基础档来源=★ 未接线（会跳过发放）} ——
     * 那是<b>真话</b>（装配层确实没接线），但旧实现只会在这句与「已注入」之间二选一，
     * 于是「接线了、可是卡池里 BASIC 是 0 张」这种情况<b>看不出来</b>。
     * 现在这里连<b>张数</b>一起报出来，与 {@code 卡池校验通过：51 张卡 / BASIC 11 / …} 那行
     * <b>可以直接对账</b>（对不上就是卡池或接线出了问题）。
     *
     * <p>★ 也正因为要报"张数"，这里<b>每次调用都问一遍来源</b>：
     * 来源是 {@code Supplier} 而不是快照（{@code /incl debug reload} 会重建卡池）⇒
     * 报出来的永远是<b>此刻</b>的读数。
     *
     * <p>★ <b>绝不抛</b>（它只用来打日志）：来源为 {@code null} / 返回 {@code null} /
     * 返回空 / 自己抛异常 ⇒ 各给一句说清后果的中文（"...会跳过发放"），
     * 而不是把异常从 {@code onEnable} 里带出去。
     *
     * @param source 装配层注入的来源；{@code null} = 没接线
     * @return 例如 {@code 卡池（BASIC 11 张）} / {@code ★ 未接线（会跳过发放）}
     */
    public static String describeBasicSource(Supplier<Collection<String>> source) {
        if (source == null) {
            return "未接线（会跳过发放）";
        }
        try {
            Collection<String> ids = source.get();
            if (ids == null || ids.isEmpty()) {
                return "卡池（BASIC 0 张，会跳过发放）";
            }
            return "卡池（BASIC " + ids.size() + " 张）";
        } catch (Throwable t) {
            // 读卡池失败只是「这次读不到」：照实说出来，并写明后果（跳过发放），不抛
            return "卡池读取失败（会跳过发放）：" + t;
        }
    }

    // ================================================================== 纯函数（离线可测）

    /**
     * ★ <b>纯</b>：把逗号分隔的卡 id 串解析成<b>保序</b>的集合。
     *
     * <ul>
     *   <li>{@code null} / 空串 / 只有逗号 ⇒ 空集合（不是 null）；</li>
     *   <li>去首尾空白（{@code " a , b "} ⇒ {@code [a, b]}）；</li>
     *   <li>丢空项（{@code "a,,b,"} ⇒ {@code [a, b]}）；</li>
     *   <li>重复项只保留**第一次出现**的位置（{@code "a,b,a"} ⇒ {@code [a, b]}）——
     *       保序的理由与 {@link PlayerProgress} 一致：同输入必须同结果（同种子同落盘顺序）；</li>
     *   <li>返回值是 {@link LinkedHashSet}（★ 不用 {@code Set.of} / {@code Set.copyOf}：
     *       它们的迭代顺序是随机的）。</li>
     * </ul>
     *
     * <p>★ 它<b>不</b>校验卡 id 是否存在（那是卡池 {@code card/CardCatalog} 的事，
     * 而本类必须能在普通 JUnit 里跑）。
     */
    public static Set<String> parseOwned(String csv) {
        Set<String> owned = new LinkedHashSet<>();
        if (csv == null) {
            return owned;
        }
        for (String raw : csv.split(",", -1)) {
            String cardId = raw.trim();
            if (!cardId.isEmpty()) {
                owned.add(cardId);
            }
        }
        return owned;
    }

    /**
     * ★ <b>纯</b>：把 {@code base} 的「已拥有卡」**替换**成 {@code csv} 指定的那张表
     * （其它字段 —— 金币 / 尘 / 预设 / 计数器 / UUID —— 原样保留）。
     *
     * <p>★ 这是**替换**不是追加（调试指令 {@code setowned} 的字面语义）。
     * 图鉴同步写成 {@code NORMAL}：{@link PlayerProgress} 的不变量要求
     * 「{@code codex} 与 {@code owned} 必须对得上」，不一起改就会在构造器里抛异常。
     *
     * <p>⚠️ 已存在的预设**不**做合法性重校验（{@code docs/08} §6：卡牌被移除时允许留下
     * 需要降级处理的旧预设）—— 本方法只保证 {@code owned} 与 {@code codex} 自洽。
     *
     * @param base 基准进度（不可为 {@code null}）
     * @param csv  卡 id 列表（{@code null} ⇒ 清空）
     * @throws IllegalArgumentException {@code base} 为 {@code null}
     */
    public static PlayerProgress withOwned(PlayerProgress base, String csv) {
        if (base == null) {
            throw new IllegalArgumentException("base 不能为 null（替换「已拥有」要以一份进度为基准）");
        }
        Set<String> owned = parseOwned(csv);
        Map<String, String> codex = new LinkedHashMap<>();
        for (String cardId : owned) {
            codex.put(cardId, PlayerProgress.CODEX_NORMAL);
        }
        return new PlayerProgress(base.playerId(), base.gold(), base.dust(), owned, codex,
                base.presets(), base.packsOpened(), base.gamesPlayed());
    }

    /**
     * ★ <b>纯</b>：写回前的参数校验（把「能不能写」这条判据放到离线可测的地方）。
     *
     * @return {@code null} = 可以写；否则是**中文**的问题描述（调用方负责告警）
     */
    public static String validateSave(UUID playerId, PlayerProgress progress) {
        if (playerId == null) {
            return "playerId 不能为 null";
        }
        if (progress == null) {
            return "progress 不能为 null（要用 PlayerProgress.fresh(playerId) 表示空白进度）";
        }
        if (!playerId.equals(progress.playerId())) {
            return "进度的 playerId=" + progress.playerId() + " 与目标 " + playerId
                    + " 不一致 —— 绝不把别人的进度写到这个玩家名下（归错人比丢数据更糟）";
        }
        return null;
    }

    // ================================================================== 内部

    /** 只读：MGC 内存里有没有这个 uuid 的档案（不创建、不告警）。 */
    private InfernalPlayerData inMemory(UUID playerId) {
        try {
            for (InfernalPlayerData data
                    : MCZJUGameCore.getPlayerDataManager().getAllPlayerData(InfernalPlayerData.class)) {
                if (data != null && playerId.toString().equals(data.getPlayerID())) {
                    return data;
                }
            }
            return null;
        } catch (Throwable t) {
            // 未注册 / 没有数据桶时 MGC 会抛（getAllPlayerData 走的是「找不到就抛」那条路）
            return null;
        }
    }

    /**
     * 取数据对象：先在内存里找，找不到再 {@link #ensure} 造一个。
     *
     * @return {@code null} = 拿不到（调用方按各自的失败方向处理）
     */
    private InfernalPlayerData dataOrEnsure(UUID playerId, String playerName) {
        InfernalPlayerData data = inMemory(playerId);
        if (data != null) {
            return data;
        }
        if (!ensure(playerId, playerName)) {
            return null;
        }
        return inMemory(playerId);
    }

    private void warn(String message) {
        logger().warning("[IC] 档案：" + message);
    }

    private void info(String message) {
        logger().info("[IC] 档案：" + message);
    }

    private Logger logger() {
        return plugin.getLogger();
    }
}
