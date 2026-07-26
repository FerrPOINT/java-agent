package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DatabaseSkillManagerTest {

    @Test
    void listNames() {
        SkillRepository repo = mock(SkillRepository.class);
        SkillEntity e = new SkillEntity();
        e.setName("s1");
        when(repo.findAll()).thenReturn(List.of(e));
        assertThat(new DatabaseSkillManager(repo).listSkillNames()).containsExactly("s1");
    }

    @Test
    void getSkillContent() {
        SkillRepository repo = mock(SkillRepository.class);
        SkillEntity e = new SkillEntity();
        e.setContent("content");
        when(repo.findByName("s1")).thenReturn(Optional.of(e));
        assertThat(new DatabaseSkillManager(repo).getSkill("s1")).isEqualTo("content");
        assertThat(new DatabaseSkillManager(repo).getSkill("missing")).isNull();
    }

    @Test
    void saveNewSkill() {
        SkillRepository repo = mock(SkillRepository.class);
        when(repo.findByName("s1")).thenReturn(Optional.empty());
        new DatabaseSkillManager(repo).saveSkill("s1", "c");
        verify(repo).save(argThat(e -> e.getName().equals("s1") && e.getContent().equals("c")));
    }

    @Test
    void deleteExistingSkill() {
        SkillRepository repo = mock(SkillRepository.class);
        SkillEntity e = new SkillEntity();
        when(repo.findByName("s1")).thenReturn(Optional.of(e));
        assertThat(new DatabaseSkillManager(repo).deleteSkill("s1")).isTrue();
        verify(repo).delete(e);
    }
}
