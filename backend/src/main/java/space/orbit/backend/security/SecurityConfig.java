package space.orbit.backend.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security configuration.
 *
 * <p>Phase 1 posture: <strong>permitAll + dev user principal</strong>. Every
 * request passes through the Spring Security pipeline (so audit and RBAC
 * hooks can attach to it later), but no auth is enforced and a fixed dev
 * user is injected by {@link DevUserAuthenticationFilter}.
 *
 * <p>The swap to real OIDC/SAML (Phase 10) is a single change: replace
 * {@link DevUserAuthenticationFilter} with the IdP-backed filter and tighten
 * the {@code authorizeHttpRequests} rules. The filter chain shape stays.
 *
 * <p>See decisions.md §16 (enterprise posture) and the deferred decisions
 * list for the IdP choice.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DevUserAuthenticationFilter devUserFilter) throws Exception {
        return http
                .cors(c -> {})                       // uses corsConfigurationSource bean
                .csrf(c -> c.disable())              // stateless JSON API
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Phase 1: every request is permitted. Phase 10
                        // tightens this with role-aware rules.
                        .anyRequest().permitAll())
                // Inject the dev user into the security context before the
                // anonymous filter would create an anonymous principal.
                .addFilterBefore(devUserFilter, AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${CORS_ALLOWED_ORIGINS:*}") String allowedOrigins) {
        var cfg = new CorsConfiguration();
        // Origin PATTERNS (not origins): the browser's origin is the frontend's
        // host:port (e.g. http://<server-ip>:5174), which varies and is never the
        // backend's. Patterns are also required to coexist with allowCredentials.
        // Default "*" for dev; lock down via CORS_ALLOWED_ORIGINS in Phase 10.
        // This gate matters for the WebSocket handshake too — browsers always send
        // Origin on WS upgrades, so a too-narrow list 403s the catalog stream.
        cfg.setAllowedOriginPatterns(Arrays.asList(allowedOrigins.split(",")));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Location"));
        cfg.setAllowCredentials(true);

        var src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
