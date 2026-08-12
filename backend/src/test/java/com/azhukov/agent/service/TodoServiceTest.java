package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.TodoDto;
import com.azhukov.agent.persistence.entity.TodoEntity;
import com.azhukov.agent.persistence.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @InjectMocks
    private TodoService todoService;

    @Test
    void listByUserId_returnsMappedDtos() {
        TodoEntity entity = new TodoEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId("user-1");
        entity.setTitle("Buy milk");
        entity.setStatus("pending");
        entity.setPriority("medium");
        entity.setCreatedAt(Instant.now());

        when(todoRepository.findByUserId("user-1")).thenReturn(List.of(entity));

        List<TodoDto> result = todoService.listByUserId("user-1");

        assertThat(result).hasSize(1);
        TodoDto dto = result.get(0);
        assertThat(dto.userId()).isEqualTo("user-1");
        assertThat(dto.title()).isEqualTo("Buy milk");
        assertThat(dto.status()).isEqualTo("pending");
        assertThat(dto.priority()).isEqualTo("medium");
    }

    @Test
    void listByUserId_emptyListReturnsEmpty() {
        when(todoRepository.findByUserId("user-1")).thenReturn(List.of());

        List<TodoDto> result = todoService.listByUserId("user-1");

        assertThat(result).isEmpty();
    }

    @Test
    void add_createsAndReturnsTodo() {
        TodoEntity saved = new TodoEntity();
        saved.setId(UUID.randomUUID());
        saved.setUserId("user-1");
        saved.setTitle("New task");
        saved.setStatus("pending");
        saved.setPriority("medium");
        saved.setCreatedAt(Instant.now());
        saved.setUpdatedAt(Instant.now());

        when(todoRepository.save(any(TodoEntity.class))).thenReturn(saved);

        TodoDto result = todoService.add("user-1", "New task");

        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo("user-1");
        assertThat(result.title()).isEqualTo("New task");
        assertThat(result.status()).isEqualTo("pending");
        assertThat(result.priority()).isEqualTo("medium");
    }

    @Test
    void add_nullTextThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> todoService.add("user-1", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("required");
    }

    @Test
    void add_blankTextThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> todoService.add("user-1", "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("required");
    }

    @Test
    void markDone_existingTodoUpdatesStatus() {
        UUID id = UUID.randomUUID();
        TodoEntity entity = new TodoEntity();
        entity.setId(id);
        entity.setUserId("user-1");
        entity.setTitle("Task");
        entity.setStatus("pending");
        entity.setPriority("medium");
        entity.setCreatedAt(Instant.now());

        when(todoRepository.findById(id)).thenReturn(Optional.of(entity));
        when(todoRepository.save(any(TodoEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<TodoDto> result = todoService.markDone(id);

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo("done");
    }

    @Test
    void markDone_nonExistentReturnsEmpty() {
        UUID id = UUID.randomUUID();
        when(todoRepository.findById(id)).thenReturn(Optional.empty());

        Optional<TodoDto> result = todoService.markDone(id);

        assertThat(result).isEmpty();
    }

    @Test
    void clearByUserId_delegatesToRepository() {
        todoService.clearByUserId("user-1");
        verify(todoRepository).deleteByUserId("user-1");
    }
}