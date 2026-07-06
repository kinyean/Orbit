package space.orbit.backend.scenario;

import java.util.UUID;

/**
 * Published exactly once per user, when {@link UserProvisioner} creates the row on
 * first sight (Phase 11, US-UX-01). Fired inside the provisioning transaction;
 * {@link SampleScenarioSeeder} listens {@code AFTER_COMMIT} to seed the new user's
 * demo scenarios (§5.6.1 — every user, incl. real OIDC users, gets loadable samples).
 */
public record UserProvisionedEvent(UUID userId, String email) {}
