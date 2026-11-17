package plmux;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import clojure.lang.Var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Beat Link Trigger のトリガーの式のように、ユーザーが Clojure で書いた式を動かす。
 *
 * <p>式はファイル ({@code PLMUX_EXPRESSIONS_FILE}、既定はデータディレクトリの {@code expressions.clj}) にだけ書ける。
 * Web GUI には認証がないので、GUI からは内容と実行状況を見られるだけにしている。
 * ファイルは {@value #POLL_MS}ms ごとに確かめ、内容が変わったら読み込み直す (Kubernetes の ConfigMap の更新にも追従する)。
 *
 * <p>式の書き方とフックは {@code plmux/expressions.clj} を参照。フックはすべて 1 本のスレッドで順番に実行する。
 * Clojure の実行環境は、式のファイルが見つかったときに初めて読み込む (使わなければ起動が遅くならない)。
 */
public final class ExpressionService {
    private static final Logger log = LoggerFactory.getLogger(ExpressionService.class);

    static final long POLL_MS = 2000;
    private static final long ONLINE_CHECK_MS = 500;
    static final int MIN_INTERVAL_MS = 20;
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    /** 拍のパケットと再生位置のずれを吸収する (拍の直前の位置を前の拍と数えないように)。 */
    private static final double BEAT_TOLERANCE_MS = 20;

    /** フックの実行状況。 */
    public record HookStatus(String name, boolean defined, Integer intervalMs, long runs, long errors,
                             String lastError, Long lastErrorAt) {}

    /** GUI に出す状態。 */
    public record Status(String path, boolean exists, String source, Long loadedAt, String error,
                         List<HookStatus> hooks) {}

    private final Path file;
    private final long pollMs;
    private final Supplier<Model.State> state;
    private final PlayerSource source;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "expressions");
        t.setDaemon(true);
        return t;
    });

    // ---- executor スレッドだけが触る ----
    private IFn loadFn;
    private IFn unloadFn;
    private Map<String, Map<?, ?>> hooks = Map.of();
    private ScheduledFuture<?> everyTask;
    private boolean online;
    private PhraseState phrase;
    private final Map<Integer, WaveformSampler> samplers = new HashMap<>();

    // ---- GUI 向け ----
    private volatile String loadedSource;
    private volatile String currentSource;
    private volatile boolean exists;
    private volatile Long loadedAt;
    private volatile String loadError;
    private final Map<String, Stats> stats = new ConcurrentHashMap<>();

    /**
     * tracked-update は、実行が追いつかないときに古い状態を捨てる。XDJ-AZ のように 1 台が複数デッキの状態を続けて送るので、
     * プレイヤーごとに最新の 1 件を残す (マスターの状態がほかのデッキの状態で上書きされないように)。
     */
    private final Map<Integer, PlayerSource.StatusEvent> pendingStatus = new ConcurrentHashMap<>();
    private final AtomicBoolean statusQueued = new AtomicBoolean();

    private static final class Stats {
        volatile long runs, errors;
        volatile String lastError;
        volatile Long lastErrorAt;
    }

    /** 今いるフレーズ。曲かフレーズが変わったら ended → started を実行する。 */
    private record PhraseState(int player, String track, Model.Phrase phrase, Map<Object, Object> context) {
        boolean same(PhraseState o) {
            return o != null && player == o.player && track.equals(o.track) && phrase.equals(o.phrase);
        }
    }

    public ExpressionService(Path file, Supplier<Model.State> state, PlayerSource source) {
        this(file, POLL_MS, state, source);
    }

    /** テスト用: ファイルを確かめる間隔を変えられる。 */
    ExpressionService(Path file, long pollMs, Supplier<Model.State> state, PlayerSource source) {
        this.file = file;
        this.pollMs = pollMs;
        this.state = state;
        this.source = source;
    }

    public void start() {
        source.onStatus(this::onStatus);
        source.onBeat(this::onBeat);
        executor.scheduleWithFixedDelay(() -> run(this::checkFile), 0, pollMs, TimeUnit.MILLISECONDS);
        long onlineMs = Math.min(ONLINE_CHECK_MS, pollMs);
        executor.scheduleWithFixedDelay(() -> run(this::checkOnline), onlineMs, onlineMs, TimeUnit.MILLISECONDS);
        log.info("Clojure expressions file: {}", file.toAbsolutePath());
    }

    public void stop() {
        try {
            executor.submit(() -> run(this::unload)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Unable to run global-shutdown", e);
        }
        executor.shutdownNow();
    }

    public Status status() {
        List<HookStatus> list = new ArrayList<>();
        Map<String, Map<?, ?>> h = hooks;
        for (String name : HOOK_NAMES) {
            Map<?, ?> def = h.get(name);
            Stats s = stats.get(name);
            Object interval = def != null ? def.get(kw("interval-ms")) : null;
            list.add(new HookStatus(name, def != null,
                    interval instanceof Number n ? Math.max(MIN_INTERVAL_MS, n.intValue()) : null,
                    s != null ? s.runs : 0, s != null ? s.errors : 0,
                    s != null ? s.lastError : null, s != null ? s.lastErrorAt : null));
        }
        return new Status(file.toAbsolutePath().toString(), exists, currentSource, loadedAt, loadError, list);
    }

    /** plmux/expressions.clj の hook-names と同じ順番。 */
    static final List<String> HOOK_NAMES = List.of("global-setup", "setup", "came-online", "tracked-update", "every",
            "beat", "phrase-started", "phrase-ended", "going-offline", "shutdown", "global-shutdown");

    // ---- ファイルの読み込み ----

    private void checkFile() {
        String text;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                exists = true;
                fail("The expressions file is larger than 1 MiB");
                return;
            }
            text = Files.readString(file);
            exists = true;
        } catch (NoSuchFileException e) {
            text = null;
            exists = false;
        } catch (IOException e) {
            exists = Files.exists(file);
            fail("Unable to read " + file + ": " + e);
            return;
        }
        currentSource = text;
        if (Objects.equals(text, loadedSource)) return;
        unload();
        loadedSource = text;
        if (text == null) {
            loadError = null;
            loadedAt = null;
            log.info("Clojure expressions file removed: {}", file);
            return;
        }
        load(text);
    }

    private void load(String text) {
        stats.clear();
        try {
            if (loadFn == null) initClojure();
            Object result = loadFn.invoke(text, file.toAbsolutePath().toString());
            Map<String, Map<?, ?>> defined = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) result).entrySet()) {
                defined.put(((Keyword) e.getKey()).getName(), (Map<?, ?>) e.getValue());
            }
            hooks = defined;
            loadError = null;
            loadedAt = System.currentTimeMillis();
            log.info("Loaded Clojure expressions from {}: {}", file, defined.keySet());
        } catch (Throwable t) {
            hooks = Map.of();
            loadedAt = null;
            fail(describe(t));
            return;
        }
        invoke("global-setup", new HashMap<>());
        invoke("setup", new HashMap<>());
        Map<?, ?> every = hooks.get("every");
        if (every != null) {
            Object interval = every.get(kw("interval-ms"));
            long ms = interval instanceof Number n ? Math.max(MIN_INTERVAL_MS, n.longValue()) : 1000;
            everyTask = executor.scheduleAtFixedRate(() -> run(this::runEvery), ms, ms, TimeUnit.MILLISECONDS);
        }
        // つながっていれば、次の確認で came-online を実行する
        online = false;
        phrase = null;
    }

    private void unload() {
        if (everyTask != null) {
            everyTask.cancel(false);
            everyTask = null;
        }
        if (hooks.isEmpty()) return;
        if (phrase != null) invoke("phrase-ended", phrase.context());
        phrase = null;
        if (online) invoke("going-offline", new HashMap<>());
        online = false;
        invoke("shutdown", new HashMap<>());
        invoke("global-shutdown", new HashMap<>());
        hooks = Map.of();
        samplers.clear();
        try {
            unloadFn.invoke();
        } catch (Throwable t) {
            log.debug("Unable to remove the expressions namespace", t);
        }
    }

    private void initClojure() {
        long started = System.currentTimeMillis();
        // Clojure は自分を読み込んだクラスローダーから .clj を探すので、スレッドのものを揃えておく
        Thread.currentThread().setContextClassLoader(ExpressionService.class.getClassLoader());
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("plmux.expressions"));
        ((Var) Clojure.var("plmux.expressions", "service")).bindRoot(this);
        loadFn = Clojure.var("plmux.expressions", "load-expressions");
        unloadFn = Clojure.var("plmux.expressions", "unload-expressions");
        log.info("Clojure runtime ready in {} ms", System.currentTimeMillis() - started);
    }

    private void fail(String message) {
        if (!message.equals(loadError)) log.warn("Clojure expressions: {}", message);
        loadError = message;
    }

    // ---- イベント ----

    private void onStatus(PlayerSource.StatusEvent e) {
        pendingStatus.put(e.player(), e);
        if (!statusQueued.getAndSet(true)) {
            executor.execute(() -> run(() -> {
                statusQueued.set(false);
                for (Integer player : List.copyOf(pendingStatus.keySet())) {
                    PlayerSource.StatusEvent latest = pendingStatus.remove(player);
                    if (latest != null) runTrackedUpdate(latest);
                }
            }));
        }
    }

    private void onBeat(PlayerSource.BeatEvent e) {
        if (!e.master()) return;
        executor.execute(() -> run(() -> {
            if (hooks.isEmpty()) return;
            Model.Player p = masterPlayer();
            if (p == null || p.number() != e.player()) return;
            Map<Object, Object> ctx = context(p, e.raw());
            // 拍のパケットのほうが正確なので、ここで拍の中の位置とテンポを上書きする
            if (e.beatWithinBar() > 0) ctx.put(kw("beat-within-bar"), (long) e.beatWithinBar());
            ctx.put(kw("effective-tempo"), e.bpm());
            invoke("beat", ctx);
            checkPhrase(p);
        }));
    }

    private void runTrackedUpdate(PlayerSource.StatusEvent e) {
        if (hooks.isEmpty()) return;
        Model.Player p = masterPlayer();
        if (p == null || p.number() != e.player()) return;
        invoke("tracked-update", context(p, e.raw()));
        checkPhrase(p);
    }

    private void runEvery() {
        if (hooks.isEmpty()) return;
        Model.Player p = masterPlayer();
        if (p == null) return;
        invoke("every", context(p, null));
        checkPhrase(p);
    }

    private void checkOnline() {
        if (hooks.isEmpty()) return;
        Model.State st = state.get();
        boolean now = st != null && st.djLinkOnline();
        if (now == online) return;
        online = now;
        invoke(now ? "came-online" : "going-offline", new HashMap<>());
        if (!now) checkPhrase(null);
    }

    /** テンポマスターのデッキのフレーズが変わったら、phrase-ended と phrase-started を実行する。 */
    private void checkPhrase(Model.Player p) {
        if (!hooks.containsKey("phrase-started") && !hooks.containsKey("phrase-ended")) return;
        PhraseState next = null;
        if (p != null && p.playing() && p.track() != null) {
            Model.Waveform w = source.waveform(p.number());
            if (w != null && !w.phrases().isEmpty()) {
                int beat = w.beatTimesMs().length > 0 ? w.beatAt(p.positionMs() + BEAT_TOLERANCE_MS) : p.beat();
                Model.Phrase ph = w.phraseAt(beat);
                if (ph != null) {
                    String track = p.track().sourcePlayer() + ":" + p.track().sourceSlot() + ":" + p.track().rekordboxId();
                    next = new PhraseState(p.number(), track, ph, null);
                }
            }
        }
        if (next != null && next.same(phrase)) return;
        if (next == null && phrase == null) return;
        if (phrase != null) invoke("phrase-ended", phrase.context());
        if (next != null) {
            Map<Object, Object> ctx = context(p, null);
            ctx.put(kw("phrase"), next.phrase());
            ctx.put(kw("phrase-kind"), next.phrase().kind());
            ctx.put(kw("phrase-label"), next.phrase().label());
            next = new PhraseState(next.player(), next.track(), next.phrase(), ctx);
            invoke("phrase-started", ctx);
        }
        phrase = next;
    }

    // ---- 実行 ----

    private void invoke(String hook, Map<Object, Object> context) {
        Map<?, ?> def = hooks.get(hook);
        if (def == null) return;
        Stats s = stats.computeIfAbsent(hook, k -> new Stats());
        try {
            ((IFn) def.get(kw("fn"))).invoke(PersistentHashMap.create(context));
            s.runs++;
        } catch (Throwable t) {
            String message = describe(t);
            if (!message.equals(s.lastError)) log.warn("Clojure expression {} failed: {}", hook, message, t);
            s.lastError = message;
            s.lastErrorAt = System.currentTimeMillis();
            s.runs++;
            s.errors++;
        }
    }

    /** executor のタスクで例外が漏れると、以後そのタスクが実行されなくなるので必ず捕まえる。 */
    private void run(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            log.warn("Clojure expressions failed", t);
        }
    }

    /**
     * 式の中で起きた例外なら、ファイルの何行目かを付ける。式のファイルを評価した関数は plmux.user 名前空間の
     * クラスになるので、そのスタックフレームだけを見る (同じ名前の plmux/expressions.clj の行と取り違えないように)。
     */
    private String describe(Throwable t) {
        Throwable cause = t;
        // CompilerException は「どこで」を持っているので、その下の原因と合わせて出す
        StringBuilder sb = new StringBuilder();
        String fileName = file.getFileName().toString();
        while (cause != null) {
            if (!sb.isEmpty()) sb.append(" ← ");
            sb.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) sb.append(": ").append(cause.getMessage());
            for (StackTraceElement el : cause.getStackTrace()) {
                if (el.getClassName().startsWith(USER_NS_CLASS_PREFIX) && fileName.equals(el.getFileName())
                        && el.getLineNumber() > 0) {
                    sb.append(" (line ").append(el.getLineNumber()).append(')');
                    break;
                }
            }
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return sb.toString();
    }

    private Map<Object, Object> context(Model.Player p, Object raw) {
        Map<Object, Object> m = new HashMap<>();
        m.put(kw("status"), raw);
        m.put(kw("player"), p);
        m.put(kw("device-number"), (long) p.number());
        m.put(kw("playing?"), p.playing());
        m.put(kw("tempo-master?"), p.master());
        m.put(kw("synced?"), p.synced());
        m.put(kw("on-air?"), p.onAirKnown() ? p.onAir() : null);
        m.put(kw("effective-tempo"), p.bpm());
        m.put(kw("pitch-multiplier"), 1.0 + p.pitchPercent() / 100.0);
        m.put(kw("beat-number"), (long) p.beat());
        m.put(kw("beat-within-bar"), p.beatWithinBar() > 0 ? (long) p.beatWithinBar() : null);
        m.put(kw("track-time-reached"), p.positionMs());
        Model.Track t = p.track();
        if (t != null) {
            m.put(kw("rekordbox-id"), (long) t.rekordboxId());
            m.put(kw("track-title"), t.title());
            m.put(kw("track-artist"), t.artist());
            m.put(kw("track-album"), t.album());
            m.put(kw("track-genre"), t.genre());
            m.put(kw("track-key"), t.key());
            m.put(kw("track-length"), (long) t.durationSec());
        }
        return m;
    }

    private static final String USER_NS_CLASS_PREFIX = "plmux.user$";

    private static final Map<String, Keyword> KEYWORDS = new ConcurrentHashMap<>();

    private static Keyword kw(String name) {
        return KEYWORDS.computeIfAbsent(name, Keyword::intern);
    }

    // ---- plmux.expressions の関数から呼ぶ (executor スレッド) ----

    public Model.State state() {
        return state.get();
    }

    public Model.Player masterPlayer() {
        Model.State st = state.get();
        if (st == null || st.masterPlayer() == null) return null;
        for (Model.Player p : st.players()) {
            if (p.number() == st.masterPlayer()) return p;
        }
        return null;
    }

    public Model.Waveform waveform(int player) {
        return source.waveform(player);
    }

    /** 色付き (RGB) の波形があればそれを、なければ表示用の波形を使う。 */
    public WaveformSampler.Sample sample(int player, double timeMs) {
        WaveformSampler color = source.colorSampler(player);
        if (color != null) return color.sample(timeMs);
        Model.Waveform w = source.waveform(player);
        if (w == null) return null;
        WaveformSampler s = samplers.get(player);
        if (s == null || !s.key().equals(w.key())) {
            s = WaveformSampler.of(w);
            if (s == null) return null;
            samplers.put(player, s);
        }
        return s.sample(timeMs);
    }

    /** 実機のときだけ、色付き (RGB) の詳細波形を beat-link の WaveformDetail で返す。 */
    public Object rgbWaveformDetail(int player) {
        return source instanceof BeatLinkService b ? b.rgbWaveformDetail(player) : null;
    }

    public int beatAt(int player, double timeMs) {
        Model.Waveform w = source.waveform(player);
        return w == null ? 0 : w.beatAt(timeMs);
    }

    public Model.Phrase phraseAt(int player, int beat) {
        Model.Waveform w = source.waveform(player);
        return w == null ? null : w.phraseAt(beat);
    }
}
