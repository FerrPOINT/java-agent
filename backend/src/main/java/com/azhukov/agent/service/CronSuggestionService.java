package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S18: Cron suggestions engine — presents automation suggestions based on usage patterns.
 * <p>
 * Ported from Hermes' cron/suggestions.py. A suggestion is a ready-to-run cron job spec
 * that the user accepts (creates the real cron job) or dismisses (latched so it is
 * never re-offered). Consent-first: suggestions never auto-create jobs.
 */
@Service
@Slf4j
public class CronSuggestionService {

    private static final int MAX_PENDING = 5;
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_ACCEPTED = "accepted";
    private static final String STATUS_DISMISSED = "dismissed";

    private final CronJobService cronJobService;
    private final Map<String, SuggestionRecord> suggestions = new ConcurrentHashMap<>();
    private final List<SuggestionRecord> orderedSuggestions = new ArrayList<>();

    public CronSuggestionService(CronJobService cronJobService) {
        this.cronJobService = cronJobService;
    }

    /**
     * Add a pending suggestion. Returns the record, or null if skipped.
     * Skipped when: the same dedup_key was already dismissed or accepted,
     * an identical pending suggestion exists, or the pending list is full.
     */
    public synchronized SuggestionRecord addSuggestion(String title, String description,
                                                        String source, JobSpec jobSpec,
                                                        String dedupKey) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (dedupKey == null || dedupKey.isBlank()) {
            throw new IllegalArgumentException("dedup_key is required");
        }

        // Never re-offer something the user already saw and decided on
        for (SuggestionRecord existing : orderedSuggestions) {
            if (existing.dedupKey().equals(dedupKey)) {
                if (STATUS_DISMISSED.equals(existing.status()) || STATUS_ACCEPTED.equals(existing.status())) {
                    return null;
                }
                if (STATUS_PENDING.equals(existing.status())) {
                    return null;
                }
            }
        }

        long pendingCount = orderedSuggestions.stream()
            .filter(s -> STATUS_PENDING.equals(s.status())).count();
        if (pendingCount >= MAX_PENDING) {
            log.info("Suggestion backlog full ({}); dropping '{}'", MAX_PENDING, title);
            return null;
        }

        SuggestionRecord record = new SuggestionRecord(
            UUID.randomUUID().toString().substring(0, 12),
            title.strip(), description != null ? description.strip() : "",
            source, jobSpec, dedupKey.strip(), STATUS_PENDING, Instant.now()
        );
        orderedSuggestions.add(record);
        suggestions.put(record.id(), record);
        log.info("Suggestion added: '{}' (source: {}, dedup: {})", title, source, dedupKey);
        return record;
    }

    /**
     * List pending suggestions in creation order (oldest first).
     */
    public List<SuggestionRecord> listPending() {
        return orderedSuggestions.stream()
            .filter(s -> STATUS_PENDING.equals(s.status()))
            .toList();
    }

    /**
     * List all suggestions (any status).
     */
    public List<SuggestionRecord> listAll() {
        return List.copyOf(orderedSuggestions);
    }

    /**
     * Get a suggestion by id, 1-based pending index, or exact title.
     */
    public SuggestionRecord getSuggestion(String ref) {
        // By id
        SuggestionRecord byId = suggestions.get(ref);
        if (byId != null) return byId;
        // By 1-based pending index
        if (ref != null && ref.matches("\\d+")) {
            List<SuggestionRecord> pending = listPending();
            int idx = Integer.parseInt(ref) - 1;
            if (idx >= 0 && idx < pending.size()) {
                return pending.get(idx);
            }
        }
        // By exact title (case-insensitive)
        if (ref != null) {
            for (SuggestionRecord s : orderedSuggestions) {
                if (s.title().equalsIgnoreCase(ref)) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * Accept a suggestion — creates the real cron job from its job_spec.
     * Returns the created cron job entity, or null if the suggestion isn't found/not pending.
     */
    public synchronized CronJobEntity acceptSuggestion(String ref) {
        SuggestionRecord s = getSuggestion(ref);
        if (s == null || !STATUS_PENDING.equals(s.status())) {
            return null;
        }
        JobSpec spec = s.jobSpec();
        CronJobEntity entity = cronJobService.create(
            spec.name(), spec.schedule(), spec.prompt(), spec.deliverTo(), spec.skills()
        );
        setStatus(s.id(), STATUS_ACCEPTED);
        log.info("Suggestion '{}' accepted — cron job '{}' created", s.title(), spec.name());
        return entity;
    }

    /**
     * Dismiss a suggestion (latched — never re-offered for its dedup_key).
     */
    public synchronized boolean dismissSuggestion(String ref) {
        SuggestionRecord s = getSuggestion(ref);
        if (s == null) {
            return false;
        }
        return setStatus(s.id(), STATUS_DISMISSED);
    }

    /**
     * Clear accepted suggestions from the list.
     */
    public synchronized int clearAccepted() {
        int before = orderedSuggestions.size();
        orderedSuggestions.removeIf(s -> STATUS_ACCEPTED.equals(s.status()));
        int removed = before - orderedSuggestions.size();
        // Rebuild map
        suggestions.clear();
        for (SuggestionRecord s : orderedSuggestions) {
            suggestions.put(s.id(), s);
        }
        if (removed > 0) {
            log.info("Cleared {} accepted suggestions", removed);
        }
        return removed;
    }

    private boolean setStatus(String suggestionId, String status) {
        for (int i = 0; i < orderedSuggestions.size(); i++) {
            SuggestionRecord s = orderedSuggestions.get(i);
            if (s.id().equals(suggestionId)) {
                SuggestionRecord updated = new SuggestionRecord(
                    s.id(), s.title(), s.description(), s.source(),
                    s.jobSpec(), s.dedupKey(), status, Instant.now()
                );
                orderedSuggestions.set(i, updated);
                suggestions.put(suggestionId, updated);
                return true;
            }
        }
        return false;
    }

    // ── Records ────────────────────────────────────────────────────────

    /**
     * A suggestion record.
     */
    public record SuggestionRecord(
        String id,
        String title,
        String description,
        String source,
        JobSpec jobSpec,
        String dedupKey,
        String status,
        Instant createdAt
    ) {}

    /**
     * A job specification for creating a cron job from a suggestion.
     */
    public record JobSpec(
        String name,
        String schedule,
        String prompt,
        String deliverTo,
        String skills
    ) {}
}