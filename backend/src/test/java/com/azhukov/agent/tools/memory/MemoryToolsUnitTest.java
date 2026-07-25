package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.MemoryProvider;
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

    // 1. MemoryTool stores and searches memory
    @Test
    void memoryToolStoresFact() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        String args = "{\"action\":\"store\",\"category\":\"preferences\",\"content\":\"User prefers dark mode\",\"limit\":5}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("Stored memory.");
        verify(memoryProvider).store(USER_ID, "preferences", "User prefers dark mode");
    }

    @Test
    void memoryToolRecallsFacts() {
        MemoryTool tool = new MemoryTool(memoryProvider);
        when(memoryProvider.recall(USER_ID, "dark mode", 3)).thenReturn(List.of("User prefers dark mode", "UI theme is dark"));
        String args = "{\"action\":\"recall\",\"category\":\"preferences\",\"content\":\"dark mode\",\"limit\":3}";

        ToolResult result = tool.execute(args, LAST_MESSAGE, SESSION);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("User prefers dark mode\nUI theme is dark");
        verify(memoryProvider).recall(USER_ID, "dark mode", 3);
        verify(memoryProvider, never()).store(any(), any(), any());
    }

    // 2. TodoTool creates/updates/lists todos
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

    // 3. SessionSearchTool searches messages
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

    // 4. SkillViewTool returns skill content
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

    // 5. SkillsListTool lists skills
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

    // 6. SkillManageTool creates/updates/deletes skill
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
