package space.orbit.backend.scenario;

import java.util.List;

/**
 * Web-agnostic input to {@link ScenarioService} create/update. The controller
 * maps a validated request DTO into this (NORAD ids only — the service resolves
 * names + TLE snapshots from the catalog).
 */
public record ScenarioDraft(
        String name,
        String fidelity,
        String start,
        String end,
        int chiefNoradId,
        List<Integer> deputyNoradIds) {

    public ScenarioDraft {
        deputyNoradIds = deputyNoradIds == null ? List.of() : List.copyOf(deputyNoradIds);
    }
}
