package io.mczju.infernalclash.ui;

import com.github.mczjuops.mczjugamecore.menu.Menu;
import io.mczju.infernalclash.InfernalClashPlugin;
import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.config.DisplayConfig;
import io.mczju.infernalclash.profile.PlayerProgress;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * ★ <b>三个界面的共同基类</b>（P8）：U2 图鉴+卡组预设 / U4 商店 / U5 图鉴只读。
 *
 * <h2>为什么要有它（{@code docs/04} §16.12.2「落地方式」）</h2>
 * 界面引擎**复用 MGC 的 {@code MenuFacade}**（实测签名见 {@code docs/06}），不自造 GUI 框架。
 * 于是每个界面都要重复三件事：
 * <ol>
 *   <li>权限串（{@code docs/10} §5：{@code /incl shop} 与 {@code /incl cards} 都要
 *       {@code infernalclash.play}）—— 收进 {@link #PERM_PLAY}；</li>
 *   <li>「只读**该玩家自己**的进度」的唯一入口（{@code ProfileStore}，{@code docs/04} §16.12.3）；</li>
 *   <li>「绝不让异常冒到 MGC 里」的失败方向 —— 本类的每个运行期辅助都自带 {@code catch (Throwable)}。</li>
 * </ol>
 *
 * <h2>★★ 实测：{@code Menu} 的<b>构造器</b>就会调用 {@code getRows()} / {@code getTitle()}</h2>
 * {@code javap -c com.github.mczjuops.mczjugamecore.menu.Menu} 显示：
 * {@code Menu(Player, Object...)} → {@code Bukkit.createInventory(this, getRows()*9, TextParser.parse(getTitle()))}。
 * ⇒ 子类的 {@link #getRows()} / 子类的 {@code getTitle()} <b>只许返回静态常量</b>，
 * 不许读实例字段（那时子类字段还是 {@code null}/{@code 0}）。
 * 这与「构造器不要做重活、真正装配放 {@code setup()}」（MGC 用反射 {@code (Player, Object...)} 造对象）
 * 是同一条约束的两面。
 *
 * <h2>★★ 点击链路（实测）：门面按 {@code getRawSlot()} 过滤、{@code Menu} 按 {@code getSlot()} 查表</h2>
 * {@code MenuFacade.onClick} 只把 {@code 0 <= rawSlot < topInventory.getSize()} 的点击转发给
 * {@code Menu.handleClick}，而后者用 {@code event.getSlot()} 去 {@code slotActions} 里查。
 * ⇒ <b>只用顶部箱子</b>（{@link MenuRegistry} 的纯函数保证每个界面的槽位都 &lt; 行数 × 9）。
 *
 * <h2>★ 文案纪律（P8「文案接线」已落地）</h2>
 * 界面标题 / 按钮名 / 金币行**一律经 {@link MenuTexts} 读 {@code display.yml}**（{@code messages.*}）；
 * 读不到（插件未就绪 / 键缺失 / 值仍是占位 / 占位符没给全）就<b>回退到各菜单类里的中文常量</b>
 * 并<b>告警一次</b> —— 即「配置是唯一来源，常量只是兜底」。
 * <ul>
 *   <li>★ 兜底是<b>必须</b>的：{@code command.*} / {@code arena-edit.*} / 一批 {@code errors.*}
 *       的值仍是占位 {@code [待填]}，而 {@code [待填]} <b>绝不</b>能印给玩家
 *       （{@link MenuTexts} 里那条保护）；</li>
 *   <li>★ 每个常量的 javadoc 写明它<b>对应哪把键</b>（接线关系一处可见）；</li>
 *   <li>★ 还没有 {@code display.yml} 键的文案（例 {@code ShopMenu.HINT_BUY_PENDING}、
 *       {@code DeckPresetMenu.HINT_PICK_CARD}）继续用代码常量，等措辞定案时<b>新增键</b>再接线
 *       —— 不发明键、也不发明文案（铁律 8 / 11）。</li>
 * </ul>
 *
 * <h2>★ 铁律 10</h2>
 * 本类<b>只按 UUID 读写该玩家自己的进度</b>，不出现
 * {@code getOnlinePlayers / getEntities / getLivingEntities / getWorlds}。
 */
public abstract class InfernalMenu extends Menu {

    /**
     * 本界面族的权限：{@code infernalclash.play}
     * （{@code docs/10} §5 的权限表；{@code plugin.yml} 里 {@code default: true}）。
     */
    public static final String PERM_PLAY = "infernalclash.play";

    /**
     * 翻页按钮名「前一页」。
     *
     * <p>出处：{@code docs/09} §3.2「{@code 45} —— <b>前一页</b> —— 第 6 行最左」（§5.2 同）。
     * 它在 U2 与 U5 里是**同一段槽位**（§5.2 明写「两个界面可共用同一套 Layout」）
     * ⇒ 文案常量只留**一处**（不许出现第二处事实来源）。
     *
     * <p>★ 它是 {@code icons.collection.prev.name}（**图标名**），不属于本轮接线的
     * 8 条 {@code messages.*} 界面文案 ⇒ 仍用本常量；接线时一并改读（同 {@link #CODEX_BUTTON_NEXT}）。
     */
    public static final String CODEX_BUTTON_PREV = "前一页";

    /**
     * 翻页按钮名「后一页」（出处同 {@link #CODEX_BUTTON_PREV}）。
     *
     * <p>★ 它是 {@code icons.collection.next.name}（图标名），理由同 {@link #CODEX_BUTTON_PREV}。
     */
    public static final String CODEX_BUTTON_NEXT = "后一页";

    /**
     * 图鉴里**未拥有**卡牌的显示名「???」。
     *
     * <p>出处：{@code docs/09} §3.3「未拥有显示为 {@code "???"}」（【复用】PocketMine 的
     * {@code pokedex}：未捕捉 = 黑色混凝土 + {@code "???"}）；材质见 {@code docs/03} §5.2 的
     * {@code icons.collection.locked}。
     *
     * <p>★ 它是 {@code icons.collection.locked.name}（图标名），理由同 {@link #CODEX_BUTTON_PREV}。
     */
    public static final String CODEX_NAME_LOCKED = "???";

    private final MiniMessage mini = MiniMessage.miniMessage();

    /**
     * @param player 打开界面的玩家（由 MGC 传入）
     * @param args   界面参数（本项目的约定见 {@link MenuRegistry#openDeck(Player, int)}）
     */
    protected InfernalMenu(Player player, Object... args) {
        super(player, args);
    }

    // ------------------------------------------------------------------ Menu 契约

    /**
     * 三个界面共用一个权限（{@code docs/10} §5）—— {@code Menu.open()} 自己会判它，
     * 判不过就**静默 return**（实测），所以「给玩家一句可读提示」在
     * {@link MenuRegistry} 的打开入口里另做一次。
     */
    @Override
    protected final String getPermission() {
        return PERM_PLAY;
    }

    // ------------------------------------------------------------------ 运行期辅助（全部 fail-safe）

    /** 本插件的实例；未启用 / 已停用 ⇒ {@code null}（**绝不抛**）。 */
    protected final InfernalClashPlugin plugin() {
        return InfernalClashPlugin.getInstance();
    }

    /** 日志器（插件拿不到时退回 JDK 日志 —— 装配失败必须留痕，不许静默）。 */
    protected final Logger logger() {
        InfernalClashPlugin instance = plugin();
        return instance == null ? Logger.getLogger("InfernalClash") : instance.getLogger();
    }

    /**
     * 本插件的 {@code display.yml}（已校验）；<b>拿不到就是 {@code null}</b>，绝不抛。
     *
     * <p>★ 文案的读取一律经 {@link MenuTexts#resolve}（它负责「读不到 ⇒ 回退常量 + 告警一次」），
     * 本方法只回答「配置在不在」。
     *
     * <p>★ <b>必须能在 MGC 的 {@code Menu} 构造器里被调用</b>：{@code getTitle()} 是构造期回调，
     * 那时本类的实例字段还没初始化 ⇒ 这里只碰<b>静态单例</b>（{@code InfernalClashPlugin.getInstance()}），
     * 不读任何字段。
     */
    protected final DisplayConfig displayOrNull() {
        InfernalClashPlugin instance = plugin();
        if (instance == null) {
            return null;
        }
        try {
            return instance.display();
        } catch (Throwable t) {
            // 拿不到配置不是「界面不能开」的理由：文案回退常量，界面照旧可用
            return null;
        }
    }

    /** 打一条告警（界面里的任何失败都要能被服主看见）。 */
    protected final void logWarning(String message) {
        try {
            logger().warning("[IC] 界面：" + message);
        } catch (Throwable ignored) {
            // 日志本身失败不能再造出第二个失败
        }
    }

    /** 打一条普通日志（成功路径也要留痕：带数量才好对账）。 */
    protected final void logInfo(String message) {
        try {
            logger().info("[IC] 界面：" + message);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /**
     * 读**这名玩家自己**的进度（{@code docs/04} §16.12.3：开界面只读该玩家自己的进度）。
     *
     * <p>★ 失败方向沿用 {@code ProfileStore} 的口径：拿不到档案 ⇒ 空白进度 + 告警，
     * <b>绝不抛</b>（抛出去会让点击事件炸掉）。真正的「打不开」判断在
     * {@link MenuRegistry} 的入口做（那里能先把话说清、根本不打开界面）。
     */
    protected final PlayerProgress progress() {
        PlayerProgress fallback = PlayerProgress.fresh(player.player().getUniqueId());
        InfernalClashPlugin instance = plugin();
        if (instance == null) {
            return fallback;
        }
        try {
            return instance.profileStore().load(player.player().getUniqueId());
        } catch (Throwable t) {
            logWarning("读进度失败：" + t);
            return fallback;
        }
    }

    /**
     * 卡池快照（**保序**：{@code CardCatalog} 按 id 升序装进 {@code LinkedHashMap}）
     * —— 直接喂给 {@code PresetRules.validate(cards, progress, knownCardIds)}。
     * 拿不到 ⇒ 空表（此时所有卡都会被报「不是已知的卡牌 id」，这是**有信息的**失败，不是静默通过）。
     */
    protected final Set<String> knownCardIds() {
        InfernalClashPlugin instance = plugin();
        if (instance == null || instance.cardCatalog() == null) {
            return Set.of();
        }
        try {
            return instance.cardCatalog().cards().keySet();
        } catch (Throwable t) {
            logWarning("取卡池快照失败：" + t);
            return Set.of();
        }
    }

    /** 全部卡牌（**保序**：id 升序）—— 图鉴分页与卡位显示都用它。 */
    protected final List<Card> allCards() {
        InfernalClashPlugin instance = plugin();
        if (instance == null || instance.cardCatalog() == null) {
            return List.of();
        }
        try {
            // ★★★ 2026-10-20（用户：「让卡牌在图鉴里按照 基础-普通-稀有-史诗-传说 的顺序排列？
            //   排列无视金卡」）⇒ 排序收在纯函数 CardOrder 一处（图鉴的三个界面都用这里）；
            //   ★ 稳定排序 + 只比稀有度 ⇒ 同档内仍是卡池原顺序；金卡是"同一张卡+品质标记"，天然不受影响。
            return io.mczju.infernalclash.card.CardOrder
                    .codexOrder(List.copyOf(instance.cardCatalog().cards().values()));
        } catch (Throwable t) {
            logWarning("取卡池失败：" + t);
            return List.of();
        }
    }

    /** 按 id 取一张卡；卡池里没有（脏存档 / 被移除的卡）⇒ {@code null}。 */
    protected final Card cardOf(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            return null;
        }
        InfernalClashPlugin instance = plugin();
        if (instance == null || instance.cardCatalog() == null) {
            return null;
        }
        try {
            return instance.cardCatalog().cards().get(cardId);
        } catch (Throwable t) {
            logWarning("取卡牌 " + cardId + " 失败：" + t);
            return null;
        }
    }

    /**
     * 卡牌图标 —— ★ <b>复用 {@code CardItems.cardItem(card)}</b>
     * （材质来自卡牌 {@code icon}、显示名与 lore 来自卡牌配置、身份写进 PDC：只有那一处实现）。
     */
    protected final ItemStack cardIcon(Card card) {
        InfernalClashPlugin instance = plugin();
        if (instance != null && instance.cardItems() != null) {
            try {
                return instance.cardItems().cardItem(card);
            } catch (Throwable t) {
                logWarning("造卡牌图标失败（" + card.id() + "）：" + t);
            }
        }
        return icon(Material.RECOVERY_COMPASS, card.displayName(), List.of());
    }

    /**
     * 卡牌图标 + 金色标记（{@code docs/08} §3.3：图鉴三态的 {@code GOLD}）。
     *
     * <p>★★ 2026-10-18 更正：原先这里**只加附魔光效、不改名字颜色**，
     * 而注释写着「R53 的『名称颜色金色』由卡牌配置的颜色承担」——
     * 那是**错的**：卡牌配置里的颜色是**稀有度色**（白/绿/蓝/紫/红），根本不是金色。
     * ⇒ 用户实机报「**部分卡牌没有被替换成金色**」。
     * <p>现在改为：{@code glint == true} ⇒ 走 {@code CardItems#cardItem(card, true)}
     * （名字套 {@code <gold>} 并剥掉原稀有度色 ＋ 附魔光效）。
     * ★ 判据仍是同一个 {@code glint} 参数，调用点一处都不用改。
     */
    protected final ItemStack cardIcon(Card card, boolean glint) {
        return cardIcon(card, glint, false);
    }

    /**
     * ★★★ <b>卡牌图标 + 金色标记 + 「要不要带风味描述」</b>（2026-10-19 新增）
     *
     * <h2>出处（用户逐字）</h2>
     * 「现在游戏内**完全看不到卡牌的风味描述**，请你在**图鉴界面**加上」。
     *
     * <p>★ 只有**图鉴格**与**解锁确认**传 {@code withFlavor=true}；
     * 选卡界面（U4）与战斗快捷栏保持 {@code false}（那里的 tooltip 越短越好）。
     * ★ 默认走 {@code false} 重载 ⇒ **既有 11 个调用点行为一字未变**。
     *
     * @param glint      金色（图鉴三态的 {@code GOLD}）
     * @param withFlavor lore 末尾追加一行斜体风味（见 {@code CardItems#cardItem(Card, boolean, boolean)}）
     */
    protected final ItemStack cardIcon(Card card, boolean glint, boolean withFlavor) {
        InfernalClashPlugin instance = plugin();
        if (instance != null && instance.cardItems() != null) {
            try {
                return instance.cardItems().cardItem(card, glint, withFlavor);
            } catch (Throwable t) {
                logWarning("造卡牌图标失败（" + card.id() + "，golden=" + glint
                        + "，flavor=" + withFlavor + "）：" + t);
            }
        }
        ItemStack stack = icon(Material.RECOVERY_COMPASS, card.displayName(), List.of());
        if (glint) {
            try {
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    meta.setEnchantmentGlintOverride(Boolean.TRUE);
                    stack.setItemMeta(meta);
                }
            } catch (Throwable t) {
                logWarning("金色标记失败（" + card.id() + "）：" + t);
            }
        }
        return stack;
    }

    /** 未拥有（图鉴三态 {@code NONE}）的占位图标：黑色混凝土 + {@code "???"}（{@code docs/09} §3.3）。 */
    protected final ItemStack lockedIcon() {
        return icon("collection.locked", Material.BLACK_CONCRETE, CODEX_NAME_LOCKED, null);
    }

    /**
     * ★★★ <b>未拥有卡的图标</b>（2026-10-20 用户裁定；替换掉 {@link #lockedIcon()} 的 {@code "???"}）。
     *
     * <h2>用户原话（逐字）</h2>
     * > 「**卡牌解锁前是「？？？」，无法得知解锁的这张牌是什么，
     * > 应该显示卡牌名称和效果，只有图标用黑色混凝土**」
     *
     * <p>⇒ 材质 = {@link io.mczju.infernalclash.card.CardItems#LOCKED_MATERIAL}（黑色混凝土），
     * <b>名字与 lore（费用 / 冷却 / 效果 / 风味）用真实卡牌内容</b>——
     * 生成逻辑复用 {@code CardItems#lockedCardItem}，与已拥有的卡是同一份实现
     * （lore 绝不写第二份，否则"未解锁卡显示什么"会随卡牌改动分叉）。
     *
     * <p>★ 失败方向：拿不到 {@code CardItems} / 卡为 {@code null} ⇒ 退回旧占位图标
     * （宁可显示 {@code "???"}，也不能让界面崩成空格）。
     */
    protected final ItemStack lockedCardIcon(Card card) {
        try {
            InfernalClashPlugin plugin = plugin();
            if (plugin != null && plugin.cardItems() != null && card != null) {
                return plugin.cardItems().lockedCardItem(card);
            }
        } catch (Throwable t) {
            logWarning("未解锁卡的图标构建失败（" + (card == null ? "null" : card.id()) + "）：" + t);
        }
        return card == null
                ? lockedIcon()
                : icon(Material.BLACK_CONCRETE, card.displayName(), List.of());
    }

    /** 图鉴 / 卡组 / 选卡三个界面的「这张卡还没解锁」提示（★ 2026-10-20 起它只作兜底文案：点它会打开解锁界面）。 */
    public static final String HINT_UNLOCK_OPENED =
            "这张卡还没有解锁 —— 已打开解锁界面（用烈焰粉解锁）。";

    /**
     * 通用图标：材质**优先取自 {@code display.yml} 的 {@code icons.<key>.material}**，
     * 取不到（键缺失 / 值是占位 / 插件没起来）⇒ 用调用方给的兜底材质。
     *
     * <p>★ 名字与 lore 由调用方给（标题 / 按钮名走 {@link MenuTexts} 读 {@code messages.*}，
     * 见类注释）；这里<b>不</b>读 {@code icons.*.name} —— 图标名与 {@code messages.*}
     * 是两组键，本轮只接了 8 条 {@code messages.*}，这里**不偷偷多读一处**。
     */
    protected final ItemStack icon(String iconKey, Material fallback, String name, List<String> lore) {
        Material material = fallback;
        InfernalClashPlugin instance = plugin();
        if (instance != null && instance.display() != null) {
            try {
                Material parsed = Material.matchMaterial(instance.display().icon(iconKey).material());
                if (parsed != null) {
                    material = parsed;
                }
            } catch (Throwable t) {
                logWarning("图标 " + iconKey + " 取不到，用兜底材质 " + fallback + "：" + t);
            }
        }
        return icon(material, name, lore);
    }

    /** 通用图标（材质写死在调用方 —— 只用于 {@code display.yml} 还没有对应 {@code icons} 键的格子）。 */
    protected final ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(mini.deserialize(name));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(mini.deserialize(line));
                }
                meta.lore(lines);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ------------------------------------------------------------------ 给玩家的话

    /** 中性提示（走 MiniMessage 解析，便于将来直接换成 {@code display.yml} 的文案）。 */
    protected final void tell(String text) {
        send(text, NamedTextColor.WHITE);
    }

    /** 成功提示（绿色）。 */
    protected final void tellOk(String text) {
        send(text, NamedTextColor.GREEN);
    }

    /** 失败提示（红色）—— 「拿不到进度 / 参数不对 / 校验不通过」一律走它。 */
    protected final void tellError(String text) {
        send(text, NamedTextColor.RED);
    }

    /** 发消息（★ 绝不外抛：连发消息都不许把异常冒回 MGC）。 */
    private void send(String text, NamedTextColor color) {
        try {
            player.player().sendMessage(mini.deserialize(text).colorIfAbsent(color));
        } catch (Throwable t) {
            logWarning("给玩家发消息失败：" + t);
        }
    }
}
