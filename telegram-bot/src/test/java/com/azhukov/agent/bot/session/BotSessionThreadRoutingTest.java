package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DM-topics depth (docs/34 gap 9 remainder): the originating forum/DM topic
 * must survive a restart so non-interactive sends (recovery notices, resumed
 * turns) land in the same topic the user was talking in.
 *
 * Contract under test — {@code BotSessionEntity} persistence helpers:
 * - a positive thread id is stored under the well-known metadata key;
 * - zero (no routing) clears the key instead of storing a bogus 0;
 * - reads return 0 when nothing was ever stored;
 * - the key round-trips through the metadata JSON converter column.
 */
class BotSessionThreadRoutingTest {

    private BotSessionEntity session;

    @BeforeEach
    void setUp() {
        session = new BotSessionEntity();
        session.setUserId("u1");
        session.setChatId("100");
        session.setActive(true);
        session.setCreatedAt(Instant.now());
    }

    @Test
    void positiveThreadIdIsPersistedUnderMetadataKey() {
        session.setLastMessageThreadId(42L);
        assertThat(session.getLastMessageThreadId()).isEqualTo(42L);
        assertThat(session.getMetadata(BotSessionEntity.METADATA_KEY_THREAD_ID)).isEqualTo("42");
    }

    @Test
    void zeroThreadIdClearsStoredRouting() {
        session.setLastMessageThreadId(42L);
        session.setLastMessageThreadId(0L);
        assertThat(session.getLastMessageThreadId()).isZero();
        assertThat(session.getMetadata(BotSessionEntity.METADATA_KEY_THREAD_ID)).isNull();
    }

    @Test
    void missingKeyReadsAsZero() {
        assertThat(session.getLastMessageThreadId()).isZero();
    }

    @Test
    void corruptStoredValueReadsAsZeroFailSafe() {
        session.setMetadata(BotSessionEntity.METADATA_KEY_THREAD_ID, "not-a-number");
        assertThat(session.getLastMessageThreadId()).isZero();
    }
}
