package plmux;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OscClientTest {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    void encodesAddressWithoutArguments() {
        // "/dmx/tap" は 8 文字なので、終端の 0 を足して 12 バイトに揃う。型タグは "," のみ
        byte[] p = OscClient.encode("/dmx/tap");
        assertArrayEquals(bytes('/', 'd', 'm', 'x', '/', 't', 'a', 'p', 0, 0, 0, 0, ',', 0, 0, 0), p);
    }

    @Test
    void encodesTypedArguments() {
        byte[] p = OscClient.encode("/a", 1, 2L, 0.5f, 0.25, "x", true, false);
        ByteBuffer b = ByteBuffer.wrap(p);
        assertEquals("/a", readString(b));
        assertEquals(",ihfdsTF", readString(b));
        assertEquals(1, b.getInt());
        assertEquals(2L, b.getLong());
        assertEquals(0.5f, b.getFloat());
        assertEquals(0.25, b.getDouble());
        assertEquals("x", readString(b));
        assertFalse(b.hasRemaining());
    }

    @Test
    void encodesOtherNumbersAsDouble() {
        ByteBuffer b = ByteBuffer.wrap(OscClient.encode("/a", new java.math.BigDecimal("1.5")));
        readString(b);
        assertEquals(",d", readString(b));
        assertEquals(1.5, b.getDouble());
    }

    @Test
    void encodesBlobWithPadding() {
        ByteBuffer b = ByteBuffer.wrap(OscClient.encode("/a", (Object) new byte[]{1, 2, 3}));
        readString(b);
        assertEquals(",b", readString(b));
        assertEquals(3, b.getInt());
        assertEquals(1, b.get());
        b.position(b.position() + 3);
        assertFalse(b.hasRemaining());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> OscClient.encode("dmx"));
        assertThrows(IllegalArgumentException.class, () -> OscClient.encode("/a", (Object) null));
        assertThrows(IllegalArgumentException.class, () -> OscClient.encode("/a", new Object()));
    }

    @Test
    void encodesBundle() {
        byte[] p = OscClient.encodeBundle(List.of(new OscClient.Message("/a", 1), new OscClient.Message("/b")));
        ByteBuffer b = ByteBuffer.wrap(p);
        assertEquals("#bundle", readString(b));
        assertEquals(1L, b.getLong());
        int len = b.getInt();
        assertArrayEquals(OscClient.encode("/a", 1), Arrays.copyOfRange(p, b.position(), b.position() + len));
        b.position(b.position() + len);
        len = b.getInt();
        assertArrayEquals(OscClient.encode("/b"), Arrays.copyOfRange(p, b.position(), b.position() + len));
    }

    @Test
    void sendsOverUdp() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             OscClient client = new OscClient("127.0.0.1", server.getLocalPort())) {
            server.setSoTimeout(2000);
            client.send("/dmx/level/at", 0.5f, 0.1f);
            DatagramPacket packet = new DatagramPacket(new byte[512], 512);
            server.receive(packet);
            assertArrayEquals(OscClient.encode("/dmx/level/at", 0.5f, 0.1f),
                    Arrays.copyOf(packet.getData(), packet.getLength()));
        }
    }

    static String readString(ByteBuffer b) {
        int start = b.position();
        while (b.get() != 0) { }
        String s = new String(b.array(), start, b.position() - start - 1);
        while (b.position() % 4 != 0) b.get();
        return s;
    }
}
