package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link CwTargeting} (Phase 9B). Pure CW math — no Orekit data. Verifies the two-impulse
 * solver lands the deputy on the target relative position/velocity when propagated by the
 * same {@link CwPropagation#advance} STM, and that the NMC no-drift condition is a closed
 * (bounded) relative orbit.
 */
class CwTargetingTest {

    private static final double N = 0.0011; // ~LEO mean motion, rad/s

    @Test
    void twoImpulseLandsOnTargetPositionAndVelocity() {
        double[] r0 = {120.0, -300.0, 40.0};
        double[] v0 = {0.2, -0.1, 0.05};
        double[] rT = {0.0, 800.0, 0.0};   // a V-bar (in-track) hold point
        double[] vT = {0.0, 0.0, 0.0};     // come to rest there
        double dt = 1400.0;                // ~quarter period (away from integer-rev singularity)

        double[] dv = CwTargeting.twoImpulse(r0, v0, rT, vT, N, dt);
        assertThat(dv).isNotNull();

        // Apply the departure burn, propagate, and confirm we arrive at rT with vT after the
        // arrival burn — i.e. the targeting is self-consistent with the CW propagation.
        double[] state = {r0[0], r0[1], r0[2], v0[0] + dv[0], v0[1] + dv[1], v0[2] + dv[2]};
        double[] arr = CwPropagation.advance(state, N, dt);
        assertThat(arr[0]).isCloseTo(rT[0], org.assertj.core.api.Assertions.within(1.0e-3));
        assertThat(arr[1]).isCloseTo(rT[1], org.assertj.core.api.Assertions.within(1.0e-3));
        assertThat(arr[2]).isCloseTo(rT[2], org.assertj.core.api.Assertions.within(1.0e-3));
        // Arrival velocity + the second burn matches the desired arrival velocity.
        assertThat(arr[3] + dv[3]).isCloseTo(vT[0], org.assertj.core.api.Assertions.within(1.0e-6));
        assertThat(arr[4] + dv[4]).isCloseTo(vT[1], org.assertj.core.api.Assertions.within(1.0e-6));
        assertThat(arr[5] + dv[5]).isCloseTo(vT[2], org.assertj.core.api.Assertions.within(1.0e-6));
    }

    @Test
    void nmcNoDriftConditionTracesAClosedOrbit() {
        // vy0 = −2 n x0 cancels the along-track secular drift → a bounded 2:1 relative ellipse.
        double x0 = 150.0;
        double[] state = {x0, 0.0, 60.0, 0.0, -2.0 * N * x0, 0.0};
        double period = 2.0 * Math.PI / N;
        double[] after = CwPropagation.advance(state, N, period);
        // After one period the deputy returns to its start (closed loop), not drifted away.
        for (int k = 0; k < 6; k++) {
            assertThat(after[k]).isCloseTo(state[k], org.assertj.core.api.Assertions.within(1.0e-3));
        }
    }

    @Test
    void integerRevTransferIsReportedSingular() {
        // At exactly one revolution Φrv is singular — the solver returns null, not garbage.
        double[] r0 = {100.0, 0.0, 0.0};
        double[] v0 = {0.0, 0.0, 0.0};
        double[] rT = {0.0, 500.0, 0.0};
        double dt = 2.0 * Math.PI / N; // one full revolution
        assertThat(CwTargeting.twoImpulse(r0, v0, rT, new double[] {0, 0, 0}, N, dt)).isNull();
    }
}
