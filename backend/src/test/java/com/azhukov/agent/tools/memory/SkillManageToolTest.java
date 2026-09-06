package com.azhukov.agent.tools.memory;

import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.memory.WriteContext;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.WriteOrigin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SkillManageTool} — covers create, update, delete, patch,
 * write_file, remove_file, and validation error branches.
 */
@ExtendWith(MockitoExtension.class)
class SkillManageToolTest {

    private static final ObjectMapper JSON = SharedObjectMapper.get();

    @Mock private SkillManager skillManager;

    private SkillManageTool tool;

    @BeforeEach
    void setUp() {
        tool = new SkillManageTool(skillManager,
            new com.azhukov.agent.core.skill.SkillMutationLedger(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                    .getBeanProvider(com.azhukov.agent.core.ports.SkillAuditPort.class)));
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

    private JsonNode json(ToolResult result) {
        try {
            return JSON.readTree(result.content());
        } catch (Exception e) {
            throw new AssertionError("Expected JSON tool result: " + result.content(), e);
        }
    }

    private String message(ToolResult result) {
        return json(result).path("message").asText();
    }

    private String error(ToolResult result) {
        assertThat(result.success()).isFalse();
        String error = json(result).path("error").asText();
        assertThat(error).isNotBlank();
        assertThat(result.error()).isEqualTo(error);
        return error;
    }

    private String skillContent(String name, String description) {
        return "---\nname: " + name + "\ndescription: \"" + description + "\"\n---\nbody";
    }

    @Test
    void successfulMutationBumpsManageCountAndInvalidatesPromptCache() {
        // Hermes parity (skill_manager_tool.py:1653): every successful mutation
        // clears the cached skills system prompt + bumps the manage counter.
        com.azhukov.agent.core.prompt.PromptCacheTracker tracker =
            new com.azhukov.agent.core.prompt.PromptCacheTracker(new com.azhukov.agent.config.AgentProperties());
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "promptCacheTracker", tracker);

        ToolResult result = tool.execute("{\"action\":\"create\",\"name\":\"cache-check\",\"content\":\"---\\ndescription: x\\n---\\nbody\"}",
            assistant(), session());

        assertThat(result.success()).isTrue();
        verify(skillManager).incrementManageCount("cache-check");
    }

    @Test
    void createSavesSkillWithFrontmatter() {
        String args = "{\"action\":\"create\",\"name\":\"my-skill\",\"content\":\""
            + skillContent("my-skill", "demo").replace("\n", "\\n").replace("\"", "\\\"")
            + "\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("created");
        assertThat(json(result).path("hint").asText()).contains("write_file");
        assertThat(json(result).path("_change").path("description").asText()).isEqualTo("demo");
        verify(skillManager).saveSkill(eq("my-skill"), any(), any());
    }

    @Test
    void createWithBlankContentReturnsJsonError() {
        String args = "{\"action\":\"create\",\"name\":\"my-skill\",\"content\":\"\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("content is required");
        verify(skillManager, never()).saveSkill(eq("my-skill"), any(), any());
    }

    @Test
    void invalidJsonReturnsStructuredError() {
        ToolResult result = tool.execute("{not-json", assistant(), session());

        assertThat(error(result)).contains("Invalid tool arguments");
        verifyNoInteractions(skillManager);
    }

    @Test
    void createWithExistingFrontmatterPreservesIt() {
        String args = "{\"action\":\"create\",\"name\":\"my-skill\",\"content\":\"---\\nname: x\\ndescription: y\\n---\\nbody\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).saveSkill(eq("my-skill"), eq("---\nname: x\ndescription: y\n---\nbody"), any());
    }

    @Test
    void updateSavesSkillContent() {
        String content = skillContent("my-skill", "new description");
        String args = "{\"action\":\"update\",\"name\":\"my-skill\",\"content\":\""
            + content.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("updated");
        assertThat(json(result).path("_change").path("description").asText()).isEqualTo("new description");
        verify(skillManager).saveSkill(eq("my-skill"), eq(content), any(), any());
    }

    @Test
    void deleteReturnsOkWhenDeleted() {
        when(skillManager.deleteSkill("my-skill")).thenReturn(true);
        String args = "{\"action\":\"delete\",\"name\":\"my-skill\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("deleted");
        verify(skillManager).deleteSkill("my-skill");
    }

    @Test
    void deleteReturnsFailWhenNotFound() {
        when(skillManager.deleteSkill("my-skill")).thenReturn(false);
        String args = "{\"action\":\"delete\",\"name\":\"my-skill\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("not found");
    }

    @Test
    void patchSucceedsWhenPatched() {
        when(skillManager.patchSkill("my-skill", "old", "new", false)).thenReturn(true);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\",\"new_text\":\"new\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("Patched SKILL.md");
        assertThat(json(result).path("_change").path("old").asText()).isEqualTo("old");
    }

    @Test
    void patchFailsWhenOldTextMissing() {
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"\",\"new_text\":\"new\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("old_string/new_string");
    }

    @Test
    void patchFailsWhenNewTextMissing() {
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("new_string is required");
    }

    @Test
    void patchWithContentPerformsFullRewrite() {
        String content = skillContent("my-skill", "rewritten");
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"content\":\""
            + content.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";

        ToolResult result = tool.execute(args, assistant(), session());

        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("updated (full rewrite)");
        verify(skillManager).saveSkill(eq("my-skill"), eq(content), any(), any());
    }

    @Test
    void patchContentCannotCombineWithOldString() {
        String content = skillContent("my-skill", "rewritten");
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"content\":\""
            + content.replace("\n", "\\n").replace("\"", "\\\"")
            + "\",\"old_string\":\"old\",\"new_string\":\"new\"}";

        ToolResult result = tool.execute(args, assistant(), session());

        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("Pass EITHER content");
        verify(skillManager, never()).saveSkill(any(), any(), any(), any());
    }

    @Test
    void patchFailsWhenNotFound() {
        when(skillManager.patchSkill("my-skill", "old", "new", false)).thenReturn(false);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\",\"new_text\":\"new\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("not found");
    }

    @Test
    void patchAmbiguousMatchReturnsFail() {
        when(skillManager.patchSkill("my-skill", "old", "new", false))
            .thenThrow(new IllegalArgumentException("old_text matches 2 times; use replace_all=true"));
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\",\"new_text\":\"new\"}";

        ToolResult result = tool.execute(args, assistant(), session());

        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("old_text matches 2 times");
    }

    @Test
    void writeFileSucceeds() {
        String args = "{\"action\":\"write_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\",\"content\":\"data\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("written");
        assertThat(json(result).path("file_path").asText()).isEqualTo("references/ref.md");
        verify(skillManager).writeSupportFile("my-skill", "references/ref.md", "data");
    }

    @Test
    void writeFileAcceptsHermesFileContentAlias() {
        String args = "{\"action\":\"write_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\",\"file_content\":\"data\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).writeSupportFile("my-skill", "references/ref.md", "data");
    }

    @Test
    void writeFileFailsWhenPathMissing() {
        String args = "{\"action\":\"write_file\",\"name\":\"my-skill\",\"file_path\":\"\",\"content\":\"data\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("file_path is required");
    }

    @Test
    void writeFileFailsWhenContentMissing() {
        String args = "{\"action\":\"write_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("file_content is required");
    }

    @Test
    void writeFileCatchesException() {
        doThrow(new RuntimeException("disk full"))
            .when(skillManager).writeSupportFile("my-skill", "references/ref.md", "data");
        String args = "{\"action\":\"write_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\",\"content\":\"data\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("disk full");
    }

    @Test
    void removeFileSucceeds() {
        when(skillManager.removeSupportFile("my-skill", "references/ref.md")).thenReturn(true);
        String args = "{\"action\":\"remove_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("removed");
    }

    @Test
    void removeFileFailsWhenNotFound() {
        when(skillManager.removeSupportFile("my-skill", "references/ref.md")).thenReturn(false);
        String args = "{\"action\":\"remove_file\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("not found");
    }

    @Test
    void removeFileFailsWhenPathMissing() {
        String args = "{\"action\":\"remove_file\",\"name\":\"my-skill\",\"file_path\":\"\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("file_path is required");
    }

    @Test
    void unknownActionReturnsFail() {
        String args = "{\"action\":\"bogus\",\"name\":\"my-skill\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("Unknown action");
    }

    @Test
    void createInvalidSkillNameReturnsJsonError() {
        // "My Skill" has a space and uppercase -> invalid
        String args = "{\"action\":\"create\",\"name\":\"My Skill\",\"content\":\""
            + skillContent("My Skill", "bad").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("Invalid skill name");
    }

    @Test
    void createBlankNameReturnsJsonError() {
        String args = "{\"action\":\"create\",\"name\":\"\",\"content\":\""
            + skillContent("blank", "bad").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("blank");
    }

    @Test
    void createConsecutiveHyphensAllowedLikeHermes() {
        String args = "{\"action\":\"create\",\"name\":\"my--skill\",\"content\":\""
            + skillContent("my--skill", "ok").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).saveSkill(eq("my--skill"), any(), any());
    }

    @Test
    void writeContextSetsBackgroundReview() {
        WriteContext.set(WriteOrigin.BACKGROUND_REVIEW, "background_review",
            "session-1", "parent-1", "telegram", "memory");
        try {
            String args = "{\"action\":\"create\",\"name\":\"my-skill\",\"content\":\""
                + skillContent("my-skill", "background").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
            ToolResult result = tool.execute(args, assistant(), session());
            assertThat(result.success()).isTrue();
            verify(skillManager).saveSkill(eq("my-skill"), any(), eq(WriteOrigin.BACKGROUND_REVIEW));
        } finally {
            WriteContext.clear();
        }
    }

    // ─── replace_all tests ───

    @Test
    void patchReplaceAllDefaultIsFirstOnly() {
        // Default replace_all=false → calls patchSkill with replaceAll=false
        when(skillManager.patchSkill("my-skill", "old", "new", false)).thenReturn(true);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\",\"new_text\":\"new\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("Patched SKILL.md");
        verify(skillManager).patchSkill("my-skill", "old", "new", false);
    }

    @Test
    void patchReplaceAllTrueCallsWithTrue() {
        when(skillManager.patchSkill("my-skill", "old", "new", true)).thenReturn(true);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\",\"new_text\":\"new\",\"replace_all\":true}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).patchSkill("my-skill", "old", "new", true);
    }

    @Test
    void patchReplaceAllFalseExplicit() {
        when(skillManager.patchSkill("my-skill", "old", "new", false)).thenReturn(true);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"old_text\":\"old\",\"new_text\":\"new\",\"replace_all\":false}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).patchSkill("my-skill", "old", "new", false);
    }

    // ─── patch support file (file_path) tests ───

    @Test
    void patchSupportFileSucceeds() {
        when(skillManager.patchSupportFile("my-skill", "references/ref.md", "old", "new", false)).thenReturn(true);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\",\"old_text\":\"old\",\"new_text\":\"new\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("Patched references/ref.md");
        assertThat(json(result).path("file_path").asText()).isEqualTo("references/ref.md");
    }

    @Test
    void patchSupportFileReplaceAllTrue() {
        when(skillManager.patchSupportFile("my-skill", "templates/tmpl.txt", "old", "new", true)).thenReturn(true);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"file_path\":\"templates/tmpl.txt\",\"old_text\":\"old\",\"new_text\":\"new\",\"replace_all\":true}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).patchSupportFile("my-skill", "templates/tmpl.txt", "old", "new", true);
    }

    @Test
    void patchSupportFileFailsWhenNotFound() {
        when(skillManager.patchSupportFile("my-skill", "references/ref.md", "old", "new", false)).thenReturn(false);
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\",\"old_text\":\"old\",\"new_text\":\"new\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("not found");
    }

    @Test
    void patchSupportFileAmbiguousMatchReturnsFail() {
        when(skillManager.patchSupportFile("my-skill", "references/ref.md", "old", "new", false))
            .thenThrow(new IllegalArgumentException("old_text matches 2 times; use replace_all=true"));
        String args = "{\"action\":\"patch\",\"name\":\"my-skill\",\"file_path\":\"references/ref.md\",\"old_text\":\"old\",\"new_text\":\"new\"}";

        ToolResult result = tool.execute(args, assistant(), session());

        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("old_text matches 2 times");
    }

    // ─── absorbed_into in delete tests ───

    @Test
    void deleteWithAbsorbedIntoCallsOverload() {
        when(skillManager.deleteSkill("my-skill", "umbrella-skill")).thenReturn(true);
        String args = "{\"action\":\"delete\",\"name\":\"my-skill\",\"absorbed_into\":\"umbrella-skill\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(message(result)).contains("deleted");
        assertThat(json(result).path("absorbed_into").asText()).isEqualTo("umbrella-skill");
        verify(skillManager).deleteSkill("my-skill", "umbrella-skill");
        verify(skillManager, never()).deleteSkill("my-skill");
    }

    @Test
    void deleteWithoutAbsorbedIntoCallsSimpleOverload() {
        when(skillManager.deleteSkill("my-skill")).thenReturn(true);
        String args = "{\"action\":\"delete\",\"name\":\"my-skill\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).deleteSkill("my-skill");
    }

    @Test
    void deleteWithBlankAbsorbedIntoCallsSimpleOverload() {
        when(skillManager.deleteSkill("my-skill")).thenReturn(true);
        String args = "{\"action\":\"delete\",\"name\":\"my-skill\",\"absorbed_into\":\"\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isTrue();
        verify(skillManager).deleteSkill("my-skill");
    }

    @Test
    void deleteWithAbsorbedIntoFailsWhenNotFound() {
        when(skillManager.deleteSkill("my-skill", "umbrella")).thenReturn(false);
        String args = "{\"action\":\"delete\",\"name\":\"my-skill\",\"absorbed_into\":\"umbrella\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("not found");
    }

    // ─── P2-49: Security scan rollback tests ───

    @Test
    void createSecurityExceptionReturnsFailNotThrows() {
        // When saveSkill throws SecurityException, the tool should catch it
        // and return ToolResult.fail instead of propagating the exception.
        doThrow(new SecurityException("Security scan blocked skill 'evil' (trust: AGENT_CREATED, verdict: DANGEROUS)"))
            .when(skillManager).saveSkill(eq("evil-skill"), any(), any());
        String args = "{\"action\":\"create\",\"name\":\"evil-skill\",\"content\":\""
            + skillContent("evil-skill", "bad").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("Security scan blocked");
    }

    @Test
    void updateSecurityExceptionReturnsFailNotThrows() {
        doThrow(new SecurityException("Security scan blocked skill 'evil' (trust: AGENT_CREATED, verdict: DANGEROUS)"))
            .when(skillManager).saveSkill(eq("evil-skill"), any(), any(), any());
        String args = "{\"action\":\"update\",\"name\":\"evil-skill\",\"content\":\""
            + skillContent("evil-skill", "bad").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("Security scan blocked");
    }

    @Test
    void patchSkillSecurityExceptionReturnsFailNotThrows() {
        // When patchSkill throws SecurityException (e.g., because the patched
        // content fails the security scan in saveSkill), the tool should catch
        // it and return ToolResult.fail instead of propagating the exception.
        doThrow(new SecurityException("Security scan blocked skill 'evil' (trust: AGENT_CREATED, verdict: DANGEROUS)"))
            .when(skillManager).patchSkill("evil-skill", "safe", "rm -rf /", false);
        String args = "{\"action\":\"patch\",\"name\":\"evil-skill\",\"old_text\":\"safe\",\"new_text\":\"rm -rf /\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("Security scan blocked");
    }

    @Test
    void writeFileSecurityExceptionReturnsFailNotThrows() {
        doThrow(new SecurityException("Security scan blocked skill 'evil' (trust: AGENT_CREATED, verdict: DANGEROUS)"))
            .when(skillManager).writeSupportFile("evil-skill", "references/ref.md", "rm -rf /");
        String args = "{\"action\":\"write_file\",\"name\":\"evil-skill\",\"file_path\":\"references/ref.md\",\"content\":\"rm -rf /\"}";
        ToolResult result = tool.execute(args, assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(error(result)).contains("Security scan blocked");
    }
}
