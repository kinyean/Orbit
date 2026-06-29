package space.orbit.backend.analysis;

import java.util.List;

/**
 * The rendezvous arrival-time × revolution ΔV map (Phase 9A, US-MAN-03): a request→
 * response analysis (not the stream) the UI renders as a heatmap/table so the user can
 * pick a feasible/cheap transfer, which then feeds the differential corrector. A
 * two-body Lambert grid — deliberately the cheap selector, not the final answer.
 *
 * @param deputyNoradId the maneuvering deputy
 * @param windowStart   scenario window start (ISO-8601 UTC) — the fixed departure epoch
 * @param windowEnd     scenario window end (ISO-8601 UTC)
 * @param arrivalCount  number of arrival-epoch samples in the grid
 * @param revCount      number of revolution-count columns (0..revCount-1 attempted)
 * @param cells         every feasible cell, sorted cheapest-first by total ΔV
 * @param cheapest      the global minimum-ΔV cell (null if none feasible)
 */
public record RendezvousSearchResult(
        int deputyNoradId,
        String windowStart,
        String windowEnd,
        int arrivalCount,
        int revCount,
        List<DvCell> cells,
        DvCell cheapest) {
}
