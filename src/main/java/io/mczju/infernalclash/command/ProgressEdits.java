package io.mczju.infernalclash.command;

import io.mczju.infernalclash.profile.PlayerProgress;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ★ <b>R69 那几条调试指令的「档案变换」纯函数层</b>（{@code docs/10} §3）。
 *
 * <h2>为什么单独一个类（而不是写在指令里）</h2>
 * 「把一份进度变成另一份进度」是这几条指令<b>唯一的业务内容</b>，也是离线最该钉住的部分：
 * 它必须 ① 只碰该碰的字段；② 幂等；③ 与 {@link PlayerProgress} 的不变量
 * （{@code owned} 与 {@code codex} 必须对得上）自洽。
 * 写成纯函数后，这些性质全部能在普通 JUnit 里断言，不需要服务器
 * （{@code DebugSubcommandsTest}）。
 *
 * <h2>★★ 它<b>不</b>做的事（每一件都刻意划出去）</h2>
 * <ul>
 *   <li><b>不落盘</b>：写档案的唯一入口是 {@code profile/ProfileStore}（{@code load → 变换 → save}）。
 *       本类只产出「变换后的新进度」，一个字节都不写；</li>
 *   <li><b>不读档案</b>：{@code base} 由调用方从 {@code ProfileStore.load} 拿来；</li>
 *   <li><b>不 import Bukkit / MGC</b>：连 {@code Plugin} 都不需要 ⇒ 可在普通 JUnit 里跑；</li>
 *   <li><b>不猜卡池</b>：{@code grant-all} 要的「全部卡 id」由调用方从 {@code card/CardCatalog}
 *       递进来（★ 卡池的唯一事实来源），本类<b>绝不写死 51</b>。</li>
 * </ul>
 *
 * <h2>★ 幂等（重复执行结果一致）</h2>
 * 三条变换都是「<b>设置成</b>某个形状」而不是「累加」：
 * {@link #acceptAll} 把 owned/codex 直接换成全卡池；
 * {@link #resetCollection} 把 owned/codex 换成**卡池里的基础档**（★ 2026-10-21 用户裁定：
 * 「重置卡牌收藏时不应该删掉我的基础卡」⇒ 重置 = 回到新玩家的收藏，而不是清成一张不剩）；
 * {@link #setNumber} 直接把某个数字设成 {@code amount}。
 * ⇒ 连跑两次的结果与跑一次<b>逐字段相等</b>（测试里有断言），也就没有「重复执行会重复计数」这类问题。
 *
 * <h2>★ 与原值相同时返回<b>同一个实例</b>（{@code ==} 可判）</h2>
 * 这让调用方能区分「真的改了」与「本来就是那样」——既省一次无谓的落盘 IO，
 * 也让指令输出能诚实地写「本来就已是该值」。参照 {@link PlayerProgress#unlock} 的既有做法。
 */
public final class ProgressEdits {

    private ProgressEdits() {
    }

    /**
     * ★ <b>设置</b>一个数值字段（{@code set-gold} ⇒ 金币；{@code set-powder} ⇒ 尘 / 烈焰粉）。
     *
     * <p>★ 是「设置」不是「加」：{@code docs/10} §3 的指令名与语义就是
     * 「<b>设置</b>金币数」/「<b>设置</b>烈焰粉」（表里 {@code set-gold <玩家> <数量>}）；
     * 「加钱」那条路已经存在，是 {@code /incl debug profile grant <uuid> <gold> [dust]}，
     * 两者<b>不合并</b>（名字与语义一一对应，免得同一个行为有两种叫法）。
     *
     * <p>其它字段（另一个数值 / owned / codex / presets / 计数器）<b>原样保留</b>；
     * {@code playerId} 仍取自 {@code base}（★ 绝不把进度写到别人名下，
     * 那是 {@code ProfileStore.validateSave} 会拦下的错）。
     *
     * @param base   基准进度（不可为 {@code null}）
     * @param field  要设置哪个数字（见 {@link Field}）
     * @param amount 目标值（{@code 0 ~ Integer.MAX_VALUE}）
     * @throws IllegalArgumentException {@code base} / {@code field} 为 {@code null}，或 {@code amount} 为负
     */
    public static PlayerProgress setNumber(PlayerProgress base, Field field, int amount) {
        if (base == null) {
            throw new IllegalArgumentException("base 不能为 null（要改一份进度，先 load 它）");
        }
        if (field == null) {
            throw new IllegalArgumentException("field 不能为 null（要设置金币还是尘？）");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("数量不能为负：实际 " + amount
                    + "（调试指令只能把数量设置成 0 及以上；要扣请用对局的正常路径）");
        }
        return switch (field) {
            case GOLD -> amount == base.gold() ? base
                    : copy(base, amount, base.dust(), base.owned(), base.codex());
            case DUST -> amount == base.dust() ? base
                    : copy(base, base.gold(), amount, base.owned(), base.codex());
        };
    }

    /** {@link #setNumber} 的两个目标字段（金币 / 尘 = 烈焰粉）。 */
    public enum Field {
        /** 金币（{@code PlayerProgress.gold}）。 */
        GOLD,
        /** 尘 = 分解重复卡所得的烈焰粉（{@code PlayerProgress.dust}）。 */
        DUST
    }

    /**
     * ★ <b>获取全卡</b>（{@code grant-all}，【Q40】「连金色版一起给」）。
     *
     * <h2>语义（逐条有出处，不猜）</h2>
     * <ol>
     *   <li>{@code owned} 被设置成 {@code pool} 的<b>全部 id</b>（{@code docs/10} §3「获取全卡」；
     *       卡池的唯一事实来源是 {@code card/CardCatalog}，由调用方递进来）；</li>
     *   <li>每个 id 的图鉴状态写成 {@link PlayerProgress#CODEX_GOLD} ——
     *       【Q40】用户原话「<b>连金色版一起给</b>」，而 {@code docs/08} §3.3 /【Q31】裁定
     *       「<b>金色版会代替普通版本的位置</b>、{@code GOLD} 是 {@code NORMAL} 的升级态、
     *       图鉴按卡 id 索引一条记录」⇒ 「给金色版」在数据结构上就是
     *       <b>owned 含该 id + codex = GOLD</b>（★ 不存在「一张卡同时有条目和金色条目」这种形态）；</li>
     *   <li>金币 / 尘 / 预设（{@code presets}）/ 计数器<b>全部原样保留</b> ——
     *       {@code docs/10} §3 只说要「获取全卡」，没说要动钱或预设（不自己扩权）。</li>
     * </ol>
     *
     * <p>★ <b>幂等</b>：结果只取决于 {@code pool}，与 {@code base} 原来拥有什么无关
     * ⇒ 连跑两次逐字段相等（不会「重复计数」，也不存在重复卡自动转尘那类副作用 ——
     * 转尘属于<b>开包流程</b>的结算，见 {@code docs/08} §3.2 /【Q30】，不是这里的语义）。
     *
     * @param base 基准进度（不可为 {@code null}）
     * @param pool 卡池的全部卡 id（不可为 {@code null}；空集合 ⇒ 结果是一张卡都没有）
     * @throws IllegalArgumentException {@code base} / {@code pool} 为 {@code null}，
     *         池里有 {@code null} / 空白 id，或同一个 id 出现两次（卡池重复是接线错误，fail-fast）
     */
    public static PlayerProgress acceptAll(PlayerProgress base, Collection<String> pool) {
        if (base == null) {
            throw new IllegalArgumentException("base 不能为 null（要改一份进度，先 load 它）");
        }
        if (pool == null) {
            throw new IllegalArgumentException("pool 不能为 null（全卡 = 卡池里的全部 id，"
                    + "它必须由 card/CardCatalog 递进来；本类不猜卡池）");
        }
        Set<String> owned = new LinkedHashSet<>();
        Map<String, String> codex = new LinkedHashMap<>();
        for (String raw : pool) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("卡池里有 null / 空白的卡 id（实际 "
                        + (raw == null ? "null" : "\"" + raw + "\"") + "）—— 那是卡池的问题，"
                        + "不在指令里替它兜底");
            }
            String cardId = raw.trim();
            if (!owned.add(cardId)) {
                throw new IllegalArgumentException("卡池里有重复的卡 id：" + cardId
                        + "（id 必须唯一；重复说明卡池加载有问题）");
            }
            codex.put(cardId, PlayerProgress.CODEX_GOLD);
        }
        return copy(base, base.gold(), base.dust(), owned, codex);
    }

    /**
     * ★★★ <b>重置图鉴</b>（{@code reset-collection}）—— <b>保留基础卡</b>（2026-10-21 用户裁定）。
     *
     * <h2>用户原话（逐字）</h2>
     * > 「**在用 debug 重置卡牌收藏时，不应该删掉我的基础卡。**」
     *
     * <h2>为什么这是「修缺陷」而不是「改玩法」</h2>
     * R44 逐字写着「玩家**初始拥有**所有基础品质的卡牌」——
     * 也就是说对一个真实新玩家而言，他的收藏<b>本来就有</b>那 11 张基础卡
     * （{@link io.mczju.infernalclash.profile.R44BasicGrants} 就是发这批卡的）。
     * 而旧实现把 {@code owned} / {@code codex} 一起清成空 ⇒ 重置完的档案<b>反而比新玩家还空</b>
     * —— 玩家连牌都打不了（{@code docs/21} §1 记的就是这个形状）。
     * ⇒ 现在「重置」= 回到**新玩家的收藏状态**：<b>基础卡保留（图鉴为 {@code NORMAL}），其余清空</b>。
     *
     * <h2>语义（逐条）</h2>
     * <ol>
     *   <li>{@code owned} = {@code basicCardIds} 里的全部 id（★ 由调用方从 {@code card/CardCatalog}
     *       递进来 —— 判据 {@code rarity == BASIC} 只有
     *       {@code R44BasicGrants#basicCardIds} 一处，本类<b>绝不写死卡名清单</b>）；</li>
     *   <li>{@code codex} 里这些 id 一律 {@link PlayerProgress#CODEX_NORMAL}（走
     *       {@link PlayerProgress#unlock}，因此「{@code owned} 与 {@code codex} 必须对得上」
     *       这条不变量由 {@code PlayerProgress} 自己保证，本类不另写一份）；</li>
     *   <li><b>非基础卡</b>（含金卡）一律清空 —— 那才是「重置」的语义；</li>
     *   <li>金币 / 尘 / 计数器<b>不动</b>（「重置图鉴」没说动钱）。</li>
     * </ol>
     *
     * <p>★ <b>幂等</b>：清空之后再清一次结果逐字段相等；
     * ★ <b>没有变化时返回同一个实例</b>（{@code ==} 可判）—— 调用方据此不写盘。
     *
     * <p>⚠️ <b>一处如实登记的缺口（没有自行拍板）</b>：预设（{@code presets}）<b>本方法不动</b>。
     * 理由：{@code docs/10} §4 只要求「报出多少个预设」；而 {@code docs/08} §6 明写
     * 「卡牌被移除时要<b>降级处理</b>」旧预设 ⇒「owned 清掉后预设留着、由 {@code PresetRules.validate}
     * 在使用时判非法」是本项目既有的口径。若用户裁定「重置图鉴连预设一起清」，改这一处即可。
     *
     * @param base         基准进度（不可为 {@code null}）
     * @param basicCardIds 卡池里 {@code rarity == BASIC} 的卡 id（不可为 {@code null}；
     *                     空集合 = 卡池里一张基础卡都没有 —— 那是运行时现实，不是编程错误）
     * @throws IllegalArgumentException {@code base} / {@code basicCardIds} 为 {@code null}，
     *                                  或清单里出现 {@code null} / 空白 id
     */
    public static PlayerProgress resetCollection(PlayerProgress base, Collection<String> basicCardIds) {
        if (base == null) {
            throw new IllegalArgumentException("base 不能为 null（要改一份进度，先 load 它）");
        }
        if (basicCardIds == null) {
            throw new IllegalArgumentException("basicCardIds 不能为 null（基础卡池必须由 "
                    + "R44BasicGrants#basicCardIds 从 card/CardCatalog 递进来；本类不猜卡名）");
        }
        PlayerProgress next = copy(base, base.gold(), base.dust(), Set.of(), Map.of());
        for (String raw : basicCardIds) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("基础卡池里有 null / 空白的卡 id（实际 "
                        + (raw == null ? "null" : "\"" + raw + "\"") + "）—— 那是卡池的问题，"
                        + "不在指令里替它兜底");
            }
            String cardId = raw.trim();
            // ★ 走 unlock：幂等、只留第一次出现的位置、并把图鉴写成 NORMAL（不变量由它保证）
            next = next.unlock(cardId);
        }
        // ★ 与入参逐字段相同 ⇒ 返回同一个实例（调用方据此判断「不必写盘」）
        if (next.owned().equals(base.owned()) && next.codex().equals(base.codex())) {
            return base;
        }
        return next;
    }

    /** 用「同一个玩家 + 新的四项」构造新实例（其它字段一律从 {@code base} 抄）。 */
    private static PlayerProgress copy(PlayerProgress base, int gold, int dust,
                                       Set<String> owned, Map<String, String> codex) {
        return new PlayerProgress(base.playerId(), gold, dust, owned, codex,
                base.presets(), base.packsOpened(), base.gamesPlayed());
    }
}
