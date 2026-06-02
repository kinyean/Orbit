package space.orbit.backend.catalog;

import org.orekit.propagation.analytical.tle.TLEPropagator;

/**
 * A satellite in the live catalog: its identity + static display fields plus a
 * reusable SGP4 propagator (built once per refresh, queried each pass).
 *
 * @param noradId        NORAD catalog id
 * @param name           OBJECT_NAME
 * @param inclinationDeg orbital inclination, degrees
 * @param periodMinutes  orbital period, minutes
 * @param propagator     reusable Orekit SGP4 propagator (TEME frame)
 */
public record TrackedSatellite(
        int noradId,
        String name,
        double inclinationDeg,
        double periodMinutes,
        TLEPropagator propagator) {
}
