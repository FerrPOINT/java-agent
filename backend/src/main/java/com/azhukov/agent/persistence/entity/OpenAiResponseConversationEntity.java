package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "openai_response_conversations")
@Data
public class OpenAiResponseConversationEntity {

    @Id
    private String name;

    @Column(name = "response_id", nullable = false)
    private String responseId;
}
