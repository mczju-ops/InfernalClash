package io.mczju.infernalclash.ai;

import io.mczju.infernalclash.config.TrainingConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * ★★ <b>「多套人机 ＋ 随机占位符」的纯策略</b>（2026-10-21 用户裁定）。
 *
 * <h2>它回答两个问题（都不碰 Bukkit、不碰卡池）</h2>
 * <ol>
 *   <li>{@link #pickBot} —— {@code /incl bot on} 之后，本局从 {@code training.bots} 里抽哪一套；</li>
 *   <li>{@link #resolve} —— 那一套卡组里的「随机」占位符，各自换成哪一张卡。</li>
 * </ol>
 *
 * <h2>★★ 为什么随机必须是可注入的 {@link Random}</h2>
 * 本项目对随机的一贯要求是「同种子 ⇒ 同结果」（{@code Map.copyOf} 那条教训的同源）：
 * 一个不可复现的随机，在测试里只能断言"没抛异常"，等于没测；
 * 在排障时也无法回答"上一局抽到的到底是哪张"。
 * ⇒ 生产侧用一次性的新实例，离线测试传定种子。
 *
 * <h2>★ 「随机」的口径（用户裁定）</h2>
 * <ul>
 *   <li>字面量：`随机` 与 `random`（忽略大小写与首尾空白）—— {@link TrainingConfig#isRandomToken}；</li>
 *   <li><b>不能重复</b>：与卡组里**写死**的卡、以及**彼此之间**都不得重复；</li>
 *   <li>候选池 = 调用方给的「人机可用卡」（{@link TrainingConfig#usableCardIds}）——
 *       本类<b>不认识</b>哪些卡被禁（那是配置层的唯一判据，见 {@code docs/31} §4）。</li>
 * </ul>
 *
 * <h2>★ 铁律 10</h2>
 * 本类连 Bukkit 都不 import：它只排卡片 id，没有实体、没有世界、没有"附近"。
 */
public final class BotDeckResolver {

    private BotDeckResolver() {
    }

    // ==================================================================================
    // 一、抽哪一套人机
    // ==================================================================================

    /** 抽中的那一套（下标从 0 起，用于日志「第 N 项 / 共 M 项」）。 */
    public record BotPick(int index, TrainingConfig.BotSpec bot) {

        public BotPick {
            if (bot == null) {
                throw new IllegalArgumentException("抽中的人机不能为 null");
            }
        }

        /** 一行日志（可对账：能看出是**哪一项**被抽中）。 */
        public String describe(int total) {
            return "本局人机 = " + bot.name() + "（training.bots 第 " + (index + 1) + " 项 / 共 "
                    + total + " 项，随机抽取）";
        }
    }

    /**
     * ★ <b>随机抽一套人机</b>。
     *
     * @param bots   人机列表（{@code null} / 空 ⇒ 抛：装载期已保证非空，走到这里说明装配顺序被改了）
     * @param random 随机源（{@code null} ⇒ 抛：不可复现的随机不许进生产路径）
     * @throws IllegalStateException 列表为空（这是配置/装配缺陷，必须大声报出来）
     */
    public static BotPick pickBot(List<TrainingConfig.BotSpec> bots, Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random 不能为 null：抽人机必须可复现（同种子同结果）");
        }
        if (bots == null || bots.isEmpty()) {
            throw new IllegalStateException("training.bots 为空：没有人机可以上场"
                    + "（装载期应已拒绝启动 —— 请核对 config.yml 的 training.bots）");
        }
        int index = random.nextInt(bots.size());
        return new BotPick(index, bots.get(index));
    }

    // ==================================================================================
    // 二、解析「随机」占位符
    // ==================================================================================

    /**
     * 一套**解析后**的卡组（8 个条目：真实卡 id 或尚未解析的 {@code steal} 占位符）。
     *
     * @param cards       解析后的卡组（保序、长度 == 原卡组长度）
     * @param randomSlots 其中有多少个位置是「随机」换来的（日志用）
     * @param stealSlots  ★ 其中有多少个位置是「偷牌」占位符（2026-10-21 追加）
     */
    public record Deck(List<String> cards, int randomSlots, int stealSlots) {

        public Deck {
            cards = List.copyOf(cards);
            if (randomSlots < 0 || stealSlots < 0) {
                throw new IllegalArgumentException("randomSlots / stealSlots 不能为负");
            }
        }

        /** 兼容旧夹具（没有「偷牌」占位符）。 */
        public Deck(List<String> cards, int randomSlots) {
            this(cards, randomSlots, 0);
        }

        /** 一行日志（可对账：写死几张 ＋ 随机几张 ＋ 偷牌几张 ⇒ 实际哪 8 张）。 */
        public String describe(String botName, List<String> originalDeck) {
            List<String> fixed = new ArrayList<>();
            for (String entry : originalDeck) {
                if (!TrainingConfig.isPlaceholder(entry)) {
                    fixed.add(entry.trim());
                }
            }
            return "本局人机卡组（" + botName + "）：写死 " + fixed.size() + " 张 ＋ 随机 "
                    + randomSlots + " 张" + (stealSlots > 0 ? " ＋ steal " + stealSlots + " 张" : "")
                    + " ⇒ 实际 " + cards.size() + " 张 " + cards;
        }
    }

    /**
     * ★★ <b>把卡组里的「随机」换成具体卡</b>。
     *
     * <h2>规则（逐条可复述）</h2>
     * <ol>
     *   <li>写死的条目**原样保留**（去首尾空白）；</li>
     *   <li>候选池 = {@code usable} − 写死的卡，并**按 id 字典序排**再抽
     *       （排序是为了「同种子 ⇒ 同结果」不依赖调用方给的集合顺序）；</li>
     *   <li>每个「随机」从**剩余**池里取一张并**移除**它 ⇒ 彼此之间不可能重复；</li>
     *   <li>池子不够填满 ⇒ 抛 {@link IllegalStateException}（装载期 {@code crossCheckBots}
     *       已经算过容量并拒绝启动，走到这里就是缺陷；<b>绝不**静默留空位**）。</li>
     * </ol>
     *
     * @param deck   人机卡组（{@code null} ⇒ 空结果）
     * @param usable 人机可用卡（{@code null} 视同空集）
     * @param random 随机源（{@code null} ⇒ 抛）
     */
    public static Deck resolve(List<String> deck, Collection<String> usable, Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random 不能为 null：解析「随机」必须可复现");
        }
        if (deck == null || deck.isEmpty()) {
            return new Deck(List.of(), 0, 0);
        }
        Set<String> taken = new LinkedHashSet<>();
        for (String entry : deck) {
            if (entry != null && !TrainingConfig.isPlaceholder(entry)) {
                taken.add(entry.trim());
            }
        }
        List<String> pool = new ArrayList<>();
        if (usable != null) {
            for (String cardId : usable) {
                if (cardId != null && !cardId.isBlank() && !taken.contains(cardId.trim())) {
                    pool.add(cardId.trim());
                }
            }
        }
        Collections.sort(pool);   // ★ 排序 ⇒ 结果不依赖调用方给的集合顺序

        List<String> cards = new ArrayList<>(deck.size());
        int randomSlots = 0;
        int stealSlots = 0;
        for (String entry : deck) {
            if (TrainingConfig.isStealToken(entry)) {
                // ★ 偷牌要等玩家确认卡组才能解析（game/InfernalClashGame 在战斗开始时做）
                //   ⇒ 这里只**原样保留标记**，既不占「随机」池，也不算写死的卡。
                cards.add(TrainingConfig.STEAL_TOKEN);
                stealSlots++;
                continue;
            }
            if (!TrainingConfig.isRandomToken(entry)) {
                cards.add(entry == null ? "" : entry.trim());
                continue;
            }
            if (pool.isEmpty()) {
                throw new IllegalStateException("「" + TrainingConfig.RANDOM_TOKEN
                        + "」占位符没有可用的卡了：人机可用卡共 " + (usable == null ? 0 : usable.size())
                        + " 张，写死的已占 " + taken.size() + " 张 —— 请减少「随机」个数或减少写死的卡"
                        + "（装载期本应已拒绝启动，见 docs/31 §2.2）");
            }
            String picked = pool.remove(random.nextInt(pool.size()));
            taken.add(picked);
            cards.add(picked);
            randomSlots++;
        }
        return new Deck(cards, randomSlots, stealSlots);
    }

    /**
     * ★★★ <b>「偷牌」的解析结果</b>（2026-10-21 用户追加）。
     *
     * @param cards    解析后的 8 个真实卡 id（保序）
     * @param stolen   真的偷到玩家卡的位置数
     * @param fallback 兜底成随机卡的位置数（玩家那张人机用不了 / 重复 / 玩家还没确认卡组）
     * @param notes    逐格说明（写进日志，便于对账「哪一格为什么没偷到」）
     */
    public record StealResult(List<String> cards, int stolen, int fallback, List<String> notes) {

        public StealResult {
            cards = List.copyOf(cards);
            notes = notes == null ? List.of() : List.copyOf(notes);
            if (stolen < 0 || fallback < 0) {
                throw new IllegalArgumentException("stolen / fallback 不能为负");
            }
        }
    }

    /**
     * ★★★ <b>把 {@code steal} 换成玩家卡组同位置的卡</b>（纯函数，2026-10-21 用户追加）。
     *
     * <h2>口径（每一步都可复述）</h2>
     * <ol>
     *   <li><b>位置对齐</b>：人机第 i 项（0 起）= 玩家第 i 项；写死的卡原样保留并计入「已占用」；</li>
     *   <li><b>偷的条件</b>：玩家那一格有卡 ＋ 属于「人机可用卡」＋ 不与已占用的卡重复；</li>
     *   <li><b>失败方向</b>（任一条件不满足）⇒ 从「可用卡 − 已占用」里**随机**取一张，
     *       并在 {@code notes} 里写明原因 —— 绝不静默留空、也绝不把人机用不了的卡塞进去；</li>
     *   <li><b>随机池排序</b> + 可注入 {@link Random} ⇒ 同种子同结果（与「随机」同一口径）。</li>
     * </ol>
     *
     * @param deck       已经过 {@link #resolve} 的卡组（含 {@code steal} 标记）
     * @param playerDeck 玩家**已确认**的 8 张卡 id；{@code null} = 还没确认 ⇒ 全部走随机兜底
     * @param usable     人机可用卡（{@link TrainingConfig#usableCardIds}）
     * @param random     随机源（{@code null} ⇒ 抛：兜底必须可复现）
     */
    public static StealResult applySteal(List<String> deck, List<String> playerDeck,
                                         Collection<String> usable, Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random 不能为 null：偷牌兜底必须可复现（同种子同结果）");
        }
        if (deck == null || deck.isEmpty()) {
            return new StealResult(List.of(), 0, 0, List.of());
        }
        Set<String> taken = new LinkedHashSet<>();
        List<String> cards = new ArrayList<>(deck.size());
        List<String> notes = new ArrayList<>();
        int stolen = 0;
        int fallback = 0;
        for (int i = 0; i < deck.size(); i++) {
            String entry = deck.get(i);
            if (!TrainingConfig.isStealToken(entry)) {
                String id = entry == null ? "" : entry.trim();
                cards.add(id);
                if (!id.isEmpty()) {
                    taken.add(id);
                }
                continue;
            }
            int slot = i + 2;
            String candidate = playerDeck != null && i < playerDeck.size() && playerDeck.get(i) != null
                    ? playerDeck.get(i).trim() : "";
            String why;
            if (candidate.isEmpty()) {
                why = "玩家那一格没有卡（卡组未确认 / 不足 8 张）";
            } else if (usable == null || !usable.contains(candidate)) {
                why = "玩家第 " + (i + 1) + " 张 " + candidate + " 人机用不了（禁用卡 / 没配法术规则）";
            } else if (taken.contains(candidate)) {
                why = "玩家第 " + (i + 1) + " 张 " + candidate + " 与前面格子重复";
            } else {
                why = null;
            }
            if (why == null) {
                cards.add(candidate);
                taken.add(candidate);
                stolen++;
                notes.add("槽位 " + slot + " ← 玩家第 " + (i + 1) + " 张 " + candidate);
                continue;
            }
            String picked = pickRandom(usable, taken, random);
            fallback++;
            if (picked.isEmpty()) {
                cards.add("");
                notes.add("槽位 " + slot + " 无卡可用（" + why + "）");
            } else {
                cards.add(picked);
                taken.add(picked);
                notes.add("槽位 " + slot + " 兜底成 " + picked + "（" + why + "）");
            }
        }
        return new StealResult(cards, stolen, fallback, notes);
    }

    /** 从「可用卡 − 已占用」里随机取一张（**排序**后取，保证同种子同结果）；空池 ⇒ 空串。 */
    private static String pickRandom(Collection<String> usable, Set<String> taken, Random random) {
        List<String> pool = new ArrayList<>();
        if (usable != null) {
            for (String cardId : usable) {
                if (cardId != null && !cardId.isBlank() && !taken.contains(cardId.trim())) {
                    pool.add(cardId.trim());
                }
            }
        }
        Collections.sort(pool);
        if (pool.isEmpty()) {
            return "";
        }
        return pool.get(random.nextInt(pool.size()));
    }
}
