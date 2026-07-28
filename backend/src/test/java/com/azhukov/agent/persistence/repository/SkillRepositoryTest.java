package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SkillEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:skillrepo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=none",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false",
    "agent.mcp.enabled=false",
    "agent.mcp.servers=",
    "agent.chromium.auto-start=false",
    "agent.chromium.auto-install=false"
})
@Transactional
class SkillRepositoryTest {

    private static final String CATEGORY_CORE = "core";
    private static final String CATEGORY_UTILITY = "utility";

    private static final String NAME_PLANNING = "planning";
    private static final String NAME_RESEARCH = "research";
    private static final String NAME_UNKNOWN = "missing-skill";

    private static final String CONTENT_PLANNING = "Break complex tasks into small, verifiable steps.";
    private static final String CONTENT_RESEARCH = "Search for authoritative sources and cite them.";
    private static final String CONTENT_UPDATED = "Break complex tasks into small, verifiable steps and estimate effort.";

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T12:00:01Z");

    @Autowired
    private SkillRepository skillRepository;

    @Test
    void saveSkill() {
        SkillEntity skill = newSkill(NAME_PLANNING, CATEGORY_CORE, CONTENT_PLANNING, T1);

        SkillEntity saved = skillRepository.save(skill);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo(NAME_PLANNING);
        assertThat(saved.getCategory()).isEqualTo(CATEGORY_CORE);
        assertThat(saved.getContent()).isEqualTo(CONTENT_PLANNING);
        assertThat(saved.getCreatedAt()).isEqualTo(T1);
        assertThat(saved.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void findByNameReturnsSkill() {
        SkillEntity saved = skillRepository.save(newSkill(NAME_PLANNING, CATEGORY_CORE, CONTENT_PLANNING, T1));

        SkillEntity found = skillRepository.findByName(NAME_PLANNING).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getName()).isEqualTo(NAME_PLANNING);
        assertThat(found.getCategory()).isEqualTo(CATEGORY_CORE);
        assertThat(found.getContent()).isEqualTo(CONTENT_PLANNING);
    }

    @Test
    void findByNameReturnsNullForMissingSkill() {
        skillRepository.save(newSkill(NAME_PLANNING, CATEGORY_CORE, CONTENT_PLANNING, T1));

        SkillEntity found = skillRepository.findByName(NAME_UNKNOWN).orElse(null);

        assertThat(found).isNull();
    }

    @Test
    void updateContent() {
        SkillEntity skill = skillRepository.save(newSkill(NAME_PLANNING, CATEGORY_CORE, CONTENT_PLANNING, T1));
        UUID skillId = skill.getId();

        skill.setContent(CONTENT_UPDATED);
        skill.setUpdatedAt(T2);
        skillRepository.save(skill);

        SkillEntity found = skillRepository.findById(skillId).orElseThrow();
        assertThat(found.getContent()).isEqualTo(CONTENT_UPDATED);
        assertThat(found.getUpdatedAt()).isEqualTo(T2);
        assertThat(found.getName()).isEqualTo(NAME_PLANNING);
    }

    @Test
    void listAllSkills() {
        SkillEntity planning = skillRepository.save(newSkill(NAME_PLANNING, CATEGORY_CORE, CONTENT_PLANNING, T1));
        SkillEntity research = skillRepository.save(newSkill(NAME_RESEARCH, CATEGORY_UTILITY, CONTENT_RESEARCH, T2));

        List<SkillEntity> all = skillRepository.findAll();

        assertThat(all).hasSize(2);
        assertThat(all)
            .extracting(SkillEntity::getId)
            .containsExactlyInAnyOrder(planning.getId(), research.getId());
        assertThat(all)
            .extracting(SkillEntity::getName)
            .containsExactlyInAnyOrder(NAME_PLANNING, NAME_RESEARCH);
    }

    private SkillEntity newSkill(String name, String category, String content, Instant createdAt) {
        SkillEntity skill = new SkillEntity();
        skill.setName(name);
        skill.setCategory(category);
        skill.setContent(content);
        skill.setCreatedAt(createdAt);
        skill.setUpdatedAt(createdAt);
        return skill;
    }
}
