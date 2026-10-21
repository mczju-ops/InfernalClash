package io.mczju.infernalclash.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 读取 YAML 文件的**唯一入口** —— 离线安全，且解析失败时报错可读。
 *
 * <h2>★ 为什么不用 {@code YamlConfiguration.loadConfiguration(File)}</h2>
 * 2026-10-15 实测（P1 卡牌加载）：**当文件解析失败时**，它会走
 * {@code Bukkit.getLogger().log(...)} 那条路 —— 离线单测里 {@code Bukkit.server == null}
 * ⇒ 抛的是
 * {@code NullPointerException: Cannot invoke "org.bukkit.Server.getLogger()" because "org.bukkit.Bukkit.server" is null}，
 * **把真正的解析错误完全盖掉**（现场表现为「某个 YAML 坏了，但报的是 NPE」）。
 *
 * <p>{@code AGENTS.md} §4.1 原本只记了 {@code loadConfiguration(Reader)} 的同类问题；
 * 本类把整条「读文件 → 解析」的路都换成**自己读文本 + {@code loadFromString}**，
 * 于是一次性消掉两个坑：
 * <ol>
 *   <li>不再依赖任何会碰服务端的 API；</li>
 *   <li>解析失败时给出**文件 + 原始解析器消息**的 {@link ConfigError}。</li>
 * </ol>
 *
 * <h2>★ 顺带吃掉 BOM</h2>
 * 带 UTF-8 BOM 的文件按 UTF-8 读出来，首字符是 {@code \uFEFF} ⇒ 首行的键会变成
 * {@code \uFEFFconfig-version}，表现为「第一行配置神秘失效」（{@code AGENTS.md} §4.2）。
 * 出厂文件已有测试禁止 BOM，但**现场文件是服主编辑的**，这里直接吃掉更稳妥。
 */
public final class YamlFile {

    private YamlFile() {
    }

    /** 读一个 YAML 文件（{@code fileName} 只用于报错信息）。 */
    public static YamlConfiguration read(String fileName, File file) {
        if (file == null || !file.isFile()) {
            throw new ConfigError(fileName, "<root>",
                    "文件不存在：" + (file == null ? "null" : file.getAbsolutePath()));
        }
        String text;
        try {
            text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigError(fileName, "<root>", "读取失败：" + e.getMessage());
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new ConfigError(fileName, "<root>", "YAML 解析失败：" + e.getMessage());
        }
        return yaml;
    }
}
