package space.orbit.backend.scenario;

/**
 * One entry in a scenario's version history (Phase 10, US-INFRA-06 /
 * US-SCN-04). Returned by {@code GET /scenarios/{id}/versions} — the list
 * counterpart to the point-in-time {@link ScenarioVersionResponse}. Metadata
 * only (no body), so the UI can render a compact history without pulling every
 * version's payload.
 */
public record ScenarioVersionSummary(
        int versionNo,
        String authorEmail,
        String createdAt) {
}
