package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.TodoDto;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryScope;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import com.azhukov.agent.service.TodoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KanbanControllerProfileTest {

    @TempDir
    private Path tempDir;

    private TodoService todoService;
    private MockMvc mockMvc;
    private String workTodoUser;

    @BeforeEach
    void setUp() throws Exception {
        todoService = mock(TodoService.class);
        AgentProperties properties = new AgentProperties();
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        ProfileService profileService = new ProfileService(properties, new RuntimeConfigService());
        profileService.createProfile(new ProfileService.CreateProfileRequest(
            "work", null, false, false, true, null, null, null, null));
        workTodoUser = MemoryScope.userId("default", "work");
        mockMvc = MockMvcBuilders.standaloneSetup(new KanbanController(todoService, profileService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void profilePrefixedKanbanRoutesUseScopedTodoUser() throws Exception {
        UUID id = UUID.randomUUID();
        TodoDto todo = new TodoDto(id, null, workTodoUser, "work task", "pending", "medium", Instant.now());
        when(todoService.listByUserId(workTodoUser)).thenReturn(List.of(todo));
        when(todoService.add(eq(workTodoUser), eq("new work task"))).thenReturn(
            new TodoDto(id, null, workTodoUser, "new work task", "pending", "medium", Instant.now()));
        when(todoService.markDoneForUser(id, workTodoUser)).thenReturn(Optional.of(todo));
        doNothing().when(todoService).clearByUserId(workTodoUser);

        mockMvc.perform(get("/p/work/api/v1/agent/kanban"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].userId").value(workTodoUser))
            .andExpect(jsonPath("$[0].title").value("work task"));

        mockMvc.perform(post("/p/work/api/v1/agent/kanban/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"new work task\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(workTodoUser));

        mockMvc.perform(post("/p/work/api/v1/agent/kanban/done/" + id))
            .andExpect(status().isOk());

        mockMvc.perform(delete("/p/work/api/v1/agent/kanban"))
            .andExpect(status().isOk());

        verify(todoService).listByUserId(workTodoUser);
        verify(todoService).add(workTodoUser, "new work task");
        verify(todoService).markDoneForUser(id, workTodoUser);
        verify(todoService).clearByUserId(workTodoUser);
    }

    @Test
    void profilePrefixedKanbanRoutesFailClosedForUnknownAndMismatchedProfiles() throws Exception {
        mockMvc.perform(get("/p/ghost/api/v1/agent/kanban"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown profile: ghost"));

        mockMvc.perform(get("/p/work/api/v1/agent/kanban?profile=default"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("profile values do not match"));

        mockMvc.perform(get("/api/v1/agent/kanban?profile=all"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("profile=all is not supported for kanban"));
    }
}
