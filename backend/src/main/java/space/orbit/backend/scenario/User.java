package space.orbit.backend.scenario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * An operator identity (the RBAC seam, Decision 16). Maps to {@code users}
 * (V1__init.sql). Auth is stubbed in Phase 1–3 — the dev user is seeded by
 * V2__seed_dev_user.sql; a real OIDC principal self-provisions via
 * {@link UserService#getOrCreateByEmail} in Phase 10.
 *
 * <p>Mapping notes: {@code roles TEXT[]} → {@code List<String>} via
 * {@link JdbcTypeCode}{@code (ARRAY)}; the DB-defaulted timestamps are read
 * back after insert ({@code insertable=false} + {@link Generated}).
 */
@Entity
@Table(name = "users")
public class User extends AbstractUuidEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String email;

    @Column(name = "sso_subject")
    private String ssoSubject;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> roles;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected User() {
        // for JPA
    }

    public User(UUID id, String email, List<String> roles) {
        this.id = id;
        this.email = email;
        this.roles = roles;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getSsoSubject() {
        return ssoSubject;
    }

    /** Set the OIDC subject claim (Phase 10) — synced from the token on login. */
    public void setSsoSubject(String ssoSubject) {
        this.ssoSubject = ssoSubject;
    }

    public List<String> getRoles() {
        return roles;
    }

    /** Replace the role set (Phase 10) — synced from the IdP's realm roles on login. */
    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
