package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Pure tests for the Clohessy–Wiltshire state-transition math (Phase 5C). No
 * Orekit data needed — {@link CwPropagation#advance} is closed-form arithmetic.
 */
class CwPropagationTests {

    // ~550 km LEO mean motion (rad/s): n = 2π / (~95.6 min).
    private static final double N = 2.0 * Math.PI / 5736.0;

    @Test
    void identityAtZeroDt() {
        double[] s = {1000, -2000, 500, 1.0, -0.5, 0.25};
        double[] r = CwPropagation.advance(s, N, 0.0);
        for (int k = 0; k < 6; k++) {
            assertThat(r[k]).as("component " + k).isCloseTo(s[k], within(1e-9));
        }
    }

    @Test
    void boundedOrbitClosesAfterOnePeriod() {
        // The CW bounded-orbit condition vy0 = -2 n x0 removes the in-track secular
        // drift, so the relative trajectory is a closed 2:1 ellipse that returns to
        // its start after one period. Cross-track is decoupled SHM (also closes).
        double x0 = 1000.0;
        double[] s0 = {x0, 0.0, 500.0, 0.0, -2.0 * N * x0, 0.0};
        double period = 2.0 * Math.PI / N;

        double[] s = CwPropagation.advance(s0, N, period);
        for (int k = 0; k < 6; k++) {
            assertThat(s[k]).as("component " + k + " after one period").isCloseTo(s0[k], within(1e-3));
        }
    }

    @Test
    void inTrackDriftsWhenUnbounded() {
        // Without the bounded-orbit velocity, a radial offset produces a secular
        // in-track drift (the dominant CW behavior). Pin its presence + direction:
        // a positive radial offset drifts in-track negative over a period.
        double[] s0 = {1000.0, 0.0, 0.0, 0.0, 0.0, 0.0};
        double[] s = CwPropagation.advance(s0, N, 2.0 * Math.PI / N);
        assertThat(s[1]).as("in-track secular drift").isLessThan(-1000.0);
    }
}
