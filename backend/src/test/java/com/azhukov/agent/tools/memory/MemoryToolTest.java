package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryToolTest {

    private static final String USER_ID = "user-42";
    private static final Session SESSION = Session.create(USER_ID, "noop", "default");
    private static final Message LAST_MSG = Message.user("test");

    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private WriteApprovalGate writeApprovalGate;

    @AfterEach
    void cleanUp() {
        // Clear any WriteContext ThreadLocal that tests may have set
        com.azhukov.agent.core.memory.WriteContext.clear();
    }

    @Test
    @DisplayName("Should add entry to memory store successfully")
    void shouldAddEntryToMemoryStore() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(100);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(3);

        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"User prefers dark mode\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Entry added.");
        assertThat(result.content()).contains("usage:");
        assertThat(result.content()).contains("entry_count: 3");
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("User prefers dark mode"), anyMap());
    }

    @Test
    @DisplayName("Should add entry to user store with comma-formatted usage")
    void shouldAddEntryToUserStore() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharCount(USER_ID, "user")).thenReturn(1200);
        when(memoryProvider.getEntryCount(USER_ID, "user")).thenReturn(5);

        String args = "{\"action\":\"add\",\"target\":\"user\",\"content\":\"Name: Alice\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Entry added.");
        // M3: numbers should be comma-grouped (1,200 / 1,375)
        assertThat(result.content()).contains("1,200/1,375");
        assertThat(result.content()).contains("entry_count: 5");
        verify(memoryProvider).store(eq(USER_ID), eq("user"), eq("auto"), eq("Name: Alice"), anyMap());
    }

    @Test
    @DisplayName("Should fail when content is blank for add action")
    void shouldFailWhenContentBlankForAdd() {
        MemoryTool tool = new MemoryTool(memoryProvider);

        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"  \"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("content is required for add action");
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should fail when content is null for add action")
    void shouldFailWhenContentNullForAdd() {
        MemoryTool tool = new MemoryTool(memoryProvider);

        String args = "{\"action\":\"add\",\"target\":\"memory\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("content is required for add action");
    }

    @Test
    @DisplayName("Should default target to memory when not specified")
    void shouldDefaultTargetToMemoryWhenNotSpecified() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(0);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(0);

        // target is required per the annotation, but the code defaults to "memory" when blank
        String args = "{\"action\":\"add\",\"target\":\"\",\"content\":\"test fact\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("test fact"), anyMap());
    }

    @Test
    @DisplayName("Should fail for invalid target")
    void shouldFailForInvalidTarget() {
        MemoryTool tool = new MemoryTool(memoryProvider);

        String args = "{\"action\":\"add\",\"target\":\"invalid\",\"content\":\"test\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid target");
        assertThat(result.error()).contains("invalid");
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should replace entry successfully")
    void shouldReplaceEntrySuccessfully() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.replace(eq(USER_ID), eq("memory"), eq("old text"), eq("new text"), anyMap()))
            .thenReturn(null); // null means no error
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(200);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(4);

        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old text\",\"content\":\"new text\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Entry replaced.");
        verify(memoryProvider).replace(eq(USER_ID), eq("memory"), eq("old text"), eq("new text"), anyMap());
    }

    @Test
    @DisplayName("Should fail when old_text is missing for replace action")
    void shouldFailWhenOldTextMissingForReplace() {
        MemoryTool tool = new MemoryTool(memoryProvider);

        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"content\":\"new text\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("old_text is required for replace action");
        verify(memoryProvider, never()).replace(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should fail when content is missing for replace action")
    void shouldFailWhenContentMissingForReplace() {
        MemoryTool tool = new MemoryTool(memoryProvider);

        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("content is required for replace action");
        verify(memoryProvider, never()).replace(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should return error response when replace returns an error string")
    void shouldReturnErrorResponseWhenReplaceReturnsError() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.replace(eq(USER_ID), eq("memory"), eq("old"), eq("new"), anyMap()))
            .thenReturn("Entry not found");
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(500);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(2);

        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old\",\"content\":\"new\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Entry not found");
        // Error response should also include usage info
        assertThat(result.error()).contains("Current:");
        assertThat(result.error()).contains("Consolidate now");
    }

    @Test
    @DisplayName("Should remove entry successfully")
    void shouldRemoveEntrySuccessfully() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.remove(eq(USER_ID), eq("memory"), eq("old text"), anyMap()))
            .thenReturn(null); // null = no error
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(100);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(2);

        String args = "{\"action\":\"remove\",\"target\":\"memory\",\"old_text\":\"old text\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Entry removed.");
        verify(memoryProvider).remove(eq(USER_ID), eq("memory"), eq("old text"), anyMap());
    }

    @Test
    @DisplayName("Should fail when old_text is missing for remove action")
    void shouldFailWhenOldTextMissingForRemove() {
        MemoryTool tool = new MemoryTool(memoryProvider);

        String args = "{\"action\":\"remove\",\"target\":\"memory\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("old_text is required for remove action");
        verify(memoryProvider, never()).remove(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should return error response when remove returns an error string")
    void shouldReturnErrorResponseWhenRemoveReturnsError() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.remove(eq(USER_ID), eq("memory"), eq("old"), anyMap()))
            .thenReturn("Entry not found");
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(300);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(1);

        String args = "{\"action\":\"remove\",\"target\":\"memory\",\"old_text\":\"old\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Entry not found");
        assertThat(result.error()).contains("Current:");
    }

    @Test
    @DisplayName("Should fail for unknown action")
    void shouldFailForUnknownAction() {
        MemoryTool tool = new MemoryTool(memoryProvider);

        String args = "{\"action\":\"delete\",\"target\":\"memory\",\"content\":\"test\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown action");
        assertThat(result.error()).contains("delete");
    }

    @Test
    @DisplayName("Should stage write for approval when gate is enabled (add)")
    void shouldStageWriteForApprovalWhenGateEnabledAdd() {
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        UUID pendingId = UUID.randomUUID();
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(eq(USER_ID), eq("add"), eq("memory"), eq("test content"),
            eq(null), anyString(), anyString()))
            .thenReturn(pendingId);

        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"test content\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Staged for approval");
        assertThat(result.content()).contains(pendingId.toString());
        // Should NOT call store directly
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should stage write for approval when gate is enabled (replace)")
    void shouldStageWriteForApprovalWhenGateEnabledReplace() {
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        UUID pendingId = UUID.randomUUID();
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(eq(USER_ID), eq("replace"), eq("memory"), eq("new text"),
            eq("old text"), anyString(), anyString()))
            .thenReturn(pendingId);

        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old text\",\"content\":\"new text\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Staged for approval");
        assertThat(result.content()).contains(pendingId.toString());
        verify(memoryProvider, never()).replace(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should stage write for approval when gate is enabled (remove)")
    void shouldStageWriteForApprovalWhenGateEnabledRemove() {
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        UUID pendingId = UUID.randomUUID();
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(eq(USER_ID), eq("remove"), eq("memory"), eq(null),
            eq("old text"), anyString(), anyString()))
            .thenReturn(pendingId);

        String args = "{\"action\":\"remove\",\"target\":\"memory\",\"old_text\":\"old text\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Staged for approval");
        assertThat(result.content()).contains(pendingId.toString());
        verify(memoryProvider, never()).remove(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should handle case-insensitive action and target")
    void shouldHandleCaseInsensitiveActionAndTarget() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(0);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(0);

        String args = "{\"action\":\"ADD\",\"target\":\"MEMORY\",\"content\":\"case test\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Entry added.");
        // Should store to "memory" target (lowercased)
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("case test"), anyMap());
    }

    @Test
    @DisplayName("Should build error response with usage info when store throws IllegalStateException")
    void shouldBuildErrorResponseWhenStoreThrowsIllegalStateException() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        // IllegalStateException is caught in doAdd and returns a structured error response
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(2200);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(10);

        // Use doThrow on the void store method with provenance (5-arg)
        org.mockito.Mockito.doThrow(new IllegalStateException("Memory full"))
            .when(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("content"), anyMap());

        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"content\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Memory full");
        assertThat(result.error()).contains("Current:");
        assertThat(result.error()).contains("Consolidate now");
    }

    @Test
    @DisplayName("Should truncate long content in approval summary for add")
    void shouldTruncateLongContentInApprovalSummary() {
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        UUID pendingId = UUID.randomUUID();
        String longContent = "x".repeat(100);
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(eq(USER_ID), eq("add"), eq("memory"), eq(longContent),
            eq(null), org.mockito.ArgumentMatchers.contains("..."), anyString()))
            .thenReturn(pendingId);

        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"" + longContent + "\"}";
        ToolResult result = tool.execute(args, LAST_MSG, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Staged for approval");
        // Verify the summary was truncated (content > 80 chars triggers truncation)
        verify(writeApprovalGate).stageWrite(eq(USER_ID), eq("add"), eq("memory"), eq(longContent),
            eq(null), org.mockito.ArgumentMatchers.contains("..."), anyString());
    }
}