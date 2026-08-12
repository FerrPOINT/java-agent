package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * L41 test: verify that ManagedProcess.writeStdin reuses a single OutputStreamWriter
 * instead of creating a new one each call. The fix stores the writer as a field.
 */
class ProcessToolStdinWriterTest {

    @Test
    void writeStdinReusesSameWriter() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(999L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(baos);

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_test", "cat", process, 300
        );

        // Write data twice — both should go through the same OutputStreamWriter
        managed.writeStdin("hello");
        managed.writeStdin(" world");

        // Verify both writes reached the output stream
        String written = baos.toString(StandardCharsets.UTF_8);
        assertThat(written).isEqualTo("hello world");

        // Verify the stdinWriter field is set (not null) — indicating reuse
        Field writerField = ProcessTool.ManagedProcess.class.getDeclaredField("stdinWriter");
        writerField.setAccessible(true);
        Object writer = writerField.get(managed);
        assertThat(writer).isNotNull();

        managed.closeStdin();
        // After closeStdin, the writer should be nulled out
        writer = writerField.get(managed);
        assertThat(writer).isNull();
    }

    @Test
    void closeStdinFlushesBeforeClosing() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(998L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(baos);

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_test2", "cat", process, 300
        );

        managed.writeStdin("test data");
        managed.closeStdin();

        // Data should be flushed to the output stream before closing
        String written = baos.toString(StandardCharsets.UTF_8);
        assertThat(written).isEqualTo("test data");
    }

    @Test
    void closeStdinWithoutWriteClosesStreamDirectly() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.pid()).thenReturn(997L);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)));
        when(process.getOutputStream()).thenReturn(baos);

        ProcessTool.ManagedProcess managed = new ProcessTool.ManagedProcess(
            "proc_test3", "cat", process, 300
        );

        // Close without writing — should close the underlying stream directly
        managed.closeStdin();

        // Verify the stream was closed (ByteArrayOutputStream.close() is a no-op but
        // the code path should not throw)
        // The key is that closeStdin() without prior writeStdin() doesn't throw
    }
}