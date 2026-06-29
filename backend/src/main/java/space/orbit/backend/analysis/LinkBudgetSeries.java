package space.orbit.backend.analysis;

/**
 * A sensor↔target link-budget SNR time-series (Phase 9D, US-EVT-05), computed on the
 * already-sampled trajectory in the chief-LVLH scene (no re-propagation), like the other
 * {@code analysis/} computers. {@code series} is a flat {@code [t0,snr0, t1,snr1, …]}
 * (seconds from the scenario epoch, dB); the frontend draws it as a timeline band (red
 * below {@code thresholdDb}) and interpolates it for a live readout.
 *
 * @param hostNoradId  craft carrying the sensor
 * @param sensorId     the sensor's id
 * @param targetNoradId the observed craft
 * @param kind         {@code "rf"} or {@code "optical"}
 * @param thresholdDb  detection SNR floor
 * @param series       flat {@code [t,snr, …]} samples
 */
public record LinkBudgetSeries(
        int hostNoradId,
        String sensorId,
        int targetNoradId,
        String kind,
        double thresholdDb,
        double[] series) {
}
