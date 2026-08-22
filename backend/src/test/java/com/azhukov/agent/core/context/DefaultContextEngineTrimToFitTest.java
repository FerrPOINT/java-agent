package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M25: Test that trimToFit removes complete tool-call/tool-result pairs
 * together, not individual messages that would break pairs.
 */
@ExtendWith(MockitoExtension.class)
class DefaultContextEngineTrimToFitTest {

    @Mock
    private MemoryProvider memoryProvider;

    @Mock
    private SkillManager skillManager;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ContextCompressor contextCompressor;

    private AgentProperties properties;
    private DefaultContextEngine contextEngine;
    private Session session;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getContext().setMaxContextMessages(5);
        properties.getContext().setMaxTokens(100);
        properties.getContext().setTargetTokens(80);

        contextEngine = new DefaultContextEngine(
                memoryProvider,
                skillManager,
                messageRepository,
                contextCompressor,
                properties
        );

        session = Session.create("user-42", "openai-compatible", "gpt-4o-mini");
    }

    @Test
    void trimToFitRemovesToolCallAndResultTogether() {
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(Collections.emptyList());

        // Build context: system + user + assistant(toolCalls) + tool(result) + user
        // With maxContextMessages=5, we need to trim one message.
        // The trim should remove the tool call AND its result together (2 messages)
        // rather than removing just the tool call (leaving an orphaned result).
        List<Message> input = List.of(
            Message.system("system prompt"),
            Message.user("first question"),
            Message.assistantToolCalls(
                List.of(new ToolCall("call-1", "tool_a", "{}")), 1),
            Message.toolResult("call-1", "tool result", 1),
            Message.user("second question")
        );

        List<Message> result = contextEngine.prepareContext(session, input);

        // After trimming, we should NOT have an orphaned tool result without its tool call
        boolean hasToolResult = result.stream().anyMatch(m -> m.role() == Role.TOOL);
        boolean hasToolCall = result.stream().anyMatch(m -> m.toolCalls() != null && !m.toolCalls().isEmpty());

        // If there's a tool result, there must be a corresponding tool call
        if (hasToolResult) {
            assertThat(hasToolCall).as("Tool result must not be orphaned without tool call").isTrue();
        }
    }

    @Test
    void trimToFitWithMultipleToolCallsRemovesAllResults() {
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(Collections.emptyList());

        // Context with 2 tool calls and 2 results — trimming should remove pairs together
        properties.getContext().setMaxContextMessages(4);
        properties.getContext().setMaxTokens(50);
        properties.getContext().setTargetTokens(30);

        List<Message> input = List.of(
            Message.system("sys"),
            Message.assistantToolCalls(
                List.of(new ToolCall("c1", "t1", "{}"), new ToolCall("c2", "t2", "{}")), 1),
            Message.toolResult("c1", "r1", 1),
            Message.toolResult("c2", "r2", 1),
            Message.user("latest question")
        );

        List<Message> result = contextEngine.prepareContext(session, input);

        // Count remaining TOOL messages
        long toolResults = result.stream().filter(m -> m.role() == Role.TOOL).count();
        long toolCalls = result.stream().filter(m -> m.toolCalls() != null && !m.toolCalls().isEmpty()).count();

        // Either both tool calls and results are present, or neither
        if (toolResults > 0) {
            assertThat(toolCalls).as("Tool results should not be orphaned").isGreaterThan(0);
        }
    }
}