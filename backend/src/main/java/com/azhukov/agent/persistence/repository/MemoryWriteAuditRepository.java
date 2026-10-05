package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.MemoryWriteAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MemoryWriteAuditRepository extends JpaRepository<MemoryWriteAuditEntity, Long> {

    List<MemoryWriteAuditEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<MemoryWriteAuditEntity> findBySourceSessionIdOrderByCreatedAtDesc(String sourceSessionId);
}
