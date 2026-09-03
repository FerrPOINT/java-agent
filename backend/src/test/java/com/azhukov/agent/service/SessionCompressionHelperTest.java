package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionCompressionHelperTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ConversationCompressor conversationCompressor;

    @Mock
    private SessionRepository sessionRepository;

    private SessionCompressionHelper helper;

    @BeforeEach
    void setUp() {
        helper = new SessionCompressionHelper(
            messageRepository,
            Mappers.getMapper(MessageMapper.class),
            conversationCompressor,
            sessionRepository
        );
    }

    @Test
    void compressSessionInternalBackfillsToolNameWhenPersistingCompressedTail() {
        UUID sessionId = UUID.randomUUID();
        List<MessageEntity> existing = List.of(
            entity("user", "one"),
            entity("assistant", "two"),
            entity("user", "three"),
            entity("assistant", "four"),
            entity("user", "five")
        );
        ToolCall toolCall = new ToolCall("call-1|response-1", "web_search", "{\"query\":\"java\"}");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(existing);
        when(conversationCompressor.compressPartial(anyList(), eq(2))).thenReturn(List.of(
            Message.assistantToolCalls(List.of(toolCall), 1),
            Message.toolResult("call-1", "search result", 1)
        ));

        helper.compressSessionInternal(sessionId, null, 2);

        verify(messageRepository).saveAll(existing);
        assertThat(existing).allSatisfy(archived -> {
            assertThat(archived.getActive()).isFalse();
            assertThat(archived.getCompacted()).isTrue();
        });
        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<MessageEntity> saved = captor.getAllValues();
        assertThat(saved.get(0).getToolCalls())
            .contains("\"id\":\"call-1|response-1\"")
            .contains("\"name\":\"web_search\"");
        assertThat(saved.get(1).getRole()).isEqualTo("tool");
        assertThat(saved.get(1).getToolCallId()).isEqualTo("call-1");
        assertThat(saved.get(1).getToolCallName()).isEqualTo("web_search");
        assertThat(saved.get(0).getCreatedAt()).isBefore(saved.get(1).getCreatedAt());
        verify(sessionRepository).updateLastActiveAndMessageCount(eq(sessionId), org.mockito.Mockito.any(), eq(2));
    }

    @Test
    void compressSessionInternalClonesConcurrentTailAfterCompactedRowsLikeHermes() {
        UUID sessionId = UUID.randomUUID();
        List<MessageEntity> initial = List.of(
            entity("user", "one"),
            entity("assistant", "two"),
            entity("user", "three"),
            entity("assistant", "four"),
            entity("user", "five")
        );
        MessageEntity concurrentTail = entity("assistant", "arrived during compression");
        concurrentTail.setToolCallId("late-call");
        concurrentTail.setToolCallName("read_file");
        concurrentTail.setToolCallArguments("{\"path\":\"README.md\"}");
        concurrentTail.setToolCalls("""
            [{"id":"late-call","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]
            """);
        concurrentTail.setTurnIndex(99);
        concurrentTail.setImageCount(1);
        java.util.ArrayList<MessageEntity> currentActive = new java.util.ArrayList<>(initial);
        currentActive.add(concurrentTail);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId))
            .thenReturn(initial, currentActive);
        when(conversationCompressor.compress(anyList(), eq("focus"))).thenReturn(List.of(
            Message.system("summary"),
            Message.user("live ask")
        ));

        helper.compressSessionInternal(sessionId, "focus", null);

        verify(messageRepository).saveAll(currentActive);
        assertThat(currentActive).allSatisfy(archived -> {
            assertThat(archived.getActive()).isFalse();
            assertThat(archived.getCompacted()).isTrue();
        });
        ArgumentCaptor<MessageEntity> captor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        List<MessageEntity> saved = captor.getAllValues();
        assertThat(saved.get(0).getContent()).isEqualTo("summary");
        assertThat(saved.get(1).getContent()).isEqualTo("live ask");
        assertThat(saved.get(2).getContent()).isEqualTo("arrived during compression");
        assertThat(saved.get(2).getToolCallName()).isEqualTo("read_file");
        assertThat(saved.get(2).getToolCalls()).contains("late-call");
        assertThat(saved.get(2).getActive()).isTrue();
        assertThat(saved.get(2).getCompacted()).isFalse();
        assertThat(saved.get(0).getCreatedAt()).isBefore(saved.get(1).getCreatedAt());
        assertThat(saved.get(1).getCreatedAt()).isBefore(saved.get(2).getCreatedAt());
        verify(sessionRepository).updateLastActiveAndMessageCount(eq(sessionId), org.mockito.Mockito.any(), eq(3));
    }

    private static MessageEntity entity(String role, String content) {
        MessageEntity entity = new MessageEntity();
        entity.setId(UUID.nameUUIDFromBytes((role + ":" + content).getBytes(StandardCharsets.UTF_8)));
        entity.setRole(role);
        entity.setContent(content);
        entity.setActive(true);
        entity.setCompacted(false);
        entity.setCreatedAt(Instant.parse("2026-08-28T10:00:00Z"));
        return entity;
    }
}
