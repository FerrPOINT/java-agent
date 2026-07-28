package com.azhukov.agent.bot.group;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.client.TelegramResponse;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * B3.1: Tests for DmTopicManager — caching behavior, getCachedTopicId, clearCache, cacheSize.
 */
class DmTopicManagerTest {

    private BotProperties properties;
    private TelegramClient telegramClient;
    private DmTopicManager topicManager;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        telegramClient = mock(TelegramClient.class);
        topicManager = new DmTopicManager(properties, telegramClient);
    }

    /**
     * Helper to create a TelegramResponse with a message_thread_id result.
     */
    private TelegramResponse createTopicResponse(long threadId) {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("message_thread_id", threadId);
        return new TelegramResponse(true, null, null, resultMap);
    }

    @Test
    void ensureTopic_cachesResults_secondCallDoesNotHitApi() {
        // Arrange: mock callApi to return a response with message_thread_id
        when(telegramClient.callApi(anyString(), anyMap()))
            .thenReturn(Optional.of(createTopicResponse(42L)));

        // Act: first call should hit the API
        Optional<Long> firstResult = topicManager.ensureTopic(-100123L, "general");
        assertThat(firstResult).isPresent();
        assertThat(firstResult.get()).isEqualTo(42L);

        // Act: second call should return cached result
        Optional<Long> secondResult = topicManager.ensureTopic(-100123L, "general");
        assertThat(secondResult).isPresent();
        assertThat(secondResult.get()).isEqualTo(42L);

        // Verify: callApi was invoked only once (cached on second call)
        verify(telegramClient, times(1)).callApi(anyString(), anyMap());
    }

    @Test
    void ensureTopic_differentTopicNames_cachedSeparately() {
        when(telegramClient.callApi(anyString(), anyMap()))
            .thenReturn(Optional.of(createTopicResponse(10L)))
            .thenReturn(Optional.of(createTopicResponse(20L)));

        Optional<Long> first = topicManager.ensureTopic(-100123L, "topic-a");
        Optional<Long> second = topicManager.ensureTopic(-100123L, "topic-b");

        assertThat(first).isPresent();
        assertThat(first.get()).isEqualTo(10L);
        assertThat(second).isPresent();
        assertThat(second.get()).isEqualTo(20L);

        // Both calls hit the API because different topic names
        verify(telegramClient, times(2)).callApi(anyString(), anyMap());
    }

    @Test
    void ensureTopic_nullTopicName_returnsEmpty() {
        Optional<Long> result = topicManager.ensureTopic(-100123L, null);
        assertThat(result).isEmpty();
        verifyNoInteractions(telegramClient);
    }

    @Test
    void ensureTopic_blankTopicName_returnsEmpty() {
        Optional<Long> result = topicManager.ensureTopic(-100123L, "  ");
        assertThat(result).isEmpty();
        verifyNoInteractions(telegramClient);
    }

    @Test
    void ensureTopic_apiReturnsEmptyResult_returnsEmptyAndDoesNotCache() {
        when(telegramClient.callApi(anyString(), anyMap()))
            .thenReturn(Optional.empty());

        Optional<Long> result = topicManager.ensureTopic(-100123L, "failed-topic");
        assertThat(result).isEmpty();
        assertThat(topicManager.cacheSize()).isZero();

        // Second call should still hit the API since nothing was cached
        topicManager.ensureTopic(-100123L, "failed-topic");
        verify(telegramClient, times(2)).callApi(anyString(), anyMap());
    }

    @Test
    void getCachedTopicId_returnsEmptyWhenNotCached() {
        Optional<Long> cached = topicManager.getCachedTopicId(-100123L, "nonexistent");
        assertThat(cached).isEmpty();
    }

    @Test
    void getCachedTopicId_returnsCachedValueAfterEnsureTopic() {
        when(telegramClient.callApi(anyString(), anyMap()))
            .thenReturn(Optional.of(createTopicResponse(99L)));

        topicManager.ensureTopic(-100456L, "cached-topic");

        Optional<Long> cached = topicManager.getCachedTopicId(-100456L, "cached-topic");
        assertThat(cached).isPresent();
        assertThat(cached.get()).isEqualTo(99L);
    }

    @Test
    void getCachedTopicId_nullTopicName_returnsEmpty() {
        Optional<Long> cached = topicManager.getCachedTopicId(-100123L, null);
        assertThat(cached).isEmpty();
    }

    @Test
    void getCachedTopicId_blankTopicName_returnsEmpty() {
        Optional<Long> cached = topicManager.getCachedTopicId(-100123L, "");
        assertThat(cached).isEmpty();
    }

    @Test
    void clearCache_removesAllCachedEntries() {
        when(telegramClient.callApi(anyString(), anyMap()))
            .thenReturn(Optional.of(createTopicResponse(1L)))
            .thenReturn(Optional.of(createTopicResponse(2L)));

        topicManager.ensureTopic(-1001L, "topic-1");
        topicManager.ensureTopic(-1002L, "topic-2");
        assertThat(topicManager.cacheSize()).isEqualTo(2);

        topicManager.clearCache();

        assertThat(topicManager.cacheSize()).isZero();
    }

    @Test
    void clearCache_subsequentEnsureTopicHitsApiAgain() {
        when(telegramClient.callApi(anyString(), anyMap()))
            .thenReturn(Optional.of(createTopicResponse(55L)));

        // First call caches
        topicManager.ensureTopic(-100789L, "recycled-topic");
        verify(telegramClient, times(1)).callApi(anyString(), anyMap());

        // Clear cache
        topicManager.clearCache();
        assertThat(topicManager.cacheSize()).isZero();

        // Second call should hit API again
        Optional<Long> result = topicManager.ensureTopic(-100789L, "recycled-topic");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(55L);
        verify(telegramClient, times(2)).callApi(anyString(), anyMap());
    }

    @Test
    void cacheSize_returnsCorrectCount() {
        assertThat(topicManager.cacheSize()).isZero();

        when(telegramClient.callApi(anyString(), anyMap()))
            .thenReturn(Optional.of(createTopicResponse(1L)))
            .thenReturn(Optional.of(createTopicResponse(2L)))
            .thenReturn(Optional.of(createTopicResponse(3L)));

        topicManager.ensureTopic(-1001L, "a");
        assertThat(topicManager.cacheSize()).isEqualTo(1);

        topicManager.ensureTopic(-1002L, "b");
        assertThat(topicManager.cacheSize()).isEqualTo(2);

        topicManager.ensureTopic(-1003L, "c");
        assertThat(topicManager.cacheSize()).isEqualTo(3);

        // Duplicate call (same chat+topic) should not increase cache size
        topicManager.ensureTopic(-1001L, "a");
        assertThat(topicManager.cacheSize()).isEqualTo(3);
    }
}