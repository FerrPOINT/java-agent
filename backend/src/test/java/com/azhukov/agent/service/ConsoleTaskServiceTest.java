package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.ConsoleTaskEntity;
import com.azhukov.agent.persistence.entity.ConsoleTaskOutputEntity;
import com.azhukov.agent.persistence.repository.ConsoleTaskOutputRepository;
import com.azhukov.agent.persistence.repository.ConsoleTaskRepository;
import com.azhukov.agent.tools.terminal.CommandGuard;
import com.azhukov.agent.tools.terminal.ProcessTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-9 (ADR-015): durable console tasks — guard-before-create, cursor replay
 * without duplicates/gaps, idempotent cancel, retention cap.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleTaskServiceTest {

    @Mock
    private ProcessTool processTool;

    @Mock
    private ConsoleTaskRepository taskRepository;

    @Mock
    private ConsoleTaskOutputRepository outputRepository;

    @Mock
    private CommandGuard commandGuard;

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

    private ConsoleTaskService service() {
        lenient().when(taskRepository.save(any(ConsoleTaskEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(taskRepository.deleteExpired(any())).thenReturn(0);
        return new ConsoleTaskService(prov(processTool), prov(taskRepository),
            prov(outputRepository), prov(commandGuard), null);
    }

    private ConsoleTaskEntity task(String id, String state) {
        ConsoleTaskEntity entity = new ConsoleTaskEntity();
        entity.setId(id);
        entity.setProfile("default");
        entity.setCommand("echo hi");
        entity.setState(state);
        return entity;
    }

    @Test
    void blockedCommandNeverCreatesTask() throws java.io.IOException {
        when(commandGuard.check("rm -rf /")).thenReturn("destructive command");

        var result = service().start("default", "u1", "rm -rf /", null, 60);

        assertThat(result.httpStatus()).isEqualTo(403);
        assertThat(result.error()).contains("blocked");
        verify(taskRepository, never()).save(any());
        verify(processTool, never()).spawn(anyString(), anyInt());
        verify(processTool, never()).spawn(anyString(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.<java.util.function.Consumer<String>>any(),
            org.mockito.ArgumentMatchers.<String>any());
    }

    @Test
    void startCreatesRunningTaskAndSpawnsProcess() throws IOException {
        when(commandGuard.check("echo hi")).thenReturn(null);
        ProcessTool.ManagedProcess managed = org.mockito.Mockito.mock(ProcessTool.ManagedProcess.class);
        org.mockito.Mockito.doReturn(managed).when(processTool)
            .spawn("echo hi", 60, false, null, null);
        lenient().when(processTool.isProcessAlive(managed)).thenReturn(false);
        lenient().when(processTool.recentOutput(org.mockito.ArgumentMatchers.same(managed), anyInt()))
            .thenReturn(List.of("hi"));
        lenient().when(processTool.processId(managed)).thenReturn("ignored");

        var result = service().start("default", "u1", "echo hi", null, 60);

        assertThat(result.id()).startsWith("task_");
        verify(taskRepository).save(any(ConsoleTaskEntity.class));
    }

    @Test
    void cursorReplayNoDuplicatesNoGaps() {
        when(outputRepository.replayAfter(eq("t1"), eq(0L), any(Pageable.class)))
            .thenReturn(List.of(output("t1", 1, "a"), output("t1", 2, "b"), output("t1", 3, "c")));
        when(outputRepository.replayAfter(eq("t1"), eq(2L), any(Pageable.class)))
            .thenReturn(List.of(output("t1", 3, "c")));

        Map<String, Object> full = service().output("t1", 0, 100);
        Map<String, Object> tail = service().output("t1", 2, 100);

        assertThat((List<?>) full.get("lines")).hasSize(3);
        assertThat(full.get("cursor")).isEqualTo(3L);
        assertThat((List<?>) tail.get("lines")).hasSize(1);
        assertThat(tail.get("cursor")).isEqualTo(3L);
    }

    @Test
    void cancelIsIdempotentPerTerminalState() {
        when(taskRepository.deleteExpired(any())).thenReturn(0);
        when(taskRepository.findByIdAndProfile("t1", "default"))
            .thenReturn(Optional.of(task("t1", "completed")));

        boolean result = service().cancel("default", "t1");

        assertThat(result).isTrue();
        // already terminal — no second finish write
        verify(taskRepository, never()).finishTask(anyString(), anyString(), any(), any());
    }

    @Test
    void cancelRunningTaskKillsAndFinishesOnce() {
        when(taskRepository.deleteExpired(any())).thenReturn(0);
        when(taskRepository.findByIdAndProfile("t1", "default"))
            .thenReturn(Optional.of(task("t1", "running")));

        boolean result = service().cancel("default", "t1");

        assertThat(result).isTrue();
        verify(taskRepository).finishTask(eq("t1"), eq("cancelled"), any(), any());
    }

    @Test
    void appendLineAssignsMonotonicSequenceAndRedacts() {
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task("t1", "running")));
        when(outputRepository.findFirstByTaskIdOrderBySequenceDesc("t1"))
            .thenReturn(Optional.of(output("t1", 7, "prev")));

        long seq = service().appendLine("t1", "next line");

        assertThat(seq).isEqualTo(8);
        verify(outputRepository).save(any(ConsoleTaskOutputEntity.class));
    }

    @Test
    void appendLineUnknownTaskReturnsMinusOne() {
        when(taskRepository.findById("missing")).thenReturn(Optional.empty());
        assertThat(service().appendLine("missing", "x")).isEqualTo(-1);
    }

    private ConsoleTaskOutputEntity output(String taskId, long seq, String line) {
        ConsoleTaskOutputEntity entity = new ConsoleTaskOutputEntity();
        entity.setTaskId(taskId);
        entity.setSequence(seq);
        entity.setLine(line);
        return entity;
    }
}
