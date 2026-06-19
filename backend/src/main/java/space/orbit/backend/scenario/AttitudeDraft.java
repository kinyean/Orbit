package space.orbit.backend.scenario;

/**
 * Web-agnostic input to {@link ScenarioService#setAttitude} (Phase 7, US-PROX-01).
 * Sets a host's (chief or deputy) attitude profile. {@code mode} is {@code "lvlh"}
 * (modeled LVLH-aligned attitude; {@code quaternion} ignored) or {@code "fixed"}
 * (constant ECI→body orientation given by {@code quaternion} as x,y,z,w).
 */
public record AttitudeDraft(
        int noradId,
        String mode,
        double[] quaternion) {
}
