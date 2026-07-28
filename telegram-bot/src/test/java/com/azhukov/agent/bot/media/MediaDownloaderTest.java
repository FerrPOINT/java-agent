package com.azhukov.agent.bot.media;

import com.azhukov.agent.bot.client.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MediaDownloaderTest {

    private TelegramClient client;
    private MediaCache cache;
    private MediaDownloader downloader;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        cache = new MediaCache(java.time.Duration.ofHours(24));
        downloader = new MediaDownloader(client, cache);
    }

    @Test
    void downloadToFileId_success_returnsBytes() {
        String fileId = "file-123";
        when(client.getFile(fileId))
            .thenReturn(Optional.of(Map.of("file_id", fileId, "file_path", "photos/file_1.jpg")));
        byte[] fileData = "image-data".getBytes();
        when(client.downloadFile("photos/file_1.jpg"))
            .thenReturn(Optional.of(fileData));

        Optional<byte[]> result = downloader.downloadToFileId(fileId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(fileData);
        verify(client).getFile(fileId);
        verify(client).downloadFile("photos/file_1.jpg");
    }

    @Test
    void downloadToFileId_cacheHit_skipsApiCalls() {
        String fileId = "file-123";
        byte[] cached = "cached-data".getBytes();
        cache.put(fileId, cached);

        Optional<byte[]> result = downloader.downloadToFileId(fileId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(cached);
        verify(client, never()).getFile(anyString());
        verify(client, never()).downloadFile(anyString());
    }

    @Test
    void downloadToFileId_getFileFails_returnsEmpty() {
        String fileId = "file-123";
        when(client.getFile(fileId)).thenReturn(Optional.empty());

        Optional<byte[]> result = downloader.downloadToFileId(fileId);

        assertThat(result).isEmpty();
        verify(client, never()).downloadFile(anyString());
    }

    @Test
    void downloadToFileId_noFilePath_returnsEmpty() {
        String fileId = "file-123";
        when(client.getFile(fileId))
            .thenReturn(Optional.of(Map.of("file_id", fileId))); // no file_path

        Optional<byte[]> result = downloader.downloadToFileId(fileId);

        assertThat(result).isEmpty();
        verify(client, never()).downloadFile(anyString());
    }

    @Test
    void downloadToFileId_downloadFails_returnsEmpty() {
        String fileId = "file-123";
        when(client.getFile(fileId))
            .thenReturn(Optional.of(Map.of("file_id", fileId, "file_path", "docs/file.pdf")));
        when(client.downloadFile("docs/file.pdf")).thenReturn(Optional.empty());

        Optional<byte[]> result = downloader.downloadToFileId(fileId);

        assertThat(result).isEmpty();
    }

    @Test
    void downloadToFileId_nullFileId_returnsEmpty() {
        Optional<byte[]> result = downloader.downloadToFileId(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void downloadToFileId_blankFileId_returnsEmpty() {
        Optional<byte[]> result = downloader.downloadToFileId("");

        assertThat(result).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void downloadToFileId_cachesResultForSubsequentCalls() {
        String fileId = "file-123";
        when(client.getFile(fileId))
            .thenReturn(Optional.of(Map.of("file_id", fileId, "file_path", "photos/file_1.jpg")));
        when(client.downloadFile("photos/file_1.jpg"))
            .thenReturn(Optional.of("image-data".getBytes()));

        // First call — downloads
        Optional<byte[]> first = downloader.downloadToFileId(fileId);
        assertThat(first).isPresent();

        // Second call — should use cache
        Optional<byte[]> second = downloader.downloadToFileId(fileId);
        assertThat(second).isPresent();
        assertThat(second.get()).isEqualTo(first.get());

        // getFile should only be called once
        verify(client, times(1)).getFile(fileId);
        verify(client, times(1)).downloadFile("photos/file_1.jpg");
    }
}