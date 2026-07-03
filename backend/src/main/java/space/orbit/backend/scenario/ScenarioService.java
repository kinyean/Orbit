package space.orbit.backend.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.orbit.backend.catalog.CatalogService;
import space.orbit.backend.catalog.TleSnapshot;
import space.orbit.backend.io.MeasuredEphemeris;
import space.orbit.backend.io.WodCsvReader;

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
    private final MeasuredDatasetRepository measuredDatasets;
    private final UserService userService;
    private final CatalogService catalog;
    private final WodCsvReader wodReader;
    private final ObjectMapper objectMapper;

    /** Filesystem root that measured-data imports must stay within (path-traversal guard). */
    private final String importAllowedRoot;

    public ScenarioService(ScenarioRepository scenarios,
                           ScenarioVersionRepository versions,
                           AuditLogRepository auditLog,
                           MeasuredDatasetRepository measuredDatasets,
                           UserService userService,
                           CatalogService catalog,
                           WodCsvReader wodReader,
                           ObjectMapper objectMapper,
                           @Value("${orbit.import.allowed-root:}") String importAllowedRoot) {
        this.scenarios = scenarios;
        this.versions = versions;
        this.auditLog = auditLog;
        this.measuredDatasets = measuredDatasets;
        this.userService = userService;
        this.catalog = catalog;
        this.wodReader = wodReader;
        this.objectMapper = objectMapper;
        this.importAllowedRoot = importAllowedRoot;
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

    /**
     * Import a measured ephemeris (WOD CSV on the server) as a new scenario whose
     * chief IS the measured craft — a read-only "truth" reference you compose
     * hypothetical deputies around. The samples are frozen into an immutable
     * {@link MeasuredDataset} (out of the jsonb body), referenced by the chief's
     * {@code InitialState{kind:"ephemeris"}}; the time range spans the data. Goes
     * through the same audited create path (Decision 16) — one version + one audit
     * row ({@code IMPORT_MEASURED}).
     *
     * @param serverPath    path to the WOD CSV, constrained to {@code orbit.import.allowed-root}
     * @param noradOverride optional NORAD id (used when the name isn't in the catalog)
     */
    @Transactional
    public ScenarioResponse importMeasured(String serverPath, Integer noradOverride) {
        User owner = userService.currentUser();
        Path path = resolveImportPath(serverPath);

        MeasuredEphemeris eph;
        try (InputStream in = Files.newInputStream(path)) {
            eph = wodReader.parse(in);
        } catch (IOException e) {
            throw new ScenarioValidationException("Could not read measured-data file: " + e.getMessage());
        }
        List<MeasuredEphemeris.Sample> samples = eph.samples();
        if (samples == null || samples.size() < 2) {
            throw new ScenarioValidationException("Measured-data file has too few valid states (need ≥ 2)");
        }
        int noradId = resolveNorad(eph.satelliteName(), noradOverride);
        String satName = (eph.satelliteName() == null || eph.satelliteName().isBlank())
                ? ("NORAD " + noradId) : eph.satelliteName();

        // Freeze the samples + raw attitude as an immutable, content-hashed dataset (R11).
        byte[] blob = MeasuredDatasetCodec.encode(samples, eph.attitude());
        String hash = MeasuredDatasetCodec.sha256(blob);
        OffsetDateTime startUtc = millisToOffset(samples.get(0).epochMillis());
        OffsetDateTime endUtc = millisToOffset(samples.get(samples.size() - 1).epochMillis());
        UUID datasetId = UUID.randomUUID();
        measuredDatasets.saveAndFlush(new MeasuredDataset(datasetId, owner.getId(), satName, noradId,
                eph.frame(), startUtc, endUtc, samples.size(), fileName(path), hash, blob));

        // Chief = the measured craft (read-only truth); window = data span. Fidelity
        // applies ONLY to non-measured deputies added later — the chief is always served
        // from the tabulated ephemeris regardless of this field. Default to SGP4, not
        // numerical: measured windows span days, and a catalog-TLE deputy propagated
        // numerically over days is very slow (R18 — a 7-day window can take ~90 s to
        // encode, so nothing renders meanwhile) AND no more accurate than SGP4 for a
        // stale catalog TLE. Switch a specific scenario to numerical when warranted.
        // When the file carries measured attitude (slice 2), the chief flies it
        // ("measured" mode → the role's dataset quaternions); else modeled LVLH (null).
        boolean hasAttitude = eph.attitude() != null && !eph.attitude().isEmpty();
        ScenarioBody.AttitudeProfile attitude =
                hasAttitude ? new ScenarioBody.AttitudeProfile("measured", null) : null;
        ScenarioBody.Role chief = new ScenarioBody.Role("chief", noradId, satName,
                new ScenarioBody.InitialState("ephemeris", null, datasetId.toString()),
                List.of(), List.of(), attitude);
        ScenarioBody body = new ScenarioBody(ScenarioBody.CURRENT_SCHEMA_VERSION, "sgp4",
                new ScenarioBody.TimeRange(startUtc.toString(), endUtc.toString()), chief, List.of());

        String scenarioName = satName + " (measured " + startUtc.toLocalDate() + ")";
        String json = serialize(body);
        UUID scenarioId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Scenario scenario = new Scenario(scenarioId, owner.getId(), scenarioName);
        try {
            scenarios.saveAndFlush(scenario);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateScenarioNameException(scenarioName);
        }
        versions.saveAndFlush(new ScenarioVersion(versionId, scenarioId, 1, owner.getId(), json));
        scenario.setLatestVersionId(versionId);
        scenarios.saveAndFlush(scenario);
        audit(scenarioId, versionId, owner.getId(), "IMPORT_MEASURED",
                "Imported measured ephemeris for " + satName + " (" + samples.size() + " states)");
        return toResponse(scenario, body, 1, 1);
    }

    /** Validate the import path is a readable file within the configured allowed root. */
    private Path resolveImportPath(String serverPath) {
        if (serverPath == null || serverPath.isBlank()) {
            throw new ScenarioValidationException("import path is required");
        }
        if (importAllowedRoot == null || importAllowedRoot.isBlank()) {
            throw new ScenarioValidationException(
                    "measured-data import is disabled (orbit.import.allowed-root not configured)");
        }
        Path root = Path.of(importAllowedRoot).toAbsolutePath().normalize();
        Path path;
        try {
            path = Path.of(serverPath).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new ScenarioValidationException("invalid import path");
        }
        if (!path.startsWith(root)) {
            throw new ScenarioValidationException("import path must be within " + root);
        }
        if (Files.isDirectory(path) || !Files.isReadable(path)) {
            throw new ScenarioValidationException("import path is not a readable file: " + path);
        }
        return path;
    }

    private int resolveNorad(String satelliteName, Integer override) {
        if (override != null && override > 0) {
            return override;
        }
        return catalog.findNoradByName(satelliteName)
                .orElseThrow(() -> new ScenarioValidationException(
                        "Could not resolve a NORAD id for satellite \"" + satelliteName
                                + "\"; supply noradId explicitly"));
    }

    private static OffsetDateTime millisToOffset(long millis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static String fileName(Path p) {
        Path f = p.getFileName();
        return f == null ? null : f.toString();
    }

    /**
     * Seed a pre-built scenario body for {@code ownerId} if no live scenario of
     * that name exists (idempotent). Used to ship demo/sample scenarios whose
     * roles aren't catalog satellites (e.g. a synthetic close-formation), so the
     * body is supplied directly rather than resolved from the catalog. Still goes
     * through this single audited mutation path (Decision 16).
     */
    @Transactional
    public void seedIfAbsent(UUID ownerId, String name, ScenarioBody body) {
        if (scenarios.findByOwnerIdAndNameAndDeletedAtIsNull(ownerId, name).isPresent()) {
            return;
        }
        String json = serialize(body);
        UUID scenarioId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        scenarios.saveAndFlush(new Scenario(scenarioId, ownerId, name));
        versions.saveAndFlush(new ScenarioVersion(versionId, scenarioId, 1, ownerId, json));
        Scenario scenario = scenarios.findById(scenarioId).orElseThrow();
        scenario.setLatestVersionId(versionId);
        scenarios.saveAndFlush(scenario);
        audit(scenarioId, versionId, ownerId, "SEED", "Seeded sample scenario \"" + name + "\"");
    }

    @Transactional
    public ScenarioResponse update(UUID id, ScenarioDraft draft) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        // Merge against the current body so a measured (ephemeris) role survives the
        // edit — its state lives in a dataset, not the catalog, so rebuilding it from
        // a NORAD id would clobber the imported chief.
        ScenarioBody existing = parse(latestVersion(scenario).getBody());
        ScenarioBody body = buildBody(draft, existing);
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

    /**
     * Add an impulsive ΔV maneuver to a deputy (Phase 5B, US-MAN-01). Like every
     * other mutation it writes a new immutable version + one audit row (Decision
     * 16) — re-propagation happens when the client reopens the scenario stream.
     */
    @Transactional
    public ScenarioResponse addManeuver(UUID id, ManeuverDraft draft) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        validateManeuver(body, draft);
        ScenarioBody.Maneuver maneuver = new ScenarioBody.Maneuver(
                UUID.randomUUID().toString(), "delta_v", draft.epoch(), "ric",
                new ScenarioBody.DeltaV(draft.r(), draft.i(), draft.c()), draft.thrustN(), draft.ispSec());

        ScenarioBody updated = withDeputyManeuvers(body, draft.deputyNoradId(), existing -> {
            List<ScenarioBody.Maneuver> next = new ArrayList<>(existing);
            next.add(maneuver);
            return next;
        });

        int nextNo = saveVersion(scenario, updated, author,
                "MANEUVER_ADD", "Added Δv to deputy " + draft.deputyNoradId() + " at " + draft.epoch());
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    /**
     * Insert several maneuvers in ONE version + audit row (Phase 5C templates —
     * Hohmann/Lambert produce two impulses that are one logical edit). All drafts
     * are validated before any is applied.
     */
    @Transactional
    public ScenarioResponse addManeuvers(UUID id, List<ManeuverDraft> drafts, String summary) {
        if (drafts == null || drafts.isEmpty()) {
            throw new ScenarioValidationException("No maneuvers to add");
        }
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        for (ManeuverDraft draft : drafts) {
            validateManeuver(body, draft);
        }
        ScenarioBody updated = body;
        for (ManeuverDraft draft : drafts) {
            ScenarioBody.Maneuver maneuver = new ScenarioBody.Maneuver(
                    UUID.randomUUID().toString(), "delta_v", draft.epoch(), "ric",
                    new ScenarioBody.DeltaV(draft.r(), draft.i(), draft.c()), draft.thrustN(), draft.ispSec());
            updated = withDeputyManeuvers(updated, draft.deputyNoradId(), existing -> {
                List<ScenarioBody.Maneuver> next = new ArrayList<>(existing);
                next.add(maneuver);
                return next;
            });
        }
        int nextNo = saveVersion(scenario, updated, author, "MANEUVER_TEMPLATE", summary);
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    /** Remove a maneuver by its id (Phase 5B). New version + audit, as with any edit. */
    @Transactional
    public ScenarioResponse removeManeuver(UUID id, String maneuverId) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        boolean[] removed = {false};
        List<ScenarioBody.Role> deputies = body.deputies().stream()
                .map(d -> {
                    if (d.maneuvers().stream().noneMatch(m -> m.id().equals(maneuverId))) {
                        return d;
                    }
                    removed[0] = true;
                    List<ScenarioBody.Maneuver> kept = d.maneuvers().stream()
                            .filter(m -> !m.id().equals(maneuverId))
                            .toList();
                    return d.withManeuvers(kept); // preserve sensors + attitude (Phase 7)
                })
                .toList();
        if (!removed[0]) {
            throw new ScenarioValidationException("No maneuver " + maneuverId + " in this scenario");
        }
        ScenarioBody updated = new ScenarioBody(ScenarioBody.CURRENT_SCHEMA_VERSION,
                body.fidelity(), body.timeRange(), body.chief(), deputies, body.missDistanceThresholdM());

        int nextNo = saveVersion(scenario, updated, author, "MANEUVER_REMOVE",
                removedManeuverSummary(body, maneuverId));
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    /** Human summary of the removed maneuver (ΔV + epoch + owning deputy), for the audit row. */
    private static String removedManeuverSummary(ScenarioBody body, String maneuverId) {
        for (ScenarioBody.Role d : body.deputies()) {
            for (ScenarioBody.Maneuver m : d.maneuvers()) {
                if (m.id().equals(maneuverId)) {
                    return "Removed Δv on " + roleName(d) + ": " + fmtManeuver(m);
                }
            }
        }
        return "Removed maneuver " + maneuverId;
    }

    // --- sensors & attitude (Phase 7, US-SENSE-01 / US-PROX-01) --------------

    /**
     * Add a body-fixed sensor to a host (chief or deputy). New immutable version +
     * one audit row (Decision 16); FOV volumes / acquisition events recompute when
     * the client reopens the scenario stream.
     */
    @Transactional
    public ScenarioResponse addSensor(UUID id, SensorDraft draft) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        validateSensor(body, draft);
        ScenarioBody.Sensor sensor = buildSensor(draft);
        ScenarioBody updated = withRoleSensors(body, draft.noradId(), existing -> {
            List<ScenarioBody.Sensor> next = new ArrayList<>(existing);
            next.add(sensor);
            return next;
        });

        int nextNo = saveVersion(scenario, updated, author,
                "SENSOR_ADD", "Added " + sensor.kind() + " sensor to " + draft.noradId());
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    /** Remove a sensor by its id (from whichever role carries it). New version + audit. */
    @Transactional
    public ScenarioResponse removeSensor(UUID id, String sensorId) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        boolean[] removed = {false};
        ScenarioBody updated = mapAllRoles(body, r -> {
            if (r.sensors().stream().noneMatch(s -> s.id().equals(sensorId))) {
                return r;
            }
            removed[0] = true;
            return r.withSensors(r.sensors().stream().filter(s -> !s.id().equals(sensorId)).toList());
        });
        if (!removed[0]) {
            throw new ScenarioValidationException("No sensor " + sensorId + " in this scenario");
        }

        int nextNo = saveVersion(scenario, updated, author, "SENSOR_REMOVE",
                removedSensorSummary(body, sensorId));
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    /** Human summary of the removed sensor (kind + FOV + range + host), for the audit row. */
    private static String removedSensorSummary(ScenarioBody body, String sensorId) {
        for (ScenarioBody.Role r : orderedRoles(body)) {
            for (ScenarioBody.Sensor s : r.sensors()) {
                if (s.id().equals(sensorId)) {
                    return "Removed sensor on " + roleName(r) + ": " + fmtSensor(s);
                }
            }
        }
        return "Removed sensor " + sensorId;
    }

    /** Set (or clear, with a null draft) a sensor's link budget (Phase 9D, US-EVT-05). New
     *  version + audit; the sensor is found on whichever role carries it. */
    @Transactional
    public ScenarioResponse setLinkBudget(UUID id, String sensorId, LinkBudgetDraft draft) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        ScenarioBody.LinkBudget lb = draft == null ? null : validateLinkBudget(draft);
        boolean[] found = {false};
        ScenarioBody updated = mapAllRoles(body, r -> {
            if (r.sensors().stream().noneMatch(s -> s.id().equals(sensorId))) {
                return r;
            }
            found[0] = true;
            return r.withSensors(r.sensors().stream()
                    .map(s -> s.id().equals(sensorId) ? s.withLinkBudget(lb) : s).toList());
        });
        if (!found[0]) {
            throw new ScenarioValidationException("No sensor " + sensorId + " in this scenario");
        }

        int nextNo = saveVersion(scenario, updated, author, "LINK_BUDGET_SET",
                (lb == null ? "Cleared" : "Set") + " link budget on sensor " + sensorId);
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    /** Validate + build a {@link ScenarioBody.LinkBudget} (semantics → 422). */
    private static ScenarioBody.LinkBudget validateLinkBudget(LinkBudgetDraft d) {
        String kind = d.kind() == null || d.kind().isBlank() ? "rf" : d.kind().trim().toLowerCase();
        if (!kind.equals("rf") && !kind.equals("optical")) {
            throw new ScenarioValidationException("link budget kind must be 'rf' or 'optical'");
        }
        if (!(d.frequencyGhz() > 0)) {
            throw new ScenarioValidationException("link budget frequency must be a positive number of GHz");
        }
        if (!(d.bandwidthHz() > 0)) {
            throw new ScenarioValidationException("link budget bandwidth must be a positive number of Hz");
        }
        return new ScenarioBody.LinkBudget(kind, d.eirpDbw(), d.gOverTdbK(),
                d.frequencyGhz(), d.bandwidthHz(), d.thresholdDb());
    }

    /** Set a host's attitude profile ({@code lvlh} or {@code fixed}). New version + audit. */
    @Transactional
    public ScenarioResponse setAttitude(UUID id, AttitudeDraft draft) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        validateAttitude(body, draft);
        String mode = normalizeMode(draft.mode());
        ScenarioBody.AttitudeProfile profile = new ScenarioBody.AttitudeProfile(
                mode, "fixed".equals(mode) ? draft.quaternion() : null);
        ScenarioBody updated = mapRole(body, draft.noradId(), r -> r.withAttitude(profile));

        int nextNo = saveVersion(scenario, updated, author,
                "ATTITUDE_SET", "Set " + mode + " attitude on " + draft.noradId());
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    // --- constraints & conjunctions (Phase 8, US-EVT-02 / US-EVT-03) ----------

    /**
     * Add a safety/observability constraint to a host (chief or deputy). New immutable
     * version + one audit row (Decision 16); violation events recompute when the client
     * reopens the scenario stream.
     */
    @Transactional
    public ScenarioResponse addConstraint(UUID id, ConstraintDraft draft) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        validateConstraint(body, draft);
        String kind = draft.kind().toLowerCase();
        ScenarioBody.Constraint constraint = new ScenarioBody.Constraint(
                UUID.randomUUID().toString(), kind, draft.hostNoradId(),
                draft.sensorId(), draft.targetNoradId(), draft.limitDeg(), draft.rangeM());
        ScenarioBody updated = mapRole(body, draft.hostNoradId(), r -> {
            List<ScenarioBody.Constraint> next = new ArrayList<>(r.constraints());
            next.add(constraint);
            return r.withConstraints(next);
        });

        int nextNo = saveVersion(scenario, updated, author,
                "CONSTRAINT_ADD", "Added " + kind + " constraint to " + draft.hostNoradId());
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    /** Remove a constraint by its id (from whichever role carries it). New version + audit. */
    @Transactional
    public ScenarioResponse removeConstraint(UUID id, String constraintId) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        boolean[] removed = {false};
        ScenarioBody updated = mapAllRoles(body, r -> {
            if (r.constraints().stream().noneMatch(c -> c.id().equals(constraintId))) {
                return r;
            }
            removed[0] = true;
            return r.withConstraints(
                    r.constraints().stream().filter(c -> !c.id().equals(constraintId)).toList());
        });
        if (!removed[0]) {
            throw new ScenarioValidationException("No constraint " + constraintId + " in this scenario");
        }

        int nextNo = saveVersion(scenario, updated, author, "CONSTRAINT_REMOVE",
                removedConstraintSummary(body, constraintId));
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    /** Human summary of the removed constraint (kind + params + host), for the audit row. */
    private static String removedConstraintSummary(ScenarioBody body, String constraintId) {
        for (ScenarioBody.Role r : orderedRoles(body)) {
            for (ScenarioBody.Constraint c : r.constraints()) {
                if (c.id().equals(constraintId)) {
                    return "Removed constraint on " + roleName(r) + ": " + fmtConstraint(c);
                }
            }
        }
        return "Removed constraint " + constraintId;
    }

    /** Set (or clear, with null) the intra-scenario conjunction miss-distance threshold (metres). */
    @Transactional
    public ScenarioResponse setMissDistanceThreshold(UUID id, Double thresholdM) {
        User author = userService.currentUser();
        Scenario scenario = activeScenario(id, author);
        ScenarioBody body = parse(latestVersion(scenario).getBody());

        if (thresholdM != null && (!Double.isFinite(thresholdM) || thresholdM <= 0)) {
            throw new ScenarioValidationException(
                    "missDistanceThresholdM must be a positive number of metres (or null to clear)");
        }
        ScenarioBody updated = new ScenarioBody(ScenarioBody.CURRENT_SCHEMA_VERSION, body.fidelity(),
                body.timeRange(), body.chief(), body.deputies(), thresholdM);

        int nextNo = saveVersion(scenario, updated, author, "MISS_DISTANCE_SET",
                thresholdM == null ? "Cleared conjunction threshold"
                        : "Set conjunction threshold to " + Math.round(thresholdM) + " m");
        return toResponse(scenario, updated, nextNo, (int) versions.countByScenarioId(id));
    }

    private void validateConstraint(ScenarioBody body, ConstraintDraft draft) {
        if (!roleExists(body, draft.hostNoradId())) {
            throw new ScenarioValidationException("Host NORAD id " + draft.hostNoradId() + " is not in this scenario");
        }
        if (!(draft.limitDeg() > 0 && draft.limitDeg() < 180)) {
            throw new ScenarioValidationException("constraint limitDeg must be in (0,180) degrees");
        }
        String kind = draft.kind() == null ? "" : draft.kind().toLowerCase();
        switch (kind) {
            case "sun-keep-out" -> {
                if (draft.sensorId() == null || draft.sensorId().isBlank()
                        || !hostHasSensor(body, draft.hostNoradId(), draft.sensorId())) {
                    throw new ScenarioValidationException(
                            "sun-keep-out requires a sensorId belonging to host " + draft.hostNoradId());
                }
            }
            case "approach-corridor" -> {
                if (draft.targetNoradId() == draft.hostNoradId() || !roleExists(body, draft.targetNoradId())) {
                    throw new ScenarioValidationException(
                            "approach-corridor requires a targetNoradId in the scenario, different from the host");
                }
                if (!(draft.rangeM() > 0)) {
                    throw new ScenarioValidationException("approach-corridor requires rangeM > 0");
                }
            }
            default -> throw new ScenarioValidationException(
                    "Unsupported constraint kind \"" + draft.kind() + "\" (sun-keep-out|approach-corridor)");
        }
    }

    private boolean hostHasSensor(ScenarioBody body, int noradId, String sensorId) {
        ScenarioBody.Role r = findRole(body, noradId);
        return r != null && r.sensors().stream().anyMatch(s -> sensorId.equals(s.id()));
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

    /**
     * Version history (Phase 10, US-INFRA-06 / US-SCN-04): all versions of a
     * scenario, oldest first, metadata only. Owner-gated exactly like
     * {@link #get} (else 404).
     */
    @Transactional(readOnly = true)
    public List<ScenarioVersionSummary> versionHistory(UUID id) {
        User me = userService.currentUser();
        activeScenario(id, me); // ownership + not-archived gate
        List<ScenarioVersion> all = versions.findByScenarioIdOrderByVersionNoAsc(id);
        Map<UUID, String> emails = userService.emailsByIds(
                all.stream().map(ScenarioVersion::getAuthorId).collect(Collectors.toSet()));
        return all.stream()
                .map(v -> new ScenarioVersionSummary(
                        v.getVersionNo(),
                        emails.getOrDefault(v.getAuthorId(), v.getAuthorId().toString()),
                        str(v.getCreatedAt())))
                .toList();
    }

    /**
     * Audit trail (Phase 10, US-INFRA-06): every action against a scenario,
     * newest first, with the actor resolved to an email. Owner-gated like
     * {@link #get} (else 404).
     */
    @Transactional(readOnly = true)
    public List<AuditEntryResponse> auditTrail(UUID id) {
        User me = userService.currentUser();
        activeScenario(id, me); // ownership + not-archived gate
        List<AuditLog> entries = auditLog.findByScenarioIdOrderByTimestampDesc(id);
        Map<UUID, String> emails = userService.emailsByIds(
                entries.stream().map(AuditLog::getActorId).collect(Collectors.toSet()));
        return entries.stream()
                .map(e -> new AuditEntryResponse(
                        e.getAction(),
                        emails.getOrDefault(e.getActorId(), e.getActorId().toString()),
                        str(e.getTimestamp()),
                        e.getDiffSummary()))
                .toList();
    }

    /**
     * Structured diff of version {@code versionNo} against its predecessor
     * (Phase 10 governance follow-up). Every version stores the full body, so the
     * delta — maneuvers/sensors/constraints added/removed/changed, with ΔV numbers,
     * epochs and resolved role names — is recovered by comparing two bodies (works
     * retroactively). Owner-gated like {@link #get}. v1 diffs against an empty
     * scenario (everything is an {@code add}).
     */
    @Transactional(readOnly = true)
    public VersionDiff versionDiff(UUID id, int versionNo) {
        User me = userService.currentUser();
        activeScenario(id, me); // ownership + not-archived gate
        ScenarioBody newBody = versions.findByScenarioIdAndVersionNo(id, versionNo)
                .map(v -> parse(v.getBody()))
                .orElseThrow(() -> new ScenarioNotFoundException(id));
        Integer fromNo = versionNo > 1 ? versionNo - 1 : null;
        ScenarioBody oldBody = fromNo == null ? null
                : versions.findByScenarioIdAndVersionNo(id, fromNo).map(v -> parse(v.getBody())).orElse(null);
        return new VersionDiff(versionNo, fromNo, diffBodies(oldBody, newBody));
    }

    // --- version diff (Phase 10 governance) -----------------------------------

    /** Compare two scenario bodies ({@code oldB} null → the empty pre-v1 state). */
    private List<VersionDiff.Change> diffBodies(ScenarioBody oldB, ScenarioBody newB) {
        List<VersionDiff.Change> out = new ArrayList<>();

        // settings
        String oldFid = oldB == null ? null : oldB.fidelity();
        if (!Objects.equals(oldFid, newB.fidelity())) {
            out.add(new VersionDiff.Change(oldB == null ? "add" : "change", "settings",
                    oldB == null ? "Fidelity " + newB.fidelity()
                                 : "Fidelity " + oldFid + " → " + newB.fidelity()));
        }
        ScenarioBody.TimeRange oldTr = oldB == null ? null : oldB.timeRange();
        if (oldTr != null && newB.timeRange() != null
                && (!Objects.equals(oldTr.start(), newB.timeRange().start())
                 || !Objects.equals(oldTr.end(), newB.timeRange().end()))) {
            out.add(new VersionDiff.Change("change", "settings",
                    "Time range " + oldTr.start() + "…" + oldTr.end()
                            + " → " + newB.timeRange().start() + "…" + newB.timeRange().end()));
        }
        Double oldThr = oldB == null ? null : oldB.missDistanceThresholdM();
        if (!Objects.equals(oldThr, newB.missDistanceThresholdM())) {
            out.add(new VersionDiff.Change(oldThr == null ? "add" : "change", "settings",
                    "Miss-distance threshold " + fmtThreshold(oldThr, newB.missDistanceThresholdM())));
        }

        // roster (added / removed roles), then per-role sub-collection diffs
        Map<Integer, ScenarioBody.Role> oldRoles = rolesById(oldB);
        Map<Integer, ScenarioBody.Role> newRoles = rolesById(newB);
        for (ScenarioBody.Role r : orderedRoles(newB)) {
            if (!oldRoles.containsKey(r.noradId())) {
                out.add(new VersionDiff.Change("add", "roster",
                        ("chief".equals(r.role()) ? "Chief " : "Deputy ") + roleName(r) + describeRoleContents(r)));
            }
        }
        for (ScenarioBody.Role r : orderedRoles(oldB)) {
            if (!newRoles.containsKey(r.noradId())) {
                out.add(new VersionDiff.Change("remove", "roster",
                        ("chief".equals(r.role()) ? "Chief " : "Deputy ") + roleName(r)));
            }
        }
        for (ScenarioBody.Role rn : orderedRoles(newB)) {
            ScenarioBody.Role ro = oldRoles.get(rn.noradId());
            if (ro != null) {
                diffRole(ro, rn, out);
            }
        }
        return out;
    }

    /** Diff the maneuvers / sensors / constraints / attitude of a role present in both versions. */
    private void diffRole(ScenarioBody.Role ro, ScenarioBody.Role rn, List<VersionDiff.Change> out) {
        String who = roleName(rn);
        Map<String, ScenarioBody.Maneuver> oldM = byId(ro.maneuvers(), ScenarioBody.Maneuver::id);
        Map<String, ScenarioBody.Maneuver> newM = byId(rn.maneuvers(), ScenarioBody.Maneuver::id);
        for (ScenarioBody.Maneuver m : rn.maneuvers()) {
            if (!oldM.containsKey(m.id())) {
                out.add(new VersionDiff.Change("add", "maneuver", "Δv on " + who + ": " + fmtManeuver(m)));
            }
        }
        for (ScenarioBody.Maneuver m : ro.maneuvers()) {
            if (!newM.containsKey(m.id())) {
                out.add(new VersionDiff.Change("remove", "maneuver", "Δv on " + who + ": " + fmtManeuver(m)));
            }
        }
        Map<String, ScenarioBody.Sensor> oldS = byId(ro.sensors(), ScenarioBody.Sensor::id);
        Map<String, ScenarioBody.Sensor> newS = byId(rn.sensors(), ScenarioBody.Sensor::id);
        for (ScenarioBody.Sensor s : rn.sensors()) {
            ScenarioBody.Sensor prev = oldS.get(s.id());
            if (prev == null) {
                out.add(new VersionDiff.Change("add", "sensor", "Sensor on " + who + ": " + fmtSensor(s)));
            } else if (!sensorContentEquals(prev, s)) {
                out.add(new VersionDiff.Change("change", "sensor",
                        "Sensor " + sensorName(s) + " on " + who + ": " + fmtSensorChange(prev, s)));
            }
        }
        for (ScenarioBody.Sensor s : ro.sensors()) {
            if (!newS.containsKey(s.id())) {
                out.add(new VersionDiff.Change("remove", "sensor", "Sensor on " + who + ": " + fmtSensor(s)));
            }
        }
        Map<String, ScenarioBody.Constraint> oldC = byId(ro.constraints(), ScenarioBody.Constraint::id);
        Map<String, ScenarioBody.Constraint> newC = byId(rn.constraints(), ScenarioBody.Constraint::id);
        for (ScenarioBody.Constraint c : rn.constraints()) {
            if (!oldC.containsKey(c.id())) {
                out.add(new VersionDiff.Change("add", "constraint", "Constraint on " + who + ": " + fmtConstraint(c)));
            }
        }
        for (ScenarioBody.Constraint c : ro.constraints()) {
            if (!newC.containsKey(c.id())) {
                out.add(new VersionDiff.Change("remove", "constraint", "Constraint on " + who + ": " + fmtConstraint(c)));
            }
        }
        if (!attitudeEquals(ro.attitude(), rn.attitude())) {
            out.add(new VersionDiff.Change("change", "attitude",
                    "Attitude on " + who + ": " + attMode(ro.attitude()) + " → " + attMode(rn.attitude())));
        }
    }

    private static List<ScenarioBody.Role> orderedRoles(ScenarioBody b) {
        if (b == null) {
            return List.of();
        }
        List<ScenarioBody.Role> all = new ArrayList<>();
        if (b.chief() != null) {
            all.add(b.chief());
        }
        if (b.deputies() != null) {
            all.addAll(b.deputies());
        }
        return all;
    }

    private static Map<Integer, ScenarioBody.Role> rolesById(ScenarioBody b) {
        Map<Integer, ScenarioBody.Role> m = new LinkedHashMap<>();
        for (ScenarioBody.Role r : orderedRoles(b)) {
            m.put(r.noradId(), r);
        }
        return m;
    }

    private static <T> Map<String, T> byId(List<T> items, Function<T, String> id) {
        Map<String, T> m = new LinkedHashMap<>();
        for (T t : items) {
            m.put(id.apply(t), t);
        }
        return m;
    }

    private static String describeRoleContents(ScenarioBody.Role r) {
        List<String> bits = new ArrayList<>();
        if (!r.maneuvers().isEmpty()) {
            bits.add(r.maneuvers().size() + " maneuver" + (r.maneuvers().size() == 1 ? "" : "s"));
        }
        if (!r.sensors().isEmpty()) {
            bits.add(r.sensors().size() + " sensor" + (r.sensors().size() == 1 ? "" : "s"));
        }
        if (!r.constraints().isEmpty()) {
            bits.add(r.constraints().size() + " constraint" + (r.constraints().size() == 1 ? "" : "s"));
        }
        return bits.isEmpty() ? "" : " (" + String.join(", ", bits) + ")";
    }

    private static boolean sensorContentEquals(ScenarioBody.Sensor a, ScenarioBody.Sensor b) {
        return Objects.equals(a.kind(), b.kind())
                && Objects.equals(a.name(), b.name())
                && Objects.equals(a.fov(), b.fov())
                && a.minRangeM() == b.minRangeM()
                && a.maxRangeM() == b.maxRangeM()
                && Objects.equals(a.linkBudget(), b.linkBudget())
                && mountEquals(a.mount(), b.mount());
    }

    private static boolean mountEquals(ScenarioBody.Mount a, ScenarioBody.Mount b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.clockDeg() == b.clockDeg() && Arrays.equals(a.boresightBody(), b.boresightBody());
    }

    private static boolean attitudeEquals(ScenarioBody.AttitudeProfile a, ScenarioBody.AttitudeProfile b) {
        double[] qa = a == null ? null : a.quaternion();
        double[] qb = b == null ? null : b.quaternion();
        return attMode(a).equals(attMode(b)) && Arrays.equals(qa, qb);
    }

    private static String attMode(ScenarioBody.AttitudeProfile p) {
        return p == null || p.mode() == null || p.mode().isBlank() ? "lvlh" : p.mode();
    }

    private static String roleName(ScenarioBody.Role r) {
        String n = r.name() == null || r.name().isBlank() ? "unknown" : r.name();
        return n + " (" + r.noradId() + ")";
    }

    private static String sensorName(ScenarioBody.Sensor s) {
        return s.name() == null || s.name().isBlank() ? s.kind() : s.name();
    }

    private static String fmtManeuver(ScenarioBody.Maneuver m) {
        ScenarioBody.DeltaV dv = m.deltaV();
        String base = String.format(Locale.ROOT, "R %+.2f, I %+.2f, C %+.2f m/s @ %s",
                dv.r(), dv.i(), dv.c(), m.epoch());
        if (m.finite()) {
            base += String.format(Locale.ROOT, " (finite %.1f N / %.0f s)", m.thrustN(), m.ispSec());
        }
        return base;
    }

    private static String fmtSensor(ScenarioBody.Sensor s) {
        return sensorName(s) + " [" + s.kind() + ", " + fmtFov(s.fov())
                + ", range " + fmtMetres(s.minRangeM()) + "–" + fmtMetres(s.maxRangeM()) + "]";
    }

    private static String fmtFov(ScenarioBody.Fov f) {
        if (f == null) {
            return "fov?";
        }
        if ("rect".equalsIgnoreCase(f.type())) {
            return String.format(Locale.ROOT, "%.1f×%.1f°", f.hDeg(), f.vDeg());
        }
        return String.format(Locale.ROOT, "cone %.1f°", f.halfAngleDeg());
    }

    private static String fmtSensorChange(ScenarioBody.Sensor a, ScenarioBody.Sensor b) {
        if (!Objects.equals(a.linkBudget(), b.linkBudget())) {
            return b.linkBudget() == null ? "link budget cleared"
                    : "link budget set (" + fmtLinkBudget(b.linkBudget()) + ")";
        }
        return "updated";
    }

    private static String fmtLinkBudget(ScenarioBody.LinkBudget lb) {
        return String.format(Locale.ROOT, "%s, EIRP %.1f dBW, G/T %.1f dB/K, %.2f GHz",
                lb.kind(), lb.eirpDbw(), lb.gOverTdbK(), lb.frequencyGhz());
    }

    private static String fmtConstraint(ScenarioBody.Constraint c) {
        if ("sun-keep-out".equals(c.kind())) {
            return String.format(Locale.ROOT, "sun-keep-out %.1f° (sensor %s)", c.limitDeg(), shortId(c.sensorId()));
        }
        if ("approach-corridor".equals(c.kind())) {
            return String.format(Locale.ROOT, "approach-corridor %.1f° vs %d within %s",
                    c.limitDeg(), c.targetNoradId(), fmtMetres(c.rangeM()));
        }
        return c.kind();
    }

    private static String fmtThreshold(Double oldV, Double newV) {
        if (newV == null) {
            return "cleared";
        }
        if (oldV == null) {
            return fmtMetres(newV);
        }
        return fmtMetres(oldV) + " → " + fmtMetres(newV);
    }

    private static String fmtMetres(double m) {
        if (Math.abs(m) >= 1000.0) {
            return String.format(Locale.ROOT, "%.2f km", m / 1000.0);
        }
        return String.format(Locale.ROOT, "%.0f m", m);
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "—";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
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

    /** Write {@code body} as the next immutable version of {@code scenario} + one audit row. */
    private int saveVersion(Scenario scenario, ScenarioBody body, User author, String action, String summary) {
        UUID id = scenario.getId();
        int nextNo = versions.findMaxVersionNo(id).orElse(0) + 1;
        UUID versionId = UUID.randomUUID();
        versions.saveAndFlush(new ScenarioVersion(versionId, id, nextNo, author.getId(), serialize(body)));
        scenario.setLatestVersionId(versionId);
        scenarios.saveAndFlush(scenario);
        audit(id, versionId, author.getId(), action, summary);
        return nextNo;
    }

    /** Replace one deputy's maneuver list (by NORAD id), re-stamping the schema version. */
    private ScenarioBody withDeputyManeuvers(ScenarioBody body, int deputyNoradId,
                                             java.util.function.UnaryOperator<List<ScenarioBody.Maneuver>> edit) {
        List<ScenarioBody.Role> deputies = body.deputies().stream()
                .map(d -> d.noradId() == deputyNoradId
                        ? d.withManeuvers(edit.apply(d.maneuvers())) // preserve sensors + attitude (Phase 7)
                        : d)
                .toList();
        return new ScenarioBody(ScenarioBody.CURRENT_SCHEMA_VERSION,
                body.fidelity(), body.timeRange(), body.chief(), deputies, body.missDistanceThresholdM());
    }

    /** Apply {@code edit} to the role (chief or deputy) matching {@code noradId}; others unchanged. */
    private ScenarioBody mapRole(ScenarioBody body, int noradId,
                                 java.util.function.UnaryOperator<ScenarioBody.Role> edit) {
        boolean[] hit = {false};
        ScenarioBody.Role chief = body.chief();
        if (chief != null && chief.noradId() == noradId) {
            chief = edit.apply(chief);
            hit[0] = true;
        }
        List<ScenarioBody.Role> deputies = body.deputies().stream()
                .map(d -> {
                    if (d.noradId() != noradId) {
                        return d;
                    }
                    hit[0] = true;
                    return edit.apply(d);
                })
                .toList();
        if (!hit[0]) {
            throw new ScenarioValidationException("NORAD id " + noradId + " is not in this scenario");
        }
        return new ScenarioBody(ScenarioBody.CURRENT_SCHEMA_VERSION,
                body.fidelity(), body.timeRange(), chief, deputies, body.missDistanceThresholdM());
    }

    /** Apply {@code edit} to every role (chief + deputies) — used by id-keyed sensor removal. */
    private ScenarioBody mapAllRoles(ScenarioBody body,
                                     java.util.function.UnaryOperator<ScenarioBody.Role> edit) {
        ScenarioBody.Role chief = body.chief() == null ? null : edit.apply(body.chief());
        List<ScenarioBody.Role> deputies = body.deputies().stream().map(edit).toList();
        return new ScenarioBody(ScenarioBody.CURRENT_SCHEMA_VERSION,
                body.fidelity(), body.timeRange(), chief, deputies, body.missDistanceThresholdM());
    }

    /** Replace one role's sensor list (by NORAD id; chief or deputy), preserving other fields. */
    private ScenarioBody withRoleSensors(ScenarioBody body, int noradId,
                                         java.util.function.UnaryOperator<List<ScenarioBody.Sensor>> edit) {
        return mapRole(body, noradId, r -> r.withSensors(edit.apply(r.sensors())));
    }

    private ScenarioBody.Sensor buildSensor(SensorDraft d) {
        String type = (d.fovType() == null || d.fovType().isBlank()) ? "cone" : d.fovType().toLowerCase();
        ScenarioBody.Fov fov = new ScenarioBody.Fov(type, d.halfAngleDeg(), d.hDeg(), d.vDeg());
        double[] boresight = {d.boresightX(), d.boresightY(), d.boresightZ()};
        if (boresight[0] == 0 && boresight[1] == 0 && boresight[2] == 0) {
            boresight = new double[] {1, 0, 0}; // default body +X
        }
        ScenarioBody.Mount mount = new ScenarioBody.Mount(boresight, d.clockDeg());
        String kind = (d.kind() == null || d.kind().isBlank()) ? "optical" : d.kind();
        String name = (d.name() == null || d.name().isBlank()) ? (kind + " sensor") : d.name();
        return new ScenarioBody.Sensor(UUID.randomUUID().toString(), kind, name, fov,
                d.minRangeM(), d.maxRangeM(), mount);
    }

    private void validateSensor(ScenarioBody body, SensorDraft draft) {
        if (!roleExists(body, draft.noradId())) {
            throw new ScenarioValidationException("NORAD id " + draft.noradId() + " is not in this scenario");
        }
        String type = draft.fovType() == null ? "cone" : draft.fovType();
        if ("cone".equalsIgnoreCase(type)) {
            if (!(draft.halfAngleDeg() > 0 && draft.halfAngleDeg() < 90)) {
                throw new ScenarioValidationException("cone FOV half-angle must be in (0,90) degrees");
            }
        } else if ("rect".equalsIgnoreCase(type)) {
            if (!(draft.hDeg() > 0 && draft.hDeg() < 180 && draft.vDeg() > 0 && draft.vDeg() < 180)) {
                throw new ScenarioValidationException("rect FOV H/V must be in (0,180) degrees");
            }
        } else {
            throw new ScenarioValidationException("Unsupported FOV type \"" + type + "\" (cone|rect)");
        }
        if (!Double.isFinite(draft.minRangeM()) || !Double.isFinite(draft.maxRangeM())
                || draft.minRangeM() < 0 || !(draft.maxRangeM() > draft.minRangeM())) {
            throw new ScenarioValidationException("sensor range must satisfy 0 <= minRangeM < maxRangeM");
        }
    }

    private void validateAttitude(ScenarioBody body, AttitudeDraft draft) {
        if (!roleExists(body, draft.noradId())) {
            throw new ScenarioValidationException("NORAD id " + draft.noradId() + " is not in this scenario");
        }
        String mode = draft.mode();
        if (mode != null && !mode.isBlank()
                && !"lvlh".equalsIgnoreCase(mode) && !"fixed".equalsIgnoreCase(mode)) {
            throw new ScenarioValidationException("Unsupported attitude mode \"" + mode + "\" (lvlh|fixed)");
        }
        if ("fixed".equalsIgnoreCase(mode)) {
            double[] q = draft.quaternion();
            if (q == null || q.length != 4) {
                throw new ScenarioValidationException("fixed attitude requires a quaternion [x,y,z,w]");
            }
            double n2 = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3];
            if (!(n2 > 1e-9)) {
                throw new ScenarioValidationException("fixed attitude quaternion must be non-zero");
            }
        }
    }

    private boolean roleExists(ScenarioBody body, int noradId) {
        if (body.chief() != null && body.chief().noradId() == noradId) {
            return true;
        }
        return body.deputies().stream().anyMatch(d -> d.noradId() == noradId);
    }

    private static String normalizeMode(String mode) {
        return (mode == null || mode.isBlank()) ? "lvlh" : mode.toLowerCase();
    }

    private void validateManeuver(ScenarioBody body, ManeuverDraft draft) {
        if (body.chief() != null && body.chief().noradId() == draft.deputyNoradId()) {
            throw new ScenarioValidationException("The chief cannot carry maneuvers; it is the LVLH reference");
        }
        boolean isDeputy = body.deputies().stream().anyMatch(d -> d.noradId() == draft.deputyNoradId());
        if (!isDeputy) {
            throw new ScenarioValidationException("Deputy " + draft.deputyNoradId() + " is not in this scenario");
        }
        if (draft.frame() != null && !"ric".equalsIgnoreCase(draft.frame())) {
            throw new ScenarioValidationException(
                    "Only RIC-frame maneuvers are supported in this phase (got \"" + draft.frame() + "\")");
        }
        if (!Double.isFinite(draft.r()) || !Double.isFinite(draft.i()) || !Double.isFinite(draft.c())) {
            throw new ScenarioValidationException("ΔV components must be finite");
        }
        // Finite-burn parameters (Phase 9, US-MAN-11): thrust + Isp come as a pair, both > 0.
        boolean hasThrust = draft.thrustN() != null;
        boolean hasIsp = draft.ispSec() != null;
        if (hasThrust != hasIsp) {
            throw new ScenarioValidationException(
                    "A finite burn needs both thrust (N) and Isp (s); got only one");
        }
        if (hasThrust && (!Double.isFinite(draft.thrustN()) || draft.thrustN() <= 0.0
                || !Double.isFinite(draft.ispSec()) || draft.ispSec() <= 0.0)) {
            throw new ScenarioValidationException("Finite-burn thrust (N) and Isp (s) must be positive and finite");
        }
        Instant epoch = parseInstant(draft.epoch(), "maneuver.epoch");
        Instant start = parseInstant(body.timeRange().start(), "timeRange.start");
        Instant end = parseInstant(body.timeRange().end(), "timeRange.end");
        if (epoch.isBefore(start) || epoch.isAfter(end)) {
            throw new ScenarioValidationException("maneuver.epoch must fall within the scenario time range");
        }
    }

    private ScenarioBody buildBody(ScenarioDraft d) {
        return buildBody(d, null);
    }

    /**
     * Build a body from the draft's NORAD ids, optionally merging against an
     * {@code existing} body so measured (ephemeris) roles are preserved rather
     * than rebuilt from the catalog ({@code existing} is null on create).
     */
    private ScenarioBody buildBody(ScenarioDraft d, ScenarioBody existing) {
        validateSemantics(d);
        String fidelity = (d.fidelity() == null || d.fidelity().isBlank()) ? "sgp4" : d.fidelity();
        ScenarioBody.Role chief = resolveRole("chief", d.chiefNoradId(), existing);
        List<ScenarioBody.Role> deputies = d.deputyNoradIds().stream()
                .map(n -> resolveRole("deputy", n, existing))
                .toList();
        return new ScenarioBody(ScenarioBody.CURRENT_SCHEMA_VERSION, fidelity,
                new ScenarioBody.TimeRange(d.start(), d.end()), chief, deputies,
                existing == null ? null : existing.missDistanceThresholdM());
    }

    /**
     * Resolve a role for {@code noradId}: if {@code existing} already has it as a
     * measured (ephemeris) role, preserve that role (dataset, name, maneuvers,
     * sensors, attitude) — its state isn't in the catalog. Otherwise build a fresh
     * catalog TLE role. Only the role label (chief/deputy) is (re)applied.
     */
    private ScenarioBody.Role resolveRole(String roleLabel, int noradId, ScenarioBody existing) {
        ScenarioBody.Role prior = existing == null ? null : findRole(existing, noradId);
        if (prior != null && prior.initialState() != null
                && "ephemeris".equals(prior.initialState().kind())) {
            return new ScenarioBody.Role(roleLabel, noradId, prior.name(), prior.initialState(),
                    prior.maneuvers(), prior.sensors(), prior.attitude());
        }
        return role(roleLabel, noradId);
    }

    private static ScenarioBody.Role findRole(ScenarioBody body, int noradId) {
        if (body.chief() != null && body.chief().noradId() == noradId) {
            return body.chief();
        }
        return body.deputies().stream().filter(x -> x.noradId() == noradId).findFirst().orElse(null);
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
            ScenarioBody body = objectMapper.readValue(json, ScenarioBody.class);
            // Forward-migrate an older body: maneuver/sensor lists are already
            // null-coalesced by the Role constructor (and a null attitude means the
            // default LVLH profile); here we re-stamp the schema version so callers see
            // a consistent v3 (the stored JSON is rewritten on the next save).
            if (body.schemaVersion() != ScenarioBody.CURRENT_SCHEMA_VERSION) {
                body = new ScenarioBody(ScenarioBody.CURRENT_SCHEMA_VERSION,
                        body.fidelity(), body.timeRange(), body.chief(), body.deputies(),
                        body.missDistanceThresholdM());
            }
            return body;
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
