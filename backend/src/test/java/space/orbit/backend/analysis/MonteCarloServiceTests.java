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
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.NumericalPropagation;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioResponse;
import space.orbit.backend.scenario.ScenarioService;

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
        FrameService frames = frames();
        PropagationService prop = new PropagationService(
                new SatellitePropagator(frames), new NumericalPropagation(frames), frames);
        ScenarioService scenarioService = mock(ScenarioService.class);
        when(scenarioService.get(any())).thenReturn(new ScenarioResponse(
                ID.toString(), "S", UUID.randomUUID().toString(), null, 1, 1, body()));
        MonteCarloService svc = new MonteCarloService(scenarioService, prop, frames);
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
}
