package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
    void executeWithNoGoalAndNoTasksFails() {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute("{}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Provide either 'goal'");
    }

    // ── 4. Invalid args — task missing goal ───────────────────────────────

    @Test
    void executeWithTaskMissingGoalFails() {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        String args = """
            {"tasks":[
              {"goal":"Valid task"},
              {"context":"no goal here"}
            ]}""";

        ToolResult result = tool.execute(args, null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Task 1 is missing a 'goal'");
    }

    // ── 5. Delegation disabled — returns fail ─────────────────────────────

    @Test
    void executeFailsWhenDelegationDisabled() {
        AgentProperties p = properties();
        p.getDelegation().setEnabled(false);
        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute("{\"goal\":\"test\"}", null, session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Delegation is disabled");
    }

    // ── 6. Depth limit reached — returns fail ─────────────────────────────

    @Test
    void executeFailsWhenDepthLimitReached() {
        AgentProperties p = properties();
        p.getDelegation().setMaxSpawnDepth(1);
        DelegateTaskTool tool = newTool(p);

        // Session at depth 0, maxSpawnDepth=1 → depth 0 < 1 is allowed, but
        // let's set the session to depth 1 which equals maxSpawnDepth
        Session deepSession = new Session(UUID.randomUUID(), "user", "test", "openai", "gpt-4", null,
            Map.of("delegation_depth", "1"), null);

        ToolResult result = tool.execute("{\"goal\":\"deep task\"}", null, deepSession);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Delegation depth limit reached");
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
    void executeFailsWhenTooManyTasks() {
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

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Too many tasks");
        assertThat(result.error()).contains("max_concurrent_children is 2");
    }

    // ── 9. resolveChildToolsets — inheritance strips blocked toolsets ─────

    @Test
    void resolveChildToolsetsInheritsAndStripsBlocked() {
        AgentProperties p = properties();
        DelegateTaskTool tool = newTool(p);

        Set<String> parentToolsets = new java.util.LinkedHashSet<>(List.of("web", "file", "delegation", "memory", "gateway", "core", "code"));

        List<String> child = tool.resolveChildToolsets(null, parentToolsets, "leaf");

        // Blocked toolsets (delegation, memory, gateway, core, code) should be stripped
        assertThat(child).contains("web", "file");
        assertThat(child).doesNotContain("delegation", "memory", "gateway", "core", "code");
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

    // ── 11. normalizeRole — unknown role coerces to leaf ──────────────────

    @Test
    void normalizeRoleCoercesUnknownToLeaf() {
        assertThat(DelegateTaskTool.normalizeRole(null)).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("")).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("invalid")).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("LEAF")).isEqualTo("leaf");
        assertThat(DelegateTaskTool.normalizeRole("orchestrator")).isEqualTo("orchestrator");
    }
    // ── rev-133: ancestor ownership (Hermes _is_descendant_of parity) ──────

    private void registerSubagent(DelegateTaskTool tool, String sid, String parentSid, UUID childSid, int depth) {
        try {
            var field = DelegateTaskTool.class.getDeclaredField("activeSubagents");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, DelegateTaskTool.SubagentRecord>) field.get(tool);
            map.put(sid, new DelegateTaskTool.SubagentRecord(sid, depth, "g", System.currentTimeMillis(), parentSid, childSid));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void ancestorMayControlGrandchildren() {
        DelegateTaskTool tool = newTool(properties());
        UUID root = UUID.randomUUID();
        UUID orchestratorChild = UUID.randomUUID();
        UUID grandchild = UUID.randomUUID();
        registerSubagent(tool, "mid", root.toString(), orchestratorChild, 1);
        registerSubagent(tool, "leaf-b", orchestratorChild.toString(), grandchild, 2);

        var midRec = readRecord(tool, "mid");
        var leafRec = readRecord(tool, "leaf-b");
        org.junit.jupiter.api.Assertions.assertTrue(tool.isOwnedDescendant(leafRec, root),
            "root must steer/stop its grandchild (ancestor chain, Hermes _is_descendant_of)");
        org.junit.jupiter.api.Assertions.assertTrue(tool.isOwnedDescendant(midRec, root),
            "direct child is owned");
        org.junit.jupiter.api.Assertions.assertTrue(tool.isOwnedDescendant(leafRec, orchestratorChild),
            "mid orchestrator owns its direct child");
    }

    @Test
    void siblingTreeIsNeverOwned() {
        DelegateTaskTool tool = newTool(properties());
        UUID root = UUID.randomUUID();
        UUID otherRoot = UUID.randomUUID();
        UUID strangerChild = UUID.randomUUID();
        registerSubagent(tool, "stranger", otherRoot.toString(), strangerChild, 1);

        var rec = readRecord(tool, "stranger");
        org.junit.jupiter.api.Assertions.assertFalse(tool.isOwnedDescendant(rec, root),
            "another conversation's subtree must not be controllable");
    }

    @Test
    void nullsAndCyclesAreRejected() {
        DelegateTaskTool tool = newTool(properties());
        org.junit.jupiter.api.Assertions.assertFalse(tool.isOwnedDescendant(null, UUID.randomUUID()));
        UUID root = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        registerSubagent(tool, "c", root.toString(), child, 1);
        var rec = readRecord(tool, "c");
        org.junit.jupiter.api.Assertions.assertFalse(tool.isOwnedDescendant(rec, null));
        org.junit.jupiter.api.Assertions.assertFalse(tool.isOwnedDescendant(rec, child),
            "self is not its own ancestor");
    }

    private DelegateTaskTool.SubagentRecord readRecord(DelegateTaskTool tool, String sid) {
        try {
            var field = DelegateTaskTool.class.getDeclaredField("activeSubagents");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, DelegateTaskTool.SubagentRecord>) field.get(tool);
            return map.get(sid);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}