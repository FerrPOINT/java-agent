package com.azhukov.agent.api;

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
            ownerId, profile, sessionId, messageId,
            origin == null || origin.isBlank() ? "api" : origin,
            disposition, file.getContentType(), sanitizeFileName(file.getOriginalFilename()), data);
        if (registration.rejection() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, registration.rejection());
        }
        return ResponseEntity.ok(UploadResponse.of(registration));
    }

    @GetMapping("/{id}")
    public AttachmentArtifact get(@PathVariable("id") String id) {
        return service().find(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown artifact " + id));
    }

    /** Attachment block injected into the turn (bounded text preview or path reference). */
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable("id") String id) {
        AttachmentArtifact artifact = service().find(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown artifact " + id));
        Path path = service().contentPath(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "artifact content is gone " + id));
        if (!Files.isReadable(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "artifact content is gone " + id);
        }
        Resource resource = new FileSystemResource(path);
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

    /** Outbound receipt: mark delivered exactly once (idempotent). */
    @PostMapping("/{id}/delivered")
    public Map<String, Object> delivered(@PathVariable("id") String id) {
        boolean ok = service().markDelivered(id);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown artifact " + id);
        }
        return Map.of("ok", true, "id", id, "state", "delivered");
    }

    @GetMapping("/session/{sessionId}")
    public List<AttachmentArtifact> bySession(@PathVariable("sessionId") UUID sessionId) {
        return service().bySession(sessionId);
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "file.bin";
        String cleaned = name.replaceAll("[^a-zA-Z0-9._-]", "_")
            .replaceAll("\\.{2,}", "_");
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }
}
