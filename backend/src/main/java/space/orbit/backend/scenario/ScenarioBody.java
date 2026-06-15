package space.orbit.backend.scenario;

import java.util.List;

/**
 * Scenario JSON body schema — the typed shape of the {@code jsonb}
 * {@link ScenarioVersion#getBody()}. Immutable; {@link ScenarioService} owns
 * (de)serialization via Jackson.
 *
 * <p>Each role stores both the <b>NORAD id</b> (the composer's join key, used
 * to display names from the frontend catalog index) AND a <b>TLE snapshot</b>
 * (line strings + epoch) captured at compose time, so a saved scenario is
 * reproducible and does not drift on the periodic catalog refresh (SRS §5.4.1).
 *
 * <p><b>schemaVersion 2</b> (Phase 5B) adds an optional {@link Maneuver} list per
 * role. The schema is forward-additive: a stored v1 body deserializes with a null
 * maneuver list, which the {@link Role} canonical constructor coalesces to empty,
 * and {@link ScenarioService} re-stamps {@code schemaVersion = 2} on the next
 * save. No DB migration is needed (the body is raw {@code jsonb}).
 *
 * <p>{@code fidelity} is one of {@code sgp4} / {@code numerical} / {@code cw};
 * a maneuvered deputy is always propagated numerically regardless (an impulse
 * cannot be applied to the analytic SGP4 propagator). Returned verbatim to the
 * frontend (embedded in the controller response DTOs) so {@code gen:api} produces
 * a usable TypeScript schema.
 */
public record ScenarioBody(
        int schemaVersion,
        String fidelity,
        TimeRange timeRange,
        Role chief,
        List<Role> deputies) {

    /** The current body schema version (Phase 5B). */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /** ISO-8601 UTC start/end of the scenario's propagation window. */
    public record TimeRange(String start, String end) {}

    /** A chief or deputy: its catalog identity + frozen initial state + maneuvers. */
    public record Role(String role, int noradId, String name, InitialState initialState,
                       List<Maneuver> maneuvers) {

        /** Coalesce a null maneuver list (v1 bodies, or roles built without one) to empty. */
        public Role {
            maneuvers = maneuvers == null ? List.of() : List.copyOf(maneuvers);
        }

        /** Convenience for callers with no maneuvers (composition, seeds, tests). */
        public Role(String role, int noradId, String name, InitialState initialState) {
            this(role, noradId, name, initialState, List.of());
        }
    }

    /** Initial-state source. Phase 3A: {@code kind = "tle"} only. */
    public record InitialState(String kind, Tle tle) {}

    /** A frozen TLE (the two line strings + epoch). */
    public record Tle(String line1, String line2, String epoch) {}

    /**
     * An impulsive ΔV applied to a deputy (Phase 5B, US-MAN-01). {@code kind} is
     * {@code "delta_v"}; {@code frame} is {@code "ric"} in 5B (body-frame ΔV
     * arrives with attitude in Phase 7). {@code id} is a stable UUID so glyphs and
     * the ΔV budget can reference it and edits stay idempotent.
     */
    public record Maneuver(String id, String kind, String epoch, String frame, DeltaV deltaV) {}

    /** A ΔV vector in metres/second, components in the maneuver's {@code frame}. */
    public record DeltaV(double r, double i, double c) {}
}
