package com.azhukov.agent.bot.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BotSettingsServiceTest {

    @Mock
    private BotSettingsRepository repository;
    private BotSettingsService service;

    @BeforeEach
    void setUp() {
        service = new BotSettingsService(repository);
    }

    @Test
    void getSetting_existingKey_returnsValue() {
        BotSettingsEntity entity = new BotSettingsEntity();
        entity.setKey("home_chat_id");
        entity.setValue("12345");
        when(repository.findByKey("home_chat_id")).thenReturn(Optional.of(entity));

        String result = service.getSetting("home_chat_id", "default");

        assertThat(result).isEqualTo("12345");
    }

    @Test
    void getSetting_missingKey_returnsDefault() {
        when(repository.findByKey("missing")).thenReturn(Optional.empty());

        String result = service.getSetting("missing", "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void getSetting_nullValue_returnsDefault() {
        BotSettingsEntity entity = new BotSettingsEntity();
        entity.setKey("null_val");
        entity.setValue(null);
        when(repository.findByKey("null_val")).thenReturn(Optional.of(entity));

        String result = service.getSetting("null_val", "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void setSetting_newKey_createsEntity() {
        when(repository.findByKey("new_key")).thenReturn(Optional.empty());

        service.setSetting("new_key", "new_value");

        ArgumentCaptor<BotSettingsEntity> captor = ArgumentCaptor.forClass(BotSettingsEntity.class);
        verify(repository).save(captor.capture());
        BotSettingsEntity saved = captor.getValue();
        assertThat(saved.getKey()).isEqualTo("new_key");
        assertThat(saved.getValue()).isEqualTo("new_value");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void setSetting_existingKey_updatesEntity() {
        BotSettingsEntity existing = new BotSettingsEntity();
        existing.setId(1L);
        existing.setKey("existing_key");
        existing.setValue("old_value");
        when(repository.findByKey("existing_key")).thenReturn(Optional.of(existing));

        service.setSetting("existing_key", "new_value");

        ArgumentCaptor<BotSettingsEntity> captor = ArgumentCaptor.forClass(BotSettingsEntity.class);
        verify(repository).save(captor.capture());
        BotSettingsEntity saved = captor.getValue();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getValue()).isEqualTo("new_value");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void getSettingsByPrefix_returnsMatchingEntries() {
        BotSettingsEntity e1 = new BotSettingsEntity();
        e1.setKey("topic_session:123:topic1");
        e1.setValue("session-a");
        BotSettingsEntity e2 = new BotSettingsEntity();
        e2.setKey("topic_session:123:topic2");
        e2.setValue("session-b");
        BotSettingsEntity e3 = new BotSettingsEntity();
        e3.setKey("topic_session:123:nullval");
        e3.setValue(null);
        when(repository.findByKeyStartingWith("topic_session:123:"))
            .thenReturn(List.of(e1, e2, e3));

        Map<String, String> result = service.getSettingsByPrefix("topic_session:123:");

        assertThat(result).hasSize(2);
        assertThat(result.get("topic_session:123:topic1")).isEqualTo("session-a");
        assertThat(result.get("topic_session:123:topic2")).isEqualTo("session-b");
        assertThat(result).doesNotContainKey("topic_session:123:nullval");
    }

    @Test
    void getSettingsByPrefix_noMatches_returnsEmptyMap() {
        when(repository.findByKeyStartingWith("nonexistent:")).thenReturn(List.of());

        Map<String, String> result = service.getSettingsByPrefix("nonexistent:");

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }
}