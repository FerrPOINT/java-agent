package com.azhukov.agent.gateway;

import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface BasePlatformAdapter {

    Platform platform();

    CompletableFuture<Boolean> connect(PlatformConfig config);

    CompletableFuture<Boolean> disconnect();

    CompletableFuture<SendResult> send(SessionSource target, String text);

    CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption);

    CompletableFuture<SendResult> sendDocument(SessionSource target, byte[] document, String fileName, String caption);

    CompletableFuture<SendResult> sendTyping(SessionSource target);

    void setMessageHandler(Consumer<MessageEvent> handler);

    Optional<Map<String, Object>> buildSource(Map<String, Object> raw);
}
