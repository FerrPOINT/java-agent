package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class DatabaseMemoryProvider implements MemoryProvider {

    private final MemoryRepository memoryRepository;

    private static final int MEMORY_CHAR_LIMIT = 2200;
    private static final int USER_CHAR_LIMIT = 1375;
    private static final String DELIMITER = "\n§\n";

    /**
     * Drift error message — equivalent to Hermes _drift_error().
     * Triggered when @Version optimistic lock detects a concurrent modification,
     * OR when a fact exceeds the store's char limit (external writer appended
     * oversized content, matching Hermes signal #2: entry-size overflow).
     */
    private static String driftError(String target, String detail) {
        return "Refusing to write memory target '" + target + "': " + detail
            + ". A concurrent session or external tool modified this entry"
            + " since it was read. Resolve the drift first — re-read the entry,"
            + " integrate any external changes, then retry. This guard exists"
            + " to prevent silent data loss (parity with Hermes issue #26045).";
    }

    private static String driftErrorFromLock(String target) {
        return driftError(target, "optimistic lock conflict detected");
    }

    private static String driftErrorFromSize(String target, int actualLen, int limit) {
        return driftError(target, "entry size (" + actualLen + ") exceeds the store's char limit (" + limit + ")");
    }

    private int charLimitFor(String target) {
        return "user".equalsIgnoreCase(target) ? USER_CHAR_LIMIT : MEMORY_CHAR_LIMIT;
    }

    /**
     * Check if a fact's length exceeds the store's char limit — Hermes drift signal #2.
     */
    private String checkFactSize(String target, String fact) {
        if (fact == null) return null;
        int limit = charLimitFor(target);
        if (fact.length() > limit) {
            return driftErrorFromSize(target, fact.length(), limit);
        }
        return null;
    }

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
        // Drift signal #2: entry-size overflow check (parity with Hermes _detect_external_drift)
        String sizeError = checkFactSize(target, fact);
        if (sizeError != null) {
            throw new IllegalStateException(sizeError);
        }
        // Overflow check: total store chars must not exceed limit after adding
        // Parity with Hermes add() lines 328-341
        int limit = charLimitFor(target);
        List<String> existingFacts = memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, target)
            .stream().map(MemoryEntity::getFact).toList();
        // Dedup: if exact duplicate, no-op (parity with Hermes add())
        if (existingFacts.contains(fact)) {
            return;
        }
        int currentChars = existingFacts.isEmpty() ? 0 : String.join(DELIMITER, existingFacts).length();
        int newTotal = currentChars + fact.length() + (existingFacts.isEmpty() ? 0 : DELIMITER.length());
        if (newTotal > limit) {
            throw new IllegalStateException(
                "Memory at " + currentChars + "/" + limit + " chars. "
                + "Adding this entry (" + fact.length() + " chars) would exceed the limit. "
                + "Consolidate now: use 'replace' to merge overlapping entries into shorter ones "
                + "or 'remove' stale or less important entries, then retry this add — all in this turn."
            );
        }
        MemoryEntity e = new MemoryEntity();
        e.setUserId(userId);
        e.setCategory(category);
        e.setFact(fact);
        e.setTarget(target != null ? target : "memory");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        try {
            memoryRepository.save(e);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new IllegalStateException(driftErrorFromLock(target), ex);
        }
    }

    @Override
    public String replace(String userId, String target, String oldText, String newText) {
        // Parity with Hermes: substring match (contains), not exact equals
        List<MemoryEntity> all = memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, target);
        List<MemoryEntity> matches = all.stream()
            .filter(e -> e.getFact() != null && e.getFact().contains(oldText))
            .toList();
        if (matches.isEmpty()) {
            return "No entry found containing: " + oldText;
        }
        // Parity with Hermes: refuse if multiple UNIQUE entries match — ambiguous replacement
        if (matches.size() > 1) {
            long uniqueCount = matches.stream().map(MemoryEntity::getFact).distinct().count();
            if (uniqueCount > 1) {
                // Build previews like Hermes: e[:80] + "..." if len > 80
                StringBuilder sb = new StringBuilder();
                sb.append("Multiple entries match '").append(oldText).append("'. Be more specific:");
                int i = 1;
                for (MemoryEntity m : matches) {
                    String fact = m.getFact();
                    String preview = fact.length() > 80 ? fact.substring(0, 80) + "..." : fact;
                    sb.append("\n").append(i++).append(". ").append(preview);
                }
                return sb.toString();
            }
            // All identical — safe to replace first
        }
        // Drift signal #2: entry-size overflow check on new content
        String sizeError = checkFactSize(target, newText);
        if (sizeError != null) {
            return sizeError;
        }
        // Overflow check: after replacement, total store chars must not exceed limit
        // Parity with Hermes replace() lines 389-406
        int limit = charLimitFor(target);
        List<String> allFacts = all.stream().map(MemoryEntity::getFact).toList();
        int replaceIdx = all.indexOf(matches.get(0));
        List<String> testEntries = new java.util.ArrayList<>(allFacts);
        testEntries.set(replaceIdx, newText);
        int newTotal = String.join(DELIMITER, testEntries).length();
        if (newTotal > limit) {
            int current = String.join(DELIMITER, allFacts).length();
            return "Replacement would put memory at " + newTotal + "/" + limit + " chars. "
                + "Shorten the new content, or 'remove' other stale or less important entries "
                + "to make room, then retry — all in this turn.";
        }
        MemoryEntity e = matches.get(0);
        e.setFact(newText);
        e.setUpdatedAt(Instant.now());
        try {
            memoryRepository.save(e);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.warn("Optimistic lock conflict on memory replace for user={}, target={}: {}",
                userId, target, ex.getMessage());
            return driftErrorFromLock(target);
        }
        return null;
    }

    @Override
    public String remove(String userId, String target, String oldText) {
        // Parity with Hermes: substring match (contains), not exact equals
        List<MemoryEntity> all = memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, target);
        List<MemoryEntity> matches = all.stream()
            .filter(e -> e.getFact() != null && e.getFact().contains(oldText))
            .toList();
        if (matches.isEmpty()) {
            return "No entry found containing: " + oldText;
        }
        // Parity with Hermes: refuse if multiple UNIQUE entries match — ambiguous deletion
        if (matches.size() > 1) {
            long uniqueCount = matches.stream().map(MemoryEntity::getFact).distinct().count();
            if (uniqueCount > 1) {
                // Build previews like Hermes: e[:80] + "..." if len > 80
                StringBuilder sb = new StringBuilder();
                sb.append("Multiple entries match '").append(oldText).append("'. Be more specific:");
                int i = 1;
                for (MemoryEntity m : matches) {
                    String fact = m.getFact();
                    String preview = fact.length() > 80 ? fact.substring(0, 80) + "..." : fact;
                    sb.append("\n").append(i++).append(". ").append(preview);
                }
                return sb.toString();
            }
            // All identical — safe to remove first
        }
        try {
            memoryRepository.delete(matches.get(0));
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.warn("Optimistic lock conflict on memory remove for user={}, target={}: {}",
                userId, target, ex.getMessage());
            return driftErrorFromLock(target);
        }
        return null;
    }

    @Override
    public List<String> getRawEntries(String userId, String target) {
        return memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, target).stream()
            .map(MemoryEntity::getFact)
            .toList();
    }

    @Override
    public int getCharCount(String userId, String target) {
        List<String> entries = getRawEntries(userId, target);
        if (entries.isEmpty()) {
            return 0;
        }
        return String.join(DELIMITER, entries).length();
    }

    @Override
    public int getEntryCount(String userId, String target) {
        return getRawEntries(userId, target).size();
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
            sb.append("[").append(e.getCategory()).append("] ").append(e.getFact()).append(DELIMITER);
        }
        // Remove trailing delimiter
        String result = sb.toString();
        if (result.endsWith(DELIMITER)) {
            result = result.substring(0, result.length() - DELIMITER.length());
        }
        return result.trim();
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