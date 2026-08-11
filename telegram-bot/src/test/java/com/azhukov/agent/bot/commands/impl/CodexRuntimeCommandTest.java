package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CodexRuntimeCommandTest {

    private BotProperties properties;
    private CodexRuntimeCommand cmd;
    private BotSessionEntity session;
    private BotSessionStore store;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        properties.setDefaultModel("kimi-k2.6");
        properties.getAvailableModels().addAll(List.of("kimi-k2.6", "gpt-4o", "claude-3.5"));
        store = mock(BotSessionStore.class);
        cmd = new CodexRuntimeCommand(properties, store);
        session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("codex_runtime");
        assertThat(cmd.description()).isEqualTo("Show or switch active model runtime");
    }

    @Test
    void showsCurrentModel() {
        String result = cmd.handle(textEvent("/codex_runtime", null), session);
        assertThat(result).contains("kimi-k2.6");
        assertThat(result).contains("Model:");
    }

    @Test
    void listModels() {
        String result = cmd.handle(textEvent("/codex_runtime", "list"), session);
        assertThat(result).contains("kimi-k2.6");
        assertThat(result).contains("gpt-4o");
        assertThat(result).contains("claude-3.5");
        assertThat(result).contains("(default)");
    }

    @Test
    void switchModel() {
        String result = cmd.handle(textEvent("/codex_runtime", "gpt-4o"), session);
        assertThat(result).contains("gpt-4o");
        // Verify the change was persisted via store, not just set on the session
        verify(store).setModelOverride(session.getId(), "gpt-4o");
    }

    @Test
    void invalidModelRejected() {
        String result = cmd.handle(textEvent("/codex_runtime", "nonexistent"), session);
        assertThat(result).contains("not in available models");
        verifyNoInteractions(store);
    }

    @Test
    void resetModel() {
        session.setModelOverride("gpt-4o");
        String result = cmd.handle(textEvent("/codex_runtime", "reset"), session);
        assertThat(result).contains("reset");
        verify(store).setModelOverride(session.getId(), null);
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "codex_runtime", args != null ? args : "");
    }
}