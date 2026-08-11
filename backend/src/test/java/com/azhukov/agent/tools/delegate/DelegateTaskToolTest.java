package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

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

    private AgentProperties defaultProperties() {
        AgentProperties props = new AgentProperties();
        // Use defaults: maxDepth=3, maxSpawnDepth=3, maxConcurrentChildren=3
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

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
        Session session = defaultSession();

        ToolResult result = tool.execute("{}", null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Provide either 'goal'");
    }

    @Test
    void failsWhenMaxSpawnDepthReached() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime), 5);
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
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenReturn(completedResult("Orchestrated"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
        int resolved = tool.resolveChildTimeout(props.getDelegation(), 600);
        assertThat(resolved).isEqualTo(600);
    }

    @Test
    void resolveChildTimeoutFloorsAt30Seconds() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
        int resolved = tool.resolveChildTimeout(props.getDelegation(), 5);
        assertThat(resolved).isEqualTo(30);
    }

    @Test
    void resolveChildTimeoutFallsBackToChildTimeoutConfig() {
        AgentProperties props = defaultProperties();
        props.getDelegation().setChildTimeoutSeconds(120);
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
        int resolved = tool.resolveChildTimeout(props.getDelegation(), 0);
        assertThat(resolved).isEqualTo(120);
    }

    @Test
    void resolveChildTimeoutFallsBackToDefaultTimeoutConfig() {
        AgentProperties props = defaultProperties();
        props.getDelegation().setDefaultTimeoutSeconds(450);
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
        Session session = defaultSession();

        tool.execute("{\"goal\":\"test depth\"}", null, session);
    }

    // ── Error handling ──────────────────────────────────────────────────

    @Test
    void childErrorProducesFailedResult() throws Exception {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any(String.class), eq(List.of()), any()))
            .thenReturn(errorResult("LLM API failed"));

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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

        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
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
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
        assertThat(tool.getActiveSubagentCount()).isEqualTo(0);
        assertThat(tool.getAvailableConcurrencyPermits()).isEqualTo(3);
    }

    @Test
    void listActiveSubagentsIsEmptyWhenIdle() {
        AgentProperties props = defaultProperties();
        AgentRuntime runtime = mock(AgentRuntime.class);
        DelegateTaskTool tool = new DelegateTaskTool(props, runtimeProvider(runtime));
        assertThat(tool.listActiveSubagents()).isEmpty();
    }
}