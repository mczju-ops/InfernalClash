package io.mczju.infernalclash.card;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ <b>卡牌物品与「战斗」物品的构造 / 识别</b>（快捷栏 R18、Q9）。
 *
 * <h2>为什么卡牌身份放在 PDC 而不是比较物品</h2>
 * 所有卡牌是**同一种物品**（`docs/07` §7.2：R10 说全部基于追溯指南针）——
 * 靠 `ItemStack#isSimilar` 或材质根本分不出是哪张卡。
 * ⇒ 卡牌 id 写进 **PDC**（{@link #KEY_CARD}），右键时从手持物读回来。
 * 这与「归属靠 PDC、不靠类型」是同一条纪律。
 *
 * <h2>外形（本阶段的诚实说明）</h2>
 * <ul>
 *   <li>★ 卡牌的 {@code icon} 字段目前是**占位值**（等于卡牌 id，物品包还没做）⇒
 *       {@link #cardItem} 先尝试按材质名解析，解析不了就用 {@link #FALLBACK_CARD_MATERIAL}
 *       （追溯指南针，`docs/07` §7.2 的基准物品）。真正的图标（ItemCreator）属于 **P8**。</li>
 *   <li>显示名与 lore 取自卡牌（含 MiniMessage 标记）—— 文本**来源是卡牌配置**，
 *       不是硬编码在代码里（R76 的口径）。</li>
 *   <li>⚠️ 「战斗」物品的材质文档里**没有规定**（`docs/05` Q9 只说它是个菜单）
 *       ⇒ 本阶段用 {@link #BATTLE_ITEM_MATERIAL} 占位，并记在 `docs/04` §17（观感，可否决）。</li>
 * </ul>
 */
public final class CardItems {

    /** PDC 键：卡牌 id。 */
    public static final String KEY_CARD = "card";

    /** PDC 键：标记「这是战斗物品」。 */
    public static final String KEY_BATTLE = "battle";

    /** PDC 键：标记「这是准备阶段的选卡菜单物品」（{@code docs/02} §2 的 R15）。 */
    public static final String KEY_PREP_MENU = "prep_menu";

    /**
     * ★★ PDC 键：标记「这张卡是**金色版本**」（2026-10-20 新增）。
     *
     * <h2>为什么要写进 PDC（而不只是把名字染成金色）</h2>
     * 用户 2026-10-20 报：「**金卡召唤/释放时的金色粒子特效没有添加**」。
     * 要播金色粒子，施放时就必须知道手里的这张卡是不是金卡；
     * 而 2026-10-20 之前金色**只体现在外观**（名字 {@code <gold>} + 附魔光效）——
     * 一离开物品栏就丢了。⇒ 现在写成 PDC 字节，和卡牌身份（{@link #KEY_CARD}）同一口径：
     * <b>身份与稀有版本都只认 PDC，不认外观</b>。
     */
    public static final String KEY_GOLD = "gold";

    /** 卡牌解析不出图标时的兜底材质（`docs/07` §7.2：所有卡牌都基于追溯指南针）。 */
    public static final Material FALLBACK_CARD_MATERIAL = Material.RECOVERY_COMPASS;

    /**
     * ★★ <b>「未解锁」卡的图标材质 = 黑色混凝土</b>（2026-10-20 用户裁定，逐字见
     * {@link #lockedCardItem}）。
     *
     * <p>★ 它与 {@code display.yml} 的 {@code icons.collection.locked.material} 是**同一件事**；
     * 这里留常量是为了「读不到配置时仍能画出黑色混凝土」
     * （失败方向：宁可是黑色方块，也不把未解锁的卡画成真卡）。
     */
    public static final Material LOCKED_MATERIAL = Material.BLACK_CONCRETE;

    /**
     * ★★★ <b>「战斗」物品的材质 = {@code CLOCK}（钟）</b>。
     *
     * <h2>用户 2026-10-17 原话（逐字，留档 {@code docs/24}）</h2>
     * > 「投降菜单没有制作，并且是一个<b>真正的可以使用的盾牌</b>。
     * > <b>你可以把它设置成一个钟，这样玩家就无法与其互动了</b>」
     *
     * <p>★「真正的可以使用的盾牌」是什么意思：{@code SHIELD} 在**右键时会举盾**
     * ⇒ 与「右键打牌」抢事件（也就是用户说的"可以与它互动"）。
     * 钟没有任何右键行为 ⇒ 玩家无法与它互动，右键只会落到我们自己的监听器上。
     *
     * <p>★ 改这一个常量即可（这就是"文档未规定，我取占位值、你一句话就能换"的形状）；
     * 出厂取值由 {@code ShippedConfigTest#shippedBattleItemIsAClockAsTheUserAsked} 钉住。
     */
    public static final Material BATTLE_ITEM_MATERIAL = Material.CLOCK;

    /**
     * 「选卡菜单」物品的材质（R15）。
     *
     * <p>★ 与「战斗」物品（{@link #BATTLE_ITEM_MATERIAL} = {@code CLOCK}）**必须不同**：
     * 两件物品都在 1 号格、且都靠右键触发（一个开 U4、一个开战斗菜单）——
     * 靠 PDC 当然也分得开，但形状相同会让玩家与排障者都误判
     * （★ 观感项，文档未规定，我用 {@code BOOK}；要换只改这一行）。
     */
    public static final Material PREP_MENU_MATERIAL = Material.BOOK;

    private final NamespacedKey cardKey;
    private final NamespacedKey battleKey;
    private final NamespacedKey prepMenuKey;
    private final NamespacedKey goldKey;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public CardItems(Plugin plugin) {
        this.cardKey = new NamespacedKey(plugin, KEY_CARD);
        this.battleKey = new NamespacedKey(plugin, KEY_BATTLE);
        this.prepMenuKey = new NamespacedKey(plugin, KEY_PREP_MENU);
        this.goldKey = new NamespacedKey(plugin, KEY_GOLD);
    }

    /**
     * ★★★ <b>一张卡对应的物品材质</b>（{@code icon} 解析不出来 ⇒ {@link #FALLBACK_CARD_MATERIAL}）。
     *
     * <h2>为什么把它抽成一个方法（2026-10-18）</h2>
     * 同一条规则（{@code Material.matchMaterial(icon) ＋ 兜底}）原先在<b>四处</b>各写了一遍：
     * 造卡牌物品（本类）、上 / 撤冷却遮罩（{@code game/CardCastListener}）、
     * 开局清遮罩（{@code game/CardLoadout}），以及本轮新增的
     * 「{@code REFRESH_COOLDOWN} 清客户端遮罩」（{@code spell/EffectApplier}）。
     * ★ 这正是 {@code docs/11} §R12 那个「第二处白名单」的形状：四处各写一次，
     * 将来只要有一处忘了跟改（例如换成"图标键"），就会出现「服务端认为可以用了、
     * 客户端物品还是暗的」那种只在实机看得见的缺陷。⇒ 判据只留这一处。
     *
     * @param card 卡牌；{@code null} ⇒ 兜底材质（调用方不必判空）
     */
    public static Material materialOf(Card card) {
        if (card == null) {
            return FALLBACK_CARD_MATERIAL;
        }
        Material material = Material.matchMaterial(card.icon());
        return material == null ? FALLBACK_CARD_MATERIAL : material;
    }

    /** 造一张卡牌物品（1 个）。 */
    public ItemStack cardItem(Card card) {
        return cardItem(card, false);
    }

    /**
     * ★★★ <b>卡牌 lore 的文本行</b>（MiniMessage 原文，**纯函数、与物品无关**）——
     * 2026-10-19 新增。
     *
     * <h2>为什么把它从 {@link #cardItem} 里抽出来</h2>
     * {@code new ItemStack(Material)} 在离线会抛（`AGENTS.md` §4.1：`Registry` 要服务端
     * `RegistryAccess`）⇒ 「lore 到底有几行、风味在不在」这件事**没法用物品断言**。
     * 抽成纯函数之后，它在 JUnit 里逐行可断言（{@code card/CardItemFlavorTest}），
     * 而 {@code cardItem} 只剩"把文本变成 Component"这一步。
     *
     * @param withFlavor {@code true} ⇒ 末尾追加「空行 ＋ 一行灰色斜体风味」；
     *                   **风味为空 / 空白 ⇒ 什么都不加**
     * @return 保序的文本行；**空串 = 留白行**（调用方要转成空 {@code Component}）
     */
    public static List<String> loreLines(Card card, boolean withFlavor) {
        return loreLines(card, withFlavor, null);
    }

    /**
     * ★★★ <b>风味那一行的实际渲染</b>（2026-10-20 用户裁定：**紫色斜体**）。
     *
     * <h2>用户原话（逐字）</h2>
     * > 「**flavor的颜色可以改成紫色的斜体，其他的文本就不要用斜体了**」
     *
     * <p>⇒ 风味行 = `<light_purple><italic>…</italic></light_purple>`；
     * <b>其余每一行都不带斜体、不改颜色</b>（它们本来就是各卡自己的 MiniMessage 颜色）。
     * ★ 标签**成对闭合**：MiniMessage 的样式会延续到后续行，只开不闭会把"下面所有东西"都染成紫色斜体。
     */
    public static String flavorLine(String flavor) {
        return "<light_purple><italic>" + flavor + "</italic></light_purple>";
    }

    /** 未解锁卡的 flavor 占位（用户 2026-10-20：「未解锁卡牌可以在 flavor 的位置写『未解锁』」）。 */
    public static final String LOCKED_FLAVOR_TEXT = "未解锁";

    /**
     * ★ 同 {@link #loreLines(Card, boolean)}，但可以**替换**风味那一行
     * （未解锁卡用它把真实 flavor 换成「{@link #LOCKED_FLAVOR_TEXT}」）。
     *
     * @param flavorOverride {@code null} ⇒ 用卡自己的 flavor；非 {@code null} ⇒ 用这一句
     */
    public static List<String> loreLines(Card card, boolean withFlavor, String flavorOverride) {
        List<String> lines = new ArrayList<>();
        lines.add("<gray>费用 <white>" + card.cost()
                + " <gray>｜冷却 <white>" + (card.cooldownTicks() / 20) + "s");
        lines.addAll(card.description());
        if (withFlavor) {
            String flavor = flavorOverride != null ? flavorOverride : card.flavor();
            if (flavor != null && !flavor.isBlank()) {
                // ★ 空一行再放风味：让"机制说明"与"风味"在视觉上分开（原版物品也是这么排的）
                lines.add("");
                lines.add(flavorLine(flavor));
            }
        }
        return lines;
    }

    /**
     * 造一张卡牌物品，可选「金色版本」（**不带风味描述** —— 战斗快捷栏 / 选卡界面用）。
     *
     * @param golden {@code true} ⇒ 名字套 {@code <gold>} 并加附魔光效；{@code false} ⇒ 原样
     */
    public ItemStack cardItem(Card card, boolean golden) {
        return cardItem(card, golden, false);
    }

    /**
     * ★★★ <b>造一张卡牌物品，可选「金色版本」与「带风味描述」</b>
     *
     * <h2>出处（用户 2026-10-19 逐字）</h2>
     * 「现在游戏内**完全看不到卡牌的风味描述**，请你在**图鉴界面**加上」。
     *
     * <h2>为什么是一把开关，而不是直接加到所有卡牌物品上</h2>
     * 卡牌物品有**三个来源**（图鉴 / 选卡界面 / 战斗快捷栏），它们都走本类造物品
     * （见 {@code CardItems#cardItem} 的说明）。而风味那一行只在**图鉴**里是有价值的阅读内容；
     * 战斗中快捷栏的 tooltip 越短越好（多两行会盖住卡面）。
     * ⇒ 由调用方决定：图鉴（U2/U5）与解锁确认传 {@code true}，其余保持 {@code false}
     * （**其余界面的物品一字未变**）。
     *
     * <p>★ 风味渲染成 `<gray><italic>` 一行；**空 / 空白风味不加任何东西**
     * （不给 tooltip 留一条空荡荡的斜体行）。
     *
     * <h2>★ 金色版本为什么必须在本类改（2026-10-18 用户裁定「名字一律金色」）</h2>
     * 卡牌物品的三个来源**都**走本类造物品 ⇒ 只在菜单层套金色的话，
     * 战斗中的快捷栏就不是金的（那正是用户当时报的「部分卡牌没有被替换成金色」）。
     * ★ 而且必须**先剥掉原来的稀有度色标签**再套 `<gold>`：MiniMessage 内层标签优先，
     * 直接加前缀会让名字仍是白/绿/蓝/紫/红（复用 {@code policy/SideNameColor#stripColorTags}）。
     *
     * @param golden     {@code true} ⇒ 名字套 {@code <gold>} 并加附魔光效；{@code false} ⇒ 原样
     * @param withFlavor {@code true} ⇒ lore 末尾追加一行 `<gray><italic>` 风味描述
     */
    public ItemStack cardItem(Card card, boolean golden, boolean withFlavor) {
        return buildCardItem(card, golden, withFlavor, materialOf(card), null);
    }

    /**
     * ★★★ <b>「未解锁」的卡牌图标</b>（2026-10-20 用户裁定）。
     *
     * <h2>用户原话（逐字）</h2>
     * > 「卡牌解锁界面未实现，因此卡牌无法用烈焰粉解锁。并且<b>卡牌解锁前是「？？？」，
     * > 无法得知解锁的这张牌是什么，应该显示卡牌名称和效果，只有图标用黑色混凝土</b>」
     *
     * <p>⇒ 口径：**图标 = 黑色混凝土，名字与 lore（费用/冷却/效果/风味）= 真实卡牌内容**。
     * ★ 与以前那个 {@code "???"} 占位（{@code docs/09} §3.3）的差别只有材质与名字，
     * 其余与已拥有的卡**完全相同** —— 因为用的是同一个 {@link #buildCardItem}
     * （lore 逻辑绝不许写第二份，否则"未解锁卡显示什么"会随卡牌改动而分叉）。
     *
     * <p>★ 为什么让玩家看到效果：他要用烈焰粉"买"这张卡，看不到效果等于闭着眼睛买
     * （用户这句话就是这么来的）。
     */
    public ItemStack lockedCardItem(Card card) {
        // ★★ 2026-10-20（用户：「图鉴里的未解锁卡牌可以在 flavor 的位置写『未解锁』
        //   —— 未解锁卡牌看不到，也不应该看到 flavor，反正也是空着」）
        //   ⇒ withFlavor = true（要那一行）＋ flavorOverride = 「未解锁」（**不泄露真实 flavor**）。
        return buildCardItem(card, false, true, LOCKED_MATERIAL, LOCKED_FLAVOR_TEXT);
    }

    /**
     * 造卡牌物品的唯一实现（材质由调用方决定）。
     *
     * @param material 图标材质（普通卡 = {@link #materialOf(Card)}；未解锁 = {@link #LOCKED_MATERIAL}）
     */
    private ItemStack buildCardItem(Card card, boolean golden, boolean withFlavor, Material material,
                                     String flavorOverride) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = golden
                    ? "<gold>" + io.mczju.infernalclash.policy.SideNameColor
                            .stripColorTags(card.displayName())
                    : card.displayName();
            meta.displayName(mini.deserialize(name));
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines(card, withFlavor, flavorOverride)) {
                // ★ 空串 = 分行用的空行（lore 里留白，见 loreLines 的说明）
                lore.add(line.isEmpty() ? Component.empty() : mini.deserialize(line));
            }
            meta.lore(lore);
            // ★ 身份在 PDC：卡牌物品的材质**不再全部相同**（2026-10-18 起每卡一个真实材质），
            //   但身份仍然只认 PDC —— 材质随时可改，PDC 才是判据
            meta.getPersistentDataContainer().set(cardKey, PersistentDataType.STRING, card.id());
            if (golden) {
                // ★ 金色版本的视觉标记（与名字的 <gold> 一起用；★ 失败只告警，绝不影响物品可用性）
                try {
                    meta.setEnchantmentGlintOverride(Boolean.TRUE);
                } catch (Throwable ignored) {
                    // 旧版本没有这个 API —— 名字已经是金色了，光效只是锦上添花
                }
                // ★★★ 2026-10-20：**把金卡身份写进 PDC** —— 施放时要靠它播金色粒子
                //   （见 KEY_GOLD 的 javadoc；只染名字的话一离开物品栏就丢了）
                meta.getPersistentDataContainer().set(goldKey, PersistentDataType.BYTE, (byte) 1);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 造 1 号格的「投降」物品。 */
    public ItemStack battleItem(String displayName) {
        return battleItem(displayName, battleItemLore());
    }

    /**
     * 造 1 号格的「投降」物品（名字与 lore 都可由 {@code display.yml} 提供）。
     *
     * <p>★ 2026-10-21：lore 也走配置 —— 之前它硬编码在这个方法里（R76 的漏网点）。
     */
    public ItemStack battleItem(String displayName, List<String> lore) {
        ItemStack stack = new ItemStack(BATTLE_ITEM_MATERIAL);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(mini.deserialize(displayName));
            // ★ 2026-10-17 用户裁定 E26：物品改名「投降」⇒ lore 也照着它说
            //   （原来写的是「右键打开战斗菜单（投降等）」—— 名字与方法都对不上了）
            List<String> lines = lore == null || lore.isEmpty() ? battleItemLore() : lore;
            meta.lore(lines.stream().map(mini::deserialize).toList());
            meta.getPersistentDataContainer().set(battleKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 从手持物读出卡牌 id；不是卡牌则 {@code null}。 */
    public String cardIdOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(cardKey, PersistentDataType.STRING);
    }

    /** 是不是「战斗」物品。 */
    public boolean isBattleItem(ItemStack stack) {
        return hasFlag(stack, battleKey);
    }

    /**
     * ★★ <b>这张卡是不是金色版本</b>（2026-10-20 新增；判据 = PDC 的 {@link #KEY_GOLD}）。
     *
     * <p>★ 失败方向：物品为 {@code null} / 空 / 没有元数据 / 没有这个键 ⇒ {@code false}。
     * 把「认不出来」当成普通卡，<b>绝不反过来猜成金卡</b>
     * （那会让普通卡也冒金色粒子，属于"改观感"）。
     */
    public boolean isGolden(ItemStack stack) {
        return hasFlag(stack, goldKey);
    }

    /**
     * ★ <b>造「选卡菜单」物品</b>（准备阶段 1 号格；{@code docs/02} §2 的 R15）。
     *
     * <p>出处：{@code docs/09} §5.4「准备时间内玩家持有『选卡菜单』物品；
     * <b>对任何位置右键</b>可以再次打开该 UI；打开后进入的是<b>就是 U4 本身</b>（【Q37】）」。
     *
     * <p>★ 显示名与 lore 由调用方从 {@code display.yml} 取（本类不读配置：
     * 它是纯物品构造层），所以这里只收两个字符串参数。
     */
    public ItemStack prepMenuItem(String displayName, List<String> lore) {
        ItemStack stack = new ItemStack(PREP_MENU_MATERIAL);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(mini.deserialize(displayName));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(mini.deserialize(line));
                }
                meta.lore(lines);
            }
            meta.getPersistentDataContainer().set(prepMenuKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * ★ <b>是不是「选卡菜单」物品</b>（{@code PlayerInteractEvent} 的门禁判据）。
     *
     * <p>★ 纪律（{@code docs/09} §5.4 的【AI 补充 · 安全】）：
     * 右键监听**必须过滤到手持的是选卡菜单物品**，绝不无过滤地
     * {@code setCancelled(true)}（那会打死大厅菜单与别的玩法）。
     * ⇒ 判据就是这一个方法，且它走 PDC（与卡牌物品、战斗物品同一口径）。
     */
    public boolean isPrepMenuItem(ItemStack stack) {
        return hasFlag(stack, prepMenuKey);
    }

    /** PDC 里有没有这个 BYTE 标记（三种物品的判据共用它，避免各写一遍空值判断）。 */
    private static boolean hasFlag(ItemStack stack, NamespacedKey key) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * 1 号格那个物品的显示名（= **「投降」**）。
     *
     * <p>★★ <b>2026-10-17 用户裁定（E26 结案，逐字）</b>：
     * 「**战斗菜单的物品改名『投降』**，内部只有两个按钮『**确认投降**』和『**取消**』，
     * **对称排布**」⇒ 这一格从 `"<gold>战斗"` 改成 `"<gold>投降"`。
     *
     * <p>★ 它与 {@code docs/02} §6.1 的「「战斗」物品 = 战斗菜单」在**名字**上分叉了 ——
     * 但那是**用户当场的裁定**（文档写的是底座期的设想），所以以用户原话为准
     * （铁律 11：用户改了口径就照用户的；文档已同步，见 {@code docs/02} §6.1 的裁定块）。
     * ★ 界面标题读的是 {@code display.yml} 的 {@code messages.match.surrender}（同一个词）。
     */
    public static String battleItemName() {
        return "<gold>投降";
    }

    /**
     * ★★★ 「投降」物品的 lore**回退常量**（正常路径读 {@code display.yml} 的
     * {@code messages.match.surrender-hint}，由 {@code CardLoadout} 经 {@code MenuTexts} 取）。
     *
     * <p>2026-10-21：用户要求「所有会显示在游戏内的文本都要能被 {@code display.yml} 覆盖」
     * ⇒ 这一行原先**硬编码在 {@link #battleItem(String)} 里**（全项目最后一处漏网的玩家可见文本），
     * 现在与「选卡菜单」物品同一条路（名字 + lore 都走配置，取不到才回退到这里）。
     */
    public static List<String> battleItemLore() {
        return List.of("<gray>右键打开投降菜单（确认投降 / 取消）");
    }

    /**
     * 「选卡菜单」物品的显示名**回退常量**（正常路径读 {@code display.yml} 的
     * {@code messages.match.prep-menu}，由 {@code CardLoadout} 经 {@code MenuTexts} 取）。
     */
    public static String prepMenuItemName() {
        return "<gold>选卡菜单";
    }

    /**
     * 「选卡菜单」物品的 lore**回退常量**（正常路径读
     * {@code messages.match.prep-menu-lore}，见 {@code docs/03} §5.2 的新键说明）。
     */
    public static List<String> prepMenuItemLore() {
        return List.of("<gray>右键任意位置打开选卡界面（准备阶段内可反复重开）");
    }
}
