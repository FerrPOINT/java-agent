package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.CheckpointDiffDto;
import com.azhukov.agent.api.dto.CheckpointDto;
import com.azhukov.agent.api.mapper.CheckpointDtoMapper;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.CheckpointManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Checkpoints", description = "Checkpoint creation, listing, diff, restore, delete")
public class CheckpointController {

    private final CheckpointManager checkpointManager;
    private final CheckpointDtoMapper checkpointDtoMapper;

    @Operation(summary = "Create a checkpoint snapshot")
    @PostMapping("/agent/checkpoint")
    public CheckpointDto createCheckpoint(@RequestBody(required = false) CheckpointRequest request) {
        String description = request != null ? request.description() : "Manual checkpoint";
        return checkpointDtoMapper.toDto(checkpointManager.snapshot(UserContext.getUserId(), description));
    }

    @Operation(summary = "List all checkpoints")
    @GetMapping("/agent/checkpoint")
    public List<CheckpointDto> listCheckpoints() {
        return checkpointDtoMapper.toDtoList(checkpointManager.list(UserContext.scopeUserId()));
    }

    @Operation(summary = "Diff two checkpoints")
    @GetMapping("/agent/diff")
    public CheckpointDiffDto checkpointDiff(@RequestParam UUID left, @RequestParam UUID right,
                                            @RequestParam(defaultValue = "context") String scope) {
        JsonNode node = checkpointManager.diff(left, right, scope);
        return new CheckpointDiffDto(
            left,
            right,
            scope,
            node.get("changed"),
            node.get("added"),
            node.get("removed"),
            node.has("leftFileCount") ? node.get("leftFileCount").asInt() : 0,
            node.has("rightFileCount") ? node.get("rightFileCount").asInt() : 0,
            node.has("error") ? node.get("error").asText() : null
        );
    }

    @PostMapping("/agent/checkpoint/{id}/restore")
    public String restoreCheckpoint(@PathVariable UUID id) {
        checkpointManager.restore(id);
        return "Checkpoint restored: " + id;
    }

    @DeleteMapping("/agent/checkpoint/{id}")
    public void deleteCheckpoint(@PathVariable UUID id) {
        checkpointManager.remove(id);
    }

    public record CheckpointRequest(String description) {}
}