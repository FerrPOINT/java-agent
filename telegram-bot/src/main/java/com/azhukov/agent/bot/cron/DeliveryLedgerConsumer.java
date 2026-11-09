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
        consume(System::nanoTime);
    }

    void consume(java.util.function.LongSupplier nanoTime) {
        if (!properties.isCronDeliveryEnabled()) {
            return;
        }
        int processed = 0;
        while (processed < 10) {
            ClaimedBatch batch = claimNextBatch(nanoTime.getAsLong());
            if (batch.items().isEmpty()) {
                break;
            }
            deliverBatch(batch.items(), batch.startedNanos(), batch.leaseNanos(), nanoTime);
            processed += batch.items().size();
        }
    }

    /**
     * Hermes boot-sweep parity (07a1d55d33/877848d2f3): a bot restart is the
     * real recovery signal for rows parked as {@code send_path_degraded}.
     * Their reserved last attempt is spent by THIS consumer on its first
     * claim cycle — not by the timer. Called once at startup.
     */
    @jakarta.annotation.PostConstruct
    void rearmParkedRows() {
        if (!properties.isCronDeliveryEnabled()) {
            return;
        }
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/delivery/rearm")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(Map.of("consumer_id", consumerId())))
                .retrieve()
                .body(String.class);
            log.info("Delivery boot-sweep: {}", json);
        } catch (Exception e) {
            log.debug("Delivery rearm failed (backend not up yet): {}", e.getMessage());
        }
    }

    /**
     * Hermes completion-batch parity: claim several pending items for ONE
     * target and deliver them as a single coalesced message (or the plain
     * payload when there is exactly one).
     */
    private record ClaimedBatch(List<ClaimedItem> items, long startedNanos, long leaseNanos) {}

    private ClaimedBatch claimNextBatch(long started) {
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
                return new ClaimedBatch(List.of(), started, 0);
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
            // Anchor before the request so network and parsing delays consume budget, without
            // comparing the bot clock to the database server clock.
            long leaseNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(parsed.path("lease_remaining_ms").asLong(0));
            return new ClaimedBatch(items, started, leaseNanos);
        } catch (Exception e) {
            log.debug("Delivery batch claim failed: {}", e.getMessage());
            return new ClaimedBatch(List.of(), started, 0);
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
    private void deliverBatch(List<ClaimedItem> batch, long startedNanos, long leaseNanos, java.util.function.LongSupplier nanoTime) {
        ClaimedItem first = batch.get(0);
        if (!"telegram".equals(first.platform()) || first.chatId().isBlank()) {
            for (ClaimedItem item : batch) {
                outcome(item, "drop", "unsupported_platform",
                    "platform=" + item.platform() + " chat=" + item.chatId());
            }
            return;
        }
        long chatId;
        Integer threadId;
        try {
            threadId = first.threadId().isBlank() ? null : Integer.valueOf(first.threadId());
        } catch (NumberFormatException e) {
            for (ClaimedItem item : batch) outcome(item, "drop", "invalid_thread_id", null);
            return;
        }
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
        Long lastMessageId = null;
        try {
            List<String> chunks = com.azhukov.agent.bot.formatting.MessageSplitter.splitPlainText(text);
            boolean sendFailed = false;
            for (String chunk : chunks) {
                if (nanoTime.getAsLong() - startedNanos >= leaseNanos) {
                    for (ClaimedItem item : batch) outcome(item, "unknown", "delivery_lease_elapsed", null);
                    log.warn("delivery_batch_lease_elapsed items={} chat={}", batch.size(), chatId);
                    return;
                }
                Optional<Long> sent = threadId == null
                    ? telegramClient.sendMessageChecked(chatId, chunk, null, null, null, null, false)
                    : telegramClient.sendMessageChecked(chatId, chunk, null, null, threadId, null, false);
                if (sent.isEmpty()) {
                    sendFailed = true;
                    break;
                }
                lastMessageId = sent.get();
            }
            if (!sendFailed) {
                String messageId = lastMessageId == null ? null : lastMessageId.toString();
                int confirmed = 0;
                for (ClaimedItem item : batch) {
                    if (ack(item, messageId)) confirmed++;
                }
                if (confirmed == batch.size()) {
                    log.info("delivery_batch_acked items={} chat={} chunks={} lastMessageId={}",
                        confirmed, chatId, chunks.size(), lastMessageId);
                } else {
                    log.warn("delivery_batch_ack_unconfirmed confirmed={} items={} chat={} lastMessageId={}",
                        confirmed, batch.size(), chatId, lastMessageId);
                }
                return;
            }
            for (ClaimedItem item : batch) {
                outcome(item, "unknown",
                    lastMessageId != null ? "partial_chunk_failure" : "empty_send_result", null);
            }
            log.warn("delivery_batch_unresolved items={} sourceIds={} chat={} category={}", batch.size(),
                batch.stream().map(ClaimedItem::sourceId).toList(), chatId,
                lastMessageId != null ? "partial_chunk_failure" : "empty_send_result");
        } catch (TelegramClient.SendRefusalException e) {
            if (lastMessageId != null) {
                for (ClaimedItem item : batch) outcome(item, "unknown", "partial_chunk_failure", null);
                return;
            }
            // Hermes delivery.py parity: the platform REFUSED the send — a
            // known failure (never ambiguous). A flood refusal (429) keeps the
            // platform's retry_after. Retrying a rejected payload cannot fix a bad request.
            boolean badRequest = e.getErrorCode() == 400;
            String category = e.getRetryAfterSeconds() > 0
                ? "flood_control:" + e.getRetryAfterSeconds()
                : badRequest ? "bad_request" : "send_refused";
            for (ClaimedItem item : batch) {
                outcome(item, badRequest ? "drop" : "release", category,
                    e.getRetryAfterSeconds() > 0
                        ? "retry_after=" + e.getRetryAfterSeconds() + "s " + e.getMessage()
                        : e.getMessage());
            }
            log.warn("delivery_batch_refused items={} sourceIds={} chat={} category={} retryAfter={}",
                batch.size(), batch.stream().map(ClaimedItem::sourceId).toList(), chatId,
                category, e.getRetryAfterSeconds());
        } catch (Exception e) {
            for (ClaimedItem item : batch) {
                outcome(item, "unknown", lastMessageId != null ? "partial_chunk_failure" : "transport_error", null);
            }
            log.warn("delivery_batch_unresolved items={} sourceIds={} chat={} category={}", batch.size(),
                batch.stream().map(ClaimedItem::sourceId).toList(), chatId,
                lastMessageId != null ? "partial_chunk_failure" : "transport_error");
        }
    }

    record ClaimedItem(String id, String claimToken, String sourceType, String sourceId,
                       String platform, String chatId, String threadId, String payload, int attempts) {}

    /** Package-private batch bridge for tests. */
    void deliverBatchForTest(List<ClaimedItem> batch) {
        deliverBatch(batch, 0, Long.MAX_VALUE, System::nanoTime);
    }

    private boolean ack(ClaimedItem item, String messageId) {
        return post("/api/v1/agent/delivery/ack", Map.of(
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

    private boolean post(String path, Object body) {
        try {
            restClient.post()
                .uri(path)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Delivery outcome {} failed ({})", path, e.getClass().getSimpleName());
            return false;
        }
    }

    private String consumerId() {
        return "telegram-bot";
    }

    private String profile() {
        return "default";
    }
}
