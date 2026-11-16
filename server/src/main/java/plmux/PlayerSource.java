package plmux;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.function.Consumer;

/** プレイヤー状態の供給元。実機 ({@link BeatLinkService}) とデモ ({@link DemoSource}) を差し替えられるようにする。 */
public interface PlayerSource {

    /**
     * WebSocket で配信する拍イベント。
     *
     * @param raw beat-link の {@code Beat} (Clojure の式に status として渡す)。デモでは null
     */
    record BeatEvent(int player, int beatWithinBar, double bpm, boolean master, @JsonIgnore Object raw) {}

    /**
     * プレイヤーの状態が届いた (実機では約 200ms ごと)。Clojure の式の tracked-update に使う。
     *
     * @param raw beat-link の {@code CdjStatus}。デモでは null
     */
    record StatusEvent(int player, Object raw) {}

    void start() throws Exception;

    void stop();

    void onChange(Runnable listener);

    void onBeat(Consumer<BeatEvent> listener);

    void onStatus(Consumer<StatusEvent> listener);

    Model.State snapshot(Model.LinkStatus link);

    /** アートワーク画像 (JPEG/PNG)。なければ null。 */
    byte[] artwork(int player);

    /** 波形の表示形式 (THREE_BAND / RGB / BLUE) を切り替える。 */
    void setWaveformStyle(String style);

    /** 波形・ビートグリッド・キュー。何も取れていなければ null。 */
    Model.Waveform waveform(int player);
}
