package space.orbit.backend.scenario;

/**
 * One audit-log row for the audit-log UI (Phase 10, US-INFRA-06). Newest-first
 * list from {@code GET /scenarios/{id}/audit}. {@code actorEmail} is resolved
 * from {@code actor_id}; {@code action} is the free-VARCHAR action name
 * (CREATE / UPDATE / MANEUVER_ADD / …); {@code diffSummary} is the human-readable
 * change description written with the mutation (Decision 16).
 */
public record AuditEntryResponse(
        String action,
        String actorEmail,
        String timestamp,
        String diffSummary) {
}
