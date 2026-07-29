package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.memory.MemoryTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void reviewTurn_noToolCalls_doesNotUpdateMemory() throws Exception {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("", List.of()));
        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("hello"), Message.assistant("hi", 0)));
        Thread.sleep(200);
        assertThat(svc.wasMemoryUpdated(sessionId)).isFalse();
        svc.shutdown();
    }

    @Test
    void reviewTurn_withMemoryToolCall_updatesFlag() throws Exception {
        ChatResponse response = new ChatResponse("", List.of(
            new ToolCall("call_1", "memory", "{\"action\":\"add\",\"content\":\"User prefers Java\"}")
        ));
        when(modelClient.complete(any(), any())).thenReturn(response);
        when(memoryTool.execute(any(), any(), any())).thenReturn(ToolResult.ok("Added to memory store."));

        var svc = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
        UUID sessionId = UUID.randomUUID();
        svc.reviewTurn(sessionId, List.of(Message.user("I like Java"), Message.assistant("Great!", 0)));
        Thread.sleep(500);
        assertThat(svc.wasMemoryUpdated(sessionId)).isTrue();
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