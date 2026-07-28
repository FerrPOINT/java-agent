package com.azhukov.agent.core.audit;

import com.azhukov.agent.persistence.entity.AuditLogEntity;
import com.azhukov.agent.persistence.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuditLogServiceTest {

    @Test
    void logDelegatesToRepository() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AuditLogService service = new AuditLogService(repository);
        service.log("s1", "user", "tool", "event", "detail");
        verify(repository).save(any(AuditLogEntity.class));
    }

    @Test
    void findAllReturnsRepositoryResult() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AuditLogService service = new AuditLogService(repository);
        when(repository.findAll()).thenReturn(List.of(new AuditLogEntity()));
        assertThat(service.findAll()).hasSize(1);
    }
}
