package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TelegramAdapterExtraTest {

    private AgentProperties props;
    private TelegramBotApiClient botApiClient;
    private TelegramAdapter adapter;
    private SessionSource source;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        botApiClient = mock(TelegramBotApiClient.class);
        adapter = new TelegramAdapter(props, botApiClient);
        source = new SessionSource(Platform.TELEGRAM, "12345", "u", "u", "U");
    }

    @Test
    @DisplayName("disconnect sets connected false")
    void disconnectSetsConnectedFalse() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        assertThat(adapter.isConnected()).isTrue();
        adapter.disconnect().get();
        assertThat(adapter.isConnected()).isFalse();
    }

    @Test
    @DisplayName("sendImage returns success when bot API returns message_id")
    void sendImageReturnsSuccess() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        when(botApiClient.sendPhoto(12345L, new byte[]{1, 2}, "caption")).thenReturn(Optional.of("99"));

        SendResult result = adapter.sendImage(source, new byte[]{1, 2}, "caption").get();
        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("99");
    }

    @Test
    @DisplayName("sendImage returns failure when bot API returns empty")
    void sendImageReturnsFailureWhenEmpty() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        when(botApiClient.sendPhoto(anyLong(), any(), any())).thenReturn(Optional.empty());

        SendResult result = adapter.sendImage(source, new byte[]{1}, "cap").get();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("sendPhoto failed");
    }

    @Test
    @DisplayName("sendImage returns failure when not connected")
    void sendImageFailsWhenNotConnected() throws Exception {
        SendResult result = adapter.sendImage(source, new byte[]{1}, "cap").get();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Not connected");
    }

    @Test
    @DisplayName("sendImage returns failure when chatId is 0")
    void sendImageFailsWhenChatIdZero() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        SessionSource badSource = new SessionSource(Platform.TELEGRAM, null, "u", "u", "U");

        SendResult result = adapter.sendImage(badSource, new byte[]{1}, "cap").get();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No chat_id");
    }

    @Test
    @DisplayName("sendDocument returns success when bot API returns message_id")
    void sendDocumentReturnsSuccess() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        when(botApiClient.sendDocument(12345L, new byte[]{4, 5}, "file.pdf", "doc")).thenReturn(Optional.of("200"));

        SendResult result = adapter.sendDocument(source, new byte[]{4, 5}, "file.pdf", "doc").get();
        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("200");
    }

    @Test
    @DisplayName("sendDocument returns failure when bot API returns empty")
    void sendDocumentReturnsFailureWhenEmpty() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        when(botApiClient.sendDocument(anyLong(), any(), any(), any())).thenReturn(Optional.empty());

        SendResult result = adapter.sendDocument(source, new byte[]{1}, "f", "c").get();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("sendDocument failed");
    }

    @Test
    @DisplayName("sendDocument returns failure when not connected")
    void sendDocumentFailsWhenNotConnected() throws Exception {
        SendResult result = adapter.sendDocument(source, new byte[]{1}, "f", "c").get();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Not connected");
    }

    @Test
    @DisplayName("sendDocument returns failure when chatId is 0")
    void sendDocumentFailsWhenChatIdZero() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        SessionSource badSource = new SessionSource(Platform.TELEGRAM, null, "u", "u", "U");

        SendResult result = adapter.sendDocument(badSource, new byte[]{1}, "f", "c").get();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No chat_id");
    }

    @Test
    @DisplayName("addReaction returns success when bot API accepts reaction")
    void addReactionReturnsSuccess() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        when(botApiClient.setMessageReaction(12345L, 99L, "👍")).thenReturn(true);

        SendResult result = adapter.addReaction(source, "👍", "99").get();

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("99");
        verify(botApiClient).setMessageReaction(12345L, 99L, "👍");
    }

    @Test
    @DisplayName("removeReaction clears Telegram reaction")
    void removeReactionClearsReaction() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        when(botApiClient.setMessageReaction(12345L, 99L, "")).thenReturn(true);

        SendResult result = adapter.removeReaction(source, "99").get();

        assertThat(result.success()).isTrue();
        verify(botApiClient).setMessageReaction(12345L, 99L, "");
    }

    @Test
    @DisplayName("addReaction fails closed for invalid target ids")
    void addReactionFailsClosedForInvalidTargetIds() throws Exception {
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();

        SendResult badChat = adapter.addReaction(new SessionSource(Platform.TELEGRAM, "bad", "u", "u", "U"), "👍", "99").get();
        SendResult badMessage = adapter.addReaction(source, "👍", "bad").get();

        assertThat(badChat.success()).isFalse();
        assertThat(badChat.error()).contains("No chat_id");
        assertThat(badMessage.success()).isFalse();
        assertThat(badMessage.error()).contains("No message_id");
        verify(botApiClient, never()).setMessageReaction(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("buildSource returns input")
    void buildSourceReturnsInput() {
        assertThat(adapter.buildSource(Map.of("x", 1))).hasValue(Map.of("x", 1));
    }
}
