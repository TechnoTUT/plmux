package plmux;

import io.javalin.http.BadRequestResponse;

import java.sql.SQLException;
import java.util.List;

/**
 * OBS 用オーバーレイ (/overlay) の見た目の設定。Settings タブから変更し、SQLite に保存する。
 *
 * @param theme       DARK / LIGHT
 * @param position    カードを寄せる位置 (TOP_LEFT / TOP_RIGHT / BOTTOM_LEFT / BOTTOM_RIGHT)
 * @param showArtwork アートワークを表示するか
 * @param hideWhenIdle テンポマスターが再生していないときに隠すか
 */
public record OverlaySettings(String theme, String position, boolean showArtwork, boolean hideWhenIdle) {
    public static final List<String> THEMES = List.of("DARK", "LIGHT");
    public static final List<String> POSITIONS = List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT");

    private static final OverlaySettings DEFAULTS = new OverlaySettings("DARK", "BOTTOM_LEFT", true, false);

    /** PUT /api/settings の overlay。null の項目は変更しない。 */
    public record Update(String theme, String position, Boolean showArtwork, Boolean hideWhenIdle) {}

    public static OverlaySettings load(SettingsStore store) throws SQLException {
        String theme = store.get("overlay.theme", DEFAULTS.theme);
        String position = store.get("overlay.position", DEFAULTS.position);
        return new OverlaySettings(
                THEMES.contains(theme) ? theme : DEFAULTS.theme,
                POSITIONS.contains(position) ? position : DEFAULTS.position,
                Boolean.parseBoolean(store.get("overlay.showArtwork", Boolean.toString(DEFAULTS.showArtwork))),
                Boolean.parseBoolean(store.get("overlay.hideWhenIdle", Boolean.toString(DEFAULTS.hideWhenIdle))));
    }

    /** 値を検証してから保存する。不正な値があれば何も保存せずに 400 を返す。 */
    public static void save(SettingsStore store, Update u) throws SQLException {
        String theme = u.theme() == null ? null : u.theme().toUpperCase();
        String position = u.position() == null ? null : u.position().toUpperCase();
        if (theme != null && !THEMES.contains(theme)) {
            throw new BadRequestResponse("overlay.theme must be one of " + THEMES);
        }
        if (position != null && !POSITIONS.contains(position)) {
            throw new BadRequestResponse("overlay.position must be one of " + POSITIONS);
        }
        if (theme != null) store.put("overlay.theme", theme);
        if (position != null) store.put("overlay.position", position);
        if (u.showArtwork() != null) store.put("overlay.showArtwork", u.showArtwork().toString());
        if (u.hideWhenIdle() != null) store.put("overlay.hideWhenIdle", u.hideWhenIdle().toString());
    }
}
