package com.azhukov.agent.bot.cron;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.BaseBackendClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WP-1 durable delivery consumer (Hermes delivery_ledger parity).
 *
 * <p>Replaces the old high-water-mark flow: the bot no longer scans cron jobs
 * and session messages. It claims one ledger item at a time from the backend
 * ({@code POST /api/v1/agent/delivery/claim}), sends it through Telegram, and
 * acks with the outbound message id. A crash between send and ack leaves the
 * item in an explicitly fenced state on the backend — never a silent resend.
 */
@Service
@Slf4j
public class DeliveryLedgerConsumer {

    private final RestClient restClient;
    private final TelegramClient telegramClient;
    private final BotProperties properties;
    private final ObjectMapper objectMapper;

    public DeliveryLedgerConsumer(
        @Qualifier("backendRestClient") RestClient restClient,
        TelegramClient telegramClient,
        BotProperties properties,
        ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.telegramClient = telegramClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 10_000L, initialDelay = 15_000L)
    public void consume() {
        if (!properties.isCronDeliveryEnabled()) {
            return;
        }
        int processed = 0;
        while (processed < 10) {
            Optional<ClaimedItem> claimed = claimNext();
            if (claimed.isEmpty()) {
                break;
            }
            deliver(claimed.get());
            processed++;
        }
    }

    record ClaimedItem(String id, String claimToken, String sourceType, String sourceId,
                       String platform, String chatId, String threadId, String payload, int attempts) {}

    private Optional<ClaimedItem> claimNext() {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "consumerId", consumerId(),
                "profiles", List.of(profile())));
            String json = restClient.post()
                .uri("/api/v1/agent/delivery/claim")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            JsonNode parsed = json == null ? null : objectMapper.readTree(json);
            if (parsed == null || !parsed.path("claimed").asBoolean(false)) {
                return Optional.empty();
            }
            return Optional.of(new ClaimedItem(
                parsed.path("id").asText(),
                parsed.path("claim_token").asText(),
                parsed.path("source_type").asText(),
                parsed.path("source_id").asText(),
                parsed.path("platform").asText(""),
                parsed.path("chat_id").asText(""),
                parsed.path("thread_id").asText(""),
                parsed.path("payload").asText(""),
                parsed.path("attempts").asInt(0)));
        } catch (Exception e) {
            log.debug("Delivery claim failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void deliver(ClaimedItem item) {
        if (!"telegram".equals(item.platform()) || item.chatId().isBlank()) {
            // Unsupported platform for this consumer — release without send.
            outcome(item, "drop", "unsupported_platform",
                "platform=" + item.platform() + " chat=" + item.chatId());
            return;
        }
        long chatId;
        Integer threadId = item.threadId().isBlank() ? null : Integer.valueOf(item.threadId());
        try {
            chatId = Long.parseLong(item.chatId());
        } catch (NumberFormatException e) {
            outcome(item, "drop", "invalid_chat_id", item.chatId());
            return;
        }
        try {
            Optional<Long> sent = threadId == null
                ? telegramClient.sendMessage(chatId, item.payload())
                : telegramClient.sendMessage(chatId, item.payload(), null, null, threadId, false);
            if (sent.isPresent()) {
                ack(item, sent.get().toString());
                log.info("Delivered {} item {} to chat {} (msg {})",
                    item.sourceType(), item.sourceId(), chatId, sent.get());
                return;
            }
            // Send returned empty — the outcome is ambiguous (Hermes: unknown,
            // never auto-retry a possibly-sent message).
            outcome(item, "unknown", "empty_send_result", null);
        } catch (Exception e) {
            // Transport raised BEFORE Telegram accepted anything — known failure.
            outcome(item, "release", "transport_error", e.getMessage());
        }
    }

    private void ack(ClaimedItem item, String messageId) {
        post("/api/v1/agent/delivery/ack", Map.of(
            "id", item.id(),
            "claim_token", item.claimToken(),
            "outbound_message_id", messageId,
            "idempotency_key", item.sourceType() + ":" + item.sourceId() + ":" + messageId));
    }

    private void outcome(ClaimedItem item, String action, String category, String detail) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", item.id());
        body.put("claim_token", item.claimToken());
        if (category != null) {
            body.put("category", category);
        }
        if (detail != null) {
            body.put("detail", detail);
        }
        post("/api/v1/agent/delivery/" + action, body);
    }

    private void post(String path, Object body) {
        try {
            restClient.post()
                .uri(path)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            // The claim will expire or be re-driven on the next cycle; the
            // backend's stale-claim policy is the safety net.
            log.warn("Delivery outcome {} failed: {}", path, e.getMessage());
        }
    }

    private String consumerId() {
        return "telegram-bot";
    }

    private String profile() {
        return "default";
    }
}
