package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.persistence.entity.CompressionLockEntity;
import com.azhukov.agent.persistence.repository.CompressionLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefaultContextCompressor implements ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextCompressor.class);

    private final ModelClient modelClient;
    private final CompressionLockRepository lockRepository;
    private final AgentProperties properties;
    private final ConcurrentHashMap<String, Integer> inMemoryLocks = new ConcurrentHashMap<>();

    public DefaultContextCompressor(ModelClient modelClient, CompressionLockRepository lockRepository, AgentProperties properties) {
        this.modelClient = modelClient;
        this.lockRepository = lockRepository;
        this.properties = properties;
    }

    @Override
    public List<Message> compress(List<Message> messages, int targetChars) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        int currentChars = messages.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum();
        if (currentChars <= targetChars) {
            return messages;
        }
        int keepCount = Math.max(2, messages.size() / 2);
        List<Message> head = messages.subList(0, keepCount);
        List<Message> tail = messages.subList(keepCount, messages.size());

        StringBuilder summaryInput = new StringBuilder();
        for (Message m : head) {
            if (m.content() != null) {
                summaryInput.append(m.role()).append(": ").append(m.content()).append("\n\n");
            }
        }
        String summary = summarize(summaryInput.toString());

        List<Message> compressed = new ArrayList<>();
        compressed.add(Message.system("Earlier conversation (summarized):\n" + summary));
        compressed.addAll(tail);
        return compressed;
    }

    @Override
    public boolean isLocked(String sessionId, int generation) {
        if (sessionId == null) {
            return inMemoryLocks.getOrDefault("anonymous", -1) >= generation;
        }
        Integer memoryGen = inMemoryLocks.get(sessionId);
        if (memoryGen != null && memoryGen >= generation) {
            return true;
        }
        try {
            return lockRepository.findBySessionId(UUID.fromString(sessionId)).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void lock(String sessionId, int generation) {
        if (sessionId == null) {
            inMemoryLocks.put("anonymous", generation);
            return;
        }
        inMemoryLocks.put(sessionId, generation);
        try {
            UUID uuid = UUID.fromString(sessionId);
            if (lockRepository.findBySessionId(uuid).isEmpty()) {
                CompressionLockEntity lock = new CompressionLockEntity();
                lock.setSessionId(uuid);
                lockRepository.save(lock);
            }
        } catch (IllegalArgumentException e) {
            log.debug("Cannot persist compression lock for non-uuid session {}", sessionId);
        }
    }

    private String summarize(String text) {
        try {
            String prompt = "Summarize the following conversation history into a concise memory that captures facts, decisions, and pending tasks. Keep under 500 tokens.\n\n" + text;
            ChatResponse response = modelClient.complete(
                List.of(Message.system("You are a summarizer."), Message.user(prompt)),
                List.of()
            );
            String result = response.content();
            return result != null && !result.isBlank() ? result : fallbackSummarize(text);
        } catch (Exception e) {
            log.warn("LLM compression failed, using fallback truncation", e);
            return fallbackSummarize(text);
        }
    }

    private String fallbackSummarize(String text) {
        int limit = properties.getContext().getMaxTokens();
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n\n[truncated]";
    }
}
