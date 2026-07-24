package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Component
public class TelegramAdapter implements BasePlatformAdapter {

    private final AgentProperties properties;
    private Consumer<MessageEvent> messageHandler;
    private volatile boolean connected;

    public TelegramAdapter(AgentProperties properties) {
        this.properties = properties;
    }

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
        // TODO actual Telegram HTTP call (Phase F6)
        return CompletableFuture.completedFuture(new SendResult(true, "stub", null));
    }

    @Override
    public CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption) {
        return CompletableFuture.completedFuture(new SendResult(true, "stub-image", null));
    }

    @Override
    public CompletableFuture<SendResult> sendDocument(SessionSource target, byte[] document, String fileName, String caption) {
        return CompletableFuture.completedFuture(new SendResult(true, "stub-doc", null));
    }

    @Override
    public CompletableFuture<SendResult> sendTyping(SessionSource target) {
        return CompletableFuture.completedFuture(new SendResult(true, null, null));
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
