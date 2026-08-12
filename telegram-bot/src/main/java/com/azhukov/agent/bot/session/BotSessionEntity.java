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

    public String getMetadata(String key) {
        return metadata.get(key);
    }

    public void setMetadata(String key, String value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }
}