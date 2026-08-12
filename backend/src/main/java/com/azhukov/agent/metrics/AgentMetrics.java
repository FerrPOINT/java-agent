package com.azhukov.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralised Micrometer metrics for the agent.
 * All counters/timers/gauges are registered here and injected where needed.
 */
@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter chatRequests;
    private final Counter chatStreaming;
    private final Timer llmLatency;
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.chatRequests = Counter.builder("agent.chat.requests")
            .description("Total number of chat requests")
            .register(meterRegistry);
        this.chatStreaming = Counter.builder("agent.chat.streaming")
            .description("Total number of streaming chat requests")
            .register(meterRegistry);
        this.llmLatency = Timer.builder("agent.llm.latency")
            .description("LLM call latency")
            .register(meterRegistry);

        Gauge.builder("agent.sessions.active", activeSessions, AtomicInteger::get)
            .description("Number of active agent sessions")
            .register(meterRegistry);
    }

    public void incrementChatRequests() {
        chatRequests.increment();
    }

    public void incrementChatStreaming() {
        chatStreaming.increment();
    }

    public void incrementToolCalls(String toolName) {
        Counter.builder("agent.tool.calls")
            .description("Total number of tool calls")
            .tag("tool", toolName)
            .register(meterRegistry)
            .increment();
    }

    public void incrementToolErrors(String toolName) {
        Counter.builder("agent.tool.errors")
            .description("Total number of tool errors")
            .tag("tool", toolName)
            .register(meterRegistry)
            .increment();
    }

    public Timer llmLatencyTimer() {
        return llmLatency;
    }

    public void incrementActiveSessions() {
        activeSessions.incrementAndGet();
    }

    public void decrementActiveSessions() {
        activeSessions.decrementAndGet();
    }
}