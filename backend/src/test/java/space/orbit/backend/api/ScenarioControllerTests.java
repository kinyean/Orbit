package space.orbit.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import space.orbit.backend.analysis.DvCell;
import space.orbit.backend.analysis.MonteCarloResult;
import space.orbit.backend.analysis.MonteCarloService;
import space.orbit.backend.analysis.RendezvousSearchResult;
import space.orbit.backend.analysis.RendezvousSearchService;
import space.orbit.backend.analysis.ScreeningService;
import space.orbit.backend.scenario.DuplicateScenarioNameException;
import space.orbit.backend.scenario.ManeuverTemplateService;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioNotFoundException;
import space.orbit.backend.scenario.ScenarioResponse;
import space.orbit.backend.scenario.ScenarioService;
import space.orbit.backend.scenario.ScenarioValidationException;

/**
 * Web-slice test for {@link ScenarioController} (mirrors HealthControllerTests:
 * web layer only, security filters disabled, service mocked). Asserts status
 * codes + JSON shapes, including the 404 / 409 / 422 paths handled by
 * {@link ScenarioExceptionHandler}.
 */
@WebMvcTest(ScenarioController.class)
@AutoConfigureMockMvc(addFilters = false)
class ScenarioControllerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ScenarioService service;

    @MockitoBean
    private ManeuverTemplateService templates;

    @MockitoBean
    private ScreeningService screening;

    @MockitoBean
    private RendezvousSearchService rendezvousSearch;

    @MockitoBean
    private MonteCarloService monteCarlo;

    private static final String VALID_BODY = """
            {"name":"Rendezvous","fidelity":"sgp4",
             "timeRange":{"start":"2024-06-01T00:00:00Z","end":"2024-06-02T00:00:00Z"},
             "chief":{"noradId":25544},"deputies":[{"noradId":33591}]}
            """;

    private static ScenarioResponse sampleResponse() {
        ScenarioBody body = new ScenarioBody(1, "sgp4",
                new ScenarioBody.TimeRange("2024-06-01T00:00:00Z", "2024-06-02T00:00:00Z"),
                new ScenarioBody.Role("chief", 25544, "ISS (ZARYA)",
                        new ScenarioBody.InitialState("tle",
                                new ScenarioBody.Tle("l1", "l2", "2024-06-01T12:00:00.000"))),
                List.of());
        return new ScenarioResponse(UUID.randomUUID().toString(), "Rendezvous",
                "00000000-0000-0000-0000-000000000001", "2024-06-01T00:00:00Z", 1, 1, body);
    }

    @Test
    void createReturns201AndBody() throws Exception {
        when(service.create(any())).thenReturn(sampleResponse());
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Rendezvous"))
                .andExpect(jsonPath("$.latestVersionNo").value(1))
                .andExpect(jsonPath("$.body.fidelity").value("sgp4"))
                .andExpect(jsonPath("$.body.chief.noradId").value(25544));
    }

    @Test
    void blankNameReturns422() throws Exception {
        String body = VALID_BODY.replace("\"Rendezvous\"", "\"\"");
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getMissingReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new ScenarioNotFoundException(id));
        mvc.perform(get("/scenarios/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateNameReturns409() throws Exception {
        when(service.create(any())).thenThrow(new DuplicateScenarioNameException("Rendezvous"));
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void unresolvableNoradReturns422() throws Exception {
        when(service.create(any()))
                .thenThrow(new ScenarioValidationException("NORAD id 99999 is not in the catalog"));
        mvc.perform(post("/scenarios").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("99999")));
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/scenarios/{id}", UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }

    @Test
    void listReturnsArray() throws Exception {
        when(service.list()).thenReturn(List.of());
        mvc.perform(get("/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void versionsReturnsHistory() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.versionHistory(id)).thenReturn(List.of(
                new space.orbit.backend.scenario.ScenarioVersionSummary(1, "maya@orbit.local", "2026-07-01T00:00:00Z"),
                new space.orbit.backend.scenario.ScenarioVersionSummary(2, "maya@orbit.local", "2026-07-01T01:00:00Z")));
        mvc.perform(get("/scenarios/{id}/versions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].versionNo").value(2))
                .andExpect(jsonPath("$[0].authorEmail").value("maya@orbit.local"));
    }

    @Test
    void auditReturnsTrail() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.auditTrail(id)).thenReturn(List.of(
                new space.orbit.backend.scenario.AuditEntryResponse(
                        "MANEUVER_ADD", "maya@orbit.local", "2026-07-01T02:00:00Z", "Added ΔV to Deputy-1")));
        mvc.perform(get("/scenarios/{id}/audit", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("MANEUVER_ADD"))
                .andExpect(jsonPath("$[0].actorEmail").value("maya@orbit.local"));
    }

    @Test
    void addManeuverReturnsUpdatedScenario() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.addManeuver(any(), any())).thenReturn(sampleResponse());
        String body = "{\"deputyNoradId\":33591,\"epoch\":\"2024-06-01T06:00:00Z\","
                + "\"frame\":\"ric\",\"r\":0.0,\"i\":1.5,\"c\":0.0}";
        mvc.perform(post("/scenarios/{id}/maneuvers", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fidelity").value("sgp4"));
    }

    @Test
    void hohmannTemplateReturnsUpdatedScenario() throws Exception {
        UUID id = UUID.randomUUID();
        when(templates.hohmann(any(), Mockito.anyInt(), Mockito.anyDouble())).thenReturn(sampleResponse());
        String body = "{\"deputyNoradId\":33591,\"targetAltitudeKm\":600.0}";
        mvc.perform(post("/scenarios/{id}/maneuvers/hohmann", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void rendezvousCorrectedReturnsUpdatedScenario() throws Exception {
        UUID id = UUID.randomUUID();
        when(templates.rendezvous(any(), Mockito.anyInt(), any(), Mockito.anyBoolean(), any()))
                .thenReturn(sampleResponse());
        String body = "{\"deputyNoradId\":33591,\"arrivalEpoch\":\"2024-06-01T01:00:00Z\",\"corrected\":true}";
        mvc.perform(post("/scenarios/{id}/maneuvers/rendezvous", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void rendezvousSearchReturnsDvMap() throws Exception {
        UUID id = UUID.randomUUID();
        when(rendezvousSearch.search(any(), Mockito.anyInt())).thenReturn(
                new RendezvousSearchResult(33591, "2024-06-01T00:00:00Z", "2024-06-02T00:00:00Z", 10, 7,
                        List.of(new DvCell("2024-06-01T01:30:00Z", 1, 12.0, 8.0, 20.0)),
                        new DvCell("2024-06-01T01:30:00Z", 1, 12.0, 8.0, 20.0)));
        mvc.perform(post("/scenarios/{id}/maneuvers/rendezvous/search", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deputyNoradId\":33591}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cheapest.nRev").value(1))
                .andExpect(jsonPath("$.cells[0].totalDvMs").value(20.0));
    }

    @Test
    void phasingReturnsUpdatedScenario() throws Exception {
        UUID id = UUID.randomUUID();
        when(templates.phasing(any(), Mockito.anyInt(), Mockito.anyInt())).thenReturn(sampleResponse());
        mvc.perform(post("/scenarios/{id}/maneuvers/phasing", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deputyNoradId\":33591,\"phasingRevs\":5}"))
                .andExpect(status().isOk());
    }

    @Test
    void nmcReturnsUpdatedScenario() throws Exception {
        UUID id = UUID.randomUUID();
        when(templates.nmc(any(), Mockito.anyInt())).thenReturn(sampleResponse());
        mvc.perform(post("/scenarios/{id}/maneuvers/nmc", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deputyNoradId\":33591}"))
                .andExpect(status().isOk());
    }

    @Test
    void holdReturnsUpdatedScenario() throws Exception {
        UUID id = UUID.randomUUID();
        when(templates.hold(any(), Mockito.anyInt(), any(), Mockito.anyDouble(), any()))
                .thenReturn(sampleResponse());
        mvc.perform(post("/scenarios/{id}/maneuvers/hold", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deputyNoradId\":33591,\"axis\":\"vbar\",\"distanceM\":1000.0,"
                                + "\"arrivalEpoch\":\"2024-06-01T01:00:00Z\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void monteCarloReturnsResult() throws Exception {
        UUID id = UUID.randomUUID();
        when(monteCarlo.analyze(any(), Mockito.anyInt(), any())).thenReturn(
                new MonteCarloResult(33591, "DEP", 42L, 200, 150, "2026-06-25T00:00:00Z", 0L, 30, 2,
                        List.of(new double[] {0, 0, 0}), List.of(), 0));
        mvc.perform(post("/scenarios/{id}/monte-carlo", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deputyNoradId\":33591,\"sampleCount\":200,\"seed\":42,\"posSigmaM\":100,"
                                + "\"velSigmaMs\":0.1,\"dvMagFrac\":0.01,\"dvPointingDeg\":0.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(42));
    }

    @Test
    void setLinkBudgetReturnsUpdatedScenario() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.setLinkBudget(any(), any(), any())).thenReturn(sampleResponse());
        mvc.perform(put("/scenarios/{id}/sensors/{sensorId}/link-budget", id, "s1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"rf\",\"eirpDbw\":20,\"gOverTdbK\":5,\"frequencyGhz\":2.2,"
                                + "\"bandwidthHz\":1000000,\"thresholdDb\":10}"))
                .andExpect(status().isOk());
    }

    @Test
    void addConstraintReturnsUpdatedScenario() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.addConstraint(any(), any())).thenReturn(sampleResponse());
        String body = "{\"hostNoradId\":25544,\"kind\":\"approach-corridor\","
                + "\"targetNoradId\":33591,\"limitDeg\":15.0,\"rangeM\":5000.0}";
        mvc.perform(post("/scenarios/{id}/constraints", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fidelity").value("sgp4"));
    }

    @Test
    void screenReturnsResults() throws Exception {
        UUID id = UUID.randomUUID();
        when(screening.screen(any(), Mockito.anyDouble())).thenReturn(
                new space.orbit.backend.analysis.ScreeningResult(5000.0, "2026-06-23T00:00:00Z", 14500, 3,
                        List.of(new space.orbit.backend.analysis.ConjunctionResult(
                                25544, "ISS", 40001, "TWIN", "2026-06-23T00:10:00Z", 842.0))));
        mvc.perform(post("/scenarios/{id}/screening", id).param("thresholdKm", "5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conjunctions[0].catalogNoradId").value(40001))
                .andExpect(jsonPath("$.catalogSize").value(14500));
    }
}
