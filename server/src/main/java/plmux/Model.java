package plmux;

import java.util.List;

/** WebSocket / REST で返す JSON 用のデータ型。 */
public final class Model {
    private Model() {}

    public record Track(
            int rekordboxId,
            String title,
            String artist,
            String album,
            String genre,
            String key,
            int durationSec,
            double bpm,
            int sourcePlayer,
            String sourceSlot,
            String artKey,
            String waveformKey) {}

    public record Player(
            int number,
            String model,
            String address,
            boolean playing,
            boolean onAir,
            /** false when the device does not report On-Air (the XDJ-AZ always reports every channel as off-air). */
            boolean onAirKnown,
            boolean master,
            boolean synced,
            double bpm,
            double pitchPercent,
            int beat,
            int beatWithinBar,
            long positionMs,
            boolean trackLoaded,
            Track track) {}

    public record Device(int number, String name, String address) {}

    public record LinkStatus(
            String mode,
            boolean connected,
            boolean embedded,
            int peers,
            double bpm,
            Double targetBpm,
            String error) {}

    public record State(
            boolean demo,
            boolean djLinkOnline,
            Integer masterPlayer,
            double masterBpm,
            List<Player> players,
            List<Device> otherDevices,
            List<String> warnings,
            LinkStatus link) {}

    /**
     * 波形・ビートグリッド・キューをまとめたもの。ブラウザが canvas に描画する。
     *
     * <p>{@code detail.data} / {@code preview.data} は Base64 のバイト列で、1 区間あたり {@code stride} バイト:
     * <ul>
     *   <li>BLUE / RGB detail: [高さ 0-31, R, G, B] (1 区間 = 1/150 秒)</li>
     *   <li>THREE_BAND detail: [低域, 中域, 高域] の高さ (それぞれ独立)</li>
     *   <li>BLUE preview: [高さ, R, G, B]</li>
     *   <li>RGB preview: [奥の高さ, R, G, B, 手前の高さ, R, G, B]</li>
     *   <li>THREE_BAND preview: [低域, 低+中, 低+中+高] の累積高さ</li>
     * </ul>
     *
     * <p>{@code phrases} は rekordbox のフレーズ解析 (曲の構成)。解析されていない曲や、まだ取れていなければ空。
     */
    public record Waveform(
            String key,
            String style,
            long durationMs,
            WavePreview preview,
            WaveDetail detail,
            int[] beatTimesMs,
            int[] beatsWithinBar,
            List<Cue> cues,
            List<Phrase> phrases) {

        /** 曲の先頭からの時刻 (ms) にある拍の番号 (1 始まり)。ビートグリッドがないか、最初の拍より前なら 0。 */
        public int beatAt(double timeMs) {
            int lo = 0, hi = beatTimesMs.length - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (beatTimesMs[mid] <= timeMs) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found + 1;
        }

        /** その拍 (1 始まり) を含むフレーズ。なければ null。 */
        public Phrase phraseAt(int beat) {
            for (Phrase p : phrases) {
                if (beat >= p.beat() && beat < p.endBeat()) return p;
            }
            return null;
        }
    }

    /**
     * 曲の構成の 1 区間 (rekordbox のフレーズ)。
     *
     * @param beat    始まりの拍 (1 始まり)
     * @param endBeat 終わりの拍 (この拍は含まない)
     * @param kind    種類 (intro / verse / bridge / chorus / up / down / outro)。分からなければ null
     * @param label   rekordbox と同じ表記 ("Verse 2", "Up 1" など)
     * @param mood    曲の雰囲気 (LOW / MID / HIGH)。種類と表記はこれによって変わる
     * @param color   rekordbox がフレーズの表示に使う色 (#rrggbb)
     */
    public record Phrase(int beat, int endBeat, String kind, String label, String mood, String color) {}

    public record WavePreview(int segments, int maxHeight, int stride, String data) {}

    public record WaveDetail(int frames, int stride, String data) {}

    /** hotCue が 0 ならメモリーポイント。endMs があればループ。 */
    public record Cue(long timeMs, Long endMs, int hotCue, String color, String comment) {}

    public record Session(long id, String name, long startedAt, Long endedAt, int trackCount) {}

    public record HistoryEntry(
            long id,
            Long sessionId,
            long playedAt,
            int player,
            int rekordboxId,
            String title,
            String artist,
            String album,
            String genre,
            String key,
            double bpm,
            int durationSec) {}
}
