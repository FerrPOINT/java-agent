package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
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
import static org.mockito.ArgumentMatchers.eq;
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

    // 1. MemoryTool adds fact to memory store
    @Test
    void memoryToolAddsFactToMemory() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"add\",\"target\":\"memory\",\"content\":\"User prefers dark mode\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Added to memory store");
        verify(memoryProvider).store(USER_ID, "memory", "auto", "User prefers dark mode");
    }

    // 2. MemoryTool adds fact to user store
    @Test
    void memoryToolAddsFactToUser() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"add\",\"target\":\"user\",\"content\":\"Name is Alice\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(memoryProvider).store(USER_ID, "user", "auto", "Name is Alice");
    }

    // 3. MemoryTool replaces fact
    @Test
    void memoryToolReplacesFact() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.replace(USER_ID, "memory", "dark mode", "User prefers light mode")).thenReturn(null);
        String args = "{\"action\":\"replace\",\"target\":\"memory\",\"old_text\":\"dark mode\",\"content\":\"User prefers light mode\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(memoryProvider).replace(USER_ID, "memory", "dark mode", "User prefers light mode");
    }

    // 4. MemoryTool removes fact
    @Test
    void memoryToolRemovesFact() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.remove(USER_ID, "memory", "dark mode")).thenReturn(null);
        String args = "{\"action\":\"remove\",\"target\":\"memory\",\"old_text\":\"dark mode\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(memoryProvider).remove(USER_ID, "memory", "dark mode");
    }

    // 5. MemoryTool reads facts
    @Test
    void memoryToolReadsFacts() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.read(USER_ID, "memory")).thenReturn("§ MEMORY\n[auto] Fact 1\n[auto] Fact 2");
        String args = "{\"action\":\"read\",\"target\":\"memory\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Fact 1");
        verify(memoryProvider).read(USER_ID, "memory");
    }

    // 6. MemoryTool stages write when approval gate enabled
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
        verify(memoryProvider, never()).store(any(), any(), any(), any());
    }

    // 7. MemoryTool rejects unknown action
    @Test
    void memoryToolRejectsUnknownAction() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"delete\",\"target\":\"memory\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown action");
    }

    // 8. MemoryTool defaults target to memory
    @Test
    void memoryToolDefaultsTargetToMemory() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"add\",\"content\":\"test fact\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        verify(memoryProvider).store(USER_ID, "memory", "auto", "test fact");
    }

    // ── TodoTool tests ──

    @Test
    void todoToolCreatesTodo() {
        TodoTool tool = new TodoTool(todoRepository);
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
        when(sessionRepository.findAll()).thenReturn(List.of(s));
        when(messageRepository.findAll()).thenReturn(List.of());
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
        when(sessionRepository.findAll()).thenReturn(List.of());
        when(messageRepository.findAll()).thenReturn(List.of(m));
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
        when(sessionRepository.findAll()).thenReturn(List.of());
        when(messageRepository.findAll()).thenReturn(List.of());
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

    // ── SkillViewTool tests ──

    @Test
    void skillViewToolReturnsSkillContent() {
        SkillViewTool tool = new SkillViewTool(skillManager);
        when(skillManager.getSkill("testing")).thenReturn("# Testing Guide\nUse JUnit 5.");
        String args = "{\"name\":\"testing\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("# Testing Guide\nUse JUnit 5.");
    }

    @Test
    void skillViewToolFailsWhenSkillNotFound() {
        SkillViewTool tool = new SkillViewTool(skillManager);
        when(skillManager.getSkill("missing")).thenReturn(null);
        String args = "{\"name\":\"missing\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Skill not found: missing");
    }

    // ── SkillsListTool tests ──

    @Test
    void skillsListToolReturnsNames() {
        SkillsListTool tool = new SkillsListTool(skillManager);
        when(skillManager.listSkillNames()).thenReturn(List.of("testing", "refactoring", "docs"));

        ToolResult result = tool.execute("{}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("testing\nrefactoring\ndocs");
    }

    @Test
    void skillsListToolReturnsEmptyList() {
        SkillsListTool tool = new SkillsListTool(skillManager);
        when(skillManager.listSkillNames()).thenReturn(List.of());

        ToolResult result = tool.execute("{}", LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEmpty();
    }

    // ── SkillManageTool tests ──

    @Test
    void skillManageToolCreatesSkill() {
        SkillManageTool tool = new SkillManageTool(skillManager);
        String args = "{\"action\":\"create\",\"name\":\"testing\",\"content\":\"# Testing Guide\\nUse JUnit 5.\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("Skill testing saved.");
        verify(skillManager).saveSkill("testing", "# Testing Guide\nUse JUnit 5.");
    }

    @Test
    void skillManageToolUpdatesSkill() {
        SkillManageTool tool = new SkillManageTool(skillManager);
        String args = "{\"action\":\"update\",\"name\":\"testing\",\"content\":\"# Testing Guide\\nUse AssertJ.\"}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("Skill testing saved.");
        verify(skillManager).saveSkill("testing", "# Testing Guide\nUse AssertJ.");
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
}