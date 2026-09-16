package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * WP-11 (docs/35): a cross-surface attachment reference on a chat request.
 *
 * <p>Clients (Telegram bot, CLI) register inbound media as artifacts via
 * {@code POST /api/v1/attachments} first, then reference the artifact id here.
 * The session pipeline resolves each id to a safe cache path (never a client
 * path) and injects a bounded attachment block into the turn.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttachmentRef(
    String artifactId,
    String origin,
    String disposition
) {
    public static AttachmentRef of(String artifactId) {
        return new AttachmentRef(artifactId, null, null);
    }

    public boolean valid() {
        return artifactId != null && artifactId.startsWith("att_");
    }
}
