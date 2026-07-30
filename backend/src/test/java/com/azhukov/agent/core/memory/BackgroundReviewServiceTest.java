package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.memory.MemoryTool;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BackgroundReviewServiceTest {

    private ModelClient modelClient;
    private MemoryProvider memoryProvider;
    private WriteApprovalGate writeApprovalGate;
    private MemoryTool memoryTool;
    private AgentProperties properties;
    private AgentProperties.MemoryProperties memProps;
    private AgentProperties.BackgroundReviewProperties reviewProps;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        memoryProvider = mock(MemoryProvider.class);
        writeApprovalGate = mock(WriteApprovalGate.class);
        memoryTool = mock(MemoryTool.class);
        properties = mock(AgentProperties.class);
        memProps = mock(AgentProperties.MemoryProperties.class);
        reviewProps = mock(AgentProperties.BackgroundReviewProperties.class);

        when(properties.getMemory()).thenReturn(memProps);
        when(memProps.getBackgroundReview()).thenReturn(reviewProps);
        when(reviewProps.isEnabled()).thenReturn(true);
        when(reviewProps.getDelayMs()).thenReturn(0);
    }

    @Test
    void reviewTurn_disabled_doesNothing() {
        when(reviewProps.isEnabled()).thenReturn(false);
        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        svc.reviewTurn(UUID.randomUUID(), List.of(Message.user("hello")));
        verifyNoInteractions(modelClient);
        svc.shutdown();
    }

    @Test
    void reviewTurn_emptyMessages_doesNothing() {
        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        svc.reviewTurn(UUID.randomUUID(), List.of());
        verifyNoInteractions(modelClient);
        svc.shutdown();
    }

    @Test
    void reviewTurn_nullMessages_doesNothing() {
        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        svc.reviewTurn(UUID.randomUUID(), null);
        verifyNoInteractions(modelClient);
        svc.shutdown();
    }

    @Test
    void reviewTurn_noToolCalls_doesNotUpdateMemory() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("", List.of()));
        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("hello"), Message.assistant("hi", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isFalse()
        );
        verify(memoryProvider, never()).store(any(), any(), any());
        verify(memoryProvider, never()).store(any(), any(), any(), any());
        svc.shutdown();
    }

    @Test
    void reviewTurn_withMemoryToolCall_updatesFlagAndExecutesToolWithCorrectArgs() {
        String toolArguments = "{\"action\":\"add\",\"content\":\"User prefers Java\"}";
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "memory", toolArguments)
        ));
        when(modelClient.complete(any(), any())).thenReturn(response);
        when(memoryTool.execute(eq(toolArguments), any(), any()))
            .thenReturn(ToolResult.ok("Added to memory store."));

        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("I like Java"), Message.assistant("Great!", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isTrue()
        );

        // Verify the memory tool was called with the exact arguments from the tool call
        verify(memoryTool).execute(eq(toolArguments), isNull(), any());
        svc.shutdown();
    }

    @Test
    void reviewTurn_toolCallFails_doesNotUpdateFlag() {
        String toolArguments = "{\"action\":\"add\",\"content\":\"failed fact\"}";
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "memory", toolArguments)
        ));
        when(modelClient.complete(any(), any())).thenReturn(response);
        when(memoryTool.execute(any(), any(), any())).thenReturn(ToolResult.fail("store error"));

        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
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

        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("test"), Message.assistant("ok", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isFalse()
        );
        // Memory tool should not be called since model failed
        verifyNoInteractions(memoryTool);
        svc.shutdown();
    }

    @Test
    void reviewTurn_nonMemoryToolCall_doesNotUpdateFlag() {
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "web_search", "{\"query\":\"test\"}")
        ));
        when(modelClient.complete(any(), any())).thenReturn(response);

        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("test"), Message.assistant("ok", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(svc.wasMemoryUpdated(sessionId)).isFalse()
        );
        // Memory tool should not be called for non-memory tool calls
        verifyNoInteractions(memoryTool);
        svc.shutdown();
    }

    @Test
    void clearFlag_removesFlag() {
        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        UUID sessionId = UUID.randomUUID();
        svc.clearFlag(sessionId);
        assertThat(svc.wasMemoryUpdated(sessionId)).isFalse();
        svc.shutdown();
    }
}