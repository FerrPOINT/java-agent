package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.browser.BrowserBackTool;
import com.azhukov.agent.tools.browser.BrowserCdpTool;
import com.azhukov.agent.tools.browser.BrowserClickTool;
import com.azhukov.agent.tools.browser.BrowserConsoleTool;
import com.azhukov.agent.tools.browser.BrowserDialogTool;
import com.azhukov.agent.tools.browser.BrowserGetImagesTool;
import com.azhukov.agent.tools.browser.BrowserNavigateTool;
import com.azhukov.agent.tools.browser.BrowserPressTool;
import com.azhukov.agent.tools.browser.BrowserScrollTool;
import com.azhukov.agent.tools.browser.BrowserService;
import com.azhukov.agent.tools.browser.BrowserSnapshotTool;
import com.azhukov.agent.tools.browser.BrowserTypeTool;
import com.azhukov.agent.tools.browser.BrowserVisionTool;
import com.azhukov.agent.tools.file.PatchTool;
import com.azhukov.agent.tools.file.ReadFileTool;
import com.azhukov.agent.tools.file.SearchFilesTool;
import com.azhukov.agent.tools.file.WriteFileTool;
import com.azhukov.agent.tools.memory.ClarifyTool;
import com.azhukov.agent.tools.memory.SessionSearchService;
import com.azhukov.agent.tools.memory.SessionSearchTool;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.databind.JsonNode;
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

        public record Args(
            @ToolParam(description = "maximum count") int count,
            @ToolParam(description = "nested items") List<Item> items
        ) {}

        public record Item(
            @ToolParam(description = "item id") String id,
            @ToolParam(description = "item status", enumValues = {"pending", "done"}) String status
        ) {}
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

    static class OkHandler implements ToolHandler {
        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            return ToolResult.ok("ok");
        }
    }

    @AgentTool(name = "web_search", description = "web search", toolset = "web")
    static class WebSearchHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "search query") String query,
            @ToolParam(description = "maximum number of results", required = false) int limit
        ) {}
    }

    @AgentTool(name = "web_extract", description = "web extract", toolset = "web")
    static class WebExtractHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "urls") List<Object> urls,
            @ToolParam(description = "char limit", required = false) Integer char_limit
        ) {}
    }

    @AgentTool(name = "terminal", description = "terminal", toolset = "terminal")
    static class TerminalHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "command") String command,
            @ToolParam(description = "timeout", required = false) Integer timeout,
            @ToolParam(description = "background", required = false) boolean background,
            @ToolParam(description = "pty", required = false) boolean pty,
            @ToolParam(description = "workdir", required = false) String workdir,
            @com.fasterxml.jackson.annotation.JsonProperty("notify")
            @ToolParam(description = "notify", required = false) Object notifyValue,
            @com.fasterxml.jackson.annotation.JsonProperty("notify_on_complete")
            @ToolParam(description = "notify complete", required = false) boolean notifyOnComplete,
            @com.fasterxml.jackson.annotation.JsonProperty("watch_patterns")
            @ToolParam(description = "watch patterns", required = false) List<String> watchPatterns
        ) {}
    }

    @AgentTool(name = "process", description = "process", toolset = "terminal")
    static class ProcessHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "action") String action,
            @com.fasterxml.jackson.annotation.JsonProperty("session_id")
            @ToolParam(description = "session id", required = false) String sessionId,
            @ToolParam(description = "data", required = false) String data,
            @ToolParam(description = "timeout", required = false) Integer timeout,
            @ToolParam(description = "offset", required = false) Integer offset,
            @ToolParam(description = "limit", required = false) Integer limit
        ) {}
    }

    @AgentTool(name = "read_file", description = "read file", toolset = "file")
    static class ReadFileHandler extends OkHandler {}

    @AgentTool(name = "delete_file", description = "delete file", toolset = "file")
    static class DeleteFileHandler extends OkHandler {}

    @AgentTool(name = "vision_analyze", description = "vision", toolset = "vision")
    static class VisionAnalyzeHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "image") @com.fasterxml.jackson.annotation.JsonProperty("image_url") String image,
            @ToolParam(description = "question") String question,
            @ToolParam(description = "region", required = false) int[] region
        ) {}
    }

    @AgentTool(name = "skill_view", description = "skill view", toolset = "skills")
    static class SkillViewHandler extends OkHandler {}

    @AgentTool(name = "skill_manage", description = "skill manage", toolset = "skills")
    static class SkillManageHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "action") String action,
            @ToolParam(description = "name") String name,
            @ToolParam(description = "content", required = false)
            @com.fasterxml.jackson.annotation.JsonAlias("file_content") String content,
            @ToolParam(description = "old text", required = false)
            @com.fasterxml.jackson.annotation.JsonProperty("old_text")
            @com.fasterxml.jackson.annotation.JsonAlias("old_string") String oldText,
            @ToolParam(description = "new text", required = false)
            @com.fasterxml.jackson.annotation.JsonProperty("new_text")
            @com.fasterxml.jackson.annotation.JsonAlias("new_string") String newText,
            @ToolParam(description = "file path", required = false) String file_path,
            @ToolParam(description = "replace all", required = false) Boolean replace_all,
            @ToolParam(description = "absorbed into", required = false) String absorbed_into,
            @ToolParam(description = "category", required = false) String category
        ) {}
    }

    @AgentTool(name = "execute_code", description = "execute code", toolset = "code_execution")
    static class ExecuteCodeHandler extends OkHandler {
        public static class ExecuteCodeArgs {
            @ToolParam(description = "code")
            private String code;
            @ToolParam(description = "reset", required = false)
            private Boolean reset;
            @ToolParam(description = "legacy timeout", required = false)
            private String timeout;
        }
    }

    @AgentTool(name = "delegate_task", description = "delegate. Leaf children (the default) cannot call delegate_task, clarify, memory, send_message, or cronjob; orchestrators regain only delegate_task.", toolset = "delegation")
    static class DelegateTaskHandler extends OkHandler {}

    @AgentTool(name = "image_generate", description = "image", toolset = "image_gen")
    static class ImageGenerateHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "prompt") String prompt,
            @ToolParam(description = "aspect ratio", required = false)
            @com.fasterxml.jackson.annotation.JsonProperty("aspect_ratio") String aspectRatio,
            @ToolParam(description = "source image", required = false)
            @com.fasterxml.jackson.annotation.JsonProperty("image_url") String imageUrl,
            @ToolParam(description = "reference images", required = false)
            @com.fasterxml.jackson.annotation.JsonProperty("reference_image_urls") List<String> referenceImageUrls,
            @ToolParam(description = "upscale", required = false) Boolean upscale
        ) {}
    }

    @AgentTool(name = "text_to_speech", description = "tts", toolset = "tts")
    static class TextToSpeechHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "text") String text,
            @ToolParam(description = "output path", required = false)
            @com.fasterxml.jackson.annotation.JsonProperty("output_path") String outputPath,
            @ToolParam(description = "voice", required = false) String voice,
            @ToolParam(description = "speed", required = false) Double speed,
            @ToolParam(description = "instructions", required = false) String instructions,
            @ToolParam(description = "provider", required = false) String provider
        ) {}
    }

    @AgentTool(name = "cronjob", description = "cron", toolset = "cronjob")
    static class CronJobHandler extends OkHandler {}

    @AgentTool(name = "send_message", description = "send", toolset = "gateway")
    static class SendMessageHandler extends OkHandler {}

    @AgentTool(name = "memory", description = "memory", toolset = "memory")
    static class MemoryHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "action", required = false, enumValues = {"add", "replace", "remove"}) String action,
            @ToolParam(description = "target", enumValues = {"memory", "user"}) String target,
            @ToolParam(description = "content", required = false) String content,
            @com.fasterxml.jackson.annotation.JsonProperty("old_text") String oldText,
            @com.fasterxml.jackson.annotation.JsonProperty("new_text") String newText,
            @ToolParam(description = "legacy read limit", required = false) Integer limit,
            @ToolParam(description = "operations", required = false) List<MemoryOperation> operations
        ) {}

        public record MemoryOperation(
            @ToolParam(description = "action", enumValues = {"add", "replace", "remove"}) String action,
            @ToolParam(description = "content", required = false) String content,
            @com.fasterxml.jackson.annotation.JsonProperty("old_text") String oldText,
            @com.fasterxml.jackson.annotation.JsonProperty("new_text") String newText
        ) {}
    }

    @AgentTool(name = "todo", description = "todo", toolset = "todo")
    static class TodoHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "todos", required = false) List<TodoItem> todos,
            @ToolParam(description = "merge", required = false) Boolean merge
        ) {}

        public record TodoItem(
            @ToolParam(description = "id") String id,
            @ToolParam(description = "content") String content,
            @ToolParam(description = "status", enumValues = {"pending", "in_progress", "completed", "cancelled"})
            String status
        ) {}
    }

    @AgentTool(name = "session_search", description = "session search", toolset = "session_search")
    static class SessionSearchHandler extends OkHandler {}

    @AgentTool(name = "browser_cdp", description = "browser cdp", toolset = "browser")
    static class BrowserCdpHandler extends OkHandler {}

    @AgentTool(name = "browser_dialog", description = "browser dialog", toolset = "browser")
    static class BrowserDialogHandler extends OkHandler {}

    @AgentTool(name = "browser_navigate", description = "browser navigate", toolset = "browser")
    static class BrowserNavigateHandler extends OkHandler {}

    @AgentTool(name = "array_tool", description = "array tool", toolset = "core")
    static class ArrayToolHandler extends OkHandler {
        public record Args(
            @ToolParam(description = "names") List<String> names,
            @ToolParam(description = "flex values") List<Object> values
        ) {}
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

    private SpringToolRegistry registryWithBeans(Object... handlers) {
        org.springframework.context.ApplicationContext ctx = mock(org.springframework.context.ApplicationContext.class);
        Map<String, Object> beans = new LinkedHashMap<>();
        for (int i = 0; i < handlers.length; i++) {
            beans.put("tool" + i, handlers[i]);
        }
        when(ctx.getBeansWithAnnotation(AgentTool.class)).thenReturn(beans);

        SpringToolRegistry r = new SpringToolRegistry(ctx, properties, objectMapper, managedToolGateway);
        r.registerBeans();
        return r;
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

    @Test
    void getDefinitions_withHermesCodingComposite_resolvesByToolName() {
        SpringToolRegistry r = registryWithBeans(
            new WebSearchHandler(), new TerminalHandler(), new ReadFileHandler(),
            new DeleteFileHandler(), new VisionAnalyzeHandler(), new SkillViewHandler(),
            new SkillManageHandler(), new ExecuteCodeHandler(), new DelegateTaskHandler(),
            new ImageGenerateHandler(), new TextToSpeechHandler(), new CronJobHandler(),
            new SendMessageHandler()
        );

        List<ToolDefinition> defs = r.getDefinitions(Set.of("coding"));

        assertThat(defs).extracting(ToolDefinition::name)
            .contains("web_search", "terminal", "read_file", "vision_analyze",
                "skill_view", "skill_manage", "execute_code", "delegate_task")
            .doesNotContain("image_generate", "text_to_speech", "cronjob",
                "send_message", "delete_file");
    }

    @Test
    void getDefinitions_withSafeComposite_excludesTerminalAndGatewayTools() {
        SpringToolRegistry r = registryWithBeans(
            new WebSearchHandler(), new TerminalHandler(), new VisionAnalyzeHandler(),
            new ImageGenerateHandler(), new SendMessageHandler()
        );

        List<ToolDefinition> defs = r.getDefinitions(Set.of("safe"));

        assertThat(defs).extracting(ToolDefinition::name)
            .contains("web_search", "vision_analyze", "image_generate")
            .doesNotContain("terminal", "send_message");
    }

    @Test
    void getDefinitions_withHermesCliCompositeKeepsGatewayOutOfDefaultSurface() {
        SpringToolRegistry r = registryWithBeans(
            new WebSearchHandler(), new TerminalHandler(), new ReadFileHandler(),
            new DeleteFileHandler(), new SkillViewHandler(), new SkillManageHandler(),
            new ImageGenerateHandler(), new TextToSpeechHandler(), new CronJobHandler(),
            new SendMessageHandler()
        );

        List<ToolDefinition> defs = r.getDefinitions(Set.of("hermes-cli"));

        assertThat(defs).extracting(ToolDefinition::name)
            .contains("web_search", "terminal", "read_file", "skill_view",
                "skill_manage", "image_generate", "text_to_speech", "cronjob")
            .doesNotContain("send_message", "delete_file");
    }

    @Test
    void getDefinitions_keepsSessionSearchSeparateFromMemoryToolset() {
        SpringToolRegistry r = registryWithBeans(new MemoryHandler(), new SessionSearchHandler());

        assertThat(r.getDefinitions(Set.of("memory"))).extracting(ToolDefinition::name)
            .containsExactly("memory");
        assertThat(r.getDefinitions(Set.of("session_search"))).extracting(ToolDefinition::name)
            .containsExactly("session_search");
        assertThat(r.getDefinitions(Set.of("hermes-cli"))).extracting(ToolDefinition::name)
            .contains("memory", "session_search");
    }

    @Test
    void getDefinitions_withBrowserCdpAliasResolvesOnlyRawCdpTools() {
        SpringToolRegistry r = registryWithBeans(
            new BrowserCdpHandler(), new BrowserDialogHandler(), new BrowserNavigateHandler()
        );

        assertThat(r.getDefinitions(Set.of("browser-cdp"))).extracting(ToolDefinition::name)
            .containsExactlyInAnyOrder("browser_cdp", "browser_dialog")
            .doesNotContain("browser_navigate");
        assertThat(r.getToolsets()).contains("browser", "browser-cdp");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedArraySchemasIncludeItemsForGenericLists() {
        SpringToolRegistry r = registryWithBeans(new ArrayToolHandler());

        ToolDefinition definition = r.getDefinitions().stream()
            .filter(d -> "array_tool".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> properties = (Map<String, Object>) definition.parameters().get("properties");
        Map<String, Object> names = (Map<String, Object>) properties.get("names");
        Map<String, Object> namesItems = (Map<String, Object>) names.get("items");
        Map<String, Object> values = (Map<String, Object>) properties.get("values");
        Map<String, Object> valuesItems = (Map<String, Object>) values.get("items");

        assertThat(namesItems).containsEntry("type", "string");
        assertThat(valuesItems).containsKey("anyOf");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedWebSchemasIncludeHermesConstraints() {
        SpringToolRegistry r = registryWithBeans(new WebSearchHandler(), new WebExtractHandler());

        ToolDefinition webSearch = r.getDefinitions().stream()
            .filter(d -> "web_search".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> searchProps = (Map<String, Object>) webSearch.parameters().get("properties");
        Map<String, Object> searchLimit = (Map<String, Object>) searchProps.get("limit");

        assertThat(searchLimit)
            .containsEntry("minimum", 1)
            .containsEntry("maximum", 100)
            .containsEntry("default", 5);

        ToolDefinition webExtract = r.getDefinitions().stream()
            .filter(d -> "web_extract".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> extractProps = (Map<String, Object>) webExtract.parameters().get("properties");
        Map<String, Object> urls = (Map<String, Object>) extractProps.get("urls");
        Map<String, Object> urlItems = (Map<String, Object>) urls.get("items");
        Map<String, Object> charLimit = (Map<String, Object>) extractProps.get("char_limit");

        assertThat(urls).containsEntry("maxItems", 5);
        assertThat(urlItems).containsEntry("type", "string");
        assertThat(charLimit).containsEntry("minimum", 2000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedTerminalSchemaAdvertisesHermesNotifySurfaceOnly() {
        SpringToolRegistry r = registryWithBeans(new TerminalHandler());

        ToolDefinition terminal = r.getDefinitions().stream()
            .filter(d -> "terminal".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) terminal.parameters().get("properties");
        Map<String, Object> background = (Map<String, Object>) props.get("background");
        Map<String, Object> timeout = (Map<String, Object>) props.get("timeout");
        Map<String, Object> pty = (Map<String, Object>) props.get("pty");
        Map<String, Object> notify = (Map<String, Object>) props.get("notify");

        assertThat(props).containsOnlyKeys("command", "background", "timeout", "workdir", "pty", "notify");
        assertThat(props).doesNotContainKeys("notify_on_complete", "watch_patterns");
        assertThat(background).containsEntry("default", false);
        assertThat(timeout).containsEntry("minimum", 1);
        assertThat(pty).containsEntry("default", false);
        assertThat(notify).doesNotContainKey("type");
        assertThat(notify.get("anyOf")).isEqualTo(List.of(
            Map.of("type", "boolean"),
            Map.of("type", "array", "items", Map.of("type", "string"))
        ));
        assertThat(terminal.parameters().get("required")).isEqualTo(List.of("command"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedProcessSchemaIncludesHermesActionEnumAndBounds() {
        SpringToolRegistry r = registryWithBeans(new ProcessHandler());

        ToolDefinition process = r.getDefinitions().stream()
            .filter(d -> "process".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) process.parameters().get("properties");
        Map<String, Object> action = (Map<String, Object>) props.get("action");
        Map<String, Object> timeout = (Map<String, Object>) props.get("timeout");
        Map<String, Object> limit = (Map<String, Object>) props.get("limit");

        assertThat(props).containsOnlyKeys("action", "session_id", "data", "timeout", "offset", "limit");
        assertThat(action).containsEntry("enum", List.of("list", "poll", "log", "wait", "kill", "write", "submit", "close"));
        assertThat(timeout).containsEntry("minimum", 1);
        assertThat(limit).containsEntry("minimum", 1);
        assertThat(process.parameters().get("required")).isEqualTo(List.of("action"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedFileSchemasIncludeHermesDefaultsAndEnums() {
        SpringToolRegistry r = registryWithBeans(
            new ReadFileTool(properties),
            new WriteFileTool(properties),
            new PatchTool(properties),
            new SearchFilesTool(properties)
        );

        Map<String, ToolDefinition> defs = new HashMap<>();
        for (ToolDefinition definition : r.getDefinitions()) {
            defs.put(definition.name(), definition);
        }

        Map<String, Object> readProps = (Map<String, Object>) defs.get("read_file").parameters().get("properties");
        assertThat((Map<String, Object>) readProps.get("offset"))
            .containsEntry("default", 1)
            .containsEntry("minimum", 1);
        assertThat((Map<String, Object>) readProps.get("limit"))
            .containsEntry("default", 2000)
            .containsEntry("maximum", 2000);
        assertThat(defs.get("read_file").parameters().get("required")).isEqualTo(List.of("path"));

        Map<String, Object> writeProps = (Map<String, Object>) defs.get("write_file").parameters().get("properties");
        assertThat((Map<String, Object>) writeProps.get("cross_profile")).containsEntry("default", false);
        assertThat(defs.get("write_file").parameters().get("required")).isEqualTo(List.of("path", "content"));

        Map<String, Object> patchProps = (Map<String, Object>) defs.get("patch").parameters().get("properties");
        assertThat((Map<String, Object>) patchProps.get("mode"))
            .containsEntry("enum", List.of("replace", "patch"))
            .containsEntry("default", "replace");
        assertThat((Map<String, Object>) patchProps.get("replace_all")).containsEntry("default", false);
        assertThat((Map<String, Object>) patchProps.get("cross_profile")).containsEntry("default", false);
        assertThat(defs.get("patch").parameters().get("required")).isEqualTo(List.of("mode"));

        Map<String, Object> searchProps = (Map<String, Object>) defs.get("search_files").parameters().get("properties");
        assertThat((Map<String, Object>) searchProps.get("target"))
            .containsEntry("enum", List.of("content", "files"))
            .containsEntry("default", "content");
        assertThat((Map<String, Object>) searchProps.get("path")).containsEntry("default", ".");
        assertThat((Map<String, Object>) searchProps.get("limit")).containsEntry("default", 50);
        assertThat((Map<String, Object>) searchProps.get("offset")).containsEntry("default", 0);
        assertThat((Map<String, Object>) searchProps.get("output_mode"))
            .containsEntry("enum", List.of("content", "files_only", "count"))
            .containsEntry("default", "content");
        assertThat((Map<String, Object>) searchProps.get("context")).containsEntry("default", 0);
        assertThat(defs.get("search_files").parameters().get("required")).isEqualTo(List.of("pattern"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedVisionSchemaIncludesHermesRegionBounds() {
        SpringToolRegistry r = registryWithBeans(new VisionAnalyzeHandler());

        ToolDefinition vision = r.getDefinitions().stream()
            .filter(d -> "vision_analyze".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) vision.parameters().get("properties");
        Map<String, Object> region = (Map<String, Object>) props.get("region");

        assertThat(region)
            .containsEntry("minItems", 4)
            .containsEntry("maxItems", 4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedImageGenerateSchemaIncludesHermesAspectRatioDefaults() {
        SpringToolRegistry r = registryWithBeans(new ImageGenerateHandler());

        ToolDefinition imageGenerate = r.getDefinitions().stream()
            .filter(d -> "image_generate".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) imageGenerate.parameters().get("properties");
        Map<String, Object> aspectRatio = (Map<String, Object>) props.get("aspect_ratio");
        Map<String, Object> references = (Map<String, Object>) props.get("reference_image_urls");
        Map<String, Object> referenceItems = (Map<String, Object>) references.get("items");

        assertThat(aspectRatio.get("enum"))
            .asList()
            .containsExactly("landscape", "square", "portrait");
        assertThat(aspectRatio).containsEntry("default", "landscape");
        assertThat(referenceItems).containsEntry("type", "string");
        assertThat(imageGenerate.parameters().get("required")).isEqualTo(List.of("prompt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedTextToSpeechSchemaKeepsHermesModelFacingSurface() {
        SpringToolRegistry r = registryWithBeans(new TextToSpeechHandler());

        ToolDefinition tts = r.getDefinitions().stream()
            .filter(d -> "text_to_speech".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) tts.parameters().get("properties");

        assertThat(props).containsOnlyKeys("text", "output_path", "speed", "instructions", "provider");
        assertThat(tts.parameters().get("required")).isEqualTo(List.of("text"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedSkillManageSchemaKeepsHermesModelFacingSurface() {
        SpringToolRegistry r = registryWithBeans(new SkillManageHandler());

        ToolDefinition skillManage = r.getDefinitions().stream()
            .filter(d -> "skill_manage".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) skillManage.parameters().get("properties");
        Map<String, Object> action = (Map<String, Object>) props.get("action");

        assertThat(props).containsOnlyKeys(
            "action", "name", "content", "old_string", "new_string",
            "replace_all", "category", "file_path", "file_content");
        assertThat(props).doesNotContainKeys("update", "old_text", "new_text", "absorbed_into");
        assertThat(action).containsEntry("enum", List.of("create", "patch", "delete", "write_file", "remove_file"));
        assertThat(skillManage.parameters().get("required")).isEqualTo(List.of("action", "name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedExecuteCodeSchemaKeepsHermesModelFacingSurface() {
        SpringToolRegistry r = registryWithBeans(new ExecuteCodeHandler());

        ToolDefinition executeCode = r.getDefinitions().stream()
            .filter(d -> "execute_code".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) executeCode.parameters().get("properties");

        assertThat(props).containsOnlyKeys("code", "reset");
        assertThat(executeCode.parameters().get("required")).isEqualTo(List.of("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedDelegateTaskSchemaKeepsHermesBatchAndControlSurface() {
        SpringToolRegistry r = registryWithBeans(new DelegateTaskHandler());

        ToolDefinition delegateTask = r.getDefinitions().stream()
            .filter(d -> "delegate_task".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) delegateTask.parameters().get("properties");
        Map<String, Object> tasks = (Map<String, Object>) props.get("tasks");
        Map<String, Object> taskItem = (Map<String, Object>) tasks.get("items");
        Map<String, Object> taskProps = (Map<String, Object>) taskItem.get("properties");
        Map<String, Object> action = (Map<String, Object>) props.get("action");

        assertThat(props).containsOnlyKeys("tasks", "action", "subagent_id", "message");
        assertThat(props).doesNotContainKeys("goal", "context", "toolsets", "role",
            "timeout_seconds", "max_iterations", "acp_command", "acp_args", "output_schema");
        assertThat(tasks).containsEntry("minItems", 1);
        assertThat(taskProps).containsOnlyKeys("goal", "context", "output_schema");
        assertThat(taskItem.get("required")).isEqualTo(List.of("goal"));
        assertThat(action).containsEntry("enum", List.of("spawn", "list", "steer", "stop"));
        assertThat(delegateTask.parameters().get("required")).isEqualTo(List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedClarifySchemaKeepsHermesBatchOnlySurface() {
        SpringToolRegistry r = registryWithBeans(new ClarifyTool());

        ToolDefinition clarify = r.getDefinitions().stream()
            .filter(d -> "clarify".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) clarify.parameters().get("properties");
        Map<String, Object> questions = (Map<String, Object>) props.get("questions");
        Map<String, Object> questionItem = (Map<String, Object>) questions.get("items");
        Map<String, Object> itemProps = (Map<String, Object>) questionItem.get("properties");
        Map<String, Object> choices = (Map<String, Object>) itemProps.get("choices");

        assertThat(props).containsOnlyKeys("questions");
        assertThat(questions)
            .containsEntry("minItems", 1)
            .containsEntry("maxItems", 5);
        assertThat(questionItem.get("required")).isEqualTo(List.of("question"));
        assertThat(choices).containsEntry("maxItems", 4);
        assertThat(clarify.parameters().get("required")).isEqualTo(List.of("questions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedSessionSearchSchemaIncludesHermesDefaultsAndSnakeCaseFields() {
        SpringToolRegistry r = registryWithBeans(
            new SessionSearchTool(mock(SessionSearchService.class), objectMapper)
        );

        ToolDefinition sessionSearch = r.getDefinitions().stream()
            .filter(d -> "session_search".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) sessionSearch.parameters().get("properties");

        assertThat(props).containsKeys("session_id", "around_message_id", "role_filter");
        assertThat((Map<String, Object>) props.get("limit")).containsEntry("default", 3);
        assertThat((Map<String, Object>) props.get("window")).containsEntry("default", 5);
        assertThat((Map<String, Object>) props.get("sort")).containsEntry("enum", List.of("newest", "oldest"));
        assertThat((Map<String, Object>) props.get("detail"))
            .containsEntry("enum", List.of("adaptive", "full"))
            .containsEntry("default", "adaptive");
        assertThat(sessionSearch.parameters().get("required")).isEqualTo(List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedMemorySchemaRemovesLegacyLimitAndKeepsHermesBatchShape() {
        SpringToolRegistry r = registryWithBeans(new MemoryHandler());

        ToolDefinition memory = r.getDefinitions().stream()
            .filter(d -> "memory".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) memory.parameters().get("properties");
        Map<String, Object> operations = (Map<String, Object>) props.get("operations");
        Map<String, Object> operationItem = (Map<String, Object>) operations.get("items");
        Map<String, Object> operationProps = (Map<String, Object>) operationItem.get("properties");

        assertThat(props).containsKeys("action", "target", "content", "old_text", "new_text", "operations");
        assertThat(props).doesNotContainKey("limit");
        assertThat(operationProps).containsKeys("action", "content", "old_text", "new_text");
        assertThat(operationItem.get("required")).isEqualTo(List.of("action"));
        assertThat(memory.parameters().get("required")).isEqualTo(List.of("target"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedMemorySchemaNarrowsToUserWhenOnlyUserProfileEnabled() {
        properties.getMemory().setMemoryEnabled(false);
        properties.getMemory().setUserProfileEnabled(true);
        SpringToolRegistry r = registryWithBeans(new MemoryHandler());

        ToolDefinition memory = r.getDefinitions().stream()
            .filter(d -> "memory".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) memory.parameters().get("properties");
        Map<String, Object> target = (Map<String, Object>) props.get("target");

        assertThat((List<String>) target.get("enum")).containsExactly("user");
        assertThat(target.get("description")).isEqualTo("The enabled built-in store: 'user' for user profile.");
        assertThat(memory.description())
            .contains("only 'user' is enabled")
            .doesNotContain("only 'memory' is enabled");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedMemorySchemaNarrowsToMemoryWhenOnlyMemoryEnabled() {
        properties.getMemory().setMemoryEnabled(true);
        properties.getMemory().setUserProfileEnabled(false);
        SpringToolRegistry r = registryWithBeans(new MemoryHandler());

        ToolDefinition memory = r.getDefinitions().stream()
            .filter(d -> "memory".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) memory.parameters().get("properties");
        Map<String, Object> target = (Map<String, Object>) props.get("target");

        assertThat((List<String>) target.get("enum")).containsExactly("memory");
        assertThat(target.get("description")).isEqualTo("The enabled built-in store: 'memory' for personal notes.");
        assertThat(memory.description())
            .contains("only 'memory' is enabled")
            .doesNotContain("only 'user' is enabled");
    }

    @Test
    void getDefinitionsHidesMemoryWhenBothBuiltInStoresDisabled() {
        properties.getMemory().setMemoryEnabled(false);
        properties.getMemory().setUserProfileEnabled(false);
        SpringToolRegistry r = registryWithBeans(new MemoryHandler(), new SessionSearchHandler());

        assertThat(r.getDefinitions()).extracting(ToolDefinition::name)
            .doesNotContain("memory")
            .contains("session_search");
        assertThat(r.getDefinitions(Set.of("memory"))).isEmpty();
        assertThat(r.getDefinitions(Set.of("hermes-cli"))).extracting(ToolDefinition::name)
            .doesNotContain("memory")
            .contains("session_search");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedTodoSchemaIncludesHermesMergeDefault() {
        SpringToolRegistry r = registryWithBeans(new TodoHandler());

        ToolDefinition todo = r.getDefinitions().stream()
            .filter(d -> "todo".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) todo.parameters().get("properties");
        Map<String, Object> merge = (Map<String, Object>) props.get("merge");
        Map<String, Object> todos = (Map<String, Object>) props.get("todos");
        Map<String, Object> todoItem = (Map<String, Object>) todos.get("items");

        assertThat(merge).containsEntry("default", false);
        assertThat(todoItem.get("required")).isEqualTo(List.of("id", "content", "status"));
        assertThat(todo.parameters().get("required")).isEqualTo(List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedCronJobSchemaKeepsHermesModelFacingSurface() {
        SpringToolRegistry r = registryWithBeans(new CronJobHandler());

        ToolDefinition cronjob = r.getDefinitions().stream()
            .filter(d -> "cronjob".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) cronjob.parameters().get("properties");
        Map<String, Object> noAgent = (Map<String, Object>) props.get("no_agent");
        Map<String, Object> contextFrom = (Map<String, Object>) props.get("context_from");
        Map<String, Object> enabledToolsets = (Map<String, Object>) props.get("enabled_toolsets");

        assertThat(props).containsOnlyKeys(
            "action", "job_id", "prompt", "schedule", "name", "repeat", "deliver",
            "skills", "script", "monitor", "no_agent", "context_from", "continuity",
            "enabled_toolsets", "workdir", "attach_to_session");
        assertThat(props).doesNotContainKeys("skill", "model_provider", "model_name", "base_url");
        assertThat(noAgent).containsEntry("default", false);
        assertThat(contextFrom).containsEntry("type", "array");
        assertThat((Map<String, Object>) contextFrom.get("items")).containsEntry("type", "string");
        assertThat(enabledToolsets).containsEntry("type", "array");
        assertThat((Map<String, Object>) enabledToolsets.get("items")).containsEntry("type", "string");
        assertThat(cronjob.parameters().get("required")).isEqualTo(List.of("action"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedSendMessageSchemaKeepsHermesTargetMessageSurface() {
        SpringToolRegistry r = registryWithBeans(new SendMessageHandler());

        ToolDefinition send = r.getDefinitions().stream()
            .filter(d -> "send_message".equals(d.name()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> props = (Map<String, Object>) send.parameters().get("properties");
        Map<String, Object> action = (Map<String, Object>) props.get("action");

        assertThat(props).containsOnlyKeys("action", "target", "message");
        assertThat(props).doesNotContainKeys("platform", "chat_id", "chatId", "text", "emoji", "message_id");
        assertThat(action).containsEntry("enum", List.of("send", "list"));
        assertThat(send.parameters().get("required")).isEqualTo(List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedBrowserSchemasKeepHermesModelFacingSurface() {
        BrowserService browserService = mock(BrowserService.class);
        SpringToolRegistry r = registryWithBeans(
            new BrowserNavigateTool(browserService),
            new BrowserSnapshotTool(browserService),
            new BrowserClickTool(browserService),
            new BrowserTypeTool(browserService),
            new BrowserScrollTool(browserService),
            new BrowserBackTool(browserService),
            new BrowserPressTool(browserService),
            new BrowserGetImagesTool(browserService),
            new BrowserVisionTool(browserService),
            new BrowserConsoleTool(browserService),
            new BrowserCdpTool(browserService),
            new BrowserDialogTool(browserService)
        );

        Map<String, ToolDefinition> defs = new HashMap<>();
        for (ToolDefinition definition : r.getDefinitions()) {
            defs.put(definition.name(), definition);
        }

        Map<String, Object> navigateProps = (Map<String, Object>) defs.get("browser_navigate").parameters().get("properties");
        assertThat(navigateProps).containsOnlyKeys("url");
        assertThat(defs.get("browser_navigate").parameters().get("required")).isEqualTo(List.of("url"));

        Map<String, Object> typeProps = (Map<String, Object>) defs.get("browser_type").parameters().get("properties");
        assertThat(typeProps).containsOnlyKeys("ref", "text");
        assertThat(defs.get("browser_type").parameters().get("required")).isEqualTo(List.of("ref", "text"));

        Map<String, Object> scrollProps = (Map<String, Object>) defs.get("browser_scroll").parameters().get("properties");
        Map<String, Object> direction = (Map<String, Object>) scrollProps.get("direction");
        assertThat(scrollProps).containsOnlyKeys("direction");
        assertThat(direction).containsEntry("enum", List.of("up", "down"));
        assertThat(defs.get("browser_scroll").parameters().get("required")).isEqualTo(List.of("direction"));

        assertThat((Map<String, Object>) defs.get("browser_back").parameters().get("properties")).isEmpty();
        assertThat(defs.get("browser_back").parameters().get("required")).isEqualTo(List.of());

        Map<String, Object> pressProps = (Map<String, Object>) defs.get("browser_press").parameters().get("properties");
        assertThat(pressProps).containsOnlyKeys("key");
        assertThat(defs.get("browser_press").parameters().get("required")).isEqualTo(List.of("key"));

        assertThat((Map<String, Object>) defs.get("browser_get_images").parameters().get("properties")).isEmpty();
        assertThat(defs.get("browser_get_images").parameters().get("required")).isEqualTo(List.of());

        Map<String, Object> snapshotProps = (Map<String, Object>) defs.get("browser_snapshot").parameters().get("properties");
        assertThat(snapshotProps).containsOnlyKeys("full");
        assertThat((Map<String, Object>) snapshotProps.get("full")).containsEntry("default", false);

        Map<String, Object> visionProps = (Map<String, Object>) defs.get("browser_vision").parameters().get("properties");
        assertThat(visionProps).containsOnlyKeys("question", "annotate");
        assertThat((Map<String, Object>) visionProps.get("annotate")).containsEntry("default", false);
        assertThat(defs.get("browser_vision").parameters().get("required")).isEqualTo(List.of("question"));

        Map<String, Object> consoleProps = (Map<String, Object>) defs.get("browser_console").parameters().get("properties");
        assertThat(consoleProps).containsOnlyKeys("clear", "expression");
        assertThat((Map<String, Object>) consoleProps.get("clear")).containsEntry("default", false);
        assertThat(defs.get("browser_console").parameters().get("required")).isEqualTo(List.of());

        Map<String, Object> cdpProps = (Map<String, Object>) defs.get("browser_cdp").parameters().get("properties");
        assertThat(cdpProps).containsOnlyKeys("method", "params", "target_id", "frame_id", "timeout");
        assertThat(cdpProps).doesNotContainKeys("targetId", "frameId", "expression");
        assertThat((Map<String, Object>) cdpProps.get("params"))
            .containsEntry("properties", Map.of())
            .containsEntry("additionalProperties", true);
        assertThat((Map<String, Object>) cdpProps.get("timeout"))
            .containsEntry("type", "number")
            .containsEntry("default", 30);
        assertThat(defs.get("browser_cdp").parameters().get("required")).isEqualTo(List.of("method"));

        Map<String, Object> dialogProps = (Map<String, Object>) defs.get("browser_dialog").parameters().get("properties");
        assertThat(dialogProps).containsOnlyKeys("action", "prompt_text", "dialog_id");
        assertThat(dialogProps).doesNotContainKeys("promptText", "dialogId", "text");
        assertThat((Map<String, Object>) dialogProps.get("action")).containsEntry("enum", List.of("accept", "dismiss"));
        assertThat(defs.get("browser_dialog").parameters().get("required")).isEqualTo(List.of("action"));
    }

    @Test
    void getDefinitions_adaptsDelegateTaskDescriptionToFilteredTools() {
        SpringToolRegistry r = registryWithBeans(
            new DelegateTaskHandler(), new CronJobHandler(), new SendMessageHandler()
        );

        ToolDefinition definition = r.getDefinitions(Set.of("hermes-cli")).stream()
            .filter(d -> d.name().equals("delegate_task"))
            .findFirst()
            .orElseThrow();

        assertThat(definition.description())
            .contains("cannot call delegate_task or cronjob")
            .doesNotContain("send_message");
    }

    @Test
    void getToolsetsIncludesHermesAliasesThatResolveToRegisteredTools() {
        SpringToolRegistry r = registryWithBeans(
            new WebSearchHandler(), new VisionAnalyzeHandler(), new ImageGenerateHandler()
        );

        assertThat(r.getToolsets())
            .contains("web", "vision", "image_gen", "safe", "coding", "hermes-cli")
            .doesNotContain("video", "homeassistant", "kanban");
    }

    // ── execute() ──

    @Test
    void execute_knownTool_delegatesToHandler() {
        ToolResult result = registry.execute("core_tool", "call-1", "{}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("core-result");
    }

    @Test
    void execute_unknownTool_returnsStructuredFailResult() throws Exception {
        ToolResult result = registry.execute("nonexistent_tool", "call-1", "{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown tool");
        assertThat(result.error()).contains("nonexistent_tool");

        JsonNode payload = objectMapper.readTree(result.content());
        assertThat(payload.path("success").asBoolean()).isFalse();
        assertThat(payload.path("error").asText()).isEqualTo(result.error());
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
    void registerDynamic_withToolsetIsScopedAndResolvableByMcpAlias() {
        ToolDefinition dynDef = new ToolDefinition("mcp__my_server__ping", "MCP ping", Map.of("type", "object"));
        ToolHandler dynHandler = (args, lastAssistant, session) -> ToolResult.ok("pong");

        registry.registerDynamic("mcp__my_server__ping", "mcp-my-server", dynDef, dynHandler);

        assertThat(registry.getDefinitions(Set.of("safe")))
            .extracting(ToolDefinition::name)
            .doesNotContain("mcp__my_server__ping");
        assertThat(registry.getDefinitions(Set.of("mcp-my-server")))
            .extracting(ToolDefinition::name)
            .containsExactly("mcp__my_server__ping");
        assertThat(registry.getDefinitions(Set.of("my-server")))
            .extracting(ToolDefinition::name)
            .containsExactly("mcp__my_server__ping");
        assertThat(registry.getToolsets()).contains("mcp-my-server");
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

    @Test
    @SuppressWarnings("unchecked")
    void getDefinitions_infersParamTypesAndNestedRecordItems() {
        ToolDefinition coreDef = registry.getDefinitions().stream()
            .filter(d -> d.name().equals("core_tool"))
            .findFirst().orElseThrow();

        Map<String, Object> props = (Map<String, Object>) coreDef.parameters().get("properties");
        assertThat((Map<String, Object>) props.get("count")).containsEntry("type", "integer");

        Map<String, Object> itemsParam = (Map<String, Object>) props.get("items");
        assertThat(itemsParam).containsEntry("type", "array");
        Map<String, Object> itemSchema = (Map<String, Object>) itemsParam.get("items");
        assertThat(itemSchema).containsEntry("type", "object");
        Map<String, Object> itemProps = (Map<String, Object>) itemSchema.get("properties");
        assertThat(itemProps).containsKeys("id", "status");
        assertThat((Map<String, Object>) itemProps.get("status"))
            .containsEntry("enum", List.of("pending", "done"));
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
