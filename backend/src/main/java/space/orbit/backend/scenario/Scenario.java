package space.orbit.backend.scenario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * The mutable identity of a scenario (Decision 14) — like a Git repo whose
 * commits are {@link ScenarioVersion} rows. Maps to {@code scenarios}
 * (V1__init.sql), with {@code deleted_at} added by V3 for soft-delete.
 *
 * <p>{@code latest_version_id} is a denormalized pointer to the newest version;
 * it is null only in the brief window between the scenario insert and its v1
 * insert (the service resolves the circular FK in one transaction).
 *
 * <p>FK columns are plain {@code UUID} fields, not {@code @ManyToOne}
 * associations: we run {@code open-in-view=false} and resolve ids explicitly to
 * avoid lazy-loading traps.
 */
@Entity
@Table(name = "scenarios")
public class Scenario extends AbstractUuidEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String name;

    @Column(name = "latest_version_id")
    private UUID latestVersionId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected Scenario() {
        // for JPA
    }

    public Scenario(UUID id, UUID ownerId, String name) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getLatestVersionId() {
        return latestVersionId;
    }

    public void setLatestVersionId(UUID latestVersionId) {
        this.latestVersionId = latestVersionId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
