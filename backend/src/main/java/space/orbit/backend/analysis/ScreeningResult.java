package space.orbit.backend.analysis;

import java.util.List;

/**
 * The result of a catalog conjunction screening (Phase 8, US-EVT-02 / UC-7): the
 * sorted close approaches plus run metadata. Tagged with {@code ranAt} (the live
 * catalog is refreshed every ~6 h, so a screening is a snapshot analysis, NOT a
 * reproducible scenario artifact — R11 caveat in the Phase-8 plan).
 *
 * @param thresholdM    the miss-distance threshold used, metres
 * @param ranAt         ISO-8601 UTC instant the screening ran (catalog as-of)
 * @param catalogSize   number of catalog satellites screened against
 * @param candidateCount survivors of the coarse shell prune that were fine-sampled
 * @param conjunctions  close approaches below the threshold, closest first
 */
public record ScreeningResult(
        double thresholdM,
        String ranAt,
        int catalogSize,
        int candidateCount,
        List<ConjunctionResult> conjunctions) {
}
