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
 * {@link RendezvousSearchService} behaviour (Phase 9A, US-MAN-03). Real prop/frame
 * stack + the Orekit data bundle; {@link ScenarioService#get} mocked to a planted
 * co-orbital chief+deputy. Asserts the ΔV grid is populated, sorted cheapest-first,
 * {@code cheapest} is the global minimum, and the (parallel) result is deterministic.
 */
class RendezvousSearchServiceTests {

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
        GpRecord r = new GpRecord("SAT-" + norad, "1998-067A", "2024-06-01T12:00:00.000",
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
                new ScenarioBody.TimeRange("2024-06-01T12:00:00Z", "2024-06-01T15:00:00Z"),
                role("chief", leoTle(25544, 12.0), "CHIEF"),
                List.of(role("deputy", leoTle(25545, 0.0), "DEPUTY")));
    }

    private static RendezvousSearchService service() {
        FrameService frames = frames();
        PropagationService prop = new PropagationService(
                new SatellitePropagator(frames), new NumericalPropagation(frames), frames);
        ScenarioService scenarioService = mock(ScenarioService.class);
        when(scenarioService.get(any())).thenReturn(new ScenarioResponse(
                ID.toString(), "S", UUID.randomUUID().toString(), null, 1, 1, body()));
        RendezvousSearchService svc = new RendezvousSearchService(scenarioService, prop, frames);
        svc.init();
        return svc;
    }

    @Test
    void buildsSortedGridWithCheapestAsGlobalMin() {
        RendezvousSearchResult res = service().search(ID, 25545);

        assertThat(res.cells()).isNotEmpty();
        assertThat(res.cheapest()).isNotNull();
        // Sorted ascending by total ΔV.
        for (int i = 1; i < res.cells().size(); i++) {
            assertThat(res.cells().get(i).totalDvMs())
                    .isGreaterThanOrEqualTo(res.cells().get(i - 1).totalDvMs());
        }
        // cheapest is the global minimum.
        double min = res.cells().stream().mapToDouble(DvCell::totalDvMs).min().orElseThrow();
        assertThat(res.cheapest().totalDvMs()).isEqualTo(min);
        assertThat(res.cheapest()).isEqualTo(res.cells().get(0));
    }

    @Test
    void resultIsDeterministic() {
        RendezvousSearchResult a = service().search(ID, 25545);
        RendezvousSearchResult b = service().search(ID, 25545);
        assertThat(b.cells()).hasSameSizeAs(a.cells());
        for (int i = 0; i < a.cells().size(); i++) {
            assertThat(Double.doubleToLongBits(b.cells().get(i).totalDvMs()))
                    .isEqualTo(Double.doubleToLongBits(a.cells().get(i).totalDvMs()));
            assertThat(b.cells().get(i).nRev()).isEqualTo(a.cells().get(i).nRev());
            assertThat(b.cells().get(i).arrivalEpoch()).isEqualTo(a.cells().get(i).arrivalEpoch());
        }
    }
}
