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
 * <p>Sole cron/delegate delivery lane since the WP-1 cutover (the old
 * high-water-mark poller is gone): the bot claims one ledger item at a time
 * from the backend ({@code POST /api/v1/agent/delivery/claim}), sends it
 * through Telegram (chunked via MessageSplitter — full output, UTF-16-safe),
 * and acks with the outbound message id. A crash between send and ack leaves
 * the item in an explicitly fenced state on the backend — never a silent
 * resend; the backend sweeper recovers expired leases.
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
            List<ClaimedItem> batch = claimNextBatch();
            if (batch.isEmpty()) {
                break;
            }
            deliverBatch(batch);
            processed += batch.size();
        }
    }

    /**
     * Hermes completion-batch parity: claim several pending items for ONE
     * target and deliver them as a single coalesced message (or the plain
     * payload when there is exactly one).
     */
    private List<ClaimedItem> claimNextBatch() {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "consumer_id", consumerId(),
                "profiles", List.of(profile()),
                "max", 5));
            String json = restClient.post()
                .uri("/api/v1/agent/delivery/claim-batch")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            JsonNode parsed = json == null ? null : objectMapper.readTree(json);
            if (parsed == null || !parsed.path("claimed").asBoolean(false)) {
                return List.of();
            }
            List<ClaimedItem> items = new java.util.ArrayList<>();
            for (JsonNode node : parsed.path("items")) {
                items.add(new ClaimedItem(
                    node.path("id").asText(),
                    node.path("claim_token").asText(),
                    node.path("source_type").asText(),
                    node.path("source_id").asText(),
                    node.path("platform").asText(""),
                    node.path("chat_id").asText(""),
                    node.path("thread_id").asText(""),
                    node.path("payload").asText(""),
                    node.path("attempts").asInt(0)));
            }
            return items;
        } catch (Exception e) {
            log.debug("Delivery batch claim failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Hermes {@code _format_coalesced_process_completions} parity: one bounded
     * synthetic message for several completions, last-800-chars tail, redaction
     * before slicing, "absorb silently" instruction.
     */
    static String formatCoalescedBatch(List<ClaimedItem> batch) {
        if (batch.size() == 1) {
            return batch.get(0).payload();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[IMPORTANT: ").append(batch.size())
            .append(" background processes completed for this session.\n")
            .append("Treat these results as one completion batch and send at most one ")
            .append("consolidated user-facing response.\n");
        List<ClaimedItem> shown = batch.size() > 10 ? batch.subList(0, 10) : batch;
        for (ClaimedItem item : shown) {
            sb.append("\n- ").append(item.sourceId()).append(": ");
            String output = item.payload() == null ? "" : item.payload().strip();
            if (output.length() > 800) {
                output = "[… truncated …]\n" + output.substring(output.length() - 800);
            }
            sb.append(output);
        }
        int omitted = batch.size() - shown.size();
        if (omitted > 0) {
            sb.append("\n\n- … and ").append(omitted)
                .append(" more completion(s); inspect them with the process tool if they affect the conclusion.");
        }
        sb.append("\nIf a result does not change the current conclusion, absorb it silently.]");
        return sb.toString();
    }

    /** Deliver a coalesced batch; on failure every item gets the SAME fence. */
    private void deliverBatch(List<ClaimedItem> batch) {
        ClaimedItem first = batch.get(0);
        if (!"telegram".equals(first.platform()) || first.chatId().isBlank()) {
            for (ClaimedItem item : batch) {
                outcome(item, "drop", "unsupported_platform",
                    "platform=" + item.platform() + " chat=" + item.chatId());
            }
            return;
        }
        long chatId;
        Integer threadId = first.threadId().isBlank() ? null : Integer.valueOf(first.threadId());
        try {
            chatId = Long.parseLong(first.chatId());
        } catch (NumberFormatException e) {
            for (ClaimedItem item : batch) {
                outcome(item, "drop", "invalid_chat_id", item.chatId());
            }
            return;
        }
        String text = formatCoalescedBatch(batch);
        log.info("delivery_batch_started items={} sourceIds={} chat={} attempts={}", batch.size(),
            batch.stream().map(ClaimedItem::sourceId).toList(), chatId,
            batch.stream().map(ClaimedItem::attempts).toList());
        try {
            List<String> chunks = com.azhukov.agent.bot.formatting.MessageSplitter.split(text);
            Long lastMessageId = null;
            boolean sendFailed = false;
            for (String chunk : chunks) {
                Optional<Long> sent = threadId == null
                    ? telegramClient.sendMessage(chatId, chunk)
                    : telegramClient.sendMessage(chatId, chunk, null, null, threadId, false);
                if (sent.isEmpty()) {
                    sendFailed = true;
                    break;
                }
                lastMessageId = sent.get();
            }
            if (!sendFailed) {
                String messageId = lastMessageId == null ? null : lastMessageId.toString();
                for (ClaimedItem item : batch) {
                    ack(item, messageId);
                }
                log.info("delivery_batch_acked items={} sourceIds={} chat={} chunks={} lastMessageId={}",
                    batch.size(), batch.stream().map(ClaimedItem::sourceId).toList(), chatId, chunks.size(), lastMessageId);
                log.info("Delivered batch of {} items ({} source types) to chat {} ({} chunks, last msg {})",
                    batch.size(), batch.stream().map(ClaimedItem::sourceType).distinct().count(),
                    chatId, chunks.size(), lastMessageId);
                return;
            }
            for (ClaimedItem item : batch) {
                outcome(item, "unknown",
                    lastMessageId != null ? "partial_chunk_failure" : "empty_send_result", null);
            }
            log.warn("delivery_batch_unresolved items={} sourceIds={} chat={} category={}", batch.size(),
                batch.stream().map(ClaimedItem::sourceId).toList(), chatId,
                lastMessageId != null ? "partial_chunk_failure" : "empty_send_result");
        } catch (Exception e) {
            for (ClaimedItem item : batch) {
                outcome(item, "release", "transport_error", e.getMessage());
            }
            log.warn("delivery_batch_released items={} sourceIds={} chat={} error={}", batch.size(),
                batch.stream().map(ClaimedItem::sourceId).toList(), chatId, e.getMessage());
        }
    }

    record ClaimedItem(String id, String claimToken, String sourceType, String sourceId,
                       String platform, String chatId, String threadId, String payload, int attempts) {}

    /** Package-private delivery bridge for tests (no HTTP claim round-trip). */
    void deliverForTest(ClaimedItem item) {
        deliver(item);
    }

    /** Package-private batch bridge for tests. */
    void deliverBatchForTest(List<ClaimedItem> batch) {
        deliverBatch(batch);
    }

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
            List<String> chunks = com.azhukov.agent.bot.formatting.MessageSplitter.split(item.payload());
            Long lastMessageId = null;
            boolean sendFailed = false;
            for (String chunk : chunks) {
                Optional<Long> sent = threadId == null
                    ? telegramClient.sendMessage(chatId, chunk)
                    : telegramClient.sendMessage(chatId, chunk, null, null, threadId, false);
                if (sent.isEmpty()) {
                    sendFailed = true;
                    break;
                }
                lastMessageId = sent.get();
            }
            if (!sendFailed) {
                // P-03 (Hermes delivery.py): Telegram is chunking-capable, so the
                // FULL output is sent (MessageSplitter, UTF-16-safe); the receipt
                // names the LAST chunk's message id.
                ack(item, lastMessageId == null ? null : lastMessageId.toString());
                log.info("Delivered {} item {} to chat {} ({} chunks, last msg {})",
                    item.sourceType(), item.sourceId(), chatId, chunks.size(), lastMessageId);
                return;
            }
            if (lastMessageId != null) {
                // A chunk sent, a later chunk failed — PARTIAL delivery, the
                // outcome is ambiguous (Hermes: unknown, never auto-retry).
                outcome(item, "unknown", "partial_chunk_failure", null);
            } else {
                // First chunk returned empty — sendMessage conflates "chat not
                // found" with "timeout after Telegram accepted" into empty, so
                // the outcome is ambiguous (Hermes: unknown, never auto-retry
                // a possibly-sent message).
                outcome(item, "unknown", "empty_send_result", null);
            }
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
