package com.azhukov.agent.service;

import com.azhukov.agent.core.memory.MemoryContextFence;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.service.ToolResultNameResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@Slf4j
public class SessionCompressionHelper {

    @org.springframework.beans.factory.annotation.Autowired
    public SessionCompressionHelper(
            MessageRepository messageRepository,
            MessageMapper messageMapper,
            ConversationCompressor conversationCompressor,
            org.springframework.beans.factory.ObjectProvider<SessionCompressionHelper> self,
            org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.core.memory.MemoryManager> memoryManagerProvider,
            com.azhukov.agent.persistence.repository.SessionRepository sessionRepository) {
        this.messageRepository = messageRepository;
        this.messageMapper = messageMapper;
        this.conversationCompressor = conversationCompressor;
        this.self = self;
        this.memoryManagerProvider = memoryManagerProvider;
        this.sessionRepository = sessionRepository;
    }

    private final MessageRepository messageRepository;
    private final com.azhukov.agent.persistence.repository.SessionRepository sessionRepository;
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
        proxy.persistCompressed(sessionId, compressed, cutoff, compressionWatermarkIds);
    }

    /**
     * Read and map messages in a short read-only transaction.
     */
    @Transactional(readOnly = true)
    List<Message> readMessages(UUID sessionId) {
        List<MessageEntity> messageEntities = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        // Capture the compression-start watermark (Hermes get_active_message_watermark):
        // ids active at the moment the snapshot was taken. Rows absent from this set
        // at persist time arrived DURING the LLM call and must survive compaction.
        compressionWatermarkIds = messageIds(
            messageEntities.stream().filter(SessionCompressionHelper::isActive).toList());
        return messageEntities.stream()
            .filter(SessionCompressionHelper::isActive)
            .map(messageMapper::toDomain)
            .toList();
    }

    /** Compression-start watermark: active row ids when the snapshot was read. */
    private transient java.util.Set<UUID> compressionWatermarkIds;

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
    void persistCompressed(UUID sessionId, List<Message> compressed, Instant cutoffTimestamp) {
        persistCompressed(sessionId, compressed, cutoffTimestamp, null);
    }

    @Transactional
    void persistCompressed(UUID sessionId, List<Message> compressed, Instant cutoffTimestamp,
                           java.util.Set<UUID> watermarkIds) {
        // Hermes archive_and_compact parity (hermes_state.py:11191): old rows are
        // soft-archived (active=false, compacted=true) in one saveAll — NOT deleted, so
        // session_search keeps finding them and the transcript stays recoverable.
        // Concurrent-append safety (#75316): rows that arrived DURING the slow LLM call
        // (absent from the compression-start watermark) are re-sequenced after the
        // compacted set by a column clone. watermarkIds mirrors Hermes's
        // get_active_message_watermark captured at compression START; when absent we
        // fall back to the cutoff timestamp.
        List<MessageEntity> currentActive = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
            .stream()
            .filter(SessionCompressionHelper::isActive)
            .toList();
        java.util.Set<UUID> originalIds = watermarkIds != null ? watermarkIds : messageIds(
            currentActive.stream()
                .filter(entity -> entity.getCreatedAt() != null
                    && cutoffTimestamp != null
                    && entity.getCreatedAt().isBefore(cutoffTimestamp))
                .toList());
        List<MessageEntity> concurrentTail = currentActive.stream()
            .filter(entity -> entity.getId() != null && !originalIds.contains(entity.getId()))
            .toList();
        for (MessageEntity old : currentActive) {
            old.setActive(false);
            old.setCompacted(true);
        }
        messageRepository.saveAll(currentActive);
        Instant now = Instant.now();
        int sequence = 0;
        Map<String, String> toolNamesByCallId = ToolResultNameResolver.collect(compressed);
        for (Message m : compressed) {
            MessageEntity e = messageMapper.toEntity(m);
            ToolResultNameResolver.apply(e, m, toolNamesByCallId);
            e.setSessionId(sessionId);
            e.setCreatedAt(now.plusNanos(sequence++));
            e.setActive(true);
            e.setCompacted(false);
            messageRepository.save(e);
        }
        for (MessageEntity tail : concurrentTail) {
            MessageEntity clone = cloneLiveMessage(tail);
            clone.setSessionId(sessionId);
            clone.setCreatedAt(now.plusNanos(sequence++));
            clone.setActive(true);
            clone.setCompacted(false);
            messageRepository.save(clone);
        }
        if (sessionRepository != null) {
            sessionRepository.updateLastActiveAndMessageCount(sessionId, now, compressed.size() + concurrentTail.size());
        }
    }

    private static boolean isActive(MessageEntity entity) {
        return entity != null && !Boolean.FALSE.equals(entity.getActive());
    }

    private static java.util.Set<UUID> messageIds(List<MessageEntity> messages) {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (MessageEntity message : messages) {
            if (message != null && message.getId() != null) {
                ids.add(message.getId());
            }
        }
        return ids;
    }

    private static MessageEntity cloneLiveMessage(MessageEntity source) {
        MessageEntity clone = new MessageEntity();
        clone.setRole(source.getRole());
        clone.setContent(source.getContent());
        clone.setToolCallId(source.getToolCallId());
        clone.setToolCallName(source.getToolCallName());
        clone.setToolCallArguments(source.getToolCallArguments());
        clone.setToolCalls(source.getToolCalls());
        clone.setTurnIndex(source.getTurnIndex());
        clone.setImageCount(source.getImageCount());
        return clone;
    }

    /** Back-compat 5-arg ctor for existing unit tests: no SessionRepository touch. */
    public SessionCompressionHelper(
            MessageRepository messageRepository,
            MessageMapper messageMapper,
            ConversationCompressor conversationCompressor,
            ObjectProvider<SessionCompressionHelper> self,
            ObjectProvider<com.azhukov.agent.core.memory.MemoryManager> memoryManagerProvider) {
        this(messageRepository, messageMapper, conversationCompressor, self, memoryManagerProvider, null);
    }

    /** PR-3 parity ctor: direct deps for standalone tests. Not the Spring ctor. */
    public SessionCompressionHelper(
            com.azhukov.agent.persistence.repository.MessageRepository messageRepository,
            com.azhukov.agent.persistence.mapper.MessageMapper messageMapper,
            ConversationCompressor conversationCompressor,
            com.azhukov.agent.persistence.repository.SessionRepository sessionRepository) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.messageMapper = messageMapper;
        this.conversationCompressor = conversationCompressor;
        this.self = new org.springframework.beans.factory.ObjectProvider<SessionCompressionHelper>() {
            @Override public SessionCompressionHelper getObject(Object... args) { return SessionCompressionHelper.this; }
            @Override public SessionCompressionHelper getObject() { return SessionCompressionHelper.this; }
            @Override public SessionCompressionHelper getIfAvailable() { return null; }
            @Override public SessionCompressionHelper getIfUnique() { return null; }
        };
        this.memoryManagerProvider = new org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.core.memory.MemoryManager>() {
            @Override public com.azhukov.agent.core.memory.MemoryManager getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public com.azhukov.agent.core.memory.MemoryManager getObject() { throw new UnsupportedOperationException(); }
            @Override public com.azhukov.agent.core.memory.MemoryManager getIfAvailable() { return null; }
            @Override public com.azhukov.agent.core.memory.MemoryManager getIfUnique() { return null; }
        };
    }

}