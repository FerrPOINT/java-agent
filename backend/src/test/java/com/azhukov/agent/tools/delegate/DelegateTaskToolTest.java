package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.persistence.entity.DelegatedTaskRunEntity;
import com.azhukov.agent.service.DelegatedTaskRunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DelegateTaskTool}.
 * Covers execute() with single goal, batch tasks, invalid args, role='leaf',
 * disabled delegation, depth limit, and orchestrator role handling.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DelegateTaskToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private AgentRuntime agentRuntime;

    @Mock
    private ObjectProvider<AgentRuntime> runtimeProvider;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private ObjectProvider<ToolRegistry> registryProvider;

    @Mock
    private ObjectProvider<InterruptToken> interruptTokenProvider;

    @Mock
    private ObjectProvider<SteerBuffer> steerBufferProvider;

    @Mock
    private ObjectProvider<DelegatedTaskRunService> delegatedRunServiceProvider;

    @Mock
    private DelegatedTaskRunService delegatedRunService;

    private AgentProperties properties() {
        AgentProperties p = new AgentProperties();
        p.getDelegation().setEnabled(true);
        p.getDelegation().setMaxSpawnDepth(3);
        p.getDelegation().setMaxConcurrentChildren(5);
        p.getDelegation().setDefaultTimeoutSeconds(60);
        p.getDelegation().setChildTimeoutSeconds(0);
        p.getDelegation().setMaxIterations(10);
        p.getDelegation().setOrchestratorEnabled(true);
        p.getSkills().setDefaultToolsets(List.of("web", "file", "terminal", "delegation", "memory"));
        return p;
    }

    private DelegateTaskTool newTool(AgentProperties p) {
        // Use the test-friendly constructor with explicit concurrency limit
        DelegateTaskTool tool = new DelegateTaskTool(p, runtimeProvider, registryProvider, 5);
        return tool;
    }

    private Session session() {
        return new Session(UUID.randomUUID(), "user", "test", "openai", "gpt-4", null,
            Map.of("delegation_depth", "0"), null);
    }

    private void mockRuntimeReturns(String finalText) {
        lenient().when(runtimeProvider.getObject()).thenReturn(agentRuntime);
        lenient().when(agentRuntime.runTurn(any(Session.class), anyString(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(
                List.of(Message.assistant(finalText, 0)), true, null
            ));
    }

    private void mockRegistryReturns(Set<String> toolsets) {
        lenient().when(registryProvider.getIfAvailable()).thenReturn(toolRegistry);
        lenient().when(toolRegistry.getToolsets()).thenReturn(toolsets);
    }

    private Map<UUID, DelegatedTaskRunEntity> mockDelegatedRunLedger() {
        Map<UUID, DelegatedTaskRunEntity> ledger = new ConcurrentHashMap<>();
        lenient().when(delegatedRunServiceProvider.getIfAvailable()).thenReturn(delegatedRunService);
        lenient().when(delegatedRunService.activeCountForParent(any(UUID.class))).thenAnswer(invocation -> {
            UUID parentSessionId = invocation.getArgument(0);
            return ledger.values().stream()
                .filter(entity -> parentSessionId.equals(entity.getParentSessionId()))
                .filter(entity -> "running".equals(entity.getStatus())
                    || "cancel_requested".equals(entity.getStatus()))
                .count();
        });
        lenient().when(delegatedRunService.create(any(UUID.class), anyString(), anyString())).thenAnswer(invocation -> {
            DelegatedTaskRunEntity entity = new DelegatedTaskRunEntity();
            entity.setId(UUID.randomUUID());
            entity.setParentSessionId(invocation.getArgument(0));
            entity.setProfile(invocation.getArgument(1));
            entity.setGoal(invocation.getArgument(2));
            entity.setStatus("running");
            entity.setCreatedAt(Instant.now());
            ledger.put(entity.getId(), entity);
            return entity;
        });
        lenient().when(delegatedRunService.createIfCapacity(any(UUID.class), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            UUID parentSessionId = invocation.getArgument(0);
            String profile = invocation.getArgument(1);
            String goal = invocation.getArgument(2);
            int capacity = invocation.getArgument(3);
            long active = ledger.values().stream()
                .filter(entity -> parentSessionId.equals(entity.getParentSessionId()))
                .filter(entity -> "running".equals(entity.getStatus())
                    || "cancel_requested".equals(entity.getStatus()))
                .count();
            if (active >= capacity) {
                return new DelegatedTaskRunService.CreateAttempt(false, null, active, capacity);
            }
            DelegatedTaskRunEntity entity = new DelegatedTaskRunEntity();
            entity.setId(UUID.randomUUID());
            entity.setParentSessionId(parentSessionId);
            entity.setProfile(profile);
            entity.setGoal(goal);
            entity.setStatus("running");
            entity.setCreatedAt(Instant.now());
            ledger.put(entity.getId(), entity);
            return new DelegatedTaskRunService.CreateAttempt(true, entity, active, capacity);
        });
        lenient().when(delegatedRunService.markStarted(any(UUID.class), any(UUID.class))).thenAnswer(invocation -> {
            DelegatedTaskRunEntity entity = ledger.get(invocation.getArgument(0));
            if (entity != null) {
                if (entity.getChildSessionId() == null) {
                    entity.setChildSessionId(invocation.getArgument(1));
                }
                if (entity.getStartedAt() == null) {
                    entity.setStartedAt(Instant.now());
                }
            }
            return entity;
        });
        lenient().when(delegatedRunService.finish(any(UUID.class), anyString(), any(), any())).thenAnswer(invocation -> {
            DelegatedTaskRunEntity entity = ledger.get(invocation.getArgument(0));
            if (entity != null) {
                entity.setStatus(invocation.getArgument(1));
                Object result = invocation.getArgument(2);
                entity.setResultJson(result == null ? null : result.toString());
                entity.setError(invocation.getArgument(3));
                entity.setCompletedAt(Instant.now());
            }
            return entity;
        });
        lenient().when(delegatedRunService.fail(any(UUID.class), anyString())).thenAnswer(invocation -> {
            DelegatedTaskRunEntity entity = ledger.get(invocation.getArgument(0));
            if (entity != null) {
                entity.setStatus("error");
                entity.setError(invocation.getArgument(1));
                entity.setCompletedAt(Instant.now());
            }
            return entity;
        });
        lenient().when(delegatedRunService.isCancelRequested(any(UUID.class))).thenAnswer(invocation -> {
            DelegatedTaskRunEntity entity = ledger.get(invocation.getArgument(0));
            return entity != null && "cancel_requested".equals(entity.getStatus());
        });
        lenient().when(delegatedRunService.findForParent(any(UUID.class), any(UUID.class))).thenAnswer(invocation -> {
            DelegatedTaskRunEntity entity = ledger.get(invocation.getArgument(0));
            UUID parentSessionId = invocation.getArgument(1);
            return entity != null && parentSessionId.equals(entity.getParentSessionId())
                ? Optional.of(entity)
                : Optional.empty();
        });
        lenient().when(delegatedRunService.listForParent(any(UUID.class), anyInt())).thenAnswer(invocation -> {
            UUID parentSessionId = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return ledger.values().stream()
                .filter(entity -> parentSessionId.equals(entity.getParentSessionId()))
                .limit(limit)
                .toList();
        });
        lenient().when(delegatedRunService.requestCancel(any(UUID.class), any(UUID.class))).thenAnswer(invocation -> {
            DelegatedTaskRunEntity entity = ledger.get(invocation.getArgument(0));
            UUID parentSessionId = invocation.getArgument(1);
            if (entity == null || !parentSessionId.equals(entity.getParentSessionId())) {
                throw new IllegalArgumentException("Delegated run was not found for this session.");
            }
            entity.setStatus("cancel_requested");
            entity.setCancelRequestedAt(Instant.now());
            return entity;
        });
        return ledger;
    }

    private JsonNode jsonError(ToolResult result) throws Exception {
        assertThat(result.success()).isFalse();
        assertThat(result.content()).isNotBlank();
        JsonNode root = mapper.readTree(result.content());
        assertThat(root.get("success").asBoolean()).isFalse();
        assertThat(root.get("error").asText()).isNotBlank();
        assertThat(result.error()).isEqualTo(root.get("error").asText());
        return root;
    }

    // ── 1. Single goal — completes successfully ───────────────────────────

    @Test
    void executeWithSingleGoalReturnsCompletedResult() throws Exception {
        AgentProperties p = properties();
        mockRuntimeReturns("Task done: analyzed data");
        mockRegistryReturns(Set.of("web", "file", "terminal"));

        DelegateTaskTool tool = newTool(p);
        // Disable timeout so the CompletableFuture.get doesn't time out
        p.getDelegation().setChildTimeoutSeconds(0);
        p.getDelegation().setDefaultTimeoutSeconds(0);

        ToolResult result = tool.execute(
            "{\"goal\":\"Analyze the data\",\"timeoutSeconds\":0}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode root = mapper.readTree(result.content());
        assertThat(root.has("results")).isTrue();
        JsonNode results = root.get("results");
        assertThat(results.isArray()).isTrue();
        assertThat(results.size()).isEqualTo(1);
        assertThat(results.get(0).get("status").asText()).isEqualTo("completed");
        assertThat(results.get(0).get("summary").asText()).contains("Task done");
    }

    // ── 2. Batch tasks — runs each in parallel ────────────────────────────

    @Test
    void executeWithBatchTasksReturnsAllResults() throws Exception {
        AgentProperties p = properties();
        mockRuntimeReturns("Completed subtask");
        mockRegistryReturns(Set.of("web", "file"));

        DelegateTaskTool tool = newTool(p);
        p.getDelegation().setChildTimeoutSeconds(0);
        p.getDelegation().setDefaultTimeoutSeconds(0);

        String args = """
            {"tasks":[
              {"goal":"Task A","timeoutSeconds":0},
              {"goal":"Task B","timeoutSeconds":0}
            ]}""";

        ToolResult result = tool.execute(args, null, session());

        assertThat(result.success()).isTrue();
        JsonNode root = mapper.readTree(result.content());
        JsonNode results = root.get("results");
        assertThat(results.size()).isEqualTo(2);
        // Both should complete
        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).get("status").asText()).isEqualTo("completed");
        }
    }

    // ── 3. Invalid args — no goal and no tasks ────────────────────────────

    @Test
    void executeWithNoGoalAndNoTasksFails() throws Exception {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute("{}", null, session());

        JsonNode root = jsonError(result);
        assertThat(root.get("error").asText()).contains("No tasks provided");
    }

    // ── 4. Invalid args — task missing goal ───────────────────────────────

    @Test
    void executeWithTaskMissingGoalFails() throws Exception {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        String args = """
            {"tasks":[
              {"goal":"Valid task"},
              {"context":"no goal here"}
            ]}""";

        ToolResult result = tool.execute(args, null, session());

        JsonNode root = jsonError(result);
        assertThat(root.get("error").asText()).contains("Task 1 is missing a 'goal'");
    }

    // ── 5. Delegation disabled — returns fail ─────────────────────────────

    @Test
    void executeFailsWhenDelegationDisabled() throws Exception {
        AgentProperties p = properties();
        p.getDelegation().setEnabled(false);
        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute("{\"goal\":\"test\"}", null, session());

        JsonNode root = jsonError(result);
        assertThat(root.get("error").asText()).contains("Delegation is disabled");
    }

    // ── 6. Depth limit reached — returns fail ─────────────────────────────

    @Test
    void executeFailsWhenDepthLimitReached() throws Exception {
        AgentProperties p = properties();
        p.getDelegation().setMaxSpawnDepth(1);
        DelegateTaskTool tool = newTool(p);

        // Session at depth 0, maxSpawnDepth=1 → depth 0 < 1 is allowed, but
        // let's set the session to depth 1 which equals maxSpawnDepth
        Session deepSession = new Session(UUID.randomUUID(), "user", "test", "openai", "gpt-4", null,
            Map.of("delegation_depth", "1"), null);

        ToolResult result = tool.execute("{\"goal\":\"deep task\"}", null, deepSession);

        JsonNode root = jsonError(result);
        assertThat(root.get("error").asText()).contains("Delegation depth limit reached");
    }

    // ── 7. role='leaf' — default, cannot delegate ─────────────────────────

    @Test
    void executeWithLeafRoleProducesLeafChild() throws Exception {
        AgentProperties p = properties();
        mockRuntimeReturns("Leaf task complete");
        mockRegistryReturns(Set.of("web", "file", "delegation", "memory"));
        p.getDelegation().setChildTimeoutSeconds(0);
        p.getDelegation().setDefaultTimeoutSeconds(0);

        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"goal\":\"do leaf work\",\"role\":\"leaf\",\"timeoutSeconds\":0}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode root = mapper.readTree(result.content());
        JsonNode results = root.get("results");
        // The child's role should be "leaf"
        assertThat(results.get(0).get("role").asText()).isEqualTo("leaf");
        assertThat(results.get(0).get("status").asText()).isEqualTo("completed");
    }

    // ── 8. Too many tasks — exceeds maxConcurrentChildren ─────────────────

    @Test
    void executeFailsWhenTooManyTasks() throws Exception {
        AgentProperties p = properties();
        p.getDelegation().setMaxConcurrentChildren(2);
        DelegateTaskTool tool = new DelegateTaskTool(p, runtimeProvider, registryProvider, 2);

        String args = """
            {"tasks":[
              {"goal":"A"},
              {"goal":"B"},
              {"goal":"C"}
            ]}""";

        ToolResult result = tool.execute(args, null, session());

        JsonNode root = jsonError(result);
        assertThat(root.get("error").asText()).contains("Too many tasks");
        assertThat(root.get("error").asText()).contains("max_concurrent_children is 2");
    }

    @Test
    void executeWithInvalidJsonReturnsStructuredError() throws Exception {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute("not-json", null, session());

        JsonNode root = jsonError(result);
        assertThat(root.get("error").asText()).contains("Invalid tool arguments");
    }

    @Test
    void executeWithInvalidOutputSchemaFailsBeforeSpawningChild() throws Exception {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute("""
            {"goal":"Return structured result","output_schema":{"properties":{"value":{"type":"string"}}}}""",
            null, session());

        JsonNode root = jsonError(result);
        assertThat(root.get("error").asText()).contains("Task 0 output_schema invalid");
        assertThat(root.get("error").asText()).contains("top-level 'type'");
    }

    @Test
    void controlListReturnsStructuredJsonWhenNoChildrenAreActive() throws Exception {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute("{\"action\":\"list\"}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode root = mapper.readTree(result.content());
        assertThat(root.get("action").asText()).isEqualTo("list");
        assertThat(root.get("count").asInt()).isZero();
        assertThat(root.get("subagents").size()).isZero();
        assertThat(root.get("note").asText()).contains("No live subagents");
    }

    @Test
    void controlListReturnsStructuredJsonForActiveChildren() throws Exception {
        AgentProperties p = properties();
        p.getDelegation().setChildTimeoutSeconds(0);
        p.getDelegation().setDefaultTimeoutSeconds(0);
        mockRegistryReturns(Set.of("web", "file"));
        Session parent = session();
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        lenient().when(runtimeProvider.getObject()).thenReturn(agentRuntime);
        lenient().when(agentRuntime.runTurn(any(Session.class), anyString(), anyList(), any(ModelRequestOptions.class)))
            .thenAnswer(invocation -> {
                childStarted.countDown();
                assertThat(releaseChild.await(5, TimeUnit.SECONDS)).isTrue();
                return new TurnResult(List.of(Message.assistant("Child done", 0)), true, null);
            });

        InterruptToken interruptToken = new InterruptToken();
        SteerBuffer steerBuffer = new SteerBuffer();
        lenient().when(interruptTokenProvider.getIfAvailable()).thenReturn(interruptToken);
        lenient().when(steerBufferProvider.getIfAvailable()).thenReturn(steerBuffer);
        DelegateTaskTool tool = new DelegateTaskTool(
            p, runtimeProvider, registryProvider, interruptTokenProvider, steerBufferProvider);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ToolResult> childRun = executor.submit(() ->
            tool.execute("{\"goal\":\"Long enough child task\",\"timeoutSeconds\":0}", null, parent));
        try {
            assertThat(childStarted.await(5, TimeUnit.SECONDS)).isTrue();

            ToolResult result = tool.execute("{\"action\":\"list\"}", null, parent);

            assertThat(result.success()).isTrue();
            JsonNode root = mapper.readTree(result.content());
            assertThat(root.get("action").asText()).isEqualTo("list");
            assertThat(root.get("count").asInt()).isEqualTo(1);
            JsonNode child = root.get("subagents").get(0);
            assertThat(child.get("subagent_id").asText()).startsWith("sa-0-");
            assertThat(child.get("goal").asText()).isEqualTo("Long enough child task");
            assertThat(child.get("status").asText()).isEqualTo("running");
            assertThat(child.get("parent_session_id").asText()).isEqualTo(parent.id().toString());
            assertThat(child.get("child_session_id").asText()).isNotBlank();
            String subagentId = child.get("subagent_id").asText();
            UUID childSessionId = UUID.fromString(child.get("child_session_id").asText());

            ToolResult steerResult = tool.execute(
                "{\"action\":\"steer\",\"subagent_id\":\"" + subagentId + "\",\"message\":\"stay focused\"}",
                null, parent);
            JsonNode steer = mapper.readTree(steerResult.content());
            assertThat(steerResult.success()).isTrue();
            assertThat(steer.get("action").asText()).isEqualTo("steer");
            assertThat(steer.get("status").asText()).isEqualTo("queued");
            assertThat(steerBuffer.consume(childSessionId)).isEqualTo("stay focused");

            ToolResult stopResult = tool.execute(
                "{\"action\":\"stop\",\"subagent_id\":\"" + subagentId + "\"}", null, parent);
            JsonNode stop = mapper.readTree(stopResult.content());
            assertThat(stopResult.success()).isTrue();
            assertThat(stop.get("action").asText()).isEqualTo("stop");
            assertThat(stop.get("status").asText()).isEqualTo("interrupt_requested");
            assertThat(interruptToken.isCancelled(childSessionId)).isTrue();
        } finally {
            releaseChild.countDown();
            assertThat(childRun.get(5, TimeUnit.SECONDS).success()).isTrue();
            executor.shutdownNow();
        }
    }

    @Test
    void controlActionsReturnStructuredErrors() throws Exception {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        JsonNode unknown = jsonError(tool.execute("{\"action\":\"pause\"}", null, session()));
        assertThat(unknown.get("error").asText()).contains("Unknown action");

        JsonNode stop = jsonError(tool.execute("{\"action\":\"stop\"}", null, session()));
        assertThat(stop.get("error").asText()).contains("requires subagent_id");

        JsonNode steer = jsonError(tool.execute("{\"action\":\"steer\",\"subagent_id\":\"sa-1\"}", null, session()));
        assertThat(steer.get("error").asText()).contains("non-empty 'message'");
    }

    @Test
    void backgroundDelegationReturnsImmediatelyAndReadExposesCompletion() throws Exception {
        AgentProperties p = properties();
        p.getDelegation().setChildTimeoutSeconds(0);
        p.getDelegation().setDefaultTimeoutSeconds(0);
        mockRegistryReturns(Set.of("web", "file"));
        Map<UUID, DelegatedTaskRunEntity> ledger = mockDelegatedRunLedger();
        Session parent = session();
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch completionSaved = new CountDownLatch(1);
        lenient().when(runtimeProvider.getObject()).thenReturn(agentRuntime);
        lenient().when(agentRuntime.runTurn(any(Session.class), anyString(), anyList(), any(ModelRequestOptions.class)))
            .thenAnswer(invocation -> {
                childStarted.countDown();
                assertThat(releaseChild.await(5, TimeUnit.SECONDS)).isTrue();
                return new TurnResult(List.of(Message.assistant("Async child done", 0)), true, null);
            });
        lenient().when(delegatedRunService.finish(any(UUID.class), anyString(), any(), any())).thenAnswer(invocation -> {
            DelegatedTaskRunEntity entity = ledger.get(invocation.getArgument(0));
            if (entity != null) {
                entity.setStatus(invocation.getArgument(1));
                Object storedResult = invocation.getArgument(2);
                entity.setResultJson(storedResult == null ? null : storedResult.toString());
                entity.setError(invocation.getArgument(3));
                entity.setCompletedAt(Instant.now());
            }
            completionSaved.countDown();
            return entity;
        });
        DelegateTaskTool tool = new DelegateTaskTool(
            p, runtimeProvider, registryProvider, delegatedRunServiceProvider, 3);

        long startedAt = System.nanoTime();
        ToolResult result = tool.execute(
            "{\"goal\":\"slow async child\",\"background\":true,\"timeoutSeconds\":0}",
            null, parent);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(result.success()).isTrue();
        verify(delegatedRunService, never()).activeCountForParent(parent.id());
        assertThat(elapsedMs).isLessThan(1000);
        assertThat(releaseChild.getCount()).isEqualTo(1);
        JsonNode dispatch = mapper.readTree(result.content());
        assertThat(dispatch.get("status").asText()).isEqualTo("dispatched");
        assertThat(dispatch.get("mode").asText()).isEqualTo("background");
        assertThat(dispatch.get("delegation_id").asText()).startsWith("deleg_");
        String runId = dispatch.get("run_id").asText();

        assertThat(childStarted.await(5, TimeUnit.SECONDS)).isTrue();
        ToolResult statusResult = tool.execute(
            "{\"action\":\"status\",\"run_id\":\"" + runId + "\"}", null, parent);
        JsonNode status = mapper.readTree(statusResult.content());
        assertThat(statusResult.success()).isTrue();
        assertThat(status.get("run").get("status").asText()).isEqualTo("running");
        assertThat(status.get("run").get("child_session_id").asText()).isNotBlank();

        releaseChild.countDown();
        assertThat(completionSaved.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ledger.get(UUID.fromString(runId)).getStatus()).isEqualTo("completed");
        ToolResult readResult = tool.execute(
            "{\"action\":\"read\",\"delegation_id\":\"deleg_" + runId + "\"}", null, parent);
        JsonNode read = mapper.readTree(readResult.content());
        assertThat(readResult.success()).isTrue();
        assertThat(read.get("run").get("status").asText()).isEqualTo("completed");
        assertThat(read.get("run").get("delivery_pending").asBoolean()).isTrue();
        assertThat(read.get("run").get("delivery_attempts").asInt()).isEqualTo(0);
        assertThat(read.get("run").get("result").get("results").get(0).get("summary").asText())
            .isEqualTo("Async child done");
    }

    @Test
    void backgroundDelegationRejectsWhenSessionIsAtCapacity() throws Exception {
        AgentProperties p = properties();
        p.getDelegation().setMaxConcurrentChildren(1);
        mockRegistryReturns(Set.of("web", "file"));
        Map<UUID, DelegatedTaskRunEntity> ledger = mockDelegatedRunLedger();
        Session parent = session();
        DelegatedTaskRunEntity running = new DelegatedTaskRunEntity();
        running.setId(UUID.randomUUID());
        running.setParentSessionId(parent.id());
        running.setProfile("default");
        running.setGoal("already running");
        running.setStatus("running");
        running.setCreatedAt(Instant.now());
        ledger.put(running.getId(), running);
        DelegateTaskTool tool = new DelegateTaskTool(
            p, runtimeProvider, registryProvider, delegatedRunServiceProvider, 1);

        ToolResult result = tool.execute(
            "{\"goal\":\"second async child\",\"background\":true}", null, parent);

        assertThat(result.success()).isFalse();
        JsonNode root = mapper.readTree(result.content());
        assertThat(root.get("status").asText()).isEqualTo("rejected");
        assertThat(root.get("error").asText()).contains("capacity");
    }

    @Test
    void cancelDelegatedRunMarksLedgerAndSignalsChildSession() throws Exception {
        AgentProperties p = properties();
        Map<UUID, DelegatedTaskRunEntity> ledger = mockDelegatedRunLedger();
        Session parent = session();
        UUID runId = UUID.randomUUID();
        UUID childSessionId = UUID.randomUUID();
        DelegatedTaskRunEntity running = new DelegatedTaskRunEntity();
        running.setId(runId);
        running.setParentSessionId(parent.id());
        running.setChildSessionId(childSessionId);
        running.setProfile("default");
        running.setGoal("running async child");
        running.setStatus("running");
        running.setCreatedAt(Instant.now());
        ledger.put(runId, running);
        InterruptToken interruptToken = new InterruptToken();
        lenient().when(interruptTokenProvider.getIfAvailable()).thenReturn(interruptToken);
        DelegateTaskTool tool = new DelegateTaskTool(
            p, runtimeProvider, registryProvider, interruptTokenProvider,
            steerBufferProvider, delegatedRunServiceProvider);

        ToolResult result = tool.execute(
            "{\"action\":\"cancel\",\"run_id\":\"" + runId + "\"}", null, parent);

        assertThat(result.success()).isTrue();
        JsonNode root = mapper.readTree(result.content());
        assertThat(root.get("status").asText()).isEqualTo("cancel_requested");
        assertThat(root.get("run").get("status").asText()).isEqualTo("cancel_requested");
        assertThat(root.get("run").get("delivery_pending").asBoolean()).isFalse();
        assertThat(interruptToken.isCancelled(childSessionId)).isTrue();
    }

    // ── 9. resolveChildToolsets — inheritance strips blocked toolsets ─────

    @Test
    void resolveChildToolsetsInheritsAndStripsBlocked() {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        Set<String> parentToolsets = new java.util.LinkedHashSet<>(List.of("web", "file", "delegation", "memory", "gateway", "clarify", "cronjob"));

        List<String> child = tool.resolveChildToolsets(null, parentToolsets, "leaf");

        // Blocked one-tool/toolset surfaces should be stripped.
        assertThat(child).contains("web", "file");
        assertThat(child).doesNotContain("delegation", "memory", "gateway", "clarify", "cronjob");
    }

    // ── 10. resolveChildToolsets — orchestrator re-adds delegation ────────

    @Test
    void resolveChildToolsetsOrchestratorReaddsDelegation() {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        Set<String> parentToolsets = new java.util.LinkedHashSet<>(List.of("web", "file", "delegation"));

        List<String> child = tool.resolveChildToolsets(null, parentToolsets, "orchestrator");

        assertThat(child).contains("web", "file", "delegation");
    }

    @Test
    void resolveParentToolsetsUsesSessionMetadataBeforeRegistry() {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);
        mockRegistryReturns(Set.of("web", "file", "terminal", "gateway"));

        Session parent = new Session(UUID.randomUUID(), "user", "test", "openai", "gpt-4", null,
            Map.of("delegation_depth", "1", DelegateTaskTool.META_TOOLSETS, "web, terminal"), null);

        assertThat(tool.resolveParentToolsets(parent)).containsExactly("web", "terminal");
    }

    @Test
    void resolveChildToolsetsExpandsCompositeParentWithoutGrantingBlockedToolsets() {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);
        lenient().when(registryProvider.getIfAvailable()).thenReturn(toolRegistry);
        lenient().when(toolRegistry.getDefinitions(Set.of("hermes-cli"))).thenReturn(List.of(
            new ToolDefinition("web_search", "", Map.of()),
            new ToolDefinition("read_file", "", Map.of()),
            new ToolDefinition("write_file", "", Map.of()),
            new ToolDefinition("patch", "", Map.of()),
            new ToolDefinition("search_files", "", Map.of())
        ));
        lenient().when(toolRegistry.getToolsets()).thenReturn(Set.of("file", "gateway", "memory"));
        lenient().when(toolRegistry.getDefinitions(Set.of("file"))).thenReturn(List.of(
            new ToolDefinition("read_file", "", Map.of()),
            new ToolDefinition("write_file", "", Map.of()),
            new ToolDefinition("patch", "", Map.of()),
            new ToolDefinition("search_files", "", Map.of()),
            new ToolDefinition("delete_file", "", Map.of())
        ));
        lenient().when(toolRegistry.getDefinitions(Set.of("gateway")))
            .thenReturn(List.of(new ToolDefinition("send_message", "", Map.of())));
        lenient().when(toolRegistry.getDefinitions(Set.of("memory")))
            .thenReturn(List.of(new ToolDefinition("memory", "", Map.of())));

        List<String> child = tool.resolveChildToolsets(
            List.of("file", "gateway", "memory"),
            new java.util.LinkedHashSet<>(List.of("hermes-cli")),
            "leaf");

        assertThat(child).containsExactly("file");
    }

    @Test
    void resolveChildDisabledToolsMatchesHermesAndKeepsDelegationForOrchestrators() {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        assertThat(tool.resolveChildDisabledTools("leaf"))
            .contains("delegate_task", "clarify", "memory", "send_message", "cronjob", "delete_file")
            .doesNotContain("execute_code");
        assertThat(tool.resolveChildDisabledTools("orchestrator"))
            .doesNotContain("delegate_task")
            .contains("clarify", "memory", "send_message", "cronjob", "delete_file");
    }

    @Test
    void resolveMaxIterationsIgnoresCallerOverrideLikeHermes() {
        AgentProperties p = properties();
        p.getDelegation().setMaxIterations(10);
        DelegateTaskTool tool = newTool(p);

        assertThat(tool.resolveMaxIterations(p.getDelegation(), 1)).isEqualTo(10);
        assertThat(tool.resolveMaxIterations(p.getDelegation(), 500)).isEqualTo(10);
    }

    @Test
    void resolveChildTimeoutCanDisableHardCapWhenConfigIsZero() {
        AgentProperties p = properties();
        p.getDelegation().setChildTimeoutSeconds(0);
        p.getDelegation().setDefaultTimeoutSeconds(0);
        DelegateTaskTool tool = newTool(p);

        assertThat(tool.resolveChildTimeout(p.getDelegation(), 0)).isZero();
    }

    @Test
    void resolveChildTimeoutFloorsExplicitHardCapsAtThirtySeconds() {
        AgentProperties p = properties();
        p.getDelegation().setChildTimeoutSeconds(5);
        p.getDelegation().setDefaultTimeoutSeconds(0);
        DelegateTaskTool tool = newTool(p);

        assertThat(tool.resolveChildTimeout(p.getDelegation(), 0)).isEqualTo(30);
        assertThat(tool.resolveChildTimeout(p.getDelegation(), 7)).isEqualTo(30);
    }

    // ── 11. normalizeRole — unknown role coerces to leaf ──────────────────

    @Test
    void normalizeRoleCoercesUnknownToLeaf() {
        assertThat(DelegateTaskTool.normalizeRole(null)).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("")).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("invalid")).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("LEAF")).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("orchestrator")).isEqualTo("orchestrator");
    }
}
