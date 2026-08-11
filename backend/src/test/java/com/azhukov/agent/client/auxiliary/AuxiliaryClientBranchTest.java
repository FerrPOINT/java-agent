package com.azhukov.agent.client.auxiliary;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link AuxiliaryClient}.
 * Covers null backends, all fallback paths, vision task overrides, and async completion.
 */
class AuxiliaryClientBranchTest {

    // ── null backends constructor ──

    @Test
    void nullBackends_defaultsToEmptyList() {
        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(null, props);
        assertThat(client.isAvailable(AuxiliaryClient.TaskType.VISION)).isFalse();
    }

    @Test
    void nullProperties_doesNotThrowOnIsAvailable() {
        AuxiliaryBackend backend = mock(AuxiliaryBackend.class);
        when(backend.supportedTasks()).thenReturn(Set.of(AuxiliaryClient.TaskType.VISION));
        AuxiliaryClient client = new AuxiliaryClient(List.of(backend), null);
        assertThat(client.isAvailable(AuxiliaryClient.TaskType.VISION)).isTrue();
    }

    // ── all backends fail ──

    @Test
    void complete_allBackendsFail_returnsEmpty() {
        AuxiliaryBackend b1 = mock(AuxiliaryBackend.class);
        when(b1.name()).thenReturn("b1");
        when(b1.supportedTasks()).thenReturn(EnumSet.allOf(AuxiliaryClient.TaskType.class));
        when(b1.complete(any(), any())).thenThrow(new RuntimeException("fail1"));

        AuxiliaryBackend b2 = mock(AuxiliaryBackend.class);
        when(b2.name()).thenReturn("b2");
        when(b2.supportedTasks()).thenReturn(EnumSet.allOf(AuxiliaryClient.TaskType.class));
        when(b2.complete(any(), any())).thenThrow(new RuntimeException("fail2"));

        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(b1, b2), props);

        ChatResponse result = client.complete(AuxiliaryClient.TaskType.COMPRESSION,
            List.of(Message.user("test")), List.of());
        assertThat(result.content()).isEmpty();
    }

    // ── fallback skips unsupported task ──

    @Test
    void complete_primaryFails_fallbackSkipsUnsupportedTask() {
        AuxiliaryBackend primary = mock(AuxiliaryBackend.class);
        when(primary.name()).thenReturn("primary");
        when(primary.supportedTasks()).thenReturn(EnumSet.allOf(AuxiliaryClient.TaskType.class));
        when(primary.complete(any(), any())).thenThrow(new RuntimeException("fail"));

        AuxiliaryBackend fallback = mock(AuxiliaryBackend.class);
        when(fallback.name()).thenReturn("fallback");
        // Fallback only supports VISION, not TITLE
        when(fallback.supportedTasks()).thenReturn(Set.of(AuxiliaryClient.TaskType.VISION));

        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(primary, fallback), props);

        // Request TITLE — fallback doesn't support it, so returns empty
        ChatResponse result = client.complete(AuxiliaryClient.TaskType.TITLE,
            List.of(Message.user("test")), List.of());
        assertThat(result.content()).isEmpty();
        // Fallback.complete should never be called since it doesn't support TITLE
        verify(fallback, never()).complete(any(), any());
    }

    // ── resolveBackend with vision task model override ──

    @Test
    void complete_visionTaskWithModelOverride_prefersMatchingBackend() {
        AgentProperties props = new AgentProperties();
        props.getVision().setModelName("gpt-4-vision");
        props.getVision().setProvider("openai-compatible");

        AuxiliaryBackend matching = mock(AuxiliaryBackend.class);
        when(matching.name()).thenReturn("matching");
        when(matching.provider()).thenReturn("openai-compatible");
        when(matching.model()).thenReturn("gpt-4-vision");
        when(matching.supportedTasks()).thenReturn(Set.of(AuxiliaryClient.TaskType.VISION));
        when(matching.complete(any(), any())).thenReturn(ChatResponse.text("vision result"));

        AuxiliaryBackend nonMatching = mock(AuxiliaryBackend.class);
        when(nonMatching.name()).thenReturn("non-matching");
        when(nonMatching.provider()).thenReturn("anthropic");
        when(nonMatching.model()).thenReturn("claude-3");
        when(nonMatching.supportedTasks()).thenReturn(Set.of(AuxiliaryClient.TaskType.VISION));

        AuxiliaryClient client = new AuxiliaryClient(List.of(nonMatching, matching), props);

        ChatResponse result = client.complete(AuxiliaryClient.TaskType.VISION,
            List.of(Message.user("image")), List.of());
        assertThat(result.content()).isEqualTo("vision result");
        verify(matching).complete(any(), any());
        verify(nonMatching, never()).complete(any(), any());
    }

    @Test
    void complete_visionTaskWithProviderMismatch_fallsToFirstAvailable() {
        AgentProperties props = new AgentProperties();
        props.getVision().setProvider("anthropic");
        props.getVision().setModelName("claude-3");

        AuxiliaryBackend backend = mock(AuxiliaryBackend.class);
        when(backend.name()).thenReturn("backend");
        when(backend.provider()).thenReturn("openai-compatible");
        when(backend.model()).thenReturn("gpt-4");
        when(backend.supportedTasks()).thenReturn(Set.of(AuxiliaryClient.TaskType.VISION));
        when(backend.complete(any(), any())).thenReturn(ChatResponse.text("fallback result"));

        AuxiliaryClient client = new AuxiliaryClient(List.of(backend), props);

        ChatResponse result = client.complete(AuxiliaryClient.TaskType.VISION,
            List.of(Message.user("test")), List.of());
        assertThat(result.content()).isEqualTo("fallback result");
    }

    // ── async completion ──

    @Test
    void completeAsync_returnsResult() throws ExecutionException, InterruptedException {
        AuxiliaryBackend backend = mock(AuxiliaryBackend.class);
        when(backend.supportedTasks()).thenReturn(EnumSet.allOf(AuxiliaryClient.TaskType.class));
        when(backend.complete(any(), any())).thenReturn(ChatResponse.text("async result"));

        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(backend), props);

        var future = client.completeAsync(AuxiliaryClient.TaskType.TITLE,
            List.of(Message.user("test")), List.of());
        assertThat(future.get().content()).isEqualTo("async result");
    }

    // ── completeText with empty result ──

    @Test
    void completeText_noBackend_returnsEmpty() {
        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(), props);
        String result = client.completeText(AuxiliaryClient.TaskType.TITLE, "sys", "usr");
        assertThat(result).isEmpty();
    }

    // ── backendMatches: blank provider and model ──

    @Test
    void complete_visionTaskBlankProvider_matchesAnyBackend() {
        AgentProperties props = new AgentProperties();
        // Set blank model but null provider — should not match override path
        props.getVision().setModelName(null);
        props.getVision().setProvider(null);

        AuxiliaryBackend backend = mock(AuxiliaryBackend.class);
        when(backend.name()).thenReturn("backend");
        when(backend.supportedTasks()).thenReturn(Set.of(AuxiliaryClient.TaskType.VISION));
        when(backend.complete(any(), any())).thenReturn(ChatResponse.text("ok"));

        AuxiliaryClient client = new AuxiliaryClient(List.of(backend), props);

        ChatResponse result = client.complete(AuxiliaryClient.TaskType.VISION,
            List.of(Message.user("test")), List.of());
        assertThat(result.content()).isEqualTo("ok");
    }

    // ── isAvailable for each task type ──

    @Test
    void isAvailable_checksSpecificTaskTypes() {
        AuxiliaryBackend backend = mock(AuxiliaryBackend.class);
        when(backend.supportedTasks()).thenReturn(Set.of(
            AuxiliaryClient.TaskType.COMPRESSION,
            AuxiliaryClient.TaskType.TITLE
        ));

        AgentProperties props = new AgentProperties();
        AuxiliaryClient client = new AuxiliaryClient(List.of(backend), props);

        assertThat(client.isAvailable(AuxiliaryClient.TaskType.COMPRESSION)).isTrue();
        assertThat(client.isAvailable(AuxiliaryClient.TaskType.TITLE)).isTrue();
        assertThat(client.isAvailable(AuxiliaryClient.TaskType.VISION)).isFalse();
        assertThat(client.isAvailable(AuxiliaryClient.TaskType.REVIEW)).isFalse();
    }
}