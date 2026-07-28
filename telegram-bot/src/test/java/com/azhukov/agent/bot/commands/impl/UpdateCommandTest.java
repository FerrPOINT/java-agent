package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UpdateCommandTest {

    @Test
    @SuppressWarnings("unchecked")
    void showsDevWhenBuildPropsAbsent() {
        BotProperties properties = mock(BotProperties.class);
        when(properties.getAgentName()).thenReturn("TestAgent");
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var cmd = new UpdateCommand(properties, provider);
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).contains("TestAgent");
        assertThat(result).contains("dev");
        assertThat(result).contains("Update via:");
        assertThat(result).contains("docker-compose pull && docker-compose up -d");
    }

    @Test
    @SuppressWarnings("unchecked")
    void showsVersionWhenBuildPropsPresent() {
        BotProperties properties = mock(BotProperties.class);
        when(properties.getAgentName()).thenReturn("MyAgent");
        BuildProperties buildProps = mock(BuildProperties.class);
        when(buildProps.getVersion()).thenReturn("2.1.3");
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(buildProps);
        var cmd = new UpdateCommand(properties, provider);
        UpdateEvent event = makeEvent();
        String result = cmd.handle(event, null);
        assertThat(result).contains("MyAgent");
        assertThat(result).contains("2.1.3");
        assertThat(result).contains("Update via:");
        assertThat(result).contains("docker-compose pull && docker-compose up -d");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nameAndDescription() {
        var cmd = new UpdateCommand(mock(BotProperties.class), mock(ObjectProvider.class));
        assertThat(cmd.name()).isEqualTo("update");
        assertThat(cmd.description()).isEqualTo("Show update instructions");
    }

    private UpdateEvent makeEvent() {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/update", null, null, null, null, null, null, true, "update", "");
    }
}