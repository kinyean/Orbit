package space.orbit.backend.stream;

/**
 * The scenario exists and is readable, but its body cannot be turned into a
 * stream: an unsupported fidelity (CW, Phase 5), an initial state that is not a
 * TLE, or a TLE that fails to parse. The handler maps this to close code
 * {@link StreamContract#CLOSE_UNPROCESSABLE} (4422).
 */
public class ScenarioStreamUnprocessableException extends RuntimeException {

    public ScenarioStreamUnprocessableException(String message) {
        super(message);
    }
}
