package space.orbit.backend.scenario;

/**
 * Web-agnostic input to {@link ScenarioService#addManeuver} (Phase 5B, US-MAN-01).
 * The controller maps a validated request DTO into this. An impulsive ΔV applied
 * to one deputy at one epoch; {@code frame} is {@code "ric"} in 5B (body-frame ΔV
 * arrives with attitude in Phase 7). Components are metres/second.
 */
public record ManeuverDraft(
        int deputyNoradId,
        String epoch,
        String frame,
        double r,
        double i,
        double c) {
}
