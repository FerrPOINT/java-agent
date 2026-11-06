package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.TodoDto;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class TodoService {

    private final TodoRepository todoRepository;

    public List<TodoDto> listByUserId(String userId) {
        return todoRepository.findByUserId(userId).stream()
            .map(TodoService::toDto)
            .toList();
    }

    public List<TodoDto> listBySessionId(UUID sessionId) {
        return todoRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
            .map(TodoService::toDto)
            .toList();
    }

    public TodoDto add(String userId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Task text is required");
        }
        todoRepository.lockUserList(userId);
        TodoEntity todo = new TodoEntity();
        todo.setUserId(userId);
        todo.setTitle(text);
        todo.setStatus("pending");
        todo.setPriority("medium");
        todo.setCreatedAt(Instant.now());
        todo.setUpdatedAt(Instant.now());
        return toDto(todoRepository.save(todo));
    }

    public Optional<TodoDto> markDone(UUID id) {
        return todoRepository.findOwnerId(id)
            .flatMap(owner -> {
                String scoped = com.azhukov.agent.core.security.UserContext.scopeUserId();
                if (scoped != null && !scoped.equals(owner)) {
                    throw new SecurityException("Task does not belong to the current user");
                }
                todoRepository.lockUserList(owner);
                return todoRepository.findOwnedForUpdate(id, owner);
            }).map(todo -> {
                todo.setStatus("completed");
                todo.setUpdatedAt(Instant.now());
                return toDto(todoRepository.save(todo));
            });
    }

    public Optional<TodoDto> markDoneForUser(UUID id, String userId) {
        if (userId == null) return Optional.empty();
        todoRepository.lockUserList(userId);
        return todoRepository.findOwnedForUpdate(id, userId)
            .map(todo -> {
                todo.setStatus("completed");
                todo.setUpdatedAt(Instant.now());
                return toDto(todoRepository.save(todo));
            });
    }

    public void clearByUserId(String userId) {
        todoRepository.lockUserList(userId);
        todoRepository.deleteByUserId(userId);
    }

    private static TodoDto toDto(TodoEntity entity) {
        return new TodoDto(
            entity.getId(),
            entity.getSessionId(),
            entity.getUserId(),
            entity.getTitle(),
            entity.getStatus(),
            entity.getPriority(),
            entity.getCreatedAt()
        );
    }
}
