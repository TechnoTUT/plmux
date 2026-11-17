package plmux;

import org.deepsymmetry.beatlink.Beat;
import org.deepsymmetry.beatlink.DeviceUpdate;
import org.deepsymmetry.beatlink.MasterListener;
import org.deepsymmetry.beatlink.VirtualCdj;
import org.deepsymmetry.libcarabiner.Runner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carabiner 経由の Ableton Link 連携。beat-carabiner (Clojure) の jump-only 追従ロジックを Java に移植したもの。
 *
 * <ul>
 *   <li>{@link Mode#OFF}: 何もしない</li>
 *   <li>{@link Mode#MONITOR}: Carabiner に接続して Link セッションの BPM / ピア数を表示するだけ</li>
 *   <li>{@link Mode#FOLLOW}: Link のテンポと拍 (オプションで小節) を DJ Link のテンポマスターに追従させる</li>
 * </ul>
 *
 * <p>Carabiner がポートで待ち受けていなければ、lib-carabiner 同梱のバイナリを起動する
 * (Linux x64 / 32bit ARM のみ同梱。それ以外は Carabiner を別途起動すること)。
 * 接続先 ({@code PLMUX_CARABINER_HOST}) が自分以外のときは起動しない。別のホスト (Kubernetes では別の Pod) の
 * Carabiner は {@link CarabinerRelay} を通して使う (Carabiner 自身は 127.0.0.1 でしか待ち受けないため)。
 *
 * <p>時刻は Link と同じ単調時計 (System.nanoTime, Linux では CLOCK_MONOTONIC) のマイクロ秒で扱う。
 */
public final class LinkService {
    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    public enum Mode { OFF, MONITOR, FOLLOW }

    /** Link のテンポがこれ以上ずれていたら合わせ直す (beat-carabiner の bpm-tolerance)。 */
    private static final double BPM_TOLERANCE = 0.00001;
    /** 拍の位置がこの割合以上ずれていたら Link のタイムラインをジャンプさせる (beat-carabiner の skew-tolerance)。 */
    private static final double SKEW_TOLERANCE = 0.0166;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 2000;

    private final String host;
    private final int port;
    private final boolean allowEmbedded;
    private final VirtualCdj virtualCdj;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private volatile Mode mode;
    private volatile int latencyMs;
    private volatile boolean alignBars;

    private volatile Socket socket;
    private volatile boolean embedded = false;
    private volatile int peers = 0;
    private volatile double linkBpm = 0;
    private volatile Double targetBpm = null;
    private volatile String error = null;
    /** 直近の beat-at-time 問い合わせ: [時刻(µs), 小節内の拍(1-4, 不明なら 0)] */
    private volatile long[] pendingBeat = null;

    private final Thread worker;
    private volatile boolean running = true;

    private final MasterListener masterListener = new MasterListener() {
        @Override public void masterChanged(DeviceUpdate update) { }

        @Override public void tempoChanged(double tempo) {
            if (tempo >= 20.0 && tempo <= 999.0) {
                targetBpm = tempo;
                checkLinkTempo();
            } else {
                targetBpm = null;
            }
            fireChange();
        }

        @Override public void newBeat(Beat beat) {
            if (!beat.isTempoMaster()) return;
            long micros = TimeUnit.NANOSECONDS.toMicros(beat.getTimestamp()) - TimeUnit.MILLISECONDS.toMicros(latencyMs);
            int beatWithinBar = (alignBars && beat.isBeatWithinBarMeaningful()) ? beat.getBeatWithinBar() : 0;
            pendingBeat = new long[]{micros, beatWithinBar};
            send("beat-at-time " + micros + " 4.0");
        }
    };

    public LinkService(Config config, VirtualCdj virtualCdj) {
        this.host = config.carabinerHost();
        this.port = config.carabinerPort();
        // 同梱の Carabiner はこのホストでしか起動できない
        this.allowEmbedded = config.carabinerEmbedded() && isLoopbackHost(host);
        this.virtualCdj = virtualCdj;
        this.mode = config.linkMode();
        this.latencyMs = config.linkLatencyMs();
        this.alignBars = config.linkAlignBars();
        this.worker = new Thread(this::run, "carabiner-client");
        this.worker.setDaemon(true);
    }

    public void start() {
        worker.start();
    }

    public void onChange(Runnable r) { listeners.add(r); }

    private void fireChange() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception e) { log.warn("link listener failed", e); }
        }
    }

    public Model.LinkStatus status() {
        return new Model.LinkStatus(mode.name(), socket != null, embedded, peers,
                BeatLinkService.round2(linkBpm), mode == Mode.FOLLOW ? targetBpm : null, error);
    }

    public synchronized void setMode(Mode newMode) {
        if (newMode == mode) return;
        Mode old = mode;
        mode = newMode;
        log.info("Link mode: {} -> {}", old, newMode);
        if (old == Mode.FOLLOW) {
            virtualCdj.removeMasterListener(masterListener);
            targetBpm = null;
        }
        if (newMode == Mode.OFF) {
            disconnect();
        } else if (newMode == Mode.FOLLOW && socket != null) {
            tieToMaster();
        }
        worker.interrupt();  // 接続待ちのスリープを起こす
        fireChange();
    }

    public void setLatencyMs(int ms) { this.latencyMs = ms; fireChange(); }

    public void setAlignBars(boolean align) { this.alignBars = align; fireChange(); }

    public int latencyMs() { return latencyMs; }

    public boolean alignBars() { return alignBars; }

    private void tieToMaster() {
        virtualCdj.addMasterListener(masterListener);
        if (virtualCdj.isRunning()) {
            masterListener.tempoChanged(virtualCdj.getMasterTempo());
        }
    }

    // ---- 接続管理 ----

    private void run() {
        while (running) {
            try {
                if (mode == Mode.OFF) {
                    sleepQuietly(60_000);
                    continue;
                }
                if (socket == null) {
                    connect();
                }
                if (socket != null) {
                    readLoop(socket);
                }
            } catch (Exception e) {
                error = e.getMessage();
                log.warn("Carabiner connection problem: {}", e.toString());
                disconnect();
                fireChange();
            }
            if (running && mode != Mode.OFF) sleepQuietly(5_000);
        }
    }

    private void connect() throws IOException {
        Socket s;
        try {
            s = open();
        } catch (IOException first) {
            Runner runner = Runner.getInstance();
            if (!allowEmbedded || !runner.canRunCarabiner()) {
                throw new IOException("Carabiner is not running on " + host + ":" + port
                        + (allowEmbedded ? " (and the bundled binary cannot run on this platform)" : ""), first);
            }
            log.info("Starting embedded Carabiner ({}) on port {}", runner.getExecutableName(), port);
            runner.setPort(port);
            runner.start();
            embedded = true;
            s = null;
            for (int i = 0; i < 200 && s == null; i++) {
                try {
                    s = open();
                } catch (IOException e) {
                    sleepQuietly(10);
                }
            }
            if (s == null) throw new IOException("Unable to connect to the bundled Carabiner");
        }
        s.setSoTimeout(READ_TIMEOUT_MS);
        socket = s;
        error = null;
        log.info("Connected to Carabiner on {}:{}", host, port);
        send("version");
        send("status");
        if (mode == Mode.FOLLOW) tieToMaster();
        fireChange();
    }

    private Socket open() throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    /** localhost か、ループバックの IP アドレスか。名前解決はしない (DNS が引けないときに起動を待たせないため)。 */
    static boolean isLoopbackHost(String host) {
        if (host.equalsIgnoreCase("localhost")) return true;
        if (!host.matches("[0-9.]+") && !host.contains(":")) return false;  // IP アドレスの表記だけ
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    private synchronized void disconnect() {
        virtualCdj.removeMasterListener(masterListener);
        Socket s = socket;
        socket = null;
        peers = 0;
        linkBpm = 0;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
        if (embedded) {
            Runner.getInstance().stop();
            embedded = false;
        }
    }

    public void stop() {
        running = false;
        worker.interrupt();
        disconnect();
    }

    private void send(String message) {
        Socket s = socket;
        if (s == null) return;
        try {
            OutputStream out = s.getOutputStream();
            synchronized (this) {
                out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            log.warn("Unable to send to Carabiner: {}", e.toString());
        }
    }

    private void readLoop(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        byte[] buf = new byte[4096];
        StringBuilder pending = new StringBuilder();
        long lastStatusPoll = System.nanoTime();
        while (running && socket == s && mode != Mode.OFF) {
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                n = 0;
            }
            if (n < 0) throw new IOException("Carabiner closed the connection");
            if (n > 0) {
                pending.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                for (CarabinerMessage m; (m = CarabinerMessage.next(pending)) != null; ) {
                    handle(m);
                }
            }
            if (System.nanoTime() - lastStatusPoll > TimeUnit.SECONDS.toNanos(2)) {
                send("status");  // 生存確認を兼ねる
                lastStatusPoll = System.nanoTime();
            }
        }
    }

    // ---- メッセージ処理 ----

    private void handle(CarabinerMessage m) {
        switch (m.command()) {
            case "status" -> {
                linkBpm = m.number("bpm", linkBpm);
                peers = (int) m.number("peers", peers);
                checkLinkTempo();
                fireChange();
            }
            case "beat-at-time" -> handleBeatAtTime(m);
            case "version" -> log.info("Carabiner version {}", m.body());
            case "unsupported" -> log.warn("Carabiner does not support: {}", m.body());
            default -> log.debug("Ignoring Carabiner message: {} {}", m.command(), m.body());
        }
    }

    private void checkLinkTempo() {
        Double target = targetBpm;
        if (mode == Mode.FOLLOW && target != null && socket != null && Math.abs(linkBpm - target) > BPM_TOLERANCE) {
            send("bpm " + target);
        }
    }

    /** beat-carabiner の handle-beat-at-time (jump-only モード) の移植。 */
    private void handleBeatAtTime(CarabinerMessage m) {
        if (mode != Mode.FOLLOW) return;
        double beat = m.number("beat", Double.NaN);
        long when = (long) m.number("when", -1);
        if (Double.isNaN(beat)) return;

        long rawBeat = Math.round(beat);
        double rawSkew = beat - Math.floor(beat);
        double beatSkew = rawSkew > 0.5 ? rawSkew - 1.0 : rawSkew;

        long[] probe = pendingBeat;
        long candidate = rawBeat;
        if (probe != null && probe[0] == when && probe[1] > 0) {
            long barSkew = (probe[1] - 1) - Math.floorMod(rawBeat, 4);
            long adjustment = barSkew <= -2 ? barSkew + 4 : barSkew;
            candidate = rawBeat + adjustment;
        }
        long target = candidate < 0 ? candidate + 4 : candidate;
        boolean multiBeat = target != rawBeat;

        if (Math.abs(beatSkew) > SKEW_TOLERANCE || multiBeat) {
            log.debug("Link jump: raw={} target={} skew={}", rawBeat, target, String.format("%.4f", beatSkew));
            send("force-beat-at-time " + target + " " + when + " 4.0");
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // setMode() から起こされる
        }
    }

    /**
     * Carabiner の応答メッセージ。形式は EDN 風: {@code status { :peers 1 :bpm 120.0 :start 123 :beat 4.0 }}
     * や {@code version "1.1.6"}。TCP 上で複数メッセージが連結・分割されて届くことがある。
     */
    record CarabinerMessage(String command, String body, Map<String, String> fields) {
        private static final Pattern FIELD = Pattern.compile(":([a-z-]+)\\s+([^\\s}]+)");

        double number(String key, double def) {
            String v = fields.get(key);
            if (v == null) return def;
            try { return Double.parseDouble(v); } catch (NumberFormatException e) { return def; }
        }

        /** バッファの先頭から完全なメッセージを 1 つ取り出す。不完全なら null を返しバッファは残す。 */
        static CarabinerMessage next(StringBuilder buf) {
            int i = 0, len = buf.length();
            while (i < len && Character.isWhitespace(buf.charAt(i))) i++;
            if (i == len) { buf.setLength(0); return null; }
            int cmdStart = i;
            while (i < len && !Character.isWhitespace(buf.charAt(i))) i++;
            if (i == len) return null;
            String command = buf.substring(cmdStart, i);
            while (i < len && Character.isWhitespace(buf.charAt(i)) && buf.charAt(i) != '\n') i++;
            if (i == len) return null;
            int bodyStart = i;
            int end;
            char c = buf.charAt(i);
            if (c == '\n') {
                end = i;  // 引数なしのメッセージ
            } else if (c == '{') {
                end = buf.indexOf("}", i);
                if (end < 0) return null;
                end++;
            } else if (c == '"') {
                end = buf.indexOf("\"", i + 1);
                if (end < 0) return null;
                end++;
            } else {
                end = i;
                while (end < len && !Character.isWhitespace(buf.charAt(end))) end++;
                if (end == len) return null;
            }
            String body = buf.substring(bodyStart, end).trim();
            buf.delete(0, end);
            Map<String, String> fields = new HashMap<>();
            Matcher fm = FIELD.matcher(body);
            while (fm.find()) fields.put(fm.group(1), fm.group(2));
            return new CarabinerMessage(command, body, fields);
        }
    }
}
