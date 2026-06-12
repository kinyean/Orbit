package space.orbit.backend.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.orbit.backend.catalog.CatalogService;
import space.orbit.backend.catalog.TleSnapshot;

/**
 * The single mutation path for scenarios (Decision 16). Every create/update/
 * delete is one transaction that writes the version row (immutable history) AND
 * exactly one {@link AuditLog} row — so the audit trail can never disagree with
 * the data, and a rollback drops both together.
 *
 * <p>Initial states are TLE snapshots frozen from the catalog at compose time
 * (Phase 3A); fidelity is {@code sgp4}. CCSDS/Keplerian sources and the
 * numerical/CW propagators are later phases.
 */
@Service
public class ScenarioService {

    private final ScenarioRepository scenarios;
    private final ScenarioVersionRepository versions;
    private final AuditLogRepository auditLog;
    private final UserService userService;
    private final CatalogService catalog;
    private final ObjectMapper objectMapper;

    public ScenarioService(ScenarioRepository scenarios,
                           ScenarioVersionRepository versions,
                           AuditLogRepository auditLog,
                           UserService userService,
                           CatalogService catalog,
                           ObjectMapper objectMapper) {
        this.scenarios = scenarios;
        this.versions = versions;
        this.auditLog = auditLog;
        this.userService = userService;
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    // --- commands -------------------------------------------------------------

    @Transactional
    public ScenarioResponse create(ScenarioDraft draft) {
        User owner = userService.currentUser();
        ScenarioBody body = buildBody(draft);
        String json = serialize(body);

        UUID scenarioId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        // Insert the scenario shell first (latest_version_id null is allowed),
        // flushing so the UNIQUE(owner_id, name) violation surfaces here → 409.
        Scenario scenario = new Scenario(scenarioId, owner.getId(), draft.name());
        try {
            scenarios.saveAndFlush(scenario);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateScenarioNameException(draft.name());
        }

        // v1, then point latest_version_id at it (resolves the circular FK).
        versions.saveAndFlush(new ScenarioVersion(versionId, scenarioId, 1, owner.getId(), json));
        scenario.setLatestVersionId(versionId);
        scenarios.saveAndFlush(scenario);

        audit(scenarioId, versionId, owner.getId(), "CREATE", "Created scenario \"" + draft.name() + "\"");
        return toResponse(scenario, body, 1, 1);
    }

    @Transactional
    public ScenarioResponse update(UUID id, ScenarioDraft draft) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = buildBody(draft);
        String json = serialize(body);

        int nextNo = versions.findMaxVersionNo(id).orElse(0) + 1;
        UUID versionId = UUID.randomUUID();
        versions.saveAndFlush(new ScenarioVersion(versionId, id, nextNo, author.getId(), json));

        scenario.setName(draft.name()); // a save-as-new-version may also rename
        scenario.setLatestVersionId(versionId);
        try {
            scenarios.saveAndFlush(scenario);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateScenarioNameException(draft.name());
        }

        audit(id, versionId, author.getId(), "UPDATE", "Saved version " + nextNo);
        return toResponse(scenario, body, nextNo, (int) versions.countByScenarioId(id));
    }

    @Transactional
    public void delete(UUID id) {
        User actor = userService.currentUser();
        Scenario scenario = activeScenario(id, actor);
        scenario.setDeletedAt(OffsetDateTime.now());
        scenarios.save(scenario);
        audit(id, scenario.getLatestVersionId(), actor.getId(), "DELETE", "Archived scenario");
    }

    // --- queries --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ScenarioSummary> list() {
        User me = userService.currentUser();
        return scenarios.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(me.getId()).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScenarioResponse get(UUID id) {
        User me = userService.currentUser();
        Scenario scenario = activeScenario(id, me);
        ScenarioVersion latest = latestVersion(scenario);
        return toResponse(scenario, parse(latest.getBody()), latest.getVersionNo(),
                (int) versions.countByScenarioId(id));
    }

    /**
     * Latest-version body for the per-scenario WebSocket stream (Phase 4),
     * resolved by caller <em>email</em> off the request thread. Same gate as
     * {@link #activeScenario} (exists ∧ not soft-deleted ∧ owned); a missing
     * user or scenario both collapse to {@link ScenarioNotFoundException} (the
     * handler maps it to close 4404 — no owned/not-found distinction, so ids
     * can't be enumerated). Read-only: never provisions a user (unlike
     * {@link #get}, which runs on the request thread via {@link UserService#currentUser}).
     */
    @Transactional(readOnly = true)
    public ScenarioBody bodyForStream(UUID id, String callerEmail) {
        User caller = userService.findByEmail(callerEmail)
                .orElseThrow(() -> new ScenarioNotFoundException(id));
        Scenario scenario = activeScenario(id, caller);
        ScenarioVersion latest = latestVersion(scenario);
        return parse(latest.getBody());
    }

    @Transactional(readOnly = true)
    public ScenarioVersionResponse getVersion(UUID id, int versionNo) {
        User me = userService.currentUser();
        activeScenario(id, me); // ownership + not-archived gate
        ScenarioVersion v = versions.findByScenarioIdAndVersionNo(id, versionNo)
                .orElseThrow(() -> new ScenarioNotFoundException(
                        "Scenario " + id + " has no version " + versionNo));
        return new ScenarioVersionResponse(id.toString(), v.getVersionNo(),
                v.getAuthorId().toString(), str(v.getCreatedAt()), parse(v.getBody()));
    }

    // --- helpers --------------------------------------------------------------

    /** Resolve a scenario that exists, is not archived, and belongs to the caller. */
    private Scenario activeScenario(UUID id, User owner) {
        return scenarios.findById(id)
                .filter(s -> s.getDeletedAt() == null)
                .filter(s -> s.getOwnerId().equals(owner.getId()))
                .orElseThrow(() -> new ScenarioNotFoundException(id));
    }

    private ScenarioVersion latestVersion(Scenario scenario) {
        UUID latestId = scenario.getLatestVersionId();
        if (latestId == null) {
            throw new ScenarioNotFoundException("Scenario " + scenario.getId() + " has no version");
        }
        return versions.findById(latestId)
                .orElseThrow(() -> new ScenarioNotFoundException(
                        "Scenario " + scenario.getId() + " latest version missing"));
    }

    private void audit(UUID scenarioId, UUID versionId, UUID actorId, String action, String summary) {
        auditLog.save(new AuditLog(UUID.randomUUID(), scenarioId, versionId, actorId, action, summary));
    }

    private ScenarioBody buildBody(ScenarioDraft d) {
        validateSemantics(d);
        String fidelity = (d.fidelity() == null || d.fidelity().isBlank()) ? "sgp4" : d.fidelity();
        ScenarioBody.Role chief = role("chief", d.chiefNoradId());
        List<ScenarioBody.Role> deputies = d.deputyNoradIds().stream()
                .map(n -> role("deputy", n))
                .toList();
        return new ScenarioBody(1, fidelity, new ScenarioBody.TimeRange(d.start(), d.end()), chief, deputies);
    }

    private ScenarioBody.Role role(String role, int noradId) {
        TleSnapshot snap = catalog.findSnapshot(noradId)
                .orElseThrow(() -> new ScenarioValidationException(
                        "NORAD id " + noradId + " is not in the catalog"));
        return new ScenarioBody.Role(role, noradId, snap.name(),
                new ScenarioBody.InitialState("tle",
                        new ScenarioBody.Tle(snap.line1(), snap.line2(), snap.epoch())));
    }

    private void validateSemantics(ScenarioDraft d) {
        Instant start = parseInstant(d.start(), "timeRange.start");
        Instant end = parseInstant(d.end(), "timeRange.end");
        if (!end.isAfter(start)) {
            throw new ScenarioValidationException("timeRange.end must be after timeRange.start");
        }
        // Deputies must be distinct and must not duplicate the chief.
        Set<Integer> seen = new HashSet<>();
        List<Integer> dupes = new ArrayList<>();
        for (int deputy : d.deputyNoradIds()) {
            if (deputy == d.chiefNoradId()) {
                throw new ScenarioValidationException(
                        "Deputy " + deputy + " is also the chief");
            }
            if (!seen.add(deputy)) {
                dupes.add(deputy);
            }
        }
        if (!dupes.isEmpty()) {
            throw new ScenarioValidationException("Duplicate deputy NORAD ids: " + dupes);
        }
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ScenarioValidationException(field + " is required");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException e1) {
            try {
                return Instant.parse(value);
            } catch (DateTimeException e2) {
                throw new ScenarioValidationException(field + " must be an ISO-8601 date-time");
            }
        }
    }

    private ScenarioSummary toSummary(Scenario s) {
        ScenarioVersion latest = latestVersion(s);
        ScenarioBody body = parse(latest.getBody());
        List<Integer> deputyIds = body.deputies().stream()
                .map(ScenarioBody.Role::noradId)
                .toList();
        return new ScenarioSummary(s.getId().toString(), s.getName(), str(s.getCreatedAt()),
                latest.getVersionNo(), (int) versions.countByScenarioId(s.getId()),
                body.chief().noradId(), deputyIds);
    }

    private ScenarioResponse toResponse(Scenario s, ScenarioBody body, int latestNo, int count) {
        return new ScenarioResponse(s.getId().toString(), s.getName(), s.getOwnerId().toString(),
                str(s.getCreatedAt()), latestNo, count, body);
    }

    private ScenarioBody parse(String json) {
        try {
            return objectMapper.readValue(json, ScenarioBody.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt scenario body", e);
        }
    }

    private String serialize(ScenarioBody body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize scenario body", e);
        }
    }

    private static String str(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }
}
