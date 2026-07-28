package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class DefaultContextEngine implements ContextEngine {

    private static final int RECALL_LIMIT = 5;
    private static final int SKILL_LIMIT = 3;
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    private final MemoryProvider memoryProvider;
    private final SkillManager skillManager;
    private final MessageRepository messageRepository;
    private final ContextCompressor contextCompressor;
    private final AgentProperties.ContextProperties contextProps;
    private final PromptCacheTracker cacheTracker;
    private final java.util.Map<UUID, Map<String, String>> snapshotCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, String> lastMemoryHash = new java.util.concurrent.ConcurrentHashMap<>();

    public DefaultContextEngine(MemoryProvider memoryProvider,
                                SkillManager skillManager,
                                MessageRepository messageRepository,
                                ContextCompressor contextCompressor,
                                AgentProperties properties) {
        this(memoryProvider, skillManager, messageRepository, contextCompressor, properties, null);
    }

    public DefaultContextEngine(MemoryProvider memoryProvider,
                                SkillManager skillManager,
                                MessageRepository messageRepository,
                                ContextCompressor contextCompressor,
                                AgentProperties properties,
                                PromptCacheTracker cacheTracker) {
        this.memoryProvider = memoryProvider;
        this.skillManager = skillManager;
        this.messageRepository = messageRepository;
        this.contextCompressor = contextCompressor;
        this.contextProps = properties.getContext();
        this.cacheTracker = cacheTracker;
    }

    @Override
    public List<Message> prepareContext(Session session, List<Message> messages) {
        List<Message> context = new ArrayList<>();

        StringBuilder systemExtra = new StringBuilder();
        appendSkills(systemExtra);
        appendMemoryRecall(session, systemExtra);

        // Compose system message first if present
        if (!messages.isEmpty() && messages.get(0).role() == Role.SYSTEM) {
            Message base = messages.get(0);
            String systemText = base.content();
            if (!systemExtra.isEmpty()) {
                systemText = systemText + "\n\n" + systemExtra;
            }
            context.add(Message.system(systemText));
        }

        // Then add recent history (excluding the current turn messages to avoid duplication)
        appendRecentHistory(session, context);

        // Add remaining incoming messages after system
        int start = (!messages.isEmpty() && messages.get(0).role() == Role.SYSTEM) ? 1 : 0;
        context.addAll(messages.subList(start, messages.size()));

        List<Message> trimmed = trimToFit(context);
        if (estimateChars(trimmed) > contextProps.getMaxTokens() * CHARS_PER_TOKEN_ESTIMATE) {
            trimmed = contextCompressor.compress(trimmed, contextProps.getTargetTokens() * CHARS_PER_TOKEN_ESTIMATE);
        }
        return trimmed;
    }

    private List<Message> trimToFit(List<Message> context) {
        int maxMessages = contextProps.getMaxContextMessages();
        if (maxMessages <= 0) {
            maxMessages = 50;
        }
        int maxChars = contextProps.getMaxTokens() * CHARS_PER_TOKEN_ESTIMATE;
        int targetChars = contextProps.getTargetTokens() * CHARS_PER_TOKEN_ESTIMATE;

        // Always keep system message (index 0) and last user message
        if (context.size() <= maxMessages && estimateChars(context) <= maxChars) {
            return context;
        }

        List<Message> trimmed = new ArrayList<>(context);
        while (trimmed.size() > maxMessages || estimateChars(trimmed) > targetChars) {
            if (trimmed.size() <= 2) break; // keep system + latest
            // Remove oldest non-system message
            boolean removed = false;
            for (int i = 1; i < trimmed.size() - 1; i++) {
                trimmed.remove(i);
                removed = true;
                break;
            }
            if (!removed) break;
        }
        if (estimateChars(trimmed) > maxChars) {
            trimmed = new ArrayList<>(trimmed.subList(Math.max(0, trimmed.size() - 2), trimmed.size()));
            log.warn("Context exceeded hard token limit; truncated to last 2 messages");
        }
        return trimmed;
    }

    private int estimateChars(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += m.content() != null ? m.content().length() : 0;
            total += 20; // overhead per message
        }
        return total;
    }

    private void appendSkills(StringBuilder sb) {
        List<String> names = skillManager.listSkillNames();
        if (names.isEmpty()) return;

        int count = 0;
        sb.append("Available skills:\n");
        for (String name : names) {
            if (++count > SKILL_LIMIT) break;
            String content = skillManager.getSkill(name);
            if (content != null) {
                sb.append("- ").append(name).append(": ")
                  .append(content.length() > 400 ? content.substring(0, 400) + "..." : content)
                  .append("\n");
            }
        }
    }

    private void appendMemoryRecall(Session session, StringBuilder sb) {
        try {
            // Use frozen snapshot — cached per session, stable for session lifetime
            Map<String, String> snapshot = snapshotCache.computeIfAbsent(session.id(), id -> {
                try {
                    return memoryProvider.getSnapshot(session.userId());
                } catch (Exception e) {
                    log.debug("Memory snapshot failed: {}", e.getMessage());
                    return java.util.Map.of("memory", "", "user", "");
                }
            });
            String memoryBlock = snapshot.get("memory");
            String userBlock = snapshot.get("user");
            boolean added = false;
            if (memoryBlock != null && !memoryBlock.isBlank()) {
                sb.append(memoryBlock).append("\n\n");
                added = true;
            }
            if (userBlock != null && !userBlock.isBlank()) {
                sb.append(userBlock).append("\n\n");
                added = true;
            }
            if (!added) {
                // Fallback to live recall if snapshot empty
                String lastUser = findLastUserMessage(session);
                if (lastUser == null || lastUser.isBlank()) return;
                List<String> facts = memoryProvider.recall(session.userId(), lastUser, RECALL_LIMIT);
                if (facts.isEmpty()) return;
                sb.append("Relevant memory:\n");
                for (String fact : facts) {
                    sb.append("- ").append(fact).append("\n");
                }
            }
            // Check cache validity — if memory content changed, invalidate prompt cache
            if (cacheTracker != null) {
                String memoryContent = (memoryBlock != null ? memoryBlock : "") + (userBlock != null ? userBlock : "");
                String memoryHash = PromptCacheTracker.hashPrefix(memoryContent);
                String previousHash = lastMemoryHash.get(session.id());
                if (previousHash != null && !previousHash.equals(memoryHash)) {
                    log.debug("Memory changed for session {}, invalidating cache", session.id());
                    cacheTracker.invalidate(String.valueOf(session.id()));
                }
                lastMemoryHash.put(session.id(), memoryHash);
            }
        } catch (Exception e) {
            log.debug("Memory recall failed: {}", e.getMessage());
        }
    }

    private String findLastUserMessage(Session session) {
        List<MessageEntity> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.id());
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).getRole())) {
                return history.get(i).getContent();
            }
        }
        return null;
    }

    private void appendRecentHistory(Session session, List<Message> context) {
        try {
            List<MessageEntity> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.id());
            int start = Math.max(0, history.size() - contextProps.getMaxContextMessages());
            for (MessageEntity e : history.subList(start, history.size())) {
                String role = e.getRole();
                String content = e.getContent() != null ? e.getContent() : "";
                context.add(switch (role) {
                    case "assistant" -> Message.assistant(content, e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                    case "tool" -> Message.toolResult(e.getToolCallId(), content, e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                    default -> Message.user(content);
                });
            }
        } catch (Exception e) {
            log.debug("History load failed: {}", e.getMessage());
        }
    }
}
