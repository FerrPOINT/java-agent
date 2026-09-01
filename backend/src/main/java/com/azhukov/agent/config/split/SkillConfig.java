package com.azhukov.agent.config.split;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.DatabaseSkillManager;
import com.azhukov.agent.core.skill.NoOpSkillManager;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.SkillRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skill-related beans: {@link SkillManager} (database or noop).
 */
@Configuration(proxyBeanMethods = false)
public class SkillConfig {

    @Bean
    @ConditionalOnProperty(name = "agent.skills.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(SkillManager.class)
    public SkillManager skillManager(SkillRepository skillRepository, AgentProperties properties) {
        return new DatabaseSkillManager(skillRepository, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.skills.enabled", havingValue = "false")
    @ConditionalOnMissingBean(SkillManager.class)
    public SkillManager noOpSkillManager() {
        return new NoOpSkillManager();
    }
}