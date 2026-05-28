package space.orbit.backend.security;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Phase 1 stub authentication. Injects a fixed dev user into the Spring
 * Security context for every request, so:
 * <ul>
 *   <li>{@code SecurityContextHolder.getContext().getAuthentication()} is
 *   never anonymous in dev,</li>
 *   <li>downstream code (audit logging, scenario ownership) can read a
 *   real principal,</li>
 *   <li>the swap to real OIDC/SAML in Phase 10 only requires replacing this
 *   filter — the rest of the security pipeline stays.</li>
 * </ul>
 *
 * <p>This is <em>not</em> production-safe. It will be replaced wholesale at
 * Phase 10 (see decisions.md §16, US-AUTH-02/03).
 */
@Component
public class DevUserAuthenticationFilter extends OncePerRequestFilter {

    /** Stable identifier for the dev user across restarts. */
    public static final String DEV_USER_ID = "00000000-0000-0000-0000-000000000001";
    public static final String DEV_USER_EMAIL = "dev@orbit.local";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        var authorities = List.of(
                new SimpleGrantedAuthority("ROLE_MISSION_PLANNER"),
                new SimpleGrantedAuthority("ROLE_FLIGHT_DYNAMICS_ENGINEER"),
                new SimpleGrantedAuthority("ROLE_ADMIN"));

        // Use the dev email as the principal "name" — what request.getUserPrincipal().getName()
        // returns. The DB user-id maps via a separate lookup in code that needs it.
        var auth = new UsernamePasswordAuthenticationToken(DEV_USER_EMAIL, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            chain.doFilter(request, response);
        } finally {
            // Clear after the request to avoid leaking into pooled threads.
            SecurityContextHolder.clearContext();
        }
    }
}
