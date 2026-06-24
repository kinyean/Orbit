package space.orbit.backend.scenario;

/**
 * Web-agnostic input to {@link ScenarioService#addConstraint} (Phase 8, US-EVT-03).
 * The controller maps a validated request DTO into this. {@code kind} is
 * {@code "sun-keep-out"} (uses {@code sensorId} on the host + {@code limitDeg}) or
 * {@code "approach-corridor"} (uses {@code targetNoradId} + {@code limitDeg} +
 * {@code rangeM}, about the host body +Y axis). Unused fields per kind are ignored.
 */
public record ConstraintDraft(
        int hostNoradId,
        String kind,
        String sensorId,
        int targetNoradId,
        double limitDeg,
        double rangeM) {
}
