package space.orbit.backend.analysis;

import java.time.Instant;

/**
 * A sensor acquisition or loss-of-sight event (Phase 7, US-EVT-01 / SRS §3.12.2):
 * the moment a target enters ({@code type = "acquisition"}) or leaves
 * ({@code type = "los"}) a host sensor's field of view with a clear line of sight
 * and within range. Computed on the live propagators, streamed for the timeline.
 *
 * @param type     {@code "acquisition"} or {@code "los"}
 * @param hostId   NORAD id of the sensor's host spacecraft
 * @param sensorId the host sensor's stable id
 * @param targetId NORAD id of the observed spacecraft
 * @param epoch    UTC instant of the crossing (bisection-refined)
 * @param rangeM   host→target range at {@code epoch}, metres
 */
public record SensorEvent(
        String type,
        int hostId,
        String sensorId,
        int targetId,
        Instant epoch,
        double rangeM) {
}
