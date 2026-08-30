package com.azhukov.agent.client.auxiliary;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class AuxiliaryClientTest {

    @Test
    void complete_usesFirstAvailableBackend() {
        AuxiliaryBackend backend = mock(AuxiliaryBackend.class);
        when(backend.supportedTasks()).thenReturn(EnumSet.allOf(AuxiliaryClient.TaskType.class));
        when(backend.complete(any(), any())).thenReturn(ChatResponse.text("result"));

        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(backend), props);

        ChatResponse result = client.complete(AuxiliaryClient.TaskType.COMPRESSION,
            List.of(
                Message.assistantToolCalls(List.of(new com.azhukov.agent.core.model.ToolCall("orphan", "read_file", "{}")), 0),
                Message.user("test")), List.of());
        assertThat(result.content()).isEqualTo("result");

        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(backend).complete(messages.capture(), any());
        assertThat(messages.getValue()).noneMatch(message ->
            message.toolCalls() != null && !message.toolCalls().isEmpty());
    }

    @Test
    void complete_fallsBackToNextBackend() {
        AuxiliaryBackend primary = mock(AuxiliaryBackend.class);
        when(primary.name()).thenReturn("primary");
        when(primary.supportedTasks()).thenReturn(EnumSet.allOf(AuxiliaryClient.TaskType.class));
        when(primary.complete(any(), any())).thenThrow(new RuntimeException("primary failed"));

        AuxiliaryBackend fallback = mock(AuxiliaryBackend.class);
        when(fallback.name()).thenReturn("fallback");
        when(fallback.supportedTasks()).thenReturn(EnumSet.allOf(AuxiliaryClient.TaskType.class));
        when(fallback.complete(any(), any())).thenReturn(ChatResponse.text("fallback result"));

        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(primary, fallback), props);

        ChatResponse result = client.complete(AuxiliaryClient.TaskType.TITLE,
            List.of(Message.user("test")), List.of());
        assertThat(result.content()).isEqualTo("fallback result");
    }

    @Test
    void complete_noBackend_returnsEmpty() {
        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(), props);
        ChatResponse result = client.complete(AuxiliaryClient.TaskType.VISION,
            List.of(Message.user("test")), List.of());
        assertThat(result.content()).isEmpty();
    }

    @Test
    void completeText_simpleTextCompletion() {
        AuxiliaryBackend backend = mock(AuxiliaryBackend.class);
        when(backend.supportedTasks()).thenReturn(EnumSet.allOf(AuxiliaryClient.TaskType.class));
        when(backend.complete(any(), any())).thenReturn(ChatResponse.text("title text"));

        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(backend), props);

        String result = client.completeText(AuxiliaryClient.TaskType.TITLE, "system prompt", "user prompt");
        assertThat(result).isEqualTo("title text");
    }

    @Test
    void isAvailable_trueWhenBackendSupportsTask() {
        AuxiliaryBackend backend = mock(AuxiliaryBackend.class);
        when(backend.supportedTasks()).thenReturn(Set.of(AuxiliaryClient.TaskType.VISION));

        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(backend), props);
        assertThat(client.isAvailable(AuxiliaryClient.TaskType.VISION)).isTrue();
        assertThat(client.isAvailable(AuxiliaryClient.TaskType.COMPRESSION)).isFalse();
    }

    @Test
    void isAvailable_falseWhenNoBackends() {
        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(), props);
        assertThat(client.isAvailable(AuxiliaryClient.TaskType.VISION)).isFalse();
    }
}