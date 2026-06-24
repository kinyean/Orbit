package space.orbit.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EclipseEventComputer} behaviour (Phase 8, US-ENV-02). Pure unit test on
 * <strong>synthetic geocentric ECI trajectories</strong> — no Orekit, no propagation —
 * mirroring {@link SensorEventComputerTests} (that is exactly how the computer runs in
 * production: it reads the already-sampled tracks).
 *
 * <p>The Sun sits far along +X. A craft sits behind the Earth (fixed {@code x=-7000 km},
 * so it is always on the anti-sun side) while its {@code y} sweeps {@code +1e7 → −1e7}:
 * its perpendicular miss from the shadow axis falls through the penumbra and umbra
 * radii and back, producing exactly penumbra-ingress → umbra-ingress → umbra-egress →
 * penumbra-egress.
 */
class EclipseEventComputerTests {

    private static final Instant EPOCH = Instant.parse("2026-06-01T00:00:00Z");
    private static final int STEP = 10;
    private static final int STEPS = 100; // t = 0..1000 s
    private static final double BEHIND_X = -7_000_000.0; // behind the Earth on the anti-sun side

    /** Sun far along +X (geocentric ECI), constant over the window. */
    private static double[] sunAlongX() {
        double[] s = new double[(STEPS + 1) * 4];
        for (int k = 0; k <= STEPS; k++) {
            s[k * 4] = k * STEP;
            s[k * 4 + 1] = 1.496e11; // ~1 AU
            s[k * 4 + 2] = 0;
            s[k * 4 + 3] = 0;
        }
        return s;
    }

    /** Craft at fixed x behind the Earth; y sweeps +1e7 → −1e7 (dips through the shadow). */
    private static double[] sweepingBehind(double x) {
        double[] p = new double[(STEPS + 1) * 4];
        for (int k = 0; k <= STEPS; k++) {
            double t = k * STEP;
            p[k * 4] = t;
            p[k * 4 + 1] = x;
            p[k * 4 + 2] = 1e7 - 2e4 * t; // +1e7 at t=0, 0 at t=500, −1e7 at t=1000
            p[k * 4 + 3] = 0;
        }
        return p;
    }

    @Test
    void detectsPenumbraUmbraSequence() {
        List<SampledGeocentricCraft> crafts =
                List.of(new SampledGeocentricCraft(1, sweepingBehind(BEHIND_X)));
        List<EclipseEvent> ev = new EclipseEventComputer()
                .compute(crafts, sunAlongX(), 0, STEP, STEPS, EPOCH, 1000);

        assertThat(ev).extracting(EclipseEvent::type).containsExactly(
                EclipseEvent.PENUMBRA_INGRESS, EclipseEvent.UMBRA_INGRESS,
                EclipseEvent.UMBRA_EGRESS, EclipseEvent.PENUMBRA_EGRESS);
        for (EclipseEvent e : ev) {
            assertThat(e.noradId()).isEqualTo(1);
        }
        // Strictly time-ordered, and umbra is bracketed inside the penumbra.
        for (int i = 1; i < ev.size(); i++) {
            assertThat(ev.get(i).epoch()).isAfterOrEqualTo(ev.get(i - 1).epoch());
        }
        // Deepest shadow is near t=500 s; ingress before, egress after.
        assertThat(ev.get(1).epoch()).isBefore(EPOCH.plusSeconds(500));
        assertThat(ev.get(2).epoch()).isAfter(EPOCH.plusSeconds(500));
    }

    @Test
    void alwaysSunlitCraftHasNoEvents() {
        // Fixed in FRONT of the Earth (positive x → sunlit side): never eclipsed.
        List<SampledGeocentricCraft> crafts =
                List.of(new SampledGeocentricCraft(2, sweepingBehind(+7_000_000.0)));
        List<EclipseEvent> ev = new EclipseEventComputer()
                .compute(crafts, sunAlongX(), 0, STEP, STEPS, EPOCH, 1000);
        assertThat(ev).isEmpty();
    }

    @Test
    void isDeterministicOnRerun() {
        List<SampledGeocentricCraft> crafts =
                List.of(new SampledGeocentricCraft(1, sweepingBehind(BEHIND_X)));
        List<EclipseEvent> a = new EclipseEventComputer()
                .compute(crafts, sunAlongX(), 0, STEP, STEPS, EPOCH, 1000);
        List<EclipseEvent> b = new EclipseEventComputer()
                .compute(crafts, sunAlongX(), 0, STEP, STEPS, EPOCH, 1000);
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).type()).isEqualTo(b.get(i).type());
            assertThat(a.get(i).epoch()).isEqualTo(b.get(i).epoch());
        }
    }
}
