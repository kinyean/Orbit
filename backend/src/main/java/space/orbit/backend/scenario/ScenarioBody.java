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
 * role. <b>schemaVersion 3</b> (Phase 7) adds an optional {@link Sensor} list and an
 * {@link AttitudeProfile} per role (chief or deputy). The schema is forward-additive:
 * a stored v1/v2 body deserializes with null sensor/maneuver lists (the {@link Role}
 * canonical constructor coalesces them to empty) and a null attitude (treated as the
 * default LVLH-aligned profile), and {@link ScenarioService} re-stamps
 * {@code schemaVersion = 3} on the next save. No DB migration is needed (the body is
 * raw {@code jsonb}).
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

    /** The current body schema version (v4: measured-ephemeris initial state). */
    public static final int CURRENT_SCHEMA_VERSION = 4;

    /** ISO-8601 UTC start/end of the scenario's propagation window. */
    public record TimeRange(String start, String end) {}

    /**
     * A chief or deputy: its catalog identity + frozen initial state + maneuvers +
     * sensors + an attitude profile. Sensors and the attitude profile are valid on
     * BOTH the chief and deputies (UC-4's imager rides the chief — the LVLH origin).
     * Maneuvers stay deputy-only (the chief is the LVLH reference; enforced in
     * {@link ScenarioService}).
     */
    public record Role(String role, int noradId, String name, InitialState initialState,
                       List<Maneuver> maneuvers, List<Sensor> sensors, AttitudeProfile attitude) {

        /** Coalesce null maneuver/sensor lists (v1/v2 bodies, or roles built without one) to empty. */
        public Role {
            maneuvers = maneuvers == null ? List.of() : List.copyOf(maneuvers);
            sensors = sensors == null ? List.of() : List.copyOf(sensors);
        }

        /** Convenience: maneuvers only, no sensors / default (LVLH) attitude (Phase 5B call sites, tests). */
        public Role(String role, int noradId, String name, InitialState initialState,
                    List<Maneuver> maneuvers) {
            this(role, noradId, name, initialState, maneuvers, List.of(), null);
        }

        /** Convenience for callers with no maneuvers (composition, seeds, tests). */
        public Role(String role, int noradId, String name, InitialState initialState) {
            this(role, noradId, name, initialState, List.of(), List.of(), null);
        }

        /** Immutable copy with a replaced maneuver list (other fields preserved). */
        public Role withManeuvers(List<Maneuver> next) {
            return new Role(role, noradId, name, initialState, next, sensors, attitude);
        }

        /** Immutable copy with a replaced sensor list (other fields preserved). */
        public Role withSensors(List<Sensor> next) {
            return new Role(role, noradId, name, initialState, maneuvers, next, attitude);
        }

        /** Immutable copy with a replaced attitude profile (other fields preserved). */
        public Role withAttitude(AttitudeProfile next) {
            return new Role(role, noradId, name, initialState, maneuvers, sensors, next);
        }
    }

    /**
     * Initial-state source. {@code kind = "tle"} carries a frozen {@link Tle}
     * (Phase 3A). {@code kind = "ephemeris"} (v4) carries no TLE — instead a
     * {@code datasetId} referencing a stored {@link MeasuredDataset} of real
     * measured states, served via an Orekit tabulated ephemeris. Forward-additive:
     * older bodies deserialize with {@code datasetId = null}.
     */
    public record InitialState(String kind, Tle tle, String datasetId) {

        /** Convenience for TLE-sourced roles (no measured dataset). */
        public InitialState(String kind, Tle tle) {
            this(kind, tle, null);
        }
    }

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

    /**
     * A sensor mounted on a spacecraft (Phase 7, US-SENSE-01). v1 is a body-fixed
     * camera/RF/lidar: a {@link Fov} shape, a working range band, and a body-fixed
     * boresight ({@link Mount}). {@code id} is a stable UUID so FOV volumes, the
     * sensor-frame camera, and acquisition events can reference it and edits stay
     * idempotent. Gimbaled pointing / frustum-polygonal FOV / CCSDS AEM attitude are
     * deferred (the records leave room — see Decision 24).
     */
    public record Sensor(String id, String kind, String name, Fov fov,
                         double minRangeM, double maxRangeM, Mount mount) {}

    /**
     * FOV geometry. {@code type = "cone"} uses {@code halfAngleDeg} (circular);
     * {@code type = "rect"} uses {@code hDeg}/{@code vDeg} (full angular width/height,
     * e.g. UC-4's 20°×15° imager). Unused fields are ignored per type.
     */
    public record Fov(String type, double halfAngleDeg, double hDeg, double vDeg) {}

    /**
     * Body-fixed sensor mounting. {@code boresightBody} is the pointing axis in the
     * spacecraft body frame (defaults to +X when null/empty); {@code clockDeg} rolls
     * a rectangular FOV about the boresight. (Gimbal fields omitted — deferred.)
     */
    public record Mount(double[] boresightBody, double clockDeg) {}

    /**
     * Per-spacecraft attitude profile (Phase 7). {@code mode = "lvlh"} (the default
     * when the field is null) is the modeled LVLH-aligned attitude built from the
     * orbital state — the backend-authoritative successor to the Phase-6 frontend
     * estimate (Decision 24); {@code mode = "fixed"} holds a constant ECI→body
     * orientation given by {@code quaternion} (x,y,z,w). CCSDS AEM (measured) is a
     * later {@code mode}.
     */
    public record AttitudeProfile(String mode, double[] quaternion) {}
}
