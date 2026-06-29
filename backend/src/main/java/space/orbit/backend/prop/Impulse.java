package space.orbit.backend.prop;

import org.orekit.time.AbsoluteDate;

/**
 * A ΔV in the deputy's RIC frame at an epoch (Phase 5B, US-MAN-01). A prop-layer
 * value type so {@link PropagationService} never imports the scenario body (the
 * bridge {@code ScenarioStreamService} converts maneuvers into these). Components
 * are metres/second: {@code r} radial, {@code i} in-track, {@code c} cross-track.
 *
 * <p><b>Finite burns (Phase 9, US-MAN-11).</b> When {@code thrustN} and {@code ispSec}
 * are both present + positive the burn is <em>finite</em>: {@link PropagationService}
 * realises it as an Orekit {@code ConstantThrustManeuver} of the duration that achieves
 * this {@code (r,i,c)} ΔV given the spacecraft mass (Tsiolkovsky), centred on
 * {@link #epoch()} (so it reduces to the impulsive case as thrust → ∞). When either is
 * null the burn is impulsive (an {@code ImpulseManeuver}) — the Phase-5B behaviour. The
 * CW path always treats the ΔV as impulsive at {@code epoch} (i.e. at the burn midpoint).
 */
public record Impulse(AbsoluteDate epoch, double r, double i, double c,
                      Double thrustN, Double ispSec) {

    /** Impulsive convenience (no finite-burn parameters) — keeps every Phase-5B call site. */
    public Impulse(AbsoluteDate epoch, double r, double i, double c) {
        this(epoch, r, i, c, null, null);
    }

    /** True when this should be integrated as a finite-thrust burn rather than an instant ΔV. */
    public boolean finite() {
        return thrustN != null && ispSec != null && thrustN > 0.0 && ispSec > 0.0;
    }
}
