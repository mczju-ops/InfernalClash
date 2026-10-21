package io.mczju.infernalclash.ai;

import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.Side;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * ★★ <b>人机「装配」的两个可离线断言的部分</b>（2026-10-16 第七轮新增）。
 *
 * <h2>为什么要把这两件事抽出来（现场缺陷的根因）</h2>
 * 2026-10-16 现场：`/incl bot on` 之后**局真的开了、目标物真的生成了**，紧接着
 * {@code onGameStart()} 里的**人机组装**抛
 * {@code IllegalArgumentException: plugin / match / side 都不能为 null}，
 * 异常从回调里逃出去 ⇒ 局半初始化、房间留 {@code IN_GAME}、控制台还打了一条**误导**的
 * 「{@code 直接 startGame() 失败}」（现场原文见 {@code dev-log/2026-10-16-026}）。
 *
 * <p>实测根因：装配点把 {@code new TrainingBotDriver(plugin, this.match, …)} 写在
 * {@code this.match = MatchService.start(…)} <b>之前</b> ⇒ 那时字段还是 {@code null}。
 * 而这两步的先后是**有硬约束的**：
 * <pre>
 *   ① 名册注入（往 players 追加「人机那一方」）  ← 必须**早于** MatchService.start
 *      （MatchService 的构造器按名册建 SideState；晚一步 ⇒ 人机那一方不存在 ⇒
 *       addXp 静默无效、xp 恒为 0 ⇒ 它一张牌都打不出来，而且**不抛异常**）
 *   ② 执行层（TrainingBotDriver）              ← 必须**晚于** MatchService.start
 *      （构造器要求 match 非 null）
 * </pre>
 *
 * <h2>本类提供两样东西</h2>
 * <ol>
 *   <li>{@link #appendBotToRoster} —— ①的<b>纯函数</b>（判据 ＋ 追加一条
 *       {@link MatchService.PlayerName}）。不碰 Bukkit、不碰世界 ⇒ 普通 JUnit 可逐格断言；</li>
 *   <li>{@link #assembleOrNull} —— <b>fail-safe 闸门</b>：把「人机相关的装配」整体包起来，
 *       失败 ⇒ <b>只告警 + 返回 {@code null}</b>，异常**绝不外抛**
 *       ⇒ 本局按「无人机」继续，不影响对局与房间。</li>
 * </ol>
 *
 * <h2>★★ 闸门只圈「人机相关的那几步」</h2>
 * 任务口径是「不要吞掉会影响对局正确性的异常」。⇒ 生产路径（{@code game/InfernalClashGame}）
 * 只把**这两步**放进闸门；建立 `MatchService` / 目标物 / 快捷栏 / 战斗指挥等步骤的异常
 * **照旧往外抛**（原语义一字未改）。
 *
 * <h2>★ 纯（{@code AGENTS.md} §3）</h2>
 * 本类不 import 任何 Bukkit 类型，也不查世界 / 不数在线玩家（铁律 10）：
 * 人数是**传进来的名册大小**。
 */
public final class TrainingBotAssembly {

    /**
     * 人机在名册里的**回退**显示名（配置里没给名字 / 旧调用点用）。
     *
     * <p>★★ <b>2026-10-21 起这个名字是「回退值」，不再是唯一值</b>：用户裁定
     * 「每个人机的卡组和**人机的名字**都可以在 config.yml 里配置」⇒ 真实路径用的是
     * {@code training.bots[].name}（见 {@link #appendBotToRoster(String, List, boolean, String)}）。
     *
     * <p>★ 它<b>不是</b>玩家名（真人的名字由 {@code InfernalClashPlugin.identityOf} 给），
     * 只出现在名册与日志里；人机的 UUID 由 {@link #botIdFor} 从 {@code matchId + side} 派生
     * ⇒ <b>稳定、可复现、不占用任何真人 UUID</b>（它同时是 PDC 归属里的 owner）。
     */
    public static final String BOT_NAME = "InfernalBot";

    private TrainingBotAssembly() {
    }

    /**
     * ★ <b>装配步骤</b>（就是那些"会抛异常"的动作）。
     *
     * <p>抽成函数式接口只为一件事：<b>让失败路径可以被离线断言</b>
     * （测试里塞一个必然抛异常的装配器 ⇒ 断言「不外抛、返回 null、有告警」）。
     */
    @FunctionalInterface
    public interface Assembler<T> {

        /** 执行装配；允许抛任何异常（由 {@link #assembleOrNull} 兜住）。 */
        T assemble();
    }

    // ==================================================================================
    // ① 名册：要不要造人机 ＋ 往名册追加「人机那一方」（纯函数，可离线断言）
    // ==================================================================================

    /**
     * ★★ <b>判定这是不是训练局，是就往名册里追加一条「人机那一方」</b>。
     *
     * <h2>判据只有一处（不在这里再写一遍）</h2>
     * 用两维纯规则 {@link TrainingMatchRules#botEnabledFor(int, boolean)}：
     * <b>有人显式敲过 {@code /incl bot on}</b>（{@code botRequested}）<b>且</b>真人只有 1 名。
     * <ul>
     *   <li>1 真人 ＋ 已请求 ⇒ 人机占真人**另一侧**（{@link Side#other()}）；</li>
     *   <li>★ 2 真人 ＋ 已请求 ⇒ {@code null}（<b>正常 1v1，人机不上场</b> —— 用户原话
     *       「存在人机时进来第二个人也能正常开战」，{@code docs/24} §5.5）；</li>
     *   <li>1 真人 ＋ 没请求 ⇒ {@code null}（★ R62 的"自动"已被 2026-10-15 裁定取代）；</li>
     *   <li>空名册 / {@code null} ⇒ {@code null}（不抛：本方法跑在开局回调里，见下）。</li>
     * </ul>
     *
     * <h2>★ 为什么「空名册」是返回 {@code null} 而不是抛</h2>
     * 本方法跑在 {@code onGameStart()} 里 ⇒ 抛出去的代价是「房间卡住 + 玩家看到一段堆栈」，
     * 而"没有真人"这件事本身就意味着**没有人机局可开** ⇒ 如实返回 {@code null} 更安全。
     * （★ 与 {@link TrainingMatchRules#botEnabledFor(int, boolean)} 的 fail-fast 口径**不冲突**：
     * 那条是"读判据"的规矩，这条是"喂养装配流程"的失败方向，与
     * {@link TrainingMatchRules#effectiveCombatants} 的处理一致。）
     *
     * @param matchId      本局 id（= MGC 房间名）；用于派生人机 UUID，{@code null} 也能跑
     *                     （派生出来的是"null|RED"那种稳定 id，绝不会是 {@code null}）
     * @param players      真人名册（★ <b>会被就地追加</b>一条人机条目；不是训练局 ⇒ 一字不动）
     * @param botRequested 本房间有没有人敲过 {@code /incl bot on}
     * @param botName      ★ 本局抽中的那个人机的名字（{@code training.bots[].name}；
     *                     {@code null} / 空白 ⇒ 回退到 {@link #BOT_NAME}）——
     *                     它就是**侧边栏那一行**显示的东西（{@link MatchService.SideState#name()}）
     * @return 人机那一方；不是训练局 ⇒ {@code null}
     */
    public static Side appendBotToRoster(String matchId, List<MatchService.PlayerName> players,
                                         boolean botRequested, String botName) {
        if (players == null || players.isEmpty()
                || !TrainingMatchRules.botEnabledFor(players.size(), botRequested)) {
            return null;   // 没请求 或 两名真人 ⇒ 正常对局（人机不上场，名册一字不动）
        }
        // ★ 阵营的另一侧：阵营判定的唯一来源仍是 MatchService.sideAssignments（加入顺序），
        //   这里只是取「另一方」，没有第二套判据。
        Side botSide = MatchService.sideAssignments(players).keySet().iterator().next().other();
        String name = botName == null || botName.isBlank() ? BOT_NAME : botName.trim();
        players.add(new MatchService.PlayerName(botIdFor(matchId, botSide), name));
        return botSide;
    }

    /**
     * 兼容旧签名（名字 = {@link #BOT_NAME}）。
     *
     * <p>★ 生产路径**不**走它（2026-10-21 起每个房间都会传入抽中的那个名字）；
     * 保留只为旧夹具与「配置里没配 bots」这种不可达情形。
     */
    public static Side appendBotToRoster(String matchId, List<MatchService.PlayerName> players,
                                         boolean botRequested) {
        return appendBotToRoster(matchId, players, botRequested, BOT_NAME);
    }

    /**
     * ★ 人机那一方的 UUID：由 <b>{@code matchId + side}</b> 派生（{@code nameUUIDFromBytes}）。
     *
     * <p>为什么需要它：训练局里人机那一方<b>没有真人玩家</b>
     * （{@code MatchService.SideState.player()} 可能拿到真人那一侧的 id），
     * 而它必须是 PDC 归属里的 owner ⇒ 给一个<b>与本局 + 本方绑定</b>的稳定 id，
     * 而不是 {@code null}（{@code null} 会让归属判定少一位，日志里也看不出是谁放的）。
     * ★ 稳定的含义：同一个 {@code matchId + side} **任何时刻**都算出同一个 UUID
     * ⇒ 「日志里那个 id」与「PDC 里那个 id」永远对得上。
     */
    public static UUID botIdFor(String matchId, Side side) {
        return UUID.nameUUIDFromBytes(((matchId == null ? "null" : matchId) + "|"
                + (side == null ? "?" : side.name())).getBytes(StandardCharsets.UTF_8));
    }

    // ==================================================================================
    // ② fail-safe 闸门：人机的装配失败 ⇒ 只告警 + 返回 null（绝不外抛）
    // ==================================================================================

    /**
     * ★★ <b>执行一次「人机相关的装配」，失败只告警并返回 {@code null}</b>（fail-safe）。
     *
     * <h2>它修的是什么</h2>
     * 现场（2026-10-16）：人机组装抛的 {@code IllegalArgumentException} 从
     * {@code onGameStart()} 逃出去 ⇒ 局半初始化、房间留 {@code IN_GAME}、玩家一脸问号。
     * ⇒ 人机是**可选的外挂**，它装不上不该毁掉整局：本闸门把它降级成
     * 「一行明确 WARN ＋ 本局按无人机继续」（{@link #unavailableMessage}）。
     *
     * <h2>★ 为什么 catch {@code Throwable} 而不是 {@code Exception}</h2>
     * 与项目既有口径一致（{@code MatchHooks.combine} / {@code cleanup} 里的记账 /
     * {@code loadProfilesForHumans} 都是 {@code catch (Throwable)}）：
     * 装配路径上真正难查的失败常常是 {@code NoClassDefFoundError} / {@code LinkageError}
     * 这类 {@code Error}，而它们<b>同样不该</b>让整局停摆（此时房间已经占住、玩家已在局里）。
     * ★ 代价如实说明：这一类错误会被降级成一行 WARN ⇒ **不做**静默
     * （消息里带完整异常类名与 message；{@link #describe}）。
     *
     * @param assembler 装配步骤（可以是 {@code null} ⇒ 返回 {@code null}，什么都不做）
     * @param warn      告警出口（{@code null} ⇒ 只返回 {@code null}，不打日志）
     * @return 装配结果；失败 / 未装配 ⇒ {@code null}
     */
    public static <T> T assembleOrNull(Assembler<T> assembler, Consumer<String> warn) {
        if (assembler == null) {
            return null;
        }
        try {
            return assembler.assemble();
        } catch (Throwable failure) {
            if (warn != null) {
                warn.accept(unavailableMessage(failure));
            }
            return null;
        }
    }

    /**
     * ★★ <b>人机没能上场时那唯一一行告警文案</b>（常量在代码里 ⇒ 离线测试可逐字钉住）。
     *
     * <p>现场可读性要求：一眼看出 ① 是人机出了问题 ② <b>这一局还要不要继续</b>
     * （答案：继续）③ 原因是哪一个异常。
     */
    public static String unavailableMessage(Throwable failure) {
        return "[IC] 人机没能上场（原因 " + describe(failure)
                + "），本局按「无人机」继续，不影响对局与房间";
    }

    /** 异常的一行摘要（{@code 类名: message}；{@code null} 也不抛）。 */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "未知（异常对象为 null）";
        }
        String message = failure.getMessage();
        return (message == null || message.isBlank())
                ? failure.getClass().getName()
                : failure.getClass().getName() + ": " + message;
    }
}
