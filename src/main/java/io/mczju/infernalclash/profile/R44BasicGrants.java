package io.mczju.infernalclash.profile;

import io.mczju.infernalclash.card.Card;
import io.mczju.infernalclash.policy.Rarity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * ★ <b>R44「玩家初始拥有所有基础品质的卡牌」的发放判据</b>（{@code docs/08} §1.2 / §3.1 ①、
 * {@code docs/21} §1 的那条实现缺口）。
 *
 * <h2>为什么单独一个类</h2>
 * 「哪些卡属于基础档」只有卡池（{@code card/CardCatalog}）知道，而这件事<b>只能有一个出处</b>：
 * 本类<b>不写卡名清单</b>，只提供 {@link #basicCardIds(Collection)} ——
 * 「输入卡集合 ⇒ 输出 {@code rarity == BASIC} 的卡 id（保序）」这一步的<b>纯函数</b>；
 * 装配层（{@code InfernalClashPlugin}）再把它的结果递给
 * {@link ProfileStore#bindBasicCardIds(java.util.function.Supplier)}。
 * 判定与发放也都是<b>纯</b>的：除 {@code java.*} 之外只 import 纯数据
 * （{@code policy/Rarity} 与记录 {@code card/Card} —— 后者自己不碰服务端，
 * 见 {@code Card} 的类注释）⇒ 能在普通 JUnit 里离线断言、<b>不伪造 Bukkit</b>。
 * ★ 本类<b>绝不</b>出现 {@code org.bukkit.*} / {@code com.github.mczjuops.mczjugamecore.*}。
 * ★ <b>绝不</b>在这里写一份硬编码的基础卡名清单（也不写「11」这个数量）——
 * 那是"第二处事实来源"，会在卡池改动后静默分叉
 * （本项目最反对的事，见 {@code AGENTS.md} 的「第二处白名单」教训）。
 *
 * <h2>★★ 「空白档案」的判据（本类最关键的一条决定）</h2>
 * 判据 = <b>{@link #isBlankProfile} 检查 {@code PlayerProgress} 的<b>全部</b>业务字段都还是
 * 出厂值</b>（{@link PlayerProgress#fresh} 给出的那个状态）：
 *
 * <table border="1">
 *   <caption>逐字段判据</caption>
 *   <tr><th>字段</th><th>判据</th><th>它被谁写过（= 为什么它能当"痕迹"）</th></tr>
 *   <tr><td>{@code owned}</td><td>为空</td><td>{@link PlayerProgress#unlock}（开包 / 合成 / 调试发放）</td></tr>
 *   <tr><td>{@code codex}</td><td>为空</td><td>{@code unlock}；★ 注意 {@code codex} 里可以存在
 *       {@code NONE} 记录而 {@code owned} 为空 ⇒ 它必须单独查</td></tr>
 *   <tr><td>{@code presets}</td><td>为空</td><td>{@link PlayerProgress#withPreset}（组过卡组）</td></tr>
 *   <tr><td>{@code gold}</td><td>{@code == 0}</td><td>{@code addGold} / {@code spendGold}（打过对局 /
 *       买过包 / 指令发过钱）</td></tr>
 *   <tr><td>{@code dust}</td><td>{@code == 0}</td><td>{@code addDust} / {@code spendDust}（分解过卡）</td></tr>
 *   <tr><td>{@code packsOpened}</td><td>{@code == 0}</td><td>开包计数</td></tr>
 *   <tr><td>{@code gamesPlayed}</td><td>{@code == 0}</td><td>对局结算计数</td></tr>
 * </table>
 *
 * <p><b>为什么取"全部字段都是出厂值"这一最严读法</b>：以上每一个字段都<b>只可能</b>被
 * 「玩家真的做过什么」或「有人动过这份档案」写出来 ⇒ 全部为零是"这份档案从没被动过"的
 * 唯一充分判据。反面例子：只看 {@code owned.isEmpty()} 是不够的 ——
 * 调试指令 {@code /incl debug profile setowned <uuid> ""} 会造出「没有卡、但有金币 / 有对局数」
 * 的档案，那不是新玩家。
 *
 * <p><b>★ 误判的两种后果</b>：
 * <ul>
 *   <li><b>假阳性</b>（把<b>老</b>档案当空白）⇒ <b>凭空送卡</b>。这不只是"多给了"：
 *       它会把一次真实的数据事故（档案被清空 / 换过玩法版本 / 卡池迁移失败）伪装成
 *       "一切正常"，也就是<b>掩盖数据问题</b>；而且基础档一扩容（新增 BASIC 卡），
 *       所有"看起来空白"的老档案都会白得新卡。</li>
 *   <li><b>假阴性</b>（把<b>空白</b>档案当"非空白"）⇒ 新玩家<b>拿不到基础卡 ⇒ 无牌可打</b>
 *       —— 那正是 {@code docs/21} §1 记录的现场缺陷本身。</li>
 * </ul>
 * 两种后果都真实存在，本类<b>故意</b>选择"宁可假阴性"：漏发是<b>可人工补救</b>的
 * （{@code /incl debug profile grant}），而白送卡是<b>不可撤销</b>的，还会盖住数据问题。
 *
 * <h2>★ 已拥有"部分"基础卡的玩家：整份不动（保守选择，可否决）</h2>
 * 本类<b>只</b>在完全空白档案上发放。若一份档案已经有 1 张基础卡（开包开到的 / 调试发过的），
 * 本类<b>一个字段都不动</b>，<b>不会</b>把缺的那 10 张补齐。
 * <p>理由：基础卡<b>不进卡包</b>（R44 + R49 的 4 档概率表）⇒「他本该有 11 张，实际只有 1 张」
 * 本身就是一个<b>数据问题的信号</b>，自动补齐等于把它抹掉。要改成"只补缺的"只需改
 * {@link #isBlankProfile} 的判据，接线层（{@code ProfileStore}）与卡池都不必动。
 * <p>⚠️ 这是<b>可被否决</b>的产品选择，不是需求原文 —— 已登记在 {@code docs/21} §1。
 *
 * <h2>★ 幂等 / 无副作用</h2>
 * <ul>
 *   <li>{@link PlayerProgress} 是<b>不可变</b>值对象 ⇒ 本类绝不修改入参，也绝不改传入的集合；</li>
 *   <li>再调用一次（用第一次的返回值）⇒「不是空白」⇒ 返回<b>同一个实例</b>，语义结果不变；</li>
 *   <li>★ <b>没有变化时返回同一个实例</b>（{@code ==} 可判）—— 接线层正是靠这一条决定
 *       「要不要写盘」，从而做到"只在真的发放了的时候才写回"。</li>
 * </ul>
 *
 * <h2>★ 入参约定（null / 空集合）</h2>
 * <ul>
 *   <li>{@code progress == null} ⇒ 抛 {@link IllegalArgumentException}（调用方 bug，
 *       和 {@link PlayerProgress} 自己的口径一致）；</li>
 *   <li>{@code basicCardIds == null} ⇒ 抛 {@link IllegalArgumentException}。<b>但</b>
 *       「运行时拿不到卡池」<b>不该</b>走这条路：接线层拿不到就<b>压根不调用</b>本方法
 *       （跳过 + 告警，见 {@code ProfileStore}）；传 {@code null} 只可能是编程错误；</li>
 *   <li>{@code basicCardIds} 为<b>空集合</b> ⇒ 原样返回（不抛）。空清单是"卡池里一张基础卡
 *       都没有"这个<b>运行时现实</b>，不是编程错误（而且我们绝不用硬编码卡名兜底）；</li>
 *   <li>清单里出现 {@code null} / 空白 id ⇒ 由 {@link PlayerProgress#unlock} 抛
 *       {@link IllegalArgumentException}。★ 因为 {@code PlayerProgress} 不可变，
 *       <b>抛出时入参一字未动</b>（不会留下"发了一半"的档案）。</li>
 * </ul>
 */
public final class R44BasicGrants {

    private R44BasicGrants() {
    }

    /**
     * ★ <b>纯</b>：「<b>从卡池取基础档 id</b>」这一步 —— 输入卡集合，输出
     * {@code rarity == BASIC} 的卡 id（<b>保序</b>）。
     *
     * <h2>它为什么是纯函数、又为什么必须有</h2>
     * R44 的现场缺陷（{@code docs/21} §1）是<b>装配层没把卡池里的 BASIC id 交给发放逻辑</b>
     * ⇒ 空白档案走"跳过发放"。这一步抽成纯函数之后，
     * 「挑对了没有」（恰好 11 张？保序？空池不抛？）可以<b>离线</b>钉死，
     * 不必起服务器（{@code R44BasicGrantsTest} 用<b>真实出厂卡池</b>断言 11 张）。
     *
     * <p>判据<b>只有一处</b>：{@code card.rarity() == Rarity.BASIC}（+ 卡池自己的 id）。
     * 本方法<b>不</b>认卡名、<b>不</b>认数量 —— 卡池改了（加/删一张基础卡）它自动跟着变，
     * 不存在"第二处白名单"（{@code AGENTS.md} §4.1 的教训）。
     *
     * <h2>★ 顺序 = 传入顺序（保序）</h2>
     * 输入通常是 {@code CardCatalog.cards().values()}（{@code LinkedHashMap}，按 id 升序）
     * ⇒ 输出也是 id 升序 ⇒ 「同输入同落盘顺序」，与 {@link PlayerProgress}
     * 和 {@code ProfileStore.parseOwned} 同一条口径。
     *
     * <h2>★ 失败方向：空 / 拿不到 ⇒ <b>空列表</b>，绝不抛</h2>
     * 「卡池为空」是<b>运行时现实</b>（不是编程错误）：调用方（接线层 {@link ProfileStore}）
     * 拿到空清单时会<b>跳过发放并告警</b> —— ★ 这里绝不返回一份硬编码的卡名兜底。
     * <ul>
     *   <li>{@code cards == null} ⇒ 空列表（"拿不到卡池"）；</li>
     *   <li>空集合 / 一张 BASIC 都没有 ⇒ 空列表；</li>
     *   <li>集合里有 {@code null} 元素（或 id 为 {@code null} 的卡）⇒ <b>跳过那一个</b>，
     *       其余照常收集（★ 生产路径上不可达：{@code CardCatalog} 要求
     *       {@code id == 文件名} ⇒ id 必定非空；这一支只为"绝不抛"）。</li>
     * </ul>
     *
     * @param cards 卡池里的卡（可为 {@code null}；顺序即输出顺序）
     * @return {@code rarity == BASIC} 的卡 id，<b>保序</b>且<b>不可变</b>；
     *         拿不到 / 一张都没有 ⇒ 空列表（不是 {@code null}）
     */
    public static List<String> basicCardIds(Collection<Card> cards) {
        if (cards == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(cards.size());
        for (Card card : cards) {
            if (card == null || card.id() == null) {
                continue;   // 见 javadoc：跳过而不是抛（失败方向 = 接线层「跳过 + 告警」）
            }
            if (card.rarity() == Rarity.BASIC) {
                ids.add(card.id());
            }
        }
        // List.copyOf：不可变快照（本方法绝不把内部列表漏给调用方）
        return List.copyOf(ids);
    }

    /**
     * ★ 若 {@code progress} 是<b>空白档案</b>，就让它"拥有" {@code basicCardIds} 里的全部卡
     * （{@code docs/08} §1.2 的 BASIC 档 / R44）；否则<b>原样返回同一个实例</b>。
     *
     * <p>发放走 {@link PlayerProgress#unlock}（判据只有一处），因此：
     * <ul>
     *   <li>顺序 = {@code basicCardIds} 的<b>迭代顺序</b>（保序；装配层给的是卡池按 id 升序的列表
     *       ⇒ 同输入同落盘顺序）；</li>
     *   <li>重复 id 只留第一次出现的位置；</li>
     *   <li>图鉴同步写成 {@code NORMAL}（{@code PlayerProgress} 的不变量要求两者对得上）；</li>
     *   <li><b>金币 / 尘 / 预设 / 计数器一律不动</b>。</li>
     * </ul>
     *
     * @param progress     基准进度（不可为 {@code null}）
     * @param basicCardIds 卡池里 {@code rarity == BASIC} 的卡 id（不可为 {@code null}；
     *                     空集合 ⇒ 原样返回）
     * @return 发放后的新进度；<b>语义上没有变化时返回 {@code progress} 本身</b>（{@code ==} 可判）
     * @throws IllegalArgumentException {@code progress} / {@code basicCardIds} 为 {@code null}，
     *                                  或清单里出现 {@code null} / 空白 id
     */
    public static PlayerProgress grantBasicIfBlank(PlayerProgress progress,
                                                   Collection<String> basicCardIds) {
        if (progress == null) {
            throw new IllegalArgumentException("progress 不能为 null（发放基础卡要以一份进度为基准；"
                    + "新玩家请用 PlayerProgress.fresh(playerId)）");
        }
        if (basicCardIds == null) {
            throw new IllegalArgumentException("basicCardIds 不能为 null（拿不到卡池请传空集合，"
                    + "或干脆不调用本方法 —— 「拿不到就跳过 + 告警」由接线层 ProfileStore 负责，"
                    + "本类绝不猜卡名）");
        }
        if (!isBlankProfile(progress) || basicCardIds.isEmpty()) {
            // ★ 没有变化 ⇒ 返回同一个实例：接线层据此判断「不必写盘」
            return progress;
        }
        PlayerProgress next = progress;
        for (String cardId : basicCardIds) {
            next = next.unlock(cardId);   // 未拥有 ⇒ 新实例；已拥有 ⇒ 同一个实例（幂等）
        }
        return next;
    }

    /**
     * ★ <b>"空白档案"的判据</b>：{@code PlayerProgress} 的<b>全部</b>业务字段都还是出厂值
     * （判据逐条列在类注释的表里，务必先读那里再改这里）。
     *
     * <p>★ 这条判据是**唯一**来源：{@link #grantBasicIfBlank} 与接线层（{@code ProfileStore}）
     * 都调它，不各写一份（"第二处判据"与本项目的"第二处白名单"是同一类事故）。
     *
     * <p>⚠️ 它<b>不</b>查 {@code playerId}：UUID 不是"痕迹"，任何 UUID 的空白档案都算空白。
     *
     * @param progress 待判的进度（不可为 {@code null}）
     * @return {@code true} = 这份档案从没被动过（第一次进服）
     * @throws IllegalArgumentException {@code progress} 为 {@code null}
     */
    public static boolean isBlankProfile(PlayerProgress progress) {
        if (progress == null) {
            throw new IllegalArgumentException("progress 不能为 null（判断「空白档案」先要有一份进度）");
        }
        return progress.owned().isEmpty()
                && progress.codex().isEmpty()
                && progress.presets().isEmpty()
                && progress.gold() == 0
                && progress.dust() == 0
                && progress.packsOpened() == 0
                && progress.gamesPlayed() == 0;
    }
}
