package com.azhukov.agent.bot.session;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_sessions")
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
    private String reasoningLevel = "medium";
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getModelOverride() { return modelOverride; }
    public void setModelOverride(String modelOverride) { this.modelOverride = modelOverride; }
    public boolean isYoloMode() { return yoloMode; }
    public void setYoloMode(boolean yoloMode) { this.yoloMode = yoloMode; }
    public boolean isVerboseMode() { return verboseMode; }
    public void setVerboseMode(boolean verboseMode) { this.verboseMode = verboseMode; }
    public boolean isFastMode() { return fastMode; }
    public void setFastMode(boolean fastMode) { this.fastMode = fastMode; }
    public boolean isFooterEnabled() { return footerEnabled; }
    public void setFooterEnabled(boolean footerEnabled) { this.footerEnabled = footerEnabled; }
    public String getReasoningLevel() { return reasoningLevel; }
    public void setReasoningLevel(String reasoningLevel) { this.reasoningLevel = reasoningLevel; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}