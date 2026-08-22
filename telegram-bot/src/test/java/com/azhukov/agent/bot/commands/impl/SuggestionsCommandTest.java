package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Hermes-parity tests: /suggestions is the curated catalog store
 * (pending/accept/dismiss-latched), NOT a CRUD shim over live cron jobs.
 */
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
        assertThat(cmd.description()).contains("suggested automations");
    }

    @Test
    void emptyListPointsAtCatalog() {
        when(backendClient.listSuggestions()).thenReturn(mapper.createArrayNode());

        String result = cmd.handle(textEvent("/suggestions"), null);

        assertThat(result).contains("No suggested automations");
        assertThat(result).contains("catalog");
    }

    @Test
    void listShowsPendingSuggestionsNumbered() {
        ArrayNode pending = mapper.createArrayNode();
        ObjectNode s = mapper.createObjectNode();
        s.put("id", "sgg-1");
        s.put("title", "Daily briefing");
        s.put("description", "Every morning at 8am");
        pending.add(s);
        when(backendClient.listSuggestions()).thenReturn(pending);

        String result = cmd.handle(textEvent("/suggestions"), null);

        assertThat(result).contains("Daily briefing");
        assertThat(result).contains("Every morning at 8am");
        assertThat(result).contains("accept");
    }

    @Test
    void catalogSeeds() {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("added", 4);
        when(backendClient.suggestionPost("/api/v1/agent/cron/suggestions/catalog")).thenReturn(resp);

        String result = cmd.handle(textEvent("/suggestions catalog"), null);

        assertThat(result).contains("Seeded 4");
    }

    @Test
    void acceptCreatesCronJob() {
        ArrayNode pending = mapper.createArrayNode();
        ObjectNode s = mapper.createObjectNode();
        s.put("id", "sgg-9");
        s.put("title", "Weekly review");
        s.put("description", "d");
        pending.add(s);
        when(backendClient.listSuggestions()).thenReturn(pending);
        ObjectNode ok = mapper.createObjectNode();
        ok.put("accepted", true);
        ok.put("name", "Weekly review");
        when(backendClient.suggestionPost("/api/v1/agent/cron/suggestions/sgg-9/accept")).thenReturn(ok);

        String result = cmd.handle(textEvent("/suggestions accept 1"), null);

        assertThat(result).contains("Weekly review");
    }

    @Test
    void dismissIsLatched() {
        ArrayNode pending = mapper.createArrayNode();
        ObjectNode s = mapper.createObjectNode();
        s.put("id", "sgg-2");
        s.put("title", "t");
        s.put("description", "d");
        pending.add(s);
        when(backendClient.listSuggestions()).thenReturn(pending);
        ObjectNode ok = mapper.createObjectNode();
        ok.put("dismissed", true);
        when(backendClient.suggestionPost("/api/v1/agent/cron/suggestions/sgg-2/dismiss")).thenReturn(ok);

        String result = cmd.handle(textEvent("/suggestions dismiss 1"), null);

        assertThat(result).contains("Dismissed");
    }

    @Test
    void unknownSubcommandShowsUsage() {
        String result = cmd.handle(textEvent("/suggestions frobnicate"), null);
        assertThat(result).contains("Unknown subcommand");
    }

    private UpdateEvent textEvent(String text) {
        String args = text.startsWith("/suggestions ") ? text.substring("/suggestions ".length()) : "";
        return new UpdateEvent(1, UpdateEvent.Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "suggestions", args);
    }
}
