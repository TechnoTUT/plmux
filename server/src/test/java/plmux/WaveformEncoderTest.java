package plmux;

import org.deepsymmetry.beatlink.data.WaveformDetail;
import org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class WaveformEncoderTest {

    /**
     * PWV5 (色付きの詳細波形) は 1 区間 2 バイト。beat-link の WaveformDetail で読むと、
     * 上位から 3bit ずつが赤・青・緑、続く 5bit が高さになる。
     */
    @Test
    void decodesRawColorWaveformDetail() {
        int redAndGreen = (7 << 13) | (7 << 7) | (31 << 2);  // 赤と緑が最大、高さ 31
        int blueHalf = (3 << 10) | (10 << 2);
        ByteBuffer raw = ByteBuffer.allocate(4).putShort((short) redAndGreen).putShort((short) blueHalf).flip();
        WaveformDetail detail = new WaveformDetail(null, raw, WaveformStyle.RGB);

        assertEquals(4, WaveformEncoder.detailStride(detail));
        byte[] bytes = WaveformEncoder.detailBytes(detail);
        assertArrayEquals(new byte[]{31, (byte) 255, (byte) 255, 0, 10, 0, 0, (byte) (3 * 255 / 7)}, bytes);

        WaveformSampler sampler = WaveformSampler.of("rgb", "RGB", 4, bytes);
        assertNotNull(sampler);
        assertEquals("RGB", sampler.style());
        WaveformSampler.Sample s = sampler.sample(0);
        assertEquals((1f + 0f) / 2, s.rgb()[0], 1e-6f);
        assertEquals((31 + 10) / 2f / 31f, s.level(), 1e-6f);
    }

    @Test
    void emptySamplerIsNull() {
        assertNull(WaveformSampler.of("rgb", "RGB", 4, new byte[0]));
    }
}
