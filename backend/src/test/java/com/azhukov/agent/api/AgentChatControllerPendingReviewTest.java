package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.MemoryNudgeManager;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.metrics.AgentMetrics;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.BackgroundJobRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import com.azhukov.agent.service.tts.TtsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1-style branch coverage for the pending-review surface endpoint
 * ({@code GET /agent/session/{id}/review/pending}).
 *
 * Covers all contract branches of the pending-release semantics
 * (Hermes parity: background_review_callback pending-release):
 * disabled via config, no manager bean, summary present, blank summary,
 * plus the invalid-session-id guard. Complements AgentChatControllerT1Test
 * without modifying it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentChatControllerPendingReviewTest {

    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private MockMvc mockMvc;
    private AgentChatController controller;

    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private AgentStreamingService streamingService;
    @Mock private MemoryProvider memoryProvider;
    @Mock private SkillManager skillManager;
    @Mock private TtsService ttsService;
    @Mock private TranscriptionService transcriptionService;
    @Mock private ObjectProvider<com.azhukov.agent.core.memory.BackgroundReviewService> backgroundReviewServiceProvider;
    @Mock private BackgroundJobRepository backgroundJobRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ToolRegistry toolRegistry;
    @Mock private AgentMetrics agentMetrics;

    private final AgentProperties properties = new AgentProperties();
    private final MessageMapper messageMapper =
        org.mapstruct.factory.Mappers.getMapper(MessageMapper.class);

    @Mock private ObjectProvider<MemoryNudgeManager> memoryNudgeManagerProvider;
    @Mock private MemoryNudgeManager memoryNudgeManager;

    @BeforeEach
    void setUp() {
        when(skillManager.listSkillNames()).thenReturn(List.of("a"));
        controller = new AgentChatController(
            agentRuntimeService, streamingService, memoryProvider, skillManager,
            ttsService, transcriptionService,
            new com.azhukov.agent.core.agent.SteerBuffer(),
            new com.azhukov.agent.core.agent.InterruptToken(),
            backgroundReviewServiceProvider,
            backgroundJobRepository,
            sessionRepository, messageRepository, messageMapper,
            new ApprovalQueue(),
            properties,
            agentMetrics,
            toolRegistry
        );
        inject(controller, memoryNudgeManagerProvider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static void inject(AgentChatController target, ObjectProvider<MemoryNudgeManager> provider) {
        try {
            var f = AgentChatController.class.getDeclaredField("memoryNudgeManagerProviderField");
            f.setAccessible(true);
            f.set(target, provider);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("field injection failed", e);
        }
    }

    @Test
    void disabledBackgroundReviewReportsPendingFalseWithReason() throws Exception {
        properties.getMemory().getBackgroundReview().setEnabled(false);
        mockMvc.perform(get("/api/v1/agent/session/{id}/review/pending", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pending").value(false))
            .andExpect(jsonPath("$.reason").value("background review disabled"));
    }

    @Test
    void missingManagerBeanReportsPendingFalse() throws Exception {
        var f = AgentChatController.class.getDeclaredField("memoryNudgeManagerProviderField");
        f.setAccessible(true);
        f.set(controller, null);
        mockMvc.perform(get("/api/v1/agent/session/{id}/review/pending", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pending").value(false));
    }

    @Test
    void pendingSummaryIsReleasedOnce() throws Exception {
        when(memoryNudgeManagerProvider.getObject()).thenReturn(memoryNudgeManager);
        when(memoryNudgeManager.getReviewSummaryForSurface(SESSION_ID))
            .thenReturn("Self-improvement review: memory updated");
        mockMvc.perform(get("/api/v1/agent/session/{id}/review/pending", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pending").value(true))
            .andExpect(jsonPath("$.summary").value("Self-improvement review: memory updated"));
    }

    @Test
    void blankSummaryMeansNothingPending() throws Exception {
        when(memoryNudgeManagerProvider.getObject()).thenReturn(memoryNudgeManager);
        when(memoryNudgeManager.getReviewSummaryForSurface(SESSION_ID)).thenReturn("   ");
        mockMvc.perform(get("/api/v1/agent/session/{id}/review/pending", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pending").value(false));
    }

    @Test
    void invalidSessionIdFailsClosedAsNotPending() throws Exception {
        when(memoryNudgeManagerProvider.getObject()).thenReturn(memoryNudgeManager);
        mockMvc.perform(get("/api/v1/agent/session/{id}/review/pending", "not-a-uuid"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pending").value(false))
            .andExpect(jsonPath("$.error").value("invalid session id"));
    }
}
