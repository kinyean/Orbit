package space.orbit.backend.catalog;

import org.orekit.propagation.analytical.tle.TLEPropagator;

/**
 * A satellite in the live catalog: its identity + static display fields plus a
 * reusable SGP4 propagator (built once per refresh, queried each pass).
 *
 * <p>{@code perigeeRadiusM}/{@code apogeeRadiusM} are the geocentric orbit shell
 * (from the TLE's semi-major axis + eccentricity), used by catalog conjunction
 * screening (Phase 8) for a cheap radial-band prune before fine sampling.
 *
 * @param noradId        NORAD catalog id
 * @param name           OBJECT_NAME
 * @param inclinationDeg orbital inclination, degrees
 * @param periodMinutes  orbital period, minutes
 * @param perigeeRadiusM geocentric perigee radius, metres
 * @param apogeeRadiusM  geocentric apogee radius, metres
 * @param propagator     reusable Orekit SGP4 propagator (TEME frame)
 */
public record TrackedSatellite(
        int noradId,
        String name,
        double inclinationDeg,
        double periodMinutes,
        double perigeeRadiusM,
        double apogeeRadiusM,
        TLEPropagator propagator) {
}
