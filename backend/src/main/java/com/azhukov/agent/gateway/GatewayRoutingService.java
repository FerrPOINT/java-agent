package com.azhukov.agent.gateway;

import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.service.OutboundReceiptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Routes outbound sends to platform adapters and inbound events to the shared
 * handler. Since WP-2 (ADR-012) it also records outbound message receipts for
 * every send that learns a platform message id, and gates inbound dispatch on
 * the gateway lifecycle state (drain/stop reject new inbound).
 */
@Slf4j
public class GatewayRoutingService {

    private final Map<Platform, BasePlatformAdapter> adapters;
    private final Consumer<MessageEvent> messageHandler;
    private final ObjectProvider<OutboundReceiptService> receiptServiceProvider;
    private final ObjectProvider<GatewayLifecycleService> lifecycleProvider;

    public GatewayRoutingService(List<BasePlatformAdapter> adapters, Consumer<MessageEvent> messageHandler) {
        this(adapters, messageHandler, null, null);
    }

    public GatewayRoutingService(List<BasePlatformAdapter> adapters, Consumer<MessageEvent> messageHandler,
                                 ObjectProvider<OutboundReceiptService> receiptServiceProvider,
                                 ObjectProvider<GatewayLifecycleService> lifecycleProvider) {
        this.adapters = new HashMap<>();
        for (BasePlatformAdapter a : adapters) {
            this.adapters.put(a.platform(), a);
            a.setMessageHandler(messageHandler);
        }
        this.messageHandler = messageHandler;
        this.receiptServiceProvider = receiptServiceProvider;
        this.lifecycleProvider = lifecycleProvider;
    }

    public CompletableFuture<Boolean> connect(PlatformConfig config) {
        BasePlatformAdapter adapter = adapters.get(config.platform());
        if (adapter == null) {
            return CompletableFuture.completedFuture(false);
        }
        return adapter.connect(config);
    }

    public CompletableFuture<SendResult> send(Platform platform, SessionSource target, String text) {
        return send(platform, target, text, null);
    }

    /**
     * Send with receipt recording. {@code sessionId} scopes the receipt so
     * react/unreact can validate same-session access to the last message.
     */
    public CompletableFuture<SendResult> send(Platform platform, SessionSource target, String text, UUID sessionId) {
        BasePlatformAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "Platform not registered"));
        }
        return adapter.send(target, text).thenApply(result -> {
            if (result.success() && result.messageId() != null && !result.messageId().isBlank()) {
                recordReceipt(platform, target, sessionId, result.messageId());
            }
            return result;
        });
    }

    public CompletableFuture<SendResult> sendTyping(Platform platform, SessionSource target) {
        BasePlatformAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "Platform not registered"));
        }
        return adapter.sendTyping(target);
    }

    public CompletableFuture<SendResult> addReaction(Platform platform, SessionSource target, String emoji, String messageId) {
        BasePlatformAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "Platform not registered"));
        }
        return adapter.addReaction(target, emoji, messageId);
    }

    public CompletableFuture<SendResult> removeReaction(Platform platform, SessionSource target, String messageId) {
        BasePlatformAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "Platform not registered"));
        }
        return adapter.removeReaction(target, messageId);
    }

    /** Inbound dispatch, gated on lifecycle state (drain/stop rejects new inbound). */
    public void dispatchInbound(MessageEvent event) {
        GatewayLifecycleService lifecycle = lifecycleProvider == null ? null : lifecycleProvider.getIfAvailable();
        if (lifecycle != null && !lifecycle.beginInbound()) {
            log.info("Inbound event {} rejected: gateway not accepting (state {})",
                event.eventId(), lifecycle.currentState());
            return;
        }
        try {
            messageHandler.accept(event);
        } finally {
            if (lifecycle != null) {
                lifecycle.endInbound();
            }
        }
    }

    public Optional<BasePlatformAdapter> adapterFor(Platform platform) {
        return Optional.ofNullable(adapters.get(platform));
    }

    public Map<Platform, BasePlatformAdapter> adapters() {
        return Map.copyOf(adapters);
    }

    private void recordReceipt(Platform platform, SessionSource target, UUID sessionId, String messageId) {
        if (receiptServiceProvider == null) {
            return;
        }
        OutboundReceiptService receipts = receiptServiceProvider.getIfAvailable();
        if (receipts == null) {
            return;
        }
        try {
            receipts.record(OutboundReceiptService.Receipt.of(
                platform.name().toLowerCase(Locale.ROOT),
                target.chatId(),
                target.threadId(),
                sessionId,
                messageId));
        } catch (Exception e) {
            log.debug("Outbound receipt recording failed for {}/{}: {}",
                platform, target.chatId(), e.getMessage());
        }
    }
}
