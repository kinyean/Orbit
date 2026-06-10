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
        String message = buildCatalogMessage(Instant.now(), snapshot);
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
                built.add(new TrackedSatellite(r.noradId(), r.objectName(), inclinationDeg, periodMinutes, prop));
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
        log.info("Catalog loaded from {}: {} satellites ({} skipped)", origin, built.size(), failed);
    }

    /** Build a message for the current tracked set (convenience for tests/diagnostics). */
    String buildCatalogMessage(Instant now) {
        return buildCatalogMessage(now, tracked);
    }

    /** Propagate every tracked satellite over the window and encode one CZML message. */
    String buildCatalogMessage(Instant now, List<TrackedSatellite> snapshot) {
        AbsoluteDate start = new AbsoluteDate(now, utc);
        int step = Math.max(1, props.stepSeconds());
        int window = Math.max(step, props.windowSeconds());
        int steps = window / step;

        List<CatalogSatelliteSamples> samples = snapshot.parallelStream()
                .map(sat -> sampleSatellite(sat, start, step, steps))
                .filter(java.util.Objects::nonNull)
                .toList();

        return encoder.encodeCatalog(now, samples);
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
}
