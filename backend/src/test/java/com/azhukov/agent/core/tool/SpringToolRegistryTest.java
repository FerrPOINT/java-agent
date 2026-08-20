package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SpringToolRegistry} — the production implementation of
 * {@link ToolRegistry}. Covers all public methods: getDefinitions(), getDefinitions(Set),
 * execute(), getToolsets(), registerDynamic(), deregisterDynamic(), and toolset filtering.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringToolRegistryTest {

    private org.springframework.context.ApplicationContext context;
    private AgentProperties properties;
    private ObjectMapper objectMapper;
    private ManagedToolGate managedToolGateway;
    private SpringToolRegistry registry;

    // ── Test fixtures ──

    /** A fake handler annotated with @AgentTool for the "core" toolset. */
    @AgentTool(name = "core_tool", description = "A core tool", toolset = "core")
    static class CoreToolHandler implements ToolHandler {
        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            return ToolResult.ok("core-result");
        }
    }

    /** A fake handler annotated with @AgentTool for the "filesystem" toolset. */
    @AgentTool(name = "fs_tool", description = "A filesystem tool", toolset = "filesystem")
    static class FsToolHandler implements ToolHandler {
        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            return ToolResult.ok("fs-result");
        }
    }

    /** A fake handler annotated with @AgentTool for a different toolset. */
    @AgentTool(name = "web_tool", description = "A web tool", toolset = "web")
    static class WebToolHandler implements ToolHandler {
        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            return ToolResult.ok("web-result");
        }
    }

    /** A non-ToolHandler bean that should be skipped during registration. */
    @AgentTool(name = "not_a_handler", description = "Should be skipped")
    static class NotAHandler {
    }

    @BeforeEach
    void setUp() {
        context = mock(org.springframework.context.ApplicationContext.class);
        properties = new AgentProperties();
        objectMapper = new ObjectMapper();
        managedToolGateway = new ManagedToolGate(properties);

        // Wire up three real ToolHandler beans + one non-handler bean
        Map<String, Object> beans = new LinkedHashMap<>();
        CoreToolHandler coreHandler = new CoreToolHandler();
        FsToolHandler fsHandler = new FsToolHandler();
        WebToolHandler webHandler = new WebToolHandler();
        NotAHandler notHandler = new NotAHandler();
        beans.put("coreTool", coreHandler);
        beans.put("fsTool", fsHandler);
        beans.put("webTool", webHandler);
        beans.put("notHandler", notHandler);
        when(context.getBeansWithAnnotation(AgentTool.class)).thenReturn(beans);

        registry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        registry.registerBeans(); // manually trigger @PostConstruct
    }

    // ── getDefinitions() ──

    @Test
    void getDefinitions_returnsAllRegisteredTools() {
        List<ToolDefinition> defs = registry.getDefinitions();

        assertThat(defs).hasSize(3); // NotAHandler is skipped
        assertThat(defs).extracting(ToolDefinition::name)
            .containsExactlyInAnyOrder("core_tool", "fs_tool", "web_tool");
    }

    @Test
    void getDefinitions_returnsNewListOnEachCall() {
        List<ToolDefinition> first = registry.getDefinitions();
        List<ToolDefinition> second = registry.getDefinitions();

        assertThat(first).isNotSameAs(second);
        assertThat(first).hasSameSizeAs(second);
    }

    // ── getDefinitions(Set<String> toolsets) ──

    @Test
    void getDefinitions_withToolsetFilter_returnsOnlyMatchingTools() {
        List<ToolDefinition> defs = registry.getDefinitions(Set.of("core"));

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).name()).isEqualTo("core_tool");
    }

    @Test
    void getDefinitions_withMultipleToolsets_returnsMatchingTools() {
        List<ToolDefinition> defs = registry.getDefinitions(Set.of("core", "web"));

        assertThat(defs).hasSize(2);
        assertThat(defs).extracting(ToolDefinition::name)
            .containsExactlyInAnyOrder("core_tool", "web_tool");
    }

    @Test
    void getDefinitions_withEmptyToolsetSet_returnsAllTools() {
        List<ToolDefinition> defs = registry.getDefinitions(Set.of());

        assertThat(defs).hasSize(3);
    }

    @Test
    void getDefinitions_withNullToolsetSet_returnsAllTools() {
        List<ToolDefinition> defs = registry.getDefinitions(null);

        assertThat(defs).hasSize(3);
    }

    @Test
    void getDefinitions_withNonMatchingToolset_returnsEmptyList() {
        List<ToolDefinition> defs = registry.getDefinitions(Set.of("nonexistent"));

        assertThat(defs).isEmpty();
    }

    // ── execute() ──

    @Test
    void execute_knownTool_delegatesToHandler() {
        ToolResult result = registry.execute("core_tool", "call-1", "{}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("core-result");
    }

    @Test
    void execute_unknownTool_returnsFailResult() {
        ToolResult result = registry.execute("nonexistent_tool", "call-1", "{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown tool");
        assertThat(result.error()).contains("nonexistent_tool");
    }

    @Test
    void execute_filesystemTool_delegatesCorrectly() {
        ToolResult result = registry.execute("fs_tool", "call-2", "{}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("fs-result");
    }

    // ── getToolsets() ──

    @Test
    void getToolsets_returnsAllDistinctToolsets() {
        Set<String> toolsets = registry.getToolsets();

        assertThat(toolsets).containsExactlyInAnyOrder("core", "filesystem", "web");
    }

    // ── registerDynamic() / deregisterDynamic() ──

    @Test
    void registerDynamic_addsToolAccessibleByAllMethods() {
        ToolDefinition dynDef = new ToolDefinition("dyn_tool", "Dynamic tool", Map.of("type", "object"));
        ToolHandler dynHandler = (args, lastAssistant, session) -> ToolResult.ok("dyn-result");

        registry.registerDynamic("dyn_tool", dynDef, dynHandler);

        // Visible in getDefinitions
        List<ToolDefinition> defs = registry.getDefinitions();
        assertThat(defs).extracting(ToolDefinition::name).contains("dyn_tool");

        // Executable
        ToolResult result = registry.execute("dyn_tool", "call-3", "{}", null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("dyn-result");
    }

    @Test
    void registerDynamic_toolWithoutToolsetAnnotation_appearsInAllToolsetFilters() {
        ToolDefinition dynDef = new ToolDefinition("dyn_no_toolset", "Dynamic no toolset", Map.of("type", "object"));
        ToolHandler dynHandler = (args, lastAssistant, session) -> ToolResult.ok("ok");

        registry.registerDynamic("dyn_no_toolset", dynDef, dynHandler);

        // Dynamic tools have annotation=null, so the filter `e.annotation() == null` matches
        List<ToolDefinition> defs = registry.getDefinitions(Set.of("any_random_toolset"));
        assertThat(defs).extracting(ToolDefinition::name).contains("dyn_no_toolset");
    }

    @Test
    void deregisterDynamic_removesToolFromRegistry() {
        // First register a dynamic tool
        ToolDefinition dynDef = new ToolDefinition("to_remove", "Temp tool", Map.of("type", "object"));
        ToolHandler dynHandler = (args, lastAssistant, session) -> ToolResult.ok("temp");
        registry.registerDynamic("to_remove", dynDef, dynHandler);
        assertThat(registry.getDefinitions()).extracting(ToolDefinition::name).contains("to_remove");

        // Deregister it
        registry.deregisterDynamic("to_remove");

        // No longer in definitions
        assertThat(registry.getDefinitions()).extracting(ToolDefinition::name).doesNotContain("to_remove");

        // Execution fails
        ToolResult result = registry.execute("to_remove", "call-4", "{}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown tool");
    }

    @Test
    void deregisterDynamic_nonExistentTool_doesNotThrow() {
        // Should be a no-op, not throw
        registry.deregisterDynamic("never_registered");
        // Other tools still present
        assertThat(registry.getDefinitions()).hasSize(3);
    }

    @Test
    void registerDynamic_overwritesExistingToolWithSameName() {
        // Register initial dynamic tool
        ToolDefinition def1 = new ToolDefinition("override_tool", "V1", Map.of("type", "object"));
        ToolHandler handler1 = (args, lastAssistant, session) -> ToolResult.ok("v1");
        registry.registerDynamic("override_tool", def1, handler1);

        // Overwrite with new handler
        ToolDefinition def2 = new ToolDefinition("override_tool", "V2", Map.of("type", "object"));
        ToolHandler handler2 = (args, lastAssistant, session) -> ToolResult.ok("v2");
        registry.registerDynamic("override_tool", def2, handler2);

        // Only one entry with that name, and it uses the new handler
        List<ToolDefinition> defs = registry.getDefinitions();
        long count = defs.stream().filter(d -> d.name().equals("override_tool")).count();
        assertThat(count).isEqualTo(1);

        ToolResult result = registry.execute("override_tool", "call-5", "{}", null, null);
        assertThat(result.content()).isEqualTo("v2");
    }

    // ── Tool definition structure ──

    @Test
    void getDefinitions_includesCorrectNameAndDescription() {
        List<ToolDefinition> defs = registry.getDefinitions();
        ToolDefinition coreDef = defs.stream()
            .filter(d -> d.name().equals("core_tool"))
            .findFirst().orElseThrow();

        assertThat(coreDef.name()).isEqualTo("core_tool");
        assertThat(coreDef.description()).isEqualTo("A core tool");
        assertThat(coreDef.parameters()).containsKey("type");
        assertThat(coreDef.parameters().get("type")).isEqualTo("object");
    }

    // ── ManagedToolGate integration ──

    @Test
    void registerBeans_skipsToolsDisabledByGateway() {
        // Enable managed gateway and register a check that disables "web_tool"
        properties.getTools().setManagedGatewayEnabled(true);
        managedToolGateway.registerTool("web_tool", name -> false);

        // Re-create registry with the same context — web_tool should be filtered out
        SpringToolRegistry filteredRegistry = new SpringToolRegistry(context, properties, objectMapper, managedToolGateway);
        filteredRegistry.registerBeans();

        List<ToolDefinition> defs = filteredRegistry.getDefinitions();
        assertThat(defs).extracting(ToolDefinition::name)
            .containsExactlyInAnyOrder("core_tool", "fs_tool");
        assertThat(defs).extracting(ToolDefinition::name).doesNotContain("web_tool");
    }

    @Test
    void registerBeans_skipsBeansThatAreNotToolHandlers() {
        // NotAHandler is annotated with @AgentTool but doesn't implement ToolHandler
        List<ToolDefinition> defs = registry.getDefinitions();
        assertThat(defs).extracting(ToolDefinition::name).doesNotContain("not_a_handler");
    }
}