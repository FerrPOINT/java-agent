package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetHomeCommandTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec putUriSpec;
    @Mock private RestClient.RequestBodySpec bodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private BotProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
    }

    @SuppressWarnings("unchecked")
    private void stubPutChain(boolean fail) {
        when(restClient.put()).thenReturn(putUriSpec);
        when(putUriSpec.uri("/api/gateway/home-channel")).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenAnswer(inv -> {
            if (fail) {
                throw new org.springframework.web.client.RestClientResponseException(
                    "500", 500, "boom", null, null, null);
            }
            return org.springframework.http.ResponseEntity.ok().build();
        });
    }

    @Test
    void handle_setsHomeChatIdAndPersistsToBackend() {
        stubPutChain(false);
        var cmd = new SetHomeCommand(properties, restClient);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).contains("123").contains("persisted");
        assertThat(properties.getHomeChatId()).isEqualTo("123");
        verify(restClient).put();
        verify(putUriSpec).uri("/api/gateway/home-channel");
    }

    @Test
    void handle_reportsBackendFailureWithoutLosingInMemoryValue() {
        stubPutChain(true);
        var cmd = new SetHomeCommand(properties, restClient);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).contains("123").contains("persist failed");
        assertThat(properties.getHomeChatId()).isEqualTo("123");
    }

    @Test
    void nameAndDescription() {
        var cmd = new SetHomeCommand(properties, restClient);
        assertThat(cmd.name()).isEqualTo("set_home");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/set_home " + args,
            null, null, null, null, null, null, true, "set_home", args);
    }
}
