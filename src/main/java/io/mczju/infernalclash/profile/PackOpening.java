package io.mczju.infernalclash.profile;

import io.mczju.infernalclash.policy.PackRoller;
import io.mczju.infernalclash.policy.Rarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ★★★ <b>开包结算（纯函数，可离线单测）</b> —— 2026-10-19 新增（接入 U5 商店的「购买」）。
 *
 * <h2>它补的是什么</h2>
 * 在此之前 {@code ui/ShopMenu} 的三个购买按钮只弹一句
 * 「开包功能还没接入（本轮不扣金币、也不发卡）」—— 抽取器 {@link PackRoller} 早就有了，
 * 缺的是「抽出来的卡怎么落到玩家档案里」这一层。
 *
 * <h2>规则（逐条出自 docs，不是我发明的）</h2>
 * <ol>
 *   <li>抽到**新卡** ⇒ 图鉴置 {@link PlayerProgress#CODEX_NORMAL}（普通版）；</li>
 *   <li>抽到**已拥有**的普通卡 ⇒ **自动转成烈焰粉**（【Q30】已裁定：即时结算、不弹二次确认），
 *       数量 = {@link CraftRules#dustFromDismantle(Rarity, boolean)} 的**普通版**列；</li>
 *   <li>抽到**金卡**且玩家只有普通版 ⇒ **普通版被自动分解**（【Q30】/【Q31】：
 *       "同一卡牌抽到第一张金色版本时，会自动分解掉非金色版本"），
 *       并把图鉴置 {@link PlayerProgress#CODEX_GOLD}；</li>
 *   <li>抽到**已拥有的金卡** ⇒ 自动转成烈焰粉，数量 = 兑换表的**金色**列（= 普通版的 4 倍）；</li>
 *   <li>金卡**只能抽到**（【Q32】），本类不提供任何"合成金卡"的路径。</li>
 * </ol>
 *
 * <h2>失败方向</h2>
 * 纯函数、不抛「钱不够」这类错（那是调用方的事）：它假定钱已经扣过。
 * 唯一的异常来自入参为空 / 图纸里出现 null —— 属于**接线错误**，应当立刻暴露。
 */
public final class PackOpening {

    /** 一张卡的结算种类（给界面/消息用）。 */
    public enum Outcome {
        /** 新卡（普通版首次拥有）。 */
        NEW,
        /** 新卡（金色版首次拥有，且原本没有普通版）。 */
        NEW_GOLD,
        /** 原来是普通版、这次抽到金卡 ⇒ 普通版被自动分解，图鉴升级为金。 */
        UPGRADED,
        /** 重复（已拥有同版本）⇒ 自动转成烈焰粉。 */
        DUPLICATE
    }

    /**
     * 一张卡的结算结果。
     *
     * @param cardId  卡 id
     * @param rarity  稀有度（决定转尘数量）
     * @param gold    这张抽到的是不是金卡
     * @param outcome 结算种类
     * @param dust    这一张换到的烈焰粉（0 = 新卡）
     */
    public record Grant(String cardId, Rarity rarity, boolean gold, Outcome outcome, int dust) {
        public Grant {
            if (cardId == null || cardId.isBlank()) {
                throw new IllegalArgumentException("结算的卡 id 不能为空");
            }
            if (rarity == null) {
                throw new IllegalArgumentException("结算的稀有度不能为空（卡 " + cardId + "）");
            }
            if (dust < 0) {
                throw new IllegalArgumentException("换到的烈焰粉不能为负：实际 " + dust
                        + "（卡 " + cardId + "）");
            }
        }

        /**
         * ★ <b>这张卡是不是「新到手」的</b>（2026-10-21 新增 —— 用户追加要求：
         * 「抽到**未拥有**卡牌时应该标注”新！“」）。
         *
         * <h2>判据为什么是这两档</h2>
         * <ul>
         *   <li>{@link Outcome#NEW} —— 首次拥有普通版 ⇒ **新**；</li>
         *   <li>{@link Outcome#NEW_GOLD} —— 首次拥有这张卡（而且是金版）⇒ **新**；</li>
         *   <li>{@link Outcome#UPGRADED} —— 他**本来就有**这张卡的普通版，这次只是升成金版
         *       ⇒ 卡牌本身不是新的（图鉴里那张格子一直在），<b>不标</b>「新！」；</li>
         *   <li>{@link Outcome#DUPLICATE} —— 重复转尘 ⇒ 当然不是新的。</li>
         * </ul>
         * ★ 判据收在这里（而不是界面里写 {@code == NEW || == NEW_GOLD}）：
         * 开包结算的四种结局**只有这一个类**知道，界面只负责显示。
         */
        public boolean isNew() {
            return outcome == Outcome.NEW || outcome == Outcome.NEW_GOLD;
        }
    }

    /**
     * 一次开包的结算结果。
     *
     * @param progress   结算后的进度（**新实例**）
     * @param grants     逐张结算明细（顺序 = 抽取顺序）
     * @param dustGained 本次总共换到的烈焰粉（= 各 grant 的 dust 之和）
     */
    public record Result(PlayerProgress progress, List<Grant> grants, int dustGained) {
        public Result {
            Objects.requireNonNull(progress, "progress");
            grants = List.copyOf(grants == null ? List.of() : grants);
            if (dustGained < 0) {
                throw new IllegalArgumentException("总转尘不能为负：实际 " + dustGained);
            }
        }
    }

    private PackOpening() {
    }

    /**
     * 把一包（或多包）抽出来的卡**逐张**结算进进度。
     *
     * <p>★ 逐张顺序结算（不是先算集合再合并）：同一包里抽到两张同名卡时，
     * 第一张算新卡、第二张算重复转尘 —— 这才是"按抽取顺序结算"的语义。
     *
     * @param before 结算前的进度（调用方应已扣掉金币）
     * @param drawn  抽取结果（{@link PackRoller#drawPack} 的输出）
     */
    public static Result open(PlayerProgress before, List<PackRoller.DrawnCard> drawn) {
        Objects.requireNonNull(before, "before");
        if (drawn == null || drawn.isEmpty()) {
            return new Result(before, List.of(), 0);
        }
        PlayerProgress progress = before;
        List<Grant> grants = new ArrayList<>(drawn.size());
        long dust = 0;
        for (PackRoller.DrawnCard card : drawn) {
            Objects.requireNonNull(card, "drawn 里出现了 null（接线错误）");
            Rarity rarity = card.rarity();
            String id = card.cardId();
            String state = progress.codexState(id);
            Grant grant;
            if (card.gold()) {
                if (PlayerProgress.CODEX_GOLD.equals(state)) {
                    int amount = CraftRules.dustFromDuplicateGold(rarity);
                    progress = progress.addDust(amount);
                    grant = new Grant(id, rarity, true, Outcome.DUPLICATE, amount);
                } else if (PlayerProgress.CODEX_NORMAL.equals(state)) {
                    // 升级：普通版被自动分解（【Q30】），图鉴变成金
                    int amount = CraftRules.dustFromDismantle(rarity, false);
                    progress = progress.addDust(amount).unlockGold(id);
                    grant = new Grant(id, rarity, true, Outcome.UPGRADED, amount);
                } else {
                    progress = progress.unlockGold(id);
                    grant = new Grant(id, rarity, true, Outcome.NEW_GOLD, 0);
                }
            } else if (PlayerProgress.CODEX_NONE.equals(state)) {
                progress = progress.unlock(id);
                grant = new Grant(id, rarity, false, Outcome.NEW, 0);
            } else {
                int amount = CraftRules.dustFromDismantle(rarity, false);
                progress = progress.addDust(amount);
                grant = new Grant(id, rarity, false, Outcome.DUPLICATE, amount);
            }
            dust += grant.dust();
            grants.add(grant);
        }
        if (dust > Integer.MAX_VALUE) {
            throw new IllegalStateException("本次开包换到的烈焰粉超出上限（" + dust + "）");
        }
        return new Result(progress, grants, (int) dust);
    }
}
