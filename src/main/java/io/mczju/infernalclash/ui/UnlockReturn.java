package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.profile.PresetRules;

/**
 * ★★★ <b>「解锁确认界面（U3）关掉之后回到哪儿」</b> —— 2026-10-21 新增（纯数据，不碰 Bukkit / MGC）。
 *
 * <h2>它补的是哪两个现场缺陷（用户逐字）</h2>
 * <ol>
 *   <li>「<b>合成卡牌界面点击”取消“后应该返回图鉴界面，而不是直接退出</b>」——
 *       在此之前取消按钮的动作就是 {@code closeInventory()}，玩家被直接踢出界面；</li>
 *   <li>「<b>我在解锁过一张卡后，返回图鉴界面时，所有未解锁卡又会变成”？？？“</b>」——
 *       根因是**回程写死成了 U5 只读图鉴**（{@code MenuRegistry#openCodexQuietly}），
 *       而玩家真正在用的是 U2「图鉴与卡组」（{@code /incl cards}）。
 *       两个界面的图鉴格是同一段槽位，但未解锁卡的画法不同：
 *       U2 走 {@code lockedCardIcon}（黑色混凝土 + 真名 + 真效果），
 *       U5 当时还留着旧的 {@code lockedIcon}（{@code "???"}）
 *       ⇒ 玩家看到的就是「解锁完一张，剩下的又全变回问号」。</li>
 * </ol>
 *
 * <h2>★★ 为什么不直接在界面里写「打开图鉴」</h2>
 * 解锁确认界面有<b>三个</b>打开方（U2 / U4 / U5，见 {@code MenuRegistry#openUnlockConfirm} 的
 * 三个调用点）。回程若写死一个目标，就必然有两条路是错的 —— 这正是上面第 2 条缺陷的形状。
 * ⇒ 「回到<b>当初打开我的那个界面</b>」变成<b>由打开方注入的参数</b>，
 * 界面自己不再猜（判据只有这一处，测试也能离线钉住）。
 *
 * <h2>★ 三个字段各自为什么必需</h2>
 * <table border="1">
 *   <caption>回程要复原的状态（少一个，玩家就会被弹到别处）</caption>
 *   <tr><th>字段</th><th>为什么</th></tr>
 *   <tr><td>{@link #kind()}</td><td>回到哪个界面（U2 / U4 / U5 —— 三者打开方式完全不同）</td></tr>
 *   <tr><td>{@link #page()}</td><td>图鉴**页码**是界面内部状态（关掉就丢）；不复原 ⇒
 *       玩家刚买的那张卡在别的页上，他以为没生效</td></tr>
 *   <tr><td>{@link #presetIndex()}</td><td>U2 是「第几套预设」的编辑界面（参数来自打开方）；
 *       不复原 ⇒ 玩家会被弹回第 1 套，且看到的 8 个卡位不是他刚才那套</td></tr>
 *   <tr><td>{@link #matchId()}</td><td>U4 是**对局内**选卡界面，必须带本局 id
 *       （【Q44】多场并发 ⇒ 不能按玩家现查"当前对局"）</td></tr>
 * </table>
 *
 * <h2>★ 失败方向（构造期绝不抛）</h2>
 * <ul>
 *   <li>{@code kind == null} ⇒ 当成 {@link Kind#CODEX}（只读图鉴 —— 与改动前**唯一**那条回程同形）；</li>
 *   <li>{@code page < 0} ⇒ 夹到 {@code 0}（页码是显示用途，夹一下总比越界好）；</li>
 *   <li>{@code presetIndex} 越界 ⇒ 退回 {@code 0}（判据取自 {@link PresetRules#PRESET_LIMIT}，
 *       ★ 不另写一个 3）；</li>
 *   <li>{@code kind == MATCH_DECK} 但没有 {@code matchId} ⇒ <b>降级成 {@link Kind#CODEX}</b>
 *       —— 宁可回只读图鉴，也不打开一个"不知道是哪一场"的选卡界面。</li>
 * </ul>
 */
public record UnlockReturn(Kind kind, int page, int presetIndex, String matchId) {

    /** 回程的三种界面（★ 与 {@code MenuRegistry} 的三张图鉴族界面一一对应）。 */
    public enum Kind {

        /** U2 图鉴 + 卡组预设（{@code /incl cards}；{@link MenuRegistry#MENU_DECK}）。 */
        DECK,

        /** U5 只读图鉴（{@code /incl codex}；{@link MenuRegistry#MENU_CODEX}）。 */
        CODEX,

        /** U4 对局内选卡（准备阶段；{@link MenuRegistry#MENU_MATCH_DECK}）—— 必须带 {@code matchId}。 */
        MATCH_DECK
    }

    /**
     * 规范化：非法/缺失的字段一律按 {@link #kind()} 那一段 javadoc 里写的失败方向处理。
     *
     * <p>★ <b>只做夹取与降级，不做任何 IO</b>：本类因此能在普通 JUnit 里逐格断言。
     */
    public UnlockReturn {
        if (kind == null) {
            kind = Kind.CODEX;
        }
        page = Math.max(0, page);
        if (presetIndex < 0 || presetIndex >= PresetRules.PRESET_LIMIT) {
            presetIndex = 0;
        }
        if (kind == Kind.MATCH_DECK && (matchId == null || matchId.isBlank())) {
            // 见类注释「失败方向」最后一条：没有 matchId 的 U4 是打不开的，降级而不是抛
            kind = Kind.CODEX;
            matchId = null;
        }
    }

    /** 回到 U2「图鉴与卡组」的某一页、某一套预设。 */
    public static UnlockReturn deck(int page, int presetIndex) {
        return new UnlockReturn(Kind.DECK, page, presetIndex, null);
    }

    /** 回到 U5 只读图鉴的某一页。 */
    public static UnlockReturn codex(int page) {
        return new UnlockReturn(Kind.CODEX, page, 0, null);
    }

    /** 回到 U4 对局内选卡界面的某一页（{@code matchId} 必需，见 {@link Kind#MATCH_DECK}）。 */
    public static UnlockReturn matchDeck(String matchId, int page) {
        return new UnlockReturn(Kind.MATCH_DECK, page, 0, matchId);
    }

    /**
     * ★ <b>从界面参数里读出回程目标</b>（{@code UnlockConfirmMenu} 的 {@code args[1]}）。
     *
     * <p>三种输入形态都要认，且都不许抛：
     * <ul>
     *   <li>{@link UnlockReturn} —— 本插件自己的三个打开方传的就是它；</li>
     *   <li>{@link Number} —— 历史参数（只有页码，界面 = 只读图鉴）；
     *       ★ 兼容它是为了让「MGC 的 {@code /menu infernalclash:unlock-confirm <玩家> <卡id> <页码>}」
     *       这条通用通道照旧可用；</li>
     *   <li>其它 / 缺失 / {@code null} ⇒ {@code codex(0)}（最保守：回只读图鉴第 1 页）。</li>
     * </ul>
     */
    public static UnlockReturn fromArgs(Object[] args) {
        if (args == null || args.length < 2) {
            return codex(0);
        }
        Object raw = args[1];
        if (raw instanceof UnlockReturn target) {
            return target;
        }
        if (raw instanceof Number number) {
            return codex(number.intValue());
        }
        return codex(0);
    }
}
