package space.orbit.backend.io;

import java.util.List;

/**
 * The normalized output of a measured-ephemeris reader (the internal artifact —
 * Decision: one artifact, many readers). A satellite's real position + velocity
 * over time in a single inertial frame, SI units. {@link WodCsvReader} is the
 * first reader; CCSDS OEM/AEM readers can later produce the same shape.
 *
 * <p>Frame is currently always {@code EME2000} (J2000 inertial) — the TELEOS-2
 * GNSS "ECI" was verified to be EME2000 to ~1 m via an ECI→ECEF self-check.
 *
 * @param satelliteName display name parsed from the source (e.g. {@code TELEOS-2})
 * @param frame         inertial frame tag of the samples (Decision 12, R15)
 * @param samples       time-ordered states; position metres, velocity m/s
 */
public record MeasuredEphemeris(String satelliteName, String frame, List<Sample> samples) {

    /** One measured state: UTC epoch + ECI position (m) + velocity (m/s). */
    public record Sample(long epochMillis,
                         double px, double py, double pz,
                         double vx, double vy, double vz) {}
}
