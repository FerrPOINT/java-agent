package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /approvals} contract: read-only view of the approval mode resolved
 * from the backend capabilities endpoint ({@code features.approval_events});
 * manual when enabled, off otherwise, with mode-appropriate guidance.
 */
class ApprovalsCommandTest {

    private final AgentBackendClient client = mock(AgentBackendClient.class);
    private final ApprovalsCommand cmd = new ApprovalsCommand(client);
    private final ObjectMapper mapper = new ObjectMapper();

    private UpdateEvent event() {
        return new UpdateEvent(1, UpdateEvent.Type.COMMAND, 1L, 1L, "u",
            "/approvals", null, null, null, null, null, null, true, "approvals", "");
    }

    @Test
    void approvalsEnabled_showsManualMode() throws Exception {
        when(client.suggestionGet("/v1/capabilities"))
            .thenReturn(mapper.readTree("{\"features\":{\"approval_events\":true}}"));

        String out = cmd.handle(event(), null);
        assertThat(out).contains("Approval mode: manual");
        assertThat(out).contains("/approve or /deny");
    }

    @Test
    void approvalsDisabled_showsOffMode() throws Exception {
        when(client.suggestionGet("/v1/capabilities"))
            .thenReturn(mapper.readTree("{\"features\":{\"approval_events\":false}}"));

        String out = cmd.handle(event(), null);
        assertThat(out).contains("Approval mode: off");
        assertThat(out).contains("without confirmation");
    }

    @Test
    void capabilitiesUnavailable_fallsBackToOff() {
        when(client.suggestionGet("/v1/capabilities")).thenReturn(null);

        String out = cmd.handle(event(), null);
        assertThat(out).contains("Approval mode: off");
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("approvals");
        assertThat(cmd.description()).contains("approval mode");
    }
}
