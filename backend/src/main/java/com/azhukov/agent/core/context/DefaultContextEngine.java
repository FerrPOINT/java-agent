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
 private final PromptCacheTracker cacheTracker;
 private final ModelMetadataService modelMetadataService;

 /**
  * Session lineage port for loading ancestor messages after compression rotation.
  * Optional — set via {@link #setSessionLineageService} after construction.
  * When null, falls back to loading current-session-only history.
  */
 private SessionLineagePort sessionLineageService;
 private SessionRepository sessionRepository;

 private final Map<UUID, Map<String, String>> snapshotCache = new ConcurrentHashMap<>();
 private final Map<UUID, UUID> rotatedSessionIds = new ConcurrentHashMap<>();
 private final Map<UUID, String> lastMemoryHash = new ConcurrentHashMap<>();
 private final ConcurrentHashMap<UUID, Instant> lastCompressedAt = new ConcurrentHashMap<>();

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
 appendRecentHistory(session, context);

 // Deduplicate the current turn's user message: mid-turn persistence already
 // wrote it to the DB, so appendRecentHistory loaded it as the LAST history
 // entry. Drop that trailing duplicate before appending the incoming turn
 // messages — otherwise the model sees the user input twice (surfaced by
 // HistorySanitizer merging consecutive USER messages).
 int from = (!messages.isEmpty() && (messages.get(0).role() == Role.SYSTEM
 || messages.get(0).role() == Role.DEVELOPER)) ? 1 : 0;
 if (from < messages.size()) {
 Message firstIncoming = messages.get(from);
 if (firstIncoming.role() == Role.USER && !context.isEmpty()) {
 Message lastHistory = context.get(context.size() - 1);
 if (lastHistory.role() == Role.USER
 && java.util.Objects.equals(lastHistory.content(), firstIncoming.content())) {
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
         trimmed = contextCompressor.compress(trimmed, contextProps.getTargetTokens() * charsPerToken());
         lastCompressedAt.put(session.id(), Instant.now());
         compressionCount.incrementAndGet();
         // Session rotation: create child session or fall back to logCompressionBoundary
         if (contextCompressor instanceof DefaultContextCompressor dcc) {
             var rotationResult = dcc.rotateSession(String.valueOf(session.id()));
             if (rotationResult.isPresent()) {
                 UUID newId = rotationResult.get().newSessionId();
                 rotatedSessionIds.put(session.id(), newId);
                 log.info("Session rotated: old={}, new={}, title='{}'",
                         session.id(), newId, rotationResult.get().newTitle());
                 // P2 (Hermes conversation_loop.py:2740): the compacted transcript
                 // (summary + protected tail) becomes the child session's rows, so
                 // the next turn loads the post-compaction state instead of the
                 // deactivated ancestor history.
                 persistRotatedTranscript(newId, trimmed);
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
 return trimmed;
 }

 /**
  * P2 (Hermes conversation_loop.py:2740): persist the compacted transcript
  * (compaction summary + protected tail) as the rotated child session's rows.
  * The next turn then loads the post-compaction state; ancestor rows were
  * deactivated by the rotation and never re-enter the model context.
  */
 private void persistRotatedTranscript(UUID childSessionId, List<Message> compacted) {
     if (messageRepository == null || compacted == null || compacted.isEmpty()) {
         return;
     }
     try {
         Instant now = Instant.now();
         int order = 0;
         for (Message m : compacted) {
             MessageEntity e = new MessageEntity();
             e.setSessionId(childSessionId);
             e.setRole(m.role() != null ? m.role().name().toLowerCase() : "user");
             e.setContent(m.content() != null ? m.content() : "");
             if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                 var first = m.toolCalls().get(0);
                 e.setToolCallId(first.id());
                 e.setToolCallName(first.name());
                 e.setToolCallArguments(first.arguments());
             }
             if (m.role() == Role.TOOL && m.toolCallId() != null) {
                 e.setToolCallId(m.toolCallId());
             }
             e.setTurnIndex(m.turnIndex() > 0 ? m.turnIndex() : order);
             e.setActive(true);
             e.setCompacted(false);
             e.setCreatedAt(now.plusNanos(order++)); // stable ascending order
             messageRepository.save(e);
         }
         log.info("Rotated transcript persisted: {} rows into child session {}", compacted.size(), childSessionId);
     } catch (RuntimeException e) {
         log.warn("Failed to persist rotated transcript into child session {}", childSessionId, e);
     }
 }

 @Override
 public boolean shouldCompressPreflight(List<Message> messages) {
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
 // Use real token usage if available (from last API response)
 if (lastPromptTokens > 0) {
 // Rough estimate: scale last real usage by current message count ratio
 int estimated = lastPromptTokens;
 // This is a preflight estimate — real usage will update after the API call
 return estimated;
 }
 // Fallback to chars/4 estimate
 return estimateChars(messages) / charsPerToken();
 }

 private List<Message> trimToFit(List<Message> context) {
 int maxMessages = contextProps.getMaxContextMessages();
 if (maxMessages <= 0 || maxMessages < 500) {
     maxMessages = 10000; // H-SYNC: effectively unlimited — compression handles trimming
 }
 int maxChars = contextProps.getMaxTokens() * charsPerToken();
 int targetChars = contextProps.getTargetTokens() * charsPerToken();

 if (context.size() <= maxMessages && estimateChars(context) <= maxChars) {
 return context;
 }

 List<Message> trimmed = new ArrayList<>(context);
 while (trimmed.size() > maxMessages || estimateChars(trimmed) > targetChars) {
 if (trimmed.size() <= 2) break;
 boolean removed = false;
 for (int i = 1; i < trimmed.size() - 1; i++) {
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

 private void appendRecentHistory(Session session, List<Message> context) {
     try {
         // When SessionLineageService is available, load messages from the
         // entire session lineage (root-to-tip) so that ancestor messages from
         // compression-rotated sessions are included. Mirrors Hermes
         // get_messages_as_conversation(include_ancestors=True).
         if (sessionLineageService != null) {
             List<Message> lineageMessages = sessionLineageService.loadMessagesWithAncestors(session.id());
             if (lineageMessages != null && !lineageMessages.isEmpty()) {
                 // Apply the same maxMessages limit as the paginated path.
                 int maxMessages = contextProps.getMaxContextMessages();
                 if (maxMessages <= 0 || maxMessages < 500) {
                     maxMessages = 10000; // H-SYNC: effectively unlimited — let compression handle it
                 }
                 List<Message> recent;
                 if (lineageMessages.size() > maxMessages) {
                     // Keep the most recent N messages
                     recent = new ArrayList<>(lineageMessages.subList(
                         lineageMessages.size() - maxMessages, lineageMessages.size()));
                 } else {
                     recent = new ArrayList<>(lineageMessages);
                 }
                 context.addAll(recent);
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
         for (MessageEntity e : ascHistory) {
             String role = e.getRole();
             String content = e.getContent() != null ? e.getContent() : "";
             int turnIdx = e.getTurnIndex() != null ? e.getTurnIndex() : 0;
             context.add(switch (role) {
                 // Hermes parity: history loaded from the DB must carry the
                 // assistant's tool_call (id/name/args) in toolCalls — the
                 // sanitizer and the wire mapper match tool results against
                 // that list. Mapping to a bare assistant() message left the
                 // following TOOL result "orphaned" → dropped → strict
                 // providers 400 → CONTEXT_OVERFLOW misclassification.
                 case "assistant" -> {
                     if (e.getToolCallId() != null || e.getToolCallName() != null) {
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
    }
}