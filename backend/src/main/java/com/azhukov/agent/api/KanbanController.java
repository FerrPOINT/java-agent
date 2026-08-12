package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.KanbanAddRequest;
import com.azhukov.agent.api.dto.TodoDto;
import com.azhukov.agent.service.TodoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Kanban", description = "Todo/kanban board management")
public class KanbanController {

    private final TodoService todoService;

    @Operation(summary = "List all kanban/todo items")
    @GetMapping("/agent/kanban")
    public List<TodoDto> getKanban() {
        return todoService.listByUserId("default");
    }

    @Operation(summary = "Add a new kanban/todo item")
    @PostMapping("/agent/kanban/add")
    public TodoDto addKanbanItem(@Valid @RequestBody KanbanAddRequest body) {
        String text = body.text();
        return todoService.add("default", text);
    }

    @PostMapping("/agent/kanban/done/{id}")
    public ResponseEntity<Void> completeKanbanItem(@PathVariable UUID id) {
        return todoService.markDone(id)
            .map(dto -> ResponseEntity.ok().<Void>build())
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/agent/kanban")
    public ResponseEntity<Void> deleteKanbanItem() {
        todoService.clearByUserId("default");
        return ResponseEntity.ok().build();
    }
}