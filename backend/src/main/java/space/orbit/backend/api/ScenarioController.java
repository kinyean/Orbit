package space.orbit.backend.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import space.orbit.backend.analysis.MonteCarloResult;
import space.orbit.backend.analysis.MonteCarloService;
import space.orbit.backend.analysis.RendezvousSearchResult;
import space.orbit.backend.analysis.RendezvousSearchService;
import space.orbit.backend.analysis.ScreeningResult;
import space.orbit.backend.analysis.ScreeningService;
import space.orbit.backend.io.OemExportService;
import space.orbit.backend.scenario.AttitudeDraft;
import space.orbit.backend.scenario.AuditEntryResponse;
import space.orbit.backend.scenario.ConstraintDraft;
import space.orbit.backend.scenario.LinkBudgetDraft;
import space.orbit.backend.scenario.ManeuverDraft;
import space.orbit.backend.scenario.ManeuverTemplateService;
import space.orbit.backend.scenario.ScenarioDraft;
import space.orbit.backend.scenario.SensorDraft;
import space.orbit.backend.scenario.ScenarioResponse;
import space.orbit.backend.scenario.ScenarioService;
import space.orbit.backend.scenario.ScenarioSummary;
import space.orbit.backend.scenario.ScenarioVersionResponse;
import space.orbit.backend.scenario.ScenarioVersionSummary;
import space.orbit.backend.scenario.VersionDiff;

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
    private final ScreeningService screening;
    private final RendezvousSearchService rendezvousSearch;
    private final MonteCarloService monteCarlo;
    private final OemExportService oemExport;

    public ScenarioController(ScenarioService service, ManeuverTemplateService templates,
                              ScreeningService screening, RendezvousSearchService rendezvousSearch,
                              MonteCarloService monteCarlo, OemExportService oemExport) {
        this.service = service;
        this.templates = templates;
        this.screening = screening;
        this.rendezvousSearch = rendezvousSearch;
        this.monteCarlo = monteCarlo;
        this.oemExport = oemExport;
    }

    @Tag(name = "Scenarios")
    @Operation(summary = "Create a scenario (v1, TLEs frozen from the catalog)")
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
    @Tag(name = "Scenarios")
    @Operation(summary = "Import a measured ephemeris (WOD CSV on the server) as a scenario")
    @PostMapping("/import/measured")
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioResponse importMeasured(@Valid @RequestBody MeasuredImportRequest req) {
        return service.importMeasured(req.path(), req.noradId());
    }

    @Tag(name = "Scenarios")
    @Operation(summary = "List my scenarios")
    @GetMapping
    public List<ScenarioSummary> list() {
        return service.list();
    }

    @Tag(name = "Scenarios")
    @Operation(summary = "Get a scenario (latest version)")
    @GetMapping("/{id}")
    public ScenarioResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Version history list (Phase 10, US-INFRA-06 / US-SCN-04) — metadata only. */
    @Tag(name = "Versions & audit")
    @Operation(summary = "List a scenario's version history")
    @GetMapping("/{id}/versions")
    public List<ScenarioVersionSummary> versions(@PathVariable UUID id) {
        return service.versionHistory(id);
    }

    @Tag(name = "Versions & audit")
    @Operation(summary = "Get one immutable version")
    @GetMapping("/{id}/versions/{v}")
    public ScenarioVersionResponse getVersion(@PathVariable UUID id, @PathVariable int v) {
        return service.getVersion(id, v);
    }

    /**
     * Structured diff of version {@code v} against its predecessor (Phase 10
     * governance follow-up) — what actually changed (ΔV numbers, epochs, sensors,
     * constraints, settings) rather than the flat audit summary. Owner-gated.
     */
    @Tag(name = "Versions & audit")
    @Operation(summary = "Diff a version against its predecessor")
    @GetMapping("/{id}/versions/{v}/diff")
    public VersionDiff versionDiff(@PathVariable UUID id, @PathVariable int v) {
        return service.versionDiff(id, v);
    }

    /** Audit trail (Phase 10, US-INFRA-06): who changed what, newest first. */
    @Tag(name = "Versions & audit")
    @Operation(summary = "Get the audit trail (who changed what, newest first)")
    @GetMapping("/{id}/audit")
    public List<AuditEntryResponse> audit(@PathVariable UUID id) {
        return service.auditTrail(id);
    }

    /**
     * Export the scenario's propagated ephemerides as one CCSDS OEM message
     * (Phase 11, US-IO-06 / SRS §4.2.1) — a KVN text attachment with a segment
     * per craft (EME2000/UTC), sampled from the same providers the stream flies.
     * Owner-gated (→ 404); the export is recorded in the audit trail
     * ({@code EXPORT_OEM}). Byte-identical on re-export of the same version (R11).
     */
    @Tag(name = "Export")
    @Operation(summary = "Export propagated ephemerides as a CCSDS OEM file")
    @GetMapping(value = "/{id}/export/oem", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportOem(@PathVariable UUID id) {
        OemExportService.OemExport export = oemExport.export(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + export.fileName() + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(export.content());
    }

    @Tag(name = "Scenarios")
    @Operation(summary = "Update a scenario (creates a new immutable version)")
    @PutMapping("/{id}")
    public ScenarioResponse update(@PathVariable UUID id, @Valid @RequestBody ScenarioRequest req) {
        return service.update(id, toDraft(req));
    }

    @Tag(name = "Scenarios")
    @Operation(summary = "Archive a scenario (soft delete; history preserved)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    // --- maneuvers (Phase 5B, US-MAN-01) -------------------------------------

    @Tag(name = "Maneuvers")
    @Operation(summary = "Add an impulsive or finite ΔV burn to a deputy")
    @PostMapping("/{id}/maneuvers")
    public ScenarioResponse addManeuver(@PathVariable UUID id, @Valid @RequestBody ManeuverRequest req) {
        return service.addManeuver(id, new ManeuverDraft(
                req.deputyNoradId(), req.epoch(), req.frame(), req.r(), req.i(), req.c(),
                req.thrustN(), req.ispSec()));
    }

    @Tag(name = "Maneuvers")
    @Operation(summary = "Remove a maneuver")
    @DeleteMapping("/{id}/maneuvers/{maneuverId}")
    public ScenarioResponse removeManeuver(@PathVariable UUID id, @PathVariable String maneuverId) {
        return service.removeManeuver(id, maneuverId);
    }

    // --- maneuver templates (Phase 5C, US-MAN-02 / US-MAN-03) ----------------

    @Tag(name = "Maneuvers")
    @Operation(summary = "Insert a Hohmann transfer to a target altitude (template)")
    @PostMapping("/{id}/maneuvers/hohmann")
    public ScenarioResponse hohmann(@PathVariable UUID id, @Valid @RequestBody HohmannRequest req) {
        return templates.hohmann(id, req.deputyNoradId(), req.targetAltitudeKm());
    }

    @Tag(name = "Maneuvers")
    @Operation(summary = "Insert a two-impulse rendezvous (differential-corrected by default)")
    @PostMapping("/{id}/maneuvers/rendezvous")
    public ScenarioResponse rendezvous(@PathVariable UUID id, @Valid @RequestBody RendezvousRequest req) {
        boolean corrected = req.corrected() == null || req.corrected();
        return templates.rendezvous(id, req.deputyNoradId(), req.arrivalEpoch(), corrected, req.nRev());
    }

    /**
     * Arrival-time × revolution ΔV search (Phase 9A, US-MAN-03). One-shot analysis (not
     * the stream) → a sorted ΔV map the UI shows as a heatmap; picking a cell feeds the
     * corrected {@code rendezvous} endpoint with its {@code nRev}.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "Search the rendezvous arrival-time × revolution ΔV map")
    @PostMapping("/{id}/maneuvers/rendezvous/search")
    public RendezvousSearchResult searchRendezvous(@PathVariable UUID id,
                                                   @Valid @RequestBody RendezvousSearchRequest req) {
        return rendezvousSearch.search(id, req.deputyNoradId());
    }

    /** Phasing-orbit rendezvous template (Phase 9A, US-MAN-06): close the along-track
     *  phase gap over {@code phasingRevs} revolutions with two in-track burns. */
    @Tag(name = "Maneuvers")
    @Operation(summary = "Insert a phasing-orbit transfer (template)")
    @PostMapping("/{id}/maneuvers/phasing")
    public ScenarioResponse phasing(@PathVariable UUID id, @Valid @RequestBody PhasingRequest req) {
        return templates.phasing(id, req.deputyNoradId(), req.phasingRevs());
    }

    // --- close-range CW templates (Phase 9B, US-MAN-07..10) ------------------

    /** NMC insertion (Phase 9B, US-MAN-09): one in-track burn onto a bounded relative orbit. */
    @Tag(name = "Maneuvers")
    @Operation(summary = "Insert an NMC circumnavigation burn (template)")
    @PostMapping("/{id}/maneuvers/nmc")
    public ScenarioResponse nmc(@PathVariable UUID id, @Valid @RequestBody NmcRequest req) {
        return templates.nmc(id, req.deputyNoradId());
    }

    /** V-bar / R-bar hold (Phase 9B, US-MAN-07/08/10): CW two-impulse transfer to a hold point. */
    @Tag(name = "Maneuvers")
    @Operation(summary = "Insert a V-bar/R-bar hold-point transfer (template)")
    @PostMapping("/{id}/maneuvers/hold")
    public ScenarioResponse hold(@PathVariable UUID id, @Valid @RequestBody HoldRequest req) {
        return templates.hold(id, req.deputyNoradId(), req.axis(), req.distanceM(), req.arrivalEpoch());
    }

    /** Glideslope approach (Phase 9B, US-MAN-09): constant-closing-rate V-bar/R-bar approach. */
    @Tag(name = "Maneuvers")
    @Operation(summary = "Insert a constant-rate glideslope approach (template)")
    @PostMapping("/{id}/maneuvers/glideslope")
    public ScenarioResponse glideslope(@PathVariable UUID id, @Valid @RequestBody GlideslopeRequest req) {
        return templates.glideslope(id, req.deputyNoradId(), req.axis(),
                req.startRangeM(), req.endRangeM(), req.closingRateMps(), req.segments());
    }

    /** Closed-loop station-keeping (Phase 9B, US-MAN-10): periodic corrective burns holding a point. */
    @Tag(name = "Maneuvers")
    @Operation(summary = "Insert closed-loop station-keeping burns (template)")
    @PostMapping("/{id}/maneuvers/station-keep")
    public ScenarioResponse stationKeep(@PathVariable UUID id, @Valid @RequestBody StationKeepRequest req) {
        return templates.stationKeep(id, req.deputyNoradId(), req.axis(),
                req.distanceM(), req.intervalSec(), req.corrections());
    }

    // --- sensors & attitude (Phase 7, US-SENSE-01 / US-PROX-01) --------------

    @Tag(name = "Sensors & attitude")
    @Operation(summary = "Add a body-fixed sensor to a craft")
    @PostMapping("/{id}/sensors")
    public ScenarioResponse addSensor(@PathVariable UUID id, @Valid @RequestBody SensorRequest req) {
        return service.addSensor(id, new SensorDraft(
                req.noradId(), req.kind(), req.name(), req.fovType(),
                req.halfAngleDeg(), req.hDeg(), req.vDeg(), req.minRangeM(), req.maxRangeM(),
                req.boresightX(), req.boresightY(), req.boresightZ(), req.clockDeg()));
    }

    @Tag(name = "Sensors & attitude")
    @Operation(summary = "Remove a sensor")
    @DeleteMapping("/{id}/sensors/{sensorId}")
    public ScenarioResponse removeSensor(@PathVariable UUID id, @PathVariable String sensorId) {
        return service.removeSensor(id, sensorId);
    }

    /** Set a sensor's RF/optical link budget (Phase 9D, US-EVT-05) → SNR series stream. */
    @Tag(name = "Sensors & attitude")
    @Operation(summary = "Set or clear a sensor's RF/optical link budget")
    @PutMapping("/{id}/sensors/{sensorId}/link-budget")
    public ScenarioResponse setLinkBudget(@PathVariable UUID id, @PathVariable String sensorId,
                                          @Valid @RequestBody LinkBudgetRequest req) {
        return service.setLinkBudget(id, sensorId, new LinkBudgetDraft(
                req.kind(), req.eirpDbw(), req.gOverTdbK(), req.frequencyGhz(), req.bandwidthHz(), req.thresholdDb()));
    }

    @Tag(name = "Sensors & attitude")
    @Operation(summary = "Set a craft's attitude profile (lvlh / fixed)")
    @PutMapping("/{id}/attitude")
    public ScenarioResponse setAttitude(@PathVariable UUID id, @Valid @RequestBody AttitudeRequest req) {
        return service.setAttitude(id, new AttitudeDraft(req.noradId(), req.mode(), req.quaternion()));
    }

    // --- constraints & conjunctions (Phase 8, US-EVT-02 / US-EVT-03) ---------

    @Tag(name = "Environment & constraints")
    @Operation(summary = "Add a sun-keep-out or approach-corridor constraint")
    @PostMapping("/{id}/constraints")
    public ScenarioResponse addConstraint(@PathVariable UUID id, @Valid @RequestBody ConstraintRequest req) {
        return service.addConstraint(id, new ConstraintDraft(
                req.hostNoradId(), req.kind(), req.sensorId(), req.targetNoradId(),
                req.limitDeg(), req.rangeM()));
    }

    @Tag(name = "Environment & constraints")
    @Operation(summary = "Remove a constraint")
    @DeleteMapping("/{id}/constraints/{constraintId}")
    public ScenarioResponse removeConstraint(@PathVariable UUID id, @PathVariable String constraintId) {
        return service.removeConstraint(id, constraintId);
    }

    @Tag(name = "Environment & constraints")
    @Operation(summary = "Set the conjunction miss-distance threshold")
    @PutMapping("/{id}/miss-distance")
    public ScenarioResponse setMissDistance(@PathVariable UUID id, @Valid @RequestBody MissDistanceRequest req) {
        return service.setMissDistanceThreshold(id, req.missDistanceThresholdM());
    }

    /**
     * Screen the scenario craft against the live catalog (Phase 8, US-EVT-02 / UC-7).
     * One-shot analysis (not the stream) → a sorted list of close approaches below
     * {@code thresholdKm} (default 5 km).
     */
    @Tag(name = "Analysis")
    @Operation(summary = "Screen the scenario against the full live catalog (snapshot)")
    @PostMapping("/{id}/screening")
    public ScreeningResult screen(@PathVariable UUID id,
                                  @RequestParam(name = "thresholdKm", defaultValue = "5.0") double thresholdKm) {
        return screening.screen(id, thresholdKm);
    }

    /**
     * Monte Carlo dispersion + covariance for a deputy (Phase 9C, UC-6, US-MC-01/02). A
     * one-shot analysis (not the stream): runs {@code sampleCount} seeded samples → a
     * trajectory cloud + per-epoch covariance ellipsoids. Reproducible given the seed.
     */
    @Tag(name = "Analysis")
    @Operation(summary = "Run seeded Monte Carlo dispersion for a deputy")
    @PostMapping("/{id}/monte-carlo")
    public MonteCarloResult monteCarlo(@PathVariable UUID id, @Valid @RequestBody MonteCarloRequest req) {
        return monteCarlo.analyze(id, req.deputyNoradId(), new MonteCarloService.Params(
                req.sampleCount(), req.seed(), req.posSigmaM(), req.velSigmaMs(),
                req.dvMagFrac(), req.dvPointingDeg()));
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
     * ΔV maneuver payload (Phase 5B, US-MAN-01). {@code frame} optional (defaults to
     * RIC); ΔV components are metres/second. Optional {@code thrustN} (N) + {@code ispSec}
     * (s) make it a finite burn (Phase 9, US-MAN-11) — both required together, else 422;
     * omit both for an impulsive ΔV.
     */
    public record ManeuverRequest(
            @Positive int deputyNoradId,
            @NotBlank String epoch,
            String frame,
            double r,
            double i,
            double c,
            Double thrustN,
            Double ispSec) {
    }

    /** Hohmann template payload (Phase 5C, US-MAN-02): target circular altitude (km). */
    public record HohmannRequest(
            @Positive int deputyNoradId,
            @Positive double targetAltitudeKm) {
    }

    /**
     * Rendezvous payload (Phase 5C / 9A, US-MAN-03): chief-arrival epoch (ISO-8601 UTC).
     * {@code corrected} (default true) runs the differential corrector against the real
     * propagators (R16); {@code nRev} (optional, from the arrival×rev search) fixes the
     * transfer revolution count instead of searching all feasible counts.
     */
    public record RendezvousRequest(
            @Positive int deputyNoradId,
            @NotBlank String arrivalEpoch,
            Boolean corrected,
            Integer nRev) {
    }

    /** Rendezvous arrival×rev search payload (Phase 9A): the deputy to search for. */
    public record RendezvousSearchRequest(@Positive int deputyNoradId) {
    }

    /** Phasing-orbit template payload (Phase 9A, US-MAN-06): revolutions to phase over. */
    public record PhasingRequest(
            @Positive int deputyNoradId,
            @Positive int phasingRevs) {
    }

    /** NMC insertion payload (Phase 9B, US-MAN-09): the deputy to put on a bounded orbit. */
    public record NmcRequest(@Positive int deputyNoradId) {
    }

    /**
     * Monte Carlo dispersion payload (Phase 9C, UC-6). {@code sampleCount} 0 → default;
     * {@code seed} makes the run reproducible; the σ are 1-σ initial-state uncertainty
     * (position m, velocity m/s) and maneuver execution error (ΔV fraction, pointing °).
     */
    public record MonteCarloRequest(
            @Positive int deputyNoradId,
            int sampleCount,
            long seed,
            double posSigmaM,
            double velSigmaMs,
            double dvMagFrac,
            double dvPointingDeg) {
    }

    /**
     * V-bar/R-bar hold payload (Phase 9B, US-MAN-07/08/10). {@code axis} ∈ {vbar, rbar};
     * signed {@code distanceM} places the hold point ahead/behind or above/below the chief;
     * {@code arrivalEpoch} is when the deputy parks there (ISO-8601 UTC).
     */
    public record HoldRequest(
            @Positive int deputyNoradId,
            @NotBlank String axis,
            double distanceM,
            @NotBlank String arrivalEpoch) {
    }

    /**
     * Glideslope payload (Phase 9B, US-MAN-09): a constant-closing-rate approach along
     * {@code axis} ({@code "vbar"}/{@code "rbar"}) from {@code startRangeM} to {@code endRangeM}
     * (signed, same side, closing in) at {@code closingRateMps}, in {@code segments} legs.
     */
    public record GlideslopeRequest(
            @Positive int deputyNoradId,
            @NotBlank String axis,
            double startRangeM,
            double endRangeM,
            @Positive double closingRateMps,
            @Positive int segments) {
    }

    /**
     * Station-keeping payload (Phase 9B, US-MAN-10): hold the deputy at the {@code axis}
     * ({@code "vbar"}/{@code "rbar"}) point {@code distanceM} from the chief with a corrective
     * burn every {@code intervalSec}, for {@code corrections} corrections (bounded by the window).
     */
    public record StationKeepRequest(
            @Positive int deputyNoradId,
            @NotBlank String axis,
            double distanceM,
            @Positive double intervalSec,
            @Positive int corrections) {
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

    /**
     * Link-budget payload (Phase 9D, US-EVT-05). {@code kind} ∈ {rf, optical}; a concise
     * Friis model (EIRP + G/T − free-space loss + Boltzmann − 10·log10 B). Semantics → 422.
     */
    public record LinkBudgetRequest(
            String kind,
            double eirpDbw,
            double gOverTdbK,
            @Positive double frequencyGhz,
            @Positive double bandwidthHz,
            double thresholdDb) {
    }

    /** Set-attitude payload (Phase 7). {@code mode} ∈ {lvlh, fixed}; {@code quaternion} = [x,y,z,w] for fixed. */
    public record AttitudeRequest(
            @Positive int noradId,
            String mode,
            double[] quaternion) {
    }

    /**
     * Add-constraint payload (Phase 8, US-EVT-03). {@code kind} ∈ {sun-keep-out,
     * approach-corridor}. sun-keep-out uses {@code sensorId} (on the host) + {@code limitDeg};
     * approach-corridor uses {@code targetNoradId} + {@code limitDeg} + {@code rangeM}.
     * Semantics validated in the service → 422.
     */
    public record ConstraintRequest(
            @Positive int hostNoradId,
            @NotBlank String kind,
            String sensorId,
            int targetNoradId,
            double limitDeg,
            double rangeM) {
    }

    /** Set conjunction miss-distance threshold (Phase 8, US-EVT-02). Null clears it. */
    public record MissDistanceRequest(Double missDistanceThresholdM) {
    }
}
