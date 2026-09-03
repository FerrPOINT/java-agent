package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.OpenAiResponseEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OpenAiResponseRepository extends JpaRepository<OpenAiResponseEntity, String> {

    @Query("select response.responseId from OpenAiResponseEntity response order by response.accessedAt asc, response.responseId asc")
    List<String> findOldestResponseIds(Pageable pageable);
}
