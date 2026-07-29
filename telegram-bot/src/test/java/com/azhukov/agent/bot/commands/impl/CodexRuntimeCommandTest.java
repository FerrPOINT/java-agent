package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodexRuntimeCommandTest {

    private BotProperties properties;
    private CodexRuntimeCommand cmd;
    private BotSessionEntity session;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        properties.setDefaultModel("kimi-k2.6");
        properties.getAvailableModels().addAll(List.of("kimi-k2.6", "gpt-4o", "claude-3.5"));
        cmd = new CodexRuntimeCommand(properties);
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
        assertThat(session.getModelOverride()).isEqualTo("gpt-4o");
    }

    @Test
    void invalidModelRejected() {
        String result = cmd.handle(textEvent("/codex_runtime", "nonexistent"), session);
        assertThat(result).contains("not in available models");
        assertThat(session.getModelOverride()).isNull();
    }

    @Test
    void resetModel() {
        session.setModelOverride("gpt-4o");
        String result = cmd.handle(textEvent("/codex_runtime", "reset"), session);
        assertThat(result).contains("reset");
        assertThat(session.getModelOverride()).isNull();
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "codex_runtime", args != null ? args : "");
    }
}