package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DelegateTaskToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wraps a mock AgentRuntime in an ObjectProvider for test-friendly constructor. */
    private static ObjectProvider<AgentRuntime> runtimeProvider(AgentRuntime runtime) {
        return new ObjectProvider<>() {
            @Override public AgentRuntime getObject() { return runtime; }
            @Override public AgentRuntime getObject(Object... args) { return runtime; }
            @Override public AgentRuntime getIfAvailable() { return runtime; }
            @Override public AgentRuntime getIfUnique() { return runtime; }
        };
    }

    /** Wraps a mock ToolRegistry in an ObjectProvider for test-friendly constructor. */
    private static ObjectProvider<ToolRegistry> toolRegistryProvider(ToolRegistry registry) {
        return new ObjectProvider<>() {
            @Override public ToolRegistry getObject() { return registry; }
            @Override public ToolRegistry getObject(Object... args) { return registry; }
            @Override public ToolRegistry getIfAvailable() { return registry; }
            @Override public ToolRegistry getIfUnique() { return registry; }
        };
    }

    /** Creates a mock ToolRegistry with the given toolsets. */
    private static ObjectProvider<ToolRegistry> toolRegistry(Set<String> toolsets) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(toolsets);
        when(registry.getDefinitions(any())).thenReturn(List.of());
        return toolRegistryProvider(registry);
    }

    /** Creates a mock ToolRegistry with the given toolsets and definitions. */
    private static ObjectProvider<ToolRegistry> toolRegistry(Set<String> toolsets, List<ToolDefinition> defs) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(toolsets);
        when(registry.getDefinitions(any())).thenReturn(defs);
        return toolRegistryProvider(registry);
    }

    private static final Set<String> DEFAULT_PARENT_TOOLSETS = Set.of(
        "web", "file", "terminal", "coding", "core", "delegation", "memory", "gateway", "todo", "browser"
    );

    private AgentProperties defaultProperties() {
        AgentProperties props = new AgentProperties();
        // Use defaults: maxDepth=3, maxSpawnDepth=1, maxConcurrentChildren=3
        return props;
    }

    private TurnResult completedResult(String text) {
        return new TurnResult(
            List.of(Message.user("task"), Message.assistant(text, 1)),
            true,
            null
        );
    }

    private TurnResult errorResult(String error) {
        return new TurnResult(List.of(), false, error);
    }

    private Session defaultSession() {
        return Session.create("user1", "openai", "gpt-4");
    }

    private Session sessionWithDepth(int depth) {
        return new Session(java.util.UUID.randomUUID(), "user1", null, "openai", "gpt-4",
            null, Map.of("delegation_depth", String.valueOf(depth)), null);
    }

    // ── Single task mode ────────────────────────────────────────────────

    @Test
    void returnsResultFromSingleChildAgent() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenReturn(completedResult("Task completed successfully"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        ToolResult result = tool.execute("{\"goal\":\"analyze logs\"}", null, session);

        assertThat(result.success()).isTrue();
        JsonNode node = MAPPER.readTree(result.content());
        assertThat(node.get("results").size()).isEqualTo(1);
        assertThat(node.get("results").get(0).get("status").asText()).isEqualTo("completed");
        assertThat(node.get("results").get(0).get("summary").asText()).isEqualTo("Task completed successfully");
    }

    @Test
    void failsWhenGoalMissing() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        ToolResult result = tool.execute("{}", null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Provide either 'goal'");
    }

    @Test
    void failsWhenMaxSpawnDepthReached() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = sessionWithDepth(3);

        ToolResult result = tool.execute("{\"goal\":\"nested task\"}", null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("depth limit reached");
    }

    @Test
    void failsWhenDelegationDisabled() {
        AgentProperties props = defaultProperties();
        props.getDelegation().setEnabled(false);
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        ToolResult result = tool.execute("{\"goal\":\"some task\"}", null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Delegation is disabled");
    }

    // ── Batch mode ──────────────────────────────────────────────────────

    @Test
    void batchModeRunsAllTasks() throws Exception {
        AgentProperties props = defaultProperties();
        props.getDelegation().setMaxConcurrentChildren(5);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenReturn(completedResult("Done"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS), 5);
        Session session = defaultSession();

        String args = """
            {"tasks": [
              {"goal": "task A"},
              {"goal": "task B"},
              {"goal": "task C"}
            ]}
            """;

        ToolResult result = tool.execute(args, null, session);

        assertThat(result.success()).isTrue();
        JsonNode node = MAPPER.readTree(result.content());
        assertThat(node.get("results").size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(node.get("results").get(i).get("status").asText()).isEqualTo("completed");
            assertThat(node.get("results").get(i).get("taskIndex").asInt()).isEqualTo(i);
        }
    }

    @Test
    void batchFailsWhenTooManyTasks() {
        AgentProperties props = defaultProperties();
        // maxConcurrentChildren defaults to 3
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        String args = """
            {"tasks": [
              {"goal": "a"}, {"goal": "b"}, {"goal": "c"}, {"goal": "d"}
            ]}
            """;

        ToolResult result = tool.execute(args, null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Too many tasks");
        assertThat(result.error()).contains("max_concurrent_children");
    }

    @Test
    void batchFailsWhenTaskMissingGoal() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        String args = """
            {"tasks": [{"goal": "ok"}, {"context": "no goal here"}]}
            """;

        ToolResult result = tool.execute(args, null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Task 1 is missing a 'goal'");
    }

    // ── Role handling ───────────────────────────────────────────────────

    @Test
    void normalizeRoleDefaultsToLeaf() {
        assertThat(DelegateTaskTool.normalizeRole(null)).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("")).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("LEAF")).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("orchestrator")).isEqualTo("orchestrator");
        assertThat(DelegateTaskTool.normalizeRole("ORCHESTRATOR")).isEqualTo("orchestrator");
    }

    @Test
    void normalizeRoleCoercesUnknownToLeaf() {
        assertThat(DelegateTaskTool.normalizeRole("supervisor")).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("manager")).isEqualTo("leaf");
    }

    @Test
    void orchestratorRoleRespectedWhenEnabledAndWithinDepth() throws Exception {
        AgentProperties props = defaultProperties();
        props.getDelegation().setMaxSpawnDepth(2); // allow orchestrator at depth 1
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenReturn(completedResult("Orchestrated"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = sessionWithDepth(0); // parent at depth 0

        ToolResult result = tool.execute("{\"goal\":\"orchestrate\",\"role\":\"orchestrator\"}", null, session);

        assertThat(result.success()).isTrue();
        JsonNode node = MAPPER.readTree(result.content());
        assertThat(node.get("results").get(0).get("role").asText()).isEqualTo("orchestrator");
    }

    @Test
    void orchestratorRoleCoercedToLeafWhenKillSwitchOff() throws Exception {
        AgentProperties props = defaultProperties();
        props.getDelegation().setOrchestratorEnabled(false);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenReturn(completedResult("Leaf work"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        ToolResult result = tool.execute("{\"goal\":\"try orchestrate\",\"role\":\"orchestrator\"}", null, session);

        assertThat(result.success()).isTrue();
        JsonNode node = MAPPER.readTree(result.content());
        assertThat(node.get("results").get(0).get("role").asText()).isEqualTo("leaf");
    }

    @Test
    void orchestratorRoleCoercedToLeafAtMaxSpawnDepth() throws Exception {
        AgentProperties props = defaultProperties();
        props.getDelegation().setMaxSpawnDepth(2);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenReturn(completedResult("Leaf work"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        // Parent at depth 1, child would be at depth 2 = max_spawn_depth → orchestrator can't nest further
        Session session = sessionWithDepth(1);

        ToolResult result = tool.execute("{\"goal\":\"try orchestrate\",\"role\":\"orchestrator\"}", null, session);

        assertThat(result.success()).isTrue();
        JsonNode node = MAPPER.readTree(result.content());
        assertThat(node.get("results").get(0).get("role").asText()).isEqualTo("leaf");
    }

    // ── System prompt ───────────────────────────────────────────────────

    @Test
    void childSystemPromptContainsGoal() {
        String prompt = DelegateTaskTool.buildChildSystemPrompt(
            "analyze data", null, "leaf", 3, 1);
        assertThat(prompt).contains("analyze data");
        assertThat(prompt).contains("focused subagent");
        assertThat(prompt).doesNotContain("Orchestrator Role");
    }

    @Test
    void childSystemPromptContainsContext() {
        String prompt = DelegateTaskTool.buildChildSystemPrompt(
            "analyze data", "extra context here", "leaf", 3, 1);
        assertThat(prompt).contains("extra context here");
    }

    @Test
    void orchestratorPromptContainsDelegationGuidance() {
        String prompt = DelegateTaskTool.buildChildSystemPrompt(
            "coordinate tasks", null, "orchestrator", 3, 1);
        assertThat(prompt).contains("Orchestrator Role");
        assertThat(prompt).contains("delegate_task");
        assertThat(prompt).contains("depth 1");
        assertThat(prompt).contains("max_spawn_depth=3");
    }

    @Test
    void orchestratorPromptNotesLeafChildrenAtDepthFloor() {
        String prompt = DelegateTaskTool.buildChildSystemPrompt(
            "coordinate tasks", null, "orchestrator", 2, 1);
        // child_depth + 1 >= max_spawn_depth (2) → children must be leaves
        assertThat(prompt).contains("MUST be leaves");
    }

    @Test
    void orchestratorPromptNotesNestingPossibleWhenDepthAllows() {
        String prompt = DelegateTaskTool.buildChildSystemPrompt(
            "coordinate tasks", null, "orchestrator", 5, 1);
        // child_depth + 1 < max_spawn_depth (5) → children can be orchestrators
        assertThat(prompt).contains("can themselves be orchestrators");
    }

    // ── Timeout resolution ──────────────────────────────────────────────

    @Test
    void resolveChildTimeoutUsesCallerTimeoutWhenPositive() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        int resolved = tool.resolveChildTimeout(props.getDelegation(), 600);
        assertThat(resolved).isEqualTo(600);
    }

    @Test
    void resolveChildTimeoutFloorsAt30Seconds() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        int resolved = tool.resolveChildTimeout(props.getDelegation(), 5);
        assertThat(resolved).isEqualTo(30);
    }

    @Test
    void resolveChildTimeoutFallsBackToChildTimeoutConfig() {
        AgentProperties props = defaultProperties();
        props.getDelegation().setChildTimeoutSeconds(120);
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        int resolved = tool.resolveChildTimeout(props.getDelegation(), 0);
        assertThat(resolved).isEqualTo(120);
    }

    @Test
    void resolveChildTimeoutFallsBackToDefaultTimeoutConfig() {
        AgentProperties props = defaultProperties();
        props.getDelegation().setDefaultTimeoutSeconds(450);
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        int resolved = tool.resolveChildTimeout(props.getDelegation(), 0);
        assertThat(resolved).isEqualTo(450);
    }

    // ── Result formatting ───────────────────────────────────────────────

    @Test
    void formatResultsProducesValidJson() throws Exception {
        var results = List.of(
            new DelegateTaskTool.TaskResult(0, "completed", "summary 1", null, 5, "leaf"),
            new DelegateTaskTool.TaskResult(1, "error", null, "boom", 3, "leaf")
        );
        String json = DelegateTaskTool.formatResults(results);
        JsonNode node = MAPPER.readTree(json);
        assertThat(node.get("results").size()).isEqualTo(2);
        assertThat(node.get("results").get(0).get("summary").asText()).isEqualTo("summary 1");
        assertThat(node.get("results").get(1).get("error").asText()).isEqualTo("boom");
    }

    // ── Child session metadata ──────────────────────────────────────────

    @Test
    void childSessionPropagatesDelegationDepth() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                Session childSession = invocation.getArgument(0);
                // Verify the child session has the correct depth metadata
                assertThat(childSession.metadata()).containsEntry("delegation_depth", "1");
                return completedResult("done");
            });

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        tool.execute("{\"goal\":\"test depth\"}", null, session);
    }

    @Test
    void subagentAutoApproveSetsMetadataWhenEnabled() throws Exception {
        AgentProperties props = defaultProperties();
        props.getDelegation().setSubagentAutoApprove(true);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                Session childSession = invocation.getArgument(0);
                assertThat(childSession.metadata()).containsEntry("subagent_auto_approve", "true");
                return completedResult("auto-approved");
            });

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        tool.execute("{\"goal\":\"auto approve task\"}", null, session);
    }

    @Test
    void subagentAutoApproveOmitsMetadataWhenDisabled() throws Exception {
        AgentProperties props = defaultProperties();
        // subagentAutoApprove defaults to false
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                Session childSession = invocation.getArgument(0);
                assertThat(childSession.metadata()).doesNotContainKey("subagent_auto_approve");
                return completedResult("normal");
            });

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        tool.execute("{\"goal\":\"normal task\"}", null, session);
    }

    // ── Error handling ──────────────────────────────────────────────────

    @Test
    void childErrorProducesFailedResult() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenReturn(errorResult("LLM API failed"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        ToolResult result = tool.execute("{\"goal\":\"failing task\"}", null, session);

        assertThat(result.success()).isTrue();
        JsonNode node = MAPPER.readTree(result.content());
        assertThat(node.get("results").get(0).get("status").asText()).isEqualTo("error");
        assertThat(node.get("results").get(0).get("error").asText()).contains("LLM API failed");
    }

    @Test
    void childExceptionProducesErrorResult() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenThrow(new RuntimeException("Runtime crashed"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        ToolResult result = tool.execute("{\"goal\":\"crash test\"}", null, session);

        assertThat(result.success()).isTrue();
        JsonNode node = MAPPER.readTree(result.content());
        assertThat(node.get("results").get(0).get("status").asText()).isEqualTo("error");
    }

    // ── Observability ───────────────────────────────────────────────────

    @Test
    void activeSubagentCountReturnsZeroWhenIdle() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        assertThat(tool.getActiveSubagentCount()).isEqualTo(0);
        assertThat(tool.getAvailableConcurrencyPermits()).isEqualTo(3);
    }

    @Test
    void listActiveSubagentsIsEmptyWhenIdle() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        assertThat(tool.listActiveSubagents()).isEmpty();
    }

    // ── Fix 1: Toolset inheritance ──────────────────────────────────────

    @Test
    void resolveChildToolsetsInheritsParentToolsetsWhenNoneSpecified() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));

        Set<String> parent = Set.of("web", "file", "terminal", "coding", "memory", "gateway", "delegation", "core");
        List<String> child = tool.resolveChildToolsets(null, parent, "leaf");

        // Should inherit parent's toolsets minus blocked ones (delegation, memory, gateway, core, code)
        assertThat(child).contains("web", "file", "terminal", "coding");
        assertThat(child).doesNotContain("delegation", "memory", "gateway", "core", "code");
    }

    @Test
    void resolveChildToolsetsIntersectsWhenExplicitToolsetsSpecified() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));

        Set<String> parent = Set.of("web", "file", "terminal", "coding", "memory", "gateway", "delegation", "core");
        // Request only web and file (both exist in parent)
        List<String> child = tool.resolveChildToolsets(List.of("web", "file"), parent, "leaf");

        assertThat(child).containsExactlyInAnyOrder("web", "file");
    }

    @Test
    void resolveChildToolsetsFiltersOutToolsetsNotInParent() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));

        Set<String> parent = Set.of("web", "file");
        // Request toolsets that parent doesn't have
        List<String> child = tool.resolveChildToolsets(List.of("web", "browser", "terminal"), parent, "leaf");

        // Only "web" should remain (browser and terminal are not in parent)
        assertThat(child).containsExactly("web");
    }

    @Test
    void resolveChildToolsetsOrchestratorReAddsDelegation() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));

        Set<String> parent = Set.of("web", "file", "delegation", "memory");
        List<String> child = tool.resolveChildToolsets(null, parent, "orchestrator");

        // Orchestrator should get delegation back
        assertThat(child).contains("delegation");
        assertThat(child).contains("web", "file");
        assertThat(child).doesNotContain("memory"); // memory is still blocked
    }

    @Test
    void resolveParentToolsetsReadsFromRegistry() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        Set<String> registered = Set.of("web", "file", "terminal");
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(registered));

        Set<String> parent = tool.resolveParentToolsets();
        assertThat(parent).containsExactlyInAnyOrder("web", "file", "terminal");
    }

    @Test
    void childSessionReceivesToolsetsMetadata() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                Session childSession = invocation.getArgument(0);
                String toolsetsMeta = childSession.getMetadata("delegation_toolsets");
                assertThat(toolsetsMeta).isNotNull();
                // Should contain web, file, terminal, coding, core but NOT delegation, memory, gateway
                assertThat(toolsetsMeta).contains("web");
                assertThat(toolsetsMeta).doesNotContain("delegation");
                assertThat(toolsetsMeta).doesNotContain("memory");
                assertThat(toolsetsMeta).doesNotContain("gateway");
                return completedResult("done");
            });

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        tool.execute("{\"goal\":\"test toolsets\"}", null, session);
    }

    // ── Fix 2: Blocked tools ─────────────────────────────────────────────

    @Test
    void blockedToolsIncludeMemoryAndExecuteCode() {
        AgentProperties props = defaultProperties();
        List<String> blocked = props.getDelegation().getBlockedTools();
        assertThat(blocked).contains("delegate_task", "clarify", "memory", "send_message", "execute_code");
    }

    @Test
    void blockedToolsetNamesIncludesDelegationMemoryGateway() {
        assertThat(DelegateTaskTool.BLOCKED_TOOLSET_NAMES).contains("delegation", "memory", "gateway");
    }

    // ── Fix 3: Per-task toolsets ────────────────────────────────────────

    @Test
    void perTaskToolsetsAppliedToChildSession() throws Exception {
        AgentProperties props = defaultProperties();
        props.getDelegation().setMaxConcurrentChildren(5);
        AgentRuntime runtime = mock(AgentRuntime.class);

        // Capture toolsets metadata for each child
        java.util.List<String> capturedToolsets = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                Session childSession = invocation.getArgument(0);
                capturedToolsets.add(childSession.getMetadata("delegation_toolsets"));
                return completedResult("Done");
            });

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS), 5);
        Session session = defaultSession();

        String args = """
            {"tasks": [
              {"goal": "task A", "toolsets": ["web", "file"]},
              {"goal": "task B", "toolsets": ["terminal", "coding"]}
            ]}
            """;

        ToolResult result = tool.execute(args, null, session);
        assertThat(result.success()).isTrue();
        assertThat(capturedToolsets).hasSize(2);
        // Batch tasks run in parallel, so order is non-deterministic.
        // Verify both expected toolset combinations are present.
        assertThat(capturedToolsets).contains("web,file");
        assertThat(capturedToolsets).contains("terminal,coding");
    }

    // ── Fix 4: acp_command / acp_args ────────────────────────────────────

    @Test
    void acpCommandPassedToChildSession() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                Session childSession = invocation.getArgument(0);
                assertThat(childSession.getMetadata("delegation_acp_command")).isEqualTo("copilot");
                assertThat(childSession.getMetadata("delegation_acp_args")).isEqualTo("--acp,--stdio");
                return completedResult("done");
            });

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        String args = """
            {"goal":"test acp","acpCommand":"copilot","acpArgs":["--acp","--stdio"]}
            """;
        tool.execute(args, null, session);
    }

    @Test
    void perTaskAcpCommandOverridesTopLevel() throws Exception {
        AgentProperties props = defaultProperties();
        props.getDelegation().setMaxConcurrentChildren(5);
        AgentRuntime runtime = mock(AgentRuntime.class);

        java.util.List<String> capturedCommands = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                Session childSession = invocation.getArgument(0);
                capturedCommands.add(childSession.getMetadata("delegation_acp_command"));
                return completedResult("done");
            });

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS), 5);
        Session session = defaultSession();

        // Top-level acpCommand: "copilot"
        // Task 0: no per-task acp → should inherit "copilot"
        // Task 1: per-task acp "other-cli" → should be "other-cli"
        String args = """
            {"acpCommand":"copilot","tasks": [
              {"goal": "task A"},
              {"goal": "task B", "acpCommand": "other-cli"}
            ]}
            """;
        tool.execute(args, null, session);

        assertThat(capturedCommands).hasSize(2);
        // Batch tasks run in parallel, so order is non-deterministic.
        assertThat(capturedCommands).contains("copilot", "other-cli");
    }

    @Test
    void acpCommandOmittedWhenNotSet() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                Session childSession = invocation.getArgument(0);
                assertThat(childSession.metadata()).doesNotContainKey("delegation_acp_command");
                assertThat(childSession.metadata()).doesNotContainKey("delegation_acp_args");
                return completedResult("done");
            });

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        tool.execute("{\"goal\":\"no acp\"}", null, session);
    }

    // ── Fix 5: max_iterations ───────────────────────────────────────────

    @Test
    void maxIterationsFromConfigPassedToChildSession() throws Exception {
        AgentProperties props = defaultProperties();
        props.getDelegation().setMaxIterations(25);
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                Session childSession = invocation.getArgument(0);
                assertThat(childSession.getMetadata("delegation_max_turns")).isEqualTo("25");
                return completedResult("done");
            });

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        tool.execute("{\"goal\":\"test max iter\"}", null, session);
    }

    @Test
    void maxIterationsFromCallerOverridesConfig() {
        AgentProperties props = defaultProperties();
        props.getDelegation().setMaxIterations(25);
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));

        int resolved = tool.resolveMaxIterations(props.getDelegation(), 50);
        assertThat(resolved).isEqualTo(50);
    }

    @Test
    void maxIterationsDefaultsToConfigWhenCallerNotSet() {
        AgentProperties props = defaultProperties();
        props.getDelegation().setMaxIterations(30);
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));

        int resolved = tool.resolveMaxIterations(props.getDelegation(), null);
        assertThat(resolved).isEqualTo(30);
    }

    @Test
    void maxIterationsZeroWhenConfigZeroAndCallerNotSet() {
        AgentProperties props = defaultProperties();
        props.getDelegation().setMaxIterations(0);
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));

        int resolved = tool.resolveMaxIterations(props.getDelegation(), null);
        assertThat(resolved).isEqualTo(0);
    }

    // ── Fix 6: Stale comment ─────────────────────────────────────────────

    @Test
    void maxSpawnDepthDefaultIsOne() {
        AgentProperties props = new AgentProperties();
        // The default should be 1 (matches Hermes MAX_DEPTH), not 3
        assertThat(props.getDelegation().getMaxSpawnDepth()).isEqualTo(1);
    }

    // ── Subagent interrupt / pause tests (Finding 3.2) ──────────────────

    @Test
    void interruptSubagentSetsFlag() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));

        // The interrupt map is populated during runSingleChild, but we can test
        // the interrupt mechanism directly by injecting into the map.
        // First, verify that interruptSubagent returns false for unknown id.
        assertThat(tool.interruptSubagent("unknown-id")).isFalse();

        // Simulate an active subagent by registering it via a task execution.
        // We use a blocking runtime to keep the subagent alive, then interrupt.
        // However, for a unit test, we can directly test the flag mechanism:
        // Register a subagent ID manually via a CountDownLatch.
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);

        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenAnswer(inv -> {
                started.countDown();
                finish.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return completedResult("done");
            });

        DelegateTaskTool tool2 = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        java.util.concurrent.CompletableFuture<ToolResult> future =
            java.util.concurrent.CompletableFuture.supplyAsync(() ->
                tool2.execute("{\"goal\":\"long task\"}", null, session));

        // Wait for the subagent to start
        started.await(5, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(100); // give time for subagentInterrupts map to be populated

        // Get the active subagent ID
        String subagentId = tool2.listActiveSubagents().isEmpty() ? null
            : tool2.listActiveSubagents().get(0).subagentId();
        assertThat(subagentId).isNotNull();

        // Interrupt it
        assertThat(tool2.interruptSubagent(subagentId)).isTrue();
        assertThat(tool2.isSubagentInterrupted(subagentId)).isTrue();

        // Let the task finish
        finish.countDown();
        future.join();
    }

    @Test
    void pauseSpawnRejectsNewTasks() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        // Pause spawning
        tool.setSpawnPaused(true);
        assertThat(tool.isSpawnPaused()).isTrue();

        // Delegation should fail because spawning is paused
        // The error comes from runSingleChild → "Subagent spawning is paused"
        // which is wrapped in the result JSON
        ToolResult result = tool.execute("{\"goal\":\"test paused\"}", null, session);

        // The result should indicate an error
        assertThat(result.success()).isTrue(); // tool execution itself succeeds, returns JSON
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = MAPPER.readTree(result.content());
            assertThat(node.get("results").get(0).get("status").asText()).isEqualTo("error");
            assertThat(node.get("results").get(0).get("error").asText()).contains("paused");
        } catch (Exception e) {
            // If JSON parsing fails, check the raw content
            assertThat(result.content()).contains("paused");
        }

        // Resume for cleanup
        tool.setSpawnPaused(false);
    }

    @Test
    void resumeSpawnAllowsNewTasks() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenReturn(completedResult("resumed task done"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), toolRegistry(DEFAULT_PARENT_TOOLSETS));
        Session session = defaultSession();

        // Pause then resume
        tool.setSpawnPaused(true);
        tool.setSpawnPaused(false);
        assertThat(tool.isSpawnPaused()).isFalse();

        // Delegation should work now
        ToolResult result = tool.execute("{\"goal\":\"test resumed\"}", null, session);

        assertThat(result.success()).isTrue();
        JsonNode node = MAPPER.readTree(result.content());
        assertThat(node.get("results").get(0).get("status").asText()).isEqualTo("completed");
    }
}