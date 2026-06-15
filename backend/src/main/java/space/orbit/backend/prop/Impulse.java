package space.orbit.backend.prop;

import org.orekit.time.AbsoluteDate;

/**
 * An impulsive ΔV in the deputy's RIC frame at an epoch (Phase 5B, US-MAN-01).
 * A prop-layer value type so {@link PropagationService} never imports the scenario
 * body (the bridge {@code ScenarioStreamService} converts maneuvers into these).
 * Components are metres/second: {@code r} radial, {@code i} in-track, {@code c}
 * cross-track.
 */
public record Impulse(AbsoluteDate epoch, double r, double i, double c) {
}
