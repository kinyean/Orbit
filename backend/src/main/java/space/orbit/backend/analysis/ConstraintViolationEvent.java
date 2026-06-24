package space.orbit.backend.analysis;

import java.time.Instant;

/**
 * A constraint-violation boundary crossing (Phase 8, US-EVT-03 / SRS §3.12.3): the
 * moment a sun-keep-out or approach-corridor constraint starts ({@code "violation-start"})
 * or clears ({@code "violation-end"}). Computed on the already-sampled trajectory +
 * attitude + Sun vector — no re-propagation, deterministic (R11).
 *
 * @param type         {@code "violation-start"} or {@code "violation-end"}
 * @param constraintId the violated constraint's stable id
 * @param kind         the constraint kind ({@code sun-keep-out} / {@code approach-corridor})
 * @param hostId       NORAD id of the constrained host
 * @param sensorId     the sensor id (sun-keep-out); null/empty otherwise
 * @param targetId     NORAD id of the corridor target; 0 for sun-keep-out
 * @param epoch        UTC instant of the crossing (bisection-refined)
 * @param valueDeg     the measured angle (deg) at the crossing — ≈ {@code limitDeg}
 * @param limitDeg     the constraint's limit angle (deg)
 */
public record ConstraintViolationEvent(
        String type,
        String constraintId,
        String kind,
        int hostId,
        String sensorId,
        int targetId,
        Instant epoch,
        double valueDeg,
        double limitDeg) {

    public static final String START = "violation-start";
    public static final String END = "violation-end";
}
