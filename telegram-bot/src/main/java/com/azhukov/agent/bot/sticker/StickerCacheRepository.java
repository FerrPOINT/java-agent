package com.azhukov.agent.bot.sticker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StickerCacheRepository extends JpaRepository<StickerCacheEntity, String> {
}