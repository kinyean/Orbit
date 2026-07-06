package space.orbit.backend.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import space.orbit.backend.stream.StreamContract;

/**
 * /health — a small, predictable status endpoint for the frontend status chip
 * and any external uptime checks.
 *
 * <p>Returns {@code version}, {@code buildTime}, {@code dbStatus}, and the
 * streaming contract {@code contractVersion}. The frontend consumes this via
 * the OpenAPI-generated client.
 *
 * <p>Distinct from Spring Boot's {@code /actuator/health} — that one is a
 * generic ops endpoint; this one is a tailored contract for the UI.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    /**
     * Stream-contract version sent in the {@link HealthResponse}. Frontend
     * uses this to refuse connections on mismatch (R12, see risks.md). Single
     * source of truth is {@link StreamContract#VERSION}.
     */
    public static final String CONTRACT_VERSION = StreamContract.VERSION;

    private final DataSource dataSource;
    private final String appVersion;
    private final String buildTime;

    public HealthController(
            DataSource dataSource,
            @Value("${info.app.version:dev}") String appVersion,
            @Value("${info.app.buildTime:dev}") String buildTime) {
        this.dataSource = dataSource;
        this.appVersion = appVersion;
        this.buildTime = buildTime;
    }

    @Tag(name = "Health")
    @Operation(summary = "Service health, build info + streaming-contract version")
    @GetMapping
    public HealthResponse health() {
        return new HealthResponse(
                appVersion,
                buildTime,
                checkDb(),
                CONTRACT_VERSION,
                Instant.now().toString());
    }

    private String checkDb() {
        try (Connection c = dataSource.getConnection()) {
            // isValid uses a driver-level health check; 1s timeout is plenty for local Postgres.
            return c.isValid(1) ? "up" : "down";
        } catch (Exception e) {
            return "down";
        }
    }

    public record HealthResponse(
            String version,
            String buildTime,
            String dbStatus,
            String contractVersion,
            String serverTime) {}
}
