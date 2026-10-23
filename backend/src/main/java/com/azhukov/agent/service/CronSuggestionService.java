package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.entity.CronSuggestionEntity;
import com.azhukov.agent.persistence.repository.CronSuggestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Consent-first, durable cron suggestions. Suggestions are isolated by owner so a restart
 * or a second authenticated user cannot lose or consume another user's pending decisions.
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
 private final CronSuggestionRepository cronSuggestionRepository;

 /**
 * Add a pending suggestion for the authenticated owner, or return null if it was
 * already offered or that owner's pending backlog is full.
 */
 @Transactional
 public SuggestionRecord addSuggestion(String title, String description,
 String source, JobSpec jobSpec, String dedupKey) {
 if (title == null || title.isBlank()) {
 throw new IllegalArgumentException("title is required");
 }
 if (dedupKey == null || dedupKey.isBlank()) {
 throw new IllegalArgumentException("dedup_key is required");
 }
 if (jobSpec == null) {
 throw new IllegalArgumentException("job_spec is required");
 }

 String userId = currentUserId();
 String normalizedDedupKey = dedupKey.strip();
 if (cronSuggestionRepository.findByUserIdAndDedupKey(userId, normalizedDedupKey).isPresent()) {
 return null;
 }
 if (cronSuggestionRepository.countByUserIdAndStatus(userId, STATUS_PENDING) >= MAX_PENDING) {
 log.info("Suggestion backlog full for owner {}; dropping source={}", userId, source);
 return null;
 }

 CronSuggestionEntity entity = new CronSuggestionEntity();
 entity.setUserId(userId);
 entity.setTitle(title.strip());
 entity.setDescription(description != null ? description.strip() : "");
 entity.setSource(source);
 applyJobSpec(entity, jobSpec);
 entity.setDedupKey(normalizedDedupKey);
 entity.setStatus(STATUS_PENDING);
 entity.setCreatedAt(Instant.now());
 try {
 return toRecord(cronSuggestionRepository.save(entity));
 } catch (DataIntegrityViolationException ignored) {
 // The unique owner/dedup key constraint closes races between application nodes.
 return null;
 }
 }

 /** List the current owner's pending suggestions in creation order. */
 @Transactional(readOnly = true)
 public List<SuggestionRecord> listPending() {
 return cronSuggestionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(currentUserId(), STATUS_PENDING)
 .stream().map(CronSuggestionService::toRecord).toList();
 }

 /** List every suggestion visible to the current owner. */
 @Transactional(readOnly = true)
 public List<SuggestionRecord> listAll() {
 return cronSuggestionRepository.findByUserIdOrderByCreatedAtAsc(currentUserId())
 .stream().map(CronSuggestionService::toRecord).toList();
 }

 /** Resolve a current-owner suggestion by UUID, 1-based pending index, or exact title. */
 @Transactional(readOnly = true)
 public SuggestionRecord getSuggestion(String ref) {
 CronSuggestionEntity entity = resolve(ref, currentUserId());
 return entity == null ? null : toRecord(entity);
 }

 /**
 * Accept a pending suggestion and create its cron job with the same owner from the first write.
 */
 @Transactional
 public CronJobEntity acceptSuggestion(String ref) {
 String userId = currentUserId();
 CronSuggestionEntity suggestion = resolveForUpdate(ref, userId);
 if (suggestion == null || !STATUS_PENDING.equals(suggestion.getStatus())) {
 return null;
 }

 CronJobEntity entity = cronJobService.create(
 userId, suggestion.getJobName(), suggestion.getJobSchedule(), suggestion.getJobPrompt(),
 suggestion.getDeliverTo(), suggestion.getSkills(), null, null, null, false,
 null, null, null, null, null);
 suggestion.setStatus(STATUS_ACCEPTED);
 suggestion.setResolvedAt(Instant.now());
 cronSuggestionRepository.save(suggestion);
 log.info("Cron suggestion accepted owner={} source={}", userId, suggestion.getSource());
 return entity;
 }

 /** Dismiss a current-owner suggestion and retain its dedup latch. */
 @Transactional
 public boolean dismissSuggestion(String ref) {
 CronSuggestionEntity suggestion = resolve(ref, currentUserId());
 if (suggestion == null || !STATUS_PENDING.equals(suggestion.getStatus())) {
 return false;
 }
 suggestion.setStatus(STATUS_DISMISSED);
 suggestion.setResolvedAt(Instant.now());
 cronSuggestionRepository.save(suggestion);
 return true;
 }

 /** Remove only accepted current-owner records; dismissed records keep the dedup latch. */
 @Transactional
 public int clearAccepted() {
 String userId = currentUserId();
 List<CronSuggestionEntity> accepted = cronSuggestionRepository
 .findByUserIdAndStatusOrderByCreatedAtAsc(userId, STATUS_ACCEPTED);
 cronSuggestionRepository.deleteAll(accepted);
 return accepted.size();
 }

 private CronSuggestionEntity resolveForUpdate(String ref, String userId) {
 CronSuggestionEntity suggestion = resolve(ref, userId);
 if (suggestion == null) {
 return null;
 }
 return cronSuggestionRepository.findByIdAndUserIdForUpdate(suggestion.getId(), userId).orElse(null);
 }

 private CronSuggestionEntity resolve(String ref, String userId) {
 if (ref == null || ref.isBlank()) {
 return null;
 }
 try {
 CronSuggestionEntity byId = cronSuggestionRepository.findByIdAndUserId(UUID.fromString(ref), userId).orElse(null);
 if (byId != null) {
 return byId;
 }
 } catch (IllegalArgumentException ignored) {
 // The reference can be a numeric index or title.
 }
 List<CronSuggestionEntity> pending = cronSuggestionRepository
 .findByUserIdAndStatusOrderByCreatedAtAsc(userId, STATUS_PENDING);
 if (ref.matches("\\d+")) {
 int index = Integer.parseInt(ref) - 1;
 if (index >= 0 && index < pending.size()) {
 return pending.get(index);
 }
 }
 return pending.stream().filter(s -> s.getTitle().equalsIgnoreCase(ref)).findFirst().orElse(null);
 }

 private static void applyJobSpec(CronSuggestionEntity entity, JobSpec jobSpec) {
 entity.setJobName(jobSpec.name());
 entity.setJobSchedule(jobSpec.schedule());
 entity.setJobPrompt(jobSpec.prompt());
 entity.setDeliverTo(jobSpec.deliverTo());
 entity.setSkills(jobSpec.skills());
 }

 private static SuggestionRecord toRecord(CronSuggestionEntity entity) {
 return new SuggestionRecord(entity.getId().toString(), entity.getTitle(), entity.getDescription(),
 entity.getSource(), new JobSpec(entity.getJobName(), entity.getJobSchedule(), entity.getJobPrompt(),
 entity.getDeliverTo(), entity.getSkills()), entity.getDedupKey(), entity.getStatus(), entity.getCreatedAt());
 }

 private static String currentUserId() {
 return UserContext.effectiveUserId(AgentProperties.DEFAULT_USER_ID);
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