package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.NoOpSkillManager;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.WriteOrigin;
import com.azhukov.agent.tools.memory.MemoryTool;
import com.azhukov.agent.tools.memory.SkillManageTool;
import com.azhukov.agent.tools.memory.SkillViewTool;
import com.azhukov.agent.tools.memory.SkillsListTool;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class BackgroundReviewServiceTest {

    private ModelClient modelClient;
    private MemoryProvider memoryProvider;
    private WriteApprovalGate writeApprovalGate;
    private MemoryTool memoryTool;
    private SkillManageTool skillManageTool;
    private SkillsListTool skillsListTool;
    private SkillViewTool skillViewTool;
    private AgentProperties properties;
    private AgentProperties.MemoryProperties memProps;
    private AgentProperties.BackgroundReviewProperties reviewProps;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        memoryProvider = mock(MemoryProvider.class);
        writeApprovalGate = mock(WriteApprovalGate.class);
        memoryTool = mock(MemoryTool.class);
        skillManageTool = mock(SkillManageTool.class);
        skillsListTool = mock(SkillsListTool.class);
        skillViewTool = mock(SkillViewTool.class);
        properties = mock(AgentProperties.class);
        memProps = mock(AgentProperties.MemoryProperties.class);
        reviewProps = mock(AgentProperties.BackgroundReviewProperties.class);

        when(properties.getMemory()).thenReturn(memProps);
        when(memProps.getBackgroundReview()).thenReturn(reviewProps);
        when(reviewProps.isEnabled()).thenReturn(true);
        when(reviewProps.getDelayMs()).thenReturn(0);
        // M9: Stub maxReviewTurns (default 8)
        when(reviewProps.getMaxReviewTurns()).thenReturn(8);
    }

    @AfterEach
    void clearWriteContext() {
        WriteContext.clear();
    }

    private BackgroundReviewService createService() {
        return new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate,
            memoryTool, skillManageTool, skillsListTool, skillViewTool, properties);
    }

    @Test
    void reviewTurn_disabled_doesNothing() {
        when(reviewProps.isEnabled()).thenReturn(false);
        var svc = createService();
        svc.reviewTurn(UUID.randomUUID(), List.of(Message.user("hello")));
        verifyNoInteractions(modelClient);
        svc.shutdown();
    }

    @Test
    void reviewTurn_emptyMessages_doesNothing() {
        var svc = createService();
        svc.reviewTurn(UUID.randomUUID(), List.of());
        verifyNoInteractions(modelClient);
        svc.shutdown();
    }

    @Test
    void reviewTurn_nullMessages_doesNothing() {
        var svc = createService();
        svc.reviewTurn(UUID.randomUUID(), null);
        verifyNoInteractions(modelClient);
        svc.shutdown();
    }

    @Test
    void reviewTurn_noToolCalls_doesNotUpdateMemory() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("", List.of()));
        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("hello"), Message.assistant("hi", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isFalse()
        );
        svc.shutdown();
    }

    @Test
    void reviewTurn_withMemoryToolCall_updatesFlagAndExecutesToolWithCorrectArgs() {
        String toolArguments = "{\"action\":\"add\",\"content\":\"User prefers Java\"}";
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "memory", toolArguments)
        ));
        when(modelClient.complete(any(), any())).thenReturn(response, new ChatResponse("Nothing to save.", List.of()));
        when(memoryTool.execute(eq(toolArguments), isNull(), any()))
            .thenReturn(ToolResult.ok("Added to memory store."));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("I like Java"), Message.assistant("Great!", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isTrue()
        );

        verify(memoryTool).execute(eq(toolArguments), isNull(), any());
        svc.shutdown();
    }

    @Test
    void reviewTurn_toolCallFails_doesNotUpdateFlag() {
        String toolArguments = "{\"action\":\"add\",\"content\":\"failed fact\"}";
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "memory", toolArguments)
        ));
        when(modelClient.complete(any(), any())).thenReturn(response, new ChatResponse("Done", List.of()));
        when(memoryTool.execute(any(), any(), any())).thenReturn(ToolResult.fail("store error"));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("test"), Message.assistant("ok", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isFalse()
        );
        svc.shutdown();
    }

    @Test
    void reviewTurn_modelCallThrows_doesNotUpdateFlag() {
        when(modelClient.complete(any(), any())).thenThrow(new RuntimeException("model unavailable"));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("test"), Message.assistant("ok", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isFalse()
        );
        verifyNoInteractions(memoryTool);
        svc.shutdown();
    }

    @Test
    void reviewTurn_nonWhitelistedToolCall_doesNotUpdateFlag() {
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "web_search", "{\"query\":\"test\"}")
        ));
        when(modelClient.complete(any(), any())).thenReturn(response, new ChatResponse("Done", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("test"), Message.assistant("ok", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isFalse()
        );
        // Memory tool should not be called for non-whitelisted tool calls
        verifyNoInteractions(memoryTool);
        verifyNoInteractions(skillManageTool);
        svc.shutdown();
    }

    @Test
    void clearFlag_removesFlag() {
        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.clearFlag(sessionId);
        assertThat(svc.wasMemoryUpdated(sessionId)).isFalse();
        svc.shutdown();
    }

    @Test
    void reviewTurn_skillManageToolCall_executesAndTracksAction() {
        String toolArguments = "{\"action\":\"create\",\"name\":\"test-skill\",\"content\":\"# Test\"}";
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "skill_manage", toolArguments)
        ));
        when(modelClient.complete(any(), any())).thenReturn(response, new ChatResponse("Done", List.of()));
        when(skillManageTool.execute(eq(toolArguments), any(), any()))
            .thenReturn(ToolResult.ok("Skill test-skill created."));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("create a skill"), Message.assistant("ok", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(svc.wasMemoryUpdated(sessionId)).isFalse();
            // The review actions list should have the skill action
            assertThat(svc.getReviewActions(sessionId)).isNotEmpty();
        });
        verify(skillManageTool).execute(eq(toolArguments), any(), any());
        svc.shutdown();
    }

    // ── S3: Review Summary tests ──────────────────────────────────────

    @Test
    void reviewTurn_memoryUpdated_producesReviewSummary() {
        String toolArguments = "{\"action\":\"add\",\"content\":\"User prefers Java\"}";
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "memory", toolArguments)
        ));
        when(modelClient.complete(any(), any())).thenReturn(response, new ChatResponse("Done", List.of()));
        when(memoryTool.execute(any(), any(), any()))
            .thenReturn(ToolResult.ok("Added to memory store."));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("I like Java"), Message.assistant("Great!", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(svc.hasReviewSummary(sessionId)).isTrue();
            ReviewSummary summary = svc.getReviewSummary(sessionId);
            assertThat(summary.memoryUpdated()).isTrue();
            assertThat(summary.hasActions()).isTrue();
            assertThat(summary.formattedSummary()).isNotBlank();
        });
        svc.shutdown();
    }

    @Test
    void reviewTurn_noActions_hasNoReviewSummary() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Nothing to save.", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("hello"), Message.assistant("hi", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.hasReviewSummary(sessionId)).isFalse()
        );
        svc.shutdown();
    }

    @Test
    void getReviewSummary_emptySession_returnsEmptySummary() {
        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        ReviewSummary summary = svc.getReviewSummary(sessionId);
        assertThat(summary.hasActions()).isFalse();
        svc.shutdown();
    }

    @Test
    void clearFlag_alsoClearsReviewSummary() {
        // First, trigger a review that produces a summary
        String toolArguments = "{\"action\":\"add\",\"content\":\"User prefers Java\"}";
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "memory", toolArguments)
        ));
        when(modelClient.complete(any(), any())).thenReturn(response, new ChatResponse("Done", List.of()));
        when(memoryTool.execute(any(), any(), any()))
            .thenReturn(ToolResult.ok("Added to memory store."));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("I like Java"), Message.assistant("Great!", 0)));

        // Wait for the review to complete and produce a summary
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.hasReviewSummary(sessionId)).isTrue()
        );

        // Now clearFlag should remove the summary
        svc.clearFlag(sessionId);
        assertThat(svc.hasReviewSummary(sessionId)).isFalse();
        assertThat(svc.wasMemoryUpdated(sessionId)).isFalse();
        svc.shutdown();
    }

    // ── S3: Tool Schema tests ─────────────────────────────────────────

    @Test
    void reviewTurn_passesToolsWithFullSchemas_notEmptyMaps() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Done", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("test"), Message.assistant("ok", 0)));

        // Wait for async review to complete
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(modelClient).complete(any(), any())
        );

        // Verify modelClient.complete was called with non-empty tool schemas
        org.mockito.ArgumentCaptor<List<ToolDefinition>> toolsCaptor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(any(), toolsCaptor.capture());
        List<ToolDefinition> tools = toolsCaptor.getValue();
        assertThat(tools).isNotEmpty();
        for (ToolDefinition tool : tools) {
            assertThat(tool.parameters())
                .as("Tool '%s' should have non-empty parameters", tool.name())
                .isNotEmpty();
        }
        svc.shutdown();
    }

    // ── S7: Per-turn prompt selection tests ───────────────────────────

    @Test
    void reviewTurn_memoryOnlyConversation_usesMemoryPrompt() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Done", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        // User mentions personal preferences (memory signal only)
        List<Message> messages = List.of(
            Message.user("I prefer dark themes and I like Java"),
            Message.assistant("Great!", 0)
        );
        svc.reviewTurn(sessionId, messages);

        // Wait for async review to complete
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(modelClient).complete(any(), any())
        );

        org.mockito.ArgumentCaptor<List<Message>> msgsCaptor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(msgsCaptor.capture(), any());
        List<Message> reviewMessages = msgsCaptor.getValue();
        // The last user message should be the memory review prompt
        Message lastUserMsg = null;
        for (Message m : reviewMessages) {
            if (m.role() == com.azhukov.agent.core.model.Role.USER) {
                lastUserMsg = m;
            }
        }
        assertThat(lastUserMsg).isNotNull();
        assertThat(lastUserMsg.content()).isEqualTo(ReviewPrompts.MEMORY_REVIEW_PROMPT);
        svc.shutdown();
    }

    @Test
    void reviewTurn_skillOnlyConversation_usesSkillPrompt() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Done", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        // User mentions skill-related content
        List<Message> messages = List.of(
            Message.user("stop doing that, this is too verbose"),
            Message.assistant("Sorry about that.", 0)
        );
        svc.reviewTurn(sessionId, messages);

        // Wait for async review to complete
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(modelClient).complete(any(), any())
        );

        org.mockito.ArgumentCaptor<List<Message>> msgsCaptor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(msgsCaptor.capture(), any());
        List<Message> reviewMessages = msgsCaptor.getValue();
        Message lastUserMsg = null;
        for (Message m : reviewMessages) {
            if (m.role() == com.azhukov.agent.core.model.Role.USER) {
                lastUserMsg = m;
            }
        }
        assertThat(lastUserMsg).isNotNull();
        assertThat(lastUserMsg.content()).isEqualTo(ReviewPrompts.SKILL_REVIEW_PROMPT);
        svc.shutdown();
    }

    @Test
    void reviewTurn_bothSignalsConversation_usesCombinedPrompt() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Done", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(
            Message.user("I prefer concise answers"),
            Message.assistant("This debugging workflow approach should work.", 0)
        );
        svc.reviewTurn(sessionId, messages);

        // Wait for async review to complete
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(modelClient).complete(any(), any())
        );

        org.mockito.ArgumentCaptor<List<Message>> msgsCaptor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(msgsCaptor.capture(), any());
        List<Message> reviewMessages = msgsCaptor.getValue();
        Message lastUserMsg = null;
        for (Message m : reviewMessages) {
            if (m.role() == com.azhukov.agent.core.model.Role.USER) {
                lastUserMsg = m;
            }
        }
        assertThat(lastUserMsg).isNotNull();
        assertThat(lastUserMsg.content()).isEqualTo(ReviewPrompts.COMBINED_REVIEW_PROMPT);
        svc.shutdown();
    }

    // ── S7: WriteContext provenance tests ─────────────────────────────

    @Test
    void reviewTurn_setsWriteContextDuringReview() {
        String toolArguments = "{\"action\":\"add\",\"content\":\"test fact\"}";
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "memory", toolArguments)
        ));
        when(modelClient.complete(any(), any())).thenReturn(response, new ChatResponse("Done", List.of()));

        // Capture the WriteContext at the time memoryTool.execute is called
        when(memoryTool.execute(any(), any(), any())).thenAnswer(invocation -> {
            // Verify WriteContext is set during the review
            assertThat(WriteContext.effectiveOrigin()).isEqualTo(WriteOrigin.BACKGROUND_REVIEW);
            assertThat(WriteContext.effectiveExecutionContext()).isEqualTo("background_review");
            return ToolResult.ok("Added to memory store.");
        });

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("test"), Message.assistant("ok", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isTrue()
        );
        // After review completes, WriteContext should be cleared
        assertThat(WriteContext.current()).isNull();
        svc.shutdown();
    }

    // ── S7: Stale-action filtering tests ──────────────────────────────

    @Test
    void reviewTurn_staleToolResultsFromPriorConversation_areFilteredOut() {
        // Prior conversation has a tool result with call_1
        Message priorToolResult = Message.toolResult("call_1", "Added to memory store.", 0);
        List<Message> messages = List.of(
            Message.user("I like Java"),
            Message.assistant("Great!", 0),
            priorToolResult
        );

        // Review agent tries to call memory with the same call_id
        String toolArguments = "{\"action\":\"add\",\"content\":\"User prefers Java\"}";
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "memory", toolArguments)
        ));
        when(modelClient.complete(any(), any())).thenReturn(response, new ChatResponse("Done", List.of()));
        when(memoryTool.execute(any(), any(), any()))
            .thenReturn(ToolResult.ok("Added to memory store."));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, messages);

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            // The tool was executed (tool dispatch happens regardless of stale filtering),
            // but the stale action should NOT be tracked in the summary.
            // The tool result "Added to memory store." matches the prior conversation's
            // tool result content, so StaleActionFilter.isStale() returns true and
            // the action is filtered out — getReviewActions should be empty.
            List<String> actions = svc.getReviewActions(sessionId);
            assertThat(actions).noneMatch(a -> a.contains("User prefers Java"));
        });
        svc.shutdown();
    }

    // ── Nudge-gated review tests ─────────────────────────────────────

    @Test
    void reviewTurn_memoryOnlyFlag_usesMemoryPrompt() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Done", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(
            Message.user("some conversation without skill signals"),
            Message.assistant("ok", 0)
        );
        svc.reviewTurn(sessionId, messages, true, false);

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(modelClient).complete(any(), any())
        );

        org.mockito.ArgumentCaptor<List<Message>> msgsCaptor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(msgsCaptor.capture(), any());
        List<Message> reviewMessages = msgsCaptor.getValue();
        Message lastUserMsg = null;
        for (Message m : reviewMessages) {
            if (m.role() == com.azhukov.agent.core.model.Role.USER) {
                lastUserMsg = m;
            }
        }
        assertThat(lastUserMsg).isNotNull();
        // When only memory nudge fired, the prompt should be MEMORY_REVIEW_PROMPT
        // (not combined, not skill)
        assertThat(lastUserMsg.content()).isEqualTo(ReviewPrompts.MEMORY_REVIEW_PROMPT);
        svc.shutdown();
    }

    @Test
    void reviewTurn_skillOnlyFlag_usesSkillPrompt() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Done", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(
            Message.user("some conversation"),
            Message.assistant("ok", 0)
        );
        svc.reviewTurn(sessionId, messages, false, true);

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(modelClient).complete(any(), any())
        );

        org.mockito.ArgumentCaptor<List<Message>> msgsCaptor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(msgsCaptor.capture(), any());
        List<Message> reviewMessages = msgsCaptor.getValue();
        Message lastUserMsg = null;
        for (Message m : reviewMessages) {
            if (m.role() == com.azhukov.agent.core.model.Role.USER) {
                lastUserMsg = m;
            }
        }
        assertThat(lastUserMsg).isNotNull();
        // When only skill nudge fired, the prompt should be SKILL_REVIEW_PROMPT
        assertThat(lastUserMsg.content()).isEqualTo(ReviewPrompts.SKILL_REVIEW_PROMPT);
        svc.shutdown();
    }

    @Test
    void reviewTurn_bothFlagsFalse_doesNothing() {
        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("test")), false, false);
        verifyNoInteractions(modelClient);
        svc.shutdown();
    }

    @Test
    void reviewTurn_bothFlagsTrue_usesCombinedPromptFromSelector() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Done", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        // Include both memory and skill signals so ReviewPromptSelector picks COMBINED
        List<Message> messages = List.of(
            Message.user("I prefer concise answers"),
            Message.assistant("This debugging workflow should work.", 0)
        );
        svc.reviewTurn(sessionId, messages, true, true);

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(modelClient).complete(any(), any())
        );

        org.mockito.ArgumentCaptor<List<Message>> msgsCaptor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(msgsCaptor.capture(), any());
        List<Message> reviewMessages = msgsCaptor.getValue();
        Message lastUserMsg = null;
        for (Message m : reviewMessages) {
            if (m.role() == com.azhukov.agent.core.model.Role.USER) {
                lastUserMsg = m;
            }
        }
        assertThat(lastUserMsg).isNotNull();
        // When both nudges fired, the prompt is selected by ReviewPromptSelector
        // The conversation includes both memory ("I prefer concise answers") and
        // skill ("debugging workflow") signals, so ReviewPromptSelector should
        // select the COMBINED prompt — not memory-only or skill-only.
        assertThat(lastUserMsg.content()).isEqualTo(ReviewPrompts.COMBINED_REVIEW_PROMPT);
        svc.shutdown();
    }

    @Test
    void reviewTurn_memoryOnlyFlagWithSkillSignals_stillUsesMemoryPrompt() {
        // When only memory nudge fired, even if conversation has skill signals,
        // the prompt should be MEMORY_REVIEW_PROMPT (nudge overrides content-based selection)
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Done", List.of()));

        var svc = createService();
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(
            Message.user("stop doing that, this is too verbose"),
            Message.assistant("Sorry, I'll fix the workflow.", 0)
        );
        svc.reviewTurn(sessionId, messages, true, false);

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            verify(modelClient).complete(any(), any())
        );

        org.mockito.ArgumentCaptor<List<Message>> msgsCaptor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient).complete(msgsCaptor.capture(), any());
        List<Message> reviewMessages = msgsCaptor.getValue();
        Message lastUserMsg = null;
        for (Message m : reviewMessages) {
            if (m.role() == com.azhukov.agent.core.model.Role.USER) {
                lastUserMsg = m;
            }
        }
        assertThat(lastUserMsg).isNotNull();
        assertThat(lastUserMsg.content()).isEqualTo(ReviewPrompts.MEMORY_REVIEW_PROMPT);
        svc.shutdown();
    }
}