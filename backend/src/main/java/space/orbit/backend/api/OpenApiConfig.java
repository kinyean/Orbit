package space.orbit.backend.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata (Phase 11, US-UX-04 — SRS §4.3.3). Everything the
 * auto-generated spec can't know: what the service is, how auth works per mode,
 * and that the two streaming endpoints are WebSocket companions outside this
 * spec. The frontend's {@code gen:api} consumes this document, so annotations
 * here are doc-only — they never change the generated types.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI orbitOpenApi(@Value("${orbit.auth.mode:stub}") String authMode,
                         @Value("${info.app.version:0.1.0-dev}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("Orbit — Inter-Satellite RPO Platform API")
                        .version(version)
                        .description("""
                                REST API for the Orbit RPO visualization & simulation platform: \
                                scenario CRUD with immutable versioning + audit, maneuver templates, \
                                sensors/attitude/constraints, one-shot analyses (conjunction screening, \
                                rendezvous search, Monte Carlo), and CCSDS OEM export.

                                **Streaming companions (not in this spec):** `WS /stream/catalog` \
                                (shared live-catalog CZML) and `WS /stream/scenario/{id}` (per-scenario \
                                CZML + chief-LVLH relative state) carry gzip binary frames on streaming \
                                contract v1 — see docs/streaming-contract.md.

                                **Auth:** currently running in `%s` mode. In `oidc` mode every request \
                                needs a Bearer JWT (mutations need an operator role); WebSockets pass \
                                the token as `?access_token=`. In `stub` mode (local dev) requests run \
                                as a fixed dev user.""".formatted(authMode)))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("OIDC access token (oidc mode only)")));
    }
}
