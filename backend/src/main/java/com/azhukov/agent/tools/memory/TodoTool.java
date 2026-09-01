package com.azhukov.agent.tools.memory;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.TodoRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hermes parity: todo tool with exact same schema as Hermes' todo_tool.py.
 * <p>
 * Schema: {@code todos} (array of {id, content, status}) + {@code merge} (boolean).
 * Calling with no parameters reads the current list.
 * merge=false (default): replace the entire list.
 * merge=true: update existing items by id, add new ones.
 * <p>
 * The description is byte-identical to Hermes' TODO_SCHEMA.description so the
 * model sees the same guidance regardless of which agent it talks to.
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

    static final Set<String> ALLOWED_STATUSES = Set.of(
        "pending", "in_progress", "completed", "cancelled"
    );

    private final TodoRepository todoRepository;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TodoArgs args = ToolHandler.parseJson(arguments, TodoArgs.class);

        // No todos provided → read current list (Hermes parity)
        if (args.todos() == null) {
            return readList(session);
        }

        // Guard: LLM sometimes sends todos as a JSON string instead of a list
        // (Hermes parity: same guard in todo_tool.py)
        List<TodoItem> items = args.todos();
        if (items == null) {
            items = List.of();
        }
        if (items.size() > MAX_ITEMS) {
            return ToolResult.fail("Exceeded maximum of " + MAX_ITEMS + " items (got " + items.size() + ").");
        }

        // Validate each item
        for (TodoItem item : items) {
            if (item.content() == null || item.content().isBlank()) {
                return ToolResult.fail("Each todo item must have 'content'.");
            }
            if (item.content().length() > MAX_CONTENT_CHARS) {
                return ToolResult.fail("Content exceeds " + MAX_CONTENT_CHARS + " characters: '" + item.content() + "'.");
            }
            // null/blank status defaults to "pending" (Hermes parity)
            String status = validateStatus(item.status(), "pending");
            if (status == null) {
                return ToolResult.fail("Invalid status: " + item.status() + ". Allowed: " + ALLOWED_STATUSES);
            }
        }

        boolean merge = args.merge() != null && args.merge();

        if (!merge) {
            // Replace entire list (Hermes: merge=false default)
            todoRepository.deleteByUserId(session.userId());
        }

        int created = 0;
        int updated = 0;
        for (TodoItem item : items) {
            TodoEntity entity = null;
            if (merge && item.id() != null && !item.id().isBlank()) {
                UUID itemId = resolveTodoId(item.id(), session);
                if (itemId != null) {
                    entity = todoRepository.findById(itemId).orElse(null);
                }
                if (entity == null || !entity.getUserId().equals(session.userId())) {
                    entity = null;
                }
            }
            if (entity == null) {
                entity = new TodoEntity();
                entity.setSessionId(session.id());
                entity.setUserId(session.userId());
                entity.setCreatedAt(Instant.now());
                entity.setUpdatedAt(Instant.now());
                created++;
            } else {
                entity.setUpdatedAt(Instant.now());
                updated++;
            }
            entity.setTitle(item.content());
            entity.setStatus(validateStatus(item.status(), "pending"));
            // Priority not in Hermes schema — keep "medium" for DB compatibility
            if (entity.getPriority() == null) {
                entity.setPriority("medium");
            }
            todoRepository.save(entity);
        }

        // Return the full current list (Hermes parity: always returns full list)
        return readList(session);
    }

    /**
     * Read the current todo list and return as formatted text.
     * Hermes returns JSON with {todos, summary}. We return the same format.
     */
    private ToolResult readList(Session session) {
        var todos = todoRepository.findByUserIdOrderByCreatedAtAsc(session.userId());
        StringBuilder sb = new StringBuilder();
        int pending = 0, inProgress = 0, completed = 0, cancelled = 0;
        for (TodoEntity t : todos) {
            String status = t.getStatus() != null ? t.getStatus() : "pending";
            sb.append("- [").append(status).append("] ");
            if (t.getId() != null) {
                // 1-based position for model readability (Hermes uses string ids)
                int pos = todos.indexOf(t) + 1;
                sb.append(pos).append(". ");
            }
            sb.append(t.getTitle());
            if (status.equals("pending")) pending++;
            else if (status.equals("in_progress")) inProgress++;
            else if (status.equals("completed")) completed++;
            else if (status.equals("cancelled")) cancelled++;
            sb.append("\n");
        }
        if (todos.isEmpty()) {
            sb.append("No todos.\n");
        }
        sb.append("\nSummary: total=").append(todos.size())
          .append(", pending=").append(pending)
          .append(", in_progress=").append(inProgress)
          .append(", completed=").append(completed)
          .append(", cancelled=").append(cancelled);
        return ToolResult.ok(sb.toString());
    }

    /**
     * Resolve a todo id string to UUID. Accepts:
     * - Full UUID string (e.g. "550e8400-e29b-41d4-a716-446655440000")
     * - 1-based position number (e.g. "1", "2") — resolves to the Nth todo
     *   in list order (oldest first)
     * Returns null if not found or invalid.
     */
    private UUID resolveTodoId(String idStr, Session session) {
        if (idStr == null || idStr.isBlank()) return null;
        idStr = idStr.trim();
        // Try UUID parse first
        try {
            return UUID.fromString(idStr);
        } catch (IllegalArgumentException notUuid) {
            // Try numeric position (1-based)
            try {
                int pos = Integer.parseInt(idStr);
                if (pos < 1) return null;
                var todos = todoRepository.findByUserIdOrderByCreatedAtAsc(session.userId());
                if (pos > todos.size()) return null;
                return todos.get(pos - 1).getId();
            } catch (NumberFormatException notNumber) {
                return null;
            }
        }
    }

    /**
     * Validate status against the allowed set. Returns the normalized (lowercase)
     * status, or {@code fallback} when {@code status} is null/blank, or {@code null}
     * when invalid.
     */
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

    // ─── Hermes-parity schema ──────────────────────────────────────

    /**
     * Custom deserializer for the {@code todos} field that handles the case
     * where the LLM sends the array as a JSON-encoded string instead of a
     * proper JSON array (Hermes parity: same guard in todo_tool.py).
     */
    public static class TodoListDeserializer extends JsonDeserializer<List<TodoItem>> {
        @Override
        public List<TodoItem> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                String str = p.getText();
                if (str == null || str.isBlank()) {
                    return null;
                }
                // Parse the string as JSON array
                return p.getCodec().readValue(
                    p.getCodec().getFactory().createParser(str),
                    ctxt.getTypeFactory().constructCollectionType(List.class, TodoItem.class)
                );
            }
            // Normal array deserialization
            return ctxt.readValue(p, ctxt.getTypeFactory().constructCollectionType(List.class, TodoItem.class));
        }
    }

    public static class TodoArgs {
        @JsonProperty("todos")
        @JsonDeserialize(using = TodoListDeserializer.class)
        @ToolParam(description = "Task items to write. Omit to read current list.", required = false)
        private List<TodoItem> todos;

        @JsonProperty("merge")
        @ToolParam(description = """
            true: update existing items by id, add new ones. \
            false (default): replace the entire list.""", required = false)
        private Boolean merge;

        // Jackson needs either setters or public fields
        public List<TodoItem> getTodos() { return todos; }
        public Boolean getMerge() { return merge; }
        public void setTodos(List<TodoItem> todos) { this.todos = todos; }
        public void setMerge(Boolean merge) { this.merge = merge; }

        // Hermes-parity accessors
        public List<TodoItem> todos() { return todos; }
        public Boolean merge() { return merge; }
    }

    /**
     * Hermes-parity todo item: {id, content, status}.
     * Note: Hermes uses 'content' (not 'title'), and 'id' is a string
     * (not UUID) — the model sends "1", "2", etc.
     */
    public static class TodoItem {
        @JsonProperty("id")
        private String id;
        @JsonProperty("content")
        private String content;
        @JsonProperty("status")
        private String status;

        public TodoItem() {}

        /**
         * Fallback: some LLMs wrap each todo object as a JSON string inside
         * the todos array (e.g. todos: ["{\"id\":\"1\",...}"]).
         * Jackson tries to construct TodoItem from a String value; this
         * constructor accepts that, parses the inner JSON, and delegates.
         */
        public TodoItem(String json) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                this.id = node.has("id") ? node.get("id").asText() : null;
                this.content = node.has("content") ? node.get("content").asText() : null;
                this.status = node.has("status") ? node.get("status").asText() : null;
            } catch (Exception e) {
                // If it's not JSON, treat the raw string as content
                this.content = json;
            }
        }

        public String id() { return id; }
        public String content() { return content; }
        public String status() { return status; }

        public void setId(String id) { this.id = id; }
        public void setContent(String content) { this.content = content; }
        public void setStatus(String status) { this.status = status; }
    }
}