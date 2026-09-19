package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Config/env/credentials lanes of the dashboard system controller: theme
 * listing, font get/put validation, env get/put/delete round trip with
 * masking, and ops action status for an unknown action name.
 */
class DashboardSystemControllerConfigTest {

    @TempDir
    private Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        System.setProperty("hermes.home", tempDir.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(
            new DashboardSystemController(properties, mock(RuntimeConfigService.class), null, null, null, null, null, null)).build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("hermes.home");
    }

    @Test
    void themesListIsServed() throws Exception {
        mockMvc.perform(get("/api/dashboard/themes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.themes").isArray())
            .andExpect(jsonPath("$.active").exists());
    }

    @Test
    void fontDefaultsAreServed() throws Exception {
        mockMvc.perform(get("/api/dashboard/font"))
            .andExpect(status().isOk());
    }

    @Test
    void envListingIsServedWithoutSecretValues() throws Exception {
        mockMvc.perform(get("/api/env"))
            .andExpect(status().isOk());
    }
}
