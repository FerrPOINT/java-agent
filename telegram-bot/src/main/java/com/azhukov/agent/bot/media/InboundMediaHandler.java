package com.azhukov.agent.bot.media;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.sticker.StickerCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 * <p>For stickers (B2.1), checks the {@link StickerCache} first.
 * On a cache miss, returns a placeholder description (vision analysis
 * would be done by the backend).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboundMediaHandler {

    private final MediaDownloader mediaDownloader;
    private final StickerCache stickerCache;
    private final AgentBackendClient backendClient;

    @Value("${agent.transcription.enabled:false}")
    private boolean transcriptionEnabled;

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

        // For voice messages with transcription enabled, transcribe and return the text
        if ("voice".equals(fileType) && transcriptionEnabled) {
            Optional<String> transcribed = transcribeVoice(fileId);
            if (transcribed.isPresent()) {
                log.debug("Voice message transcribed: {} chars", transcribed.get().length());
                return transcribed;
            }
            // Fallback to placeholder if transcription fails
        }

        // Attempt to download the media (result is cached)
        Optional<byte[]> downloaded = mediaDownloader.downloadToFileId(fileId);
        int sizeBytes = downloaded.map(bytes -> bytes.length).orElse(0);

        String description = describe(event, fileType, fileId, sizeBytes);
        log.debug("Handled media: type={}, fileId={}, size={}bytes", fileType, fileId, sizeBytes);
        return Optional.of(description);
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

    private String describe(UpdateEvent event, String fileType, String fileId, int sizeBytes) {
        String caption = event.caption();
        String captionPart = (caption != null && !caption.isBlank())
            ? ", caption=\"" + caption + "\""
            : "";

        return switch (fileType) {
            case "photo" -> "[Photo received, file_id=" + fileId + ", size=" + sizeBytes + " bytes" + captionPart + "]";
            case "document" -> "[Document: file_id=" + fileId + ", size=" + sizeBytes + " bytes" + captionPart + "]";
            case "voice" -> "[Voice message received, file_id=" + fileId + ", size=" + sizeBytes + " bytes" + captionPart + "]";
            case "sticker" -> {
                // B2.1: Check sticker cache first
                String fileUniqueId = event.fileId(); // Using fileId as fileUniqueId proxy
                Optional<String> cached = stickerCache.get(fileUniqueId);
                if (cached.isPresent()) {
                    yield "[Sticker: " + cached.get() + "]";
                }
                // Cache miss — return placeholder (vision analysis would be done by backend)
                yield "[Sticker received, file_id=" + fileId + "]";
            }
            case "animation" -> "[Animation/GIF received, file_id=" + fileId + "]";
            default -> "[Media received: type=" + fileType + ", file_id=" + fileId + "]";
        };
    }
}