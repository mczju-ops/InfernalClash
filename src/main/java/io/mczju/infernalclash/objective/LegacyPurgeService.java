package io.mczju.infernalclash.objective;

import io.mczju.infernalclash.arena.ArenaConfig;
import io.mczju.infernalclash.arena.Point;
import io.mczju.infernalclash.match.UnitRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * ★★★ <b>遗留实体清理的执行层</b>（{@code /incl debug purge-legacy}）
 * —— 2026-10-17 第十二轮，用户裁定 §A 第 1 条
 * 「<b>因为是测试服，所以破格一次，你直接全域清理</b>」。
 *
 * <h2>★★ 铁律 10 的四问（这是本项目唯一一处"会按范围删实体"的地方，四问必须逐条答清）</h2>
 * <ol>
 *   <li><b>范围界定</b>：{@code arenas.yml} 里本插件自己的点位
 *       （信标 ×2 / 塔 ×4 / 出生点 ×2 / 河道 / 桥 ×2）各 {@value LegacyPurge#LEGACY_RADIUS_CELLS}
 *       格方形。★ 只遍历**这些方形覆盖到的已加载区块**
 *       （{@code World#isChunkLoaded} + {@code Chunk#getEntities()}），
 *       <b>没有</b> {@code world.getEntities()} / {@code getLivingEntities} / {@code getNearbyEntities}。</li>
 *   <li><b>失败方向</b>：点位缺失 / 世界名取不到 / 玩家不在场而区块没加载
 *       ⇒ <b>一个都不删</b>（{@link LegacyPurge.Decision#SKIP_NO_CENTER}）+ 明确告警。
 *       <b>绝不</b>退化成"按世界清"。</li>
 *   <li><b>归属判定</b>：<b>PDC</b>（{@link UnitRegistry#KEY_MATCH} 等四键）。
 *       <b>没带 PDC 的一律只报告</b>（连坐标一起给用户）—— 绝不按类型认领
 *       （那正是当年把玩家假人 / 盔甲架 / 别人的怪一起删掉的那条路）。</li>
 *   <li><b>可对账</b>：扫描前后各一次计数，四类桶（可清 / 只报告 / 跳过 / 总数）由
 *       {@link LegacyPurge.Plan#describe(int)} 一次说清，且带一条**恒等式断言**。</li>
 * </ol>
 *
 * <h2>★ 默认只报告，{@code --yes} 才真删</h2>
 * 用户要求「默认只报告不删，加 {@code --yes} 才真删」⇒ 判据在
 * {@link #run(boolean, Consumer, Consumer)} 的第一个参数上，**没有别的开关**。
 *
 * <h2>★ 为什么不做成"自动跑"</h2>
 * 自动清理的爆炸半径是"每次服务器启动都可能按范围删东西"；而显式指令的爆炸半径是
 * "发令者敲了一次"。用户已经说了会自己按需触发 ⇒ 只提供显式入口。
 */
public final class LegacyPurgeService {

    private final Plugin plugin;
    private final ArenaConfig arena;
    private final UnitRegistry registry;
    private final Logger logger;

    public LegacyPurgeService(Plugin plugin, ArenaConfig arena, UnitRegistry registry) {
        this.plugin = plugin;
        this.arena = arena;
        this.registry = registry;
        this.logger = plugin.getLogger();
    }

    /**
     * 跑一次。
     *
     * @param confirm  {@code false} = **只报告**（默认）；{@code true} = 真的删
     * @param report   面向发令者的输出（可为 {@code null}）
     * @param warn     告警出口（可为 {@code null}）
     * @return 本次**真的删掉**的数量（只报告时恒为 0）
     */
    public int run(boolean confirm, Consumer<String> report, Consumer<String> warn) {
        return run(confirm, report, warn, false);
    }

    /**
     * ★★ <b>同 {@link #run(boolean, Consumer, Consumer)}，但可以"安静"</b>（2026-10-20 新增）。
     *
     * <h2>为什么需要 quiet</h2>
     * 自动兜底（启动一次 + 无对局时每 60 秒重试）会**反复**跑本方法；若每次都打那三行
     * "扫描区块 N 个 / 分类统计 / 只报告"的控制台摘要，日志会被刷满（20 分钟 20 遍）。
     * ⇒ {@code quiet = true} 时：**只在真的删了东西、或有带 PDC 之外的东西要报告时**才输出；
     * 例行"什么都没找到"不刷屏。★ 但**告警**永远照打（fail-safe 跳过、上限截断）。
     *
     * @param quiet {@code true} = 无事发生时不出声（自动兜底用）；{@code false} = 手动指令用
     */
    public int run(boolean confirm, Consumer<String> report, Consumer<String> warn, boolean quiet) {
        Consumer<String> out = report == null ? text -> { } : report;
        Consumer<String> alarm = warn == null ? text -> logger.warning(text) : warn;

        // ---- ① 中心点（点位缺失 ⇒ fail-safe 直接返回，一个都不删）
        List<Center> centers = centers();
        if (centers.isEmpty()) {
            String message = "[IC] 遗留清理跳过：arenas.yml 里一个可用点位都没有"
                    + "（或它们的世界名取不到），按 fail-safe 一个都不删。"
                    + "绝不退化成「按世界清」（铁律 10）";
            alarm.accept(message);
            out.accept(message);
            return 0;
        }

        // ---- ② 有限范围的扫描：只遍历这些方形覆盖到的**已加载区块**
        Map<UUID, Entity> scanned = new LinkedHashMap<>();
        Set<String> loadedChunks = new LinkedHashSet<>();
        int skippedUnloaded = 0;
        for (Center center : centers) {
            for (int chunkX = (center.x() - LegacyPurge.LEGACY_RADIUS_CELLS) >> 4;
                 chunkX <= (center.x() + LegacyPurge.LEGACY_RADIUS_CELLS) >> 4; chunkX++) {
                for (int chunkZ = (center.z() - LegacyPurge.LEGACY_RADIUS_CELLS) >> 4;
                     chunkZ <= (center.z() + LegacyPurge.LEGACY_RADIUS_CELLS) >> 4; chunkZ++) {
                    if (!loadedChunks.add(chunkX + "," + chunkZ + "@" + center.world().getName())) {
                        continue;   // 同一个区块被两个中心点覆盖：只数一次
                    }
                    if (!center.world().isChunkLoaded(chunkX, chunkZ)) {
                        skippedUnloaded++;
                        continue;   // ★ 不主动加载区块：加载会改世界状态，且可能把一大片地形拉进内存
                    }
                    Chunk chunk = center.world().getChunkAt(chunkX, chunkZ);
                    for (Entity entity : chunk.getEntities()) {
                        scanned.putIfAbsent(entity.getUniqueId(), entity);
                    }
                }
            }
        }

        // ---- ③ 分类（纯判据）
        List<LegacyPurge.Candidate> candidates = new ArrayList<>();
        for (Entity entity : scanned.values()) {
            candidates.add(candidateOf(entity, centers));
        }
        // ★★★ 2026-10-20（真实事故的补丁）：**先滤掉"属于进行中对局"的实体**。
        //   事故原貌：把本清理挂到"准备阶段传送之后"时，顺序是 生成目标物 → 传送 → 清理
        //   ⇒ 清理把刚生成的 12 个目标物当遗留删了（现场：侧边栏全 0/240、场上没有塔）。
        //   「遗留」的定义就是"不属于任何进行中的对局"⇒ 这一步必须在 plan() 之前。
        candidates = new ArrayList<>(LegacyPurge.excludingActiveMatches(candidates, matchId ->
                io.mczju.infernalclash.match.MatchService.get(matchId) != null));
        LegacyPurge.Plan plan = LegacyPurge.plan(candidates, true,
                candidate -> withinAnyCenter(candidate, centers));

        boolean noteworthy = !plan.purgeable().isEmpty() || !plan.reportOnly().isEmpty();
        if (!quiet || noteworthy) {
            String header = "[IC] 遗留清理" + (confirm ? "（--yes：会真的删）" : "（默认只报告）")
                    + "｜matchId=(不属于任何进行中的对局)｜中心点 " + centers.size() + " 个"
                    + "｜扫描区块 " + loadedChunks.size() + " 个（其中未加载跳过 " + skippedUnloaded + " 个）";
            logger.info(header);
            logger.info("[IC] " + plan.describe(scanned.size()) + "｜" + LegacyPurge.scopeProof());
            out.accept(header);
            out.accept("[IC] " + plan.describe(scanned.size()));
        }
        if (!quiet && skippedUnloaded > 0) {
            logger.warning("[IC] 有 " + skippedUnloaded + " 个区块没加载，里面的遗留实体这次清不到"
                    + "（本类绝不主动加载区块）。玩家走进那片区域后，兜底扫描会再试一次。");
        }

        // ---- ④ 未带 PDC 的那批：**只报告**，连坐标一起给（绝不擅自扩大白名单）
        //   ★ 2026-10-20：quiet 模式（自动兜底每 60 秒一次）**不逐条列**，否则那几只路过的原版怪
        //     会每分钟把控制台刷一遍（实测：lobby y=92 的 4 只怪，与竞技场无关）。
        //     ★ 手动指令仍逐条列（那是给人看的清单）。
        if (!plan.reportOnly().isEmpty() && !quiet) {
            logger.warning("[IC] 有 " + plan.reportOnly().size()
                    + " 个实体没带本插件的 PDC（多半是第十轮之前生成的旧版本遗留物）"
                    + "，按铁律 10 只报告不删（绝不按类型认领）。清单如下：");
            for (LegacyPurge.Candidate candidate : plan.reportOnly()) {
                logger.warning("[IC] · " + candidate.describe());
            }
            List<String> manual = LegacyPurge.suggestManualCommands(plan.reportOnly(), 20);
            logger.warning("[IC] 若确认要清，请用下面这些单独的有界指令（自己核对坐标后再敲）：");
            for (String command : manual) {
                logger.warning("[IC] " + command);
            }
            out.accept("[IC] 有 " + plan.reportOnly().size()
                    + " 个实体没带 PDC，只报告不删，精确坐标与控制台清单见日志");
        }

        // ---- ⑤ 真要删（只在 --yes 时）—— ★ 删之前**再核一次 PDC**（防 UUID 复用 / 跨局串场）
        if (!confirm) {
            out.accept("[IC] 本次是只报告（没有删任何东西）。"
                    + "确认清单无误后，用 /incl debug purge-legacy --yes 真删");
            return 0;
        }
        if (plan.truncated()) {
            alarm.accept("[IC] 可清数量 " + plan.purgeable().size() + " 超过单次硬上限 "
                    + LegacyPurge.MAX_PURGE_PER_RUN + "，本次只删前 "
                    + LegacyPurge.MAX_PURGE_PER_RUN + " 个，请再敲一次继续"
                    + "（绝不「删一半装作删完了」）");
        }
        int removed = 0;
        int mismatched = 0;
        int vanished = 0;
        for (LegacyPurge.Candidate candidate : plan.purgeable()) {
            if (removed >= LegacyPurge.MAX_PURGE_PER_RUN) {
                break;
            }
            Entity entity = Bukkit.getEntity(candidate.uuid());
            if (entity == null || !entity.isValid()) {
                vanished++;   // ★ 已经不在世界里了（区块卸载 / 早被删掉）—— 计数，别静默
                continue;
            }
            // ★★★ 2026-10-21 修（用户：「为什么会有俩信标」）：**删前复核只认 PDC**。
            //   原来调的是 registry.isOwnedBy(entity, matchId)，而它要求「PDC 同意 **且** 账本也登记过」
            //   ⇒ 遗留清理处理的恰恰是"账本已经没了"的场景（重启后账本归零）
            //   ⇒ 每一个真·遗留物都被判成「归属不符」⇒ **一个都删不掉**（现场 `已删除 0 个`）⇒
            //     每打一局就在原地多垒一个信标/塔（用户看到的就是两个信标）。
            //   ★ 范围与归属判据一个字没动：仍是「带本插件 PDC」＋「不属于进行中的对局」＋「点位 64 格内」，
            //     这里只是把「账本也要认」这条**在遗留场景下必然为假**的条件去掉（判据 = LegacyPurge#stillOurs）。
            OwnershipFromPdc now = ownerOf(entity);
            if (!LegacyPurge.stillOurs(now.matchId(), candidate.matchId())) {
                mismatched++;
                continue;   // ★ 复核说它不再属于那一场 ⇒ 故意不删
            }
            entity.remove();
            registry.forget(candidate.uuid());
            removed++;
        }
        // ---- ⑥ 清理后计数（可对账）
        int after = 0;
        for (Center center : centers) {
            for (int chunkX = (center.x() - LegacyPurge.LEGACY_RADIUS_CELLS) >> 4;
                 chunkX <= (center.x() + LegacyPurge.LEGACY_RADIUS_CELLS) >> 4; chunkX++) {
                for (int chunkZ = (center.z() - LegacyPurge.LEGACY_RADIUS_CELLS) >> 4;
                     chunkZ <= (center.z() + LegacyPurge.LEGACY_RADIUS_CELLS) >> 4; chunkZ++) {
                    if (!center.world().isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    for (Entity entity : center.world().getChunkAt(chunkX, chunkZ).getEntities()) {
                        if (registry.ownerFromPdc(entity) != null) {
                            after++;
                        }
                    }
                }
            }
        }
        String summary = "[IC] 遗留清理完成：已删除 " + removed + " 个"
                + "｜已不在 " + vanished + " 个"
                + "｜归属不符（PDC 复核未过）故意不删 " + mismatched + " 个"
                + "｜清理后范围内仍带本插件 PDC 的实体 " + after + " 个"
                + "（>0 说明还有没删干净的，再敲一次本指令即可）"
                + "｜归属判定 = PDC 三键 + 删前复核（绝不按类型 / 绝不按世界）";
        logger.info(summary);
        out.accept(summary);
        return removed;
    }

    // ------------------------------------------------------------------ 中心点

    /** 一个清理中心（世界 + 格坐标）。 */
    private record Center(World world, int x, int z) {
    }

    /**
     * 清理中心点 = {@code arenas.yml} 里**本插件自己的点位**。
     *
     * <p>★ 只要 4 个必需点位里**至少一个**能用就继续（其余能用的一起用）——
     * 但若**一个都没有**，调用方 fail-safe 一个都不删。
     * ★ 世界名取不到 ⇒ 跳过那个点（不猜世界、不用 {@code getWorlds().get(0)}）。
     */
    private List<Center> centers() {
        List<Center> centers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String key : new String[]{"beacon.red", "beacon.blue", "spawn.red", "spawn.blue",
                "tower.red.left", "tower.red.right", "tower.blue.left", "tower.blue.right",
                // ★ 2026-10-18：河与桥改成两个对角点（旧键 river / bridge.1 / bridge.2 已作废）
                "river.1", "river.2", "bridge.1.a", "bridge.1.b", "bridge.2.a", "bridge.2.b",
                "spectate"}) {
            Point point = arena.point(key);
            if (point == null) {
                continue;
            }
            World world = worldOf(point);
            if (world == null) {
                logger.warning("[IC] 遗留清理：点位 " + key + " 的世界 " + point.world()
                        + " 取不到，跳过这个中心点（不猜世界、不用 getWorlds）");
                continue;
            }
            int cellX = (int) Math.floor(point.x());
            int cellZ = (int) Math.floor(point.z());
            if (seen.add(world.getName() + ":" + cellX + "," + cellZ)) {
                centers.add(new Center(world, cellX, cellZ));
            }
        }
        // ★ 兜底：4 个必需点位一个都用不上 ⇒ 返回空列表（调用方 fail-safe）
        if (centers.isEmpty()) {
            logger.warning("[IC] 遗留清理：6 个目标物点位与双方出生点一个都不可用，范围界定失败");
        }
        return centers;
    }

    /** 点位 → 世界（★ 按名取，**不用** {@code getWorlds()} —— 那条记号被字节码守卫拦着）。 */
    private World worldOf(Point point) {
        try {
            return Bukkit.getWorld(point.world());
        } catch (Throwable t) {
            return null;
        }
    }

    private LegacyPurge.Candidate candidateOf(Entity entity, List<Center> centers) {
        OwnershipFromPdc owner = ownerOf(entity);
        return new LegacyPurge.Candidate(entity.getUniqueId(), entity.getType().name(),
                owner.matchId(), entity.getWorld().getName(),
                entity.getLocation().getX(), entity.getLocation().getY(),
                entity.getLocation().getZ(), entity.isValid());
    }

    /** 只读 PDC 的归属（本类不查账本 —— 账本正是"已经丢了"的那份东西）。 */
    private record OwnershipFromPdc(String matchId) {
    }

    private OwnershipFromPdc ownerOf(Entity entity) {
        try {
            var owner = registry.ownerFromPdc(entity);
            return new OwnershipFromPdc(owner == null ? null : owner.matchId());
        } catch (Throwable t) {
            return new OwnershipFromPdc(null);
        }
    }

    private boolean withinAnyCenter(LegacyPurge.Candidate candidate, List<Center> centers) {
        for (Center center : centers) {
            if (center.world().getName().equals(candidate.world())
                    && LegacyPurge.inSquare(candidate.x(), candidate.z(),
                            center.x(), center.z(), LegacyPurge.LEGACY_RADIUS_CELLS)) {
                return true;
            }
        }
        return false;
    }


    /** 本服务是否可用（点位不齐时给一条明确的前置说明）。 */
    public String describeReadiness() {
        List<Center> centers = centers();
        if (centers.isEmpty()) {
            return "不可用：arenas.yml 里没有可用点位（先在 /incl arena 里采集点位）";
        }
        return "可用：中心点 " + centers.size() + " 个，半径 " + LegacyPurge.LEGACY_RADIUS_CELLS
                + " 格方形；默认只报告，--yes 才真删";
    }


}
