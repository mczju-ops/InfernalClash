package io.mczju.infernalclash.policy;

import io.mczju.infernalclash.card.Card;

import java.util.UUID;

/**
 * ★★★ <b>「锁定同一个目标越久、打得越疼」</b> —— 纯状态机（2026-10-18 新增）。
 *
 * <h2>出处（{@code docs/19} 第 113 行烈焰守卫，逐字）</h2>
 * 「锁定目标后，对该目标的伤害**每 0.5s +1**（从 **1** 起、**上限每 0.5s 10 点**），
 * **重新索敌时重置**」。
 *
 * <h2>使用顺序（★ 顺序本身就是口径，调用方必须照做）</h2>
 * <pre>{@code
 * track = track.focus(targetId);      // ① 先对准目标（换目标 ⇒ 档位归零）
 * int damage = track.damageOf(spec);  // ② 再读"这一次该打多少"
 * target.damage(damage);              // ③ 真的造成伤害
 * track = track.hit();                // ④ 最后记一次出手（下一拍 +1）
 * }</pre>
 * ★ 为什么必须"先 focus 再读伤害"：换目标那一拍应当**按第一档结算**（1 点）。
 * 若反过来（先算伤害再 focus），换目标的那一击会用**上一个目标攒出来的档位**打 ——
 * 那就是"越打越疼却打错人"的经典错法，且完全不会报错。
 *
 * <h2>口径（四条）</h2>
 * <ol>
 *   <li>{@link #fresh()} 的第 1 次 = {@link Card.RampDamage#base()}（docs「从 1 起」）；</li>
 *   <li>之后每次 +{@link Card.RampDamage#step()}，**不超过** {@link Card.RampDamage#max()}；</li>
 *   <li>★ 「重新索敌」= {@link #focus} 看到的目标与上次不同 ⇒ 档位归零；</li>
 *   <li>★ 目标一时消失（{@code focus(null)}）<b>不清档</b> —— 只有"看到新目标"才归零
 *       （否则"目标走出视野一下"会把攒了 10 档的伤害清空，玩家会觉得莫名其妙变弱）。</li>
 * </ol>
 *
 * <p>★ 本类不碰 Bukkit（只吃 UUID 与 {@link Card.RampDamage}）⇒ 必然可离线单测。
 */
public final class RampDamageTrack {

    private final UUID target;
    private final int hits;
    private final boolean capped;

    private RampDamageTrack(UUID target, int hits, boolean capped) {
        this.target = target;
        this.hits = hits;
        this.capped = capped;
    }

    /** 全新的跟踪器：还没有目标、也还没有出手。 */
    public static RampDamageTrack fresh() {
        return new RampDamageTrack(null, 0, false);
    }

    /** 当前锁定的目标（{@code null} = 还没有 / 刚丢失）。 */
    public UUID target() {
        return target;
    }

    /** 已经对**当前目标**出手过几次。 */
    public int hits() {
        return hits;
    }

    /** 是否已经到上限（只用于日志/描述，判据本身在 {@link #damageOf}）。 */
    public boolean capped() {
        return capped;
    }

    /**
     * 对准这一拍的目标（★ 换目标 ⇒ 档位归零）。
     *
     * @param targetNow 这一 tick 的目标；{@code null} = 没有目标 ⇒ **状态完全不变**
     *                  （★ 刻意保留"上一次的目标"与档位：目标走出视野一下不该清档，
     *                  见本方法下方的「口径④」）
     */
    public RampDamageTrack focus(UUID targetNow) {
        if (targetNow == null) {
            return this;
        }
        if (targetNow.equals(target)) {
            return this;
        }
        // ★ 「重新索敌」⇒ 归零：这一拍按第一档打
        return new RampDamageTrack(targetNow, 0, false);
    }

    /** 这一档的伤害（还没出手时 = 第一档）。 */
    public int damageOf(Card.RampDamage spec) {
        return spec == null ? 0 : Math.min(spec.max(), spec.base() + spec.step() * hits);
    }

    /** 记一次出手（★ 必须在**真的造成伤害之后**调）。 */
    public RampDamageTrack hit() {
        return new RampDamageTrack(target, hits + 1, capped);
    }

    /** 记一次出手，并按新档位更新「是否到上限」。 */
    public RampDamageTrack hit(Card.RampDamage spec) {
        RampDamageTrack next = hit();
        if (spec == null) {
            return next;
        }
        return new RampDamageTrack(next.target, next.hits, next.damageOf(spec) >= spec.max());
    }

    /** 一行可读摘要（日志用）。 */
    public String describe(Card.RampDamage spec) {
        if (spec == null) {
            return "无递增伤害";
        }
        return "第 " + (hits + 1) + " 次出手 / 本次 " + damageOf(spec) + " 点"
                + "（每 " + spec.intervalTicks() + " tick 一次，每档 +" + spec.step()
                + "，上限 " + spec.max() + "）" + (capped ? "｜已到上限" : "");
    }
}
