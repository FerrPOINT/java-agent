package com.azhukov.agent.tools.memory;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@AgentTool(
    name = "todo",
    description = "Create, update, or list todos for the current session. Supports merge mode (update by id) and statuses: pending, in_progress, completed, cancelled.",
    toolset = "todo"
)
@Component
@RequiredArgsConstructor
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

        if ("create".equalsIgnoreCase(args.action())) {
            return handleCreate(args, session);
        }
        if ("update".equalsIgnoreCase(args.action())) {
            return handleUpdate(args, session);
        }
        if ("merge".equalsIgnoreCase(args.action()) || "set".equalsIgnoreCase(args.action())) {
            boolean merge = "merge".equalsIgnoreCase(args.action()) || args.merge();
            return mergeOrSet(args.items(), merge, session);
        }
        if ("list".equalsIgnoreCase(args.action())) {
            return handleList(args, session);
        }

        return ToolResult.fail("Unknown action: " + args.action() + ". Supported: create, update, merge, set, list.");
    }

    private ToolResult handleCreate(TodoArgs args, Session session) {
        String title = args.title();
        if (title == null || title.isBlank()) {
            return ToolResult.fail("Title is required for create action.");
        }
        if (title.length() > MAX_CONTENT_CHARS) {
            return ToolResult.fail("Title exceeds " + MAX_CONTENT_CHARS + " characters (got " + title.length() + ").");
        }
        long currentCount = todoRepository.findByUserId(session.userId()).size();
        if (currentCount >= MAX_ITEMS) {
            return ToolResult.fail("Maximum of " + MAX_ITEMS + " todos reached. Remove or complete some items first.");
        }
        String status = validateStatus(args.status(), "pending");
        if (status == null) {
            return ToolResult.fail("Invalid status: " + args.status() + ". Allowed: " + ALLOWED_STATUSES);
        }
        TodoEntity e = new TodoEntity();
        e.setSessionId(session.id());
        e.setUserId(session.userId());
        e.setTitle(title);
        e.setStatus(status);
        e.setPriority(args.priority() != null ? args.priority() : "medium");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        todoRepository.save(e);
        return ToolResult.ok("Created todo: " + title);
    }

    private ToolResult handleUpdate(TodoArgs args, Session session) {
        if (args.id() == null) {
            return ToolResult.fail("id is required for update action.");
        }
        TodoEntity existing = todoRepository.findById(args.id()).orElse(null);
        if (existing == null) {
            return ToolResult.fail("Todo not found: " + args.id());
        }
        if (!existing.getUserId().equals(session.userId())) {
            return ToolResult.fail("Todo not found: " + args.id());
        }
        if (args.title() != null) {
            if (args.title().length() > MAX_CONTENT_CHARS) {
                return ToolResult.fail("Title exceeds " + MAX_CONTENT_CHARS + " characters (got " + args.title().length() + ").");
            }
            existing.setTitle(args.title());
        }
        if (args.status() != null) {
            String validated = validateStatus(args.status(), null);
            if (validated == null) {
                return ToolResult.fail("Invalid status: " + args.status() + ". Allowed: " + ALLOWED_STATUSES);
            }
            existing.setStatus(validated);
        }
        if (args.priority() != null) {
            existing.setPriority(args.priority());
        }
        existing.setUpdatedAt(Instant.now());
        todoRepository.save(existing);
        return ToolResult.ok("Updated todo: " + existing.getId());
    }

    private ToolResult handleList(TodoArgs args, Session session) {
        var todos = todoRepository.findByUserId(session.userId());
        int limit = args.limit() != null && args.limit() > 0 ? args.limit() : todos.size();
        return ToolResult.ok(todos.stream()
            .limit(limit)
            .map(t -> "- [" + t.getStatus() + "] " + t.getTitle() + " (" + (t.getPriority() != null ? t.getPriority() : "medium") + ")")
            .reduce((a, b) -> a + "\n" + b)
            .orElse("No todos."));
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

    /**
     * Merge mode: update existing todos by id without replacing the full list.
     * When {@code merge=true}, items containing an id will update existing entities;
     * items without an id will be created as new entities. When {@code merge=false}
     * (default), all existing todos for the user are replaced by the provided items.
     *
     * @param items the list of todo items to merge or set
     * @param merge when true, update by id; when false, replace all
     * @param session the current session context
     * @return the result of the operation
     */
    ToolResult mergeOrSet(List<TodoItem> items, boolean merge, Session session) {
        if (items == null) {
            items = List.of();
        }
        if (items.size() > MAX_ITEMS) {
            return ToolResult.fail("Exceeded maximum of " + MAX_ITEMS + " items (got " + items.size() + ").");
        }
        for (TodoItem item : items) {
            if (item.title() != null && item.title().length() > MAX_CONTENT_CHARS) {
                return ToolResult.fail("Title exceeds " + MAX_CONTENT_CHARS + " characters: '" + item.title() + "'.");
            }
            String status = validateStatus(item.status(), "pending");
            if (status == null) {
                return ToolResult.fail("Invalid status: " + item.status() + ". Allowed: " + ALLOWED_STATUSES);
            }
        }
        if (!merge) {
            todoRepository.deleteByUserId(session.userId());
        }
        int created = 0;
        int updated = 0;
        for (TodoItem item : items) {
            TodoEntity entity = null;
            if (merge && item.id() != null) {
                entity = todoRepository.findById(item.id()).orElse(null);
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
            if (item.title() != null) {
                entity.setTitle(item.title());
            }
            if (item.status() != null) {
                entity.setStatus(validateStatus(item.status(), "pending"));
            } else if (entity.getStatus() == null) {
                entity.setStatus("pending");
            }
            if (item.priority() != null) {
                entity.setPriority(item.priority());
            } else if (entity.getPriority() == null) {
                entity.setPriority("medium");
            }
            todoRepository.save(entity);
        }
        String mode = merge ? "Merged" : "Set";
        return ToolResult.ok(mode + " todos: " + updated + " updated, " + created + " created.");
    }

    public static class TodoArgs {
        @ToolParam(description = "create, update, or list")
        private String action;
        @ToolParam(description = "todo title (max 4000 chars)", required = false)
        private String title;
        @ToolParam(description = "low/medium/high", required = false)
        private String priority;
        @ToolParam(description = "max items to list", required = false)
        private Integer limit;
        @ToolParam(description = "todo id (for update/merge)", required = false)
        private UUID id;
        @ToolParam(description = "status: pending, in_progress, completed, cancelled", required = false)
        private String status;
        @ToolParam(description = "when true, update existing items by id instead of replacing all", required = false)
        private Boolean merge;
        @ToolParam(description = "batch mode: list of {id, title, status, priority} items for merge/set", required = false)
        private List<TodoItem> items;

        public String action() { return action; }
        public String title() { return title; }
        public String priority() { return priority; }
        public Integer limit() { return limit; }
        public UUID id() { return id; }
        public String status() { return status; }
        public Boolean merge() { return merge != null && merge; }
        public List<TodoItem> items() { return items; }
    }

    public record TodoItem(
        @ToolParam(description = "todo id (for update/merge mode)", required = false) UUID id,
        @ToolParam(description = "todo title (max 4000 chars)", required = false) String title,
        @ToolParam(description = "status: pending, in_progress, completed, cancelled", required = false) String status,
        @ToolParam(description = "low/medium/high", required = false) String priority
    ) {}
}