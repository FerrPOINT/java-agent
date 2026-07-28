package com.azhukov.agent.bot.sticker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "bot_sticker_cache")
@Data
public class StickerCacheEntity {

    @Id
    @Column(name = "file_unique_id", length = 128)
    private String fileUniqueId;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at")
    private Instant createdAt;

    public StickerCacheEntity() {}

    public StickerCacheEntity(String fileUniqueId, String description) {
        this.fileUniqueId = fileUniqueId;
        this.description = description;
        this.createdAt = Instant.now();
    }
}