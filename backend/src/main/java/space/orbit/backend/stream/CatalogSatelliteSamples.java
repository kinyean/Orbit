package space.orbit.backend.stream;

/**
 * One satellite's propagated samples for a single catalog pass, ready for CZML
 * encoding. Plain data — no Orekit types — so the encoder stays decoupled from
 * the propagation engine.
 *
 * @param noradId        NORAD catalog id
 * @param name           OBJECT_NAME (display label)
 * @param inclinationDeg orbital inclination, degrees (static info-panel field)
 * @param periodMinutes  orbital period, minutes (static info-panel field)
 * @param cartesian      flat interleaved samples {@code [t,X,Y,Z, t,X,Y,Z, ...]}:
 *                       {@code t} seconds since the pass epoch, {@code X/Y/Z}
 *                       ECEF metres
 */
public record CatalogSatelliteSamples(
        int noradId,
        String name,
        double inclinationDeg,
        double periodMinutes,
        double[] cartesian) {
}
