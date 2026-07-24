package com.azhukov.agent.gateway;

import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Component
public class GatewayRoutingService {

    private final Map<Platform, BasePlatformAdapter> adapters;
    private final Consumer<MessageEvent> messageHandler;

    public GatewayRoutingService(List<BasePlatformAdapter> adapters, Consumer<MessageEvent> messageHandler) {
        this.adapters = new HashMap<>();
        for (BasePlatformAdapter a : adapters) {
            this.adapters.put(a.platform(), a);
            a.setMessageHandler(messageHandler);
        }
        this.messageHandler = messageHandler;
    }

    public CompletableFuture<Boolean> connect(PlatformConfig config) {
        BasePlatformAdapter adapter = adapters.get(config.platform());
        if (adapter == null) {
            return CompletableFuture.completedFuture(false);
        }
        return adapter.connect(config);
    }

    public CompletableFuture<SendResult> send(Platform platform, SessionSource target, String text) {
        BasePlatformAdapter adapter = adapters.get(platform);
        if (adapter == null) {
            return CompletableFuture.completedFuture(new SendResult(false, null, "Platform not registered"));
        }
        return adapter.send(target, text);
    }

    public Optional<BasePlatformAdapter> adapterFor(Platform platform) {
        return Optional.ofNullable(adapters.get(platform));
    }
}
