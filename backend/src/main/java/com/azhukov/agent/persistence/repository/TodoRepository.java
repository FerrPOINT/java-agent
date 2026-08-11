package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.TodoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface TodoRepository extends JpaRepository<TodoEntity, UUID> {

    List<TodoEntity> findByUserId(String userId);

    List<TodoEntity> findByUserIdAndStatus(String userId, String status);

    @Modifying
    @Transactional
    void deleteByUserIdAndStatus(String userId, String status);

    @Modifying
    @Transactional
    void deleteByUserId(String userId);
}
