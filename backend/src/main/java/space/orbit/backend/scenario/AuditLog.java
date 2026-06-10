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
 * The "who did what when" trail (SRS §5.4.2). Maps to {@code audit_log}
 * (V1__init.sql). Written by {@link ScenarioService} — exactly one row per
 * mutating call, in the same transaction as the change (Decision 16).
 *
 * <p>{@code scenarioId} / {@code versionId} are nullable to allow
 * non-scenario actions later; {@code action} is a free VARCHAR so new action
 * types don't need a migration.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog extends AbstractUuidEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "scenario_id", updatable = false)
    private UUID scenarioId;

    @Column(name = "version_id", updatable = false)
    private UUID versionId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Generated(event = EventType.INSERT)
    @Column(name = "timestamp", insertable = false, updatable = false)
    private OffsetDateTime timestamp;

    @Column(name = "diff_summary", updatable = false)
    private String diffSummary;

    protected AuditLog() {
        // for JPA
    }

    public AuditLog(UUID id, UUID scenarioId, UUID versionId, UUID actorId, String action, String diffSummary) {
        this.id = id;
        this.scenarioId = scenarioId;
        this.versionId = versionId;
        this.actorId = actorId;
        this.action = action;
        this.diffSummary = diffSummary;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public String getDiffSummary() {
        return diffSummary;
    }
}
