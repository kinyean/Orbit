package space.orbit.backend.scenario;

import java.util.UUID;

/** A scenario (or version) doesn't exist, is archived, or isn't the caller's → 404. */
public class ScenarioNotFoundException extends RuntimeException {

    public ScenarioNotFoundException(UUID id) {
        super("Scenario not found: " + id);
    }

    public ScenarioNotFoundException(String message) {
        super(message);
    }
}
