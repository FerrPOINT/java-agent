package com.azhukov.agent.service;

import com.azhukov.agent.service.ConversationCompressor;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * rev-31: compression must NOT delete messages persisted DURING the LLM
 * compression call. The old deleteAll(existing) wiped messages added in the
 * 10-60s window between readMessages and persistCompressed.
 */
@ExtendWith(MockitoExtension.class)
class SessionCompressionRaceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private ConversationCompressor conversationCompressor;
    @Mock private ObjectProvider<SessionCompressionHelper> self;

    private SessionCompressionHelper helper;

    @BeforeEach
    void setUp() {
        MessageMapper mapper = new MessageMapper() {};
        helper = new SessionCompressionHelper(messageRepository, mapper, conversationCompressor, self, org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    private static List<Message> messages(int n) {
        return IntStream.range(0, n)
            .mapToObj(i -> Message.user("msg " + i))
            .toList();
    }

    @Test
    void persistPreservesMessagesAddedDuringCompression() {
        UUID sessionId = UUID.randomUUID();
        List<Message> original = messages(10);

        // self-proxy returns the helper itself so inner @Transactional methods run
        when(self.getObject()).thenReturn(helper);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId))
            .thenReturn(original.stream().map(m -> entity(m, Instant.now().minusSeconds(60))).toList());
        when(conversationCompressor.compress(any(), any())).thenReturn(messages(2));

        helper.compressSessionInternal(sessionId, null, null);

        // Hermes archive_and_compact contract (hermes_state.py:11191): old rows are
        // soft-archived (active=false, compacted=true) in one saveAll — NEVER deleted,
        // so session_search keeps finding them and the transcript stays recoverable.
        org.mockito.Mockito.verify(messageRepository,
            org.mockito.Mockito.never()).deleteBySessionIdAndCreatedAtBefore(
                org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(messageRepository,
            org.mockito.Mockito.never()).deleteAll(org.mockito.ArgumentMatchers.anyList());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.azhukov.agent.persistence.entity.MessageEntity>> archived =
            org.mockito.ArgumentCaptor.forClass((Class) List.class);
        org.mockito.Mockito.verify(messageRepository).saveAll(archived.capture());
        assertThat(archived.getValue()).isNotEmpty();
        assertThat(archived.getValue()).allSatisfy(row -> {
            assertThat(row.getActive()).isFalse();
            assertThat(row.getCompacted()).isTrue();
        });
    }

    private static MessageEntity entity(Message m, Instant createdAt) {
        MessageEntity e = new MessageEntity();
        e.setSessionId(UUID.randomUUID());
        e.setRole(m.role().name().toLowerCase());
        e.setContent(m.content());
        e.setCreatedAt(createdAt);
        return e;
    }
}
