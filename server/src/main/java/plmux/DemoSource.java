package plmux;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * GUI 確認用のダミーデータ源 (PLMUX_DEMO=true)。DJ Link には一切接続しない。
 *
 * <p>XDJ-AZ の 4 デッキを模して、1 サイクル {@value #CYCLE_MS} ms で次のミックスを繰り返す:
 * 次のデッキを再生 → On-Air → マスター交代 → 前のデッキを Off-Air → 停止 → 次の曲をロード。
 * 曲履歴の記録 (HistoryService) も実機と同じ経路で動く。
 */
public final class DemoSource implements PlayerSource {

    private static final long CYCLE_MS = 64_000;
    private static final long TICK_MS = 20;
    private static final String MODEL = "XDJ-AZ";
    private static final String ADDRESS = "169.254.10.1";

    /** 架空の曲リスト。 */
    private record DemoTrack(String title, String artist, String album, String genre, String key, double bpm, int durationSec) {}

    private static final List<DemoTrack> TRACKS = List.of(
            new DemoTrack("Midnight Circuit", "Kaito Sora", "Night Drive EP", "Techno", "8A", 124.0, 372),
            new DemoTrack("Neon Rain", "Lumen Fields", "Neon Rain", "Deep House", "5A", 122.0, 401),
            new DemoTrack("Subway Pulse", "Undergroove", "Transit", "Tech House", "11B", 126.0, 344),
            new DemoTrack("Afterglow Theory", "Mira Hoshino", "Afterglow", "Melodic Techno", "2A", 125.0, 455),
            new DemoTrack("Concrete Bloom", "Tokyo Static", "Concrete Bloom", "Techno", "9A", 128.0, 389),
            new DemoTrack("夜明けのシグナル", "Asagiri", "Signals", "Progressive House", "4B", 124.0, 418),
            new DemoTrack("Hydraulic", "Machine Poets", "Hydraulic EP", "Hard Techno", "7A", 132.0, 330),
            new DemoTrack("Velvet Static", "Lumen Fields", "Velvet", "Deep House", "10A", 121.0, 397),
            new DemoTrack("Orbit Loop", "Kaito Sora", "Orbit", "Minimal", "6A", 127.0, 362),
            new DemoTrack("Glass Horizon", "Nuance Lab", "Glass Horizon", "Trance", "1B", 134.0, 433));

    private static final class Deck {
        final int number;
        int trackIndex;
        boolean loaded = true;
        long metadataReadyAt = 0;   // ロード直後は「メタデータ取得中」を再現する
        boolean playing, onAir, master, synced;
        double positionMs;
        int beat;                   // 曲頭からの拍番号 (1 始まり)

        Deck(int number, int trackIndex) {
            this.number = number;
            this.trackIndex = trackIndex;
        }

        DemoTrack track() { return TRACKS.get(trackIndex); }
    }

    private final Deck[] decks = new Deck[4];
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<BeatEvent>> beatListeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, byte[]> artCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "demo-ticker");
        t.setDaemon(true);
        return t;
    });

    private int nextTrack = 4;
    private int current = 0;        // いま主に鳴っているデッキ (index)
    private long cycleStart;
    private long lastTick;
    private double masterBpm;
    private double beatPhaseMs = 0; // マスターの拍クロック
    private int masterBeatWithinBar = 1;
    private final boolean[] done = new boolean[8];

    public DemoSource() {
        for (int i = 0; i < 4; i++) decks[i] = new Deck(i + 1, i);
        Deck a = decks[0];
        a.playing = a.onAir = a.master = true;
        a.positionMs = 75_000;
        a.beat = (int) (a.positionMs / (60_000 / a.track().bpm())) + 1;
        masterBpm = a.track().bpm();
    }

    @Override
    public void start() {
        cycleStart = lastTick = System.currentTimeMillis();
        ticker.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        ticker.shutdownNow();
    }

    @Override
    public void onChange(Runnable listener) { changeListeners.add(listener); }

    @Override
    public void onBeat(Consumer<BeatEvent> listener) { beatListeners.add(listener); }

    private synchronized void tick() {
        try {
            long now = System.currentTimeMillis();
            double dt = now - lastTick;
            lastTick = now;
            runScenario(now - cycleStart, now);

            Deck master = decks[current];
            for (Deck d : decks) if (d.master) master = d;
            masterBpm = master.track().bpm();

            // 再生位置を進める (同期中のデッキはマスターのテンポで再生している)
            for (Deck d : decks) {
                if (d.playing) d.positionMs += dt * (masterBpm / d.track().bpm());
                if (d.positionMs > d.track().durationSec() * 1000.0) d.positionMs = d.track().durationSec() * 1000.0;
            }

            // マスターの拍クロック
            beatPhaseMs += dt;
            double interval = 60_000.0 / masterBpm;
            if (beatPhaseMs >= interval) {
                beatPhaseMs -= interval;
                masterBeatWithinBar = masterBeatWithinBar % 4 + 1;
                for (Deck d : decks) {
                    if (!d.playing) continue;
                    d.beat++;
                    BeatEvent e = new BeatEvent(d.number, masterBeatWithinBar, masterBpm, d.master);
                    for (Consumer<BeatEvent> l : beatListeners) l.accept(e);
                }
            }
            for (Runnable r : changeListeners) r.run();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DemoSource.class).warn("demo tick failed", e);
        }
    }

    /** 1 サイクル内のイベントを時刻に応じて進める。 */
    private void runScenario(long t, long now) {
        Deck out = decks[current];
        Deck in = decks[(current + 1) % 4];
        if (t >= 20_000 && once(0)) {           // 次のデッキを再生開始 (SYNC)
            in.playing = true;
            in.synced = true;
            in.beat = 1;
        }
        if (t >= 28_000 && once(1)) in.onAir = true;      // フェーダーを上げる
        if (t >= 40_000 && once(2)) {           // マスター交代
            out.master = false;
            in.master = true;
        }
        if (t >= 48_000 && once(3)) out.onAir = false;    // 前のデッキのフェーダーを下げる
        if (t >= 52_000 && once(4)) {           // 前のデッキを停止
            out.playing = false;
            out.synced = false;
        }
        if (t >= 56_000 && once(5)) {           // 前のデッキに次の曲をロード
            out.trackIndex = nextTrack;
            nextTrack = (nextTrack + 1) % TRACKS.size();
            out.positionMs = 0;
            out.beat = 1;
            out.loaded = true;
            out.metadataReadyAt = now + 1500;
        }
        if (t >= CYCLE_MS) {                    // 次のサイクルへ
            current = (current + 1) % 4;
            cycleStart = now;
            java.util.Arrays.fill(done, false);
        }
    }

    private boolean once(int step) {
        if (done[step]) return false;
        done[step] = true;
        return true;
    }

    @Override
    public synchronized Model.State snapshot(Model.LinkStatus link) {
        List<Model.Player> players = new ArrayList<>();
        Integer masterPlayer = null;
        for (Deck d : decks) {
            DemoTrack t = d.track();
            boolean metadataReady = d.loaded && System.currentTimeMillis() >= d.metadataReadyAt;
            Model.Track track = metadataReady
                    ? new Model.Track(1000 + d.trackIndex, t.title(), t.artist(), t.album(), t.genre(), t.key(),
                            t.durationSec(), t.bpm(), d.number, d.number % 2 == 1 ? "USB 1" : "USB 2",
                            "demo-" + d.number + "-" + d.trackIndex, null)
                    : null;
            double bpm = d.synced || d.master ? masterBpm : t.bpm();
            if (d.master) masterPlayer = d.number;
            players.add(new Model.Player(
                    d.number, MODEL, ADDRESS, d.playing, d.onAir, d.master, d.synced,
                    BeatLinkService.round2(bpm),
                    BeatLinkService.round2((bpm / t.bpm() - 1.0) * 100.0),
                    d.beat, d.playing ? masterBeatWithinBar : 0,
                    (long) d.positionMs, d.loaded, track));
        }
        return new Model.State(true, true, masterPlayer, BeatLinkService.round2(masterBpm), players,
                List.of(), List.of(), link);
    }

    @Override
    public byte[] artwork(int player) {
        if (player < 1 || player > 4) return null;
        int trackIndex;
        synchronized (this) {
            trackIndex = decks[player - 1].trackIndex;
        }
        return artCache.computeIfAbsent(trackIndex, DemoSource::renderArt);
    }

    @Override
    public Model.Waveform waveform(int player) {
        return null;
    }

    /** 曲ごとに色の違う抽象的なジャケット画像を生成する。 */
    private static byte[] renderArt(int trackIndex) {
        int size = 240;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float hue = (trackIndex * 0.137f) % 1f;
        g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, 0.7f, 0.9f),
                size, size, Color.getHSBColor((hue + 0.25f) % 1f, 0.8f, 0.35f)));
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(255, 255, 255, 70));
        g.setStroke(new BasicStroke(6));
        for (int i = 0; i < 4; i++) {
            int r = 40 + i * 36 + (trackIndex % 3) * 8;
            g.drawOval(size / 2 - r / 2, size / 2 - r / 2, r, r);
        }
        g.setColor(new Color(0, 0, 0, 90));
        g.fillOval(size / 2 - 14, size / 2 - 14, 28, 28);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
