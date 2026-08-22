package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GET /v1/toolsets — list toolsets and their resolved tools.
 *
 * Mirrors Hermes' GET /v1/toolsets endpoint: returns each toolset's
 * enabled/configured state plus the concrete tool names it expands to.
 */
@RestController
@RequestMapping("/v1/toolsets")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "OpenAI-compatible", description = "Toolset listing and management")
public class ToolsetsController {

    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;

    @GetMapping
    @Operation(summary = "List all toolsets with their tools and enabled state")
    public Map<String, Object> listToolsets() {
        Set<String> allToolsets = toolRegistry.getToolsets();
        List<String> defaultToolsets = properties.getSkills() != null
            ? properties.getSkills().getDefaultToolsets()
            : List.of();

        List<Map<String, Object>> data = new ArrayList<>();

        for (String toolset : allToolsets) {
            List<ToolDefinition> tools = toolRegistry.getDefinitions(Set.of(toolset));
            List<String> toolNames = tools.stream()
                .map(ToolDefinition::name)
                .sorted()
                .toList();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", toolset);
            entry.put("label", toolset);
            entry.put("description", "Toolset: " + toolset);
            entry.put("enabled", defaultToolsets.contains(toolset));
            entry.put("configured", true);
            entry.put("tools", toolNames);
            data.add(entry);
        }

        return Map.of(
            "object", "list",
            "platform", "java-agent",
            "data", data
        );
    }

    /** Toggle a toolset for NEW sessions (default-toolsets override). */
    @org.springframework.web.bind.annotation.PostMapping("/{toolset}/enable")
    public Map<String, Object> enable(@org.springframework.web.bind.annotation.PathVariable String toolset) {
        return toggle(toolset, true);
    }

    @org.springframework.web.bind.annotation.PostMapping("/{toolset}/disable")
    public Map<String, Object> disable(@org.springframework.web.bind.annotation.PathVariable String toolset) {
        return toggle(toolset, false);
    }

    private Map<String, Object> toggle(String toolset, boolean enabled) {
        Set<String> all = toolRegistry.getToolsets();
        if (!all.contains(toolset)) {
            return Map.of("ok", false, "reason", "unknown toolset: " + toolset);
        }
        List<String> current = new ArrayList<>(properties.getSkills() != null
            ? properties.getSkills().getDefaultToolsets() : List.of());
        if (enabled && !current.contains(toolset)) {
            current.add(toolset);
        } else if (!enabled) {
            current.remove(toolset);
        }
        properties.getSkills().setDefaultToolsets(current);
        return Map.of("ok", true, "toolset", toolset, "enabled", enabled);
    }
}