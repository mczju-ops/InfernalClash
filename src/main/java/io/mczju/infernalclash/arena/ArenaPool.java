package io.mczju.infernalclash.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * ★★★ <b>竞技场占用池：开局随机挑一个「空的」竞技场</b> —— 2026-10-18 新增。
 *
 * <h2>用户原话（逐字）</h2>
 * 「**我需要让房间开启时随机选择一个空的竞技场（可参考 ig）**」
 *
 * <h2>★ 参考 IG 的哪一点、以及本项目必须补的那一点</h2>
 * IG（{@code InfernalGuessGame#startSession} → {@code SessionPlanner.selectArena}）的做法是
 * 「从 {@code playable()} 里按本局种子<b>随机</b>挑一个」——
 * <b>但它没有"占用"这个概念</b>（IG 的竞技场不与对局绑定生命周期）。
 * 本项目要多竞技场**同时**开局，所以除了「可用（12 个必需点位齐全）」还必须有
 * 「<b>此刻没有别的对局在用</b>」。
 * <ul>
 *   <li><b>空</b> = 没有被任何进行中的对局占用（{@link #acquire} 记下 matchId）；</li>
 *   <li><b>随机</b> = 从空池里均匀挑一个（用本局种子派生 ⇒ 可复现、可离线断言）；</li>
 *   <li>★ <b>一个空的都没有</b> ⇒ 返回空 ⇒ 调用方**拒绝开局并说清原因**
 *       （绝不复用同一个竞技场开第二局：两局的实体/目标物会叠在一起，
 *       那是"玩着玩着塔被别人打"这种最难查的事故）。</li>
 * </ul>
 *
 * <h2>★ 为什么是"池"而不是"查在线对局"</h2>
 * 反查（"有没有对局用了这个竞技场"）要求每次开局都遍历全部对局，
 * 而且对局的清理顺序不确定（先死哪一个先释放）⇒ 容易出现"竞技场已经空了但没人知道"。
 * 池自己记账、自己在对局停止时释放，状态只有一处。
 *
 * <p>★ 纯类（不碰 Bukkit / 世界 / 实体）⇒ 离线逐条断言（{@code arena/ArenaPoolTest}）。
 */
public final class ArenaPool {

    /** 竞技场名 → 占着它的 matchId（★ 保序，便于日志与排障）。 */
    private final Map<String, String> busy = new LinkedHashMap<>();

    /** 当前被占用的竞技场（只读快照）。 */
    public Map<String, String> busy() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(busy));
    }

    /** 这个竞技场此刻被占着吗。 */
    public boolean isBusy(String name) {
        return name != null && busy.containsKey(name);
    }

    /** 占着这个竞技场的 matchId（没占 ⇒ {@code null}）。 */
    public String holderOf(String name) {
        return name == null ? null : busy.get(name);
    }

    /**
     * 从候选里挑一个**空**的竞技场并占住它。
     *
     * @param candidates 候选竞技场名（★ 调用方只应传「12 个必需点位齐全」的那些）
     * @param matchId    占用者（对局 id；{@code null} / 空白 ⇒ 拒绝，绝不记一个"无名占用"）
     * @param seed       本局种子（同一个种子 + 同一份空池 ⇒ 同一个结果，便于复现与单测）
     * @return 挑中的竞技场名；<b>没有空的 / 候选为空</b> ⇒ {@link Optional#empty()}
     */
    public Optional<String> acquire(List<String> candidates, String matchId, long seed) {
        if (matchId == null || matchId.isBlank()) {
            return Optional.empty();
        }
        List<String> free = freeOf(candidates);
        if (free.isEmpty()) {
            return Optional.empty();
        }
        String picked = pick(free, seed);
        busy.put(picked, matchId);
        return Optional.of(picked);
    }

    /**
     * ★ <b>纯挑选</b>（可离线逐格断言）：从 {@code free} 里按种子均匀挑一个。
     *
     * <p>★ 用 {@link Random}（而不是 {@code ThreadLocalRandom}）是因为要**可复现**：
     * 同一个种子必须给出同一个结果 —— 否则"随机挑竞技场"这件事没法在测试里钉住。
     * ★ 列表为空 ⇒ 抛（调用方必须先判空；静默返回 {@code null} 会让空池被当成"挑中了 null"）。
     */
    public static String pick(List<String> free, long seed) {
        if (free == null || free.isEmpty()) {
            throw new IllegalArgumentException("空池里挑不出竞技场（调用方必须先判 freeOf().isEmpty()）");
        }
        return free.get(new Random(seed).nextInt(free.size()));
    }

    /** 候选里**没被占用**的那些（保序）。 */
    public List<String> freeOf(List<String> candidates) {
        List<String> free = new ArrayList<>();
        if (candidates == null) {
            return free;
        }
        for (String name : candidates) {
            if (name != null && !busy.containsKey(name) && !free.contains(name)) {
                free.add(name);
            }
        }
        return List.copyOf(free);
    }

    /**
     * 释放一个竞技场（对局结束 / 中止时调）。
     *
     * @param matchId 只有**持有者本人**才能释放（★ 防止"后来的一局把前一局占的场子放掉"）
     * @return 真的释放了吗
     */
    public boolean release(String matchId) {
        if (matchId == null) {
            return false;
        }
        return busy.entrySet().removeIf(entry -> matchId.equals(entry.getValue()));
    }

    /** 释放某个竞技场（只在明确知道名字时用；同样要求 matchId 对得上）。 */
    public boolean release(String name, String matchId) {
        if (name == null || matchId == null) {
            return false;
        }
        return busy.remove(name, matchId);
    }

    /** 当前占用了几个（日志 / 探针用）。 */
    public int busyCount() {
        return busy.size();
    }

    /** 一行摘要（启动 / 排障日志用）。 */
    public String describe() {
        return busy.isEmpty() ? "竞技场占用：0 个（全部空闲）" : "竞技场占用：" + busy.size() + " 个 " + busy;
    }
}
