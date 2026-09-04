package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * mu14: userId-scoped skill reads — a scoped user sees only own + shared
 * skills; admin scope (null) sees everything including other users' skills.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseSkillManagerScopeTest {

    @Mock private SkillRepository skillRepository;

    @InjectMocks private DatabaseSkillManager manager;

    private SkillEntity skill(String name, String userId) {
        SkillEntity s = new SkillEntity();
        s.setName(name);
        s.setUserId(userId);
        s.setContent("content-of-" + name);
        s.setArchived(false);
        return s;
    }

    @Test
    void adminScopeSeesAnySkill() {
        when(skillRepository.findByName("bob-private"))
            .thenReturn(Optional.of(skill("bob-private", "bob")));

        assertThat(manager.getSkill("bob-private")).isEqualTo("content-of-bob-private");
        assertThat(manager.getSkill("bob-private", null)).isEqualTo("content-of-bob-private");
    }

    @Test
    void scopedUserSeesOwnAndSharedSkills() {
        when(skillRepository.findVisibleSkills("alice")).thenReturn(List.of(
            skill("alice-private", "alice"),
            skill("shared-skill", null)));

        assertThat(manager.getSkill("alice-private", "alice")).isEqualTo("content-of-alice-private");
        assertThat(manager.getSkill("shared-skill", "alice")).isEqualTo("content-of-shared-skill");
    }

    @Test
    void scopedUserCannotReadSomeoneElsesPrivateSkill() {
        // findVisibleSkills(alice) does NOT include bob's private skill —
        // even if it exists in the repository.
        when(skillRepository.findVisibleSkills("alice")).thenReturn(List.of(
            skill("shared-skill", null)));
        when(skillRepository.findByName("bob-private"))
            .thenReturn(Optional.of(skill("bob-private", "bob")));

        assertThat(manager.getSkill("bob-private", "alice")).isNull();
        // The unscoped path (admin) still resolves it:
        assertThat(manager.getSkill("bob-private")).isEqualTo("content-of-bob-private");
    }
}
