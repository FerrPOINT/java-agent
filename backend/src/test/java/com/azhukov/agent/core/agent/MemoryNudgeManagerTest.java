package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.ReviewSummary;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryNudgeManagerTest {

    @Mock private ContextEngine contextEngine;
    @Mock private BackgroundReviewService backgroundReviewService;

    private AgentProperties properties;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        sessionId = UUID.randomUUID();
    }

    private MemoryNudgeManager createManager() {
        return new MemoryNudgeManager(properties, contextEngine, backgroundReviewService);
    }

    private Session createSession() {
        return new Session(sessionId, "user-1", "title", "openai-compatible", "model", null, Map.of(), null);
    }

    // ─── initMemoryCounter ───

    @Test
    void initMemoryCounter_positiveNudgeInterval_initializesCounter() {
        properties.getMemory().setNudgeInterval(10);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 5);
        // Counter is initialized — increment and verify via triggerNudgedBackgroundReview
        // The initial value should be 5 % 10 = 5
        manager.incrementMemoryTurns(sessionId);
        // Now counter = 6, which is < 10, so no review should fire
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void initMemoryCounter_zeroNudgeInterval_doesNothing() {
        properties.getMemory().setNudgeInterval(0);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 5);
        // No counter created — increment should be no-op
        manager.incrementMemoryTurns(sessionId);
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void initMemoryCounter_negativeNudgeInterval_doesNothing() {
        properties.getMemory().setNudgeInterval(-1);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 5);
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void initMemoryCounter_hydratesFromPriorTurns_modulo() {
        properties.getMemory().setNudgeInterval(10);
        MemoryNudgeManager manager = createManager();
        // 25 prior turns → initial = 25 % 10 = 5
        manager.initMemoryCounter(sessionId, 25);
        // Increment 5 times → counter reaches 10 → should trigger review
        for (int i = 0; i < 5; i++) {
            manager.incrementMemoryTurns(sessionId);
        }
        when(contextEngine.prepareContext(any(), anyList())).thenReturn(List.of(Message.user("ctx")));
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), anyString(), eq(true), anyBoolean());
    }

    // ─── incrementMemoryTurns ───

    @Test
    void incrementMemoryTurns_withoutInit_doesNothing() {
        MemoryNudgeManager manager = createManager();
        manager.incrementMemoryTurns(sessionId);
        // No counter exists, so no exception and no review
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), anyString(), anyBoolean(), anyBoolean());
    }

    // ─── incrementSkillIters / resetSkillIters ───

    @Test
    void incrementSkillIters_incrementsCounter() {
        properties.getSkills().setCreationNudgeInterval(3);
        MemoryNudgeManager manager = createManager();
        manager.incrementSkillIters(sessionId);
        manager.incrementSkillIters(sessionId);
        manager.incrementSkillIters(sessionId);
        // 3 iters >= 3 → should trigger review
        when(contextEngine.prepareContext(any(), anyList())).thenReturn(List.of(Message.user("ctx")));
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), anyString(), anyBoolean(), eq(true));
    }

    @Test
    void resetSkillIters_resetsToZero() {
        properties.getSkills().setCreationNudgeInterval(3);
        MemoryNudgeManager manager = createManager();
        manager.incrementSkillIters(sessionId);
        manager.incrementSkillIters(sessionId);
        manager.resetSkillIters(sessionId);
        // After reset, 2 more increments = 2, which is < 3 → no review
        manager.incrementSkillIters(sessionId);
        manager.incrementSkillIters(sessionId);
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void resetSkillIters_withoutPriorIncrement_doesNothing() {
        MemoryNudgeManager manager = createManager();
        manager.resetSkillIters(sessionId); // should not throw
    }

    // ─── resetMemoryTurns ───

    @Test
    void resetMemoryTurns_resetsToZero() {
        properties.getMemory().setNudgeInterval(3);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);
        manager.incrementMemoryTurns(sessionId);
        manager.resetMemoryTurns(sessionId);
        // After reset, 2 more increments = 2, which is < 3 → no review
        manager.incrementMemoryTurns(sessionId);
        manager.incrementMemoryTurns(sessionId);
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void resetMemoryTurns_withoutInit_createsCounter() {
        properties.getMemory().setNudgeInterval(2);
        MemoryNudgeManager manager = createManager();
        manager.resetMemoryTurns(sessionId);
        // Counter now exists at 0; increment twice → should trigger
        manager.incrementMemoryTurns(sessionId);
        manager.incrementMemoryTurns(sessionId);
        when(contextEngine.prepareContext(any(), anyList())).thenReturn(List.of(Message.user("ctx")));
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), anyString(), eq(true), anyBoolean());
    }

    // ─── triggerNudgedBackgroundReview ───

    @Test
    void triggerNudgedBackgroundReview_interrupted_doesNothing() {
        MemoryNudgeManager manager = createManager();
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), true);
        verifyNoInteractions(backgroundReviewService);
    }

    @Test
    void triggerNudgedBackgroundReview_subagentSession_doesNothing() {
        MemoryNudgeManager manager = createManager();
        Session session = createSession().withMetadata("delegation_depth", "2");
        manager.triggerNudgedBackgroundReview(session, List.of(Message.user("test")), false);
        verifyNoInteractions(backgroundReviewService);
    }

    @Test
    void triggerNudgedBackgroundReview_subagentDepthZero_proceedsNormally() {
        properties.getMemory().setNudgeInterval(1);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);
        Session session = createSession().withMetadata("delegation_depth", "0");
        when(contextEngine.prepareContext(any(), anyList())).thenReturn(List.of(Message.user("ctx")));
        manager.triggerNudgedBackgroundReview(session, List.of(Message.user("test")), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), anyString(), eq(true), anyBoolean());
    }

    @Test
    void triggerNudgedBackgroundReview_subagentDepthInvalid_proceedsNormally() {
        properties.getMemory().setNudgeInterval(1);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);
        Session session = createSession().withMetadata("delegation_depth", "not-a-number");
        when(contextEngine.prepareContext(any(), anyList())).thenReturn(List.of(Message.user("ctx")));
        manager.triggerNudgedBackgroundReview(session, List.of(Message.user("test")), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), anyString(), eq(true), anyBoolean());
    }

    @Test
    void triggerNudgedBackgroundReview_skipBackgroundReviewTrue_doesNothing() {
        MemoryNudgeManager manager = createManager();
        Session session = createSession().withMetadata("skip_background_review", "true");
        manager.triggerNudgedBackgroundReview(session, List.of(Message.user("test")), false);
        verifyNoInteractions(backgroundReviewService);
    }

    @Test
    void triggerNudgedBackgroundReview_skipBackgroundReviewFalse_proceeds() {
        properties.getMemory().setNudgeInterval(1);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);
        Session session = createSession().withMetadata("skip_background_review", "false");
        when(contextEngine.prepareContext(any(), anyList())).thenReturn(List.of(Message.user("ctx")));
        manager.triggerNudgedBackgroundReview(session, List.of(Message.user("test")), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), anyString(), eq(true), anyBoolean());
    }

    @Test
    void triggerNudgedBackgroundReview_contextEngineThrows_fallsBackToTurnMessages() {
        properties.getMemory().setNudgeInterval(1);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);
        when(contextEngine.prepareContext(any(), anyList())).thenThrow(new RuntimeException("ctx error"));
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), eq(List.of(Message.user("test"))), anyString(), eq(true), anyBoolean());
    }

    @Test
    void triggerNudgedBackgroundReview_reviewThrows_doesNotPropagate() {
        properties.getMemory().setNudgeInterval(1);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);
        when(contextEngine.prepareContext(any(), anyList())).thenReturn(List.of(Message.user("ctx")));
        doThrow(new RuntimeException("review failed")).when(backgroundReviewService)
            .clearFlag(sessionId);
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        // Should not throw — exception is caught internally
    }

    @Test
    void triggerNudgedBackgroundReview_bothMemoryAndSkillTriggered() {
        properties.getMemory().setNudgeInterval(1);
        properties.getSkills().setCreationNudgeInterval(1);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);
        manager.incrementSkillIters(sessionId);
        when(contextEngine.prepareContext(any(), anyList())).thenReturn(List.of(Message.user("ctx")));
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), anyString(), eq(true), eq(true));
    }

    @Test
    void triggerNudgedBackgroundReview_neitherThresholdMet_doesNothing() {
        properties.getMemory().setNudgeInterval(100);
        properties.getSkills().setCreationNudgeInterval(100);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);
        manager.incrementSkillIters(sessionId);
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verifyNoInteractions(backgroundReviewService);
    }

    // ─── getReviewSummaryForSurface ───

    @Test
    void getReviewSummaryForSurface_noSummary_returnsNull() {
        MemoryNudgeManager manager = createManager();
        when(backgroundReviewService.hasReviewSummary(sessionId)).thenReturn(false);
        assertThat(manager.getReviewSummaryForSurface(sessionId)).isNull();
    }

    @Test
    void getReviewSummaryForSurface_emptySummary_returnsNull() {
        MemoryNudgeManager manager = createManager();
        when(backgroundReviewService.hasReviewSummary(sessionId)).thenReturn(true);
        when(backgroundReviewService.getReviewSummary(sessionId)).thenReturn(ReviewSummary.empty());
        assertThat(manager.getReviewSummaryForSurface(sessionId)).isNull();
    }

    @Test
    void getReviewSummaryForSurface_summaryWithActions_returnsFormatted() {
        MemoryNudgeManager manager = createManager();
        ReviewSummary summary = ReviewSummary.of(true, List.of("Memory: added fact"));
        when(backgroundReviewService.hasReviewSummary(sessionId)).thenReturn(true);
        when(backgroundReviewService.getReviewSummary(sessionId)).thenReturn(summary);
        String result = manager.getReviewSummaryForSurface(sessionId);
        assertThat(result).isNotBlank();
        assertThat(result).contains("Memory: added fact");
        verify(backgroundReviewService).clearFlag(sessionId);
    }

    @Test
    void getReviewSummaryForSurface_clearsFlagAfterRetrieving() {
        MemoryNudgeManager manager = createManager();
        ReviewSummary summary = ReviewSummary.of(false, List.of("Skill: patched skill"));
        when(backgroundReviewService.hasReviewSummary(sessionId)).thenReturn(true);
        when(backgroundReviewService.getReviewSummary(sessionId)).thenReturn(summary);
        manager.getReviewSummaryForSurface(sessionId);
        verify(backgroundReviewService).clearFlag(sessionId);
    }

    // ─── clearSession ───

    @Test
    void clearSession_removesCounters() {
        properties.getMemory().setNudgeInterval(10);
        properties.getSkills().setCreationNudgeInterval(10);
        MemoryNudgeManager manager = createManager();
        manager.initMemoryCounter(sessionId, 5);
        manager.incrementSkillIters(sessionId);
        manager.clearSession(sessionId);
        // After clearing, increment should be no-op
        manager.incrementMemoryTurns(sessionId);
        manager.triggerNudgedBackgroundReview(createSession(), List.of(Message.user("test")), false);
        verifyNoInteractions(backgroundReviewService);
    }

    @Test
    void clearSession_neverInitialized_doesNotThrow() {
        MemoryNudgeManager manager = createManager();
        manager.clearSession(sessionId); // should not throw
    }
}