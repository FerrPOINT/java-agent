package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.settings.BotSettingsService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TopicCommandTest {

    @Test
    void handle_noArgs_returnsUsage() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        BotSettingsService settingsService = mock(BotSettingsService.class);
        var cmd = new TopicCommand(sessionStore, settingsService);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).contains("Usage");
        assertThat(result).contains("/topic");
    }

    @Test
    void handle_list_empty_returnsMessage() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        BotSettingsService settingsService = mock(BotSettingsService.class);
        when(settingsService.getSettingsByPrefix("topic_session:123:")).thenReturn(Map.of());
        var cmd = new TopicCommand(sessionStore, settingsService);
        UpdateEvent event = makeEvent("list");

        String result = cmd.handle(event, null);

        assertThat(result).contains("No topic sessions");
    }

    @Test
    void handle_create_thenList() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        BotSettingsService settingsService = mock(BotSettingsService.class);
        BotSessionEntity newSession = new BotSessionEntity();
        newSession.setId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate("456", "123", "user")).thenReturn(newSession);
        when(settingsService.getSetting("topic_session:123:test1", null)).thenReturn(null);
        Map<String, String> storedTopics = new LinkedHashMap<>();
        storedTopics.put("topic_session:123:test1", newSession.getId().toString());
        when(settingsService.getSettingsByPrefix("topic_session:123:")).thenReturn(storedTopics);
        var cmd = new TopicCommand(sessionStore, settingsService);

        // Create a topic
        UpdateEvent createEvent = makeEvent("create test1");
        String createResult = cmd.handle(createEvent, null);
        assertThat(createResult).contains("created");

        // Verify the setting was persisted
        verify(settingsService).setSetting("topic_session:123:test1", newSession.getId().toString());

        // List topics
        UpdateEvent listEvent = makeEvent("list");
        String listResult = cmd.handle(listEvent, null);
        assertThat(listResult).contains("test1");
        assertThat(listResult).contains(newSession.getId().toString());
    }

    @Test
    void handle_create_duplicate_returnsMessage() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        BotSettingsService settingsService = mock(BotSettingsService.class);
        when(settingsService.getSetting("topic_session:123:existing", null)).thenReturn("session-uuid-123");
        var cmd = new TopicCommand(sessionStore, settingsService);

        UpdateEvent event = makeEvent("create existing");
        String result = cmd.handle(event, null);

        assertThat(result).contains("already exists");
        verify(sessionStore, never()).resolveOrCreate(any(), any(), any());
    }

    @Test
    void handle_switch_existing_returnsMessage() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        BotSettingsService settingsService = mock(BotSettingsService.class);
        when(settingsService.getSetting("topic_session:123:mytopic", null)).thenReturn("session-abc");
        var cmd = new TopicCommand(sessionStore, settingsService);

        UpdateEvent event = makeEvent("switch mytopic");
        String result = cmd.handle(event, null);

        assertThat(result).contains("Switched");
        assertThat(result).contains("mytopic");
        assertThat(result).contains("session-abc");
    }

    @Test
    void handle_switch_nonexistent_returnsMessage() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        BotSettingsService settingsService = mock(BotSettingsService.class);
        when(settingsService.getSetting("topic_session:123:nonexist", null)).thenReturn(null);
        var cmd = new TopicCommand(sessionStore, settingsService);

        UpdateEvent event = makeEvent("switch nonexist");
        String result = cmd.handle(event, null);

        assertThat(result).contains("not found");
    }

    @Test
    void nameAndDescription() {
        BotSessionStore sessionStore = mock(BotSessionStore.class);
        BotSettingsService settingsService = mock(BotSettingsService.class);
        var cmd = new TopicCommand(sessionStore, settingsService);

        assertThat(cmd.name()).isEqualTo("topic");
        assertThat(cmd.description()).isEqualTo("Manage DM topic sessions");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/topic " + args,
            null, null, null, null, null, null, true, "topic", args);
    }
}