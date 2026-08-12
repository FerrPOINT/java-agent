package com.azhukov.agent.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HealthIndicatorsTest {

    @Test
    void modelHealthUpWhenClientResponds() throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.complete(anyList(), anyList())).thenReturn(ChatResponse.text("pong"));
        AgentProperties p = new AgentProperties();
        p.getModel().setModelName("test-model");
        p.getModel().setProvider("openai-compatible");
        ModelHealthIndicator h = new ModelHealthIndicator(client, p);
        assertThat(h.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void modelHealthDownOnError() throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.complete(anyList(), anyList())).thenThrow(new RuntimeException("boom"));
        AgentProperties p = new AgentProperties();
        p.getModel().setModelName("test-model");
        p.getModel().setProvider("openai-compatible");
        ModelHealthIndicator h = new ModelHealthIndicator(client, p);
        assertThat(h.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void modelHealthUpWithNotConfiguredWhenNoopProvider() {
        ModelClient client = mock(ModelClient.class);
        AgentProperties p = new AgentProperties();
        p.getModel().setProvider("noop");
        p.getModel().setModelName("noop-model");
        ModelHealthIndicator h = new ModelHealthIndicator(client, p);
        Health health = h.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "not_configured");
        assertThat(health.getDetails()).containsEntry("provider", "noop");
        verifyNoInteractions(client);
    }
}