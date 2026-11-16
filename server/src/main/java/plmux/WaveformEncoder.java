package plmux;

import org.deepsymmetry.beatlink.Util;
import org.deepsymmetry.beatlink.data.BeatGrid;
import org.deepsymmetry.beatlink.data.CueList;
import org.deepsymmetry.beatlink.data.WaveformDetail;
import org.deepsymmetry.beatlink.data.WaveformFinder.ThreeBandLayer;
import org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle;
import org.deepsymmetry.beatlink.data.WaveformPreview;
import org.deepsymmetry.cratedigger.pdb.RekordboxAnlz;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * beat-link の波形オブジェクトを、ブラウザで描画しやすい {@link Model.Waveform} に変換する。
 * 高さや色の解釈は beat-link 自身の segmentHeight / segmentColor に任せ、
 * 描画方法は WaveformDetailComponent / WaveformPreviewComponent に合わせている。
 */
final class WaveformEncoder {
    private WaveformEncoder() {}

    static Model.Waveform encode(String key, WaveformPreview preview, WaveformDetail detail,
                                 BeatGrid grid, CueList cues, RekordboxAnlz.SongStructureTag structure,
                                 long durationMs) {
        WaveformStyle style = detail != null ? detail.style : preview != null ? preview.style : WaveformStyle.RGB;
        int[] beatTimes = new int[0];
        int[] beatsWithinBar = new int[0];
        if (grid != null) {
            beatTimes = new int[grid.beatCount];
            beatsWithinBar = new int[grid.beatCount];
            for (int i = 0; i < grid.beatCount; i++) {
                beatTimes[i] = (int) grid.getTimeWithinTrack(i + 1);
                beatsWithinBar[i] = grid.getBeatWithinBar(i + 1);
            }
        }
        if (durationMs <= 0 && detail != null) durationMs = detail.getTotalTime();
        return new Model.Waveform(key, style.name(), durationMs,
                preview != null ? encodePreview(preview) : null,
                detail != null ? encodeDetail(detail) : null,
                beatTimes, beatsWithinBar, encodeCues(cues), encodePhrases(structure));
    }

    static Model.WaveDetail encodeDetail(WaveformDetail detail) {
        int frames = detail.getFrameCount();
        if (detail.style == WaveformStyle.THREE_BAND) {
            byte[] out = new byte[frames * 3];
            for (int i = 0; i < frames; i++) {
                out[i * 3] = clampByte(detail.segmentHeight(i, 1, ThreeBandLayer.LOW));
                out[i * 3 + 1] = clampByte(detail.segmentHeight(i, 1, ThreeBandLayer.MID));
                out[i * 3 + 2] = clampByte(detail.segmentHeight(i, 1, ThreeBandLayer.HIGH));
            }
            return new Model.WaveDetail(frames, 3, b64(out));
        }
        byte[] out = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            Color c = detail.segmentColor(i, 1);
            out[i * 4] = clampByte(detail.segmentHeight(i, 1));
            out[i * 4 + 1] = (byte) c.getRed();
            out[i * 4 + 2] = (byte) c.getGreen();
            out[i * 4 + 3] = (byte) c.getBlue();
        }
        return new Model.WaveDetail(frames, 4, b64(out));
    }

    static Model.WavePreview encodePreview(WaveformPreview preview) {
        int n = preview.segmentCount;
        switch (preview.style) {
            case THREE_BAND -> {
                byte[] out = new byte[n * 3];
                for (int i = 0; i < n; i++) {
                    out[i * 3] = clampByte(preview.segmentHeight(i, ThreeBandLayer.LOW));
                    out[i * 3 + 1] = clampByte(preview.segmentHeight(i, ThreeBandLayer.MID));
                    out[i * 3 + 2] = clampByte(preview.segmentHeight(i, ThreeBandLayer.HIGH));
                }
                return new Model.WavePreview(n, preview.maxHeight, 3, b64(out));
            }
            case RGB -> {
                byte[] out = new byte[n * 8];
                for (int i = 0; i < n; i++) {
                    put(out, i * 8, preview.segmentHeight(i, false), preview.segmentColor(i, false));
                    put(out, i * 8 + 4, preview.segmentHeight(i, true), preview.segmentColor(i, true));
                }
                return new Model.WavePreview(n, preview.maxHeight, 8, b64(out));
            }
            default -> {
                byte[] out = new byte[n * 4];
                for (int i = 0; i < n; i++) {
                    put(out, i * 4, preview.segmentHeight(i, false), preview.segmentColor(i, false));
                }
                return new Model.WavePreview(n, preview.maxHeight, 4, b64(out));
            }
        }
    }

    static List<Model.Cue> encodeCues(CueList cues) {
        List<Model.Cue> out = new ArrayList<>();
        if (cues == null) return out;
        for (CueList.Entry e : cues.entries) {
            Color c = e.getColor();
            out.add(new Model.Cue(e.cueTime, e.isLoop ? e.loopTime : null, e.hotCueNumber, hex(c),
                    e.comment == null || e.comment.isBlank() ? null : e.comment));
        }
        return out;
    }

    /** 表記は beat-link (BLT) の {@link Util#phraseLabel} / {@link Util#phraseColor} に任せる。 */
    static List<Model.Phrase> encodePhrases(RekordboxAnlz.SongStructureTag structure) {
        List<Model.Phrase> out = new ArrayList<>();
        if (structure == null) return out;
        List<RekordboxAnlz.SongStructureEntry> entries = structure.body().entries();
        for (int i = 0; i < entries.size(); i++) {
            RekordboxAnlz.SongStructureEntry e = entries.get(i);
            int end = i == entries.size() - 1 ? structure.body().endBeat() : entries.get(i + 1).beat();
            String label = Util.phraseLabel(e);
            out.add(new Model.Phrase(e.beat(), end, phraseKind(label), label,
                    structure.body().mood() != null ? structure.body().mood().name() : null, hex(Util.phraseColor(e))));
        }
        return out;
    }

    /** "Verse 2" → "verse"、"Up 1" → "up"。beat-link が知らないフレーズ ("Unknown High" など) なら null。 */
    static String phraseKind(String label) {
        if (label == null || label.startsWith("Unknown")) return null;
        String kind = label.replaceAll("\\s*\\d+$", "").trim().toLowerCase(java.util.Locale.ROOT);
        return kind.isEmpty() ? null : kind;
    }

    private static String hex(Color c) {
        return c == null ? null : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** 1 区間 (half frame) の長さ。150 区間で 1 秒。 */
    static long halfFrameMs(long halfFrames) {
        return Util.halfFrameToTime(halfFrames);
    }

    private static void put(byte[] out, int at, int height, Color c) {
        out[at] = clampByte(height);
        out[at + 1] = (byte) c.getRed();
        out[at + 2] = (byte) c.getGreen();
        out[at + 3] = (byte) c.getBlue();
    }

    private static byte clampByte(int v) {
        return (byte) Math.max(0, Math.min(255, v));
    }

    static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
