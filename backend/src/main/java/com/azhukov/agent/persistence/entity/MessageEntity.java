package com.azhukov.agent.persistence.entity;

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
@Table(name = "messages")
@Data
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID sessionId;

    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String toolCallId;

    private String toolCallName;

    @Column(columnDefinition = "TEXT")
    private String toolCallArguments;

    private Integer turnIndex;

    private Instant createdAt;
}