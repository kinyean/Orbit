package space.orbit.backend.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.StringReader;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataSource;
import org.orekit.files.ccsds.ndm.ParserBuilder;
import org.orekit.files.ccsds.ndm.odm.oem.Oem;
import org.orekit.files.ccsds.ndm.odm.oem.OemSatelliteEphemeris;
import org.orekit.files.ccsds.ndm.odm.oem.OemSegment;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.TimeStampedPVCoordinates;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.NumericalPropagation;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.scenario.MeasuredDatasetRepository;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioService;
import space.orbit.backend.stream.ScenarioStreamProperties;

/**
 * {@link OemExportService} behaviour (Phase 11, US-IO-06 / SRS §4.2.1). Uses the real
 * propagation/frame stack + the Orekit data bundle; {@link ScenarioService} is mocked
 * (ownership gating is its own tested concern). The written KVN is <b>round-tripped
 * through Orekit's OemParser</b> and compared against independently built propagators —
 * so the file provably carries the real trajectories, in the declared frame/time system.
 */
class OemExportServiceTests {

    private static final UUID ID = UUID.randomUUID();
    private static final TleFactory TLE_FACTORY = tleFactory();
    private static final String START = "2024-06-01T12:00:00Z";
    private static final String END = "2024-06-01T13:30:00Z";

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

    private static ScenarioBody.Role role(String roleName, TLE tle, String name,
                                          List<ScenarioBody.Maneuver> maneuvers) {
        return new ScenarioBody.Role(roleName, tle.getSatelliteNumber(), name,
                new ScenarioBody.InitialState("tle",
                        new ScenarioBody.Tle(tle.getLine1(), tle.getLine2(), tle.getDate().toString())),
                maneuvers, List.of(), null, List.of());
    }

    private static ScenarioBody body(List<ScenarioBody.Maneuver> deputyManeuvers) {
        return new ScenarioBody(6, "sgp4", new ScenarioBody.TimeRange(START, END),
                role("chief", leoTle(25544, 0.0), "ISS", List.of()),
                List.of(role("deputy", leoTle(33591, 0.05), "TWIN", deputyManeuvers)));
    }

    private record Fixture(OemExportService service, ScenarioService scenarios,
                           PropagationService prop, FrameService frames) {}

    private static Fixture fixture(ScenarioBody body) {
        FrameService frames = frames();
        PropagationService prop = new PropagationService(
                new SatellitePropagator(frames), new NumericalPropagation(frames), frames);
        ScenarioService scenarioService = mock(ScenarioService.class);
        when(scenarioService.exportView(any())).thenReturn(new ScenarioService.ExportView(
                "Test Scenario — OEM", body, 3, OffsetDateTime.parse("2024-06-01T00:00:00Z")));
        OemExportService svc = new OemExportService(scenarioService, prop, frames,
                mock(MeasuredDatasetRepository.class),
                new ScenarioStreamProperties(30, 200, true, true));
        svc.init();
        return new Fixture(svc, scenarioService, prop, frames);
    }

    private static Oem parse(String content) {
        return new ParserBuilder().buildOemParser()
                .parse(new DataSource("test.oem", () -> new StringReader(content)));
    }

    @Test
    void roundTripsThroughOrekitParserWithBothCraft() {
        Fixture fx = fixture(body(List.of()));
        OemExportService.OemExport export = fx.service().export(ID);

        assertThat(export.fileName()).isEqualTo("test-scenario-oem.oem");
        assertThat(export.content()).contains("CCSDS_OEM_VERS");

        Oem oem = parse(export.content());
        assertThat(oem.getSatellites()).containsKeys("25544", "33591");

        OemSatelliteEphemeris chief = oem.getSatellites().get("25544");
        OemSegment segment = chief.getSegments().get(0);
        assertThat(segment.getMetadata().getObjectName()).isEqualTo("ISS");
        assertThat(segment.getFrame().getName()).isEqualTo(fx.frames().eci().getName());
        assertThat(segment.getMetadata().getTimeSystem().name()).isEqualTo("UTC");

        // Window coverage: [start, end] on the grid.
        AbsoluteDate start = new AbsoluteDate("2024-06-01T12:00:00.000", TimeScalesFactory.getUTC());
        assertThat(segment.getStart().durationFrom(start)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(segment.getStop().durationFrom(start)).isCloseTo(90.0 * 60.0, org.assertj.core.data.Offset.offset(60.0));

        // A mid-grid parsed state matches an independently built SGP4 propagator < 1 m.
        Propagator sgp4 = fx.prop().propagatorFor(leoTle(25544, 0.0), Fidelity.SGP4);
        TimeStampedPVCoordinates parsed = segment.getCoordinates().get(10);
        Vector3D expected = sgp4.getPVCoordinates(parsed.getDate(), fx.frames().eci()).getPosition();
        assertThat(Vector3D.distance(parsed.getPosition(), expected)).isLessThan(1.0);
    }

    @Test
    void byteIdenticalOnRerun() {
        Fixture fx = fixture(body(List.of()));
        String first = fx.service().export(ID).content();
        String second = fx.service().export(ID).content();
        assertThat(second).isEqualTo(first); // R11: deterministic grid + pinned creation date
    }

    @Test
    void maneuveredDeputyExportsTheManeuveredTrajectory() {
        // A 5 m/s prograde burn 20 min in: the exported deputy states must follow the
        // maneuvered numerical propagation, not the raw SGP4 track.
        String burnEpoch = "2024-06-01T12:20:00Z";
        ScenarioBody.Maneuver burn = new ScenarioBody.Maneuver(
                "m1", "delta-v", burnEpoch, "ric", new ScenarioBody.DeltaV(0.0, 5.0, 0.0),
                null, null);
        Fixture fx = fixture(body(List.of(burn)));

        Oem oem = parse(fx.service().export(ID).content());
        OemSegment dep = oem.getSatellites().get("33591").getSegments().get(0);
        List<TimeStampedPVCoordinates> coords = dep.getCoordinates();
        TimeStampedPVCoordinates late = coords.get(coords.size() - 1);

        TLE depTle = leoTle(33591, 0.05);
        AbsoluteDate epoch = new AbsoluteDate("2024-06-01T12:20:00.000", TimeScalesFactory.getUTC());
        Propagator maneuvered = fx.prop().propagatorFor(depTle, Fidelity.SGP4,
                List.of(new Impulse(epoch, 0.0, 5.0, 0.0, null, null)));
        Vector3D expected = maneuvered.getPVCoordinates(late.getDate(), fx.frames().eci()).getPosition();
        assertThat(Vector3D.distance(late.getPosition(), expected)).isLessThan(1.0);

        Propagator raw = fx.prop().propagatorFor(depTle, Fidelity.SGP4);
        Vector3D unmaneuvered = raw.getPVCoordinates(late.getDate(), fx.frames().eci()).getPosition();
        assertThat(Vector3D.distance(late.getPosition(), unmaneuvered))
                .as("post-burn track must differ from the raw SGP4 track")
                .isGreaterThan(1000.0);
    }

    /** A LEO TLE whose epoch is explicit (so it can sit before the scenario window). */
    private static TLE leoTleAtEpoch(int norad, double meanAnomalyDeg, String epochIso) {
        GpRecord r = new GpRecord("SAT-" + norad, "1998-067A", epochIso,
                15.50125000, 0.0006703, 51.6416, 247.4627, 130.5360, meanAnomalyDeg,
                norad, 999, 45000, 0.00010270, "U", 0);
        return TLE_FACTORY.fromGp(r);
    }

    @Test
    void burnAtTheWindowStartIsExported() {
        // A maneuver template fires its first burn AT the scenario start — which is also
        // the OEM grid's first point. The burn epoch lands on a propagate() boundary, so
        // this is the case most at risk of an ImpulseManeuver not firing. With a normal
        // (earlier) deputy seed epoch — as real catalog deputies always have — the burn
        // IS applied: the forward propagation from the seed crosses it in the interior.
        // Guards against a regression that would drop a t0 burn from the handoff file.
        TLE depTle = leoTleAtEpoch(33591, 0.05, "2024-06-01T11:00:00.000"); // seed 1 h before the window
        ScenarioBody.Maneuver burn = new ScenarioBody.Maneuver(
                "m1", "delta-v", START, "ric", new ScenarioBody.DeltaV(0.0, 5.0, 0.0), null, null);
        ScenarioBody body = new ScenarioBody(6, "sgp4", new ScenarioBody.TimeRange(START, END),
                role("chief", leoTle(25544, 0.0), "ISS", List.of()),
                List.of(role("deputy", depTle, "TWIN", List.of(burn))));
        Fixture fx = fixture(body);

        Oem oem = parse(fx.service().export(ID).content());
        List<TimeStampedPVCoordinates> coords =
                oem.getSatellites().get("33591").getSegments().get(0).getCoordinates();
        TimeStampedPVCoordinates late = coords.get(coords.size() - 1);

        AbsoluteDate burnDate = new AbsoluteDate(START, TimeScalesFactory.getUTC());
        Vector3D maneuvered = fx.prop().propagatorFor(depTle, Fidelity.SGP4,
                List.of(new Impulse(burnDate, 0.0, 5.0, 0.0, null, null)))
                .getPVCoordinates(late.getDate(), fx.frames().eci()).getPosition();
        Vector3D unmaneuvered = fx.prop().propagatorFor(depTle, Fidelity.SGP4)
                .getPVCoordinates(late.getDate(), fx.frames().eci()).getPosition();

        // The exported late state follows the MANEUVERED track (a ~5 m/s in-track burn
        // moves it ~80 km over the window), sitting far from the raw SGP4 one — i.e. the
        // t0 burn was applied, not dropped.
        double toManeuvered = Vector3D.distance(late.getPosition(), maneuvered);
        double toUnmaneuvered = Vector3D.distance(late.getPosition(), unmaneuvered);
        assertThat(toManeuvered).as("exported track follows the maneuvered propagation (m)")
                .isLessThan(100.0);
        assertThat(toUnmaneuvered).as("exported track is nowhere near the un-maneuvered orbit (m)")
                .isGreaterThan(10_000.0);
    }

    @Test
    void recordsTheExportInTheAuditTrail() {
        Fixture fx = fixture(body(List.of()));
        fx.service().export(ID);
        verify(fx.scenarios()).recordOemExport(eq(ID), contains("2 craft"));
    }
}
