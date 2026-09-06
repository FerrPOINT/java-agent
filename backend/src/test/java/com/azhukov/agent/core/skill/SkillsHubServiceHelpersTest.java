package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryThreatScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for SkillsHubService pure helpers: description parsing, identifier
 * normalization, repo URL conversion, severity counting. Uses reflection only
 * where the helper is private and trivially pure.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillsHubServiceHelpersTest {

    @Mock private SkillManager skillManager;

    private SkillsHubService service;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        service = new SkillsHubService(skillManager, properties, new MemoryThreatScanner());
    }

    private String invoke(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = SkillsHubService.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return (String) m.invoke(service, args);
    }

    @Test
    void descriptionFromFrontMatterWins() throws Exception {
        String d = invoke("descriptionFromContent", new Class<?>[]{String.class},
            "---\nname: x\ndescription: My skill does things\n---\n# Title\nBody");
        assertThat(d).isEqualTo("My skill does things");
    }

    @Test
    void descriptionFallsBackToFirstProseLine() throws Exception {
        // No description: front-matter → first non-heading, non-front-matter line.
        // "name:" lines are skipped by the front-matter-aware fallback? No — the
        // parser only skips '#', '---' and blanks, so a bare front-matter-less doc
        // is the deterministic input here.
        String d = invoke("descriptionFromContent", new Class<?>[]{String.class},
            "# Title\nFirst prose line that is quite long and should be used");
        assertThat(d).startsWith("First prose line");
    }

    @Test
    void descriptionTruncatedAt120Chars() throws Exception {
        String longLine = "x".repeat(300);
        String d = invoke("descriptionFromContent", new Class<?>[]{String.class},
            "# T\n" + longLine);
        assertThat(d.length()).isLessThanOrEqualTo(121);
        assertThat(d).endsWith("…");
    }

    @Test
    void skillNameFromPlainIdentifierIsUnchanged() throws Exception {
        assertThat(invoke("skillNameFromIdentifier", new Class<?>[]{String.class}, "my-skill"))
            .isEqualTo("my-skill");
    }

    @Test
    void skillNameFromGitHubUrlStripsRepoParts() throws Exception {
        String n = invoke("skillNameFromIdentifier", new Class<?>[]{String.class},
            "https://github.com/FerrPOINT/skills/tree/main/my-skill");
        assertThat(n).isEqualTo("my-skill");
    }

    @Test
    void rawUrlConversionHandlesHttpsAndSsh() throws Exception {
        assertThat(invoke("repoUrlToRawUrl", new Class<?>[]{String.class},
                "https://github.com/FerrPOINT/skills"))
            .contains("raw.githubusercontent.com/FerrPOINT/skills");
        // SSH form is normalized to https github.com URL before raw conversion
        assertThat(invoke("repoUrlToRawUrl", new Class<?>[]{String.class},
                "git@github.com:FerrPOINT/skills.git"))
            .contains("FerrPOINT/skills");
    }

    @Test
    void apiUrlConversionUsesGitHubApi() throws Exception {
        assertThat(invoke("repoUrlToApiUrl", new Class<?>[]{String.class},
                "https://github.com/FerrPOINT/skills"))
            .contains("api.github.com/repos/FerrPOINT/skills");
    }

    @Test
    void hubRepoDefaultsToFerrPointWhenUnset() throws Exception {
        assertThat(invoke("hubRepo", new Class<?>[]{})).isEqualTo(SkillsHubService.DEFAULT_HUB_REPO);
        properties.getSkills().setHubRepo("https://github.com/acme/skills");
        assertThat(invoke("hubRepo", new Class<?>[]{})).isEqualTo("https://github.com/acme/skills");
    }
}
