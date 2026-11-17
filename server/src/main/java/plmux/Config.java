package plmux;

import java.nio.file.Path;

/**
 * 環境変数から読み込むサーバー設定。k8s では ConfigMap / env で指定する想定。
 */
public record Config(
        int httpPort,
        Path dataDir,
        String deviceName,
        boolean useStandardPlayerNumber,
        int historyMinPlaySeconds,
        boolean historyRequireOnAir,
        String carabinerHost,
        int carabinerPort,
        boolean carabinerEmbedded,
        LinkService.Mode linkMode,
        int linkLatencyMs,
        boolean linkAlignBars,
        String waveformStyle,
        boolean demo) {

    public static Config fromEnv() {
        return new Config(
                intEnv("PLMUX_HTTP_PORT", 8080),
                Path.of(env("PLMUX_DATA_DIR", "./data")),
                env("PLMUX_DEVICE_NAME", "plmux"),
                boolEnv("PLMUX_USE_STANDARD_PLAYER_NUMBER", false),
                intEnv("PLMUX_HISTORY_MIN_PLAY_SECONDS", 15),
                boolEnv("PLMUX_HISTORY_REQUIRE_ON_AIR", true),
                env("PLMUX_CARABINER_HOST", "127.0.0.1"),
                intEnv("PLMUX_CARABINER_PORT", 17000),
                boolEnv("PLMUX_CARABINER_EMBEDDED", true),
                LinkService.Mode.valueOf(env("PLMUX_LINK_MODE", "OFF").toUpperCase()),
                intEnv("PLMUX_LINK_LATENCY_MS", 1),
                boolEnv("PLMUX_LINK_ALIGN_BARS", true),
                env("PLMUX_WAVEFORM_STYLE", "THREE_BAND").toUpperCase(),
                boolEnv("PLMUX_DEMO", false));
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    static int intEnv(String key, int def) {
        return Integer.parseInt(env(key, Integer.toString(def)));
    }

    private static boolean boolEnv(String key, boolean def) {
        return Boolean.parseBoolean(env(key, Boolean.toString(def)));
    }
}
