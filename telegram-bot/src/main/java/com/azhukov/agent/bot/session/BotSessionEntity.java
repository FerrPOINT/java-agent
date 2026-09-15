package com.azhukov.agent.bot.session;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Entity
@Table(name = "bot_sessions")
@Data
public class BotSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String userId;
    private String chatId;
    private String username;
    private String title;
    private String modelOverride;
    private boolean yoloMode;
    private boolean verboseMode;
    private boolean fastMode;
    private boolean footerEnabled;
    private boolean voiceMode;
    private String reasoningLevel = "medium";
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * The session ID assigned by the backend. The bot sends this to the backend
     * so it can find the correct conversation history. Null on first message
     * (backend creates a new session and returns its ID).
     */
    @Column(name = "backend_session_id")
    private UUID backendSessionId;

    // P0: Session lifecycle states — suspend / resume-pending (persisted)
    @Column(name = "suspended")
    private boolean suspended = false;
    @Column(name = "resume_pending")
    private boolean resumePending = false;

    /**
     * Metadata storage persisted as JSON in the {@code metadata} column.
     * Used for session state like standing goals, subgoals, etc.
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    @Convert(converter = MetadataConverter.class)
    private ConcurrentHashMap<String, String> metadata = new ConcurrentHashMap<>();

    /** Metadata key persisting the last known forum/DM topic thread id (docs/34 gap 9). */
    public static final String METADATA_KEY_THREAD_ID = "last_thread_id";

    public String getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Last Telegram thread (forum topic / DM topic) the user messaged from.
     * Persisted in metadata so restart-surviving sends can route back into
     * the same topic; 0 means "no thread routing".
     */
    public long getLastMessageThreadId() {
        String raw = metadata.get(METADATA_KEY_THREAD_ID);
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L; // corrupt value: fail safe to "no routing"
        }
    }

    /**
     * Persist the last thread id. Passing 0 clears the routing (a bogus 0
     * must never override a real topic).
     */
    public void setLastMessageThreadId(long threadId) {
        if (threadId > 0) {
            setMetadata(METADATA_KEY_THREAD_ID, Long.toString(threadId));
        } else {
            setMetadata(METADATA_KEY_THREAD_ID, null);
        }
    }

    public void setMetadata(String key, String value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }
}