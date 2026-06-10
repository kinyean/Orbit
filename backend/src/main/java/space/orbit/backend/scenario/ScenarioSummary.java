package space.orbit.backend.scenario;

import java.util.List;

/**
 * Compact list item (GET /scenarios). Carries enough for the scenario panel to
 * render a row — name, version info, and the chief/deputy NORAD ids it maps to
 * display names via the frontend catalog index — without the full body.
 */
public record ScenarioSummary(
        String id,
        String name,
        String createdAt,
        int latestVersionNo,
        int versionCount,
        int chiefNoradId,
        List<Integer> deputyNoradIds) {
}
