package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreditsCommandTest {

    private AgentBackendClient backendClient;
    private CreditsCommand cmd;

    @BeforeEach
    void setUp() {
        backendClient = mock(AgentBackendClient.class);
        cmd = new CreditsCommand(backendClient);
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("credits");
        assertThat(cmd.description()).isEqualTo("Show usage balance (tokens, messages)");
    }

    @Test
    void showsUsageBalance() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode insights = mapper.createObjectNode();
        insights.put("totalTokens", 15000);
        insights.put("totalMessages", 42);
        ObjectNode byModel = mapper.createObjectNode();
        byModel.put("kimi-k2.6", 10000);
        byModel.put("gpt-4o", 5000);
        insights.set("byModel", byModel);
        when(backendClient.getInsights()).thenReturn(insights);

        String result = cmd.handle(textEvent("/credits"), null);

        assertThat(result).contains("15000");
        assertThat(result).contains("42");
        assertThat(result).contains("kimi-k2.6");
    }

    @Test
    void showsNotAvailableWhenBackendOffline() {
        when(backendClient.getInsights()).thenReturn(null);

        String result = cmd.handle(textEvent("/credits"), null);

        assertThat(result).contains("not available");
    }

    private UpdateEvent textEvent(String text) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "credits", "");
    }
}