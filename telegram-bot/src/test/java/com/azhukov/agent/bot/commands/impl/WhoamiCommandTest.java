package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.auth.SlashAccessPolicy;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WhoamiCommandTest {

    @Test
    void showsUserInfoAdmin() {
        BotProperties properties = mock(BotProperties.class);
        BotProperties.Auth auth = mock(BotProperties.Auth.class);
        when(auth.getAllowedUserIds()).thenReturn(List.of("456"));
        when(auth.isAllowByDefault()).thenReturn(false);
        when(properties.getAuth()).thenReturn(auth);
        SlashAccessPolicy policy = mock(SlashAccessPolicy.class);
        when(policy.accessLevel(456L)).thenReturn("admin");
        var cmd = new WhoamiCommand(properties, policy);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("User ID: 456");
        assertThat(result).contains("@user");
        assertThat(result).contains("Chat ID: 123");
        assertThat(result).contains("Authorized: true");
        assertThat(result).contains("Slash access: admin");
    }

    @Test
    void showsUserInfoUnauthorized() {
        BotProperties properties = mock(BotProperties.class);
        BotProperties.Auth auth = mock(BotProperties.Auth.class);
        when(auth.getAllowedUserIds()).thenReturn(List.of("999"));
        when(auth.isAllowByDefault()).thenReturn(false);
        when(properties.getAuth()).thenReturn(auth);
        SlashAccessPolicy policy = mock(SlashAccessPolicy.class);
        when(policy.accessLevel(456L)).thenReturn("none");
        var cmd = new WhoamiCommand(properties, policy);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Authorized: false");
        assertThat(result).contains("Slash access: none");
    }

    @Test
    void nameAndDescription() {
        var cmd = new WhoamiCommand(mock(BotProperties.class), mock(SlashAccessPolicy.class));
        assertThat(cmd.name()).isEqualTo("whoami");
        assertThat(cmd.description()).isEqualTo("Show your user info");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/whoami " + args, null, null, null, null, null, null, true, "whoami", args);
    }
}