package space.orbit.backend.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security configuration — two interchangeable filter chains selected by
 * {@code orbit.auth.mode} (Decision 16 / Decision 28, US-AUTH-02/03):
 *
 * <ul>
 *   <li><strong>{@code stub}</strong> (default) — the Phase-1 posture: every
 *   request passes through the pipeline but nothing is enforced and a fixed dev
 *   user is injected by {@link DevUserAuthenticationFilter}. Keeps local dev
 *   working with zero IdP.</li>
 *   <li><strong>{@code oidc}</strong> — an OAuth2 <em>resource server</em> that
 *   validates OIDC bearer JWTs (issuer-uri from env), maps the Keycloak realm
 *   roles to {@code ROLE_*} authorities, and enforces authentication + role
 *   rules. Stateless — no sessions, no CSRF (bearer tokens).</li>
 * </ul>
 *
 * <p>Per-record scenario ownership is enforced independently in
 * {@code ScenarioService.activeScenario()} (owned ∧ not-deleted → else 404), so
 * these rules only add coarse authentication + capability gating on top.
 */
@Configuration
public class SecurityConfig {

    /** Public endpoints (health, API docs) — permitted in either mode. */
    private static final String[] PUBLIC_MATCHERS = {
            "/health", "/actuator/health/**", "/actuator/info",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    // --- stub mode (default): dev user + permit-all --------------------------

    @Bean
    @ConditionalOnProperty(name = "orbit.auth.mode", havingValue = "stub", matchIfMissing = true)
    public SecurityFilterChain stubFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(c -> {})                       // uses corsConfigurationSource bean
                .csrf(c -> c.disable())              // stateless JSON API
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Inject the dev user before the anonymous filter. Constructed here
                // (not a @Component) so it is never a global servlet filter — that
                // would leak into oidc mode. See DevUserAuthenticationFilter.
                .addFilterBefore(new DevUserAuthenticationFilter(), AnonymousAuthenticationFilter.class)
                .build();
    }

    // --- oidc mode: OAuth2 resource server + RBAC ----------------------------

    @Bean
    @ConditionalOnProperty(name = "orbit.auth.mode", havingValue = "oidc")
    public SecurityFilterChain oidcFilterChain(HttpSecurity http) throws Exception {
        // Allow the bearer token in the ?access_token= query parameter as well as
        // the Authorization header. Browsers can't set headers on a WebSocket
        // upgrade, so the stream handshakes carry the token in the query string;
        // this lets the normal filter chain authenticate them (the existing
        // ScenarioHandshakeInterceptor then reads request.getPrincipal()). The
        // query-string token is acceptable behind TLS (10C) with short-lived tokens.
        DefaultBearerTokenResolver bearerTokenResolver = new DefaultBearerTokenResolver();
        bearerTokenResolver.setAllowUriQueryParameter(true);

        return http
                .cors(c -> {})
                .csrf(c -> c.disable())              // bearer tokens, not cookies
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_MATCHERS).permitAll()
                        // Reads: any authenticated user (ownership still scopes them).
                        .requestMatchers(HttpMethod.GET, "/scenarios/**").authenticated()
                        // Mutations + analyses: an operator role (Maya/Frank/Gita).
                        .requestMatchers("/scenarios/**")
                            .hasAnyRole("MISSION_PLANNER", "FLIGHT_DYNAMICS_ENGINEER", "ADMIN")
                        // Stream handshakes: authenticated (token in ?access_token=).
                        .requestMatchers("/stream/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Maps a Keycloak JWT to a Spring {@code Authentication}: the principal
     * <em>name</em> is the {@code email} claim (so {@code UserService.currentEmail()}
     * keeps returning an email unchanged), and {@code realm_access.roles} become
     * {@code ROLE_<UPPER>} authorities (so {@code hasRole("MISSION_PLANNER")} matches
     * the Keycloak role {@code mission_planner}).
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("email");
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::realmRoleAuthorities);
        return converter;
    }

    /** Package-private for unit testing the Keycloak realm-role → authority mapping. */
    static Collection<GrantedAuthority> realmRoleAuthorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                authorities.add(new SimpleGrantedAuthority(
                        "ROLE_" + String.valueOf(role).toUpperCase(Locale.ROOT)));
            }
        }
        return authorities;
    }

    // --- CORS (shared) -------------------------------------------------------

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @org.springframework.beans.factory.annotation.Value("${CORS_ALLOWED_ORIGINS:*}") String allowedOrigins) {
        var cfg = new CorsConfiguration();
        // Origin PATTERNS (not origins): the browser's origin is the frontend's
        // host:port, which varies and is never the backend's. Patterns are also
        // required to coexist with allowCredentials. Default "*" for dev; lock down
        // via CORS_ALLOWED_ORIGINS in production. This gate matters for the
        // WebSocket handshake too — browsers always send Origin on WS upgrades.
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
