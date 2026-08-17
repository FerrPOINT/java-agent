package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.metrics.AgentMetrics;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the debug-report endpoint in {@link AgentChatController}.
 * Verifies that multipart/form-data upload returns a JSON response with a shareable link
 * and system properties.
 */
@ExtendWith(MockitoExtension.class)
class DebugReportControllerTest {

    private MockMvc mockMvc;

    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private AgentStreamingService streamingService;
    @Mock private MemoryProvider memoryProvider;
    @Mock private SkillManager skillManager;
    @Mock private TtsService ttsService;
    @Mock private TranscriptionService transcriptionService;
    @Mock private AgentProperties agentProperties;
    @Mock private AgentMetrics agentMetrics;

    @BeforeEach
    void setUp() {
        AgentChatController controller = new AgentChatController(
            agentRuntimeService, streamingService, memoryProvider, skillManager,
            ttsService, transcriptionService,
            new SteerBuffer(),
            new InterruptToken(),
            new com.azhukov.agent.core.security.ApprovalQueue(),
            agentProperties,
            agentMetrics
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void debugReportReturnsLinkAndSystemProperties() throws Exception {
        MockMultipartFile systemInfo = new MockMultipartFile(
            "systemInfo", "", MediaType.TEXT_PLAIN_VALUE, "OS: Linux, CPU: 8 cores".getBytes());
        MockMultipartFile logs = new MockMultipartFile(
            "logs", "", MediaType.TEXT_PLAIN_VALUE, "INFO: agent started".getBytes());

        mockMvc.perform(multipart("/api/v1/agent/debug-report")
                .file(systemInfo)
                .file(logs))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.link").exists())
            .andExpect(jsonPath("$.link").isString())
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.systemProperties['java.version']").exists())
            .andExpect(jsonPath("$.systemProperties['os.name']").exists())
            .andExpect(jsonPath("$.providedSystemInfo").value("OS: Linux, CPU: 8 cores"))
            .andExpect(jsonPath("$.logsIncluded").value(true))
            .andExpect(jsonPath("$.logsSize").value("INFO: agent started".length()))
            .andExpect(jsonPath("$.message").value("Debug report uploaded."));
    }

    @Test
    void debugReportWithoutOptionalParts() throws Exception {
        mockMvc.perform(multipart("/api/v1/agent/debug-report"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.link").exists())
            .andExpect(jsonPath("$.systemProperties['java.version']").exists())
            .andExpect(jsonPath("$.logsIncluded").value(false))
            .andExpect(jsonPath("$.message").value("Debug report uploaded."));
    }

    @Test
    void debugReportLinkContainsUuid() throws Exception {
        mockMvc.perform(multipart("/api/v1/agent/debug-report"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.link").isString())
            .andExpect(jsonPath("$.id").isString());
    }
}