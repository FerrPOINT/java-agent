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
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class DefaultContextEngine implements ContextEngine {

 private static final int RECALL_LIMIT = 5;
 private static final int SKILL_LIMIT = 3;
 private static final long COMPRESSION_COOLDOWN_SECONDS = 600;
 private static final double PREFLIGHT_THRESHOLD = 0.8;

 private final MemoryProvider memoryProvider;
 private final SkillManager skillManager;
 private final MessageRepository messageRepository;
 private final ContextCompressor contextCompressor;
 private final AgentProperties.ContextProperties contextProps;
 private final PromptCacheTracker cacheTracker;
 private final ModelMetadataService modelMetadataService;

 /**
  * Session lineage port for loading ancestor messages after compression rotation.
  * Optional — set via {@link #setSessionLineageService} after construction.
  * When null, falls back to loading current-session-only history.
  */
 private SessionLineagePort sessionLineageService;

 private final Map<UUID, Map<String, String>> snapshotCache = new ConcurrentHashMap<>();
 private final Map<UUID, String> lastMemoryHash = new ConcurrentHashMap<>();
 private final ConcurrentHashMap<UUID, Instant> lastCompressedAt = new ConcurrentHashMap<>();

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

 @Override
 public List<Message> prepareContext(Session session, List<Message> messages) {
 List<Message> context = new ArrayList<>();

 StringBuilder systemExtra = new StringBuilder();
 appendSkills(systemExtra);
 // Memory is injected into the system prompt by DefaultPromptBuilder, not here

 // Compose system/developer message first if present
 if (!messages.isEmpty() && (messages.get(0).role() == Role.SYSTEM
 || messages.get(0).role() == Role.DEVELOPER)) {
 Message base = messages.get(0);
 String systemText = base.content();
 if (!systemExtra.isEmpty()) {
 systemText = systemText + "\n\n" + systemExtra;
 }
 context.add(base.role() == Role.DEVELOPER
 ? Message.developer(systemText) : Message.system(systemText));
 }

 // Then add recent history (excluding the current turn messages to avoid duplication)
 appendRecentHistory(session, context);

 // Add remaining incoming messages after system/developer
 int start = (!messages.isEmpty() && (messages.get(0).role() == Role.SYSTEM
 || messages.get(0).role() == Role.DEVELOPER)) ? 1 : 0;
 context.addAll(messages.subList(start, messages.size()));

 List<Message> trimmed = trimToFit(context);
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
                 log.info("Session rotated: old={}, new={}, title='{}'",
                         session.id(), rotationResult.get().newSessionId(), rotationResult.get().newTitle());
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

 @Override
 public boolean shouldCompressPreflight(List<Message> messages) {
 if (messages == null || messages.isEmpty()) return false;
 int estimatedTokens = estimateTokens(messages);
 int maxTokens = contextLength > 0 ? contextLength : contextProps.getMaxTokens();
 return estimatedTokens > maxTokens * PREFLIGHT_THRESHOLD;
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
         this.thresholdTokens = (int) (contextLength * 0.75);
         log.debug("Updated model: {}, contextLength={}, threshold={}", model, contextLength, thresholdTokens);
         // Wire recalculateThreshold in the compressor so it stays calibrated
         // after a model switch (e.g., 200K → 32K). Mirrors Hermes update_model():
         //   self.threshold_tokens = max(int(context_length * threshold_percent), MINIMUM_CONTEXT_LENGTH)
         //   self.tail_token_budget = int(self.threshold_tokens * self.summary_target_ratio)
         //   self.max_summary_tokens = min(int(context_length * 0.05), _SUMMARY_TOKENS_CEILING)
         if (contextCompressor != null) {
             contextCompressor.recalculateThreshold(contextLength);
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
         // Query all messages for the session and count user-role ones.
         // This is much cheaper than prepareContext which loads skills,
         // checks compression, builds system prompt, etc.
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
 if (maxMessages <= 0) {
 maxMessages = 50;
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
 trimmed = new ArrayList<>(trimmed.subList(Math.max(0, trimmed.size() - 2), trimmed.size()));
 log.warn("Context exceeded hard token limit; truncated to last 2 messages");
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

 private void appendSkills(StringBuilder sb) {
 List<String> names = skillManager.listSkillNames();
 if (names.isEmpty()) return;

 int count = 0;
 sb.append("Available skills:\n");
 for (String name : names) {
 if (++count > SKILL_LIMIT) break;
 String content = skillManager.getSkill(name);
 if (content != null) {
 sb.append("- ").append(name).append(": ")
 .append(content.length() > 400 ? content.substring(0, 400) + "..." : content)
 .append("\n");
 }
 }
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
             context.add(switch (role) {
                 case "assistant" -> Message.assistant(content, e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                 case "tool" -> Message.toolResult(e.getToolCallId(), content, e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                 default -> Message.user(content);
             });
         }
     } catch (Exception e) {
         log.debug("History load failed: {}", e.getMessage());
     }
 }
}