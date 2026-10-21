package io.mczju.infernalclash.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * ★★ <b>玩家本体的「常驻效果」清单（纯函数）</b> —— 2026-10-17 第十轮新增，缺陷 P0-b 的落点。
 *
 * <h2>文档依据（逐字，这是"按文档实现"而不是"我编数值"）</h2>
 * <ul>
 *   <li>{@code docs/需求.txt} 第 20 行：「玩家进入游戏后处于生存模式，但是获得<b>持续的速度II、
 *       夜视以及抗性提升V</b>，即玩家无法受到任何伤害，也不会死亡。」</li>
 *   <li>{@code docs/02-玩法与数值.md} §5「玩家本体」第 265~267 行：
 *       {@code 速度 = 速度 II，持续 / 夜视 = 持续 / 抗性提升 = 抗性提升 V，持续}（出处列 **R40**）；</li>
 *   <li>同文件第 273~275 行：「★ <b>两层防护都要做</b>：1. <b>药水层</b>：抗性提升 V（R40）
 *       ＋ 伤害拦截兜底；2. <b>索敌层</b>：显式把玩家从候选目标里剔除」。</li>
 * </ul>
 * ⇒ 数值**全部有出处**（II / V / 持续），因此本类**没有**任何「待填」项；
 * 唯一文档没写的是**粒子与图标开关**（见 {@link #SHOW_PARTICLES} 的说明，已登记待裁）。
 *
 * <h2>MC 的等级 ↔ amplifier</h2>
 * MC 的 amplifier = 等级 − 1（{@code docs/19} / {@code EffectApplier.potion} 同口径）：
 * 速度 II ⇒ {@code amplifier = 1}；抗性提升 V ⇒ {@code amplifier = 4}；夜视只有 I ⇒ {@code 0}。
 *
 * <h2>★ 为什么「持续」要写成 {@code INFINITE}</h2>
 * 「持续」不是「很长」：写成 999999 tick 的后果是玩家在局中会看到效果倒计时、
 * 而且到期后**静默失效**（本项目最讨厌的那类"配置写了却慢慢不生效"）。
 * ⇒ 用 MC 的无限时长（{@code PotionEffect.INFINITE_DURATION}），并在运行期用
 * {@code PotionEffect#isInfinite()} 判定（见 {@code game/MatchPlayerBuffs}）。
 *
 * <p>★ 纯类：不碰 Bukkit / MGC（效果名是**字符串**，由运行期用注册表解析）
 * ⇒ 可离线逐条断言。已登记进 {@code PolicyPurityGuardTest} 与 {@code BytecodeScopeGuardTest}。
 */
public final class PlayerBuffs {

    /**
     * ★ <b>是否显示药水粒子</b>。
     *
     * <p>{@code docs/02} §5 / {@code docs/19} 都**没有**写这件事（它们只说"持续"）⇒
     * 这是一个**观感决定**，按本项目「观感由用户定」（{@code AGENTS.md} 铁律 8）
     * 我取**最保守的一格**：<b>不显示粒子</b>（{@code particles = false}）。
     * 理由（写下来，便于用户否决）：这三条效果是<b>常驻</b>的，
     * 开着粒子 = 双方玩家整局都在冒漩涡，干扰对线与观察；
     * 而它们的作用（伤害免疫 / 速度）本来就有别的可见反馈。
     * ★ 已登记 {@code docs/05}（待裁定）与 {@code docs/27}（D-台账）。
     */
    public static final boolean SHOW_PARTICLES = false;

    /**
     * 一条常驻效果。
     *
     * @param effect    效果名（★ 运行期用 {@code PotionEffectType.getByName} 解析，
     *                  与 {@code spell/EffectApplier} 同一口径 ⇒ 全大写、不带命名空间）
     * @param amplifier MC 的 amplifier（= 等级 − 1）
     */
    public record Spec(String effect, int amplifier) {
        public Spec {
            // ★ null 与空白都报 IllegalArgumentException（而不是 Objects.requireNonNull 的 NPE）：
            //   两者都是**编程错误**，报同一种异常便于调用方一处兜住。
            if (effect == null || effect.isBlank()) {
                throw new IllegalArgumentException("效果名不能为空或空白");
            }
            if (amplifier < 0) {
                throw new IllegalArgumentException("amplifier 不能是负数（等级 = amplifier + 1）：" + amplifier);
            }
        }

        /** MC 语义里的等级（仅用于日志/测试：{@code I} / {@code II} / {@code V}）。 */
        public int level() {
            return amplifier + 1;
        }
    }

    /**
     * ★★ <b>出厂的三条常驻效果</b>（{@code docs/02} §5 的 R40，逐条对得上）。
     *
     * <p>顺序刻意与文档一致：速度 → 夜视 → 抗性（日志里读起来与文档同序）。
     */
    public static final List<Spec> DEFAULT = List.of(
            new Spec("SPEED", 1),          // 速度 II（R40）
            new Spec("NIGHT_VISION", 0),   // 夜视（R40）
            new Spec("RESISTANCE", 4)      // 抗性提升 V（R40）—— 原版 20%×5 = 100% 免伤
    );

    private PlayerBuffs() {
    }

    /**
     * ★ <b>还缺哪几条</b>：把「现在实体上是什么样」交给调用方（{@code present} 谓词），
     * 返回**需要补的**那几条。
     *
     * <p>为什么要这个纯判据：这三条是「持续」的，而对局里会发生**掉效果**的合法情况
     * （死亡重生、{@code /effect clear}、维度切换）⇒ 我们需要每隔一段时间**补齐**。
     * 补齐的判据如果写成「每次都 addPotionEffect」，就会每秒重放一次效果
     * （客户端会闪、且日志刷屏）⇒ 判据必须在纯函数里可断言。
     *
     * @param specs   要保证的效果（通常是 {@link #DEFAULT}）
     * @param present 「这一条现在**已经是**无限时长且等级不低」的判据
     * @return 需要补的效果（保序；空 = 什么都不用做）
     */
    public static List<Spec> missing(List<Spec> specs, Predicate<Spec> present) {
        List<Spec> result = new ArrayList<>();
        if (specs == null || present == null) {
            return result;
        }
        for (Spec spec : specs) {
            if (!present.test(spec)) {
                result.add(spec);
            }
        }
        return List.copyOf(result);
    }

    /** 对局结束时该拿这条效果怎么办。 */
    public enum RestoreAction {
        /** 本局之前**没有**这条效果 ⇒ 结束时移除（不留副作用）。 */
        REMOVE,
        /** 本局之前有、而且是**有限时长** ⇒ 结束时按原时长原等级放回。 */
        REAPPLY,
        /** 本局之前就有、而且**本来就是无限时长** ⇒ 什么都不做（别去动别人的永久效果）。 */
        KEEP
    }

    /**
     * 一条效果在**本局开始之前**的样子（快照）。
     *
     * @param spec     哪一条
     * @param present  当时在不在
     * @param infinite 当时是不是无限时长（{@code present == false} 时无意义）
     * @param duration 当时的剩余时长 tick（用于 {@link RestoreAction#REAPPLY}）
     * @param amplifier 当时的 amplifier（用于 {@link RestoreAction#REAPPLY}）
     */
    public record Snapshot(Spec spec, boolean present, boolean infinite, int duration, int amplifier) {
    }

    /**
     * ★★ <b>还原判据</b>（"记原值 → 应用 → 对局结束还原"，本项目对世界级副作用的一贯口径）。
     *
     * <p>为什么必须按「原来有没有」分档：这三条效果不一定是我们第一个放的
     * （服主可能本来就是这么配的、别的插件也可能放）⇒ 无脑移除会**改坏别人的状态**。
     * 而「原来就是无限时长」这一格<b>什么都不做</b>，是唯一天然安全的答案。
     */
    public static RestoreAction restoreAction(Snapshot before) {
        if (before == null || !before.present()) {
            return RestoreAction.REMOVE;
        }
        return before.infinite() ? RestoreAction.KEEP : RestoreAction.REAPPLY;
    }

    /** 日志用的一行清单（★ 带等级，便于与人眼看到的对照）。 */
    public static String describe(List<Spec> specs) {
        if (specs == null || specs.isEmpty()) {
            return "（无）";
        }
        StringBuilder builder = new StringBuilder();
        for (Spec spec : specs) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(spec.effect()).append(" 等级 ").append(spec.level());
        }
        return builder.toString();
    }
}
