package com.azhukov.agent.bot.commands;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.impl.ModelCommand;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.keyboard.InlineKeyboardBuilder;
import com.azhukov.agent.bot.keyboard.ModelKeyboardBuilder;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCommandTest {

    private ModelCommand makeCommand(BotSessionStore store, BotProperties properties) {
        TelegramClient tc = mock(TelegramClient.class);
        when(tc.sendMessage(anyLong(), any())).thenReturn(Optional.of(1L));
        when(tc.sendMessage(anyLong(), any(), any(), any(), any())).thenReturn(Optional.of(1L));
        return new ModelCommand(store, properties, mock(ModelKeyboardBuilder.class), mock(InlineKeyboardBuilder.class), tc);
    }

    @Test
    void noArgs_showsCurrentModel() {
        BotSessionStore store = mock(BotSessionStore.class);
        BotProperties properties = new BotProperties();
        var cmd = makeCommand(store, properties);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setModelOverride("gpt-4o");
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).contains("gpt-4o");
    }

    @Test
    void noArgsNoOverride_showsDefault() {
        BotSessionStore store = mock(BotSessionStore.class);
        BotProperties properties = new BotProperties();
        var cmd = makeCommand(store, properties);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).contains("default");
    }

    @Test
    void withArgs_setsModel() {
        BotSessionStore store = mock(BotSessionStore.class);
        doNothing().when(store).setModelOverride(any(UUID.class), eq("claude-3-opus"));
        BotProperties properties = new BotProperties();
        var cmd = makeCommand(store, properties);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        UpdateEvent event = makeEvent("claude-3-opus");
        String result = cmd.handle(event, session);
        assertThat(result).contains("claude-3-opus");
    }

    @Test
    void nameAndDescription_correct() {
        var cmd = makeCommand(mock(BotSessionStore.class), new BotProperties());
        assertThat(cmd.name()).isEqualTo("model");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user",
            "/model " + args, null, null, null, null, null, null, true, "model", args);
    }
}