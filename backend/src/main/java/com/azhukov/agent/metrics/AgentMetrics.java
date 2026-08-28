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
    // ── Performance instrumentation (2026-08-28 latency investigation) ──
    private final Timer turnLatency;        // full user->done turn
    private final Timer prepareContextLatency;
    private final Timer toolExecLatency;
    private final Timer compressionLatency; // summary model call inside compress()
    private final Counter sessionRotations;
    private final Counter compressionCalls;
    private final AtomicInteger contextLengthGauge = new AtomicInteger(0);

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
        this.turnLatency = Timer.builder("agent.turn.latency")
            .description("Full agent turn latency (user message -> done event)")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
        this.prepareContextLatency = Timer.builder("agent.context.prepare.latency")
            .description("prepareContext() latency (history load + sanitize + preflight)")
            .publishPercentiles(0.5, 0.95)
            .register(meterRegistry);
        this.toolExecLatency = Timer.builder("agent.tool.latency")
            .description("Tool execution latency")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
        this.compressionLatency = Timer.builder("agent.compression.latency")
            .description("Compression summary model call latency")
            .publishPercentiles(0.5, 0.95)
            .register(meterRegistry);
        this.sessionRotations = Counter.builder("agent.compression.rotations")
            .description("Session rotations performed (each = full history rewrite + summarizer call)")
            .register(meterRegistry);
        this.compressionCalls = Counter.builder("agent.compression.calls")
            .description("Compression attempts (preflight-triggered)")
            .register(meterRegistry);

        Gauge.builder("agent.sessions.active", activeSessions, AtomicInteger::get)
            .description("Number of active agent sessions")
            .register(meterRegistry);
        Gauge.builder("agent.context.window", contextLengthGauge, AtomicInteger::get)
            .description("Effective context window (tokens) used for preflight threshold; 0 = config fallback")
            .register(meterRegistry);
    }

    // ── Performance instrumentation accessors ──

    public void recordTurnDuration(long ms) { turnLatency.record(java.time.Duration.ofMillis(ms)); }
    public void recordPrepareContext(long ms) { prepareContextLatency.record(java.time.Duration.ofMillis(ms)); }
    public void recordToolDuration(String tool, long ms) {
        Timer.builder("agent.tool.latency").tag("tool", tool)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry).record(java.time.Duration.ofMillis(ms));
    }
    public void recordCompression(long ms) { compressionLatency.record(java.time.Duration.ofMillis(ms)); }
    public void incrementSessionRotations() { sessionRotations.increment(); }
    public void incrementCompressionCalls() { compressionCalls.increment(); }
    public void setContextWindow(int tokens) { contextLengthGauge.set(Math.max(0, tokens)); }

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