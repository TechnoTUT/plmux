package plmux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

/**
 * GUI から変更できる設定を SQLite (plmux.db) に保存する。保存値がなければ環境変数の値を既定として使う。
 */
public final class SettingsStore {
    private final Connection db;

    public SettingsStore(Path dataDir) throws Exception {
        Files.createDirectories(dataDir);
        db = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("plmux.db").toAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }

    public synchronized String get(String key, String def) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : def;
            }
        }
    }

    public synchronized void put(String key, String value) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public synchronized void close() {
        try { db.close(); } catch (SQLException ignored) { }
    }
}
