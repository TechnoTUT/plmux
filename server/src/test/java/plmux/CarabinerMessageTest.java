package plmux;

import org.junit.jupiter.api.Test;
import plmux.LinkService.CarabinerMessage;

import static org.junit.jupiter.api.Assertions.*;

class CarabinerMessageTest {

    @Test
    void parsesStatus() {
        StringBuilder buf = new StringBuilder("status { :peers 2 :bpm 128.000000 :start 73743731220 :beat 597.737570 }\n");
        CarabinerMessage m = CarabinerMessage.next(buf);
        assertNotNull(m);
        assertEquals("status", m.command());
        assertEquals(2, m.number("peers", -1));
        assertEquals(128.0, m.number("bpm", -1));
        assertEquals(597.73757, m.number("beat", -1), 1e-9);
        assertNull(CarabinerMessage.next(buf));
    }

    @Test
    void handlesConcatenatedAndSplitMessages() {
        StringBuilder buf = new StringBuilder("version \"1.2.0\"\nbeat-at-time { :when 100 :quantum 4.0 :be");
        CarabinerMessage v = CarabinerMessage.next(buf);
        assertEquals("version", v.command());
        assertEquals("\"1.2.0\"", v.body());
        assertNull(CarabinerMessage.next(buf), "incomplete message must wait for more data");
        buf.append("at 3.98 }\n");
        CarabinerMessage b = CarabinerMessage.next(buf);
        assertEquals("beat-at-time", b.command());
        assertEquals(100, (long) b.number("when", -1));
        assertEquals(3.98, b.number("beat", -1), 1e-9);
    }
}
