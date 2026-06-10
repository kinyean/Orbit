package space.orbit.backend.scenario;

/**
 * A semantically invalid scenario the request shape couldn't catch:
 * deputies-require-chief, end ≤ start, a NORAD id not resolvable in the
 * catalog, a duplicate/self-referential deputy → 422.
 */
public class ScenarioValidationException extends RuntimeException {

    public ScenarioValidationException(String message) {
        super(message);
    }
}
