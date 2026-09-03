package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.PendingMemoryEntity;
import com.azhukov.agent.persistence.repository.PendingMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WriteApprovalGateTest {

    private PendingMemoryRepository pendingRepo;
    private MemoryProvider memoryProvider;
    private AgentProperties properties;
    private WriteApprovalGate gate;

    @BeforeEach
    void setUp() {
        pendingRepo = mock(PendingMemoryRepository.class);
        memoryProvider = mock(MemoryProvider.class);
        properties = mock(AgentProperties.class);
        AgentProperties.MemoryProperties memProps = mock(AgentProperties.MemoryProperties.class);
        when(memProps.isWriteApproval()).thenReturn(false);
        when(properties.getMemory()).thenReturn(memProps);

        gate = new WriteApprovalGate(pendingRepo, memoryProvider, properties);
        gate.init();
    }

    @Test
    void isEnabled_defaultFalse() {
        assertThat(gate.isEnabled()).isFalse();
    }

    @Test
    void setApproval_toggle() {
        gate.setApproval(true);
        assertThat(gate.isEnabled()).isTrue();
        gate.setApproval(false);
        assertThat(gate.isEnabled()).isFalse();
    }

    @Test
    void stageWrite_savesToPendingRepo() {
        when(pendingRepo.save(any())).thenAnswer(inv -> {
            PendingMemoryEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        UUID id = gate.stageWrite("user1", "add", "memory", "Test fact", null, "summary", "foreground");
        assertThat(id).isNotNull();
        verify(pendingRepo).save(any());
    }

    @Test
    void listPending_returnsPendingEntries() {
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setUserId("user1");
        e.setStatus("pending");
        e.setAction("add");
        when(pendingRepo.findByUserIdAndStatus("user1", "pending")).thenReturn(List.of(e));
        var result = gate.listPending("user1");
        assertThat(result).hasSize(1);
    }

    @Test
    void approve_appliesAddToMemory() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setStatus("pending");
        e.setAction("add");
        e.setTarget("memory");
        e.setContent("Test fact");
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(pendingRepo.save(any())).thenReturn(e);

        boolean result = gate.approve("user1", id);
        assertThat(result).isTrue();
        verify(memoryProvider).store("user1", "memory", "auto", "Test fact");
        assertThat(e.getStatus()).isEqualTo("approved");
    }

    @Test
    void approve_appliesRemoveToMemory() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setStatus("pending");
        e.setAction("remove");
        e.setTarget("memory");
        e.setOldText("old fact");
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(pendingRepo.save(any())).thenReturn(e);

        boolean result = gate.approve("user1", id);
        assertThat(result).isTrue();
        verify(memoryProvider).remove("user1", "memory", "old fact");
    }

    @Test
    void approve_replaceFailureDoesNotMarkApproved() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setStatus("pending");
        e.setAction("replace");
        e.setTarget("memory");
        e.setOldText("old fact");
        e.setContent("new fact");
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(memoryProvider.replace("user1", "memory", "old fact", "new fact")).thenReturn("No unique match");

        boolean result = gate.approve("user1", id);

        assertThat(result).isFalse();
        assertThat(e.getStatus()).isEqualTo("pending");
        assertThat(e.getResolvedAt()).isNull();
        verify(pendingRepo, never()).save(any());
    }

    @Test
    void approve_removeFailureDoesNotMarkApproved() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setStatus("pending");
        e.setAction("remove");
        e.setTarget("memory");
        e.setOldText("old fact");
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(memoryProvider.remove("user1", "memory", "old fact")).thenReturn("No unique match");

        boolean result = gate.approve("user1", id);

        assertThat(result).isFalse();
        assertThat(e.getStatus()).isEqualTo("pending");
        assertThat(e.getResolvedAt()).isNull();
        verify(pendingRepo, never()).save(any());
    }

    @Test
    void approve_appliesBatchToMemory() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setStatus("pending");
        e.setAction("batch");
        e.setTarget("memory");
        e.setOrigin("foreground");
        e.setContent("""
            [
              {"action":"replace","old_text":"old fact","new_text":"new fact"},
              {"action":"add","content":"extra fact"}
            ]
            """);
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(pendingRepo.save(any())).thenReturn(e);

        boolean result = gate.approve("user1", id);

        assertThat(result).isTrue();
        verify(memoryProvider).applyBatch(eq("user1"), eq("memory"), argThat(operations ->
            operations.size() == 2
                && "replace".equals(operations.get(0).action())
                && "new fact".equals(operations.get(0).content())
                && "old fact".equals(operations.get(0).oldText())
                && "add".equals(operations.get(1).action())
                && "extra fact".equals(operations.get(1).content())
                && operations.get(1).oldText() == null
        ), argThat(provenance ->
            id.toString().equals(provenance.get("approved_pending_id"))
                && "foreground".equals(provenance.get("origin"))
        ));
        assertThat(e.getStatus()).isEqualTo("approved");
    }

    @Test
    void approve_unknownActionDoesNotMarkApproved() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setStatus("pending");
        e.setAction("unknown");
        e.setTarget("memory");
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));

        boolean result = gate.approve("user1", id);

        assertThat(result).isFalse();
        assertThat(e.getStatus()).isEqualTo("pending");
        assertThat(e.getResolvedAt()).isNull();
        verify(pendingRepo, never()).save(any());
        verifyNoInteractions(memoryProvider);
    }

    @Test
    void approve_batchFailureDoesNotMarkApproved() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setStatus("pending");
        e.setAction("batch");
        e.setTarget("memory");
        e.setContent("[{\"action\":\"add\",\"content\":\"new fact\"}]");
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(memoryProvider.applyBatch(eq("user1"), eq("memory"), anyList(), anyMap()))
            .thenReturn("Memory full");

        boolean result = gate.approve("user1", id);

        assertThat(result).isFalse();
        assertThat(e.getStatus()).isEqualTo("pending");
        assertThat(e.getResolvedAt()).isNull();
        verify(pendingRepo, never()).save(any());
    }

    @Test
    void reject_marksAsRejected() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setStatus("pending");
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(pendingRepo.save(any())).thenReturn(e);

        boolean result = gate.reject("user1", id);
        assertThat(result).isTrue();
        assertThat(e.getStatus()).isEqualTo("rejected");
        verify(memoryProvider, never()).store(any(), any(), any(), any());
    }

    @Test
    void approve_notFound_returnsFalse() {
        UUID id = UUID.randomUUID();
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.empty());
        assertThat(gate.approve("user1", id)).isFalse();
    }

    @Test
    void approve_alreadyResolved_returnsFalse() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setStatus("approved");
        when(pendingRepo.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        assertThat(gate.approve("user1", id)).isFalse();
    }
}
