package space.orbit.backend.scenario;

/**
 * Web-agnostic input to {@link ScenarioService#setLinkBudget} (Phase 9D, US-EVT-05). The
 * controller maps a validated request DTO into this; a null draft clears the sensor's link
 * budget. {@code kind ∈ {rf, optical}}. See {@link ScenarioBody.LinkBudget} for the model.
 */
public record LinkBudgetDraft(
        String kind,
        double eirpDbw,
        double gOverTdbK,
        double frequencyGhz,
        double bandwidthHz,
        double thresholdDb) {
}
