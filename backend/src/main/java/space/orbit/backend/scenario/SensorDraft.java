package space.orbit.backend.scenario;

/**
 * Web-agnostic input to {@link ScenarioService#addSensor} (Phase 7, US-SENSE-01).
 * The controller maps a validated request DTO into this. A body-fixed sensor on
 * one host (chief or deputy): a FOV shape, a working range band, and a boresight
 * axis in the body frame. {@code fovType} is {@code "cone"} (uses
 * {@code halfAngleDeg}) or {@code "rect"} (uses {@code hDeg}/{@code vDeg}).
 * {@code boresight*} default to +X when all zero.
 */
public record SensorDraft(
        int noradId,
        String kind,
        String name,
        String fovType,
        double halfAngleDeg,
        double hDeg,
        double vDeg,
        double minRangeM,
        double maxRangeM,
        double boresightX,
        double boresightY,
        double boresightZ,
        double clockDeg) {
}
