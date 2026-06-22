package space.orbit.backend.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import space.orbit.backend.scenario.AttitudeDraft;
import space.orbit.backend.scenario.ManeuverDraft;
import space.orbit.backend.scenario.ManeuverTemplateService;
import space.orbit.backend.scenario.ScenarioDraft;
import space.orbit.backend.scenario.SensorDraft;
import space.orbit.backend.scenario.ScenarioResponse;
import space.orbit.backend.scenario.ScenarioService;
import space.orbit.backend.scenario.ScenarioSummary;
import space.orbit.backend.scenario.ScenarioVersionResponse;

/**
 * Scenario CRUD (US-SCN-03). Mirrors {@link HealthController}: thin controller,
 * record DTOs, constructor injection; springdoc auto-exposes everything at
 * {@code /v3/api-docs} for the frontend's {@code gen:api}.
 *
 * <p>Requests carry NORAD ids only — {@link ScenarioService} resolves display
 * names + frozen TLE snapshots from the catalog. Responses return the typed
 * {@link ScenarioResponse}/{@code ScenarioBody} so the generated client gets a
 * usable schema. Errors map to 404 / 409 / 422 via {@link ScenarioExceptionHandler}.
 *
 * <p>Served at {@code /scenarios}; the frontend reaches it at {@code /api/scenarios}
 * (the Vite proxy strips {@code /api}).
 */
@RestController
@RequestMapping("/scenarios")
public class ScenarioController {

    private final ScenarioService service;
    private final ManeuverTemplateService templates;

    public ScenarioController(ScenarioService service, ManeuverTemplateService templates) {
        this.service = service;
        this.templates = templates;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioResponse create(@Valid @RequestBody ScenarioRequest req) {
        return service.create(toDraft(req));
    }

    /**
     * Import a measured ephemeris (a WOD CSV already on the server) as a new
     * scenario whose chief is the measured craft (US-SCN-06 generalized). JSON
     * body — the file is read server-side from {@code path} (constrained to
     * {@code orbit.import.allowed-root}); no upload. Returns the created scenario.
     */
    @PostMapping("/import/measured")
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioResponse importMeasured(@Valid @RequestBody MeasuredImportRequest req) {
        return service.importMeasured(req.path(), req.noradId());
    }

    @GetMapping
    public List<ScenarioSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ScenarioResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/versions/{v}")
    public ScenarioVersionResponse getVersion(@PathVariable UUID id, @PathVariable int v) {
        return service.getVersion(id, v);
    }

    @PutMapping("/{id}")
    public ScenarioResponse update(@PathVariable UUID id, @Valid @RequestBody ScenarioRequest req) {
        return service.update(id, toDraft(req));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    // --- maneuvers (Phase 5B, US-MAN-01) -------------------------------------

    @PostMapping("/{id}/maneuvers")
    public ScenarioResponse addManeuver(@PathVariable UUID id, @Valid @RequestBody ManeuverRequest req) {
        return service.addManeuver(id, new ManeuverDraft(
                req.deputyNoradId(), req.epoch(), req.frame(), req.r(), req.i(), req.c()));
    }

    @DeleteMapping("/{id}/maneuvers/{maneuverId}")
    public ScenarioResponse removeManeuver(@PathVariable UUID id, @PathVariable String maneuverId) {
        return service.removeManeuver(id, maneuverId);
    }

    // --- maneuver templates (Phase 5C, US-MAN-02 / US-MAN-03) ----------------

    @PostMapping("/{id}/maneuvers/hohmann")
    public ScenarioResponse hohmann(@PathVariable UUID id, @Valid @RequestBody HohmannRequest req) {
        return templates.hohmann(id, req.deputyNoradId(), req.targetAltitudeKm());
    }

    @PostMapping("/{id}/maneuvers/rendezvous")
    public ScenarioResponse rendezvous(@PathVariable UUID id, @Valid @RequestBody RendezvousRequest req) {
        return templates.rendezvous(id, req.deputyNoradId(), req.arrivalEpoch());
    }

    // --- sensors & attitude (Phase 7, US-SENSE-01 / US-PROX-01) --------------

    @PostMapping("/{id}/sensors")
    public ScenarioResponse addSensor(@PathVariable UUID id, @Valid @RequestBody SensorRequest req) {
        return service.addSensor(id, new SensorDraft(
                req.noradId(), req.kind(), req.name(), req.fovType(),
                req.halfAngleDeg(), req.hDeg(), req.vDeg(), req.minRangeM(), req.maxRangeM(),
                req.boresightX(), req.boresightY(), req.boresightZ(), req.clockDeg()));
    }

    @DeleteMapping("/{id}/sensors/{sensorId}")
    public ScenarioResponse removeSensor(@PathVariable UUID id, @PathVariable String sensorId) {
        return service.removeSensor(id, sensorId);
    }

    @PutMapping("/{id}/attitude")
    public ScenarioResponse setAttitude(@PathVariable UUID id, @Valid @RequestBody AttitudeRequest req) {
        return service.setAttitude(id, new AttitudeDraft(req.noradId(), req.mode(), req.quaternion()));
    }

    private static ScenarioDraft toDraft(ScenarioRequest req) {
        List<Integer> deputyIds = req.deputies() == null
                ? List.of()
                : req.deputies().stream().map(RoleRef::noradId).toList();
        return new ScenarioDraft(
                req.name(),
                req.fidelity(),
                req.timeRange().start(),
                req.timeRange().end(),
                req.chief().noradId(),
                deputyIds);
    }

    // --- request DTOs (field-shape validation; semantics in the service) ------

    /** Create/update payload. {@code fidelity} optional (defaults to sgp4). */
    public record ScenarioRequest(
            @NotBlank String name,
            String fidelity,
            @NotNull @Valid TimeRangeRequest timeRange,
            @NotNull @Valid RoleRef chief,
            @Valid List<RoleRef> deputies) {
    }

    public record TimeRangeRequest(@NotBlank String start, @NotBlank String end) {
    }

    /**
     * Measured-data import payload. {@code path} is a server-side file path
     * (within {@code orbit.import.allowed-root}); {@code noradId} is optional —
     * used when the satellite name isn't resolvable from the catalog.
     */
    public record MeasuredImportRequest(@NotBlank String path, Integer noradId) {
    }

    public record RoleRef(@Positive int noradId) {
    }

    /**
     * Impulsive ΔV maneuver payload (Phase 5B, US-MAN-01). {@code frame} optional
     * (defaults to RIC, the only frame in 5B); ΔV components are metres/second.
     */
    public record ManeuverRequest(
            @Positive int deputyNoradId,
            @NotBlank String epoch,
            String frame,
            double r,
            double i,
            double c) {
    }

    /** Hohmann template payload (Phase 5C, US-MAN-02): target circular altitude (km). */
    public record HohmannRequest(
            @Positive int deputyNoradId,
            @Positive double targetAltitudeKm) {
    }

    /** Lambert rendezvous payload (Phase 5C, US-MAN-03): chief-arrival epoch (ISO-8601 UTC). */
    public record RendezvousRequest(
            @Positive int deputyNoradId,
            @NotBlank String arrivalEpoch) {
    }

    /**
     * Add-sensor payload (Phase 7, US-SENSE-01). {@code noradId} is the host (chief
     * or deputy). {@code fovType} ∈ {cone, rect}; {@code halfAngleDeg} for cone,
     * {@code hDeg}/{@code vDeg} for rect. Range in metres; boresight in body axes
     * (defaults to +X when all zero); semantics validated in the service → 422.
     */
    public record SensorRequest(
            @Positive int noradId,
            String kind,
            String name,
            String fovType,
            double halfAngleDeg,
            double hDeg,
            double vDeg,
            double minRangeM,
            double maxRangeM,
            double boresightX,
            double boresightY,
            double boresightZ,
            double clockDeg) {
    }

    /** Set-attitude payload (Phase 7). {@code mode} ∈ {lvlh, fixed}; {@code quaternion} = [x,y,z,w] for fixed. */
    public record AttitudeRequest(
            @Positive int noradId,
            String mode,
            double[] quaternion) {
    }
}
