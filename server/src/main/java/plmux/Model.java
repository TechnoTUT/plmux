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
            String artKey) {}

    public record Player(
            int number,
            String model,
            String address,
            boolean playing,
            boolean onAir,
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
