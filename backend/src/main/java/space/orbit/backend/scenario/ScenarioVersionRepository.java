package space.orbit.backend.scenario;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScenarioVersionRepository extends JpaRepository<ScenarioVersion, UUID> {

    Optional<ScenarioVersion> findByScenarioIdAndVersionNo(UUID scenarioId, int versionNo);

    List<ScenarioVersion> findByScenarioIdOrderByVersionNoAsc(UUID scenarioId);

    /** Highest version number for a scenario; {@code max + 1} is the next one. */
    @Query("select max(v.versionNo) from ScenarioVersion v where v.scenarioId = :scenarioId")
    Optional<Integer> findMaxVersionNo(@Param("scenarioId") UUID scenarioId);

    long countByScenarioId(UUID scenarioId);
}
