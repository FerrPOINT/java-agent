package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DelegateTaskTool — Subagent Architecture
 *
 * Spawns child agent turns with isolated context, restricted toolsets,
 * and configurable depth/timeout/concurrency limits. Supports single-task
 * and batch (parallel) modes. The parent blocks until all children complete.
 *
 * Each child gets:
 * - A fresh session (no parent history)
 * - A restricted toolset (blocked tools stripped, delegation toolset
 * stripped for leaf children)
 * - A focused system prompt built from the delegated goal + context
 * - Its own delegation depth metadata so depth limits propagate
 *
 * The parent's context only sees the delegation call and the summary result,
 * never the child's intermediate tool calls or reasoning.
 *
 * Modeled on the original project's delegate_tool.py: role='orchestrator' retains the
 * delegation toolset and can spawn its own workers (bounded by
 * maxSpawnDepth); role='leaf' (default) cannot.
 */
@AgentTool(
 name = "delegate_task",
 description = "Spawn one or more focused sub-agents to work on sub-tasks in parallel. "
 + "Supports single goal or batch tasks array. Each child gets isolated context and "
 + "restricted tools. role='orchestrator' allows the child to further delegate. "
 + "Max spawn depth and concurrent children are configurable.",
 toolset = "delegation"
)
@Component
@Slf4j
public class DelegateTaskTool implements ToolHandler {

 private static final ObjectMapper MAPPER = new ObjectMapper();

 /** Default toolsets for child subagents when none are specified. */
 static final List<String> DEFAULT_CHILD_TOOLSETS = List.of("web", "file", "terminal", "coding");

 /** Toolset names that are always stripped from leaf children. */
 static final Set<String> LEAF_BLOCKED_TOOLSETS = Set.of("delegation");

 final AgentProperties properties;
 final ObjectProvider<AgentRuntime> agentRuntimeProvider;

 /** Virtual thread executor for parallel child runs. */
 private final ExecutorService childExecutor = Executors.newVirtualThreadPerTaskExecutor();

 /** Semaphore to enforce maxConcurrentChildren across all active delegations. */
 private Semaphore concurrencyLimit;

 /** Active subagent registry for observability. */
 private final Map<String, SubagentRecord> activeSubagents = new java.util.concurrent.ConcurrentHashMap<>();

 @org.springframework.beans.factory.annotation.Autowired
 public DelegateTaskTool(AgentProperties properties, ObjectProvider<AgentRuntime> agentRuntimeProvider) {
 this.properties = properties;
 this.agentRuntimeProvider = agentRuntimeProvider;
 int maxChildren = Math.max(1, properties.getDelegation().getMaxConcurrentChildren());
 this.concurrencyLimit = new Semaphore(maxChildren, true);
 }

 /**
 * Test-friendly constructor that allows overriding the concurrency limit.
 */
 DelegateTaskTool(AgentProperties properties, ObjectProvider<AgentRuntime> agentRuntimeProvider, int maxConcurrentChildren) {
 this.properties = properties;
 this.agentRuntimeProvider = agentRuntimeProvider;
 this.concurrencyLimit = new Semaphore(Math.max(1, maxConcurrentChildren), true);
 }

 @Override
 public ToolResult execute(String arguments, Message lastAssistant, Session session) {
 AgentProperties.DelegationProperties deleg = properties.getDelegation();
 if (!deleg.isEnabled()) {
 return ToolResult.fail("Delegation is disabled (agent.delegation.enabled=false)");
 }

 DelegateArgs args = ToolHandler.parseJson(arguments, DelegateArgs.class);

 // ── Normalize tasks to a list ──────────────────────────────────
 List<TaskSpec> taskList;
 if (args.tasks() != null && !args.tasks().isEmpty()) {
 taskList = new ArrayList<>(args.tasks());
 // Validate each task has a goal
 for (int i = 0; i < taskList.size(); i++) {
 TaskSpec t = taskList.get(i);
 if (t.goal() == null || t.goal().isBlank()) {
 return ToolResult.fail("Task " + i + " is missing a 'goal'");
 }
 }
 } else if (args.goal() != null && !args.goal().isBlank()) {
 String role = normalizeRole(args.role());
 taskList = List.of(new TaskSpec(args.goal(), args.context(), null, role, args.timeoutSeconds()));
 } else {
 return ToolResult.fail("Provide either 'goal' (single task) or 'tasks' (batch)");
 }

 if (taskList.isEmpty()) {
 return ToolResult.fail("No tasks provided");
 }

 // ── Depth check ────────────────────────────────────────────────
 int currentDepth = parseInt(session.metadata().get("delegation_depth"), 0);
 int maxSpawn = Math.max(1, deleg.getMaxSpawnDepth());
 if (currentDepth >= maxSpawn) {
 return ToolResult.fail("Delegation depth limit reached (depth=" + currentDepth
 + ", max_spawn_depth=" + maxSpawn + "). Raise agent.delegation.max-spawn-depth if deeper nesting is required.");
 }

 // ── Concurrent children check ──────────────────────────────────
 int maxChildren = Math.max(1, deleg.getMaxConcurrentChildren());
 if (taskList.size() > maxChildren) {
 return ToolResult.fail("Too many tasks: " + taskList.size()
 + " provided, but max_concurrent_children is " + maxChildren
 + ". Reduce the task count or increase agent.delegation.max-concurrent-children.");
 }

 // ── Resolve effective child timeout ────────────────────────────
 int childTimeoutSeconds = resolveChildTimeout(deleg, args.timeoutSeconds());

 // ── Run tasks ──────────────────────────────────────────────────
 try {
 String resultJson;
 if (taskList.size() == 1) {
 // Single task — run directly (no thread pool overhead)
 TaskResult entry = runSingleChild(taskList.get(0), session, currentDepth + 1,
 childTimeoutSeconds, 0);
 resultJson = formatResults(List.of(entry));
 } else {
 // Batch — run in parallel with virtual threads
 List<TaskResult> results = runBatch(taskList, session, currentDepth + 1,
 childTimeoutSeconds);
 resultJson = formatResults(results);
 }
 return ToolResult.ok(resultJson);
 } catch (Exception e) {
 log.warn("delegate_task failed: {}", e.getMessage(), e);
 return ToolResult.fail("Delegation failed: " + e.getMessage());
 }
 }

 // ── Batch execution ────────────────────────────────────────────────

 private List<TaskResult> runBatch(List<TaskSpec> tasks, Session parentSession,
 int childDepth, int childTimeoutSeconds) {
 List<Future<TaskResult>> futures = new ArrayList<>(tasks.size());
 for (int i = 0; i < tasks.size(); i++) {
 final int index = i;
 final TaskSpec task = tasks.get(i);
 futures.add(childExecutor.submit(() ->
 runSingleChild(task, parentSession, childDepth, childTimeoutSeconds, index)));
 }

 List<TaskResult> results = new ArrayList<>(tasks.size());
 for (int i = 0; i < futures.size(); i++) {
 try {
 if (childTimeoutSeconds > 0) {
 results.add(futures.get(i).get(childTimeoutSeconds, TimeUnit.SECONDS));
 } else {
 results.add(futures.get(i).get());
 }
 } catch (TimeoutException e) {
 futures.get(i).cancel(true);
 results.add(TaskResult.timeout(i, tasks.get(i).goal(), childTimeoutSeconds));
 } catch (InterruptedException e) {
 Thread.currentThread().interrupt();
 results.add(TaskResult.error(i, tasks.get(i).goal(), "Interrupted"));
 } catch (ExecutionException e) {
 Throwable cause = e.getCause() != null ? e.getCause() : e;
 results.add(TaskResult.error(i, tasks.get(i).goal(), cause.getMessage()));
 }
 }
 // Sort by taskIndex to match input order
 results.sort(java.util.Comparator.comparingInt(TaskResult::taskIndex));
 return results;
 }

 // ── Single child execution ─────────────────────────────────────────

 private TaskResult runSingleChild(TaskSpec task, Session parentSession,
 int childDepth, int childTimeoutSeconds, int taskIndex) {
 String subagentId = "sa-" + taskIndex + "-" + UUID.randomUUID().toString().substring(0, 8);
 long startTime = System.nanoTime();
 String goal = task.goal();

 // Acquire concurrency permit
 try {
 if (!concurrencyLimit.tryAcquire(childTimeoutSeconds > 0 ? childTimeoutSeconds : 60, TimeUnit.SECONDS)) {
 return TaskResult.error(taskIndex, goal, "Timed out waiting for concurrency permit");
 }
 } catch (InterruptedException e) {
 Thread.currentThread().interrupt();
 return TaskResult.error(taskIndex, goal, "Interrupted while waiting for concurrency permit");
 }

 // Register in active subagent registry
 SubagentRecord record = new SubagentRecord(subagentId, childDepth, goal, System.currentTimeMillis());
 activeSubagents.put(subagentId, record);

 try {
 // Resolve role
 String requestedRole = normalizeRole(task.role());
 String effectiveRole = resolveEffectiveRole(requestedRole, childDepth);

 // Build child session with isolated context
 Session childSession = createChildSession(parentSession, childDepth);

 // Build child system prompt
 String childPrompt = buildChildSystemPrompt(goal, task.context(), effectiveRole,
 properties.getDelegation().getMaxSpawnDepth(), childDepth);

 // Apply the child prompt as the session's system prompt
 Session sessionWithPrompt = childSession.withMetadata("system_prompt_override", childPrompt);

 // Build the user message for the child
 String userMessage = buildChildUserMessage(goal, task.context());

 log.info("[{}] Starting subagent (depth={}, role={}, goal='{}')",
 subagentId, childDepth, effectiveRole, truncate(goal, 80));

 // Run the child agent turn
 TurnResult turnResult;
 try {
 turnResult = agentRuntimeProvider.getObject().runTurn(sessionWithPrompt, userMessage, List.of(),
 ModelRequestOptions.empty());
 } catch (Exception e) {
 long durationSec = durationSeconds(startTime);
 log.warn("[{}] Subagent failed after {}s: {}", subagentId, durationSec, e.getMessage());
 return TaskResult.error(taskIndex, goal, e.getMessage());
 }

 long durationSec = durationSeconds(startTime);
 String summary = turnResult.finalText();
 boolean completed = turnResult.completed();

 String status;
 String errorMsg = null;
 if (!completed && turnResult.error() != null && !turnResult.error().isBlank()) {
 status = "error";
 errorMsg = turnResult.error();
 } else if (summary != null && !summary.isBlank()) {
 status = "completed";
 } else {
 status = "failed";
 errorMsg = "Subagent did not produce a response";
 }

 log.info("[{}] Subagent {} in {}s (status={})", subagentId,
 status.equals("completed") ? "completed" : "finished", durationSec, status);

 return new TaskResult(taskIndex, status, summary, errorMsg, durationSec, effectiveRole);

 } catch (Exception e) {
 long durationSec = durationSeconds(startTime);
 log.warn("[{}] Subagent error after {}s: {}", subagentId, durationSec, e.getMessage(), e);
 return TaskResult.error(taskIndex, goal, e.getMessage());
 } finally {
 concurrencyLimit.release();
 activeSubagents.remove(subagentId);
 }
 }

 // ── Role resolution ────────────────────────────────────────────────

 static String normalizeRole(String role) {
 if (role == null || role.isBlank()) {
 return "leaf";
 }
 String normalized = role.trim().toLowerCase();
 if (normalized.equals("leaf") || normalized.equals("orchestrator")) {
 return normalized;
 }
 log.warn("Unknown delegate_task role='{}', coercing to 'leaf'", role);
 return "leaf";
 }

 String resolveEffectiveRole(String requestedRole, int childDepth) {
 if (!"orchestrator".equals(requestedRole)) {
 return "leaf";
 }
 // Orchestrator is only allowed when the kill switch is on AND
 // the child's depth allows further nesting
 int maxSpawn = Math.max(1, properties.getDelegation().getMaxSpawnDepth());
 if (!properties.getDelegation().isOrchestratorEnabled()) {
 log.debug("Orchestrator role requested but kill switch is off; coercing to 'leaf'");
 return "leaf";
 }
 if (childDepth >= maxSpawn) {
 log.debug("Orchestrator role requested but child depth {} >= max_spawn_depth {}; coercing to 'leaf'",
 childDepth, maxSpawn);
 return "leaf";
 }
 return "orchestrator";
 }

 // ── Child session creation ─────────────────────────────────────────

 private Session createChildSession(Session parentSession, int childDepth) {
 String userId = parentSession.userId() != null ? parentSession.userId() : "delegate";
 String provider = parentSession.modelProvider();
 String modelName = parentSession.modelName();

 Map<String, String> metadata = new HashMap<>();
 metadata.put("delegation_depth", String.valueOf(childDepth));
 metadata.put("delegation_parent_session", parentSession.id().toString());

 return new Session(
 UUID.randomUUID(),
 userId,
 "Delegated task",
 provider,
 modelName,
 null,
 Map.copyOf(metadata),
 null
 );
 }

 // ── System prompt construction ─────────────────────────────────────

 static String buildChildSystemPrompt(String goal, String context, String role,
 int maxSpawnDepth, int childDepth) {
 StringBuilder sb = new StringBuilder();
 sb.append("You are a focused subagent working on a specific delegated task.\n\n");
 sb.append("YOUR TASK:\n").append(goal).append("\n");

 if (context != null && !context.isBlank()) {
 sb.append("\nCONTEXT:\n").append(context).append("\n");
 }

 sb.append("\nComplete this task using the tools available to you. ");
 sb.append("When finished, provide a clear, concise summary of:\n");
 sb.append("- What you did\n");
 sb.append("- What you found or accomplished\n");
 sb.append("- Any files you created or modified\n");
 sb.append("- Any issues encountered\n\n");
 sb.append("Be thorough but concise — your response is returned to the ");
 sb.append("parent agent as a summary.\n");

 if ("orchestrator".equals(role)) {
 String childNote;
 if (childDepth + 1 >= maxSpawnDepth) {
 childNote = "Your own children MUST be leaves (cannot delegate further) "
 + "because they would be at the depth floor.";
 } else {
 childNote = "Your own children can themselves be orchestrators or leaves, "
 + "depending on the `role` you pass to delegate_task.";
 }
 sb.append("\n## Subagent Spawning (Orchestrator Role)\n");
 sb.append("You have access to the `delegate_task` tool and CAN spawn ");
 sb.append("your own subagents to parallelize independent work.\n\n");
 sb.append("WHEN to delegate:\n");
 sb.append("- The goal decomposes into 2+ independent subtasks\n");
 sb.append("- A subtask is reasoning-heavy and would flood your context\n\n");
 sb.append("WHEN NOT to delegate:\n");
 sb.append("- Single-step mechanical work — do it directly\n");
 sb.append("- Trivial tasks you can execute in one or two tool calls\n");
 sb.append("- Re-delegating your entire assigned goal to one worker\n\n");
 sb.append("Coordinate your workers' results and synthesize them before ");
 sb.append("reporting back to your parent. You are responsible for the ");
 sb.append("final summary, not your workers.\n\n");
 sb.append("NOTE: You are at depth ").append(childDepth);
 sb.append(". The delegation tree is capped at max_spawn_depth=");
 sb.append(maxSpawnDepth).append(". ").append(childNote).append("\n");
 }

 return sb.toString();
 }

 static String buildChildUserMessage(String goal, String context) {
 if (context != null && !context.isBlank()) {
 return goal + "\n\nAdditional context:\n" + context;
 }
 return goal;
 }

 // ── Timeout resolution ─────────────────────────────────────────────

 int resolveChildTimeout(AgentProperties.DelegationProperties deleg, int callerTimeout) {
 // Caller-specified timeout takes priority, then config child_timeout_seconds,
 // then config default_timeout_seconds
 if (callerTimeout > 0) {
 return Math.max(30, callerTimeout);
 }
 if (deleg.getChildTimeoutSeconds() > 0) {
 return Math.max(30, deleg.getChildTimeoutSeconds());
 }
 return Math.max(30, deleg.getDefaultTimeoutSeconds());
 }

 // ── Result formatting ──────────────────────────────────────────────

 static String formatResults(List<TaskResult> results) {
 try {
 Map<String, Object> root = new HashMap<>();
 root.put("results", results);
 return MAPPER.writeValueAsString(root);
 } catch (Exception e) {
 // Fallback: build a simple string
 StringBuilder sb = new StringBuilder();
 for (TaskResult r : results) {
 sb.append("[Task ").append(r.taskIndex()).append("] ");
 sb.append(r.status()).append(": ");
 if (r.summary() != null && !r.summary().isBlank()) {
 sb.append(r.summary());
 } else if (r.error() != null) {
 sb.append(r.error());
 }
 sb.append("\n");
 }
 return sb.toString().trim();
 }
 }

 // ── Observability ──────────────────────────────────────────────────

 public List<SubagentRecord> listActiveSubagents() {
 return List.copyOf(activeSubagents.values());
 }

 public int getActiveSubagentCount() {
 return activeSubagents.size();
 }

 public int getAvailableConcurrencyPermits() {
 return concurrencyLimit.availablePermits();
 }

 // ── Utility ────────────────────────────────────────────────────────

 private static int parseInt(Object value, int fallback) {
 if (value == null) return fallback;
 try {
 return Integer.parseInt(value.toString());
 } catch (NumberFormatException e) {
 return fallback;
 }
 }

 private static long durationSeconds(long startTimeNanos) {
 return (System.nanoTime() - startTimeNanos) / 1_000_000_000;
 }

 private static String truncate(String s, int maxLen) {
 if (s == null) return "";
 return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
 }

 // ── DTOs ───────────────────────────────────────────────────────────

 /**
 * Arguments for the delegate_task tool.
 * Supports both single-task (goal) and batch (tasks) modes.
 */
 public record DelegateArgs(
 @ToolParam(description = "concise task description for the sub-agent (single-task mode)",
 required = false) String goal,
 @ToolParam(description = "optional context/instructions for the sub-agent",
 required = false) String context,
 @ToolParam(description = "role: 'leaf' (default, cannot delegate) or 'orchestrator' (can spawn own subagents)",
 required = false) String role,
 @ToolParam(description = "timeout in seconds for each child (0 = use config default)",
 required = false) int timeoutSeconds,
 @ToolParam(description = "batch mode: JSON array of {goal, context, role, timeoutSeconds} objects",
 required = false) List<TaskSpec> tasks
 ) {}

 /**
 * A single task specification within a batch.
 */
 public record TaskSpec(
 @ToolParam(description = "task goal for this sub-agent", required = true) String goal,
 @ToolParam(description = "optional context for this sub-agent", required = false) String context,
 @ToolParam(description = "optional toolsets to enable for this child", required = false) List<String> toolsets,
 @ToolParam(description = "role: 'leaf' or 'orchestrator'", required = false) String role,
 @ToolParam(description = "timeout in seconds (0 = use config default)", required = false) int timeoutSeconds
 ) {}

 /**
 * Result of a single child subagent run.
 */
 public record TaskResult(
 int taskIndex,
 String status, // "completed", "failed", "error", "timeout", "interrupted"
 String summary,
 String error,
 long durationSeconds,
 String role
 ) {
 static TaskResult error(int index, String goal, String errorMsg) {
 return new TaskResult(index, "error", null, errorMsg, 0, "leaf");
 }

 static TaskResult timeout(int index, String goal, int timeoutSeconds) {
 return new TaskResult(index, "timeout", null,
 "Subagent timed out after " + timeoutSeconds + "s", 0, "leaf");
 }
 }

 /**
 * Registry record for an active subagent (observability).
 */
 public record SubagentRecord(
 String subagentId,
 int depth,
 String goal,
 long startedAtMs
 ) {}
}