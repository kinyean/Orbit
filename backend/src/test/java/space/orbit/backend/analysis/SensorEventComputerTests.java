package space.orbit.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import space.orbit.backend.scenario.ScenarioBody;

/**
 * {@link SensorEventComputer} behaviour (Phase 7, US-EVT-01). Pure unit test on
 * <strong>synthetic sampled trajectories</strong> — no Orekit, no propagation — which
 * is exactly how the computer works in production now (it reads the rendered samples,
 * never re-propagates; that's the fix for the maneuvered-deputy bug, Decision 24).
 *
 * <p>A host at the origin with an identity attitude carries a 30° cone along +X. A
 * target orbits in angle so its bearing sweeps 60°→0°→60° at a fixed in-range
 * distance — so it enters the cone (bearing crosses 30°) and later leaves it,
 * producing exactly one acquisition then one loss.
 */
class SensorEventComputerTests {

    private static final Instant EPOCH = Instant.parse("2026-06-01T00:00:00Z");
    private static final int STEP = 10;
    private static final int STEPS = 100; // t = 0..1000 s
    private static final double RANGE_M = 10_000.0;
    private static final double CHIEF_RADIUS_M = 6_900_000.0; // Earth far off the line of sight

    private static double bearingDeg(double t) {
        // 60° → 0° over [0,500], back 0° → 60° over [500,1000]; crosses 30° at t≈250 and t≈750.
        return t <= 500 ? 60.0 - (60.0 / 500.0) * t : (60.0 / 500.0) * (t - 500.0);
    }

    private static double[] sweepingTargetPos() {
        double[] pos = new double[(STEPS + 1) * 4];
        for (int k = 0; k <= STEPS; k++) {
            double t = k * STEP;
            double th = Math.toRadians(bearingDeg(t));
            pos[k * 4] = t;
            pos[k * 4 + 1] = RANGE_M * Math.cos(th); // R component
            pos[k * 4 + 2] = RANGE_M * Math.sin(th); // I component
            pos[k * 4 + 3] = 0;                       // C component
        }
        return pos;
    }

    private static double[] identityAttitude() {
        double[] att = new double[(STEPS + 1) * 5];
        for (int k = 0; k <= STEPS; k++) {
            att[k * 5] = k * STEP;
            att[k * 5 + 4] = 1.0; // (qx,qy,qz,qw) = (0,0,0,1)
        }
        return att;
    }

    private static ScenarioBody.Sensor cone(double halfDeg) {
        return new ScenarioBody.Sensor("s1", "optical", "cam",
                new ScenarioBody.Fov("cone", halfDeg, 0, 0), 100, 50_000,
                new ScenarioBody.Mount(new double[] {1, 0, 0}, 0)); // boresight +X
    }

    private static List<SampledCraft> scene(List<ScenarioBody.Sensor> hostSensors) {
        // Host at origin (pos=null), identity attitude → boresight is fixed scene +X.
        SampledCraft host = new SampledCraft(1, null, 4, identityAttitude(), hostSensors);
        SampledCraft target = new SampledCraft(2, sweepingTargetPos(), 4, identityAttitude(), List.of());
        return List.of(host, target);
    }

    @Test
    void detectsOneAcquisitionThenOneLoss() {
        List<SensorEvent> ev = new SensorEventComputer().compute(
                scene(List.of(cone(30.0))), 0, STEP, STEPS, EPOCH, 1000, CHIEF_RADIUS_M);

        assertThat(ev).hasSize(2);
        SensorEvent acq = ev.get(0);
        SensorEvent los = ev.get(1);
        assertThat(acq.type()).isEqualTo("acquisition");
        assertThat(los.type()).isEqualTo("los");
        for (SensorEvent e : ev) {
            assertThat(e.hostId()).isEqualTo(1);
            assertThat(e.sensorId()).isEqualTo("s1");
            assertThat(e.targetId()).isEqualTo(2);
            assertThat(e.rangeM()).as("constant in-range distance").isCloseTo(RANGE_M, org.assertj.core.api.Assertions.within(50.0));
        }
        // bearing crosses 30° at t≈250 s (acq) and t≈750 s (los); refine pins it within a step.
        assertThat(acq.epoch()).isBetween(EPOCH.plusSeconds(240), EPOCH.plusSeconds(260));
        assertThat(los.epoch()).isBetween(EPOCH.plusSeconds(740), EPOCH.plusSeconds(760));
        assertThat(acq.epoch()).isBefore(los.epoch());
    }

    @Test
    void noSensorsNoEvents() {
        List<SensorEvent> ev = new SensorEventComputer().compute(
                scene(List.of()), 0, STEP, STEPS, EPOCH, 1000, CHIEF_RADIUS_M);
        assertThat(ev).isEmpty();
    }

    @Test
    void isDeterministicOnRerun() {
        List<SensorEvent> a = new SensorEventComputer().compute(
                scene(List.of(cone(30.0))), 0, STEP, STEPS, EPOCH, 1000, CHIEF_RADIUS_M);
        List<SensorEvent> b = new SensorEventComputer().compute(
                scene(List.of(cone(30.0))), 0, STEP, STEPS, EPOCH, 1000, CHIEF_RADIUS_M);
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).type()).isEqualTo(b.get(i).type());
            assertThat(a.get(i).epoch()).isEqualTo(b.get(i).epoch());
            assertThat(a.get(i).rangeM()).isEqualTo(b.get(i).rangeM());
        }
    }

    @Test
    void narrowerConeAcquiresLater() {
        // A 15° cone is entered later (bearing must drop further) than a 30° cone.
        List<SensorEvent> wide = new SensorEventComputer().compute(
                scene(List.of(cone(30.0))), 0, STEP, STEPS, EPOCH, 1000, CHIEF_RADIUS_M);
        List<SensorEvent> narrow = new SensorEventComputer().compute(
                scene(List.of(cone(15.0))), 0, STEP, STEPS, EPOCH, 1000, CHIEF_RADIUS_M);
        assertThat(narrow.get(0).epoch()).isAfter(wide.get(0).epoch());
    }
}
