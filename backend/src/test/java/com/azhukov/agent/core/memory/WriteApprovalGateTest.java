package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.PendingMemoryEntity;
import com.azhukov.agent.persistence.repository.PendingMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WriteApprovalGateTest {

    @Mock
    private PendingMemoryRepository pendingRepository;
    @Mock
    private MemoryProvider memoryProvider;

    private AgentProperties properties;
    private WriteApprovalGate gate;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        gate = new WriteApprovalGate(pendingRepository, memoryProvider, properties);
    }

    // 1. Disabled by default
    @Test
    void disabledByDefault() {
        assertThat(gate.isEnabled()).isFalse();
    }

    // 2. Set approval toggles gate
    @Test
    void setApprovalTogglesGate() {
        gate.setApproval(true);
        assertThat(gate.isEnabled()).isTrue();
        gate.setApproval(false);
        assertThat(gate.isEnabled()).isFalse();
    }

    // 3. Stage write creates pending entity
    @Test
    void stageWriteCreatesPendingEntity() {
        PendingMemoryEntity saved = new PendingMemoryEntity();
        saved.setId(UUID.randomUUID());
        when(pendingRepository.save(any())).thenReturn(saved);

        UUID id = gate.stageWrite("user1", "add", "memory", "test fact", null, "test summary", "foreground");

        assertThat(id).isNotNull();
        ArgumentCaptor<PendingMemoryEntity> captor = ArgumentCaptor.forClass(PendingMemoryEntity.class);
        verify(pendingRepository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("add");
        assertThat(captor.getValue().getTarget()).isEqualTo("memory");
        assertThat(captor.getValue().getContent()).isEqualTo("test fact");
        assertThat(captor.getValue().getStatus()).isEqualTo("pending");
    }

    // 4. List pending returns pending entries
    @Test
    void listPendingReturnsEntries() {
        PendingMemoryEntity e1 = new PendingMemoryEntity();
        e1.setUserId("user1");
        e1.setStatus("pending");
        e1.setAction("add");
        when(pendingRepository.findByUserIdAndStatus("user1", "pending"))
            .thenReturn(List.of(e1));

        List<PendingMemoryEntity> result = gate.listPending("user1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAction()).isEqualTo("add");
    }

    // 5. Approve applies write via memoryProvider
    @Test
    void approveAppliesWrite() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setAction("add");
        e.setTarget("memory");
        e.setContent("test fact");
        e.setStatus("pending");
        when(pendingRepository.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(pendingRepository.save(any())).thenReturn(e);

        boolean result = gate.approve("user1", id);

        assertThat(result).isTrue();
        verify(memoryProvider).store("user1", "memory", "auto", "test fact");
        assertThat(e.getStatus()).isEqualTo("approved");
        assertThat(e.getResolvedAt()).isNotNull();
    }

    // 6. Approve replace action calls replace
    @Test
    void approveReplaceAction() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setAction("replace");
        e.setTarget("memory");
        e.setContent("new fact");
        e.setOldText("old");
        e.setStatus("pending");
        when(pendingRepository.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(pendingRepository.save(any())).thenReturn(e);

        boolean result = gate.approve("user1", id);
        assertThat(result).isTrue();
        verify(memoryProvider).replace("user1", "memory", "old", "new fact");
    }

    // 7. Reject sets status to rejected
    @Test
    void rejectSetsStatus() {
        UUID id = UUID.randomUUID();
        PendingMemoryEntity e = new PendingMemoryEntity();
        e.setId(id);
        e.setUserId("user1");
        e.setAction("add");
        e.setStatus("pending");
        when(pendingRepository.findByIdAndUserId(id, "user1")).thenReturn(Optional.of(e));
        when(pendingRepository.save(any())).thenReturn(e);

        boolean result = gate.reject("user1", id);
        assertThat(result).isTrue();
        assertThat(e.getStatus()).isEqualTo("rejected");
        assertThat(e.getResolvedAt()).isNotNull();
    }

    // 8. Approve returns false for non-existent ID
    @Test
    void approveReturnsFalseForMissingId() {
        UUID id = UUID.randomUUID();
        when(pendingRepository.findByIdAndUserId(id, "user1")).thenReturn(Optional.empty());

        boolean result = gate.approve("user1", id);
        assertThat(result).isFalse();
        verifyNoInteractions(memoryProvider);
    }
}