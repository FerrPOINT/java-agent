package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TodoToolTest {

    private static final String USER_ID = "user-42";
    private static final Session SESSION = Session.create(USER_ID, "noop", "default");
    private static final Message LAST_MESSAGE = Message.user("test prompt");

    @Mock
    private TodoRepository todoRepository;

    // ── Read (no args = list) ──

    @Test
    @DisplayName("No todos arg → read current list")
    void noArgs_readsList() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserIdOrderByCreatedAtAsc(USER_ID)).thenReturn(List.of());

        ToolResult result = tool.execute("{}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("No todos");
    }

    @Test
    @DisplayName("Empty list returns 'No todos'")
    void emptyList_returnsNoTodos() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserIdOrderByCreatedAtAsc(USER_ID)).thenReturn(List.of());

        ToolResult result = tool.execute("{\"todos\":null}", LAST_MESSAGE, SESSION);

        // todos=null → read mode (Hermes parity)
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("No todos");
    }

    // ── Write (merge=false = replace) ──

    @Test
    @DisplayName("merge=false (default): replace entire list")
    void write_replaceAll() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserIdOrderByCreatedAtAsc(USER_ID)).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(todoRepository).deleteByUserId(USER_ID);

        String args = "{\"todos\":[{\"id\":\"1\",\"content\":\"Task A\",\"status\":\"pending\"}," +
            "{\"id\":\"2\",\"content\":\"Task B\",\"status\":\"in_progress\"}]}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        // Should delete all existing todos (replace mode)
        verify(todoRepository).deleteByUserId(USER_ID);
        // Should save 2 new entities
        verify(todoRepository, times(2)).save(any(TodoEntity.class));
    }

    // ── Write (merge=true = update by id) ──

    @Test
    @DisplayName("merge=true: update existing items by id")
    void merge_updateById() {
        TodoTool tool = new TodoTool(todoRepository);
        UUID existingId = UUID.randomUUID();
        TodoEntity existing = new TodoEntity();
        existing.setId(existingId);
        existing.setUserId(USER_ID);
        existing.setTitle("Old title");
        existing.setStatus("pending");
        existing.setPriority("medium");
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());

        when(todoRepository.findById(existingId)).thenReturn(Optional.of(existing));
        when(todoRepository.findByUserIdOrderByCreatedAtAsc(USER_ID))
            .thenReturn(List.of(existing));

        String args = "{\"todos\":[{\"id\":\"" + existingId + "\",\"content\":\"Updated title\",\"status\":\"completed\"}],\"merge\":true}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        // Should NOT delete (merge mode)
        verify(todoRepository, never()).deleteByUserId(any());
        // Should update existing entity
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Updated title");
        assertThat(captor.getValue().getStatus()).isEqualTo("completed");
    }

    // ── Validation ──

    @Test
    @DisplayName("Reject invalid status")
    void rejectInvalidStatus() {
        TodoTool tool = new TodoTool(todoRepository);

        String args = "{\"todos\":[{\"id\":\"1\",\"content\":\"Bad\",\"status\":\"invalid\"}]}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid status");
    }

    @Test
    @DisplayName("Reject content exceeding max chars")
    void rejectContentTooLong() {
        TodoTool tool = new TodoTool(todoRepository);
        String longContent = "x".repeat(TodoTool.MAX_CONTENT_CHARS + 1);

        String args = "{\"todos\":[{\"id\":\"1\",\"content\":\"" + longContent + "\",\"status\":\"pending\"}]}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exceeds");
    }

    @Test
    @DisplayName("Reject missing content")
    void rejectMissingContent() {
        TodoTool tool = new TodoTool(todoRepository);

        String args = "{\"todos\":[{\"id\":\"1\",\"status\":\"pending\"}]}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("content");
    }

    @Test
    @DisplayName("Reject exceeding max items")
    void rejectTooManyItems() {
        TodoTool tool = new TodoTool(todoRepository);

        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < TodoTool.MAX_ITEMS + 1; i++) {
            if (i > 0) items.append(",");
            items.append("{\"id\":\"").append(i).append("\",\"content\":\"T").append(i).append("\",\"status\":\"pending\"}");
        }
        items.append("]");

        String args = "{\"todos\":" + items + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("maximum");
    }

    // ── Default status ──

    @Test
    @DisplayName("Default status is pending when not specified")
    void defaultStatusPending() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserIdOrderByCreatedAtAsc(USER_ID)).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(todoRepository).deleteByUserId(USER_ID);

        String args = "{\"todos\":[{\"id\":\"1\",\"content\":\"No status\"}]}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("pending");
    }

    // ── Read returns summary ──

    @Test
    @DisplayName("Read returns summary with counts")
    void read_returnsSummary() {
        TodoTool tool = new TodoTool(todoRepository);
        TodoEntity t1 = new TodoEntity();
        t1.setId(UUID.randomUUID());
        t1.setUserId(USER_ID);
        t1.setTitle("Pending task");
        t1.setStatus("pending");
        t1.setCreatedAt(Instant.now());
        t1.setUpdatedAt(Instant.now());

        TodoEntity t2 = new TodoEntity();
        t2.setId(UUID.randomUUID());
        t2.setUserId(USER_ID);
        t2.setTitle("Completed task");
        t2.setStatus("completed");
        t2.setCreatedAt(Instant.now());
        t2.setUpdatedAt(Instant.now());

        when(todoRepository.findByUserIdOrderByCreatedAtAsc(USER_ID))
            .thenReturn(List.of(t1, t2));

        ToolResult result = tool.execute("{}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("pending=1");
        assertThat(result.content()).contains("completed=1");
        assertThat(result.content()).contains("total=2");
    }

    // ── Numeric id resolution ──

    @Test
    @DisplayName("Numeric id resolves to position in merge mode")
    void numericId_resolvesToPosition() {
        TodoTool tool = new TodoTool(todoRepository);
        UUID existingId = UUID.randomUUID();
        TodoEntity existing = new TodoEntity();
        existing.setId(existingId);
        existing.setUserId(USER_ID);
        existing.setTitle("Old");
        existing.setStatus("pending");
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());

        when(todoRepository.findByUserIdOrderByCreatedAtAsc(USER_ID))
            .thenReturn(List.of(existing));
        when(todoRepository.findById(existingId)).thenReturn(Optional.of(existing));

        // Use "1" (1-based position) instead of UUID
        String args = "{\"todos\":[{\"id\":\"1\",\"content\":\"Updated\",\"status\":\"completed\"}],\"merge\":true}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(todoRepository).findById(existingId);
        verify(todoRepository).save(any(TodoEntity.class));
    }

    // ── Status validation helper ──

    @Test
    @DisplayName("validateStatus: null/blank returns fallback")
    void validateStatus_nullReturnsFallback() {
        assertThat(TodoTool.validateStatus(null, "pending")).isEqualTo("pending");
        assertThat(TodoTool.validateStatus("", "pending")).isEqualTo("pending");
        assertThat(TodoTool.validateStatus("  ", "pending")).isEqualTo("pending");
    }

    @Test
    @DisplayName("validateStatus: valid returns normalized")
    void validateStatus_validReturnsNormalized() {
        assertThat(TodoTool.validateStatus("PENDING", null)).isEqualTo("pending");
        assertThat(TodoTool.validateStatus("In_Progress", null)).isEqualTo("in_progress");
        assertThat(TodoTool.validateStatus("COMPLETED", null)).isEqualTo("completed");
        assertThat(TodoTool.validateStatus("cancelled", null)).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("validateStatus: invalid returns null")
    void validateStatus_invalidReturnsNull() {
        assertThat(TodoTool.validateStatus("done", null)).isNull();
        assertThat(TodoTool.validateStatus("waiting", null)).isNull();
        assertThat(TodoTool.validateStatus("todo", null)).isNull();
    }

    // ── Stringified JSON array regression (DEFECT-01) ──

    @Test
    @DisplayName("todos as array of JSON strings (LLM wraps each item as string)")
    void todosAsStringifiedItems() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserIdOrderByCreatedAtAsc(USER_ID)).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(todoRepository).deleteByUserId(USER_ID);

        // Model sends: {"todos": ["{\"id\":\"1\",\"content\":\"Task\",\"status\":\"pending\"}"]}
        String args = "{\"todos\":[\"{\\\"id\\\":\\\"1\\\",\\\"content\\\":\\\"Task\\\",\\\"status\\\":\\\"pending\\\"}\"]}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Task");
        assertThat(captor.getValue().getStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("todos as single JSON string (not array) — TodoListDeserializer path")
    void todosAsSingleJsonString() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserIdOrderByCreatedAtAsc(USER_ID)).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(todoRepository).deleteByUserId(USER_ID);

        // Model sends: {"todos": "[{\"id\":\"1\",\"content\":\"Task\",\"status\":\"pending\"}]"}
        String args = "{\"todos\":\"[{\\\"id\\\":\\\"1\\\",\\\"content\\\":\\\"Task\\\",\\\"status\\\":\\\"pending\\\"}]\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(todoRepository).save(any(TodoEntity.class));
    }
}