package plmux;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaveformSamplerTest {

    private static Model.Waveform waveform(String style, int stride, byte[] detail) {
        return new Model.Waveform("k", style, 1000, null,
                new Model.WaveDetail(detail.length / stride, stride, WaveformEncoder.b64(detail)),
                new int[0], new int[0], List.of(), List.of());
    }

    /** 1 秒 (150 区間) の間、同じ値が続く詳細波形。 */
    private static byte[] constant(int... frame) {
        byte[] out = new byte[150 * frame.length];
        for (int f = 0; f < 150; f++) {
            for (int i = 0; i < frame.length; i++) out[f * frame.length + i] = (byte) frame[i];
        }
        return out;
    }

    @Test
    void averagesRgbFrames() {
        WaveformSampler s = WaveformSampler.of(waveform("RGB", 4, constant(31, 255, 0, 51)));
        WaveformSampler.Sample sample = s.sample(500);
        assertNotNull(sample);
        assertArrayEquals(new float[]{1f, 0f, 0.2f}, sample.rgb(), 1e-6f);
        assertEquals(1f, sample.level(), 1e-6f);
    }

    @Test
    void mapsThreeBandToRedGreenBlue() {
        // 低域が最大なので赤が 1、中域は半分、高域はなし。大きさは最大の帯域 (15.5 / 31)
        WaveformSampler s = WaveformSampler.of(waveform("THREE_BAND", 3, constant(16, 8, 0)));
        WaveformSampler.Sample sample = s.sample(100);
        assertArrayEquals(new float[]{1f, 0.5f, 0f}, sample.rgb(), 1e-6f);
        assertEquals(16 / 31f, sample.level(), 1e-6f);
    }

    @Test
    void silentThreeBandIsBlack() {
        WaveformSampler.Sample sample = WaveformSampler.of(waveform("THREE_BAND", 3, constant(0, 0, 0))).sample(100);
        assertArrayEquals(new float[]{0f, 0f, 0f}, sample.rgb());
        assertEquals(0f, sample.level());
    }

    @Test
    void blueHasNoColor() {
        WaveformSampler.Sample sample = WaveformSampler.of(waveform("BLUE", 4, constant(31, 0, 90, 120))).sample(100);
        assertNull(sample.rgb());
        assertEquals(1f, sample.level(), 1e-6f);
    }

    @Test
    void averagesAroundTheTime() {
        // 前半が無音、後半が最大。境目 (0.5 秒) では前後 8 区間ずつなので半分になる
        byte[] detail = new byte[150 * 4];
        for (int f = 75; f < 150; f++) detail[f * 4] = 31;
        WaveformSampler s = WaveformSampler.of(waveform("RGB", 4, detail));
        assertEquals(0.5f, s.sample(500).level(), 1e-6f);
        assertEquals(0f, s.sample(100).level());
        assertEquals(1f, s.sample(900).level());
    }

    @Test
    void outsideTheTrackIsNull() {
        WaveformSampler s = WaveformSampler.of(waveform("RGB", 4, constant(31, 0, 0, 0)));
        assertNull(s.sample(-1));
        assertNull(s.sample(1000));
        assertNotNull(s.sample(999));
    }

    @Test
    void needsDetail() {
        assertNull(WaveformSampler.of(null));
        assertNull(WaveformSampler.of(new Model.Waveform("k", "RGB", 0, null, null, new int[0], new int[0], List.of(), List.of())));
    }
}
