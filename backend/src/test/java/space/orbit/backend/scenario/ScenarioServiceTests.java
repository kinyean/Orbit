package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import space.orbit.backend.catalog.CatalogService;
import space.orbit.backend.catalog.TleSnapshot;
import space.orbit.backend.io.WodCsvReader;

/**
 * Service-layer behavior with mocked repos + catalog (no DB, no Orekit). Pins
 * the Decision-16 invariants: monotonic immutable versions, latest_version_id
 * tracks the newest, exactly one audit row per mutation (zero on rejection),
 * and the TLE snapshot is frozen into the body at compose time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScenarioServiceTests {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private ScenarioRepository scenarios;
    @Mock private ScenarioVersionRepository versions;
    @Mock private AuditLogRepository auditLog;
    @Mock private MeasuredDatasetRepository measuredDatasets;
    @Mock private UserService userService;
    @Mock private CatalogService catalog;
    @Mock private WodCsvReader wodReader;

    private ScenarioService service;

    @BeforeEach
    void setUp() {
        service = new ScenarioService(scenarios, versions, auditLog, measuredDatasets, userService, catalog,
                wodReader, new ObjectMapper(), "/shared_folder");
        when(userService.currentUser()).thenReturn(new User(OWNER, "dev@orbit.local", List.of("admin")));
        when(scenarios.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scenarios.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versions.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(catalog.findSnapshot(25544)).thenReturn(Optional.of(
                new TleSnapshot(25544, "ISS (ZARYA)", "1 25544U L1", "2 25544 L2", "2024-06-01T12:00:00.000")));
        when(catalog.findSnapshot(33591)).thenReturn(Optional.of(
                new TleSnapshot(33591, "NOAA 19", "1 33591U L1", "2 33591 L2", "2024-06-01T10:00:00.000")));
    }

    private static ScenarioDraft draft(String name, int chief, List<Integer> deputies) {
        return new ScenarioDraft(name, "sgp4", "2024-06-01T00:00:00Z", "2024-06-02T00:00:00Z", chief, deputies);
    }

    @Test
    void createWritesV1WithFrozenSnapshotAndOneAudit() {
        ScenarioResponse resp = service.create(draft("Rendezvous", 25544, List.of(33591)));

        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        assertThat(vCap.getValue().getVersionNo()).isEqualTo(1);
        assertThat(vCap.getValue().getAuthorId()).isEqualTo(OWNER);
        assertThat(vCap.getValue().getBody()).contains("1 25544U L1").contains("ISS (ZARYA)");

        ArgumentCaptor<Scenario> sCap = ArgumentCaptor.forClass(Scenario.class);
        verify(scenarios, times(2)).saveAndFlush(sCap.capture());
        assertThat(sCap.getValue().getLatestVersionId()).isEqualTo(vCap.getValue().getId());

        verify(auditLog, times(1)).save(any());
        assertThat(resp.latestVersionNo()).isEqualTo(1);
        assertThat(resp.body().chief().noradId()).isEqualTo(25544);
        assertThat(resp.body().deputies()).hasSize(1);
    }

    @Test
    void updateCreatesMonotonicNextVersionAndTracksLatest() {
        UUID id = UUID.randomUUID();
        Scenario existing = new Scenario(id, OWNER, "Rendezvous");
        UUID priorVersionId = UUID.randomUUID();
        existing.setLatestVersionId(priorVersionId);
        when(scenarios.findById(id)).thenReturn(Optional.of(existing));
        when(versions.findMaxVersionNo(id)).thenReturn(Optional.of(1));
        when(versions.countByScenarioId(id)).thenReturn(2L);
        // update() now merges against the current body (to preserve measured roles),
        // so it reads the latest version. Provide a plain TLE-chief prior body.
        when(versions.findById(priorVersionId)).thenReturn(Optional.of(new ScenarioVersion(
                priorVersionId, id, 1, OWNER,
                "{\"schemaVersion\":4,\"fidelity\":\"sgp4\","
                        + "\"timeRange\":{\"start\":\"2024-06-01T00:00:00Z\",\"end\":\"2024-06-02T00:00:00Z\"},"
                        + "\"chief\":{\"role\":\"chief\",\"noradId\":25544,\"name\":\"ISS (ZARYA)\","
                        + "\"initialState\":{\"kind\":\"tle\",\"tle\":{\"line1\":\"1 25544U L1\","
                        + "\"line2\":\"2 25544 L2\",\"epoch\":\"2024-06-01T12:00:00.000\"}}},\"deputies\":[]}")));

        ScenarioResponse resp = service.update(id, draft("Rendezvous v2", 25544, List.of()));

        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        assertThat(vCap.getValue().getVersionNo()).isEqualTo(2);
        assertThat(existing.getLatestVersionId()).isEqualTo(vCap.getValue().getId());
        assertThat(existing.getName()).isEqualTo("Rendezvous v2"); // rename applied
        verify(auditLog, times(1)).save(any());
        assertThat(resp.latestVersionNo()).isEqualTo(2);
    }

    @Test
    void rejectsEndBeforeStartWithNoVersionOrAudit() {
        ScenarioDraft bad = new ScenarioDraft("Bad", "sgp4",
                "2024-06-02T00:00:00Z", "2024-06-01T00:00:00Z", 25544, List.of());
        assertThatThrownBy(() -> service.create(bad))
                .isInstanceOf(ScenarioValidationException.class);
        verify(versions, never()).saveAndFlush(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    void rejectsUnresolvableNorad() {
        when(catalog.findSnapshot(99999)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(draft("Bad", 99999, List.of())))
                .isInstanceOf(ScenarioValidationException.class)
                .hasMessageContaining("99999");
        verify(auditLog, never()).save(any());
    }

    @Test
    void rejectsDeputyEqualToChief() {
        assertThatThrownBy(() -> service.create(draft("Bad", 25544, List.of(25544))))
                .isInstanceOf(ScenarioValidationException.class);
        verify(auditLog, never()).save(any());
    }

    // --- maneuvers (Phase 5B, US-MAN-01) -------------------------------------

    private static final String DEPUTY_TLE_BODY_V2 =
            // a v2 body: chief 25544 + deputy 33591, no maneuvers yet
            "{\"schemaVersion\":2,\"fidelity\":\"sgp4\","
            + "\"timeRange\":{\"start\":\"2024-06-01T00:00:00Z\",\"end\":\"2024-06-02T00:00:00Z\"},"
            + "\"chief\":{\"role\":\"chief\",\"noradId\":25544,\"name\":\"ISS\","
            + "\"initialState\":{\"kind\":\"tle\",\"tle\":{\"line1\":\"1\",\"line2\":\"2\",\"epoch\":\"e\"}},\"maneuvers\":[]},"
            + "\"deputies\":[{\"role\":\"deputy\",\"noradId\":33591,\"name\":\"NOAA\","
            + "\"initialState\":{\"kind\":\"tle\",\"tle\":{\"line1\":\"1\",\"line2\":\"2\",\"epoch\":\"e\"}},\"maneuvers\":[]}]}";

    /** A v1 body (no maneuvers field anywhere) — the forward-migration fixture. */
    private static final String BODY_V1 =
            "{\"schemaVersion\":1,\"fidelity\":\"sgp4\","
            + "\"timeRange\":{\"start\":\"2024-06-01T00:00:00Z\",\"end\":\"2024-06-02T00:00:00Z\"},"
            + "\"chief\":{\"role\":\"chief\",\"noradId\":25544,\"name\":\"ISS\","
            + "\"initialState\":{\"kind\":\"tle\",\"tle\":{\"line1\":\"1\",\"line2\":\"2\",\"epoch\":\"e\"}}},"
            + "\"deputies\":[{\"role\":\"deputy\",\"noradId\":33591,\"name\":\"NOAA\","
            + "\"initialState\":{\"kind\":\"tle\",\"tle\":{\"line1\":\"1\",\"line2\":\"2\",\"epoch\":\"e\"}}}]}";

    /** Stand up an existing, owned scenario whose latest version has {@code bodyJson}. */
    private Scenario existingWithBody(UUID id, String bodyJson) {
        Scenario s = new Scenario(id, OWNER, "S");
        UUID versionId = UUID.randomUUID();
        s.setLatestVersionId(versionId);
        when(scenarios.findById(id)).thenReturn(Optional.of(s));
        when(versions.findById(versionId)).thenReturn(
                Optional.of(new ScenarioVersion(versionId, id, 1, OWNER, bodyJson)));
        when(versions.findMaxVersionNo(id)).thenReturn(Optional.of(1));
        when(versions.countByScenarioId(id)).thenReturn(2L);
        return s;
    }

    @Test
    void addManeuverWritesV2VersionWithImpulseAndOneAudit() {
        UUID id = UUID.randomUUID();
        Scenario s = existingWithBody(id, DEPUTY_TLE_BODY_V2);

        ScenarioResponse resp = service.addManeuver(id,
                new ManeuverDraft(33591, "2024-06-01T06:00:00Z", "ric", 0.0, 1.5, 0.0));

        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        assertThat(vCap.getValue().getVersionNo()).isEqualTo(2);
        assertThat(vCap.getValue().getBody()).contains("\"schemaVersion\":5").contains("delta_v");
        assertThat(s.getLatestVersionId()).isEqualTo(vCap.getValue().getId());
        verify(auditLog, times(1)).save(any());

        assertThat(resp.body().deputies()).hasSize(1);
        assertThat(resp.body().deputies().get(0).maneuvers()).hasSize(1);
        assertThat(resp.body().deputies().get(0).maneuvers().get(0).deltaV().i()).isEqualTo(1.5);
    }

    @Test
    void addManeuverForwardMigratesAV1Body() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, BODY_V1);

        ScenarioResponse resp = service.addManeuver(id,
                new ManeuverDraft(33591, "2024-06-01T06:00:00Z", "ric", 1.0, 0.0, 0.0));

        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        // The v1 body deserialized (null maneuvers/sensors → empty), then re-stamped to v5.
        assertThat(vCap.getValue().getBody()).contains("\"schemaVersion\":5").contains("delta_v");
        assertThat(resp.body().schemaVersion()).isEqualTo(5);
        assertThat(resp.body().chief().noradId()).isEqualTo(25544);
        assertThat(resp.body().deputies().get(0).maneuvers()).hasSize(1);
    }

    @Test
    void rejectsManeuverOnChief() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.addManeuver(id,
                new ManeuverDraft(25544, "2024-06-01T06:00:00Z", "ric", 0.0, 1.0, 0.0)))
                .isInstanceOf(ScenarioValidationException.class);
        verify(versions, never()).saveAndFlush(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    void rejectsManeuverEpochOutOfRange() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.addManeuver(id,
                new ManeuverDraft(33591, "2024-07-01T00:00:00Z", "ric", 0.0, 1.0, 0.0)))
                .isInstanceOf(ScenarioValidationException.class);
        verify(auditLog, never()).save(any());
    }

    @Test
    void rejectsNonRicManeuverFrame() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.addManeuver(id,
                new ManeuverDraft(33591, "2024-06-01T06:00:00Z", "body", 0.0, 1.0, 0.0)))
                .isInstanceOf(ScenarioValidationException.class);
        verify(auditLog, never()).save(any());
    }

    @Test
    void removeManeuverDropsItAndWritesANewVersion() {
        // Seed a body that already carries one maneuver (id "m-1") on the deputy.
        String withManeuver = DEPUTY_TLE_BODY_V2.replace(
                "\"name\":\"NOAA\","
                + "\"initialState\":{\"kind\":\"tle\",\"tle\":{\"line1\":\"1\",\"line2\":\"2\",\"epoch\":\"e\"}},\"maneuvers\":[]}",
                "\"name\":\"NOAA\","
                + "\"initialState\":{\"kind\":\"tle\",\"tle\":{\"line1\":\"1\",\"line2\":\"2\",\"epoch\":\"e\"}},"
                + "\"maneuvers\":[{\"id\":\"m-1\",\"kind\":\"delta_v\",\"epoch\":\"2024-06-01T06:00:00Z\","
                + "\"frame\":\"ric\",\"deltaV\":{\"r\":0.0,\"i\":1.5,\"c\":0.0}}]}");
        UUID id = UUID.randomUUID();
        existingWithBody(id, withManeuver);

        ScenarioResponse resp = service.removeManeuver(id, "m-1");

        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        assertThat(vCap.getValue().getBody()).doesNotContain("m-1");
        verify(auditLog, times(1)).save(any());
        assertThat(resp.body().deputies().get(0).maneuvers()).isEmpty();
    }

    @Test
    void rejectsRemovingUnknownManeuver() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.removeManeuver(id, "nope"))
                .isInstanceOf(ScenarioValidationException.class);
        verify(versions, never()).saveAndFlush(any());
        verify(auditLog, never()).save(any());
    }

    // --- sensors & attitude (Phase 7, US-SENSE-01 / US-PROX-01) --------------

    @Test
    void addSensorOnChiefWritesV3VersionWithOneAudit() {
        // UC-4: the imager rides the chief (the LVLH origin) — sensors are allowed there.
        UUID id = UUID.randomUUID();
        Scenario s = existingWithBody(id, DEPUTY_TLE_BODY_V2);

        ScenarioResponse resp = service.addSensor(id, new SensorDraft(
                25544, "optical", "Imager", "rect", 0, 20, 15, 100, 50000, 1, 0, 0, 0));

        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        assertThat(vCap.getValue().getVersionNo()).isEqualTo(2);
        assertThat(vCap.getValue().getBody()).contains("\"schemaVersion\":5").contains("Imager");
        assertThat(s.getLatestVersionId()).isEqualTo(vCap.getValue().getId());
        verify(auditLog, times(1)).save(any());
        assertThat(resp.body().chief().sensors()).hasSize(1);
        assertThat(resp.body().chief().sensors().get(0).fov().type()).isEqualTo("rect");
    }

    @Test
    void addSensorOnDeputyAndRemoveByIdRoundTrips() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        ScenarioResponse added = service.addSensor(id, new SensorDraft(
                33591, "lidar", "Rdv lidar", "cone", 10, 0, 0, 1, 5000, 1, 0, 0, 0));
        String sensorId = added.body().deputies().get(0).sensors().get(0).id();
        assertThat(sensorId).isNotBlank();

        // The remove reads the latest version — point findById at the just-saved one.
        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        ScenarioVersion saved = vCap.getValue();
        when(versions.findById(saved.getId())).thenReturn(Optional.of(saved));

        ScenarioResponse removed = service.removeSensor(id, sensorId);
        assertThat(removed.body().deputies().get(0).sensors()).isEmpty();
        verify(auditLog, times(2)).save(any()); // add + remove
    }

    @Test
    void rejectsSensorWithBadConeFov() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.addSensor(id, new SensorDraft(
                25544, "optical", "X", "cone", 120, 0, 0, 100, 5000, 1, 0, 0, 0)))
                .isInstanceOf(ScenarioValidationException.class);
        verify(versions, never()).saveAndFlush(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    void rejectsSensorWithInvertedRange() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.addSensor(id, new SensorDraft(
                33591, "optical", "X", "cone", 10, 0, 0, 5000, 100, 1, 0, 0, 0)))
                .isInstanceOf(ScenarioValidationException.class);
        verify(auditLog, never()).save(any());
    }

    @Test
    void rejectsRemovingUnknownSensor() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.removeSensor(id, "nope"))
                .isInstanceOf(ScenarioValidationException.class);
        verify(versions, never()).saveAndFlush(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    void setFixedAttitudeWritesNewVersionWithOneAudit() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        ScenarioResponse resp = service.setAttitude(id,
                new AttitudeDraft(33591, "fixed", new double[] {0, 0, 0, 1}));

        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        assertThat(vCap.getValue().getBody()).contains("\"mode\":\"fixed\"");
        verify(auditLog, times(1)).save(any());
        assertThat(resp.body().deputies().get(0).attitude().mode()).isEqualTo("fixed");
    }

    @Test
    void rejectsFixedAttitudeWithoutQuaternion() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.setAttitude(id, new AttitudeDraft(25544, "fixed", null)))
                .isInstanceOf(ScenarioValidationException.class);
        verify(auditLog, never()).save(any());
    }

    @Test
    void rejectsAttitudeOnUnknownNorad() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.setAttitude(id, new AttitudeDraft(99999, "lvlh", null)))
                .isInstanceOf(ScenarioValidationException.class);
        verify(auditLog, never()).save(any());
    }

    @Test
    void editingManeuverPreservesSensors() {
        // Regression: adding/removing a maneuver must not wipe a deputy's sensors (Phase 7).
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        service.addSensor(id, new SensorDraft(33591, "optical", "S", "cone", 8, 0, 0, 1, 5000, 1, 0, 0, 0));
        // The next mutation reads the latest body; re-stub findById's version to the just-saved one.
        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        ScenarioVersion saved = vCap.getValue();
        when(versions.findById(saved.getId())).thenReturn(Optional.of(saved));

        ScenarioResponse resp = service.addManeuver(id,
                new ManeuverDraft(33591, "2024-06-01T06:00:00Z", "ric", 0.0, 1.0, 0.0));
        assertThat(resp.body().deputies().get(0).sensors()).as("sensors survive a maneuver edit").hasSize(1);
        assertThat(resp.body().deputies().get(0).maneuvers()).hasSize(1);
    }

    // --- constraints & conjunctions (Phase 8, US-EVT-02 / US-EVT-03) ---------

    @Test
    void addApproachCorridorWritesV5VersionWithOneAudit() {
        UUID id = UUID.randomUUID();
        Scenario s = existingWithBody(id, DEPUTY_TLE_BODY_V2);

        ScenarioResponse resp = service.addConstraint(id,
                new ConstraintDraft(25544, "approach-corridor", null, 33591, 15.0, 5000.0));

        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        assertThat(vCap.getValue().getBody()).contains("\"schemaVersion\":5").contains("approach-corridor");
        assertThat(s.getLatestVersionId()).isEqualTo(vCap.getValue().getId());
        verify(auditLog, times(1)).save(any());
        assertThat(resp.body().chief().constraints()).hasSize(1);
        assertThat(resp.body().chief().constraints().get(0).targetNoradId()).isEqualTo(33591);
    }

    @Test
    void addSunKeepOutRequiresASensorOnTheHost() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        // No sensor on the host yet → 422.
        assertThatThrownBy(() -> service.addConstraint(id,
                new ConstraintDraft(25544, "sun-keep-out", "nope", 0, 20.0, 0.0)))
                .isInstanceOf(ScenarioValidationException.class);
        verify(auditLog, never()).save(any());
    }

    @Test
    void rejectsConstraintWithBadAngleAndUnknownHost() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        assertThatThrownBy(() -> service.addConstraint(id,
                new ConstraintDraft(25544, "approach-corridor", null, 33591, 200.0, 5000.0)))
                .isInstanceOf(ScenarioValidationException.class); // limitDeg out of (0,180)
        assertThatThrownBy(() -> service.addConstraint(id,
                new ConstraintDraft(99999, "approach-corridor", null, 33591, 15.0, 5000.0)))
                .isInstanceOf(ScenarioValidationException.class); // unknown host
        verify(versions, never()).saveAndFlush(any());
        verify(auditLog, never()).save(any());
    }

    @Test
    void setMissDistanceThresholdWritesNewVersion() {
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        ScenarioResponse resp = service.setMissDistanceThreshold(id, 1500.0);

        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        assertThat(vCap.getValue().getBody()).contains("missDistanceThresholdM");
        verify(auditLog, times(1)).save(any());
        assertThat(resp.body().missDistanceThresholdM()).isEqualTo(1500.0);
    }

    @Test
    void editingManeuverPreservesConstraintsAndThreshold() {
        // Regression: a maneuver edit must not wipe a host's constraints or the threshold (Phase 8).
        UUID id = UUID.randomUUID();
        existingWithBody(id, DEPUTY_TLE_BODY_V2);
        service.addConstraint(id, new ConstraintDraft(25544, "approach-corridor", null, 33591, 15.0, 5000.0));
        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        ScenarioVersion saved = vCap.getValue();
        when(versions.findById(saved.getId())).thenReturn(Optional.of(saved));

        ScenarioResponse resp = service.addManeuver(id,
                new ManeuverDraft(33591, "2024-06-01T06:00:00Z", "ric", 0.0, 1.0, 0.0));
        assertThat(resp.body().chief().constraints()).as("constraints survive a maneuver edit").hasSize(1);
        assertThat(resp.body().deputies().get(0).maneuvers()).hasSize(1);
    }

    @Test
    void snapshotFrozenAgainstLaterCatalogChange() {
        service.create(draft("Frozen", 25544, List.of()));
        ArgumentCaptor<ScenarioVersion> vCap = ArgumentCaptor.forClass(ScenarioVersion.class);
        verify(versions).saveAndFlush(vCap.capture());
        String persistedBody = vCap.getValue().getBody();
        assertThat(persistedBody).contains("1 25544U L1");

        // A catalog refresh changes the live TLE for the same NORAD id…
        when(catalog.findSnapshot(25544)).thenReturn(Optional.of(
                new TleSnapshot(25544, "ISS (ZARYA)", "1 25544U NEW1", "2 25544 NEW2", "2024-07-01T00:00:00.000")));

        // …but the already-saved version's body is unchanged (serialized at create time).
        assertThat(persistedBody).contains("1 25544U L1").doesNotContain("NEW1");
    }
}
