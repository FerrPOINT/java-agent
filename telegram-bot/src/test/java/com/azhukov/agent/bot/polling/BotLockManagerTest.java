package com.azhukov.agent.bot.polling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotLockManagerTest {

    private Path tempDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("bot-lock-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.list(tempDir).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (IOException e) { /* ignore */ }
        });
        Files.deleteIfExists(tempDir);
    }

    @Test
    void acquireAndReleaseLock() throws IOException {
        BotLockManager manager = new BotLockManager(objectMapper, tempDir, "test-token-123", false);
        manager.acquire();
        assertThat(manager.isLockHeld()).isTrue();
        manager.release();
        assertThat(manager.isLockHeld()).isFalse();
    }

    @Test
    void differentTokensGetDifferentLockFiles() {
        BotLockManager manager1 = new BotLockManager(objectMapper, tempDir, "token-one", false);
        BotLockManager manager2 = new BotLockManager(objectMapper, tempDir, "token-two", false);
        assertThat(manager1.getLockFilePath()).isNotEqualTo(manager2.getLockFilePath());
    }

    @Test
    void sameTokenGetsSameLockFile() {
        BotLockManager manager1 = new BotLockManager(objectMapper, tempDir, "same-token", false);
        BotLockManager manager2 = new BotLockManager(objectMapper, tempDir, "same-token", false);
        assertThat(manager1.getLockFilePath()).isEqualTo(manager2.getLockFilePath());
    }

    @Test
    void acquireFailsWhenLockHeldWithoutReplace() throws IOException {
        BotLockManager manager1 = new BotLockManager(objectMapper, tempDir, "shared-token", false);
        manager1.acquire();

        BotLockManager manager2 = new BotLockManager(objectMapper, tempDir, "shared-token", false);
        // The lock file exists with a live PID — should fail
        // But since the PID is the current process (which is alive), it should throw
        assertThatThrownBy(manager2::acquire)
            .isInstanceOf(BotLockManager.LockAcquisitionException.class)
            .hasMessageContaining("Another bot instance is running");

        manager1.release();
    }

    @Test
    void staleLockCleanedUp() throws IOException {
        // Write a lock file with a dead PID
        String scopeHash = BotLockManager.scopeHash("test-token");
        Path lockPath = tempDir.resolve(scopeHash + "-bot.lock");
        String json = """
            {"pid": 999999, "start_time": "12345"}
            """;
        Files.writeString(lockPath, json);

        BotLockManager manager = new BotLockManager(objectMapper, tempDir, "test-token", false);
        manager.acquire(); // Should clean up stale lock
        assertThat(manager.isLockHeld()).isTrue();
        manager.release();
    }

    @Test
    void isLockHeldReturnsFalseForNoLockFile() {
        BotLockManager manager = new BotLockManager(objectMapper, tempDir, "no-such-token", false);
        assertThat(manager.isLockHeld()).isFalse();
    }

    @Test
    void scopeHashIsDeterministic() {
        String hash1 = BotLockManager.scopeHash("my-bot-token");
        String hash2 = BotLockManager.scopeHash("my-bot-token");
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void scopeHashDiffersForDifferentTokens() {
        String hash1 = BotLockManager.scopeHash("token-a");
        String hash2 = BotLockManager.scopeHash("token-b");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void isPidAliveReturnsFalseForNonexistentPid() {
        assertThat(BotLockManager.isPidAlive(999999)).isFalse();
    }

    @Test
    void isPidAliveReturnsTrueForCurrentPid() {
        int currentPid = BotLockManager.getCurrentPid();
        assertThat(BotLockManager.isPidAlive(currentPid)).isTrue();
    }

    @Test
    void isPidAliveReturnsFalseForZeroOrNegative() {
        assertThat(BotLockManager.isPidAlive(0)).isFalse();
        assertThat(BotLockManager.isPidAlive(-1)).isFalse();
    }
}