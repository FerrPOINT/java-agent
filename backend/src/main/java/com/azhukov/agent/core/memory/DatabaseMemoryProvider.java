package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class DatabaseMemoryProvider implements MemoryProvider {

    private final MemoryRepository memoryRepository;

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public List<String> recall(String userId, String query, int limit) {
        return memoryRepository.searchByUserId(userId, query, limit).stream()
            .map(e -> "[" + e.getCategory() + "] " + e.getFact())
            .toList();
    }

    @Override
    public void store(String userId, String category, String fact) {
        store(userId, "memory", category, fact);
    }

    @Override
    public void store(String userId, String target, String category, String fact) {
        MemoryEntity e = new MemoryEntity();
        e.setUserId(userId);
        e.setCategory(category);
        e.setFact(fact);
        e.setTarget(target != null ? target : "memory");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        memoryRepository.save(e);
    }

    @Override
    public String replace(String userId, String target, String oldText, String newText) {
        List<MemoryEntity> matches = memoryRepository.findByUserIdAndTargetAndFactContaining(userId, target, oldText);
        if (matches.isEmpty()) {
            return "No entry found containing: " + oldText;
        }
        for (MemoryEntity e : matches) {
            String updatedFact = e.getFact().replace(oldText, newText);
            e.setFact(updatedFact);
            e.setUpdatedAt(Instant.now());
            memoryRepository.save(e);
        }
        return null;
    }

    @Override
    public String remove(String userId, String target, String oldText) {
        List<MemoryEntity> matches = memoryRepository.findByUserIdAndTargetAndFactContaining(userId, target, oldText);
        if (matches.isEmpty()) {
            return "No entry found containing: " + oldText;
        }
        memoryRepository.deleteAll(matches);
        return null;
    }

    @Override
    public String read(String userId, String target) {
        List<MemoryEntity> entries = memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, target);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§ ").append(target.toUpperCase()).append("\n");
        for (MemoryEntity e : entries) {
            sb.append("[").append(e.getCategory()).append("] ").append(e.getFact()).append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public Map<String, String> getSnapshot(String userId) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("memory", read(userId, "memory"));
        snapshot.put("user", read(userId, "user"));
        return snapshot;
    }

    /**
     * S4: Fixed syncTurn — no longer writes truncated turn summaries as memory facts.
     * Instead, just logs the turn for audit purposes. The background review
     * (BackgroundReviewService) decides what's actually worth saving to memory.
     */
    @Override
    public void syncTurn(String sessionId, List<Message> turnMessages) {
        if (turnMessages == null || turnMessages.isEmpty()) {
            return;
        }
        // S4: Only log the turn for audit — do NOT write truncated summaries as memory facts.
        // The background review service handles deciding what's worth saving.
        int userMsgs = 0;
        int assistantMsgs = 0;
        for (Message m : turnMessages) {
            if (m.content() == null || m.content().isBlank()) continue;
            if (m.role() == Role.USER) userMsgs++;
            else if (m.role() == Role.ASSISTANT) assistantMsgs++;
        }
        log.debug("syncTurn audit for session {}: {} user messages, {} assistant messages", sessionId, userMsgs, assistantMsgs);
    }
}