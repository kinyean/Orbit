package space.orbit.backend.scenario;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /** All actions against a scenario, newest first (audit-log UI lands in Phase 10). */
    List<AuditLog> findByScenarioIdOrderByTimestampDesc(UUID scenarioId);
}
