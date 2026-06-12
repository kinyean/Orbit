package space.orbit.backend.scenario;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the Spring Security principal to a persisted {@link User} row, which
 * supplies {@code owner_id} / {@code author_id} / {@code actor_id} for scenario
 * mutations and the audit log.
 *
 * <p>In Phase 1–3 the principal is the stubbed dev user
 * ({@code dev@orbit.local}), seeded by V2__seed_dev_user.sql. The
 * {@link #getOrCreateByEmail} fallback means a real OIDC principal
 * self-provisions in Phase 10 with no code change here (additive — Decision 16).
 */
@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    /** The current request's user, provisioning a row on first sight. */
    @Transactional
    public User currentUser() {
        return getOrCreateByEmail(currentEmail());
    }

    @Transactional
    public User getOrCreateByEmail(String email) {
        return users.findByEmail(email)
                .orElseGet(() -> users.save(new User(UUID.randomUUID(), email, List.of())));
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

    /** The principal name — {@code dev@orbit.local} in dev (the email). */
    private String currentEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new IllegalStateException("No authenticated principal on the request");
        }
        return auth.getName();
    }
}
