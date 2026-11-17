package plmux;

import org.deepsymmetry.libcarabiner.Runner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * 同梱の Carabiner を動かし、ほかのホストからの接続を中継する ({@code java -jar plmux-server.jar carabiner})。
 *
 * <p>Carabiner は 127.0.0.1 でしか待ち受けないので、別のホスト (Kubernetes では別の Pod) の plmux からはつなげない。
 * そこで Carabiner を内部のポート ({@code PLMUX_CARABINER_INTERNAL_PORT}、既定 17001) で動かし、
 * 全てのアドレスの {@code PLMUX_CARABINER_PORT} (既定 17000) で受けた接続をそこへ中継する。
 *
 * <p>Ableton Link は、Carabiner が動いているホストの全てのネットワークで広報される。
 * Link を広報するネットワーク (例: VJ 用の VLAN) だけにつないだ Pod でこのモードを動かし、
 * plmux 本体は {@code PLMUX_CARABINER_HOST} でそこへつなぐと、Link をそのネットワークだけに出せる。
 *
 * <p>Carabiner のプロセスが止まっていたら (中継先につながらなければ) 起動し直す。
 * 中継の接続は Carabiner の制御 (テンポの変更など) ができるので、Kubernetes では NetworkPolicy で plmux からだけに絞る。
 */
public final class CarabinerRelay {
    private static final Logger log = LoggerFactory.getLogger(CarabinerRelay.class);

    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final long CHECK_INTERVAL_MS = 5000;
    /** 続けてこの回数つながらなければ、Carabiner を起動し直す。 */
    private static final int MAX_FAILURES = 3;

    private final int listenPort;
    private final int upstreamPort;
    private volatile ServerSocket server;

    CarabinerRelay(int listenPort, int upstreamPort) {
        this.listenPort = listenPort;
        this.upstreamPort = upstreamPort;
    }

    /** {@code carabiner} の引数で起動したとき。戻らない。 */
    static void main() throws Exception {
        int listenPort = Config.intEnv("PLMUX_CARABINER_PORT", 17000);
        int internalPort = Config.intEnv("PLMUX_CARABINER_INTERNAL_PORT", 17001);
        if (listenPort == internalPort) {
            throw new IllegalArgumentException("PLMUX_CARABINER_PORT and PLMUX_CARABINER_INTERNAL_PORT must differ");
        }
        Runner runner = Runner.getInstance();
        if (!runner.canRunCarabiner()) {
            throw new IllegalStateException("The bundled Carabiner cannot run on this platform");
        }
        runner.setPort(internalPort);
        CarabinerRelay relay = new CarabinerRelay(listenPort, internalPort);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            relay.close();
            runner.stop();
        }, "shutdown"));

        log.info("Starting Carabiner ({}) on 127.0.0.1:{}", runner.getExecutableName(), internalPort);
        runner.start();
        relay.start();
        Thread.ofVirtual().name("carabiner-supervisor").start(() -> relay.supervise(runner));
        relay.serve();
    }

    /** 中継の待ち受けを始める。 */
    void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(listenPort));
        server = s;
        log.info("Relaying connections on port {} to Carabiner on 127.0.0.1:{}", s.getLocalPort(), upstreamPort);
    }

    int localPort() {
        return server.getLocalPort();
    }

    /** 接続を受け付け続ける。{@link #close()} されたら戻る。 */
    void serve() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().name("carabiner-relay").start(() -> relay(client));
            } catch (IOException e) {
                if (!server.isClosed()) log.warn("Unable to accept a connection", e);
            }
        }
    }

    void close() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void relay(Socket client) {
        String from = client.getRemoteSocketAddress().toString();
        try (client; Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress("127.0.0.1", upstreamPort), CONNECT_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            log.info("Relaying {} to Carabiner", from);
            Thread back = Thread.ofVirtual().start(() -> pipe(upstream, client));
            pipe(client, upstream);
            back.join();
        } catch (IOException e) {
            log.warn("Unable to relay {} to Carabiner: {}", from, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Connection from {} closed", from);
    }

    /** 片方向にコピーする。どちらかが閉じたら、両方を閉じてもう片方向も止める。 */
    private static void pipe(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) >= 0; ) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (SocketException ignored) {
            // 相手が閉じた
        } catch (IOException e) {
            log.debug("Relay stopped", e);
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /** Carabiner につながるか確かめ続け、止まっていたら起動し直す。 */
    private void supervise(Runner runner) {
        int failures = 0;
        while (!server.isClosed()) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (reachable()) {
                failures = 0;
                continue;
            }
            if (++failures < MAX_FAILURES) continue;
            log.warn("Carabiner is not responding on 127.0.0.1:{}; restarting it", upstreamPort);
            try {
                runner.stop();
                runner.start();
            } catch (IOException e) {
                log.warn("Unable to restart Carabiner", e);
            }
            failures = 0;
        }
    }

    private boolean reachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", upstreamPort), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
