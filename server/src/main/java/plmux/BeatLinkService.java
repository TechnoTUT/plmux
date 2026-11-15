package plmux;

import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * beat-link の各 Finder を起動・監視し、プレイヤー状態を {@link Model.State} として提供する。
 *
 * <p>XDJ-AZ について (beat-link main 時点):
 * <ul>
 *   <li>Pro DJ Link モード (LINK) のときだけ CDJ と同等に扱える。それ以外のモードは検出して警告する
 *       ({@link BeatFinder#canSeeXdjAzInProDJLinkMode()})。</li>
 *   <li>Device Library Plus の ID を使うため DeviceSQL (export.pdb) のダウンロードは使えない。
 *       メタデータは dbserver 問い合わせで取得する (beat-link 側が自動で切り替える)。</li>
 *   <li>XDJ-AZ はメタデータ取得に 1〜4 番を必要としない機種 (METADATA_FLEXIBLE) なので、
 *       VirtualCdj は標準外の空き番号で動かしてよい。4 デッキで 1〜4 番は埋まる。</li>
 *   <li>1 台で複数デッキ分のステータスを送るため、プレイヤーはアナウンスではなくステータスから列挙する。</li>
 *   <li>スロット番号の意味が他機種と異なる (SD_SLOT が USB 2)。</li>
 * </ul>
 */
public final class BeatLinkService implements PlayerSource {
    private static final Logger log = LoggerFactory.getLogger(BeatLinkService.class);

    private final Config config;
    private final DeviceFinder deviceFinder = DeviceFinder.getInstance();
    private final VirtualCdj virtualCdj = VirtualCdj.getInstance();
    private final BeatFinder beatFinder = BeatFinder.getInstance();
    private final MetadataFinder metadataFinder = MetadataFinder.getInstance();
    private final CrateDigger crateDigger = CrateDigger.getInstance();
    private final ArtFinder artFinder = ArtFinder.getInstance();
    private final TimeFinder timeFinder = TimeFinder.getInstance();
    private final BeatGridFinder beatGridFinder = BeatGridFinder.getInstance();
    private final WaveformFinder waveformFinder = WaveformFinder.getInstance();
    /** プレイヤーごとに直近にエンコードした波形 (waveformKey が変わるまで使い回す) */
    private final Map<Integer, Model.Waveform> waveformCache = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "beatlink-supervisor");
        t.setDaemon(true);
        return t;
    });

    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<BeatEvent>> beatListeners = new CopyOnWriteArrayList<>();
    private volatile Long xdjAzNotLinkSince = null;

    public BeatLinkService(Config config) {
        this.config = config;
    }

    @Override
    public void onChange(Runnable listener) { changeListeners.add(listener); }

    @Override
    public void onBeat(Consumer<BeatEvent> listener) { beatListeners.add(listener); }

    public VirtualCdj virtualCdj() { return virtualCdj; }

    private void fireChange() {
        for (Runnable r : changeListeners) {
            try { r.run(); } catch (Exception e) { log.warn("change listener failed", e); }
        }
    }

    @Override
    public void start() throws Exception {
        virtualCdj.setDeviceName(config.deviceName());
        virtualCdj.setUseStandardPlayerNumber(config.useStandardPlayerNumber());
        try {
            waveformFinder.setPreferredStyle(WaveformFinder.WaveformStyle.valueOf(config.waveformStyle()));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown PLMUX_WAVEFORM_STYLE {}, using RGB", config.waveformStyle());
        }

        deviceFinder.addDeviceAnnouncementListener(new DeviceAnnouncementListener() {
            @Override public void deviceFound(DeviceAnnouncement a) {
                log.info("Device found: {} #{} ({})", a.getDeviceName(), a.getDeviceNumber(),
                        a.getAddress().getHostAddress());
                fireChange();
            }
            @Override public void deviceLost(DeviceAnnouncement a) {
                log.info("Device lost: {} #{}", a.getDeviceName(), a.getDeviceNumber());
                fireChange();
            }
        });
        virtualCdj.addUpdateListener(update -> fireChange());
        metadataFinder.addTrackMetadataListener(update -> fireChange());
        artFinder.addAlbumArtListener(update -> fireChange());
        beatGridFinder.addBeatGridListener(update -> fireChange());
        waveformFinder.addWaveformListener(new WaveformListener() {
            @Override public void previewChanged(WaveformPreviewUpdate update) { fireChange(); }
            @Override public void detailChanged(WaveformDetailUpdate update) { fireChange(); }
        });
        beatFinder.addBeatListener(beat -> {
            BeatEvent event = new BeatEvent(beat.getDeviceNumber(),
                    beat.isBeatWithinBarMeaningful() ? beat.getBeatWithinBar() : 0,
                    round2(beat.getEffectiveTempo()), beat.isTempoMaster());
            for (Consumer<BeatEvent> l : beatListeners) {
                try { l.accept(event); } catch (Exception e) { log.warn("beat listener failed", e); }
            }
        });

        deviceFinder.start();
        // VirtualCdj.start() はデバイスが見つかるまで長時間ブロックするので、監視スレッドで起動・再起動する。
        scheduler.scheduleWithFixedDelay(this::supervise, 0, 5, TimeUnit.SECONDS);
    }

    private void supervise() {
        try {
            if (!deviceFinder.isRunning()) {
                deviceFinder.start();
            }
            if (!virtualCdj.isRunning()) {
                if (deviceFinder.getCurrentDevices().isEmpty()) {
                    updateXdjAzMode();
                    return;
                }
                log.info("Starting VirtualCdj ...");
                if (!virtualCdj.start()) {
                    log.warn("VirtualCdj could not start yet; will retry");
                    return;
                }
                log.info("VirtualCdj running as device #{}", virtualCdj.getDeviceNumber());
            }
            startIfNeeded(beatFinder::isRunning, beatFinder::start, "BeatFinder");
            startIfNeeded(metadataFinder::isRunning, metadataFinder::start, "MetadataFinder");
            startIfNeeded(crateDigger::isRunning, crateDigger::start, "CrateDigger");
            startIfNeeded(artFinder::isRunning, artFinder::start, "ArtFinder");
            startIfNeeded(timeFinder::isRunning, timeFinder::start, "TimeFinder");
            startIfNeeded(beatGridFinder::isRunning, beatGridFinder::start, "BeatGridFinder");
            startIfNeeded(waveformFinder::isRunning, waveformFinder::start, "WaveformFinder");
            updateXdjAzMode();
            fireChange();
        } catch (Exception e) {
            log.warn("beat-link supervisor error", e);
        }
    }

    private interface Starter { void start() throws Exception; }

    private static void startIfNeeded(BooleanSupplier running, Starter starter, String name) {
        if (!running.getAsBoolean()) {
            try {
                starter.start();
                log.info("{} started", name);
            } catch (Exception e) {
                log.warn("Unable to start {}", name, e);
            }
        }
    }

    private void updateXdjAzMode() {
        boolean azPresent = deviceFinder.getCurrentDevices().stream().anyMatch(d -> d.isXdjAz);
        boolean azInLink = azPresent && beatFinder.isRunning() && beatFinder.canSeeXdjAzInProDJLinkMode();
        if (azPresent && !azInLink) {
            if (xdjAzNotLinkSince == null) xdjAzNotLinkSince = System.currentTimeMillis();
        } else {
            xdjAzNotLinkSince = null;
        }
    }

    @Override
    public void stop() {
        scheduler.shutdownNow();
        for (Runnable r : List.<Runnable>of(waveformFinder::stop, beatGridFinder::stop, timeFinder::stop, artFinder::stop, crateDigger::stop,
                metadataFinder::stop, beatFinder::stop, virtualCdj::stop, deviceFinder::stop)) {
            try { r.run(); } catch (Exception e) { log.debug("stop failed", e); }
        }
    }

    public boolean isOnline() {
        return virtualCdj.isRunning();
    }

    @Override
    public byte[] artwork(int player) {
        AlbumArt art = artFinder.isRunning() ? artFinder.getLatestArtFor(player) : null;
        if (art == null) return null;
        java.nio.ByteBuffer raw = art.getRawBytes();
        byte[] bytes = new byte[raw.remaining()];
        raw.get(bytes);
        return bytes;
    }

    public TrackMetadata metadataFor(int player) {
        return metadataFinder.isRunning() ? metadataFinder.getLatestMetadataFor(player) : null;
    }

    @Override
    public Model.Waveform waveform(int player) {
        String key = waveformKey(player, metadataFor(player));
        if (key == null) return null;
        Model.Waveform cached = waveformCache.get(player);
        if (cached != null && cached.key().equals(key)) return cached;
        TrackMetadata md = metadataFor(player);
        Model.Waveform w = WaveformEncoder.encode(key,
                waveformFinder.isRunning() ? waveformFinder.getLatestPreviewFor(player) : null,
                waveformFinder.isRunning() ? waveformFinder.getLatestDetailFor(player) : null,
                beatGridFinder.isRunning() ? beatGridFinder.getLatestBeatGridFor(player) : null,
                md != null ? md.getCueList() : null,
                md != null ? md.getDuration() * 1000L : 0);
        waveformCache.put(player, w);
        return w;
    }

    /**
     * 波形関連データの版を表すキー。取得済みのデータが増えるたびに変わるので、ブラウザはキーが変わったら取り直す。
     * 何も取れていなければ null。
     */
    private String waveformKey(int player, TrackMetadata md) {
        WaveformPreview preview = waveformFinder.isRunning() ? waveformFinder.getLatestPreviewFor(player) : null;
        WaveformDetail detail = waveformFinder.isRunning() ? waveformFinder.getLatestDetailFor(player) : null;
        BeatGrid grid = beatGridFinder.isRunning() ? beatGridFinder.getLatestBeatGridFor(player) : null;
        if (preview == null && detail == null && grid == null) return null;
        return player + "-" + (md != null ? md.trackReference.rekordboxId : 0)
                + "-p" + (preview != null ? System.identityHashCode(preview) : 0)
                + "-d" + (detail != null ? System.identityHashCode(detail) : 0)
                + "-g" + (grid != null ? System.identityHashCode(grid) : 0)
                + "-c" + (md != null && md.getCueList() != null ? md.getCueList().entries.size() : 0);
    }

    /** 現在の全プレイヤー状態のスナップショットを作る。 */
    @Override
    public Model.State snapshot(Model.LinkStatus link) {
        List<Model.Player> players = new ArrayList<>();
        List<Model.Device> others = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Integer master = null;
        double masterBpm = 0;

        Map<Integer, DeviceAnnouncement> devices = new TreeMap<>();
        for (DeviceAnnouncement a : deviceFinder.getCurrentDevices()) {
            devices.put(a.getDeviceNumber(), a);
        }

        boolean online = virtualCdj.isRunning();
        Set<Integer> shown = new HashSet<>();
        Set<String> playerHosts = new HashSet<>();
        if (online) {
            DeviceUpdate m = virtualCdj.getTempoMaster();
            if (m != null) {
                master = m.getDeviceNumber();
                masterBpm = round2(virtualCdj.getMasterTempo());
            }
            List<CdjStatus> statuses = new ArrayList<>();
            for (DeviceUpdate u : virtualCdj.getLatestStatus()) {
                if (u instanceof CdjStatus s) statuses.add(s);
            }
            statuses.sort(Comparator.comparingInt(DeviceUpdate::getDeviceNumber));
            for (CdjStatus s : statuses) {
                players.add(toPlayer(ownerOf(s, devices), s));
                shown.add(s.getDeviceNumber());
                playerHosts.add(s.getAddress().getHostAddress() + "/" + s.getDeviceName());
            }
        }
        for (DeviceAnnouncement a : devices.values()) {
            int n = a.getDeviceNumber();
            if (shown.contains(n) || (online && n == virtualCdj.getDeviceNumber())) continue;
            // 複数デッキ機器 (XDJ-AZ 等) の本体アナウンスはプレイヤー側に含まれているので除く
            if (playerHosts.contains(a.getAddress().getHostAddress() + "/" + a.getDeviceName())) continue;
            others.add(new Model.Device(n, a.getDeviceName(), a.getAddress().getHostAddress()));
        }

        Long since = xdjAzNotLinkSince;
        if (since != null && System.currentTimeMillis() - since > 5000) {
            warnings.add("The XDJ-AZ does not seem to be in Pro DJ Link mode. "
                    + "Check its LINK setting (Pro DJ Link).");
        }
        if (!online && !devices.isEmpty()) {
            warnings.add("DJ Link devices are visible, but the Virtual CDJ could not start. Check the network settings.");
        }

        return new Model.State(false, online, master, masterBpm, players, others, warnings, link);
    }

    private static DeviceAnnouncement ownerOf(CdjStatus s, Map<Integer, DeviceAnnouncement> devices) {
        DeviceAnnouncement a = devices.get(s.getDeviceNumber());
        if (a != null) return a;
        for (DeviceAnnouncement d : devices.values()) {
            if (d.getAddress().equals(s.getAddress())) return d;
        }
        return null;
    }

    private Model.Player toPlayer(DeviceAnnouncement owner, CdjStatus s) {
        int n = s.getDeviceNumber();
        long positionMs = 0;
        if (timeFinder.isRunning()) {
            try { positionMs = timeFinder.getTimeFor(n); } catch (Exception ignored) { }
        }
        Model.Track track = null;
        TrackMetadata md = metadataFor(n);
        if (s.isTrackLoaded() && md != null) {
            String artKey = n + "-" + s.getTrackSourcePlayer() + "-" + s.getRekordboxId() + "-" + md.getArtworkId();
            track = new Model.Track(
                    s.getRekordboxId(),
                    blankToNull(md.getTitle()),
                    label(md.getArtist()),
                    label(md.getAlbum()),
                    label(md.getGenre()),
                    label(md.getKey()),
                    md.getDuration(),
                    md.getTempo() / 100.0,
                    s.getTrackSourcePlayer(),
                    slotName(s, owner),
                    artKey,
                    waveformKey(n, md));
        }
        return new Model.Player(
                n,
                s.getDeviceName(),
                s.getAddress().getHostAddress(),
                s.isPlaying(),
                s.isOnAir(),
                s.isTempoMaster(),
                s.isSynced(),
                round2(s.getEffectiveTempo()),
                round2(Util.pitchToPercentage(s.getPitch())),
                s.getBeatNumber(),
                s.isBeatWithinBarMeaningful() ? s.getBeatWithinBar() : 0,
                positionMs,
                s.isTrackLoaded(),
                track);
    }

    /** 機種ごとのスロット表記。XDJ-AZ / XDJ-XZ は SD_SLOT が「USB 2」に相当する (BLT players.clj と同じ扱い)。 */
    static String slotName(CdjStatus s, DeviceAnnouncement owner) {
        boolean usbPair = (owner != null && owner.isXdjAz)
                || "XDJ-AZ".equals(s.getDeviceName()) || "XDJ-XZ".equals(s.getDeviceName());
        return switch (s.getTrackSourceSlot()) {
            case NO_TRACK -> null;
            case CD_SLOT -> "CD";
            case SD_SLOT -> usbPair ? "USB 2" : "SD";
            case USB_SLOT -> usbPair ? "USB 1" : "USB";
            case USB_2_SLOT -> "USB 2";
            case COLLECTION -> "rekordbox";
            case BEATPORT_LINK_STREAMING, BEATPORT_LINK_STREAMING_AZ -> "Beatport";
            default -> "?";
        };
    }

    private static String label(SearchableItem item) {
        return item == null ? null : blankToNull(item.label);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
