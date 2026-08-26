package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.DefaultIterationBudget;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.security.DefaultToolCallGuardrail;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.security.SecretRedactor;
import com.azhukov.agent.core.security.UserInputSanitizer;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * c2: sync-path recovery parity — the non-streaming runtime (REST /agent/chat +
 * CLI) must run the SAME Hermes recovery policies as the streaming loop:
 * LENGTH continuation with stitched partial, dropped-toolcall re-prompt,
 * ceiling-kept stitched partial, STOP never triggers recovery.
 */
class SyncRecoveryParityTest {

    private AgentProperties makeProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getSkills().getDefaultToolsets().clear();
        properties.getSkills().getDefaultToolsets().add("core");
        properties.getCore().setMaxTurns(10);
        properties.getCore().setEmptyBackoffBaseMs(1L);
        properties.getCore().setEmptyBackoffCapMs(2L);
        return properties;
    }

    private DefaultAgentRuntime buildRuntime(AtomicInteger calls, BiFunction<Integer, Integer, ChatResponse> responder) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of());
        when(registry.getDefinitions(any(Set.class))).thenReturn(List.of());
        when(registry.getToolsets()).thenReturn(Set.of("core"));

        AgentProperties properties = makeProperties();
        PromptBuilder promptBuilder = new DefaultPromptBuilder(properties, registry);
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.prepareContext(any(), any()))
            .thenReturn(List.of(Message.system("sys"), Message.user("hi")));

        java.util.concurrent.atomic.AtomicInteger lastPromptSize = new java.util.concurrent.atomic.AtomicInteger(0);
        com.azhukov.agent.core.client.ModelClient model = (msgs, tools, opts) -> {
            int n = calls.incrementAndGet();
            lastPromptSize.set(msgs.size());
            return responder.apply(n, calls.get());
        };

        ToolExecutionService toolExecutionService = new ToolExecutionService(registry, properties,
            new DefaultToolCallGuardrail(properties), new SecretRedactor(properties),
            new com.azhukov.agent.core.tool.ToolResultClassifier(),
            new com.azhukov.agent.core.tool.ToolOutputLimiter(properties), null);

        var svc = mock(com.azhukov.agent.core.context.ContextReferenceService.class);
        when(svc.resolve(any())).thenReturn(List.of());
        when(svc.loadContent(any())).thenReturn(Optional.empty());

        return new DefaultAgentRuntime(
            model, registry, toolExecutionService, promptBuilder, contextEngine,
            mock(MemoryProvider.class), mock(SkillManager.class),
            new DefaultIterationBudget(properties),
            new MessageSanitizer(new SecretRedactor(properties)), svc, properties,
            new UserInputSanitizer(), new DefaultToolCallGuardrail(properties),
            new TurnStateManager(), null, null, null, new SteerBuffer(),
            new ErrorClassifier(), null, new com.azhukov.agent.core.security.ApprovalQueue(), null, null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);
    }

    private String lastAssistant(TurnResult result) {
        return result.messages().stream()
            .filter(m -> m.role() == Role.ASSISTANT)
            .reduce((a, b) -> b)
            .map(Message::content)
            .orElse("");
    }

    @Test
    void lengthTruncationStitchesPartialAndContinues() {
        AtomicInteger calls = new AtomicInteger();
        var runtime = buildRuntime(calls, (n, total) ->
            n == 1 ? ChatResponse.text("abc", "LENGTH") : ChatResponse.text("def", "STOP"));

        TurnResult result = runtime.runTurn(Session.create("user", "noop", "noop"), "hi");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(lastAssistant(result)).isEqualTo("abcdef");
        // Hermes parity: the stitched partial AND the continuation nudge must reach
        // the second model call (turn transcript grows by 2 rows), not a throwaway
        // context that repeats the original request.
        assertThat(result.messages().stream()
            .filter(m -> m.role() == Role.USER
                && m.content() != null
                && m.content().contains("Continue exactly where you left off"))
            .count()).isEqualTo(1);
        assertThat(result.messages().stream()
            .filter(m -> m.role() == Role.ASSISTANT && "abc".equals(m.content()))
            .count()).isEqualTo(1);
    }

    @Test
    void lengthCeilingKeepsStitchedPartial() {
        AtomicInteger calls = new AtomicInteger();
        var runtime = buildRuntime(calls, (n, total) ->
            ChatResponse.text("f" + n, "LENGTH"));

        TurnResult result = runtime.runTurn(Session.create("user", "noop", "noop"), "hi");

        // 1 original + 4 continuations = 5 model calls, then stitched partial kept
        assertThat(calls.get()).isEqualTo(5);
        assertThat(lastAssistant(result)).isEqualTo("f1f2f3f4f5");
    }

    @Test
    void droppedToolcallIsRePrompted() {
        AtomicInteger calls = new AtomicInteger();
        var runtime = buildRuntime(calls, (n, total) ->
            n == 1 ? new ChatResponse("I will now call the tool.", List.<ToolCall>of(), "TOOL_EXECUTION")
                   : ChatResponse.text("done", "STOP"));

        TurnResult result = runtime.runTurn(Session.create("user", "noop", "noop"), "hi");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(lastAssistant(result)).isEqualTo("done");
    }

    @Test
    void stopFinishReasonNeverTriggersRecovery() {
        AtomicInteger calls = new AtomicInteger();
        var runtime = buildRuntime(calls, (n, total) -> ChatResponse.text("final", "STOP"));

        runtime.runTurn(Session.create("user", "noop", "noop"), "hi");

        assertThat(calls.get()).isEqualTo(1);
    }
}
