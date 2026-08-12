package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skills")
@Data
public class SkillEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    private String content;

    private String category;

    @Column(name = "version")
    private String version;

    private Instant updatedAt;
    private Instant createdAt;

    // S6: Provenance — who wrote this skill
    private String writeOrigin;

    // S7: Usage telemetry
    private int viewCount;
    private int manageCount;
    private Instant lastActivityAt;

    // S2: Curator lifecycle
    private boolean archived;

    // S12: Trust level
    private String trustLevel;

    // S5: Curator — skill lifecycle state (active/stale/archived)
    private String lifecycleState;

    // S5: Curator — pinned skills bypass all auto-transitions
    private boolean pinned;

    // S5: Curator — absorbed_into declaration when consolidated into umbrella
    private String absorbedInto;
}