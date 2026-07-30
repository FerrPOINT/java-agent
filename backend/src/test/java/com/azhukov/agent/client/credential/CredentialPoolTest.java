package com.azhukov.agent.client.credential;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialPoolTest {

    @Test
    void hasCredentials_emptyPool() {
        CredentialPool pool = new CredentialPool("test", List.of(), CredentialPool.Strategy.FILL_FIRST);
        assertThat(pool.hasCredentials()).isFalse();
    }

    @Test
    void hasCredentials_nonEmptyPool() {
        PooledCredential cred = new PooledCredential("c1", "test", "key1", "http://localhost", 0);
        CredentialPool pool = new CredentialPool("test", List.of(cred), CredentialPool.Strategy.FILL_FIRST);
        assertThat(pool.hasCredentials()).isTrue();
    }

    @Test
    void fillFirst_returnsHighestPriority() {
        PooledCredential cred1 = new PooledCredential("c1", "test", "key1", "http://localhost", 0);
        PooledCredential cred2 = new PooledCredential("c2", "test", "key2", "http://localhost", 1);
        CredentialPool pool = new CredentialPool("test", Arrays.asList(cred2, cred1), CredentialPool.Strategy.FILL_FIRST);
        assertThat(pool.current()).extracting(PooledCredential::id).isEqualTo("c1");
    }

    @Test
    void roundRobin_rotatesThroughCredentials() {
        PooledCredential cred1 = new PooledCredential("c1", "test", "key1", "http://localhost", 0);
        PooledCredential cred2 = new PooledCredential("c2", "test", "key2", "http://localhost", 0);
        CredentialPool pool = new CredentialPool("test", Arrays.asList(cred1, cred2), CredentialPool.Strategy.ROUND_ROBIN);
        PooledCredential first = pool.current();
        PooledCredential second = pool.current();
        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    void leastUsed_returnsLeastUsedCredential() {
        PooledCredential cred1 = new PooledCredential("c1", "test", "key1", "http://localhost", 0);
        PooledCredential cred2 = new PooledCredential("c2", "test", "key2", "http://localhost", 0);
        cred1.incrementRequestCount();
        cred1.incrementRequestCount();
        CredentialPool pool = new CredentialPool("test", Arrays.asList(cred1, cred2), CredentialPool.Strategy.LEAST_USED);
        assertThat(pool.current()).extracting(PooledCredential::id).isEqualTo("c2");
    }

    @Test
    void markExhausted_entersCooldown() {
        PooledCredential cred = new PooledCredential("c1", "test", "key1", "http://localhost", 0);
        CredentialPool pool = new CredentialPool("test", List.of(cred), CredentialPool.Strategy.FILL_FIRST);
        pool.markExhausted(cred, 429, "rate limited");
        assertThat(pool.hasAvailable()).isFalse();
    }

    @Test
    void markDead_makesUnavailable() {
        PooledCredential cred = new PooledCredential("c1", "test", "key1", "http://localhost", 0);
        CredentialPool pool = new CredentialPool("test", List.of(cred), CredentialPool.Strategy.FILL_FIRST);
        pool.markDead(cred, "token revoked");
        assertThat(pool.hasAvailable()).isFalse();
        assertThat(pool.current()).isNull();
    }

    @Test
    void markUsed_incrementsRequestCount() {
        PooledCredential cred = new PooledCredential("c1", "test", "key1", "http://localhost", 0);
        CredentialPool pool = new CredentialPool("test", List.of(cred), CredentialPool.Strategy.FILL_FIRST);
        pool.markUsed(cred);
        assertThat(cred.requestCount()).isEqualTo(1);
    }

    @Test
    void hasAvailable_withMultipleCredentials() {
        PooledCredential cred1 = new PooledCredential("c1", "test", "key1", "http://localhost", 0);
        PooledCredential cred2 = new PooledCredential("c2", "test", "key2", "http://localhost", 1);
        CredentialPool pool = new CredentialPool("test", Arrays.asList(cred1, cred2), CredentialPool.Strategy.FILL_FIRST);
        pool.markExhausted(cred1, 429, "rate limited");
        // cred2 should still be available
        assertThat(pool.hasAvailable()).isTrue();
        assertThat(pool.current()).extracting(PooledCredential::id).isEqualTo("c2");
    }

    @Test
    void pruneDead_removesOldManualEntries() {
        PooledCredential cred = new PooledCredential("c1", "test", "manual", "key1", "http://localhost", 0);
        cred.setStatus(CredentialPool.Status.DEAD);
        // Set lastStatusAt to 25 hours ago to exceed prune TTL (24h)
        cred.setLastStatusAt(System.currentTimeMillis() - 25 * 60 * 60 * 1000);
        CredentialPool pool = new CredentialPool("test", List.of(cred), CredentialPool.Strategy.FILL_FIRST);
        pool.pruneDead();
        assertThat(pool.hasCredentials()).isFalse();
    }

    @Test
    void pruneDead_keepsRecentDeadEntries() {
        PooledCredential cred = new PooledCredential("c1", "test", "manual", "key1", "http://localhost", 0);
        cred.setStatus(CredentialPool.Status.DEAD);
        cred.setLastStatusAt(System.currentTimeMillis() - 1000); // 1 second ago
        CredentialPool pool = new CredentialPool("test", List.of(cred), CredentialPool.Strategy.FILL_FIRST);
        pool.pruneDead();
        assertThat(pool.hasCredentials()).isTrue();
    }

    @Test
    void entries_returnsAllCredentials() {
        PooledCredential cred1 = new PooledCredential("c1", "test", "key1", "http://localhost", 0);
        PooledCredential cred2 = new PooledCredential("c2", "test", "key2", "http://localhost", 1);
        CredentialPool pool = new CredentialPool("test", Arrays.asList(cred1, cred2), CredentialPool.Strategy.FILL_FIRST);
        assertThat(pool.entries()).hasSize(2);
    }
}