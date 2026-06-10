package space.orbit.backend.api;

import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import space.orbit.backend.scenario.DuplicateScenarioNameException;
import space.orbit.backend.scenario.ScenarioNotFoundException;
import space.orbit.backend.scenario.ScenarioValidationException;

/**
 * Maps scenario service/validation failures to HTTP status codes for
 * {@link ScenarioController}:
 * <ul>
 *   <li>{@link ScenarioNotFoundException} → 404</li>
 *   <li>{@link DuplicateScenarioNameException} → 409</li>
 *   <li>{@link ScenarioValidationException} (semantic) and bean-validation
 *       failures on the request body → 422</li>
 * </ul>
 */
@RestControllerAdvice
public class ScenarioExceptionHandler {

    /** Uniform error body. */
    public record ApiError(int status, String error, String message) {
    }

    @ExceptionHandler(ScenarioNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ScenarioNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DuplicateScenarioNameException.class)
    public ResponseEntity<ApiError> conflict(DuplicateScenarioNameException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ScenarioValidationException.class)
    public ResponseEntity<ApiError> unprocessable(ScenarioValidationException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /** Bean-validation failures on the request DTO (e.g. blank name). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.UNPROCESSABLE_ENTITY,
                detail.isBlank() ? "Invalid request body" : detail);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), status.getReasonPhrase(), message));
    }
}
