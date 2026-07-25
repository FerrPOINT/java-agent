package com.azhukov.agent.core.audit;

import com.azhukov.agent.persistence.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("noop")
class AuditLogServiceTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository repository;

    @Test
    void persistsAuditEntry() {
        repository.deleteAll();
        auditLogService.log("session-1", "user", "CHAT", "/api/v1/agent/chat", "message received");
        assertThat(auditLogService.findAll()).hasSize(1);
    }
}
