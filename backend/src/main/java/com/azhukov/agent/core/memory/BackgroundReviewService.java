package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.memory.ReviewToolProvider;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S1/S3/S7: Self-improvement background review service.
 * <p>
 * After each turn, forks a mini conversation loop (up to 8 turns, configurable) with a
 * tool whitelist (memory + skill tools only) to analyze the conversation
 * and save durable facts to memory or update skills.
 * <p>
 * S3 fixes:
 * <ul>
 * <li>Full JSON Schema for each review tool (via {@link ReviewToolSchemas})</li>
 * <li>WriteOrigin tracking — all review writes tagged as BACKGROUND_REVIEW</li>
 * <li>Review results surfaced to user via {@link ReviewSummary}</li>
 * </ul>
 * S7 fixes:
 * <ul>
 * <li>Per-turn prompt selection (memory-only, skill-only, combined)</li>
 * <li>Runtime inheritance — uses parent's model/client/config</li>
 * <li>External memory isolation — review doesn't pollute external providers</li>
 * <li>Provenance metadata on all memory writes</li>
 * <li>Stale-action filtering — skips prior conversation tool results</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BackgroundReviewService {

 private static final int DEFAULT_MAX_REVIEW_TURNS = 8;

 // S1: Tool whitelist — only memory and skill tools are allowed
 private static final Set<String> REVIEW_TOOL_WHITELIST = Set.of(
 "memory", "skill_manage", "skills_list", "skill_view"
 );

 private final ModelClient modelClient;
 private final MemoryProvider memoryProvider;
 private final WriteApprovalGate writeApprovalGate;
 private final ReviewToolProvider reviewToolProvider;
 private final AgentProperties properties;

 private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
 Thread t = new Thread(r, "background-review");
 t.setDaemon(true);
 return t;
 });

 private final ConcurrentHashMap<UUID, AtomicBoolean> memoryUpdatedFlags = new ConcurrentHashMap<>();
 private final ConcurrentHashMap<UUID, List<String>> reviewActions = new ConcurrentHashMap<>();
 // S3: Review summaries for user-facing notifications
 private final ConcurrentHashMap<UUID, ReviewSummary> reviewSummaries = new ConcurrentHashMap<>();
 // S7: Track which sessions have been reviewed to prevent stale-action re-processing
 private final ConcurrentHashMap<UUID, StaleActionFilter.PriorToolResults> priorResultsCache = new ConcurrentHashMap<>();

 /**
  * Review a turn's conversation and save facts to memory if appropriate.
  * Runs asynchronously with a configurable delay.
  *
  * S7: Uses per-turn prompt selection based on conversation content.
  * C3: Uses the parent session's userId for the review session so memory
  * writes are attributed to the actual user.
  */
 public void reviewTurn(UUID sessionId, List<Message> messages, String parentUserId) {
     reviewTurn(sessionId, messages, parentUserId, true, true);
 }

 /**
  * Backward-compatible overload — defaults parentUserId to null (falls back
  * to "review-bot" in {@link #doReview}).
  */
 public void reviewTurn(UUID sessionId, List<Message> messages) {
     reviewTurn(sessionId, messages, null, true, true);
 }

 /**
  * Backward-compatible nudge-gated overload — defaults parentUserId to null.
  */
 public void reviewTurn(UUID sessionId, List<Message> messages,
                        boolean reviewMemory, boolean reviewSkills) {
     reviewTurn(sessionId, messages, null, reviewMemory, reviewSkills);
 }

 /**
  * Nudge-gated review: only reviews memory and/or skills depending on which
  * nudge counter triggered. Mirrors Hermes spawn_background_review_thread
  * which receives review_memory and review_skills booleans and picks the
  * prompt accordingly.
  *
  * C3: Uses the parent session's userId for the review session so memory
  * writes are attributed to the actual user, not a throwaway "review-bot".
  *
  * @param reviewMemory  true if the memory nudge counter triggered
  * @param reviewSkills   true if the skill nudge counter triggered
  */
 public void reviewTurn(UUID sessionId, List<Message> messages, String parentUserId,
                        boolean reviewMemory, boolean reviewSkills) {
     reviewTurn(sessionId, messages, parentUserId, reviewMemory, reviewSkills, null);
 }

 /**
  * Hermes parity (/refine, background_review.py:1320-1358): explicit
  * user-triggered review with optional focus instructions. A non-empty focus
  * bypasses the enabled-gate (same contract as zeroing nudge intervals —
  * manual refine keeps working when automatic forks are disabled, #87250)
  * and appends a focus block to the chosen review prompt.
  */
 public void reviewTurn(UUID sessionId, List<Message> messages, String parentUserId,
                        boolean reviewMemory, boolean reviewSkills, String focus) {
     String f = focus == null ? "" : focus.strip();
     // Explicit /refine (focus set) bypasses the enabled gate.
     if (f.isEmpty() && !properties.getMemory().getBackgroundReview().isEnabled()) {
         return;
     }
     if (messages == null || messages.isEmpty()) {
         return;
     }
     if (!reviewMemory && !reviewSkills) {
         return;
     }

     int delayMs = properties.getMemory().getBackgroundReview().getDelayMs();
     executor.schedule(() -> {
         try {
             doReview(sessionId, messages, parentUserId, reviewMemory, reviewSkills, f);
         } catch (Exception e) {
             log.error("Background review failed for session {}: {}", sessionId, e.getMessage());
         }
     }, delayMs, TimeUnit.MILLISECONDS);
 }

 /**
 * S3: Check if the background review produced memory writes for a session.
 */
 public boolean wasMemoryUpdated(UUID sessionId) {
 AtomicBoolean flag = memoryUpdatedFlags.get(sessionId);
 return flag != null && flag.get();
 }

 /**
 * S3: Get the list of actions performed during the review.
 */
 public List<String> getReviewActions(UUID sessionId) {
 return reviewActions.getOrDefault(sessionId, List.of());
 }

 /**
 * S3: Get the review summary for a session (for user-facing notification).
 */
 public ReviewSummary getReviewSummary(UUID sessionId) {
 return reviewSummaries.getOrDefault(sessionId, ReviewSummary.empty());
 }

 /**
 * S3: Check if a session has a pending review summary to surface to the user.
 */
 public boolean hasReviewSummary(UUID sessionId) {
 ReviewSummary summary = reviewSummaries.get(sessionId);
 return summary != null && summary.hasActions();
 }

 /**
 * Clear the memory updated flag and review summary for a session.
 */
 public void clearFlag(UUID sessionId) {
 memoryUpdatedFlags.remove(sessionId);
 reviewActions.remove(sessionId);
 reviewSummaries.remove(sessionId);
 priorResultsCache.remove(sessionId);
 }

 /**
  * S3/S7: Mini conversation loop with tool whitelist and full schemas.
  * Nudge-gated: picks the review prompt based on which nudge triggered,
  * mirroring Hermes spawn_background_review_thread prompt selection.
  */
 private void doReview(UUID sessionId, List<Message> messages, String parentUserId,
                       boolean reviewMemory, boolean reviewSkills, String focus) {
     log.debug("Starting background review for session {} (memory={}, skills={})",
         sessionId, reviewMemory, reviewSkills);

     // S7: Collect prior tool results for stale-action filtering
     StaleActionFilter.PriorToolResults priorResults = StaleActionFilter.collectPriorToolResults(messages);
     priorResultsCache.put(sessionId, priorResults);

     // Pick the review prompt based on which nudge triggered — same logic as
     // Hermes spawn_background_review_thread: combined > memory-only > skill-only.
     String reviewPrompt;
     if (reviewMemory && reviewSkills) {
         reviewPrompt = ReviewPromptSelector.selectPrompt(messages);
     } else if (reviewMemory) {
         reviewPrompt = ReviewPrompts.MEMORY_REVIEW_PROMPT;
     } else {
         reviewPrompt = ReviewPrompts.SKILL_REVIEW_PROMPT;
     }
     // Hermes parity (background_review.py:1352-1358): /refine focus block.
     String f = focus == null ? "" : focus.strip();
     if (!f.isEmpty()) {
         reviewPrompt = reviewPrompt + "\n\n"
             + "The user explicitly requested this review with the following "
             + "focus — prioritize it over the general instructions above:\n"
             + f;
     }
     log.debug("Background review for session {}: prompt type = {}",
         sessionId, reviewMemory && reviewSkills
             ? ReviewPromptSelector.selectReviewType(messages)
             : (reviewMemory ? "MEMORY_ONLY" : "SKILL_ONLY"));

 // Build review messages: system prompt + conversation snapshot
 List<Message> reviewMessages = new ArrayList<>();
 reviewMessages.add(Message.system(ReviewPrompts.REVIEW_SYSTEM_PROMPT));

 // Add conversation snapshot (last few messages for context).
 // Hermes parity (background_review.py _digest_history): never let the
 // snapshot START with a tool message — a tool result whose assistant
 // tool_call fell outside the window is an orphan that Gemini/LiteLLM
 // reject with "Missing corresponding tool call for tool response"
 // (observed live: review retried forever on a memory-limit error result).
 int start = Math.max(0, messages.size() - 10);
 while (start < messages.size() && "tool".equals(messages.get(start).role())) {
 start++;
 }
 for (Message m : messages.subList(start, messages.size())) {
 if (m.content() != null && !m.content().isBlank()) {
 reviewMessages.add(m);
 }
 }

 // S7: Add the selected review prompt (not hardcoded combined)
 reviewMessages.add(Message.user(reviewPrompt));

 // S3: Tool definitions with full JSON Schema parameters (not empty Map.of())
 List<ToolDefinition> tools = ReviewToolSchemas.build();

 // S1: Create review session — use the parent session's userId so memory
 // writes are attributed to the actual user, not a throwaway "review-bot".
 String reviewUserId = parentUserId != null ? parentUserId : "review-bot";
 Session reviewSession = Session.create(reviewUserId, "openai-compatible", "");
 boolean memoryUpdated = false;
 List<String> actions = new ArrayList<>();

 // S7: Set WriteContext for this review thread — all writes tagged as BACKGROUND_REVIEW
 WriteContext.setReviewContext(sessionId.toString(), null, "background-review");

 // M9: Configurable max review turns (default 8, via AgentProperties)
 int maxReviewTurns = properties.getMemory().getBackgroundReview().getMaxReviewTurns();
 if (maxReviewTurns <= 0) {
     maxReviewTurns = DEFAULT_MAX_REVIEW_TURNS;
 }

 try {
 // S1: Mini conversation loop (up to maxReviewTurns turns)
 for (int turn = 0; turn < maxReviewTurns; turn++) {
 ChatResponse response = modelClient.complete(reviewMessages, tools);

 if (!response.hasToolCalls()) {
 // Model finished — check for text response
 if (response.content() != null && !response.content().isBlank()) {
 log.debug("Background review finished: {}", response.content());
 }
 break;
 }

 // S1: Process tool calls with whitelist enforcement
 boolean anyToolExecuted = false;
 for (ToolCall call : response.toolCalls()) {
 if (!REVIEW_TOOL_WHITELIST.contains(call.name())) {
 log.warn("Background review denied non-whitelisted tool: {}", call.name());
 // Add denied result to conversation
 reviewMessages.add(Message.assistantToolCalls(
 List.of(new ToolCall(call.id(), call.name(), call.arguments())), turn));
 reviewMessages.add(Message.toolResult(call.pairingId(),
 "{\"error\":\"Tool not allowed in background review\"}", turn));
 continue;
 }

 // Execute whitelisted tool
 ToolResult result = executeWhitelistedTool(call, reviewSession);
 anyToolExecuted = true;

 // S7: Build a tool-result message for stale-action filtering
 Message toolResultMsg = Message.toolResult(call.pairingId(), result.content(), turn);

 // S7: Skip stale actions — tool results already in prior conversation
 if (StaleActionFilter.isStale(toolResultMsg, priorResults)) {
 log.debug("Background review skipping stale tool result: callId={}", call.id());
 } else if (result.success()) {
 // Track actions for summary
 String action = summarizeAction(call, result);
 if (action != null) {
 actions.add(action);
 }
 if ("memory".equals(call.name())) {
 memoryUpdated = true;
 }
 log.debug("Background review tool {}: {}", call.name(), result.content());
 }

 // Add assistant tool call and tool result to conversation
 reviewMessages.add(Message.assistantToolCalls(
 List.of(new ToolCall(call.id(), call.name(), call.arguments())), turn));
 reviewMessages.add(Message.toolResult(call.pairingId(), result.content(), turn));
 }

 if (!anyToolExecuted) {
 // All tool calls were denied — stop the loop
 break;
 }
 }

 // S1/S3: Update flags, actions, and build review summary
 if (memoryUpdated || !actions.isEmpty()) {
 memoryUpdatedFlags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false)).set(memoryUpdated);
 reviewActions.put(sessionId, actions);
 // S3: Build and store the ReviewSummary for user notification
 ReviewSummary summary = ReviewSummary.of(memoryUpdated, actions);
 reviewSummaries.put(sessionId, summary);
 // H9: Log the formatted summary so the review result is surfaced (not dead code).
 log.info("Background review completed for session {}: memoryUpdated={}, actions={}",
 sessionId, memoryUpdated, actions);
 if (summary.hasActions() && !summary.formattedSummary().isBlank()) {
     log.info("Background review summary for session {}: {}", sessionId, summary.formattedSummary());
 }
 } else {
 log.debug("Background review found nothing to save for session {}", sessionId);
 }

 } catch (Exception e) {
 log.error("Background review model call failed for session {}: {}", sessionId, e.getMessage());
 } finally {
 // S7: Clear WriteContext for this thread
 WriteContext.clear();
 }
 }

 /**
 * S1: Execute a whitelisted tool call.
 * S7: WriteContext is set on the current thread, so tool implementations
 * can pick up the BACKGROUND_REVIEW origin.
 */
 private ToolResult executeWhitelistedTool(ToolCall call, Session session) {
 try {
 return switch (call.name()) {
 case "memory" -> reviewToolProvider.execute("memory", call.arguments(), session);
 case "skill_manage" -> reviewToolProvider.execute("skill_manage", call.arguments(), session);
 case "skills_list" -> reviewToolProvider.execute("skills_list", call.arguments(), session);
 case "skill_view" -> reviewToolProvider.execute("skill_view", call.arguments(), session);
 default -> ToolResult.fail("Tool not in whitelist: " + call.name());
 };
 } catch (Exception e) {
     log.warn("Background review tool '{}' execution failed: {}", call.name(), e.getMessage());
     return ToolResult.fail("Tool execution error: " + e.getMessage());
 }
 }

 /**
 * S1/S3: Summarize what was done in a tool call for the action summary.
 * Mirrors the original project's {@code summarize_background_review_actions}.
 */
 static String summarizeAction(ToolCall call, ToolResult result) {
 String content = result.content();
 if (content == null) return null;
 String lower = content.toLowerCase();
 if ("memory".equals(call.name())) {
 if (lower.contains("added") || lower.contains("replaced") || lower.contains("removed")) {
 return "Memory: " + content;
 }
 }
 if ("skill_manage".equals(call.name())) {
 if (lower.contains("saved") || lower.contains("deleted") || lower.contains("patched") ||
 lower.contains("created") || lower.contains("updated") || lower.contains("written")) {
 return "Skill: " + content;
 }
 }
 return null;
 }

 /**
 * Shutdown the executor.
 */
 @PreDestroy
 public void shutdown() {
 executor.shutdown();
 }
}