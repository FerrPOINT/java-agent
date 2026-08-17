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

    List<TodoEntity> findByUserId(String userId);

    List<TodoEntity> findBySessionId(UUID sessionId);

    Page<TodoEntity> findByUserId(String userId, Pageable pageable);

    List<TodoEntity> findByUserIdAndStatus(String userId, String status);

    Page<TodoEntity> findByUserIdAndStatus(String userId, String status, Pageable pageable);

    @Modifying
    @Transactional
    void deleteByUserIdAndStatus(String userId, String status);

    @Modifying
    @Transactional
    void deleteByUserId(String userId);
}
