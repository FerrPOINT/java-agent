package com.azhukov.agent.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivery coalescing core (Hermes completion-batch parity, docs/35 WP-1
 * tail): pending delegated completions grouped per parent session collapse
 * into one synthetic reinjection note instead of N separate pings. Pure
 * decision logic — persistence and delivery stay in the gateway.
 */
public final class DelegateCompletionBatcher {

    public DelegateCompletionBatcher(@SuppressWarnings("unused") Duration window) {
        // Window kept for configuration parity; each tick drains the whole
        // pending backlog, so grouping is by parent session only.
    }

    /** Group pending runs by parent session (arrival order), capped per batch. */
    public List<List<PendingRun>> batches(List<PendingRun> pending, int maxPerBatch) {
        Map<java.util.UUID, List<PendingRun>> byParent = new LinkedHashMap<>();
        for (PendingRun run : pending) {
            byParent.computeIfAbsent(run.parentSessionId(), key -> new ArrayList<>()).add(run);
        }
        List<List<PendingRun>> batches = new ArrayList<>();
        for (List<PendingRun> group : byParent.values()) {
            for (int i = 0; i < group.size(); i += maxPerBatch) {
                batches.add(List.copyOf(group.subList(i, Math.min(i + maxPerBatch, group.size()))));
            }
        }
        return batches;
    }

    /** One synthetic summary for a coalesced batch of completions. */
    public String summary(List<PendingRun> batch) {
        if (batch.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Completed " + batch.size() + " background task(s):\n");
        for (PendingRun run : batch) {
            sb.append("- ").append(run.goal());
            if (!"completed".equals(run.status())) {
                sb.append(" (").append(run.status()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Canonical pending-run shape the batcher reasons about. */
    public record PendingRun(
        String runId,
        java.util.UUID parentSessionId,
        String goal,
        String status,
        java.time.Instant completedAt
    ) {}
}
