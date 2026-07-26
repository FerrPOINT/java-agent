package com.azhukov.agent.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;

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
        ModelHealthIndicator h = new ModelHealthIndicator(client, p);
        assertThat(h.health().getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.UP);
    }

    @Test
    void modelHealthDownOnError() throws Exception {
        ModelClient client = mock(ModelClient.class);
        when(client.complete(anyList(), anyList())).thenThrow(new RuntimeException("boom"));
        AgentProperties p = new AgentProperties();
        p.getModel().setModelName("test-model");
        ModelHealthIndicator h = new ModelHealthIndicator(client, p);
        assertThat(h.health().getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.DOWN);
    }
}
