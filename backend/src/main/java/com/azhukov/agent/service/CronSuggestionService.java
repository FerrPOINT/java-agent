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
 * Ported from the original project's cron/suggestions.py. A suggestion is a ready-to-run cron job spec
 * that the user accepts (creates the real cron job) or dismisses (latched so it is
 * never re-offered). Consent-first: suggestions never auto-create jobs.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CronSuggestionService {

 private static final int MAX_PENDING = 5;
 private static final String STATUS_PENDING = "pending";
 private static final String STATUS_ACCEPTED = "accepted";
 private static final String STATUS_DISMISSED = "dismissed";

 private final CronJobService cronJobService;
 private final Map<String, SuggestionRecord> suggestions = new ConcurrentHashMap<>();
 private final List<SuggestionRecord> orderedSuggestions = new ArrayList<>();

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
/**
  * Hermes parity (cron/suggestion_catalog.py CATALOG): the curated starter
  * automation set. Schedules use the same syntax the cron service accepts.
  */
 private static final List<Object[]> CATALOG = List.of(
     new Object[]{"catalog:daily-briefing", "Daily briefing",
         "Every morning at 8am, a short briefing: today's calendar, weather, and anything urgent waiting on you.",
         "Produce a concise morning briefing for the user: today's calendar events, the local weather, and any urgent items (unread important email, due tasks). Keep it short and scannable. If you have no connected data sources, give a brief general good-morning with the date and offer to connect calendar/email.",
         "0 8 * * *", "Daily briefing"},
     new Object[]{"catalog:important-mail-monitor", "Important-mail monitor",
         "Check your inbox periodically and ping you ONLY about mail that actually needs attention — never the newsletters.",
         "Check the user's inbox for new messages since the last run. For each candidate, judge urgency against this rule: surface only mail that needs a reply today, is from a manager/family member, or mentions a deadline. Pipe candidates through the urgency classifier (run `python3 -m cron.scripts.classify_items --threshold 7 --criteria ...` from the hermes-agent install — resolve the script path at run time, do not assume a fixed location) and deliver ONLY what it returns. If nothing clears the bar, respond with [SILENT] so the user is not pinged. Requires a connected mail source; if none is configured, explain how to connect one and then stop.",
         "every 30m", "Important-mail monitor"},
     new Object[]{"catalog:weekly-review", "Weekly review",
         "Every Sunday evening, a recap of the week: what got done, what's still open, and what's coming up next week.",
         "Produce a weekly review for the user: summarize what was accomplished this week, list still-open items, and preview next week's calendar. Pull from whatever sources are connected (calendar, task tools, recent conversations). Keep it tight.",
         "0 18 * * 0", "Weekly review"},
     new Object[]{"catalog:standup-reminder", "Workday start reminder",
         "A weekday nudge at 9am with your day's agenda and top priorities, so you start focused.",
         "Give the user a brief weekday start-of-day nudge: their calendar for today and the 1-3 highest-priority things to focus on, inferred from recent context and any task tools. Encouraging, short, one message.",
         "0 9 * * 1-5", "Workday start reminder"}
 );

 /**
  * Hermes parity (seed_catalog_suggestions): seed the curated catalog as
  * pending suggestions. Already-dismissed/accepted keys are skipped by
  * {@link #addSuggestion}'s dedup — the dismiss latch is never re-offered.
  * Returns the number of NEW suggestions added.
  */
 public synchronized int seedCatalogSuggestions() {
     int added = 0;
     for (Object[] e : CATALOG) {
         String key = (String) e[0];
         String title = (String) e[1];
         String description = (String) e[2];
         String prompt = (String) e[3];
         String schedule = (String) e[4];
         String name = (String) e[5];
         SuggestionRecord rec = addSuggestion(title, description, "catalog",
             new JobSpec(name, schedule, prompt, "origin", null), key);
         if (rec != null) {
             added++;
         }
     }
     return added;
 }

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