package plmux;

import java.util.Base64;

/**
 * {@link Model.Waveform} の詳細波形から、曲中のある時刻の「色」と「大きさ」を取り出す (照明の連動用)。
 *
 * <p>Beat Link Trigger で dmxctl に送る式 (dmxctl の README) と同じく、その時刻の前後の区間を平均する。
 * 実機とデモのどちらの波形も同じ形式なので、ここで扱いを揃えられる。
 * <ul>
 *   <li>RGB: 区間の色の平均と、高さ (0〜31) の平均</li>
 *   <li>THREE_BAND: 低域=赤・中域=緑・高域=青として色を作る (いちばん強い帯域が 1 になるよう正規化)。
 *       大きさは帯域ごとの最大値の平均</li>
 *   <li>BLUE: 色には意味がないので大きさだけ</li>
 * </ul>
 */
public final class WaveformSampler {
    /** 詳細波形の 1 秒あたりの区間数 (half frame)。 */
    static final int FRAMES_PER_SECOND = 150;
    /** 前後に平均する区間数。8 区間 ≒ 53ms なので、合わせて約 0.1 秒になる。 */
    static final int WINDOW = 8;

    /**
     * @param rgb   0〜1 の RGB。色を出せない波形 (BLUE) なら null
     * @param level 0〜1 の大きさ
     */
    public record Sample(float[] rgb, float level) {}

    private final String key;
    private final String style;
    private final int frames;
    private final int stride;
    private final byte[] data;

    private WaveformSampler(String key, String style, int frames, int stride, byte[] data) {
        this.key = key;
        this.style = style;
        this.frames = frames;
        this.stride = stride;
        this.data = data;
    }

    /** 詳細波形がなければ null。 */
    static WaveformSampler of(Model.Waveform w) {
        if (w == null || w.detail() == null || w.detail().frames() <= 0) return null;
        byte[] data = Base64.getDecoder().decode(w.detail().data());
        int frames = Math.min(w.detail().frames(), data.length / w.detail().stride());
        return new WaveformSampler(w.key(), w.style(), frames, w.detail().stride(), data);
    }

    String key() { return key; }

    /** 曲の先頭からの時刻 (ms) の色と大きさ。曲の範囲外なら null。 */
    Sample sample(double timeMs) {
        int center = (int) Math.floor(timeMs * FRAMES_PER_SECOND / 1000.0);
        if (center < 0 || center >= frames) return null;
        int from = Math.max(0, center - WINDOW), to = Math.min(frames, center + WINDOW);
        int n = to - from;
        double r = 0, g = 0, b = 0, size = 0;
        for (int f = from; f < to; f++) {
            int at = f * stride;
            if (style.equals("THREE_BAND")) {
                double low = u(at), mid = u(at + 1), high = u(at + 2);
                double max = Math.max(low, Math.max(mid, high));
                if (max > 0) {
                    r += low / max;
                    g += mid / max;
                    b += high / max;
                }
                size += max;
            } else {
                size += u(at);
                r += u(at + 1) / 255.0;
                g += u(at + 2) / 255.0;
                b += u(at + 3) / 255.0;
            }
        }
        float level = (float) Math.min(1.0, size / n / 31.0);
        float[] rgb = style.equals("BLUE") ? null : new float[]{(float) (r / n), (float) (g / n), (float) (b / n)};
        return new Sample(rgb, level);
    }

    private int u(int at) {
        return data[at] & 0xff;
    }
}
