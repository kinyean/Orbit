package space.orbit.backend.catalog;

/**
 * An immutable snapshot of a catalog satellite's TLE, captured at scenario
 * compose time. Freezing the two line strings + epoch (and the display name)
 * makes a saved scenario reproducible: it does NOT drift when the periodic
 * catalog refresh replaces the in-memory TLE (Frank's requirement, SRS §5.4.1).
 *
 * @param noradId NORAD catalog id
 * @param name    OBJECT_NAME at capture time
 * @param line1   TLE line 1 (TEME mean elements, SGP4 input)
 * @param line2   TLE line 2
 * @param epoch   TLE epoch, ISO-8601 UTC
 */
public record TleSnapshot(int noradId, String name, String line1, String line2, String epoch) {
}
