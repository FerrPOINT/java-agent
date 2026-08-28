package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Hermes parity (memory_tool.py:1054 _missing_old_text_error): replace/remove
 * without old_text must return the current inventory + retry instruction,
 * not a dead-end error.
 */
@ExtendWith(MockitoExtension.class)
class MemoryToolMissingOldTextTest {

    @Mock private MemoryProvider memoryProvider;

    private MemoryTool tool;
    private Session session;

    @BeforeEach
    void setUp() {
        tool = new MemoryTool(memoryProvider, null);
        session = new Session(UUID.randomUUID(), "user-1", "test", "openai-compatible", "gpt-4", null, Map.of(), null);
        lenient().when(memoryProvider.read(anyString(), anyString())).thenReturn("- uses pytest");
        lenient().when(memoryProvider.getCharCount(anyString(), anyString())).thenReturn(120);
    }

    @Test
    void replaceWithoutOldTextReturnsInventoryForRetry() {
        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"content\":\"new text\"}",
            Message.assistant("t", 0), session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("needs old_text");
        assertThat(result.error()).contains("Reissue the replace");
        assertThat(result.error()).contains("uses pytest");
    }

    @Test
    void removeWithoutOldTextReturnsInventoryForRetry() {
        ToolResult result = tool.execute(
            "{\"action\":\"remove\"}",
            Message.assistant("t", 0), session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("needs old_text");
        assertThat(result.error()).contains("Reissue the remove");
        assertThat(result.error()).contains("uses pytest");
    }
}
