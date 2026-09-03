package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.metrics.AgentMetrics;
import com.azhukov.agent.core.security.GuardrailDecision;
import com.azhukov.agent.core.security.SecretRedactor;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolExecutionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String USER_ID = "user-42";
    private static final Session SESSION = Session.create(USER_ID, "noop", "default");
    private static final Message LAST_MSG = Message.user("test prompt");

    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ToolCallGuardrail guardrail;
    @Mock
    private SecretRedactor redactor;
    @Mock
    private ToolResultClassifier toolResultClassifier;
    @Mock
    private ToolOutputLimiter toolOutputLimiter;
    @Mock
    private AgentMetrics agentMetrics;

    private AgentProperties properties;
    private ToolExecutionService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        // Use a high maxChars so truncation doesn't interfere with tests
        properties.getToolOutput().setMaxChars(100_000);
        // Set a short timeout so timeout tests run quickly
        properties.getToolOutput().setTimeoutSeconds(5);
        service = new ToolExecutionService(
            toolRegistry, properties, guardrail, redactor,
            toolResultClassifier, toolOutputLimiter, agentMetrics);
    }

    @Test
    @DisplayName("Should execute tool successfully and return redacted, truncated result")
    void shouldExecuteToolSuccessfully() {
        String toolName = "web_search";
        String toolCallId = "call-1";
        String arguments = "{\"query\":\"test\"}";
        ToolResult rawResult = ToolResult.ok("search results here");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(toolName, toolCallId, arguments, LAST_MSG, SESSION)).thenReturn(rawResult);
        when(redactor.redact("search results here")).thenReturn("search results here");
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolResultClassifier.classify(any(ToolResult.class)))
            .thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, toolCallId, arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("search results here");
        verify(agentMetrics).incrementToolCalls(toolName);
        verify(agentMetrics, never()).incrementToolErrors(toolName);
        verify(toolResultClassifier).classify(any(ToolResult.class));
        verify(toolOutputLimiter).truncate(any(ToolResult.class), anyString());
    }

    @Test
    @DisplayName("Should return fail when guardrail blocks before call")
    void shouldFailWhenGuardrailBlocksBeforeCall() {
        String toolName = "bash";
        String arguments = "rm -rf /";
        GuardrailDecision block = GuardrailDecision.block(toolName, "dangerous", "Blocked: dangerous command");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(block);

        ToolResult result = service.execute(toolName, "call-2", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Blocked: dangerous command");
        // Should NOT call the registry since guardrail blocked
        verify(toolRegistry, never()).execute(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Should return fail when guardrail halts before call")
    void shouldFailWhenGuardrailHaltsBeforeCall() {
        String toolName = "bash";
        String arguments = "halt command";
        GuardrailDecision halt = GuardrailDecision.halt(toolName, "halt_code", "Halted by guardrail");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(halt);

        ToolResult result = service.execute(toolName, "call-3", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Halted by guardrail");
        verify(toolRegistry, never()).execute(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Should handle tool execution failure and record metrics")
    void shouldHandleToolExecutionFailure() {
        String toolName = "failing_tool";
        String arguments = "{}";

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION)))
            .thenThrow(new RuntimeException("Tool crashed"));
        // redactor.redact is called on the error path; return the error as-is
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, "call-4", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Tool execution failed");
        assertThat(result.error()).contains("failing_tool");
        verify(agentMetrics).incrementToolCalls(toolName);
        verify(agentMetrics).incrementToolErrors(toolName);
    }

    @Test
    @DisplayName("Should preserve diagnostic content from failed tool results")
    void shouldPreserveFailedToolDiagnosticContent() {
        String toolName = "terminal";
        String arguments = "{\"command\":\"failing-build\"}";
        ToolResult rawResult = new ToolResult(false, "compiler output\nBUILD FAILED", "exit 1");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION)))
            .thenReturn(rawResult);
        when(redactor.redact("compiler output\nBUILD FAILED")).thenReturn("compiler output\nBUILD FAILED");
        when(redactor.redact("exit 1")).thenReturn("exit 1");
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolResultClassifier.classify(any(ToolResult.class)))
            .thenReturn(ToolResultClassifier.ResultType.FAILURE);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, "call-terminal-fail", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("compiler output");
        assertThat(result.content()).contains("BUILD FAILED");
        assertThat(result.error()).isEqualTo("exit 1");
    }

    @Test
    @DisplayName("Should handle IllegalArgumentException without retry (ignored exception)")
    void shouldHandleIllegalArgumentExceptionWithoutRetry() {
        String toolName = "bad_args_tool";
        String arguments = "{}";

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION)))
            .thenThrow(new IllegalArgumentException("Invalid arguments"));
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, "call-5", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        // IllegalArgumentException is an ignored exception for the retry — should fail immediately
        assertThat(result.error()).contains("Tool execution failed");
        // Should only be called once (no retry)
        verify(toolRegistry).execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION));
    }

    @Test
    @DisplayName("Should modify result when guardrail blocks after call")
    void shouldModifyResultWhenGuardrailBlocksAfterCall() {
        String toolName = "file_write";
        String arguments = "{\"path\":\"/etc/passwd\"}";
        ToolResult rawResult = ToolResult.ok("written");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(toolName, "call-6", arguments, LAST_MSG, SESSION)).thenReturn(rawResult);
        // afterCall blocks
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.block(toolName, "post_block", "Post-call block"));
        // On block, the result error is redacted
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(toolResultClassifier.classify(any(ToolResult.class)))
            .thenReturn(ToolResultClassifier.ResultType.FAILURE);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, "call-6", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Guardrail");
        assertThat(result.error()).contains("Post-call block");
    }

    @Test
    @DisplayName("Should redact secrets from successful result content")
    void shouldRedactSecretsFromSuccessfulResult() {
        String toolName = "web_search";
        String arguments = "{}";
        ToolResult rawResult = ToolResult.ok("api_key=sk-abc123secret");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(toolName, "call-7", arguments, LAST_MSG, SESSION)).thenReturn(rawResult);
        when(redactor.redact("api_key=sk-abc123secret")).thenReturn("api_key=[REDACTED]");
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolResultClassifier.classify(any(ToolResult.class)))
            .thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, "call-7", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("api_key=[REDACTED]");
        verify(redactor).redact("api_key=sk-abc123secret");
    }

    @Test
    @DisplayName("Should record execution in TurnState when provided")
    void shouldRecordExecutionInTurnState() {
        String toolName = "web_search";
        String arguments = "{\"query\":\"test\"}";
        TurnState turnState = new TurnState("session-1", 0);
        ToolResult rawResult = ToolResult.ok("result");

        when(guardrail.beforeCall(eq(toolName), eq(arguments), eq(turnState)))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(toolName, "call-8", arguments, LAST_MSG, SESSION)).thenReturn(rawResult);
        when(redactor.redact("result")).thenReturn("result");
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean(), eq(turnState)))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolResultClassifier.classify(any(ToolResult.class)))
            .thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, "call-8", arguments, LAST_MSG, SESSION, turnState);

        assertThat(result.success()).isTrue();
        assertThat(turnState.totalExecutions()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should handle unknown tool failure from registry")
    void shouldHandleUnknownToolFromRegistry() throws Exception {
        String toolName = "nonexistent_tool";
        String arguments = "{}";

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION)))
            .thenReturn(ToolResult.fail("Unknown tool: " + toolName));
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, "call-9", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Unknown tool: " + toolName);
        assertThat(result.error()).isEqualTo(json.path("error").asText());
        verify(agentMetrics).incrementToolErrors(toolName);
    }

    @Test
    @DisplayName("Overloaded execute without TurnState should delegate to full method")
    void overloadedExecuteShouldDelegateToFullMethod() {
        String toolName = "web_search";
        String arguments = "{}";
        ToolResult rawResult = ToolResult.ok("ok");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(toolName, "call-10", arguments, LAST_MSG, SESSION)).thenReturn(rawResult);
        when(redactor.redact("ok")).thenReturn("ok");
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolResultClassifier.classify(any(ToolResult.class)))
            .thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, "call-10", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("ok");
    }

    @Test
    @DisplayName("Should scope guardrail calls to the executing session")
    void shouldScopeGuardrailCallsToSession() {
        RecordingGuardrail recordingGuardrail = new RecordingGuardrail();
        ToolExecutionService scopedService = new ToolExecutionService(
            toolRegistry, properties, recordingGuardrail, redactor,
            toolResultClassifier, toolOutputLimiter, agentMetrics);
        String toolName = "web_search";
        String arguments = "{}";
        ToolResult rawResult = ToolResult.ok("ok");

        when(toolRegistry.execute(toolName, "call-session", arguments, LAST_MSG, SESSION)).thenReturn(rawResult);
        when(redactor.redact("ok")).thenReturn("ok");
        when(toolResultClassifier.classify(any(ToolResult.class)))
            .thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = scopedService.execute(toolName, "call-session", arguments, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(recordingGuardrail.beforeSessionIds).containsExactly(SESSION.id());
        assertThat(recordingGuardrail.afterSessionIds).containsExactly(SESSION.id());
        assertThat(InterruptToken.currentSessionId()).isNull();
    }

    private static class RecordingGuardrail implements ToolCallGuardrail {
        final List<UUID> beforeSessionIds = new ArrayList<>();
        final List<UUID> afterSessionIds = new ArrayList<>();

        @Override
        public GuardrailDecision beforeCall(String toolName, String arguments) {
            beforeSessionIds.add(InterruptToken.currentSessionId());
            return GuardrailDecision.allow(toolName);
        }

        @Override
        public GuardrailDecision afterCall(String toolName, String arguments, ToolResult result, boolean failed) {
            afterSessionIds.add(InterruptToken.currentSessionId());
            return GuardrailDecision.allow(toolName);
        }
    }


    @Test
    @DisplayName("Should append idempotent no-progress warning to a successful tool result")
    void shouldAppendNoProgressWarning() {
        ToolLoopGuardrail loopGuardrail = new ToolLoopGuardrail(true, 2, 3, 2, 50, 50);
        service.setToolLoopGuardrail(loopGuardrail);
        String toolName = "read_file";
        String arguments = "{\"path\":\"README.md\"}";
        ToolResult rawResult = ToolResult.ok("same content");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION))).thenReturn(rawResult);
        when(redactor.redact("same content")).thenReturn("same content");
        when(toolResultClassifier.classify(any(ToolResult.class))).thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(toolName, "call-1", arguments, LAST_MSG, SESSION);
        ToolResult second = service.execute(toolName, "call-2", arguments, LAST_MSG, SESSION);

        assertThat(second.success()).isTrue();
        assertThat(second.content()).contains("Tool loop guardrail");
        assertThat(second.content()).contains("same result 2 times");
    }

    @Test
    @DisplayName("Should block web_search after the per-turn cap before registry execution")
    void shouldBlockWebSearchAfterCap() {
        ToolLoopGuardrail loopGuardrail = new ToolLoopGuardrail(true, 2, 3, 2, 1, 50);
        service.setToolLoopGuardrail(loopGuardrail);
        String toolName = "web_search";
        String arguments = "{\"query\":\"test\"}";
        ToolResult rawResult = ToolResult.ok("result");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION))).thenReturn(rawResult);
        when(redactor.redact("result")).thenReturn("result");
        when(toolResultClassifier.classify(any(ToolResult.class))).thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(toolName, "call-1", arguments, LAST_MSG, SESSION);
        ToolResult blocked = service.execute(toolName, "call-2", arguments, LAST_MSG, SESSION);

        assertThat(blocked.success()).isFalse();
        assertThat(blocked.error()).contains("Blocked web_search");
        assertThat(blocked.content()).contains("\"error\":\"Blocked web_search");
        assertThat(blocked.content()).contains("\"code\":\"loop_web_search_cap\"");
        assertThat(blocked.content()).contains("\"action\":\"block\"");
        verify(toolRegistry, org.mockito.Mockito.times(1)).execute(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Reset loop guardrail clears the per-turn web_search cap")
    void resetLoopGuardrailClearsCap() {
        ToolLoopGuardrail loopGuardrail = new ToolLoopGuardrail(true, 2, 3, 2, 1, 50);
        service.setToolLoopGuardrail(loopGuardrail);
        String toolName = "web_search";
        String arguments = "{\"query\":\"test\"}";
        ToolResult rawResult = ToolResult.ok("result");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION))).thenReturn(rawResult);
        when(redactor.redact("result")).thenReturn("result");
        when(toolResultClassifier.classify(any(ToolResult.class))).thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(toolName, "call-1", arguments, LAST_MSG, SESSION);
        service.resetLoopGuardrailForTurn();
        ToolResult afterReset = service.execute(toolName, "call-2", arguments, LAST_MSG, SESSION);

        assertThat(afterReset.success()).isTrue();
    }



    @Test
    @DisplayName("Should persist oversized successful result before the output limiter")
    void shouldPersistBeforeOutputLimiter() {
        ToolResultStorage storage = org.mockito.Mockito.mock(ToolResultStorage.class);
        service.setToolResultStorage(storage);
        String toolName = "terminal";
        String arguments = "{\"command\":\"long-output\"}";
        ToolResult rawResult = ToolResult.ok("very large output");
        ToolResult persisted = ToolResult.ok("""
            <persisted-output>
            Full output saved to: /tmp/java-agent-results/id.txt
            preview
            </persisted-output>""");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION))).thenReturn(rawResult);
        when(redactor.redact("very large output")).thenReturn("very large output");
        when(storage.maybePersist(any(ToolResult.class), eq(toolName), eq("call-persist"))).thenReturn(persisted);
        when(toolResultClassifier.classify(any(ToolResult.class))).thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString())).thenAnswer(inv -> inv.getArgument(0));

        ToolResult result = service.execute(toolName, "call-persist", arguments, LAST_MSG, SESSION);

        assertThat(result.content()).contains("<persisted-output>");
        verify(storage).maybePersist(any(ToolResult.class), eq(toolName), eq("call-persist"));
        verify(toolOutputLimiter).truncate(eq(persisted), eq("terminal"));
    }



    @Test
    @DisplayName("Should checkpoint before write_file execution")
    void shouldCheckpointBeforeWriteFile() {
        com.azhukov.agent.service.CheckpointManager ckptMgr = org.mockito.Mockito.mock(com.azhukov.agent.service.CheckpointManager.class);
        service.setCheckpointManager(ckptMgr);
        String toolName = "write_file";
        String arguments = "{\"path\":\"/tmp/test.txt\",\"content\":\"hello\"}";
        ToolResult okResult = ToolResult.ok("File written");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION))).thenReturn(okResult);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(toolResultClassifier.classify(any(ToolResult.class))).thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(toolName, "call-ckpt-1", arguments, LAST_MSG, SESSION);

        verify(ckptMgr).snapshot("before write_file");
    }

    @Test
    @DisplayName("Should checkpoint before patch execution")
    void shouldCheckpointBeforePatch() {
        com.azhukov.agent.service.CheckpointManager ckptMgr = org.mockito.Mockito.mock(com.azhukov.agent.service.CheckpointManager.class);
        service.setCheckpointManager(ckptMgr);
        String toolName = "patch";
        String arguments = "{\"path\":\"/tmp/test.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}";
        ToolResult okResult = ToolResult.ok("Patched");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION))).thenReturn(okResult);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(toolResultClassifier.classify(any(ToolResult.class))).thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(toolName, "call-ckpt-2", arguments, LAST_MSG, SESSION);

        verify(ckptMgr).snapshot("before patch");
    }

    @Test
    @DisplayName("Should NOT checkpoint for read-only tools")
    void shouldNotCheckpointForReadFile() {
        com.azhukov.agent.service.CheckpointManager ckptMgr = org.mockito.Mockito.mock(com.azhukov.agent.service.CheckpointManager.class);
        service.setCheckpointManager(ckptMgr);
        String toolName = "read_file";
        String arguments = "{\"path\":\"/tmp/test.txt\"}";
        ToolResult okResult = ToolResult.ok("content");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION))).thenReturn(okResult);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(toolResultClassifier.classify(any(ToolResult.class))).thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(toolName, "call-ckpt-3", arguments, LAST_MSG, SESSION);

        verify(ckptMgr, org.mockito.Mockito.never()).snapshot(anyString());
    }

    @Test
    @DisplayName("Should checkpoint before destructive terminal command")
    void shouldCheckpointForDestructiveTerminal() {
        com.azhukov.agent.service.CheckpointManager ckptMgr = org.mockito.Mockito.mock(com.azhukov.agent.service.CheckpointManager.class);
        service.setCheckpointManager(ckptMgr);
        String toolName = "terminal";
        String arguments = "{\"command\":\"rm -rf /tmp/test\"}";
        ToolResult okResult = ToolResult.ok("done");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION))).thenReturn(okResult);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(toolResultClassifier.classify(any(ToolResult.class))).thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(toolName, "call-ckpt-4", arguments, LAST_MSG, SESSION);

        verify(ckptMgr).snapshot("before terminal");
    }

    @Test
    @DisplayName("Should NOT checkpoint for non-destructive terminal command")
    void shouldNotCheckpointForSafeTerminal() {
        com.azhukov.agent.service.CheckpointManager ckptMgr = org.mockito.Mockito.mock(com.azhukov.agent.service.CheckpointManager.class);
        service.setCheckpointManager(ckptMgr);
        String toolName = "terminal";
        String arguments = "{\"command\":\"ls -la /tmp\"}";
        ToolResult okResult = ToolResult.ok("output");

        when(guardrail.beforeCall(toolName, arguments)).thenReturn(GuardrailDecision.allow(toolName));
        when(guardrail.afterCall(eq(toolName), eq(arguments), any(ToolResult.class), anyBoolean()))
            .thenReturn(GuardrailDecision.allow(toolName));
        when(toolRegistry.execute(eq(toolName), anyString(), eq(arguments), eq(LAST_MSG), eq(SESSION))).thenReturn(okResult);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(toolResultClassifier.classify(any(ToolResult.class))).thenReturn(ToolResultClassifier.ResultType.SUCCESS);
        when(toolOutputLimiter.truncate(any(ToolResult.class), anyString())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(toolName, "call-ckpt-5", arguments, LAST_MSG, SESSION);

        verify(ckptMgr, org.mockito.Mockito.never()).snapshot(anyString());
    }

}
