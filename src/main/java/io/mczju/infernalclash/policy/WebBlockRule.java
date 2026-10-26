package io.mczju.infernalclash.policy;

import java.util.Locale;
import java.util.Set;

/**
 * ★★★ <b>「这个方块能不能被蜘蛛网替换」的判据</b>（纯函数）—— 1.1.0「囚笼编织者」。
 *
 * <h2>出处（{@code docs/1.1卡牌.txt}，逐字）</h2>
 * 「该蜘蛛会在攻击目标处<b>结网</b>，网会存在 8 秒」＋
 * 「织网仿照 IG 的『webber』词条，写<b>生成、接管和删除</b>蜘蛛网的逻辑。
 * 场地和 ig 一样是大平地不用担心替换方块，<b>除了岩浆，不要替换岩浆</b>」。
 *
 * <h2>★ 用户 2026-10-26 追问后的裁定（比原文更严）</h2>
 * 「<b>只替换空气与「可替换方块」（草、花、水、雪层等），岩浆、石头、地板结构一律不碰</b>」。
 * ⇒ 判据不是"除岩浆外都行"，而是<b>只碰软方块</b>：
 * <ol>
 *   <li><b>固体方块一律不碰</b>（石头 / 木头 / 地板 —— 这一条同时挡住了"把竞技场地板换成蜘蛛网"
 *       那种最贵的破坏）；</li>
 *   <li><b>岩浆 / 火一律不碰</b>（岩浆是竞技场的河，用户点名过；火会烧掉网）；</li>
 *   <li>其余（空气 / 草 / 花 / 水 / 雪层 / 藤蔓…）⇒ <b>可以结网</b>。</li>
 * </ol>
 *
 * <h2>为什么判据在纯类里</h2>
 * {@code Material#isSolid()} 要服务端注册表，而"哪些方块算软"是一条**玩法规则**。
 * ⇒ 执行层（{@code spell/WebField}）把「是不是固体」与「材料名」当两个布尔/字符串传进来，
 * 本类只做判断 ⇒ 可以在 JUnit 里逐格穷举（含"岩浆 / 石头 / 草 / 空气"四个对照）。
 *
 * <p>★ 本类不许 import Bukkit（{@code PolicyPurityGuardTest} 钉着 policy 包的纯净）。
 */
public final class WebBlockRule {

    /**
     * 明确**不许**替换的材料名（全大写）。
     *
     * <p>★ 它们都不是固体（{@code isSolid() == false}），所以必须单独点名：
     * 岩浆与水一样是"非固体流体"，但用户只允许水。
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "LAVA", "FIRE", "SOUL_FIRE", "LAVA_CAULDRON", "POWDER_SNOW_CAULDRON");

    private WebBlockRule() {
    }

    /**
     * 这个方块能不能被蜘蛛网替换。
     *
     * @param solid        原版 {@code Material#isSolid()} 的读数（读不到 ⇒ 调用方传 {@code true}）
     * @param materialName {@code Material#name()}（大小写不敏感；{@code null} ⇒ 不替换）
     * @return {@code true} = 可以在这里结网
     */
    public static boolean replaceable(boolean solid, String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return false;
        }
        if (solid) {
            return false;   // 石头 / 木板 / 地板结构 —— 一律不碰
        }
        return !FORBIDDEN.contains(materialName.trim().toUpperCase(Locale.ROOT));
    }

    /** 一行说明（日志用：为什么这一格没结网）。 */
    public static String describe(boolean solid, String materialName) {
        if (replaceable(solid, materialName)) {
            return "可以结网（" + materialName + "）";
        }
        if (solid) {
            return "固体方块，不结网（" + materialName + "）";
        }
        return "危险/禁止替换的方块，不结网（" + materialName + "）";
    }
}
