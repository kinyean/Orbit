package space.orbit.backend.scenario;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {

    /** Owner's live scenarios, newest first (the scenario-panel list). */
    List<Scenario> findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID ownerId);

    /** Active scenario by (owner, name) — used to detect duplicates pre-flight. */
    Optional<Scenario> findByOwnerIdAndNameAndDeletedAtIsNull(UUID ownerId, String name);
}
