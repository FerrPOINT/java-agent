package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewToolSchemasTest {

    @Test
    void build_returnsFourToolDefinitions() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        assertThat(tools).hasSize(4);
    }

    @Test
    void build_containsAllWhitelistedTools() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        List<String> names = tools.stream().map(ToolDefinition::name).toList();
        assertThat(names).contains("memory", "skill_manage", "skills_list", "skill_view");
    }

    @Test
    void memoryTool_hasNonEmptyParameters() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition memoryTool = tools.stream()
            .filter(t -> "memory".equals(t.name()))
            .findFirst().orElseThrow();
        assertThat(memoryTool.parameters()).isNotEmpty();
        assertThat(memoryTool.parameters()).containsKey("type");
        assertThat(memoryTool.parameters().get("type")).isEqualTo("object");
    }

    @Test
    void memoryTool_hasRequiredFields() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition memoryTool = tools.stream()
            .filter(t -> "memory".equals(t.name()))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) memoryTool.parameters().get("required");
        assertThat(required).contains("action");
    }

    @Test
    void memoryTool_hasPropertiesWithActionEnum() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition memoryTool = tools.stream()
            .filter(t -> "memory".equals(t.name()))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) memoryTool.parameters().get("properties");
        assertThat(properties).containsKey("action");
        @SuppressWarnings("unchecked")
        Map<String, Object> actionProp = (Map<String, Object>) properties.get("action");
        assertThat(actionProp).containsEntry("type", "string");
        assertThat(actionProp).containsKey("enum");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) actionProp.get("enum");
        assertThat(enumValues).contains("add", "replace", "remove", "read");
    }

    @Test
    void memoryTool_hasContentProperty() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition memoryTool = tools.stream()
            .filter(t -> "memory".equals(t.name()))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) memoryTool.parameters().get("properties");
        assertThat(properties).containsKey("content");
        assertThat(properties).containsKey("old_text");
        assertThat(properties).containsKey("target");
    }

    @Test
    void skillManageTool_hasRequiredActionAndName() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition skillManage = tools.stream()
            .filter(t -> "skill_manage".equals(t.name()))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) skillManage.parameters().get("required");
        assertThat(required).contains("action", "name");
    }

    @Test
    void skillManageTool_hasActionEnumWithAllValues() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition skillManage = tools.stream()
            .filter(t -> "skill_manage".equals(t.name()))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) skillManage.parameters().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> actionProp = (Map<String, Object>) properties.get("action");
        @SuppressWarnings("unchecked")
        List<String> enumValues = (List<String>) actionProp.get("enum");
        assertThat(enumValues).contains("create", "update", "delete", "patch", "write_file", "remove_file");
    }

    @Test
    void skillManageTool_hasContentOldTextNewTextFilePath() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition skillManage = tools.stream()
            .filter(t -> "skill_manage".equals(t.name()))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) skillManage.parameters().get("properties");
        assertThat(properties).containsKeys("content", "old_text", "new_text", "file_path");
    }

    @Test
    void skillViewTool_hasRequiredName() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition skillView = tools.stream()
            .filter(t -> "skill_view".equals(t.name()))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) skillView.parameters().get("required");
        assertThat(required).contains("name");
    }

    @Test
    void skillViewTool_hasNameProperty() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition skillView = tools.stream()
            .filter(t -> "skill_view".equals(t.name()))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) skillView.parameters().get("properties");
        assertThat(properties).containsKey("name");
    }

    @Test
    void skillsListTool_hasObjectTypeSchema() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        ToolDefinition skillsList = tools.stream()
            .filter(t -> "skills_list".equals(t.name()))
            .findFirst().orElseThrow();
        assertThat(skillsList.parameters()).containsEntry("type", "object");
    }

    @Test
    void allToolsHaveDescriptions() {
        List<ToolDefinition> tools = ReviewToolSchemas.build();
        for (ToolDefinition tool : tools) {
            assertThat(tool.description()).isNotBlank();
        }
    }
}