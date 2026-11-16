package plmux;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * UDP で OSC メッセージを送るだけの最小限のクライアント (dmxctl など照明ソフトへの送信用)。
 *
 * <p>引数の型タグは Beat Link Trigger が使う overtone/osc-clj に合わせる:
 * Integer → i, Long → h, Float → f, Double → d, String → s, Boolean → T/F, byte[] → b。
 * そのため BLT と同じく、小数を 32bit で送りたいときは呼び出し側で float にする。
 */
public final class OscClient implements Closeable {
    private final InetSocketAddress target;
    private final DatagramSocket socket;

    /** 送り先の名前解決はここで 1 回だけ行う。 */
    public OscClient(String host, int port) throws IOException {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
        this.target = new InetSocketAddress(InetAddress.getByName(host), port);
        this.socket = new DatagramSocket();
    }

    public InetSocketAddress target() { return target; }

    public void send(String address, Object... args) throws IOException {
        byte[] packet = encode(address, args);
        socket.send(new DatagramPacket(packet, packet.length, target));
    }

    /** 複数のメッセージを 1 つのバンドル (タイムタグ = すぐに実行) にまとめて送る。 */
    public void sendBundle(List<Message> messages) throws IOException {
        byte[] packet = encodeBundle(messages);
        socket.send(new DatagramPacket(packet, packet.length, target));
    }

    public boolean isClosed() { return socket.isClosed(); }

    @Override
    public void close() { socket.close(); }

    public record Message(String address, Object... args) {}

    // ---- エンコード ----

    static byte[] encode(String address, Object... args) {
        if (address == null || !address.startsWith("/")) {
            throw new IllegalArgumentException("OSC address must start with '/': " + address);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        putString(out, address);
        StringBuilder tags = new StringBuilder(",");
        for (Object a : args) tags.append(tag(a));
        putString(out, tags.toString());
        for (Object a : args) putValue(out, a);
        return out.toByteArray();
    }

    static byte[] encodeBundle(List<Message> messages) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        putString(out, "#bundle");
        out.writeBytes(ByteBuffer.allocate(8).putLong(1L).array());  // 1 = immediately
        for (Message m : messages) {
            byte[] el = encode(m.address(), m.args());
            out.writeBytes(ByteBuffer.allocate(4).putInt(el.length).array());
            out.writeBytes(el);
        }
        return out.toByteArray();
    }

    private static char tag(Object a) {
        return switch (a) {
            case Integer i -> 'i';
            case Long l -> 'h';
            case Float f -> 'f';
            case Double d -> 'd';
            case String s -> 's';
            case Boolean b -> b ? 'T' : 'F';
            case byte[] b -> 'b';
            case null -> throw new IllegalArgumentException("OSC arguments must not be null");
            // Clojure の比率 (1/2) や BigDecimal なども数値として送れるようにする
            case Number n -> 'd';
            default -> throw new IllegalArgumentException("Unsupported OSC argument type: " + a.getClass().getName());
        };
    }

    private static void putValue(ByteArrayOutputStream out, Object a) {
        switch (a) {
            case Integer i -> out.writeBytes(ByteBuffer.allocate(4).putInt(i).array());
            case Long l -> out.writeBytes(ByteBuffer.allocate(8).putLong(l).array());
            case Float f -> out.writeBytes(ByteBuffer.allocate(4).putFloat(f).array());
            case Double d -> out.writeBytes(ByteBuffer.allocate(8).putDouble(d).array());
            case String s -> putString(out, s);
            case Boolean b -> { }
            case byte[] b -> {
                out.writeBytes(ByteBuffer.allocate(4).putInt(b.length).array());
                out.writeBytes(b);
                pad(out);
            }
            case Number n -> out.writeBytes(ByteBuffer.allocate(8).putDouble(n.doubleValue()).array());
            default -> throw new IllegalStateException();
        }
    }

    private static void putString(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        pad(out);
    }

    private static void pad(ByteArrayOutputStream out) {
        while (out.size() % 4 != 0) out.write(0);
    }
}
