package space.orbit.backend.catalog;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import space.orbit.backend.io.GpCatalogParser;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.stream.CatalogSatelliteSamples;
import space.orbit.backend.stream.CatalogStreamHandler;
import space.orbit.backend.stream.CzmlEncoder;

/**
 * The catalog operating mode (Decision 13): keep the full active-satellite set
 * propagated and broadcast as one shared CZML feed.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>startup — load the bundled offline seed (guaranteed data with zero network);</li>
 *   <li>app-ready — async best-effort refresh from configured sources (CelesTrak
 *       is blocked here and fails fast; the GitHub mirror is reachable);</li>
 *   <li>every {@code propagation-interval-ms} — propagate all tracked satellites
 *       over a window and broadcast the CZML to all connected clients;</li>
 *   <li>on {@code refresh-cron} — refresh the TLE set again.</li>
 * </ol>
 */
@Service
@DependsOn("orekitConfig")
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final CatalogProperties props;
    private final GpCatalogParser parser;
    private final TleFactory tleFactory;
    private final SatellitePropagator propagator;
    private final CzmlEncoder encoder;
    private final CatalogStreamHandler streamHandler;
    private final HttpClient http;

    private TimeScale utc;
    private volatile List<TrackedSatellite> tracked = List.of();
    // NORAD id → frozen TLE snapshot, rebuilt with `tracked` each refresh. Lets
    // the scenario composer resolve a clicked satellite to a reproducible
    // initial state (Phase 3A, SRS §5.4.1) without re-reading the live catalog.
    private volatile Map<Integer, TleSnapshot> snapshotsByNorad = Map.of();
    // NORAD id → tracked satellite (with its propagator), for the orbit-path request.
    private volatile Map<Integer, TrackedSatellite> trackedByNorad = Map.of();

    public CatalogService(CatalogProperties props,
                          GpCatalogParser parser,
                          TleFactory tleFactory,
                          SatellitePropagator propagator,
                          CzmlEncoder encoder,
                          CatalogStreamHandler streamHandler) {
        this.props = props;
        this.parser = parser;
        this.tleFactory = tleFactory;
        this.propagator = propagator;
        this.encoder = encoder;
        this.streamHandler = streamHandler;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PostConstruct
    void init() {
        utc = TimeScalesFactory.getUTC(); // safe: @DependsOn orekitConfig
        // Answer client time-travel requests on the catalog socket (Decision 21):
        // propagate the whole tracked set to the requested epoch (over the
        // requested window, for play-from-time prefetch) and reply to that session.
        streamHandler.setSeekHandler((session, epoch, windowSeconds) ->
                streamHandler.sendMessageTo(session, buildSnapshotMessage(epoch, windowSeconds)));
        // Orbit-path request: propagate one satellite over one period and reply
        // with a dashed-polyline message (click-to-toggle on the globe).
        streamHandler.setOrbitHandler((session, noradId, epoch) -> {
            String message = buildOrbitMessage(noradId, epoch);
            if (message != null) {
                streamHandler.sendMessageTo(session, message);
            }
        });
        loadSeed();
    }

    /** Kick a best-effort refresh shortly after startup, off the main thread. */
    @EventListener(ApplicationReadyEvent.class)
    void initialRefresh() {
        Thread t = new Thread(this::refresh, "catalog-initial-refresh");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(cron = "${orbit.catalog.refresh-cron}")
    void scheduledRefresh() {
        refresh();
    }

    /** Propagate the tracked set over a window and broadcast it. */
    @Scheduled(fixedDelayString = "${orbit.catalog.propagation-interval-ms}")
    void propagationPass() {
        List<TrackedSatellite> snapshot = tracked;
        if (snapshot.isEmpty()) {
            return;
        }
        long t0 = System.nanoTime();
        String message = buildCatalogMessage(Instant.now(), snapshot,
                space.orbit.backend.stream.StreamContract.MESSAGE_TYPE_CATALOG);
        streamHandler.broadcast(message);
        double ms = (System.nanoTime() - t0) / 1e6;
        log.info("Catalog pass: {} sats, {} bytes, {} ms, {} clients",
                snapshot.size(), message.length(), String.format("%.0f", ms),
                streamHandler.connectionCount());
    }

    // --- catalog building -----------------------------------------------------

    private void loadSeed() {
        String seedPath = props.seedFile();
        if (seedPath == null || seedPath.isBlank()) {
            return;
        }
        Path path = Path.of(seedPath);
        if (!Files.isReadable(path)) {
            log.info("No catalog seed at {} (will rely on refresh)", path);
            return;
        }
        try {
            byte[] json = Files.readAllBytes(path);
            loadFromGpJson(json, "seed " + path);
        } catch (IOException e) {
            log.warn("Failed to read catalog seed {}: {}", path, e.toString());
        }
    }

    /** Try each configured source in order; first success replaces the catalog. */
    void refresh() {
        List<String> sources = props.sources();
        if (sources == null || sources.isEmpty()) {
            return;
        }
        for (String url : sources) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(props.fetchTimeoutMs()))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200 && resp.body().length > 0) {
                    loadFromGpJson(resp.body(), "source " + url);
                    return;
                }
                log.debug("Catalog source {} returned HTTP {}", url, resp.statusCode());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.debug("Catalog source {} unreachable: {}", url, e.toString());
            }
        }
        log.info("Catalog refresh: no source reachable; keeping {} satellites", tracked.size());
    }

    /** Parse GP/OMM JSON and rebuild the tracked-satellite set. */
    void loadFromGpJson(byte[] json, String origin) {
        List<GpRecord> records;
        try {
            records = parser.parse(json);
        } catch (IOException e) {
            log.warn("Failed to parse catalog from {}: {}", origin, e.toString());
            return;
        }

        int cap = props.maxSatellites();
        List<TrackedSatellite> built = new ArrayList<>(records.size());
        Map<Integer, TleSnapshot> snapshots = new HashMap<>(records.size());
        Map<Integer, TrackedSatellite> byNorad = new HashMap<>(records.size());
        int failed = 0;
        for (GpRecord r : records) {
            if (cap > 0 && built.size() >= cap) {
                break;
            }
            try {
                TLE tle = tleFactory.fromGp(r);
                TLEPropagator prop = propagator.build(tle);
                double periodMinutes = (2.0 * Math.PI / tle.getMeanMotion()) / 60.0;
                double inclinationDeg = Math.toDegrees(tle.getI());
                TrackedSatellite sat =
                        new TrackedSatellite(r.noradId(), r.objectName(), inclinationDeg, periodMinutes, prop);
                built.add(sat);
                byNorad.put(r.noradId(), sat);
                // Freeze the TLE lines + epoch now, while we hold the built TLE,
                // so scenarios can capture a reproducible initial state.
                snapshots.put(r.noradId(),
                        new TleSnapshot(r.noradId(), r.objectName(),
                                tle.getLine1(), tle.getLine2(), tle.getDate().toString()));
            } catch (RuntimeException e) {
                failed++;
            }
        }
        tracked = List.copyOf(built);
        snapshotsByNorad = Map.copyOf(snapshots);
        trackedByNorad = Map.copyOf(byNorad);
        log.info("Catalog loaded from {}: {} satellites ({} skipped)", origin, built.size(), failed);
    }

    /** Build a message for the current tracked set (convenience for tests/diagnostics). */
    String buildCatalogMessage(Instant now) {
        return buildCatalogMessage(now, tracked, space.orbit.backend.stream.StreamContract.MESSAGE_TYPE_CATALOG);
    }

    /** Largest snapshot window a client may request (bounds per-message size). */
    private static final int MAX_SNAPSHOT_WINDOW_SECONDS = 2400;

    /**
     * On-demand catalog snapshot at an arbitrary epoch (live time-travel,
     * Decision 21). Propagates the current tracked set to {@code epoch} — past or
     * future — and tags the message {@code catalog-snapshot} so the client
     * applies it even while ignoring the live broadcast.
     */
    public String buildSnapshotMessage(Instant epoch) {
        return buildSnapshotMessage(epoch, 0);
    }

    /**
     * Snapshot with a caller-chosen window (seconds). The client widens the
     * window when playing forward from a traveled time so prefetch has headroom
     * at high rates; {@code 0} means the default broadcast window. Clamped to
     * {@link #MAX_SNAPSHOT_WINDOW_SECONDS}.
     */
    public String buildSnapshotMessage(Instant epoch, int windowSeconds) {
        return buildCatalogMessage(epoch, tracked,
                space.orbit.backend.stream.StreamContract.MESSAGE_TYPE_CATALOG_SNAPSHOT, windowSeconds);
    }

    /** Polyline segments per orbital period (kept constant so longer paths stay as smooth). */
    private static final int ORBIT_SEGMENTS_PER_PERIOD = 180;
    /** Orbit path length, in orbital periods (slightly more than one loop). */
    private static final double ORBIT_PERIODS = 1.5;

    /** Orbit path from server "now" (convenience for tests/diagnostics). */
    public String buildOrbitMessage(int noradId) {
        return buildOrbitMessage(noradId, null);
    }

    /**
     * Orbit path for one satellite: one orbital period of ECEF positions from
     * {@code epoch} (or "now" if null), as a flat {@code [X,Y,Z, ...]} array
     * (click-to-toggle dashed polyline). The client re-requests at the current
     * clock as time advances to keep the path live. Returns {@code null} if the
     * NORAD id isn't in the catalog.
     */
    public String buildOrbitMessage(int noradId, Instant epoch) {
        TrackedSatellite sat = trackedByNorad.get(noradId);
        if (sat == null) {
            return null;
        }
        double periodSec = Math.max(60.0, sat.periodMinutes() * 60.0);
        int segments = (int) Math.round(ORBIT_SEGMENTS_PER_PERIOD * ORBIT_PERIODS);
        double step = (periodSec * ORBIT_PERIODS) / segments; // ≈ periodSec / ORBIT_SEGMENTS_PER_PERIOD
        AbsoluteDate start = new AbsoluteDate(epoch != null ? epoch : Instant.now(), utc);
        double[] xyz = new double[(segments + 1) * 3];
        for (int k = 0; k <= segments; k++) {
            Vector3D ecef = propagator.ecefPosition(sat.propagator(), start.shiftedBy(k * step));
            int base = k * 3;
            xyz[base] = ecef.getX();
            xyz[base + 1] = ecef.getY();
            xyz[base + 2] = ecef.getZ();
        }
        return encoder.encodeOrbit(noradId, xyz);
    }

    /** Propagate every tracked satellite over the default window from {@code epoch}. */
    String buildCatalogMessage(Instant epoch, List<TrackedSatellite> snapshot, String messageType) {
        return buildCatalogMessage(epoch, snapshot, messageType, 0);
    }

    /**
     * Propagate every tracked satellite over a window from {@code epoch} and
     * encode one CZML message. {@code windowSecondsOverride <= 0} uses the
     * configured broadcast window; otherwise it's clamped to
     * {@code [step, MAX_SNAPSHOT_WINDOW_SECONDS]}.
     */
    String buildCatalogMessage(Instant epoch, List<TrackedSatellite> snapshot, String messageType,
                               int windowSecondsOverride) {
        AbsoluteDate start = new AbsoluteDate(epoch, utc);
        int step = Math.max(1, props.stepSeconds());
        int window = windowSecondsOverride > 0
                ? Math.min(MAX_SNAPSHOT_WINDOW_SECONDS, Math.max(step, windowSecondsOverride))
                : Math.max(step, props.windowSeconds());
        int steps = window / step;

        List<CatalogSatelliteSamples> samples = snapshot.parallelStream()
                .map(sat -> sampleSatellite(sat, start, step, steps))
                .filter(java.util.Objects::nonNull)
                .toList();

        return encoder.encodeCatalog(epoch, samples, messageType);
    }

    private CatalogSatelliteSamples sampleSatellite(TrackedSatellite sat, AbsoluteDate start, int step, int steps) {
        try {
            double[] cartesian = new double[(steps + 1) * 4];
            for (int k = 0; k <= steps; k++) {
                double t = (double) k * step;
                AbsoluteDate date = start.shiftedBy(t);
                Vector3D ecef = propagator.ecefPosition(sat.propagator(), date);
                int base = k * 4;
                cartesian[base] = t;
                cartesian[base + 1] = ecef.getX();
                cartesian[base + 2] = ecef.getY();
                cartesian[base + 3] = ecef.getZ();
            }
            return new CatalogSatelliteSamples(
                    sat.noradId(), sat.name(), sat.inclinationDeg(), sat.periodMinutes(), cartesian);
        } catch (RuntimeException e) {
            // A satellite that fails to propagate this pass is simply omitted.
            return null;
        }
    }

    /** Tracked-satellite count (for tests / diagnostics). */
    public int size() {
        return tracked.size();
    }

    /**
     * Frozen TLE snapshot for a NORAD id, if it is in the current catalog. The
     * scenario composer uses this to capture a reproducible initial state for a
     * clicked satellite (Phase 3A). Empty for an id absent from the in-memory
     * catalog — the caller turns that into a clear 422.
     */
    public Optional<TleSnapshot> findSnapshot(int noradId) {
        return Optional.ofNullable(snapshotsByNorad.get(noradId));
    }

    /**
     * Resolve a NORAD id from a satellite display name (case-insensitive exact
     * match against the current catalog). Used by measured-data import to map a
     * WOD file's {@code Satellite:} name (e.g. {@code TELEOS-2}) to its catalog id;
     * empty if the name isn't in the in-memory catalog (the caller can fall back
     * to a user-supplied id).
     */
    public Optional<Integer> findNoradByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String target = name.trim();
        return snapshotsByNorad.values().stream()
                .filter(s -> s.name() != null && s.name().trim().equalsIgnoreCase(target))
                .map(TleSnapshot::noradId)
                .findFirst();
    }
}
