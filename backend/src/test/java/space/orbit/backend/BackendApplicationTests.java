package space.orbit.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full-context smoke test. Runs against an ephemeral Postgres (Testcontainers)
 * so Flyway migrations + JPA {@code ddl-auto=validate} exercise the real schema
 * — the entity mappings (TEXT[], jsonb, the scenario tables) must validate for
 * the context to load. No manually-running DB required.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
