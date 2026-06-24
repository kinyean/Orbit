package space.orbit.backend.analysis;

/**
 * One catalog-screening conjunction (Phase 8, US-EVT-02 / UC-7): a scenario craft's
 * close approach to a third-party catalog satellite, below the screening threshold.
 * Unlike the intra-scenario {@link ConjunctionEvent} this is a one-shot REST analysis
 * result (not streamed), screened against the live SGP4 catalog.
 *
 * @param scenarioNoradId NORAD id of the scenario craft
 * @param scenarioName    display name of the scenario craft
 * @param catalogNoradId  NORAD id of the third-party catalog satellite
 * @param catalogName     display name of the catalog satellite
 * @param tcaEpoch        ISO-8601 UTC time of closest approach (refined)
 * @param missDistanceM   separation at {@code tcaEpoch}, metres
 */
public record ConjunctionResult(
        int scenarioNoradId,
        String scenarioName,
        int catalogNoradId,
        String catalogName,
        String tcaEpoch,
        double missDistanceM) {
}
