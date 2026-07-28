package com.azhukov.agent.bot.media;

import com.azhukov.agent.bot.polling.UpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Processes inbound media (photo/document/voice/sticker/animation) from
 * {@link UpdateEvent}s. Downloads the media and returns a description
 * string suitable for inclusion in LLM context.
 *
 * <p>Does NOT perform OCR or vision analysis yet — just returns metadata
 * such as "[Photo received, file_id=xxx]" or "[Document: report.pdf]".
 */
@Service
public class InboundMediaHandler {

    private static final Logger log = LoggerFactory.getLogger(InboundMediaHandler.class);

    private final MediaDownloader mediaDownloader;

    public InboundMediaHandler(MediaDownloader mediaDownloader) {
        this.mediaDownloader = mediaDownloader;
    }

    /**
     * Handles an UpdateEvent that contains media. If the event does not
     * contain media, returns an empty Optional.
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

        // Attempt to download the media (result is cached)
        Optional<byte[]> downloaded = mediaDownloader.downloadToFileId(fileId);
        int sizeBytes = downloaded.map(bytes -> bytes.length).orElse(0);

        String description = describe(event, fileType, fileId, sizeBytes);
        log.debug("Handled media: type={}, fileId={}, size={}bytes", fileType, fileId, sizeBytes);
        return Optional.of(description);
    }

    private String describe(UpdateEvent event, String fileType, String fileId, int sizeBytes) {
        String caption = event.caption();
        String captionPart = (caption != null && !caption.isBlank())
            ? ", caption=\"" + caption + "\""
            : "";

        return switch (fileType) {
            case "photo" -> "[Photo received, file_id=" + fileId + ", size=" + sizeBytes + " bytes" + captionPart + "]";
            case "document" -> "[Document: file_id=" + fileId + ", size=" + sizeBytes + " bytes" + captionPart + "]";
            case "voice" -> "[Voice message received, file_id=" + fileId + ", size=" + sizeBytes + " bytes" + captionPart + "]";
            case "sticker" -> "[Sticker received, file_id=" + fileId + "]";
            case "animation" -> "[Animation/GIF received, file_id=" + fileId + "]";
            default -> "[Media received: type=" + fileType + ", file_id=" + fileId + "]";
        };
    }
}