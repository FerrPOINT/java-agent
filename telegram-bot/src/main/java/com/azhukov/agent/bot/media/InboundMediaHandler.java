package com.azhukov.agent.bot.media;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.core.AttachmentApiClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.sticker.StickerCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Processes inbound media (photo/document/voice/sticker/animation) from
 * {@link UpdateEvent}s. Downloads the media and returns a description
 * string suitable for inclusion in LLM context.
 *
 * <p>For voice messages, if transcription is enabled (agent.transcription.enabled=true),
 * the audio is transcribed to text via the backend and the transcribed text
 * is used as the message content for the LLM.
 *
 * <p>For photos, documents, and stickers, the file is downloaded via the
 * {@link MediaDownloader} (Telegram getFile API) and saved to
 * the agent media temp directory so vision tools can analyze it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboundMediaHandler {

    private static final long MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024; // 20 MB
    private static final Path MEDIA_DIR = AgentMediaPaths.mediaDir();

    private final MediaDownloader mediaDownloader;
    private final StickerCache stickerCache;
    private final AgentBackendClient backendClient;
    private final AttachmentApiClient attachmentApiClient;

    @Value("${agent.transcription.enabled:false}")
    private boolean transcriptionEnabled;

    /**
     * Handles an UpdateEvent that contains media. If the event does not
     * contain media, returns an empty Optional.
     *
     * <p>When the {@code fileId} contains a comma-separated list of IDs
     * (produced by {@link com.azhukov.agent.bot.batch.PhotoBatchDebouncer}
     * when merging a photo album), each ID is processed in turn and the
     * resulting descriptions are concatenated with newlines.
     *
     * @param event the UpdateEvent to process
     * @return Optional with a description string for LLM context, or empty
     *         if the event has no media
     */
    public Optional<String> handle(UpdateEvent event) {
        if (event == null) return Optional.empty();

        String fileType = event.fileType();
        String fileId = event.fileId();

        if (fileType == null || fileId == null) {
            return Optional.empty();
        }

        // Photo album fix: PhotoBatchDebouncer may merge multiple file IDs
        // into a single comma-separated string. Split and process each.
        if (fileId.contains(",")) {
            String[] ids = fileId.split(",");
            StringBuilder combined = new StringBuilder();
            for (String id : ids) {
                String trimmed = id.trim();
                if (trimmed.isEmpty()) continue;
                Optional<String> desc = handleSingle(event, fileType, trimmed);
                if (desc.isPresent()) {
                    if (combined.length() > 0) combined.append("\n");
                    combined.append(desc.get());
                }
            }
            return combined.length() > 0 ? Optional.of(combined.toString()) : Optional.empty();
        }

        return handleSingle(event, fileType, fileId);
    }

    /**
     * Handle a single (non-comma-separated) file ID.
     */
    private Optional<String> handleSingle(UpdateEvent event, String fileType, String fileId) {

        // For voice messages with transcription enabled, transcribe and return the text
        if ("voice".equals(fileType) && transcriptionEnabled) {
            Optional<String> transcribed = transcribeVoice(fileId);
            if (transcribed.isPresent()) {
                log.debug("Voice message transcribed: {} chars", transcribed.get().length());
                return transcribed;
            }
            // Fallback to placeholder if transcription fails
        }

        // Skip location — it's not a downloadable file
        if ("location".equals(fileType)) {
            return Optional.empty();
        }

        // Attempt to download the media
        Optional<byte[]> downloaded = mediaDownloader.downloadToFileId(fileId);

        if (downloaded.isEmpty()) {
            log.warn("Failed to download media: type={}, fileId={}", fileType, fileId);
            String desc = describe(event, fileType, fileId, 0, null);
            return Optional.of(desc);
        }

        byte[] data = downloaded.get();
        int sizeBytes = data.length;

        // Enforce file size limit (20 MB)
        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            log.warn("Media too large ({} bytes > {} max): type={}, fileId={}, skipping download",
                sizeBytes, MAX_FILE_SIZE_BYTES, fileType, fileId);
            String desc = describe(event, fileType, fileId, sizeBytes, null);
            return Optional.of(desc);
        }

        // Save to the shared agent media temp directory.
        Path savedPath = saveMedia(fileId, fileType, data);
        String description = describe(event, fileType, fileId, sizeBytes, savedPath);
        // WP-11: register the downloaded media as a backend artifact so the
        // turn references a durable, deduped id instead of only a temp path.
        String artifactId = registerArtifact(event, fileType, data, savedPath);
        if (artifactId != null) {
            description = description + " (artifact=" + artifactId + ")";
        }
        log.debug("Handled media: type={}, fileId={}, size={}bytes, saved={}, artifact={}",
            fileType, fileId, sizeBytes, savedPath, artifactId);
        return Optional.of(description);
    }

    /**
     * WP-11: register downloaded media as a backend attachment artifact.
     * Best-effort — on any failure returns null and the caller keeps the
     * legacy local-path description (media handling never hard-fails).
     */
    private String registerArtifact(UpdateEvent event, String fileType, byte[] data, Path savedPath) {
        try {
            String ownerId = event.userId() > 0 ? String.valueOf(event.userId()) : null;
            String messageId = event.messageId() > 0 ? String.valueOf(event.messageId()) : null;
            String fileName = savedPath != null ? savedPath.getFileName().toString()
                : (fileType + ".bin");
            String mime = mimeFor(fileType, fileName);
            return attachmentApiClient
                .register(data, fileName, mime, dispositionFor(fileType), "telegram",
                    ownerId, null, messageId)
                .map(AttachmentApiClient.Registered::id)
                .orElse(null);
        } catch (Exception e) {
            log.debug("artifact registration skipped: {}", e.getMessage());
            return null;
        }
    }

    private static String dispositionFor(String fileType) {
        return switch (fileType == null ? "" : fileType) {
            case "photo" -> "photo";
            case "voice" -> "voice";
            case "video", "video_note", "animation" -> "video";
            case "audio" -> "audio";
            case "sticker" -> "sticker";
            default -> "file";
        };
    }

    private static String mimeFor(String fileType, String fileName) {
        return switch (fileType == null ? "" : fileType) {
            case "photo" -> "image/jpeg";
            case "voice" -> "audio/ogg";
            case "video" -> "video/mp4";
            case "video_note" -> "video/mp4";
            case "animation" -> "image/gif";
            case "audio" -> "audio/mpeg";
            case "sticker" -> "image/webp";
            default -> java.util.Optional.ofNullable(fileName)
                .filter(n -> n.lastIndexOf('.') > 0)
                .map(n -> {
                    String ext = n.substring(n.lastIndexOf('.') + 1).toLowerCase();
                    return switch (ext) {
                        case "png" -> "image/png";
                        case "jpg", "jpeg" -> "image/jpeg";
                        case "webp" -> "image/webp";
                        case "gif" -> "image/gif";
                        case "pdf" -> "application/pdf";
                        case "txt" -> "text/plain";
                        case "json" -> "application/json";
                        case "mp3" -> "audio/mpeg";
                        case "ogg" -> "audio/ogg";
                        case "mp4" -> "video/mp4";
                        default -> "application/octet-stream";
                    };
                })
                .orElse("application/octet-stream");
        };
    }

    /**
     * Save downloaded media bytes to the agent media temp directory with a filename derived
     * from the file_id and a sensible extension.
     *
     * @param fileId   Telegram file_id
     * @param fileType media type (photo, document, sticker, etc.)
     * @param data     file bytes
     * @return the path the file was saved to, or null on failure
     */
    private Path saveMedia(String fileId, String fileType, byte[] data) {
        try {
            Path dir = MEDIA_DIR;
            Files.createDirectories(dir);

            String ext = extensionFor(fileType);
            // Sanitize fileId for use as filename (keep only alphanumeric + - _)
            String safeName = fileId.replaceAll("[^A-Za-z0-9_-]", "_");
            String fileName = fileType + "_" + safeName + ext;
            Path path = dir.resolve(fileName);

            Files.write(path, data);
            log.debug("Saved media to {}", path);
            return path;
        } catch (IOException e) {
            log.warn("Failed to save media to {}: {}", MEDIA_DIR, e.getMessage());
            return null;
        }
    }

    /**
     * Returns a file extension appropriate for the media type.
     */
    private String extensionFor(String fileType) {
        return switch (fileType) {
            case "photo" -> ".jpg";
            case "sticker" -> ".webp";
            case "voice" -> ".ogg";
            case "animation" -> ".gif";
            case "document" -> ".bin"; // extension unknown without file metadata
            default -> ".bin";
        };
    }

    /**
     * Transcribe a voice message by downloading it and sending to the backend for transcription.
     *
     * @param fileId the Telegram file_id of the voice message
     * @return Optional with transcribed text, or empty if transcription failed
     */
    private Optional<String> transcribeVoice(String fileId) {
        try {
            Optional<byte[]> audioBytes = mediaDownloader.downloadToFileId(fileId);
            if (audioBytes.isEmpty()) {
                log.warn("Failed to download voice message for transcription: {}", fileId);
                return Optional.empty();
            }
            String text = backendClient.transcribe(audioBytes.get());
            if (text != null && !text.isBlank()) {
                return Optional.of(text);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Voice transcription failed for {}: {}", fileId, e.getMessage());
            return Optional.empty();
        }
    }

    private String describe(UpdateEvent event, String fileType, String fileId, int sizeBytes, Path savedPath) {
        String caption = event.caption();
        String captionPart = (caption != null && !caption.isBlank())
            ? ", caption=\"" + caption + "\""
            : "";

        String pathPart = savedPath != null ? ", path=" + savedPath : "";

        return switch (fileType) {
            case "photo" -> "[Photo: " + (savedPath != null ? savedPath : "download failed") + captionPart + "]";
            case "document" -> "[Document: " + (savedPath != null ? savedPath : "download failed") + ", size=" + sizeBytes + " bytes" + captionPart + "]";
            case "voice" -> "[Voice message: file_id=" + fileId + ", size=" + sizeBytes + " bytes" + captionPart + "]";
            case "sticker" -> {
                // B2.1: Check sticker cache first
                Optional<String> cached = stickerCache.get(fileId);
                if (cached.isPresent()) {
                    yield "[Sticker: " + cached.get() + "]";
                }
                // Cache miss — return description with saved path if available
                yield "[Sticker: " + (savedPath != null ? savedPath : "file_id=" + fileId) + "]";
            }
            case "animation" -> "[Animation/GIF: file_id=" + fileId + "]";
            default -> "[Media: type=" + fileType + ", file_id=" + fileId + pathPart + "]";
        };
    }
}
