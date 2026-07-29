package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class TelegramAdapter implements BasePlatformAdapter {

    private final AgentProperties properties;
    private final TelegramBotApiClient botApiClient;
    private Consumer<MessageEvent> messageHandler;
    private volatile boolean connected;

    @Override
    public Platform platform() {
        return Platform.TELEGRAM;
    }

    @Override
    public CompletableFuture<Boolean> connect(PlatformConfig config) {
        this.connected = true;
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> disconnect() {
        this.connected = false;
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<SendResult> send(SessionSource target, String text) {
        if (!connected) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "Not connected"));
        }
        long chatId = extractChatId(target);
        if (chatId == 0L) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "No chat_id in target"));
        }
        return CompletableFuture.supplyAsync(() -> {
            var messageId = botApiClient.sendMessage(chatId, text);
            return messageId
                .map(id -> new SendResult(true, id, null))
                .orElseGet(() -> new SendResult(false, null, "Telegram sendMessage failed"));
        });
    }

    @Override
    public CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption) {
        if (!connected) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "Not connected"));
        }
        long chatId = extractChatId(target);
        if (chatId == 0L) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "No chat_id in target"));
        }
        return CompletableFuture.supplyAsync(() -> {
            var messageId = botApiClient.sendPhoto(chatId, image, caption);
            return messageId
                .map(id -> new SendResult(true, id, null))
                .orElseGet(() -> new SendResult(false, null, "Telegram sendPhoto failed"));
        });
    }

    @Override
    public CompletableFuture<SendResult> sendDocument(SessionSource target, byte[] document, String fileName, String caption) {
        if (!connected) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "Not connected"));
        }
        long chatId = extractChatId(target);
        if (chatId == 0L) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "No chat_id in target"));
        }
        return CompletableFuture.supplyAsync(() -> {
            var messageId = botApiClient.sendDocument(chatId, document, fileName, caption);
            return messageId
                .map(id -> new SendResult(true, id, null))
                .orElseGet(() -> new SendResult(false, null, "Telegram sendDocument failed"));
        });
    }

    @Override
    public CompletableFuture<SendResult> sendTyping(SessionSource target) {
        long chatId = extractChatId(target);
        if (chatId == 0L) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "No chat_id in target"));
        }
        return CompletableFuture.supplyAsync(() ->
            botApiClient.sendChatAction(chatId, "typing")
                ? new SendResult(true, null, null)
                : new SendResult(false, null, "sendChatAction failed")
        );
    }

    private long extractChatId(SessionSource target) {
        if (target == null || target.chatId() == null) return 0L;
        try {
            return Long.parseLong(target.chatId());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public void setMessageHandler(Consumer<MessageEvent> handler) {
        this.messageHandler = handler;
    }

    @Override
    public Optional<Map<String, Object>> buildSource(Map<String, Object> raw) {
        return Optional.of(raw);
    }

    public boolean isConnected() {
        return connected;
    }
}
