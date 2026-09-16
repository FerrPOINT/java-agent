package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.ProfileConfigRevisionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileConfigRevisionRepository extends JpaRepository<ProfileConfigRevisionEntity, UUID> {

    Optional<ProfileConfigRevisionEntity> findFirstByProfileOrderByRevisionDesc(String profile);

    List<ProfileConfigRevisionEntity> findByProfileOrderByRevisionDesc(String profile, Pageable pageable);
}
