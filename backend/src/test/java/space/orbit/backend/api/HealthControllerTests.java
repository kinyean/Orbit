package space.orbit.backend.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link HealthController}. Loads only the web layer (no
 * JPA, no Flyway, no DB) and disables the security filter chain — we're
 * exercising the controller's logic, not auth. The {@link DataSource} is
 * mocked so we can drive both the healthy and db-down branches deterministically.
 */
@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
class HealthControllerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DataSource dataSource;

    @Test
    void reportsUpWhenDatabaseConnectionValid() throws Exception {
        Connection conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.isValid(1)).thenReturn(true);

        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbStatus").value("up"))
                .andExpect(jsonPath("$.contractVersion").value(HealthController.CONTRACT_VERSION))
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.buildTime").exists())
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void reportsDownWhenDatabaseConnectionInvalid() throws Exception {
        Connection conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.isValid(1)).thenReturn(false);

        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbStatus").value("down"));
    }

    @Test
    void reportsDownWhenDatabaseUnreachable() throws Exception {
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("boom"));

        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbStatus").value("down"));
    }
}
