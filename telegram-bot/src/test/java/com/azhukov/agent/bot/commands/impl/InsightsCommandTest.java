package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InsightsCommandTest {

    @Test
    void nullInsights_returnsNoInsights() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.getInsights()).thenReturn(null);
        var cmd = new InsightsCommand(client);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("No insights available");
    }

    @Test
    void showsInsights() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode insights = mapper.createObjectNode();
        insights.put("totalTokens", 15000);
        insights.put("totalMessages", 42);
        ObjectNode byModel = mapper.createObjectNode();
        byModel.put("gpt-4", 8000);
        byModel.put("gpt-3.5", 7000);
        insights.set("byModel", byModel);
        when(client.getInsights()).thenReturn(insights);
        var cmd = new InsightsCommand(client);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Total tokens: 15000");
        assertThat(result).contains("Total messages: 42");
        assertThat(result).contains("By model:");
        assertThat(result).contains("gpt-4: 8000");
        assertThat(result).contains("gpt-3.5: 7000");
    }

    @Test
    void nameAndDescription() {
        var cmd = new InsightsCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("insights");
        assertThat(cmd.description()).isEqualTo("Show usage insights");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/insights " + args, null, null, null, null, null, null, true, "insights", args);
    }
}