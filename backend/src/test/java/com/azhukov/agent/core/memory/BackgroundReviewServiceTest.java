package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.memory.MemoryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackgroundReviewServiceTest {

    @Mock
    private ModelClient modelClient;
    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private WriteApprovalGate writeApprovalGate;
    @Mock
    private MemoryTool memoryTool;

    private AgentProperties properties;
    private BackgroundReviewService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getMemory().getBackgroundReview().setDelayMs(0); // no delay for tests
        // We need the @AgentTool annotation — use a mock-friendly approach
        service = new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool, properties);
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
    }

    // 1. Review does nothing when disabled
    @Test
    void reviewDoesNothingWhenDisabled() throws Exception {
        properties.getMemory().getBackgroundReview().setEnabled(false);
        UUID sessionId = UUID.randomUUID();
        service.reviewTurn(sessionId, List.of(Message.user("hello")));
        Thread.sleep(100);
        verifyNoInteractions(modelClient);
    }

    // 2. Review does nothing with empty messages
    @Test
    void reviewDoesNothingWithEmptyMessages() throws Exception {
        UUID sessionId = UUID.randomUUID();
        service.reviewTurn(sessionId, List.of());
        Thread.sleep(100);
        verifyNoInteractions(modelClient);
    }

    // 3. Memory updated flag starts false
    @Test
    void memoryUpdatedFlagStartsFalse() {
        UUID sessionId = UUID.randomUUID();
        assertThat(service.wasMemoryUpdated(sessionId)).isFalse();
    }

    // 4. Clear flag works
    @Test
    void clearFlagWorks() {
        UUID sessionId = UUID.randomUUID();
        service.clearFlag(sessionId);
        assertThat(service.wasMemoryUpdated(sessionId)).isFalse();
    }

    // 5. Review with tool calls sets memory updated
    @Test
    void reviewWithToolCallsSetsMemoryUpdated() throws Exception {
        UUID sessionId = UUID.randomUUID();

        // Mock model client to return a response with memory tool calls
        ToolCall memoryCall = new ToolCall("call-1", "memory", "{\"action\":\"add\",\"content\":\"test fact\"}");
        ChatResponse response = new ChatResponse("", List.of(memoryCall));
        when(modelClient.complete(any(), any())).thenReturn(response);

        // Mock memory tool to return success
        when(memoryTool.execute(any(), any(), any())).thenReturn(ToolResult.ok("Added to memory store."));

        service.reviewTurn(sessionId, List.of(Message.user("I prefer dark mode"), Message.assistant("Noted!", 1)));
        Thread.sleep(200);

        assertThat(service.wasMemoryUpdated(sessionId)).isTrue();
    }
}