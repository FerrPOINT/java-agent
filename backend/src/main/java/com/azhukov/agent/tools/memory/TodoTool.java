package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.TodoRepository;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Hermes parity: todo tool with the same model-facing schema as Hermes'
 * todo_tool.py. The Java backend persists state in Postgres, but the tool
 * surface remains session-scoped and JSON-shaped.
 */
@AgentTool(
    name = "todo",
    description = """
        Manage your task list for the current session. Use for complex tasks \
        with 3+ steps or when the user provides multiple tasks. \
        For 'all N items' tasks, enumerate every instance as its own checklist \
        item so none are silently dropped. \
        Call with no parameters to read the current list.\n\n\
        Writing:\n\
        - Provide 'todos' array to create/update items\n\
        - merge=false (default): replace the entire list with a fresh plan\n\
        - merge=true: update existing items by id, add any new ones\n\n\
        Each item: {id: string, content: string, \
        status: pending|in_progress|completed|cancelled}\n\
        List order is priority. Only ONE item in_progress at a time.\n\
        Mark an item completed only after the work is verified done, never \
        based on intent. If something fails, \
        cancel it and add a revised item.\n\n\
        Always returns the full current list.""",
    toolset = "todo"
)
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoTool implements ToolHandler {

    static final int MAX_CONTENT_CHARS = 4000;
    static final int MAX_ITEMS = 256;
    static final String TRUNCATION_MARKER = "\u2026 [truncated]";

    static final Set<String> ALLOWED_STATUSES = Set.of(
        "pending", "in_progress", "completed", "cancelled"
    );

    private static final ObjectMapper MAPPER = ToolHandler.TOOL_ARGS_MAPPER.copy();

    private final TodoRepository todoRepository;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        JsonNode root;
        try {
            root = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (JsonProcessingException e) {
            return jsonError("Invalid tool arguments: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            return jsonError("Invalid tool arguments: expected an object");
        }

        JsonNode todosNode = root.get("todos");
        if (todosNode == null || todosNode.isNull()) {
            return readList(session);
        }

        ParsedTodos parsed = parseTodos(todosNode);
        if (parsed.error() != null) {
            return jsonError(parsed.error());
        }

        boolean merge = root.path("merge").asBoolean(false);
        if (merge) {
            mergeTodos(parsed.items(), session);
        } else {
            replaceTodos(parsed.items(), session);
        }
        return readList(session);
    }

    private ParsedTodos parseTodos(JsonNode todosNode) {
        JsonNode arrayNode = todosNode;
        if (todosNode.isTextual()) {
            try {
                arrayNode = MAPPER.readTree(todosNode.asText());
            } catch (JsonProcessingException e) {
                return ParsedTodos.error("todos must be a list of objects, got unparseable string");
            }
        }
        if (arrayNode == null || !arrayNode.isArray()) {
            return ParsedTodos.error("todos must be a list, got " + jsonTypeName(arrayNode));
        }

        List<ParsedTodo> items = new ArrayList<>();
        int index = 0;
        for (JsonNode node : arrayNode) {
            items.add(parseTodoItem(node, index++));
        }
        return ParsedTodos.ok(dedupeById(items));
    }

    private ParsedTodo parseTodoItem(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            return new ParsedTodo(
                "__invalid_" + index,
                "?",
                "(invalid item)",
                "pending",
                false,
                true,
                true
            );
        }

        String rawId = stringValue(node.get("id"));
        boolean hasMergeId = rawId != null && !rawId.isBlank();
        String id = hasMergeId ? rawId.trim() : "?";

        JsonNode contentNode = node.get("content");
        boolean hasContentUpdate = contentNode != null && !contentNode.isNull()
            && !contentNode.asText().isBlank();
        String content = hasContentUpdate
            ? capContent(contentNode.asText().trim())
            : "(no description)";

        JsonNode statusNode = node.get("status");
        String rawStatus = stringValue(statusNode);
        String normalizedStatus = validateStatus(rawStatus, "pending");
        boolean hasStatusUpdate = rawStatus != null && !rawStatus.isBlank()
            && normalizedStatus != null
            && ALLOWED_STATUSES.contains(normalizedStatus);
        if (normalizedStatus == null) {
            normalizedStatus = "pending";
        }

        return new ParsedTodo(
            id,
            id,
            content,
            normalizedStatus,
            hasMergeId,
            hasContentUpdate,
            hasStatusUpdate
        );
    }

    private List<ParsedTodo> dedupeById(List<ParsedTodo> todos) {
        Map<String, Integer> lastIndex = new LinkedHashMap<>();
        for (int i = 0; i < todos.size(); i++) {
            lastIndex.put(todos.get(i).dedupeKey(), i);
        }
        return lastIndex.values().stream()
            .sorted()
            .map(todos::get)
            .toList();
    }

    private void replaceTodos(List<ParsedTodo> items, Session session) {
        todoRepository.deleteBySessionIdAndUserId(session.id(), session.userId());
        List<ParsedTodo> ordered = normalizeOrder(items, ParsedTodo::status);
        int count = Math.min(ordered.size(), MAX_ITEMS);
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            ParsedTodo item = ordered.get(i);
            TodoEntity entity = new TodoEntity();
            entity.setSessionId(session.id());
            entity.setUserId(session.userId());
            entity.setTitle(item.content());
            entity.setStatus(item.status());
            entity.setPriority("medium");
            entity.setCreatedAt(now.plusMillis(i));
            entity.setUpdatedAt(now.plusMillis(i));
            todoRepository.save(entity);
        }
    }

    private void mergeTodos(List<ParsedTodo> items, Session session) {
        List<TodoEntity> current = currentTodos(session);
        for (ParsedTodo item : items) {
            if (!item.hasMergeId()) {
                continue;
            }

            TodoEntity entity = resolveTodo(item.id(), session, current);
            boolean existing = entity != null;
            if (!existing) {
                entity = new TodoEntity();
                entity.setSessionId(session.id());
                entity.setUserId(session.userId());
                entity.setTitle(item.content());
                entity.setStatus(item.status());
                entity.setPriority("medium");
                entity.setCreatedAt(Instant.now());
            } else {
                if (item.hasContentUpdate()) {
                    entity.setTitle(item.content());
                }
                if (item.hasStatusUpdate()) {
                    entity.setStatus(item.status());
                }
                if (entity.getPriority() == null) {
                    entity.setPriority("medium");
                }
            }
            entity.setUpdatedAt(Instant.now());
            TodoEntity saved = todoRepository.save(entity);
            if (!existing) {
                current = new ArrayList<>(current);
                current.add(saved);
            }
        }
        capSessionTodos(session);
    }

    private ToolResult readList(Session session) {
        List<TodoEntity> todos = currentTodos(session);
        List<Map<String, String>> items = new ArrayList<>();
        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        int cancelled = 0;

        for (int i = 0; i < todos.size(); i++) {
            TodoEntity todo = todos.get(i);
            String status = validateStatus(todo.getStatus(), "pending");
            if (status == null) {
                status = "pending";
            }

            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(i + 1));
            item.put("content", normalizedContent(todo.getTitle()));
            item.put("status", status);
            items.add(item);

            if ("pending".equals(status)) {
                pending++;
            } else if ("in_progress".equals(status)) {
                inProgress++;
            } else if ("completed".equals(status)) {
                completed++;
            } else if ("cancelled".equals(status)) {
                cancelled++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", items.size());
        summary.put("pending", pending);
        summary.put("in_progress", inProgress);
        summary.put("completed", completed);
        summary.put("cancelled", cancelled);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("todos", items);
        response.put("summary", summary);
        return ToolResult.ok(toJson(response));
    }

    private List<TodoEntity> currentTodos(Session session) {
        List<TodoEntity> rows = todoRepository.findBySessionIdOrderByCreatedAtAsc(session.id());
        if (rows == null) {
            rows = List.of();
        }
        List<TodoEntity> todos = rows.stream()
            .filter(todo -> session.userId().equals(todo.getUserId()))
            .sorted(Comparator.comparing(TodoEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        return normalizeOrder(todos, todo -> {
            String status = validateStatus(todo.getStatus(), "pending");
            return status == null ? "pending" : status;
        });
    }

    private TodoEntity resolveTodo(String idStr, Session session, List<TodoEntity> current) {
        if (idStr == null || idStr.isBlank()) {
            return null;
        }
        String id = idStr.trim();
        try {
            int pos = Integer.parseInt(id);
            if (pos >= 1 && pos <= current.size()) {
                return current.get(pos - 1);
            }
        } catch (NumberFormatException ignored) {
            // Not a numeric display id; try UUID below.
        }

        try {
            UUID uuid = UUID.fromString(id);
            for (TodoEntity todo : current) {
                if (uuid.equals(todo.getId())) {
                    return todo;
                }
            }
            return todoRepository.findById(uuid)
                .filter(todo -> session.id().equals(todo.getSessionId()))
                .filter(todo -> session.userId().equals(todo.getUserId()))
                .orElse(null);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    private void capSessionTodos(Session session) {
        List<TodoEntity> todos = currentTodos(session);
        for (int i = MAX_ITEMS; i < todos.size(); i++) {
            todoRepository.delete(todos.get(i));
        }
    }

    private static <T> List<T> normalizeOrder(List<T> items, Function<T, String> statusReader) {
        int activeIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if ("in_progress".equals(statusReader.apply(items.get(i)))) {
                activeIndex = i;
                break;
            }
        }
        if (activeIndex < 0) {
            return items;
        }

        int pendingIndex = -1;
        for (int i = 0; i < activeIndex; i++) {
            if ("pending".equals(statusReader.apply(items.get(i)))) {
                pendingIndex = i;
                break;
            }
        }
        if (pendingIndex < 0) {
            return items;
        }

        List<T> normalized = new ArrayList<>(items);
        T active = normalized.remove(activeIndex);
        normalized.add(pendingIndex, active);
        return normalized;
    }

    static String validateStatus(String status, String fallback) {
        if (status == null || status.isBlank()) {
            return fallback;
        }
        String normalized = status.toLowerCase().trim();
        if (!ALLOWED_STATUSES.contains(normalized)) {
            return null;
        }
        return normalized;
    }

    private static String normalizedContent(String content) {
        if (content == null || content.isBlank()) {
            return "(no description)";
        }
        return capContent(content.strip());
    }

    private static String capContent(String content) {
        if (content.length() > MAX_CONTENT_CHARS) {
            int keep = MAX_CONTENT_CHARS - TRUNCATION_MARKER.length();
            return content.substring(0, keep) + TRUNCATION_MARKER;
        }
        return content;
    }

    private static String stringValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static String jsonTypeName(JsonNode node) {
        if (node == null || node.isNull()) {
            return "NoneType";
        }
        if (node.isObject()) {
            return "dict";
        }
        if (node.isArray()) {
            return "list";
        }
        if (node.isTextual()) {
            return "str";
        }
        if (node.isBoolean()) {
            return "bool";
        }
        if (node.isIntegralNumber()) {
            return "int";
        }
        if (node.isNumber()) {
            return "float";
        }
        return node.getNodeType().name().toLowerCase();
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize todo response", e);
        }
    }

    private static ToolResult jsonError(String error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", error);
        return new ToolResult(false, toJson(response), error);
    }

    // Schema carrier for SpringToolRegistry. execute() parses raw JSON manually
    // so it can accept Hermes' recoverable string-encoded todos guard.
    public record TodoArgs(
        @JsonProperty("todos")
        @ToolParam(description = "Task items to write. Omit to read current list.", required = false)
        List<TodoItem> todos,
        @JsonProperty("merge")
        @ToolParam(description = """
            true: update existing items by id, add new ones. \
            false (default): replace the entire list.""", required = false)
        Boolean merge
    ) {}

    public record TodoItem(
        @JsonProperty("id")
        @ToolParam(description = "Unique item identifier")
        String id,
        @JsonProperty("content")
        @ToolParam(description = "Task description")
        String content,
        @JsonProperty("status")
        @ToolParam(
            description = "Current status",
            enumValues = {"pending", "in_progress", "completed", "cancelled"}
        )
        String status
    ) {}

    private record ParsedTodos(List<ParsedTodo> items, String error) {
        static ParsedTodos ok(List<ParsedTodo> items) {
            return new ParsedTodos(items, null);
        }

        static ParsedTodos error(String error) {
            return new ParsedTodos(List.of(), error);
        }
    }

    private record ParsedTodo(
        String dedupeKey,
        String id,
        String content,
        String status,
        boolean hasMergeId,
        boolean hasContentUpdate,
        boolean hasStatusUpdate
    ) {}
}
