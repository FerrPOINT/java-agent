package com.azhukov.agent.bot.session;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
     * In-memory metadata storage (not persisted).
     * Used for transient session state like standing goals, subgoals, etc.
     */
    @Transient
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

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