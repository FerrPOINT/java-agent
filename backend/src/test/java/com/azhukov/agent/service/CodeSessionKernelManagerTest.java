package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.CodeSessionKernelEntity;
import com.azhukov.agent.persistence.repository.CodeSessionKernelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-7: session kernel — persistent state across calls, cross-session
 * isolation, same-session serialization, reset, restart kernel_lost.
 * Requires python3 on PATH (skipped otherwise).
 */
@ExtendWith(MockitoExtension.class)
class CodeSessionKernelManagerTest {

    @Mock
    private CodeSessionKernelRepository repository;

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "--version").start();
            boolean ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> ObjectProvider<T> prov(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private CodeSessionKernelManager manager() {
        lenient().when(repository.findBySessionId(any(UUID.class))).thenReturn(Optional.empty());
        lenient().when(repository.findByStateAndExpiresAtBefore(any(String.class), any()))
            .thenReturn(List.of());
        lenient().when(repository.save(any(CodeSessionKernelEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().doNothing().when(repository).deleteBySessionId(any(UUID.class));
        return new CodeSessionKernelManager(prov(repository));
    }

    @Test
    void statePersistsAcrossCallsInSameSession() {
        if (!pythonAvailable()) {
            return;
        }
        var kernel = manager();
        UUID session = UUID.randomUUID();

        Map<String, Object> first = kernel.evaluate(session, "default", "u1", "x = 2", null);
        Map<String, Object> second = kernel.evaluate(session, "default", "u1", "print(x + 2)", null);

        assertThat(first.get("status")).isEqualTo("success");
        assertThat(second.get("status")).isEqualTo("success");
        assertThat(String.valueOf(second.get("output"))).isEqualTo("4");
        kernel.reset(session);
    }

    @Test
    void sessionsAreIsolated() {
        if (!pythonAvailable()) {
            return;
        }
        var kernel = manager();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        kernel.evaluate(a, "default", "u1", "y = 'A'", null);
        Map<String, Object> other = kernel.evaluate(b, "default", "u2", "print('y' in globals())", null);

        // session B never sees session A's y
        assertThat(String.valueOf(other.get("output"))).isEqualTo("False");
        kernel.reset(a);
        kernel.reset(b);
    }

    @Test
    void resetClearsKernelState() {
        if (!pythonAvailable()) {
            return;
        }
        var kernel = manager();
        UUID session = UUID.randomUUID();

        kernel.evaluate(session, "default", "u1", "z = 100", null);
        kernel.reset(session);
        Map<String, Object> after = kernel.evaluate(session, "default", "u1",
            "print('z' in globals())", null);

        assertThat(String.valueOf(after.get("output"))).isEqualTo("False");
        kernel.reset(session);
    }

    @Test
    void timeoutKillsKernelAndReports() {
        if (!pythonAvailable()) {
            return;
        }
        var kernel = manager();
        UUID session = UUID.randomUUID();

        Map<String, Object> result = kernel.evaluate(session, "default", "u1",
            "import time\ntime.sleep(30)", 2);

        assertThat(result.get("status")).isEqualTo("timeout");
        assertThat(String.valueOf(result.get("error"))).contains("timed out");
        assertThat(kernel.liveKernelCount()).isZero();
        verify(repository).findBySessionId(session);
    }

    @Test
    void restartedPersistedKernelIsMarkedLost() {
        CodeSessionKernelEntity persisted = new CodeSessionKernelEntity();
        persisted.setKernelId("kernel_old");
        UUID session = UUID.randomUUID();
        persisted.setSessionId(session);
        persisted.setState("alive");
        // no live process in THIS manager → must be marked lost
        when(repository.findByStateAndExpiresAtBefore(any(String.class), any()))
            .thenReturn(List.of(persisted))
            .thenReturn(List.of());

        int marked = new CodeSessionKernelManager(prov(repository)).markRestartedKernelsLost();

        assertThat(marked).isEqualTo(1);
        assertThat(persisted.getState()).isEqualTo("lost");
    }

    @Test
    void evaluationSurvivesLargeOutputWithCap() {
        if (!pythonAvailable()) {
            return;
        }
        var kernel = manager();
        UUID session = UUID.randomUUID();

        Map<String, Object> result = kernel.evaluate(session, "default", "u1",
            "print('x' * 200000)", null);

        assertThat(result.get("status")).isEqualTo("success");
        assertThat(String.valueOf(result.get("output")).length())
            .isLessThanOrEqualTo(CodeSessionKernelManager.MAX_OUTPUT_CHARS + 200);
        kernel.reset(session);
    }

    @Test
    void resetWithoutKernelIsIdempotent() {
        var kernel = manager();
        UUID session = UUID.randomUUID();
        kernel.reset(session);
        verify(repository, never()).deleteBySessionId(session);
    }
}
