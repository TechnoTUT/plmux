package plmux;

import clojure.java.api.Clojure;
import clojure.lang.IDeref;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionServiceTest {

    /** 状態と波形をテストから決められる PlayerSource。拍は 500ms ごと、64 拍ごとに intro → chorus。 */
    static final class FakeSource implements PlayerSource {
        final List<Consumer<StatusEvent>> statusListeners = new CopyOnWriteArrayList<>();
        final List<Consumer<BeatEvent>> beatListeners = new CopyOnWriteArrayList<>();
        final Model.Waveform waveform;

        FakeSource() {
            int[] beats = new int[128];
            for (int i = 0; i < beats.length; i++) beats[i] = i * 500;
            byte[] detail = new byte[150 * 4];  // 最初の 1 秒は赤で最大
            for (int f = 0; f < 150; f++) {
                detail[f * 4] = 31;
                detail[f * 4 + 1] = (byte) 255;
            }
            waveform = new Model.Waveform("wf", "RGB", 64_000, null,
                    new Model.WaveDetail(150, 4, WaveformEncoder.b64(detail)), beats, new int[beats.length], List.of(),
                    List.of(new Model.Phrase(1, 65, "intro", "Intro 1", "HIGH", "#c80000"),
                            new Model.Phrase(65, 129, "chorus", "Chorus 1", "HIGH", "#0faa00")));
        }

        void status(int player) {
            for (Consumer<StatusEvent> l : statusListeners) l.accept(new StatusEvent(player, null));
        }

        void beat(int player, int beatWithinBar, boolean master) {
            for (Consumer<BeatEvent> l : beatListeners) l.accept(new BeatEvent(player, beatWithinBar, 128, master, null));
        }

        @Override public void start() { }
        @Override public void stop() { }
        @Override public void onChange(Runnable listener) { }
        @Override public void onBeat(Consumer<BeatEvent> listener) { beatListeners.add(listener); }
        @Override public void onStatus(Consumer<StatusEvent> listener) { statusListeners.add(listener); }
        @Override public Model.State snapshot(Model.LinkStatus link) { return null; }
        @Override public byte[] artwork(int player) { return null; }
        @Override public void setWaveformStyle(String style) { }
        @Override public Model.Waveform waveform(int player) { return player == 1 ? waveform : null; }
    }

    private static final Model.Track TRACK =
            new Model.Track(42, "Title", "Artist", null, null, "8A", 64, 128, 1, "USB 1", "a", "wf");

    static Model.State state(boolean online, boolean playing, long positionMs) {
        Model.Player p = new Model.Player(1, "XDJ-AZ", "x", playing, false, false, true, false, 128.0, 6.4,
                3, 2, positionMs, true, TRACK);
        return new Model.State(false, online, 1, 128, List.of(p), List.of(), List.of(), null);
    }

    @TempDir
    Path dir;
    private Path file;
    private FakeSource source;
    private volatile Model.State current;
    private ExpressionService service;

    @BeforeEach
    void setUp() {
        file = dir.resolve("expressions.clj");
        source = new FakeSource();
        current = state(false, false, 0);
        service = new ExpressionService(file, 50, () -> current, source);
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    private static void eventually(BooleanSupplier condition, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) fail("Timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    /** plmux.user/globals の中身。 */
    private static Map<?, ?> globals() {
        Object v = ((IDeref) Clojure.var("plmux.user", "globals")).deref();
        return v instanceof IDeref d ? (Map<?, ?>) d.deref() : Map.of();
    }

    private static Object global(String key) {
        return globals().get(clojure.lang.Keyword.intern(key));
    }

    private ExpressionService.HookStatus hook(String name) {
        return service.status().hooks().stream().filter(h -> h.name().equals(name)).findFirst().orElseThrow();
    }

    private void write(String source) throws Exception {
        Files.writeString(file, source);
    }

    private void startAndAwaitLoad() throws InterruptedException {
        service.start();
        eventually(() -> service.status().loadedAt() != null || service.status().error() != null, "load");
    }

    @Test
    void runsSetupHooksAndOnlineTransitions() throws Exception {
        write("""
                (global-setup (swap! globals assoc :log [:global-setup]))
                (setup (swap! globals update :log conj :setup))
                (came-online (swap! globals update :log conj :came-online))
                (going-offline (swap! globals update :log conj :going-offline))
                """);
        startAndAwaitLoad();
        assertNull(service.status().error());
        eventually(() -> List.of("global-setup", "setup").equals(names(global("log"))), "setup");

        current = state(true, false, 0);
        eventually(() -> names(global("log")).contains("came-online"), "came-online");
        current = state(false, false, 0);
        eventually(() -> names(global("log")).contains("going-offline"), "going-offline");
        assertEquals(1, hook("came-online").runs());
        assertTrue(hook("global-setup").defined());
        assertFalse(hook("beat").defined());
    }

    private static List<String> names(Object log) {
        if (!(log instanceof List<?> l)) return List.of();
        return l.stream().map(k -> ((clojure.lang.Keyword) k).getName()).toList();
    }

    @Test
    void bindsBltStyleValuesInTrackedUpdateAndBeat() throws Exception {
        write("""
                (tracked-update
                  (swap! globals assoc :update [device-number effective-tempo pitch-multiplier beat-number
                                                beat-within-bar track-time-reached track-title track-artist
                                                rekordbox-id playing? tempo-master? on-air? status]))
                (beat (swap! globals assoc :beat [beat-within-bar effective-tempo]))
                """);
        current = state(true, true, 1500);
        startAndAwaitLoad();

        // テンポマスターではないデッキは無視する。XDJ-AZ のように続けて届いても、マスターの状態は捨てない
        source.status(1);
        source.status(2);
        eventually(() -> global("update") != null, "tracked-update");
        assertEquals(List.of(1L, 128.0, 1.064, 3L, 2L, 1500L, "Title", "Artist", 42L, true, true),
                ((List<?>) global("update")).subList(0, 11));
        assertNull(((List<?>) global("update")).get(11));  // XDJ-AZ は On-Air を報告しない
        assertNull(((List<?>) global("update")).get(12));  // デモと同じく status はない

        source.beat(1, 4, false);
        source.beat(1, 3, true);
        eventually(() -> global("beat") != null, "beat");
        assertEquals(List.of(3L, 128.0), global("beat"));
        assertEquals(1, hook("beat").runs());
    }

    @Test
    void runsPhraseHooksWhenThePhraseChanges() throws Exception {
        write("""
                (phrase-started (swap! globals update :log (fnil conj []) [:started phrase-kind phrase-label]))
                (phrase-ended (swap! globals update :log (fnil conj []) [:ended phrase-kind]))
                """);
        current = state(true, true, 10_000);   // 21 拍目 = intro
        startAndAwaitLoad();
        source.status(1);
        eventually(() -> global("log") != null, "phrase-started");

        source.status(1);  // 同じフレーズのままなら何もしない
        // 状態は追いつかないと古いものが捨てられるので、1 つずつ処理されるのを待つ
        eventually(() -> hook("phrase-started").runs() == 1 && !service.status().hooks().isEmpty(), "same phrase");
        current = state(true, true, 32_000);   // 65 拍目 = chorus
        source.status(1);
        eventually(() -> ((List<?>) global("log")).size() == 3, "chorus");
        current = state(true, false, 32_000);  // 止めたら終わる
        source.status(1);
        eventually(() -> ((List<?>) global("log")).size() == 4, "phrase changes");
        assertEquals("[[:started \"intro\" \"Intro 1\"] [:ended \"intro\"] [:started \"chorus\" \"Chorus 1\"] [:ended \"chorus\"]]",
                clojure.lang.RT.printString(global("log")));
    }

    @Test
    void sendsOscWithWaveformSamples() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            receiver.setSoTimeout(5000);
            write("""
                    (global-setup (swap! globals assoc :dmx (osc/osc-client "127.0.0.1" %d)))
                    (tracked-update
                      (let [lead 0.3 light 0.05
                            s (waveform-sample device-number (+ track-time-reached (* lead 1000 pitch-multiplier)))]
                        (osc/osc-send (:dmx @globals) "/dmx/main/rgb/at"
                                      (float (:r s)) (float (:g s)) (float (:b s)) (float (- lead light)))))
                    (global-shutdown (osc/osc-close (:dmx @globals)))
                    """.formatted(receiver.getLocalPort()));
            current = state(true, true, 100);
            startAndAwaitLoad();
            source.status(1);

            DatagramPacket packet = new DatagramPacket(new byte[512], 512);
            receiver.receive(packet);
            ByteBuffer b = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
            assertEquals("/dmx/main/rgb/at", OscClientTest.readString(b));
            assertEquals(",ffff", OscClientTest.readString(b));
            assertEquals(1f, b.getFloat());
            assertEquals(0f, b.getFloat());
            assertEquals(0f, b.getFloat());
            assertEquals(0.25f, b.getFloat(), 1e-6f);
        }
    }

    @Test
    void reportsErrorsWithLineNumbers() throws Exception {
        write("""
                (global-setup (swap! globals assoc :ok true))

                (tracked-update (/ 1 0))
                """);
        current = state(true, true, 0);
        startAndAwaitLoad();
        source.status(1);
        eventually(() -> hook("tracked-update").errors() == 1, "hook error");
        assertTrue(hook("tracked-update").lastError().contains("Divide by zero"), hook("tracked-update").lastError());
        assertTrue(hook("tracked-update").lastError().contains("(line 3)"), hook("tracked-update").lastError());
        assertEquals(true, global("ok"));
    }

    @Test
    void reportsLoadErrorsAndReloadsWhenTheFileChanges() throws Exception {
        write("(global-setup (swap! globals assoc :v 1))\n(global-shutdown (spit \"%s\" \"bye\"))\n"
                .formatted(dir.resolve("shutdown.txt").toString().replace("\\", "\\\\")));
        startAndAwaitLoad();
        eventually(() -> Long.valueOf(1).equals(global("v")), "first load");

        write("(global-setup (swap! globals assoc :v 2))\n(no-such-fn)\n");
        eventually(() -> service.status().error() != null, "load error");
        assertTrue(service.status().error().contains("no-such-fn"), service.status().error());
        assertTrue(service.status().error().contains("expressions.clj"), service.status().error());
        assertEquals("bye", Files.readString(dir.resolve("shutdown.txt")));
        assertFalse(hook("global-setup").defined());

        write("(global-setup (swap! globals assoc :v 3))\n");
        eventually(() -> Long.valueOf(3).equals(global("v")), "reload");
        assertNull(service.status().error());

        Files.delete(file);
        eventually(() -> !service.status().exists(), "file removed");
        eventually(() -> !hook("global-setup").defined(), "unloaded");
    }

    @Test
    void rejectsDuplicateHooks() throws Exception {
        write("(beat 1)\n(beat 2)\n");
        startAndAwaitLoad();
        assertTrue(service.status().error().contains("more than once"), service.status().error());
    }

    @Test
    void runsEveryAtTheGivenInterval() throws Exception {
        write("(every 20 (swap! globals update :n (fnil inc 0)))\n");
        current = state(true, true, 0);
        startAndAwaitLoad();
        eventually(() -> global("n") instanceof Long n && n >= 5, "every");
        assertEquals(20, hook("every").intervalMs());
    }

    @Test
    void worksWithoutAFile() throws Exception {
        service.start();
        eventually(() -> service.status().path() != null, "status");
        Thread.sleep(150);
        assertFalse(service.status().exists());
        assertNull(service.status().error());
        assertNull(service.status().source());
    }
}
