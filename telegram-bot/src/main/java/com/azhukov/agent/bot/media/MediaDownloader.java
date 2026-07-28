package com.azhukov.agent.bot.media;

import com.azhukov.agent.bot.client.TelegramClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Downloads media files from Telegram by file_id.
 * Uses {@link TelegramClient#getFile(String)} to obtain the file_path,
 * then {@link TelegramClient#downloadFile(String)} to fetch the bytes.
 * Results are cached in {@link MediaCache}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaDownloader {

    private final TelegramClient telegramClient;
    private final MediaCache cache;

    /**
     * Downloads a file by its Telegram file_id. Returns cached data if available.
     *
     * @param fileId Telegram file_id
     * @return Optional with file bytes, or empty if download failed
     */
    public Optional<byte[]> downloadToFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }

        // Check cache first
        Optional<byte[]> cached = cache.get(fileId);
        if (cached.isPresent()) {
            log.debug("Media cache hit for fileId={}", fileId);
            return cached;
        }

        // Step 1: get file metadata (need file_path)
        Optional<Map<String, Object>> fileInfo = telegramClient.getFile(fileId);
        if (fileInfo.isEmpty()) {
            log.warn("getFile failed for fileId={}", fileId);
            return Optional.empty();
        }

        String filePath = null;
        Object pathObj = fileInfo.get().get("file_path");
        if (pathObj != null) {
            filePath = pathObj.toString();
        }
        if (filePath == null || filePath.isBlank()) {
            log.warn("No file_path in getFile response for fileId={}", fileId);
            return Optional.empty();
        }

        // Step 2: download the actual file
        Optional<byte[]> data = telegramClient.downloadFile(filePath);
        if (data.isEmpty()) {
            log.warn("downloadFile failed for filePath={}", filePath);
            return Optional.empty();
        }

        // Cache the result
        cache.put(fileId, data.get());
        log.debug("Downloaded and cached media for fileId={} ({} bytes)", fileId, data.get().length);
        return data;
    }
}