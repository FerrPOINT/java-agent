package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link MemoryTool} and {@link TodoTool}.
 * Covers error paths, null inputs, boundary conditions, and untested branches.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryTodoBranchTest {

    private static final String USER_ID = "user-42";
    private static final Session SESSION = Session.create(USER_ID, "noop", "default");
    private static final Message LAST_MESSAGE = Message.user("test");

    @Mock
    private MemoryProvider memoryProvider;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private WriteApprovalGate writeApprovalGate;

    // ── MemoryTool branch coverage ──

    @Test
    void memoryTool_addNullContent_returnsFail() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute("{\"action\":\"add\",\"content\":null}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("content is required");
    }

    @Test
    void memoryTool_addBlankContent_returnsFail() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute("{\"action\":\"add\",\"content\":\"  \"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("content is required");
    }

    @Test
    void memoryTool_addLongContent_truncatesForApprovalDescription() {
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(UUID.randomUUID());

        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        String longContent = "x".repeat(100);
        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"content\":\"" + longContent + "\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Staged for approval");
    }

    @Test
    void memoryTool_replaceNullOldText_returnsFail() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"old_text\":null,\"content\":\"new\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("old_text");
    }

    @Test
    void memoryTool_replaceBlankOldText_returnsFail() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"old_text\":\"\",\"content\":\"new\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("old_text");
    }

    @Test
    void memoryTool_replaceNullContent_returnsFail() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"old_text\":\"old\",\"content\":null}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("content is required");
    }

    @Test
    void memoryTool_replaceBlankContent_returnsFail() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"old_text\":\"old\",\"content\":\"\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("content is required");
    }

    @Test
    void memoryTool_replaceProviderReturnsError_returnsFail() {
        when(memoryProvider.replace(eq(USER_ID), eq("memory"), eq("old"), eq("new"), anyMap())).thenReturn("Item not found");
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"old_text\":\"old\",\"content\":\"new\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("Item not found");
    }

    @Test
    void memoryTool_removeNullOldText_returnsFail() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"remove\",\"old_text\":null}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("old_text");
    }

    @Test
    void memoryTool_removeBlankOldText_returnsFail() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"remove\",\"old_text\":\"\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("old_text");
    }

    @Test
    void memoryTool_removeProviderReturnsError_returnsFail() {
        when(memoryProvider.remove(eq(USER_ID), eq("memory"), eq("old"), anyMap())).thenReturn("Not found");
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"remove\",\"old_text\":\"old\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("Not found");
    }

    @Test
    void memoryTool_readAction_rejectedAsUnknown() {
        // L1: "read" case removed from MemoryTool — it's not in the schema enum
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"read\",\"target\":\"memory\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("Unknown action");
    }

    @Test
    void memoryTool_readActionWithNullTarget_rejectedAsUnknown() {
        // L1: "read" case removed — regardless of target, should fail
        MemoryTool tool = new MemoryTool(memoryProvider);
        ToolResult result = tool.execute(
            "{\"action\":\"read\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("Unknown action");
    }

    @Test
    void memoryTool_replaceWithGate_stagesWrite() {
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(UUID.randomUUID());

        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"old_text\":\"old\",\"content\":\"new\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Staged for approval");
        verify(memoryProvider, never()).replace(any(), any(), any(), any(), any());
    }

    @Test
    void memoryTool_removeWithGate_stagesWrite() {
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(UUID.randomUUID());

        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        ToolResult result = tool.execute(
            "{\"action\":\"remove\",\"old_text\":\"old\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Staged for approval");
        verify(memoryProvider, never()).remove(any(), any(), any(), any());
    }

    @Test
    void memoryTool_gateNotEnabled_executesDirectly() {
        when(writeApprovalGate.isEnabled()).thenReturn(false);

        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"content\":\"test\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("test"), anyMap());
    }

    @Test
    void memoryTool_replaceLongOldText_truncatesForApproval() {
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(UUID.randomUUID());

        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        String longOldText = "x".repeat(70);
        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"old_text\":\"" + longOldText + "\",\"content\":\"new\"}",
            LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
    }

    @Test
    void memoryTool_removeLongOldText_truncatesForApproval() {
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(UUID.randomUUID());

        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        String longOldText = "x".repeat(70);
        ToolResult result = tool.execute(
            "{\"action\":\"remove\",\"old_text\":\"" + longOldText + "\"}",
            LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
    }

    // ── TodoTool branch coverage (Hermes-parity schema: todos + merge) ──

    @Test
    void todo_writeWithDefaultPriority_usesMedium() {
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(todoRepository).deleteBySessionIdAndUserId(any(), any());
        TodoTool tool = new TodoTool(todoRepository);
        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"1\",\"content\":\"task\",\"status\":\"pending\"}]}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        verify(todoRepository).save(argThat(e -> "medium".equals(e.getPriority())));
    }

    @Test
    void todo_writeWithNullStatus_defaultsToPending() {
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(todoRepository).deleteBySessionIdAndUserId(any(), any());
        TodoTool tool = new TodoTool(todoRepository);
        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"1\",\"content\":\"task\"}]}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        verify(todoRepository).save(argThat(e -> "pending".equals(e.getStatus())));
    }

    @Test
    void todo_writeWithBlankStatus_defaultsToPending() {
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(todoRepository).deleteBySessionIdAndUserId(any(), any());
        TodoTool tool = new TodoTool(todoRepository);
        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"1\",\"content\":\"task\",\"status\":\"\"}]}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
    }

    @Test
    void todo_mergeWithEmptyItems_noDelete() {
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of());
        TodoTool tool = new TodoTool(todoRepository);
        ToolResult result = tool.execute(
            "{\"todos\":[],\"merge\":true}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        verify(todoRepository, never()).deleteBySessionIdAndUserId(any(), any());
    }

    @Test
    void todo_mergeItemWithExistingNotFound_createsNew() {
        UUID todoId = UUID.randomUUID();
        when(todoRepository.findById(todoId)).thenReturn(Optional.empty());
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TodoTool tool = new TodoTool(todoRepository);
        String itemsJson = "[{\"id\":\"" + todoId + "\",\"content\":\"New\",\"status\":\"pending\"}]";
        ToolResult result = tool.execute(
            "{\"todos\":" + itemsJson + ",\"merge\":true}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
    }

    @Test
    void todo_mergeItemBelongsToDifferentUser_createsNew() {
        UUID todoId = UUID.randomUUID();
        TodoEntity existing = new TodoEntity();
        existing.setId(todoId);
        existing.setUserId("different-user");
        when(todoRepository.findById(todoId)).thenReturn(Optional.of(existing));
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TodoTool tool = new TodoTool(todoRepository);
        String itemsJson = "[{\"id\":\"" + todoId + "\",\"content\":\"New\",\"status\":\"pending\"}]";
        ToolResult result = tool.execute(
            "{\"todos\":" + itemsJson + ",\"merge\":true}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
    }

    @Test
    void todo_replaceWithEmptyItems_deletesAll() {
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of());
        doNothing().when(todoRepository).deleteBySessionIdAndUserId(SESSION.id(), USER_ID);
        TodoTool tool = new TodoTool(todoRepository);
        ToolResult result = tool.execute(
            "{\"todos\":[]}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        verify(todoRepository).deleteBySessionIdAndUserId(SESSION.id(), USER_ID);
    }

    @Test
    void todo_validateStatus_null_returnsFallback() {
        assertThat(TodoTool.validateStatus(null, "pending")).isEqualTo("pending");
    }

    @Test
    void todo_validateStatus_blank_returnsFallback() {
        assertThat(TodoTool.validateStatus("  ", "pending")).isEqualTo("pending");
    }

    @Test
    void todo_validateStatus_invalid_returnsNull() {
        assertThat(TodoTool.validateStatus("archived", null)).isNull();
    }

    @Test
    void todo_validateStatus_valid_returnsNormalized() {
        assertThat(TodoTool.validateStatus("PENDING", null)).isEqualTo("pending");
        assertThat(TodoTool.validateStatus("IN_PROGRESS", null)).isEqualTo("in_progress");
        assertThat(TodoTool.validateStatus("COMPLETED", null)).isEqualTo("completed");
        assertThat(TodoTool.validateStatus("CANCELLED", null)).isEqualTo("cancelled");
    }

    @Test
    void todo_mergeWithFlagTrue_noDelete() {
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of());
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        TodoTool tool = new TodoTool(todoRepository);
        String itemsJson = "[{\"id\":\"1\",\"content\":\"Task\",\"status\":\"pending\"}]";
        ToolResult result = tool.execute(
            "{\"todos\":" + itemsJson + ",\"merge\":true}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        verify(todoRepository, never()).deleteBySessionIdAndUserId(any(), any());
    }

    @Test
    void todo_contentExceedingMax_isCapped() {
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        TodoTool tool = new TodoTool(todoRepository);
        String longContent = "x".repeat(TodoTool.MAX_CONTENT_CHARS + 1);
        ToolResult result = tool.execute(
            "{\"todos\":[{\"id\":\"1\",\"content\":\"" + longContent + "\",\"status\":\"pending\"}]}",
            LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        verify(todoRepository).save(argThat(e -> e.getTitle().length() == TodoTool.MAX_CONTENT_CHARS
            && e.getTitle().endsWith(TodoTool.TRUNCATION_MARKER)));
    }

    @Test
    void todo_listWithNegativeLimit_returnsAll() {
        TodoEntity t1 = new TodoEntity();
        t1.setSessionId(SESSION.id());
        t1.setUserId(USER_ID);
        t1.setTitle("Task1");
        t1.setStatus("pending");
        t1.setPriority("high");
        TodoEntity t2 = new TodoEntity();
        t2.setSessionId(SESSION.id());
        t2.setUserId(USER_ID);
        t2.setTitle("Task2");
        t2.setStatus("done");
        t2.setPriority("low");
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of(t1, t2));

        TodoTool tool = new TodoTool(todoRepository);
        ToolResult result = tool.execute(
            "{\"action\":\"list\",\"limit\":-1}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        // Negative limit → returns all (not limited)
        assertThat(result.content()).contains("Task1");
        assertThat(result.content()).contains("Task2");
    }

    @Test
    void todo_listWithNullLimit_returnsAll() {
        TodoEntity t1 = new TodoEntity();
        t1.setSessionId(SESSION.id());
        t1.setUserId(USER_ID);
        t1.setTitle("Task1");
        t1.setStatus("pending");
        when(todoRepository.findBySessionIdOrderByCreatedAtAsc(SESSION.id())).thenReturn(List.of(t1));

        TodoTool tool = new TodoTool(todoRepository);
        ToolResult result = tool.execute(
            "{\"action\":\"list\"}", LAST_MESSAGE, SESSION);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Task1");
    }

    // Removed: todo_updateOnlyPriority_updatesPriority — Hermes-parity schema
    // has no 'priority' field; only {id, content, status} are supported.
}
