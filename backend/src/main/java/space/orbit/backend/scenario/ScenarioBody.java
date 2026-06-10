package space.orbit.backend.scenario;

import java.util.List;

/**
 * Scenario JSON body schema v1 — the typed shape of the {@code jsonb}
 * {@link ScenarioVersion#getBody()}. Immutable; {@link ScenarioService} owns
 * (de)serialization via Jackson.
 *
 * <p>Each role stores both the <b>NORAD id</b> (the composer's join key, used
 * to display names from the frontend catalog index) AND a <b>TLE snapshot</b>
 * (line strings + epoch) captured at compose time, so a saved scenario is
 * reproducible and does not drift on the periodic catalog refresh (SRS §5.4.1).
 *
 * <p>{@code fidelity} is {@code "sgp4"} in Phase 3A (the only honored value);
 * {@code "numerical"} / {@code "cw"} arrive in later phases. Returned verbatim
 * to the frontend (embedded in the controller response DTOs) so {@code gen:api}
 * produces a usable TypeScript schema.
 */
public record ScenarioBody(
        int schemaVersion,
        String fidelity,
        TimeRange timeRange,
        Role chief,
        List<Role> deputies) {

    /** ISO-8601 UTC start/end of the scenario's propagation window. */
    public record TimeRange(String start, String end) {}

    /** A chief or deputy: its catalog identity + frozen initial state. */
    public record Role(String role, int noradId, String name, InitialState initialState) {}

    /** Initial-state source. Phase 3A: {@code kind = "tle"} only. */
    public record InitialState(String kind, Tle tle) {}

    /** A frozen TLE (the two line strings + epoch). */
    public record Tle(String line1, String line2, String epoch) {}
}
