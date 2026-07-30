package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.security.MessageSanitizer;
import com.azhukov.agent.security.ToolCallGuardrail;
import com.azhukov.agent.security.UserInputSanitizer;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full scenario tests for {@link DefaultAgentRuntime}.
 * All dependencies are mocked; no Spring context is used.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRuntimeFullScenariosTest {

    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private ContextEngine contextEngine;
    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private SkillManager skillManager;
    @Mock
    private IterationBudget iterationBudget;
    @Mock
    private MessageSanitizer messageSanitizer;
    @Mock
    private ContextReferenceService contextReferenceService;
    @Mock
    private UserInputSanitizer inputSanitizer;
    @Mock
    private ToolCallGuardrail guardrail;

    private AgentProperties properties;
    private Session session;
    private DefaultAgentRuntime runtime;
    private TurnStateManager turnStateManager;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setMaxTurns(10);
        properties.getBudget().setMaxModelCallsPerTurn(5);
        properties.getBudget().setMaxToolExecutionsPerTurn(20);
        properties.getBudget().setMaxTokensPerTurn(200000);
        properties.getBudget().setMaxToolDurationMsPerTurn(600000);
        properties.getBudget().setEnabled(true);

        session = Session.create("test-user", "openai-compatible", "gpt-4");
        turnStateManager = new TurnStateManager();

        runtime = new DefaultAgentRuntime(
            null,
            toolRegistry,
            toolExecutionService,
            promptBuilder,
            contextEngine,
            memoryProvider,
            skillManager,
            iterationBudget,
            messageSanitizer,
            contextReferenceService,
            properties,
            inputSanitizer,
            guardrail,
            turnStateManager,
            null,
            null,
            null,
            new SteerBuffer(),
            new ErrorClassifier(),
            null,
            new com.azhukov.agent.core.security.ApprovalQueue()
        );
    }

    private void useModelClient(ModelClient modelClient) {
        runtime = new DefaultAgentRuntime(
            modelClient,
            toolRegistry,
            toolExecutionService,
            promptBuilder,
            contextEngine,
            memoryProvider,
            skillManager,
            iterationBudget,
            messageSanitizer,
            contextReferenceService,
            properties,
            inputSanitizer,
            guardrail,
            turnStateManager,
            null,
            null,
            null,
            new SteerBuffer(),
            new ErrorClassifier(),
            null,
            new com.azhukov.agent.core.security.ApprovalQueue()
        );
    }

    private void stubRunTurnMocks() {
        when(promptBuilder.buildSystemMessage(any())).thenReturn(Message.system("system prompt"));
        when(contextEngine.prepareContext(eq(session), anyList())).thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(any())).thenReturn(List.of(
            new ToolDefinition("read_file", "reads a file", Map.of())
        ));
    }

    private IterationBudget.TurnSnapshot freshBudget() {
        return new IterationBudget.TurnSnapshot(
            session.id(), java.time.Instant.now(), 1, 0, 0, 0, 0, 0L, false, null
        );
    }

    private IterationBudget.TurnSnapshot afterModelCall(IterationBudget.TurnSnapshot current) {
        return new IterationBudget.TurnSnapshot(
            current.sessionId(), current.startedAt(), current.turnIndex(),
            current.modelCalls() + 1, current.toolExecutions(),
            current.totalInputTokens(), current.totalOutputTokens(),
            current.totalToolDurationMs(), false, null
        );
    }

    private IterationBudget.TurnSnapshot afterToolExecution(IterationBudget.TurnSnapshot current) {
        return new IterationBudget.TurnSnapshot(
            current.sessionId(), current.startedAt(), current.turnIndex(),
            current.modelCalls(), current.toolExecutions() + 1,
            current.totalInputTokens(), current.totalOutputTokens(),
            current.totalToolDurationMs(), false, null
        );
    }

    @Test
    void runTurnReturnsFinalTextWithoutToolCalls() {
        stubRunTurnMocks();
        IterationBudget.TurnSnapshot budget = freshBudget();
        when(iterationBudget.startTurn(session.id())).thenReturn(budget);
        when(iterationBudget.isExhausted(budget)).thenReturn(false);
        when(iterationBudget.recordModelCall(eq(budget), anyInt(), anyInt())).thenReturn(afterModelCall(budget));

        useModelClient(new MockModelClient("final answer"));

        TurnResult result = runtime.runTurn(session, "hello");

        assertThat(result.completed()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.messages()).hasSize(3);
        assertThat(result.messages().get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.messages().get(1).role()).isEqualTo(Role.USER);
        assertThat(result.messages().get(2).role()).isEqualTo(Role.ASSISTANT);
        assertThat(result.messages().get(2).content()).isEqualTo("final answer");
        assertThat(result.finalText()).isEqualTo("final answer");
    }

    @Test
    void runTurnExecutesOneToolCallAndReturnsFinalText() {
        stubRunTurnMocks();
        IterationBudget.TurnSnapshot budget = freshBudget();
        IterationBudget.TurnSnapshot afterCall = afterModelCall(budget);
        IterationBudget.TurnSnapshot afterTool = afterToolExecution(afterCall);
        IterationBudget.TurnSnapshot afterFinalCall = afterModelCall(afterTool);

        when(iterationBudget.startTurn(session.id())).thenReturn(budget);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(eq(budget), anyInt(), anyInt())).thenReturn(afterCall);
        when(iterationBudget.recordToolExecution(eq(afterCall), anyString(), anyLong())).thenReturn(afterTool);
        when(iterationBudget.recordModelCall(eq(afterTool), anyInt(), anyInt())).thenReturn(afterFinalCall);

        ToolCall toolCall = new ToolCall("call-1", "read_file", "{\"path\":\"/tmp/file.txt\"}");
        when(toolExecutionService.execute(eq("read_file"), eq("call-1"), eq("{\"path\":\"/tmp/file.txt\"}"), eq(null), eq(session), any(com.azhukov.agent.core.state.TurnState.class)))
            .thenReturn(ToolResult.ok("file content"));

        useModelClient(new MockModelClient(List.of(toolCall), "done"));

        TurnResult result = runtime.runTurn(session, "read the file");

        assertThat(result.completed()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.finalText()).isEqualTo("done");
        assertThat(result.messages()).hasSize(5); // system, user, assistant tool-calls, tool result, assistant final

        verify(toolExecutionService).execute(eq("read_file"), eq("call-1"), eq("{\"path\":\"/tmp/file.txt\"}"), eq(null), eq(session), any(com.azhukov.agent.core.state.TurnState.class));
    }

    @Test
    void runTurnExecutesMultipleSequentialToolCalls() {
        stubRunTurnMocks();
        IterationBudget.TurnSnapshot budget = freshBudget();
        IterationBudget.TurnSnapshot s1 = afterModelCall(budget);
        IterationBudget.TurnSnapshot s2 = afterToolExecution(s1);
        IterationBudget.TurnSnapshot s3 = afterToolExecution(s2);
        IterationBudget.TurnSnapshot s4 = afterModelCall(s3);

        when(iterationBudget.startTurn(session.id())).thenReturn(budget);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(eq(budget), anyInt(), anyInt())).thenReturn(s1);
        when(iterationBudget.recordToolExecution(eq(s1), eq("first"), anyLong())).thenReturn(s2);
        when(iterationBudget.recordToolExecution(eq(s2), eq("second"), anyLong())).thenReturn(s3);
        when(iterationBudget.recordModelCall(eq(s3), anyInt(), anyInt())).thenReturn(s4);

        ToolCall call1 = new ToolCall("c1", "first", "{}");
        ToolCall call2 = new ToolCall("c2", "second", "{}");
        when(toolExecutionService.execute(eq("first"), eq("c1"), eq("{}"), eq(null), eq(session), any(com.azhukov.agent.core.state.TurnState.class))).thenReturn(ToolResult.ok("one"));
        when(toolExecutionService.execute(eq("second"), eq("c2"), eq("{}"), eq(null), eq(session), any(com.azhukov.agent.core.state.TurnState.class))).thenReturn(ToolResult.ok("two"));

        useModelClient(new MockModelClient(
            List.of(ChatResponse.toolCalls(List.of(call1, call2)), ChatResponse.text("finished"))
        ));

        TurnResult result = runtime.runTurn(session, "run two tools");

        assertThat(result.completed()).isTrue();
        assertThat(result.finalText()).isEqualTo("finished");
        assertThat(result.messages()).hasSize(6); // system, user, assistant(2 calls), tool1, tool2, assistant final
        assertThat(result.messages().get(3).role()).isEqualTo(Role.TOOL);
        assertThat(result.messages().get(4).role()).isEqualTo(Role.TOOL);

        verify(toolExecutionService).execute(eq("first"), eq("c1"), eq("{}"), eq(null), eq(session), any(com.azhukov.agent.core.state.TurnState.class));
        verify(toolExecutionService).execute(eq("second"), eq("c2"), eq("{}"), eq(null), eq(session), any(com.azhukov.agent.core.state.TurnState.class));
    }

    @Test
    void runTurnMaxTurnsReachedReturnsError() {
        stubRunTurnMocks();
        properties.getCore().setMaxTurns(2);
        IterationBudget.TurnSnapshot budget = freshBudget();
        IterationBudget.TurnSnapshot afterFirst = afterModelCall(budget);
        IterationBudget.TurnSnapshot afterTool = afterToolExecution(afterFirst);

        when(iterationBudget.startTurn(session.id())).thenReturn(budget);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(eq(budget), anyInt(), anyInt())).thenReturn(afterFirst);
        when(iterationBudget.recordToolExecution(eq(afterFirst), anyString(), anyLong())).thenReturn(afterTool);
        when(iterationBudget.recordModelCall(eq(afterTool), anyInt(), anyInt())).thenReturn(afterModelCall(afterTool));

        ToolCall toolCall = new ToolCall("c1", "tool", "{}");
        when(toolExecutionService.execute(eq("tool"), eq("c1"), eq("{}"), eq(null), eq(session), any(com.azhukov.agent.core.state.TurnState.class))).thenReturn(ToolResult.ok("x"));

        // First model response: tool call. Second model response: another tool call.
        // With maxTurns=2 the loop ends and returns the max-turns error.
        useModelClient(new MockModelClient(
            List.of(
                ChatResponse.toolCalls(List.of(toolCall)),
                ChatResponse.toolCalls(List.of(toolCall))
            )
        ));

        TurnResult result = runtime.runTurn(session, "loop");

        assertThat(result.completed()).isFalse();
        assertThat(result.error()).isEqualTo("Reached max turns without completion");
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void runTurnIterationBudgetExhaustedStopsLoop() {
        stubRunTurnMocks();
        properties.getCore().setMaxTurns(10);
        IterationBudget.TurnSnapshot budget = freshBudget();
        IterationBudget.TurnSnapshot afterCall = afterModelCall(budget);
        IterationBudget.TurnSnapshot afterTool = afterToolExecution(afterCall);

        when(iterationBudget.startTurn(session.id())).thenReturn(budget);
        when(iterationBudget.isExhausted(budget)).thenReturn(false);
        when(iterationBudget.isExhausted(afterTool)).thenReturn(true);
        when(iterationBudget.recordModelCall(eq(budget), anyInt(), anyInt())).thenReturn(afterCall);
        when(iterationBudget.recordToolExecution(eq(afterCall), anyString(), anyLong())).thenReturn(afterTool);

        ToolCall toolCall = new ToolCall("c1", "tool", "{}");
        useModelClient(new MockModelClient(
            List.of(ChatResponse.toolCalls(List.of(toolCall)))
        ));
        when(toolExecutionService.execute(eq("tool"), eq("c1"), eq("{}"), eq(null), eq(session), any(com.azhukov.agent.core.state.TurnState.class))).thenReturn(ToolResult.ok("ok"));

        TurnResult result = runtime.runTurn(session, "budget test");

        assertThat(result.completed()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.finalText()).isEqualTo("Iteration budget exhausted. Stopping to avoid runaway loop.");
        assertThat(result.messages()).hasSize(5); // system, user, assistant tool-calls, tool result, budget assistant

        assertThat(result.messages().get(result.messages().size() - 1).content())
            .isEqualTo("Iteration budget exhausted. Stopping to avoid runaway loop.");
    }

    @Test
    void runTurnModelFailureReturnsErrorTurnResult() {
        stubRunTurnMocks();
        IterationBudget.TurnSnapshot budget = freshBudget();
        when(iterationBudget.startTurn(session.id())).thenReturn(budget);
        when(iterationBudget.isExhausted(budget)).thenReturn(false);

        ModelClient failingClient = (messages, tools) -> {
            throw new RuntimeException("provider outage");
        };
        useModelClient(failingClient);

        TurnResult result = runtime.runTurn(session, "hello");

        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        assertThat(result.error()).contains("provider outage");
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void runListSanitizesAndCallsModelClient() {
        Message rawUser = Message.user("raw");
        Message sanitizedUser = Message.user("sanitized");
        List<Message> rawMessages = List.of(rawUser);
        List<Message> sanitizedMessages = List.of(sanitizedUser);
        List<ToolDefinition> tools = List.of(new ToolDefinition("tool", "desc", Map.of()));

        when(messageSanitizer.sanitize(rawMessages)).thenReturn(sanitizedMessages);
        when(contextEngine.prepareContext(any(), eq(sanitizedMessages))).thenReturn(sanitizedMessages);

        ModelClient client = mock(ModelClient.class);
        when(client.complete(sanitizedMessages, tools)).thenReturn(ChatResponse.text("response"));
        useModelClient(client);

        ChatResponse response = runtime.run(rawMessages, tools);

        assertThat(response.content()).isEqualTo("response");
        verify(messageSanitizer).sanitize(rawMessages);
        verify(client).complete(sanitizedMessages, tools);
        verify(contextEngine).prepareContext(any(), eq(sanitizedMessages));
    }
}
