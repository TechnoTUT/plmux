package plmux;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CarabinerRelayTest {

    private ServerSocket upstream;
    private CarabinerRelay relay;

    @AfterEach
    void tearDown() throws Exception {
        if (relay != null) relay.close();
        if (upstream != null) upstream.close();
    }

    /** Carabiner の代わりに、受け取った行を "echo " を付けて返すサーバー。 */
    private void startEchoUpstream() throws Exception {
        upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!upstream.isClosed()) {
                try {
                    Socket s = upstream.accept();
                    Thread.ofVirtual().start(() -> {
                        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                             OutputStream out = s.getOutputStream()) {
                            for (String line; (line = in.readLine()) != null; ) {
                                out.write(("echo " + line + "\n").getBytes(StandardCharsets.UTF_8));
                            }
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void startRelay() throws Exception {
        relay = new CarabinerRelay(0, upstream.getLocalPort());
        relay.start();
        Thread.ofVirtual().start(relay::serve);
    }

    @Test
    void relaysBothWays() throws Exception {
        startEchoUpstream();
        startRelay();
        for (int i = 0; i < 2; i++) {  // 接続ごとに別の中継になる
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.localPort()), 2000);
                client.setSoTimeout(2000);
                client.getOutputStream().write("status\nbpm 128\n".getBytes(StandardCharsets.UTF_8));
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("echo status", in.readLine());
                assertEquals("echo bpm 128", in.readLine());
            }
        }
    }

    @Test
    void closesTheClientWhenCarabinerIsDown() throws Exception {
        // 中継先が待ち受けていない
        try (ServerSocket unused = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            int port = upstream.getLocalPort();
            upstream.close();
            relay = new CarabinerRelay(0, port);
        }
        relay.start();
        Thread.ofVirtual().start(relay::serve);
        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.localPort()), 2000);
            client.setSoTimeout(3000);
            assertEquals(-1, client.getInputStream().read());
        }
    }

    @Test
    void embedsCarabinerOnlyForLoopbackHosts() {
        assertTrue(LinkService.isLoopbackHost("127.0.0.1"));
        assertTrue(LinkService.isLoopbackHost("localhost"));
        assertTrue(LinkService.isLoopbackHost("::1"));
        assertFalse(LinkService.isLoopbackHost("10.10.16.5"));
        assertFalse(LinkService.isLoopbackHost("carabiner.plmux.svc.cluster.local"));
        assertFalse(LinkService.isLoopbackHost("cafe"));  // 16 進数だけの名前でも名前解決しない
    }
}
