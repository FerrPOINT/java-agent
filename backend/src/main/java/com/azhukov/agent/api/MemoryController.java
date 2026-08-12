package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ApproveMemoryRequest;
import com.azhukov.agent.api.dto.ApprovalRequest;
import com.azhukov.agent.api.dto.MemoryDto;
import com.azhukov.agent.api.dto.PendingMemoryDto;
import com.azhukov.agent.api.dto.RejectMemoryRequest;
import com.azhukov.agent.api.dto.StoreMemoryRequest;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.service.AgentRuntimeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Memory", description = "Memory storage, pending memory approval, CRUD")
public class MemoryController {

    private final MemoryProvider memoryProvider;
    private final AgentRuntimeService agentRuntimeService;

    @Operation(summary = "Recall memory entries for the default user")
    @GetMapping("/agent/memory")
    public List<String> memory() {
        return memoryProvider.recall("default", "", 100);
    }

    @Operation(summary = "Store a new memory entry")
    @PostMapping("/agent/memory")
    public void storeMemory(@Valid @RequestBody StoreMemoryRequest body) {
        String userId = body.userId() != null ? body.userId() : "default";
        String fact = body.fact();
        String category = body.category() != null ? body.category() : "user";
        String target = body.target() != null ? body.target() : "memory";
        memoryProvider.store(userId, target, category, fact);
    }

    // ── Memory management endpoints (Stage 6.1-6.6) ──

    @GetMapping("/agent/memory/pending/{userId}")
    public List<PendingMemoryDto> listPendingMemory(@PathVariable String userId) {
        return agentRuntimeService.listPendingMemory(userId);
    }

    @PostMapping("/agent/memory/approve")
    public boolean approveMemory(@RequestBody ApproveMemoryRequest request) {
        return agentRuntimeService.approvePendingMemory(request);
    }

    @PostMapping("/agent/memory/reject")
    public boolean rejectMemory(@RequestBody RejectMemoryRequest request) {
        return agentRuntimeService.rejectPendingMemory(request);
    }

    @PostMapping("/agent/memory/approval")
    public void setApproval(@RequestBody ApprovalRequest request) {
        agentRuntimeService.setMemoryApproval(request.enabled());
    }

    @GetMapping("/agent/memory/approval")
    public boolean getApproval() {
        return agentRuntimeService.isMemoryApprovalEnabled();
    }

    @GetMapping("/agent/memory/all/{userId}")
    public List<MemoryDto> listAllMemory(@PathVariable String userId) {
        return agentRuntimeService.listAllMemory(userId);
    }

    @DeleteMapping("/agent/memory/{userId}/{entryId}")
    public void deleteMemory(@PathVariable String userId, @PathVariable UUID entryId) {
        agentRuntimeService.deleteMemory(userId, entryId);
    }
}