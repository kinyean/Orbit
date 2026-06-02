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

    /** {@code type} field value for catalog CZML messages. */
    public static final String MESSAGE_TYPE_CATALOG = "catalog-czml";

    private StreamContract() {}
}
