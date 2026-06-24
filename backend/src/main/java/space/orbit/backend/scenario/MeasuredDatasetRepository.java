package space.orbit.backend.scenario;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for imported measured ephemerides ({@link MeasuredDataset}). */
public interface MeasuredDatasetRepository extends JpaRepository<MeasuredDataset, UUID> {
}
