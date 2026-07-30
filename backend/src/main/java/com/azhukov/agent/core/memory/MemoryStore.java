package com.azhukov.agent.core.memory;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory two-store model: "memory" (agent notes) and "user" (user profile).
 * Char limits: memory=2200, user=1375. Entry delimiter: §.
 * Snapshot is frozen per session — captured once and stable for the session lifetime.
 */
@RequiredArgsConstructor
public class MemoryStore {

    public static final String TARGET_MEMORY = "memory";
    public static final String TARGET_USER = "user";
    public static final String DELIMITER = "§";
    public static final int MEMORY_CHAR_LIMIT = 2200;
    public static final int USER_CHAR_LIMIT = 1375;

    private final List<String> memoryEntries = new ArrayList<>();
    private final List<String> userEntries = new ArrayList<>();
    private final MemoryThreatScanner threatScanner;
    private final Map<UUID, Map<String, String>> snapshotCache = new ConcurrentHashMap<>();

    /**
     * Creates an empty store without threat scanning.
     */
    public MemoryStore() {
        this(null);
    }

    /**
     * Add content to the specified target store.
     * @return null on success, error message on failure (threat detected, exceeds limit)
     */
    public synchronized String add(String target, String content) {
        if (content == null || content.isBlank()) {
            return "Content is empty";
        }
        final String trimmed = content.trim();
        if (threatScanner != null) {
            var threat = threatScanner.scan(trimmed);
            if (threat.isPresent()) {
                return "Blocked: " + threat.get();
            }
        }
        List<String> store = getStore(target);
        if (store == null) {
            return "Unknown target: " + target;
        }
        // Dedup: preserve order, keep first occurrence
        if (store.stream().anyMatch(e -> e.equals(trimmed))) {
            return null; // Already exists, no-op (dedup)
        }
        int limit = getCharLimit(target);
        int currentChars = store.stream().mapToInt(String::length).sum();
        if (currentChars + trimmed.length() > limit) {
            return "Content exceeds char limit (" + limit + ") for target: " + target;
        }
        store.add(trimmed);
        invalidateSnapshot();
        return null;
    }

    /**
     * Replace an entry containing oldText with newText in the specified target store.
     * @return null on success, error message on failure
     */
    public synchronized String replace(String target, String oldText, String newText) {
        if (oldText == null || oldText.isBlank()) {
            return "old_text is required";
        }
        if (newText == null || newText.isBlank()) {
            return "content is required";
        }
        if (threatScanner != null) {
            var threat = threatScanner.scan(newText);
            if (threat.isPresent()) {
                return "Blocked: " + threat.get();
            }
        }
        List<String> store = getStore(target);
        if (store == null) {
            return "Unknown target: " + target;
        }
        for (int i = 0; i < store.size(); i++) {
            if (store.get(i).contains(oldText)) {
                store.set(i, newText.trim());
                invalidateSnapshot();
                return null;
            }
        }
        return "No entry found containing: " + oldText;
    }

    /**
     * Remove an entry containing oldText from the specified target store.
     * @return null on success, error message on failure
     */
    public synchronized String remove(String target, String oldText) {
        if (oldText == null || oldText.isBlank()) {
            return "old_text is required";
        }
        List<String> store = getStore(target);
        if (store == null) {
            return "Unknown target: " + target;
        }
        for (int i = 0; i < store.size(); i++) {
            if (store.get(i).contains(oldText)) {
                store.remove(i);
                invalidateSnapshot();
                return null;
            }
        }
        return "No entry found containing: " + oldText;
    }

    /**
     * Read all entries from the specified target store, joined by delimiter.
     */
    public synchronized String read(String target) {
        List<String> store = getStore(target);
        if (store == null || store.isEmpty()) {
            return "";
        }
        return String.join(DELIMITER, store);
    }

    /**
     * Get a frozen snapshot of both stores, formatted as blocks.
     * Returns Map with "memory" and "user" keys containing formatted blocks.
     */
    public synchronized Map<String, String> getSnapshot() {
        Map<String, String> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put(TARGET_MEMORY, formatBlock(TARGET_MEMORY, memoryEntries));
        snapshot.put(TARGET_USER, formatBlock(TARGET_USER, userEntries));
        return snapshot;
    }

    /**
     * Get a cached snapshot for a session — frozen at first call, stable for session.
     */
    public Map<String, String> getSnapshot(UUID sessionId) {
        return snapshotCache.computeIfAbsent(sessionId, id -> getSnapshot());
    }

    /**
     * Invalidate cached snapshots (called when memory changes).
     */
    public synchronized void invalidateSnapshot() {
        snapshotCache.clear();
    }

    private String formatBlock(String target, List<String> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§ ").append(target.toUpperCase()).append("\n");
        for (String entry : entries) {
            String safeEntry = entry;
            if (threatScanner != null) {
                var threat = threatScanner.scan(entry);
                if (threat.isPresent()) {
                    safeEntry = "[BLOCKED: " + threat.get() + "]";
                }
            }
            sb.append(safeEntry).append("\n");
        }
        return sb.toString().trim();
    }

    private List<String> getStore(String target) {
        if (TARGET_MEMORY.equalsIgnoreCase(target)) {
            return memoryEntries;
        } else if (TARGET_USER.equalsIgnoreCase(target)) {
            return userEntries;
        }
        return null;
    }

    private int getCharLimit(String target) {
        if (TARGET_MEMORY.equalsIgnoreCase(target)) {
            return MEMORY_CHAR_LIMIT;
        } else if (TARGET_USER.equalsIgnoreCase(target)) {
            return USER_CHAR_LIMIT;
        }
        return 0;
    }
}