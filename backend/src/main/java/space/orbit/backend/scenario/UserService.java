package space.orbit.backend.scenario;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the Spring Security principal to a persisted {@link User} row, which
 * supplies {@code owner_id} / {@code author_id} / {@code actor_id} for scenario
 * mutations and the audit log.
 *
 * <p>In {@code stub} mode the principal is the dev user ({@code dev@orbit.local},
 * seeded by V2). In {@code oidc} mode (Phase 10) a real OIDC principal
 * self-provisions on first sight via {@link #getOrCreateByEmail}, and its
 * {@code sub} claim + realm roles are synced onto the row (additive — Decision 16).
 */
@Service
public class UserService {

    /** App roles we persist (the IdP may carry others we ignore, e.g. offline_access). */
    private static final Set<String> APP_ROLES =
            Set.of("mission_planner", "flight_dynamics_engineer", "admin");

    private final UserRepository users;
    private final UserProvisioner provisioner;

    public UserService(UserRepository users, UserProvisioner provisioner) {
        this.users = users;
        this.provisioner = provisioner;
    }

    /** The current request's user, provisioning a row on first sight and syncing IdP claims. */
    @Transactional
    public User currentUser() {
        Authentication auth = requireAuthentication();
        User user = getOrCreateByEmail(auth.getName());
        if (auth instanceof JwtAuthenticationToken jwt) {
            syncFromToken(user, jwt);
        }
        return user;
    }

    /**
     * Resolve by email, provisioning a row on first sight. Creation is delegated to
     * {@link UserProvisioner} (its own committed transaction + one
     * {@link UserProvisionedEvent} — the per-user demo-seeding hook, Phase 11).
     */
    @Transactional
    public User getOrCreateByEmail(String email) {
        return users.findByEmail(email).orElseGet(() -> provisioner.provision(email));
    }

    /**
     * Resolve a user by email <em>without</em> provisioning a row — for the
     * scenario WebSocket path, which must not create users as a side effect of a
     * stream connect. Empty when no such user exists.
     */
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return users.findByEmail(email);
    }

    /**
     * Resolve id → email for a set of user ids in one query (Phase 10 audit-log /
     * version-history UI, so actor/author rows show a readable email, not a UUID).
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> emailsByIds(Collection<UUID> ids) {
        Map<UUID, String> byId = new HashMap<>();
        for (User u : users.findAllById(ids)) {
            byId.put(u.getId(), u.getEmail());
        }
        return byId;
    }

    /**
     * Mirror the OIDC token's {@code sub} + granted app roles onto the row so the
     * DB reflects the current IdP state (used by the audit-log UI + any later
     * app-role reporting). Runtime authorization uses the token authorities, not
     * this column — this is a record-keeping sync, saved only when something changed.
     */
    private void syncFromToken(User user, JwtAuthenticationToken jwt) {
        boolean changed = false;

        String sub = jwt.getToken().getSubject();
        if (sub != null && !sub.equals(user.getSsoSubject())) {
            user.setSsoSubject(sub);
            changed = true;
        }

        List<String> roles = jwt.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()).toLowerCase(Locale.ROOT))
                .filter(APP_ROLES::contains)
                .sorted()
                .toList();
        if (!roles.equals(user.getRoles())) {
            user.setRoles(roles);
            changed = true;
        }

        if (changed) {
            users.save(user);
        }
    }

    private Authentication requireAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new IllegalStateException("No authenticated principal on the request");
        }
        return auth;
    }
}
