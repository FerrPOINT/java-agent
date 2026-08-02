package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.TodoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TodoRepository extends JpaRepository<TodoEntity, UUID> {

    List<TodoEntity> findByUserId(String userId);

    List<TodoEntity> findByUserIdAndStatus(String userId, String status);

    void deleteByUserIdAndStatus(String userId, String status);

    void deleteByUserId(String userId);
}
