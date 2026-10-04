package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Two sessions sharing the singleton helper must retain independent compression watermarks. */
class SessionCompressionWatermarkTest {
    @Test
    @SuppressWarnings("unchecked")
    void concurrentSessionCannotOverwriteTheSnapshotOfAnInFlightCompression() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var repository = mock(MessageRepository.class);
        var compressor = mock(ConversationCompressor.class);
        var self = (ObjectProvider<SessionCompressionHelper>)mock(ObjectProvider.class);
        var helper = new SessionCompressionHelper(repository, MessageMapper.INSTANCE, compressor, self, mock(ObjectProvider.class));
        when(self.getObject()).thenReturn(helper);
        when(repository.findBySessionIdOrderByCreatedAtAsc(first)).thenReturn(rows(first, "first"));
        when(repository.findBySessionIdOrderByCreatedAtAsc(second)).thenReturn(rows(second, "second"));
        var saved = new ConcurrentLinkedQueue<MessageEntity>();
        when(repository.save(any(MessageEntity.class))).thenAnswer(call -> {
            MessageEntity row = call.getArgument(0); saved.add(row); return row;
        });
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(compressor.compress(anyList(), isNull())).thenAnswer(call -> {
            List<Message> messages = call.getArgument(0);
            if (messages.getFirst().content().equals("first")) {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return List.of(Message.user("summary"));
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> helper.compressSessionInternal(first, null, null));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                helper.compressSessionInternal(second, null, null);
                release.countDown(); task.get(10, TimeUnit.SECONDS);
                assertEquals(1, saved.stream().filter(row -> first.equals(row.getSessionId())).count(),
                    "the original first-session snapshot must not be mistaken for a concurrent append");
            } finally { release.countDown(); }
        }
    }

    private static List<MessageEntity> rows(UUID session, String content) {
        return IntStream.range(0, 5).mapToObj(index -> {
            var row = new MessageEntity(); row.setId(UUID.randomUUID()); row.setSessionId(session);
            row.setRole("user"); row.setContent(content); row.setActive(true);
            row.setCreatedAt(Instant.now().minusSeconds(60)); return row;
        }).toList();
    }
}
