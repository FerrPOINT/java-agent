package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.MessageType;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
public class TelegramLongPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramLongPollingService.class);
    private final AgentProperties properties;
    private final GatewayRoutingService routingService;
    private final RestClient restClient;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastUpdateId = new AtomicLong(0);

    public TelegramLongPollingService(AgentProperties properties, GatewayRoutingService routingService) {
        this.properties = properties;
        this.routingService = routingService;
        this.restClient = TelegramRestClientFactory.create(
            Duration.ofSeconds(properties.getGateway().getTelegram().getTimeoutSeconds())
        );
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "telegram-long-polling");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        if (properties.getGateway().getTelegram().getBotToken().isBlank()) {
            log.warn("Telegram long polling not started: bot token is empty");
            return;
        }
        running.set(true);
        executor.submit(this::pollLoop);
        log.info("Telegram long polling started");
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
                log.warn("Telegram long polling error: {}", e.getMessage());
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
    private List<Map<String, Object>> fetchUpdates() throws InterruptedException {
        try {
            var response = restClient.get()
                .uri("https://api.telegram.org/bot{token}/getUpdates?offset={offset}&limit=100",
                    properties.getGateway().getTelegram().getBotToken(),
                    lastUpdateId.get() + 1)
                .retrieve()
                .toEntity(Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return List.of();
            }
            Boolean ok = (Boolean) response.getBody().get("ok");
            if (!Boolean.TRUE.equals(ok)) return List.of();
            Object result = response.getBody().get("result");
            if (result instanceof List list) {
                return (List<Map<String, Object>>) list;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("getUpdates failed: {}", e.getMessage());
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
    private void processUpdate(Map<String, Object> update) {
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
