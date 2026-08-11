package com.azhukov.agent.bot.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotSettingsRepository extends JpaRepository<BotSettingsEntity, Long> {

    Optional<BotSettingsEntity> findByKey(String key);

    @Query("SELECT s FROM BotSettingsEntity s WHERE s.key LIKE :prefix")
    List<BotSettingsEntity> findByKeyStartingWith(@Param("prefix") String prefix);
}