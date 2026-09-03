package com.azhukov.agent.tools.memory;

import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * S9: SkillsListTool — progressive disclosure with name, description, category.
 * <p>
 * P2-50: Optional category filter — when provided, only skills matching the
 * given category are returned (case-insensitive), matching Hermes behavior.
 */
@AgentTool(
    name = "skills_list",
    description = "List available skills (name + description). Use skill_view(name) to load full content.",
    toolset = "skills"
)
@Component
@RequiredArgsConstructor
public class SkillsListTool implements ToolHandler {

    private static final ObjectMapper JSON = SharedObjectMapper.get();

    private final SkillManager skillManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SkillsListArgs args;
        try {
            args = ToolHandler.parseJson(arguments, SkillsListArgs.class);
        } catch (Exception e) {
            return jsonFail("Invalid tool arguments: " + e.getMessage());
        }
        try {
            String categoryFilter = args.category();

            // S9: Return name + category + trust level (not just name)
            List<SkillManager.SkillInfo> skills = skillManager.listSkills();
            if (skills.isEmpty()) {
                return skillsJson(List.of(), "No skills found in skills/ directory.");
            }

            // P2-50: Filter by category when provided
            if (categoryFilter != null && !categoryFilter.isBlank()) {
                skills = skills.stream()
                    .filter(s -> s.category() != null
                        && s.category().equalsIgnoreCase(categoryFilter))
                    .toList();
                if (skills.isEmpty()) {
                    return skillsJson(List.of(), "No skills available in category: " + categoryFilter);
                }
            }

            return skillsJson(skills, null);
        } catch (Exception e) {
            return jsonFail("Failed to list skills: " + e.getMessage());
        }
    }

    private ToolResult skillsJson(List<SkillManager.SkillInfo> skills, String message) {
        List<Map<String, Object>> rows = skills.stream()
            .sorted(Comparator
                .comparing((SkillManager.SkillInfo s) -> blankToEmpty(s.category()))
                .thenComparing(SkillManager.SkillInfo::name))
            .map(this::skillRow)
            .toList();

        TreeSet<String> categories = new TreeSet<>();
        for (var skill : rows) {
            Object category = skill.get("category");
            if (category instanceof String s && !s.isBlank()) {
                categories.add(s);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("skills", rows);
        result.put("categories", List.copyOf(categories));
        result.put("count", rows.size());
        result.put("hint", "Use skill_view(name) to see full content, tags, and linked files");
        if (message != null && !message.isBlank()) {
            result.put("message", message);
        }
        return jsonOk(result);
    }

    private Map<String, Object> skillRow(SkillManager.SkillInfo skill) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", skill.name());
        row.put("description", descriptionOf(skill));
        if (skill.category() != null && !skill.category().isBlank()) {
            row.put("category", skill.category());
        }
        if (skill.archived()) {
            row.put("archived", true);
        }
        return row;
    }

    private String descriptionOf(SkillManager.SkillInfo skill) {
        String desc = skill.description();
        if (desc == null || desc.isBlank()) {
            desc = frontmatterDescription(skill.content());
        }
        return desc == null ? "" : desc;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ToolResult jsonOk(Map<String, Object> result) {
        try {
            return ToolResult.ok(JSON.writeValueAsString(result));
        } catch (IOException e) {
            return ToolResult.ok(String.valueOf(result));
        }
    }

    private static ToolResult jsonFail(String message) {
        String error = message == null || message.isBlank() ? "Failed to list skills" : message;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", error);
        try {
            return new ToolResult(false, JSON.writeValueAsString(result), error);
        } catch (IOException e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"Failed to list skills\"}", error);
        }
    }

    /** Best-effort frontmatter description parse (falls back to null). */
    private String frontmatterDescription(String content) {
        try {
            if (content == null || !content.startsWith("---")) return null;
            int end = content.indexOf("\n---", 3);
            if (end < 0) return null;
            for (String line : content.substring(3, end).lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.startsWith("description:")) {
                    return trimmed.substring("description:".length()).trim();
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    public record SkillsListArgs(
        @ToolParam(description = "Optional: filter skills by category (case-insensitive)", required = false)
        String category
    ) {}
}
