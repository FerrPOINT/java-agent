package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /** Find audit entries for a specific user. */
    List<AuditLogEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Find audit entries for a specific user, paginated. */
    Page<AuditLogEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /** Find audit entries for a specific session. */
    List<AuditLogEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** Find all audit entries ordered by creation time (admin view). */
    default List<AuditLogEntity> findAllOrdered() {
        return findAll(org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }
}