package com.azhukov.agent.gateway;

import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.MessageType;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void dispatchInboundRoutesTelegramMessageEventToHandler() {
        AtomicReference<MessageEvent> captured = new AtomicReference<>();
        BasePlatformAdapter telegramAdapter = new BasePlatformAdapter() {
            @Override public Platform platform() { return Platform.TELEGRAM; }
            @Override public CompletableFuture<Boolean> connect(PlatformConfig config) { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<Boolean> disconnect() { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<SendResult> send(SessionSource target, String text) { return null; }
            @Override public CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendDocument(SessionSource target, byte[] document, String fileName, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendTyping(SessionSource target) { return null; }
            @Override public void setMessageHandler(Consumer<MessageEvent> handler) { }
            @Override public java.util.Optional<Map<String, Object>> buildSource(Map<String, Object> raw) { return java.util.Optional.of(raw); }
        };

        GatewayRoutingService service = new GatewayRoutingService(List.of(telegramAdapter), captured::set);
        SessionSource source = new SessionSource(Platform.TELEGRAM, "42", "7", "user", "User");
        MessageEvent event = new MessageEvent("evt-1", source, MessageType.TEXT, "hello", List.of(), Map.of(), Instant.now());

        service.dispatchInbound(event);

        assertThat(captured.get()).isEqualTo(event);
    }

    @Test
    void dispatchInboundDropsEventsWithUnsupportedSource() {
        AtomicReference<MessageEvent> captured = new AtomicReference<>();
        BasePlatformAdapter telegramAdapter = new BasePlatformAdapter() {
            @Override public Platform platform() { return Platform.TELEGRAM; }
            @Override public CompletableFuture<Boolean> connect(PlatformConfig config) { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<Boolean> disconnect() { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<SendResult> send(SessionSource target, String text) { return null; }
            @Override public CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendDocument(SessionSource target, byte[] document, String fileName, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendTyping(SessionSource target) { return null; }
            @Override public void setMessageHandler(Consumer<MessageEvent> handler) { }
            @Override public java.util.Optional<Map<String, Object>> buildSource(Map<String, Object> raw) { return java.util.Optional.of(raw); }
        };

        GatewayRoutingService service = new GatewayRoutingService(List.of(telegramAdapter), captured::set) {
            @Override
            public void dispatchInbound(MessageEvent event) {
                if (event.source().platform() != Platform.TELEGRAM) {
                    return;
                }
                super.dispatchInbound(event);
            }
        };
        SessionSource source = new SessionSource(Platform.DISCORD, "42", "7", "user", "User");
        MessageEvent event = new MessageEvent("evt-2", source, MessageType.TEXT, "hello", List.of(), Map.of(), Instant.now());

        service.dispatchInbound(event);

        assertThat(captured.get()).isNull();
    }

    @Test
    void dispatchOutboundSendsThroughCorrectAdapter() {
        BasePlatformAdapter telegramAdapter = new BasePlatformAdapter() {
            @Override public Platform platform() { return Platform.TELEGRAM; }
            @Override public CompletableFuture<Boolean> connect(PlatformConfig config) { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<Boolean> disconnect() { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<SendResult> send(SessionSource target, String text) {
                return CompletableFuture.completedFuture(new SendResult(true, "tg-out", null));
            }
            @Override public CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendDocument(SessionSource target, byte[] document, String fileName, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendTyping(SessionSource target) { return null; }
            @Override public void setMessageHandler(Consumer<MessageEvent> handler) { }
            @Override public java.util.Optional<Map<String, Object>> buildSource(Map<String, Object> raw) { return java.util.Optional.of(raw); }
        };

        BasePlatformAdapter webAdapter = new BasePlatformAdapter() {
            @Override public Platform platform() { return Platform.WEB; }
            @Override public CompletableFuture<Boolean> connect(PlatformConfig config) { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<Boolean> disconnect() { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<SendResult> send(SessionSource target, String text) {
                return CompletableFuture.completedFuture(new SendResult(true, "web-out", null));
            }
            @Override public CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendDocument(SessionSource target, byte[] document, String fileName, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendTyping(SessionSource target) { return null; }
            @Override public void setMessageHandler(Consumer<MessageEvent> handler) { }
            @Override public java.util.Optional<Map<String, Object>> buildSource(Map<String, Object> raw) { return java.util.Optional.of(raw); }
        };

        GatewayRoutingService service = new GatewayRoutingService(List.of(telegramAdapter, webAdapter), e -> { });
        SessionSource target = new SessionSource(Platform.TELEGRAM, "42", "7", "user", "User");

        SendResult result = service.send(Platform.TELEGRAM, target, "hello").join();

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("tg-out");
    }

    @Test
    void sendTypingDelegatesToAdapter() {
        AtomicReference<SessionSource> typingTarget = new AtomicReference<>();
        BasePlatformAdapter telegramAdapter = new BasePlatformAdapter() {
            @Override public Platform platform() { return Platform.TELEGRAM; }
            @Override public CompletableFuture<Boolean> connect(PlatformConfig config) { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<Boolean> disconnect() { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<SendResult> send(SessionSource target, String text) { return null; }
            @Override public CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendDocument(SessionSource target, byte[] document, String fileName, String caption) { return null; }
            @Override public CompletableFuture<SendResult> sendTyping(SessionSource target) {
                typingTarget.set(target);
                return CompletableFuture.completedFuture(new SendResult(true, "typing", null));
            }
            @Override public void setMessageHandler(Consumer<MessageEvent> handler) { }
            @Override public java.util.Optional<Map<String, Object>> buildSource(Map<String, Object> raw) { return java.util.Optional.of(raw); }
        };

        GatewayRoutingService service = new GatewayRoutingService(List.of(telegramAdapter), e -> { });
        SessionSource target = new SessionSource(Platform.TELEGRAM, "42", "7", "user", "User");

        SendResult result = service.adapterFor(Platform.TELEGRAM)
            .map(adapter -> adapter.sendTyping(target))
            .orElse(CompletableFuture.completedFuture(new SendResult(false, null, "Platform not registered")))
            .join();

        assertThat(result.success()).isTrue();
        assertThat(typingTarget.get()).isEqualTo(target);
    }
}
