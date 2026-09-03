package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.persistence.entity.DelegatedTaskRunEntity;
import com.azhukov.agent.service.DelegatedTaskRunService;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * Hermes-style background mode is available explicitly via background/async/live
 * arguments or action=create; that path returns a durable run id immediately.
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
 *
 * <p>Toolset inheritance (parity with Hermes delegate_tool.py lines 987-1029):
 * <ul>
 *   <li>When no toolsets specified → inherits the parent's enabled toolsets
 *       with blocked toolsets stripped.</li>
 *   <li>When explicit toolsets specified → intersected with the parent's
 *       enabled toolsets (child can only use what parent has), with blocked
 *       toolsets stripped.</li>
 * </ul>
 * The effective toolsets are passed to the child via session metadata key
 * {@code delegation_toolsets} (comma-separated), which {@code DefaultAgentRuntime}
 * reads to filter the tool definitions exposed to the child.
 */
@AgentTool(
    name = "delegate_task",
    description = "Spawn subagents in isolated contexts; each gets its own conversation, terminal session, and toolset, and only its final summary returns to you. Provide 'goal' for a single task or 'tasks' for a parallel batch (limits and nesting rules are in the parameter descriptions).\n\nJava background mode: background=true, async=true, live=true, or action='create' returns immediately with run_id/delegation_id and records durable status/read/cancel state. Completion is persisted and published to the event stream; until the full Hermes gateway reinjection loop lands in Java, inspect completion with action='status' or action='read'.\n\nLIVE ORCHESTRATION: while synchronous children run, this tool also controls them - action='list' (live children + ids), action='steer' (subagent_id + message, redirect without stopping), action='stop' (subagent_id, end early; partial result still returns). Steer when a live transcript shows a child drifting.\n\nUSE FOR: reasoning-heavy subtasks, work that would flood your context with intermediate data, or independent parallel workstreams.\nDO NOT USE FOR (use these instead):\n- Mechanical multi-step work with no reasoning needed -> execute_code\n- A single tool call -> call the tool directly\n- Tasks needing user interaction -> subagents cannot ask questions\n- Durable scheduled work -> cronjob or terminal(background=True, notify_on_complete=True).\n\nRULES:\n- Children know nothing of this conversation: pass everything needed via 'context', including any required output language, tone, or style (e.g. \"respond in Chinese\").\n- Child summaries are SELF-REPORTS, not verified facts: a child claiming \"uploaded successfully\" or \"file written\" may be wrong. For external side effects (uploads, remote writes, publishing), require a verifiable handle (URL, ID, absolute path) and verify it yourself - fetch the URL, stat the file, read back the content - before telling the user the operation succeeded.\n- Leaf children (the default) cannot call delegate_task, clarify, memory, send_message, or cronjob; orchestrators regain only delegate_task.\n- Children inherit the parent model and fallback chain unless pinned globally via delegation.provider / delegation.model in config.yaml. Results are returned as an array, one entry per task.",
    toolset = "delegation"
)
@Component
@Slf4j
public class DelegateTaskTool implements ToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Toolset names that are always stripped from child subagents (parity with
     * Hermes {@code _strip_blocked_tools} / {@code DELEGATE_BLOCKED_TOOLS}).
     * These map toolset names containing blocked tools.
     */
    static final Set<String> BLOCKED_TOOLSET_NAMES = Set.of(
        "delegation",    // no recursive delegation (leaf children)
        "memory",        // no writes to shared MEMORY.md
        "gateway",       // no cross-platform side effects (send_message)
        "clarify",       // no user interaction
        "cronjob"        // no scheduling more work in the parent's name
    );

    /** Session metadata key for effective child toolsets (comma-separated). */
    static final String META_TOOLSETS = "delegation_toolsets";
    /** Session metadata key for child-only disabled tool names (comma-separated). */
    static final String META_DISABLED_TOOLS = "delegation_disabled_tools";
    /** Session metadata key for max iterations override. */
    static final String META_MAX_TURNS = "delegation_max_turns";
    /** Session metadata key for ACP command override. */
    static final String META_ACP_COMMAND = "delegation_acp_command";
    /** Session metadata key for ACP args override (comma-separated). */
    static final String META_ACP_ARGS = "delegation_acp_args";

    final AgentProperties properties;
    final ObjectProvider<AgentRuntime> agentRuntimeProvider;
    final ObjectProvider<ToolRegistry> toolRegistryProvider;
    final ObjectProvider<InterruptToken> interruptTokenProvider;
    final ObjectProvider<SteerBuffer> steerBufferProvider;
    final ObjectProvider<DelegatedTaskRunService> delegatedTaskRunServiceProvider;

    /** Virtual thread executor for parallel child runs. */
    private final ExecutorService childExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** Semaphore to enforce maxConcurrentChildren across all active delegations. */
    private Semaphore concurrencyLimit;

    /** Active subagent registry for observability. */
    private final Map<String, SubagentRecord> activeSubagents = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile boolean spawnPaused = false;

    @org.springframework.beans.factory.annotation.Autowired
    public DelegateTaskTool(AgentProperties properties,
                            ObjectProvider<AgentRuntime> agentRuntimeProvider,
                            ObjectProvider<ToolRegistry> toolRegistryProvider,
                            ObjectProvider<InterruptToken> interruptTokenProvider,
                            ObjectProvider<SteerBuffer> steerBufferProvider,
                            ObjectProvider<DelegatedTaskRunService> delegatedTaskRunServiceProvider) {
        this.properties = properties;
        this.agentRuntimeProvider = agentRuntimeProvider;
        this.toolRegistryProvider = toolRegistryProvider;
        this.interruptTokenProvider = interruptTokenProvider;
        this.steerBufferProvider = steerBufferProvider;
        this.delegatedTaskRunServiceProvider = delegatedTaskRunServiceProvider;
        int maxChildren = Math.max(1, properties.getDelegation().getMaxConcurrentChildren());
        this.concurrencyLimit = new Semaphore(maxChildren, true);
    }

    DelegateTaskTool(AgentProperties properties,
                     ObjectProvider<AgentRuntime> agentRuntimeProvider,
                     ObjectProvider<ToolRegistry> toolRegistryProvider,
                     ObjectProvider<InterruptToken> interruptTokenProvider,
                     ObjectProvider<SteerBuffer> steerBufferProvider) {
        this(properties, agentRuntimeProvider, toolRegistryProvider,
            interruptTokenProvider, steerBufferProvider, null);
    }

    /**
     * Test-friendly constructor that allows overriding the concurrency limit.
     */
    DelegateTaskTool(AgentProperties properties,
                     ObjectProvider<AgentRuntime> agentRuntimeProvider,
                     ObjectProvider<ToolRegistry> toolRegistryProvider,
                     int maxConcurrentChildren) {
        this.properties = properties;
        this.agentRuntimeProvider = agentRuntimeProvider;
        this.toolRegistryProvider = toolRegistryProvider;
        this.interruptTokenProvider = null;
        this.steerBufferProvider = null;
        this.delegatedTaskRunServiceProvider = null;
        this.concurrencyLimit = new Semaphore(Math.max(1, maxConcurrentChildren), true);
    }

    DelegateTaskTool(AgentProperties properties,
                     ObjectProvider<AgentRuntime> agentRuntimeProvider,
                     ObjectProvider<ToolRegistry> toolRegistryProvider,
                     ObjectProvider<DelegatedTaskRunService> delegatedTaskRunServiceProvider,
                     int maxConcurrentChildren) {
        this.properties = properties;
        this.agentRuntimeProvider = agentRuntimeProvider;
        this.toolRegistryProvider = toolRegistryProvider;
        this.interruptTokenProvider = null;
        this.steerBufferProvider = null;
        this.delegatedTaskRunServiceProvider = delegatedTaskRunServiceProvider;
        this.concurrencyLimit = new Semaphore(Math.max(1, maxConcurrentChildren), true);
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        AgentProperties.DelegationProperties deleg = properties.getDelegation();
        if (!deleg.isEnabled()) {
            return jsonError("Delegation is disabled (agent.delegation.enabled=false)");
        }

        DelegateArgs args;
        try {
            args = ToolHandler.parseJson(arguments, DelegateArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonError(e.getMessage());
        }

        // ── Live orchestration control actions (Hermes parity) ──────────
        // action=list/steer/stop are synchronous control-plane operations
        // that do NOT spawn children.
        String action = args.action();
        String normalizedAction = action == null || action.isBlank()
            ? "spawn"
            : action.trim().toLowerCase();
        if (!"spawn".equals(normalizedAction) && !"create".equals(normalizedAction)) {
            return handleControlAction(normalizedAction,
                args.subagentId(), args.runId(), args.message(), session);
        }

        // ── Normalize tasks to a list ──────────────────────────────────
        List<TaskSpec> taskList;
        if (args.tasks() != null && !args.tasks().isEmpty()) {
            taskList = new ArrayList<>(args.tasks());
            // Validate each task has a goal
            for (int i = 0; i < taskList.size(); i++) {
                TaskSpec t = taskList.get(i);
                if (t.goal() == null || t.goal().isBlank()) {
                    return jsonError("Task " + i + " is missing a 'goal'.");
                }
            }
        } else if (args.goal() != null && !args.goal().isBlank()) {
            String role = normalizeRole(args.role());
            taskList = List.of(new TaskSpec(args.goal(), args.context(), args.toolsets(),
                role, args.timeoutSeconds(), args.acpCommand(), args.acpArgs(), args.outputSchema()));
        } else {
            return jsonError("No tasks provided. Pass tasks=[{goal: '...', context: '...'}, ...] - one entry per subagent, or provide 'goal' for single-task mode.");
        }

        if (taskList.isEmpty()) {
            return jsonError("No tasks provided.");
        }

        ToolResult schemaFailure = validateTaskOutputSchemas(taskList);
        if (schemaFailure != null) {
            return schemaFailure;
        }

        // ── Depth check ────────────────────────────────────────────────
        int currentDepth = parseInt(session.metadata().get("delegation_depth"), 0);
        int maxSpawn = Math.max(1, deleg.getMaxSpawnDepth());
        if (currentDepth >= maxSpawn) {
            return jsonError("Delegation depth limit reached (depth=" + currentDepth
                + ", max_spawn_depth=" + maxSpawn + "). Raise agent.delegation.max-spawn-depth if deeper nesting is required.");
        }

        // ── Concurrent children check ──────────────────────────────────
        int maxChildren = Math.max(1, deleg.getMaxConcurrentChildren());
        if (taskList.size() > maxChildren) {
            return jsonError("Too many tasks: " + taskList.size()
                + " provided, but max_concurrent_children is " + maxChildren
                + ". Reduce the task count or increase agent.delegation.max-concurrent-children.");
        }

        // ── Resolve effective child timeout ────────────────────────────
        int childTimeoutSeconds = resolveChildTimeout(deleg, args.timeoutSeconds());

        // ── Resolve parent's enabled toolsets (for inheritance/intersection) ──
        Set<String> parentToolsets = resolveParentToolsets(session);

        // ── Resolve effective max iterations ────────────────────────────
        int effectiveMaxIterations = resolveMaxIterations(deleg, args.maxIterations());

        boolean asyncRequested = asyncRequested(args) || "create".equals(normalizedAction);
        if (asyncRequested) {
            return dispatchAsyncDelegation(taskList, session, currentDepth + 1, childTimeoutSeconds,
                parentToolsets, effectiveMaxIterations, args.acpCommand(), args.acpArgs());
        }

        // ── Run tasks ──────────────────────────────────────────────────
        try {
            String resultJson;
            if (taskList.size() == 1) {
                // Single task — run directly (no thread pool overhead) but with a timeout
                // to prevent blocking forever if the model hangs (BUG 3b).
                final int childDepth = currentDepth + 1;
                TaskResult entry = runSingleChildWithTimeout(taskList.get(0), session, childDepth,
                    childTimeoutSeconds, 0, parentToolsets, effectiveMaxIterations,
                    args.acpCommand(), args.acpArgs(), taskList.get(0).outputSchema(), null);
                resultJson = formatResults(List.of(entry));
            } else {
                // Batch — run in parallel with virtual threads
                final int childDepth = currentDepth + 1;
                List<TaskResult> results = runBatch(taskList, session,
                    childTimeoutSeconds, parentToolsets, effectiveMaxIterations,
                    args.acpCommand(), args.acpArgs(), childDepth);
                resultJson = formatResults(results);
            }
            return ToolResult.ok(resultJson);
        } catch (Exception e) {
            log.warn("delegate_task failed: {}", e.getMessage(), e);
            return jsonError("Delegation failed: " + e.getMessage());
        }
    }

    // ── Async execution ───────────────────────────────────────────────

    private ToolResult dispatchAsyncDelegation(List<TaskSpec> taskList,
                                               Session parentSession,
                                               int childDepth,
                                               int childTimeoutSeconds,
                                               Set<String> parentToolsets,
                                               int effectiveMaxIterations,
                                               String topAcpCommand,
                                               List<String> topAcpArgs) {
        DelegatedTaskRunService runService = delegatedTaskRunService();
        if (runService == null) {
            return jsonError("Async delegate_task requires the delegated task run ledger service.");
        }

        int maxChildren = Math.max(1, properties.getDelegation().getMaxConcurrentChildren());
        DelegatedTaskRunService.CreateAttempt attempt = runService.createIfCapacity(
            parentSession.id(),
            resolveProfile(parentSession),
            summarizeDelegationGoal(taskList),
            maxChildren);
        if (!attempt.accepted()) {
            return jsonRejected("Async delegation capacity reached for this session (active=" + attempt.activeCount()
                + ", max_async_children=" + attempt.capacity() + ").");
        }

        DelegatedTaskRunEntity run = attempt.run();
        UUID runId = run.getId();

        try {
            childExecutor.submit(() -> executeAsyncRun(runId, taskList, parentSession, childDepth,
                childTimeoutSeconds, parentToolsets, effectiveMaxIterations, topAcpCommand, topAcpArgs));
        } catch (RuntimeException e) {
            runService.fail(runId, e.getMessage());
            return jsonError("Failed to dispatch async delegation: " + e.getMessage());
        }

        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", true);
        response.put("status", "dispatched");
        response.put("mode", "background");
        response.put("run_id", runId.toString());
        response.put("delegation_id", delegationId(runId));
        response.put("task_count", taskList.size());
        response.put("parent_session_id", parentSession.id().toString());
        response.put("profile", run.getProfile() == null ? "default" : run.getProfile());
        response.put("message", "Delegated task run started in the background. Use action='status' or action='read' with run_id to inspect it.");
        return jsonOk(response);
    }

    private void executeAsyncRun(UUID runId,
                                 List<TaskSpec> taskList,
                                 Session parentSession,
                                 int childDepth,
                                 int childTimeoutSeconds,
                                 Set<String> parentToolsets,
                                 int effectiveMaxIterations,
                                 String topAcpCommand,
                                 List<String> topAcpArgs) {
        DelegatedTaskRunService runService = delegatedTaskRunService();
        if (runService == null) {
            return;
        }
        try {
            List<TaskResult> results;
            if (runService.isCancelRequested(runId)) {
                results = List.of(TaskResult.interrupted(0, summarizeDelegationGoal(taskList), "cancelled before start"));
            } else if (taskList.size() == 1) {
                TaskSpec task = taskList.get(0);
                results = List.of(runSingleChildWithTimeout(task, parentSession, childDepth,
                    childTimeoutSeconds, 0, parentToolsets, effectiveMaxIterations,
                    topAcpCommand, topAcpArgs, task.outputSchema(), runId));
            } else {
                results = runBatch(taskList, parentSession, childTimeoutSeconds,
                    parentToolsets, effectiveMaxIterations, topAcpCommand, topAcpArgs,
                    childDepth, runId);
            }
            String resultJson = formatResults(results);
            runService.finish(runId, aggregateStatus(results), resultJson, firstError(results));
        } catch (Exception e) {
            log.warn("async delegate_task run {} failed: {}", runId, e.getMessage(), e);
            runService.fail(runId, e.getMessage());
        }
    }

    private TaskResult runSingleChildWithTimeout(TaskSpec task, Session parentSession,
                                                 int childDepth, int childTimeoutSeconds, int taskIndex,
                                                 Set<String> parentToolsets, int effectiveMaxIterations,
                                                 String acpCommand, List<String> acpArgs,
                                                 java.util.Map<String, Object> outputSchema,
                                                 UUID delegatedRunId) {
        if (childTimeoutSeconds <= 0) {
            return runSingleChild(task, parentSession, childDepth, childTimeoutSeconds,
                taskIndex, parentToolsets, effectiveMaxIterations, acpCommand, acpArgs,
                outputSchema, delegatedRunId);
        }
        CompletableFuture<TaskResult> future = CompletableFuture.supplyAsync(
            () -> runSingleChild(task, parentSession, childDepth, childTimeoutSeconds,
                taskIndex, parentToolsets, effectiveMaxIterations, acpCommand, acpArgs,
                outputSchema, delegatedRunId),
            childExecutor
        );
        try {
            return future.get(childTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return TaskResult.timeout(taskIndex, task.goal(), childTimeoutSeconds);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return TaskResult.error(taskIndex, task.goal(), cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TaskResult.interrupted(taskIndex, task.goal(), "Interrupted");
        }
    }

    // ── Batch execution ────────────────────────────────────────────────

    private List<TaskResult> runBatch(List<TaskSpec> tasks, Session parentSession,
                                      int childTimeoutSeconds,
                                      Set<String> parentToolsets, int effectiveMaxIterations,
                                      String topAcpCommand, List<String> topAcpArgs,
                                      int childDepth) {
        return runBatch(tasks, parentSession, childTimeoutSeconds, parentToolsets,
            effectiveMaxIterations, topAcpCommand, topAcpArgs, childDepth, null);
    }

    private List<TaskResult> runBatch(List<TaskSpec> tasks, Session parentSession,
                                      int childTimeoutSeconds,
                                      Set<String> parentToolsets, int effectiveMaxIterations,
                                      String topAcpCommand, List<String> topAcpArgs,
                                      int childDepth,
                                      UUID delegatedRunId) {
        List<Future<TaskResult>> futures = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            final int index = i;
            final TaskSpec task = tasks.get(i);
            // Finding 3.3: Per-task timeout — task.timeoutSeconds() overrides childTimeoutSeconds
            final int taskTimeout = task.timeoutSeconds() > 0 ? task.timeoutSeconds() : childTimeoutSeconds;
            // Per-task acp_command/acp_args override the top-level ones (parity with Hermes)
            String taskAcpCommand = task.acpCommand() != null ? task.acpCommand() : topAcpCommand;
            List<String> taskAcpArgs = task.acpArgs() != null ? task.acpArgs() : topAcpArgs;
            futures.add(childExecutor.submit(() ->
                runSingleChild(task, parentSession, childDepth, taskTimeout, index,
                    parentToolsets, effectiveMaxIterations, taskAcpCommand, taskAcpArgs, task.outputSchema(),
                    delegatedRunId)));
        }

        List<TaskResult> results = new ArrayList<>(tasks.size());
        for (int i = 0; i < futures.size(); i++) {
            final TaskSpec task = tasks.get(i);
            int taskTimeout = task.timeoutSeconds() > 0 ? task.timeoutSeconds() : childTimeoutSeconds;
            try {
                if (taskTimeout > 0) {
                    results.add(futures.get(i).get(taskTimeout, TimeUnit.SECONDS));
                } else {
                    results.add(futures.get(i).get());
                }
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                results.add(TaskResult.timeout(i, task.goal(), taskTimeout));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(TaskResult.interrupted(i, task.goal(), "Interrupted"));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                results.add(TaskResult.error(i, task.goal(), cause.getMessage()));
            }
        }
        // Sort by taskIndex to match input order
        results.sort(java.util.Comparator.comparingInt(TaskResult::taskIndex));
        return results;
    }

    // ── Single child execution ─────────────────────────────────────────

    private TaskResult runSingleChild(TaskSpec task, Session parentSession,
                                      int childDepth, int childTimeoutSeconds, int taskIndex,
                                      Set<String> parentToolsets, int effectiveMaxIterations,
                                      String acpCommand, List<String> acpArgs,
                                      java.util.Map<String, Object> outputSchema) {
        return runSingleChild(task, parentSession, childDepth, childTimeoutSeconds,
            taskIndex, parentToolsets, effectiveMaxIterations, acpCommand, acpArgs,
            outputSchema, null);
    }

    private TaskResult runSingleChild(TaskSpec task, Session parentSession,
                                      int childDepth, int childTimeoutSeconds, int taskIndex,
                                      Set<String> parentToolsets, int effectiveMaxIterations,
                                      String acpCommand, List<String> acpArgs,
                                      java.util.Map<String, Object> outputSchema,
                                      UUID delegatedRunId) {
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

        // Finding 3.2: Check spawn-paused flag before starting
        if (spawnPaused) {
            concurrencyLimit.release();
            return TaskResult.error(taskIndex, goal, "Subagent spawning is paused");
        }

        try {
            // Resolve role
            String requestedRole = normalizeRole(task.role());
            String effectiveRole = resolveEffectiveRole(requestedRole, childDepth);

            // Resolve effective toolsets for this child (Fix 1 + Fix 3)
            List<String> childToolsets = resolveChildToolsets(task.toolsets(), parentToolsets, effectiveRole);
            List<String> childDisabledTools = resolveChildDisabledTools(effectiveRole);

            // Build child session with isolated context
            Session childSession = createChildSession(parentSession, childDepth, childToolsets,
                childDisabledTools, effectiveMaxIterations, acpCommand, acpArgs);
            markDelegatedRunStarted(delegatedRunId, childSession.id());

            // Build child system prompt
            String childPrompt = buildChildSystemPrompt(goal, task.context(), effectiveRole,
                properties.getDelegation().getMaxSpawnDepth(), childDepth);
            String schemaError = validateOutputSchemaDefinition(outputSchema);
            if (schemaError != null) {
                return TaskResult.error(taskIndex, goal, "output_schema invalid: " + schemaError);
            }
            if (outputSchema != null) {
                childPrompt += "\n\n" + buildOutputSchemaContract(outputSchema);
            }

            // Apply the child prompt as the session's system prompt
            Session sessionWithPrompt = childSession.withMetadata("system_prompt_override", childPrompt);

            // Register live control state against the child's actual isolated session.
            // DefaultAgentRuntime reads this shared SteerBuffer and InterruptToken.
            SubagentRecord record = new SubagentRecord(subagentId, childDepth, goal,
                System.currentTimeMillis(), parentSession.id().toString(), childSession.id());
            activeSubagents.put(subagentId, record);

            // Build the user message for the child
            String userMessage = buildChildUserMessage(goal, task.context());

            log.info("[{}] Starting subagent (depth={}, role={}, goal='{}', toolsets={})",
                subagentId, childDepth, effectiveRole, truncate(goal, 80), childToolsets);

            // A stop may have arrived between registration and the first child call.
            InterruptToken interruptToken = interruptTokenProvider != null ? interruptTokenProvider.getIfAvailable() : null;
            if (isAsyncCancelRequested(delegatedRunId)
                || (interruptToken != null && interruptToken.isCancelled(childSession.id()))) {
                return TaskResult.interrupted(taskIndex, goal, "Subagent interrupted before start");
            }

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
            if (isAsyncCancelRequested(delegatedRunId)) {
                status = "interrupted";
                errorMsg = "cancelled";
            } else if (!completed && turnResult.error() != null && !turnResult.error().isBlank()) {
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

    // ── Toolset resolution (Fix 1: inheritance + intersection) ──────────

    /**
     * Resolve the parent's enabled toolsets. If the parent is itself a child,
     * inherit its narrowed metadata; otherwise use configured defaults.
     */
    Set<String> resolveParentToolsets(Session parentSession) {
        Set<String> inherited = parseCsv(parentSession.getMetadata(META_TOOLSETS));
        if (!inherited.isEmpty()) {
            return inherited;
        }
        List<String> configured = properties.getSkills() != null
            ? properties.getSkills().getDefaultToolsets()
            : List.of();
        if (configured != null && !configured.isEmpty()) {
            return new LinkedHashSet<>(configured);
        }
        ToolRegistry registry = toolRegistryProvider.getIfAvailable();
        if (registry != null) {
            Set<String> registered = registry.getToolsets();
            if (registered != null && !registered.isEmpty()) {
                return new LinkedHashSet<>(registered);
            }
        }
        return new LinkedHashSet<>(List.of("web", "file", "terminal"));
    }

    /**
     * Resolve effective child toolsets (parity with Hermes lines 987-1029).
     * <p>
     * When {@code requestedToolsets} is null/empty → inherit parent's toolsets,
     * with blocked toolsets stripped.
     * When {@code requestedToolsets} is specified → intersect with parent's
     * toolsets (child can only use what parent has), with blocked toolsets stripped.
     * Orchestrator role re-adds the "delegation" toolset.
     */
    List<String> resolveChildToolsets(List<String> requestedToolsets,
                                       Set<String> parentToolsets,
                                       String effectiveRole) {
        List<String> childToolsets;
        if (requestedToolsets == null || requestedToolsets.isEmpty()) {
            // Inherit parent's enabled toolsets, strip blocked
            childToolsets = stripBlockedToolsets(new ArrayList<>(parentToolsets));
        } else {
            // Intersect with parent — subagent must not gain tools the parent lacks
            Set<String> expandedParent = expandParentToolsets(parentToolsets, effectiveRole);
            List<String> intersection = new ArrayList<>();
            for (String ts : requestedToolsets) {
                if (expandedParent.contains(ts)) {
                    intersection.add(ts);
                }
            }
            childToolsets = stripBlockedToolsets(intersection);
        }

        // Orchestrators retain the 'delegation' toolset that stripBlockedToolsets removed.
        // Re-add is unconditional on parent-toolset membership because orchestrator
        // capability is granted by role, not inherited (parity with Hermes line 1028).
        if ("orchestrator".equals(effectiveRole) && !childToolsets.contains("delegation")) {
            childToolsets.add("delegation");
        }

        return childToolsets;
    }

    /**
     * Remove toolsets that contain only blocked tools (parity with Hermes
     * {@code _strip_blocked_tools}).
     * <p>
     * Strips exact one-tool/composite-deny sets. Mixed bundles such as
     * hermes-cli are preserved and filtered later with META_DISABLED_TOOLS.
     */
    List<String> stripBlockedToolsets(List<String> toolsets) {
        toolsets.removeAll(BLOCKED_TOOLSET_NAMES);
        return toolsets;
    }

    private Set<String> expandParentToolsets(Set<String> parentToolsets, String effectiveRole) {
        ToolRegistry registry = toolRegistryProvider.getIfAvailable();
        if (registry == null || parentToolsets == null || parentToolsets.isEmpty()) {
            return parentToolsets != null ? new LinkedHashSet<>(parentToolsets) : Set.of();
        }

        Set<String> blockedNames = new LinkedHashSet<>(resolveChildDisabledTools(effectiveRole));
        Set<String> parentToolNames = new LinkedHashSet<>();
        for (var definition : registry.getDefinitions(parentToolsets)) {
            parentToolNames.add(definition.name());
        }
        if (parentToolNames.isEmpty()) {
            return new LinkedHashSet<>(parentToolsets);
        }

        Set<String> expanded = new LinkedHashSet<>(parentToolsets);
        for (String candidate : registry.getToolsets()) {
            if (expanded.contains(candidate)) {
                continue;
            }
            Set<String> candidateToolNames = new LinkedHashSet<>();
            for (var definition : registry.getDefinitions(Set.of(candidate))) {
                if (!blockedNames.contains(definition.name())) {
                    candidateToolNames.add(definition.name());
                }
            }
            if (!candidateToolNames.isEmpty() && parentToolNames.containsAll(candidateToolNames)) {
                expanded.add(candidate);
            }
        }
        return expanded;
    }

    List<String> resolveChildDisabledTools(String effectiveRole) {
        List<String> configured = properties.getDelegation().getBlockedTools();
        LinkedHashSet<String> disabled = new LinkedHashSet<>();
        if (configured != null) {
            for (String tool : configured) {
                if (tool != null && !tool.isBlank()) {
                    disabled.add(tool.trim());
                }
            }
        }
        if ("orchestrator".equals(effectiveRole)) {
            disabled.remove("delegate_task");
        }
        return new ArrayList<>(disabled);
    }

    private Set<String> parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    // ── Max iterations resolution (Fix 5) ────────────────────────────────

    /**
     * Resolve effective max iterations for child subagents.
     * <p>
     * Mirrors Hermes: the config value (delegation.max_iterations) is authoritative.
     * Model-supplied max_iterations is ignored so callers cannot unexpectedly shrink
     * or expand the child budget mid-run.
     */
    int resolveMaxIterations(AgentProperties.DelegationProperties deleg, Integer callerMaxIterations) {
        int configMax = deleg.getMaxIterations();
        if (configMax > 0) {
            return configMax;
        }
        // Fallback: 0 means "use global max-turns" (signal to DefaultAgentRuntime).
        return 0;
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

    private Session createChildSession(Session parentSession, int childDepth,
                                        List<String> childToolsets, List<String> childDisabledTools,
                                        int maxIterations,
                                        String acpCommand, List<String> acpArgs) {
        String userId = parentSession.userId() != null ? parentSession.userId() : "delegate";
        String provider = parentSession.modelProvider();
        String modelName = parentSession.modelName();

        // Apply delegation config model/provider overrides if configured
        String delegModel = properties.getDelegation().getModel();
        String delegProvider = properties.getDelegation().getProvider();
        if (delegModel != null && !delegModel.isBlank()) {
            modelName = delegModel;
        }
        if (delegProvider != null && !delegProvider.isBlank()) {
            provider = delegProvider;
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("delegation_depth", String.valueOf(childDepth));
        metadata.put("delegation_parent_session", parentSession.id().toString());

        // Pass effective toolsets to child via metadata (Fix 1)
        if (childToolsets != null && !childToolsets.isEmpty()) {
            metadata.put(META_TOOLSETS, String.join(",", childToolsets));
        }
        if (childDisabledTools != null && !childDisabledTools.isEmpty()) {
            metadata.put(META_DISABLED_TOOLS, String.join(",", childDisabledTools));
        }

        // Pass max iterations override to child via metadata (Fix 5)
        if (maxIterations > 0) {
            metadata.put(META_MAX_TURNS, String.valueOf(maxIterations));
        }

        // Pass ACP command/args to child via metadata (Fix 4)
        if (acpCommand != null && !acpCommand.isBlank()) {
            metadata.put(META_ACP_COMMAND, acpCommand);
            if (acpArgs != null && !acpArgs.isEmpty()) {
                metadata.put(META_ACP_ARGS, String.join(",", acpArgs));
            }
        }

        // When subagent-auto-approve is enabled, tag the child session so that
        // DefaultAgentRuntime skips the approval gate for all tool calls.
        if (properties.getDelegation().isSubagentAutoApprove()) {
            metadata.put("subagent_auto_approve", "true");
        }

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
        // then legacy config default_timeout_seconds. A non-positive resolved value
        // means no hard wall-clock cap, matching Hermes child_timeout_seconds=0.
        if (callerTimeout > 0) {
            return Math.max(30, callerTimeout);
        }
        if (deleg.getChildTimeoutSeconds() > 0) {
            return Math.max(30, deleg.getChildTimeoutSeconds());
        }
        if (deleg.getDefaultTimeoutSeconds() > 0) {
            return Math.max(30, deleg.getDefaultTimeoutSeconds());
        }
        return 0;
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

    private ToolResult validateTaskOutputSchemas(List<TaskSpec> taskList) {
        for (int i = 0; i < taskList.size(); i++) {
            String schemaError = validateOutputSchemaDefinition(taskList.get(i).outputSchema());
            if (schemaError != null) {
                return jsonError("Task " + i + " output_schema invalid: " + schemaError);
            }
        }
        return null;
    }

    private ToolResult jsonError(String error) {
        String message = error == null || error.isBlank() ? "Delegation failed" : error;
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", false);
        response.put("error", message);
        return new ToolResult(false, response.toString(), message);
    }

    private ToolResult jsonRejected(String error) {
        String message = error == null || error.isBlank() ? "Delegation rejected" : error;
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", false);
        response.put("status", "rejected");
        response.put("error", message);
        return new ToolResult(false, response.toString(), message);
    }

    private ToolResult jsonOk(ObjectNode response) {
        return ToolResult.ok(response.toString());
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

    // Finding 3.2: Per-subagent interrupt/pause support (parity with Hermes)

    /**
     * Pause spawning of new subagents. Already-running subagents continue.
     * Mirrors Hermes {@code set_spawn_paused(True)}.
     */
    public void setSpawnPaused(boolean paused) {
        this.spawnPaused = paused;
        log.info("Subagent spawn paused={}", paused);
    }

    /** Returns whether spawning of new subagents is currently paused. */
    public boolean isSpawnPaused() {
        return spawnPaused;
    }

    /**
     * Interrupt a specific running subagent by id.
     * Mirrors Hermes {@code interrupt_subagent(subagent_id)}.
     */
    public boolean interruptSubagent(String subagentId) {
        SubagentRecord record = activeSubagents.get(subagentId);
        InterruptToken interruptToken = interruptTokenProvider != null ? interruptTokenProvider.getIfAvailable() : null;
        if (record == null || interruptToken == null) return false;
        interruptToken.cancel(record.childSessionId());
        log.info("Interrupted subagent {}", subagentId);
        return true;
    }

    /** Check whether a subagent has been interrupted. */
    boolean isSubagentInterrupted(String subagentId) {
        SubagentRecord record = activeSubagents.get(subagentId);
        InterruptToken interruptToken = interruptTokenProvider != null ? interruptTokenProvider.getIfAvailable() : null;
        return record != null && interruptToken != null && interruptToken.isCancelled(record.childSessionId());
    }

    // ── Utility ────────────────────────────────────────────────────────

    /**
     * Handle live orchestration control actions: list, steer, stop.
     * Hermes parity (delegate_tool.py:466-579): these run synchronously
     * in-turn and operate on this conversation's active subagents.
     */
    private ToolResult handleControlAction(String action, String subagentId, String runId, String message, Session session) {
        switch (action) {
            case "list" -> {
                ObjectNode response = MAPPER.createObjectNode();
                response.put("action", "list");
                ArrayNode subagents = response.putArray("subagents");
                activeSubagents.entrySet().stream()
                    .filter(e -> session.id().toString().equals(e.getValue().parentSessionId()))
                    .forEach(e -> {
                        SubagentRecord record = e.getValue();
                        ObjectNode child = subagents.addObject();
                        child.put("subagent_id", e.getKey());
                        child.put("goal", record.goal());
                        child.put("status", "running");
                        child.put("running_seconds", Math.round((System.currentTimeMillis() - record.startedAtMs()) / 100.0) / 10.0);
                        child.put("accepting_steer", true);
                        child.put("parent_session_id", record.parentSessionId());
                        child.put("child_session_id", record.childSessionId().toString());
                    });
                response.put("count", subagents.size());
                if (subagents.isEmpty()) {
                    response.put("note", "No live subagents right now. Children that already finished have delivered their results; there is nothing to steer or stop.");
                }
                appendDurableRuns(response, session);
                return jsonOk(response);
            }
            case "stop" -> {
                if (subagentId == null || subagentId.isBlank()) {
                    return jsonError("action='stop' requires subagent_id (from the spawn dispatch response or action='list').");
                }
                var record = activeSubagents.get(subagentId);
                if (record == null || !session.id().toString().equals(record.parentSessionId())) {
                    return jsonError("No live subagent '" + subagentId + "' in this conversation's spawn tree. It may have already finished. Use action='list' to see live children.");
                }
                InterruptToken interruptToken = interruptTokenProvider != null ? interruptTokenProvider.getIfAvailable() : null;
                if (interruptToken == null) {
                    return jsonError("Subagent interruption service is unavailable.");
                }
                interruptToken.cancel(record.childSessionId());
                ObjectNode response = MAPPER.createObjectNode();
                response.put("action", "stop");
                response.put("subagent_id", subagentId);
                response.put("status", "interrupt_requested");
                response.put("note", "The subagent stops at its next iteration boundary. Its partial result still returns as a completion message.");
                return jsonOk(response);
            }
            case "steer" -> {
                if (subagentId == null || subagentId.isBlank()) {
                    return jsonError("action='steer' requires subagent_id (from the spawn dispatch response or action='list').");
                }
                if (message == null || message.isBlank()) {
                    return jsonError("action='steer' requires a non-empty 'message' describing the course correction.");
                }
                var record = activeSubagents.get(subagentId);
                if (record == null || !session.id().toString().equals(record.parentSessionId())) {
                    return jsonError("Subagent '" + subagentId + "' is no longer accepting steering (finishing or not found). Use action='list' to see live children.");
                }
                SteerBuffer steerBuffer = steerBufferProvider != null ? steerBufferProvider.getIfAvailable() : null;
                if (steerBuffer != null) {
                    steerBuffer.steer(record.childSessionId(), message);
                    ObjectNode response = MAPPER.createObjectNode();
                    response.put("action", "steer");
                    response.put("subagent_id", subagentId);
                    response.put("status", "queued");
                    response.put("note", "Steering text queued. The subagent sees it appended to its next tool result.");
                    return jsonOk(response);
                }
                return jsonError("Subagent steering service is unavailable.");
            }
            case "status", "read" -> {
                return readDelegatedRun(action, runId, session);
            }
            case "cancel" -> {
                return cancelDelegatedRun(runId, session);
            }
            default -> {
                return jsonError("Unknown action '" + action + "'. Use spawn, create, list, status, read, cancel, steer, or stop.");
            }
        }
    }

    private ToolResult readDelegatedRun(String action, String runId, Session session) {
        DelegatedTaskRunService runService = delegatedTaskRunService();
        if (runService == null) {
            return jsonError("Async delegate_task run ledger is unavailable.");
        }
        UUID id;
        try {
            id = parseRunId(runId);
        } catch (IllegalArgumentException e) {
            return jsonError(e.getMessage());
        }
        return runService.findForParent(id, session.id())
            .map(entity -> {
                ObjectNode response = MAPPER.createObjectNode();
                response.put("success", true);
                response.put("action", action);
                response.set("run", delegatedRunJson(entity, "read".equals(action)));
                return jsonOk(response);
            })
            .orElseGet(() -> jsonError("Delegated run '" + runId + "' was not found for this session."));
    }

    private ToolResult cancelDelegatedRun(String runId, Session session) {
        DelegatedTaskRunService runService = delegatedTaskRunService();
        if (runService == null) {
            return jsonError("Async delegate_task run ledger is unavailable.");
        }
        UUID id;
        try {
            id = parseRunId(runId);
        } catch (IllegalArgumentException e) {
            return jsonError(e.getMessage());
        }
        try {
            DelegatedTaskRunEntity entity = runService.requestCancel(id, session.id());
            if (entity.getChildSessionId() != null) {
                InterruptToken interruptToken = interruptTokenProvider != null ? interruptTokenProvider.getIfAvailable() : null;
                if (interruptToken != null) {
                    interruptToken.cancel(entity.getChildSessionId());
                }
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("success", true);
            response.put("action", "cancel");
            response.put("status", entity.getStatus());
            response.set("run", delegatedRunJson(entity, true));
            return jsonOk(response);
        } catch (IllegalArgumentException e) {
            return jsonError(e.getMessage());
        }
    }

    private void appendDurableRuns(ObjectNode response, Session session) {
        ArrayNode runs = response.putArray("runs");
        DelegatedTaskRunService runService = delegatedTaskRunService();
        if (runService == null) {
            response.put("run_count", 0);
            return;
        }
        try {
            for (DelegatedTaskRunEntity entity : runService.listForParent(session.id(), 25)) {
                runs.add(delegatedRunJson(entity, false));
            }
        } catch (RuntimeException e) {
            response.put("runs_error", e.getMessage());
        }
        response.put("run_count", runs.size());
    }

    private ObjectNode delegatedRunJson(DelegatedTaskRunEntity entity, boolean includeResult) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("run_id", entity.getId().toString());
        node.put("delegation_id", delegationId(entity.getId()));
        node.put("parent_session_id", entity.getParentSessionId().toString());
        if (entity.getChildSessionId() != null) {
            node.put("child_session_id", entity.getChildSessionId().toString());
        }
        node.put("profile", entity.getProfile() == null ? "default" : entity.getProfile());
        node.put("goal", entity.getGoal());
        node.put("status", entity.getStatus());
        putInstant(node, "created_at", entity.getCreatedAt());
        putInstant(node, "started_at", entity.getStartedAt());
        putInstant(node, "completed_at", entity.getCompletedAt());
        putInstant(node, "cancel_requested_at", entity.getCancelRequestedAt());
        putInstant(node, "delivered_at", entity.getDeliveredAt());
        putInstant(node, "delivery_dropped_at", entity.getDeliveryDroppedAt());
        putInstant(node, "delivery_claimed_at", entity.getDeliveryClaimedAt());
        node.put("delivery_state", deliveryState(entity));
        node.put("delivery_pending", entity.getCompletedAt() != null
            && entity.getDeliveredAt() == null
            && entity.getDeliveryDroppedAt() == null);
        node.put("delivery_attempts", entity.getDeliveryAttempts());
        if (entity.getDeliveryTarget() != null && !entity.getDeliveryTarget().isBlank()) {
            node.put("delivery_target", entity.getDeliveryTarget());
        }
        if (entity.getDeliveryError() != null && !entity.getDeliveryError().isBlank()) {
            node.put("delivery_error", entity.getDeliveryError());
        }
        if (entity.getDeliveryIdempotencyKey() != null && !entity.getDeliveryIdempotencyKey().isBlank()) {
            node.put("delivery_idempotency_key", entity.getDeliveryIdempotencyKey());
        }
        if (entity.getError() != null && !entity.getError().isBlank()) {
            node.put("error", entity.getError());
        }
        if (includeResult && entity.getResultJson() != null && !entity.getResultJson().isBlank()) {
            node.put("result_json", entity.getResultJson());
            node.set("result", parseResultJson(entity.getResultJson()));
        }
        return node;
    }

    private JsonNode parseResultJson(String resultJson) {
        try {
            return MAPPER.readTree(resultJson);
        } catch (Exception e) {
            return MAPPER.getNodeFactory().textNode(resultJson);
        }
    }

    private void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            node.put(field, instant.toString());
        }
    }

    private UUID parseRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id is required for this delegate_task action.");
        }
        String normalized = runId.trim();
        if (normalized.startsWith("deleg_")) {
            normalized = normalized.substring("deleg_".length());
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("run_id must be a UUID or delegation_id of the form deleg_<uuid>.");
        }
    }

    private void markDelegatedRunStarted(UUID delegatedRunId, UUID childSessionId) {
        if (delegatedRunId == null) {
            return;
        }
        DelegatedTaskRunService runService = delegatedTaskRunService();
        if (runService != null) {
            runService.markStarted(delegatedRunId, childSessionId);
        }
    }

    private boolean isAsyncCancelRequested(UUID delegatedRunId) {
        if (delegatedRunId == null) {
            return false;
        }
        DelegatedTaskRunService runService = delegatedTaskRunService();
        return runService != null && runService.isCancelRequested(delegatedRunId);
    }

    private DelegatedTaskRunService delegatedTaskRunService() {
        return delegatedTaskRunServiceProvider != null
            ? delegatedTaskRunServiceProvider.getIfAvailable()
            : null;
    }

    private boolean asyncRequested(DelegateArgs args) {
        return Boolean.TRUE.equals(args.background())
            || Boolean.TRUE.equals(args.async())
            || Boolean.TRUE.equals(args.live());
    }

    private String resolveProfile(Session session) {
        String profile = session.getMetadata("profile");
        if (profile == null || profile.isBlank()) {
            profile = session.getMetadata("hermes_profile");
        }
        return profile == null || profile.isBlank() ? "default" : profile.trim();
    }

    private String summarizeDelegationGoal(List<TaskSpec> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "Delegated task";
        }
        if (tasks.size() == 1) {
            return tasks.get(0).goal();
        }
        String first = tasks.get(0).goal() == null ? "task" : truncate(tasks.get(0).goal(), 80);
        return "Batch delegation (" + tasks.size() + " tasks): " + first;
    }

    private String aggregateStatus(List<TaskResult> results) {
        if (results == null || results.isEmpty()) {
            return DelegatedTaskRunService.STATUS_FAILED;
        }
        if (results.stream().anyMatch(r -> DelegatedTaskRunService.STATUS_TIMEOUT.equals(r.status()))) {
            return DelegatedTaskRunService.STATUS_TIMEOUT;
        }
        if (results.stream().anyMatch(r -> DelegatedTaskRunService.STATUS_INTERRUPTED.equals(r.status()))) {
            return DelegatedTaskRunService.STATUS_INTERRUPTED;
        }
        if (results.stream().anyMatch(r -> DelegatedTaskRunService.STATUS_ERROR.equals(r.status()))) {
            return DelegatedTaskRunService.STATUS_ERROR;
        }
        if (results.stream().anyMatch(r -> DelegatedTaskRunService.STATUS_FAILED.equals(r.status()))) {
            return DelegatedTaskRunService.STATUS_FAILED;
        }
        return DelegatedTaskRunService.STATUS_COMPLETED;
    }

    private String firstError(List<TaskResult> results) {
        if (results == null) {
            return null;
        }
        return results.stream()
            .map(TaskResult::error)
            .filter(error -> error != null && !error.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String delegationId(UUID runId) {
        return "deleg_" + runId;
    }

    private String deliveryState(DelegatedTaskRunEntity entity) {
        if (entity.getDeliveredAt() != null) {
            return "delivered";
        }
        if (entity.getDeliveryDroppedAt() != null) {
            return "dropped";
        }
        if (entity.getCompletedAt() == null) {
            return "not_ready";
        }
        if (entity.getDeliveryClaim() != null && !entity.getDeliveryClaim().isBlank()) {
            return "claimed";
        }
        return "pending";
    }

    /** Validate that the provided object is a syntactically valid JSON Schema object.
     *  Returns null when valid, or an error message string. */
    static String validateOutputSchemaDefinition(java.util.Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return null;
        Object type = schema.get("type");
        if (type == null) {
            return "JSON Schema must have a top-level 'type' property.";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writeValueAsString(schema); // round-trip serialisation check
        } catch (Exception e) {
            return "JSON Schema is not serialisable: " + e.getMessage();
        }
        return null;
    }

    /** Build the natural-language output contract appended to the child system prompt. */
    static String buildOutputSchemaContract(java.util.Map<String, Object> schema) {
        try {
            String schemaJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(schema);
            return "## Output Contract\n\n"
                + "Your final answer MUST be valid JSON that conforms to this JSON Schema:\n\n"
                + "```json\n" + schemaJson + "\n```\n\n"
                + "Do not include any text outside the JSON object.";
        } catch (Exception e) {
            return "## Output Contract\n\nProvide your answer as valid JSON.";
        }
    }

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
        @ToolParam(description = "toolsets to enable for child sub-agents. When omitted, inherits parent's enabled toolsets. When specified, intersected with parent's.",
            required = false) List<String> toolsets,
        @ToolParam(description = "role: 'leaf' (default, cannot delegate) or 'orchestrator' (can spawn own subagents)",
            required = false) String role,
        @ToolParam(description = "timeout in seconds for each child (0 = use config default)",
            required = false) @JsonProperty("timeout_seconds") @JsonAlias("timeoutSeconds") int timeoutSeconds,
        @ToolParam(description = "batch mode: JSON array of {goal, context, toolsets, role, timeoutSeconds, acpCommand, acpArgs} objects",
            required = false) List<TaskSpec> tasks,
        @ToolParam(description = "maximum iterations (model calls) per child subagent (0 = use config default delegation.max_iterations)",
            required = false) @JsonProperty("max_iterations") @JsonAlias("maxIterations") Integer maxIterations,
        @ToolParam(description = "Override ACP command for child agents (e.g. 'copilot'). When set, children use ACP subprocess transport instead of inheriting the parent's transport. Requires an ACP-compatible CLI. Do NOT set unless the user explicitly told you an ACP CLI is installed.",
            required = false) @JsonProperty("acp_command") @JsonAlias("acpCommand") String acpCommand,
        @ToolParam(description = "Arguments for the ACP command (default: ['--acp', '--stdio']). Only used when acpCommand is set.",
            required = false) @JsonProperty("acp_args") @JsonAlias("acpArgs") List<String> acpArgs,
        @ToolParam(description = "Default 'spawn' (omit for normal delegation). Use 'create' for durable async/background dispatch. Control actions: 'list' shows live children and recent durable runs; 'status'/'read' inspect a durable run by run_id; 'cancel' requests cancellation by run_id; 'steer' queues course-correction text into one live child (requires subagent_id + message); 'stop' ends one live child early (requires subagent_id).",
            required = false) String action,
        @ToolParam(description = "Target for action='steer'/'stop'. Ids are returned in the spawn dispatch response (subagent_ids) and by action='list'.",
            required = false) @JsonProperty("subagent_id") @JsonAlias("subagentId") String subagentId,
        @ToolParam(description = "Target durable async run for action='status', action='read', or action='cancel'. Accepts either run_id UUID or Hermes-style delegation_id (deleg_<uuid>).",
            required = false) @JsonProperty("run_id") @JsonAlias({"runId", "delegation_id", "delegationId"}) String runId,
        @ToolParam(description = "For action='steer': the course correction. Be directive and specific — the child sees it appended to its next tool result mid-run.",
            required = false) String message,
        @ToolParam(description = "Optional JSON Schema the child's final answer must validate against. When set, the child is told the contract and its output is validated; a malformed result triggers one bounded correction retry.",
            required = false) @JsonProperty("output_schema") @JsonAlias("outputSchema") java.util.Map<String, Object> outputSchema,
        @ToolParam(description = "Hermes background mode. When true, dispatch returns immediately with a durable run_id/delegation_id; inspect completion with action='status' or action='read'.",
            required = false) Boolean background,
        @ToolParam(description = "Alias for background mode.",
            required = false) @JsonProperty("async") @JsonAlias("asyncMode") Boolean async,
        @ToolParam(description = "Alias for Hermes live/background delegation mode. This Java parity layer records durable status/read/cancel, while full gateway reinjection remains a separate runtime feature.",
            required = false) Boolean live
    ) {}

    /**
     * A single task specification within a batch.
     */
    public record TaskSpec(
        @ToolParam(description = "task goal for this sub-agent", required = true) String goal,
        @ToolParam(description = "optional context for this sub-agent", required = false) String context,
        @ToolParam(description = "optional toolsets to enable for this child (overrides top-level toolsets). When omitted, inherits parent's or top-level toolsets.",
            required = false) List<String> toolsets,
        @ToolParam(description = "role: 'leaf' or 'orchestrator'", required = false) String role,
        @ToolParam(description = "timeout in seconds (0 = use config default)", required = false) @JsonProperty("timeout_seconds") @JsonAlias("timeoutSeconds") int timeoutSeconds,
        @ToolParam(description = "Per-task ACP command override (e.g. 'copilot'). Overrides the top-level acpCommand for this task only.",
            required = false) @JsonProperty("acp_command") @JsonAlias("acpCommand") String acpCommand,
        @ToolParam(description = "Per-task ACP args override. Leave empty unless acpCommand is set.",
            required = false) @JsonProperty("acp_args") @JsonAlias("acpArgs") List<String> acpArgs,
        @ToolParam(description = "Optional JSON Schema the child's final answer must validate against. Overrides top-level output_schema for this task.",
            required = false) @JsonProperty("output_schema") @JsonAlias("outputSchema") java.util.Map<String, Object> outputSchema
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

        static TaskResult interrupted(int index, String goal, String errorMsg) {
            return new TaskResult(index, "interrupted", null, errorMsg, 0, "leaf");
        }
    }

    /**
     * Registry record for an active subagent (observability).
     */
    public record SubagentRecord(
        String subagentId,
        int depth,
        String goal,
        long startedAtMs,
        String parentSessionId,
        UUID childSessionId
    ) {}
}
