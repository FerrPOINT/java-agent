package com.azhukov.agent.bot.cron;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heartbeat/loop result delivery (Hermes parity: the gateway wakeup watcher
 * delivers the reply of each heartbeat tick to the user's chat).
 *
 * <p>The backend watchdog fires heartbeat turns headless into their session;
 * without this poller the user never sees the replies. We poll the active
 * sessions' heartbeat results and forward new ones to the owner chat.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HeartbeatDeliveryPoller {

    private final AgentBackendClient backendClient;
    // Delivery observability (no micrometer in the bot module — logged counters)
    private final java.util.concurrent.atomic.AtomicLong delivered = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong failed = new java.util.concurrent.atomic.AtomicLong();
    private final com.azhukov.agent.bot.client.TelegramClient telegramClient;
    private final BotProperties properties;

    /** Sessions we know have an active heartbeat — populated on /heartbeat set. */
    private final Map<UUID, Long> watched = new ConcurrentHashMap<>();

    public void watch(UUID sessionId, long chatId) {
        watched.put(sessionId, chatId);
    }

    public void unwatch(UUID sessionId) {
        watched.remove(sessionId);
    }

    @Scheduled(fixedDelay = 20_000L, initialDelay = 25_000L)
    public void poll() {
        if (!properties.isCronDeliveryEnabled() || watched.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Long> e : watched.entrySet()) {
            try {
                JsonNode r = backendClient.suggestionGet(
                    "/api/v1/agent/cron/heartbeat/" + e.getKey() + "/result");
                if (r == null || !r.path("hasResult").asBoolean(false)) {
                    continue;
                }
                String text = r.path("result").asText("");
                if (!text.isBlank()) {
                    var sent = telegramClient.sendMessage(e.getValue(),
                        "♥ Heartbeat:\n\n" + truncate(text));
                    if (sent.isPresent()) {
                        // Delivery succeeded — ACK so the result is dropped server-side
                        log.info("HEARTBEAT_DELIVERED chatId={} sessionId={} msgId={} chars={}",
                            e.getValue(), e.getKey(), sent.get(), text.length());
                        log.info("HEARTBEAT_DELIVERY_TOTAL delivered={}", delivered.incrementAndGet());
                        backendClient.suggestionPost(
                            "/api/v1/agent/cron/heartbeat/" + e.getKey() + "/result/ack");
                    } else {
                        // Send failed — NACK; after 5 attempts the backend drops
                        // the poisoned result instead of retrying forever
                        log.warn("HEARTBEAT_DELIVERY_FAILED chatId={} sessionId={} — nacking for retry",
                            e.getValue(), e.getKey());
                        log.warn("HEARTBEAT_DELIVERY_TOTAL failed={}", failed.incrementAndGet());
                        JsonNode nack = backendClient.suggestionPost(
                            "/api/v1/agent/cron/heartbeat/" + e.getKey() + "/result/nack");
                        if (nack != null && nack.path("drop").asBoolean(false)) {
                            backendClient.suggestionPost(
                                "/api/v1/agent/cron/heartbeat/" + e.getKey() + "/result/ack");
                        }
                    }
                } else {
                    // Empty result (e.g. model said nothing) — ACK to clear it
                    backendClient.suggestionPost(
                        "/api/v1/agent/cron/heartbeat/" + e.getKey() + "/result/ack");
                }
                // Stop watching cleared/finished loops
                JsonNode st = backendClient.suggestionGet(
                    "/api/v1/agent/cron/heartbeat/" + e.getKey());
                if (st == null || !st.path("set").asBoolean(false)) {
                    unwatch(e.getKey());
                }
            } catch (Exception ex) {
                log.debug("Heartbeat delivery poll failed for {}: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    private static String truncate(String s) {
        return s.length() > 3500 ? s.substring(0, 3500) + "\n…" : s;
    }
}
