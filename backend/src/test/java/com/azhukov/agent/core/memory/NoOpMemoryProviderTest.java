package com.azhukov.agent.core.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpMemoryProviderTest {

    @Test
    void allNoOpMethodsBehaveAsSpecified() {
        NoOpMemoryProvider p = new NoOpMemoryProvider();

        // recall returns empty list — not null, not populated
        assertThat(p.recall("user1", "query", 10))
            .isNotNull()
            .isEmpty();

        // store (3-arg) does nothing — no exception, no side effect
        p.store("user1", "cat", "fact");

        // store (4-arg) does nothing — no exception, no side effect
        p.store("user1", "memory", "cat", "fact");

        // replace returns null (meaning "no message to report")
        assertThat(p.replace("user1", "memory", "old", "new")).isNull();

        // remove returns null (meaning "no message to report")
        assertThat(p.remove("user1", "memory", "old")).isNull();

        // read returns empty string — not null
        assertThat(p.read("user1", "memory")).isEmpty();

        // getSnapshot returns map with empty memory and user blocks
        Map<String, String> snapshot = p.getSnapshot("user1");
        assertThat(snapshot)
            .isNotNull()
            .containsOnlyKeys("memory", "user");
        assertThat(snapshot.get("memory")).isEmpty();
        assertThat(snapshot.get("user")).isEmpty();
    }

    @Test
    void recallReturnsImmutableEmptyList() {
        NoOpMemoryProvider p = new NoOpMemoryProvider();
        List<String> result = p.recall("u", "q", 5);
        assertThat(result).isInstanceOf(List.class);
        // List.of() returns an immutable list
        assertThat(result).isEmpty();
        assertThat(result).isUnmodifiable();
    }

    @Test
    void getSnapshotReturnsImmutableMap() {
        NoOpMemoryProvider p = new NoOpMemoryProvider();
        Map<String, String> snapshot = p.getSnapshot("u");
        assertThat(snapshot).isUnmodifiable();
    }

    @Test
    void methodsAreIdempotentAcrossMultipleCalls() {
        NoOpMemoryProvider p = new NoOpMemoryProvider();
        // Calling methods multiple times should produce the same results
        for (int i = 0; i < 3; i++) {
            assertThat(p.recall("u", "q", 5)).isEmpty();
            assertThat(p.read("u", "target")).isEmpty();
            assertThat(p.replace("u", "t", "old", "new")).isNull();
            assertThat(p.remove("u", "t", "old")).isNull();
            p.store("u", "c", "f");
            p.store("u", "memory", "c", "f");
        }
        // Final snapshot should still be empty
        assertThat(p.getSnapshot("u").get("memory")).isEmpty();
    }
}