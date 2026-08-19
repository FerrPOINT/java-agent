package com.azhukov.agent.core.agent;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.persistence.entity.SessionEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CliStateApplierTest {

    private final CliStateApplier applier = new CliStateApplier();

    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private SessionEntity newSessionEntity() {
        SessionEntity e = new SessionEntity();
        e.setId(SESSION_ID);
        e.setUserId("user-1");
        e.setModelProvider("openai-compatible");
        e.setModelName("test-model");
        e.setTitle("Test");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    @Test
    void applyCliState_nullSession_returnsOriginalRequest() {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);
        ChatRequest result = applier.applyCliState(request, null);
        assertThat(result).isSameAs(request);
    }

    @Test
    void applyCliState_noCliState_returnsOriginalMessage() {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);
        SessionEntity entity = newSessionEntity();
        ChatRequest result = applier.applyCliState(request, entity);
        assertThat(result.message()).isEqualTo("Hello");
    }

    @Test
    void applyCliState_withGoalPrependsGoalBlock() {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);
        SessionEntity entity = newSessionEntity();
        entity.setCliStateValue("goal", "fix all bugs");
        ChatRequest result = applier.applyCliState(request, entity);
        assertThat(result.message()).contains("[Standing Goal]");
        assertThat(result.message()).contains("fix all bugs");
        assertThat(result.message()).contains("Hello");
    }

    @Test
    void applyCliState_goalPausedSkipsGoalBlock() {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);
        SessionEntity entity = newSessionEntity();
        entity.setCliStateValue("goal", "fix all bugs");
        entity.setCliStateValue("goalPaused", "true");
        ChatRequest result = applier.applyCliState(request, entity);
        assertThat(result.message()).doesNotContain("[Standing Goal]");
        assertThat(result.message()).contains("Hello");
    }

    @Test
    void applyCliState_withSubgoalsPrependsSubgoalsBlock() {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);
        SessionEntity entity = newSessionEntity();
        entity.setCliStateValue("subgoals", "bug1\nbug2\nbug3");
        ChatRequest result = applier.applyCliState(request, entity);
        assertThat(result.message()).contains("[Subgoals]");
        assertThat(result.message()).contains("bug1\nbug2\nbug3");
    }

    @Test
    void applyCliState_withSubgoalPrependsGoalSubgoalBlock() {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);
        SessionEntity entity = newSessionEntity();
        entity.setSubgoal("Complete the migration");
        ChatRequest result = applier.applyCliState(request, entity);
        assertThat(result.message()).contains("[Goal/Subgoal]");
        assertThat(result.message()).contains("Complete the migration");
    }

    @Test
    void applyCliState_withQueuedPromptPrependsQueuedContext() {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);
        SessionEntity entity = newSessionEntity();
        entity.setCliStateValue("queuedPrompt", "Additional context info");
        ChatRequest result = applier.applyCliState(request, entity);
        assertThat(result.message()).contains("[Queued context]");
        assertThat(result.message()).contains("Additional context info");
    }

    @Test
    void applyCliState_allBlocksPresentInCorrectOrder() {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);
        SessionEntity entity = newSessionEntity();
        entity.setCliStateValue("goal", "my goal");
        entity.setCliStateValue("subgoals", "sub1\nsub2");
        entity.setSubgoal("current subgoal");
        entity.setCliStateValue("queuedPrompt", "queued info");
        ChatRequest result = applier.applyCliState(request, entity);

        String msg = result.message();
        int goalIdx = msg.indexOf("[Standing Goal]");
        int subgoalsIdx = msg.indexOf("[Subgoals]");
        int subgoalIdx = msg.indexOf("[Goal/Subgoal]");
        int queuedIdx = msg.indexOf("[Queued context]");
        int helloIdx = msg.indexOf("Hello");

        assertThat(goalIdx).isLessThan(subgoalsIdx);
        assertThat(subgoalsIdx).isLessThan(subgoalIdx);
        assertThat(subgoalIdx).isLessThan(queuedIdx);
        assertThat(queuedIdx).isLessThan(helloIdx);
    }

    @Test
    void applyCliState_requestReasoningEffortOverridesSession() {
        ChatRequest request = new ChatRequest(
            SESSION_ID, "Hello", null, 10_000L,
            "high", null, null, null,
            null, null, null, null, null, null, null, null, null, null, null);
        SessionEntity entity = newSessionEntity();
        entity.setCliStateValue("reasoningEffort", "low");
        ChatRequest result = applier.applyCliState(request, entity);
        assertThat(result.reasoningEffort()).isEqualTo("high");
    }

    @Test
    void applyCliState_sessionReasoningEffortUsedWhenRequestIsNull() {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);
        SessionEntity entity = newSessionEntity();
        entity.setCliStateValue("reasoningEffort", "medium");
        ChatRequest result = applier.applyCliState(request, entity);
        assertThat(result.reasoningEffort()).isEqualTo("medium");
    }

    @Test
    void applyCliState_queuedPromptConsumedFromRequest() {
        ChatRequest request = new ChatRequest(
            SESSION_ID, "Hello", null, 10_000L,
            null, null, null, null,
            null, null, "request-queued", null, null, null, null, null, null, null, null);
        SessionEntity entity = newSessionEntity();
        ChatRequest result = applier.applyCliState(request, entity);
        assertThat(result.queuedPrompt()).isNull(); // consumed
        assertThat(result.message()).contains("[Queued context]");
        assertThat(result.message()).contains("request-queued");
    }
}