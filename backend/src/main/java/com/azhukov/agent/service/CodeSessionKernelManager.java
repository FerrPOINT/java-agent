package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.CodeSessionKernelEntity;
import com.azhukov.agent.persistence.repository.CodeSessionKernelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WP-7 (docs/35): execute-code session kernel.
 *
 * <p>One persistent Python process per session, serialized evaluation (a
 * per-kernel ReentrantLock — state like {@code x=2} survives across calls),
 * explicit reset, idle TTL eviction, max live kernels, hard timeout with
 * kill. Only kernel IDENTITY/lease persists (V60) — after a server restart
 * persisted rows are marked {@code lost} and callers receive
 * {@code kernel_lost} instead of a zombie handle; the client recreates
 * deliberately. No inherited secrets beyond an allowlist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeSessionKernelManager {

    public static final int MAX_LIVE_KERNELS = 16;
    public static final int MAX_OUTPUT_CHARS = 50_000;
    private static final Duration IDLE_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_EVALUATION = Duration.ofMinutes(5);
    /** Env allowlist — kernels never inherit arbitrary host secrets. */
    private static final List<String> ENV_ALLOWLIST = List.of("PATH", "HOME", "LANG", "LC_ALL",
        "PYTHONIOENCODING", "TMPDIR", "PYTHONPATH");

    private final ObjectProvider<CodeSessionKernelRepository> repositoryProvider;

    private final ConcurrentHashMap<UUID, LiveKernel> live = new ConcurrentHashMap<>();

    private record KernelResult(String status, String output, int exitCode, String error,
                                boolean kernelLost) {}

    /**
     * Single reader thread per kernel: reads stdout lines into a synchronized
     * handoff buffer. Per-eval readers on the raw stream would compete and
     * steal each other's lines.
     */
    private static final class LiveKernel {
        final Process process;
        final BufferedWriter stdin;
        final ReentrantLock lock = new ReentrantLock();
        final java.util.concurrent.ArrayBlockingQueue<String> lines =
            new java.util.concurrent.ArrayBlockingQueue<>(10_000);
        final Thread readerThread;
        volatile Instant lastUsed = Instant.now();
        volatile boolean closed;

        LiveKernel(Process process, BufferedWriter stdin) {
            this.process = process;
            this.stdin = stdin;
            this.readerThread = Thread.ofPlatform().daemon(true)
                .name("code-kernel-reader").start(new Thread(() -> {
                    try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                            process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!closed && (line = br.readLine()) != null) {
                            lines.offer(line);
                        }
                    } catch (IOException ignored) {
                        // process died
                    }
                }, "code-kernel-reader"));
        }
    }

    // ── public API ───────────────────────────────────────────────────────

    public Map<String, Object> evaluate(UUID sessionId, String profile, String userId, String code,
                                        Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null
            ? (int) MAX_EVALUATION.toSeconds()
            : Math.min(Math.max(timeoutSeconds, 1), (int) MAX_EVALUATION.toSeconds());
        markRestartedKernelsLost();
        evictExpired();
        try {
            LiveKernel kernel = acquire(sessionId, profile, userId);
            KernelResult result = evalLocked(kernel, sessionId, code, timeout);
            touch(sessionId, result.kernelLost() ? "lost" : "alive");
            return response(result, sessionId);
        } catch (Exception e) {
            log.warn("Session kernel evaluation failed for {}: {}", sessionId, e.getMessage());
            return response(new KernelResult("error", "", -1,
                "kernel evaluation failed: " + e.getMessage(), false), sessionId);
        }
    }

    public boolean reset(UUID sessionId) {
        LiveKernel kernel = live.remove(sessionId);
        if (kernel != null) {
            kill(kernel);
        }
        CodeSessionKernelRepository repository = repository();
        repository.findBySessionId(sessionId).ifPresent(row -> {
            row.setState("killed");
            repository.save(row);
            repository.deleteBySessionId(sessionId);
        });
        return true;
    }

    /** Mark persisted kernels from a PREVIOUS process as lost (startup/sweep). */
    public int markRestartedKernelsLost() {
        int marked = 0;
        for (CodeSessionKernelEntity row : repository().findByStateAndExpiresAtBefore(
                "alive", Instant.now().plus(IDLE_TTL))) {
            UUID sessionId = row.getSessionId();
            if (!live.containsKey(sessionId)) {
                // persisted row without a live process in THIS process → kernel_lost
                row.setState("lost");
                repository().save(row);
                marked++;
            }
        }
        return marked;
    }

    public void evictExpired() {
        Instant now = Instant.now();
        live.entrySet().removeIf(entry -> {
            if (entry.getValue().lastUsed.plus(IDLE_TTL).isAfter(now)) {
                return false;
            }
            kill(entry.getValue());
            repository().findBySessionId(entry.getKey()).ifPresent(row -> {
                row.setState("expired");
                repository().save(row);
            });
            return true;
        });
    }

    public int liveKernelCount() {
        return live.size();
    }

    // ── internals ────────────────────────────────────────────────────────

    private LiveKernel acquire(UUID sessionId, String profile, String userId) throws IOException {
        LiveKernel existing = live.get(sessionId);
        if (existing != null && existing.process.isAlive()) {
            return existing;
        }
        if (existing != null) {
            kill(existing);
            live.remove(sessionId);
        }
        if (live.size() >= MAX_LIVE_KERNELS) {
            evictExpired();
            if (live.size() >= MAX_LIVE_KERNELS) {
                throw new IllegalStateException("kernel capacity reached (" + MAX_LIVE_KERNELS
                    + ") — reset idle sessions first");
            }
        }
        LiveKernel created = spawn(sessionId, profile, userId);
        live.put(sessionId, created);
        return created;
    }

    private static final String CELL_SENTINEL = "###__CELL_END__###";

    /**
     * Persistent driver: reads code cells from stdin (terminated by the cell
     * sentinel line), execs them in the kernel globals, writes an error line
     * (if any) and the done marker. No REPL → no banner, no prompts, no
     * buffered-reader-ahead pitfalls.
     */
    private static final String DRIVER = String.join("\n",
        "import sys",
        "buf = []",
        "while True:",
        "    line = sys.stdin.readline()",
        "    if not line:",
        "        break",
        "    if line.strip() == '" + CELL_SENTINEL + "':",
        "        code = chr(10).join(buf)",
        "        buf = []",
        "        try:",
        "            exec(compile(code, '<cell>', 'exec'), globals())",
        "        except BaseException as e:",
        "            sys.stdout.write('KERNEL_ERROR: %s: %s' % (type(e).__name__, e) + chr(10))",
        "        sys.stdout.write('" + CELL_SENTINEL + "' + chr(10))",
        "        sys.stdout.flush()",
        "    else:",
        "        buf.append(line.rstrip(chr(10)))",
        "sys.exit(0)");

    private LiveKernel spawn(UUID sessionId, String profile, String userId) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("python3", "-u", "-c", DRIVER);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.keySet().retainAll(ENV_ALLOWLIST);
        env.put("PYTHONIOENCODING", "utf-8");
        Process process = pb.start();
        LiveKernel kernel = new LiveKernel(process,
            new BufferedWriter(new java.io.OutputStreamWriter(process.getOutputStream(),
                StandardCharsets.UTF_8)));

        CodeSessionKernelRepository repository = repository();
        CodeSessionKernelEntity row = repository.findBySessionId(sessionId)
            .orElseGet(() -> {
                CodeSessionKernelEntity created = new CodeSessionKernelEntity();
                created.setSessionId(sessionId);
                created.setKernelId("kernel_" + UUID.randomUUID().toString().replace("-", ""));
                created.setProfile(profile == null || profile.isBlank() ? "default" : profile);
                created.setUserId(userId);
                return created;
            });
        row.setState("alive");
        row.setLastHeartbeatAt(Instant.now());
        row.setExpiresAt(Instant.now().plus(IDLE_TTL));
        repository.save(row);
        log.info("Session kernel spawned for {} (kernel {})", sessionId, row.getKernelId());
        return kernel;
    }

    private KernelResult evalLocked(LiveKernel kernel, UUID sessionId, String code, int timeoutSeconds) {
        kernel.lock.lock();
        try {
            // cell protocol: code lines + sentinel; driver execs and echoes
            // the sentinel back when the cell (including prints) finished
            String wrapped = code.strip() + "\n" + CELL_SENTINEL + "\n";
            kernel.stdin.write(wrapped);
            kernel.stdin.flush();
            kernel.lastUsed = Instant.now();
            String marker = CELL_SENTINEL;

            StringBuilder output = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            boolean sawMarker = false;
            while (System.currentTimeMillis() < deadline) {
                String line = kernel.lines.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (!kernel.process.isAlive() && kernel.lines.isEmpty()) {
                        break; // kernel died mid-cell
                    }
                    continue;
                }
                if (line.contains(marker)) {
                    sawMarker = true;
                    break;
                }
                output.append(line).append('\n');
                if (output.length() > MAX_OUTPUT_CHARS * 2) {
                    break; // hard cap while reading
                }
            }
            if (!sawMarker && output.length() == 0) {
                kill(kernel);
                live.remove(sessionId);
                return new KernelResult("timeout", "", -1,
                    "kernel evaluation timed out after " + timeoutSeconds + "s; kernel killed", false);
            }
            String raw = output.toString();
            boolean pythonError = raw.contains("KERNEL_ERROR:");
            String text = raw.length() > MAX_OUTPUT_CHARS
                ? raw.substring(0, MAX_OUTPUT_CHARS / 2)
                    + "\n... [truncated " + (raw.length() - MAX_OUTPUT_CHARS) + " chars] ...\n"
                    + raw.substring(raw.length() - MAX_OUTPUT_CHARS / 2)
                : raw;
            if (pythonError) {
                String errorLine = java.util.Arrays.stream(raw.split("\n"))
                    .filter(l -> l.startsWith("KERNEL_ERROR:"))
                    .reduce((a, b) -> b).orElse("python error");
                return new KernelResult("error", text.strip(), 1,
                    errorLine.substring("KERNEL_ERROR:".length()).strip(), false);
            }
            return new KernelResult("success", text.strip(), 0, null, false);
        } catch (Exception e) {
            kill(kernel);
            live.remove(sessionId);
            return new KernelResult("error", "", -1,
                "kernel died during evaluation: " + e.getMessage(), false);
        } finally {
            kernel.lock.unlock();
        }
    }

    /** Strip interactive REPL artifacts (`>>> `, `... `) from captured lines. */
    private static String cleanReplLine(String line) {
        if (line == null) {
            return "";
        }
        String cleaned = line;
        while (cleaned.startsWith(">>> ") || cleaned.startsWith("... ")) {
            cleaned = cleaned.substring(4);
        }
        if (cleaned.equals(">>>") || cleaned.equals("...")) {
            return "";
        }
        return cleaned;
    }

    private void touch(UUID sessionId, String state) {
        try {
            if ("alive".equals(state)) {
                repository().heartbeat(sessionId, Instant.now(), Instant.now().plus(IDLE_TTL));
            } else {
                repository().findBySessionId(sessionId).ifPresent(row -> {
                    row.setState(state);
                    repository().save(row);
                });
            }
        } catch (Exception e) {
            log.debug("Kernel heartbeat failed for {}: {}", sessionId, e.getMessage());
        }
    }

    private static void kill(LiveKernel kernel) {
        try {
            kernel.stdin.close();
        } catch (IOException ignored) {
            // already closed
        }
        kernel.process.destroyForcibly();
    }

    private static Map<String, Object> response(KernelResult result, UUID sessionId) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", result.status());
        payload.put("output", result.output());
        payload.put("exit_code", result.exitCode());
        payload.put("execution_mode", "session_kernel");
        payload.put("session_id", sessionId.toString());
        if (result.kernelLost()) {
            payload.put("kernel_lost", true);
        }
        if (result.error() != null && !result.error().isBlank()) {
            payload.put("error", result.error());
        }
        payload.put("tool_calls_made", 0);
        return payload;
    }

    private CodeSessionKernelRepository repository() {
        CodeSessionKernelRepository repository = repositoryProvider == null
            ? null : repositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("kernel repository is unavailable");
        }
        return repository;
    }
}
