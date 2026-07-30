package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
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
 * S2: Curator service — skill lifecycle management.
 * Periodically reviews skills: classifies active/stale, archives stale skills,
 * and suggests consolidation of similar skills.
 * <p>
 * Ported from Hermes' curator.py (simplified — core lifecycle only).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CuratorService {

    private final SkillRepository skillRepository;
    private final AgentProperties properties;

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

            // S2: Classify skills
            List<SkillEntity> allSkills = skillRepository.findByArchivedFalse();
            List<String> active = new ArrayList<>();
            List<String> stale = new ArrayList<>();
            List<String> archived = new ArrayList<>();
            List<String> consolidated = new ArrayList<>();

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

            // S2: Consolidation — suggest merging similar skills (umbrella pattern)
            List<ConsolidationSuggestion> suggestions = findConsolidationOpportunities(allSkills);
            for (ConsolidationSuggestion s : suggestions) {
                consolidated.add(s.toString());
            }

            CuratorReport report = new CuratorReport(active, stale, archived, suggestions);
            log.info("Curator cycle complete: active={}, stale={}, archived={}, consolidationSuggestions={}",
                active.size(), stale.size(), archived.size(), suggestions.size());

            return report;
        } catch (Exception e) {
            log.error("Curator cycle failed: {}", e.getMessage());
            return new CuratorReport(List.of(), List.of(), List.of(), List.of());
        }
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
     * S2: Curator report — summary of a cycle.
     */
    public record CuratorReport(
        List<String> activeSkills,
        List<String> staleSkills,
        List<String> archivedSkills,
        List<ConsolidationSuggestion> consolidationSuggestions
    ) {}

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
}