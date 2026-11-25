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

    /** Confirmed media references kept separate from untrusted message text. */
    public record AdmittedMedia(String description, java.util.List<String> artifactIds) {}

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
     * @return confirmed references and description, or empty if the event has no media
     */
    public Optional<AdmittedMedia> handle(UpdateEvent event) {
        if (event == null) return Optional.empty();

        String fileType = event.fileType();
        String fileId = event.fileId();

        if (fileType == null || fileId == null) {
            return Optional.empty();
        }

        // Photo album fix: PhotoBatchDebouncer may merge multiple file IDs
        // into a single comma-separated string. Split and process each.
        if (fileId.contains(",")) {
            String[] ids = fileId.split(",", -1);
            if (java.util.Arrays.stream(ids).anyMatch(String::isBlank)) {
                throw new AttachmentApiClient.AdmissionFailure(false, "Missing album attachment identifier");
            }
            StringBuilder combined = new StringBuilder();
            java.util.List<String> artifactIds = new java.util.ArrayList<>();
            for (String id : ids) {
                Optional<AdmittedMedia> admitted = handleSingle(event, fileType, id.trim());
                if (admitted.isPresent()) {
                    if (combined.length() > 0) combined.append("\n");
                    combined.append(admitted.get().description());
                    artifactIds.addAll(admitted.get().artifactIds());
                }
            }
            return combined.length() > 0 ? Optional.of(new AdmittedMedia(combined.toString(), java.util.List.copyOf(artifactIds))) : Optional.empty();
        }

        return handleSingle(event, fileType, fileId);
    }

    /**
     * Handle a single (non-comma-separated) file ID.
     */
    private Optional<AdmittedMedia> handleSingle(UpdateEvent event, String fileType, String fileId) {

        // Skip location — it's not a downloadable file
        if ("location".equals(fileType)) {
            return Optional.empty();
        }

        // Attempt to download the media
        Optional<byte[]> downloaded = mediaDownloader.downloadToFileId(fileId);

        if (downloaded.isEmpty()) {
            throw new AttachmentApiClient.AdmissionFailure(false, "Attachment download failed");
        }
        byte[] data = downloaded.get();
        if (data.length == 0 || data.length > MAX_FILE_SIZE_BYTES) {
            throw new AttachmentApiClient.AdmissionFailure(false, "Attachment is empty or exceeds 20 MB");
        }
        // The local copy expires independently; no automatic upload reconciliation is available.
        Path savedPath = saveMedia(fileId, fileType, data);
        if (savedPath == null) {
            throw new AttachmentApiClient.AdmissionFailure(false, "Attachment could not be retained locally");
        }
        String fileName = savedPath.getFileName().toString();
        var artifact = attachmentApiClient.register(data, fileName, mimeFor(fileType, fileName),
                dispositionFor(fileType), "telegram", event.userId() > 0 ? String.valueOf(event.userId()) : null,
                null, event.messageId() > 0 ? String.valueOf(event.messageId()) : null);
        String description = describe(fileType, fileId, data.length, fileName);
        if ("voice".equals(fileType) && transcriptionEnabled) {
            String transcript = backendClient.transcribe(data);
            if (transcript == null || transcript.isBlank()) {
                throw new AttachmentApiClient.AdmissionFailure(false, "Voice transcription failed");
            }
            description += "\n" + transcript;
        }
        return Optional.of(new AdmittedMedia(description, java.util.List.of(artifact.id())));
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

    private String describe(String fileType, String fileId, int sizeBytes, String fileName) {
        return switch (fileType) {
            case "photo" -> "[Photo: " + fileName + "]";
            case "document" -> "[Document: " + fileName + ", size=" + sizeBytes + " bytes]";
            case "voice" -> "[Voice message: file_id=" + fileId + ", size=" + sizeBytes + " bytes]";
            case "sticker" -> {
                // B2.1: Check sticker cache first
                Optional<String> cached = stickerCache.get(fileId);
                if (cached.isPresent()) {
                    yield "[Sticker: " + cached.get() + "]";
                }
                // The backend resolves the confirmed artifact; this text is display metadata.
                yield "[Sticker: " + fileName + "]";
            }
            case "animation" -> "[Animation/GIF: file_id=" + fileId + "]";
            default -> "[Media: type=" + fileType + ", file_id=" + fileId + "]";
        };
    }
}
