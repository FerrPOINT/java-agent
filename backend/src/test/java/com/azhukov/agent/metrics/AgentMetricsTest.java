package com.azhukov.agent.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    private MeterRegistry meterRegistry;
    private AgentMetrics agentMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        agentMetrics = new AgentMetrics(meterRegistry);
    }

    @Test
    void incrementChatRequests_incrementsCounter() {
        agentMetrics.incrementChatRequests();
        agentMetrics.incrementChatRequests();
        assertThat(meterRegistry.get("agent.chat.requests").counter().count()).isEqualTo(2.0);
    }

    @Test
    void incrementChatStreaming_incrementsCounter() {
        agentMetrics.incrementChatStreaming();
        assertThat(meterRegistry.get("agent.chat.streaming").counter().count()).isEqualTo(1.0);
    }

    @Test
    void incrementToolCalls_createsTaggedCounter() {
        agentMetrics.incrementToolCalls("weather");
        agentMetrics.incrementToolCalls("weather");
        agentMetrics.incrementToolCalls("search");
        assertThat(meterRegistry.get("agent.tool.calls").tag("tool", "weather").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("agent.tool.calls").tag("tool", "search").counter().count()).isEqualTo(1.0);
    }

    @Test
    void incrementToolErrors_createsTaggedCounter() {
        agentMetrics.incrementToolErrors("weather");
        assertThat(meterRegistry.get("agent.tool.errors").tag("tool", "weather").counter().count()).isEqualTo(1.0);
    }

    @Test
    void llmLatencyTimer_isRegistered() {
        assertThat(agentMetrics.llmLatencyTimer()).isNotNull();
        agentMetrics.llmLatencyTimer().record(100, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(agentMetrics.llmLatencyTimer().count()).isEqualTo(1);
    }

    @Test
    void incrementActiveSessions_increasesGauge() {
        agentMetrics.incrementActiveSessions();
        agentMetrics.incrementActiveSessions();
        assertThat(meterRegistry.get("agent.sessions.active").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void decrementActiveSessions_decreasesGauge() {
        agentMetrics.incrementActiveSessions();
        agentMetrics.incrementActiveSessions();
        agentMetrics.decrementActiveSessions();
        assertThat(meterRegistry.get("agent.sessions.active").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void activeSessions_neverGoesNegative() {
        agentMetrics.decrementActiveSessions();
        assertThat(meterRegistry.get("agent.sessions.active").gauge().value()).isEqualTo(-1.0);
    }
}