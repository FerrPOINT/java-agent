package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "openai_responses")
@Data
public class OpenAiResponseEntity {

    @Id
    @Column(name = "response_id")
    private String responseId;

    @Column(name = "response_json", columnDefinition = "TEXT", nullable = false)
    private String responseJson;

    @Column(name = "conversation_history_json", columnDefinition = "TEXT", nullable = false)
    private String conversationHistoryJson;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;
}
