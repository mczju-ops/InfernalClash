package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.match.MatchService;
import io.mczju.infernalclash.match.Side;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * ★★★ <b>「投降」界面（1 号快捷栏那个物品右键打开的界面）</b>——
 * 2026-10-17 第十二轮新增，缺陷 {@code docs/27} §<b>D7</b>。
 *
 * <h2>用户原话（逐字，留档 {@code docs/24}）</h2>
 * > 「投降菜单没有制作，并且是一个真正的可以使用的盾牌。
 * > **你可以把它设置成一个钟，这样玩家就无法与其互动了**」
 * >
 * > （2026-10-17 追加，E26 结案）「**战斗菜单的物品改名『投降』，内部只有两个按钮
 * > 『确认投降』和『取消』，对称排布**」
 *
 * <h2>文档依据（两处，都不含逐格布局）</h2>
 * <ul>
 *   <li>{@code docs/02} §6.1 快捷栏表：「{@code 1} —— 「<b>战斗</b>」物品 = <b>战斗菜单</b>
 *       （内含<b>投降按钮</b>；「将来可能会加入功能」）｜ R18、<b>Q9</b>」；</li>
 *   <li>{@code docs/05} §B <b>Q9</b> 裁定：「是一个战斗菜单，里面有<b>投降按钮</b>；
 *       「将来可能会加入功能」」。</li>
 * </ul>
 * ★★ <b>文档**没有**给这个界面的逐格布局</b>（{@code docs/09} 只有 U1/U2/U3解锁确认/U4/U5，
 * 其中 U3 指的是"解锁确认"、不是这个界面）⇒ **2026-10-17 用户当场裁定（E26 结案）**：
 * 标题 = <b>投降</b>；两个按钮 = <b>确认投降</b> / <b>取消</b>，<b>关于第 2 行中心列左右镜像</b>
 * （{@code 12} / {@code 14}，见 {@link MenuLayout#BATTLE_SURRENDER_SLOT}）。
 *
 * <h2>★ 材质从 {@code SHIELD} 改成 {@code CLOCK}（用户明确指示）</h2>
 * 盾牌**右键会举盾** ⇒ 与「右键打牌」抢事件（这就是用户说「真正的可以使用的盾牌」的意思）。
 * 钟没有任何右键行为 ⇒ 玩家无法与它互动。落地在 {@code card/CardItems#BATTLE_ITEM_MATERIAL}
 * （一行），并由 {@code ui/BattleMenuLayoutTest} 钉住。
 *
 * <h2>★ 投降走哪条路（一条新规则都没有）</h2>
 * 点「确认投降」⇒ {@link MatchService#surrender(Side)} ⇒ 这一方判负、对面获胜
 * —— 依据 <b>【Q28】</b>（{@code docs/05} §B：「玩家退出对局 ⇒ <b>判负</b>」）的主动版本，
 * 并走**既有**收尾路径（{@code finishWith} → {@code finish} → MGC 的 {@code endGame}）。
 * ★ <b>只有一次点击</b>（用户只说"确认投降"与"取消"两个按钮，没说二级确认）——
 * 「确认」二字由按钮本身承担（点它 = 确认），所以**不再加**第三个界面。
 *
 * <h2>★ 铁律 10</h2>
 * 只读<b>该玩家自己</b>所在的那一场（{@code MatchService.get(matchId)} 的 {@code sideOf}）；
 * 没有 {@code getOnlinePlayers} / {@code getEntities} / {@code getWorlds}。
 */
public final class BattleMenu extends InfernalMenu {

    /** 界面标题的键（{@code display.yml} 的 {@code messages.match.surrender}）—— 用户裁定 = 「投降」。 */
    static final String KEY_TITLE = "match.surrender";

    /** 「确认投降」按钮名的键（{@code messages.match.surrender-confirm}）。 */
    static final String KEY_CONFIRM = "match.surrender-confirm";

    /** 「取消」按钮名的键（{@code messages.match.surrender-cancel}）。 */
    static final String KEY_CANCEL = "match.surrender-cancel";

    /** 标题的**回退常量**（★ 2026-10-17 用户裁定：「物品改名『投降』」）。 */
    public static final String TITLE = "投降";

    /** 「确认投降」按钮的**回退常量**（★ 用户原话逐字：「内部只有两个按钮『确认投降』和『取消』」）。 */
    public static final String BUTTON_CONFIRM = "确认投降";

    /** 「取消」按钮的**回退常量**（★ 用户原话逐字，同上）。 */
    public static final String BUTTON_CANCEL = "取消";

    /** 「确认投降」按钮的 lore（说明后果 —— 判负规则来自【Q28】，不是我发明的）。 */
    public static final String LORE_CONFIRM = "点击后本局立刻判你负、对面获胜";

    /** 不在对局里 / 对局已结束时点投降的提示（fail-safe，绝不静默）。 */
    public static final String HINT_NOT_IN_MATCH = "你现在没有正在进行的对局，战斗菜单不可用。";

    /** 投降成功后的提示。 */
    public static final String HINT_SURRENDERED = "你已投降 —— 本局由对面获胜。";

    /** 本局运行时的 matchId（{@code args[0]}；拿不到 ⇒ {@code null} ⇒ 界面按「不可用」渲染）。 */
    private String matchId;

    /**
     * 是否已经解析过一次 {@code args[0]} —— {@code Menu.refresh()} 会 {@code clearMenu()}
     * 后**再调一次** {@link #setup()}（与 {@link MatchDeckMenu} / {@link DeckPresetMenu} 同一个坑）
     * ⇒ {@code matchId} 只能在第一次解析。
     */
    private boolean initialized;

    /**
     * ★ <b>无副作用构造</b>（MGC 用反射 {@code (Player, Object...)} 造它；装配全部在 {@link #setup()}）。
     *
     * @param player 打开界面的玩家
     * @param args   {@code args[0]} = 本局的 {@code matchId}（{@link MenuRegistry#openBattleMenu} 会传）
     */
    public BattleMenu(Player player, Object... args) {
        super(player, args);
    }

    // ------------------------------------------------------------------ 可离线单测的那一半

    /** 标题（★ 构造期就会被 MGC 调用 ⇒ 只碰静态单例）。 */
    @Override
    protected String getTitle() {
        return title(displayOrNull(), this::logWarning);
    }

    static String title(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_TITLE, TITLE, warner);
    }

    static String confirmName(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_CONFIRM, BUTTON_CONFIRM, warner);
    }

    static String cancelName(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_CANCEL, BUTTON_CANCEL, warner);
    }

    /** 行数（★ 只许返回静态常量：MGC 的构造器会调它）：{@link MenuRegistry#battleRows()}。 */
    @Override
    protected int getRows() {
        return MenuRegistry.battleRows();
    }

    /**
     * ★★ <b>本界面占用的槽位清单</b>（= {@link MenuRegistry#battleItemSlots()}）。
     *
     * <p>★ 与 {@link MatchDeckMenu#occupiedSlots()} 同一个理由：界面与测试读**同一张**清单，
     * 否则「界面到底占哪几格」会有两份口径（迟早与文档分叉）。
     */
    public static List<Integer> occupiedSlots() {
        return MenuRegistry.battleItemSlots();
    }

    @Override
    protected void setup() {
        try {
            if (!initialized) {
                matchId = readMatchId();
                initialized = true;
            }
            render();
        } catch (Throwable t) {
            // ★ 绝不让异常冒回 MGC：一次渲染异常会把该事件的所有监听器一起炸掉
            logWarning("装配 " + getClass().getSimpleName() + " 失败（界面会显示为空）：" + t);
        }
    }

    // ------------------------------------------------------------------ 参数 / 会话

    private String readMatchId() {
        Object[] args = getArgs();
        if (args == null || args.length == 0 || args[0] == null) {
            logWarning("打开战斗菜单时没有传 matchId（args 为空），界面按「不可用」渲染");
            return null;
        }
        return String.valueOf(args[0]).trim();
    }

    /** 本局运行时（没有 ⇒ {@code null}）。 */
    private MatchService match() {
        return matchId == null ? null : MatchService.get(matchId);
    }

    /** 本玩家在这一局里的阵营（不在 ⇒ {@code null}）。 */
    private Side side() {
        MatchService service = match();
        return service == null ? null : service.sideOf(player.player().getUniqueId());
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        MatchService service = match();
        Side side = side();
        if (service == null || side == null) {
            logWarning("投降界面不可用：matchId=" + matchId + "｜有阵营=" + (side != null));
            tellError(HINT_NOT_IN_MATCH);
            return;
        }
        if (!service.state().isRunning()) {
            // 战斗 / 加时才有投降（准备阶段另有判据 —— 见 MatchService#surrender 的 javadoc）
            tellError(HINT_NOT_IN_MATCH);
            return;
        }
        // ★★ 2026-10-17 用户裁定（E26）：「内部只有两个按钮『确认投降』和『取消』，对称排布」
        //   ⇒ 两格由 MenuRegistry.battleItemSlots() 给出（12 / 14，关于第 2 行中心列镜像），
        //     这里**不再有第三个元素**（多一格就是与用户原话不符）。
        setSlot(MenuRegistry.battleItemSlots().get(0),
                icon(Material.RED_CONCRETE, confirmName(displayOrNull(), this::logWarning),
                        List.of(LORE_CONFIRM)),
                (who, event) -> surrender(service, side));
        setSlot(MenuRegistry.battleItemSlots().get(1),
                icon(Material.BARRIER, cancelName(displayOrNull(), this::logWarning), null),
                (who, event) -> player.player().closeInventory());
    }

    // ------------------------------------------------------------------ 交互

    /**
     * ★★★ <b>确认投降</b>：唯一入口是 {@link MatchService#surrender(Side)}
     * （规则与收尾路径都在那里，见它的 javadoc —— 界面里**不写**任何判负逻辑）。
     *
     * <p>成功后<b>关闭界面</b>：本局已经结束，留着一个"确认投降"按钮只会让人再点一次
     * （{@code surrender} 对已结束的对局返回 {@code false}，所以重复点是安全的，只是没必要）。
     * ★ 用户只规定「确认投降 / 取消」两个按钮（E26）⇒ **没有二级确认界面** ——
     * 「确认」由按钮本身承担（点它 = 确认）。
     */
    private void surrender(MatchService service, Side side) {
        if (!service.surrender(side)) {
            tellError(HINT_NOT_IN_MATCH);
            // 界面上的状态可能已经过期（对局刚结束）⇒ 重画一次
            refresh();
            return;
        }
        tellOk(HINT_SURRENDERED);
        player.player().closeInventory();
    }
}
