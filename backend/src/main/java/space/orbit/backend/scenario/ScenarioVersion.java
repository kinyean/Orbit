package space.orbit.backend.scenario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * An immutable snapshot of a scenario's contents — one row per edit (Git-style
 * history; SRS §3.10.5, §5.4.2). Maps to {@code scenario_versions} (V1__init.sql).
 *
 * <p>{@code body} is the {@code jsonb} scenario payload. It is mapped as a raw
 * {@link String} via {@link JdbcTypeCode}{@code (JSON)} so the evolving body
 * schema stays OUT of the validated entity — {@link ScenarioService} owns
 * (de)serialization to/from {@link ScenarioBody}.
 *
 * <p>No setters: a version is never mutated. New edits insert a new row with
 * {@code versionNo = max + 1}.
 */
@Entity
@Table(name = "scenario_versions")
public class ScenarioVersion extends AbstractUuidEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "scenario_id", nullable = false, updatable = false)
    private UUID scenarioId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private String body;

    protected ScenarioVersion() {
        // for JPA
    }

    public ScenarioVersion(UUID id, UUID scenarioId, int versionNo, UUID authorId, String body) {
        this.id = id;
        this.scenarioId = scenarioId;
        this.versionNo = versionNo;
        this.authorId = authorId;
        this.body = body;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getScenarioId() {
        return scenarioId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getBody() {
        return body;
    }
}
