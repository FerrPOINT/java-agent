package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
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
        when(skillManager.listSkillNames()).thenReturn(Collections.emptyList());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class))).thenReturn(Collections.emptyList());

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
        when(skillManager.listSkillNames()).thenReturn(Collections.emptyList());

        MessageEntity userMsg = entity("user", "previous user question", 1);
        MessageEntity assistantMsg = entity("assistant", "previous assistant answer", 1);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.id()))
                .thenReturn(List.of(userMsg, assistantMsg));

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
    void prepareContextAppendsMemoryRecallViaMemoryProvider() {
        when(skillManager.listSkillNames()).thenReturn(Collections.emptyList());

        MessageEntity userMsg = entity("user", "What do you know about me?", 1);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.id()))
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
    void prepareContextAppendsSkillInfoViaSkillManager() {
        when(skillManager.listSkillNames()).thenReturn(List.of("coding", "web"));
        when(skillManager.getSkill("coding")).thenReturn("Write clean, tested Java code.");
        when(skillManager.getSkill("web")).thenReturn("Search the web using DuckDuckGo.");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
                .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.system("System prompt"), Message.user("Help me"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content())
                .contains("System prompt")
                .contains("Available skills:")
                .contains("coding: Write clean, tested Java code.")
                .contains("web: Search the web using DuckDuckGo.");
    }

    @Test
    void prepareContextTrimsToMaxContextMessages() {
        when(skillManager.listSkillNames()).thenReturn(Collections.emptyList());

        // 4 history messages + system + current user = 6, max is 5.
        List<MessageEntity> history = List.of(
                entity("user", "msg-1", 1),
                entity("assistant", "msg-2", 1),
                entity("user", "msg-3", 2),
                entity("assistant", "msg-4", 2)
        );
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.id())).thenReturn(history);

        List<Message> incoming = List.of(Message.system("System prompt"), Message.user("current"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        assertThat(result).hasSizeLessThanOrEqualTo(contextProps.getMaxContextMessages());
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(result.size() - 1).content()).isEqualTo("current");
    }

    @Test
    void prepareContextTriggersCompressorWhenCharsExceedMaxTokensEstimate() {
        when(skillManager.listSkillNames()).thenReturn(Collections.emptyList());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
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
        when(skillManager.listSkillNames()).thenReturn(Collections.emptyList());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
                .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.user("Just a user message"));
        List<Message> result = contextEngine.prepareContext(session, incoming);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo(Role.USER);
        assertThat(result.get(0).content()).isEqualTo("Just a user message");
        verify(memoryProvider, never()).recall(anyString(), anyString(), anyInt());
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
