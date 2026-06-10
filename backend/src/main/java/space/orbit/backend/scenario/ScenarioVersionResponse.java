package space.orbit.backend.scenario;

/** A specific immutable version (GET /scenarios/{id}/versions/{v}). */
public record ScenarioVersionResponse(
        String scenarioId,
        int versionNo,
        String authorId,
        String createdAt,
        ScenarioBody body) {
}
