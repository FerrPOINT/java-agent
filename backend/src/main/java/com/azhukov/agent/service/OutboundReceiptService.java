package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.OutboundMessageReceiptEntity;
import com.azhukov.agent.persistence.repository.OutboundMessageReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound message receipt store (ADR-012). Every outbound send path that
 * learns a platform message id records here; {@code react}/{@code unreact}
 * without an explicit message id resolve the latest receipt for the target.
 *
 * <p>Not a delivery ledger: {@code delivery_work_items} (WP-1) stays the sole
 * cron/delegate delivery lane. This is send-state, keyed by target.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundReceiptService {

    private final OutboundMessageReceiptRepository repository;

    public record Receipt(String platform, String chatId, String threadId, UUID sessionId,
                          String userId, String messageId, String idempotencyKey) {

        public static Receipt of(String platform, String chatId, String threadId, UUID sessionId, String messageId) {
            return new Receipt(platform, chatId, threadId, sessionId, null, messageId, null);
        }
    }

    /**
     * Record an outbound send. Idempotent on {@code idempotencyKey}: re-recording
     * the same send (retry after crash) returns the existing row instead of
     * creating a duplicate.
     */
    @Transactional
    public Optional<OutboundMessageReceiptEntity> record(Receipt receipt) {
        if (receipt == null || isBlank(receipt.platform()) || isBlank(receipt.chatId())
            || isBlank(receipt.messageId())) {
            return Optional.empty();
        }
        String key = isBlank(receipt.idempotencyKey())
            ? defaultIdempotencyKey(receipt.platform(), receipt.chatId(), receipt.threadId(),
                receipt.sessionId(), receipt.messageId())
            : receipt.idempotencyKey();
        Optional<OutboundMessageReceiptEntity> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing;
        }
        OutboundMessageReceiptEntity entity = new OutboundMessageReceiptEntity();
        entity.setId(UUID.randomUUID());
        entity.setPlatform(receipt.platform().trim().toLowerCase(Locale.ROOT));
        entity.setChatId(receipt.chatId().trim());
        entity.setThreadId(isBlank(receipt.threadId()) ? null : receipt.threadId().trim());
        entity.setSessionId(receipt.sessionId());
        entity.setUserId(isBlank(receipt.userId()) ? null : receipt.userId().trim());
        entity.setDirection("outbound");
        entity.setMessageId(receipt.messageId().trim());
        entity.setIdempotencyKey(key);
        entity.setCreatedAt(Instant.now());
        return Optional.of(repository.save(entity));
    }

    /**
     * Latest outbound message id for a target. Thread-aware: a request scoped
     * to a topic resolves the latest receipt in that topic, a plain target
     * resolves the latest receipt regardless of thread.
     */
    @Transactional(readOnly = true)
    public Optional<OutboundMessageReceiptEntity> lastMessageFor(String platform, String chatId, String threadId) {
        if (isBlank(platform) || isBlank(chatId)) {
            return Optional.empty();
        }
        String normalizedPlatform = platform.trim().toLowerCase(Locale.ROOT);
        String normalizedChat = chatId.trim();
        if (isBlank(threadId)) {
            return repository.findFirstByPlatformAndChatIdOrderByCreatedAtDesc(normalizedPlatform, normalizedChat);
        }
        return repository.findFirstByPlatformAndChatIdAndThreadIdOrderByCreatedAtDesc(
            normalizedPlatform, normalizedChat, threadId.trim());
    }

    @Transactional(readOnly = true)
    public List<OutboundMessageReceiptEntity> forSession(UUID sessionId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        return repository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, limit));
    }

    /** Stable idempotency key for a (target, session, message id) send. */
    public static String defaultIdempotencyKey(String platform, String chatId, String threadId,
                                               UUID sessionId, String messageId) {
        String raw = String.join("|",
            blankToEmpty(platform), blankToEmpty(chatId), blankToEmpty(threadId),
            sessionId == null ? "-" : sessionId.toString(), blankToEmpty(messageId));
        return sha256(raw);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
