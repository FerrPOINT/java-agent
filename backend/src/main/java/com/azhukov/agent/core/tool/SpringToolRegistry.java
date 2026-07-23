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
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class SpringToolRegistry implements ToolRegistry {

    private final ApplicationContext context;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, ToolEntry> entries = new LinkedHashMap<>();

    public SpringToolRegistry(ApplicationContext context, AgentProperties properties, ObjectMapper objectMapper) {
        this.context = context;
        this.properties = properties;
        this.objectMapper = objectMapper;
        registerBeans();
    }

    private void registerBeans() {
        Map<String, Object> beans = context.getBeansWithAnnotation(AgentTool.class);
        for (Object bean : beans.values()) {
            AgentTool annotation = bean.getClass().getAnnotation(AgentTool.class);
            if (annotation == null || !(bean instanceof ToolHandler handler)) {
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

        // Find the args class: look for a nested record or a nested POJO class named ...Args
        Class<?> argsClass = null;
        for (Class<?> nested : handlerClass.getDeclaredClasses()) {
            if (nested.isRecord() || nested.getSimpleName().endsWith("Args")) {
                argsClass = nested;
                break;
            }
        }

        if (argsClass != null) {
            if (argsClass.isRecord()) {
                for (java.lang.reflect.RecordComponent rc : argsClass.getRecordComponents()) {
                    addProperty(properties, required, rc.getName(), rc.getType(), rc.getAnnotation(ToolParam.class));
                }
            } else {
                for (java.lang.reflect.Field field : argsClass.getDeclaredFields()) {
                    addProperty(properties, required, field.getName(), field.getType(), field.getAnnotation(ToolParam.class));
                }
            }
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        return new ToolDefinition(name, description, parameters);
    }

    private void addProperty(Map<String, Object> properties, List<String> required,
                             String name, Class<?> type, ToolParam param) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", param != null && !param.type().isBlank() ? param.type() : mapType(type));
        field.put("description", param != null ? param.description() : "");
        properties.put(name, field);
        if (param == null || param.required()) {
            required.add(name);
        }
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
        return "string";
    }

    @Override
    public List<ToolDefinition> getDefinitions() {
        return new ArrayList<>(entries.values().stream().map(ToolEntry::definition).toList());
    }

    @Override
    public List<ToolDefinition> getDefinitions(Set<String> toolsets) {
        if (toolsets == null || toolsets.isEmpty()) {
            return getDefinitions();
        }
        return entries.values().stream()
            .filter(e -> toolsets.contains(e.annotation().toolset()))
            .map(ToolEntry::definition)
            .toList();
    }

    @Override
    public ToolResult execute(String toolName, String toolCallId, String arguments, Message lastAssistant, Session session) {
        ToolEntry entry = entries.get(toolName);
        if (entry == null) {
            return ToolResult.fail("Unknown tool: " + toolName);
        }
        return entry.handler().execute(arguments, lastAssistant, session);
    }

    @Override
    public Set<String> getToolsets() {
        Set<String> result = new HashSet<>();
        for (ToolEntry e : entries.values()) {
            result.add(e.annotation().toolset());
        }
        return result;
    }

    private record ToolEntry(AgentTool annotation, ToolHandler handler, ToolDefinition definition) {}
}
