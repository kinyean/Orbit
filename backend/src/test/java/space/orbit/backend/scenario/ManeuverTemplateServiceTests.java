package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.orekit.propagation.analytical.tle.TLE;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.NumericalPropagation;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;

/**
 * {@link ManeuverTemplateService} (Phase 5C). Real prop/frame stack + the Orekit
 * data bundle; {@link ScenarioService} is mocked so the template's computed
 * impulses are captured at the {@code addManeuvers} seam without a DB.
 */
class ManeuverTemplateServiceTests {

    private static final UUID ID = UUID.randomUUID();

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    private static TLE leoTle(int norad, String name, double meanAnomalyDeg) {
        TleFactory factory = new TleFactory();
        factory.init();
        GpRecord r = new GpRecord(
                name, "1998-067A", "2024-06-01T12:00:00.000",
                15.50125000, 0.0006703, 51.6416, 247.4627, 130.5360, meanAnomalyDeg,
                norad, 999, 45000, 0.00010270, "U", 0);
        return factory.fromGp(r);
    }

    private static ScenarioBody.Role role(String roleName, TLE tle, String name) {
        return new ScenarioBody.Role(roleName, tle.getSatelliteNumber(), name,
                new ScenarioBody.InitialState("tle",
                        new ScenarioBody.Tle(tle.getLine1(), tle.getLine2(), tle.getDate().toString())));
    }

    private static ScenarioBody body(String start, String end) {
        ScenarioBody.Role chief = role("chief", leoTle(25544, "ISS (ZARYA)", 325.0), "ISS (ZARYA)");
        ScenarioBody.Role deputy = role("deputy", leoTle(25545, "DEPUTY-1", 5.0), "DEPUTY-1");
        return new ScenarioBody(2, "sgp4", new ScenarioBody.TimeRange(start, end), chief, List.of(deputy));
    }

    /** A close formation (~few km in-track) so CW templates are in their valid regime. */
    private static ScenarioBody closeBody(String start, String end) {
        ScenarioBody.Role chief = role("chief", leoTle(25544, "ISS (ZARYA)", 0.0), "ISS (ZARYA)");
        ScenarioBody.Role deputy = role("deputy", leoTle(25545, "DEPUTY-1", 0.03), "DEPUTY-1");
        return new ScenarioBody(2, "sgp4", new ScenarioBody.TimeRange(start, end), chief, List.of(deputy));
    }

    private static ManeuverTemplateService service(ScenarioService scenarioService) {
        FrameService frames = new FrameService();
        frames.init();
        PropagationService prop = new PropagationService(
                new SatellitePropagator(frames), new NumericalPropagation(frames), frames);
        ManeuverTemplateService svc = new ManeuverTemplateService(
                scenarioService, prop, frames, new RendezvousCorrector(prop, frames));
        svc.init();
        return svc;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ManeuverDraft>> captureDrafts(ScenarioService mock) {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void hohmannInsertsTwoProgradeImpulses() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        ScenarioBody b = body("2024-06-01T12:00:00Z", "2024-06-01T16:00:00Z");
        when(scenarioService.get(any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 1, 1, b));
        when(scenarioService.addManeuvers(eq(ID), any(), any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 2, 2, b));

        service(scenarioService).hohmann(ID, 25545, 800.0); // raise to 800 km

        ArgumentCaptor<List<ManeuverDraft>> cap = captureDrafts(scenarioService);
        org.mockito.Mockito.verify(scenarioService).addManeuvers(eq(ID), cap.capture(), any());
        List<ManeuverDraft> drafts = cap.getValue();
        assertThat(drafts).hasSize(2);
        // Both prograde (in-track positive) for a raise; radial/cross ≈ 0.
        assertThat(drafts.get(0).i()).isGreaterThan(0.0);
        assertThat(drafts.get(1).i()).isGreaterThan(0.0);
        assertThat(drafts.get(0).r()).isZero();
        assertThat(drafts.get(0).c()).isZero();
        // Total Δv is a sane LEO value (tens–hundreds of m/s), and burn 2 follows burn 1.
        double total = drafts.get(0).i() + drafts.get(1).i();
        assertThat(total).isBetween(10.0, 1000.0);
        assertThat(Instant.parse(drafts.get(1).epoch())).isAfter(Instant.parse(drafts.get(0).epoch()));
    }

    @Test
    void hohmannRejectsSubAtmosphericTarget() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        ScenarioBody b = body("2024-06-01T12:00:00Z", "2024-06-01T16:00:00Z");
        when(scenarioService.get(any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 1, 1, b));

        // 2 km is an absolute altitude deep in the atmosphere → rejected, not inserted.
        assertThatThrownBy(() -> service(scenarioService).hohmann(ID, 25545, 2.0))
                .isInstanceOf(ScenarioValidationException.class)
                .hasMessageContaining("re-enter");
        org.mockito.Mockito.verify(scenarioService, org.mockito.Mockito.never())
                .addManeuvers(any(), any(), any());
    }

    @Test
    void rendezvousInsertsTwoFiniteImpulsesAtDepartureAndArrival() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        String start = "2024-06-01T12:00:00Z";
        String arrival = "2024-06-01T12:40:00Z";
        ScenarioBody b = body(start, "2024-06-01T16:00:00Z");
        when(scenarioService.get(any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 1, 1, b));
        when(scenarioService.addManeuvers(eq(ID), any(), any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 2, 2, b));

        // Pure Lambert seed path (no corrector) — fast, behavior unchanged from Phase 5C.
        service(scenarioService).rendezvous(ID, 25545, arrival, false, null);

        ArgumentCaptor<List<ManeuverDraft>> cap = captureDrafts(scenarioService);
        org.mockito.Mockito.verify(scenarioService).addManeuvers(eq(ID), cap.capture(), any());
        List<ManeuverDraft> drafts = cap.getValue();
        assertThat(drafts).hasSize(2);
        for (ManeuverDraft d : drafts) {
            assertThat(Double.isFinite(d.r()) && Double.isFinite(d.i()) && Double.isFinite(d.c())).isTrue();
        }
        assertThat(drafts.get(0).epoch()).isEqualTo(start);
        assertThat(Instant.parse(drafts.get(1).epoch())).isEqualTo(Instant.parse(arrival));
    }

    @Test
    void nmcInsertsOneInTrackDriftCancelBurn() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        ScenarioBody b = closeBody("2024-06-01T12:00:00Z", "2024-06-01T16:00:00Z");
        when(scenarioService.get(any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 1, 1, b));
        when(scenarioService.addManeuvers(eq(ID), any(), any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 2, 2, b));

        service(scenarioService).nmc(ID, 25545);

        ArgumentCaptor<List<ManeuverDraft>> cap = captureDrafts(scenarioService);
        org.mockito.Mockito.verify(scenarioService).addManeuvers(eq(ID), cap.capture(), any());
        List<ManeuverDraft> drafts = cap.getValue();
        assertThat(drafts).hasSize(1);
        // The NMC drift-cancel burn is purely in-track.
        assertThat(drafts.get(0).r()).isZero();
        assertThat(drafts.get(0).c()).isZero();
        assertThat(Math.abs(drafts.get(0).i())).isGreaterThan(0.0);
    }

    @Test
    void holdInsertsTwoBurnsAtStartAndArrival() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        String start = "2024-06-01T12:00:00Z";
        String arrival = "2024-06-01T12:23:00Z"; // ~1380 s, ~quarter period (CW-nonsingular)
        ScenarioBody b = closeBody(start, "2024-06-01T16:00:00Z");
        when(scenarioService.get(any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 1, 1, b));
        when(scenarioService.addManeuvers(eq(ID), any(), any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 2, 2, b));

        service(scenarioService).hold(ID, 25545, "vbar", 1000.0, arrival);

        ArgumentCaptor<List<ManeuverDraft>> cap = captureDrafts(scenarioService);
        org.mockito.Mockito.verify(scenarioService).addManeuvers(eq(ID), cap.capture(), any());
        List<ManeuverDraft> drafts = cap.getValue();
        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).epoch()).isEqualTo(start);
        assertThat(Instant.parse(drafts.get(1).epoch())).isEqualTo(Instant.parse(arrival));
        for (ManeuverDraft d : drafts) {
            assertThat(Double.isFinite(d.r()) && Double.isFinite(d.i()) && Double.isFinite(d.c())).isTrue();
        }
    }

    @Test
    void glideslopeInsertsAChainEndingInAParkBurn() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        String start = "2024-06-01T12:00:00Z";
        ScenarioBody b = closeBody(start, "2024-06-01T16:00:00Z");
        when(scenarioService.get(any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 1, 1, b));
        when(scenarioService.addManeuvers(eq(ID), any(), any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 2, 2, b));

        // V-bar approach 1000 → 100 m at 2 m/s in 4 segments.
        service(scenarioService).glideslope(ID, 25545, "vbar", 1000.0, 100.0, 2.0, 4);

        ArgumentCaptor<List<ManeuverDraft>> cap = captureDrafts(scenarioService);
        org.mockito.Mockito.verify(scenarioService).addManeuvers(eq(ID), cap.capture(), any());
        List<ManeuverDraft> drafts = cap.getValue();
        // 4 segments → 5 transfer legs (acquire + 4) + 1 final park burn.
        assertThat(drafts).hasSize(6);
        assertThat(drafts.get(0).epoch()).isEqualTo(start);
        for (int k = 1; k < drafts.size(); k++) {
            assertThat(Instant.parse(drafts.get(k).epoch()))
                    .as("epochs strictly increasing along the glideslope")
                    .isAfter(Instant.parse(drafts.get(k - 1).epoch()));
        }
        for (ManeuverDraft d : drafts) {
            assertThat(Double.isFinite(d.r()) && Double.isFinite(d.i()) && Double.isFinite(d.c())).isTrue();
        }
    }

    @Test
    void glideslopeRejectsNonClosingRanges() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        ScenarioBody b = closeBody("2024-06-01T12:00:00Z", "2024-06-01T16:00:00Z");
        when(scenarioService.get(any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 1, 1, b));

        // end range ≥ start range is not "closing in" → rejected, nothing inserted.
        assertThatThrownBy(() -> service(scenarioService).glideslope(ID, 25545, "vbar", 100.0, 1000.0, 2.0, 4))
                .isInstanceOf(ScenarioValidationException.class)
                .hasMessageContaining("closing in");
        org.mockito.Mockito.verify(scenarioService, org.mockito.Mockito.never())
                .addManeuvers(any(), any(), any());
    }

    @Test
    void stationKeepInsertsOneCorrectiveBurnPerInterval() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        String start = "2024-06-01T12:00:00Z";
        ScenarioBody b = closeBody(start, "2024-06-01T16:00:00Z");
        when(scenarioService.get(any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 1, 1, b));
        when(scenarioService.addManeuvers(eq(ID), any(), any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 2, 2, b));

        // Hold a V-bar point at 800 m: one corrective burn every 1200 s, 4 corrections.
        service(scenarioService).stationKeep(ID, 25545, "vbar", 800.0, 1200.0, 4);

        ArgumentCaptor<List<ManeuverDraft>> cap = captureDrafts(scenarioService);
        org.mockito.Mockito.verify(scenarioService).addManeuvers(eq(ID), cap.capture(), any());
        List<ManeuverDraft> drafts = cap.getValue();
        assertThat(drafts).hasSize(4);
        assertThat(drafts.get(0).epoch()).isEqualTo(start);
        // Corrections are spaced one interval apart and all finite.
        for (int k = 1; k < drafts.size(); k++) {
            double gap = Instant.parse(drafts.get(k).epoch()).getEpochSecond()
                    - Instant.parse(drafts.get(k - 1).epoch()).getEpochSecond();
            assertThat(gap).isEqualTo(1200.0);
        }
        for (ManeuverDraft d : drafts) {
            assertThat(Double.isFinite(d.r()) && Double.isFinite(d.i()) && Double.isFinite(d.c())).isTrue();
        }
    }

    @Test
    void stationKeepRejectsAnIntervalThatOverrunsTheWindow() {
        ScenarioService scenarioService = mock(ScenarioService.class);
        // A 30-minute window but a 1-hour interval → no full interval fits → rejected.
        ScenarioBody b = closeBody("2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        when(scenarioService.get(any())).thenReturn(
                new ScenarioResponse(ID.toString(), "S", "o", "2024-06-01T00:00:00Z", 1, 1, b));

        assertThatThrownBy(() -> service(scenarioService).stationKeep(ID, 25545, "vbar", 800.0, 3600.0, 4))
                .isInstanceOf(ScenarioValidationException.class)
                .hasMessageContaining("no corrections fit");
        org.mockito.Mockito.verify(scenarioService, org.mockito.Mockito.never())
                .addManeuvers(any(), any(), any());
    }
}
