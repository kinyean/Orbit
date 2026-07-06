package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import space.orbit.backend.TestcontainersConfiguration;

/**
 * Per-user demo seeding (Phase 11, US-UX-01 / SRS §5.6.1): provisioning a new user
 * — the {@link UserProvisioner} → {@link UserProvisionedEvent} →
 * {@link SampleScenarioSeeder} chain — gives them the full demo set exactly once,
 * each demo audited with a {@code SEED} row. Full context against a real Postgres
 * (Testcontainers) because the chain crosses transaction boundaries
 * ({@code REQUIRES_NEW} + {@code AFTER_COMMIT}) that mocks can't exercise.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserProvisioningSeedTests {

    private static final List<String> DEMO_NAMES = List.of(
            SampleScenarioSeeder.NAME,
            SampleScenarioSeeder.RENDEZVOUS_NAME,
            SampleScenarioSeeder.SENSOR_NAME,
            SampleScenarioSeeder.ECLIPSE_NAME,
            SampleScenarioSeeder.VBAR_NAME);

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository users;
    @Autowired
    private ScenarioRepository scenarios;
    @Autowired
    private AuditLogRepository auditLog;
    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void provisioningANewUserSeedsTheDemoSetOnceWithAuditRows() {
        User gita = userService.getOrCreateByEmail("gita@provision.test");

        List<Scenario> mine =
                scenarios.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(gita.getId());
        assertThat(mine).extracting(Scenario::getName)
                .containsExactlyInAnyOrderElementsOf(DEMO_NAMES);
        for (Scenario s : mine) {
            List<AuditLog> trail = auditLog.findByScenarioIdOrderByTimestampDesc(s.getId());
            assertThat(trail).hasSize(1);
            assertThat(trail.get(0).getAction()).isEqualTo("SEED");
            assertThat(trail.get(0).getActorId()).isEqualTo(gita.getId());
        }

        // A second resolve is a pure read — no duplicate rows, no duplicate demos.
        User again = userService.getOrCreateByEmail("gita@provision.test");
        assertThat(again.getId()).isEqualTo(gita.getId());
        assertThat(scenarios.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(gita.getId()))
                .hasSize(DEMO_NAMES.size());
    }

    @Test
    void provisioningInsideAReadOnlyTransactionStillPersists() {
        // Regression for the pre-Phase-11 quirk: provisioning was reached from
        // read paths (e.g. ScenarioService.list, readOnly=true), where the inline
        // save joined the read-only transaction and was not reliably flushed. The
        // provisioner's REQUIRES_NEW commits independently of the surrounding tx.
        TransactionTemplate readOnly = new TransactionTemplate(txManager);
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> userService.getOrCreateByEmail("frank@provision.test"));

        User frank = users.findByEmail("frank@provision.test").orElseThrow();
        assertThat(scenarios.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(frank.getId()))
                .extracting(Scenario::getName)
                .containsExactlyInAnyOrderElementsOf(DEMO_NAMES);
    }

    @Test
    void startupSeedGaveTheDevUserTheDemoSet() {
        User dev = users.findByEmail("dev@orbit.local").orElseThrow();
        assertThat(scenarios.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(dev.getId()))
                .extracting(Scenario::getName)
                .containsAll(DEMO_NAMES);
    }
}
