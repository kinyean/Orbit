package space.orbit.backend.scenario;

/**
 * Read-only result of a collision-avoidance-maneuver preview (US-MAN-12): the avoidance ΔV (deputy
 * RIC components + magnitude) and what it achieves (baseline vs achieved miss distance), without
 * mutating the scenario. JSON-friendly (ISO-8601 UTC epoch strings) so it is returned straight from
 * the controller, mirroring {@code ScreeningResult}/{@code RendezvousSearchResult}. {@code converged}
 * is false — with a human {@code note} — when the target miss could not be fully reached (poor
 * conjunction phase, ΔV cap, or a degenerate geometry); the {@code dv*} are then the best effort.
 */
public record CamPlanResult(
        int deputyNoradId,
        int threatNoradId,
        String axis,
        String burnEpoch,
        String tcaEpoch,
        String achievedTcaEpoch,
        double dvR,
        double dvI,
        double dvC,
        double dvMagnitudeMps,
        double baselineMissM,
        double achievedMissM,
        boolean converged,
        String note) {
}
