package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.TodoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface TodoRepository extends JpaRepository<TodoEntity, UUID> {

    /** Serialize tool list mutations per user, including when the list is empty. */
    @org.springframework.data.jpa.repository.Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('agent:todo:', :userId), 0))) locked", nativeQuery = true)
    int lockUserList(@org.springframework.data.repository.query.Param("userId") String userId);

    /** Scalar owner lookup avoids retaining a managed entity while waiting for the user lock. */
    @org.springframework.data.jpa.repository.Query("select t.userId from TodoEntity t where t.id = :id")
    java.util.Optional<String> findOwnerId(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from TodoEntity t where t.id = :id and t.userId = :userId")
    java.util.Optional<TodoEntity> findOwnedForUpdate(@org.springframework.data.repository.query.Param("id") UUID id,
        @org.springframework.data.repository.query.Param("userId") String userId);

    List<TodoEntity> findByUserId(String userId);

    List<TodoEntity> findByUserIdOrderByCreatedAtAsc(String userId);

    List<TodoEntity> findBySessionId(UUID sessionId);

    List<TodoEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    Page<TodoEntity> findByUserId(String userId, Pageable pageable);

    List<TodoEntity> findByUserIdAndStatus(String userId, String status);

    Page<TodoEntity> findByUserIdAndStatus(String userId, String status, Pageable pageable);

    @Modifying
    @Transactional
    void deleteByUserIdAndStatus(String userId, String status);

    @Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from TodoEntity t where t.userId = :userId")
    @Transactional
    void deleteByUserId(@org.springframework.data.repository.query.Param("userId") String userId);

    @Modifying
    @Transactional
    void deleteBySessionId(UUID sessionId);

    @Modifying
    @Transactional
    void deleteBySessionIdAndUserId(UUID sessionId, String userId);
}
