package space.orbit.backend.scenario;

/** The owner already has an active scenario with this name (UNIQUE owner_id,name) → 409. */
public class DuplicateScenarioNameException extends RuntimeException {

    public DuplicateScenarioNameException(String name) {
        super("A scenario named \"" + name + "\" already exists");
    }
}
