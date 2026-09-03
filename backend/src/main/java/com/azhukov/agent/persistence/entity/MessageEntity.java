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

    /**
     * Complete assistant tool-call batch. The scalar fields above retain the
     * first call for compatibility, but cannot preserve parallel call IDs.
     */
    @Column(columnDefinition = "TEXT")
    private String toolCallsJson;

    /** Legacy-name aliases (PR-3 call sites use toolCalls). */
    public void setToolCalls(String json) { this.toolCallsJson = json; }
    public String getToolCalls() { return this.toolCallsJson; }

    /**
     * Responses-format response item id for this tool call (alias of
     * {@link #toolCallId}). Nullable: Chat Completions histories carry one
     * pairing id only.
     */
    @Column(name = "tool_response_item_id")
    private String toolResponseItemId;

    private Integer turnIndex;

    /** PR-3 parity: image attachments on the message (V40 migration column). */
    @jakarta.persistence.Column(name = "image_count")
    private Integer imageCount = 0;

    public Integer getImageCount() { return imageCount; }
    public void setImageCount(Integer imageCount) { this.imageCount = imageCount == null ? 0 : imageCount; }

    /** Whether the message is in live context (false after compaction archive). */
    private Boolean active = true;

    /** Whether the message was archived by compaction (active=false, compacted=true). */
    private Boolean compacted = false;

    private Instant createdAt;
}
