package space.orbit.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.io.MeasuredEphemeris;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.NumericalPropagation;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.scenario.ChiefStateResolver;
import space.orbit.backend.scenario.MeasuredDataset;
import space.orbit.backend.scenario.MeasuredDatasetCodec;
import space.orbit.backend.scenario.MeasuredDatasetRepository;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioResponse;
import space.orbit.backend.scenario.ScenarioService;
import space.orbit.backend.scenario.ScenarioValidationException;

/**
 * {@link MonteCarloService} (Phase 9C, UC-6). Real prop/frame stack + the Orekit data
 * bundle; {@link ScenarioService#get} mocked to a planted chief+deputy. Proves the
 * reproducibility posture under the codebase's first RNG (SRS §5.4.1): same {@code
 * (scenario, seed, params)} → byte-identical output even though samples run in parallel
 * (so order-independence holds); a zero-uncertainty run collapses to the nominal; and the
 * covariance recovers a known input σ.
 */
class MonteCarloServiceTests {

    private static final UUID ID = UUID.randomUUID();
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

    private static TLE leoTle(int norad, double meanAnomalyDeg) {
        GpRecord r = new GpRecord("SAT-" + norad, "1998-067A", "2024-06-01T11:00:00.000",
                15.50125000, 0.0006703, 51.6416, 247.4627, 130.5360, meanAnomalyDeg,
                norad, 999, 45000, 0.00010270, "U", 0);
        return TLE_FACTORY.fromGp(r);
    }

    private static ScenarioBody.Role role(String name, TLE tle, String display) {
        return new ScenarioBody.Role(name, tle.getSatelliteNumber(), display,
                new ScenarioBody.InitialState("tle",
                        new ScenarioBody.Tle(tle.getLine1(), tle.getLine2(), tle.getDate().toString())));
    }

    private static ScenarioBody body() {
        return new ScenarioBody(5, "sgp4",
                new ScenarioBody.TimeRange("2024-06-01T12:00:00Z", "2024-06-01T12:04:00Z"),
                role("chief", leoTle(25544, 0.0), "CHIEF"),
                List.of(role("deputy", leoTle(25545, 0.1), "DEPUTY")));
    }

    private static MonteCarloService service() {
        return service(body(), mock(MeasuredDatasetRepository.class));
    }

    private static MonteCarloService service(ScenarioBody body, MeasuredDatasetRepository datasets) {
        FrameService frames = frames();
        PropagationService prop = new PropagationService(
                new SatellitePropagator(frames), new NumericalPropagation(frames), frames);
        ScenarioService scenarioService = mock(ScenarioService.class);
        when(scenarioService.get(any())).thenReturn(new ScenarioResponse(
                ID.toString(), "S", UUID.randomUUID().toString(), null, 1, 1, body));
        ChiefStateResolver chiefResolver = new ChiefStateResolver(prop, frames, datasets);
        chiefResolver.init();
        MonteCarloService svc = new MonteCarloService(scenarioService, prop, frames, chiefResolver);
        svc.init();
        return svc;
    }

    @Test
    void sameSeedIsByteIdentical() {
        MonteCarloService.Params params = new MonteCarloService.Params(16, 12345L, 100.0, 0.1, 0.0, 0.0);
        MonteCarloResult a = service().analyze(ID, 25545, params);
        MonteCarloResult b = service().analyze(ID, 25545, params);

        assertThat(b.ellipsoids()).hasSameSizeAs(a.ellipsoids());
        for (int k = 0; k < a.ellipsoids().size(); k++) {
            EllipsoidSample ea = a.ellipsoids().get(k);
            EllipsoidSample eb = b.ellipsoids().get(k);
            for (int j = 0; j < 3; j++) {
                assertThat(Double.doubleToLongBits(eb.center()[j]))
                        .isEqualTo(Double.doubleToLongBits(ea.center()[j]));
                assertThat(Double.doubleToLongBits(eb.semiAxes1Sigma()[j]))
                        .isEqualTo(Double.doubleToLongBits(ea.semiAxes1Sigma()[j]));
            }
        }
        // And the drawn spaghetti is identical too.
        assertThat(b.tracks()).hasSameSizeAs(a.tracks());
        assertThat(Double.doubleToLongBits(b.tracks().get(0)[0]))
                .isEqualTo(Double.doubleToLongBits(a.tracks().get(0)[0]));
    }

    @Test
    void zeroUncertaintyCollapsesToTheNominal() {
        MonteCarloResult r = service().analyze(ID, 25545,
                new MonteCarloService.Params(40, 7L, 0.0, 0.0, 0.0, 0.0));
        // No dispersion → every ellipsoid is a point (semi-axes ~0).
        for (EllipsoidSample e : r.ellipsoids()) {
            for (int j = 0; j < 3; j++) {
                assertThat(e.semiAxes1Sigma()[j]).isLessThan(1.0e-3);
            }
        }
    }

    @Test
    void recoversInitialPositionSigma() {
        // Position-only σ = 100 m. At t=0 the relative-position spread is that isotropic
        // 100 m (a rotation preserves it), so each 1-σ semi-axis ≈ 100 m.
        MonteCarloResult r = service().analyze(ID, 25545,
                new MonteCarloService.Params(50, 99L, 100.0, 0.0, 0.0, 0.0));
        EllipsoidSample t0 = r.ellipsoids().get(0);
        for (int j = 0; j < 3; j++) {
            assertThat(t0.semiAxes1Sigma()[j]).isBetween(55.0, 150.0);
        }
        assertThat(r.seed()).isEqualTo(99L);
        assertThat(r.returnedTracks()).isLessThanOrEqualTo(200);
    }

    // --- measured-ephemeris chief (the chief is only the LVLH reference) -------------

    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final long MEASURED_START_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long MEASURED_STEP_MS = 300_000L; // 5-min nodes, like the WOD data
    private static final int MEASURED_NODES = 5;           // 20-min span keeps the numerical samples cheap

    private static MeasuredDatasetRepository measuredRepo() {
        List<MeasuredEphemeris.Sample> samples =
                circularLeoSamples(MEASURED_START_MS, MEASURED_STEP_MS, MEASURED_NODES);
        OffsetDateTime startUtc = OffsetDateTime.ofInstant(Instant.ofEpochMilli(MEASURED_START_MS), ZoneOffset.UTC);
        OffsetDateTime endUtc = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(MEASURED_START_MS + (MEASURED_NODES - 1) * MEASURED_STEP_MS), ZoneOffset.UTC);
        MeasuredDataset ds = new MeasuredDataset(DATASET_ID, UUID.randomUUID(), "TELEOS-2-LIKE", 56310,
                "EME2000", startUtc, endUtc, samples.size(), "test.csv", "hash",
                MeasuredDatasetCodec.encode(samples));
        MeasuredDatasetRepository repo = mock(MeasuredDatasetRepository.class);
        when(repo.findById(DATASET_ID)).thenReturn(Optional.of(ds));
        return repo;
    }

    /** Measured chief + a TLE deputy over the given window (dataset span ends at 00:20). */
    private static ScenarioBody measuredChiefBody(String end) {
        ScenarioBody.Role chief = new ScenarioBody.Role("chief", 56310, "TELEOS-2-LIKE",
                new ScenarioBody.InitialState("ephemeris", null, DATASET_ID.toString()));
        return new ScenarioBody(5, "sgp4",
                new ScenarioBody.TimeRange("2026-01-01T00:00:00Z", end),
                chief, List.of(role("deputy", leoTle(25545, 0.1), "DEPUTY")));
    }

    /** A short circular-LEO arc of measured samples (mirrors the stream-test fixture). */
    private static List<MeasuredEphemeris.Sample> circularLeoSamples(long startMs, long stepMs, int nodes) {
        double mu = 3.986004418e14;
        double r = 6_953_000.0;       // ~575 km
        double v = Math.sqrt(mu / r); // circular speed
        double n = v / r;             // mean motion (rad/s)
        List<MeasuredEphemeris.Sample> out = new ArrayList<>(nodes);
        for (int k = 0; k < nodes; k++) {
            double t = k * (stepMs / 1000.0);
            double th = n * t;
            out.add(new MeasuredEphemeris.Sample(startMs + k * stepMs,
                    r * Math.cos(th), r * Math.sin(th), 0.0,
                    -v * Math.sin(th), v * Math.cos(th), 0.0));
        }
        return out;
    }

    @Test
    void measuredChiefIsSupported() {
        // A measured (tabulated-ephemeris) chief resolves through ChiefStateResolver, and
        // the R11 posture extends to it: same (scenario, seed, params) → byte-identical.
        MonteCarloService.Params params = new MonteCarloService.Params(8, 4242L, 50.0, 0.05, 0.0, 0.0);
        ScenarioBody body = measuredChiefBody("2026-01-01T00:20:00Z");
        MonteCarloResult a = service(body, measuredRepo()).analyze(ID, 25545, params);
        MonteCarloResult b = service(body, measuredRepo()).analyze(ID, 25545, params);

        assertThat(a.tracks()).isNotEmpty();
        assertThat(a.ellipsoids()).isNotEmpty();
        for (double[] track : a.tracks()) {
            for (double value : track) {
                assertThat(Double.isFinite(value)).isTrue();
            }
        }
        assertThat(b.tracks()).hasSameSizeAs(a.tracks());
        for (int i = 0; i < a.tracks().size(); i++) {
            double[] ta = a.tracks().get(i);
            double[] tb = b.tracks().get(i);
            assertThat(tb).hasSameSizeAs(ta);
            for (int j = 0; j < ta.length; j++) {
                assertThat(Double.doubleToLongBits(tb[j])).isEqualTo(Double.doubleToLongBits(ta[j]));
            }
        }
    }

    @Test
    void measuredChiefWindowBeyondSpanIs422() {
        // The dataset ends at 00:20 but the window runs to 01:00 — the tabulated Ephemeris
        // throws past its span, which must surface as user-fixable validation, not a 500.
        ScenarioBody body = measuredChiefBody("2026-01-01T01:00:00Z");
        assertThatThrownBy(() -> service(body, measuredRepo()).analyze(ID, 25545,
                new MonteCarloService.Params(4, 1L, 0.0, 0.0, 0.0, 0.0)))
                .isInstanceOf(ScenarioValidationException.class)
                .hasMessageContaining("does not cover the scenario window");
    }

    @Test
    void measuredDeputyIsRejected() {
        // Dispersion stays TLE-only: perturbing measured truth is meaningless.
        ScenarioBody.Role measuredDeputy = new ScenarioBody.Role("deputy", 56310, "TELEOS-2-LIKE",
                new ScenarioBody.InitialState("ephemeris", null, DATASET_ID.toString()));
        ScenarioBody body = new ScenarioBody(5, "sgp4",
                new ScenarioBody.TimeRange("2024-06-01T12:00:00Z", "2024-06-01T12:04:00Z"),
                role("chief", leoTle(25544, 0.0), "CHIEF"), List.of(measuredDeputy));
        assertThatThrownBy(() -> service(body, measuredRepo()).analyze(ID, 56310,
                new MonteCarloService.Params(4, 1L, 0.0, 0.0, 0.0, 0.0)))
                .isInstanceOf(ScenarioValidationException.class)
                .hasMessageContaining("TLE");
    }
}
