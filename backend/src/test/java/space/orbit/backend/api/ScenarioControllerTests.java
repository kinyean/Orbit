package space.orbit.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
