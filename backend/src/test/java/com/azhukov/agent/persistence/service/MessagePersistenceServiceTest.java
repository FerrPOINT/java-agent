package com.azhukov.agent.persistence.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MessagePersistenceServiceTest {

    private MessageRepository repository;
    private SessionRepository sessionRepo;
    private MessagePersistenceService service;

    private static final UUID SESSION_ID = UUID.fromString("aaaa1111-2222-3333-4444-555566667777");
    private static final Session SESSION = new Session(SESSION_ID, "user1", "Test",
        "openai-compatible", "kimi-k2", null, Map.of());

    @BeforeEach
    void setUp() {
        repository = mock(MessageRepository.class);
        sessionRepo = mock(SessionRepository.class);
        when(repository.countBySessionId(any(UUID.class))).thenReturn(0L);
        when(sessionRepo.existsById(any(UUID.class))).thenReturn(true);
        service = new MessagePersistenceService(repository, sessionRepo, Mappers.getMapper(MessageMapper.class));
    }

    @Test
    void persistTurn_savesUserMessage() {
        TurnResult result = new TurnResult(List.of(Message.assistant("Hello!", 1)), true, null);

        service.persistTurn(SESSION, "Hi", result);

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(repository, times(2)).save(captor.capture()); // user + assistant

        List<MessageEntity> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);

        MessageEntity userMsg = saved.get(0);
        assertThat(userMsg.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(userMsg.getRole()).isEqualTo("user");
        assertThat(userMsg.getContent()).isEqualTo("Hi");
        assertThat(userMsg.getCreatedAt()).isNotNull();

        MessageEntity assistantMsg = saved.get(1);
        assertThat(assistantMsg.getRole()).isEqualTo("assistant");
        assertThat(assistantMsg.getContent()).isEqualTo("Hello!");
    }

    @Test
    void persistTurn_savesToolMessages() {
        TurnResult result = new TurnResult(List.of(
            Message.assistantToolCalls(List.of(new ToolCall("call-1", "read_file", "{\"path\":\"/tmp\"}")), 1),
            Message.toolResult("call-1", "file content here", 1),
            Message.assistant("Done!", 2)
        ), true, null);

        service.persistTurn(SESSION, "read file", result);

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(repository, times(4)).save(captor.capture()); // user + tool_call + tool_result + assistant

        List<MessageEntity> saved = captor.getAllValues();
        assertThat(saved.get(1).getRole()).isEqualTo("assistant");
        assertThat(saved.get(1).getToolCallName()).isEqualTo("read_file");
        assertThat(saved.get(2).getRole()).isEqualTo("tool");
        assertThat(saved.get(2).getToolCallName()).isEqualTo("read_file");
    }

    @Test
    void persistTurn_savesMultipleToolCallsInOneAssistantRow() {
        TurnResult result = new TurnResult(List.of(
            Message.assistantToolCalls(List.of(
                new ToolCall("call-1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("call-2", "web_search", "{\"query\":\"java\"}")), 1)
        ), true, null);

        service.persistTurn(SESSION, "two tools", result);

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(repository, times(2)).save(captor.capture());
        MessageEntity assistant = captor.getAllValues().get(1);
        assertThat(assistant.getRole()).isEqualTo("assistant");
        assertThat(assistant.getToolCallId()).isEqualTo("call-1");
        assertThat(assistant.getToolCalls())
            .contains("\"id\":\"call-1\"")
            .contains("\"id\":\"call-2\"")
            .contains("\"name\":\"web_search\"");
    }

    @Test
    void persistTurn_persistsMultiToolAssistantBatchAsOneRow() {
        List<ToolCall> calls = List.of(
            new ToolCall("call-1", "skills_list", "{}"),
            new ToolCall("call-2", "memory", "{\"target\":\"memory\"}")
        );
        TurnResult result = new TurnResult(List.of(
            Message.assistantWithToolCalls("checking", calls, 1),
            Message.toolResult("call-1", "skills", 1),
            Message.toolResult("call-2", "memory", 1)
        ), true, null);

        service.persistTurn(SESSION, "inspect", result);

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(repository, times(4)).save(captor.capture());
        MessageEntity assistant = captor.getAllValues().get(1);
        assertThat(assistant.getRole()).isEqualTo("assistant");
        assertThat(assistant.getToolCallId()).isEqualTo("call-1");
        assertThat(assistant.getToolCallsJson()).contains("call-1", "call-2");
    }

    @Test
    void persistTurn_withNullTurnResult_savesOnlyUserMessage() {
        service.persistTurn(SESSION, "test", null);

        verify(repository, times(1)).save(any(MessageEntity.class));
    }

    @Test
    void persistTurn_withErrorResult_savesUserMessage() {
        TurnResult errorResult = TurnResult.error("model failed");

        service.persistTurn(SESSION, "test", errorResult);

        // user + no assistant messages in error result (empty messages list)
        verify(repository, times(1)).save(any(MessageEntity.class));
    }

    @Test
    void persistTurn_setsCreatedAtOnEveryMessage() {
        TurnResult result = new TurnResult(List.of(Message.assistant("Reply", 1)), true, null);

        service.persistTurn(SESSION, "Question", result);

        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(repository, times(2)).save(captor.capture());

        for (MessageEntity entity : captor.getAllValues()) {
            assertThat(entity.getCreatedAt()).isNotNull();
        }
    }

    @Test
    void persistTurn_skipsQuietlyWhenSessionDeleted() {
        // Live defect (deleted-mid-turn race): no FK violation, no WARN spam.
        when(sessionRepo.existsById(SESSION_ID)).thenReturn(false);
        TurnResult result = new TurnResult(List.of(Message.assistant("Hello!", 1)), true, null);
        service.persistTurn(SESSION, "hi", result);
        verify(repository, never()).save(any(MessageEntity.class));
    }
}
