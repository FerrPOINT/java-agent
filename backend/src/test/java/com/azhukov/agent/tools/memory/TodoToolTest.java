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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoToolTest {

    private static final String USER_ID = "user-42";
    private static final Session SESSION = Session.create(USER_ID, "noop", "default");
    private static final Message LAST_MESSAGE = Message.user("test prompt");

    @Mock
    private TodoRepository todoRepository;

    // ── Existing create/list tests (kept and enhanced) ──

    @Test
    @DisplayName("Should create a todo with default pending status")
    void shouldCreateTodoWithPendingStatus() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserId(USER_ID)).thenReturn(List.of());
        String args = "{\"action\":\"create\",\"title\":\"Write tests\",\"priority\":\"high\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("Created todo: Write tests");
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository).save(captor.capture());
        TodoEntity saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo(SESSION.id());
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getTitle()).isEqualTo("Write tests");
        assertThat(saved.getStatus()).isEqualTo("pending");
        assertThat(saved.getPriority()).isEqualTo("high");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should create a todo with in_progress status")
    void shouldCreateTodoWithInProgressStatus() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserId(USER_ID)).thenReturn(List.of());
        String args = "{\"action\":\"create\",\"title\":\"Active task\",\"status\":\"in_progress\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("in_progress");
    }

    @Test
    @DisplayName("Should create a todo with cancelled status")
    void shouldCreateTodoWithCancelledStatus() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserId(USER_ID)).thenReturn(List.of());
        String args = "{\"action\":\"create\",\"title\":\"Cancelled task\",\"status\":\"cancelled\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("Should create a todo with completed status")
    void shouldCreateTodoWithCompletedStatus() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserId(USER_ID)).thenReturn(List.of());
        String args = "{\"action\":\"create\",\"title\":\"Done task\",\"status\":\"completed\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("Should reject invalid status on create")
    void shouldRejectInvalidStatusOnCreate() {
        TodoTool tool = new TodoTool(todoRepository);
        String args = "{\"action\":\"create\",\"title\":\"Test\",\"status\":\"done\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid status");
        assertThat(result.error()).contains("done");
    }

    @Test
    @DisplayName("Should reject blank title on create")
    void shouldRejectBlankTitleOnCreate() {
        TodoTool tool = new TodoTool(todoRepository);
        String args = "{\"action\":\"create\",\"title\":\"  \"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Title is required");
    }

    @Test
    @DisplayName("Should reject title exceeding MAX_CONTENT_CHARS")
    void shouldRejectTitleExceedingMaxChars() {
        TodoTool tool = new TodoTool(todoRepository);
        String longTitle = "x".repeat(TodoTool.MAX_CONTENT_CHARS + 1);
        String args = "{\"action\":\"create\",\"title\":\"" + longTitle + "\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exceeds " + TodoTool.MAX_CONTENT_CHARS);
    }

    @Test
    @DisplayName("Should reject create when MAX_ITEMS reached")
    void shouldRejectCreateWhenMaxItemsReached() {
        TodoTool tool = new TodoTool(todoRepository);
        List<TodoEntity> existing = new ArrayList<>();
        for (int i = 0; i < TodoTool.MAX_ITEMS; i++) {
            TodoEntity e = new TodoEntity();
            e.setTitle("Todo " + i);
            existing.add(e);
        }
        when(todoRepository.findByUserId(USER_ID)).thenReturn(existing);
        String args = "{\"action\":\"create\",\"title\":\"One too many\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Maximum of " + TodoTool.MAX_ITEMS);
    }

    // ── List tests ──

    @Test
    @DisplayName("Should list todos with all statuses")
    void shouldListTodosWithAllStatuses() {
        TodoTool tool = new TodoTool(todoRepository);
        TodoEntity t1 = new TodoEntity();
        t1.setTitle("Write tests");
        t1.setStatus("pending");
        t1.setPriority("high");
        TodoEntity t2 = new TodoEntity();
        t2.setTitle("Refactor code");
        t2.setStatus("in_progress");
        t2.setPriority("medium");
        TodoEntity t3 = new TodoEntity();
        t3.setTitle("Done task");
        t3.setStatus("cancelled");
        t3.setPriority("low");
        when(todoRepository.findByUserId(USER_ID)).thenReturn(List.of(t1, t2, t3));
        String args = "{\"action\":\"list\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("[pending]");
        assertThat(result.content()).contains("[in_progress]");
        assertThat(result.content()).contains("[cancelled]");
    }

    @Test
    @DisplayName("Should list no todos")
    void shouldListNoTodos() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserId(USER_ID)).thenReturn(List.of());
        String args = "{\"action\":\"list\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("No todos.");
    }

    @Test
    @DisplayName("Should list todos with limit applied")
    void shouldListTodosWithLimit() {
        TodoTool tool = new TodoTool(todoRepository);
        List<TodoEntity> todos = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TodoEntity e = new TodoEntity();
            e.setTitle("Todo " + i);
            e.setStatus("pending");
            e.setPriority("medium");
            todos.add(e);
        }
        when(todoRepository.findByUserId(USER_ID)).thenReturn(todos);
        String args = "{\"action\":\"list\",\"limit\":3}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        long lines = result.content().lines().count();
        assertThat(lines).isEqualTo(3);
    }

    // ── Update tests ──

    @Test
    @DisplayName("Should update existing todo by id")
    void shouldUpdateExistingTodoById() {
        TodoTool tool = new TodoTool(todoRepository);
        UUID todoId = UUID.randomUUID();
        TodoEntity existing = new TodoEntity();
        existing.setId(todoId);
        existing.setUserId(USER_ID);
        existing.setTitle("Old title");
        existing.setStatus("pending");
        existing.setPriority("low");
        when(todoRepository.findById(todoId)).thenReturn(Optional.of(existing));
        String args = "{\"action\":\"update\",\"id\":\"" + todoId + "\",\"title\":\"New title\",\"status\":\"in_progress\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Updated todo");
        assertThat(existing.getTitle()).isEqualTo("New title");
        assertThat(existing.getStatus()).isEqualTo("in_progress");
        verify(todoRepository).save(existing);
    }

    @Test
    @DisplayName("Should reject update when todo not found")
    void shouldRejectUpdateWhenTodoNotFound() {
        TodoTool tool = new TodoTool(todoRepository);
        UUID todoId = UUID.randomUUID();
        when(todoRepository.findById(todoId)).thenReturn(Optional.empty());
        String args = "{\"action\":\"update\",\"id\":\"" + todoId + "\",\"status\":\"completed\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    @DisplayName("Should reject update with invalid status")
    void shouldRejectUpdateWithInvalidStatus() {
        TodoTool tool = new TodoTool(todoRepository);
        UUID todoId = UUID.randomUUID();
        TodoEntity existing = new TodoEntity();
        existing.setId(todoId);
        existing.setUserId(USER_ID);
        existing.setTitle("Test");
        existing.setStatus("pending");
        when(todoRepository.findById(todoId)).thenReturn(Optional.of(existing));
        String args = "{\"action\":\"update\",\"id\":\"" + todoId + "\",\"status\":\"archived\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid status");
    }

    @Test
    @DisplayName("Should reject update without id")
    void shouldRejectUpdateWithoutId() {
        TodoTool tool = new TodoTool(todoRepository);
        String args = "{\"action\":\"update\",\"status\":\"completed\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("id is required");
    }

    @Test
    @DisplayName("Should reject update when todo belongs to different user")
    void shouldRejectUpdateWhenTodoBelongsToDifferentUser() {
        TodoTool tool = new TodoTool(todoRepository);
        UUID todoId = UUID.randomUUID();
        TodoEntity existing = new TodoEntity();
        existing.setId(todoId);
        existing.setUserId("different-user");
        existing.setStatus("pending");
        when(todoRepository.findById(todoId)).thenReturn(Optional.of(existing));
        String args = "{\"action\":\"update\",\"id\":\"" + todoId + "\",\"status\":\"completed\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    // ── Merge mode tests ──

    @Test
    @DisplayName("Should merge update existing item by id when merge=true")
    void shouldMergeUpdateExistingItemById() {
        TodoTool tool = new TodoTool(todoRepository);
        UUID todoId = UUID.randomUUID();
        TodoEntity existing = new TodoEntity();
        existing.setId(todoId);
        existing.setUserId(USER_ID);
        existing.setTitle("Old");
        existing.setStatus("pending");
        existing.setPriority("low");
        when(todoRepository.findById(todoId)).thenReturn(Optional.of(existing));

        String itemsJson = "[{\"id\":\"" + todoId + "\",\"title\":\"Updated\",\"status\":\"in_progress\"}]";
        String args = "{\"action\":\"merge\",\"items\":" + itemsJson + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("updated");
        assertThat(existing.getTitle()).isEqualTo("Updated");
        assertThat(existing.getStatus()).isEqualTo("in_progress");
        verify(todoRepository).save(existing);
    }

    @Test
    @DisplayName("Should merge create new items when no id provided")
    void shouldMergeCreateNewItemsWhenNoId() {
        TodoTool tool = new TodoTool(todoRepository);
        String itemsJson = "[{\"title\":\"New task\",\"status\":\"pending\"}]";
        String args = "{\"action\":\"merge\",\"items\":" + itemsJson + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("created");
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("New task");
        assertThat(captor.getValue().getStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("Should set replace all items when action=set")
    void shouldSetReplaceAllItems() {
        TodoTool tool = new TodoTool(todoRepository);
        String itemsJson = "[{\"title\":\"Task A\"},{\"title\":\"Task B\"}]";
        String args = "{\"action\":\"set\",\"items\":" + itemsJson + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Set todos");
        verify(todoRepository).deleteByUserId(USER_ID);
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
    }

    @Test
    @DisplayName("Should merge not delete existing items")
    void shouldMergeNotDeleteExisting() {
        TodoTool tool = new TodoTool(todoRepository);
        String itemsJson = "[{\"title\":\"Task A\"}]";
        String args = "{\"action\":\"merge\",\"items\":" + itemsJson + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        // merge mode should NOT call deleteByUserId
        verify(todoRepository, org.mockito.Mockito.never()).deleteByUserId(any());
    }

    @Test
    @DisplayName("Should merge handle mixed update and create")
    void shouldMergeHandleMixedUpdateAndCreate() {
        TodoTool tool = new TodoTool(todoRepository);
        UUID todoId = UUID.randomUUID();
        TodoEntity existing = new TodoEntity();
        existing.setId(todoId);
        existing.setUserId(USER_ID);
        existing.setTitle("Old");
        existing.setStatus("pending");
        existing.setPriority("low");
        when(todoRepository.findById(todoId)).thenReturn(Optional.of(existing));

        String itemsJson = "["
            + "{\"id\":\"" + todoId + "\",\"title\":\"Updated\",\"status\":\"completed\"},"
            + "{\"title\":\"Brand new\",\"status\":\"pending\"}"
            + "]";
        String args = "{\"action\":\"merge\",\"items\":" + itemsJson + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("1 updated");
        assertThat(result.content()).contains("1 created");
        assertThat(existing.getTitle()).isEqualTo("Updated");
        assertThat(existing.getStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("Should merge reject invalid status in items")
    void shouldMergeRejectInvalidStatusInItems() {
        TodoTool tool = new TodoTool(todoRepository);
        String itemsJson = "[{\"title\":\"Test\",\"status\":\"archived\"}]";
        String args = "{\"action\":\"merge\",\"items\":" + itemsJson + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid status");
    }

    @Test
    @DisplayName("Should merge reject title exceeding MAX_CONTENT_CHARS")
    void shouldMergeRejectTitleExceedingMaxChars() {
        TodoTool tool = new TodoTool(todoRepository);
        String longTitle = "x".repeat(TodoTool.MAX_CONTENT_CHARS + 1);
        String itemsJson = "[{\"title\":\"" + longTitle + "\"}]";
        String args = "{\"action\":\"merge\",\"items\":" + itemsJson + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exceeds " + TodoTool.MAX_CONTENT_CHARS);
    }

    @Test
    @DisplayName("Should merge reject items exceeding MAX_ITEMS")
    void shouldMergeRejectItemsExceedingMax() {
        TodoTool tool = new TodoTool(todoRepository);
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i <= TodoTool.MAX_ITEMS; i++) {
            if (i > 0) items.append(",");
            items.append("{\"title\":\"T").append(i).append("\"}");
        }
        items.append("]");
        String args = "{\"action\":\"merge\",\"items\":" + items + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("maximum of " + TodoTool.MAX_ITEMS);
    }

    @Test
    @DisplayName("Should merge with cancelled status in items")
    void shouldMergeWithCancelledStatus() {
        TodoTool tool = new TodoTool(todoRepository);
        String itemsJson = "[{\"title\":\"Cancelled task\",\"status\":\"cancelled\"}]";
        String args = "{\"action\":\"merge\",\"items\":" + itemsJson + "}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<TodoEntity> captor = ArgumentCaptor.forClass(TodoEntity.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("Should set handle empty items list")
    void shouldSetHandleEmptyItems() {
        TodoTool tool = new TodoTool(todoRepository);
        String args = "{\"action\":\"set\",\"items\":[]}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(todoRepository).deleteByUserId(USER_ID);
    }

    // ── Unknown action ──

    @Test
    @DisplayName("Should reject unknown action")
    void shouldRejectUnknownAction() {
        TodoTool tool = new TodoTool(todoRepository);
        String args = "{\"action\":\"delete\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown action");
    }

    // ── validateStatus helper ──

    @Test
    @DisplayName("validateStatus should return fallback for null input")
    void validateStatusShouldReturnFallbackForNull() {
        assertThat(TodoTool.validateStatus(null, "pending")).isEqualTo("pending");
    }

    @Test
    @DisplayName("validateStatus should return fallback for blank input")
    void validateStatusShouldReturnFallbackForBlank() {
        assertThat(TodoTool.validateStatus("  ", "pending")).isEqualTo("pending");
    }

    @Test
    @DisplayName("validateStatus should normalize to lowercase")
    void validateStatusShouldNormalizeToLowercase() {
        assertThat(TodoTool.validateStatus("IN_PROGRESS", null)).isEqualTo("in_progress");
        assertThat(TodoTool.validateStatus("Completed", null)).isEqualTo("completed");
    }

    @Test
    @DisplayName("validateStatus should return null for invalid status")
    void validateStatusShouldReturnNullForInvalid() {
        assertThat(TodoTool.validateStatus("archived", null)).isNull();
        assertThat(TodoTool.validateStatus("done", null)).isNull();
    }

    @Test
    @DisplayName("validateStatus should accept all 4 allowed values")
    void validateStatusShouldAcceptAllAllowedValues() {
        assertThat(TodoTool.validateStatus("pending", null)).isEqualTo("pending");
        assertThat(TodoTool.validateStatus("in_progress", null)).isEqualTo("in_progress");
        assertThat(TodoTool.validateStatus("completed", null)).isEqualTo("completed");
        assertThat(TodoTool.validateStatus("cancelled", null)).isEqualTo("cancelled");
    }
}