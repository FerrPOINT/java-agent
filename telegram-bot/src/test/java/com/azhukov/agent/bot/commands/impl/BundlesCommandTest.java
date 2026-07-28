package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BundlesCommandTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_emptyBundles_returnsNoBundles() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        JsonNode emptyArray = objectMapper.createArrayNode();
        when(client.listBundles()).thenReturn(emptyArray);

        var cmd = new BundlesCommand(client);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).isEqualTo("No skill bundles installed.");
    }

    @Test
    void handle_withBundles_listsThem() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        ArrayNode bundles = objectMapper.createArrayNode();
        bundles.add("bundle1");
        bundles.add("bundle2");
        when(client.listBundles()).thenReturn(bundles);

        var cmd = new BundlesCommand(client);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).contains("bundle1");
        assertThat(result).contains("bundle2");
    }

    @Test
    void nameAndDescription() {
        var cmd = new BundlesCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("bundles");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/bundles " + args,
            null, null, null, null, null, null, true, "bundles", args);
    }
}