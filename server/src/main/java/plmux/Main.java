package plmux;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * plmux サーバーのエントリポイント。ディスプレイなし (java.awt.headless=true) で動作し、
 * REST API と WebSocket で Web GUI (Vue) に状態を配信する。
 */
public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 状態のプッシュ間隔の下限。DeviceUpdate はプレイヤーごとに 200ms 間隔で届く。 */
    private static final long PUSH_INTERVAL_MS = 100;
    /** 変化がなくてもこの間隔で状態を送る。 */
    private static final long KEEPALIVE_MS = 2000;

    private final Config config;
    private final PlayerSource source;
    private final LinkService link;
    private final HistoryService history;
    private final SettingsStore settings;
    private final ExpressionService expressions;
    private static final List<String> WAVEFORM_STYLES = List.of("THREE_BAND", "RGB", "BLUE");
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "state-pusher");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastPush = 0;

    private Main(Config config) throws Exception {
        this.config = config;
        this.source = config.demo() ? new DemoSource() : new BeatLinkService(config);
        this.link = new LinkService(config, org.deepsymmetry.beatlink.VirtualCdj.getInstance());
        this.history = new HistoryService(config.dataDir(), config.historyMinPlaySeconds(),
                config.historyRequireOnAir());
        this.settings = new SettingsStore(config.dataDir());
        this.expressions = new ExpressionService(config.expressionsFile(), this::currentState, source);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        // Ableton Link を広報するネットワークにだけつないだ別のホスト (Pod) で、Carabiner だけを動かす
        if (args.length > 0 && args[0].equals("carabiner")) {
            CarabinerRelay.main();
            return;
        }
        Config config = Config.fromEnv();
        log.info("Starting plmux: {}", config);
        new Main(config).run();
    }

    private void run() throws Exception {
        String style = settings.get("waveformStyle", config.waveformStyle());
        if (!WAVEFORM_STYLES.contains(style)) {
            log.warn("Unknown waveform style {}, using THREE_BAND", style);
            style = "THREE_BAND";
        }
        source.setWaveformStyle(style);
        link.setMode(linkStartupMode());
        source.onChange(() -> dirty.set(true));
        link.onChange(() -> dirty.set(true));
        source.onBeat(beat -> broadcast("beat", beat));
        history.onNewEntry(entry -> broadcast("history", entry));

        Javalin app = createApp();
        app.start(config.httpPort());

        if (config.demo()) log.warn("DEMO MODE: not connecting to DJ Link, serving dummy data");
        source.start();
        link.start();
        expressions.start();
        pusher.scheduleAtFixedRate(this::pushState, PUSH_INTERVAL_MS, PUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            pusher.shutdownNow();
            expressions.stop();
            link.stop();
            source.stop();
            app.stop();
            history.close();
            settings.close();
        }, "shutdown"));
    }

    private Model.State currentState() {
        return source.snapshot(link.status());
    }

    private void pushState() {
        try {
            long now = System.currentTimeMillis();
            if (!dirty.getAndSet(false) && now - lastPush < KEEPALIVE_MS) return;
            lastPush = now;
            Model.State state = currentState();
            history.observe(state);
            broadcast("state", state);
        } catch (Exception e) {
            log.warn("Unable to push state", e);
        }
    }

    private void broadcast(String type, Object data) {
        if (clients.isEmpty()) return;
        String message;
        try {
            message = JSON.writeValueAsString(Map.of("type", type, "data", data));
        } catch (Exception e) {
            log.warn("Unable to serialize {}", type, e);
            return;
        }
        for (WsContext ctx : clients) {
            if (ctx.session.isOpen()) {
                try {
                    ctx.send(message);
                } catch (Exception e) {
                    clients.remove(ctx);
                }
            } else {
                clients.remove(ctx);
            }
        }
    }

    // ---- HTTP / WebSocket ----

    private Javalin createApp() {
        boolean hasWeb = Main.class.getResource("/public/index.html") != null;
        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jetty.modifyWebSocketServletFactory(f -> f.setIdleTimeout(Duration.ofMinutes(2)));
            if (hasWeb) {
                cfg.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = "/public";
                    sf.location = Location.CLASSPATH;
                });
                cfg.spaRoot.addFile("/", "/public/index.html", Location.CLASSPATH);
            } else {
                log.warn("Web GUI is not bundled (web/dist was missing at build time); API only.");
            }
        });

        app.get("/healthz", ctx -> ctx.result("ok"));

        // OBS のブラウザソース用。Vite で別エントリとしてビルドした overlay.html を返す
        // (spaRoot の "/" が先にマッチするので明示的なルートにしている)
        if (hasWeb) {
            app.get("/overlay", ctx -> ctx.contentType("text/html; charset=utf-8")
                    .result(Main.class.getResourceAsStream("/public/overlay.html")));
        }

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                clients.add(ctx);
                ctx.send(JSON.writeValueAsString(Map.of("type", "state", "data", currentState())));
            });
            ws.onClose(ctx -> clients.remove(ctx));
            ws.onError(ctx -> clients.remove(ctx));
        });

        app.get("/api/state", ctx -> ctx.json(currentState()));

        app.get("/api/players/{player}/artwork", this::artwork);
        app.get("/api/players/{player}/waveform", ctx -> {
            Model.Waveform w = source.waveform(Integer.parseInt(ctx.pathParam("player")));
            if (w == null) throw new NotFoundResponse();
            // URL に waveformKey を付けて呼ぶ前提なのでキャッシュさせてよい
            ctx.header("Cache-Control", ctx.queryParam("k") != null ? "public, max-age=86400" : "no-cache");
            ctx.json(w);
        });

        app.get("/api/link", ctx -> ctx.json(linkSettings()));
        app.put("/api/link", ctx -> {
            LinkUpdate u = ctx.bodyAsClass(LinkUpdate.class);
            if (u.latencyMs() != null) link.setLatencyMs(u.latencyMs());
            if (u.alignBars() != null) link.setAlignBars(u.alignBars());
            if (u.mode() != null) {
                try {
                    link.setMode(LinkService.Mode.valueOf(u.mode().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new BadRequestResponse("mode must be one of OFF, MONITOR, FOLLOW");
                }
            }
            ctx.json(linkSettings());
        });

        // 式はファイルでだけ編集できる (GUI には認証がないので、ここからは書き換えさせない)
        app.get("/api/expressions", ctx -> ctx.json(expressions.status()));

        app.get("/api/settings", ctx -> ctx.json(settingsBody()));
        app.put("/api/settings", ctx -> {
            SettingsUpdate u = ctx.bodyAsClass(SettingsUpdate.class);
            if (u.waveformStyle() != null) {
                String s = u.waveformStyle().toUpperCase();
                if (!WAVEFORM_STYLES.contains(s)) {
                    throw new BadRequestResponse("waveformStyle must be one of " + WAVEFORM_STYLES);
                }
                settings.put("waveformStyle", s);
                source.setWaveformStyle(s);
            }
            if (u.overlay() != null) OverlaySettings.save(settings, u.overlay());
            if (u.linkStartupMode() != null) {
                try {
                    LinkService.Mode m = LinkService.Mode.valueOf(u.linkStartupMode().toUpperCase());
                    settings.put("linkStartupMode", m.name());
                } catch (IllegalArgumentException e) {
                    throw new BadRequestResponse("linkStartupMode must be one of OFF, MONITOR, FOLLOW");
                }
            }
            Map<String, Object> body = settingsBody();
            // 表示中のオーバーレイにも即時反映させる
            broadcast("settings", body);
            ctx.json(body);
        });

        app.get("/api/sessions", ctx -> {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("current", history.currentSessionId());  // null 可
            body.put("sessions", history.sessions());
            ctx.json(body);
        });
        app.post("/api/sessions", ctx -> {
            SessionCreate body = ctx.bodyAsClass(SessionCreate.class);
            if (body.name() == null || body.name().isBlank()) throw new BadRequestResponse("name is required");
            ctx.status(HttpStatus.CREATED).json(history.startSession(body.name().trim()));
        });
        app.post("/api/sessions/end", ctx -> {
            history.endSession();
            ctx.status(HttpStatus.NO_CONTENT);
        });

        app.get("/api/history", ctx -> ctx.json(history.history(sessionParam(ctx), limitParam(ctx))));
        app.get("/api/history.csv", this::historyCsv);
        app.delete("/api/history/{id}", ctx -> {
            if (!history.deleteEntry(Long.parseLong(ctx.pathParam("id")))) throw new NotFoundResponse();
            ctx.status(HttpStatus.NO_CONTENT);
        });

        app.exception(NumberFormatException.class, (e, ctx) -> ctx.status(HttpStatus.BAD_REQUEST).result("bad number"));
        return app;
    }

    record LinkUpdate(String mode, Integer latencyMs, Boolean alignBars) {}

    record SessionCreate(String name) {}

    record SettingsUpdate(String waveformStyle, String linkStartupMode, OverlaySettings.Update overlay) {}

    private Map<String, Object> settingsBody() throws SQLException {
        return Map.of("waveformStyle", settings.get("waveformStyle", config.waveformStyle()),
                "waveformStyles", WAVEFORM_STYLES,
                "linkStartupMode", linkStartupMode().name(),
                "overlay", OverlaySettings.load(settings));
    }

    /** 起動時に適用する Link モード。GUI で保存した値があればそれを、なければ PLMUX_LINK_MODE を使う。 */
    private LinkService.Mode linkStartupMode() throws SQLException {
        String saved = settings.get("linkStartupMode", config.linkMode().name());
        try {
            return LinkService.Mode.valueOf(saved);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown saved Link startup mode {}, using {}", saved, config.linkMode());
            return config.linkMode();
        }
    }

    private Map<String, Object> linkSettings() {
        return Map.of("status", link.status(), "latencyMs", link.latencyMs(), "alignBars", link.alignBars());
    }

    private void artwork(Context ctx) {
        int player = Integer.parseInt(ctx.pathParam("player"));
        byte[] bytes = source.artwork(player);
        if (bytes == null) throw new NotFoundResponse();
        boolean png = bytes.length > 4 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P';
        ctx.contentType(png ? "image/png" : "image/jpeg");
        // URL に artKey を付けて呼ぶ前提なので、長めにキャッシュさせてよい
        ctx.header("Cache-Control", ctx.queryParam("k") != null ? "public, max-age=86400" : "no-cache");
        ctx.result(bytes);
    }

    private Long sessionParam(Context ctx) throws SQLException {
        String s = ctx.queryParam("session");
        if (s == null || s.isBlank() || s.equals("all")) return null;
        if (s.equals("current")) {
            Long id = history.currentSessionId();
            return id == null ? -1L : id;
        }
        return Long.parseLong(s);
    }

    private static int limitParam(Context ctx) {
        String s = ctx.queryParam("limit");
        int limit = s == null ? 500 : Integer.parseInt(s);
        return Math.max(1, Math.min(limit, 10_000));
    }

    private void historyCsv(Context ctx) throws SQLException {
        Long session = sessionParam(ctx);
        List<Model.HistoryEntry> entries = history.history(session, 10_000);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder("﻿");  // Excel で文字化けしないよう BOM を付ける
        sb.append("played_at,player,artist,title,album,genre,key,bpm,duration\n");
        for (int i = entries.size() - 1; i >= 0; i--) {  // 古い順
            Model.HistoryEntry e = entries.get(i);
            sb.append(fmt.format(Instant.ofEpochMilli(e.playedAt()))).append(',')
                    .append(e.player()).append(',')
                    .append(csv(e.artist())).append(',')
                    .append(csv(e.title())).append(',')
                    .append(csv(e.album())).append(',')
                    .append(csv(e.genre())).append(',')
                    .append(csv(e.key())).append(',')
                    .append(e.bpm()).append(',')
                    .append(String.format("%d:%02d", e.durationSec() / 60, e.durationSec() % 60)).append('\n');
        }
        String name = session == null ? "history-all" : "history-session-" + session;
        ctx.contentType("text/csv; charset=utf-8");
        ctx.header("Content-Disposition", "attachment; filename=\"" + name + ".csv\"");
        ctx.result(sb.toString());
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
