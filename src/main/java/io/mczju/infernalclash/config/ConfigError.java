package io.mczju.infernalclash.config;

/**
 * 配置错误。**必须**携带「文件 + 键路径」。
 *
 * <h2>为什么是 RuntimeException</h2>
 * 加载期校验失败要能一路冒到 {@code onEnable} 的 catch 里，
 * 而中间那些 {@code load(...)} 方法不该被 {@code throws} 污染签名
 * （与 MCZJUGuess 的 {@code ConfigError} 同一设计）。
 *
 * <h2>为什么要带键路径</h2>
 * Maggoteers 的已知缺陷是「缺键静默读成 0.0」—— 错误被掩盖，表现为
 * 「配置写了却没生效」，运维只能靠猜。带上文件与键路径，报错就能直接定位。
 */
public final class ConfigError extends RuntimeException {

    private final String file;
    private final String keyPath;

    public ConfigError(String file, String keyPath, String message) {
        super(message);
        this.file = file;
        this.keyPath = keyPath;
    }

    /** 出错的配置文件名（例：{@code config.yml}）。 */
    public String file() {
        return file;
    }

    /** 出错的键路径（例：{@code game.min-players}）。 */
    public String keyPath() {
        return keyPath;
    }

    /** 面向运维的一行描述。 */
    public String describe() {
        return "[" + file + "] " + keyPath + " —— " + getMessage();
    }
}
