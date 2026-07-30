package com.azhukov.agent.bot.group;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.client.TelegramResponse;
import com.azhukov.agent.bot.config.BotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DmTopicManagerTest {

    private TelegramClient client;
    private BotProperties properties;
    private DmTopicManager manager;

    @TempDir
    Path tempDir;

    private Path configFile;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        properties = new BotProperties();
        configFile = tempDir.resolve("bot-config.json");
        manager = new DmTopicManager(properties, client, configFile,
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));
    }

    @Test
    void ensureTopic_blankName_returnsEmpty() {
        Optional<Long> result = manager.ensureTopic(123L, "");
        assertThat(result).isEmpty();
    }

    @Test
    void ensureTopic_nullName_returnsEmpty() {
        Optional<Long> result = manager.ensureTopic(123L, null);
        assertThat(result).isEmpty();
    }

    @Test
    void ensureTopic_cached_returnsFromCache() {
        // First call creates the topic
        Map<String, Object> createResult = new LinkedHashMap<>();
        createResult.put("message_thread_id", 100L);
        TelegramResponse createResponse = mock(TelegramResponse.class);
        when(createResponse.isSuccess()).thenReturn(true);
        when(createResponse.result()).thenReturn(createResult);
        when(client.callApi(eq("createForumTopic"), any())).thenReturn(Optional.of(createResponse));

        // Mock seed message
        TelegramResponse seedResponse = mock(TelegramResponse.class);
        when(seedResponse.isSuccess()).thenReturn(true);
        when(client.callApi(eq("sendMessage"), any())).thenReturn(Optional.of(seedResponse));

        Optional<Long> first = manager.ensureTopic(123L, "General");
        assertThat(first).contains(100L);

        // Second call should return from cache — no API call
        Optional<Long> second = manager.ensureTopic(123L, "General");
        assertThat(second).contains(100L);

        // createForumTopic should only be called once
        verify(client, times(1)).callApi(eq("createForumTopic"), any());
    }

    @Test
    void ensureTopic_loadsFromConfigPersistedThreadId() {
        // Configure a topic with persisted thread_id
        BotProperties.DmTopic dmTopic = new BotProperties.DmTopic();
        dmTopic.setChatId("123");
        dmTopic.setTopicName("General");
        dmTopic.setThreadId(200L);
        properties.getGroup().getDmTopics().add(dmTopic);

        Optional<Long> result = manager.ensureTopic(123L, "General");
        assertThat(result).contains(200L);
        // Should not call createForumTopic
        verify(client, never()).callApi(eq("createForumTopic"), any());
    }

    @Test
    void ensureTopic_createsViaApiAndPersists() throws Exception {
        // No persisted thread_id in config
        BotProperties.DmTopic dmTopic = new BotProperties.DmTopic();
        dmTopic.setChatId("123");
        dmTopic.setTopicName("News");
        properties.getGroup().getDmTopics().add(dmTopic);

        Map<String, Object> createResult = new LinkedHashMap<>();
        createResult.put("message_thread_id", 300L);
        TelegramResponse createResponse = mock(TelegramResponse.class);
        when(createResponse.isSuccess()).thenReturn(true);
        when(createResponse.result()).thenReturn(createResult);
        when(client.callApi(eq("createForumTopic"), any())).thenReturn(Optional.of(createResponse));

        TelegramResponse seedResponse = mock(TelegramResponse.class);
        when(seedResponse.isSuccess()).thenReturn(true);
        when(client.callApi(eq("sendMessage"), any())).thenReturn(Optional.of(seedResponse));

        Optional<Long> result = manager.ensureTopic(123L, "News");
        assertThat(result).contains(300L);

        // Verify the thread_id was persisted to config file
        assertThat(Files.exists(configFile)).isTrue();
        String configContent = Files.readString(configFile);
        assertThat(configContent).contains("300");
    }

    @Test
    void getCachedTopicId_returnsEmptyWhenNotCached() {
        Optional<Long> result = manager.getCachedTopicId(123L, "General");
        assertThat(result).isEmpty();
    }

    @Test
    void getCachedTopicId_returnsCachedValue() {
        // Create topic to cache it
        Map<String, Object> createResult = new LinkedHashMap<>();
        createResult.put("message_thread_id", 500L);
        TelegramResponse createResponse = mock(TelegramResponse.class);
        when(createResponse.isSuccess()).thenReturn(true);
        when(createResponse.result()).thenReturn(createResult);
        when(client.callApi(eq("createForumTopic"), any())).thenReturn(Optional.of(createResponse));

        TelegramResponse seedResponse = mock(TelegramResponse.class);
        when(seedResponse.isSuccess()).thenReturn(true);
        when(client.callApi(eq("sendMessage"), any())).thenReturn(Optional.of(seedResponse));

        manager.ensureTopic(999L, "TestTopic");

        Optional<Long> cached = manager.getCachedTopicId(999L, "TestTopic");
        assertThat(cached).contains(500L);
    }

    @Test
    void renameDmTopic_callsEditForumTopic() {
        TelegramResponse response = mock(TelegramResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(client.callApi(eq("editForumTopic"), any())).thenReturn(Optional.of(response));

        boolean result = manager.renameDmTopic(123L, 100L, "New Name");
        assertThat(result).isTrue();
        verify(client).callApi(eq("editForumTopic"), argThat(params -> {
            return params.get("chat_id").equals(123L)
                && params.get("message_thread_id").equals(100L)
                && params.get("name").equals("New Name");
        }));
    }

    @Test
    void renameDmTopic_blankName_returnsFalse() {
        boolean result = manager.renameDmTopic(123L, 100L, "");
        assertThat(result).isFalse();
    }

    @Test
    void renameDmTopic_apiFailure_returnsFalse() {
        TelegramResponse response = mock(TelegramResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(client.callApi(eq("editForumTopic"), any())).thenReturn(Optional.of(response));

        boolean result = manager.renameDmTopic(123L, 100L, "New Name");
        assertThat(result).isFalse();
    }

    @Test
    void sendWithDmTopicReplyAnchorRetry_successOnFirstTry() {
        Map<String, Object> sendResult = new LinkedHashMap<>();
        sendResult.put("message_id", 42L);
        TelegramResponse sendResponse = mock(TelegramResponse.class);
        when(sendResponse.isSuccess()).thenReturn(true);
        when(sendResponse.result()).thenReturn(sendResult);
        when(sendResponse.resultMessageIdAsLong()).thenReturn(42L);
        when(client.callApi(eq("sendMessage"), any())).thenReturn(Optional.of(sendResponse));

        Optional<Long> result = manager.sendWithDmTopicReplyAnchorRetry(
            123L, "Hello", "MarkdownV2", 55L, 77L, null);
        assertThat(result).contains(42L);
    }

    @Test
    void sendWithDmTopicReplyAnchorRetry_retriesWithoutReplyAnchor() {
        // First call (with reply anchor) returns empty
        when(client.callApi(eq("sendMessage"), any()))
            .thenReturn(Optional.empty());

        // Second call (without reply anchor) succeeds
        Map<String, Object> sendResult = new LinkedHashMap<>();
        sendResult.put("message_id", 99L);
        TelegramResponse sendResponse = mock(TelegramResponse.class);
        when(sendResponse.isSuccess()).thenReturn(true);
        when(sendResponse.result()).thenReturn(sendResult);
        when(sendResponse.resultMessageIdAsLong()).thenReturn(99L);

        // Reconfigure mock: first call fails, second succeeds
        when(client.callApi(eq("sendMessage"), any()))
            .thenReturn(Optional.empty())  // First attempt
            .thenReturn(Optional.empty())  // Second attempt (without reply)
            .thenReturn(Optional.of(sendResponse));  // Third attempt (without thread)

        Optional<Long> result = manager.sendWithDmTopicReplyAnchorRetry(
            123L, "Hello", "MarkdownV2", 55L, 77L, null);
        assertThat(result).contains(99L);
    }

    @Test
    void clearCache_removesAllEntries() {
        // Create a topic to cache it
        Map<String, Object> createResult = new LinkedHashMap<>();
        createResult.put("message_thread_id", 700L);
        TelegramResponse createResponse = mock(TelegramResponse.class);
        when(createResponse.isSuccess()).thenReturn(true);
        when(createResponse.result()).thenReturn(createResult);
        when(client.callApi(eq("createForumTopic"), any())).thenReturn(Optional.of(createResponse));

        TelegramResponse seedResponse = mock(TelegramResponse.class);
        when(seedResponse.isSuccess()).thenReturn(true);
        when(client.callApi(eq("sendMessage"), any())).thenReturn(Optional.of(seedResponse));

        manager.ensureTopic(123L, "Topic1");
        assertThat(manager.cacheSize()).isEqualTo(1);

        manager.clearCache();
        assertThat(manager.cacheSize()).isZero();
    }

    @Test
    void initializeConfiguredTopics_loadsPersistedThreadIds() {
        BotProperties.DmTopic dmTopic = new BotProperties.DmTopic();
        dmTopic.setChatId("123");
        dmTopic.setTopicName("Persisted");
        dmTopic.setThreadId(800L);
        properties.getGroup().getDmTopics().add(dmTopic);

        manager.initializeConfiguredTopics();

        Optional<Long> cached = manager.getCachedTopicId(123L, "Persisted");
        assertThat(cached).contains(800L);
        // Should not call createForumTopic
        verify(client, never()).callApi(eq("createForumTopic"), any());
    }
}