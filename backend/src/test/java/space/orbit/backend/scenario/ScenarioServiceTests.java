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
    @Mock private UserService userService;
    @Mock private CatalogService catalog;

    private ScenarioService service;

    @BeforeEach
    void setUp() {
        service = new ScenarioService(scenarios, versions, auditLog, userService, catalog, new ObjectMapper());
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
        existing.setLatestVersionId(UUID.randomUUID());
        when(scenarios.findById(id)).thenReturn(Optional.of(existing));
        when(versions.findMaxVersionNo(id)).thenReturn(Optional.of(1));
        when(versions.countByScenarioId(id)).thenReturn(2L);

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
