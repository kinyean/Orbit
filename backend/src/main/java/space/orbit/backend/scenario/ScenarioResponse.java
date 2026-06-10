package space.orbit.backend.scenario;

/**
 * Full scenario view (POST / PUT / GET {id} responses): metadata + the typed
 * latest {@link ScenarioBody}. Embedding the body gives {@code gen:api} a usable
 * frontend schema and lets a load repopulate the composer in one round-trip.
 */
public record ScenarioResponse(
        String id,
        String name,
        String ownerId,
        String createdAt,
        int latestVersionNo,
        int versionCount,
        ScenarioBody body) {
}
