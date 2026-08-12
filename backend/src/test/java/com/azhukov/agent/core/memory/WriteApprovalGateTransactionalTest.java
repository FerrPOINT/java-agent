package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.PendingMemoryEntity;
import com.azhukov.agent.persistence.repository.PendingMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M26: Test that WriteApprovalGate.approve() has @Transactional annotation.
 */
class WriteApprovalGateTransactionalTest {

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
    void approveMethodHasTransactionalAnnotation() throws Exception {
        Method approveMethod = WriteApprovalGate.class.getMethod("approve", String.class, UUID.class);
        assertThat(approveMethod.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
            .isTrue();
    }

    @Test
    void approveExecutesSuccessfullyWithTransactional() {
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
}