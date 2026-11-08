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
    private static final String ID = "21a063fe-76f7-4fab-89a4-a93b69bcf1df";

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
        when(backendClient.listSuggestions("456")).thenReturn(mapper.createArrayNode());

        String result = cmd.handle(textEvent("/suggestions"), null);

        assertThat(result).contains("No suggested automations");
        assertThat(result).contains("catalog");
    }

    @Test
    void listShowsPendingSuggestionsNumbered() {
        ArrayNode pending = mapper.createArrayNode();
        ObjectNode s = mapper.createObjectNode();
        s.put("id", ID);
        s.put("title", "Daily briefing");
        s.put("description", "Every morning at 8am");
        pending.add(s);
        when(backendClient.listSuggestions("456")).thenReturn(pending);

        String result = cmd.handle(textEvent("/suggestions"), null);

        assertThat(result).contains("Daily briefing");
        assertThat(result).contains("Every morning at 8am");
        assertThat(result).contains(ID, "accept");
    }

    @Test
    void catalogSeeds() {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("added", 4);
        when(backendClient.suggestionPost("/api/v1/agent/cron/suggestions/catalog", "456")).thenReturn(resp);

        String result = cmd.handle(textEvent("/suggestions catalog"), null);

        assertThat(result).contains("Seeded 4");
    }

    @Test
    void acceptedButUnscheduledIsNotReportedAsScheduled() {
        ObjectNode response = mapper.createObjectNode();
        response.put("accepted", true);
        response.put("name", "review");
        response.put("schedulingStatus", "failed");
        when(backendClient.suggestionPost("/api/v1/agent/cron/suggestions/" + ID + "/accept", "456")).thenReturn(response);
        String result = cmd.handle(textEvent("/suggestions accept " + ID), null);
        assertThat(result).containsIgnoringCase("scheduling").containsIgnoringCase("failed");
    }

    @Test
    void acceptCreatesCronJob() {
        ObjectNode ok = mapper.createObjectNode();
        ok.put("accepted", true);
        ok.put("name", "Weekly review");
        when(backendClient.suggestionPost("/api/v1/agent/cron/suggestions/" + ID + "/accept", "456")).thenReturn(ok);

        String result = cmd.handle(textEvent("/suggestions accept " + ID), null);

        assertThat(result).contains("Weekly review");
    }

    @Test
    void dismissIsLatched() {
        ObjectNode ok = mapper.createObjectNode();
        ok.put("dismissed", true);
        when(backendClient.suggestionPost("/api/v1/agent/cron/suggestions/" + ID + "/dismiss", "456")).thenReturn(ok);

        String result = cmd.handle(textEvent("/suggestions dismiss " + ID), null);

        assertThat(result).contains("Dismissed");
    }

    @Test
    void unknownSubcommandShowsUsage() {
        String result = cmd.handle(textEvent("/suggestions frobnicate"), null);
        assertThat(result).contains("Unknown subcommand");
    }

    @Test
    void positionCannotAuthorizeAChangedSuggestion() {
        assertThat(cmd.handle(textEvent("/suggestions accept 2"), null)).contains("suggestion ID");
        verifyNoInteractions(backendClient);
    }

    @Test
    void failedListIsNotAnEmptyCatalog() {
        assertThat(cmd.handle(textEvent("/suggestions"), null)).contains("Could not load");
    }

    private UpdateEvent textEvent(String text) {
        String args = text.startsWith("/suggestions ") ? text.substring("/suggestions ".length()) : "";
        return new UpdateEvent(1, UpdateEvent.Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "suggestions", args);
    }
}
