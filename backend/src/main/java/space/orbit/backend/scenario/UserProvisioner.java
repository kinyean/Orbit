package space.orbit.backend.scenario;

import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a {@link User} row on first sight of a new principal (Phase 11).
 *
 * <p>Split out of {@link UserService#getOrCreateByEmail} for two reasons:
 * <ul>
 *   <li><b>Reliable persistence.</b> Provisioning is most often reached from
 *       read paths (e.g. {@code ScenarioService.list()}), whose
 *       {@code @Transactional(readOnly = true)} transaction the old inline save
 *       would join — under Hibernate's read-only MANUAL flush the insert was not
 *       reliably persisted. {@code REQUIRES_NEW} commits the row independently.</li>
 *   <li><b>A single provisioning moment.</b> The committed transaction publishes
 *       {@link UserProvisionedEvent} exactly once per user — the hook the sample-
 *       scenario seeder uses to give every new user the demo set (US-UX-01, §5.6.1).</li>
 * </ul>
 */
@Component
public class UserProvisioner {

    private final UserRepository users;
    private final ApplicationEventPublisher events;

    public UserProvisioner(UserRepository users, ApplicationEventPublisher events) {
        this.users = users;
        this.events = events;
    }

    /**
     * Create (or return the raced-in) user row for {@code email}. Concurrent first
     * requests race on the {@code users.email} UNIQUE constraint — the loser
     * re-reads the winner's row, so exactly one row (and one event) exists per user.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User provision(String email) {
        return users.findByEmail(email).orElseGet(() -> {
            try {
                User created = users.saveAndFlush(new User(UUID.randomUUID(), email, List.of()));
                events.publishEvent(new UserProvisionedEvent(created.getId(), email));
                return created;
            } catch (DataIntegrityViolationException raced) {
                return users.findByEmail(email).orElseThrow(() -> raced);
            }
        });
    }
}
