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
import space.orbit.backend.scenario.ScenarioDraft;
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

    public ScenarioController(ScenarioService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioResponse create(@Valid @RequestBody ScenarioRequest req) {
        return service.create(toDraft(req));
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

    public record RoleRef(@Positive int noradId) {
    }
}
