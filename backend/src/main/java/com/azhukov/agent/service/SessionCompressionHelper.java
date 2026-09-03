package com.azhukov.agent.service;

import com.azhukov.agent.core.memory.MemoryContextFence;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Extracted component for session compression to avoid the Spring self-invocation
 * pitfall where {@code @Retryable} and {@code @Transactional} annotations are
 * silently bypassed when a method is called from within the same class.
 * <p>
 * {@link AgentRuntimeService#compressSession} delegates to this component so
 * that the proxy-based annotations are properly engaged.
 * <p>
 * C8 fix: LLM compression call is executed OUTSIDE the transaction to avoid
 * holding a JDBC connection for 10-60+ seconds (pool starvation). The flow is:
 * 1. Read messages in a short read-only transaction
 * 2. Compress outside any transaction (LLM call)
 * 3. Persist results in a short write transaction
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionCompressionHelper {

    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final ConversationCompressor conversationCompressor;
    private final ObjectProvider<SessionCompressionHelper> self;
    // rev-103: Hermes parity (conversation_compression.py:2961) — on_pre_compress
    // lets the memory provider surface insights INTO the compression summary
    // before context is discarded. Optional: absent manager → no memory context.
    private final ObjectProvider<com.azhukov.agent.core.memory.MemoryManager> memoryManagerProvider;

    /**
     * Main entry point — no @Transactional here so the LLM call runs without
     * holding a DB connection. Delegates to transactional inner methods.
     */
    @Retryable(retryFor = {org.springframework.dao.OptimisticLockingFailureException.class,
                           org.springframework.dao.PessimisticLockingFailureException.class},
               maxAttempts = 3,
               backoff = @Backoff(delay = 1000, multiplier = 2))
    public void compressSessionInternal(UUID sessionId, String focusTopic, Integer keepLastN) {
        // Use self-proxy to engage @Transactional proxies on inner methods
        // (same pattern as CheckpointManager — avoids self-invocation bypass).
        SessionCompressionHelper proxy = self.getObject();
        // Record the cutoff BEFORE reading messages — any message persisted
        // after this timestamp (during the LLM compression call) is preserved.
        Instant cutoff = Instant.now();
        // 1. Read messages in a short read-only transaction
        List<Message> messages = proxy.readMessages(sessionId);
        if (messages.size() <= 4) return;

        // 2. Compress OUTSIDE any transaction (LLM call may take 10-60+ seconds)
        // rev-103: Hermes parity (conversation_compression.py:2954-2961) —
        // notify the memory provider before context is discarded; if it
        // returns insight text, prepend it to the summary so memory survives
        // compression.
        String memoryContext = collectMemoryContext(sessionId, messages);
        List<Message> compressed;
        if (keepLastN != null && keepLastN > 0) {
            compressed = conversationCompressor.compressPartial(messages, keepLastN);
        } else {
            compressed = conversationCompressor.compress(messages, focusTopic);
        }
        if (!memoryContext.isBlank()) {
            compressed = injectMemoryContext(compressed, memoryContext);
        }

        // 3. Persist results in a short write transaction (race-safe)
        proxy.persistCompressed(sessionId, compressed, cutoff);
    }

    /**
     * Read and map messages in a short read-only transaction.
     */
    @Transactional(readOnly = true)
    List<Message> readMessages(UUID sessionId) {
        List<MessageEntity> messageEntities = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return messageEntities.stream()
            .map(messageMapper::toDomain)
            .toList();
    }

    /**
     * rev-103: Hermes parity — collect the memory provider's pre-compress
     * insights. Failure-tolerant (Hermes wraps in try/except too).
     */
    private String collectMemoryContext(UUID sessionId, List<Message> messages) {
        try {
            var manager = memoryManagerProvider.getIfAvailable();
            if (manager != null && manager.hasProviders()) {
                String ctx = manager.onPreCompress(String.valueOf(sessionId), messages);
                if (ctx != null) {
                    // Fence the injected text (Hermes sanitize_memory_context)
                    return MemoryContextFence.sanitizeContext(ctx);
                }
            }
        } catch (Exception e) {
            log.debug("onPreCompress failed (ignored): {}", e.getMessage());
        }
        return "";
    }

    /**
     * rev-103: prepend the memory insights to the compression summary message
     * (the system message carrying the summary). Hermes forwards
     * memory_context into compress_kwargs; the summary keeps it at the top.
     */
    private List<Message> injectMemoryContext(List<Message> compressed, String memoryContext) {
        List<Message> result = new java.util.ArrayList<>(compressed.size());
        boolean injected = false;
        for (Message m : compressed) {
            if (!injected
                    && m.role() == com.azhukov.agent.core.model.Role.SYSTEM
                    && m.content() != null
                    && (m.content().contains("[Earlier conversation")
                        || m.content().contains("[Conversation Summary]"))) {
                result.add(Message.system(
                    "[Memory insights]\n" + memoryContext + "\n\n" + m.content()));
                injected = true;
            } else {
                result.add(m);
            }
        }
        if (!injected && !result.isEmpty()) {
            // No summary marker found — insert after the first system message.
            int insertAt = result.get(0).role() == com.azhukov.agent.core.model.Role.SYSTEM ? 1 : 0;
            result.add(insertAt, Message.system("[Memory insights]\n" + memoryContext));
        }
        return result;
    }

    /**
     * Delete old messages and persist compressed versions in a short transaction.
     * <p>
     * Race-safe: only deletes messages that existed at the start of compression
     * (identified by a cutoff timestamp), NOT messages added during the LLM
     * compression call (10-60s window). Without this guard, a new message
     * persisted mid-compression would be deleted by {@code deleteAll(existing)}.
     */
    @Transactional
    void persistCompressed(UUID sessionId, List<Message> compressed, Instant cutoffTimestamp) {
        // Delete only messages that existed before compression started
        messageRepository.deleteBySessionIdAndCreatedAtBefore(sessionId, cutoffTimestamp);
        Instant now = Instant.now();
        for (Message m : compressed) {
            MessageEntity e = messageMapper.toEntity(m);
            e.setSessionId(sessionId);
            e.setCreatedAt(now);
            messageRepository.save(e);
        }
    }
}