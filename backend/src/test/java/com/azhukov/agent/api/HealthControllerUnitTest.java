package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.OpenAiRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerUnitTest {

    private MockMvc mockMvc;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = mock(AgentProperties.class);
        when(properties.getName()).thenReturn("test-agent");
        AgentProperties.ModelProperties model = new AgentProperties.ModelProperties();
        model.setModelName("test/model");
        when(properties.getModel()).thenReturn(model);
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(properties)).build();
    }

    @Test
    void healthReturnsUpWithAgentName() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.name").value("test-agent"));
    }

    @Test
    void healthReflectsConfiguredName() {
        when(properties.getName()).thenReturn("custom-java-agent");
        var response = new HealthController(properties).health();
        assertThat(response).containsEntry("status", "UP").containsEntry("name", "custom-java-agent");
    }

    @Test
    void hermesHealthReturnsRootApiShape() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.platform").value("hermes-agent"))
            .andExpect(jsonPath("$.name").doesNotExist());

        mockMvc.perform(get("/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    void profilePrefixedHermesHealthMirrorsApiServerAlias() throws Exception {
        mockMvc.perform(get("/p/work/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.platform").value("hermes-agent"));

        mockMvc.perform(get("/p/work/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.platform").value("hermes-agent"));
    }

    @Test
    void dashboardHealthReturnsLightweightDesktopProbeShape() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.version").exists())
            .andExpect(jsonPath("$.auth_required").value(false))
            .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    void hermesDetailedHealthReturnsReadinessPayload() throws Exception {
        mockMvc.perform(get("/health/detailed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.isOneOf("ok", "degraded")))
            .andExpect(jsonPath("$.readiness.status").value(org.hamcrest.Matchers.isOneOf("ok", "degraded")))
            .andExpect(jsonPath("$.platform").value("hermes-agent"))
            .andExpect(jsonPath("$.name").doesNotExist())
            .andExpect(jsonPath("$.readiness.checks.state_db.status").value("ok"))
            .andExpect(jsonPath("$.readiness.checks.session_store.status").value("ok"))
            .andExpect(jsonPath("$.readiness.checks.config.status").value("ok"))
            .andExpect(jsonPath("$.readiness.checks.model.status").value("ok"))
            .andExpect(jsonPath("$.readiness.checks.disk.status").value(org.hamcrest.Matchers.isOneOf("ok", "degraded")))
            .andExpect(jsonPath("$.readiness.checks.gateway.state").value("running"))
            .andExpect(jsonPath("$.readiness.checks.background_queues.active_api_runs").value(0))
            .andExpect(jsonPath("$.readiness.checks.background_queues.process_completions").value(0))
            .andExpect(jsonPath("$.readiness.checks.background_queues.active_delegations").value(0))
            .andExpect(jsonPath("$.gateway_busy").value(false))
            .andExpect(jsonPath("$.gateway_drainable").value(true))
            .andExpect(jsonPath("$.pid").isNumber());
    }

    @Test
    void profilePrefixedDetailedHealthMirrorsApiServerAlias() throws Exception {
        mockMvc.perform(get("/p/work/health/detailed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.isOneOf("ok", "degraded")))
            .andExpect(jsonPath("$.platform").value("hermes-agent"));
    }

    @Test
    void hermesDetailedHealthReportsActiveApiRuns() {
        OpenAiRunService runService = mock(OpenAiRunService.class);
        when(runService.activeRunCount()).thenReturn(3);
        HealthController controller = new HealthController(properties);
        controller.setOpenAiRunService(runService);

        Map<String, Object> response = controller.hermesHealthDetailed();

        @SuppressWarnings("unchecked")
        Map<String, Object> readiness = (Map<String, Object>) response.get("readiness");
        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) readiness.get("checks");
        @SuppressWarnings("unchecked")
        Map<String, Object> backgroundQueues = (Map<String, Object>) checks.get("background_queues");
        assertThat(backgroundQueues).containsEntry("active_api_runs", 3);
        assertThat(response).containsEntry("active_agents", 3);
        assertThat(response).containsEntry("gateway_busy", true);
    }

    @Test
    void hermesDetailedHealthReportsAdmittedSessionRuns() {
        AgentProperties realProperties = new AgentProperties();
        realProperties.getApi().setMaxConcurrentRuns(1);
        ApiRunAdmissionService admissionService = new ApiRunAdmissionService(realProperties);
        HealthController controller = new HealthController(properties);
        controller.setApiRunAdmissionService(admissionService);

        try (ApiRunAdmissionService.Reservation ignored = admissionService.tryAcquire().orElseThrow()) {
            Map<String, Object> response = controller.hermesHealthDetailed();

            @SuppressWarnings("unchecked")
            Map<String, Object> readiness = (Map<String, Object>) response.get("readiness");
            @SuppressWarnings("unchecked")
            Map<String, Object> checks = (Map<String, Object>) readiness.get("checks");
            @SuppressWarnings("unchecked")
            Map<String, Object> backgroundQueues = (Map<String, Object>) checks.get("background_queues");
            assertThat(backgroundQueues).containsEntry("active_api_runs", 1);
            assertThat(response).containsEntry("active_agents", 1);
            assertThat(response).containsEntry("gateway_busy", true);
        }
    }

    @Test
    void hermesDetailedHealthDegradesWhenModelIsMissing() {
        AgentProperties.ModelProperties model = new AgentProperties.ModelProperties();
        model.setModelName("");
        when(properties.getModel()).thenReturn(model);

        Map<String, Object> response = new HealthController(properties).hermesHealthDetailed();

        @SuppressWarnings("unchecked")
        Map<String, Object> readiness = (Map<String, Object>) response.get("readiness");
        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) readiness.get("checks");
        @SuppressWarnings("unchecked")
        Map<String, Object> modelCheck = (Map<String, Object>) checks.get("model");
        assertThat(response).containsEntry("status", "degraded");
        assertThat(readiness).containsEntry("status", "degraded");
        assertThat(modelCheck).containsEntry("status", "degraded");
    }
}
