# plmux

A headless server + web GUI that brings the parts of
[Beat Link Trigger](https://github.com/Deep-Symmetry/beat-link-trigger) we use at TechnoTUT to the browser.
Built primarily for the **Pioneer DJ XDJ-AZ**.

- **Player status** — BPM, pitch, beat, Play / On-Air / Master / Sync, title, artist, artwork and playback position for every deck
- **Waveforms** — CDJ-style scrolling waveform and whole-track overview with beat grid, hot cues, memory points and loops (RGB, three-band and blue styles)
- **Track history** — automatically logs tracks that have been playing On-Air for a while (15 s by default), grouped into sessions (events), exportable as CSV
- **Ableton Link** — monitor a Link session, or make Link follow the tempo and beat grid of the CDJ tempo master (via Carabiner)
- **OBS overlay** — a transparent Now Playing page for an OBS Browser source, styled from the Settings tab

## Layout

```
vendor/beat-link/     git submodule (main)  Pro DJ Link library
vendor/crate-digger/  git submodule (main)  rekordbox database parser used by beat-link
server/               Java 21 + Javalin     REST API + WebSocket, serves the built web GUI
web/                  Vue 3 + Vite + TS     Web GUI (Tailwind CSS, styled after TechnoTUT/moip-toolkit)
deploy/k8s/           Reference manifests   (production manifests live in TechnoTUT/Infra)
```

We track beat-link **main** rather than the 8.0.0 release, because XDJ-AZ support (Pro DJ Link mode detection,
Device Library Plus, Beatport streaming slots, …) has not been released yet. crate-digger is only available as a
SNAPSHOT on main, so it is built from a submodule too instead of being pulled from a snapshot repository.

## Build & run

```bash
git clone --recurse-submodules <this repo>    # existing clone: git submodule update --init

(cd web && npm ci && npm run build)           # web/dist gets bundled into the jar
./mvnw -DskipTests package -pl server -am
java -jar server/target/plmux-server.jar      # http://localhost:8080
```

### Demo mode

To try the GUI without any DJ equipment, start the server with `PLMUX_DEMO=true`. It does not touch the network and
streams dummy data that mimics a single XDJ-AZ in Pro DJ Link mode (two decks), advancing one mix every 64 seconds. Track history is
recorded through the same code path as with real hardware.

```bash
PLMUX_DEMO=true PLMUX_DATA_DIR=/tmp/plmux-demo java -jar server/target/plmux-server.jar
```

### Frontend development

Keep the server running (`java -jar …`, demo mode works fine) and start Vite with `cd web && npm run dev`.
API and WebSocket requests are proxied to port 8080.

GUI URL options: `#history` / `#link` / `#expressions` / `#settings` open a specific tab, `?theme=dark` / `?theme=light` force the color theme
(handy for a fixed booth display).

### OBS overlay

Add a **Browser** source in OBS with the URL `http://<server>:8080/overlay` and a size of 1920×1080.
It shows the title, artist, artwork and optional tags (album, genre, key, BPM) of the track on the tempo master
deck, and keeps showing the last track when no deck is master. The look follows the Track List theme of
[prolink-tools](https://prolink.tools/), on a single translucent card.
Theme, corner position (right corners are right-aligned), artwork, card opacity (0% = fully transparent),
tags and "hide when the master deck is stopped" are set in the GUI's Settings tab (with a live preview) and
apply to open overlays immediately.
With the Vite dev server, open `/overlay.html` instead.

### Clojure expressions

Like Beat Link Trigger, plmux can run your own Clojure code when things happen on the DJ Link network: send OSC to
lighting or VJ software, react to phrases, and so on. Write the expressions in the file
`PLMUX_EXPRESSIONS_FILE` (by default `expressions.clj` in the data directory). plmux checks it every 2 seconds and
reloads it when it changes (`global-shutdown` of the old version runs first).
The GUI's **Expressions** tab shows the file, load errors and how often each hook ran, with the line of the last
error. It is read-only on purpose: the GUI has no authentication, and expressions run arbitrary code on the server.

Each hook is a top-level form whose body is the code to run:

| Hook | When it runs |
|---|---|
| `(global-setup ...)` / `(setup ...)` | Once after loading (BLT's Global Setup / trigger Setup) |
| `(came-online ...)` / `(going-offline ...)` | When DJ Link goes online / offline (`came-online` also runs after loading while online) |
| `(tracked-update ...)` | Every status packet from the tempo master deck (about every 200 ms), like a BLT trigger watching the Master Player |
| `(beat ...)` | Every beat of the tempo master deck |
| `(every ms ...)` | Every `ms` milliseconds (at least 20) with the tempo master's current state. plmux only; handy for smooth lighting |
| `(phrase-started ...)` / `(phrase-ended ...)` | When the tempo master deck enters / leaves a rekordbox phrase, or stops |
| `(shutdown ...)` / `(global-shutdown ...)` | Once before unloading or exiting |

Inside the bodies you can use the same names as in BLT expressions: `status` (beat-link's `CdjStatus` in
`tracked-update`, `Beat` in `beat`; `nil` in demo mode), `device-number`, `playing?`, `tempo-master?`, `synced?`,
`on-air?` (`nil` for the XDJ-AZ), `effective-tempo`, `pitch-multiplier`, `beat-number`, `beat-within-bar`,
`track-time-reached` (ms), `rekordbox-id`, `track-title`, `track-artist`, `track-album`, `track-genre`, `track-key`,
`track-length` (seconds), and `player` (plmux's `Model$Player`). Phrase hooks also get `phrase` (`Model$Phrase`),
`phrase-kind` (`"intro"`, `"verse"`, `"bridge"`, `"chorus"`, `"up"`, `"down"`, `"outro"`) and `phrase-label`
(`"Verse 2"`, `"Up 1"`, ...). `globals` and `locals` are atoms, and `osc/osc-client`, `osc/osc-send` and
`osc/osc-close` work like overtone/osc-clj in BLT (a Long is sent as a 64-bit `h` and a Double as `d`; use `(float x)`
for `f`). Common beat-link classes (`CdjStatus`, `Util`, `TimeFinder`, `WaveformFinder`, ...) are imported.

plmux also provides these helpers, which work in demo mode too:

| Function | Returns |
|---|---|
| `(waveform-sample player time-ms)` | `{:r :g :b :level}` (0–1) of the waveform around that position. The color always comes from rekordbox's RGB waveform, whatever waveform style the GUI displays; only for tracks without RGB analysis does it fall back to the displayed waveform (3-Band: low / mid / high become red / green / blue; Blue: `:r :g :b` are `nil`) |
| `(rgb-waveform-detail player)` | The RGB waveform detail as a beat-link `WaveformDetail`, also independent of the displayed style. Use it in place of `(.getLatestDetailFor (WaveformFinder/getInstance) n)` in BLT expressions (`nil` in demo mode) |
| `(beat-at player time-ms)` / `(phrase-at player beat)` | Beat number at a position / the `Model$Phrase` containing a beat |
| `(waveform player)` / `(master-player)` / `(current-state)` | The same data the GUI uses |

All hooks run one at a time on a single thread, so keep them quick.

#### Example: lighting with dmxctl

This does what the [Beat Link Trigger section of the dmxctl README](https://github.com/TechnoTUT/dmxctl#beat-link-trigger-との連携)
describes, without running BLT: the waveform color and loudness a little ahead of the playhead go to dmxctl's
`/dmx/main/rgb/at` and `/dmx/level/at`, and rekordbox phrases go to `/dmx/phrase`. In dmxctl, turn on OSC receive and
Auto, and set its tempo source to Ableton Link (with plmux's Link mode set to Follow CDJ Master).
The same example, ready to deploy, is in [`deploy/k8s/expressions.clj`](deploy/k8s/expressions.clj).

```clojure
(global-setup
  (swap! globals assoc :dmx (osc/osc-client "192.168.1.20" 9000)))   ; the PC running dmxctl

;; Read the waveform LEAD seconds ahead (scaled by the pitch) and ask dmxctl to reach that color exactly
;; LEAD - LIGHT seconds from now, LIGHT being how long the lights take to react.
(every 40
  (when playing?
    (let [lead  0.15
          light 0.05
          s     (waveform-sample device-number (+ track-time-reached (* lead 1000 pitch-multiplier)))]
      (when s
        (when (:r s)
          (osc/osc-send (:dmx @globals) "/dmx/main/rgb/at"
                        (float (:r s)) (float (:g s)) (float (:b s)) (float (- lead light))))
        (osc/osc-send (:dmx @globals) "/dmx/level/at" (float (:level s)) (float (- lead light)))))))

(phrase-started
  (when phrase-kind
    (osc/osc-send (:dmx @globals) "/dmx/phrase" phrase-kind)))

;; Only if Ableton Link does not reach dmxctl: send the tempo and the downbeat instead.
;; (beat
;;   (osc/osc-send (:dmx @globals) "/dmx/bpm" (float effective-tempo))
;;   (when (= beat-within-bar 1)
;;     (osc/osc-send (:dmx @globals) "/dmx/resync")))

(global-shutdown
  (osc/osc-close (:dmx @globals)))
```

The BLT expressions from the dmxctl README can also be pasted as they are (Setup → `setup`, Tracked Update →
`tracked-update`, Beat → `beat`, Shutdown → `shutdown`, the Phrase Trigger's Started → `phrase-started`), with a few
changes: they use beat-link objects, so they do nothing useful in demo mode; get the waveform with
`(rgb-waveform-detail n)` instead of `WaveformFinder`, which only holds the style the GUI displays; and pass an
integer scale to `.segmentColor` / `.segmentHeight`, e.g. `(.segmentColor detail % 1)` instead of `false`.

### Container

```bash
docker build -t plmux .
docker run --network host -v plmux-data:/data plmux
```

`--network host` is required: Pro DJ Link relies on UDP broadcasts.

## Configuration (environment variables)

| Variable | Default | Description |
|---|---|---|
| `PLMUX_HTTP_PORT` | `8080` | Port for the web GUI / API |
| `PLMUX_DATA_DIR` | `./data` | Where the SQLite database (`plmux.db`) is stored |
| `PLMUX_DEVICE_NAME` | `plmux` | Device name announced on the DJ Link network |
| `PLMUX_USE_STANDARD_PLAYER_NUMBER` | `false` | Make the Virtual CDJ claim player number 1–4. Not needed for the XDJ-AZ |
| `PLMUX_HISTORY_MIN_PLAY_SECONDS` | `15` | Seconds a track must play before it is logged |
| `PLMUX_HISTORY_REQUIRE_ON_AIR` | `true` | Only count play time while the channel is On-Air (fader up). Ignored for the XDJ-AZ, which does not report On-Air |
| `PLMUX_LINK_MODE` | `OFF` | Default Link mode on startup: `OFF` / `MONITOR` / `FOLLOW`. A startup mode chosen in the GUI's Settings tab is saved and takes precedence. The current mode can always be changed in the Link tab |
| `PLMUX_LINK_LATENCY_MS` | `1` | Latency compensation for beat packets |
| `PLMUX_LINK_ALIGN_BARS` | `true` | Align bars, not just beats |
| `PLMUX_WAVEFORM_STYLE` | `THREE_BAND` | Default waveform style: `THREE_BAND` / `RGB` / `BLUE`. Can be changed in the GUI's Settings tab, which is saved and takes precedence. Falls back if a player cannot provide it |
| `PLMUX_CARABINER_PORT` | `17000` | Carabiner port |
| `PLMUX_CARABINER_EMBEDDED` | `true` | Launch the bundled Carabiner binary if none is running |
| `PLMUX_EXPRESSIONS_FILE` | `$PLMUX_DATA_DIR/expressions.clj` | Clojure expressions file (see [Clojure expressions](#clojure-expressions)). Nothing runs if it does not exist |
| `PLMUX_DEMO` | `false` | Serve dummy data instead of connecting to DJ Link (for GUI testing) |
| `TZ` | (OS) | Time zone used for timestamps in the history CSV |

## Notes for the XDJ-AZ

- Put the XDJ-AZ into **LINK (Pro DJ Link) mode**. The GUI shows a warning if it is in any other mode.
- The server must be on the **same LAN (same L2 segment)** as the XDJ-AZ's LINK port.
- Metadata is fetched with dbserver queries. The XDJ-AZ uses Device Library Plus IDs, so DeviceSQL database
  downloads cannot be used; beat-link main switches over automatically.
- The XDJ-AZ does not report On-Air: every channel is reported as off-air even with the faders up and ON AIR DISPLAY
  enabled. Track history therefore counts all play time on its decks (including cueing with the fader down), and
  the GUI shows On Air as `n/a`.
- In Pro DJ Link mode the XDJ-AZ offers only two decks (four decks are available only with Pro DJ Link mode off).
  One unit sends status for both decks, so players are enumerated from status packets rather than device announcements.
- On the XDJ-AZ / XDJ-XZ the slots are labelled `USB 1` / `USB 2` (same as BLT).

## Kubernetes

`deploy/k8s/` is for reference only; the production manifests live in TechnoTUT/Infra. Key points:

- **`hostNetwork: true` is required**, and the pod must be pinned (`nodeSelector`) to a node that has a NIC on the
  DJ booth LAN. If the cluster is not at the venue, join an on-site machine as a node (e.g. a k3s agent).
- The pod owns UDP 50000–50002 and the SQLite file, so use `replicas: 1` and `strategy: Recreate`.
- Mount a PVC at `/data` for the history database and an `emptyDir` at `/tmp` (the bundled Carabiner is extracted there).
- [Clojure expressions](#clojure-expressions) are not bundled in the image. The reference manifests generate the
  ConfigMap `plmux-expressions` from the example `deploy/k8s/expressions.clj`, mount it at `/config` and set
  `PLMUX_EXPRESSIONS_FILE=/config/expressions.clj`; in production, provide your own file the same way. Mount the
  ConfigMap as a directory (not with `subPath`, which never updates): plmux then picks up changes without a restart.
  The ConfigMap is optional, so without it nothing runs.
- The bundled Carabiner is **x86_64 only** (the ARM build in lib-carabiner is 32-bit armhf). On arm64 nodes, run
  Carabiner separately and set `PLMUX_CARABINER_EMBEDDED=false`.
- If you go through an Ingress, make sure WebSocket (`/ws`) is allowed.
- The GUI has no authentication yet. Keep it on the booth LAN or put authentication in front of it at the Ingress.
- `.github/workflows/image.yml` builds the image and pushes it to `ghcr.io/<owner>/<repo>`.

## CI

`.github/workflows/ci.yml` runs on every push to `main` and on pull requests: it type-checks and builds the web GUI
(`npm run build`) and runs the server tests (`./mvnw verify -pl server -am`, plmux tests only).

## Releases

Push a tag that starts with `v` to publish a release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

- `.github/workflows/release.yml` builds the web GUI and server, runs the tests, and creates a GitHub Release with
  `plmux-server-<tag>.jar` attached and generated release notes. Tags with a hyphen (e.g. `v1.0.0-rc.1`) become pre-releases.
- `.github/workflows/image.yml` pushes the container image `ghcr.io/technotut/plmux:<version>` (without the `v`) for the same tag.

## Updating beat-link

```bash
scripts/update-vendor.sh   # move the submodules to the latest main and sync beatlink.version in pom.xml
./mvnw -DskipTests package -pl server -am
git add vendor pom.xml && git commit -m "Update beat-link"
```

## API

| Method | Path | Description |
|---|---|---|
| WS | `/ws` | Pushes `state` (up to 10 Hz), `beat`, `history` (new entry) and `settings` (after a change) messages |
| GET | `/overlay` | Now Playing overlay page for OBS |
| GET | `/api/state` | Current state |
| GET | `/api/players/{n}/artwork` | Artwork image |
| GET | `/api/players/{n}/waveform` | Waveform preview/detail, beat grid, cue points and rekordbox phrases (see `Model.Waveform`) |
| GET | `/api/expressions` | Clojure expressions file, load error and hook status (read-only) |
| GET / PUT | `/api/link` | Link settings `{mode, latencyMs, alignBars}` |
| GET / PUT | `/api/settings` | GUI settings `{waveformStyle, linkStartupMode, overlay: {theme, position, showArtwork, hideWhenIdle, backgroundOpacity, tags}}` (saved in `plmux.db`) |
| GET / POST | `/api/sessions` | List sessions / start one `{name}` |
| POST | `/api/sessions/end` | End the running session |
| GET | `/api/history?session=current\|all\|{id}` | Track history |
| GET | `/api/history.csv?session=...` | Track history as CSV |
| DELETE | `/api/history/{id}` | Delete one history entry |
| GET | `/healthz` | Health check |

## License

Eclipse Public License 2.0, matching Beat Link Trigger.
The Ableton Link follow logic is a Java port of [beat-carabiner](https://github.com/Deep-Symmetry/beat-carabiner).
