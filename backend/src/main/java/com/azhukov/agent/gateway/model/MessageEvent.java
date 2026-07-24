package com.azhukov.agent.gateway.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MessageEvent(
    String eventId,
    SessionSource source,
    MessageType type,
    String text,
    List<Attachment> attachments,
    Map<String, String> metadata,
    Instant receivedAt
) {
    public record Attachment(String url, String mimeType, String fileName, byte[] data) {}
}
