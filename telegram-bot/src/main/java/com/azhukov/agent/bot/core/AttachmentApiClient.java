package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * WP-11 (docs/35): bot-side client for the backend attachment artifact
 * contract ({@code /api/v1/attachments}).
 *
 * <p>Inbound Telegram media is registered as an artifact BEFORE the chat
 * request; the chat request then references the artifact id. On any backend
 * failure the caller falls back to the legacy local-path description — media
 * handling must never hard-fail because the artifact lane is down.
 */
@Slf4j
@Service
public class AttachmentApiClient extends BaseBackendClient {

    public AttachmentApiClient(@Qualifier("backendRestClient") RestClient restClient,
                               ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    /** Registration result: id on success, null on any failure. */
    public record Registered(String id, String contentHash, boolean duplicate) {}

    /**
     * Register an inbound artifact. Returns empty on ANY failure (network,
     * 4xx/5xx or an unparsable body).
     */
    public java.util.Optional<Registered> register(byte[] data, String fileName, String mimeType,
                                                   String disposition, String origin,
                                                   String ownerId, UUID sessionId, String messageId) {
        if (data == null || data.length == 0) {
            return java.util.Optional.empty();
        }
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            String safeName = sanitize(fileName);
            builder.part("file", new ByteArrayResource(data) {
                @Override public String getFilename() { return safeName; }
            }, mimeType != null ? MediaType.parseMediaType(mimeType) : MediaType.APPLICATION_OCTET_STREAM);
            if (disposition != null && !disposition.isBlank()) {
                builder.part("disposition", disposition);
            }
            if (origin != null && !origin.isBlank()) {
                builder.part("origin", origin);
            }
            if (ownerId != null && !ownerId.isBlank()) {
                builder.part("ownerId", ownerId);
            }
            if (sessionId != null) {
                builder.part("sessionId", sessionId.toString());
            }
            if (messageId != null && !messageId.isBlank()) {
                builder.part("messageId", messageId);
            }
            MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts = builder.build();
            String json = restClient.post()
                .uri("/api/v1/attachments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(String.class);
            JsonNode node = readTree(json);
            if (node == null || node.path("id").asText("").isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Registered(
                node.path("id").asText(),
                node.path("contentHash").asText(null),
                node.path("duplicate").asBoolean(false)));
        } catch (Exception e) {
            log.debug("attachment register failed ({}): {}",
                e.getClass().getSimpleName(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    public record Outbound(String id, String fileName, String mimeType, byte[] bytes) {}

    static final class OutboundUnavailable extends RuntimeException {
        OutboundUnavailable(Throwable cause) { super(cause); }
    }

    public java.util.Optional<Outbound> claimOutbound(String id, String owner, UUID sessionId) {
        try {
            var response = restClient.post().uri(uri -> uri.path("/api/v1/attachments/{id}/outbound")
                .queryParam("ownerId", owner).queryParam("sessionId", sessionId).build(id))
                .retrieve().toEntity(byte[].class);
            if (response.getStatusCode().value() == 204) return java.util.Optional.empty();
            byte[] bytes = response.getBody();
            if (bytes == null || bytes.length == 0) throw new IllegalStateException("Empty artifact body");
            var headers = response.getHeaders();
            var type = headers.getContentType();
            return java.util.Optional.of(new Outbound(id, sanitize(headers.getContentDisposition().getFilename()),
                type == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : type.toString(), bytes));
        } catch (org.springframework.web.client.RestClientException | IllegalStateException e) {
            log.warn("Outbound artifact {} unavailable ({})", id, e.getClass().getSimpleName());
            throw new OutboundUnavailable(e);
        }
    }

    public boolean markOutboundDelivered(String id, String owner, UUID sessionId, long messageId) {
        try {
            restClient.post().uri(uri -> uri.path("/api/v1/attachments/{id}/delivered")
                .queryParam("ownerId", owner).queryParam("sessionId", sessionId).queryParam("messageId", messageId)
                .build(id)).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Could not record outbound artifact {} receipt ({})", id, e.getClass().getSimpleName());
            return false;
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "file.bin";
        String cleaned = name.replaceAll("[^a-zA-Z0-9._-]", "_")
            .replaceAll("\\.{2,}", "_");
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }
}
