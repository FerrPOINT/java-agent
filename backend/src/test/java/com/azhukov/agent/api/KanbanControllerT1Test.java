package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.KanbanAddRequest;
import com.azhukov.agent.api.dto.TodoDto;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.TodoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 phase A — focused NEW unit tests for {@link KanbanController}.
 * Covers success, bad input, error, and edge paths via MockMvc + Mockito services.
 * Does NOT modify any existing tests.
 */
@ExtendWith(MockitoExtension.class)
class KanbanControllerT1Test {

    private static final UUID TODO_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant FIXED_TIME = Instant.parse("2024-01-01T00:00:00Z");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private TodoService todoService;

    @BeforeEach
    void setUp() {
        KanbanController controller = new KanbanController(todoService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    // ── getKanban() ──

    @Test
    void getKanban_returnsList() throws Exception {
        TodoDto dto = new TodoDto(TODO_ID, null, "default", "task1", "pending", "high", FIXED_TIME);
        when(todoService.listByUserId("default")).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/agent/kanban"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("task1"))
            .andExpect(jsonPath("$[0].userId").value("default"))
            .andExpect(jsonPath("$[0].status").value("pending"));
    }

    @Test
    void getKanban_emptyList() throws Exception {
        when(todoService.listByUserId("default")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/kanban"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getKanban_serviceThrows_returns500() throws Exception {
        when(todoService.listByUserId("default")).thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get("/api/v1/agent/kanban"))
            .andExpect(status().isInternalServerError());
    }

    // ── addKanbanItem() ──

    @Test
    void addKanbanItem_success() throws Exception {
        TodoDto dto = new TodoDto(TODO_ID, null, "default", "new task", "pending", null, FIXED_TIME);
        when(todoService.add("default", "new task")).thenReturn(dto);

        String body = objectMapper.writeValueAsString(new KanbanAddRequest("new task"));
        mockMvc.perform(post("/api/v1/agent/kanban/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("new task"))
            .andExpect(jsonPath("$.id").value(TODO_ID.toString()));
    }

    @Test
    void addKanbanItem_blankText_returns400() throws Exception {
        String body = "{\"text\":\"\"}";
        mockMvc.perform(post("/api/v1/agent/kanban/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
        verify(todoService, never()).add(any(String.class), any());
    }

    @Test
    void addKanbanItem_missingText_returns400() throws Exception {
        String body = "{}";
        mockMvc.perform(post("/api/v1/agent/kanban/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void addKanbanItem_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/kanban/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{bad json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void addKanbanItem_serviceThrows_returns500() throws Exception {
        when(todoService.add(eq("default"), any())).thenThrow(new RuntimeException("persist error"));

        String body = objectMapper.writeValueAsString(new KanbanAddRequest("task"));
        mockMvc.perform(post("/api/v1/agent/kanban/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isInternalServerError());
    }

    // ── completeKanbanItem() ──

    @Test
    void completeKanbanItem_found_returnsOk() throws Exception {
        TodoDto dto = new TodoDto(TODO_ID, null, "default", "task", "done", null, FIXED_TIME);
        when(todoService.markDoneForUser(eq(TODO_ID), anyString())).thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/v1/agent/kanban/done/{id}", TODO_ID))
            .andExpect(status().isOk());
    }

    @Test
    void completeKanbanItem_notFound_returns404() throws Exception {
        when(todoService.markDoneForUser(eq(TODO_ID), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/agent/kanban/done/{id}", TODO_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void completeKanbanItem_invalidUuid_returnsError() throws Exception {
        // Invalid UUID in path variable triggers a conversion error.
        // With GlobalExceptionHandler in standalone setup, TypeMismatchException
        // falls through to the generic 500 handler (no specific handler registered).
        mockMvc.perform(post("/api/v1/agent/kanban/done/{id}", "not-a-uuid"))
            .andExpect(status().is5xxServerError());
    }

    @Test
    void completeKanbanItem_serviceThrows_returns500() throws Exception {
        when(todoService.markDoneForUser(eq(TODO_ID), anyString())).thenThrow(new RuntimeException("fail"));

        mockMvc.perform(post("/api/v1/agent/kanban/done/{id}", TODO_ID))
            .andExpect(status().isInternalServerError());
    }

    // ── deleteKanbanItem() ──

    @Test
    void deleteKanbanItem_success() throws Exception {
        doNothing().when(todoService).clearByUserId("default");

        mockMvc.perform(delete("/api/v1/agent/kanban"))
            .andExpect(status().isOk());
        verify(todoService).clearByUserId("default");
    }

    @Test
    void deleteKanbanItem_serviceThrows_returns500() throws Exception {
        doThrow(new RuntimeException("clear fail")).when(todoService).clearByUserId("default");

        mockMvc.perform(delete("/api/v1/agent/kanban"))
            .andExpect(status().isInternalServerError());
    }


    // ── multi-user ownership ──

    @Test
    void getKanban_scopesToAuthenticatedUser() throws Exception {
        UserContext.set("user-77", UserContext.ROLE_USER);
        try {
            when(todoService.listByUserId("user-77")).thenReturn(List.of());
            mockMvc.perform(get("/api/v1/agent/kanban"))
                .andExpect(status().isOk());
            verify(todoService).listByUserId("user-77");
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void addKanbanItem_scopesToAuthenticatedUser() throws Exception {
        UserContext.set("user-77", UserContext.ROLE_USER);
        try {
            TodoDto dto = new TodoDto(TODO_ID, null, "user-77", "task", "pending", "medium", FIXED_TIME);
            when(todoService.add("user-77", "task")).thenReturn(dto);
            mockMvc.perform(post("/api/v1/agent/kanban/add")
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(new KanbanAddRequest("task"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-77"));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void markDone_otherUsersTask_returns403() throws Exception {
        when(todoService.markDoneForUser(eq(TODO_ID), anyString())).thenThrow(
            new SecurityException("Task does not belong to the current user"));
        mockMvc.perform(post("/api/v1/agent/kanban/done/" + TODO_ID))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("forbidden"));
    }

}
