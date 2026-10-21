package io.mczju.infernalclash.policy;

/**
 * ★★ <b>经验条（{@code Player#setLevel / setExp}）该怎么画</b> —— 纯函数
 * （2026-10-17 第十二轮，用户裁定 §A 第 4 条的后半句）。
 *
 * <h2>用户原话（逐字）</h2>
 * <blockquote>
 * 「另外，现在的经验条显示也是有问题的，玩家<b>大部分时间完全看不到</b>自己的经验条，
 * 只有在费用（经验）不够时会更新一次经验条状态，然后经验条<b>继续冻结</b>，
 * 不显示实际经验」
 * </blockquote>
 *
 * <h2>★ 根因（证据：整份 {@code src/main/java} 里一次都没调用过）</h2>
 * {@code grep -n "setLevel|setExp|giveExp|setTotalExperience" src/main/java} ⇒ <b>0 命中</b>。
 * 也就是说：**经验条从来没有被本插件同步过**。玩家看到的那一条是原版经验条
 * （进服时带着的大厅经验 / 原版残留），只有"经验不足"那句提示出现时玩家才注意到它
 * —— 因为那时他正好在看聊天栏。⇒ 用户描述的现象（"只有不够时才更新一次然后冻结"）
 * 与"从来没同步过"完全一致。
 *
 * <h2>★★ 口径（★ 这是全项目唯一的一处「经验 → 条」换算）</h2>
 * <pre>
 *   level    = floor(xp)          // 经验池的单位就是「级」（docs/02 §6.2 Q4）
 *   progress = xp - floor(xp)     // 距下一级的比例 ⇒ 「通过经验条长度判断下次升级需要多久」
 * </pre>
 * 依据 = {@code docs/02} §6.2【Q4 已裁定】：经验是<b>连续累加</b>的，
 * 用户给的理由**逐字**就是「这样能让玩家通过<b>经验条长度</b>判断下次升级需要多久」。
 * ⇒ 所以进度条必须表示「离下一级还差多少」，而不是「离满级还差多少」。
 *
 * <h2>★ 三个边界（逐格钉住）</h2>
 * <ol>
 *   <li><b>0</b> ⇒ 等级 0、进度 0（不是"隐藏"—— 隐藏就是用户抱怨的那种"看不到"）；</li>
 *   <li><b>恰好满级</b>（{@code maxLevel}）⇒ 等级 = {@code maxLevel}、进度 <b>1.0</b>
 *       （原版在满级时把条画满 ⇒ 与它一致，而不是画成 0%）；</li>
 *   <li><b>超出上限 / 负数</b> ⇒ <b>夹紧</b>（clamp），绝不抛异常 ——
 *       经验池由 {@code XpCurve} 保证在 {@code [0,maxLevel]}，
 *       但显示层不该因为一次意外写入就把整局 UI 打断（fail-safe）。</li>
 * </ol>
 *
 * <h2>★ 与原版经验的关系（诚实说明）</h2>
 * 本项目的「经验」是**费用资源**（连续实数，上限 10），与原版经验值不是同一个量。
 * ⇒ 同步时会<b>覆盖</b>原版那一条（{@code setLevel} + {@code setExp} + {@code setTotalExperience}）。
 * 依据：{@code docs/02} §5 玩家在局内是生存模式，而 §6.2 的战局经验是**唯一**的"费用"口径
 * ⇒ 局内那一条就该显示战局经验。★ 对局结束时 {@code MatchHud#onStop} 会把
 * 经验条**归零**（而不是"还原成进场前的值"—— 我们没记过那个值，
 * 而"记一个值再还回去"需要动玩家档案，超出本轮范围）⇒ 已登记 {@code docs/05} E20。
 */
public final class XpBarPolicy {

    /**
     * 经验条状态。
     *
     * @param level    要写进 {@code Player#setLevel(int)} 的等级
     * @param progress 要写进 {@code Player#setExp(float)} 的进度（[0,1]）
     */
    public record Bar(int level, double progress) {

        public Bar {
            if (level < 0) {
                throw new IllegalArgumentException("level 不能为负：实际 " + level);
            }
            if (!(progress >= 0.0) || progress > 1.0) {
                throw new IllegalArgumentException("progress 必须落在 [0,1]：实际 " + progress);
            }
        }

        /** 进度转成原版需要的 {@code float}（★ 只在这里转，避免散落的窄化转换）。 */
        public float progressAsFloat() {
            return (float) progress;
        }
    }

    private XpBarPolicy() {
    }

    /**
     * 把「经验池」换算成经验条。
     *
     * @param xp       当前经验（单位 = 「级」；可以是小数）
     * @param maxLevel 等级上限（{@code phases.max-level}；必须 &gt; 0）
     */
    public static Bar of(double xp, double maxLevel) {
        if (!(maxLevel > 0)) {
            throw new IllegalArgumentException("maxLevel 必须 > 0：实际 " + maxLevel);
        }
        // ① 夹紧（含 NaN：NaN 的比较恒 false ⇒ 落到 0）
        double value = xp > 0 ? Math.min(xp, maxLevel) : 0.0;

        // ② 满级 ⇒ 等级 = 上限、进度画满（与原版一致）
        if (value >= maxLevel) {
            return new Bar((int) Math.floor(maxLevel), 1.0);
        }

        // ③ 常规：整数部分是等级，小数部分是"距下一级"的进度
        int level = (int) Math.floor(value);
        double progress = value - level;
        // ★★ 浮点尾差：例 9.9999999999 被 floor 成 9，而小数部分是 0.9999999999
        //   ⇒ 四舍五入到 1e-6 之后会变成 **进度 1.0**，造出
        //   「等级 9 但进度 100%」这种自相矛盾的状态（玩家的条满了却还没升级）。
        //   判据只能是**进度恒 < 1.0**（除非真的满级）⇒ 贴边时往回退一格 1e-6。
        //   ★ 为什么不改成"进位到 10 级"：那会让「等级显示」比真实经验**早**一格
        //     （9.9999 就显示 10 级），而等级是玩家判断"我能不能打这张 5 费卡"的依据。
        //     宁可让条差 0.0001% 不满，也不能让等级虚高。
        double rounded = Math.round(progress * 1_000_000.0) / 1_000_000.0;
        if (rounded >= 1.0) {
            rounded = 1.0 - 1e-6;
        }
        return new Bar(level, rounded);
    }
}
