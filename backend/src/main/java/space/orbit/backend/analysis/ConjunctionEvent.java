package space.orbit.backend.analysis;

import java.time.Instant;

/**
 * An intra-scenario conjunction (Phase 8, US-EVT-02 / SRS §3.12.1): the closest
 * approach of an unordered pair of scenario craft when it falls below the
 * configured miss-distance threshold. Computed pairwise on the already-sampled
 * LVLH trajectories (range is frame-invariant) + a golden-section refine on the
 * samples — no re-propagation, deterministic (R11), like {@link SensorEvent}.
 *
 * <p>The pair is canonical ({@code aNoradId < bNoradId}) so the same pair is never
 * reported twice.
 *
 * @param aNoradId      lower NORAD id of the pair
 * @param bNoradId      higher NORAD id of the pair
 * @param tcaEpoch      UTC instant of closest approach (refined)
 * @param missDistanceM separation at {@code tcaEpoch}, metres
 */
public record ConjunctionEvent(int aNoradId, int bNoradId, Instant tcaEpoch, double missDistanceM) {
}
