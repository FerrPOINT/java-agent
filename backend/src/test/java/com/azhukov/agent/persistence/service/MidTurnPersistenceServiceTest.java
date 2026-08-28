package com.azhukov.agent.persistence.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MidTurnPersistenceServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private SessionRepository sessionRepository;

    private MidTurnPersistenceService service;

    @BeforeEach
    void setUp() {
        // Use Mappers.getMapper for real mapper (per AGENTS.md: don't mock mappers)
        MessageMapper messageMapper = org.mapstruct.factory.Mappers.getMapper(MessageMapper.class);
        lenient().when(messageRepository.countBySessionId(any())).thenReturn(0L);
        lenient().when(sessionRepository.existsById(any())).thenReturn(true);
        service = new MidTurnPersistenceService(messageRepository, messageMapper, transactionTemplate, sessionRepository);
    }

    private void stubTransaction() {
        // Make TransactionTemplate execute the callback immediately
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            return invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class)
                .doInTransaction(null);
        });
    }

    @Test
    void persistNewMessages_savesNewMessages() {
        stubTransaction();
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(
            Message.system("sys"),
            Message.user("hello"),
            Message.assistant("response", 1),
            Message.toolResult("call-1", "result", 1)
        );

        service.persistNewMessages(sessionId, messages, 2);

        // Should save messages from index 2 onward (assistant + tool result)
        // System and user messages (indices 0-1) should be skipped
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, times(2)).save(captor.capture());

        List<MessageEntity> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getRole()).isEqualTo("assistant");
        assertThat(saved.get(0).getContent()).isEqualTo("response");
        assertThat(saved.get(0).getSessionId()).isEqualTo(sessionId);
        assertThat(saved.get(1).getRole()).isEqualTo("tool");
        assertThat(saved.get(1).getContent()).isEqualTo("result");
    }

    @Test
    void persistNewMessages_savesWholeAssistantToolCallBatch() {
        stubTransaction();
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(Message.assistantWithToolCalls("", List.of(
            new com.azhukov.agent.core.model.ToolCall("call-one", "skills_list", "{}"),
            new com.azhukov.agent.core.model.ToolCall("call-two", "memory", "{\"target\":\"memory\"}")
        ), 1));

        service.persistNewMessages(sessionId, messages, 0);

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getToolCallsJson()).contains("call-one", "call-two");
    }

    @Test
    void persistNewMessages_skipsSystemAndDeveloperMessages() {
        stubTransaction();
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(
            Message.system("sys"),
            Message.developer("dev"),
            Message.assistant("response", 1)
        );

        service.persistNewMessages(sessionId, messages, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, times(1)).save(captor.capture());

        assertThat(captor.getValue().getRole()).isEqualTo("assistant");
    }

    @Test
    void persistNewMessages_doesNothingWhenFromIndexExceedsSize() {
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(Message.user("hello"));

        service.persistNewMessages(sessionId, messages, 5);

        verifyNoInteractions(messageRepository);
    }

    @Test
    void persistNewMessages_doesNothingWhenMessagesEmpty() {
        UUID sessionId = UUID.randomUUID();

        service.persistNewMessages(sessionId, List.of(), 0);

        verifyNoInteractions(messageRepository);
    }

    @Test
    void persistNewMessages_handlesExceptionGracefully() {
        stubTransaction();
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(Message.assistant("test", 1));

        when(messageRepository.save(any())).thenThrow(new RuntimeException("DB error"));

        // M6: Should not throw — just return false
        boolean result = service.persistNewMessages(sessionId, messages, 0);

        assertThat(result).isFalse();
        verify(messageRepository).save(any());
    }

    @Test
    void persistNewMessages_skipsQuietlyWhenSessionDeleted() {
        // Live defect (0.1.35 e2e run): session deleted mid-turn -> every tool
        // batch retried the INSERT and logged an FK-violation WARN.
        when(sessionRepository.existsById(any())).thenReturn(false);
        UUID sessionId = UUID.randomUUID();
        List<Message> messages = List.of(Message.user("hi"));
        boolean result = service.persistNewMessages(sessionId, messages, 0);
        assertThat(result).isTrue(); // treated as flushed, caller advances cursor
        verify(messageRepository, never()).save(any());
        verify(transactionTemplate, never()).execute(any());
    }
}
