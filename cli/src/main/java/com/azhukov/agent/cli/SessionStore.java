package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-5: Local JSON-based session store.
 * <p>
 * Persists session metadata (ID, title, timestamps, message count) to
 * {@code ~/.java-agent-cli/sessions.json}. Loaded on startup for /sessions browsing.
 */
@Slf4j
public class SessionStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".java-agent-cli");
    private static final Path STORE_FILE = STORE_DIR.resolve("sessions.json");

    private final ObjectMapper objectMapper;
    private final Path storePath;

    private final List<SessionEntry> sessions = new ArrayList<>();
    private final Map<String, SessionEntry> byId = new LinkedHashMap<>();

    public SessionStore() {
        this(SharedObjectMapper.get(), STORE_FILE);
    }

    public SessionStore(ObjectMapper objectMapper, Path storePath) {
        this.objectMapper = objectMapper;
        this.storePath = storePath;
        load();
    }

    /**
     * A session entry stored in the local session DB.
     */
    public static class SessionEntry {
        public String sessionId;
        public String title;
        public String createdAt;
        public String lastUsed;
        public int messageCount;

        public SessionEntry() {}

        public SessionEntry(String sessionId, String title) {
            this.sessionId = sessionId;
            this.title = title;
            this.createdAt = Instant.now().toString();
            this.lastUsed = this.createdAt;
            this.messageCount = 0;
        }

        public void touch() {
            this.lastUsed = Instant.now().toString();
        }

        public void incrementMessageCount() {
            this.messageCount++;
        }
    }

    /**
     * Load sessions from disk.
     */
    @SuppressWarnings("unchecked")
    public void load() {
        try {
            if (Files.exists(storePath)) {
                String json = Files.readString(storePath);
                if (json.isBlank()) return;
                List<SessionEntry> loaded = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SessionEntry.class));
                sessions.clear();
                byId.clear();
                for (SessionEntry entry : loaded) {
                    sessions.add(entry);
                    byId.put(entry.sessionId, entry);
                }
                log.debug("Loaded {} sessions from {}", sessions.size(), storePath);
            }
        } catch (Exception e) {
            log.warn("Failed to load sessions from {}: {}", storePath, e.getMessage());
        }
    }

    /**
     * Persist sessions to disk.
     */
    public void save() {
        try {
            Files.createDirectories(storePath.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sessions);
            Files.writeString(storePath, json);
        } catch (IOException e) {
            log.warn("Failed to save sessions to {}: {}", storePath, e.getMessage());
        }
    }

    /**
     * Record or update a session.
     */
    public void recordSession(String sessionId, String title) {
        SessionEntry entry = byId.get(sessionId);
        if (entry == null) {
            entry = new SessionEntry(sessionId, title != null ? title : "(untitled)");
            sessions.add(entry);
            byId.put(sessionId, entry);
        } else if (title != null && !title.isBlank()) {
            entry.title = title;
        }
        entry.touch();
        save();
    }

    /**
     * Update the title of an existing session.
     */
    public boolean setTitle(String sessionId, String title) {
        SessionEntry entry = byId.get(sessionId);
        if (entry == null) return false;
        entry.title = title;
        entry.touch();
        save();
        return true;
    }

    /**
     * Increment message count for a session.
     */
    public void incrementMessages(String sessionId) {
        SessionEntry entry = byId.get(sessionId);
        if (entry != null) {
            entry.incrementMessageCount();
            entry.touch();
            save();
        }
    }

    /**
     * Get all stored sessions, sorted by lastUsed descending.
     */
    public List<SessionEntry> listSessions() {
        List<SessionEntry> sorted = new ArrayList<>(sessions);
        sorted.sort((a, b) -> {
            String aTime = a.lastUsed != null ? a.lastUsed : "";
            String bTime = b.lastUsed != null ? b.lastUsed : "";
            return bTime.compareTo(aTime);
        });
        return sorted;
    }

    /**
     * Get a session entry by ID.
     */
    public SessionEntry getSession(String sessionId) {
        return byId.get(sessionId);
    }

    /**
     * Delete a session from the local store.
     */
    public boolean deleteSession(String sessionId) {
        SessionEntry entry = byId.remove(sessionId);
        if (entry == null) return false;
        sessions.remove(entry);
        save();
        return true;
    }

    /**
     * Format sessions as a display string.
     */
    public String formatSessions() {
        List<SessionEntry> sorted = listSessions();
        if (sorted.isEmpty()) {
            return "No saved sessions.";
        }
        StringBuilder sb = new StringBuilder("Saved sessions:\n");
        sb.append("═══════════════════════════════════════════════════\n");
        for (SessionEntry e : sorted) {
            sb.append(String.format("  %-36s | %s | msgs: %d | %s%n",
                e.sessionId,
                e.title != null ? e.title : "(untitled)",
                e.messageCount,
                e.lastUsed != null ? e.lastUsed.substring(0, Math.min(19, e.lastUsed.length())) : "?"));
        }
        sb.append("═══════════════════════════════════════════════════");
        return sb.toString().trim();
    }
}