package space.orbit.backend.io;

import java.util.List;

/**
 * The normalized output of a measured-ephemeris reader (the internal artifact —
 * Decision: one artifact, many readers). A satellite's real position + velocity
 * (and, slice 2, attitude) over time in a single inertial frame, SI units.
 * {@link WodCsvReader} is the first reader; CCSDS OEM/AEM readers can later
 * produce the same shape.
 *
 * <p>Frame is currently always {@code EME2000} (J2000 inertial) — the TELEOS-2
 * GNSS "ECI" was verified to be EME2000 to ~1 m via an ECI→ECEF self-check.
 *
 * <p><b>Attitude</b> (slice 2) is a <em>parallel</em> series with its own
 * timestamps (the ADCS quaternion can be sampled at a different cadence than
 * GNSS position, so it is NOT intersected with the position samples). Each
 * quaternion is stored <b>raw as in the file</b> ({@code Q1..Q4}); the WOD
 * convention → three.js body→ECI conversion lives in code (see
 * {@code FrameService}/{@code MeasuredAttitude}), not baked into the data, so a
 * faithful record is kept and the conversion can be corrected without re-import.
 *
 * @param satelliteName display name parsed from the source (e.g. {@code TELEOS-2})
 * @param frame         inertial frame tag of the samples (Decision 12, R15)
 * @param samples       time-ordered states; position metres, velocity m/s
 * @param attitude      time-ordered raw attitude quaternions (may be empty)
 */
public record MeasuredEphemeris(String satelliteName, String frame,
                                List<Sample> samples, List<AttitudeSample> attitude) {

    /** Convenience: a position-only ephemeris (no measured attitude). */
    public MeasuredEphemeris(String satelliteName, String frame, List<Sample> samples) {
        this(satelliteName, frame, samples, List.of());
    }

    /** One measured state: UTC epoch + ECI position (m) + velocity (m/s). */
    public record Sample(long epochMillis,
                         double px, double py, double pz,
                         double vx, double vy, double vz) {}

    /**
     * One measured attitude: UTC epoch + the raw ADCS quaternion components
     * {@code (q1,q2,q3,q4)} exactly as read from the source (WOD
     * {@code SW_TM_ADCS_EST_ATTD_Q1..Q4}). Interpretation (scalar position,
     * rotation direction) is applied downstream, not here.
     */
    public record AttitudeSample(long epochMillis, double q1, double q2, double q3, double q4) {}
}
