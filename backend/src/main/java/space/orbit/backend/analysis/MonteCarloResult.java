package space.orbit.backend.analysis;

import java.util.List;

/**
 * Monte Carlo dispersion result for one deputy (Phase 9C, UC-6, US-MC-01/02): the
 * trajectory cloud + per-epoch covariance ellipsoids in the chief LVLH frame, plus an
 * optional approach-corridor breach summary. Deterministic given {@code (scenario, seed,
 * params)} — the seed is echoed so a run is reproducible (SRS §5.4.1).
 *
 * <p>Payload (R8 — no silent truncation): the covariance is computed from <em>all</em>
 * {@code sampleCount} samples (full statistic); only the drawn spaghetti is thinned to
 * {@code returnedTracks} of them. Each track is a flat {@code [x0,y0,z0, x1,y1,z1, …]} of
 * LVLH positions at every {@code cloudStride}-th grid step (step time =
 * {@code epochMs + j·cloudStride·stepSeconds·1000}).
 *
 * @param deputyNoradId  the dispersed deputy
 * @param name           its display name
 * @param seed           the RNG seed (echoed for reproducibility)
 * @param sampleCount    samples actually run
 * @param returnedTracks tracks included in {@code tracks} (≤ sampleCount)
 * @param ranAt          wall-clock instant of the run (informational)
 * @param epochMs        scenario start, ms since the Unix epoch
 * @param stepSeconds    base grid step
 * @param cloudStride    every n-th grid step is included in each track
 * @param tracks         downsampled relative-position spaghetti (LVLH, m)
 * @param ellipsoids     per-epoch dispersion ellipsoids
 * @param corridorBreaches steps whose 3-σ envelope leaves the approach corridor (may be 0)
 */
public record MonteCarloResult(
        int deputyNoradId,
        String name,
        long seed,
        int sampleCount,
        int returnedTracks,
        String ranAt,
        long epochMs,
        int stepSeconds,
        int cloudStride,
        List<double[]> tracks,
        List<EllipsoidSample> ellipsoids,
        int corridorBreaches) {
}
