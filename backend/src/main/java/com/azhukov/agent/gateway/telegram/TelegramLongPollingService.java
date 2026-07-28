package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.MessageType;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@ConditionalOnProperty(name = "agent.gateway.telegram.long-polling.enabled", havingValue = "true")
@Component
@Slf4j
public class TelegramLongPollingService {

    private final AgentProperties properties;
    private final GatewayRoutingService routingService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastUpdateId = new AtomicLong(0);

    public boolean isRunning() {
        return running.get();
    }

    public TelegramLongPollingService(AgentProperties properties, GatewayRoutingService routingService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.routingService = routingService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "telegram-long-polling");
            t.setDaemon(true);
            return t;
        });
    }

    @Autowired
    public TelegramLongPollingService(AgentProperties properties, GatewayRoutingService routingService, ObjectMapper objectMapper) {
        this(properties, routingService, objectMapper, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build());
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void start() {
        String token = properties.getGateway().getTelegram().getBotToken();
        if (token == null || token.isBlank()) {
            log.warn("Telegram long polling not started: bot token is empty");
            return;
        }
        // Connect the Telegram adapter so it can send responses back
        routingService.connect(new com.azhukov.agent.gateway.model.PlatformConfig(
            com.azhukov.agent.gateway.model.Platform.TELEGRAM, true, java.util.Map.of(), java.util.Map.of()));
        running.set(true);
        executor.submit(this::pollLoop);
        log.info("Telegram long polling started with token prefix={} and timeout={}s",
            token.substring(0, Math.min(token.length(), 8)),
            properties.getGateway().getTelegram().getTimeoutSeconds());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdownNow();
        log.info("Telegram long polling stopped");
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                List<Map<String, Object>> updates = fetchUpdates();
                for (Map<String, Object> update : updates) {
                    processUpdate(update);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Telegram long polling error", e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fetchUpdates() throws InterruptedException {
        String token = properties.getGateway().getTelegram().getBotToken();
        long offset = lastUpdateId.get() + 1;
        int timeout = properties.getGateway().getTelegram().getTimeoutSeconds();
        String url = String.format("https://api.telegram.org/bot%s/getUpdates?offset=%d&limit=100&timeout=%d",
            token, offset, timeout);
        String maskedUrl = url.replace("/bot" + token, "/bot<token>");
        log.debug("Polling Telegram getUpdates offset={} timeout={} url={}", offset, timeout, maskedUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout + 15L))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("getUpdates non-200: {} body={}", response.statusCode(), response.body());
                return List.of();
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            Boolean ok = (Boolean) body.get("ok");
            if (!Boolean.TRUE.equals(ok)) {
                log.warn("getUpdates ok=false: {}", body);
                return List.of();
            }
            Object result = body.get("result");
            if (result instanceof List list) {
                if (!list.isEmpty()) {
                    log.info("Received {} Telegram update(s): {}", list.size(), list);
                }
                return (List<Map<String, Object>>) list;
            }
            return List.of();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("getUpdates failed", e);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    void processUpdate(Map<String, Object> update) {
        Long updateId = ((Number) update.get("update_id")).longValue();
        lastUpdateId.updateAndGet(current -> Math.max(current, updateId));

        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message == null) return;
        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        Map<String, Object> from = (Map<String, Object>) message.get("from");
        String text = Optional.ofNullable(message.get("text")).map(Object::toString).orElse("");
        long chatId = chat == null ? 0L : ((Number) chat.get("id")).longValue();
        long userId = from == null ? 0L : ((Number) from.get("id")).longValue();
        String username = from == null ? null : (String) from.get("username");

        SessionSource source = new SessionSource(
            Platform.TELEGRAM,
            String.valueOf(chatId),
            String.valueOf(userId),
            username == null ? "" : username,
            username == null ? "" : username
        );
        routingService.dispatchInbound(new MessageEvent(
            null, source, MessageType.TEXT, text, List.of(), Map.of(), java.time.Instant.now()
        ));
    }
}
