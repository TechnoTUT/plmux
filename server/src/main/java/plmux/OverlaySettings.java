package plmux;

import io.javalin.http.BadRequestResponse;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * OBS 用オーバーレイ (/overlay) の見た目の設定。Settings タブから変更し、SQLite に保存する。
 *
 * @param theme             DARK (白文字) / LIGHT (黒文字)
 * @param position          寄せる位置 (TOP_LEFT / TOP_RIGHT / BOTTOM_LEFT / BOTTOM_RIGHT)。右側なら右揃えになる
 * @param showArtwork       アートワークを表示するか
 * @param hideWhenIdle      テンポマスターが再生していないときに隠すか
 * @param backgroundOpacity カードの背景の不透明度 (0〜100)。0 で完全に透過
 * @param tags              曲名・アーティストの下に出す情報 (ALBUM / GENRE / KEY / BPM)。表示順もこの順
 */
public record OverlaySettings(String theme, String position, boolean showArtwork, boolean hideWhenIdle,
                              int backgroundOpacity, List<String> tags) {
    public static final List<String> THEMES = List.of("DARK", "LIGHT");
    public static final List<String> POSITIONS = List.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT");
    public static final List<String> TAGS = List.of("ALBUM", "GENRE", "KEY", "BPM");

    private static final OverlaySettings DEFAULTS =
            new OverlaySettings("DARK", "BOTTOM_LEFT", true, false, 25, List.of("ALBUM", "GENRE"));

    /** PUT /api/settings の overlay。null の項目は変更しない。 */
    public record Update(String theme, String position, Boolean showArtwork, Boolean hideWhenIdle,
                         Integer backgroundOpacity, List<String> tags) {}

    public static OverlaySettings load(SettingsStore store) throws SQLException {
        String theme = store.get("overlay.theme", DEFAULTS.theme);
        String position = store.get("overlay.position", DEFAULTS.position);
        int opacity;
        try {
            opacity = Integer.parseInt(store.get("overlay.backgroundOpacity", Integer.toString(DEFAULTS.backgroundOpacity)));
        } catch (NumberFormatException e) {
            opacity = DEFAULTS.backgroundOpacity;
        }
        String tags = store.get("overlay.tags", String.join(",", DEFAULTS.tags));
        return new OverlaySettings(
                THEMES.contains(theme) ? theme : DEFAULTS.theme,
                POSITIONS.contains(position) ? position : DEFAULTS.position,
                Boolean.parseBoolean(store.get("overlay.showArtwork", Boolean.toString(DEFAULTS.showArtwork))),
                Boolean.parseBoolean(store.get("overlay.hideWhenIdle", Boolean.toString(DEFAULTS.hideWhenIdle))),
                Math.clamp(opacity, 0, 100),
                Arrays.stream(tags.split(",")).filter(TAGS::contains).toList());
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
        if (u.backgroundOpacity() != null && (u.backgroundOpacity() < 0 || u.backgroundOpacity() > 100)) {
            throw new BadRequestResponse("overlay.backgroundOpacity must be between 0 and 100");
        }
        List<String> tags = null;
        if (u.tags() != null) {
            tags = u.tags().stream().map(String::toUpperCase).distinct().toList();
            if (!TAGS.containsAll(tags)) throw new BadRequestResponse("overlay.tags must be a subset of " + TAGS);
            // 表示順は選んだ順ではなく TAGS の順に揃える
            tags = TAGS.stream().filter(tags::contains).toList();
        }
        if (theme != null) store.put("overlay.theme", theme);
        if (position != null) store.put("overlay.position", position);
        if (u.showArtwork() != null) store.put("overlay.showArtwork", u.showArtwork().toString());
        if (u.hideWhenIdle() != null) store.put("overlay.hideWhenIdle", u.hideWhenIdle().toString());
        if (u.backgroundOpacity() != null) store.put("overlay.backgroundOpacity", u.backgroundOpacity().toString());
        if (tags != null) store.put("overlay.tags", String.join(",", tags));
    }
}
