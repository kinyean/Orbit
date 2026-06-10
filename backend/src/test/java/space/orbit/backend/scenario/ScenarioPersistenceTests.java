package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import space.orbit.backend.TestcontainersConfiguration;

/**
 * Repository + migration slice against a real Postgres (Testcontainers). Proves
 * the things {@code ddl-auto=validate} alone can't: Flyway applies, the dev user
 * is seeded, the per-owner uniqueness constraint bites, version numbers track,
 * and the PG-only {@code jsonb} / {@code TEXT[]} columns round-trip through JPA.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class ScenarioPersistenceTests {

    /** Mirrors DevUserAuthenticationFilter.DEV_USER_ID (seeded by V2). */
    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private UserRepository users;
    @Autowired
    private ScenarioRepository scenarios;
    @Autowired
    private ScenarioVersionRepository versions;
    @Autowired
    private TestEntityManager em;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void migrationsApplyAndSeedDevUser() {
        var dev = users.findByEmail("dev@orbit.local");
        assertThat(dev).isPresent();
        assertThat(dev.get().getId()).isEqualTo(DEV_USER);
        assertThat(dev.get().getRoles()).contains("mission_planner", "admin");
        // DB-defaulted created_at is read back after insert (@Generated).
        assertThat(dev.get().getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateScenarioNamePerOwner() {
        scenarios.saveAndFlush(new Scenario(UUID.randomUUID(), DEV_USER, "Rendezvous"));
        assertThatThrownBy(() ->
                scenarios.saveAndFlush(new Scenario(UUID.randomUUID(), DEV_USER, "Rendezvous")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reusesNameAfterSoftDelete() {
        // Create a scenario, then archive it (soft-delete).
        Scenario first = new Scenario(UUID.randomUUID(), DEV_USER, "Reusable");
        scenarios.saveAndFlush(first);
        first.setDeletedAt(OffsetDateTime.now());
        scenarios.saveAndFlush(first);

        // The freed name can be taken by a new active scenario (V4: active-only uniqueness)...
        scenarios.saveAndFlush(new Scenario(UUID.randomUUID(), DEV_USER, "Reusable"));

        // ...but two *active* scenarios still cannot share a name.
        assertThatThrownBy(() ->
                scenarios.saveAndFlush(new Scenario(UUID.randomUUID(), DEV_USER, "Reusable")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void maxVersionNoIncrementsPerScenario() {
        UUID scenarioId = UUID.randomUUID();
        scenarios.saveAndFlush(new Scenario(scenarioId, DEV_USER, "Versioned"));
        assertThat(versions.findMaxVersionNo(scenarioId)).isEmpty();

        versions.saveAndFlush(new ScenarioVersion(UUID.randomUUID(), scenarioId, 1, DEV_USER, "{\"schemaVersion\":1}"));
        versions.saveAndFlush(new ScenarioVersion(UUID.randomUUID(), scenarioId, 2, DEV_USER, "{\"schemaVersion\":1}"));

        assertThat(versions.findMaxVersionNo(scenarioId)).contains(2);
        assertThat(versions.countByScenarioId(scenarioId)).isEqualTo(2);
    }

    @Test
    void roundTripsTextArrayRoles() {
        UUID userId = UUID.randomUUID();
        users.saveAndFlush(new User(userId, "frank@orbit.local",
                List.of("flight_dynamics_engineer", "admin")));
        em.flush();
        em.clear(); // force a DB read, not the first-level cache

        User reloaded = users.findById(userId).orElseThrow();
        assertThat(reloaded.getRoles()).containsExactly("flight_dynamics_engineer", "admin");
    }

    @Test
    void roundTripsJsonbBody() throws Exception {
        UUID scenarioId = UUID.randomUUID();
        scenarios.saveAndFlush(new Scenario(scenarioId, DEV_USER, "JsonbRoundTrip"));
        UUID versionId = UUID.randomUUID();
        String body = "{\"schemaVersion\":1,\"fidelity\":\"sgp4\",\"deputies\":[1,2,3]}";
        versions.saveAndFlush(new ScenarioVersion(versionId, scenarioId, 1, DEV_USER, body));
        em.flush();
        em.clear();

        String stored = versions.findById(versionId).orElseThrow().getBody();
        // jsonb normalizes whitespace/key order, so compare as a tree, not bytes.
        JsonNode node = mapper.readTree(stored);
        assertThat(node.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(node.get("fidelity").asText()).isEqualTo("sgp4");
        assertThat(node.get("deputies")).hasSize(3);
    }
}
