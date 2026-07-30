package com.azhukov.agent.tools.browser;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.DefaultAgentRuntime;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.budget.DefaultIterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.security.DefaultToolCallGuardrail;
import com.azhukov.agent.security.SecretRedactor;
import com.azhukov.agent.security.UserInputSanitizer;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("live")
class BrowserAgentRuntimeLiveTest {

    @Test
    void browserNavigateAndScreenshotViaAgentRuntime() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of());
        when(registry.getToolsets()).thenReturn(Set.of("browser"));
        when(registry.execute(any(), any(), any(), any(), any())).thenReturn(ToolResult.ok("mock"));
        when(registry.execute("browser_navigate", "call-1", "{\"url\":\"http://example.com\"}", null, null))
            .thenReturn(ToolResult.ok("Navigated to http://example.com (frameId=abc)"));
        when(registry.execute("browser_vision", "call-2", "{}", null, null))
            .thenReturn(ToolResult.ok("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAoMBgKkH2RAAAAAASUVORK5CYII="));
        when(registry.execute("browser_vision", "call-2", "null", null, null))
            .thenReturn(ToolResult.ok("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAoMBgKkH2RAAAAAASUVORK5CYII="));
        when(registry.execute("browser_vision", "call-2", "", null, null))
            .thenReturn(ToolResult.ok("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAoMBgKkH2RAAAAAASUVORK5CYII="));

        AgentProperties properties = new AgentProperties();
        properties.getSkills().getDefaultToolsets().clear();
        properties.getSkills().getDefaultToolsets().add("browser");
        properties.getCore().setMaxTurns(10);

        PromptBuilder promptBuilder = new DefaultPromptBuilder(properties, registry);
        ContextEngine contextEngine = mock(ContextEngine.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        SkillManager skillManager = mock(SkillManager.class);

        Session session = Session.create("user", "noop", "noop");
        when(contextEngine.prepareContext(any(), any()))
            .thenReturn(List.of(Message.system("sys"), Message.user("navigate and screenshot")));

        var model = new com.azhukov.agent.core.client.ModelClient() {
            private int calls = 0;
            @Override
            public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
                calls++;
                if (calls == 1) {
                    return ChatResponse.toolCalls(List.of(new ToolCall("call-1", "browser_navigate", "{\"url\":\"http://example.com\"}")));
                }
                if (calls == 2) {
                    return ChatResponse.toolCalls(List.of(new ToolCall("call-2", "browser_vision", "{}")));
                }
                return ChatResponse.text("done");
            }
            @Override
            public String analyzeImage(String base64Image, String prompt) {
                return new NoOpModelClient().analyzeImage(base64Image, prompt);
            }
        };

        ToolExecutionService toolExecutionService = new ToolExecutionService(registry, properties,
            new DefaultToolCallGuardrail(properties), new SecretRedactor(properties),
            new com.azhukov.agent.core.tool.ToolResultClassifier(),
            new com.azhukov.agent.core.tool.ToolOutputLimiter(properties));

        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
            model, registry, toolExecutionService, promptBuilder, contextEngine, memoryProvider, skillManager,
            new DefaultIterationBudget(properties),
            new com.azhukov.agent.security.MessageSanitizer(new SecretRedactor(properties)),
            mockContextReferenceService(), properties, new UserInputSanitizer(),
            new DefaultToolCallGuardrail(properties), new TurnStateManager(), null, null, null, new SteerBuffer(),
            new ErrorClassifier()
        );

        var result = runtime.runTurn(session, "navigate and screenshot");
        assertThat(result.completed()).isTrue();
        assertThat(result.finalText()).contains("done");
    }

    private static com.azhukov.agent.core.context.ContextReferenceService mockContextReferenceService() {
        var svc = mock(com.azhukov.agent.core.context.ContextReferenceService.class);
        when(svc.resolve(any())).thenReturn(List.of());
        when(svc.loadContent(any())).thenReturn(Optional.empty());
        return svc;
    }
}
