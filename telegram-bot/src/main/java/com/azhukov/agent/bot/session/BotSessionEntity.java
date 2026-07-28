package com.azhukov.agent.bot.session;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

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
    private String reasoningLevel = "medium";
    private boolean active = true;
    private Instant createdAt;
    private Instant updatedAt;
}