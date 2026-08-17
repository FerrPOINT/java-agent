package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.CheckpointDto;
import com.azhukov.agent.persistence.entity.CheckpointEntity;
import com.azhukov.agent.service.CheckpointManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link CheckpointController} — CRUD endpoints for checkpoints.
 * Verifies create, list, diff, restore, and delete operations.
 */
@ExtendWith(MockitoExtension.class)
class CheckpointControllerTest {

    private static final UUID CHECKPOINT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID CHECKPOINT_ID_2 = UUID.fromString("660e8400-e29b-41d4-a716-446655440001");
    private static final Instant FIXED_TIME = Instant.parse("2024-01-01T00:00:00Z");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private CheckpointManager checkpointManager;

    @BeforeEach
    void setUp() {
        CheckpointController controller = new CheckpointController(checkpointManager);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void createCheckpointReturnsDto() throws Exception {
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(CHECKPOINT_ID);
        entity.setDescription("Manual checkpoint");
        entity.setFileCount(5);
        entity.setTotalSizeBytes(1024L);
        entity.setCreatedAt(FIXED_TIME);
        when(checkpointManager.snapshot(any())).thenReturn(entity);

        mockMvc.perform(post("/api/v1/agent/checkpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Manual checkpoint\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(CHECKPOINT_ID.toString()))
            .andExpect(jsonPath("$.description").value("Manual checkpoint"))
            .andExpect(jsonPath("$.fileCount").value(5))
            .andExpect(jsonPath("$.totalSizeBytes").value(1024));
    }

    @Test
    void createCheckpointWithNoBodyUsesDefaultDescription() throws Exception {
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(CHECKPOINT_ID);
        entity.setDescription("Manual checkpoint");
        entity.setFileCount(0);
        entity.setTotalSizeBytes(0L);
        entity.setCreatedAt(FIXED_TIME);
        when(checkpointManager.snapshot(any())).thenReturn(entity);

        mockMvc.perform(post("/api/v1/agent/checkpoint"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(CHECKPOINT_ID.toString()));
    }

    @Test
    void listCheckpointsReturnsAll() throws Exception {
        CheckpointEntity entity1 = new CheckpointEntity();
        entity1.setId(CHECKPOINT_ID);
        entity1.setDescription("First checkpoint");
        entity1.setFileCount(3);
        entity1.setTotalSizeBytes(512L);
        entity1.setCreatedAt(FIXED_TIME);

        CheckpointEntity entity2 = new CheckpointEntity();
        entity2.setId(CHECKPOINT_ID_2);
        entity2.setDescription("Second checkpoint");
        entity2.setFileCount(10);
        entity2.setTotalSizeBytes(2048L);
        entity2.setCreatedAt(FIXED_TIME.plusSeconds(60));

        when(checkpointManager.list()).thenReturn(List.of(entity1, entity2));

        mockMvc.perform(get("/api/v1/agent/checkpoint"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(CHECKPOINT_ID.toString()))
            .andExpect(jsonPath("$[0].description").value("First checkpoint"))
            .andExpect(jsonPath("$[0].fileCount").value(3))
            .andExpect(jsonPath("$[1].id").value(CHECKPOINT_ID_2.toString()))
            .andExpect(jsonPath("$[1].description").value("Second checkpoint"))
            .andExpect(jsonPath("$[1].fileCount").value(10));
    }

    @Test
    void listCheckpointsEmptyReturnsEmptyArray() throws Exception {
        when(checkpointManager.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/checkpoint"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void diffReturnsCheckpointDiffDto() throws Exception {
        ObjectNode diffNode = objectMapper.createObjectNode();
        diffNode.set("changed", objectMapper.createArrayNode());
        diffNode.set("added", objectMapper.createArrayNode());
        diffNode.set("removed", objectMapper.createArrayNode());
        diffNode.put("leftFileCount", 5);
        diffNode.put("rightFileCount", 7);

        when(checkpointManager.diff(eq(CHECKPOINT_ID), eq(CHECKPOINT_ID_2), eq("context")))
            .thenReturn(diffNode);

        mockMvc.perform(get("/api/v1/agent/diff")
                .param("left", CHECKPOINT_ID.toString())
                .param("right", CHECKPOINT_ID_2.toString())
                .param("scope", "context"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.left").value(CHECKPOINT_ID.toString()))
            .andExpect(jsonPath("$.right").value(CHECKPOINT_ID_2.toString()))
            .andExpect(jsonPath("$.scope").value("context"))
            .andExpect(jsonPath("$.leftFileCount").value(5))
            .andExpect(jsonPath("$.rightFileCount").value(7));
    }

    @Test
    void restoreCheckpointReturnsMessage() throws Exception {
        doNothing().when(checkpointManager).restore(CHECKPOINT_ID);

        mockMvc.perform(post("/api/v1/agent/checkpoint/{id}/restore", CHECKPOINT_ID))
            .andExpect(status().isOk())
            .andExpect(content().string("Checkpoint restored: " + CHECKPOINT_ID));
    }

    @Test
    void deleteCheckpointReturnsVoid() throws Exception {
        doNothing().when(checkpointManager).remove(CHECKPOINT_ID);

        mockMvc.perform(delete("/api/v1/agent/checkpoint/{id}", CHECKPOINT_ID))
            .andExpect(status().isOk());
    }
}