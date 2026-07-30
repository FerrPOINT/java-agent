package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * S2/S15/S16: Curator service — skill lifecycle management with LLM-driven consolidation.
 * <p>
 * Periodically reviews skills: classifies active/stale, archives stale skills,
 * and suggests consolidation of similar skills using an LLM-driven umbrella-building
 * prompt. Before any mutation, creates a backup snapshot via CuratorBackupService.
 * <p>
 * Ported from Hermes' curator.py.
 */
@Service
@Slf4j
public class CuratorService {

    private final SkillRepository skillRepository;
    private final AgentProperties properties;
    private final ModelClient modelClient;
    private final CuratorBackupService backupService;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-curator");
        t.setDaemon(true);
        return t;
    });

    // Protected skills — never archive these
    private static final List<String> PROTECTED_SKILLS = List.of(
        "hermes-agent", "hermes-agent-dev", "backend-dev", "default"
    );

    // Configurable stale threshold (default 7 days)
    private static final Duration STALE_THRESHOLD = Duration.ofDays(7);
    // Configurable review interval (default 24h)
    private static final Duration REVIEW_INTERVAL = Duration.ofHours(24);

    private volatile boolean started = false;

    /** Manual constructor for tests — no LLM, no backup. */
    public CuratorService(SkillRepository skillRepository, AgentProperties properties) {
        this.skillRepository = skillRepository;
        this.properties = properties;
        this.modelClient = null;
        this.backupService = null;
    }

    /** Full constructor with LLM and backup support. */
    @org.springframework.beans.factory.annotation.Autowired
    public CuratorService(SkillRepository skillRepository, AgentProperties properties,
                          ModelClient modelClient, CuratorBackupService backupService) {
        this.skillRepository = skillRepository;
        this.properties = properties;
        this.modelClient = modelClient;
        this.backupService = backupService;
    }

    /**
     * Start the periodic curator review.
     */
    public void start() {
        if (started) return;
        started = true;
        executor.scheduleAtFixedRate(
            this::runCuratorCycle,
            REVIEW_INTERVAL.toMinutes(),
            REVIEW_INTERVAL.toMinutes(),
            TimeUnit.MINUTES
        );
        log.info("Curator service started — review interval: {} minutes", REVIEW_INTERVAL.toMinutes());
    }

    /**
     * Run a single curator cycle manually (for testing or manual trigger).
     */
    public CuratorReport runCycle() {
        return runCuratorCycle();
    }

    private CuratorReport runCuratorCycle() {
        try {
            log.debug("Starting curator cycle");
            Instant staleBefore = Instant.now().minus(STALE_THRESHOLD);

            // S15: Create backup snapshot before any mutation
            if (backupService != null) {
                try {
                    var snapshot = backupService.createSnapshot("curator-cycle");
                    if (snapshot != null) {
                        log.info("Curator backup snapshot created: {}", snapshot.id());
                    }
                } catch (Exception e) {
                    log.warn("Failed to create curator backup: {}", e.getMessage());
                }
            }

            // S2: Classify skills
            List<SkillEntity> allSkills = skillRepository.findByArchivedFalse();
            List<String> active = new ArrayList<>();
            List<String> stale = new ArrayList<>();
            List<String> archived = new ArrayList<>();
            List<ConsolidationSuggestion> suggestions = new ArrayList<>();

            for (SkillEntity skill : allSkills) {
                boolean isStale = skill.getLastActivityAt() == null
                    || skill.getLastActivityAt().isBefore(staleBefore);

                if (isStale && !isProtected(skill.getName())) {
                    stale.add(skill.getName());
                    // S2: Archive stale skills
                    skill.setArchived(true);
                    skill.setUpdatedAt(Instant.now());
                    skillRepository.save(skill);
                    archived.add(skill.getName());
                } else {
                    active.add(skill.getName());
                }
            }

            // S16: LLM-driven consolidation — fork an LLM call with umbrella-building prompt
            List<CuratorAction> actions = new ArrayList<>();
            if (modelClient != null) {
                suggestions = runLlmConsolidation(allSkills);
                // Convert suggestions to actions
                for (ConsolidationSuggestion s : suggestions) {
                    actions.add(new CuratorAction("CONSOLIDATE", s.suggestedUmbrellaName(),
                        s.skillsToMerge(), s.reason()));
                }
            } else {
                // Fallback: heuristic consolidation (original path)
                suggestions = findConsolidationOpportunities(allSkills);
                for (ConsolidationSuggestion s : suggestions) {
                    actions.add(new CuratorAction("CONSOLIDATE", s.suggestedUmbrellaName(),
                        s.skillsToMerge(), s.reason()));
                }
            }

            // Add archive actions
            for (String archivedName : archived) {
                actions.add(new CuratorAction("ARCHIVE", archivedName, List.of(archivedName),
                    "Skill marked stale and archived"));
            }

            CuratorReport report = new CuratorReport(active, stale, archived, suggestions, actions);
            log.info("Curator cycle complete: active={}, stale={}, archived={}, consolidationSuggestions={}, actions={}",
                active.size(), stale.size(), archived.size(), suggestions.size(), actions.size());

            return report;
        } catch (Exception e) {
            log.error("Curator cycle failed: {}", e.getMessage());
            return new CuratorReport(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * S16: LLM-driven consolidation — forks an LLM call with an umbrella-building prompt
     * that analyzes skills and suggests consolidation patterns.
     */
    private List<ConsolidationSuggestion> runLlmConsolidation(List<SkillEntity> skills) {
        try {
            String prompt = buildConsolidationPrompt(skills);
            List<Message> messages = List.of(
                Message.system(CONSOLIDATION_SYSTEM_PROMPT),
                Message.user(prompt)
            );
            ChatResponse response = modelClient.complete(messages, List.of());
            return parseConsolidationResponse(response.content(), skills);
        } catch (Exception e) {
            log.warn("LLM consolidation failed, falling back to heuristic: {}", e.getMessage());
            return findConsolidationOpportunities(skills);
        }
    }

    /**
     * S16: Build the umbrella-building consolidation prompt for the LLM.
     */
    private String buildConsolidationPrompt(List<SkillEntity> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the following skill collection and suggest consolidation.\n");
        sb.append("The goal is a LIBRARY OF CLASS-LEVEL INSTRUCTIONS. A collection of hundreds of\n");
        sb.append("narrow skills where each captures one session's specific bug is a FAILURE.\n\n");
        sb.append("Rules:\n");
        sb.append("1. DO NOT touch bundled or hub-installed skills.\n");
        sb.append("2. DO NOT delete any skill — archiving is the maximum destructive action.\n");
        sb.append("3. DO NOT touch pinned skills.\n\n");
        sb.append("Three consolidation patterns:\n");
        sb.append("a. MERGE INTO EXISTING UMBRELLA — one skill is broad enough to be the umbrella.\n");
        sb.append("b. CREATE A NEW UMBRELLA — no existing member is broad enough.\n");
        sb.append("c. DEMOTE TO REFERENCES/TEMPLATES/SCRIPTS — narrow-but-valuable content.\n\n");
        sb.append("Output format (YAML):\n");
        sb.append("```yaml\n");
        sb.append("consolidations:\n");
        sb.append("  - from: <old-skill-name>\n");
        sb.append("    into: <umbrella-skill-name>\n");
        sb.append("    reason: <one short sentence>\n");
        sb.append("prunings:\n");
        sb.append("  - name: <skill-name>\n");
        sb.append("    reason: <one short sentence>\n");
        sb.append("```\n\n");
        sb.append("Skills to analyze:\n\n");
        for (SkillEntity skill : skills) {
            if (skill.isArchived()) continue;
            sb.append("- name: ").append(skill.getName()).append("\n");
            sb.append("  category: ").append(skill.getCategory() != null ? skill.getCategory() : "unknown").append("\n");
            sb.append("  contentPreview: ").append(getPreview(skill.getContent(), 200)).append("\n");
        }
        return sb.toString();
    }

    /**
     * S16: Parse the LLM consolidation response into ConsolidationSuggestion objects.
     */
    private List<ConsolidationSuggestion> parseConsolidationResponse(String response, List<SkillEntity> skills) {
        if (response == null || response.isBlank()) {
            return findConsolidationOpportunities(skills);
        }
        List<ConsolidationSuggestion> suggestions = new ArrayList<>();
        // Parse the YAML-like structure
        boolean inConsolidations = false;
        String from = null, into = null, reason = null;
        for (String line : response.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.equals("consolidations:")) {
                inConsolidations = true;
                continue;
            }
            if (trimmed.equals("prunings:")) {
                inConsolidations = false;
                // Save any pending consolidation
                if (from != null && into != null) {
                    suggestions.add(new ConsolidationSuggestion(into, List.of(from), reason != null ? reason : "LLM suggested"));
                }
                from = null; into = null; reason = null;
                continue;
            }
            if (inConsolidations) {
                if (trimmed.startsWith("- from:")) {
                    if (from != null && into != null) {
                        suggestions.add(new ConsolidationSuggestion(into, List.of(from), reason != null ? reason : "LLM suggested"));
                    }
                    from = trimmed.substring("- from:".length()).trim();
                    into = null; reason = null;
                } else if (trimmed.startsWith("into:")) {
                    into = trimmed.substring("into:".length()).trim();
                } else if (trimmed.startsWith("reason:")) {
                    reason = trimmed.substring("reason:".length()).trim();
                }
            }
        }
        // Save last pending
        if (from != null && into != null) {
            suggestions.add(new ConsolidationSuggestion(into, List.of(from), reason != null ? reason : "LLM suggested"));
        }
        return suggestions.isEmpty() ? findConsolidationOpportunities(skills) : suggestions;
    }

    private String getPreview(String content, int maxLen) {
        if (content == null) return "";
        return content.length() <= maxLen ? content : content.substring(0, maxLen) + "...";
    }

    /**
     * S2: Check if a skill is protected (never archive).
     */
    private boolean isProtected(String skillName) {
        if (skillName == null) return false;
        return PROTECTED_SKILLS.contains(skillName.toLowerCase());
    }

    /**
     * S2: Find consolidation opportunities — skills with similar names.
     * Uses simple string similarity (shared prefix words).
     */
    private List<ConsolidationSuggestion> findConsolidationOpportunities(List<SkillEntity> skills) {
        List<ConsolidationSuggestion> suggestions = new ArrayList<>();
        // Group by first word of skill name
        ConcurrentHashMap<String, List<String>> groups = new ConcurrentHashMap<>();
        for (SkillEntity s : skills) {
            if (s.isArchived()) continue;
            String firstName = s.getName().split("-")[0];
            groups.computeIfAbsent(firstName, k -> new ArrayList<>()).add(s.getName());
        }
        // Any group with 2+ skills is a consolidation candidate
        for (var entry : groups.entrySet()) {
            if (entry.getValue().size() >= 2) {
                suggestions.add(new ConsolidationSuggestion(
                    entry.getKey() + "-umbrella",
                    entry.getValue(),
                    "Consider merging into umbrella skill: " + entry.getKey()
                ));
            }
        }
        return suggestions;
    }

    /**
     * Shutdown the curator executor.
     */
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * Rollback to a previous snapshot (if backup service is available).
     */
    public boolean rollback(java.util.UUID snapshotId) {
        if (backupService == null) {
            log.warn("Backup service not available — cannot rollback");
            return false;
        }
        return backupService.rollback(snapshotId);
    }

    /**
     * List available snapshots (if backup service is available).
     */
    public List<CuratorBackupService.CuratorSnapshot> listSnapshots() {
        if (backupService == null) {
            return List.of();
        }
        return backupService.listSnapshots();
    }

    // ── System prompt for LLM consolidation ─────────────────────────────

    private static final String CONSOLIDATION_SYSTEM_PROMPT =
        "You are running as the skill CURATOR. This is an UMBRELLA-BUILDING " +
        "consolidation pass, not a passive audit and not a duplicate-finder.\n\n" +
        "The goal of the skill collection is a LIBRARY OF CLASS-LEVEL " +
        "INSTRUCTIONS AND EXPERIENTIAL KNOWLEDGE. A collection of hundreds of " +
        "narrow skills where each one captures one session's specific bug is " +
        "a FAILURE of the library — not a feature. One broad umbrella skill " +
        "with labeled subsections beats five narrow siblings for " +
        "discoverability.\n\n" +
        "Hard rules — do not violate:\n" +
        "1. DO NOT touch bundled or hub-installed skills.\n" +
        "2. DO NOT delete any skill. Archiving is the maximum destructive action.\n" +
        "3. DO NOT touch skills shown as pinned.\n" +
        "4. DO NOT use usage counters as a reason to skip consolidation.\n" +
        "5. DO NOT reject consolidation on the grounds that 'each skill has " +
        "a distinct trigger'. The right bar is: 'would a human maintainer " +
        "write this as N separate skills, or as one skill with N labeled " +
        "subsections?' When the answer is the latter, merge.\n\n" +
        "Three ways to consolidate — use the right one per cluster:\n" +
        "a. MERGE INTO EXISTING UMBRELLA — one skill is already broad enough.\n" +
        "b. CREATE A NEW UMBRELLA SKILL — no existing member is broad enough.\n" +
        "c. DEMOTE TO REFERENCES/TEMPLATES/SCRIPTS — narrow-but-valuable content.\n\n" +
        "Output format (YAML):\n" +
        "```yaml\n" +
        "consolidations:\n" +
        "  - from: <old-skill-name>\n" +
        "    into: <umbrella-skill-name>\n" +
        "    reason: <one short sentence>\n" +
        "prunings:\n" +
        "  - name: <skill-name>\n" +
        "    reason: <one short sentence>\n" +
        "```";

    // ── Records ────────────────────────────────────────────────────────

    /**
     * S2/S16: Curator report — summary of a cycle with actions taken.
     */
    public record CuratorReport(
        List<String> activeSkills,
        List<String> staleSkills,
        List<String> archivedSkills,
        List<ConsolidationSuggestion> consolidationSuggestions,
        List<CuratorAction> actions
    ) {
        /** Backward-compatible constructor for tests that don't use actions. */
        public CuratorReport(List<String> activeSkills, List<String> staleSkills,
                             List<String> archivedSkills,
                             List<ConsolidationSuggestion> consolidationSuggestions) {
            this(activeSkills, staleSkills, archivedSkills, consolidationSuggestions, List.of());
        }
    }

    /**
     * S2: Consolidation suggestion.
     */
    public record ConsolidationSuggestion(
        String suggestedUmbrellaName,
        List<String> skillsToMerge,
        String reason
    ) {
        @Override
        public String toString() {
            return suggestedUmbrellaName + " <- " + skillsToMerge + " (" + reason + ")";
        }
    }

    /**
     * S16: A single curator action (consolidate, archive, demote, merge).
     */
    public record CuratorAction(
        String type,
        String target,
        List<String> affectedSkills,
        String reason
    ) {}
}