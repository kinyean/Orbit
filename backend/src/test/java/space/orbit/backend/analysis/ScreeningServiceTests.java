package space.orbit.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.utils.Constants;
import space.orbit.backend.catalog.CatalogService;
import space.orbit.backend.catalog.TrackedSatellite;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.NumericalPropagation;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.scenario.MeasuredDatasetRepository;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioResponse;
import space.orbit.backend.scenario.ScenarioService;

/**
 * {@link ScreeningService} behaviour (Phase 8, US-EVT-02 / UC-7). Uses the real
 * propagation/frame stack + the Orekit data bundle; {@link ScenarioService#get} and
 * {@link CatalogService#tracked} are mocked. A planted co-orbital "twin" (same plane,
 * slightly phased) makes a close pass; a planted GEO satellite is dropped by the
 * radial-shell prune.
 */
class ScreeningServiceTests {

    private static final UUID ID = UUID.randomUUID();
    private static final SatellitePropagator SAT_PROP = new SatellitePropagator(frames());
    private static final TleFactory TLE_FACTORY = tleFactory();

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    private static FrameService frames() {
        FrameService f = new FrameService();
        f.init();
        return f;
    }

    private static TleFactory tleFactory() {
        TleFactory f = new TleFactory();
        f.init();
        return f;
    }

    /** A LEO TLE (ISS-like) parameterised by NORAD id + mean anomaly (deg). */
    private static TLE leoTle(int norad, double meanAnomalyDeg) {
        GpRecord r = new GpRecord("SAT-" + norad, "1998-067A", "2024-06-01T12:00:00.000",
                15.50125000, 0.0006703, 51.6416, 247.4627, 130.5360, meanAnomalyDeg,
                norad, 999, 45000, 0.00010270, "U", 0);
        return TLE_FACTORY.fromGp(r);
    }

    /** A GEO TLE (very different shell → pruned). */
    private static TLE geoTle(int norad) {
        GpRecord r = new GpRecord("GEO-" + norad, "2000-000A", "2024-06-01T12:00:00.000",
                1.00270000, 0.0001, 0.05, 95.0, 270.0, 0.0,
                norad, 999, 45000, 0.0, "U", 0);
        return TLE_FACTORY.fromGp(r);
    }

    private static TrackedSatellite tracked(TLE tle) {
        double n = tle.getMeanMotion();
        double a = Math.cbrt(Constants.WGS84_EARTH_MU / (n * n));
        double e = tle.getE();
        return new TrackedSatellite(tle.getSatelliteNumber(), "SAT-" + tle.getSatelliteNumber(),
                Math.toDegrees(tle.getI()), 2.0 * Math.PI / n / 60.0, a * (1 - e), a * (1 + e),
                SAT_PROP.build(tle));
    }

    private static ScenarioBody chiefOnlyBody() {
        TLE chief = leoTle(25544, 0.0);
        ScenarioBody.Role role = new ScenarioBody.Role("chief", 25544, "ISS",
                new ScenarioBody.InitialState("tle",
                        new ScenarioBody.Tle(chief.getLine1(), chief.getLine2(), chief.getDate().toString())));
        return new ScenarioBody(5, "sgp4",
                new ScenarioBody.TimeRange("2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z"),
                role, List.of());
    }

    private static ScreeningService service(List<TrackedSatellite> catalogSet) {
        FrameService frames = frames();
        PropagationService prop = new PropagationService(
                new SatellitePropagator(frames), new NumericalPropagation(frames), frames);
        ScenarioService scenarioService = mock(ScenarioService.class);
        when(scenarioService.get(any())).thenReturn(new ScenarioResponse(
                ID.toString(), "S", UUID.randomUUID().toString(), null, 1, 1, chiefOnlyBody()));
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.tracked()).thenReturn(catalogSet);
        ScreeningService svc = new ScreeningService(
                scenarioService, prop, frames, catalog, mock(MeasuredDatasetRepository.class));
        svc.init();
        return svc;
    }

    @Test
    void findsCoOrbitalTwinAndPrunesFarShell() {
        // twin (≈0.03° phased) ≈ a few km; twin2 (≈0.2°) farther; geo pruned by the shell test.
        ScreeningService svc = service(List.of(
                tracked(leoTle(40001, 0.03)),
                tracked(leoTle(40002, 0.20)),
                tracked(geoTle(40003))));

        ScreeningResult res = svc.screen(ID, 50.0); // 50 km threshold

        assertThat(res.catalogSize()).isEqualTo(3);
        assertThat(res.candidateCount()).as("only the two co-shell LEO twins survive the prune").isEqualTo(2);
        assertThat(res.conjunctions()).hasSize(2);
        // Sorted ascending; the closer twin (smaller phase offset) is first.
        assertThat(res.conjunctions().get(0).missDistanceM())
                .isLessThanOrEqualTo(res.conjunctions().get(1).missDistanceM());
        assertThat(res.conjunctions().get(0).catalogNoradId()).isEqualTo(40001);
        assertThat(res.conjunctions().get(0).scenarioNoradId()).isEqualTo(25544);
        assertThat(res.conjunctions().get(0).missDistanceM()).isLessThan(50_000.0);
        // The GEO satellite is never reported.
        assertThat(res.conjunctions()).noneMatch(c -> c.catalogNoradId() == 40003);
    }

    @Test
    void tightThresholdDropsTheFartherTwin() {
        ScreeningService svc = service(List.of(
                tracked(leoTle(40001, 0.03)),
                tracked(leoTle(40002, 0.20))));
        // ~4 km vs ~25 km: a 10 km threshold keeps only the closer twin.
        ScreeningResult res = svc.screen(ID, 10.0);
        assertThat(res.conjunctions()).hasSize(1);
        assertThat(res.conjunctions().get(0).catalogNoradId()).isEqualTo(40001);
    }

    @Test
    void excludesScenarioCraftFromTheCatalog() {
        // A catalog entry with the chief's own id must not self-report.
        ScreeningService svc = service(List.of(tracked(leoTle(25544, 0.0))));
        ScreeningResult res = svc.screen(ID, 50.0);
        assertThat(res.conjunctions()).isEmpty();
    }
}
