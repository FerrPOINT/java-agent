package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * c7: Tests for {@link BackendResponseFormatter} — the presentation layer
 * that converts backend {@link JsonNode} responses into formatted CLI strings.
 */
class BackendResponseFormatterTest {

    private BackendResponseFormatter formatter;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        formatter = new BackendResponseFormatter(mapper);
    }

    // ── prettyPrint ──

    @Test
    void prettyPrintNullReturnsNullString() {
        assertThat(formatter.prettyPrint(null)).isEqualTo("null");
    }

    @Test
    void prettyPrintObjectReturnsIndentedJson() {
        ObjectNode node = mapper.createObjectNode();
        node.put("key", "value");
        String result = formatter.prettyPrint(node);
        assertThat(result).contains("\"key\"").contains("value");
        // pretty printer adds newlines
        assertThat(result).contains("\n");
    }

    @Test
    void prettyPrintArrayReturnsIndentedJson() {
        ArrayNode arr = mapper.createArrayNode();
        arr.add("a").add("b");
        String result = formatter.prettyPrint(arr);
        assertThat(result).contains("\"a\"").contains("\"b\"");
    }

    // ── formatCronJobs ──

    @Test
    void formatCronJobsNullReturnsNotFound() {
        assertThat(formatter.formatCronJobs(null)).isEqualTo("No cron jobs found.");
    }

    @Test
    void formatCronJobsEmptyArrayReturnsNotFound() {
        assertThat(formatter.formatCronJobs(mapper.createArrayNode())).isEqualTo("No cron jobs found.");
    }

    @Test
    void formatCronJobsNonArrayReturnsNotFound() {
        assertThat(formatter.formatCronJobs(mapper.createObjectNode())).isEqualTo("No cron jobs found.");
    }

    @Test
    void formatCronJobsFormatsEntries() {
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode job = mapper.createObjectNode();
        job.put("id", "job-1");
        job.put("name", "nightly-report");
        job.put("schedule", "0 2 * * *");
        job.put("enabled", true);
        arr.add(job);
        ObjectNode job2 = mapper.createObjectNode();
        job2.put("id", "job-2");
        job2.put("name", "cleanup");
        job2.put("schedule", "0 4 * * 0");
        job2.put("enabled", false);
        arr.add(job2);

        String result = formatter.formatCronJobs(arr);
        assertThat(result).startsWith("Cron jobs:");
        assertThat(result).contains("job-1").contains("nightly-report").contains("0 2 * * *").contains("enabled");
        assertThat(result).contains("job-2").contains("cleanup").contains("paused");
    }

    // ── formatCheckpoints ──

    @Test
    void formatCheckpointsNullReturnsNotFound() {
        assertThat(formatter.formatCheckpoints(null)).isEqualTo("No checkpoints found.");
    }

    @Test
    void formatCheckpointsFormatsEntries() {
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode cp = mapper.createObjectNode();
        cp.put("id", "cp-1");
        cp.put("description", "before refactor");
        cp.put("fileCount", 12);
        arr.add(cp);

        String result = formatter.formatCheckpoints(arr);
        assertThat(result).startsWith("Checkpoints:");
        assertThat(result).contains("cp-1").contains("before refactor").contains("12 files");
    }

    // ── formatConfig ──

    @Test
    void formatConfigNullReturnsMessage() {
        assertThat(formatter.formatConfig(null)).isEqualTo("Config: no response from backend.");
    }

    @Test
    void formatConfigFormatsFields() {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", "java-agent");
        node.put("model", "gpt-4o");
        node.put("provider", "openai");
        node.put("baseUrl", "http://localhost:8090");
        node.put("maxTurns", 10);
        node.put("maxTokens", 4096);
        node.put("temperature", 0.7);
        node.put("timeoutSeconds", 30);
        node.put("reasoningConfig", "high");
        ObjectNode features = mapper.createObjectNode();
        features.put("memory", true);
        features.put("tts", false);
        node.set("features", features);

        String result = formatter.formatConfig(node);
        assertThat(result).startsWith("Agent config:");
        assertThat(result).contains("java-agent").contains("gpt-4o").contains("openai");
        assertThat(result).contains("Max turns: 10").contains("Max tokens: 4096");
        assertThat(result).contains("Temperature: 0.7").contains("Timeout: 30s");
        assertThat(result).contains("memory: ON").contains("tts: OFF");
    }

    // ── formatDoctor ──

    @Test
    void formatDoctorNullReturnsMessage() {
        assertThat(formatter.formatDoctor(null)).isEqualTo("Doctor: no response from backend.");
    }

    @Test
    void formatDoctorFormatsFields() {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", "UP");
        node.put("name", "agent");
        node.put("version", "1.0");
        node.put("model", "gpt-4o");
        node.put("memoryEnabled", true);
        node.put("ttsEnabled", false);
        node.put("skillCount", 5);

        String result = formatter.formatDoctor(node);
        assertThat(result).startsWith("Doctor report:");
        assertThat(result).contains("Backend: UP").contains("Memory: ON").contains("TTS: OFF");
        assertThat(result).contains("Skills loaded: 5");
    }

    // ── formatCredits ──

    @Test
    void formatCreditsNullReturnsMessage() {
        assertThat(formatter.formatCredits(null)).isEqualTo("No credits data.");
    }

    @Test
    void formatCreditsFormatsFields() {
        ObjectNode node = mapper.createObjectNode();
        node.put("totalCost", 1.23);
        node.put("totalTokens", 5000);
        node.put("totalMessages", 42);

        String result = formatter.formatCredits(node);
        assertThat(result).startsWith("Credits summary:");
        assertThat(result).contains("$1.23").contains("Total tokens: 5000").contains("Total messages: 42");
    }

    // ── formatCuratorStatus ──

    @Test
    void formatCuratorStatusNullReturnsMessage() {
        assertThat(formatter.formatCuratorStatus(null)).isEqualTo("No curator status.");
    }

    @Test
    void formatCuratorStatusFormatsFields() {
        ObjectNode node = mapper.createObjectNode();
        node.put("enabled", true);
        node.put("paused", false);
        node.put("dryRun", true);
        node.put("intervalHours", 6);
        node.put("minIdleHours", 2);
        node.put("staleAfterDays", 14);
        node.put("archiveAfterDays", 30);

        String result = formatter.formatCuratorStatus(node);
        assertThat(result).startsWith("Curator status:");
        assertThat(result).contains("Enabled: true").contains("Paused: false").contains("Dry run: true");
        assertThat(result).contains("Interval (hours): 6").contains("Stale after (days): 14");
        assertThat(result).contains("Archive after (days): 30");
    }

    // ── formatKanban ──

    @Test
    void formatKanbanNullReturnsEmpty() {
        assertThat(formatter.formatKanban(null)).isEqualTo("Kanban board is empty.");
    }

    @Test
    void formatKanbanFormatsEntries() {
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode item = mapper.createObjectNode();
        item.put("id", "k-1");
        item.put("title", "write tests");
        item.put("status", "todo");
        item.put("priority", "high");
        arr.add(item);

        String result = formatter.formatKanban(arr);
        assertThat(result).startsWith("Kanban board:");
        assertThat(result).contains("[todo]").contains("write tests").contains("high").contains("k-1");
    }

    // ── formatCodexRuntime ──

    @Test
    void formatCodexRuntimeNullReturnsMessage() {
        assertThat(formatter.formatCodexRuntime(null)).isEqualTo("No runtime data.");
    }

    @Test
    void formatCodexRuntimeFormatsFields() {
        ObjectNode node = mapper.createObjectNode();
        node.put("model", "gpt-4o");
        node.put("provider", "openai");
        node.put("maxRetries", 3);
        node.put("maxTokens", 8192);
        node.put("timeoutSeconds", 120);
        node.put("modelOverride", "gpt-4o-mini");

        String result = formatter.formatCodexRuntime(node);
        assertThat(result).startsWith("Codex runtime:");
        assertThat(result).contains("Model: gpt-4o").contains("Provider: openai");
        assertThat(result).contains("Max retries: 3").contains("Max tokens: 8192");
        assertThat(result).contains("Timeout (seconds): 120").contains("Model override: gpt-4o-mini");
    }

    @Test
    void formatCodexRuntimeWithoutOverride() {
        ObjectNode node = mapper.createObjectNode();
        node.put("model", "claude");
        String result = formatter.formatCodexRuntime(node);
        assertThat(result).contains("Model: claude");
        assertThat(result).doesNotContain("Model override");
    }

    // ── formatGoal ──

    @Test
    void formatGoalNullReturnsNoGoal() {
        assertThat(formatter.formatGoal(null)).isEqualTo("No goal set.");
    }

    @Test
    void formatGoalMissingReturnsNoGoal() {
        ObjectNode node = mapper.createObjectNode();
        assertThat(formatter.formatGoal(node)).isEqualTo("No goal set.");
    }

    @Test
    void formatGoalPresent() {
        ObjectNode node = mapper.createObjectNode();
        node.put("goal", "ship the feature");
        assertThat(formatter.formatGoal(node)).isEqualTo("Current goal: ship the feature");
    }

    @Test
    void formatGoalPaused() {
        ObjectNode node = mapper.createObjectNode();
        node.put("goal", "ship the feature");
        node.put("goalPaused", true);
        assertThat(formatter.formatGoal(node)).isEqualTo("Current goal: ship the feature (paused)");
    }

    // ── formatPlan ──

    @Test
    void formatPlanNullReturnsNoPlan() {
        assertThat(formatter.formatPlan(null)).isEqualTo("No plan available.");
    }

    @Test
    void formatPlanMissingReturnsNoPlanSet() {
        ObjectNode node = mapper.createObjectNode();
        assertThat(formatter.formatPlan(node)).isEqualTo("No plan set for this session.");
    }

    @Test
    void formatPlanArrayFormatsItems() {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode plan = mapper.createArrayNode();
        ObjectNode i1 = mapper.createObjectNode();
        i1.put("text", "design");
        i1.put("done", false);
        ObjectNode i2 = mapper.createObjectNode();
        i2.put("text", "implement");
        i2.put("done", true);
        plan.add(i1).add(i2);
        node.set("plan", plan);

        String result = formatter.formatPlan(node);
        assertThat(result).startsWith("Current plan:");
        assertThat(result).contains("design").contains("implement");
        assertThat(result).contains("[ ]").contains("[x]");
    }

    @Test
    void formatPlanStringReturnsPlanText() {
        ObjectNode node = mapper.createObjectNode();
        node.put("plan", "just do it");
        assertThat(formatter.formatPlan(node)).isEqualTo("Plan: just do it");
    }

    // ── formatHistory ──

    @Test
    void formatHistoryNullReturnsNoHistory() {
        assertThat(formatter.formatHistory(null, "sid")).isEqualTo("No history available.");
    }

    @Test
    void formatHistoryFormatsContext() {
        ObjectNode ctx = mapper.createObjectNode();
        ctx.put("messageCount", 10);
        ctx.put("tokenEstimate", 2048);
        ArrayNode tools = mapper.createArrayNode();
        tools.add("web_search").add("filesystem");
        ctx.set("toolsUsed", tools);

        String result = formatter.formatHistory(ctx, "session-42");
        assertThat(result).contains("Session: session-42");
        assertThat(result).contains("Messages: 10");
        assertThat(result).contains("Token estimate: 2048");
        assertThat(result).contains("web_search").contains("filesystem");
    }

    // ── formatSessionList ──

    @Test
    void formatSessionListNullReturnsNoSessions() {
        assertThat(formatter.formatSessionList(null)).contains("No sessions found");
    }

    @Test
    void formatSessionListEmptyReturnsNoSessions() {
        assertThat(formatter.formatSessionList(mapper.createArrayNode())).contains("No sessions found");
    }

    @Test
    void formatSessionListFormatsEntries() {
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode s1 = mapper.createObjectNode();
        s1.put("id", "sess-1");
        s1.put("title", "My session");
        arr.add(s1);

        String result = formatter.formatSessionList(arr);
        assertThat(result).startsWith("Available sessions:");
        assertThat(result).contains("sess-1").contains("My session");
        assertThat(result).contains("Use /resume <sessionId>");
    }
}