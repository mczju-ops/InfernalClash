package io.mczju.infernalclash.ui;

import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.config.EconomyConfig;
import io.mczju.infernalclash.policy.PackRoller;
import io.mczju.infernalclash.policy.RarityNames;
import io.mczju.infernalclash.profile.PackOpening;
import io.mczju.infernalclash.profile.PlayerProgress;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ★ <b>U4 局外商店</b>—— 逐格布局照抄 {@code docs/09} §4.2（{@code docs/09} 里它的编号是 U5，
 * 本节按 {@code MenuRegistry.MENU_SHOP} 的名字称它「商店」）。
 *
 * <h2>逐格布局（{@code docs/09} §4.1 / §4.2「4 行 = 36 格」）</h2>
 * <table border="1">
 *   <caption>本界面放东西的格（其余格一律不放）</caption>
 *   <tr><th>槽位</th><th>内容</th><th>出处</th></tr>
 *   <tr><td>{@code 12} / {@code 14}</td><td>购买 <b>1 / 10</b> 个卡牌包（<b>居中</b>）</td>
 *       <td>§4.1「第 2 行」+ §4.2 + 【Q34】「从 1 数」；★ <b>2026-10-20 用户裁定</b>：
 *           ① 取消「连开 30 包」（抽到的卡牌显示不全）⇒ 只剩两个；
 *           ② 两个按钮要**居中**（「买卡包的商店里两个选项没有居中」）
 *           ⇒ 第 2 行（{@code 9}~{@code 17}）关于中心列 {@code 13} 对称 ⇒ <b>{@code 12} / {@code 14}</b></td></tr>
 *   <tr><td>{@code 27}</td><td><b>你的金币数</b>（展示用，左下）</td><td>§4.2 第 4 行第 1 格</td></tr>
 *   <tr><td>{@code 35}</td><td><b>退出</b>（右下）</td><td>§4.2 第 4 行第 9 格</td></tr>
 * </table>
 * <p>★ §4.2 里 {@code 0}~{@code 8}、{@code 16}~{@code 26}、{@code 28}~{@code 34} 是「空」
 * ⇒ 不放任何东西。
 *
 * <h2>★★ 购买按钮（2026-10-19 已真正接入开包）</h2>
 * 业务流程<b>只有一条</b>：查价 → {@code PlayerProgress#canAfford/spendGold} →
 * {@code policy.PackRoller#drawPack}（每包 {@code cards-per-pack} 张）→
 * {@code profile.PackOpening#open}（新卡入档 / 重复卡自动转尘 / 金卡升级）→
 * {@code ProfileStore#save} → 关界面重开（余额立刻可见）。
 * ★ 规则细节（【Q30】自动转尘、【Q31】金卡自动分解普通版、【Q32】金卡只能抽到）
 * 全在纯类 {@link PackOpening} 里，界面层不写第二套经济规则。
 * ★ 买不起 ⇒ <b>不扣不发</b>，只回一句 {@code shop.pack-not-enough}。
 *
 * <h2>★ 文案（P8「文案接线」已落地）</h2>
 * 标题 / 购买按钮名 / 金币行 / 退出按钮名**都经 {@link MenuTexts} 读 {@code display.yml}**，
 * 读不到就回退到下面这些常量（并告警一次）。每把键与它的回退常量的对应关系写在常量 javadoc 里。
 * ★ {@code shop.buy-pack-lore} 那两行**仍用代码常量**：出厂值把张数写死成
 * {@code <white>5</white>}，而代码读的是 {@code EconomyConfig.cardsPerPack()}
 * ⇒ 改读它会让「包内张数」丢掉配置来源（那是改业务，不是改文案来源）。
 */
public final class ShopMenu extends InfernalMenu {

    /** 界面标题的配置键（{@code display.yml} 的 {@code messages.shop.title}）。 */
    static final String KEY_TITLE = "shop.title";

    /** 金币展示行的配置键（{@code messages.shop.balance}，带 {@code {gold}}）。 */
    static final String KEY_BALANCE = "shop.balance";

    /** 退出按钮名的配置键（{@code messages.shop.close}）。 */
    static final String KEY_CLOSE = "shop.close";

    /** 购买按钮名的配置键（{@code messages.shop.buy-pack}，带 {@code {count}}）。 */
    static final String KEY_BUY_PACK = "shop.buy-pack";

    /**
     * 界面标题的**回退常量**「卡牌商店」。
     *
     * <p>★ 现在是兜底：正常路径读 {@code display.yml} 的 {@link #KEY_TITLE}
     * （出厂值 {@code "<dark_gray>卡牌商店"}）；配置取不到时才用它。
     */
    public static final String TITLE = "卡牌商店";

    /**
     * 购买按钮名的**回退常量**（{@code %d} = 包数：1 / 10）。
     *
     * <p>出处：{@code docs/09} §4.1「购买 1 卡牌包 / 购买 10 卡牌包 / 购买 30 卡牌包」；
     * ★ <b>2026-10-20 用户裁定取消「连开 30 包」</b> ⇒ 只剩 1 / 10 两档。
     * ★ 正常路径读 {@link #KEY_BUY_PACK}（带 {@code {count}}）。
     */
    public static final String NAME_BUY = "购买 %d 个卡牌包";

    /**
     * 购买按钮 lore 第 1 行（{@code %d} = 总价金币）。
     *
     * <p>出处：✅【Q8】【Q55】「**100 金币 / 个**；10 包线性、无折扣（1000）」+
     * {@code docs/09} §4.3「按钮 lore 里显示价格」。价格**从配置取**
     * （{@code EconomyConfig.packPrices()}），不写死在界面里。
     * ★ 这一行**不进** {@code display.yml}（见类注释：出厂 lore 值把张数写死）。
     */
    public static final String LORE_PRICE = "价格：%d 金币";

    /**
     * 购买按钮 lore 第 2 行（{@code %d} = 一包几张，出厂 5）。
     *
     * <p>出处：R48「购买一次 = 随机抽取 5 张卡牌」+ {@code docs/09} §4.3 的 lore 说明。
     * 张数从配置取（{@code EconomyConfig.cardsPerPack()}）。
     * ★ 这一行**不进** {@code display.yml}（见类注释）。
     */
    public static final String LORE_PER_PACK = "每个卡包 %d 张卡牌";

    /**
     * 金币展示格名字的**回退常量**（{@code %d} = 该玩家自己的金币数）。
     *
     * <p>出处：{@code docs/09} §4.2「{@code 27} —— **你的金币数**（展示用，左下）」。
     * ★ 正常路径读 {@link #KEY_BALANCE}（带 {@code {gold}}）。
     */
    public static final String NAME_BALANCE = "你的金币：%d";

    /**
     * 退出按钮名的**回退常量**。
     *
     * <p>出处：{@code docs/09} §4.2「{@code 35} —— **退出**（右下）」。
     * ★ 正常路径读 {@link #KEY_CLOSE}（与 {@code icons.shop.exit.name} 同义，按「二选一」
     * 的口径取 {@code messages.*} 那一把；图标材质仍取自 {@code icons.shop.exit.material}）。
     */
    public static final String NAME_EXIT = "退出";

    /**
     * 购买按钮的「待接入」提示。
     *
     * <p>★ 2026-10-19：**开包已接入**（见 {@link #purchase(int)}）。本常量现在只在
     * 「插件 / 抽取器 / 价格拿不到」时作为**失败兜底**出现（正常路径不再走到它）。
     */
    public static final String HINT_BUY_PENDING = "开包暂时不可用（拿不到抽取器或价格）；本次未扣金币、未发卡。";

    /** 开包结果**表头**的配置键（{@code messages.shop.pack-result}）。 */
    static final String KEY_PACK_RESULT = "shop.pack-result";

    /** 开包结果**逐行**的配置键（{@code messages.shop.pack-line}；占位符 {@code {card}}/{@code {rarity}}/{@code {gold}}）。 */
    static final String KEY_PACK_LINE = "shop.pack-line";

    /** 金币不足的配置键（{@code messages.shop.pack-not-enough}；占位符 {@code {price}}/{@code {gold}}）。 */
    static final String KEY_PACK_NOT_ENOUGH = "shop.pack-not-enough";

    /** 开包结果表头的**回退常量**（占位符 {@code {packs}}/{@code {cards}}/{@code {dust}}）。 */
    public static final String PACK_RESULT_FALLBACK =
            "开包结果：{packs} 包 / {cards} 张，重复卡自动转尘 +{dust}";

    /** 开包结果逐行的**回退常量**。 */
    public static final String PACK_LINE_FALLBACK = " · {card}（{rarity}）{gold}";

    /**
     * ★ <b>「这张卡是新到手的」标记</b>（2026-10-21 新增）。
     *
     * <p>措辞**逐字取自用户**：「抽到未拥有卡牌时应该标注”**新！**“」。
     * ★ 追加在结果行的**末尾**（不另发一条消息 —— 一次 10 包就是 50 张，
     * 翻倍的消息量会把聊天框刷没），判据见 {@link PackOpening.Grant#isNew()}。
     * ★ 颜色刻意不指定：它接在整行末尾，跟着聊天栏的缺省色走（配置里的行首色不会被覆盖）。
     */
    public static final String MARK_NEW = "新！";

    /** 金币不足的**回退常量**。 */
    public static final String PACK_NOT_ENOUGH_FALLBACK =
            "金币不足：需要 {price}，你有 {gold}（本次未扣金币、未发卡）。";

    /**
     * 单次消息里最多列多少张卡（★ 原本是「买 30 包 = 150 张，全列会把聊天框刷爆」；
     * 2026-10-20 起 30 包已下线 ⇒ 现在最多 10 包 × 5 张 = 50 张，这个上限仍然有意义）。
     */
    private static final int MAX_RESULT_LINES = 10;

    /** 抽取用的随机源（★ 只在点击购买时消费，不影响离线测试）。 */
    private final java.util.Random random = new java.util.Random();

    /** 金币展示格的占位材质（{@code display.yml} 的 icons 段没有 balance 键 ⇒ 接线时再新增）。 */
    private static final Material BALANCE_MATERIAL = Material.GOLD_INGOT;

    /**
     * ★ <b>无副作用构造</b>：MGC 用反射 {@code (Player, Object...)} 造它，装配全部在 {@link #setup()}。
     *
     * @param player 打开界面的玩家
     * @param args   本界面当前不用参数
     */
    public ShopMenu(Player player, Object... args) {
        super(player, args);
    }

    /** 标题（★ 构造期就会被 MGC 调用 ⇒ 只碰静态单例；文案来源 = {@code display.yml}）。 */
    @Override
    protected String getTitle() {
        return title(displayOrNull(), this::logWarning);
    }

    /**
     * ★ <b>可离线单测的那一半</b>：标题的键 / 回退常量 / 告警出口都在这里，
     * 界面走的正是它（{@link MenuTexts#resolve}：读不到 ⇒ {@link #TITLE} + 告警一次）。
     */
    static String title(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_TITLE, TITLE, warner);
    }

    /**
     * ★ <b>可离线单测的那一半</b>：购买按钮名 —— {@code {count}} = 包数。
     */
    static String buyPackName(int packs, DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_BUY_PACK, String.format(NAME_BUY, packs),
                Map.of("count", String.valueOf(packs)), warner);
    }

    /**
     * ★ <b>可离线单测的那一半</b>：金币展示行 —— {@code {gold}} = 该玩家自己的金币数。
     */
    static String balanceName(int gold, DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_BALANCE, String.format(NAME_BALANCE, gold),
                Map.of("gold", String.valueOf(gold)), warner);
    }

    /**
     * ★ <b>可离线单测的那一半</b>：退出按钮名（无占位符 ⇒ 原样返回配置里的值）。
     */
    static String exitName(DisplayConfig display, Consumer<String> warner) {
        return MenuTexts.resolve(display, KEY_CLOSE, NAME_EXIT, warner);
    }

    /** 行数（★ 同上）：{@code docs/09} §4.2「4 行 = 36 格」。 */
    @Override
    protected int getRows() {
        return MenuLayout.SHOP_ROWS;
    }

    @Override
    protected void setup() {
        try {
            renderBuyButtons();
            renderBalance();
            renderExit();
        } catch (Throwable t) {
            // ★ 绝不让异常冒回 MGC
            logWarning("装配 " + getClass().getSimpleName() + " 失败（界面会显示为空）：" + t);
        }
    }

    private void renderBuyButtons() {
        for (int index = 0; index < MenuLayout.SHOP_ITEM_COUNT; index++) {
            int packs = MenuRegistry.packCount(index);
            int slot = MenuLayout.shopSlot(index);
            List<String> lore = new ArrayList<>(2);
            Long price = priceOf(packs);
            if (price != null) {
                lore.add(String.format(LORE_PRICE, price));
            }
            lore.add(String.format(LORE_PER_PACK, cardsPerPack()));
            setSlot(slot,
                    // ★ 材质取自 display.yml 的 icons.shop.buy1 / buy10 / buy30；名字取自 messages.shop.buy-pack
                    icon(buyIconKey(index), Material.CHEST, buyPackName(packs, displayOrNull(), this::logWarning), lore),
                    (who, event) -> purchase(packs));
        }
    }

    /**
     * ★★★ <b>真的买一单</b>（2026-10-19 接入：扣金币 → 抽包 → 结算入档 → 落盘 → 刷新界面）。
     *
     * <p>顺序刻意是「<b>先算 → 再存盘 → 最后刷新</b>」（与 {@code UnlockConfirmMenu#confirm} 同一条口径）：
     * 存盘失败时**不会**出现"界面刷新了、档案没写"的假象。
     *
     * <p>★ 规则全部在纯类里：抽取 = {@code policy.PackRoller#drawPack}；
     * 结算（新卡 / 重复转尘 / 金卡升级）= {@code profile.PackOpening#open}。本方法只做：
     * 查价 → 判够不够 → 调用两者 → 存盘 → 说话。
     */
    private void purchase(int packs) {
        try {
            InfernalClashPlugin instance = plugin();
            if (instance == null) {
                tellError(HINT_BUY_PENDING);
                return;
            }
            Long priceLong = priceOf(packs);
            PackRoller roller = instance.packRoller();
            if (priceLong == null || roller == null) {
                tellError(HINT_BUY_PENDING);
                logWarning("开包跳过：价格/抽取器不可用（packs=" + packs + "）");
                return;
            }
            int price = (int) Math.min(priceLong, Integer.MAX_VALUE);
            PlayerProgress before = progress();
            if (!before.canAfford(price)) {
                tellError(MenuTexts.resolve(displayOrNull(), KEY_PACK_NOT_ENOUGH,
                        PACK_NOT_ENOUGH_FALLBACK,
                        Map.of("price", String.valueOf(price), "gold", String.valueOf(before.gold())),
                        this::logWarning));
                return;
            }
            List<PackRoller.DrawnCard> drawn = new ArrayList<>();
            for (int pack = 0; pack < packs; pack++) {
                drawn.addAll(roller.drawPack(random));
            }
            PlayerProgress spent = before.spendGold(price);
            PackOpening.Result result = PackOpening.open(spent, drawn);
            PlayerProgress next = result.progress();
            for (int pack = 0; pack < packs; pack++) {
                next = next.withPackOpened();
            }
            instance.profileStore().save(player.player(), next);

            tell(MenuTexts.resolve(displayOrNull(), KEY_PACK_RESULT, PACK_RESULT_FALLBACK,
                    Map.of("packs", String.valueOf(packs),
                            "cards", String.valueOf(result.grants().size()),
                            "dust", String.valueOf(result.dustGained())),
                    this::logWarning));
            int shown = 0;
            for (PackOpening.Grant grant : result.grants()) {
                if (shown >= MAX_RESULT_LINES) {
                    break;
                }
                String line = MenuTexts.resolve(displayOrNull(), KEY_PACK_LINE, PACK_LINE_FALLBACK,
                        Map.of("card", cardName(instance, grant.cardId()),
                                // ★★★ 2026-10-21（用户：「抽卡结果显示的稀有度应该是‘普通’、‘史诗’
                                //   而不是‘common’、‘rare’」）⇒ 这里原本印的是
                                //   `grant.rarity().name()`（枚举常量名 = 给开发者看的标识符）
                                //   ⇒ 改走 policy/RarityNames（5 个中文词只有那一处映射）
                                "rarity", RarityNames.zh(grant.rarity()),
                                "gold", grant.gold() ? "金色" : ""),
                        this::logWarning);
                // ★★★ 2026-10-21（用户：「抽到未拥有卡牌时应该标注‘新！’」）⇒ 标记**追加在同一行**末尾
                //   （不另发一条消息，否则一次 10 包会把聊天框刷满一倍）
                if (grant.isNew()) {
                    line = line + " " + MARK_NEW;
                }
                tell(line);
                shown++;
            }
            int hidden = result.grants().size() - shown;
            if (hidden > 0) {
                tell(" ⋯ 还有 " + hidden + " 张（完整结果见日志）");
            }
            logInfo("开包：玩家=" + player.player().getName() + "｜" + packs + " 包｜扣 " + price
                    + " 金币｜" + result.grants().size() + " 张｜转尘 +" + result.dustGained()
                    + "｜金币 " + before.gold() + " → " + next.gold()
                    + "｜尘 " + before.dust() + " → " + next.dust());

            // ★ 刷新界面：余额变了，必须立刻看见（关掉再开 = 与解锁界面同一条口径）
            player.player().closeInventory();
            MenuRegistry.openShop(player.player());
        } catch (Throwable t) {
            tellError("开包时出错（本次可能未完成，金币与卡牌以档案为准；已记进日志）");
            logWarning("开包出错：" + t);
        }
    }

    /** 卡 id → 玩家可见的名字（拿不到卡池 ⇒ 回退 id，绝不因此中断开包）。 */
    private static String cardName(InfernalClashPlugin instance, String cardId) {
        try {
            if (instance.cardCatalog() != null) {
                return instance.cardCatalog().require(cardId).displayName();
            }
        } catch (Throwable ignored) {
            // 卡池里没有这张 id（理论上不可能：抽取器的池子就来自卡池）
        }
        return cardId;
    }

    private void renderBalance() {
        PlayerProgress progress = progress();
        setSlot(MenuLayout.SHOP_BALANCE_SLOT,
                icon(BALANCE_MATERIAL,
                        balanceName(progress.gold(), displayOrNull(), this::logWarning), null));
    }

    private void renderExit() {
        setSlot(MenuLayout.SHOP_EXIT_SLOT,
                icon("shop.exit", Material.BARRIER, exitName(displayOrNull(), this::logWarning), null),
                (who, event) -> {
                    try {
                        who.player().closeInventory();
                    } catch (Throwable t) {
                        logWarning("关界面失败：" + t);
                    }
                });
    }

    /** 两档购买按钮在 {@code display.yml} 里的图标键（{@code docs/03} §5.2 的 {@code icons.shop.*}）。 */
    private static String buyIconKey(int index) {
        return switch (index) {
            case 0 -> "shop.buy1";
            case 1 -> "shop.buy10";
            // ★ 30 包已下线（2026-10-20 用户裁定）；{@code shop.buy30} 作为**退役图标键**仍允许
            //   留在现场 display.yml 里（DisplayConfig.RETIRED_ICON_KEYS），但没有代码读它
            default -> throw new IllegalArgumentException(
                    "buyIconKey：" + index + " 没有对应的图标键（30 包已于 2026-10-20 下线）");
        };
    }

    /** 该规格的总价（{@code economy.yml} 的 {@code pack.price.<包数>}）；没配这个规格 ⇒ {@code null}。 */
    private Long priceOf(int packs) {
        InfernalClashPlugin instance = plugin();
        if (instance == null || instance.economyConfig() == null) {
            return null;
        }
        try {
            EconomyConfig economy = instance.economyConfig();
            return economy.packPrices().get(packs);
        } catch (Throwable t) {
            logWarning("取卡包价格失败（" + packs + " 包）：" + t);
            return null;
        }
    }

    /** 一包几张（出厂 5，{@code pack.cards-per-pack}）；配置拿不到就用出厂值。 */
    private int cardsPerPack() {
        InfernalClashPlugin instance = plugin();
        if (instance != null && instance.economyConfig() != null) {
            try {
                return instance.economyConfig().cardsPerPack();
            } catch (Throwable t) {
                logWarning("取一包张数失败：" + t);
            }
        }
        return PackRoller.SHIPPED_CARDS_PER_PACK;
    }
}
