package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.config.SharedObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Manages a PID-file based gateway lock to prevent concurrent bot instances
 * from running with the same bot token.
 *
 * <p>Features:
 * <ul>
 *   <li>PID file at {@code ~/.java-agent/bot.lock}</li>
 *   <li>Scoped lock keyed by bot token hash — different tokens get different lock files</li>
 *   <li>PID reuse detection — checks if the PID in the lock file is still alive</li>
 *   <li>{@code --replace} takeover mechanism — new instance can signal old to stop</li>
 *   <li>Planned-stop markers — distinguish intentional shutdowns from crashes</li>
 * </ul>
 *
 * <p>Mirrors the Python {@code gateway/status.py} PID/lock management.
 */
@Slf4j
public class BotLockManager {

    private static final String LOCK_DIR = System.getProperty("user.home") + "/.java-agent";
    private static final String LOCK_FILENAME = "bot.lock";

    private final ObjectMapper objectMapper;
    private final Path lockDir;
    private final String botToken;
    private final boolean replace;

    private FileChannel lockChannel;
    private FileLock fileLock;

    /**
     * @param botToken the Telegram bot token (hashed for scope)
     * @param replace   when true, signal the existing instance to stop and take over
     */
    public BotLockManager(String botToken, boolean replace) {
        this(SharedObjectMapper.get(), Path.of(LOCK_DIR), botToken, replace);
    }

    public BotLockManager(ObjectMapper objectMapper, Path lockDir, String botToken, boolean replace) {
        this.objectMapper = objectMapper;
        this.lockDir = lockDir;
        this.botToken = botToken;
        this.replace = replace;
    }

    /**
     * Compute the scoped lock file path for this bot token.
     *
     * @return the lock file path
     */
    public Path getLockFilePath() {
        String scopeHash = scopeHash(botToken);
        return lockDir.resolve(scopeHash + "-" + LOCK_FILENAME);
    }

    /**
     * Attempt to acquire the gateway lock.
     *
     * <p>If the lock is already held by a live process:
     * <ul>
     *   <li>If {@code replace} is true: signal the old process (SIGTERM) and retry</li>
     *   <li>Otherwise: throw {@link LockAcquisitionException}</li>
     * </ul>
     *
     * @throws LockAcquisitionException if the lock cannot be acquired
     * @throws IOException               on I/O errors
     */
    public void acquire() throws IOException {
        Files.createDirectories(lockDir);
        Path lockPath = getLockFilePath();

        // Check for an existing lock
        LockRecord existing = readLockRecord(lockPath);
        if (existing != null) {
            int existingPid = existing.pid();
            if (isPidAlive(existingPid)) {
                if (replace) {
                    log.info("Lock held by PID {} (started at {}); --replace requested, signaling old instance to stop",
                        existingPid, existing.startTime());
                    terminatePid(existingPid);
                    waitForPidExit(existingPid, 10_000);
                } else {
                    throw new LockAcquisitionException(
                        "Another bot instance is running (PID " + existingPid
                        + "). Use --replace to take over.");
                }
            } else {
                // Stale lock file — clean it up
                log.info("Found stale lock from dead PID {}, cleaning up", existingPid);
                deleteLockFile(lockPath);
            }
        }

        // Create/open the lock file WITHOUT truncating first — audit M25:
        // TRUNCATE_EXISTING before tryLock races with concurrent holders:
        // if tryLock fails, the file is already zeroed and readLockRecord returns null,
        // making it look like the lock is free.
        lockChannel = FileChannel.open(lockPath,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            fileLock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            closeLockChannelQuietly();
            throw new LockAcquisitionException(
                "Another bot instance is running in this JVM. Use --replace to take over.");
        }
        if (fileLock == null) {
            closeLockChannelQuietly();
            throw new LockAcquisitionException("Could not acquire file lock on " + lockPath);
        }
        // Truncate AFTER successful lock so we start with a clean file
        lockChannel.truncate(0);

        // Write our PID record
        writeLockRecord(lockPath);
        log.info("Acquired gateway lock at {} (PID {})", lockPath, getCurrentPid());
    }

    /**
     * Release the gateway lock and write a planned-stop marker.
     */
    public void release() {
        try {
            Path lockPath = getLockFilePath();

            // Write a planned-stop marker before releasing
            try {
                writePlannedStop(lockPath);
            } catch (Exception e) {
                log.debug("Could not write planned-stop marker: {}", e.getMessage());
            }

            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            if (lockChannel != null) {
                lockChannel.close();
                lockChannel = null;
            }
            deleteLockFile(lockPath);
            log.info("Released gateway lock at {}", lockPath);
        } catch (Exception e) {
            log.warn("Error releasing gateway lock: {}", e.getMessage());
        }
    }

    /**
     * Check if the lock is currently held by a live process.
     *
     * @return true if another live process holds the lock
     */
    public boolean isLockHeld() {
        if (fileLock != null && fileLock.isValid()) {
            return true;
        }
        Path lockPath = getLockFilePath();
        LockRecord record = readLockRecord(lockPath);
        if (record == null) {
            return false;
        }
        return isPidAlive(record.pid());
    }

    // ─── Internal helpers ──────────────────────────────────────────

    static String scopeHash(String token) {
        return Integer.toHexString(token.hashCode() & 0xFFFFFF);
    }

    LockRecord readLockRecord(Path lockPath) {
        if (!Files.exists(lockPath)) {
            return null;
        }
        try {
            String content = Files.readString(lockPath).trim();
            if (content.isEmpty()) {
                return null;
            }
            ObjectNode node = (ObjectNode) objectMapper.readTree(content);
            int pid = node.get("pid").asInt();
            String startTime = node.has("start_time") ? node.get("start_time").asText() : null;
            return new LockRecord(pid, startTime);
        } catch (Exception e) {
            log.debug("Could not read lock record from {}: {}", lockPath, e.getMessage());
            return null;
        }
    }

    void writeLockRecord(Path lockPath) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("pid", getCurrentPid());
        node.put("start_time", getProcessStartTime());
        byte[] data = objectMapper.writeValueAsBytes(node);
        lockChannel.position(0);
        lockChannel.write(java.nio.ByteBuffer.wrap(data));
        lockChannel.truncate(data.length);
        lockChannel.force(false);
    }

    void writePlannedStop(Path lockPath) throws IOException {
        // Write a marker file indicating planned shutdown
        Path stopMarker = lockPath.resolveSibling(lockPath.getFileName() + ".planned-stop");
        ObjectNode node = objectMapper.createObjectNode();
        node.put("pid", getCurrentPid());
        node.put("stopped_at", java.time.Instant.now().toString());
        Files.writeString(stopMarker, objectMapper.writeValueAsString(node));
    }

    static boolean isPidAlive(int pid) {
        if (pid <= 0) return false;
        try {
            // On Linux, check /proc/<pid>
            Path procPath = Path.of("/proc/" + pid);
            if (Files.exists(procPath)) {
                return true;
            }
        } catch (Exception e) {
            // Fall through to process API
        }
        try {
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            return handle != null && handle.isAlive();
        } catch (Exception e) {
            return false;
        }
    }

    void terminatePid(int pid) {
        try {
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle != null) {
                handle.destroy(); // SIGTERM
            }
        } catch (Exception e) {
            log.warn("Could not terminate PID {}: {}", pid, e.getMessage());
        }
    }

    void waitForPidExit(int pid, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isPidAlive(pid)) return;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("PID {} did not exit within {}ms", pid, timeoutMs);
    }

    void deleteLockFile(Path lockPath) {
        try {
            Files.deleteIfExists(lockPath);
        } catch (IOException e) {
            log.debug("Could not delete lock file {}: {}", lockPath, e.getMessage());
        }
    }

    private void closeLockChannelQuietly() {
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException closeError) {
                log.debug("Could not close lock channel: {}", closeError.getMessage());
            } finally {
                lockChannel = null;
            }
        }
    }

    static int getCurrentPid() {
        try {
            return (int) ProcessHandle.current().pid();
        } catch (Exception e) {
            return 0;
        }
    }

    static String getProcessStartTime() {
        try {
            return ManagementFactory.getRuntimeMXBean().getStartTime() + "";
        } catch (Exception e) {
            return null;
        }
    }

    /** Lock record parsed from the lock file. */
    record LockRecord(int pid, String startTime) {
    }

    /** Thrown when the lock cannot be acquired. */
    public static class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String message) {
            super(message);
        }
    }
}
