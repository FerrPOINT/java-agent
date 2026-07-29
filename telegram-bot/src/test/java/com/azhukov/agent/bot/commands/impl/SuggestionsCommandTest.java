package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SuggestionsCommandTest {

    private AgentBackendClient backendClient;
    private SuggestionsCommand cmd;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        backendClient = mock(AgentBackendClient.class);
        cmd = new SuggestionsCommand(backendClient);
        mapper = new ObjectMapper();
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("suggestions");
        assertThat(cmd.description()).isEqualTo("Review suggested automations");
    }

    @Test
    void emptyListShowsMessage() {
        when(backendClient.listCronJobs()).thenReturn(mapper.createArrayNode());

        String result = cmd.handle(textEvent("/suggestions", null), null);

        assertThat(result).contains("No suggested automations");
    }

    @Test
    void listShowsCronJobs() {
        ArrayNode jobs = mapper.createArrayNode();
        ObjectNode job = mapper.createObjectNode();
        job.put("id", "abc-123-def");
        job.put("name", "Daily summary");
        job.put("schedule", "0 9 * * *");
        job.put("enabled", true);
        jobs.add(job);
        when(backendClient.listCronJobs()).thenReturn(jobs);

        String result = cmd.handle(textEvent("/suggestions", null), null);

        assertThat(result).contains("Daily summary");
        assertThat(result).contains("0 9 * * *");
        assertThat(result).contains("(active)");
    }

    @Test
    void dismissDeletesJob() {
        when(backendClient.deleteCronJob("abc-123")).thenReturn(true);

        String result = cmd.handle(textEvent("/suggestions", "dismiss abc-123"), null);

        assertThat(result).contains("dismissed");
        verify(backendClient).deleteCronJob("abc-123");
    }

    @Test
    void acceptResumesJob() {
        when(backendClient.resumeCronJob("abc-123")).thenReturn(true);

        String result = cmd.handle(textEvent("/suggestions", "accept abc-123"), null);

        assertThat(result).contains("accepted");
        verify(backendClient).resumeCronJob("abc-123");
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "suggestions", args != null ? args : "");
    }
}