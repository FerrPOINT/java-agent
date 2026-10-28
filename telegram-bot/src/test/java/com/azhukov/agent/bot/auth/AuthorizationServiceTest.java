package com.azhukov.agent.bot.auth;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationServiceTest {
    @Test
    void onlyConfiguredIdsGrantAccessEvenWithLegacySettings() {
        var properties = new BotProperties();
        properties.getAuth().setAllowByDefault(true);
        properties.getAuth().getAllowedUsernames().add("other");
        properties.getAuth().getAllowedChatIds().add("456");
        properties.getAuth().getAllowedUserIds().add("*");
        properties.getAuth().getPairing().setEnabled(true);
        var service = new AuthorizationService(properties);
        assertThat(service.isAuthorized(123, "other", 456)).isFalse();
        properties.getAuth().getAllowedUserIds().add("123");
        assertThat(service.isAuthorized(123, null, 456)).isTrue();
        assertThat(service.isAuthorized(999, "other", 456)).isFalse();
        properties.getAuth().getAllowedUserIds().remove("123");
        assertThat(service.isAuthorized(123, "other", 456)).isFalse();
        assertThat(service.isAuthorized(123, "other", 456)).isFalse();
        properties.getAuth().getAllowedUserIds().add("123");
        assertThat(service.isAuthorized(123, null, 456)).isTrue();
    }

    @Test
    void eventUsesSenderIdNotChatId() {
        var properties = new BotProperties();
        properties.getAuth().getAllowedUserIds().add("123");
        var service = new AuthorizationService(properties);
        var event = new UpdateEvent(1, Type.TEXT, 456, 123, "jdoe",
            "hi", null, null, null, null, null, null, false, null, null);
        assertThat(service.isAuthorized(event)).isTrue();
    }

    @Test
    void emptyListDeniesEveryone() {
        assertThat(new AuthorizationService(new BotProperties()).isAuthorized(123, null, 456)).isFalse();
    }
}
