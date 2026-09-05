package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CapabilitiesControllerTest {

    private MockMvc mockMvc;
    private AgentProperties properties;
    private AgentProperties.ApiProperties apiProps;
    private RuntimeConfigService runtimeConfigService;

    @BeforeEach
    void setUp() {
        properties = mock(AgentProperties.class);
        AgentProperties.ModelProperties modelProps = new AgentProperties.ModelProperties();
        modelProps.setModelName("test-model");
        when(properties.getModel()).thenReturn(modelProps);

        AgentProperties.SecurityProperties secProps = new AgentProperties.SecurityProperties();
        when(properties.getSecurity()).thenReturn(secProps);

        AgentProperties.CronProperties cronProps = new AgentProperties.CronProperties();
        when(properties.getCron()).thenReturn(cronProps);

        apiProps = new AgentProperties.ApiProperties();
        when(properties.getApi()).thenReturn(apiProps);

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getModelOverride()).thenReturn(null);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "file", "terminal"));
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.listSkillNames()).thenReturn(List.of("skill1", "skill2"));

        mockMvc = MockMvcBuilders.standaloneSetup(new CapabilitiesController(
            properties, toolRegistry, skillManager, runtimeConfigService)).build();
    }

    @Test
    void capabilitiesReturnsCorrectStructure() throws Exception {
        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("java-agent.api_server.capabilities"))
            .andExpect(jsonPath("$.platform").value("java-agent"))
            .andExpect(jsonPath("$.model").value("java-agent"))
            .andExpect(jsonPath("$.auth.type").value("bearer"))
            .andExpect(jsonPath("$.auth.required").value(false))
            .andExpect(jsonPath("$.runtime.description").value(org.hamcrest.Matchers.containsString("API-server host")))
            .andExpect(jsonPath("$.features.chat_completions").value(true))
            .andExpect(jsonPath("$.features.session_chat").value(true))
            .andExpect(jsonPath("$.features.model_options").value(true))
            .andExpect(jsonPath("$.features.session_model_lock").value(true))
            .andExpect(jsonPath("$.features.responses_api").value(true))
            .andExpect(jsonPath("$.features.responses_streaming").value(true))
            .andExpect(jsonPath("$.features.run_submission").value(true))
            .andExpect(jsonPath("$.features.run_status").value(true))
            .andExpect(jsonPath("$.features.run_events_sse").value(true))
            .andExpect(jsonPath("$.features.run_stop").value(true))
            .andExpect(jsonPath("$.features.run_steer").value(true))
            .andExpect(jsonPath("$.features.run_approval_response").value(true))
            .andExpect(jsonPath("$.features.tool_progress_events").value(true))
            .andExpect(jsonPath("$.features.approval_events").value(true))
            .andExpect(jsonPath("$.features.jobs_admin").value(false))
            .andExpect(jsonPath("$.features.memory_write_api").value(false))
            .andExpect(jsonPath("$.features.audio_api").value(false))
            .andExpect(jsonPath("$.features.session_continuity_header").value("X-Hermes-Session-Id"))
            .andExpect(jsonPath("$.features.session_key_header").value("X-Hermes-Session-Key"))
            .andExpect(jsonPath("$.features.cors").value(false))
            .andExpect(jsonPath("$.features.browser_extension_control.enabled").value(false))
            .andExpect(jsonPath("$.features.browser_extension_control.protocol_version").value(1))
            .andExpect(jsonPath("$.features.browser_extension_control.capabilities").isArray())
            .andExpect(jsonPath("$.features.browser_extension_control.capabilities.length()").value(11))
            .andExpect(jsonPath("$.features.browser_extension_control.capabilities[0]").value("browser_back"))
            .andExpect(jsonPath("$.features.browser_extension_control.capabilities[10]").value("controller.noop"))
            .andExpect(jsonPath("$.features.browser_extension_control.artifact_capabilities[0]").value("browser_artifact_download"))
            .andExpect(jsonPath("$.features.browser_extension_control.artifact_capabilities[1]").value("browser_artifact_upload"))
            .andExpect(jsonPath("$.features.browser_extension_control.developer_capabilities[0]").value("browser_cdp"))
            .andExpect(jsonPath("$.features.browser_extension_control.developer_capabilities[1]").value("browser_evaluate"))
            .andExpect(jsonPath("$.features.browser_extension_control.developer_mode").value(false))
            .andExpect(jsonPath("$.features.browser_extension_control.artifact_transport.upload.path").value("/v1/artifacts/upload"))
            .andExpect(jsonPath("$.features.browser_extension_control.artifact_transport.download.path").value("/v1/artifacts/download/{artifact_id}"))
            .andExpect(jsonPath("$.features.browser_extension_control.artifact_transport.max_bytes").value(10485760))
            .andExpect(jsonPath("$.features.browser_extension_control.artifact_transport.ttl_seconds").value(300.0))
            .andExpect(jsonPath("$.features.browser_extension_control.artifact_transport.allowed_mime_types[0]").value("application/json"))
            .andExpect(jsonPath("$.features.browser_extension_control.artifact_transport.allowed_mime_types[6]").value("text/plain"))
            .andExpect(jsonPath("$.features.browser_extension_control.real_browser_actions").value(true))
            .andExpect(jsonPath("$.features.browser_extension_control.transports.local_vps").value("websocket-subprotocol-ticket"))
            .andExpect(jsonPath("$.features.browser_extension_control.transports.cloud").value("authenticated-gateway-rpc"))
            .andExpect(jsonPath("$.endpoints.health.path").value("/health"))
            .andExpect(jsonPath("$.endpoints.health_detailed.path").value("/health/detailed"))
            .andExpect(jsonPath("$.endpoints.models.path").value("/v1/models"))
            .andExpect(jsonPath("$.endpoints.model_options.path").value("/api/model/options"))
            .andExpect(jsonPath("$.endpoints.chat_completions.path").value("/v1/chat/completions"))
            .andExpect(jsonPath("$.endpoints.responses.path").value("/v1/responses"))
            .andExpect(jsonPath("$.endpoints.runs.path").value("/v1/runs"))
            .andExpect(jsonPath("$.endpoints.run_status.path").value("/v1/runs/{run_id}"))
            .andExpect(jsonPath("$.endpoints.run_events.path").value("/v1/runs/{run_id}/events"))
            .andExpect(jsonPath("$.endpoints.run_approval.path").value("/v1/runs/{run_id}/approval"))
            .andExpect(jsonPath("$.endpoints.run_steer.path").value("/v1/runs/{run_id}/steer"))
            .andExpect(jsonPath("$.endpoints.run_stop.path").value("/v1/runs/{run_id}/stop"))
            .andExpect(jsonPath("$.endpoints.sessions.path").value("/api/sessions"))
            .andExpect(jsonPath("$.endpoints.session_fork.path").value("/api/sessions/{session_id}/fork"))
            .andExpect(jsonPath("$.endpoints.session_model_lock.path").value("/api/sessions/{session_id}/model"))
            .andExpect(jsonPath("$.endpoints.browser_control_register.path").value("/v1/browser-control/register"))
            .andExpect(jsonPath("$.endpoints.browser_control_ws.path").value("/v1/browser-control/ws"))
            .andExpect(jsonPath("$.endpoints.artifact_upload.path").value("/v1/artifacts/upload"))
            .andExpect(jsonPath("$.endpoints.artifact_download.path").value("/v1/artifacts/download/{artifact_id}"))
            .andExpect(jsonPath("$.endpoints.skills.path").value("/v1/skills"))
            .andExpect(jsonPath("$.endpoints.audio_transcribe").doesNotExist())
            .andExpect(jsonPath("$.endpoints.audio_speak").doesNotExist())
            .andExpect(jsonPath("$.endpoints.audio_voice_config").doesNotExist())
            .andExpect(jsonPath("$.endpoints.audio_elevenlabs_voices").doesNotExist())
            .andExpect(jsonPath("$.toolsets").isArray())
            .andExpect(jsonPath("$.skills_count").value(2));
    }

    @Test
    void profilePrefixedCapabilitiesRouteMirrorsHermesMultiplexAlias() throws Exception {
        mockMvc.perform(get("/p/work/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("java-agent.api_server.capabilities"))
            .andExpect(jsonPath("$.endpoints.models.path").value("/v1/models"))
            .andExpect(jsonPath("$.endpoints.responses.path").value("/v1/responses"))
            .andExpect(jsonPath("$.endpoints.runs.path").value("/v1/runs"));
    }

    @Test
    void authRequiredWhenApiKeySet() throws Exception {
        AgentProperties.SecurityProperties secProps = new AgentProperties.SecurityProperties();
        secProps.setApiKey("secret-key");
        when(properties.getSecurity()).thenReturn(secProps);

        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(jsonPath("$.auth.required").value(true));
    }

    @Test
    void corsAdvertisedOnlyWhenAllowlistConfigured() throws Exception {
        apiProps.setCorsOrigins(List.of("https://app.example"));

        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.features.cors").value(true));
    }

    @Test
    void jobsAdminStaysFalseEvenWhenCronIsEnabledLikeHermes() throws Exception {
        AgentProperties.CronProperties cronProps = new AgentProperties.CronProperties();
        cronProps.setEnabled(true);
        when(properties.getCron()).thenReturn(cronProps);

        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.features.jobs_admin").value(false));
    }

    @Test
    void capabilitiesAdvertisesHermesModelWhenApiModelNameIsBlank() throws Exception {
        apiProps.setModelName(" ");
        when(runtimeConfigService.getModelOverride()).thenReturn("override-model");

        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("java-agent"));
    }

    @Test
    void directCallReturnsMap() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolsets()).thenReturn(Set.of("core"));
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.listSkillNames()).thenReturn(List.of());

        var controller = new CapabilitiesController(
            properties, toolRegistry, skillManager, runtimeConfigService);
        var result = controller.capabilities();
        assertThat(result).containsEntry("platform", "java-agent");
        assertThat(result).containsKey("features");
        assertThat(result).containsKey("endpoints");
        @SuppressWarnings("unchecked")
        var endpoints = (Map<String, Object>) result.get("endpoints");
        assertThat(endpoints)
            .doesNotContainKeys(
                "model_info",
                "model_auxiliary",
                "model_recommended_default",
                "chat",
                "chat_stream",
                "jobs",
                "job_create",
                "job_update",
                "tools",
                "memory",
                "checkpoints",
                "mcp_servers");
    }
}
