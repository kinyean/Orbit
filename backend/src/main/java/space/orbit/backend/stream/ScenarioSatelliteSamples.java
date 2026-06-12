package space.orbit.backend.stream;

/**
 * One scenario spacecraft's propagated samples for the global-view scenario
 * layer, ready for CZML encoding. Plain data — no Orekit types — so the encoder
 * stays decoupled from the propagation engine (mirrors
 * {@link CatalogSatelliteSamples}, with a {@code role} so chief and deputies can
 * be drawn distinctly).
 *
 * @param role          {@code "chief"} or {@code "deputy"}
 * @param noradId       NORAD catalog id (the composer's join key)
 * @param name          display label
 * @param periodSeconds orbital period — the path trail is one period long so it
 *                      sweeps with the clock rather than showing the whole run
 * @param cartesian     flat interleaved samples {@code [t,X,Y,Z, t,X,Y,Z, ...]}:
 *                      {@code t} seconds since the scenario epoch, {@code X/Y/Z}
 *                      ECEF metres
 */
public record ScenarioSatelliteSamples(
        String role,
        int noradId,
        String name,
        double periodSeconds,
        double[] cartesian) {
}
