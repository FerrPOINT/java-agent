package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.DefaultAgentRuntime;
import com.azhukov.agent.core.agent.MemoryNudgeManager;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Seam-audit regressions (2026-08-21, Hermes parity):
 *
 * 1. resetSession must drop runtime per-session state (nudge counters, runtime
 *    cleanup, frozen memory prefix) — Hermes starts a FRESH session after /reset;
 *    java previously only deleted the message rows, so stale counters leaked into
 *    the "fresh" session (review could fire on turn 1) and the frozen memory
 *    prefix survived.
 * 2. runBackground(..., skipBackgroundReview=true) (cron) must stamp
 *    skip_background_review metadata — Hermes cron/scheduler.py:5459.
 */
class ResetAndBackgroundReviewGateTest {

    @Mock private AgentRuntime agentRuntime;
    @Mock private MessageRepository messageRepository;
    @Mock private MemoryNudgeManager memoryNudgeManager;
    @Mock private DefaultPromptBuilder promptBuilder;
    @Mock private TransactionTemplate transactionTemplate;

    private AgentRuntimeService service;
    private com.azhukov.agent.core.agent.AgentSessionResolver sessionResolver;

    @BeforeEach
    void setUp() {
        openMocks(this);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class))).thenReturn(List.of());
        sessionResolver = org.mockito.Mockito.mock(com.azhukov.agent.core.agent.AgentSessionResolver.class);
        org.mockito.Mockito.lenient().when(sessionResolver.createSession(any(String.class), any(String.class), any(String.class)))
            .thenAnswer(inv -> new Session(UUID.randomUUID(), inv.getArgument(0), "t",
                inv.getArgument(1), inv.getArgument(2), null, java.util.Map.of(), null));
        service = new AgentRuntimeService(
            agentRuntime,                      // 1 agentRuntime
            null,                              // 2 sessionRepository
            messageRepository,                 // 3 messageRepository
            null,                              // 4 sessionTitleService
            null,                              // 5 memoryProvider
            null,                              // 6 memoryRepository
            null,                              // 7 writeApprovalGate
            null,                              // 8 conversationCompressor
            null,                              // 9 usageTracker
            null,                              // 10 turnUsageCollector
            null,                              // 11 properties
            null,                              // 12 sessionMapper
            null,                              // 13 messageMapper
            null,                              // 14 domainDtoMapper
            null,                              // 15 skillBundleService
            null,                              // 16 skillManager
            null,                              // 17 mcpLifecycleManager
            org.mockito.Mockito.mock(com.fasterxml.jackson.databind.ObjectMapper.class), // 18 objectMapper
            null,                              // 19 runtimeConfigService
            transactionTemplate,               // 20 transactionTemplate
            sessionResolver,                     // 21 sessionResolver
            null,                              // 22 cliStateApplier
            null,                              // 23 sessionCompressionHelper
            null,                              // 24 contextCompressor
            null,                              // 25 modelMetadataService
            null,                              // 26 midTurnPersistenceCallback
            memoryNudgeManager,                // 27 memoryNudgeManager
            promptBuilder);                    // 28 promptBuilder
    }

    @Test
    @DisplayName("/reset clears nudge counters + runtime state + frozen memory prefix")
    void resetClearsRuntimeState() {
        UUID id = UUID.randomUUID();
        service.resetSession(id);
        verify(memoryNudgeManager).clearSession(id);
        verify(promptBuilder).invalidateMemoryPrefix(String.valueOf(id));
        verify(messageRepository).deleteAll(any());
    }

    @Test
    @DisplayName("runBackground with skipBackgroundReview stamps metadata on the session handed to the runtime")
    void backgroundSkipFlagStampsMetadata() {
        when(transactionTemplate.execute(any())).thenReturn(null);
        org.mockito.Mockito.when(agentRuntime.runTurn(any(Session.class), any(String.class)))
            .thenAnswer(inv -> {
                // The session passed to the runtime must carry the skip flag
                Session s = inv.getArgument(0);
                assertThat(s.getMetadata("skip_background_review")).isEqualTo("true");
                return new TurnResult(List.of(), true, null);
            });

        String sessionId = service.runBackground("cron prompt", null, true);
        assertThat(sessionId).isNotBlank();
        verify(agentRuntime).runTurn(any(Session.class), any(String.class));
        verify(memoryNudgeManager, never()).clearSession(any());
    }

    @Test
    @DisplayName("DefaultAgentRuntime gate: skip_background_review metadata suppresses review trigger")
    void runtimeGateSkipsBackgroundSessions() {
        // Direct check of the gate logic: a session with skip_background_review must
        // never reach reviewTrigger. We verify via MemoryNudgeManager (streaming path)
        com.azhukov.agent.core.memory.BackgroundReviewService review =
            mock(com.azhukov.agent.core.memory.BackgroundReviewService.class);
        AgentProperties props = new AgentProperties();
        MemoryNudgeManager manager = new MemoryNudgeManager(props, null, review);
        UUID sid = UUID.randomUUID();
        manager.initMemoryCounter(sid, 9);
        manager.incrementMemoryTurns(sid); // now at threshold (10)

        Session skip = new Session(sid, "user-1", "t", "p", "m", null,
            java.util.Map.of("skip_background_review", "true"), null);
        manager.triggerNudgedBackgroundReview(skip, List.of(), false);
        verify(review, never()).reviewTurn(any(), any(), any(), any(boolean.class), any(boolean.class));

        Session normal = new Session(sid, "user-1", "t", "p", "m", null, java.util.Map.of(), null);
        manager.triggerNudgedBackgroundReview(normal, List.of(), false);
        verify(review).reviewTurn(any(), any(), any(), any(boolean.class), any(boolean.class));
    }
}
