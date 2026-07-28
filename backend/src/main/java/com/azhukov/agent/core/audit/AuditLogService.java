package com.azhukov.agent.core.audit;

import com.azhukov.agent.persistence.entity.AuditLogEntity;
import com.azhukov.agent.persistence.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository repository;

    public void log(String sessionId, String actor, String action, String resource, String details) {
        repository.save(new AuditLogEntity(sessionId, actor, action, resource, details));
    }

    public List<AuditLogEntity> findAll() {
        return repository.findAll();
    }
}
