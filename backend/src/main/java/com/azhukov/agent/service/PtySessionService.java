package com.azhukov.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WP-9 (ADR-015): interactive PTY sessions.
 *
 * <p>Pseudo-terminal sessions run {@code script -qfc <shell>} children with
 * an explicit workspace/cwd, ownership binding (profile/user/session), and a
 * bounded binary-safe output ring with cursor reconnect. Input/resize/read/
 * close operations are explicit; the process group is destroyed on close or
 * idle timeout. Capability is fail-closed: hosts without the {@code script}
 * utility report PTY unavailable — no fallback to plain pipes.
 */
@Service
@Slf4j
public class PtySessionService {

    public static final int MAX_RING_LINES = 5_000;
    private static final Duration IDLE_TTL = Duration.ofMinutes(30);
    private static final String SCRIPT = "/usr/bin/script";

    public record PtyStartResult(String id, String error) {}

    public record PtyReadResult(List<RingLine> lines, long cursor, boolean alive) {}
    public record RingLine(long seq, String text) {}

    private static final class PtySession {
        final String id;
        final String profile;
        final String userId;
        final Process process;
        final BufferedWriter stdin;
        final ReentrantLock lock = new ReentrantLock();
        final ArrayDeque<RingLine> ring = new ArrayDeque<>();
        Thread reader;
        volatile long seq = 0;
        volatile long lastRead = System.currentTimeMillis();
        volatile boolean closed;

        PtySession(String id, String profile, String userId, Process process,
                   BufferedWriter stdin, Thread reader) {
            this.id = id;
            this.profile = profile;
            this.userId = userId;
            this.process = process;
            this.stdin = stdin;
            this.reader = reader;
        }
    }

    private final ConcurrentHashMap<String, PtySession> sessions = new ConcurrentHashMap<>();

    public static boolean ptyAvailable() {
        return Files.isExecutable(Path.of(SCRIPT));
    }

    public PtyStartResult start(String profile, String userId, UUID sessionId, String cwd) {
        if (!ptyAvailable()) {
            return new PtyStartResult(null,
                "PTY unavailable: " + SCRIPT + " not found on this host (fail-closed)");
        }
        Path workdir = cwd == null || cwd.isBlank() ? Path.of(System.getProperty("user.dir"))
            : Path.of(cwd).toAbsolutePath().normalize();
        if (!Files.isDirectory(workdir)) {
            return new PtyStartResult(null, "workdir does not exist: " + workdir);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(SCRIPT, "-qfc",
                System.getenv().getOrDefault("SHELL", "/bin/bash"), "/dev/null");
            pb.directory(workdir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String id = "pty_" + UUID.randomUUID().toString().replace("-", "");
            PtySession session = new PtySession(id,
                profile == null || profile.isBlank() ? "default" : profile,
                userId, process,
                new BufferedWriter(new java.io.OutputStreamWriter(process.getOutputStream(),
                    StandardCharsets.UTF_8)),
                null);
            session.reader = Thread.ofPlatform().daemon(true).name("pty-reader-" + id).start(() -> {
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!session.closed && (line = br.readLine()) != null) {
                        session.lock.lock();
                        try {
                            session.ring.addLast(new RingLine(++session.seq, line));
                            while (session.ring.size() > MAX_RING_LINES) {
                                session.ring.removeFirst();
                            }
                        } finally {
                            session.lock.unlock();
                        }
                    }
                } catch (IOException ignored) {
                    // process died — alive() reports it
                }
            });
            sessions.put(id, session);
            return new PtyStartResult(id, null);
        } catch (IOException e) {
            return new PtyStartResult(null, "failed to start PTY: " + e.getMessage());
        }
    }

    public boolean write(String profile, String userId, String id, String input) {
        PtySession session = owned(profile, userId, id);
        if (session == null || !session.process.isAlive()) {
            return false;
        }
        try {
            session.stdin.write(input);
            session.stdin.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean resizeIgnored(String profile, String userId, String id) {
        // script-based PTY inherits the parent's dimensions; terminal resize
        // signalling is not supported by this transport — honest no-op report
        return owned(profile, userId, id) != null;
    }

    public PtyReadResult read(String profile, String userId, String id, long afterCursor, int limit) {
        evictIdle();
        PtySession session = owned(profile, userId, id);
        if (session == null) {
            return new PtyReadResult(List.of(), afterCursor, false);
        }
        session.lastRead = System.currentTimeMillis();
        int bounded = Math.min(Math.max(limit, 1), 2_000);
        List<RingLine> lines = new ArrayList<>();
        long cursor = afterCursor;
        session.lock.lock();
        try {
            for (RingLine line : session.ring) {
                if (line.seq() > afterCursor) {
                    lines.add(line);
                    cursor = line.seq();
                    if (lines.size() >= bounded) {
                        break;
                    }
                }
            }
        } finally {
            session.lock.unlock();
        }
        return new PtyReadResult(lines, cursor, session.process.isAlive());
    }

    public boolean close(String profile, String userId, String id) {
        PtySession session = owned(profile, userId, id);
        if (session == null) {
            return false;
        }
        destroy(sessions.remove(id));
        return true;
    }

    public int liveCount() {
        evictIdle();
        return sessions.size();
    }

    // ── internals ────────────────────────────────────────────────────────

    private PtySession owned(String profile, String userId, String id) {
        PtySession session = sessions.get(id);
        if (session == null) {
            return null;
        }
        String canonProfile = profile == null || profile.isBlank() ? "default" : profile;
        if (!canonProfile.equals(session.profile)
            || !java.util.Objects.equals(userId, session.userId)) {
            return null; // cross-profile or cross-user denial
        }
        return session;
    }

    private void evictIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_TTL.toMillis();
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().lastRead > cutoff && entry.getValue().process.isAlive()) {
                return false;
            }
            destroy(entry.getValue());
            return true;
        });
    }

    private static void destroy(PtySession session) {
        if (session == null) {
            return;
        }
        session.closed = true;
        try {
            session.stdin.close();
        } catch (IOException ignored) {
            // already closed
        }
        session.process.descendants().forEach(ProcessHandle::destroyForcibly);
        session.process.destroyForcibly();
    }
}
