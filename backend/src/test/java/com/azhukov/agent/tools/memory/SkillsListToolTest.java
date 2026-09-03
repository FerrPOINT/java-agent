package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.SkillManager.LinkedFiles;
import com.azhukov.agent.core.skill.SkillManager.SkillInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillsListToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private SkillManager skillManager;

    private SkillsListTool tool;

    @BeforeEach
    void setUp() {
        tool = new SkillsListTool(skillManager);
    }

    @Test
    void listsSkillsAsHermesJsonTierOneMetadata() throws Exception {
        when(skillManager.listSkills()).thenReturn(List.of(
            skill("z-skill", "direct description", "ops", "body", false),
            skill("a-skill", null, "dev", "---\ndescription: frontmatter description\n---\nbody", true)
        ));

        ToolResult result = tool.execute("{}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("count").asInt()).isEqualTo(2);
        assertThat(json.path("categories").get(0).asText()).isEqualTo("dev");
        assertThat(json.path("categories").get(1).asText()).isEqualTo("ops");
        assertThat(json.path("skills").get(0).path("name").asText()).isEqualTo("a-skill");
        assertThat(json.path("skills").get(0).path("description").asText()).isEqualTo("frontmatter description");
        assertThat(json.path("skills").get(0).path("archived").asBoolean()).isTrue();
        assertThat(json.path("skills").get(0).has("content")).isFalse();
        assertThat(json.path("hint").asText()).contains("skill_view");
    }

    @Test
    void emptyCategoryFilterReturnsStructuredEmptyResult() throws Exception {
        when(skillManager.listSkills()).thenReturn(List.of(
            skill("ops-skill", "desc", "ops", "body", false)
        ));

        ToolResult result = tool.execute("{\"category\":\"dev\"}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("count").asInt()).isZero();
        assertThat(json.path("skills").size()).isZero();
        assertThat(json.path("categories").size()).isZero();
        assertThat(json.path("message").asText()).contains("No skills available in category: dev");
    }

    @Test
    void invalidJsonReturnsStructuredFailure() throws Exception {
        ToolResult result = tool.execute("not-json", null, session());

        assertJsonError(result).contains("Invalid tool arguments");
    }

    @Test
    void managerFailureReturnsStructuredFailure() throws Exception {
        when(skillManager.listSkills()).thenThrow(new IllegalStateException("disk unavailable"));

        ToolResult result = tool.execute("{}", null, session());

        assertJsonError(result).contains("Failed to list skills: disk unavailable");
    }

    private static SkillInfo skill(String name, String description, String category, String content, boolean archived) {
        return new SkillInfo(
            name,
            content,
            description,
            category,
            Instant.EPOCH,
            0,
            0,
            Instant.EPOCH,
            archived,
            "AGENT_CREATED",
            List.of(),
            List.of(),
            false,
            new LinkedFiles(List.of(), List.of(), List.of(), List.of())
        );
    }

    private static Session session() {
        return new Session(null, "user", "profile", "noop", "noop", null, Map.of(), null);
    }

    private static String assertJsonError(ToolResult result) throws Exception {
        assertThat(result.success()).isFalse();
        assertThat(result.content()).isNotBlank();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isFalse();
        String error = json.path("error").asText();
        assertThat(result.error()).isEqualTo(error);
        return error;
    }
}
