package io.mczju.infernalclash.game;

import io.mczju.infernalclash.policy.PlayerBuffs;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * ★★★ <b>「本局临时改过的玩家状态」的待还原账本（落盘）</b> —— 2026-10-26 安全修复。
 *
 * <h2>它修的是什么（用户逐字，最高优先级）</h2>
 * <blockquote>
 * 「**本插件现在存在玩家无敌外泄的严重bug**」＋「`restoreTargetBan` 不能只依赖"玩家在线"：
 * 把「本局设过无敌的 UUID ＋ 进局前原值」持久化成待还原名单，玩家下次上线或插件 `onEnable`
 * 时对账还原。`onDisable` 也走一次还原（现在只靠 `onStop`，关服/崩溃时拿不到在线玩家）。」
 * </blockquote>
 *
 * <h2>事故链（为什么"只靠 onStop"必然漏）</h2>
 * <ol>
 *   <li>{@code MatchPlayerBuffs} 把玩家设成 {@code setInvulnerable(true)}（为了让怪不索敌他）；</li>
 *   <li>玩家<b>中途退出</b>（退出 = 投降）或对局结束时他已经离线 ⇒
 *       旧的 {@code restoreTargetBan} 对离线玩家 <b>{@code continue}</b>，
 *       然后**无条件清空**了内存里的"原值表"；</li>
 *   <li>{@code invulnerable} 是<b>会写进 playerdata 的实体标记</b> ⇒ 他下次上线就是<b>永久无敌</b>。
 *       同一类事故还有 {@code allowFlight}（{@code mayfly} 也落盘）、
 *       无限时长的药水效果（速度 II / 夜视 / 抗性 V 也落盘）。</li>
 * </ol>
 *
 * <h2>本类的口径（四条，逐条可复述）</h2>
 * <ol>
 *   <li><b>先记账、再动手</b>：{@code MatchPlayerBuffs} / {@code MatchHud} 在**第一次**改某个
 *       玩家的某一项状态之前，必须把"原值"交给本类；本类<b>立刻落盘</b>。
 *       ⇒ 崩溃 / 关服 / 玩家退出，账本都在，下次一定还得上；</li>
 *   <li><b>只记第一次</b>：同一玩家同一项，后来的补齐（每 20 tick 一次）**不许覆盖**原值
 *       （否则还原成"上一拍的样子"，等于没还原）；</li>
 *   <li><b>还原与在线解耦</b>：{@link #restoreAllOnline(String)} 只处理**此刻在线**的；
 *       离线的**留在账本里**，由 {@code PlayerJoinEvent} / {@code PlayerProfileLoadedEvent}
 *       / 插件 {@code onEnable} 三个入口补上（{@link #restoreIfPending(Player, String)}）；</li>
 *   <li><b>只还原我们自己改过的那一位</b>：进局前本来就无敌 / 本来就能飞 / 本来就有那条无限效果的，
 *       一律不动（判据在 {@code policy/PlayerBuffs#restoreAction} 与
 *       {@code policy/PlayerTargetBan#shouldRestore}）。</li>
 * </ol>
 *
 * <h2>★ 文件在哪 / 坏了怎么办</h2>
 * {@code plugins/InfernalClash/player-restore.yml}（机器生成，不用手改）。
 * 解析失败 / 读写失败 ⇒ <b>只告警、不阻止启动</b>（这是收尾用的辅助文件；
 * 启动失败会让整个游戏不可用，比"少还原一次"严重得多）。★ 但会在日志里点名"有 N 名玩家待还原"。
 *
 * <h2>★ 为什么不是"干脆别用 setInvulnerable"</h2>
 * 它是**唯一**能让 brain 型怪物（猪灵 / 猪灵蛮兵）不索敌玩家的手段（{@code javap} 实测，
 * 见 {@code policy/PlayerTargetBan} 的类注释）。⇒ 保留机制，但把它变成**可撤销**的：
 * ① 落盘账本；② 退出即还原；③ 上线对账；④ 关服也还原；⑤ {@code config.yml} 可以整体关掉。
 */
public final class PlayerStateRestore {

    /** 账本文件名（插件数据目录下）。 */
    public static final String FILE = "player-restore.yml";

    /** 顶层段落。 */
    public static final String SECTION = "pending";

    /** 结构版本（将来改字段时用它做迁移）。 */
    private static final int VERSION = 1;

    private final Plugin plugin;
    private final File file;
    private final Logger logger;
    private final Function<UUID, Player> playerLookup;

    /** 玩家 UUID → 待还原的账（保序，便于日志与测试复现）。 */
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    /**
     * @param plugin   插件（可为 {@code null}：离线单测）
     * @param file     账本文件（可为 {@code null}：只走内存，不落盘 —— 仅测试用）
     * @param logger   日志器（可为 {@code null}）
     * @param lookup   「UUID → 在线玩家」的解析器（{@code null} ⇒ 用 Bukkit；测试可注入恒 {@code null}）
     */
    public PlayerStateRestore(Plugin plugin, File file, Logger logger,
                              Function<UUID, Player> lookup) {
        this.plugin = plugin;
        this.file = file;
        this.logger = logger;
        this.playerLookup = lookup != null ? lookup : PlayerStateRestore::onlinePlayer;
    }

    /** 便利构造：日志器取插件的、解析器走 Bukkit。 */
    public PlayerStateRestore(Plugin plugin, File file) {
        this(plugin, file, plugin == null ? null : plugin.getLogger(), null);
    }

    /**
     * ★ 一个玩家的待还原账。
     *
     * <p>每一格都是 {@code null} = **这一项我们从没改过**（不还原，也不写盘）。
     * 这个"null 表示没动过"的口径很重要：它让"只还原我们自己改过的那一位"变成**结构保证**，
     * 而不是靠调用方记得别乱还原。
     */
    public static final class Pending {

        /** 哪一局留下的（排障用；不参与判据）。 */
        String matchId;

        /** 记账时间（排障用）。 */
        long savedAt;

        /** 进局前是不是本来无敌（{@code null} = 没动过）。 */
        Boolean invulnerableBefore;

        /** 进局前能不能飞（{@code null} = 没动过）。 */
        Boolean allowFlightBefore;

        /** 进局前的经验条三件套（{@code null} = 没动过经验条）。 */
        Integer levelBefore;
        Float expBefore;
        Integer totalXpBefore;

        /** 我们加过的效果 → 它原来的样子（空表 = 没加过任何效果）。 */
        final Map<String, PlayerBuffs.Snapshot> effects = new LinkedHashMap<>();

        boolean isEmpty() {
            return invulnerableBefore == null && allowFlightBefore == null
                    && levelBefore == null && effects.isEmpty();
        }

        /** 一行摘要（日志用）。 */
        String describe() {
            List<String> bits = new ArrayList<>();
            if (invulnerableBefore != null) {
                bits.add("无敌(原=" + invulnerableBefore + ")");
            }
            if (allowFlightBefore != null) {
                bits.add("飞行(原=" + allowFlightBefore + ")");
            }
            if (levelBefore != null) {
                bits.add("经验条(原=" + levelBefore + " 级)");
            }
            if (!effects.isEmpty()) {
                bits.add("效果" + effects.keySet());
            }
            return bits.isEmpty() ? "（空）" : String.join("＋", bits);
        }
    }

    // ------------------------------------------------------------------ 记账

    /**
     * 记下「这一项我们在动它之前长什么样」——**只记第一次**。
     *
     * @return 这一次真的记下了吗（{@code false} = 之前已经记过 / 参数非法）
     */
    public boolean noteInvulnerable(UUID id, String matchId, boolean before) {
        Pending entry = entryFor(id, matchId);
        if (entry == null || entry.invulnerableBefore != null) {
            return false;
        }
        entry.invulnerableBefore = before;
        save();
        return true;
    }

    /** 同上：创造飞行（{@code allowFlight} / {@code mayfly} 也会落盘）。 */
    public boolean noteAllowFlight(UUID id, String matchId, boolean before) {
        Pending entry = entryFor(id, matchId);
        if (entry == null || entry.allowFlightBefore != null) {
            return false;
        }
        entry.allowFlightBefore = before;
        save();
        return true;
    }

    /** 同上：经验条三件套（{@code MatchHud} 会把它改成对局内的经验值）。 */
    public boolean noteXp(UUID id, String matchId, int level, float exp, int total) {
        Pending entry = entryFor(id, matchId);
        if (entry == null || entry.levelBefore != null) {
            return false;
        }
        entry.levelBefore = level;
        entry.expBefore = exp;
        entry.totalXpBefore = total;
        save();
        return true;
    }

    /** 同上：一条我们加过的常驻效果（原样快照，判据见 {@link PlayerBuffs#restoreAction}）。 */
    public boolean noteEffect(UUID id, String matchId, PlayerBuffs.Snapshot snapshot) {
        if (snapshot == null || snapshot.spec() == null) {
            return false;
        }
        Pending entry = entryFor(id, matchId);
        if (entry == null || entry.effects.containsKey(snapshot.spec().effect())) {
            return false;
        }
        entry.effects.put(snapshot.spec().effect(), snapshot);
        save();
        return true;
    }

    // ------------------------------------------------------------------ 还原

    /**
     * ★★★ <b>把这个玩家欠的东西全部还掉</b>（若他欠着的话）。
     *
     * @return 真的还原了几项（0 = 他不欠 / 不在线 / 失败）
     */
    public int restoreIfPending(Player player, String reason) {
        if (player == null) {
            return 0;
        }
        UUID id = player.getUniqueId();
        Pending entry = pending.get(id);
        if (entry == null) {
            return 0;
        }
        if (entry.isEmpty()) {
            // ★ 只有"记账时先建了条目、但一项都没记成"才会到这（{@code note*} 中途失败那种）：
            //   直接扔掉，不要为"什么都没改过的玩家"打一行「已归还」的假日志。
            pending.remove(id);
            save();
            return 0;
        }
        int reverted = 0;
        int keptEffects = 0;
        int removedEffects = 0;
        int reappliedEffects = 0;

        // ① 无敌：只把"我们改上去的那一位"改回来
        if (entry.invulnerableBefore != null) {
            try {
                boolean wasBefore = entry.invulnerableBefore;
                boolean now = player.isInvulnerable();
                if (io.mczju.infernalclash.policy.PlayerTargetBan.shouldRestore(wasBefore, now)) {
                    player.setInvulnerable(false);
                    reverted++;
                }
            } catch (Throwable t) {
                warn("[IC] 还原「玩家无敌」失败（" + player.getName() + "）：" + t
                        + " —— 账本里仍留着他，下次上线会再试一次");
            }
        }

        // ② 创造飞行：原来不能飞的，收回来（并落地，避免"空中悬停"）
        if (entry.allowFlightBefore != null) {
            try {
                if (!entry.allowFlightBefore && player.getAllowFlight()) {
                    player.setAllowFlight(false);
                    if (player.isFlying()) {
                        player.setFlying(false);
                    }
                    reverted++;
                }
            } catch (Throwable t) {
                warn("[IC] 还原「创造飞行」失败（" + player.getName() + "）：" + t);
            }
        }

        // ③ 常驻效果：本局之前没有的删掉、原来是有限时长的放回、本来就永久的不动
        for (PlayerBuffs.Snapshot snapshot : entry.effects.values()) {
            PotionEffectType type = resolve(snapshot.spec().effect());
            if (type == null) {
                continue;
            }
            try {
                switch (PlayerBuffs.restoreAction(snapshot)) {
                    case REMOVE -> {
                        player.removePotionEffect(type);
                        removedEffects++;
                    }
                    case REAPPLY -> {
                        player.addPotionEffect(new PotionEffect(type, snapshot.duration(),
                                snapshot.amplifier(), false, PlayerBuffs.SHOW_PARTICLES, true), true);
                        reappliedEffects++;
                    }
                    case KEEP -> keptEffects++;
                }
            } catch (Throwable t) {
                warn("[IC] 还原常驻效果 " + snapshot.spec().effect() + " 失败（"
                        + player.getName() + "）：" + t);
            }
        }

        // ④ 经验条：还原成进局前的值（旧实现是"归零"，那等于**抹掉**玩家的等级，见 docs/05 E20）
        if (entry.levelBefore != null) {
            try {
                player.setLevel(Math.max(0, entry.levelBefore));
                player.setExp(Math.max(0.0f, Math.min(1.0f, entry.expBefore == null
                        ? 0.0f : entry.expBefore)));
                player.setTotalExperience(Math.max(0, entry.totalXpBefore == null
                        ? 0 : entry.totalXpBefore));
                reverted++;
            } catch (Throwable t) {
                warn("[IC] 还原经验条失败（" + player.getName() + "）：" + t);
            }
        }

        pending.remove(id);
        save();
        info("[IC] 已归还玩家临时状态（" + reason + "）：" + player.getName()
                + "｜本局账目 " + entry.describe()
                + "｜无敌/飞行/经验条共还原 " + reverted + " 项"
                + "｜效果：移除 " + removedEffects + " / 放回原样 " + reappliedEffects
                + " / 原本永久保持 " + keptEffects
                + "｜剩余待还原 " + pending.size() + " 名");
        return reverted;
    }

    /**
     * ★ <b>把所有**在线**玩家的账还掉</b>；离线的**留在账本里**（下次上线补）。
     *
     * <p>★ 这就是"与在线解耦"的落点：{@code onStop} / {@code onDisable} 都调它，
     * 拿不到人就只留账，绝不"清空内存当还过了"（那就是本 bug 的根因）。
     *
     * @return 真的处理了几名玩家
     */
    public int restoreAllOnline(String reason) {
        if (pending.isEmpty()) {
            return 0;
        }
        int handled = 0;
        // ★ 先快照 key（restoreIfPending 会改 pending）
        List<UUID> ids = new ArrayList<>(pending.keySet());
        for (UUID id : ids) {
            Player player = playerLookup.apply(id);
            if (player == null) {
                continue;
            }
            restoreIfPending(player, reason);
            handled++;
        }
        int left = pending.size();
        if (left > 0) {
            warn("[IC] 还有 " + left + " 名玩家的临时状态**未能还原**（此刻不在线）：" + describe()
                    + " —— 已写入 " + FILE + "，他们下次上线 / 插件下次 onEnable 时会对账还原"
                    + "（★ 这是 2026-10-26「玩家无敌外泄」事故的防复发机制）");
        }
        return handled;
    }

    // ------------------------------------------------------------------ 查询

    /** 还欠着多少名玩家（日志 / 探针用）。 */
    public int pendingCount() {
        return pending.size();
    }

    /** 还欠哪些玩家（只读快照）。 */
    public List<UUID> pendingIds() {
        return List.copyOf(pending.keySet());
    }

    /** 这个玩家欠着吗（测试 / 探针用）。 */
    public boolean hasPending(UUID id) {
        return id != null && pending.containsKey(id);
    }

    /** 一行摘要（日志用）。 */
    public String describe() {
        if (pending.isEmpty()) {
            return "（无）";
        }
        List<String> bits = new ArrayList<>();
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            bits.add(entry.getKey() + "=" + entry.getValue().describe());
        }
        return String.join("｜", bits);
    }

    // ------------------------------------------------------------------ 落盘

    /** 从 {@link #file} 读入账本（文件不存在 = 空账，正常）。 */
    public void load() {
        pending.clear();
        if (file == null || !file.isFile()) {
            return;
        }
        YamlConfiguration yaml;
        try {
            yaml = io.mczju.infernalclash.config.YamlFile.read(FILE, file);
        } catch (Throwable t) {
            warn("[IC] 待还原账本读取失败（" + t + "）⇒ 本次按空账处理。"
                    + "★ 若刚经历过一次「玩家无敌外泄」，请检查 " + file.getAbsolutePath());
            return;
        }
        ConfigurationSection root = yaml.getConfigurationSection(SECTION);
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException bad) {
                warn("[IC] 待还原账本里有一把不是 UUID 的键，已跳过：" + key);
                continue;
            }
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            Pending entry = new Pending();
            entry.matchId = s.getString("match");
            entry.savedAt = s.getLong("saved-at");
            if (s.isSet("invulnerable-before")) {
                entry.invulnerableBefore = s.getBoolean("invulnerable-before");
            }
            if (s.isSet("allow-flight-before")) {
                entry.allowFlightBefore = s.getBoolean("allow-flight-before");
            }
            if (s.isSet("level-before")) {
                entry.levelBefore = s.getInt("level-before");
                entry.expBefore = (float) s.getDouble("exp-before", 0.0);
                entry.totalXpBefore = s.getInt("total-xp-before", 0);
            }
            ConfigurationSection effects = s.getConfigurationSection("effects");
            if (effects != null) {
                for (String name : effects.getKeys(false)) {
                    ConfigurationSection e = effects.getConfigurationSection(name);
                    if (e == null) {
                        continue;
                    }
                    int ourAmplifier = e.getInt("amplifier", 0);
                    entry.effects.put(name, new PlayerBuffs.Snapshot(
                            new PlayerBuffs.Spec(name, Math.max(0, ourAmplifier)),
                            e.getBoolean("present", false),
                            e.getBoolean("infinite", false),
                            e.getInt("duration", 0),
                            e.getInt("before-amplifier", 0)));
                }
            }
            if (!entry.isEmpty()) {
                pending.put(id, entry);
            }
        }
        if (!pending.isEmpty()) {
            warn("[IC] 载入了 " + pending.size() + " 名玩家的**待还原**临时状态（" + FILE + "）："
                    + describe() + " —— 他们上线时会被立刻还原");
        }
    }

    /** 写回 {@link #file}（没有文件句柄 ⇒ 只留在内存；失败只告警）。 */
    public void save() {
        if (file == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", VERSION);
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            Pending p = entry.getValue();
            if (p.isEmpty()) {
                continue;   // ★ 不把"一个字都没改过"的空壳写进文件
            }
            String base = SECTION + "." + entry.getKey();
            if (p.matchId != null) {
                yaml.set(base + ".match", p.matchId);
            }
            yaml.set(base + ".saved-at", p.savedAt);
            if (p.invulnerableBefore != null) {
                yaml.set(base + ".invulnerable-before", p.invulnerableBefore);
            }
            if (p.allowFlightBefore != null) {
                yaml.set(base + ".allow-flight-before", p.allowFlightBefore);
            }
            if (p.levelBefore != null) {
                yaml.set(base + ".level-before", p.levelBefore);
                yaml.set(base + ".exp-before", p.expBefore);
                yaml.set(base + ".total-xp-before", p.totalXpBefore);
            }
            for (Map.Entry<String, PlayerBuffs.Snapshot> effect : p.effects.entrySet()) {
                String path = base + ".effects." + effect.getKey();
                PlayerBuffs.Snapshot snapshot = effect.getValue();
                yaml.set(path + ".amplifier", snapshot.spec().amplifier());
                yaml.set(path + ".present", snapshot.present());
                yaml.set(path + ".infinite", snapshot.infinite());
                yaml.set(path + ".duration", snapshot.duration());
                yaml.set(path + ".before-amplifier", snapshot.amplifier());
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                warn("[IC] 待还原账本的目录建不出来：" + parent.getAbsolutePath());
                return;
            }
            yaml.save(file);
        } catch (IOException | RuntimeException e) {
            warn("[IC] 待还原账本写入失败（" + file.getAbsolutePath() + "）：" + e
                    + " —— 内存里的账仍然有效，但**重启会丢**；请检查磁盘权限");
        }
    }

    /** 清空（只给测试用；正常路径永远靠"还完就删那一条"）。 */
    public void clearForTest() {
        pending.clear();
    }

    // ------------------------------------------------------------------ 内部

    /** 取/建这个玩家的账（顺带把 matchId 与时间戳写进去）。 */
    private Pending entryFor(UUID id, String matchId) {
        if (id == null) {
            return null;
        }
        Pending entry = pending.get(id);
        if (entry == null) {
            entry = new Pending();
            entry.matchId = matchId;
            entry.savedAt = System.currentTimeMillis();
            pending.put(id, entry);
        }
        return entry;
    }

    /** 效果名 → 类型（与 {@code spell/EffectApplier} 同一口径）。 */
    private PotionEffectType resolve(String name) {
        try {
            return PotionEffectType.getByName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Bukkit 的玩家解析（拿不到 / 未初始化 ⇒ {@code null}，绝不抛）。 */
    private static Player onlinePlayer(UUID id) {
        if (id == null) {
            return null;
        }
        try {
            Player player = Bukkit.getPlayer(id);
            return player != null && player.isOnline() ? player : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void info(String message) {
        if (logger != null) {
            logger.info(message);
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
