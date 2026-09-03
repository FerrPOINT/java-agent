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
import com.azhukov.agent.core.security.UserContext;
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
        return memoryProvider.recall(currentUser(), "", 100);
    }

    @Operation(summary = "Store a new memory entry")
    @PostMapping("/agent/memory")
    public void storeMemory(@Valid @RequestBody StoreMemoryRequest body) {
        String userId = UserContext.effectiveUserId(body.userId());
        if (userId == null) userId = "default";
        String fact = body.fact();
        String category = body.category() != null ? body.category() : "user";
        String target = body.target() != null ? body.target() : "memory";
        memoryProvider.store(userId, target, category, fact);
    }

    // ── Memory management endpoints (Stage 6.1-6.6) ──

    @GetMapping("/agent/memory/pending/{userId}")
    public List<PendingMemoryDto> listPendingMemory(@PathVariable String userId) {
        return agentRuntimeService.listPendingMemory(UserContext.effectiveUserId(userId));
    }

    @PostMapping("/agent/memory/approve")
    public boolean approveMemory(@RequestBody ApproveMemoryRequest request) {
        return agentRuntimeService.approvePendingMemory(scope(request));
    }

    @PostMapping("/agent/memory/reject")
    public boolean rejectMemory(@RequestBody RejectMemoryRequest request) {
        return agentRuntimeService.rejectPendingMemory(scope(request));
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
        return agentRuntimeService.listAllMemory(UserContext.effectiveUserId(userId));
    }

    @DeleteMapping("/agent/memory/{userId}/{entryId}")
    public void deleteMemory(@PathVariable String userId, @PathVariable UUID entryId) {
        String effectiveUserId = UserContext.effectiveUserId(userId);
        agentRuntimeService.deleteMemory(effectiveUserId, entryId);
    }

    /**
     * Scoped user for memory reads: authenticated user's own id, "default"
     * in dev/no-auth mode.
     */
    private static String currentUser() {
        String scoped = UserContext.scopeUserId();
        return scoped != null ? scoped : "default";
    }

    /**
     * Rewrite the request's userId so a non-admin key cannot approve/reject
     * another user's pending memory writes. Admins keep the requested id.
     */
    private static ApproveMemoryRequest scope(ApproveMemoryRequest request) {
        if (request == null) return null;
        String scoped = UserContext.effectiveUserId(request.userId());
        return new ApproveMemoryRequest(scoped, request.id());
    }

    private static RejectMemoryRequest scope(RejectMemoryRequest request) {
        if (request == null) return null;
        String scoped = UserContext.effectiveUserId(request.userId());
        return new RejectMemoryRequest(scoped, request.id());
    }
}