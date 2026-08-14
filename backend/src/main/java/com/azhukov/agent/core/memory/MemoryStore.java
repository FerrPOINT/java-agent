package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory two-store model: "memory" (agent notes) and "user" (user profile).
 * Char limits are configurable via agent.memory.memory-char-limit / user-char-limit
 * (defaults: memory=2200, user=1375). Entry delimiter: §.
 * Snapshot is frozen per session — captured once and stable for the session lifetime.
 */
public class MemoryStore {

    public static final String TARGET_MEMORY = "memory";
    public static final String TARGET_USER = "user";
    public static final String DELIMITER = "\n§\n";
    /** Default char limit for the "memory" store, used when no config is provided. */
    public static final int DEFAULT_MEMORY_CHAR_LIMIT = 2200;
    /** Default char limit for the "user" store, used when no config is provided. */
    public static final int DEFAULT_USER_CHAR_LIMIT = 1375;

    private final List<String> memoryEntries = new ArrayList<>();
    private final List<String> userEntries = new ArrayList<>();
    private final MemoryThreatScanner threatScanner;
    private final int memoryCharLimit;
    private final int userCharLimit;
    private final Map<UUID, Map<String, String>> snapshotCache = new ConcurrentHashMap<>();

    /**
     * Creates an empty store without threat scanning and with default char limits.
     */
    public MemoryStore() {
        this(null, DEFAULT_MEMORY_CHAR_LIMIT, DEFAULT_USER_CHAR_LIMIT);
    }

    /**
     * Creates an empty store without threat scanning, using limits from the given properties.
     */
    public MemoryStore(AgentProperties properties) {
        this(null, properties);
    }

    /**
     * Creates a store with the given threat scanner and default char limits.
     */
    public MemoryStore(MemoryThreatScanner threatScanner) {
        this(threatScanner, DEFAULT_MEMORY_CHAR_LIMIT, DEFAULT_USER_CHAR_LIMIT);
    }

    /**
     * Creates a store with the given threat scanner and configurable char limits from properties.
     */
    public MemoryStore(MemoryThreatScanner threatScanner, AgentProperties properties) {
        this(threatScanner,
            properties.getMemory().getMemoryCharLimit(),
            properties.getMemory().getUserCharLimit());
    }

    /**
     * Creates a store with explicit char limits.
     */
    public MemoryStore(MemoryThreatScanner threatScanner, int memoryCharLimit, int userCharLimit) {
        this.threatScanner = threatScanner;
        this.memoryCharLimit = memoryCharLimit;
        this.userCharLimit = userCharLimit;
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
        int currentChars = charCount(store);
        int newTotal = currentChars + trimmed.length() + (store.isEmpty() ? 0 : DELIMITER.length());
        if (newTotal > limit) {
            // Parity with Hermes add() lines 328-341: error with current usage
            return "Memory at " + currentChars + "/" + limit + " chars. "
                + "Adding this entry (" + trimmed.length() + " chars) would exceed the limit. "
                + "Consolidate now: use 'replace' to merge overlapping entries into shorter ones "
                + "or 'remove' stale or less important entries, then retry this add — all in this turn.";
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
        // Find ALL entries containing oldText (parity with Hermes replace() lines 369-383)
        List<int[]> matchIndices = new ArrayList<>();
        List<String> matchTexts = new ArrayList<>();
        for (int i = 0; i < store.size(); i++) {
            if (store.get(i).contains(oldText)) {
                matchIndices.add(new int[]{i});
                matchTexts.add(store.get(i));
            }
        }
        if (matchIndices.isEmpty()) {
            return "No entry found containing: " + oldText;
        }
        // If >1 unique entries match, return error with previews (parity with Hermes)
        if (matchTexts.size() > 1) {
            long uniqueCount = matchTexts.stream().distinct().count();
            if (uniqueCount > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("Multiple entries match '").append(oldText).append("'. Be more specific:");
                int i = 1;
                for (String e : matchTexts) {
                    String preview = e.length() > 80 ? e.substring(0, 80) + "..." : e;
                    sb.append("\n").append(i++).append(". ").append(preview);
                }
                return sb.toString();
            }
            // All identical — safe to replace first
        }
        int idx = matchIndices.get(0)[0];
        // Overflow check: after replacement, total store chars must not exceed limit
        // Parity with Hermes replace() lines 389-406
        int limit = getCharLimit(target);
        List<String> testEntries = new ArrayList<>(store);
        testEntries.set(idx, newText.trim());
        int newTotal = String.join(DELIMITER, testEntries).length();
        if (newTotal > limit) {
            int current = charCount(store);
            return "Replacement would put memory at " + newTotal + "/" + limit + " chars. "
                + "Shorten the new content, or 'remove' other stale or less important entries "
                + "to make room, then retry — all in this turn.";
        }
        store.set(idx, newText.trim());
        invalidateSnapshot();
        return null;
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
        // Find ALL entries containing oldText (parity with Hermes remove() lines 426-440)
        List<Integer> matchIndices = new ArrayList<>();
        List<String> matchTexts = new ArrayList<>();
        for (int i = 0; i < store.size(); i++) {
            if (store.get(i).contains(oldText)) {
                matchIndices.add(i);
                matchTexts.add(store.get(i));
            }
        }
        if (matchIndices.isEmpty()) {
            return "No entry found containing: " + oldText;
        }
        // If >1 unique entries match, return error with previews (parity with Hermes)
        if (matchTexts.size() > 1) {
            long uniqueCount = matchTexts.stream().distinct().count();
            if (uniqueCount > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("Multiple entries match '").append(oldText).append("'. Be more specific:");
                int i = 1;
                for (String e : matchTexts) {
                    String preview = e.length() > 80 ? e.substring(0, 80) + "..." : e;
                    sb.append("\n").append(i++).append(". ").append(preview);
                }
                return sb.toString();
            }
            // All identical — safe to remove first
        }
        store.remove(matchIndices.get(0).intValue());
        invalidateSnapshot();
        return null;
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
            return memoryCharLimit;
        } else if (TARGET_USER.equalsIgnoreCase(target)) {
            return userCharLimit;
        }
        return 0;
    }

    /**
     * Count total chars including delimiters (parity with Hermes _char_count).
     */
    private int charCount(List<String> store) {
        if (store == null || store.isEmpty()) {
            return 0;
        }
        return String.join(DELIMITER, store).length();
    }
}