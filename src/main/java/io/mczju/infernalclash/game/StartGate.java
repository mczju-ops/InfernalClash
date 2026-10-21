package io.mczju.infernalclash.game;

/**
 * 开局门槛（**纯函数，可离线单测**）。
 *
 * <h2>为什么要有这个类</h2>
 * MGC 的 {@code tryStart()} 对人数不足本来就是「{@code warn} 后 return」——
 * 既不 {@code startGame()} 也不 {@code cancelGame()}，等待房间原样保留。
 * 我们只需把**判据**换掉，而不必自己发明一套开局流程。
 *
 * <p>判据做成纯函数是为了让人改不动它：想放宽/收紧门槛，**必须先改测试**
 * （{@code StartGateTest}）。这与 MCZJUGuess 的 {@code StartGate} / {@code QuitAbortTest}
 * 是同一套做法。
 */
public final class StartGate {

    private StartGate() {
    }

    /**
     * 现在是否可以开局。
     *
     * @param humans     房间里当前的**真人**数量（人机不算 —— 它们不是 MGC 玩家）
     * @param minPlayers 配置要求的最少真人数
     * @return 达到门槛返回 {@code true}
     */
    public static boolean canStart(int humans, int minPlayers) {
        // ★ Math.max(1, ...)：即使配置层被绕过（minPlayers = 0 或负数），
        //   也不允许「0 人的对局」被启动。这是兜底，不是正常路径。
        return humans >= Math.max(1, minPlayers);
    }

    /** 未达门槛时发给房间内玩家的提示（MiniMessage 文本）。 */
    public static String blockedMessage(int humans, int minPlayers) {
        int need = Math.max(1, minPlayers);
        int missing = Math.max(0, need - humans);
        return "<yellow>还差 " + missing + " 名玩家才能开始（当前 " + humans + " / 至少 " + need + "）。"
                + "<gray>等待房间会保留，人齐后自动开局。";
    }

    /**
     * ★★ <b>「人机已请求，但开局窗口还没到」时发给房间的提示</b>（MiniMessage 文本）。
     *
     * <h2>它为什么必须存在（用户要求）</h2>
     * 2026-10-15 用户裁定：「<b>存在人机时进来第二个人也能正常开战</b>」
     * （留档 {@code docs/24} §5.5）⇒ 敲了 {@code /incl bot on} 之后<b>不能立刻开局</b>，
     * 必须留一个"再等一会儿"的窗口（{@code ai/TrainingMatchRules#BOT_START_WINDOW_TICKS}）。
     * 这段时间里必须<b>明确告诉玩家在等什么</b>，否则现场表现就是"敲了没反应"。
     *
     * <h2>★★ 2026-10-19：缩成一行 ＋ 进 {@code display.yml}</h2>
     * 用户逐字：「**之前要求删掉的大段带用户裁定的文本又回来了**」——
     * 上一轮我只把 {@code /incl bot on} 自己那几行压成一行，**这一条是从
     * {@code InfernalClashGame#tryStart} 里发出来的另一条**（现场看到的就是它）。
     * ⇒ ① 措辞缩成一行、**不带任何裁定引文**；② 真正的出口改成 {@code display.yml} 的
     * {@code match.bot-window}（见 {@code InfernalClashGame#botWindowText}），
     * 本方法只作**取不到配置时的回退常量**。
     *
     * <p>★ 纯函数（只吃三个数字，不碰 Bukkit / MGC）⇒ 离线可逐格断言（{@code StartGateTest}）。
     *
     * @param humans    房间里当前的真人数量（= 1，因为只有"1 真人 ＋ 人机"这种局才需要窗口）
     * @param minPlayers 配置要求的最少参战人数（{@code game.min-players}，出厂与现场都是 2）
     * @param ticksLeft 窗口剩余 tick（{@code <= 0} ⇒ 不该调用本方法；仍按 0 处理，不撒谎）
     */
    public static String botWindowMessage(int humans, int minPlayers, int ticksLeft) {
        int left = Math.max(0, ticksLeft);
        double seconds = left / 20.0;
        return "<yellow>人机已就位：还在等第二名玩家（再 " + String.format("%.1f", seconds)
                + " 秒没人来，就带你开局）。";
    }
}
