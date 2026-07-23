package com.azhukov.agent.tools.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.TodoRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@AgentTool(
    name = "todo",
    description = "Create, update, or list todos for the current session.",
    toolset = "todo"
)
@Component
public class TodoTool implements ToolHandler {

    private final TodoRepository todoRepository;

    public TodoTool(TodoRepository todoRepository) {
        this.todoRepository = todoRepository;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TodoArgs args = ToolHandler.parseJson(arguments, TodoArgs.class);
        if ("create".equalsIgnoreCase(args.action())) {
            TodoEntity e = new TodoEntity();
            e.setSessionId(session.id());
            e.setUserId(session.userId());
            e.setTitle(args.title());
            e.setStatus("pending");
            e.setPriority(args.priority());
            e.setCreatedAt(Instant.now());
            todoRepository.save(e);
            return ToolResult.ok("Created todo: " + args.title());
        }
        var todos = todoRepository.findBySessionIdOrderByCreatedAtAsc(session.id());
        return ToolResult.ok(todos.stream()
            .map(t -> "- [" + t.getStatus() + "] " + t.getTitle() + " (" + t.getPriority() + ")")
            .reduce((a, b) -> a + "\n" + b)
            .orElse("No todos."));
    }

    public static class TodoArgs {
        @JsonProperty("action")
        @ToolParam(description = "create or list")
        private String action;
        @JsonProperty("title")
        @ToolParam(description = "todo title", required = false)
        private String title;
        @JsonProperty("priority")
        @ToolParam(description = "low/medium/high", required = false)
        private String priority;
        @JsonProperty("limit")
        @ToolParam(description = "max items to list", required = false)
        private Integer limit;

        public String action() { return action; }
        public String title() { return title; }
        public String priority() { return priority; }
        public Integer limit() { return limit; }
    }
}
