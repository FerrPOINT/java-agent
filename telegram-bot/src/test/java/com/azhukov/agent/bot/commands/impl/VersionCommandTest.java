package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VersionCommandTest {

    @Test
    @SuppressWarnings("unchecked")
    void showsDevWhenBuildPropsAbsent() {
        BotProperties properties = mock(BotProperties.class);
        when(properties.getAgentName()).thenReturn("TestAgent");
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var cmd = new VersionCommand(properties, provider);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("TestAgent");
        assertThat(result).contains("dev");
        assertThat(result).contains("unknown");
    }

    @Test
    @SuppressWarnings("unchecked")
    void showsVersionWhenBuildPropsPresent() {
        BotProperties properties = mock(BotProperties.class);
        when(properties.getAgentName()).thenReturn("MyAgent");
        BuildProperties buildProps = mock(BuildProperties.class);
        when(buildProps.getVersion()).thenReturn("1.0.0");
        when(buildProps.get("build.time")).thenReturn("2025-01-01T00:00:00Z");
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(buildProps);
        var cmd = new VersionCommand(properties, provider);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("MyAgent");
        assertThat(result).contains("1.0.0");
        assertThat(result).contains("2025-01-01T00:00:00Z");
    }

    @Test
    void nameAndDescription() {
        @SuppressWarnings("unchecked")
        var cmd = new VersionCommand(mock(BotProperties.class), mock(ObjectProvider.class));
        assertThat(cmd.name()).isEqualTo("version");
        assertThat(cmd.description()).isEqualTo("Show agent version");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/version " + args, null, null, null, null, null, null, true, "version", args);
    }
}