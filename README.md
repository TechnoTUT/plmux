# plmux

A headless server + web GUI that brings the parts of
[Beat Link Trigger](https://github.com/Deep-Symmetry/beat-link-trigger) we use at TechnoTUT to the browser.
Built primarily for the **Pioneer DJ XDJ-AZ**.

- **Player status** — BPM, pitch, beat, Play / On-Air / Master / Sync, title, artist, artwork and playback position for every deck
- **Waveforms** — CDJ-style scrolling waveform and whole-track overview with beat grid, hot cues, memory points and loops (RGB, three-band and blue styles)
- **Track history** — automatically logs tracks that have been playing On-Air for a while (15 s by default), grouped into sessions (events), exportable as CSV
- **Ableton Link** — monitor a Link session, or make Link follow the tempo and beat grid of the CDJ tempo master (via Carabiner)

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
streams dummy data that mimics an XDJ-AZ in four-deck mode, advancing one mix every 64 seconds. Track history is
recorded through the same code path as with real hardware.

```bash
PLMUX_DEMO=true PLMUX_DATA_DIR=/tmp/plmux-demo java -jar server/target/plmux-server.jar
```

### Frontend development

Keep the server running (`java -jar …`, demo mode works fine) and start Vite with `cd web && npm run dev`.
API and WebSocket requests are proxied to port 8080.

GUI URL options: `#history` / `#link` / `#settings` open a specific tab, `?theme=dark` / `?theme=light` force the color theme
(handy for a fixed booth display).

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
| `PLMUX_HISTORY_REQUIRE_ON_AIR` | `true` | Only count play time while the channel is On-Air (fader up) |
| `PLMUX_LINK_MODE` | `OFF` | Default Link mode on startup: `OFF` / `MONITOR` / `FOLLOW`. A startup mode chosen in the GUI's Settings tab is saved and takes precedence. The current mode can always be changed in the Link tab |
| `PLMUX_LINK_LATENCY_MS` | `1` | Latency compensation for beat packets |
| `PLMUX_LINK_ALIGN_BARS` | `true` | Align bars, not just beats |
| `PLMUX_WAVEFORM_STYLE` | `THREE_BAND` | Default waveform style: `THREE_BAND` / `RGB` / `BLUE`. Can be changed in the GUI's Settings tab, which is saved and takes precedence. Falls back if a player cannot provide it |
| `PLMUX_CARABINER_PORT` | `17000` | Carabiner port |
| `PLMUX_CARABINER_EMBEDDED` | `true` | Launch the bundled Carabiner binary if none is running |
| `PLMUX_DEMO` | `false` | Serve dummy data instead of connecting to DJ Link (for GUI testing) |
| `TZ` | (OS) | Time zone used for timestamps in the history CSV |

## Notes for the XDJ-AZ

- Put the XDJ-AZ into **LINK (Pro DJ Link) mode**. The GUI shows a warning if it is in any other mode.
- The server must be on the **same LAN (same L2 segment)** as the XDJ-AZ's LINK port.
- Metadata is fetched with dbserver queries. The XDJ-AZ uses Device Library Plus IDs, so DeviceSQL database
  downloads cannot be used; beat-link main switches over automatically.
- One unit sends status for four decks, so players are enumerated from status packets rather than device announcements.
- On the XDJ-AZ / XDJ-XZ the slots are labelled `USB 1` / `USB 2` (same as BLT).

## Kubernetes

`deploy/k8s/` is for reference only; the production manifests live in TechnoTUT/Infra. Key points:

- **`hostNetwork: true` is required**, and the pod must be pinned (`nodeSelector`) to a node that has a NIC on the
  DJ booth LAN. If the cluster is not at the venue, join an on-site machine as a node (e.g. a k3s agent).
- The pod owns UDP 50000–50002 and the SQLite file, so use `replicas: 1` and `strategy: Recreate`.
- Mount a PVC at `/data` for the history database and an `emptyDir` at `/tmp` (the bundled Carabiner is extracted there).
- The bundled Carabiner is **x86_64 only** (the ARM build in lib-carabiner is 32-bit armhf). On arm64 nodes, run
  Carabiner separately and set `PLMUX_CARABINER_EMBEDDED=false`.
- If you go through an Ingress, make sure WebSocket (`/ws`) is allowed.
- The GUI has no authentication yet. Keep it on the booth LAN or put authentication in front of it at the Ingress.
- `.github/workflows/image.yml` builds the image and pushes it to `ghcr.io/<owner>/<repo>`.

## Updating beat-link

```bash
scripts/update-vendor.sh   # move the submodules to the latest main and sync beatlink.version in pom.xml
./mvnw -DskipTests package -pl server -am
git add vendor pom.xml && git commit -m "Update beat-link"
```

## API

| Method | Path | Description |
|---|---|---|
| WS | `/ws` | Pushes `state` (up to 10 Hz), `beat` and `history` (new entry) messages |
| GET | `/api/state` | Current state |
| GET | `/api/players/{n}/artwork` | Artwork image |
| GET | `/api/players/{n}/waveform` | Waveform preview/detail, beat grid and cue points (see `Model.Waveform`) |
| GET / PUT | `/api/link` | Link settings `{mode, latencyMs, alignBars}` |
| GET / PUT | `/api/settings` | GUI settings `{waveformStyle, linkStartupMode}` (saved in `plmux.db`) |
| GET / POST | `/api/sessions` | List sessions / start one `{name}` |
| POST | `/api/sessions/end` | End the running session |
| GET | `/api/history?session=current\|all\|{id}` | Track history |
| GET | `/api/history.csv?session=...` | Track history as CSV |
| DELETE | `/api/history/{id}` | Delete one history entry |
| GET | `/healthz` | Health check |

## License

Eclipse Public License 2.0, matching Beat Link Trigger.
The Ableton Link follow logic is a Java port of [beat-carabiner](https://github.com/Deep-Symmetry/beat-carabiner).
