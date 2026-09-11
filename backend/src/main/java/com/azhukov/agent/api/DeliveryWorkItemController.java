package com.azhukov.agent.api;

import com.azhukov.agent.service.DeliveryWorkItemService;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WP-1 durable delivery: internal consumer port for the delivery ledger.
 *
 * <p>The Telegram bot is a separate process; it consumes final cron/delegate
 * output exclusively through this contract (claim → send → ack), mirroring
 * Hermes delivery_ledger/delivery_queue semantics:
 * <ul>
 *   <li>{@code claim} atomically leases one pending item for the consumer's profiles;</li>
 *   <li>{@code ack} records the outbound receipt and terminalizes the item;</li>
 *   <li>{@code release} returns a KNOWN pre-send failure for bounded retry;</li>
 *   <li>{@code unknown} fences ambiguous sends (crash after transport I/O);</li>
 *   <li>{@code drop} terminalizes non-retryable work.</li>
 * </ul>
 * Wrong claim tokens fail closed — a consumer can never terminalize another
 * consumer's lease.
 */
@RestController
@RequestMapping("/api/v1/agent/delivery")
@RequiredArgsConstructor
@Slf4j
public class DeliveryWorkItemController {

    private final DeliveryWorkItemService deliveryService;

    public record ClaimRequest(
        @JsonProperty("consumer_id") @JsonAlias("consumerId") String consumerId,
        List<String> profiles) {}

    public record AckRequest(
        UUID id,
        @JsonProperty("claim_token") @JsonAlias("claimToken") String claimToken,
        @JsonProperty("outbound_message_id") @JsonAlias("outboundMessageId") String outboundMessageId,
        @JsonProperty("idempotency_key") @JsonAlias("idempotencyKey") String idempotencyKey) {}

    public record OutcomeRequest(
        UUID id,
        @JsonProperty("claim_token") @JsonAlias("claimToken") String claimToken,
        String category,
        String detail) {}

    @PostMapping("/claim")
    public ResponseEntity<Map<String, Object>> claim(@RequestBody ClaimRequest request) {
        if (request == null || request.consumerId() == null || request.consumerId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "consumerId is required"));
        }
        return deliveryService.claimNext(request.consumerId(), request.profiles())
            .<ResponseEntity<Map<String, Object>>>map(claimed -> {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("claimed", true);
                body.put("id", claimed.item().getId().toString());
                body.put("claim_token", claimed.claimToken());
                body.put("source_type", claimed.item().getSourceType());
                body.put("source_id", claimed.item().getSourceId());
                body.put("target_kind", claimed.item().getTargetKind());
                body.put("platform", claimed.item().getPlatform() == null ? "" : claimed.item().getPlatform());
                body.put("chat_id", claimed.item().getChatId() == null ? "" : claimed.item().getChatId());
                body.put("thread_id", claimed.item().getThreadId() == null ? "" : claimed.item().getThreadId());
                body.put("payload", claimed.item().getPayloadText());
                body.put("attempts", claimed.item().getAttempts());
                return ResponseEntity.ok(body);
            })
            .orElseGet(() -> ResponseEntity.ok(Map.of("claimed", false)));
    }

    @PostMapping("/ack")
    public ResponseEntity<Map<String, Object>> ack(@RequestBody AckRequest request) {
        if (request == null || request.id() == null || blank(request.claimToken())) {
            return ResponseEntity.badRequest().body(Map.of("error", "id and claim_token are required"));
        }
        boolean acked = deliveryService.markDelivered(
            request.id(), request.claimToken(),
            new DeliveryWorkItemService.DeliveryReceipt(request.outboundMessageId(), request.idempotencyKey()));
        return acked
            ? ResponseEntity.ok(Map.of("acked", true))
            : ResponseEntity.status(409).body(Map.of("acked", false, "error", "claim not held or already terminal"));
    }

    @PostMapping("/release")
    public ResponseEntity<Map<String, Object>> release(@RequestBody OutcomeRequest request) {
        if (request == null || request.id() == null || blank(request.claimToken())) {
            return ResponseEntity.badRequest().body(Map.of("error", "id and claim_token are required"));
        }
        boolean released = deliveryService.releaseKnownFailure(
            request.id(), request.claimToken(), request.category(), request.detail());
        return released
            ? ResponseEntity.ok(Map.of("released", true))
            : ResponseEntity.status(409).body(Map.of("released", false, "error", "claim not held"));
    }

    @PostMapping("/unknown")
    public ResponseEntity<Map<String, Object>> unknown(@RequestBody OutcomeRequest request) {
        if (request == null || request.id() == null || blank(request.claimToken())) {
            return ResponseEntity.badRequest().body(Map.of("error", "id and claim_token are required"));
        }
        boolean marked = deliveryService.markUnknown(
            request.id(), request.claimToken(), request.category(), request.detail());
        return marked
            ? ResponseEntity.ok(Map.of("marked", true))
            : ResponseEntity.status(409).body(Map.of("marked", false, "error", "claim not held"));
    }

    @PostMapping("/drop")
    public ResponseEntity<Map<String, Object>> drop(@RequestBody OutcomeRequest request) {
        if (request == null || request.id() == null || blank(request.claimToken())) {
            return ResponseEntity.badRequest().body(Map.of("error", "id and claim_token are required"));
        }
        boolean dropped = deliveryService.drop(
            request.id(), request.claimToken(), request.category(), request.detail());
        return dropped
            ? ResponseEntity.ok(Map.of("dropped", true))
            : ResponseEntity.status(409).body(Map.of("dropped", false, "error", "claim not held"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
