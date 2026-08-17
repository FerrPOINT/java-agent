package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.WriteOrigin;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.persistence.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryToolsUnitTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String USER_ID = "user-42";
    private static final Session SESSION = Session.create(USER_ID, "noop", "default").withMetadata("title", "Test Session");
    private static final Message LAST_MESSAGE = Message.user("test prompt");

    @Mock
    private MemoryProvider memoryProvider;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private SkillManager skillManager;

    // ── MemoryTool tests (8 tests) ──

    @Test
    void memoryToolAddsFactToMemory() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"User prefers dark mode\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Entry added");
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("User prefers dark mode"), anyMap());
    }

    @Test
    void memoryToolAddsFactToUser() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"add\",\"target\":\"user\",\"content\":\"Name is Alice\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(memoryProvider).store(eq(USER_ID), eq("user"), eq("auto"), eq("Name is Alice"), anyMap());
    }

    @Test
    void memoryToolReplacesFact() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.replace(eq(USER_ID), eq("memory"), eq("dark mode"), eq("User prefers light mode"), anyMap())).thenReturn(null);
        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"dark mode\",\"content\":\"User prefers light mode\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(memoryProvider).replace(eq(USER_ID), eq("memory"), eq("dark mode"), eq("User prefers light mode"), anyMap());
    }

    @Test
    void memoryToolRemovesFact() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.remove(eq(USER_ID), eq("memory"), eq("dark mode"), anyMap())).thenReturn(null);
        String args = "{\"action\":\"remove\",\"target\":\"memory\",\"old_text\":\"dark mode\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(memoryProvider).remove(eq(USER_ID), eq("memory"), eq("dark mode"), anyMap());
    }

    @Test
    void memoryToolRejectsReadAction() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"read\",\"target\":\"memory\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        // L1: "read" case has been removed — should return "Unknown action"
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown action");
    }

    @Test
    void memoryToolStagesWriteWhenGateEnabled() {
        WriteApprovalGate gate = mock(WriteApprovalGate.class);
        when(gate.isEnabled()).thenReturn(true);
        when(gate.stageWrite(any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        MemoryTool tool = new MemoryTool(memoryProvider, gate);
        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"test fact\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Staged for approval");
        verify(gate).stageWrite(eq(USER_ID), eq("add"), eq("memory"), eq("test fact"), any(), any(), any());
        verify(memoryProvider, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    void memoryToolRejectsUnknownAction() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"delete\",\"target\":\"memory\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown action");
    }

    @Test
    void memoryToolDefaultsTargetToMemory() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"add\",\"content\":\"test fact\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("test fact"), anyMap());
    }

    // ── Fix 1: Char count uses getCharCount() not read().length() ──

    @Test
    void memoryToolSuccessResponseUsesPureCharCount() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        // getCharCount returns pure entries joined: "Fact one\n§\nFact two" = 20 chars
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(20);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(2);
        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"test fact\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        // Should show 20 (pure chars), not the length of read() which includes headers
        // M3: numbers formatted with commas
        assertThat(result.content()).contains("20/2,200");
        assertThat(result.content()).contains("entry_count: 2");
    }

    // ── Fix 4: Error response includes usage info ──

    @Test
    void memoryToolAddOverflow_returnsStructuredErrorWithUsage() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(2000);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(3);
        doThrow(new IllegalStateException("Memory at 2000/2200 chars. Adding this entry (300 chars) would exceed the limit."))
            .when(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("x".repeat(300)), anyMap());
        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"" + "x".repeat(300) + "\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exceed the limit");
        // L3: should always include current usage info
        assertThat(result.error()).contains("Current:");
        // M3: numbers formatted with commas (2000 → "2,000")
        assertThat(result.error()).contains("2,000/2,200");
        assertThat(result.error()).contains("3 entries");
        assertThat(result.error()).contains("Consolidate now");
    }

    @Test
    void memoryToolReplaceOverflow_returnsStructuredErrorWithUsage() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(2000);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(2);
        when(memoryProvider.replace(eq(USER_ID), eq("memory"), eq("old"), eq("x".repeat(500)), anyMap()))
            .thenReturn("Replacement would put memory at 2400/2200 chars. Shorten the new content.");
        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old\",\"content\":\"" + "x".repeat(500) + "\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Replacement would put memory at");
        // L3: should always include current usage info
        assertThat(result.error()).contains("Current:");
        // M3: numbers formatted with commas (2000 → "2,000")
        assertThat(result.error()).contains("2,000/2,200");
        assertThat(result.error()).contains("Consolidate now");
    }

    @Test
    void memoryToolMultipleMatchError_includesPreviews() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.getCharCount(USER_ID, "memory")).thenReturn(50);
        when(memoryProvider.getEntryCount(USER_ID, "memory")).thenReturn(2);
        when(memoryProvider.replace(eq(USER_ID), eq("memory"), eq("common"), eq("new"), anyMap()))
            .thenReturn("Multiple entries match 'common'. Be more specific:\n1. First common entry\n2. Second common entry");
        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"common\",\"content\":\"new\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Multiple entries match");
        assertThat(result.error()).contains("1. First common entry");
        assertThat(result.error()).contains("2. Second common entry");
    }

    // ── TodoTool tests ──

    @Test
    void todoToolCreatesTodo() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserId(USER_ID)).thenReturn(List.of());
        String args = "{\"action\":\"create\",\"title\":\"Write tests\",\"priority\":\"high\",\"limit\":10}";

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
    void todoToolListsTodos() {
        TodoTool tool = new TodoTool(todoRepository);
        TodoEntity t1 = new TodoEntity();
        t1.setTitle("Write tests");
        t1.setStatus("pending");
        t1.setPriority("high");
        TodoEntity t2 = new TodoEntity();
        t2.setTitle("Refactor code");
        t2.setStatus("done");
        t2.setPriority("medium");
        when(todoRepository.findByUserId(USER_ID)).thenReturn(List.of(t1, t2));
        String args = "{\"action\":\"list\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("- [pending] Write tests (high)\n- [done] Refactor code (medium)");
    }

    @Test
    void todoToolReportsNoTodos() {
        TodoTool tool = new TodoTool(todoRepository);
        when(todoRepository.findByUserId(USER_ID)).thenReturn(List.of());
        String args = "{\"action\":\"list\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("No todos.");
    }

    // ── SessionSearchTool tests ──

    @Test
    void sessionSearchToolFindsBySessionTitle() {
        SessionSearchTool tool = new SessionSearchTool(sessionRepository, messageRepository);
        SessionEntity s = new SessionEntity();
        s.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        s.setTitle("Deployment planning");
        s.setUpdatedAt(Instant.parse("2026-07-25T10:00:00Z"));
        // P2-15: FTS methods throw on mock → fallback to LIKE methods
        when(sessionRepository.findByTitleContainingIgnoreCase("deployment"))
            .thenReturn(List.of(s));
        when(messageRepository.findByContentContainingIgnoreCase("deployment"))
            .thenReturn(List.of());
        String args = "{\"query\":\"deployment\",\"limit\":5}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Deployment planning");
        assertThat(result.content()).contains("title match");
    }

    @Test
    void sessionSearchToolFindsByMessageContent() {
        SessionSearchTool tool = new SessionSearchTool(sessionRepository, messageRepository);
        UUID sessionId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        SessionEntity s = new SessionEntity();
        s.setId(sessionId);
        s.setTitle("Backend work");
        s.setUpdatedAt(Instant.parse("2026-07-25T11:30:00Z"));
        MessageEntity m = new MessageEntity();
        m.setSessionId(sessionId);
        m.setContent("We need to fix the memory search bug.");
        m.setCreatedAt(Instant.parse("2026-07-25T11:00:00Z"));
        when(sessionRepository.findByTitleContainingIgnoreCase("memory search"))
            .thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase("memory search"))
            .thenReturn(List.of(m));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(s));
        String args = "{\"query\":\"memory search\",\"limit\":5}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Backend work");
        assertThat(result.content()).contains("snippet: We need to fix the memory search bug.");
    }

    @Test
    void sessionSearchToolReturnsEmptyResult() {
        SessionSearchTool tool = new SessionSearchTool(sessionRepository, messageRepository);
        when(sessionRepository.findByTitleContainingIgnoreCase("xyz"))
            .thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase("xyz"))
            .thenReturn(List.of());
        String args = "{\"query\":\"xyz\",\"limit\":5}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("No sessions found for: xyz");
    }

    @Test
    void sessionSearchToolRejectsBlankQuery() {
        SessionSearchTool tool = new SessionSearchTool(sessionRepository, messageRepository);
        String args = "{\"query\":\"  \",\"limit\":5}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Query is required");
    }

    // ── SkillViewTool tests (S9: now with metadata) ──

    @Test
    void skillViewToolReturnsSkillContent() {
        SkillViewTool tool = new SkillViewTool(skillManager);
        // S9: getSkillInfoMultiStrategy now returns rich metadata
        when(skillManager.getSkillInfoMultiStrategy("testing")).thenReturn(new SkillManager.SkillLookupResult(
            new SkillManager.SkillInfo(
                "testing", "# Testing Guide\nUse JUnit 5.", "", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false,
                new SkillManager.LinkedFiles(List.of(), List.of(), List.of(), List.of())
            ), List.of(), null
        ));
        String args = "{\"name\":\"testing\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        // S9: Content now includes metadata header + original content
        assertThat(result.content()).contains("# Testing Guide");
        assertThat(result.content()).contains("Use JUnit 5.");
        assertThat(result.content()).contains("=== Skill: testing ===");
    }

    @Test
    void skillViewToolFailsWhenSkillNotFound() {
        SkillViewTool tool = new SkillViewTool(skillManager);
        when(skillManager.getSkillInfoMultiStrategy("missing")).thenReturn(new SkillManager.SkillLookupResult(
            null, List.of(), null
        ));
        String args = "{\"name\":\"missing\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Skill not found");
    }

    // ── SkillsListTool tests (S9: now with metadata) ──

    @Test
    void skillsListToolReturnsNames() {
        SkillsListTool tool = new SkillsListTool(skillManager);
        // S9: listSkills returns SkillInfo objects
        when(skillManager.listSkills()).thenReturn(List.of(
            new SkillManager.SkillInfo("testing", "", "", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null),
            new SkillManager.SkillInfo("refactoring", "", "", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null),
            new SkillManager.SkillInfo("docs", "", "", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null)
        ));

        ToolResult result = tool.execute("{}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        // S9: Content now includes header and metadata
        assertThat(result.content()).contains("testing");
        assertThat(result.content()).contains("refactoring");
        assertThat(result.content()).contains("docs");
        assertThat(result.content()).contains("Available Skills:");
    }

    @Test
    void skillsListToolReturnsEmptyList() {
        SkillsListTool tool = new SkillsListTool(skillManager);
        when(skillManager.listSkills()).thenReturn(List.of());

        ToolResult result = tool.execute("{}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("No skills available.");
    }

    // ── SkillsListTool category filter tests (P2-50) ──

    @Test
    void skillsListToolFiltersByCategory() {
        SkillsListTool tool = new SkillsListTool(skillManager);
        when(skillManager.listSkills()).thenReturn(List.of(
            new SkillManager.SkillInfo("testing", "", "dev", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null),
            new SkillManager.SkillInfo("refactoring", "", "dev", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null),
            new SkillManager.SkillInfo("docs", "", "writing", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null)
        ));

        ToolResult result = tool.execute("{\"category\":\"dev\"}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("testing");
        assertThat(result.content()).contains("refactoring");
        assertThat(result.content()).doesNotContain("docs");
    }

    @Test
    void skillsListToolCategoryFilterIsCaseInsensitive() {
        SkillsListTool tool = new SkillsListTool(skillManager);
        when(skillManager.listSkills()).thenReturn(List.of(
            new SkillManager.SkillInfo("testing", "", "Dev", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null),
            new SkillManager.SkillInfo("docs", "", "Writing", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null)
        ));

        ToolResult result = tool.execute("{\"category\":\"DEV\"}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("testing");
        assertThat(result.content()).doesNotContain("docs");
    }

    @Test
    void skillsListToolCategoryFilterNoMatch() {
        SkillsListTool tool = new SkillsListTool(skillManager);
        when(skillManager.listSkills()).thenReturn(List.of(
            new SkillManager.SkillInfo("testing", "", "dev", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null)
        ));

        ToolResult result = tool.execute("{\"category\":\"nonexistent\"}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("No skills available in category: nonexistent");
    }

    @Test
    void skillsListToolWithoutCategoryReturnsAll() {
        SkillsListTool tool = new SkillsListTool(skillManager);
        when(skillManager.listSkills()).thenReturn(List.of(
            new SkillManager.SkillInfo("testing", "", "dev", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null),
            new SkillManager.SkillInfo("docs", "", "writing", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null)
        ));

        ToolResult result = tool.execute("{}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("testing");
        assertThat(result.content()).contains("docs");
    }

    // ── SkillManageTool tests (S3: now with frontmatter + validation) ──

    @Test
    void skillManageToolCreatesSkill() {
        SkillManageTool tool = new SkillManageTool(skillManager);
        String args = "{\"action\":\"create\",\"name\":\"testing\",\"content\":\"# Testing Guide\\nUse JUnit 5.\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        // S3: Create now returns "created" instead of "saved"
        assertThat(result.content()).contains("created");
        // S3: Content gets YAML frontmatter prepended, calls 3-arg saveSkill with WriteOrigin
        verify(skillManager).saveSkill(eq("testing"), contains("# Testing Guide"), eq(WriteOrigin.FOREGROUND));
    }

    @Test
    void skillManageToolUpdatesSkill() {
        SkillManageTool tool = new SkillManageTool(skillManager);
        String args = "{\"action\":\"update\",\"name\":\"testing\",\"content\":\"# Testing Guide\\nUse AssertJ.\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("updated");
        // S3: Update calls 3-arg saveSkill with WriteOrigin.FOREGROUND
        verify(skillManager).saveSkill(eq("testing"), eq("# Testing Guide\nUse AssertJ."), eq(WriteOrigin.FOREGROUND), any());
    }

    @Test
    void skillManageToolDeletesSkill() {
        SkillManageTool tool = new SkillManageTool(skillManager);
        when(skillManager.deleteSkill("legacy")).thenReturn(true);
        String args = "{\"action\":\"delete\",\"name\":\"legacy\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("Skill legacy deleted.");
        verify(skillManager).deleteSkill("legacy");
    }

    @Test
    void skillManageToolFailsToDeleteMissingSkill() {
        SkillManageTool tool = new SkillManageTool(skillManager);
        when(skillManager.deleteSkill("missing")).thenReturn(false);
        String args = "{\"action\":\"delete\",\"name\":\"missing\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Skill missing not found.");
    }

    @Test
    void skillManageToolRejectsUnknownAction() {
        SkillManageTool tool = new SkillManageTool(skillManager);
        String args = "{\"action\":\"rename\",\"name\":\"testing\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Unknown action: rename");
    }

    // ── Provenance through MemoryProvider tests (Finding 4.1 / S7) ───────

    @Test
    void memoryToolStorePassesProvenanceMap() {
        // Execute add, capture the provenance Map arg, verify it's not null
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"test fact\"}";

        tool.execute(args, LAST_MESSAGE, SESSION);

        // Capture the 5th argument (provenance map) from the 5-arg store call
        org.mockito.ArgumentCaptor<java.util.Map<String, String>> captor =
            org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(memoryProvider).store(eq(USER_ID), eq("memory"), eq("auto"), eq("test fact"), captor.capture());

        // The provenance map should not be null (it's always passed, even if empty)
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void memoryToolReplacePassesProvenanceMap() {
        // Execute replace, verify provenance passed
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.replace(eq(USER_ID), eq("memory"), eq("old"), eq("new"), anyMap()))
            .thenReturn(null);
        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"old\",\"content\":\"new\"}";

        tool.execute(args, LAST_MESSAGE, SESSION);

        // Capture the provenance map from the 5-arg replace call
        org.mockito.ArgumentCaptor<java.util.Map<String, String>> captor =
            org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(memoryProvider).replace(eq(USER_ID), eq("memory"), eq("old"), eq("new"), captor.capture());

        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void memoryProviderDefaultStoreIgnoresProvenance() {
        // Call 5-arg store on a mock with only 4-arg stub, verify 4-arg called
        // This tests the default method on MemoryProvider interface
        MemoryProvider provider = mock(MemoryProvider.class, org.mockito.Mockito.withSettings()
            .defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));

        // The default 5-arg store delegates to the 4-arg store
        // Since we used CALLS_REAL_METHODS, the default method should fire
        // and call the 4-arg store (which is also a default — it calls the 3-arg)
        // We need to stub the 3-arg store to verify the chain
        provider.store(USER_ID, "memory", "auto", "test fact", java.util.Map.of("key", "val"));

        // The default 5-arg → 4-arg → 3-arg chain means store(3-arg) should be called
        verify(provider).store(USER_ID, "auto", "test fact");
    }
}