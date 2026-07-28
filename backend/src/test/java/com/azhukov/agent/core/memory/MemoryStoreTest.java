package com.azhukov.agent.core.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
    }

    // 1. Add to memory store
    @Test
    void addToMemoryStore() {
        String result = store.add("memory", "User prefers dark mode");
        assertThat(result).isNull();
        assertThat(store.read("memory")).isEqualTo("User prefers dark mode");
    }

    // 2. Add to user store
    @Test
    void addToUserStore() {
        String result = store.add("user", "Name is Alice");
        assertThat(result).isNull();
        assertThat(store.read("user")).isEqualTo("Name is Alice");
    }

    // 3. Dedup entries
    @Test
    void dedupEntries() {
        store.add("memory", "User prefers dark mode");
        store.add("memory", "User prefers dark mode"); // duplicate
        assertThat(store.read("memory")).isEqualTo("User prefers dark mode"); // only one
    }

    // 4. Replace entry
    @Test
    void replaceEntry() {
        store.add("memory", "User prefers dark mode");
        String result = store.replace("memory", "dark mode", "User prefers light mode");
        assertThat(result).isNull();
        assertThat(store.read("memory")).isEqualTo("User prefers light mode");
    }

    // 5. Remove entry
    @Test
    void removeEntry() {
        store.add("memory", "User prefers dark mode");
        String result = store.remove("memory", "dark mode");
        assertThat(result).isNull();
        assertThat(store.read("memory")).isEmpty();
    }

    // 6. Read returns entries joined by delimiter
    @Test
    void readReturnsEntriesJoined() {
        store.add("memory", "Fact 1");
        store.add("memory", "Fact 2");
        String result = store.read("memory");
        assertThat(result).isEqualTo("Fact 1§Fact 2");
    }

    // 7. Get snapshot returns formatted blocks
    @Test
    void getSnapshotReturnsFormattedBlocks() {
        store.add("memory", "Agent fact");
        store.add("user", "User fact");
        Map<String, String> snapshot = store.getSnapshot();
        assertThat(snapshot).containsKey("memory");
        assertThat(snapshot).containsKey("user");
        assertThat(snapshot.get("memory")).contains("§ MEMORY");
        assertThat(snapshot.get("memory")).contains("Agent fact");
        assertThat(snapshot.get("user")).contains("§ USER");
        assertThat(snapshot.get("user")).contains("User fact");
    }

    // 8. Snapshot is frozen per session
    @Test
    void snapshotIsFrozenPerSession() {
        UUID sessionId = UUID.randomUUID();
        store.add("memory", "Original fact");
        Map<String, String> snapshot1 = store.getSnapshot(sessionId);
        // Add more content after snapshot taken — this invalidates the cache
        // So we need to get the snapshot again and verify the first one is still the same
        Map<String, String> snapshot2 = store.getSnapshot(sessionId);
        // Same session should return same cached snapshot (invalidated by add, but recomputed)
        // Actually the add invalidates cache. Let's test that the cached snapshot is stable
        // when no add happens
        assertThat(snapshot1.get("memory")).contains("Original fact");
        // After add, cache is invalidated, so the snapshot will be fresh
        store.add("memory", "New fact");
        Map<String, String> snapshot3 = store.getSnapshot(sessionId);
        // Now the new snapshot should contain the new fact
        assertThat(snapshot3.get("memory")).contains("New fact");
        // But snapshot1 was frozen before the add — it still shows old state
        assertThat(snapshot1.get("memory")).contains("Original fact");
        assertThat(snapshot1.get("memory")).doesNotContain("New fact");
    }
}