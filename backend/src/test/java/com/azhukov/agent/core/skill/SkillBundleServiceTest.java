package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SkillBundleServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void install_readsBundleAndSavesSkill() throws IOException {
        // Set up bundle directory
        Path bundlesDir = tempDir.resolve("bundles");
        Path bundleDir = bundlesDir.resolve("test-bundle");
        Files.createDirectories(bundleDir);
        Files.writeString(bundleDir.resolve("SKILL.md"), "# Test Skill\nThis is a test bundle.");
        Files.createDirectories(bundleDir.resolve("references"));

        // Set up properties
        AgentProperties properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());

        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService service = new SkillBundleService(skillManager, properties);

        service.install("test-bundle");

        verify(skillManager).saveSkill(eq("test-bundle"), contains("Test Skill"));
    }

    @Test
    void list_returnsSkillNames() {
        AgentProperties properties = new AgentProperties();
        SkillManager skillManager = mock(SkillManager.class);
        List<String> names = List.of("bundle1", "bundle2");
        when(skillManager.listSkillNames()).thenReturn(names);

        SkillBundleService service = new SkillBundleService(skillManager, properties);

        assertThat(service.list()).isEqualTo(names);
    }

    @Test
    void uninstall_deletesSkill() {
        AgentProperties properties = new AgentProperties();
        SkillManager skillManager = mock(SkillManager.class);
        when(skillManager.deleteSkill("test-bundle")).thenReturn(true);

        SkillBundleService service = new SkillBundleService(skillManager, properties);

        service.uninstall("test-bundle");

        verify(skillManager).deleteSkill("test-bundle");
    }

    @Test
    void install_throwsWhenBundleNotFound() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());

        SkillManager skillManager = mock(SkillManager.class);
        SkillBundleService service = new SkillBundleService(skillManager, properties);

        assertThatThrownBy(() -> service.install("nonexistent-bundle"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Bundle directory not found");
    }
}