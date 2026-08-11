package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.state.DefaultAgentConstants;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultPromptBuilderTest {

    @Test
    void usesConfiguredSystemPrompt() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setDefaultSystemPrompt("Hello ${agent.name}");
        properties.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        DefaultAgentConstants constants = new DefaultAgentConstants();

        DefaultPromptBuilder builder = new DefaultPromptBuilder(properties, registry, constants);
        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"));

        assertThat(msg.role()).isEqualTo(Role.SYSTEM);
        // Three-tier prompt uses properties.getName() in the stable tier
        assertThat(msg.content()).contains("Agent");
    }

    @Test
    void fallsBackToDefaultPromptWhenBlank() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setDefaultSystemPrompt("  ");
        properties.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of("files", "web"));

        DefaultPromptBuilder builder = new DefaultPromptBuilder(properties, registry);
        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"));

        assertThat(msg.content()).contains("Agent");
        assertThat(msg.content()).contains("files");
    }

    // ── Memory injection tests ──────────────────────────────────────────

    @Test
    void memoryPrefixIsPrependedToSystemPromptWhenMemoryProviderReturnsMemories() {
        AgentProperties properties = new AgentProperties();
        properties.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());
        DefaultAgentConstants constants = new DefaultAgentConstants();
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        when(memoryProvider.recall(eq("user-1"), anyString(), anyInt()))
            .thenReturn(List.of("User prefers dark mode", "User works with Python"));

        DefaultPromptBuilder builder = new DefaultPromptBuilder(
            properties, registry, constants, null, null, memoryProvider);
        Session session = new Session(UUID.randomUUID(), "user-1", null, "noop", "model", null, java.util.Map.of(), null);

        Message msg = builder.buildSystemMessage(session);

        assertThat(msg.role()).isEqualTo(Role.SYSTEM);
        // Memory prefix should be at the start of the system prompt
        assertThat(msg.content()).startsWith("## Memory (persistent facts)");
        assertThat(msg.content()).contains("User prefers dark mode");
        assertThat(msg.content()).contains("User works with Python");
        // The three-tier prompt content should still be present after the memory prefix
        assertThat(msg.content()).contains("You are Agent");
    }

    @Test
    void memoryPrefixIsEmptyWhenNoMemories() {
        AgentProperties properties = new AgentProperties();
        properties.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());
        DefaultAgentConstants constants = new DefaultAgentConstants();
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        when(memoryProvider.recall(anyString(), anyString(), anyInt()))
            .thenReturn(List.of());

        DefaultPromptBuilder builder = new DefaultPromptBuilder(
            properties, registry, constants, null, null, memoryProvider);
        Session session = new Session(UUID.randomUUID(), "user-1", null, "noop", "model", null, java.util.Map.of(), null);

        Message msg = builder.buildSystemMessage(session);

        assertThat(msg.content()).doesNotContain("## Memory (persistent facts)");
        assertThat(msg.content()).startsWith("You are Agent");
    }

    @Test
    void memoryPrefixIsEmptyWhenMemoryProviderIsNull() {
        AgentProperties properties = new AgentProperties();
        properties.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());
        DefaultAgentConstants constants = new DefaultAgentConstants();

        // 5-arg constructor — memoryProvider is null
        DefaultPromptBuilder builder = new DefaultPromptBuilder(
            properties, registry, constants, null, null);
        Session session = new Session(UUID.randomUUID(), "user-1", null, "noop", "model", null, java.util.Map.of(), null);

        Message msg = builder.buildSystemMessage(session);

        assertThat(msg.content()).doesNotContain("## Memory (persistent facts)");
        assertThat(msg.content()).startsWith("You are Agent");
    }

    @Test
    void memoryPrefixReturnsEmptyForNullSession() {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);

        DefaultPromptBuilder builder = new DefaultPromptBuilder(
            properties, registry, new DefaultAgentConstants(), null, null, memoryProvider);

        assertThat(builder.buildMemoryPrefix(null)).isEmpty();
        verify(memoryProvider, never()).recall(anyString(), anyString(), anyInt());
    }

    @Test
    void memoryPrefixReturnsEmptyWhenMemoryProviderThrows() {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        when(memoryProvider.recall(anyString(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("DB down"));

        DefaultPromptBuilder builder = new DefaultPromptBuilder(
            properties, registry, new DefaultAgentConstants(), null, null, memoryProvider);

        Session session = new Session(UUID.randomUUID(), "user-1", null, "noop", "model", null, java.util.Map.of(), null);
        assertThat(builder.buildMemoryPrefix(session)).isEmpty();
    }

    @Test
    void memoryPrefixRecallsWithCorrectUserIdAndLimit() {
        AgentProperties properties = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        when(memoryProvider.recall(eq("user-42"), anyString(), eq(20)))
            .thenReturn(List.of("Fact A"));

        DefaultPromptBuilder builder = new DefaultPromptBuilder(
            properties, registry, new DefaultAgentConstants(), null, null, memoryProvider);

        Session session = new Session(UUID.randomUUID(), "user-42", null, "noop", "model", null, java.util.Map.of(), null);
        String prefix = builder.buildMemoryPrefix(session);

        assertThat(prefix).contains("Fact A");
        verify(memoryProvider).recall(eq("user-42"), anyString(), eq(20));
    }
}