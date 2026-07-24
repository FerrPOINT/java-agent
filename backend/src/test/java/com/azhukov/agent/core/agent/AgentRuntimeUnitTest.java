package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.DefaultIterationBudget;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.sanitizer.DefaultMessageSanitizer;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeUnitTest {

    @Test
    void executesToolAndReturnsFinalAnswer() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getDefinitions(any())).thenReturn(List.of());
        when(registry.getToolsets()).thenReturn(Set.of("core"));
        when(registry.execute("read_file", "call-1", "{\"path\":\"/tmp/x.txt\",\"offset\":1,\"limit\":10}", null, null))
            .thenReturn(com.azhukov.agent.core.model.ToolResult.ok("hello"));

        PromptBuilder promptBuilder = new DefaultPromptBuilder(new AgentProperties(), registry);
        ContextEngine contextEngine = mock(ContextEngine.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        SkillManager skillManager = mock(SkillManager.class);

        Session session = Session.create("user", "noop", "noop");
        when(contextEngine.prepareContext(session, List.of()))
            .thenReturn(List.of(Message.system("sys"), Message.user("hi")));
        when(contextEngine.prepareContext(any(), any()))
            .thenReturn(List.of(Message.system("sys"), Message.user("hi")));

        AgentProperties properties = new AgentProperties();
        properties.getSkills().getDefaultToolsets().clear();
        properties.getSkills().getDefaultToolsets().add("core");
        properties.getCore().setMaxTurns(10);

        MockModelClient model = new MockModelClient(
            List.of(new ToolCall("call-1", "read_file", "{\"path\":\"/tmp/x.txt\",\"offset\":1,\"limit\":10}")),
            "done"
        );

        ToolExecutionService toolExecutionService = new ToolExecutionService(registry, properties);

        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
            model, registry, toolExecutionService, promptBuilder, contextEngine, memoryProvider, skillManager,
            new DefaultIterationBudget(properties), new DefaultMessageSanitizer(), mockContextReferenceService(), properties
        );

        var result = runtime.runTurn(session, "hi");
        assertThat(result.messages()).hasSizeGreaterThan(2);
        assertThat(result.completed()).isTrue();
    }

    private static com.azhukov.agent.core.context.ContextReferenceService mockContextReferenceService() {
        var svc = mock(com.azhukov.agent.core.context.ContextReferenceService.class);
        when(svc.resolve(any())).thenReturn(List.of());
        when(svc.loadContent(any())).thenReturn(Optional.empty());
        return svc;
    }
}
