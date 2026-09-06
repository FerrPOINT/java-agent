package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.DefaultIterationBudget;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.FileMutationTracker;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.security.DefaultToolCallGuardrail;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.security.SecretRedactor;
import com.azhukov.agent.core.security.UserInputSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeUnitTest {

    @Test
    void executesToolAndReturnsFinalAnswer() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of(
            new ToolDefinition("read_file", "reads a file", java.util.Map.of())
        ));
        when(registry.getToolsets()).thenReturn(Set.of("core"));
        when(registry.execute(eq("read_file"), eq("call-1"), eq("{\"path\":\"/tmp/x.txt\",\"offset\":1,\"limit\":10}"), any(), any()))
            .thenReturn(ToolResult.ok("hello"));

        AgentProperties properties = makeProperties();
        var runtime = buildRuntime(registry, properties, new MockModelClient(
            List.of(new ToolCall("call-1", "read_file", "{\"path\":\"/tmp/x.txt\",\"offset\":1,\"limit\":10}")),
            "done"
        ));

        Session session = Session.create("user", "noop", "noop");
        var result = runtime.runTurn(session, "hi");
        assertThat(result.messages()).hasSizeGreaterThan(2);
        assertThat(result.completed()).isTrue();

        // Verify tool result content appears in the final messages
        boolean hasToolResult = result.messages().stream()
            .filter(m -> m.role() == Role.TOOL)
            .anyMatch(m -> m.content().contains("hello"));
        assertThat(hasToolResult).isTrue();

        // Verify the final assistant message contains the model's text answer
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.role()).isEqualTo(Role.ASSISTANT);
        assertThat(lastMsg.content()).isEqualTo("done");
    }

    @Test
    void directAnswerWithoutToolCallsCompletesImmediately() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of(
            new ToolDefinition("read_file", "reads a file", java.util.Map.of())
        ));
        when(registry.getToolsets()).thenReturn(Set.of("core"));

        AgentProperties properties = makeProperties();
        // Model returns a direct text answer with no tool calls
        var runtime = buildRuntime(registry, properties, new MockModelClient("direct answer"));

        Session session = Session.create("user", "noop", "noop");
        var result = runtime.runTurn(session, "hello");

        assertThat(result.completed()).isTrue();
        // Should have system message + user message + assistant answer = 3 messages
        assertThat(result.messages()).hasSize(3);

        // Last message should be the assistant's direct answer
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.role()).isEqualTo(Role.ASSISTANT);
        assertThat(lastMsg.content()).isEqualTo("direct answer");

        // No tool result messages should be present
        assertThat(result.messages().stream().noneMatch(m -> m.role() == Role.TOOL)).isTrue();
    }

    @Test
    void runTurnUsesPersistedSessionSystemPromptAsOverride() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of());
        when(registry.getToolsets()).thenReturn(Set.of("core"));

        AgentProperties properties = makeProperties();
        var runtime = buildRuntime(registry, properties, new MockModelClient("direct answer"));
        Session session = new Session(
            UUID.randomUUID(), "user", "title", "noop", "noop",
            "Persisted session instruction.", java.util.Map.of(), null);

        var result = runtime.runTurn(session, "hello");

        assertThat(result.messages().get(0).role()).isIn(Role.SYSTEM, Role.DEVELOPER);
        assertThat(result.messages().get(0).content()).contains("Persisted session instruction.");
    }

    @Test
    void verifyOnStopNudgeDoesNotDuplicateAssistantResponse() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of(
            new ToolDefinition("write_file", "writes a file", java.util.Map.of())
        ));
        when(registry.getToolsets()).thenReturn(Set.of("core"));

        FileMutationTracker tracker = new FileMutationTracker();
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        when(toolExecutionService.getFileMutationTracker()).thenReturn(tracker);
        when(toolExecutionService.execute(eq("write_file"), eq("call-1"),
            eq("{\"path\":\"src/Main.java\",\"content\":\"class Main {}\"}"), any(), any(), any()))
            .thenAnswer(inv -> {
                tracker.recordMutation("write_file", inv.getArgument(2),
                    "{\"resolved_path\":\"src/Main.java\"}", true);
                return ToolResult.ok("Wrote src/Main.java");
            });

        AgentProperties properties = makeProperties();
        properties.getVerifyOnStop().setEnabled(true);
        properties.getCore().setMaxTurns(5);

        MockModelClient model = new MockModelClient(List.of(
            ChatResponse.toolCalls(List.of(new ToolCall("call-1", "write_file",
                "{\"path\":\"src/Main.java\",\"content\":\"class Main {}\"}"))),
            ChatResponse.text("done before verification"),
            ChatResponse.text("verified after tests")
        ));

        DefaultAgentRuntime runtime = buildRuntime(registry, properties, model, toolExecutionService);
        ReflectionTestUtils.setField(runtime, "verifyOnStopGuard", new VerifyOnStopGuard() {
            @Override
            public int getMaxNudgeAttempts() {
                return 1;
            }
        });

        TurnResult result = runtime.runTurn(Session.create("user", "noop", "noop"), "edit code");

        List<String> assistantContents = result.messages().stream()
            .filter(m -> m.role() == Role.ASSISTANT)
            .map(Message::content)
            .toList();
        assertThat(Collections.frequency(assistantContents, "done before verification")).isEqualTo(1);
        assertThat(result.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo(Role.USER);
            assertThat(m.content()).contains("fresh passing verification evidence");
        });
        assertThat(result.messages().get(result.messages().size() - 1).content())
            .isEqualTo("verified after tests");
    }

    @Test
    void usesRotatedSessionForToolExecutionAfterContextPreparation() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of(
            new ToolDefinition("read_file", "reads a file", java.util.Map.of())
        ));
        when(registry.getToolsets()).thenReturn(Set.of("core"));

        AgentProperties properties = makeProperties();
        Session childSession = new Session(
            UUID.randomUUID(), "user", "child", "noop", "noop", "", java.util.Map.of(), null);
        ContextEngine rotatingContextEngine = new DefaultContextEngine(
            mock(MemoryProvider.class), mock(SkillManager.class), mock(com.azhukov.agent.core.ports.MessageStorePort.class),
            mock(ContextCompressor.class), properties) {
            @Override
            public List<Message> prepareContext(Session session, List<Message> messages) {
                return messages;
            }

            @Override
            public Optional<Session> resolveRotatedSession(Session session) {
                return Optional.of(childSession);
            }
        };

        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        AtomicReference<UUID> toolSessionId = new AtomicReference<>();
        when(toolExecutionService.execute(eq("read_file"), eq("call-1"), eq("{}"), any(), any(), any()))
            .thenAnswer(inv -> {
                Session executionSession = inv.getArgument(4);
                toolSessionId.set(executionSession.id());
                return ToolResult.ok("content");
            });

        DefaultAgentRuntime runtime = buildRuntime(
            registry, properties,
            new MockModelClient(List.of(new ToolCall("call-1", "read_file", "{}")), "done"),
            toolExecutionService,
            rotatingContextEngine
        );

        runtime.runTurn(Session.create("user", "noop", "noop"), "read");

        assertThat(toolSessionId.get()).isEqualTo(childSession.id());
    }

    @Test
    void toolExecutionFailureReturnsErrorMessageInToolResult() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of(
            new ToolDefinition("failing_tool", "a failing tool", java.util.Map.of())
        ));
        when(registry.getToolsets()).thenReturn(Set.of("core"));
        // Tool fails on all retry attempts
        when(registry.execute(eq("failing_tool"), eq("call-1"), eq("{}"), any(), any()))
            .thenThrow(new RuntimeException("tool crashed"));

        AgentProperties properties = makeProperties();
        var runtime = buildRuntime(registry, properties, new MockModelClient(
            List.of(new ToolCall("call-1", "failing_tool", "{}")),
            "recovered after error"
        ));

        Session session = Session.create("user", "noop", "noop");
        var result = runtime.runTurn(session, "use failing tool");

        assertThat(result.completed()).isTrue();
        // The tool result message should contain the error text
        boolean hasErrorInToolResult = result.messages().stream()
            .filter(m -> m.role() == Role.TOOL)
            .anyMatch(m -> m.content().contains("Error:") || m.content().contains("failed") || m.content().contains("crashed"));
        assertThat(hasErrorInToolResult).isTrue();

        // Final assistant message should still be the recovery text
        Message lastMsg = result.messages().get(result.messages().size() - 1);
        assertThat(lastMsg.role()).isEqualTo(Role.ASSISTANT);
        assertThat(lastMsg.content()).isEqualTo("recovered after error");
    }

    @Test
    void modelCallThrowsReturnsErrorTurnResult() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of(
            new ToolDefinition("read_file", "reads a file", java.util.Map.of())
        ));
        when(registry.getToolsets()).thenReturn(Set.of("core"));

        // Model client that always throws
        com.azhukov.agent.core.client.ModelClient throwingModel = mock(com.azhukov.agent.core.client.ModelClient.class);
        when(throwingModel.complete(any(), any())).thenThrow(new RuntimeException("model unavailable"));

        AgentProperties properties = makeProperties();
        PromptBuilder promptBuilder = new DefaultPromptBuilder(properties, registry);
        ContextEngine contextEngine = mock(ContextEngine.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        SkillManager skillManager = mock(SkillManager.class);

        Session session = Session.create("user", "noop", "noop");
        when(contextEngine.prepareContext(any(), any()))
            .thenReturn(List.of(Message.system("sys"), Message.user("hi")));

        ToolExecutionService toolExecutionService = new ToolExecutionService(registry, properties,
            new DefaultToolCallGuardrail(properties), new SecretRedactor(properties),
            new com.azhukov.agent.core.tool.ToolResultClassifier(),
            new com.azhukov.agent.core.tool.ToolOutputLimiter(properties), null);

        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
            throwingModel, registry, toolExecutionService, promptBuilder, contextEngine, memoryProvider, skillManager,
            new DefaultIterationBudget(properties),
            new MessageSanitizer(new SecretRedactor(properties)),
            mockContextReferenceService(), properties, new UserInputSanitizer(),
            new DefaultToolCallGuardrail(properties), new TurnStateManager(), null, null, null, new SteerBuffer(),
            new ErrorClassifier(), null, new com.azhukov.agent.core.security.ApprovalQueue(), null, null,
            new TokenEstimator(), new ToolResultFormatter(), null, null
        );

        var result = runtime.runTurn(session, "hi");
        assertThat(result.completed()).isFalse();
        // TurnResult.error returns a result with error message
        assertThat(result.error()).isNotNull();
        assertThat(result.error()).contains("Model call failed");
    }

    private AgentProperties makeProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getSkills().getDefaultToolsets().clear();
        properties.getSkills().getDefaultToolsets().add("core");
        properties.getCore().setMaxTurns(10);
        return properties;
    }

    private DefaultAgentRuntime buildRuntime(ToolRegistry registry, AgentProperties properties, MockModelClient model) {
        ToolExecutionService toolExecutionService = new ToolExecutionService(registry, properties,
            new DefaultToolCallGuardrail(properties), new SecretRedactor(properties),
            new com.azhukov.agent.core.tool.ToolResultClassifier(),
            new com.azhukov.agent.core.tool.ToolOutputLimiter(properties), null);
        return buildRuntime(registry, properties, model, toolExecutionService);
    }

    private DefaultAgentRuntime buildRuntime(ToolRegistry registry, AgentProperties properties, MockModelClient model,
                                             ToolExecutionService toolExecutionService) {
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.prepareContext(any(), any()))
            .thenAnswer(inv -> inv.getArgument(1));
        return buildRuntime(registry, properties, model, toolExecutionService, contextEngine);
    }

    private DefaultAgentRuntime buildRuntime(ToolRegistry registry, AgentProperties properties, MockModelClient model,
                                             ToolExecutionService toolExecutionService, ContextEngine contextEngine) {
        PromptBuilder promptBuilder = new DefaultPromptBuilder(properties, registry);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        SkillManager skillManager = mock(SkillManager.class);

        return new DefaultAgentRuntime(
            model, registry, toolExecutionService, promptBuilder, contextEngine, memoryProvider, skillManager,
            new DefaultIterationBudget(properties),
            new MessageSanitizer(new SecretRedactor(properties)),
            mockContextReferenceService(), properties, new UserInputSanitizer(),
            new DefaultToolCallGuardrail(properties), new TurnStateManager(), null, null, null, new SteerBuffer(),
            new ErrorClassifier(), null, new com.azhukov.agent.core.security.ApprovalQueue(), null, null,
            new TokenEstimator(), new ToolResultFormatter(), null, null
        );
    }

    private static com.azhukov.agent.core.context.ContextReferenceService mockContextReferenceService() {
        var svc = mock(com.azhukov.agent.core.context.ContextReferenceService.class);
        when(svc.resolve(any())).thenReturn(List.of());
        when(svc.loadContent(any())).thenReturn(Optional.empty());
        return svc;
    }
}
