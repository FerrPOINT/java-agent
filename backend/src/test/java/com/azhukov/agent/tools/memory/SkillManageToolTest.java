package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.WriteContext;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.WriteOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SkillManageTool} — covers create, update, delete, patch,
 * write_file, remove_file, and validation error branches.
 */
@ExtendWith(MockitoExtension.class)
class SkillManageToolTest {

    @Mock private SkillManager skillManager;

    private SkillManageTool tool;

    @BeforeEach
    void setUp() {
        tool = new SkillManageTool(skillManager);
        // Ensure no leftover ThreadLocal from a previous test
        WriteContext.clear();
    }

    @AfterEach
    void tearDown() {
        WriteContext.clear();
    }

    private Session session() {
        return new Session(UUID.randomUUID(), "user-1", "test", "openai-compatible", "gpt-4", null, Map.of(), null);
    }

    private Message assistant() {
        return Message.assistant("test", 0);
    }

    @Test
    void createSavesSkillWithFrontmatter() {
        String args = "{\"action\":\"create\",\"name\":\"my-skill\",\"content\":\"hello world\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("created");
        verify(skillManager).saveSkill(eq("my-skill"), any(), any());
    }

    @Test
    void createWithBlankContentGeneratesDefault() {
        String args = "{\"action\":\"create\",\"name\":\"my-skill\",\"content\":\"\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).saveSkill(eq("my-skill"), any(), any());
    }

    @Test
    void createWithExistingFrontmatterPreservesIt() {
        String args = "{\"action\":\"create\",\"name\":\"my-skill\",\"content\":\"---\\nname: x\\n---\\nbody\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).saveSkill(eq("my-skill"), any(), any());
    }

    @Test
    void updateSavesSkillContent() {
        String args = "{\"action\":\"update\",\"name\":\"my-skill\",\"content\":\"new content\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("updated");
        verify(skillManager).saveSkill(eq("my-skill"), eq("new content"), any());
    }

    @Test
    void deleteReturnsOkWhenDeleted() {
        when(skillManager.deleteSkill("my-skill")).thenReturn(true);
        String args = "{\"action\":\"delete\",\"name\":\"my-skill\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("deleted");
        verify(skillManager).deleteSkill("my-skill");
    }

    @Test
    void deleteReturnsFailWhenNotFound() {
        when(skillManager.deleteSkill("my-skill")).thenReturn(false);
        String args = "{\"action\":\"delete\",\"name\":\"my-skill\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    void patchSucceedsWhenPatched() {
        when(skillManager.patchSkill("my-skill", "old", "new")).thenReturn(true);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\",\"new_text\":\"new\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("patched");
    }

    @Test
    void patchFailsWhenOldTextMissing() {
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"\",\"new_text\":\"new\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("old_text is required");
    }

    @Test
    void patchFailsWhenNewTextMissing() {
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("new_text is required");
    }

    @Test
    void patchFailsWhenNotFound() {
        when(skillManager.patchSkill("my-skill", "old", "new")).thenReturn(false);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\",\"new_text\":\"new\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    void writeFileSucceeds() {
        String args = "{\"action\":\"write_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\",\"content\":\"data\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("written");
        verify(skillManager).writeSupportFile("my-skill", "references/ref.md", "data");
    }

    @Test
    void writeFileFailsWhenPathMissing() {
        String args = "{\"action\":\"write_file\",\"name\":\"my-skill\",\"file_path\":\"\",\"content\":\"data\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("file_path is required");
    }

    @Test
    void writeFileFailsWhenContentMissing() {
        String args = "{\"action\":\"write_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("content is required");
    }

    @Test
    void writeFileCatchesException() {
        doThrow(new RuntimeException("disk full"))
            .when(skillManager).writeSupportFile("my-skill", "references/ref.md", "data");
        String args = "{\"action\":\"write_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\",\"content\":\"data\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("disk full");
    }

    @Test
    void removeFileSucceeds() {
        when(skillManager.removeSupportFile("my-skill", "references/ref.md")).thenReturn(true);
        String args = "{\"action\":\"remove_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("removed");
    }

    @Test
    void removeFileFailsWhenNotFound() {
        when(skillManager.removeSupportFile("my-skill", "references/ref.md")).thenReturn(false);
        String args = "{\"action\":\"remove_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not found");
    }

    @Test
    void removeFileFailsWhenPathMissing() {
        String args = "{\"action\":\"remove_file\",\"name\":\"my-skill\",\"file_path\":\"\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("file_path is required");
    }

    @Test
    void unknownActionReturnsFail() {
        String args = "{\"action\":\"bogus\",\"name\":\"my-skill\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown action");
    }

    @Test
    void createInvalidSkillNameThrows() {
        // "My Skill" has a space and uppercase → invalid
        String args = "{\"action\":\"create\",\"name\":\"My Skill\",\"content\":\"hello\"}";
        assertThatThrownBy(() -> tool.execute(args, assistant(), session()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createBlankNameThrows() {
        String args = "{\"action\":\"create\",\"name\":\"\",\"content\":\"hello\"}";
        assertThatThrownBy(() -> tool.execute(args, assistant(), session()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createConsecutiveHyphensThrows() {
        String args = "{\"action\":\"create\",\"name\":\"my--skill\",\"content\":\"hello\"}";
        assertThatThrownBy(() -> tool.execute(args, assistant(), session()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeContextSetsBackgroundReview() {
        WriteContext.set(WriteOrigin.BACKGROUND_REVIEW, "background_review",
            "session-1", "parent-1", "telegram", "memory");
        try {
            String args = "{\"action\":\"create\",\"name\":\"my-skill\",\"content\":\"hello\"}";
            ToolResult result = tool.execute(args, assistant(), session());
            assertThat(result.success()).isTrue();
            verify(skillManager).saveSkill(eq("my-skill"), any(), eq(WriteOrigin.BACKGROUND_REVIEW));
        } finally {
            WriteContext.clear();
        }
    }
}