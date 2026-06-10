package space.orbit.backend.scenario;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Base for entities with a <em>service-assigned</em> UUID primary key
 * (generated via {@code UUID.randomUUID()} before insert — we need the id ahead
 * of the circular {@code scenarios.latest_version_id} ↔ {@code scenario_versions.id}
 * FK; see the scenario package + V1__init.sql).
 *
 * <p>Spring Data JPA decides INSERT-vs-UPDATE from {@link #isNew()}. Its default
 * (id != null ⇒ "not new" ⇒ {@code merge()}, which issues a wasteful pre-SELECT
 * and can mis-handle assigned ids) is wrong for assigned keys, so we track
 * persistence explicitly: an entity is "new" until it has been loaded or
 * persisted once. This makes {@code save()} call {@code persist()} for fresh
 * rows. (Vlad Mihalcea's assigned-identifier pattern.)
 */
@MappedSuperclass
public abstract class AbstractUuidEntity implements Persistable<UUID> {

    @Transient
    private boolean persisted = false;

    @Override
    public abstract UUID getId();

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }
}
