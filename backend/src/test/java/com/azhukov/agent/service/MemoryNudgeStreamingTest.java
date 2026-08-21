package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.MemoryNudgeManager;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermes parity port (0.1.16): the streaming path must actually DRIVE the
 * memory/skill nudge counters, otherwise background self-improvement review
 * never fires for bot turns. Verifies the MemoryNudgeManager contract:
 * increment at turn start, review at threshold, no review below threshold,
 * subagent sessions skipped.
 */
@ExtendWith(MockitoExtension.class)
class MemoryNudgeStreamingTest {

    @Mock private AgentProperties properties;
    @Mock private ContextEngine contextEngine;
    @Mock private BackgroundReviewService backgroundReviewService;

    private MemoryNudgeManager manager;
    private UUID sessionId;

    private AgentProperties.MemoryProperties memoryCfg(int interval) {
        AgentProperties.MemoryProperties m = new AgentProperties.MemoryProperties();
        m.setNudgeInterval(interval);
        return m;
    }

    @BeforeEach
    void setUp() {
        manager = new MemoryNudgeManager(properties, contextEngine, backgroundReviewService);
        sessionId = UUID.randomUUID();
        lenient().when(contextEngine.prepareContext(any(), anyList())).thenReturn(java.util.List.of());
        lenient().when(properties.getSkills()).thenReturn(new AgentProperties.SkillsProperties());
    }

    @Test
    @DisplayName("counter reaches nudge-interval → background review fires once and resets")
    void thresholdReachedFiresReview() {
        when(properties.getMemory()).thenReturn(memoryCfg(3));

        manager.initMemoryCounter(sessionId, 0);
        // Three user turns — the third crosses the interval
        manager.incrementMemoryTurns(sessionId);
        manager.incrementMemoryTurns(sessionId);
        manager.incrementMemoryTurns(sessionId);

        var session = new com.azhukov.agent.core.model.Session(
            sessionId, "user-1", null, "noop", "noop", null, null, null);
        manager.triggerNudgedBackgroundReview(session, java.util.List.of(), false);

        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), eq("user-1"),
            eq(true), anyBoolean());
        // Counter reset — a fourth turn alone must NOT fire another review
        manager.incrementMemoryTurns(sessionId);
        manager.triggerNudgedBackgroundReview(session, java.util.List.of(), false);
        verify(backgroundReviewService, never()).reviewTurn(eq(sessionId), anyList(), eq("user-1"),
            eq(false), eq(true));
    }

    @Test
    @DisplayName("below threshold → no review")
    void belowThresholdNoReview() {
        when(properties.getMemory()).thenReturn(memoryCfg(10));

        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);

        var session = new com.azhukov.agent.core.model.Session(
            sessionId, "user-1", null, "noop", "noop", null, null, null);
        manager.triggerNudgedBackgroundReview(session, java.util.List.of(), false);

        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("hydration from prior history preserves interval across restarts (M8)")
    void hydrationFromHistory() {
        when(properties.getMemory()).thenReturn(memoryCfg(10));

        // 7 prior user turns → counter starts at 7; 3 more turns crosses 10
        manager.initMemoryCounter(sessionId, 7);
        manager.incrementMemoryTurns(sessionId);
        manager.incrementMemoryTurns(sessionId);

        var session = new com.azhukov.agent.core.model.Session(
            sessionId, "user-1", null, "noop", "noop", null, null, null);
        manager.triggerNudgedBackgroundReview(session, java.util.List.of(), false);
        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), any(), anyBoolean(), anyBoolean());

        manager.incrementMemoryTurns(sessionId);
        manager.triggerNudgedBackgroundReview(session, java.util.List.of(), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), eq("user-1"),
            eq(true), anyBoolean());
    }

    @Test
    @DisplayName("subagent sessions (delegation_depth>0) never trigger review")
    void subagentSkipped() {
        when(properties.getMemory()).thenReturn(memoryCfg(1));

        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);

        var session = new com.azhukov.agent.core.model.Session(
            sessionId, "user-1", null, "noop", "noop", null, null, null)
            .withMetadata("delegation_depth", "1");
        manager.triggerNudgedBackgroundReview(session, java.util.List.of(), false);

        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("interrupted turns never trigger review")
    void interruptedSkipped() {
        when(properties.getMemory()).thenReturn(memoryCfg(1));

        manager.initMemoryCounter(sessionId, 0);
        manager.incrementMemoryTurns(sessionId);

        var session = new com.azhukov.agent.core.model.Session(
            sessionId, "user-1", null, "noop", "noop", null, null, null);
        manager.triggerNudgedBackgroundReview(session, java.util.List.of(), true);

        verify(backgroundReviewService, never()).reviewTurn(any(), anyList(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("skill iters increment and reset on skill_manage use")
    void skillIterLifecycle() {
        AgentProperties.SkillsProperties skills = new AgentProperties.SkillsProperties();
        skills.setCreationNudgeInterval(2);
        when(properties.getSkills()).thenReturn(skills);
        when(properties.getMemory()).thenReturn(memoryCfg(0));

        manager.incrementSkillIters(sessionId);
        manager.incrementSkillIters(sessionId);

        var session = new com.azhukov.agent.core.model.Session(
            sessionId, "user-1", null, "noop", "noop", null, null, null);
        manager.triggerNudgedBackgroundReview(session, java.util.List.of(), false);
        verify(backgroundReviewService).reviewTurn(eq(sessionId), anyList(), eq("user-1"),
            anyBoolean(), eq(true));

        // reset via skill_manage path — one more iter alone stays below the
        // interval, so NO additional reviewTurn call happens at all
        manager.resetSkillIters(sessionId);
        manager.incrementSkillIters(sessionId);
        manager.triggerNudgedBackgroundReview(session, java.util.List.of(), false);
        verify(backgroundReviewService, times(1)).reviewTurn(any(), anyList(), any(), anyBoolean(), anyBoolean());
    }
}
