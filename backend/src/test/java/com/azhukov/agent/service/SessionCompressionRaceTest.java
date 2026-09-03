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
        helper = new SessionCompressionHelper(messageRepository, mapper, conversationCompressor, self);
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

        // The delete must be cutoff-bounded, NOT deleteAll
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(messageRepository, atLeastOnce())
            .deleteBySessionIdAndCreatedAtBefore(eq(sessionId), cutoff.capture());
        // Cutoff must be BEFORE the LLM call, i.e. strictly in the past
        assertThat(cutoff.getValue()).isBefore(Instant.now());
        // Must NOT load-then-deleteAll (the old racy pattern)
        org.mockito.Mockito.verify(messageRepository,
            org.mockito.Mockito.never()).deleteAll(org.mockito.ArgumentMatchers.anyList());
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
