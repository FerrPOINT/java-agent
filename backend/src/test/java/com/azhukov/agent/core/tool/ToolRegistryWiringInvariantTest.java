package com.azhukov.agent.core.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registry invariants from the tool-wiring audit:
 * 1. Every tool name promised by a static toolset must resolve to a registered
 *    definition, except an explicit allowlist (phantom platform toolsets are
 *    advertised-empty by design since 0.1.229).
 * 2. expandToolsetNames must expand composites and let the caller subtract
 *    blocked names post-expansion (delegation leak fix).
 * 3. send_message schema must expose every action the handler supports.
 */
@SpringBootTest
@ActiveProfiles("noop")
class ToolRegistryWiringInvariantTest {

    @Autowired
    private SpringToolRegistry registry;

    /** Toolsets that deliberately advertise zero tools (platform-gated). */
    private static final Set<String> EMPTY_BY_DESIGN = Set.of(
        "homeassistant", "computer_use", "video", "video_gen", "x_search", "stt",
        "context_engine", "spotify", "discord", "yuanbao", "gateway", "hermes-gateway",
        "kanban", "email", "feishu", "dingtalk", "wecom", "weixin", "qqbot",
        "mcp", "delegation");

    @Test
    void everyAdvertisedStaticToolsetToolHasAHandler() {
        Set<String> registered = new HashSet<>();
        registry.getDefinitions().forEach(d -> registered.add(d.name()));

        List<String> offenders = new java.util.ArrayList<>();
        for (String toolset : registry.getToolsets()) {
            if (EMPTY_BY_DESIGN.contains(toolset)) {
                continue;
            }
            for (String toolName : registry.expandToolsetNames(Set.of(toolset))) {
                if (!registered.contains(toolName)) {
                    offenders.add(toolset + ":" + toolName);
                }
            }
        }
        assertThat(offenders)
            .as("static toolset promises a tool with no registered handler")
            .isEmpty();
    }

    @Test
    void expandToolsetNamesExpandsHermesCliComposite() {
        Set<String> names = registry.expandToolsetNames(Set.of("hermes-cli"));
        assertThat(names).contains("delegate_task", "clarify", "memory", "cronjob");
        // …so a caller can subtract exactly these blocked names post-expansion
        Set<String> denied = Set.of("delegate_task", "clarify", "memory", "send_message", "cronjob");
        Set<String> remaining = new HashSet<>(names);
        remaining.removeAll(denied);
        assertThat(remaining).doesNotContainAnyElementsOf(denied);
        assertThat(remaining).contains("web_search", "terminal", "read_file");
    }

    @Test
    void sendMessageSchemaExposesEveryHandlerAction() {
        var def = registry.getDefinitions().stream()
            .filter(d -> "send_message".equals(d.name()))
            .findFirst();
        assertThat(def).as("send_message registered").isPresent();
        var propsMap = (java.util.Map<?, ?>) def.get().parameters().getOrDefault("properties",
            def.get().parameters());
        var action = (java.util.Map<?, ?>) propsMap.get("action");
        assertThat(action).isNotNull();
        var enumValues = (List<?>) action.get("enum");
        assertThat(enumValues.toString())
            .contains("send", "list", "react", "unreact");
        assertThat(propsMap.keySet().toString()).contains("emoji", "message_id");
    }
}
