package com.azhukov.agent.bot.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_pairing_codes")
@Data
public class PairingCodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", length = 8, nullable = false, unique = true)
    private String code;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "chat_id", nullable = false)
    private String chatId;

    @Column(name = "username", length = 128)
    private String username;

    @Column(name = "status", length = 16, nullable = false)
    private String status = "pending"; // pending | approved | denied | expired

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public PairingCodeEntity() {}
}