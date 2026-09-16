package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import com.azhukov.agent.service.DelegateCompletionBatcher.PendingRun;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery coalescing decision core (Hermes completion-batch parity): pending
 * completions for one parent session collapse into a single synthetic note
 * instead of N pings.
 */
class DelegateCompletionBatcherTest {

    private final DelegateCompletionBatcher batcher = new DelegateCompletionBatcher(Duration.ofMinutes(5));

    @Test
    void groupsByParentSessionKeepingArrivalOrder() {
        java.util.UUID parent = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        List<PendingRun> pending = List.of(
            new PendingRun("r1", parent, "fix auth", "completed", java.time.Instant.now()),
            new PendingRun("r2", other, "unrelated", "completed", java.time.Instant.now()),
            new PendingRun("r3", parent, "add tests", "completed", java.time.Instant.now())
        );

        List<List<PendingRun>> batches = batcher.batches(pending, 10);

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).extracting(PendingRun::runId).containsExactly("r1", "r3");
        assertThat(batches.get(1)).extracting(PendingRun::runId).containsExactly("r2");
    }

    @Test
    void capsBatchSizePerSession() {
        java.util.UUID parent = java.util.UUID.randomUUID();
        List<PendingRun> pending = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            pending.add(new PendingRun("r" + i, parent, "goal " + i, "completed", java.time.Instant.now()));
        }

        List<List<PendingRun>> batches = batcher.batches(pending, 3);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(3);
        assertThat(batches.get(1)).hasSize(3);
        assertThat(batches.get(2)).hasSize(1);
    }

    @Test
    void singleSummaryJoinsGoalsWithCounts() {
        java.util.UUID parent = java.util.UUID.randomUUID();
        List<PendingRun> batch = List.of(
            new PendingRun("r1", parent, "fix auth", "completed", java.time.Instant.now()),
            new PendingRun("r2", parent, "add tests", "failed", java.time.Instant.now())
        );

        String summary = batcher.summary(batch);

        assertThat(summary).contains("2");
        assertThat(summary).contains("fix auth");
        assertThat(summary).contains("add tests");
        assertThat(summary).contains("failed");
    }

    @Test
    void emptyInputYieldsNoBatches() {
        assertThat(batcher.batches(List.of(), 10)).isEmpty();
        assertThat(batcher.summary(List.of())).isEmpty();
    }
}
