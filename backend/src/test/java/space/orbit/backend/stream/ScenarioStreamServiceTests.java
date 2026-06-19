package space.orbit.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import space.orbit.backend.scenario.ScenarioService;

/**
 * {@link ScenarioStreamService} behaviour. Pure JUnit + the Orekit data bundle;
 * {@link ScenarioService#bodyForStream} is mocked so the test stands up the real
 * propagation/frame/encoder stack without a DB. Builds the body from
 * <em>line-string</em> TLEs (proving the {@code new TLE(l1,l2,utc)} rebuild),
 * and pins the R8 sample-cap clamp + the R11 determinism guarantee.
 */
class ScenarioStreamServiceTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ID = UUID.randomUUID();
    private static final String EMAIL = "dev@orbit.local";

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    // --- fixtures -------------------------------------------------------------

    private static ScenarioStreamService service(ScenarioStreamProperties props, ScenarioService scenarioService) {
        FrameService frames = new FrameService();
        frames.init();
        PropagationService prop = new PropagationService(
                new SatellitePropagator(frames), new NumericalPropagation(frames), frames);
        ScenarioStreamService svc = new ScenarioStreamService(
                scenarioService, prop, frames, new CzmlEncoder(), new RelativeStateEncoder(), props);
        svc.init();
        return svc;
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
        // Freeze the canonical line strings — exactly what the composer persists.
        return new ScenarioBody.Role(roleName, tle.getSatelliteNumber(), name,
                new ScenarioBody.InitialState("tle",
                        new ScenarioBody.Tle(tle.getLine1(), tle.getLine2(), tle.getDate().toString())));
    }

    private static ScenarioBody body(String fidelity, String start, String end) {
        ScenarioBody.Role chief = role("chief", leoTle(25544, "ISS (ZARYA)", 325.0), "ISS (ZARYA)");
        ScenarioBody.Role deputy = role("deputy", leoTle(25545, "DEPUTY-1", 5.0), "DEPUTY-1");
        return new ScenarioBody(2, fidelity, new ScenarioBody.TimeRange(start, end), chief, List.of(deputy));
    }

    /** Same as {@link #body} but the deputy carries a single prograde ΔV maneuver. */
    private static ScenarioBody bodyWithManeuver(String start, String maneuverEpoch, double dvInTrack) {
        ScenarioBody.Role chief = role("chief", leoTle(25544, "ISS (ZARYA)", 325.0), "ISS (ZARYA)");
        TLE depTle = leoTle(25545, "DEPUTY-1", 5.0);
        ScenarioBody.Maneuver m = new ScenarioBody.Maneuver(
                "m-1", "delta_v", maneuverEpoch, "ric", new ScenarioBody.DeltaV(0.0, dvInTrack, 0.0));
        ScenarioBody.Role deputy = new ScenarioBody.Role("deputy", 25545, "DEPUTY-1",
                new ScenarioBody.InitialState("tle",
                        new ScenarioBody.Tle(depTle.getLine1(), depTle.getLine2(), depTle.getDate().toString())),
                List.of(m));
        // 90 min window so the post-burn arc has time to climb to a higher apogee.
        return new ScenarioBody(2, "sgp4", new ScenarioBody.TimeRange(start, "2024-06-01T13:30:00Z"),
                chief, List.of(deputy));
    }

    private static ScenarioService mockBody(ScenarioBody body) {
        ScenarioService scenarioService = mock(ScenarioService.class);
        when(scenarioService.bodyForStream(any(), any())).thenReturn(body);
        return scenarioService;
    }

    // --- tests ----------------------------------------------------------------

    @Test
    void rebuildsLineStringTlesAndEncodesPlausibleLeo() throws Exception {
        ScenarioBody body = body("sgp4", "2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));

        EncodedScenario encoded = svc.loadAndEncode(ID, EMAIL);
        JsonNode root = MAPPER.readTree(encoded.czml());

        assertThat(root.get("type").asText()).isEqualTo(StreamContract.MESSAGE_TYPE_SCENARIO_CZML);
        assertThat(root.get("satelliteCount").asInt()).isEqualTo(2);

        JsonNode chiefPos = root.get("czml").get(1).get("position").get("cartesian");
        double x = chiefPos.get(1).asDouble(), y = chiefPos.get(2).asDouble(), z = chiefPos.get(3).asDouble();
        double rKm = Math.sqrt(x * x + y * y + z * z) / 1000.0;
        assertThat(rKm).as("chief ECEF radius (km)").isBetween(6500.0, 7200.0);

        assertThat(root.get("czml").get(1).get("properties").get("role").asText()).isEqualTo("chief");
        assertThat(root.get("czml").get(2).get("properties").get("role").asText()).isEqualTo("deputy");
    }

    @Test
    void numericalFidelityAlsoEncodes() throws Exception {
        ScenarioBody body = body("numerical", "2024-06-01T12:00:00Z", "2024-06-01T12:20:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        JsonNode root = MAPPER.readTree(svc.loadAndEncode(ID, EMAIL).czml());
        assertThat(root.get("satelliteCount").asInt()).isEqualTo(2);
    }

    @Test
    void cwFidelityStreamsWithValidityHint() throws Exception {
        // Phase 5C: CW is now streamable (no longer 4422). It encodes both views and
        // carries the validity hint (fidelity + max separation + chief eccentricity).
        ScenarioBody body = body("cw", "2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));

        EncodedScenario encoded = svc.loadAndEncode(ID, EMAIL);
        JsonNode czml = MAPPER.readTree(encoded.czml());
        assertThat(czml.get("satelliteCount").asInt()).isEqualTo(2);

        JsonNode rel = MAPPER.readTree(encoded.relative());
        assertThat(rel.get("fidelity").asText()).isEqualTo("cw");
        assertThat(rel.has("maxSeparationM")).isTrue();
        assertThat(rel.has("chiefEccentricity")).isTrue();
        // Chief geocentric radius (Phase 6 / US-PROX-05) is Earth-scale (LEO..GEO).
        double chiefRadiusKm = rel.get("chiefRadiusM").asDouble() / 1000.0;
        assertThat(chiefRadiusKm).isGreaterThan(6500.0).isLessThan(50000.0);
        // The CW deputy renders at a bounded LVLH separation (not Earth-scale).
        double sepKm = rel.get("maxSeparationM").asDouble() / 1000.0;
        assertThat(sepKm).isGreaterThan(0.0).isLessThan(20000.0);
    }

    @Test
    void sampleCapRaisesEffectiveStepAndEchoesIt() throws Exception {
        // 24 h at the requested 30 s would be 2880 samples; cap at 100 forces a
        // larger step, which must be reported (R8 — never silently truncate).
        ScenarioBody body = body("sgp4", "2024-06-01T00:00:00Z", "2024-06-02T00:00:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 100, true, true), mockBody(body));

        EncodedScenario encoded = svc.loadAndEncode(ID, EMAIL);
        assertThat(encoded.effectiveStepSeconds()).as("effective step (s)").isGreaterThan(30);

        JsonNode root = MAPPER.readTree(encoded.czml());
        assertThat(root.get("stepSeconds").asInt()).isEqualTo(encoded.effectiveStepSeconds());
        // samples per sat = steps + 1 must stay within the cap.
        int samples = root.get("czml").get(1).get("position").get("cartesian").size() / 4;
        assertThat(samples).isLessThanOrEqualTo(100);
    }

    // --- maneuvers (Phase 5B, US-MAN-01) -------------------------------------

    /** Max geocentric radius (km) of the deputy across its CZML cartesian samples. */
    private static double maxDeputyRadiusKm(JsonNode czmlRoot) {
        JsonNode cart = czmlRoot.get("czml").get(2).get("position").get("cartesian");
        double max = 0.0;
        for (int base = 0; base + 4 <= cart.size(); base += 4) {
            double x = cart.get(base + 1).asDouble();
            double y = cart.get(base + 2).asDouble();
            double z = cart.get(base + 3).asDouble();
            max = Math.max(max, Math.sqrt(x * x + y * y + z * z) / 1000.0);
        }
        return max;
    }

    @Test
    void progradeManeuverRaisesDeputyApogee() throws Exception {
        // A maneuvered deputy is forced numerical; compare a +50 m/s in-track burn
        // against a zero-ΔV burn (same numerical engine) so it's apples-to-apples.
        ScenarioStreamProperties props = new ScenarioStreamProperties(30, 5000, true, true);
        ScenarioStreamService base = service(props,
                mockBody(bodyWithManeuver("2024-06-01T12:00:00Z", "2024-06-01T12:15:00Z", 0.0)));
        ScenarioStreamService burned = service(props,
                mockBody(bodyWithManeuver("2024-06-01T12:00:00Z", "2024-06-01T12:15:00Z", 50.0)));

        double rBase = maxDeputyRadiusKm(MAPPER.readTree(base.loadAndEncode(ID, EMAIL).czml()));
        double rBurn = maxDeputyRadiusKm(MAPPER.readTree(burned.loadAndEncode(ID, EMAIL).czml()));
        assertThat(rBurn).as("apogee raised by a +50 m/s prograde burn (km)").isGreaterThan(rBase + 20.0);
    }

    @Test
    void maneuveredScenarioIsBitIdenticalOnRerun() {
        // R11 holds with maneuvers: the impulse is a frozen input, event location is
        // pinned, ordering is stable — both runs produce byte-identical payloads.
        ScenarioStreamProperties props = new ScenarioStreamProperties(30, 5000, true, true);
        ScenarioBody body = bodyWithManeuver("2024-06-01T12:00:00Z", "2024-06-01T12:15:00Z", 30.0);
        EncodedScenario a = service(props, mockBody(body)).loadAndEncode(ID, EMAIL);
        EncodedScenario b = service(props, mockBody(body)).loadAndEncode(ID, EMAIL);
        assertThat(a.czml()).isEqualTo(b.czml());
        assertThat(a.relative()).isEqualTo(b.relative());
    }

    @Test
    void encodingIsBitIdenticalOnRerun() {
        // R11: a pure function of the body — sequential ordered sampling, frozen
        // TLEs, pinned settings, no wall-clock / RNG. Both payloads byte-identical.
        ScenarioBody body = body("numerical", "2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        ScenarioStreamService a = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        ScenarioStreamService b = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        EncodedScenario ea = a.loadAndEncode(ID, EMAIL);
        EncodedScenario eb = b.loadAndEncode(ID, EMAIL);
        assertThat(ea.czml()).isEqualTo(eb.czml());
        assertThat(ea.relative()).isEqualTo(eb.relative());
    }

    // --- relative-state (proximity view, 4B) ---------------------------------

    @Test
    void relativeBlockExcludesChiefAndMatchesTheCzmlGrid() throws Exception {
        ScenarioBody body = body("sgp4", "2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        EncodedScenario encoded = svc.loadAndEncode(ID, EMAIL);

        JsonNode rel = MAPPER.readTree(encoded.relative());
        assertThat(rel.get("type").asText()).isEqualTo(StreamContract.MESSAGE_TYPE_SCENARIO_RELATIVE);
        assertThat(rel.get("frame").asText()).isEqualTo("LVLH");
        assertThat(rel.get("chiefId").asInt()).isEqualTo(25544);
        assertThat(rel.get("stride").asInt()).isEqualTo(7); // includeVelocity true → t,R,I,C,vR,vI,vC

        JsonNode deputies = rel.get("deputies");
        assertThat(deputies.size()).as("chief excluded; one deputy").isEqualTo(1);
        assertThat(deputies.get(0).get("noradId").asInt()).isEqualTo(25545);

        // Same grid as the CZML pass: samples/stride == cartesian/4 (the step count).
        int relCount = deputies.get(0).get("samples").size() / rel.get("stride").asInt();
        int czmlCount = MAPPER.readTree(encoded.czml())
                .get("czml").get(2).get("position").get("cartesian").size() / 4;
        assertThat(relCount).isEqualTo(czmlCount);
    }

    @Test
    void deputyCarriesClosestApproachWithinTheWindow() throws Exception {
        // US-REL-02 (Phase 5A): each deputy reports a TCA epoch + distance. The
        // co-orbital deputy's closest approach must fall inside [start,end] and the
        // distance must be ≤ every streamed sample's range (the refine never makes
        // it worse than the coarse grid minimum).
        String start = "2024-06-01T12:00:00Z";
        String end = "2024-06-01T13:30:00Z"; // ~1 rev so a clear minimum exists
        ScenarioBody body = body("sgp4", start, end);
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        JsonNode dep = MAPPER.readTree(svc.loadAndEncode(ID, EMAIL).relative()).get("deputies").get(0);

        assertThat(dep.has("tcaEpoch")).isTrue();
        java.time.Instant tca = java.time.Instant.parse(dep.get("tcaEpoch").asText());
        assertThat(tca).isBetween(java.time.Instant.parse(start), java.time.Instant.parse(end));

        double tcaDist = dep.get("tcaDistanceM").asDouble();
        assertThat(tcaDist).as("TCA distance (m)").isGreaterThan(0.0);

        // Min over the streamed samples (within the window) — refine must be ≤ this.
        JsonNode s = dep.get("samples");
        int stride = 7;
        long endSec = java.time.Duration.between(
                java.time.Instant.parse(start), java.time.Instant.parse(end)).getSeconds();
        double sampledMin = Double.POSITIVE_INFINITY;
        for (int base = 0; base + stride <= s.size(); base += stride) {
            double t = s.get(base).asDouble();
            if (t < 0 || t > endSec) {
                continue;
            }
            double r = s.get(base + 1).asDouble();
            double i = s.get(base + 2).asDouble();
            double c = s.get(base + 3).asDouble();
            sampledMin = Math.min(sampledMin, Math.sqrt(r * r + i * i + c * c));
        }
        assertThat(tcaDist).as("refined TCA ≤ coarse sample minimum").isLessThanOrEqualTo(sampledMin + 1.0);
    }

    @Test
    void relativeEnvelopeCarriesAttitudeAndSensors() throws Exception {
        // Phase 7: the chief carries a sensor + default LVLH attitude; the deputy gets
        // modeled attitude samples on the position grid. All additive (VERSION "1").
        ScenarioBody.Role chief = role("chief", leoTle(25544, "ISS (ZARYA)", 325.0), "ISS (ZARYA)")
                .withSensors(List.of(new ScenarioBody.Sensor("cx", "optical", "Chief imager",
                        new ScenarioBody.Fov("rect", 0, 20, 15), 100, 50000,
                        new ScenarioBody.Mount(new double[] {1, 0, 0}, 0))));
        ScenarioBody.Role deputy = role("deputy", leoTle(25545, "DEPUTY-1", 5.0), "DEPUTY-1");
        ScenarioBody body = new ScenarioBody(3, "sgp4",
                new ScenarioBody.TimeRange("2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z"),
                chief, List.of(deputy));
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));

        JsonNode rel = MAPPER.readTree(svc.loadAndEncode(ID, EMAIL).relative());
        assertThat(rel.get("contractVersion").asText()).isEqualTo(StreamContract.VERSION);

        // Chief block: attitude samples + the chief's sensor descriptor.
        JsonNode cb = rel.get("chief");
        assertThat(cb.get("noradId").asInt()).isEqualTo(25544);
        assertThat(cb.get("att").size() % 5).isZero();
        assertThat(cb.get("att").size()).isGreaterThan(0);
        assertThat(cb.get("sensors").get(0).get("fov").get("type").asText()).isEqualTo("rect");
        assertThat(cb.get("sensors").get(0).get("fov").get("hDeg").asDouble()).isEqualTo(20.0);

        // Deputy attitude samples on the same grid as positions; quaternions unit-norm.
        JsonNode d0 = rel.get("deputies").get(0);
        int attCount = d0.get("att").size() / 5;
        int posCount = d0.get("samples").size() / rel.get("stride").asInt();
        assertThat(attCount).as("attitude on the position grid").isEqualTo(posCount);
        double qx = d0.get("att").get(1).asDouble(), qy = d0.get("att").get(2).asDouble();
        double qz = d0.get("att").get(3).asDouble(), qw = d0.get("att").get(4).asDouble();
        assertThat(Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw))
                .as("modeled attitude is a unit quaternion").isCloseTo(1.0, within(0.01));
    }

    /** {@link #bodyWithManeuver} plus a wide cone on the chief aimed back at the trailing deputy. */
    private static ScenarioBody bodyManeuverWithChiefSensor() {
        ScenarioBody base = bodyWithManeuver("2024-06-01T12:00:00Z", "2024-06-01T12:15:00Z", 30.0);
        ScenarioBody.Role chiefWithSensor = base.chief().withSensors(List.of(
                new ScenarioBody.Sensor("cam", "optical", "wide",
                        new ScenarioBody.Fov("cone", 89, 0, 0), 1, 5_000_000,
                        new ScenarioBody.Mount(new double[] {0, -1, 0}, 0)))); // boresight body −Y (toward the trailing deputy)
        return new ScenarioBody(base.schemaVersion(), base.fidelity(), base.timeRange(),
                chiefWithSensor, base.deputies());
    }

    @Test
    void maneuveredScenarioWithSensorIsDeterministicAndEventsMatchSamples() throws Exception {
        // Regression guard for the maneuvered-deputy re-propagation bug (Decision 24): events are
        // now computed from the SAME sampled trajectory, so (a) they're deterministic and (b) each
        // event's range matches the deputy's sampled range at its epoch.
        ScenarioStreamProperties props = new ScenarioStreamProperties(30, 5000, true, true);
        ScenarioBody body = bodyManeuverWithChiefSensor();
        EncodedScenario a = service(props, mockBody(body)).loadAndEncode(ID, EMAIL);
        EncodedScenario b = service(props, mockBody(body)).loadAndEncode(ID, EMAIL);
        assertThat(a.relative()).as("deterministic with a sensor on a maneuvered scenario (R11)")
                .isEqualTo(b.relative());

        JsonNode rel = MAPPER.readTree(a.relative());
        JsonNode events = rel.get("events");
        if (events != null && events.size() > 0) {
            long epochMs = java.time.Instant.parse(rel.get("epoch").asText()).toEpochMilli();
            int stride = rel.get("stride").asInt();
            double[] samples = toDoubleArray(rel.get("deputies").get(0).get("samples"));
            for (JsonNode e : events) {
                double tSec = (java.time.Instant.parse(e.get("epoch").asText()).toEpochMilli() - epochMs) / 1000.0;
                double sampleRange = interpRange(samples, stride, tSec);
                assertThat(e.get("rangeM").asDouble())
                        .as("event range agrees with the sampled trajectory at its epoch")
                        .isCloseTo(sampleRange, within(Math.max(100.0, sampleRange * 0.02)));
            }
        }
    }

    /** |R,I,C| of the deputy samples interpolated at {@code t} seconds since epoch. */
    private static double interpRange(double[] s, int stride, double t) {
        int n = s.length / stride;
        if (n == 0) {
            return 0;
        }
        double r;
        double i;
        double c;
        double tFirst = s[0];
        double tLast = s[(n - 1) * stride];
        if (t <= tFirst || n == 1) {
            r = s[1];
            i = s[2];
            c = s[3];
        } else if (t >= tLast) {
            int b = (n - 1) * stride;
            r = s[b + 1];
            i = s[b + 2];
            c = s[b + 3];
        } else {
            int lo = 0;
            int hi = n - 1;
            while (lo + 1 < hi) {
                int m = (lo + hi) >>> 1;
                if (s[m * stride] <= t) {
                    lo = m;
                } else {
                    hi = m;
                }
            }
            int ba = lo * stride;
            int bb = hi * stride;
            double ta = s[ba];
            double tb = s[bb];
            double f = tb > ta ? (t - ta) / (tb - ta) : 0;
            r = s[ba + 1] + (s[bb + 1] - s[ba + 1]) * f;
            i = s[ba + 2] + (s[bb + 2] - s[ba + 2]) * f;
            c = s[ba + 3] + (s[bb + 3] - s[ba + 3]) * f;
        }
        return Math.sqrt(r * r + i * i + c * c);
    }

    private static double[] toDoubleArray(JsonNode arr) {
        double[] out = new double[arr.size()];
        for (int k = 0; k < arr.size(); k++) {
            out[k] = arr.get(k).asDouble();
        }
        return out;
    }

    @Test
    void relativeVelocityToggleChangesStride() throws Exception {
        ScenarioBody body = body("sgp4", "2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, false, true), mockBody(body));
        JsonNode rel = MAPPER.readTree(svc.loadAndEncode(ID, EMAIL).relative());
        assertThat(rel.get("includeVelocity").asBoolean()).isFalse();
        assertThat(rel.get("stride").asInt()).isEqualTo(4); // t,R,I,C only
        assertThat(rel.get("deputies").get(0).get("samples").size() % 4).isZero();
    }

    @Test
    void relativeSeparationIsPlausibleAndVelocityCapturesRotationRate() throws Exception {
        // A co-orbital deputy phased away from the chief: separation is bounded
        // (not Earth-scale), and relative velocity is non-trivially non-zero — the
        // latter proves the LVLH frame's rotation rate was carried (R15); the old
        // single-epoch toRelativeState path would collapse that term.
        ScenarioBody body = body("sgp4", "2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        JsonNode samples = MAPPER.readTree(svc.loadAndEncode(ID, EMAIL).relative())
                .get("deputies").get(0).get("samples");

        double r = samples.get(1).asDouble(), i = samples.get(2).asDouble(), c = samples.get(3).asDouble();
        double sepKm = Math.sqrt(r * r + i * i + c * c) / 1000.0;
        assertThat(sepKm).as("relative separation (km)").isGreaterThan(0.0).isLessThan(20000.0);

        double maxSpeed = 0.0;
        int stride = 7;
        for (int base = 0; base + stride <= samples.size(); base += stride) {
            double vr = samples.get(base + 4).asDouble();
            double vi = samples.get(base + 5).asDouble();
            double vc = samples.get(base + 6).asDouble();
            maxSpeed = Math.max(maxSpeed, Math.sqrt(vr * vr + vi * vi + vc * vc));
        }
        assertThat(maxSpeed).as("relative speed carries LVLH rotation rate (m/s)").isGreaterThan(0.1);
    }
}
