package space.orbit.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ConjunctionEventComputer} behaviour (Phase 8, US-EVT-02). Pure unit test on
 * <strong>synthetic LVLH trajectories</strong> — no Orekit — mirroring the other
 * sampled-trajectory computers. The chief sits at the LVLH origin ({@code pos == null});
 * a deputy approaches in-track to a 200 m closest approach at t≈500 s then recedes
 * (a V-shaped range), so its in-window minimum is a clean vertex.
 */
class ConjunctionEventComputerTests {

    private static final Instant EPOCH = Instant.parse("2026-06-01T00:00:00Z");
    private static final int STEP = 10;
    private static final int STEPS = 100; // t = 0..1000 s

    /** Deputy at (0, y, 0): range to the chief = |y|; y dips 5000 → 200 → 5000. */
    private static double[] vApproach() {
        double[] p = new double[(STEPS + 1) * 4];
        for (int k = 0; k <= STEPS; k++) {
            double t = k * STEP;
            double y = t <= 500 ? 5000 - (4800.0 / 500.0) * t : 200 + (4800.0 / 500.0) * (t - 500);
            p[k * 4] = t;
            p[k * 4 + 1] = 0;   // R
            p[k * 4 + 2] = y;   // I (in-track)
            p[k * 4 + 3] = 0;   // C
        }
        return p;
    }

    private static List<SampledCraft> scene() {
        SampledCraft chief = new SampledCraft(25544, null, 4, null, List.of());
        SampledCraft deputy = new SampledCraft(25545, vApproach(), 4, null, List.of());
        return List.of(chief, deputy);
    }

    @Test
    void reportsConjunctionBelowThreshold() {
        List<ConjunctionEvent> ev = new ConjunctionEventComputer()
                .compute(scene(), 1000.0, 0, STEP, STEPS, EPOCH, 1000);

        assertThat(ev).hasSize(1);
        ConjunctionEvent c = ev.get(0);
        assertThat(c.aNoradId()).isEqualTo(25544); // canonical a < b
        assertThat(c.bNoradId()).isEqualTo(25545);
        assertThat(c.missDistanceM()).as("refined ≤ coarse 200 m vertex").isLessThanOrEqualTo(201.0);
        assertThat(c.tcaEpoch()).isBetween(EPOCH.plusSeconds(490), EPOCH.plusSeconds(510));
    }

    @Test
    void noConjunctionWhenMinExceedsThreshold() {
        List<ConjunctionEvent> ev = new ConjunctionEventComputer()
                .compute(scene(), 100.0, 0, STEP, STEPS, EPOCH, 1000); // 200 m min > 100 m
        assertThat(ev).isEmpty();
    }

    @Test
    void refinedMissNotWorseThanCoarseGrid() {
        ConjunctionEvent c = new ConjunctionEventComputer()
                .compute(scene(), 1000.0, 0, STEP, STEPS, EPOCH, 1000).get(0);
        // Coarse grid minimum is exactly 200 m at t=500; the refine must be ≤ that.
        assertThat(c.missDistanceM()).isCloseTo(200.0, within(1.0));
    }

    @Test
    void isDeterministicOnRerun() {
        var a = new ConjunctionEventComputer().compute(scene(), 1000.0, 0, STEP, STEPS, EPOCH, 1000);
        var b = new ConjunctionEventComputer().compute(scene(), 1000.0, 0, STEP, STEPS, EPOCH, 1000);
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).tcaEpoch()).isEqualTo(b.get(i).tcaEpoch());
            assertThat(a.get(i).missDistanceM()).isEqualTo(b.get(i).missDistanceM());
        }
    }
}
