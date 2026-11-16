package plmux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * 曲履歴ログ。プレイヤー状態を定期的に観測し、
 * 「再生中 (かつ On-Air)」の累計時間が閾値を超えた曲を 1 ロードにつき 1 回だけ記録する。
 * 記録はセッション (イベント単位) にまとめられ、SQLite に保存される。
 */
public final class HistoryService {
    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final Connection db;
    private final long minPlayMs;
    private final boolean requireOnAir;
    private final Map<Integer, Candidate> candidates = new HashMap<>();
    private final List<Consumer<Model.HistoryEntry>> listeners = new ArrayList<>();
    private long lastObserved = 0;

    /** プレイヤーごとの「いまロードされている曲」の再生時間の累計。 */
    private static final class Candidate {
        final String loadKey;
        long accumulatedMs = 0;
        boolean logged = false;
        Candidate(String loadKey) { this.loadKey = loadKey; }
    }

    public HistoryService(Path dataDir, int minPlaySeconds, boolean requireOnAir) throws Exception {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("plmux.db");
        this.db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        this.minPlayMs = minPlaySeconds * 1000L;
        this.requireOnAir = requireOnAir;
        try (Statement st = db.createStatement()) {
            st.executeUpdate("PRAGMA journal_mode=WAL");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sessions (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      name TEXT NOT NULL,
                      started_at INTEGER NOT NULL,
                      ended_at INTEGER)""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS history (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      session_id INTEGER REFERENCES sessions(id),
                      played_at INTEGER NOT NULL,
                      player INTEGER NOT NULL,
                      rekordbox_id INTEGER NOT NULL,
                      title TEXT, artist TEXT, album TEXT, genre TEXT, music_key TEXT,
                      bpm REAL, duration_sec INTEGER)""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS history_session ON history(session_id, played_at)");
        }
        log.info("History database: {}", file.toAbsolutePath());
    }

    public synchronized void onNewEntry(Consumer<Model.HistoryEntry> listener) {
        listeners.add(listener);
    }

    /** プレイヤー状態を観測する。状態変化のたび、または定期的に呼ぶ。 */
    public synchronized void observe(Model.State state) {
        long now = System.currentTimeMillis();
        long delta = lastObserved == 0 ? 0 : Math.min(now - lastObserved, 2000);  // 長い空白は数えない
        lastObserved = now;

        Set<Integer> seen = new HashSet<>();
        for (Model.Player p : state.players()) {
            seen.add(p.number());
            Model.Track t = p.track();
            if (t == null) {
                candidates.remove(p.number());
                continue;
            }
            String loadKey = t.sourcePlayer() + ":" + t.sourceSlot() + ":" + t.rekordboxId();
            Candidate c = candidates.get(p.number());
            if (c == null || !c.loadKey.equals(loadKey)) {
                c = new Candidate(loadKey);
                candidates.put(p.number(), c);
            }
            // Devices that cannot report On-Air (the XDJ-AZ) count all play time.
            boolean audible = p.playing() && (!requireOnAir || !p.onAirKnown() || p.onAir());
            if (audible) c.accumulatedMs += delta;
            if (!c.logged && c.accumulatedMs >= minPlayMs) {
                c.logged = true;
                record(p.number(), t, now - c.accumulatedMs);
            }
        }
        candidates.keySet().retainAll(seen);
    }

    private void record(int player, Model.Track t, long playedAt) {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO history (session_id, played_at, player, rekordbox_id, title, artist, album, genre,
                                     music_key, bpm, duration_sec)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            Long sessionId = currentSessionId();
            if (sessionId == null) ps.setNull(1, Types.INTEGER); else ps.setLong(1, sessionId);
            ps.setLong(2, playedAt);
            ps.setInt(3, player);
            ps.setInt(4, t.rekordboxId());
            ps.setString(5, t.title());
            ps.setString(6, t.artist());
            ps.setString(7, t.album());
            ps.setString(8, t.genre());
            ps.setString(9, t.key());
            ps.setDouble(10, t.bpm());
            ps.setInt(11, t.durationSec());
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
            Model.HistoryEntry e = new Model.HistoryEntry(id, sessionId, playedAt, player, t.rekordboxId(),
                    t.title(), t.artist(), t.album(), t.genre(), t.key(), t.bpm(), t.durationSec());
            log.info("History: [{}] {} - {}", player, t.artist(), t.title());
            for (Consumer<Model.HistoryEntry> l : listeners) {
                try { l.accept(e); } catch (Exception ex) { log.warn("history listener failed", ex); }
            }
        } catch (SQLException ex) {
            log.error("Unable to record history entry", ex);
        }
    }

    // ---- セッション ----

    public synchronized Long currentSessionId() throws SQLException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id FROM sessions WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1")) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    /** 進行中のセッションを終了し、新しいセッションを開始する。 */
    public synchronized Model.Session startSession(String name) throws SQLException {
        endSession();
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO sessions (name, started_at) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setLong(2, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Model.Session(keys.getLong(1), name, now, null, 0);
            }
        }
    }

    public synchronized void endSession() throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("UPDATE sessions SET ended_at = ? WHERE ended_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public synchronized List<Model.Session> sessions() throws SQLException {
        List<Model.Session> out = new ArrayList<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT s.id, s.name, s.started_at, s.ended_at,
                            (SELECT COUNT(*) FROM history h WHERE h.session_id = s.id)
                     FROM sessions s ORDER BY s.started_at DESC""")) {
            while (rs.next()) {
                long endedAt = rs.getLong(4);
                Long ended = rs.wasNull() ? null : endedAt;
                out.add(new Model.Session(rs.getLong(1), rs.getString(2), rs.getLong(3), ended, rs.getInt(5)));
            }
        }
        return out;
    }

    // ---- 履歴 ----

    /**
     * @param sessionId 指定したセッションの履歴。null ならセッションに属さないものも含めた最新分。
     */
    public synchronized List<Model.HistoryEntry> history(Long sessionId, int limit) throws SQLException {
        String sql = "SELECT id, session_id, played_at, player, rekordbox_id, title, artist, album, genre, "
                + "music_key, bpm, duration_sec FROM history "
                + (sessionId != null ? "WHERE session_id = ? " : "")
                + "ORDER BY played_at DESC LIMIT ?";
        List<Model.HistoryEntry> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            int i = 1;
            if (sessionId != null) ps.setLong(i++, sessionId);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long sid = rs.getLong(2);
                    Long session = rs.wasNull() ? null : sid;
                    out.add(new Model.HistoryEntry(rs.getLong(1), session, rs.getLong(3), rs.getInt(4),
                            rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getDouble(11), rs.getInt(12)));
                }
            }
        }
        return out;
    }

    public synchronized boolean deleteEntry(long id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM history WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized void close() {
        try { db.close(); } catch (SQLException ignored) { }
    }
}
