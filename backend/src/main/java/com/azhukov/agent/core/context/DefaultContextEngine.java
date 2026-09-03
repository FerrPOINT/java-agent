package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TokenUsage;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.ToolCallPersistenceCodec;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class DefaultContextEngine implements ContextEngine {

 private static final int RECALL_LIMIT = 5;
 private static final long COMPRESSION_COOLDOWN_SECONDS = 600;
 // P7 parity: preflight uses the configured agent.context.threshold-percent
 // (default 0.50, context_compressor.py:3104) — no separate hard-coded fraction.

 private final MemoryProvider memoryProvider;
 private final SkillManager skillManager;
 private final MessageRepository messageRepository;
 private final ContextCompressor contextCompressor;

 /** Exposed for proactive compression checks from AgentStreamingService. */
 public ContextCompressor getContextCompressor() {
     return contextCompressor;
 }
 private final AgentProperties.ContextProperties contextProps;
 // P-09: master switch — when agent.compression.enabled=false the engine must
 // never invoke the compressor (preflight/proactive callers are gated on this too).
 private final boolean compressionEnabled;
 private final PromptCacheTracker cacheTracker;
 private final ModelMetadataService modelMetadataService;

 /**
  * Session lineage port for loading ancestor messages after compression rotation.
  * Optional — set via {@link #setSessionLineageService} after construction.
  * When null, falls back to loading current-session-only history.
  */
 private SessionLineagePort sessionLineageService;
 private SessionRepository sessionRepository;

 // Perf instrumentation (optional — no-op when not wired)
 @org.springframework.beans.factory.annotation.Autowired(required = false)
 private com.azhukov.agent.metrics.AgentMetrics agentMetrics;

 private final Map<UUID, Map<String, String>> snapshotCache = new ConcurrentHashMap<>();
 private final Map<UUID, UUID> rotatedSessionIds = new ConcurrentHashMap<>();
 private final Map<UUID, String> lastMemoryHash = new ConcurrentHashMap<>();
 private final ConcurrentHashMap<UUID, Instant> lastCompressedAt = new ConcurrentHashMap<>();
 // The in-flight messages list is unique to one agent turn. Key the snapshot
 // by identity to avoid repeated lineage loads without retaining it across a
 // later turn on the same virtual thread.
 private final ThreadLocal<TurnHistorySnapshot> turnHistorySnapshot = new ThreadLocal<>();
 private record TurnHistorySnapshot(UUID sessionId, List<Message> turnMessages, List<Message> history) {
 }

 /**
  * If the given session was rotated during a recent prepareContext call, return the
  * new child session entity loaded from the repository. Returns empty if no rotation
  * happened or the child session cannot be found.
  */
 public Optional<Session> resolveRotatedSession(Session session) {
     UUID childId = rotatedSessionIds.get(session.id());
     if (childId == null) {
         return Optional.empty();
     }
     return sessionRepository.findById(childId)
         .map(entity -> new Session(
             childId,
             entity.getUserId(),
             entity.getTitle(),
             entity.getModelProvider(),
             entity.getModelName(),
             session.systemPrompt(),
             session.metadata(), // propagate metadata (platform, source, etc.)
             session.subgoal()
         ));
 }

 // Real token usage tracking (replaces chars/4 estimate)
 private volatile int lastPromptTokens = 0;
 private volatile int lastCompletionTokens = 0;
 private volatile int lastTotalTokens = 0;
 private volatile int lastCacheReadTokens = 0;
 private volatile int lastCacheWriteTokens = 0;
 private volatile int lastReasoningTokens = 0;
 private final AtomicInteger compressionCount = new AtomicInteger(0);
 private volatile int contextLength = 0;
 private volatile int thresholdTokens = 0;

 public DefaultContextEngine(MemoryProvider memoryProvider,
 SkillManager skillManager,
 MessageRepository messageRepository,
 ContextCompressor contextCompressor,
 AgentProperties properties) {
 this(memoryProvider, skillManager, messageRepository, contextCompressor, properties, null, null);
 }

 public DefaultContextEngine(MemoryProvider memoryProvider,
 SkillManager skillManager,
 MessageRepository messageRepository,
 ContextCompressor contextCompressor,
 AgentProperties properties,
 PromptCacheTracker cacheTracker) {
 this(memoryProvider, skillManager, messageRepository, contextCompressor, properties, cacheTracker, null);
 }

 public DefaultContextEngine(MemoryProvider memoryProvider,
 SkillManager skillManager,
 MessageRepository messageRepository,
 ContextCompressor contextCompressor,
 AgentProperties properties,
 PromptCacheTracker cacheTracker,
 ModelMetadataService modelMetadataService) {
 this.memoryProvider = memoryProvider;
 this.skillManager = skillManager;
 this.messageRepository = messageRepository;
 this.contextCompressor = contextCompressor;
 this.contextProps = properties.getContext();
 this.compressionEnabled = properties.getCompression().isEnabled();
 this.cacheTracker = cacheTracker;
 this.modelMetadataService = modelMetadataService;
 // Initialize context length from model metadata if available
 if (modelMetadataService != null && properties.getModel().getModelName() != null) {
     this.contextLength = modelMetadataService.detectContextLength(properties.getModel().getModelName());
     this.thresholdTokens = (int) (contextLength * 0.75);
 }
 }

 /**
 * Inject the {@link SessionLineagePort} for loading ancestor messages
 * after compression rotation. Called by the Spring {@code @Bean} factory
 * after construction. When not set, history loading falls back to
 * current-session-only queries.
 *
 * @param sessionLineageService the lineage port, or null to disable
 */
 public void setSessionLineageService(SessionLineagePort sessionLineageService) {
     this.sessionLineageService = sessionLineageService;
 }

 public void setSessionRepository(SessionRepository sessionRepository) {
     this.sessionRepository = sessionRepository;
 }

 @Override
 public List<Message> prepareContext(Session session, List<Message> messages) {
 long __pcStart = System.nanoTime();
 if (agentMetrics != null) {
     agentMetrics.incrementPrepareContextCalls();
 }
 List<Message> __result = prepareContextInner(session, messages);
 if (agentMetrics != null) {
     agentMetrics.recordPrepareContext((System.nanoTime() - __pcStart) / 1_000_000);
 }
 return __result;
 }

 private List<Message> prepareContextInner(Session session, List<Message> messages) {
 List<Message> context = new ArrayList<>();

 // Hermes parity: NO skills are injected here. The skills INDEX (name +
 // truncated description only) is built by DefaultPromptBuilder and lives in
 // the volatile tier of the system prompt. The old appendSkills() injected
 // the first 400 chars of raw SKILL.md content for 3 arbitrary skills into
 // every system message — duplication, prompt bloat, and a second skills
 // block Hermes never has. Removed.

 // Compose system/developer message first if present
 if (!messages.isEmpty() && (messages.get(0).role() == Role.SYSTEM
 || messages.get(0).role() == Role.DEVELOPER)) {
 Message base = messages.get(0);
 context.add(base.role() == Role.DEVELOPER
 ? Message.developer(base.content()) : Message.system(base.content()));
 }

 // Then add recent history (excluding the current turn messages to avoid duplication)
 appendRecentHistory(session, context, messages);

 // Deduplicate the current turn's user message: mid-turn persistence already
 // wrote it to the DB, so appendRecentHistory loaded it as the LAST history
 // entry. Drop that trailing duplicate before appending the incoming turn
 // messages — otherwise the model sees the user input twice (surfaced by
 // HistorySanitizer merging consecutive USER messages).
 int from = (!messages.isEmpty() && (messages.get(0).role() == Role.SYSTEM
 || messages.get(0).role() == Role.DEVELOPER)) ? 1 : 0;
 if (from < messages.size()) {
 Message firstIncoming = messages.get(from);
 if (firstIncoming.role() == Role.USER) {
 // Hermes parity (_persist_session cursor semantics): drop ALL trailing
 // history USER rows equal to the incoming message. Mid-turn persistence
 // writes the user message before the first model call, and a mid-turn
 // rotation copies the whole in-flight turn into the child session —
 // the same content can therefore sit in history 2+ times. A
 // single-trailing-row dedup left the extra copies in whenever the
 // persisted suffix differed (live 2026-08-27: sanitizer merged the
 // duplicate user turns, then dropped an in-flight tool result as
 // 'orphan' — 13 -> 12 messages — corrupting the replayed context).
 while (!context.isEmpty()
     && context.get(context.size() - 1).role() == Role.USER
     && java.util.Objects.equals(context.get(context.size() - 1).content(), firstIncoming.content())) {
 context.remove(context.size() - 1);
 }
 }
 }
 context.addAll(messages.subList(from, messages.size()));

 List<Message> trimmed = trimToFit(context);
 // Hermes parity (replay_cleanup.py): strip interrupted/dangling tool tails
 // from replay history BEFORE the orphaned-tool repair pass. A process kill
 // mid-tool-call leaves an assistant(tool_calls) with no tool answers (or an
 // interrupted tool result) at the tail; without stripping, the model
 // re-issues the call on resume → infinite reboot loop (#49201, #29086).
 trimmed = ReplayCleanup.sanitize(trimmed);
 // Hermes parity: repair orphaned tool results / consecutive user turns
 // BEFORE sending to the model — strict providers (Gemini via litellm,
 // DeepSeek, Kimi) reject a tool message without a matching assistant
 // tool_call with HTTP 400 ("Missing corresponding tool call for tool
 // response message").
 trimmed = HistorySanitizer.sanitize(trimmed);
 // Preflight: trigger compression at threshold of maxTokens (before API call)
 if (shouldCompressPreflight(trimmed)) {
     // Check cooldown — skip if compressed recently
     Instant lastCompressed = lastCompressedAt.get(session.id());
     if (lastCompressed == null || Duration.between(lastCompressed, Instant.now()).getSeconds() >= COMPRESSION_COOLDOWN_SECONDS) {
         if (agentMetrics != null) agentMetrics.incrementCompressionCalls();
         List<Message> beforeCompression = new ArrayList<>(trimmed);
         trimmed = contextCompressor.compress(trimmed, contextProps.getTargetTokens() * charsPerToken());
         lastCompressedAt.put(session.id(), Instant.now());
         compressionCount.incrementAndGet();
         // Session rotation: create child session or fall back to logCompressionBoundary
         if (contextCompressor instanceof DefaultContextCompressor dcc) {
             Optional<DefaultContextCompressor.SessionRotationResult> rotationResult;
             boolean rotationFailed = false;
             try {
                 rotationResult = dcc.rotateSession(String.valueOf(session.id()), trimmed);
             } catch (RuntimeException e) {
                 log.warn("Session rotation failed for {}; retaining the parent transcript", session.id(), e);
                 rotationResult = Optional.empty();
                 rotationFailed = true;
             }
             if (rotationFailed) {
                 // Hermes restores the pre-compression in-memory transcript when
                 // atomic child publication fails; the parent remains the durable tip.
                 trimmed = beforeCompression;
                 lastCompressedAt.remove(session.id());
                 compressionCount.decrementAndGet();
                 return trimmed;
             }
             if (rotationResult.isPresent()) {
                 UUID newId = rotationResult.get().newSessionId();
                 if (agentMetrics != null) agentMetrics.incrementSessionRotations();
                // Perf fix (2026-08-28): the compression cooldown is keyed by
                // session id, but rotation just MINTED a new id — without
                // carrying lastCompressedAt over, the 10-minute cooldown never
                // applies to the child and a long tool-heavy turn re-rotates
                // every few seconds (live: 6 children in one turn). The child
                // inherits the parent's cooldown baseline.
                Instant __parentCooldown = lastCompressedAt.get(session.id());
                if (__parentCooldown != null) {
                    lastCompressedAt.put(newId, __parentCooldown);
                }
                 log.info("Session rotated: old={}, new={}, title='{}'",
                         session.id(), newId, rotationResult.get().newTitle());
                 // Rotation is published atomically with the child transcript.
                 // A returned child id is therefore always safe to adopt.
                 rotatedSessionIds.put(session.id(), newId);
             } else {
                 // Fall back to legacy compression boundary logging
                 dcc.logCompressionBoundary(String.valueOf(session.id()), ts -> {
                     lastCompressedAt.put(session.id(), ts);
                 });
             }
         }
         // Invalidate prompt cache after compression
         if (cacheTracker != null) {
             cacheTracker.invalidateSystemPrompt(String.valueOf(session.id()));
         }
     } else {
         log.debug("Skipping compression for session {} — within cooldown (last compressed {})", session.id(), lastCompressed);
     }
 }
 // P-09 (Hermes 4d1fc6ca0a, #89297): uncompressed-session guardrail. When
 // compression is disabled and mid-turn tool results push the context past
 // the model limit, warn in-loop instead of silently heading into a
 // provider 400. Defense in depth — the request still goes out unchanged.
 if (!compressionEnabled && trimmed.size() > 1) {
     int pressureTokens = estimateTokens(trimmed);
     int ctxLen = contextLength > 0 ? contextLength : contextProps.getMaxTokens();
     if (ctxLen > 0 && pressureTokens > ctxLen) {
         log.warn("Uncompressed context overflow risk for session {}: ~{} tokens vs context length {} "
             + "(agent.compression.enabled=false — enable compression or trim history)",
             session.id(), pressureTokens, ctxLen);
     }
 }
 return trimmed;
 }


 @Override
 public boolean shouldCompressPreflight(List<Message> messages) {
 // P-09: disabled compression disables preflight triggering entirely —
 // callers then surface context pressure via the uncompressed-overflow
 // warning instead of silently calling a compressor the operator turned off.
 if (!compressionEnabled) return false;
 if (messages == null || messages.isEmpty()) return false;
 int estimatedTokens = estimateTokens(messages);
 int maxTokens = contextLength > 0 ? contextLength : contextProps.getMaxTokens();
 // P7 parity: one consistent configured threshold (agent.context.threshold-percent,
 // default 0.50 — context_compressor.py:3104) for preflight and the compressor.
 return estimatedTokens > maxTokens * contextProps.getThresholdPercent();
 }

 /**
 * Update tracked token usage from a real API response.
 * Replaces the chars/4 estimate with actual token counts for accurate budget tracking.
 */
 @Override
 public void updateFromResponse(TokenUsage usage) {
 if (usage == null) return;
 this.lastPromptTokens = usage.promptTokens();
 this.lastCompletionTokens = usage.completionTokens();
 this.lastTotalTokens = usage.totalTokens();
 this.lastCacheReadTokens = usage.cacheReadTokens();
 this.lastCacheWriteTokens = usage.cacheWriteTokens();
 this.lastReasoningTokens = usage.reasoningTokens();
 log.debug("Updated token usage: prompt={}, completion={}, cacheRead={}, cacheWrite={}, reasoning={}",
 lastPromptTokens, lastCompletionTokens, lastCacheReadTokens, lastCacheWriteTokens, lastReasoningTokens);
 }

 /**
 * Get the current status for display/logging.
 */
 @Override
 public Map<String, Object> getStatus() {
 double usagePercent = contextLength > 0
 ? Math.min(100.0, (double) lastPromptTokens / contextLength * 100)
 : 0;
 return Map.of(
 "lastPromptTokens", lastPromptTokens,
 "lastCompletionTokens", lastCompletionTokens,
 "lastTotalTokens", lastTotalTokens,
 "lastCacheReadTokens", lastCacheReadTokens,
 "lastCacheWriteTokens", lastCacheWriteTokens,
 "lastReasoningTokens", lastReasoningTokens,
 "thresholdTokens", thresholdTokens,
 "contextLength", contextLength,
 "usagePercent", String.format("%.2f%%", usagePercent),
 "compressionCount", compressionCount.get()
 );
 }

 /**
  * Update model and recalculate context length from model metadata.
  * Also recalculates the compressor's threshold to stay calibrated after a model switch.
  */
 @Override
 public void updateModel(String model) {
     if (modelMetadataService != null && model != null && !model.isBlank()) {
         this.contextLength = modelMetadataService.detectContextLength(model);
         if (agentMetrics != null) {
             agentMetrics.setContextWindow(this.contextLength);
         }
         // P7 parity (context_compressor.py __init__): the configured threshold
         // percent (default 0.50) drives both preflight and the compressor —
         // no hard-coded 75%.
         this.thresholdTokens = (int) (contextLength * contextProps.getThresholdPercent());
         log.debug("Updated model: {}, contextLength={}, threshold={}", model, contextLength, thresholdTokens);
         // Wire recalculateThreshold in the compressor so it stays calibrated
         // after a model switch (e.g., 200K → 32K). Mirrors Hermes update_model():
         //   self.threshold_tokens = max(int(context_length * threshold_percent), MINIMUM_CONTEXT_LENGTH)
         //   self.tail_token_budget = int(self.threshold_tokens * self.summary_target_ratio)
         //   self.max_summary_tokens = min(int(context_length * 0.05), _SUMMARY_TOKENS_CEILING)
         if (contextCompressor != null) {
             if (contextCompressor instanceof DefaultContextCompressor dcc) {
                 dcc.recalculateThreshold(contextLength, model,
                     contextProps.getModelThresholds());
             } else {
                 contextCompressor.recalculateThreshold(contextLength);
             }
         }
     }
 }

 @Override
 public Instant getLastCompressionAt(UUID sessionId) {
     return lastCompressedAt.get(sessionId);
 }

 /**
  * Finding 5.2: Count prior user messages directly from the repository
  * instead of calling prepareContext (which triggers full context building,
  * history loading, compression checks, etc.).
  */
 @Override
 public long countPriorUserMessages(UUID sessionId) {
     try {
         // Seam audit 2026-08-21 (Hermes parity): hydration must count the SAME
         // message set the context engine sees — including lineage ancestors after
         // compression rotation. Before this, only the current session's rows were
         // counted, so every rotation reset the nudge counter to ~0 and silently
         // stretched the review interval.
         if (sessionLineageService != null) {
             List<Message> lineageMessages = sessionLineageService.loadMessagesWithAncestors(sessionId);
             if (lineageMessages != null && !lineageMessages.isEmpty()) {
                 return lineageMessages.stream()
                     .filter(m -> m.role() == Role.USER)
                     .count();
             }
         }
         // Fallback: current session only (lineage unavailable/no ancestors)
         List<MessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
         return messages.stream()
             .filter(m -> "user".equalsIgnoreCase(m.getRole()))
             .count();
     } catch (Exception e) {
         log.debug("Failed to count prior user messages for session {}: {}", sessionId, e.getMessage());
         return 0;
     }
 }

 /**
  * Returns the current context window size in tokens.
  * Used by the proactive compression check in DefaultAgentRuntime.
  */
 public int getContextLength() {
     return contextLength;
 }

 private int charsPerToken() {
 if (modelMetadataService != null) {
 return modelMetadataService.getMetadata(
 contextProps != null ? contextProps.toString() : ""
 ).charsPerToken();
 }
 return 4;
 }

 private int estimateTokens(List<Message> messages) {
        // Preflight estimates must be based on the context being sent NOW.
        // Reusing the preceding request's full prompt-token count makes even a
        // tiny post-compression transcript look permanently oversized, causing
        // a fresh rotation on every model call (live 2026-08-28 session-search
        // turn: eight child sessions in two minutes).
        return estimateChars(messages) / charsPerToken();
 }

 private List<Message> trimToFit(List<Message> context) {
 int maxMessages = contextProps.getMaxContextMessages();
 if (maxMessages <= 0 || maxMessages < 500) {
     maxMessages = 10000; // H-SYNC: effectively unlimited — compression handles trimming
 }
 // Perf/correctness fix (2026-08-28 ZaiException investigation): the trim
 // bounds must track the REAL model window (contextLength from metadata),
 // not the static config max-tokens (16K default). With the config value the
 // trim loop gutted any history over ~64K chars — dropping assistant tool_call
 // messages mid-run, leaving [system, orphan-tool] pairs the sanitizer then
 // reduced to a single [system] message — and providers reject a request with
 // no user turn ("The messages parameter is illegal"). The real window for
 // zai-glm-5.2 is 202K tokens; Hermes trims against the model window and lets
 // compression (75% threshold) handle the rest.
 int windowTokens = contextLength > 0 ? contextLength : contextProps.getMaxTokens();
 int maxChars = windowTokens * charsPerToken();
 // Trim target mirrors the compression threshold fraction of the window
 // (target-tokens stays the summary-size floor for tiny windows).
 int targetChars = Math.max(
     contextProps.getTargetTokens(),
     (int) (windowTokens * contextProps.getThresholdPercent()))
     * charsPerToken();

 if (context.size() <= maxMessages && estimateChars(context) <= maxChars) {
 return context;
 }

 List<Message> trimmed = new ArrayList<>(context);
 // Hermes parity (context_engine.py protect_first_n): the first N non-system
 // messages are always preserved verbatim — they carry the initial task/setup
 // context that must survive hard overflow trimming.
 int protectFirstN = contextProps.getProtectFirstN();
 while (trimmed.size() > maxMessages || estimateChars(trimmed) > targetChars) {
 if (trimmed.size() <= 2) break;
 boolean removed = false;
 for (int i = 1; i < trimmed.size() - 1; i++) {
     // Skip protected head messages (first N non-system after index 0)
     if (i <= protectFirstN) continue;
 // M25: If this message is a tool call (has toolCalls), also remove
 // the following tool result messages to keep pairs together.
 trimmed.remove(i);
 // Remove trailing TOOL messages that follow the removed tool call
 while (i < trimmed.size() - 1 && trimmed.get(i).role() == Role.TOOL) {
     trimmed.remove(i);
 }
 removed = true;
 break;
 }
 if (!removed) break;
 }
 if (estimateChars(trimmed) > maxChars) {
     // Safety invariant: hard trimming must retain a user turn. Otherwise the
     // fallback can leave [system, assistant, tool]; sanitizer drops the
     // orphan tool and ZAI receives a system-only request (400).
     boolean hasUser = trimmed.stream().anyMatch(m -> m.role() == Role.USER);
     if (!hasUser) {
         for (int i = context.size() - 1; i >= 0; i--) {
             if (context.get(i).role() == Role.USER) {
                 trimmed.add(context.get(i));
                 break;
             }
         }
     }
     // Hermes parity: never drop the system prompt during hard overflow.
     // Hermes has deterministic pruning/salvage passes; the Java fallback
     // was truncating to last 2 messages, silently removing the system
     // prompt — which is load-bearing context that must survive.
     // Keep system (index 0 if SYSTEM/DEVELOPER) + last N messages.
     int keepTail = Math.min(trimmed.size(), 4);
     int startFrom = trimmed.size() - keepTail;
     boolean hasSystemAt0 = !trimmed.isEmpty()
         && (trimmed.get(0).role() == Role.SYSTEM || trimmed.get(0).role() == Role.DEVELOPER);
     if (hasSystemAt0 && startFrom > 1) {
         List<Message> preserved = new ArrayList<>(keepTail + 1);
         preserved.add(trimmed.get(0));
         preserved.addAll(trimmed.subList(startFrom, trimmed.size()));
         trimmed = preserved;
     } else {
         trimmed = new ArrayList<>(trimmed.subList(Math.max(0, startFrom), trimmed.size()));
     }
     log.warn("Context exceeded hard token limit; truncated to last {} messages (system prompt preserved: {})",
         hasSystemAt0 ? keepTail + 1 : keepTail, hasSystemAt0);
 }
 return trimmed;
 }

 private int estimateChars(List<Message> messages) {
 int total = 0;
 for (Message m : messages) {
 total += m.content() != null ? m.content().length() : 0;
 total += 20;
 // Account for image parts: each image costs IMAGE_CHAR_EQUIVALENT chars in the budget.
 // Mirrors the original project's _content_length_for_budget() image handling.
 int images = m.imageCount() != null ? m.imageCount() : 0;
 total += images * DefaultContextCompressor.IMAGE_CHAR_EQUIVALENT;
 }
 return total;
 }

 private void appendRecentHistory(Session session, List<Message> context, List<Message> turnMessages) {
     try {
         List<Message> lineageMessages = null;
         boolean historyLoadedFromDb = false;
         // Turn-scoped cache: avoid re-reading full lineage history from DB on
         // every prepareContext() call within this agentic loop.
         UUID sid = session.id();
         TurnHistorySnapshot snapshot = turnHistorySnapshot.get();
         if (snapshot != null && snapshot.sessionId().equals(sid) && snapshot.turnMessages() == turnMessages) {
             lineageMessages = snapshot.history();
         }
         if (lineageMessages == null) {
             // When SessionLineageService is available, load messages from the
             // entire session lineage (root-to-tip) so that ancestor messages from
             // compression-rotated sessions are included. Mirrors Hermes
             // get_messages_as_conversation(include_ancestors=True).
             if (sessionLineageService != null) {
                 lineageMessages = sessionLineageService.loadMessagesWithAncestors(sid);
                 historyLoadedFromDb = true;
             }
             // Cache the loaded history for reuse within this turn
             if (lineageMessages != null) {
                 turnHistorySnapshot.set(new TurnHistorySnapshot(sid, turnMessages, List.copyOf(lineageMessages)));
             }
         }
         if (sessionLineageService != null && lineageMessages != null) {
             if (!lineageMessages.isEmpty()) {
                 // Only the dedicated compaction carrier survives persistence. All
                 // ordinary system/developer prompts are rebuilt for every turn.
                 List<Message> active = new ArrayList<>(lineageMessages.size());
                 for (Message m : lineageMessages) {
                     if ((m.role() != Role.SYSTEM && m.role() != Role.DEVELOPER)
                         || DefaultContextCompressor.isCompactionCarrier(m)) {
                         active.add(m);
                     }
                 }
                 if (active.isEmpty()) {
                     return;
                 }
                 // Apply the same maxMessages limit as the paginated path.
                 int maxMessages = contextProps.getMaxContextMessages();
                 if (maxMessages <= 0 || maxMessages < 500) {
                     maxMessages = 10000; // H-SYNC: effectively unlimited — let compression handle it
                 }
                 List<Message> recent;
                 if (active.size() > maxMessages) {
                     // Keep the most recent N messages
                     recent = new ArrayList<>(active.subList(
                         active.size() - maxMessages, active.size()));
                 } else {
                     recent = new ArrayList<>(active);
                 }
                 context.addAll(recent);
                 if (agentMetrics != null && historyLoadedFromDb) {
                     agentMetrics.recordHistoryRowsLoaded(lineageMessages.size());
                 }
                 return;
             }
             // No lineage messages found — fall through to current-session query
         }

         // Use paginated query to load only the last N messages instead of loading all.
         // Query in descending order (newest first) then reverse to get ascending.
         int maxMessages = contextProps.getMaxContextMessages();
         if (maxMessages <= 0) {
             maxMessages = 50;
         }
         List<MessageEntity> descHistory = messageRepository.findBySessionIdOrderByCreatedAtDesc(
             session.id(), org.springframework.data.domain.PageRequest.of(0, maxMessages));
         // Reverse to get ascending order (defensive copy in case the list is immutable)
         java.util.List<MessageEntity> ascHistory = new java.util.ArrayList<>(descHistory);
         java.util.Collections.reverse(ascHistory);
         if (agentMetrics != null) {
             agentMetrics.recordHistoryRowsLoaded(ascHistory.size());
         }
         for (MessageEntity e : ascHistory) {
             String role = e.getRole();
             String content = e.getContent() != null ? e.getContent() : "";
             int turnIdx = e.getTurnIndex() != null ? e.getTurnIndex() : 0;
             // Hermes parity: SYSTEM/DEVELOPER rows are regenerated each turn by
             // the prompt builder and must never load back as history (the old
             // default branch mapped them to Message.user(...) — a system prompt
             // masquerading as a user turn in every replay).
             if ("system".equalsIgnoreCase(role) || "developer".equalsIgnoreCase(role)) {
                 continue;
             }
             context.add(switch (role) {
                 // Hermes parity: history loaded from the DB must carry the
                 // assistant's tool_call (id/name/args) in toolCalls — the
                 // sanitizer and the wire mapper match tool results against
                 // that list. Mapping to a bare assistant() message left the
                 // following TOOL result "orphaned" → dropped → strict
                 // providers 400 → CONTEXT_OVERFLOW misclassification.
                 case "assistant" -> {
                     List<com.azhukov.agent.core.model.ToolCall> calls =
                         com.azhukov.agent.persistence.mapper.ToolCallPersistenceCodec
                             .deserialize(e.getToolCallsJson());
                     if (!calls.isEmpty()) {
                         yield Message.assistantWithToolCalls(content, calls, turnIdx);
                     }
                     // Pre-V35 rows carry only one scalar call. Do not use those
                     // fields when a malformed batch exists: it would recreate
                     // the first call and orphan the rest of the saved results.
                     if (!com.azhukov.agent.persistence.mapper.ToolCallPersistenceCodec
                         .hasSerializedBatch(e.getToolCallsJson())
                         && (e.getToolCallId() != null || e.getToolCallName() != null)) {
                         yield Message.assistantWithToolCalls(content,
                             List.of(new com.azhukov.agent.core.model.ToolCall(
                                 e.getToolCallId(), e.getToolCallName(), e.getToolCallArguments())),
                             turnIdx);
                     }
                     yield Message.assistant(content, turnIdx);
                 }
                 case "tool" -> Message.toolResult(e.getToolCallId(), content, turnIdx);
                 default -> Message.user(content);
             });
         }
     } catch (Exception e) {
         log.debug("History load failed: {}", e.getMessage());
     }
 }

    @Override
    public void evict(UUID sessionId) {
        if (sessionId == null) return;
        snapshotCache.remove(sessionId);
        lastMemoryHash.remove(sessionId);
        lastCompressedAt.remove(sessionId);
        evictTurnCache(sessionId);
    }

    /**
     * Evict the turn-scoped history snapshot for the current request thread.
     * Called when a new turn starts or compression rotates the session.
     */
    public void evictTurnCache(UUID sessionId) {
        TurnHistorySnapshot snapshot = turnHistorySnapshot.get();
        if (snapshot != null && (sessionId == null || snapshot.sessionId().equals(sessionId))) {
            turnHistorySnapshot.remove();
        }
    }
}