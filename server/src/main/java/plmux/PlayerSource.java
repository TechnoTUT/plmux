package plmux;

import java.util.function.Consumer;

/** プレイヤー状態の供給元。実機 ({@link BeatLinkService}) とデモ ({@link DemoSource}) を差し替えられるようにする。 */
public interface PlayerSource {

    /** WebSocket で配信する拍イベント。 */
    record BeatEvent(int player, int beatWithinBar, double bpm, boolean master) {}

    void start() throws Exception;

    void stop();

    void onChange(Runnable listener);

    void onBeat(Consumer<BeatEvent> listener);

    Model.State snapshot(Model.LinkStatus link);

    /** アートワーク画像 (JPEG/PNG)。なければ null。 */
    byte[] artwork(int player);

    /** 波形・ビートグリッド・キュー。何も取れていなければ null。 */
    Model.Waveform waveform(int player);
}
