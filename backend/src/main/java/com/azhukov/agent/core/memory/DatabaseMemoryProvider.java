package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Database-backed memory provider using PostgreSQL full-text search.
 * <p>
 * H1: Char limits are configurable via {@link AgentProperties.MemoryProperties}.
 * H2: Content is trimmed before saving (parity with Hermes + MemoryStore).
 * H3: Threat scanning is performed before store/replace (parity with MemoryStore).
 * H5: replace()/remove() are wrapped in {@link Transactional} for atomicity.
 * M1: maxFactsPerUser is enforced in store(); maxFactsPerQuery is the default
 *     limit in recall().
 * M4: read() returns plain entries joined by delimiter (no category prefixes
 *     or § header), matching {@link MemoryStore#read(String)}.
 * M6: Content is trimmed before dedup check.
 */
@Slf4j
public class DatabaseMemoryProvider implements MemoryProvider {

    private final MemoryRepository memoryRepository;
    private final AgentProperties agentProperties;
    private final MemoryThreatScanner threatScanner;

    private static final String DELIMITER = "\n§\n";
    private static final int DEFAULT_MEMORY_CHAR_LIMIT = 2200;
    private static final int DEFAULT_USER_CHAR_LIMIT = 1375;

    /**
     * Creates a provider with configurable char limits and threat scanning.
     *
     * @param memoryRepository the JPA repository
     * @param agentProperties  config for char limits and max-facts enforcement
     * @param threatScanner    scans content for prompt injection / exfiltration
     */
    public DatabaseMemoryProvider(MemoryRepository memoryRepository,
                                  AgentProperties agentProperties,
                                  MemoryThreatScanner threatScanner) {
        this.memoryRepository = memoryRepository;
        this.agentProperties = agentProperties;
        this.threatScanner = threatScanner;
    }

    /**
     * Backward-compatible constructor without threat scanning (for unit tests).
     */
    public DatabaseMemoryProvider(MemoryRepository memoryRepository) {
        this(memoryRepository, null, null);
    }

    /**
     * Constructor with properties but without threat scanner (for unit tests).
     */
    public DatabaseMemoryProvider(MemoryRepository memoryRepository, AgentProperties agentProperties) {
        this(memoryRepository, agentProperties, null);
    }

    private int memoryCharLimit() {
        if (agentProperties != null) {
            return agentProperties.getMemory().getMemoryCharLimit();
        }
        return DEFAULT_MEMORY_CHAR_LIMIT;
    }

    private int userCharLimit() {
        if (agentProperties != null) {
            return agentProperties.getMemory().getUserCharLimit();
        }
        return DEFAULT_USER_CHAR_LIMIT;
    }

    private int maxFactsPerUser() {
        if (agentProperties != null) {
            return agentProperties.getMemory().getMaxFactsPerUser();
        }
        return 1000; // default from AgentProperties.MemoryProperties
    }

    private int maxFactsPerQuery() {
        if (agentProperties != null) {
            return agentProperties.getMemory().getMaxFactsPerQuery();
        }
        return 10; // default from AgentProperties.MemoryProperties
    }

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
        return "user".equalsIgnoreCase(target) ? userCharLimit() : memoryCharLimit();
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

    /**
     * Scan content for threats (H3). Returns an error message if blocked, or null if safe.
     */
    private String scanForThreats(String content) {
        if (threatScanner == null || content == null) {
            return null;
        }
        Optional<String> threat = threatScanner.scan(content);
        return threat.orElse(null);
    }

    @Override
    public String name() {
        return "builtin";
    }

    /**
     * Full-text recall. Uses FTS when the query is non-empty, falls back to
     * non-FTS listing when the query is blank.
     * <p>
     * M1: Uses {@link AgentProperties.MemoryProperties#getMaxFactsPerQuery()} as
     * the default limit when the caller passes 0 or a negative limit.
     *
     * @param userId the user ID
     * @param query  the search query (may be empty for a non-FTS listing)
     * @param limit  max results (0 or negative → uses maxFactsPerQuery config)
     */
    @Override
    public List<String> recall(String userId, String query, int limit) {
        int effectiveLimit = limit > 0 ? limit : maxFactsPerQuery();
        if (query == null || query.isBlank()) {
            // C1: Use non-FTS query when the search string is empty
            return memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, "memory")
                .stream()
                .limit(effectiveLimit)
                .map(MemoryEntity::getFact)
                .toList();
        }
        return memoryRepository.searchByUserId(userId, query, effectiveLimit).stream()
            .map(MemoryEntity::getFact)
            .toList();
    }

    @Override
    @Transactional
    public void store(String userId, String category, String fact) {
        store(userId, "memory", category, fact);
    }

    @Override
    @Transactional
    public void store(String userId, String target, String category, String fact) {
        // H2: Trim content before saving
        String trimmedFact = fact != null ? fact.trim() : fact;
        if (trimmedFact == null || trimmedFact.isBlank()) {
            return;
        }

        // H3: Threat scan before storing
        String threatMsg = scanForThreats(trimmedFact);
        if (threatMsg != null) {
            throw new IllegalStateException("Blocked: " + threatMsg);
        }

        // Drift signal #2: entry-size overflow check (parity with Hermes _detect_external_drift)
        String sizeError = checkFactSize(target, trimmedFact);
        if (sizeError != null) {
            throw new IllegalStateException(sizeError);
        }

        String effectiveTarget = target != null ? target : "memory";

        // Overflow check: total store chars must not exceed limit after adding
        // Parity with Hermes add() lines 328-341
        int limit = charLimitFor(effectiveTarget);
        List<String> existingFacts = memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, effectiveTarget)
            .stream().map(MemoryEntity::getFact).toList();

        // M6: Trim before dedup check
        if (existingFacts.stream().anyMatch(e -> e != null && e.trim().equals(trimmedFact))) {
            return;
        }

        // M1: Enforce maxFactsPerUser
        int maxFacts = maxFactsPerUser();
        if (maxFacts > 0 && existingFacts.size() >= maxFacts) {
            throw new IllegalStateException(
                "Memory store '" + effectiveTarget + "' has reached the maximum of "
                + maxFacts + " facts per user. Remove stale entries before adding new ones."
            );
        }

        int currentChars = existingFacts.isEmpty() ? 0 : String.join(DELIMITER, existingFacts).length();
        int newTotal = currentChars + trimmedFact.length() + (existingFacts.isEmpty() ? 0 : DELIMITER.length());
        if (newTotal > limit) {
            // Hermes parity (memory_tool.py:454 _consolidation_failure): the
            // error carries current_entries so the model can consolidate
            // without an extra read — the structured clients that hit this
            // path otherwise dead-end on a bare usage string.
            throw new IllegalStateException(
                "Memory at " + String.format("%,d", currentChars) + "/" + String.format("%,d", limit) + " chars. "
                + "Adding this entry (" + trimmedFact.length() + " chars) would exceed the limit. "
                + "Consolidate now: use 'replace' to merge overlapping entries into shorter ones "
                + "or 'remove' stale or less important entries, then retry this add — all in this turn. "
                + "(see current_entries below)\ncurrent_entries:\n"
                + String.join("\n", existingFacts)
            );
        }

        MemoryEntity e = new MemoryEntity();
        e.setUserId(userId);
        e.setCategory(category);
        e.setFact(trimmedFact);
        e.setTarget(effectiveTarget);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        try {
            memoryRepository.save(e);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new IllegalStateException(driftErrorFromLock(effectiveTarget), ex);
        }
    }

    @Override
    @Transactional
    public String applyBatch(String userId, String target,
                             List<MemoryBatchOperation> operations,
                             Map<String, String> provenance) {
        if (operations == null || operations.isEmpty()) {
            return "operations list is empty.";
        }
        String effectiveTarget = target != null ? target : "memory";
        List<MemoryEntity> working = new java.util.ArrayList<>(
            memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, effectiveTarget));
        int limit = charLimitFor(effectiveTarget);

        // Validate and apply to an in-memory working copy first. No database
        // mutation happens until EVERY operation and the final char budget pass.
        for (int i = 0; i < operations.size(); i++) {
            MemoryBatchOperation operation = operations.get(i);
            String action = operation == null || operation.action() == null
                ? "" : operation.action().trim().toLowerCase();
            String content = operation == null || operation.content() == null
                ? "" : operation.content().trim();
            String oldText = operation == null || operation.oldText() == null
                ? "" : operation.oldText().trim();
            String position = "Operation " + (i + 1) + " (" + action + ")";

            switch (action) {
                case "add" -> {
                    if (content.isBlank()) return position + ": content is required.";
                    String threat = scanForThreats(content);
                    if (threat != null) return position + ": Blocked: " + threat;
                    if (working.stream().anyMatch(e -> e.getFact() != null && e.getFact().trim().equals(content))) {
                        continue; // Hermes idempotent duplicate add
                    }
                    MemoryEntity entity = new MemoryEntity();
                    entity.setUserId(userId);
                    entity.setTarget(effectiveTarget);
                    entity.setCategory("auto");
                    entity.setFact(content);
                    entity.setCreatedAt(Instant.now());
                    entity.setUpdatedAt(Instant.now());
                    working.add(entity);
                }
                case "replace" -> {
                    if (oldText.isBlank()) return position + ": old_text is required.";
                    if (content.isBlank()) return position + ": content is required (use action='remove' to delete).";
                    String threat = scanForThreats(content);
                    if (threat != null) return position + ": Blocked: " + threat;
                    List<MemoryEntity> matches = working.stream()
                        .filter(e -> e.getFact() != null && e.getFact().contains(oldText)).toList();
                    if (matches.isEmpty()) return position + ": no entry matched '" + oldText + "'.";
                    if (matches.stream().map(MemoryEntity::getFact).distinct().count() > 1) {
                        return position + ": '" + oldText + "' matched multiple distinct entries -- be more specific.";
                    }
                    matches.getFirst().setFact(content);
                    matches.getFirst().setUpdatedAt(Instant.now());
                }
                case "remove" -> {
                    if (oldText.isBlank()) return position + ": old_text is required.";
                    List<MemoryEntity> matches = working.stream()
                        .filter(e -> e.getFact() != null && e.getFact().contains(oldText)).toList();
                    if (matches.isEmpty()) return position + ": no entry matched '" + oldText + "'.";
                    if (matches.stream().map(MemoryEntity::getFact).distinct().count() > 1) {
                        return position + ": '" + oldText + "' matched multiple distinct entries -- be more specific.";
                    }
                    working.remove(matches.getFirst());
                }
                default -> { return position + ": unknown action. Use add, replace, or remove."; }
            }
        }

        if (maxFactsPerUser() > 0 && working.size() > maxFactsPerUser()) {
            return "Memory store '" + effectiveTarget + "' would exceed the maximum of "
                + maxFactsPerUser() + " facts per user.";
        }
        int finalChars = working.isEmpty() ? 0 : String.join(DELIMITER,
            working.stream().map(MemoryEntity::getFact).toList()).length();
        if (finalChars > limit) {
            return "After applying all " + operations.size() + " operations, memory would be at "
                + finalChars + "/" + limit + " chars.";
        }

        List<MemoryEntity> original = memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, effectiveTarget);
        try {
            // Delete only rows absent from the validated final state, then save all
            // remaining/created rows. Transaction rolls back on an optimistic-lock error.
            for (MemoryEntity entity : original) {
                if (!working.contains(entity)) memoryRepository.delete(entity);
            }
            for (MemoryEntity entity : working) memoryRepository.save(entity);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new IllegalStateException(driftErrorFromLock(effectiveTarget), ex);
        }
        return null;
    }

    @Override
    @Transactional
    public String replace(String userId, String target, String oldText, String newText) {
        // H2: Trim new content before saving
        String trimmedNewText = newText != null ? newText.trim() : newText;
        if (trimmedNewText == null || trimmedNewText.isBlank()) {
            return "content is required";
        }

        // H3: Threat scan before replacing
        String threatMsg = scanForThreats(trimmedNewText);
        if (threatMsg != null) {
            return "Blocked: " + threatMsg;
        }

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
        String sizeError = checkFactSize(target, trimmedNewText);
        if (sizeError != null) {
            return sizeError;
        }
        // Overflow check: after replacement, total store chars must not exceed limit
        // Parity with Hermes replace() lines 389-406
        int limit = charLimitFor(target);
        List<String> allFacts = all.stream().map(MemoryEntity::getFact).toList();
        int replaceIdx = all.indexOf(matches.get(0));
        List<String> testEntries = new java.util.ArrayList<>(allFacts);
        testEntries.set(replaceIdx, trimmedNewText);
        int newTotal = String.join(DELIMITER, testEntries).length();
        if (newTotal > limit) {
            int current = String.join(DELIMITER, allFacts).length();
            return "Replacement would put memory at " + newTotal + "/" + limit + " chars. "
                + "Shorten the new content, or 'remove' other stale or less important entries "
                + "to make room, then retry — all in this turn.";
        }
        MemoryEntity e = matches.get(0);
        e.setFact(trimmedNewText);
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
    @Transactional
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

    /**
     * M4: Returns plain entries joined by delimiter (matching {@link MemoryStore#read(String)}).
     * No category prefixes, no § header.
     */
    @Override
    public String read(String userId, String target) {
        List<MemoryEntity> entries = memoryRepository.findByUserIdAndTargetOrderByCreatedAtDesc(userId, target);
        if (entries.isEmpty()) {
            return "";
        }
        List<String> facts = entries.stream().map(MemoryEntity::getFact).toList();
        return String.join(DELIMITER, facts);
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