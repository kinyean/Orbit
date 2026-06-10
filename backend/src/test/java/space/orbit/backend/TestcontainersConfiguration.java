package space.orbit.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A throwaway Postgres for tests that touch the DB. The schema uses PG-only
 * features ({@code TEXT[]}, {@code jsonb}, {@code gen_random_uuid()}) that H2
 * can't emulate, so we run against a real Postgres in a container.
 *
 * <p>{@code @ServiceConnection} auto-points Spring's datasource at the container
 * (URL/user/password), and the container is started/stopped with the
 * application context (no {@code @Testcontainers}/{@code @Container} needed —
 * Boot manages the lifecycle of container beans). Image matches docker-compose
 * ({@code postgres:16}). Requires a reachable Docker daemon.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    }
}
