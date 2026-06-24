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
 * An imported measured ephemeris (the stored {@code MeasuredDataset} artifact —
 * Decision: measured-data ingestion). Holds a satellite's real position/velocity
 * samples as a compressed binary blob ({@link MeasuredDatasetCodec}), kept OUT of
 * the small jsonb scenario body; a scenario role references it by id via
 * {@code InitialState{kind:"ephemeris", datasetId}}. Immutable + content-hashed,
 * so a scenario that references it reproduces byte-for-byte (R11) — the
 * larger-artifact analogue of the frozen-TLE snapshot (Decision 19).
 *
 * <p>Service-assigned UUID PK + explicit FK columns, mirroring {@link Scenario}.
 * Maps to {@code measured_dataset} (V5__measured_dataset.sql).
 */
@Entity
@Table(name = "measured_dataset")
public class MeasuredDataset extends AbstractUuidEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "satellite_name", nullable = false, updatable = false)
    private String satelliteName;

    @Column(name = "norad_id", updatable = false)
    private Integer noradId;

    @Column(nullable = false, updatable = false)
    private String frame;

    @Column(name = "start_utc", nullable = false, updatable = false)
    private OffsetDateTime startUtc;

    @Column(name = "end_utc", nullable = false, updatable = false)
    private OffsetDateTime endUtc;

    @Column(name = "sample_count", nullable = false, updatable = false)
    private int sampleCount;

    @Column(name = "source_name", updatable = false)
    private String sourceName;

    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

    @Column(nullable = false, updatable = false)
    private byte[] samples;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MeasuredDataset() {
        // for JPA
    }

    public MeasuredDataset(UUID id, UUID ownerId, String satelliteName, Integer noradId, String frame,
                           OffsetDateTime startUtc, OffsetDateTime endUtc, int sampleCount,
                           String sourceName, String contentHash, byte[] samples) {
        this.id = id;
        this.ownerId = ownerId;
        this.satelliteName = satelliteName;
        this.noradId = noradId;
        this.frame = frame;
        this.startUtc = startUtc;
        this.endUtc = endUtc;
        this.sampleCount = sampleCount;
        this.sourceName = sourceName;
        this.contentHash = contentHash;
        this.samples = samples;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getSatelliteName() {
        return satelliteName;
    }

    public Integer getNoradId() {
        return noradId;
    }

    public String getFrame() {
        return frame;
    }

    public OffsetDateTime getStartUtc() {
        return startUtc;
    }

    public OffsetDateTime getEndUtc() {
        return endUtc;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getContentHash() {
        return contentHash;
    }

    public byte[] getSamples() {
        return samples;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
