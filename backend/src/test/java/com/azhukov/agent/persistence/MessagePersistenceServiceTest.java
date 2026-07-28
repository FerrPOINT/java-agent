package com.azhukov.agent.persistence;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MessagePersistenceServiceTest {

    private MessageRepository repository;
    private MessagePersistenceService service;

    private static final UUID SESSION_ID = UUID.fromString("aaaa1111-2222-3333-4444-555566667777");
    private static final Session SESSION = new Session(SESSION_ID, "user1", "Test",
        "openai-compatible", "kimi-k2", null, Map.of());

    @BeforeEach
    void setUp() {
        repository = mock(MessageRepository.class);
        service = new MessagePersistenceService(repository);
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

        verify(repository, times(4)).save(any(MessageEntity.class)); // user + tool_call + tool_result + assistant
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
}