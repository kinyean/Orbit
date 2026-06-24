package space.orbit.backend.analysis;

import java.time.Instant;

/**
 * An eclipse ingress/egress event for one spacecraft (Phase 8, US-ENV-02 / SRS
 * §3.7.2): the moment it crosses the Earth's penumbra or umbra boundary. Computed
 * in geocentric ECI from the already-sampled trajectory + the sampled Sun position
 * (no re-propagation), mirroring {@link SensorEvent} / {@link EclipseEventComputer}.
 *
 * @param type    one of {@code "penumbra-ingress"}, {@code "umbra-ingress"},
 *                {@code "umbra-egress"}, {@code "penumbra-egress"}
 * @param noradId NORAD id of the eclipsed spacecraft
 * @param epoch   UTC instant of the boundary crossing (bisection-refined)
 */
public record EclipseEvent(String type, int noradId, Instant epoch) {

    public static final String PENUMBRA_INGRESS = "penumbra-ingress";
    public static final String UMBRA_INGRESS = "umbra-ingress";
    public static final String UMBRA_EGRESS = "umbra-egress";
    public static final String PENUMBRA_EGRESS = "penumbra-egress";
}
