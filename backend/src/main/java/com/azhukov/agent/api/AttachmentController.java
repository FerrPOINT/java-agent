package com.azhukov.agent.api;

import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.AttachmentArtifactService;
import com.azhukov.agent.service.AttachmentArtifactService.ArtifactRegistration;
import com.azhukov.agent.service.AttachmentArtifactService.AttachmentArtifact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WP-11 (docs/35): cross-surface attachment artifacts — the REST surface the
 * bot and CLI register inbound media against before a chat request.
 *
 * <p>Upload registers an artifact (metadata row + blob under the controlled
 * cache root, hash dedupe by owner+content+disposition). Chat requests then
 * reference artifacts by id; the session pipeline injects an attachment block
 * so the model sees content (bounded text) or a safe cache path.
 */
@RestController
@RequestMapping("/api/v1/attachments")
@RequiredArgsConstructor
@Slf4j
public class AttachmentController {

    private final ObjectProvider<AttachmentArtifactService> serviceProvider;

    /** Bounded text preview injected into the turn when the artifact is text-ish. */
    private static final int TEXT_PREVIEW_CHARS = 4_000;

    private AttachmentArtifactService service() {
        AttachmentArtifactService service = serviceProvider == null
            ? null : serviceProvider.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "attachment artifacts are unavailable in this configuration");
        }
        return service;
    }

    public record UploadResponse(String id, String contentHash, boolean duplicate, String rejection) {
        public static UploadResponse of(ArtifactRegistration registration) {
            return new UploadResponse(registration.id(), registration.contentHash(),
                registration.duplicate(), registration.rejection());
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "origin", required = false) String origin,
            @RequestParam(value = "disposition", required = false, defaultValue = "file") String disposition,
            @RequestParam(value = "profile", required = false) String profile,
            @RequestParam(value = "sessionId", required = false) UUID sessionId,
            @RequestParam(value = "messageId", required = false) String messageId,
            @RequestParam(value = "ownerId", required = false) String ownerId) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file part is required");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file part is not readable");
        }
        ArtifactRegistration registration = service().register(
            authenticatedOwner(ownerId), profile, sessionId, messageId,
            // V62 chk_attachment_origin allows telegram/cli/internal only —
            // a blank origin from the REST upload is an internal one.
            origin == null || origin.isBlank() ? "internal" : origin,
            disposition, file.getContentType(), sanitizeFileName(file.getOriginalFilename()), data);
        if (registration.rejection() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, registration.rejection());
        }
        return ResponseEntity.ok(UploadResponse.of(registration));
    }

    @GetMapping("/{id}")
    public AttachmentArtifact get(@PathVariable("id") String id) {
        String owner = scopedOwner();
        return service().find(id, owner)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown artifact " + id));
    }

    /** Attachment block injected into the turn (bounded text preview or path reference). */
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable("id") String id) {
        String owner = scopedOwner();
        AttachmentArtifact artifact = service().find(id, owner)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown artifact " + id));
        Path path = service().contentPath(id, owner)
            .filter(Files::isReadable)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "artifact content is gone " + id));
        return contentResponse(artifact, new FileSystemResource(path));
    }

    @PostMapping("/{id}/outbound")
    public ResponseEntity<Resource> claimOutbound(@PathVariable("id") String id,
            @RequestParam(name = "ownerId", required = false) String ownerId,
            @RequestParam("sessionId") UUID sessionId) {
        String owner = checkedArtifactOwner(id, ownerId, sessionId);
        requireProtectedOutbound();
        var content = service().verifiedContent(id, owner)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact content is unavailable"));
        var response = contentResponse(content.artifact(), new org.springframework.core.io.ByteArrayResource(content.bytes()));
        if (!service().claimOutbound(id, owner, sessionId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Artifact delivery was already attempted");
        }
        return response;
    }

    private void requireProtectedOutbound() {
        if (!service().hasProtectedOutbound()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Outbound artifacts require an enforced storage boundary");
        }
    }

    private ResponseEntity<Resource> contentResponse(AttachmentArtifact artifact, Resource resource) {
        String fileName = artifact.fileName() != null ? artifact.fileName() : "artifact.bin";
        String contentType = artifact.mimeType() != null ? artifact.mimeType()
            : URLConnection.guessContentTypeFromName(fileName);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
            .header("X-Artifact-Id", artifact.id())
            .header("X-Content-Hash", artifact.contentHash() != null ? artifact.contentHash() : "")
            .contentType(contentType != null ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM)
            .body(resource);
    }

    /**
     * Outbound receipt: mark delivered exactly once (idempotent). Accepts an
     * platform message ID from a claimed send.
     */
    @PostMapping("/{id}/delivered")
    public Map<String, Object> delivered(@PathVariable("id") String id,
            @RequestParam(name = "ownerId", required = false) String ownerId,
            @RequestParam("sessionId") UUID sessionId,
            @RequestParam("messageId") String messageId) {
        if (messageId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Telegram message ID required");
        }
        String owner = checkedArtifactOwner(id, ownerId, sessionId);
        if (!service().markDelivered(id, owner, sessionId, messageId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No matching outbound attempt or receipt");
        }
        return Map.of("ok", true, "id", id, "state", "delivered");
    }

    @GetMapping("/session/{sessionId}")
    public List<AttachmentArtifact> bySession(@PathVariable("sessionId") UUID sessionId) {
        return service().bySession(sessionId, scopedOwner());
    }

    private String checkedArtifactOwner(String id, String ownerId, UUID sessionId) {
        String owner = UserContext.requireRequestOwner(ownerId);
        if (!service().isAvailableInSession(id, owner, sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown artifact");
        }
        return owner;
    }

    private static String scopedOwner() {
        return UserContext.scopeUserId();
    }

    private static String authenticatedOwner(String requestedOwnerId) {
        String scopedOwner = scopedOwner();
        return scopedOwner != null ? scopedOwner : requestedOwnerId;
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "file.bin";
        String cleaned = name.replaceAll("[^a-zA-Z0-9._-]", "_")
            .replaceAll("\\.{2,}", "_");
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }
}
