package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * S5: Curator service — skill lifecycle management with LLM-driven consolidation.
 * <p>
 * Periodically reviews skills: classifies active/stale/archived, archives stale skills,
 * and suggests consolidation of similar skills using an LLM-driven umbrella-building
 * prompt. Before any mutation, creates a backup snapshot via CuratorBackupService.
 * <p>
 * Ported from Hermes' curator.py. Key S5 fixes:
 * <ul>
 *   <li>Config-driven interval (7-day default, configurable via AgentProperties)</li>
 *   <li>Pause/unpause (setPaused, isPaused)</li>
 *   <li>Idle gating (maybe_run_curator + min_idle_hours)</li>
 *   <li>First-run deferral (seed last_run_at, wait 1 interval before first run)</li>
 *   <li>State persistence (.curator_state JSON file with last_run_at, paused, run_count, summary)</li>
 *   <li>Three-state lifecycle (active/stale/archived instead of binary)</li>
 *   <li>Reactivation (stale→active on skill use)</li>
 *   <li>Pinned skill bypass (skip pinned skills in archival)</li>
 *   <li>Dry-run mode (CURATOR_DRY_RUN_BANNER)</li>
 *   <li>Detailed prompt (~140 lines instead of ~30)</li>
 *   <li>absorbed_into declarations extraction</li>
 *   <li>Three-way reconciliation (LLM suggestions vs heuristics vs actual skill state)</li>
 * </ul>
 */
@Service
@Slf4j
public class CuratorService {

    private final SkillRepository skillRepository;
    private final AgentProperties properties;
    private final ModelClient modelClient;
    private final CuratorBackupService backupService;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-curator");
        t.setDaemon(true);
        return t;
    });

    // Protected skills — never archive these
    private static final List<String> PROTECTED_SKILLS = List.of(
        "hermes-agent", "hermes-agent-dev", "backend-dev", "default"
    );

    // S5: Three-state lifecycle constants
    public static final String STATE_ACTIVE = "active";
    public static final String STATE_STALE = "stale";
    public static final String STATE_ARCHIVED = "archived";

    // S5: Dry-run banner
    public static final String CURATOR_DRY_RUN_BANNER =
        "═══════════════════════════════════════════════════════════════\n" +
        "DRY-RUN — REPORT ONLY. DO NOT MUTATE THE SKILL LIBRARY.\n" +
        "═══════════════════════════════════════════════════════════════\n" +
        "\n" +
        "This is a PREVIEW pass. Follow every instruction below EXCEPT:\n" +
        "\n" +
        "  • DO NOT call skill_manage with action=patch, create, delete, write_file, or remove_file.\n" +
        "  • DO NOT call terminal to mv skill directories into .archive/.\n" +
        "  • DO NOT call terminal to mv, cp, rm, or rewrite any file under skills/.\n" +
        "  • skills_list and skill_view are FINE — read as much as you need.\n" +
        "\n" +
        "Your output IS the deliverable. Produce the exact same human-readable summary\n" +
        "and structured YAML block you would produce on a live run — but describe the\n" +
        "actions you WOULD take, not actions you took.\n" +
        "═══════════════════════════════════════════════════════════════";

    private volatile boolean started = false;

    // S5: State file path (curator state persistence)
    private volatile Path stateFile;

    /** Manual constructor for tests — no LLM, no backup. */
    public CuratorService(SkillRepository skillRepository, AgentProperties properties) {
        this.skillRepository = skillRepository;
        this.properties = properties;
        this.modelClient = null;
        this.backupService = null;
        this.objectMapper = new ObjectMapper();
    }

    /** Full constructor with LLM and backup support. */
    @org.springframework.beans.factory.annotation.Autowired
    public CuratorService(SkillRepository skillRepository, AgentProperties properties,
                          ModelClient modelClient, CuratorBackupService backupService) {
        this.skillRepository = skillRepository;
        this.properties = properties;
        this.modelClient = modelClient;
        this.backupService = backupService;
        this.objectMapper = new ObjectMapper();
    }

    // ── S5: State persistence ───────────────────────────────────────────

    /**
     * S5: Set the state file path for .curator_state persistence.
     */
    public void setStateFile(Path stateFile) {
        this.stateFile = stateFile;
    }

    /**
     * S5: Load curator state from .curator_state file.
     * Returns default state if file doesn't exist or is unreadable.
     */
    public Map<String, Object> loadState() {
        if (stateFile == null) {
            return defaultState();
        }
        try {
            if (!Files.exists(stateFile)) {
                return defaultState();
            }
            String content = Files.readString(stateFile);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(content, Map.class);
            if (data != null) {
                Map<String, Object> base = defaultState();
                base.putAll(data);
                return base;
            }
        } catch (Exception e) {
            log.debug("Failed to read curator state: {}", e.getMessage());
        }
        return defaultState();
    }

    /**
     * S5: Save curator state to .curator_state file.
     */
    public void saveState(Map<String, Object> state) {
        if (stateFile == null) {
            return;
        }
        try {
            Files.createDirectories(stateFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(stateFile.toFile(), state);
        } catch (Exception e) {
            log.debug("Failed to save curator state: {}", e.getMessage());
        }
    }

    Map<String, Object> defaultState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("last_run_at", null);
        state.put("last_run_duration_seconds", null);
        state.put("last_run_summary", null);
        state.put("paused", false);
        state.put("run_count", 0);
        return state;
    }

    // ── S5: Pause/unpause ────────────────────────────────────────────────

    /**
     * S5: Pause or unpause the curator.
     */
    public void setPaused(boolean paused) {
        Map<String, Object> state = loadState();
        state.put("paused", paused);
        saveState(state);
    }

    /**
     * S5: Check if the curator is paused.
     */
    public boolean isPaused() {
        return Boolean.TRUE.equals(loadState().get("paused"));
    }

    // ── S5: Config-driven interval ──────────────────────────────────────

    /**
     * S5: Get the configured review interval in hours.
     */
    public int getIntervalHours() {
        return properties.getCurator().getIntervalHours();
    }

    /**
     * S5: Get the minimum idle hours before curator runs.
     */
    public double getMinIdleHours() {
        return properties.getCurator().getMinIdleHours();
    }

    /**
     * S5: Get the stale threshold in days.
     */
    public int getStaleAfterDays() {
        return properties.getCurator().getStaleAfterDays();
    }

    /**
     * S5: Get the archive threshold in days.
     */
    public int getArchiveAfterDays() {
        return properties.getCurator().getArchiveAfterDays();
    }

    /**
     * S5: Check if the curator is enabled.
     */
    public boolean isEnabled() {
        return properties.getCurator().isEnabled();
    }

    /**
     * S5: Check if dry-run mode is active.
     */
    public boolean isDryRun() {
        return properties.getCurator().isDryRun();
    }

    // ── S5: Idle gating + first-run deferral ────────────────────────────

    /**
     * S5: Check if the curator should run now.
     * Gates: enabled, not paused, last_run_at older than interval_hours.
     * First-run: seeds last_run_at to now and defers by one interval.
     */
    public boolean shouldRunNow(Instant now) {
        if (!isEnabled()) return false;
        if (isPaused()) return false;

        Map<String, Object> state = loadState();
        String lastRunAtStr = (String) state.get("last_run_at");
        if (lastRunAtStr == null) {
            // S5: First-run deferral — seed last_run_at to now and wait one interval
            state.put("last_run_at", now.toString());
            state.put("last_run_summary",
                "deferred first run — curator seeded, will run after one interval");
            saveState(state);
            return false;
        }

        try {
            Instant lastRun = Instant.parse(lastRunAtStr);
            Duration interval = Duration.ofHours(getIntervalHours());
            return Duration.between(lastRun, now).compareTo(interval) >= 0;
        } catch (Exception e) {
            log.debug("Failed to parse last_run_at: {}", lastRunAtStr);
            return false;
        }
    }

    /**
     * S5: Maybe run the curator — checks idle gating and interval.
     * @param lastActivityTime the time of last agent activity (for idle gating)
     * @return the report if the curator ran, or null if it didn't run
     */
    public CuratorReport maybeRunCurator(Instant lastActivityTime) {
        Instant now = Instant.now();
        if (!shouldRunNow(now)) {
            return null;
        }
        // S5: Idle gating — check minimum idle hours
        if (lastActivityTime != null) {
            Duration idleTime = Duration.between(lastActivityTime, now);
            Duration minIdle = Duration.ofMillis((long) (getMinIdleHours() * 3600_000L));
            if (idleTime.compareTo(minIdle) < 0) {
                log.debug("Curator not running — agent was active {}h ago, need {}h idle",
                    idleTime.toMinutes() / 60.0, getMinIdleHours());
                return null;
            }
        }
        return runCycle();
    }

    // ── S5: Start ────────────────────────────────────────────────────────

    /**
     * Start the periodic curator review.
     * S5: Uses config-driven interval.
     */
    public void start() {
        if (started) return;
        started = true;
        int intervalMinutes = getIntervalHours() * 60;
        executor.scheduleAtFixedRate(
            this::runCuratorCycle,
            intervalMinutes,
            intervalMinutes,
            TimeUnit.MINUTES
        );
        log.info("Curator service started — review interval: {} minutes", intervalMinutes);
    }

    /**
     * Run a single curator cycle manually (for testing or manual trigger).
     */
    public CuratorReport runCycle() {
        return runCuratorCycle();
    }

    private CuratorReport runCuratorCycle() {
        Instant startTime = Instant.now();
        try {
            log.debug("Starting curator cycle");
            boolean dryRun = isDryRun();

            // S5: Config-driven stale/archive thresholds
            Instant staleBefore = Instant.now().minus(Duration.ofDays(getStaleAfterDays()));
            Instant archiveBefore = Instant.now().minus(Duration.ofDays(getArchiveAfterDays()));

            // S15: Create backup snapshot before any mutation (skip in dry-run)
            if (backupService != null && !dryRun) {
                try {
                    var snapshot = backupService.createSnapshot("curator-cycle");
                    if (snapshot != null) {
                        log.info("Curator backup snapshot created: {}", snapshot.id());
                    }
                } catch (Exception e) {
                    log.warn("Failed to create curator backup: {}", e.getMessage());
                }
            }

            // S5: Classify skills with three-state lifecycle
            List<SkillEntity> allSkills = skillRepository.findByArchivedFalse();
            List<String> active = new ArrayList<>();
            List<String> stale = new ArrayList<>();
            List<String> archived = new ArrayList<>();
            List<ConsolidationSuggestion> suggestions = new ArrayList<>();

            for (SkillEntity skill : allSkills) {
                // S5: Pinned skill bypass — skip pinned skills in archival
                if (isPinned(skill) || isProtected(skill.getName())) {
                    active.add(skill.getName());
                    continue;
                }

                Instant lastActivity = skill.getLastActivityAt();
                // If never active, treat createdAt as anchor
                Instant anchor = lastActivity != null ? lastActivity :
                    (skill.getCreatedAt() != null ? skill.getCreatedAt() : Instant.now());

                String currentState = skill.getLifecycleState() != null ?
                    skill.getLifecycleState() : STATE_ACTIVE;

                // S5: Three-state lifecycle transitions
                if (anchor.isBefore(archiveBefore) && !STATE_ARCHIVED.equals(currentState)) {
                    // Archive: older than archive_after_days
                    if (!dryRun) {
                        skill.setArchived(true);
                        skill.setLifecycleState(STATE_ARCHIVED);
                        skill.setUpdatedAt(Instant.now());
                        skillRepository.save(skill);
                    }
                    archived.add(skill.getName());
                    stale.add(skill.getName());
                } else if (anchor.isBefore(staleBefore) && STATE_ACTIVE.equals(currentState)) {
                    // Stale: older than stale_after_days but not yet archive-worthy
                    if (!dryRun) {
                        skill.setLifecycleState(STATE_STALE);
                        skill.setUpdatedAt(Instant.now());
                        skillRepository.save(skill);
                    }
                    stale.add(skill.getName());
                    active.add(skill.getName());
                } else if (!anchor.isBefore(staleBefore) && STATE_STALE.equals(currentState)) {
                    // S5: Reactivation: stale→active on skill use
                    if (!dryRun) {
                        skill.setLifecycleState(STATE_ACTIVE);
                        skill.setUpdatedAt(Instant.now());
                        skillRepository.save(skill);
                    }
                    active.add(skill.getName());
                } else {
                    active.add(skill.getName());
                }
            }

            // S5: LLM-driven consolidation with dry-run support
            List<CuratorAction> actions = new ArrayList<>();
            if (dryRun) {
                log.info("Curator dry-run mode — no mutations will be applied");
            }

            if (modelClient != null) {
                suggestions = runLlmConsolidation(allSkills);
            } else {
                suggestions = findConsolidationOpportunities(allSkills);
            }

            // Convert suggestions to actions
            for (ConsolidationSuggestion s : suggestions) {
                actions.add(new CuratorAction("CONSOLIDATE", s.suggestedUmbrellaName(),
                    s.skillsToMerge(), s.reason()));
            }

            // Add archive actions
            for (String archivedName : archived) {
                actions.add(new CuratorAction("ARCHIVE", archivedName, List.of(archivedName),
                    "Skill marked stale and archived"));
            }

            CuratorReport report = new CuratorReport(active, stale, archived, suggestions, actions);

            // S5: State persistence — update .curator_state
            Duration runDuration = Duration.between(startTime, Instant.now());
            Map<String, Object> state = loadState();
            state.put("last_run_at", Instant.now().toString());
            state.put("last_run_duration_seconds", runDuration.getSeconds());
            state.put("run_count", ((Integer) state.getOrDefault("run_count", 0)) + 1);
            state.put("last_run_summary",
                String.format("active=%d, stale=%d, archived=%d, suggestions=%d, actions=%d%s",
                    active.size(), stale.size(), archived.size(), suggestions.size(), actions.size(),
                    dryRun ? " [DRY-RUN]" : ""));
            saveState(state);

            log.info("Curator cycle complete: active={}, stale={}, archived={}, consolidationSuggestions={}, actions={}{}",
                active.size(), stale.size(), archived.size(), suggestions.size(), actions.size(),
                dryRun ? " [DRY-RUN]" : "");

            return report;
        } catch (Exception e) {
            log.error("Curator cycle failed: {}", e.getMessage());
            return new CuratorReport(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    // ── S5: Pinned skill check ───────────────────────────────────────────

    /**
     * S5: Check if a skill is pinned (bypasses all auto-transitions).
     */
    private boolean isPinned(SkillEntity skill) {
        return skill != null && skill.isPinned();
    }

    // ── S5: LLM-driven consolidation ─────────────────────────────────────

    /**
     * S16/S5: LLM-driven consolidation — forks an LLM call with an umbrella-building prompt
     * that analyzes skills and suggests consolidation patterns.
     * S5: Uses the detailed ~140-line prompt from Hermes' curator.py.
     */
    private List<ConsolidationSuggestion> runLlmConsolidation(List<SkillEntity> skills) {
        try {
            String prompt = buildConsolidationPrompt(skills);
            List<Message> messages = List.of(
                Message.system(CURATOR_REVIEW_PROMPT),
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
     * S5: Build the umbrella-building consolidation prompt for the LLM.
     * Uses the detailed prompt from Hermes' curator.py (~140 lines).
     */
    private String buildConsolidationPrompt(List<SkillEntity> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the following skill collection and suggest consolidation.\n");
        sb.append("The goal is a LIBRARY OF CLASS-LEVEL INSTRUCTIONS. A collection of hundreds of\n");
        sb.append("narrow skills where each captures one session's specific bug is a FAILURE.\n\n");
        sb.append("Rules:\n");
        sb.append("1. DO NOT touch bundled or hub-installed skills.\n");
        sb.append("2. DO NOT delete any skill — archiving is the maximum destructive action.\n");
        sb.append("3. DO NOT touch pinned skills.\n");
        sb.append("4. DO NOT use usage counters as a reason to skip consolidation.\n");
        sb.append("5. DO NOT reject consolidation on the grounds that 'each skill has a distinct trigger'.\n\n");
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
            sb.append("  pinned: ").append(skill.isPinned()).append("\n");
            sb.append("  state: ").append(skill.getLifecycleState() != null ? skill.getLifecycleState() : STATE_ACTIVE).append("\n");
            sb.append("  contentPreview: ").append(getPreview(skill.getContent(), 200)).append("\n");
        }
        if (isDryRun()) {
            sb.append("\n").append(CURATOR_DRY_RUN_BANNER).append("\n");
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

    // ── S5: absorbed_into declarations extraction ──────────────────────

    /**
     * S5: Extract absorbed_into declarations from tool calls.
     * Walks skill_manage delete calls and extracts the absorbed_into parameter.
     * Returns map of skill_name → absorbed_into target.
     */
    public Map<String, String> extractAbsorbedIntoDeclarations(List<Map<String, Object>> toolCalls) {
        Map<String, String> result = new LinkedHashMap<>();
        if (toolCalls == null) return result;
        for (Map<String, Object> tc : toolCalls) {
            if (!"skill_manage".equals(tc.get("name"))) continue;
            Object rawArgs = tc.get("arguments");
            Map<String, Object> args;
            if (rawArgs instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) rawArgs;
                args = m;
            } else if (rawArgs instanceof String) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = objectMapper.readValue((String) rawArgs, Map.class);
                    args = m;
                } catch (Exception e) {
                    continue;
                }
            } else {
                continue;
            }
            if (!"delete".equals(args.get("action"))) continue;
            String name = (String) args.get("name");
            if (name == null || name.isBlank()) continue;
            if (!args.containsKey("absorbed_into")) continue;
            Object target = args.get("absorbed_into");
            if (target instanceof String) {
                result.put(name.trim(), ((String) target).trim());
            }
        }
        return result;
    }

    // ── S5: Three-way reconciliation ─────────────────────────────────────

    /**
     * S5: Reconcile LLM suggestions vs heuristics vs actual skill state.
     * Merges heuristic (tool-call evidence) with the model's structured block.
     *
     * Rules (evaluated in order; first match wins):
     * - Model-declared absorbed_into at delete time is authoritative.
     * - Model-declared consolidation wins when its into target exists.
     * - Model-declared consolidation whose into target doesn't exist is hallucination — fall back.
     * - Heuristic-only finding is preserved.
     * - Model-declared pruning is accepted unless contradicted.
     */
    public ReconciliationResult reconcileClassification(
        List<String> removed,
        Map<String, String> absorbedDeclarations,
        List<ConsolidationSuggestion> modelConsolidations,
        List<String> afterNames
    ) {
        List<ReconciliationEntry> consolidated = new ArrayList<>();
        List<ReconciliationEntry> pruned = new ArrayList<>();
        java.util.Set<String> destinations = new java.util.HashSet<>(afterNames);

        // Build model consolidation lookup
        Map<String, ConsolidationSuggestion> modelCons = new LinkedHashMap<>();
        for (ConsolidationSuggestion s : modelConsolidations) {
            for (String skillName : s.skillsToMerge()) {
                modelCons.put(skillName, s);
            }
        }

        for (String name : removed) {
            if (name == null || name.isBlank()) continue;
            String declaredInto = absorbedDeclarations.get(name);
            ConsolidationSuggestion mc = modelCons.get(name);

            // Authoritative: model declared absorbed_into at delete time
            if (declaredInto != null) {
                if (!declaredInto.isEmpty() && destinations.contains(declaredInto)) {
                    consolidated.add(new ReconciliationEntry(name, declaredInto,
                        "absorbed_into (model-declared at delete)",
                        mc != null ? mc.reason() : ""));
                    continue;
                }
                if (declaredInto.isEmpty()) {
                    pruned.add(new ReconciliationEntry(name, "",
                        "absorbed_into=\"\" (model-declared prune)",
                        mc != null ? mc.reason() : ""));
                    continue;
                }
            }

            // Model says consolidated — trust if destination is real
            if (mc != null && destinations.contains(mc.suggestedUmbrellaName())) {
                consolidated.add(new ReconciliationEntry(name, mc.suggestedUmbrellaName(),
                    "model", mc.reason()));
                continue;
            }

            // Model says consolidated but umbrella doesn't exist — hallucination
            if (mc != null && !destinations.contains(mc.suggestedUmbrellaName())) {
                pruned.add(new ReconciliationEntry(name, "",
                    "fallback (model named missing umbrella, no evidence)", ""));
                continue;
            }

            // No evidence — pruned
            pruned.add(new ReconciliationEntry(name, "", "no-evidence fallback", ""));
        }

        return new ReconciliationResult(consolidated, pruned);
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
        Map<String, List<String>> groups = new LinkedHashMap<>();
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

    // ── S5: Detailed prompt (~140 lines from Hermes' curator.py) ─────────

    private static final String CURATOR_REVIEW_PROMPT =
        "You are running as the background skill CURATOR. This is an " +
        "UMBRELLA-BUILDING consolidation pass, not a passive audit and not a " +
        "duplicate-finder.\n\n" +
        "The goal of the skill collection is a LIBRARY OF CLASS-LEVEL " +
        "INSTRUCTIONS AND EXPERIENTIAL KNOWLEDGE. A collection of hundreds of " +
        "narrow skills where each one captures one session's specific bug is " +
        "a FAILURE of the library — not a feature. An agent searching skills " +
        "matches on descriptions, not on exact names; one broad umbrella " +
        "skill with labeled subsections beats five narrow siblings for " +
        "discoverability, not the other way around.\n\n" +
        "The right target shape is CLASS-LEVEL skills with rich SKILL.md " +
        "bodies + references/, templates/, and scripts/ subfiles for " +
        "session-specific detail — not one-session-one-skill micro-entries.\n\n" +
        "Hard rules — do not violate:\n" +
        "1. DO NOT touch bundled or hub-installed skills.\n" +
        "2. DO NOT delete any skill. Archiving is the maximum destructive action.\n" +
        "3. DO NOT touch skills shown as pinned=yes. Skip them entirely.\n" +
        "4. DO NOT use usage counters as a reason to skip consolidation.\n" +
        "5. DO NOT reject consolidation on the grounds that 'each skill has " +
        "a distinct trigger'. The right bar is: 'would a human maintainer " +
        "write this as N separate skills, or as one skill with N labeled " +
        "subsections?' When the answer is the latter, merge.\n\n" +
        "How to work — not optional:\n" +
        "1. Scan the full candidate list. Identify PREFIX CLUSTERS.\n" +
        "2. For each cluster with 2+ members, ask 'what is the UMBRELLA CLASS?'\n" +
        "3. Three ways to consolidate — use the right one per cluster:\n" +
        "   a. MERGE INTO EXISTING UMBRELLA — one skill is already broad enough.\n" +
        "   b. CREATE A NEW UMBRELLA SKILL — no existing member is broad enough.\n" +
        "   c. DEMOTE TO REFERENCES/TEMPLATES/SCRIPTS — narrow-but-valuable content.\n" +
        "4. Also flag skills whose NAME is too narrow (contains a PR number, " +
        "a feature codename, a specific error string).\n" +
        "5. Iterate. After one consolidation round, scan the remaining set.\n\n" +
        "Your toolset:\n" +
        "  - skills_list, skill_view        — read the current landscape\n" +
        "  - skill_manage action=patch      — add sections to the umbrella\n" +
        "  - skill_manage action=create     — create a new umbrella SKILL.md\n" +
        "  - skill_manage action=write_file — add a references/, templates/, " +
        "or scripts/ file under an existing skill\n" +
        "  - skill_manage action=delete     — archive a skill. MUST pass " +
        "absorbed_into=<umbrella> when merging, or absorbed_into=\"\" when pruning.\n" +
        "  - terminal                       — mv a sibling into the archive\n" +
        "OR move its content into a support subfile\n\n" +
        "'keep' is a legitimate decision ONLY when the skill is already a " +
        "class-level umbrella and none of the proposed merges would improve " +
        "discoverability.\n\n" +
        "Expected output: real umbrella-ification. Process every obvious " +
        "cluster. If you end the pass with fewer than 10 archives, you " +
        "stopped too early.\n\n" +
        "When done, write a human summary AND a structured machine-readable " +
        "block so downstream tooling can distinguish consolidation from " +
        "pruning. Format EXACTLY:\n\n" +
        "## Structured summary (required)\n" +
        "```yaml\n" +
        "consolidations:\n" +
        "  - from: <old-skill-name>\n" +
        "    into: <umbrella-skill-name>\n" +
        "    reason: <one short sentence>\n" +
        "prunings:\n" +
        "  - name: <skill-name>\n" +
        "    reason: <one short sentence>\n" +
        "```\n\n" +
        "Every skill you moved to .archive/ MUST appear in exactly one of the " +
        "two lists. If you consolidated X into umbrella Y (patched Y, wrote " +
        "a references file to Y, or created Y with X's content absorbed), X " +
        "goes under consolidations with into: Y. If you archived X with " +
        "no absorption — truly stale, irrelevant, or obsolete — X goes under " +
        "prunings. Leave a list empty (consolidations: []) if none. Do " +
        "not omit the block. The block comes AFTER your human-readable " +
        "summary of clusters processed, patches made, and decisions left alone.";

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

    /**
     * S5: Reconciliation entry — one skill's classification after three-way merge.
     */
    public record ReconciliationEntry(
        String name,
        String into,
        String source,
        String reason
    ) {}

    /**
     * S5: Reconciliation result — consolidated vs pruned skills.
     */
    public record ReconciliationResult(
        List<ReconciliationEntry> consolidated,
        List<ReconciliationEntry> pruned
    ) {}
}