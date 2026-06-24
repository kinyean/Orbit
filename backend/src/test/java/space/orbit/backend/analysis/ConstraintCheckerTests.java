package space.orbit.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import space.orbit.backend.scenario.ScenarioBody;

/**
 * {@link ConstraintChecker} behaviour (Phase 8, US-EVT-03). Pure unit test on
 * <strong>synthetic LVLH trajectories + attitude + Sun direction</strong> — no Orekit.
 * The host sits at the LVLH origin with an identity attitude (so a body axis equals the
 * scene axis), carrying a +X-boresight sensor.
 */
class ConstraintCheckerTests {

    private static final Instant EPOCH = Instant.parse("2026-06-01T00:00:00Z");
    private static final int STEP = 10;
    private static final int STEPS = 100; // t = 0..1000 s

    private static double[] identityAtt() {
        double[] a = new double[(STEPS + 1) * 5];
        for (int k = 0; k <= STEPS; k++) {
            a[k * 5] = k * STEP;
            a[k * 5 + 4] = 1.0; // (0,0,0,1)
        }
        return a;
    }

    private static ScenarioBody.Sensor sensorPlusX() {
        return new ScenarioBody.Sensor("s1", "optical", "cam",
                new ScenarioBody.Fov("cone", 10, 0, 0), 1, 1_000_000,
                new ScenarioBody.Mount(new double[] {1, 0, 0}, 0)); // boresight +X
    }

    /** Sun direction sweeping from 60° off +X → 0° → 60° (enters a 20° keep-out cone). */
    private static double[] sunSweepTowardX() {
        double[] s = new double[(STEPS + 1) * 4];
        for (int k = 0; k <= STEPS; k++) {
            double t = k * STEP;
            double aDeg = t <= 500 ? 60 - (60.0 / 500.0) * t : (60.0 / 500.0) * (t - 500);
            double a = Math.toRadians(aDeg);
            s[k * 4] = t;
            s[k * 4 + 1] = Math.cos(a); // toward +X as a→0
            s[k * 4 + 2] = Math.sin(a);
            s[k * 4 + 3] = 0;
        }
        return s;
    }

    private static double[] sunFixed(double x, double y, double z) {
        double[] s = new double[(STEPS + 1) * 4];
        for (int k = 0; k <= STEPS; k++) {
            s[k * 4] = k * STEP;
            s[k * 4 + 1] = x;
            s[k * 4 + 2] = y;
            s[k * 4 + 3] = z;
        }
        return s;
    }

    @Test
    void sunKeepOutFlagsIngressAndEgress() {
        SampledCraft host = new SampledCraft(1, null, 4, identityAtt(), List.of(sensorPlusX()));
        var c = new ScenarioBody.Constraint("k1", "sun-keep-out", 1, "s1", 0, 20.0, 0.0);
        List<ConstraintViolationEvent> ev = new ConstraintChecker()
                .compute(List.of(host), sunSweepTowardX(), List.of(c), 0, STEP, STEPS, EPOCH, 1000);

        assertThat(ev).extracting(ConstraintViolationEvent::type)
                .containsExactly(ConstraintViolationEvent.START, ConstraintViolationEvent.END);
        for (ConstraintViolationEvent e : ev) {
            assertThat(e.kind()).isEqualTo("sun-keep-out");
            assertThat(e.hostId()).isEqualTo(1);
            assertThat(e.sensorId()).isEqualTo("s1");
            assertThat(e.valueDeg()).as("crossing at ~the 20° limit").isCloseTo(20.0, org.assertj.core.api.Assertions.within(1.5));
        }
        // Sun crosses 20° at t≈333 (start) and t≈667 (end).
        assertThat(ev.get(0).epoch()).isBefore(EPOCH.plusSeconds(500));
        assertThat(ev.get(1).epoch()).isAfter(EPOCH.plusSeconds(500));
    }

    @Test
    void noViolationWhenSunBehindBoresight() {
        // Sun fixed along −X: 180° from the +X boresight, always outside a 20° cone.
        SampledCraft host = new SampledCraft(1, null, 4, identityAtt(), List.of(sensorPlusX()));
        var c = new ScenarioBody.Constraint("k1", "sun-keep-out", 1, "s1", 0, 20.0, 0.0);
        List<ConstraintViolationEvent> ev = new ConstraintChecker()
                .compute(List.of(host), sunFixed(-1, 0, 0), List.of(c), 0, STEP, STEPS, EPOCH, 1000);
        assertThat(ev).isEmpty();
    }

    /** Target at fixed 1000 m range, bearing from +Y sweeping 0° → 40° → 0° (exits a 20° corridor). */
    private static double[] cornerApproach() {
        double[] p = new double[(STEPS + 1) * 4];
        for (int k = 0; k <= STEPS; k++) {
            double t = k * STEP;
            double phiDeg = t <= 500 ? (40.0 / 500.0) * t : 40 - (40.0 / 500.0) * (t - 500);
            double phi = Math.toRadians(phiDeg);
            p[k * 4] = t;
            p[k * 4 + 1] = 1000 * Math.sin(phi); // R (off the +Y axis)
            p[k * 4 + 2] = 1000 * Math.cos(phi); // I (corridor axis = body +Y)
            p[k * 4 + 3] = 0;
        }
        return p;
    }

    @Test
    void approachCorridorFlagsExitAndReturn() {
        SampledCraft host = new SampledCraft(1, null, 4, identityAtt(), List.of());
        SampledCraft target = new SampledCraft(2, cornerApproach(), 4, identityAtt(), List.of());
        var c = new ScenarioBody.Constraint("k2", "approach-corridor", 1, null, 2, 20.0, 2000.0);
        List<ConstraintViolationEvent> ev = new ConstraintChecker()
                .compute(List.of(host, target), sunFixed(1, 0, 0), List.of(c), 0, STEP, STEPS, EPOCH, 1000);

        assertThat(ev).extracting(ConstraintViolationEvent::type)
                .containsExactly(ConstraintViolationEvent.START, ConstraintViolationEvent.END);
        assertThat(ev.get(0).kind()).isEqualTo("approach-corridor");
        assertThat(ev.get(0).targetId()).isEqualTo(2);
        // Bearing crosses 20° at t≈250 (exit) and t≈750 (return to corridor).
        assertThat(ev.get(0).epoch()).isBefore(EPOCH.plusSeconds(500));
        assertThat(ev.get(1).epoch()).isAfter(EPOCH.plusSeconds(500));
    }

    @Test
    void corridorIgnoredBeyondRange() {
        // Same geometry but a 500 m corridor range; the target sits at 1000 m → never checked.
        SampledCraft host = new SampledCraft(1, null, 4, identityAtt(), List.of());
        SampledCraft target = new SampledCraft(2, cornerApproach(), 4, identityAtt(), List.of());
        var c = new ScenarioBody.Constraint("k2", "approach-corridor", 1, null, 2, 20.0, 500.0);
        List<ConstraintViolationEvent> ev = new ConstraintChecker()
                .compute(List.of(host, target), sunFixed(1, 0, 0), List.of(c), 0, STEP, STEPS, EPOCH, 1000);
        assertThat(ev).isEmpty();
    }

    @Test
    void isDeterministicOnRerun() {
        SampledCraft host = new SampledCraft(1, null, 4, identityAtt(), List.of(sensorPlusX()));
        var c = new ScenarioBody.Constraint("k1", "sun-keep-out", 1, "s1", 0, 20.0, 0.0);
        var a = new ConstraintChecker().compute(List.of(host), sunSweepTowardX(), List.of(c), 0, STEP, STEPS, EPOCH, 1000);
        var b = new ConstraintChecker().compute(List.of(host), sunSweepTowardX(), List.of(c), 0, STEP, STEPS, EPOCH, 1000);
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).type()).isEqualTo(b.get(i).type());
            assertThat(a.get(i).epoch()).isEqualTo(b.get(i).epoch());
        }
    }
}
