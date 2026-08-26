package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ApproveMemoryRequest;
import com.azhukov.agent.api.dto.ApprovalRequest;
import com.azhukov.agent.api.dto.MemoryDto;
import com.azhukov.agent.api.dto.PendingMemoryDto;
import com.azhukov.agent.api.dto.RejectMemoryRequest;
import com.azhukov.agent.api.dto.StoreMemoryRequest;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.service.AgentRuntimeService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 phase A — focused NEW unit tests for {@link MemoryController}.
 * Covers success, bad input, error, and edge paths via MockMvc + Mockito services.
 * Does NOT modify any existing tests.
 */
@ExtendWith(MockitoExtension.class)
class MemoryControllerT1Test {

    private static final UUID ENTRY_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant FIXED_TIME = Instant.parse("2024-01-01T00:00:00Z");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private MemoryProvider memoryProvider;
    @Mock private AgentRuntimeService agentRuntimeService;

    @BeforeEach
    void setUp() {
        MemoryController controller = new MemoryController(memoryProvider, agentRuntimeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    // ── memory() ──

    @Test
    void memory_returnsFacts() throws Exception {
        when(memoryProvider.recall("default", "", 100)).thenReturn(List.of("fact1", "fact2"));
        mockMvc.perform(get("/api/v1/agent/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("fact1"))
            .andExpect(jsonPath("$[1]").value("fact2"));
    }

    @Test
    void memory_emptyList() throws Exception {
        when(memoryProvider.recall("default", "", 100)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/agent/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── storeMemory() ──

    @Test
    void storeMemory_success_withAllFields() throws Exception {
        doNothing().when(memoryProvider).store("alice", "memory", "user", "fact");

        String body = objectMapper.writeValueAsString(new StoreMemoryRequest("alice", "fact", "user", "memory"));
        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        verify(memoryProvider).store("alice", "memory", "user", "fact");
    }

    @Test
    void storeMemory_nullUserId_defaultsToDefault() throws Exception {
        doNothing().when(memoryProvider).store("default", "memory", "user", "fact");

        String body = objectMapper.writeValueAsString(new StoreMemoryRequest(null, "fact", null, null));
        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        verify(memoryProvider).store("default", "memory", "user", "fact");
    }

    @Test
    void storeMemory_blankFact_returns400() throws Exception {
        String body = "{\"fact\":\"\"}";
        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void storeMemory_missingFact_returns400() throws Exception {
        String body = "{\"userId\":\"alice\"}";
        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void storeMemory_customCategoryAndTarget() throws Exception {
        doNothing().when(memoryProvider).store("alice", "notes", "tech", "fact");

        String body = objectMapper.writeValueAsString(new StoreMemoryRequest("alice", "fact", "tech", "notes"));
        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        verify(memoryProvider).store("alice", "notes", "tech", "fact");
    }

    @Test
    void storeMemory_serviceThrows_returns500() throws Exception {
        doThrow(new RuntimeException("storage fail"))
            .when(memoryProvider).store(any(String.class), any(), any(), any());

        String body = objectMapper.writeValueAsString(new StoreMemoryRequest("alice", "fact", null, null));
        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isInternalServerError());
    }

    // ── listPendingMemory() ──

    @Test
    void listPendingMemory_returnsList() throws Exception {
        PendingMemoryDto dto = new PendingMemoryDto(ENTRY_ID, "alice", "add", "memory", "content", null, "summary", null, "pending", FIXED_TIME, null);
        when(agentRuntimeService.listPendingMemory("alice")).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/agent/memory/pending/{userId}", "alice"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(ENTRY_ID.toString()))
            .andExpect(jsonPath("$[0].action").value("add"))
            .andExpect(jsonPath("$[0].status").value("pending"));
    }

    @Test
    void listPendingMemory_emptyList() throws Exception {
        when(agentRuntimeService.listPendingMemory("nobody")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/memory/pending/{userId}", "nobody"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── approveMemory() ──

    @Test
    void approveMemory_success_returnsTrue() throws Exception {
        ApproveMemoryRequest req = new ApproveMemoryRequest("alice", ENTRY_ID);
        when(agentRuntimeService.approvePendingMemory(any())).thenReturn(true);

        String body = objectMapper.writeValueAsString(req);
        mockMvc.perform(post("/api/v1/agent/memory/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("true"));
    }

    @Test
    void approveMemory_returnsFalse() throws Exception {
        when(agentRuntimeService.approvePendingMemory(any())).thenReturn(false);

        String body = "{\"userId\":\"alice\",\"id\":\"" + ENTRY_ID + "\"}";
        mockMvc.perform(post("/api/v1/agent/memory/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("false"));
    }

    // ── rejectMemory() ──

    @Test
    void rejectMemory_success_returnsTrue() throws Exception {
        when(agentRuntimeService.rejectPendingMemory(any())).thenReturn(true);

        String body = "{\"userId\":\"alice\",\"id\":\"" + ENTRY_ID + "\"}";
        mockMvc.perform(post("/api/v1/agent/memory/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("true"));
    }

    @Test
    void rejectMemory_returnsFalse() throws Exception {
        when(agentRuntimeService.rejectPendingMemory(any())).thenReturn(false);

        String body = "{\"userId\":\"alice\",\"id\":\"" + ENTRY_ID + "\"}";
        mockMvc.perform(post("/api/v1/agent/memory/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("false"));
    }

    // ── setApproval() / getApproval() ──

    @Test
    void setApproval_true() throws Exception {
        doNothing().when(agentRuntimeService).setMemoryApproval(true);

        String body = objectMapper.writeValueAsString(new ApprovalRequest(true));
        mockMvc.perform(post("/api/v1/agent/memory/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        verify(agentRuntimeService).setMemoryApproval(true);
    }

    @Test
    void setApproval_false() throws Exception {
        doNothing().when(agentRuntimeService).setMemoryApproval(false);

        String body = objectMapper.writeValueAsString(new ApprovalRequest(false));
        mockMvc.perform(post("/api/v1/agent/memory/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        verify(agentRuntimeService).setMemoryApproval(false);
    }

    @Test
    void getApproval_true() throws Exception {
        when(agentRuntimeService.isMemoryApprovalEnabled()).thenReturn(true);

        mockMvc.perform(get("/api/v1/agent/memory/approval"))
            .andExpect(status().isOk())
            .andExpect(content().string("true"));
    }

    @Test
    void getApproval_false() throws Exception {
        when(agentRuntimeService.isMemoryApprovalEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/v1/agent/memory/approval"))
            .andExpect(status().isOk())
            .andExpect(content().string("false"));
    }

    // ── listAllMemory() ──

    @Test
    void listAllMemory_returnsList() throws Exception {
        MemoryDto dto = new MemoryDto(ENTRY_ID, "alice", "user", "fact", "memory", FIXED_TIME);
        when(agentRuntimeService.listAllMemory("alice")).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/agent/memory/all/{userId}", "alice"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].fact").value("fact"))
            .andExpect(jsonPath("$[0].category").value("user"));
    }

    @Test
    void listAllMemory_emptyList() throws Exception {
        when(agentRuntimeService.listAllMemory("nobody")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/memory/all/{userId}", "nobody"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── deleteMemory() ──

    @Test
    void deleteMemory_success() throws Exception {
        doNothing().when(agentRuntimeService).deleteMemory("alice", ENTRY_ID);

        mockMvc.perform(delete("/api/v1/agent/memory/{userId}/{entryId}", "alice", ENTRY_ID))
            .andExpect(status().isOk());
        verify(agentRuntimeService).deleteMemory("alice", ENTRY_ID);
    }

    @Test
    void deleteMemory_serviceThrows_returnsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("entry not found"))
            .when(agentRuntimeService).deleteMemory("alice", ENTRY_ID);

        mockMvc.perform(delete("/api/v1/agent/memory/{userId}/{entryId}", "alice", ENTRY_ID))
            .andExpect(status().isBadRequest());
    }

    // (imports for matchers)
    private static final String _UNUSED = null;
}