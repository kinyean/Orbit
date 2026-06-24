package space.orbit.backend.analysis;

/**
 * A craft's already-sampled <strong>geocentric ECI</strong> position trajectory,
 * for environment analysis that lives in the inertial frame — eclipse
 * (umbra/penumbra) detection (Phase 8). Deliberately separate from the LVLH
 * {@link SampledCraft} (whose {@code pos} is chief-relative R/I/C) so the
 * frame split is enforced at the type level (R15): a method taking a
 * {@code SampledGeocentricCraft} cannot be fed LVLH samples by mistake.
 *
 * <p>Positions are captured for free inside the existing per-step sampling loop
 * ({@code depEci.getPosition()} for deputies; the chief's own ECI state) in
 * increasing-time order — never a second propagation (Decision 24, R11).
 *
 * @param noradId   NORAD id
 * @param posEci    flat geocentric ECI samples {@code [t, x, y, z, ...]} (metres,
 *                  EME2000), {@code t} seconds relative to the scenario epoch
 * @param posStride sample stride of {@code posEci} (always 4 — position only)
 */
public record SampledGeocentricCraft(int noradId, double[] posEci, int posStride) {

    public SampledGeocentricCraft(int noradId, double[] posEci) {
        this(noradId, posEci, 4);
    }
}
