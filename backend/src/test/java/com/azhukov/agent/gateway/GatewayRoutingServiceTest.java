package com.azhukov.agent.gateway;

import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutingServiceTest {

    @Test
    void sendsViaRegisteredAdapter() {
        BasePlatformAdapter stub = new BasePlatformAdapter() {
            @Override public Platform platform() { return Platform.TELEGRAM; }
            @Override public CompletableFuture<Boolean> connect(PlatformConfig config) { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<Boolean> disconnect() { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<SendResult> send(SessionSource target, String text) { return CompletableFuture.completedFuture(new SendResult(true, "ok", null)); }
            @Override public CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendDocument(SessionSource target, byte[] document, String fileName, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendTyping(SessionSource target) { return null; }
            @Override public void setMessageHandler(Consumer<MessageEvent> handler) { }
            @Override public java.util.Optional<Map<String, Object>> buildSource(Map<String, Object> raw) { return java.util.Optional.of(raw); }
        };

        GatewayRoutingService service = new GatewayRoutingService(java.util.List.of(stub), e -> { });
        SendResult result = service.send(Platform.TELEGRAM, new SessionSource(Platform.TELEGRAM, "1", null, null, null), "hi").join();
        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("ok");
    }

    @Test
    void unknownPlatformReturnsFailure() {
        GatewayRoutingService service = new GatewayRoutingService(java.util.List.of(), e -> { });
        SendResult result = service.send(Platform.TELEGRAM, new SessionSource(Platform.TELEGRAM, "1", null, null, null), "hi").join();
        assertThat(result.success()).isFalse();
    }
}
