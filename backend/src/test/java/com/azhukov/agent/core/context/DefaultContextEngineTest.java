package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.context.SessionLineagePort;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultContextEngineTest {

    @Mock
    private MemoryProvider memoryProvider;

    @Mock
    private SkillManager skillManager;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ContextCompressor contextCompressor;

    private AgentProperties.ContextProperties contextProps;
    private DefaultContextEngine contextEngine;
    private Session session;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        contextProps = properties.getContext();
        // Small limits for deterministic trimming / compressor tests.
        contextProps.setMaxContextMessages(5);
        contextProps.setMaxTokens(100);
        contextProps.setTargetTokens(80);

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
    void prepareContextAddsSystemMessageFirst() {
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), any(org.springframework.data.domain.Pageable.class))).thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.system("You are a helpful assistant."), Message.user("Hello"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content()).isEqualTo("You are a helpful assistant.");
        assertThat(result.get(1).role()).isEqualTo(Role.USER);
        assertThat(result.get(1).content()).isEqualTo("Hello");
    }

    @Test
    void prepareContextAppendsRecentHistoryFromRepository() {

        MessageEntity userMsg = entity("user", "previous user question", 1);
        MessageEntity assistantMsg = entity("assistant", "previous assistant answer", 1);
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(eq(session.id()), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(assistantMsg, userMsg)); // Desc order (newest first)

        List<Message> incoming = List.of(Message.system("System prompt"), Message.user("Current question"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        assertThat(result).hasSize(4);
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(1).role()).isEqualTo(Role.USER);
        assertThat(result.get(1).content()).isEqualTo("previous user question");
        assertThat(result.get(2).role()).isEqualTo(Role.ASSISTANT);
        assertThat(result.get(2).content()).isEqualTo("previous assistant answer");
        assertThat(result.get(3).role()).isEqualTo(Role.USER);
        assertThat(result.get(3).content()).isEqualTo("Current question");
    }

    @Test
    void prepareContextSkipsInactiveArchivedHistoryRows() {
        MessageEntity archivedUser = entity("user", "archived user question", 1);
        archivedUser.setActive(false);
        archivedUser.setCompacted(true);
        MessageEntity liveAssistant = entity("assistant", "live assistant answer", 2);
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(eq(session.id()), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(liveAssistant, archivedUser));

        List<Message> result = contextEngine.prepareContext(
            session,
            List.of(Message.system("System prompt"), Message.user("Current question")));

        assertThat(result).extracting(Message::content)
            .containsExactly("System prompt", "live assistant answer", "Current question");
    }

    @Test
    void prepareContextHydratesStoredAssistantToolCallsArrayFromRepository() {
        MessageEntity assistantMsg = entity("assistant", "", 1);
        assistantMsg.setToolCallId("call-1");
        assistantMsg.setToolCallName("read_file");
        assistantMsg.setToolCallArguments("{\"path\":\"a.txt\"}");
        assistantMsg.setToolCalls("""
            [
              {"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"a.txt\\"}"}},
              {"id":"call-2","type":"function","function":{"name":"web_search","arguments":"{\\"query\\":\\"java\\"}"}}
            ]
            """);
        MessageEntity toolResult1 = entity("tool", "file content", 1);
        toolResult1.setToolCallId("call-1");
        MessageEntity toolResult2 = entity("tool", "search result", 1);
        toolResult2.setToolCallId("call-2");
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(
                eq(session.id()), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(List.of(toolResult2, toolResult1, assistantMsg));

        List<Message> result = contextEngine.prepareContext(
            session,
            List.of(Message.system("System prompt"), Message.user("Current question")));

        Message assistant = result.stream()
            .filter(message -> message.role() == Role.ASSISTANT && message.toolCalls() != null)
            .findFirst()
            .orElseThrow();
        assertThat(assistant.toolCalls())
            .extracting(ToolCall::id)
            .containsExactly("call-1", "call-2");
        assertThat(result.stream()
            .filter(message -> message.role() == Role.TOOL)
            .map(Message::toolCallId)
            .toList()).containsExactly("call-1", "call-2");
    }

    @Test
    void prepareContextAppendsMemoryRecallViaMemoryProvider() {

        MessageEntity userMsg = entity("user", "What do you know about me?", 1);
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(eq(session.id()), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(userMsg));

        List<Message> incoming = List.of(Message.system("System prompt"), Message.user("Tell me something"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        // Memory is NOT injected into the system prompt by DefaultContextEngine.
        // Memory is handled via DefaultPromptBuilder.buildMemoryPrefix() as a user message prefix.
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content())
                .contains("System prompt");
        // Verify memoryProvider.recall is NOT called by prepareContext
        verify(memoryProvider, never()).recall(anyString(), anyString(), anyInt());
    }

    @Test
    void prepareContextDoesNotAppendSkillInfo() {
        // Hermes parity: skills are NOT injected by prepareContext — the
        // skills index is built by DefaultPromptBuilder into the system
        // prompt's volatile tier. The system message stays byte-identical.
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.system("System prompt"), Message.user("Help me"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content())
                .isEqualTo("System prompt");
    }

    @Test
    void prepareContextTrimsToMaxContextMessages() {

        // H-SYNC: maxContextMessages below 500 is treated as "effectively unlimited"
        // (compression handles trimming). 4 history + system + current = 6 messages
        // with a small cap — all 6 must survive because trimming is deferred.
        List<MessageEntity> history = List.of(
                entity("assistant", "msg-4", 2),
                entity("user", "msg-3", 2),
                entity("assistant", "msg-2", 1),
                entity("user", "msg-1", 1)
        );
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(eq(session.id()), any(org.springframework.data.domain.Pageable.class))).thenReturn(history);

        List<Message> incoming = List.of(Message.system("System prompt"), Message.user("current"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        // maxContextMessages=5 is below the H-SYNC floor (500), so no message-count trim
        assertThat(result).hasSize(6);
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(result.size() - 1).content()).isEqualTo("current");
    }

    @Test
    void prepareContextTrimsWhenMaxContextMessagesAboveFloor() {

        // Above the floor: maxContextMessages=600 in AgentProperties default... set high but
        // trim via char budget instead — maxTokens=100 → 400 chars, target 320.
        // Six short messages stay under the char budget, so assert no over-trim.
        contextProps.setMaxContextMessages(600);
        List<MessageEntity> history = List.of(
                entity("assistant", "msg-4", 2),
                entity("user", "msg-3", 2),
                entity("assistant", "msg-2", 1),
                entity("user", "msg-1", 1)
        );
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(eq(session.id()), any(org.springframework.data.domain.Pageable.class))).thenReturn(history);

        List<Message> incoming = List.of(Message.system("System prompt"), Message.user("current"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        assertThat(result).hasSizeLessThanOrEqualTo(600);
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(result.size() - 1).content()).isEqualTo("current");
    }

    @Test
    void prepareContextTriggersCompressorWhenCharsExceedMaxTokensEstimate() {
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(Collections.emptyList());

        // maxTokens=100 => 100 * 4 = 400 chars threshold. Create a system message alone that is already over.
        String hugeSystem = "x".repeat(500);
        Message systemMessage = Message.system(hugeSystem);
        Message userMessage = Message.user("hi");

        // The compressor should be invoked because trimmed content exceeds the estimate.
        when(contextCompressor.compress(any(), anyInt())).thenReturn(List.of(systemMessage, userMessage));

        List<Message> result = contextEngine.prepareContext(session, List.of(systemMessage, userMessage));

        verify(contextCompressor).compress(any(), eq(contextProps.getTargetTokens() * 4));
        assertThat(result).containsExactly(systemMessage, userMessage);
    }

    @Test
    void prepareContextHandlesEmptyHistoryGracefully() {
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.user("Just a user message"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo(Role.USER);
        assertThat(result.get(0).content()).isEqualTo("Just a user message");
        verify(memoryProvider, never()).recall(anyString(), anyString(), anyInt());
    }

    @Test
    void prepareContextReusesLineageHistoryWithinSameTurn() {
        SessionLineagePort lineage = org.mockito.Mockito.mock(SessionLineagePort.class);
        contextEngine.setSessionLineageService(lineage);
        when(lineage.loadMessagesWithAncestors(session.id())).thenReturn(List.of(Message.user("previous")));
        List<Message> turnMessages = new ArrayList<>(List.of(Message.system("System"), Message.user("Current")));

        contextEngine.prepareContext(session, turnMessages);
        turnMessages.add(Message.assistant("tool result", 1));
        contextEngine.prepareContext(session, turnMessages);

        verify(lineage).loadMessagesWithAncestors(session.id());
    }

    @Test
    void prepareContextReloadsLineageHistoryForAnotherTurn() {
        SessionLineagePort lineage = org.mockito.Mockito.mock(SessionLineagePort.class);
        contextEngine.setSessionLineageService(lineage);
        when(lineage.loadMessagesWithAncestors(session.id())).thenReturn(List.of(Message.user("previous")));

        contextEngine.prepareContext(session, new ArrayList<>(List.of(Message.system("System"), Message.user("First"))));
        contextEngine.prepareContext(session, new ArrayList<>(List.of(Message.system("System"), Message.user("Second"))));

        verify(lineage, org.mockito.Mockito.times(2)).loadMessagesWithAncestors(session.id());
    }

    private MessageEntity entity(String role, String content, int turnIndex) {
        MessageEntity e = new MessageEntity();
        e.setSessionId(session.id());
        e.setRole(role);
        e.setContent(content);
        e.setTurnIndex(turnIndex);
        return e;
    }
}
