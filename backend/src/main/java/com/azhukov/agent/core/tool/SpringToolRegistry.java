package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SpringToolRegistry implements ToolRegistry {

    private final ApplicationContext context;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ManagedToolGate managedToolGateway;
    private final Map<String, ToolEntry> entries = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String MEMORY_TARGETS_DESCRIPTION =
        "TARGETS: 'user' = who the user is (name, role, preferences, style). 'memory' = your "
            + "notes (environment, conventions, tool quirks, lessons).";
    private static final String MEMORY_ONLY_DESCRIPTION =
        "TARGET: only 'memory' is enabled for personal notes (environment, conventions, tool quirks, lessons).";
    private static final String USER_ONLY_DESCRIPTION =
        "TARGET: only 'user' is enabled for user profile facts (name, role, preferences, style).";

    private static final List<String> WEB_TOOLS = List.of("web_search", "web_extract");
    private static final List<String> BROWSER_TOOLS = List.of(
        "browser_navigate", "browser_snapshot", "browser_click",
        "browser_type", "browser_scroll", "browser_back",
        "browser_press", "browser_get_images",
        "browser_vision", "browser_console", "browser_cdp", "browser_dialog",
        "browser_exec", "web_search"
    );
    private static final List<String> BROWSER_CDP_TOOLS = List.of("browser_cdp", "browser_dialog");
    private static final List<String> FILE_TOOLS = List.of("read_file", "write_file", "patch", "search_files");
    private static final List<String> SKILL_TOOLS = List.of("skills_list", "skill_view", "skill_manage");
    private static final List<String> HERMES_CORE_TOOLS = List.of(
        "web_search", "web_extract",
        "terminal", "process",
        "read_file", "write_file", "patch", "search_files",
        "vision_analyze", "image_generate",
        "skills_list", "skill_view", "skill_manage",
        "browser_navigate", "browser_snapshot", "browser_click",
        "browser_type", "browser_scroll", "browser_back",
        "browser_press", "browser_get_images",
        "browser_vision", "browser_console", "browser_cdp", "browser_dialog",
        "browser_exec",
        "text_to_speech",
        "todo", "memory",
        "session_search",
        "clarify",
        "execute_code", "delegate_task",
        "cronjob",
        "ha_list_entities", "ha_get_state", "ha_list_services", "ha_call_service",
        "kanban_show", "kanban_list", "kanban_complete", "kanban_block",
        "kanban_request_review", "kanban_request_changes", "kanban_heartbeat",
        "kanban_comment", "kanban_create", "kanban_link", "kanban_unblock",
        "kanban_attach", "kanban_attach_url", "kanban_attachments",
        "computer_use"
    );
    private static final List<String> CODING_TOOLS = List.of(
        "web_search", "web_extract",
        "terminal", "process",
        "read_file", "write_file", "patch", "search_files",
        "vision_analyze",
        "skills_list", "skill_view", "skill_manage",
        "browser_navigate", "browser_snapshot", "browser_click",
        "browser_type", "browser_scroll", "browser_back",
        "browser_press", "browser_get_images",
        "browser_vision", "browser_console", "browser_cdp", "browser_dialog",
        "browser_exec",
        "todo", "memory",
        "session_search", "clarify",
        "execute_code", "delegate_task"
    );
    private static final List<String> HERMES_ACP_TOOLS = List.of(
        "web_search", "web_extract",
        "terminal", "process",
        "read_file", "write_file", "patch", "search_files",
        "vision_analyze",
        "skills_list", "skill_view", "skill_manage",
        "browser_navigate", "browser_snapshot", "browser_click",
        "browser_type", "browser_scroll", "browser_back",
        "browser_press", "browser_get_images",
        "browser_vision", "browser_console", "browser_cdp", "browser_dialog",
        "browser_exec",
        "todo", "memory",
        "session_search",
        "execute_code", "delegate_task"
    );
    private static final List<String> HERMES_API_SERVER_TOOLS = List.of(
        "web_search", "web_extract",
        "terminal", "process",
        "read_file", "write_file", "patch", "search_files",
        "vision_analyze", "image_generate",
        "skills_list", "skill_view", "skill_manage",
        "browser_navigate", "browser_snapshot", "browser_click",
        "browser_type", "browser_scroll", "browser_back",
        "browser_press", "browser_get_images",
        "browser_vision", "browser_console", "browser_cdp", "browser_dialog",
        "browser_exec",
        "todo", "memory",
        "session_search",
        "execute_code", "delegate_task",
        "cronjob",
        "ha_list_entities", "ha_get_state", "ha_list_services", "ha_call_service"
    );
    private static final List<String> HERMES_WEBHOOK_SAFE_TOOLS =
        List.of("web_search", "web_extract", "vision_analyze", "clarify");
    private static final List<String> HERMES_PLATFORM_TOOLSETS = List.of(
        "hermes-telegram", "hermes-discord", "hermes-whatsapp", "hermes-slack",
        "hermes-signal", "hermes-bluebubbles", "hermes-homeassistant",
        "hermes-email", "hermes-sms", "hermes-mattermost", "hermes-matrix",
        "hermes-dingtalk", "hermes-feishu", "hermes-wecom",
        "hermes-wecom-callback", "hermes-weixin", "hermes-qqbot",
        "hermes-webhook", "hermes-yuanbao"
    );
    private static final Map<String, ToolsetSpec> HERMES_TOOLSETS = Map.ofEntries(
        Map.entry("web", spec(WEB_TOOLS)),
        Map.entry("search", spec(List.of("web_search"))),
        Map.entry("vision", spec(List.of("vision_analyze"))),
        Map.entry("image_gen", spec(List.of("image_generate"))),
        Map.entry("terminal", spec(List.of("terminal", "process"))),
        Map.entry("skills", spec(SKILL_TOOLS)),
        Map.entry("browser", spec(BROWSER_TOOLS)),
        Map.entry("browser-cdp", spec(BROWSER_CDP_TOOLS)),
        Map.entry("cronjob", spec(List.of("cronjob"))),
        Map.entry("file", spec(FILE_TOOLS)),
        Map.entry("tts", spec(List.of("text_to_speech"))),
        Map.entry("todo", spec(List.of("todo"))),
        Map.entry("memory", spec(List.of("memory"))),
        Map.entry("session_search", spec(List.of("session_search"))),
        Map.entry("clarify", spec(List.of("clarify"))),
        Map.entry("code_execution", spec(List.of("execute_code"))),
        Map.entry("delegation", spec(List.of("delegate_task"))),
        Map.entry("homeassistant", spec(List.of("ha_list_entities", "ha_get_state", "ha_list_services", "ha_call_service"))),
        Map.entry("kanban", spec(List.of(
            "kanban_show", "kanban_list", "kanban_complete", "kanban_block",
            "kanban_request_review", "kanban_request_changes", "kanban_heartbeat",
            "kanban_comment", "kanban_create", "kanban_link", "kanban_unblock",
            "kanban_attach", "kanban_attach_url", "kanban_attachments"
        ))),
        Map.entry("computer_use", spec(List.of("computer_use"))),
        Map.entry("debugging", spec(List.of("terminal", "process"), "web", "file")),
        Map.entry("safe", spec(List.of(), "web", "vision", "image_gen")),
        Map.entry("coding", spec(CODING_TOOLS)),
        Map.entry("hermes-acp", spec(HERMES_ACP_TOOLS)),
        Map.entry("hermes-api-server", spec(HERMES_API_SERVER_TOOLS)),
        Map.entry("hermes-cli", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-cron", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-telegram", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-discord", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-whatsapp", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-slack", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-signal", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-bluebubbles", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-homeassistant", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-email", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-sms", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-mattermost", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-matrix", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-dingtalk", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-feishu", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-wecom", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-wecom-callback", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-weixin", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-qqbot", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-yuanbao", spec(HERMES_CORE_TOOLS)),
        Map.entry("hermes-webhook", spec(HERMES_WEBHOOK_SAFE_TOOLS)),
        Map.entry("hermes-gateway", spec(List.of(), HERMES_PLATFORM_TOOLSETS.toArray(String[]::new)))
    );

    private static ToolsetSpec spec(List<String> tools, String... includes) {
        return new ToolsetSpec(tools, List.of(includes));
    }

    @PostConstruct
    void registerBeans() {
        Map<String, Object> beans = context.getBeansWithAnnotation(AgentTool.class);
        for (Object bean : beans.values()) {
            AgentTool annotation = bean.getClass().getAnnotation(AgentTool.class);
            if (annotation == null || !(bean instanceof ToolHandler handler)) {
                continue;
            }
            if (managedToolGateway != null && !managedToolGateway.isEnabled(annotation.name())) {
                continue;
            }
            if ("memory".equals(annotation.name()) && !isMemoryToolAvailable()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends ToolHandler> handlerClass = (Class<? extends ToolHandler>) bean.getClass();
            ToolDefinition definition = buildDefinition(annotation.name(), annotation.description(), handlerClass);
            entries.put(annotation.name(), new ToolEntry(annotation, handler, definition));
        }
    }

    private ToolDefinition buildDefinition(String name, String description, Class<? extends ToolHandler> handlerClass) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        Class<?> argsClass = findArgsClass(handlerClass);

        if (argsClass != null) {
            if (argsClass.isRecord()) {
                for (java.lang.reflect.RecordComponent rc : argsClass.getRecordComponents()) {
                    String propName = schemaPropertyName(argsClass, rc);
                    addProperty(properties, required, propName, rc.getType(), rc.getGenericType(), rc.getAnnotation(ToolParam.class));
                }
            } else {
                for (java.lang.reflect.Field field : argsClass.getDeclaredFields()) {
                    String propName = field.getName();
                    com.fasterxml.jackson.annotation.JsonProperty jp = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                    if (jp != null && !jp.value().isEmpty()) {
                        propName = jp.value();
                    }
                    addProperty(properties, required, propName, field.getType(), field.getGenericType(), field.getAnnotation(ToolParam.class));
                }
            }
        }

        applyHermesSchemaOverrides(name, properties, required);
        String effectiveDescription = applyHermesDescriptionOverrides(name, description);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        return new ToolDefinition(name, effectiveDescription, parameters);
    }

    private void addProperty(Map<String, Object> properties, List<String> required,
                             String name, Class<?> type, java.lang.reflect.Type genericType, ToolParam param) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", param != null && !param.type().isBlank() ? param.type() : mapType(type));
        field.put("description", param != null ? param.description() : "");
        if (param != null && param.enumValues().length > 0) {
            field.put("enum", java.util.Arrays.asList(param.enumValues()));
        }
        addArrayItems(field, type, genericType);
        properties.put(name, field);
        if (param != null && param.required()) {
            required.add(name);
        }
    }

    private void applyHermesSchemaOverrides(String name, Map<String, Object> properties, List<String> required) {
        if ("web_search".equals(name)) {
            Map<String, Object> limit = copySchemaProperty(properties.get("limit"));
            if (!limit.isEmpty()) {
                limit.put("minimum", 1);
                limit.put("maximum", 100);
                limit.put("default", 5);
                properties.put("limit", limit);
            }
            return;
        }

        if ("web_extract".equals(name)) {
            Map<String, Object> urls = copySchemaProperty(properties.get("urls"));
            if (!urls.isEmpty()) {
                urls.put("items", Map.of("type", "string"));
                urls.put("maxItems", 5);
                properties.put("urls", urls);
            }

            Map<String, Object> charLimit = copySchemaProperty(properties.get("char_limit"));
            if (!charLimit.isEmpty()) {
                charLimit.put("minimum", 2000);
                properties.put("char_limit", charLimit);
            }
            return;
        }

        if ("terminal".equals(name)) {
            retainProperties(properties, "command", "background", "timeout", "workdir", "pty", "notify");

            Map<String, Object> background = copySchemaProperty(properties.get("background"));
            if (!background.isEmpty()) {
                background.put("default", false);
                properties.put("background", background);
            }

            Map<String, Object> timeout = copySchemaProperty(properties.get("timeout"));
            if (!timeout.isEmpty()) {
                timeout.put("minimum", 1);
                properties.put("timeout", timeout);
            }

            Map<String, Object> pty = copySchemaProperty(properties.get("pty"));
            if (!pty.isEmpty()) {
                pty.put("default", false);
                properties.put("pty", pty);
            }

            Map<String, Object> notify = copySchemaProperty(properties.get("notify"));
            if (!notify.isEmpty()) {
                notify.remove("type");
                notify.put("anyOf", List.of(
                    Map.of("type", "boolean"),
                    Map.of("type", "array", "items", Map.of("type", "string"))
                ));
                properties.put("notify", notify);
            }

            setRequired(required, "command");
            return;
        }

        if ("process".equals(name)) {
            Map<String, Object> action = copySchemaProperty(properties.get("action"));
            if (!action.isEmpty()) {
                action.put("enum", List.of("list", "poll", "log", "wait", "kill", "write", "submit", "close"));
                properties.put("action", action);
            }

            Map<String, Object> timeout = copySchemaProperty(properties.get("timeout"));
            if (!timeout.isEmpty()) {
                timeout.put("minimum", 1);
                properties.put("timeout", timeout);
            }

            Map<String, Object> limit = copySchemaProperty(properties.get("limit"));
            if (!limit.isEmpty()) {
                limit.put("minimum", 1);
                properties.put("limit", limit);
            }

            setRequired(required, "action");
            return;
        }

        if ("read_file".equals(name)) {
            Map<String, Object> offset = copySchemaProperty(properties.get("offset"));
            if (!offset.isEmpty()) {
                offset.put("default", 1);
                offset.put("minimum", 1);
                properties.put("offset", offset);
            }

            Map<String, Object> limit = copySchemaProperty(properties.get("limit"));
            if (!limit.isEmpty()) {
                limit.put("default", 2000);
                limit.put("maximum", 2000);
                properties.put("limit", limit);
            }

            setRequired(required, "path");
            return;
        }

        if ("write_file".equals(name)) {
            Map<String, Object> crossProfile = copySchemaProperty(properties.get("cross_profile"));
            if (!crossProfile.isEmpty()) {
                crossProfile.put("default", false);
                properties.put("cross_profile", crossProfile);
            }
            setRequired(required, "path", "content");
            return;
        }

        if ("patch".equals(name)) {
            Map<String, Object> mode = copySchemaProperty(properties.get("mode"));
            if (!mode.isEmpty()) {
                mode.put("enum", List.of("replace", "patch"));
                mode.put("default", "replace");
                properties.put("mode", mode);
            }

            Map<String, Object> replaceAll = copySchemaProperty(properties.get("replace_all"));
            if (!replaceAll.isEmpty()) {
                replaceAll.put("default", false);
                properties.put("replace_all", replaceAll);
            }

            Map<String, Object> crossProfile = copySchemaProperty(properties.get("cross_profile"));
            if (!crossProfile.isEmpty()) {
                crossProfile.put("default", false);
                properties.put("cross_profile", crossProfile);
            }

            setRequired(required, "mode");
            return;
        }

        if ("search_files".equals(name)) {
            Map<String, Object> target = copySchemaProperty(properties.get("target"));
            if (!target.isEmpty()) {
                target.put("enum", List.of("content", "files"));
                target.put("default", "content");
                properties.put("target", target);
            }

            Map<String, Object> path = copySchemaProperty(properties.get("path"));
            if (!path.isEmpty()) {
                path.put("default", ".");
                properties.put("path", path);
            }

            Map<String, Object> limit = copySchemaProperty(properties.get("limit"));
            if (!limit.isEmpty()) {
                limit.put("default", 50);
                properties.put("limit", limit);
            }

            Map<String, Object> offset = copySchemaProperty(properties.get("offset"));
            if (!offset.isEmpty()) {
                offset.put("default", 0);
                properties.put("offset", offset);
            }

            Map<String, Object> outputMode = copySchemaProperty(properties.get("output_mode"));
            if (!outputMode.isEmpty()) {
                outputMode.put("enum", List.of("content", "files_only", "count"));
                outputMode.put("default", "content");
                properties.put("output_mode", outputMode);
            }

            Map<String, Object> context = copySchemaProperty(properties.get("context"));
            if (!context.isEmpty()) {
                context.put("default", 0);
                properties.put("context", context);
            }

            setRequired(required, "pattern");
            return;
        }

        if ("browser_navigate".equals(name)) {
            retainProperties(properties, "url");
            setRequired(required, "url");
            return;
        }

        if ("browser_snapshot".equals(name)) {
            retainProperties(properties, "full");
            Map<String, Object> full = copySchemaProperty(properties.get("full"));
            if (!full.isEmpty()) {
                full.put("default", false);
                properties.put("full", full);
            }
            setRequired(required);
            return;
        }

        if ("browser_click".equals(name)) {
            retainProperties(properties, "ref");
            setRequired(required, "ref");
            return;
        }

        if ("browser_type".equals(name)) {
            retainProperties(properties, "ref", "text");
            setRequired(required, "ref", "text");
            return;
        }

        if ("browser_scroll".equals(name)) {
            Map<String, Object> direction = copySchemaProperty(properties.get("direction"));
            direction.putIfAbsent("type", "string");
            direction.put("enum", List.of("up", "down"));
            direction.putIfAbsent("description", "Direction to scroll");
            properties.clear();
            properties.put("direction", direction);
            setRequired(required, "direction");
            return;
        }

        if ("browser_back".equals(name) || "browser_get_images".equals(name)) {
            properties.clear();
            setRequired(required);
            return;
        }

        if ("browser_press".equals(name)) {
            retainProperties(properties, "key");
            setRequired(required, "key");
            return;
        }

        if ("browser_vision".equals(name)) {
            retainProperties(properties, "question", "annotate");
            Map<String, Object> annotate = copySchemaProperty(properties.get("annotate"));
            if (!annotate.isEmpty()) {
                annotate.put("default", false);
                properties.put("annotate", annotate);
            }
            setRequired(required, "question");
            return;
        }

        if ("browser_console".equals(name)) {
            retainProperties(properties, "clear", "expression");
            Map<String, Object> clear = copySchemaProperty(properties.get("clear"));
            if (!clear.isEmpty()) {
                clear.put("default", false);
                properties.put("clear", clear);
            }
            setRequired(required);
            return;
        }

        if ("browser_cdp".equals(name)) {
            retainProperties(properties, "method", "params", "target_id", "frame_id", "timeout");
            Map<String, Object> params = copySchemaProperty(properties.get("params"));
            if (!params.isEmpty()) {
                params.put("properties", Map.of());
                params.put("additionalProperties", true);
                properties.put("params", params);
            }
            Map<String, Object> timeout = copySchemaProperty(properties.get("timeout"));
            if (!timeout.isEmpty()) {
                timeout.put("type", "number");
                timeout.put("default", 30);
                properties.put("timeout", timeout);
            }
            setRequired(required, "method");
            return;
        }

        if ("browser_dialog".equals(name)) {
            retainProperties(properties, "action", "prompt_text", "dialog_id");
            Map<String, Object> action = copySchemaProperty(properties.get("action"));
            if (!action.isEmpty()) {
                action.put("enum", List.of("accept", "dismiss"));
                properties.put("action", action);
            }
            setRequired(required, "action");
            return;
        }

        if ("vision_analyze".equals(name)) {
            Map<String, Object> region = copySchemaProperty(properties.get("region"));
            if (!region.isEmpty()) {
                region.put("minItems", 4);
                region.put("maxItems", 4);
                properties.put("region", region);
            }
            return;
        }

        if ("image_generate".equals(name)) {
            Map<String, Object> aspectRatio = copySchemaProperty(properties.get("aspect_ratio"));
            if (!aspectRatio.isEmpty()) {
                aspectRatio.put("enum", List.of("landscape", "square", "portrait"));
                aspectRatio.put("default", "landscape");
                properties.put("aspect_ratio", aspectRatio);
            }
            return;
        }

        if ("text_to_speech".equals(name)) {
            retainProperties(properties, "text", "output_path", "speed", "instructions", "provider");
            setRequired(required, "text");
            return;
        }

        if ("skill_manage".equals(name)) {
            applySkillManageSchemaOverride(properties, required);
            return;
        }

        if ("execute_code".equals(name)) {
            retainProperties(properties, "code", "reset");
            setRequired(required, "code");
            return;
        }

        if ("delegate_task".equals(name)) {
            applyDelegateTaskSchemaOverride(properties, required);
            return;
        }

        if ("clarify".equals(name)) {
            Map<String, Object> choices = new LinkedHashMap<>();
            choices.put("type", "array");
            choices.put("items", Map.of("type", "string"));
            choices.put("maxItems", 4);

            Map<String, Object> itemProperties = new LinkedHashMap<>();
            itemProperties.put("question", Map.of("type", "string"));
            itemProperties.put("choices", choices);
            itemProperties.put("multi_select", Map.of("type", "boolean"));

            Map<String, Object> questionItem = new LinkedHashMap<>();
            questionItem.put("type", "object");
            questionItem.put("properties", itemProperties);
            questionItem.put("required", List.of("question"));

            Map<String, Object> questions = copySchemaProperty(properties.get("questions"));
            questions.put("type", "array");
            questions.put("minItems", 1);
            questions.put("maxItems", 5);
            questions.put("items", questionItem);

            properties.clear();
            properties.put("questions", questions);
            setRequired(required, "questions");
            return;
        }

        if ("session_search".equals(name)) {
            Map<String, Object> limit = copySchemaProperty(properties.get("limit"));
            if (!limit.isEmpty()) {
                limit.put("default", 3);
                properties.put("limit", limit);
            }

            Map<String, Object> sort = copySchemaProperty(properties.get("sort"));
            if (!sort.isEmpty()) {
                sort.put("enum", List.of("newest", "oldest"));
                properties.put("sort", sort);
            }

            Map<String, Object> detail = copySchemaProperty(properties.get("detail"));
            if (!detail.isEmpty()) {
                detail.put("enum", List.of("adaptive", "full"));
                detail.put("default", "adaptive");
                properties.put("detail", detail);
            }

            Map<String, Object> window = copySchemaProperty(properties.get("window"));
            if (!window.isEmpty()) {
                window.put("default", 5);
                properties.put("window", window);
            }
            setRequired(required);
            return;
        }

        if ("cronjob".equals(name)) {
            applyCronJobSchemaOverride(properties, required);
            return;
        }

        if ("send_message".equals(name)) {
            applySendMessageSchemaOverride(properties, required);
            return;
        }

        if ("memory".equals(name)) {
            applyMemorySchemaOverride(properties, required);
            return;
        }

        if ("todo".equals(name)) {
            Map<String, Object> merge = copySchemaProperty(properties.get("merge"));
            if (!merge.isEmpty()) {
                merge.put("default", false);
                properties.put("merge", merge);
            }
            setRequired(required);
        }
    }

    private void applySkillManageSchemaOverride(Map<String, Object> properties, List<String> required) {
        Map<String, Object> action = stringSchema("The action to perform.");
        action.put("enum", List.of("create", "patch", "delete", "write_file", "remove_file"));

        properties.clear();
        properties.put("action", action);
        properties.put("name", stringSchema(
            "Skill name (lowercase, hyphens/underscores, max 64 chars). Must match an existing skill for patch/edit/delete/write_file/remove_file."));
        properties.put("content", stringSchema(
            "Full SKILL.md content. Required for create; on patch it performs a full SKILL.md rewrite."));
        properties.put("old_string", stringSchema(
            "Text to find in the file. Required for patch. Must be unique unless replace_all=true."));
        properties.put("new_string", stringSchema(
            "Replacement text. Required for patch; can be empty string to delete the matched text."));
        properties.put("replace_all", booleanSchema(
            "For patch: replace all occurrences instead of requiring a unique match."));
        properties.put("category", stringSchema("Optional category/domain for organizing the skill. Only used with create."));
        properties.put("file_path", stringSchema(
            "Path to a supporting file within the skill directory."));
        properties.put("file_content", stringSchema("Content for the file. Required for write_file."));
        setRequired(required, "action", "name");
    }

    private void applyDelegateTaskSchemaOverride(Map<String, Object> properties, List<String> required) {
        Map<String, Object> taskProps = new LinkedHashMap<>();
        taskProps.put("goal", stringSchema(
            "What this subagent should accomplish. Be specific and self-contained."));
        taskProps.put("context", stringSchema(
            "Background this child needs: file paths, errors, constraints, and expected output language."));
        taskProps.put("output_schema", objectSchema(
            "Optional JSON Schema this child's final answer must validate against."));

        Map<String, Object> taskItem = new LinkedHashMap<>();
        taskItem.put("type", "object");
        taskItem.put("properties", taskProps);
        taskItem.put("required", List.of("goal"));

        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("type", "array");
        tasks.put("minItems", 1);
        tasks.put("items", taskItem);
        tasks.put("description", "One entry per subagent. Each child sees only its own context.");

        Map<String, Object> action = stringSchema(
            "Default 'spawn'. Live control: 'list', 'steer', or 'stop'.");
        action.put("enum", List.of("spawn", "list", "steer", "stop"));

        properties.clear();
        properties.put("tasks", tasks);
        properties.put("action", action);
        properties.put("subagent_id", stringSchema("Target for action='steer'/'stop'."));
        properties.put("message", stringSchema("For action='steer': the course correction."));
        setRequired(required);
    }

    private void applyCronJobSchemaOverride(Map<String, Object> properties, List<String> required) {
        properties.clear();
        properties.put("action", stringSchema(
            "One of: create, list, update, pause, resume, remove, run. When action=create, schedule is required."));
        properties.put("job_id", stringSchema("Required for update/pause/resume/remove/run."));
        properties.put("prompt", stringSchema(
            "For create: the self-contained prompt. For run: optional transient context for that single fire."));
        properties.put("schedule", stringSchema(
            "Required for create. Examples: '30m', 'every 2h', cron syntax '0 9 * * *', or an ISO timestamp."));
        properties.put("name", stringSchema("Optional human-friendly name."));
        properties.put("repeat", integerSchema("Optional repeat count. Omit for defaults."));
        properties.put("deliver", stringSchema("Where the job output is posted or saved."));
        properties.put("skills", arrayOfStringsSchema("Optional ordered skill names loaded before the cron prompt."));
        properties.put("script", stringSchema("Optional script run each tick; empty string clears on update."));
        properties.put("monitor", stringSchema(
            "Hermes monitor field. Java Agent currently rejects non-empty values until monitor runtime support lands."));
        Map<String, Object> noAgent = booleanSchema("True = run script without LLM; script is required.");
        noAgent.put("default", false);
        properties.put("no_agent", noAgent);
        properties.put("context_from", arrayOfStringsSchema("Optional upstream job IDs whose latest output is injected."));
        properties.put("continuity", booleanSchema(
            "Hermes continuity field. Java Agent currently rejects true until self-context runtime support lands."));
        properties.put("enabled_toolsets", arrayOfStringsSchema("Optional toolset names to restrict the job's agent."));
        properties.put("workdir", stringSchema("Optional absolute existing path to run the job from."));
        properties.put("attach_to_session", booleanSchema(
            "Hermes attach field. Java Agent currently rejects true until continuable cron delivery lands."));
        setRequired(required, "action");
    }

    private void applySendMessageSchemaOverride(Map<String, Object> properties, List<String> required) {
        Map<String, Object> action = stringSchema("Action to perform. 'send' (default) sends a message. 'list' returns registered Java gateway platforms.");
        action.put("enum", List.of("send", "list"));

        properties.clear();
        properties.put("action", action);
        properties.put("target", stringSchema(
            "Delivery target. Java supports explicit 'platform:chat_id' targets. Bare platform home-channel and thread/topic targets are not implemented yet."));
        properties.put("message", stringSchema("The message text to send."));
        setRequired(required);
    }

    private void applyMemorySchemaOverride(Map<String, Object> properties, List<String> required) {
        properties.remove("limit");
        Map<String, Object> target = copySchemaProperty(properties.get("target"));
        if (!target.isEmpty()) {
            List<String> targets = enabledMemoryTargets();
            target.put("enum", targets);
            if (targets.equals(List.of("memory"))) {
                target.put("description", "The enabled built-in store: 'memory' for personal notes.");
            } else if (targets.equals(List.of("user"))) {
                target.put("description", "The enabled built-in store: 'user' for user profile.");
            }
            properties.put("target", target);
        }
        setRequired(required, "target");
    }

    private String applyHermesDescriptionOverrides(String name, String description) {
        if ("memory".equals(name)) {
            return adaptMemoryDescription(description);
        }
        return description;
    }

    private String adaptMemoryDescription(String description) {
        List<String> targets = enabledMemoryTargets();
        String replacement;
        if (targets.equals(List.of("memory"))) {
            replacement = MEMORY_ONLY_DESCRIPTION;
        } else if (targets.equals(List.of("user"))) {
            replacement = USER_ONLY_DESCRIPTION;
        } else {
            return description;
        }

        if (description.contains(MEMORY_TARGETS_DESCRIPTION)) {
            return description.replace(MEMORY_TARGETS_DESCRIPTION, replacement);
        }
        return description + "\n\n" + replacement;
    }

    private boolean isMemoryToolAvailable() {
        return isMemoryStoreEnabled("memory") || isMemoryStoreEnabled("user");
    }

    private List<String> enabledMemoryTargets() {
        List<String> targets = new ArrayList<>(2);
        if (isMemoryStoreEnabled("memory")) {
            targets.add("memory");
        }
        if (isMemoryStoreEnabled("user")) {
            targets.add("user");
        }
        return targets;
    }

    private boolean isMemoryStoreEnabled(String target) {
        AgentProperties.MemoryProperties memory = properties == null ? null : properties.getMemory();
        if (memory == null) {
            return true;
        }
        return "user".equals(target) ? memory.isUserProfileEnabled() : memory.isMemoryEnabled();
    }

    private Map<String, Object> stringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> integerSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> booleanSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "boolean");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> objectSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> arrayOfStringsSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", Map.of("type", "string"));
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> copySchemaProperty(Object value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    copy.put(key, entry.getValue());
                }
            }
        }
        return copy;
    }

    private void retainProperties(Map<String, Object> properties, String... names) {
        Set<String> allowed = Set.of(names);
        properties.keySet().removeIf(name -> !allowed.contains(name));
    }

    private void setRequired(List<String> required, String... names) {
        required.clear();
        required.addAll(List.of(names));
    }

    private String schemaPropertyName(Class<?> recordClass, java.lang.reflect.RecordComponent component) {
        String jsonName = jsonPropertyValue(component.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class));
        if (jsonName != null) {
            return jsonName;
        }
        try {
            Method accessor = recordClass.getDeclaredMethod(component.getName());
            jsonName = jsonPropertyValue(accessor.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class));
            if (jsonName != null) {
                return jsonName;
            }
        } catch (NoSuchMethodException ignored) {
            // Fall through to the backing field, then to the Java component name.
        }
        try {
            java.lang.reflect.Field field = recordClass.getDeclaredField(component.getName());
            jsonName = jsonPropertyValue(field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class));
            if (jsonName != null) {
                return jsonName;
            }
        } catch (NoSuchFieldException ignored) {
            // Fall through to the Java component name.
        }
        return component.getName();
    }

    private String jsonPropertyValue(com.fasterxml.jackson.annotation.JsonProperty annotation) {
        if (annotation == null || annotation.value().isEmpty()) {
            return null;
        }
        return annotation.value();
    }

    private Class<?> findArgsClass(Class<? extends ToolHandler> handlerClass) {
        Class<?> fallbackRecord = null;
        for (Class<?> nested : handlerClass.getDeclaredClasses()) {
            if (nested.getSimpleName().endsWith("Args")) {
                return nested;
            }
            if (fallbackRecord == null && nested.isRecord()) {
                fallbackRecord = nested;
            }
        }
        return fallbackRecord;
    }

    /**
     * Build a JSON schema fragment for a record type's properties.
     * Returns {"type":"object","properties":{...}} or null if the record
     * has no components.
     */
    private Map<String, Object> buildRecordSchema(Class<?> recordClass) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> req = new ArrayList<>();
        for (java.lang.reflect.RecordComponent rc : recordClass.getRecordComponents()) {
            String propName = schemaPropertyName(recordClass, rc);
            ToolParam tp = rc.getAnnotation(ToolParam.class);
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("type", tp != null && !tp.type().isBlank() ? tp.type() : mapType(rc.getType()));
            field.put("description", tp != null ? tp.description() : "");
            if (tp != null && tp.enumValues().length > 0) {
                field.put("enum", java.util.Arrays.asList(tp.enumValues()));
            }
            addArrayItems(field, rc.getType(), rc.getGenericType());
            props.put(propName, field);
            if (tp != null && tp.required()) {
                req.add(propName);
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", req);
        return schema;
    }

    private void addArrayItems(Map<String, Object> field, Class<?> type, java.lang.reflect.Type genericType) {
        if (!"array".equals(field.get("type"))) {
            return;
        }
        field.putIfAbsent("items", arrayItemsSchema(type, genericType));
    }

    private Map<String, Object> arrayItemsSchema(Class<?> type, java.lang.reflect.Type genericType) {
        if (type != null && type.isArray()) {
            return javaTypeSchema(type.getComponentType());
        }
        if (genericType instanceof java.lang.reflect.ParameterizedType pt
            && pt.getActualTypeArguments().length > 0) {
            return javaTypeSchema(pt.getActualTypeArguments()[0]);
        }
        return Map.of("type", "string");
    }

    private Map<String, Object> javaTypeSchema(java.lang.reflect.Type type) {
        if (type instanceof Class<?> cls) {
            if (cls.isRecord()) {
                return buildRecordSchema(cls);
            }
            if (cls == Object.class) {
                return Map.of("anyOf", List.of(
                    Map.of("type", "string"),
                    Map.of("type", "object", "properties", Map.of())
                ));
            }
            if (cls.isArray()) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "array");
                schema.put("items", javaTypeSchema(cls.getComponentType()));
                return schema;
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", mapType(cls));
            if ("object".equals(schema.get("type"))) {
                schema.put("properties", Map.of());
            } else if ("array".equals(schema.get("type"))) {
                schema.put("items", Map.of("type", "string"));
            }
            return schema;
        }
        if (type instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() instanceof Class<?> rawClass) {
            if (java.util.Collection.class.isAssignableFrom(rawClass)) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "array");
                java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                schema.put("items", args.length > 0 ? javaTypeSchema(args[0]) : Map.of("type", "string"));
                return schema;
            }
            if (java.util.Map.class.isAssignableFrom(rawClass)) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", Map.of());
                schema.put("additionalProperties", true);
                return schema;
            }
        }
        return Map.of("type", "string");
    }

    private String mapType(Class<?> type) {
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type.isArray() || java.util.Collection.class.isAssignableFrom(type)) {
            return "array";
        }
        if (java.util.Map.class.isAssignableFrom(type)) {
            return "object";
        }
        return "string";
    }

    @Override
    public List<ToolDefinition> getDefinitions() {
        return adaptToolDefinitions(new ArrayList<>(entries.values().stream().map(ToolEntry::definition).toList()));
    }

    @Override
    public List<ToolDefinition> getDefinitions(Set<String> toolsets) {
        if (toolsets == null || toolsets.isEmpty()) {
            return getDefinitions();
        }
        ResolvedToolsetFilter filter = resolveToolsets(toolsets);
        List<ToolDefinition> selected = entries.values().stream()
            .filter(e -> filter.allTools()
                || e.isUnscopedDynamic()
                || (e.toolset() != null && filter.toolsets().contains(e.toolset()))
                || filter.toolNames().contains(e.definition().name()))
            .map(ToolEntry::definition)
            .toList();
        return adaptToolDefinitions(selected);
    }

    private List<ToolDefinition> adaptToolDefinitions(List<ToolDefinition> definitions) {
        Set<String> availableToolNames = definitions.stream()
            .map(ToolDefinition::name)
            .collect(java.util.stream.Collectors.toSet());
        return definitions.stream()
            .map(definition -> "delegate_task".equals(definition.name())
                ? adaptDelegateTaskDescription(definition, availableToolNames)
                : definition)
            .toList();
    }

    private ToolDefinition adaptDelegateTaskDescription(ToolDefinition definition, Set<String> availableToolNames) {
        String fullRestriction = "Leaf children (the default) cannot call delegate_task, clarify, memory, send_message, or cronjob; orchestrators regain only delegate_task.";
        String description = definition.description();
        if (!description.contains(fullRestriction)) {
            return definition;
        }

        List<String> blockedPresent = List.of("clarify", "memory", "send_message", "cronjob").stream()
            .filter(availableToolNames::contains)
            .toList();
        List<String> names = new ArrayList<>();
        names.add("delegate_task");
        names.addAll(blockedPresent);
        String replacement = "Leaf children (the default) cannot call " + joinEnglishOr(names)
            + "; orchestrators regain only delegate_task.";
        return new ToolDefinition(definition.name(), description.replace(fullRestriction, replacement), definition.parameters());
    }

    private String joinEnglishOr(List<String> names) {
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.get(0);
        }
        if (names.size() == 2) {
            return names.get(0) + " or " + names.get(1);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + ", or " + names.get(names.size() - 1);
    }

    private ResolvedToolsetFilter resolveToolsets(Set<String> toolsets) {
        Set<String> requested = new LinkedHashSet<>();
        Set<String> toolNames = new LinkedHashSet<>();
        boolean allTools = false;

        for (String toolset : toolsets) {
            if (toolset == null || toolset.isBlank()) {
                continue;
            }
            String name = toolset.trim();
            if ("all".equals(name) || "*".equals(name)) {
                allTools = true;
                break;
            }
            requested.add(name);
            String dynamicToolset = resolveDynamicToolsetAlias(name);
            if (dynamicToolset != null) {
                requested.add(dynamicToolset);
            }
            toolNames.addAll(resolveStaticToolset(name, new HashSet<>()));
        }

        return new ResolvedToolsetFilter(allTools, requested, toolNames);
    }

    private String resolveDynamicToolsetAlias(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        String mcpAlias = trimmed.startsWith("mcp-") ? trimmed : "mcp-" + trimmed;
        for (ToolEntry entry : entries.values()) {
            String dynamicToolset = entry.dynamicToolset();
            if (dynamicToolset == null) {
                continue;
            }
            if (dynamicToolset.equals(trimmed) || dynamicToolset.equals(mcpAlias)) {
                return dynamicToolset;
            }
        }
        return null;
    }

    private Set<String> resolveStaticToolset(String name, Set<String> visited) {
        if (!visited.add(name)) {
            return Set.of();
        }
        ToolsetSpec spec = HERMES_TOOLSETS.get(name);
        if (spec == null) {
            return Set.of();
        }

        Set<String> result = new LinkedHashSet<>(spec.tools());
        for (String included : spec.includes()) {
            result.addAll(resolveStaticToolset(included, visited));
        }
        return result;
    }

    @Override
    public ToolResult execute(String toolName, String toolCallId, String arguments, Message lastAssistant, Session session) {
        ToolEntry entry = entries.get(toolName);
        if (entry == null) {
            return failureResult("Unknown tool: " + toolName);
        }
        return entry.handler().execute(arguments, lastAssistant, session);
    }

    private ToolResult failureResult(String error) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("success", false);
        payload.put("error", error);
        try {
            return new ToolResult(false, objectMapper.writeValueAsString(payload), error);
        } catch (Exception e) {
            return new ToolResult(false, payload.toString(), error);
        }
    }

    @Override
    public Set<String> getToolsets() {
        Set<String> result = new HashSet<>();
        for (ToolEntry e : entries.values()) {
            String toolset = e.toolset();
            if (toolset != null) {
                result.add(toolset);
            }
        }
        for (String toolset : HERMES_TOOLSETS.keySet()) {
            if (hasRegisteredToolFor(toolset)) {
                result.add(toolset);
            }
        }
        return result;
    }

    private boolean hasRegisteredToolFor(String toolset) {
        Set<String> resolvedTools = resolveStaticToolset(toolset, new HashSet<>());
        if (resolvedTools.isEmpty()) {
            return entries.values().stream()
                .anyMatch(e -> toolset.equals(e.toolset()));
        }
        return entries.values().stream()
            .anyMatch(e -> toolset.equals(e.toolset()) || resolvedTools.contains(e.definition().name()));
    }

    private record ToolEntry(AgentTool annotation, ToolHandler handler, ToolDefinition definition, String dynamicToolset) {
        private ToolEntry(AgentTool annotation, ToolHandler handler, ToolDefinition definition) {
            this(annotation, handler, definition, null);
        }

        private String toolset() {
            return annotation != null ? annotation.toolset() : dynamicToolset;
        }

        private boolean isUnscopedDynamic() {
            return annotation == null && dynamicToolset == null;
        }
    }
    private record ToolsetSpec(List<String> tools, List<String> includes) {}
    private record ResolvedToolsetFilter(boolean allTools, Set<String> toolsets, Set<String> toolNames) {}

    @Override
    public void registerDynamic(String toolName, ToolDefinition definition, ToolHandler handler) {
        entries.put(toolName, new ToolEntry(null, handler, definition));
    }

    @Override
    public void registerDynamic(String toolName, String toolset, ToolDefinition definition, ToolHandler handler) {
        String normalizedToolset = toolset == null || toolset.isBlank() ? null : toolset.trim();
        entries.put(toolName, new ToolEntry(null, handler, definition, normalizedToolset));
    }

    @Override
    public void deregisterDynamic(String toolName) {
        entries.remove(toolName);
    }
}
