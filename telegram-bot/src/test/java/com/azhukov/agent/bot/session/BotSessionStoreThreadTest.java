package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V9 thread-scoped sessions (Hermes bot-mode parity): a forum topic never
 * resumes another topic's or the DM's transcript — identity is
 * (userId, threadId), the DM lane keeps threadId == null.
 */
@ExtendWith(MockitoExtension.class)
class BotSessionStoreThreadTest {

    @Mock
    private BotSessionRepository repository;

    private BotSessionStore store;

    @BeforeEach
    void setUp() {
        store = new BotSessionStore(repository);
    }

    @Test
    void topicMessageResolvesItsOwnSession() {
        BotSessionEntity topicA = new BotSessionEntity();
        topicA.setUserId("100");
        topicA.setThreadId(7L);
        when(repository.findByUserIdAndThreadIdAndActiveTrue("100", 7L))
            .thenReturn(Optional.of(topicA));

        BotSessionEntity resolved = store.resolveOrCreate("100", "-200", "alice", 7L);
        assertThat(resolved).isSameAs(topicA);
        verify(repository, never()).save(any(BotSessionEntity.class));
    }

    @Test
    void differentTopicDoesNotResumeTopicA() {
        when(repository.findByUserIdAndThreadIdAndActiveTrue("100", 8L))
            .thenReturn(Optional.empty());
        when(repository.save(any(BotSessionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        BotSessionEntity created = store.resolveOrCreate("100", "-200", "alice", 8L);
        assertThat(created.getUserId()).isEqualTo("100");
        assertThat(created.getThreadId()).isEqualTo(8L);
        // never touched the topic-7 lane
        verify(repository, never()).findByUserIdAndThreadIdAndActiveTrue("100", 7L);
    }

    @Test
    void dmLaneIsNullThread() {
        when(repository.findByUserIdAndThreadIdAndActiveTrue("100", null))
            .thenReturn(Optional.empty());
        when(repository.save(any(BotSessionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        BotSessionEntity dm = store.resolveOrCreate("100", "100", "alice");
        assertThat(dm.getThreadId()).isNull();
    }

    @Test
    void dmSessionCoexistsWithTopicSessions() {
        BotSessionEntity dm = new BotSessionEntity();
        dm.setUserId("100");
        when(repository.findByUserIdAndThreadIdAndActiveTrue("100", null))
            .thenReturn(Optional.of(dm));

        assertThat(store.resolveOrCreate("100", "100", "alice")).isSameAs(dm);
        // the topic lane is untouched by a DM resolution
        verify(repository, never()).findByUserIdAndThreadIdAndActiveTrue(eq("100"), anyLong());
    }

    @Test
    void newTopicSessionCarriesThreadIdAndChatId() {
        when(repository.findByUserIdAndThreadIdAndActiveTrue(eq("100"), any()))
            .thenReturn(Optional.empty());
        lenient().when(repository.save(any(BotSessionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        BotSessionEntity created = store.resolveOrCreate("100", "-200", "bob", 42L);
        assertThat(created.getThreadId()).isEqualTo(42L);
        assertThat(created.getChatId()).isEqualTo("-200");
    }
}
