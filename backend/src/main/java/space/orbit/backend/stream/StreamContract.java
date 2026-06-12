package space.orbit.backend.stream;

/**
 * Constants for the backend→frontend streaming contract. See
 * docs/streaming-contract.md. The {@link #VERSION} is echoed in every message
 * and in {@code /health} so the client can refuse a mismatched stream (R12).
 */
public final class StreamContract {

    /** Streaming contract version. Bump on any breaking change to message shape. */
    public static final String VERSION = "1";

    /** Raw WebSocket endpoint for the shared catalog feed. */
    public static final String CATALOG_ENDPOINT = "/stream/catalog";

    /**
     * Per-scenario WebSocket endpoint (Phase 4). Single-segment wildcard — raw
     * handlers don't template {@code {id}}, so the handshake interceptor parses
     * the trailing path segment as the scenario id.
     */
    public static final String SCENARIO_ENDPOINT_PATTERN = "/stream/scenario/*";

    /** {@code type} field value for catalog CZML messages (the shared live broadcast). */
    public static final String MESSAGE_TYPE_CATALOG = "catalog-czml";

    /**
     * {@code type} for an on-demand catalog snapshot at a requested epoch (live
     * time-travel, Decision 21). Same CZML shape as the broadcast but computed
     * for one client at an arbitrary instant; tagged distinctly so the client
     * applies it even while ignoring the live broadcast (frozen/traveled state).
     */
    public static final String MESSAGE_TYPE_CATALOG_SNAPSHOT = "catalog-snapshot";

    /**
     * {@code type} for a single satellite's orbit path — one orbital period of
     * ECEF positions, drawn as a dashed polyline on the globe (click-to-toggle).
     * Carries {@code noradId} + a flat {@code cartesian} array (no time).
     */
    public static final String MESSAGE_TYPE_CATALOG_ORBIT = "catalog-orbit";

    /** {@code type} for per-scenario CZML messages (global-view scenario layer). */
    public static final String MESSAGE_TYPE_SCENARIO_CZML = "scenario-czml";

    /** {@code type} for per-scenario relative-state messages (proximity view, 4B). */
    public static final String MESSAGE_TYPE_SCENARIO_RELATIVE = "scenario-relative";

    // --- WebSocket session attributes (set at handshake, read on the WS thread) -

    /** Session-attribute key for the parsed scenario id (a {@code UUID}). */
    public static final String ATTR_SCENARIO_ID = "scenarioId";

    /** Session-attribute key for the authenticated principal name (the email). */
    public static final String ATTR_PRINCIPAL_NAME = "principalName";

    // --- Scenario-stream close codes (application range 4xxx) ------------------
    //
    // 4403 (not owned) collapses to 4404 to avoid letting a caller enumerate
    // which ids exist by distinguishing "not yours" from "not there".

    /** Malformed / unparseable scenario id in the path. */
    public static final int CLOSE_BAD_REQUEST = 4400;

    /** Scenario does not exist, is soft-deleted, or is not owned by the caller. */
    public static final int CLOSE_NOT_FOUND = 4404;

    /** Scenario body is unusable: CW fidelity, TLE parse failure, no version. */
    public static final int CLOSE_UNPROCESSABLE = 4422;

    // --- Shared send limits (both handlers wrap sessions identically) ----------

    /** Max time a single send may take before the session is abandoned. */
    public static final int SEND_TIME_LIMIT_MS = 30_000;

    /** Max buffered outbound bytes before the session is abandoned. */
    public static final int SEND_BUFFER_LIMIT_BYTES = 32 * 1024 * 1024;

    private StreamContract() {}
}
