package space.orbit.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import space.orbit.backend.analysis.MonteCarloService;
import space.orbit.backend.analysis.RendezvousSearchService;
import space.orbit.backend.analysis.ScreeningService;
import space.orbit.backend.api.ScenarioController;
import space.orbit.backend.scenario.ManeuverTemplateService;
import space.orbit.backend.scenario.ScenarioService;

/**
 * Phase 10 (US-AUTH-02/03) security tests: with {@code orbit.auth.mode=oidc} the
 * OAuth2 resource-server chain enforces authentication + role-based capability
 * gating. Web slice (filters ON, services mocked) so it needs no DB/Testcontainers;
 * {@code .with(jwt())} injects a token authority set directly, bypassing the decoder.
 */
@WebMvcTest(ScenarioController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "orbit.auth.mode=oidc")
class SecurityConfigTests {

    @Autowired
    private MockMvc mvc;

    // Present so the oauth2ResourceServer().jwt() chain wires up; never invoked
    // because .with(jwt()) injects the authentication directly.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean private ScenarioService service;
    @MockitoBean private ManeuverTemplateService templates;
    @MockitoBean private ScreeningService screening;
    @MockitoBean private RendezvousSearchService rendezvousSearch;
    @MockitoBean private MonteCarloService monteCarlo;
    @MockitoBean private space.orbit.backend.io.OemExportService oemExport;

    private static final String VALID_BODY = """
            {"name":"Rendezvous","fidelity":"sgp4",
             "timeRange":{"start":"2024-06-01T00:00:00Z","end":"2024-06-02T00:00:00Z"},
             "chief":{"noradId":25544},"deputies":[{"noradId":33591}]}
            """;

    @Test
    void unauthenticatedReadIs401() throws Exception {
        mvc.perform(get("/scenarios")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedReadIs200() throws Exception {
        Mockito.when(service.list()).thenReturn(List.of());
        mvc.perform(get("/scenarios").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void mutationWithoutRoleIs403() throws Exception {
        // Authenticated but no operator role → forbidden (capability gate).
        mvc.perform(post("/scenarios").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void mutationWithOperatorRoleIs201() throws Exception {
        Mockito.when(service.create(Mockito.any())).thenReturn(null);
        mvc.perform(post("/scenarios")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MISSION_PLANNER")))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void realmRolesMapToPrefixedUppercaseAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .claim("email", "maya@orbit.local")
                .claim("realm_access", Map.of("roles", List.of("mission_planner", "offline_access")))
                .build();

        List<String> authorities = SecurityConfig.realmRoleAuthorities(jwt).stream()
                .map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities)
                .contains("ROLE_MISSION_PLANNER", "ROLE_OFFLINE_ACCESS");
    }
}
