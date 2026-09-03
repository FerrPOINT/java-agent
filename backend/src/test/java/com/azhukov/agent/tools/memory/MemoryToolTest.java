package com.azhukov.agent.tools.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.MemoryScope;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryToolTest {

    private static final String USER_ID = "user-42";
    private static final Session SESSION = Session.create(USER_ID, "noop", "default");
    private static final Message LAST_MSG = Message.user("test");
    private static final ObjectMapper MAPPER = ToolHandler.TOOL_ARGS_MAPPER.copy();

    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private WriteApprovalGate writeApprovalGate;

    @AfterEach
    void cleanUp() {
        com.azhukov.agent.core.memory.WriteContext.clear();
    }

    @Test
    @DisplayName("Should add entry to memory store and return Hermes-shaped JSON")
    void shouldAddEntryToMemoryStore() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(100);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(3);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"User prefers dark mode\"}",
            LAST_MSG, SESSION);

        JsonNode json = okJson(result);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("done").asBoolean()).isTrue();
        assertThat(json.path("target").asText()).isEqualTo("memory");
        assertThat(json.path("message").asText()).isEqualTo("Entry added.");
        assertThat(json.path("usage").asText()).contains("100/2,200");
        assertThat(json.path("entry_count").asInt()).isEqualTo(3);
        assertThat(json.path("note").asText()).contains("do not repeat");
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"),
            eq("User prefers dark mode"), anyMap());
    }

    @Test
    @DisplayName("Named profiles write to profile-scoped memory like Hermes profile homes")
    void namedProfileWritesUseProfileScopedMemoryUser() throws Exception {
        AgentProperties properties = new AgentProperties();
        MemoryTool tool = new MemoryTool(memoryProvider, null, properties);
        Session workSession = SESSION.withMetadata("profile", "work");
        String scopedUserId = MemoryScope.userId(workSession, properties);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(scopedUserId, "memory")).thenReturn(1);
        when(memoryProvider.getEntryCount(scopedUserId, "memory")).thenReturn(1);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"Work-only note\"}",
            LAST_MSG, workSession);

        assertThat(okJson(result).path("message").asText()).isEqualTo("Entry added.");
        verify(memoryProvider).store(eq(scopedUserId), eq("memory"), eq("auto"),
            eq("Work-only note"), anyMap());
        verify(memoryProvider, never()).store(eq(USER_ID), eq("memory"), eq("auto"),
            eq("Work-only note"), anyMap());
    }

    @Test
    @DisplayName("Process profile isolates memory when session metadata has no profile")
    void processProfileFallbackWritesUseProfileScopedMemoryUser() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getProfile().setName("work");
        MemoryTool tool = new MemoryTool(memoryProvider, null, properties);
        String scopedUserId = MemoryScope.userId(SESSION, properties);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(scopedUserId, "memory")).thenReturn(1);
        when(memoryProvider.getEntryCount(scopedUserId, "memory")).thenReturn(1);

        tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"Process-profile note\"}",
            LAST_MSG, SESSION);

        verify(memoryProvider).store(eq(scopedUserId), eq("memory"), eq("auto"),
            eq("Process-profile note"), anyMap());
        verify(memoryProvider, never()).store(eq(USER_ID), eq("memory"), eq("auto"),
            eq("Process-profile note"), anyMap());
    }

    @Test
    @DisplayName("Should add entry to user store with configured comma-formatted usage")
    void shouldAddEntryToUserStore() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharLimit("user")).thenReturn(1375);
        when(memoryProvider.getCharCount(USER_ID, "user")).thenReturn(1200);
        when(memoryProvider.getEntryCount(USER_ID, "user")).thenReturn(5);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"user\",\"content\":\"Name: Alice\"}",
            LAST_MSG, SESSION);

        JsonNode json = okJson(result);
        assertThat(json.path("message").asText()).isEqualTo("Entry added.");
        assertThat(json.path("usage").asText()).contains("1,200/1,375");
        assertThat(json.path("entry_count").asInt()).isEqualTo(5);
        verify(memoryProvider).store(eq(USER_ID), eq("user"), eq("auto"), eq("Name: Alice"), anyMap());
    }

    @Test
    @DisplayName("Should fail when content is blank for add action")
    void shouldFailWhenContentBlankForAdd() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"  \"}",
            LAST_MSG, SESSION);

        assertThat(errorJson(result).path("error").asText()).contains("content is required");
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should return structured error for invalid JSON arguments")
    void shouldReturnStructuredErrorForInvalidJsonArguments() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);

        ToolResult result = tool.execute("{not-json", LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Invalid tool arguments");
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should fail when content is null for add action")
    void shouldFailWhenContentNullForAdd() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);

        ToolResult result = tool.execute("{\"action\":\"add\",\"target\":\"memory\"}", LAST_MSG, SESSION);

        assertThat(errorJson(result).path("error").asText()).contains("content is required");
    }

    @Test
    @DisplayName("Should default target to memory when not specified")
    void shouldDefaultTargetToMemoryWhenNotSpecified() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(0);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(0);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"\",\"content\":\"test fact\"}",
            LAST_MSG, SESSION);

        assertThat(okJson(result).path("target").asText()).isEqualTo("memory");
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("test fact"), anyMap());
    }

    @Test
    @DisplayName("Should fail for invalid target")
    void shouldFailForInvalidTarget() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"invalid\",\"content\":\"test\"}",
            LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("error").asText()).contains("Invalid memory target").contains("invalid");
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Disabled memory store rejects writes before provider and approval gate")
    void disabledMemoryTargetRejectsBeforeProviderAndApprovalGate() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getMemory().setMemoryEnabled(false);
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate, properties);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"should not persist\"}",
            LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("target").asText()).isEqualTo("memory");
        assertThat(json.path("error").asText()).isEqualTo("Built-in MEMORY.md writes are disabled in memory config.");
        verify(writeApprovalGate, never()).isEnabled();
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Disabled user profile store rejects batch writes before approval staging")
    void disabledUserTargetRejectsBatchBeforeApprovalStaging() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getMemory().setUserProfileEnabled(false);
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate, properties);

        ToolResult result = tool.execute(
            "{\"target\":\"user\",\"operations\":[{\"action\":\"add\",\"content\":\"should not stage\"}]}",
            LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("target").asText()).isEqualTo("user");
        assertThat(json.path("error").asText()).isEqualTo("Built-in USER.md writes are disabled in memory config.");
        verify(writeApprovalGate, never()).isEnabled();
        verify(memoryProvider, never()).applyBatch(anyString(), anyString(), anyList(), anyMap());
    }

    @Test
    @DisplayName("Should replace entry successfully")
    void shouldReplaceEntrySuccessfully() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.replace(eq(USER_ID), eq("memory"), eq("old text"), eq("new text"), anyMap()))
            .thenReturn(null);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(200);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(4);

        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old text\",\"content\":\"new text\"}",
            LAST_MSG, SESSION);

        assertThat(okJson(result).path("message").asText()).isEqualTo("Entry replaced.");
        verify(memoryProvider).replace(eq(USER_ID), eq("memory"), eq("old text"), eq("new text"), anyMap());
    }

    @Test
    @DisplayName("Should fail with current entries when old_text is missing for replace action")
    void shouldFailWhenOldTextMissingForReplace() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(21);
        when(memoryProvider.getRawEntries(USER_ID, "memory")).thenReturn(List.of("old entry"));

        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"target\":\"memory\",\"content\":\"new text\"}",
            LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("error").asText()).contains("old_text is required for replace action");
        assertThat(json.path("current_entries").get(0).asText()).isEqualTo("old entry");
        verify(memoryProvider, never()).replace(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should fail when content is missing for replace action")
    void shouldFailWhenContentMissingForReplace() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);

        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old\"}",
            LAST_MSG, SESSION);

        assertThat(errorJson(result).path("error").asText()).contains("content is required");
        verify(memoryProvider, never()).replace(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should return structured error response when replace returns an error string")
    void shouldReturnErrorResponseWhenReplaceReturnsError() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.replace(eq(USER_ID), eq("memory"), eq("old"), eq("new"), anyMap()))
            .thenReturn("Entry not found");
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(500);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(2);
        when(memoryProvider.getRawEntries(USER_ID, "memory")).thenReturn(List.of("one", "two"));

        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old\",\"content\":\"new\"}",
            LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("error").asText()).isEqualTo("Entry not found");
        assertThat(json.path("current_entries").size()).isEqualTo(2);
        assertThat(json.path("usage").asText()).contains("500/2,200");
        assertThat(json.path("hint").asText()).contains("Consolidate now");
    }

    @Test
    @DisplayName("Should remove entry successfully")
    void shouldRemoveEntrySuccessfully() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.remove(eq(USER_ID), eq("memory"), eq("old text"), anyMap()))
            .thenReturn(null);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(100);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(2);

        ToolResult result = tool.execute(
            "{\"action\":\"remove\",\"target\":\"memory\",\"old_text\":\"old text\"}",
            LAST_MSG, SESSION);

        assertThat(okJson(result).path("message").asText()).isEqualTo("Entry removed.");
        verify(memoryProvider).remove(eq(USER_ID), eq("memory"), eq("old text"), anyMap());
    }

    @Test
    @DisplayName("Should fail with current entries when old_text is missing for remove action")
    void shouldFailWhenOldTextMissingForRemove() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(7);
        when(memoryProvider.getRawEntries(USER_ID, "memory")).thenReturn(List.of("old"));

        ToolResult result = tool.execute("{\"action\":\"remove\",\"target\":\"memory\"}", LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("error").asText()).contains("old_text is required for remove action");
        assertThat(json.path("current_entries").get(0).asText()).isEqualTo("old");
        verify(memoryProvider, never()).remove(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should return structured error response when remove returns an error string")
    void shouldReturnErrorResponseWhenRemoveReturnsError() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.remove(eq(USER_ID), eq("memory"), eq("old"), anyMap()))
            .thenReturn("Entry not found");
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(300);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(1);
        when(memoryProvider.getRawEntries(USER_ID, "memory")).thenReturn(List.of("entry"));

        ToolResult result = tool.execute(
            "{\"action\":\"remove\",\"target\":\"memory\",\"old_text\":\"old\"}",
            LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("error").asText()).isEqualTo("Entry not found");
        assertThat(json.path("current_entries").get(0).asText()).isEqualTo("entry");
    }

    @Test
    @DisplayName("Should fail for unknown action")
    void shouldFailForUnknownAction() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);

        ToolResult result = tool.execute(
            "{\"action\":\"delete\",\"target\":\"memory\",\"content\":\"test\"}",
            LAST_MSG, SESSION);

        assertThat(errorJson(result).path("error").asText()).contains("Unknown action").contains("delete");
    }

    @Test
    @DisplayName("Should stage write for approval when gate is enabled (add)")
    void shouldStageWriteForApprovalWhenGateEnabledAdd() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        UUID pendingId = UUID.randomUUID();
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(eq(USER_ID), eq("add"), eq("memory"), eq("test content"),
            eq(null), anyString(), anyString()))
            .thenReturn(pendingId);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"test content\"}",
            LAST_MSG, SESSION);

        JsonNode json = okJson(result);
        assertThat(json.path("staged").asBoolean()).isTrue();
        assertThat(json.path("pending_id").asText()).isEqualTo(pendingId.toString());
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should fail when approval staging cannot persist the pending write")
    void shouldFailWhenApprovalStageFails() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(eq(USER_ID), eq("add"), eq("memory"), eq("test content"),
            eq(null), anyString(), anyString()))
            .thenReturn(null);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"test content\"}",
            LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("staged").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Failed to stage memory write");
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should stage write for approval when gate is enabled (replace)")
    void shouldStageWriteForApprovalWhenGateEnabledReplace() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        UUID pendingId = UUID.randomUUID();
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(eq(USER_ID), eq("replace"), eq("memory"), eq("new text"),
            eq("old text"), anyString(), anyString()))
            .thenReturn(pendingId);

        ToolResult result = tool.execute(
            "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old text\",\"content\":\"new text\"}",
            LAST_MSG, SESSION);

        assertThat(okJson(result).path("pending_id").asText()).isEqualTo(pendingId.toString());
        verify(memoryProvider, never()).replace(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should stage write for approval when gate is enabled (remove)")
    void shouldStageWriteForApprovalWhenGateEnabledRemove() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        UUID pendingId = UUID.randomUUID();
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(eq(USER_ID), eq("remove"), eq("memory"), eq(null),
            eq("old text"), anyString(), anyString()))
            .thenReturn(pendingId);

        ToolResult result = tool.execute(
            "{\"action\":\"remove\",\"target\":\"memory\",\"old_text\":\"old text\"}",
            LAST_MSG, SESSION);

        assertThat(okJson(result).path("pending_id").asText()).isEqualTo(pendingId.toString());
        verify(memoryProvider, never()).remove(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should handle case-insensitive action and normalize target before provider calls")
    void shouldHandleCaseInsensitiveActionAndTarget() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(0);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(0);

        ToolResult result = tool.execute(
            "{\"action\":\"ADD\",\"target\":\"MEMORY\",\"content\":\"case test\"}",
            LAST_MSG, SESSION);

        assertThat(okJson(result).path("target").asText()).isEqualTo("memory");
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("case test"), anyMap());
    }

    @Test
    @DisplayName("Should build structured error response when store throws IllegalStateException")
    void shouldBuildErrorResponseWhenStoreThrowsIllegalStateException() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(2200);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(10);
        when(memoryProvider.getRawEntries(USER_ID, "memory")).thenReturn(List.of("full"));
        doThrow(new IllegalStateException("Memory full"))
            .when(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("content"), anyMap());

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"content\"}",
            LAST_MSG, SESSION);

        JsonNode json = errorJson(result);
        assertThat(json.path("error").asText()).isEqualTo("Memory full");
        assertThat(json.path("entry_count").asInt()).isEqualTo(10);
        assertThat(json.path("hint").asText()).contains("Consolidate now");
    }

    @Test
    @DisplayName("Should accept new_text as content alias for single operations")
    void shouldAcceptNewTextAlias() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(1);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(1);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"new_text\":\"alias content\"}",
            LAST_MSG, SESSION);

        assertThat(okJson(result).path("message").asText()).isEqualTo("Entry added.");
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("alias content"), anyMap());
    }

    @Test
    @DisplayName("Should let content win over new_text even when blank")
    void shouldNotUseNewTextWhenBlankContentIsProvided() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"  \",\"new_text\":\"alias content\"}",
            LAST_MSG, SESSION);

        assertThat(errorJson(result).path("error").asText()).contains("content is required");
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should route provided empty operations list to batch error")
    void shouldFailForEmptyOperationsList() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(0);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(0);
        when(memoryProvider.getRawEntries(USER_ID, "memory")).thenReturn(List.of());

        ToolResult result = tool.execute("{\"target\":\"memory\",\"operations\":[]}", LAST_MSG, SESSION);

        assertThat(errorJson(result).path("error").asText()).isEqualTo("operations list is empty.");
        verify(memoryProvider, never()).applyBatch(anyString(), anyString(), anyList(), anyMap());
    }

    @Test
    @DisplayName("Should apply provided operations array and ignore legacy fields")
    void shouldApplyBatchOperations() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.applyBatch(eq(USER_ID), eq("memory"), anyList(), anyMap())).thenReturn(null);
        when(memoryProvider.getCharLimit("memory")).thenReturn(2200);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(40);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(2);

        ToolResult result = tool.execute(
            "{\"action\":\"add\",\"content\":\"ignored\",\"target\":\"memory\","
                + "\"operations\":[{\"action\":\"replace\",\"old_text\":\"old\",\"new_text\":\"new\"}]}",
            LAST_MSG, SESSION);

        JsonNode json = okJson(result);
        assertThat(json.path("message").asText()).isEqualTo("Applied 1 operation(s).");
        verify(memoryProvider).applyBatch(eq(USER_ID), eq("memory"), argThat(batch ->
            batch.size() == 1
                && "replace".equals(batch.get(0).action())
                && "new".equals(batch.get(0).content())
                && "old".equals(batch.get(0).oldText())
        ), anyMap());
        verify(memoryProvider, never()).store(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("Should stage batch write when approval gate is enabled")
    void shouldStageBatchWriteForApproval() throws Exception {
        MemoryTool tool = new MemoryTool(memoryProvider, writeApprovalGate);
        UUID pendingId = UUID.randomUUID();
        when(writeApprovalGate.isEnabled()).thenReturn(true);
        when(writeApprovalGate.stageWrite(eq(USER_ID), eq("batch"), eq("memory"), anyString(), eq(null),
            eq("Batch: 1 operation(s)"), anyString()))
            .thenReturn(pendingId);

        ToolResult result = tool.execute(
            "{\"target\":\"memory\",\"operations\":[{\"action\":\"add\",\"content\":\"new\"}]}",
            LAST_MSG, SESSION);

        JsonNode json = okJson(result);
        assertThat(json.path("staged").asBoolean()).isTrue();
        assertThat(json.path("pending_id").asText()).isEqualTo(pendingId.toString());
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(writeApprovalGate).stageWrite(eq(USER_ID), eq("batch"), eq("memory"), contentCaptor.capture(),
            eq(null), eq("Batch: 1 operation(s)"), anyString());
        JsonNode stagedOperations = MAPPER.readTree(contentCaptor.getValue());
        assertThat(stagedOperations).hasSize(1);
        assertThat(stagedOperations.get(0).path("action").asText()).isEqualTo("add");
        assertThat(stagedOperations.get(0).path("content").asText()).isEqualTo("new");
        verify(memoryProvider, never()).applyBatch(anyString(), anyString(), anyList(), anyMap());
    }

    private static JsonNode okJson(ToolResult result) throws Exception {
        assertThat(result.success()).isTrue();
        assertThat(result.content()).isNotBlank();
        return MAPPER.readTree(result.content());
    }

    private static JsonNode errorJson(ToolResult result) throws Exception {
        assertThat(result.success()).isFalse();
        assertThat(result.content()).isNotBlank();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(json.path("error").asText()).isNotBlank();
        assertThat(result.error()).isEqualTo(json.path("error").asText());
        return json;
    }
}
