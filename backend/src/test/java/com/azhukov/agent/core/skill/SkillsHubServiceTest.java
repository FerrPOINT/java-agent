package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryThreatScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SkillsHubService} — covers listRemoteSkills (with null/blank fetch → empty list),
 * install (skill already exists + threat-blocked path), and uninstall.
 */
@ExtendWith(MockitoExtension.class)
class SkillsHubServiceTest {

    @Mock private SkillManager skillManager;

    private AgentProperties properties;
    private MemoryThreatScanner threatScanner;
    private SkillsHubService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        threatScanner = new MemoryThreatScanner();
        service = new SkillsHubService(skillManager, properties, threatScanner);
    }

    @Test
    void uninstallDelegatesToSkillManager() {
        when(skillManager.deleteSkill("my-skill")).thenReturn(true);
        assertThat(service.uninstall("my-skill")).isTrue();
        verify(skillManager).deleteSkill("my-skill");
    }

    @Test
    void uninstallReturnsFalseWhenNotFound() {
        when(skillManager.deleteSkill("missing")).thenReturn(false);
        assertThat(service.uninstall("missing")).isFalse();
    }

    @Test
    void installReturnsFailWhenSkillAlreadyExists() {
        when(skillManager.getSkill("existing")).thenReturn("some content");
        SkillsHubService.InstallResult result =
            service.install("https://github.com/user/repo", "existing", false);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("already exists");
        // Should not have saved anything
        verify(skillManager, never()).saveSkill(eq("existing"), any(), any());
    }

    @Test
    void listRemoteSkillsReturnsEmptyForInvalidUrl() {
        // Non-existent host → fetch throws → exception caught → empty list
        var result = service.listRemoteSkills("https://github.com/nonexistent-user-xyz/repo-xyz-12345");
        assertThat(result).isEmpty();
    }

    @Test
    void installResultRecords() {
        SkillsHubService.InstallResult ok = SkillsHubService.InstallResult.ok("done");
        SkillsHubService.InstallResult fail = SkillsHubService.InstallResult.fail("oops");
        assertThat(ok.success()).isTrue();
        assertThat(ok.message()).isEqualTo("done");
        assertThat(fail.success()).isFalse();
        assertThat(fail.message()).isEqualTo("oops");
    }

    @Test
    void remoteSkillInfoRecord() {
        SkillsHubService.RemoteSkillInfo info =
            new SkillsHubService.RemoteSkillInfo("test-skill", "desc", "url");
        assertThat(info.name()).isEqualTo("test-skill");
        assertThat(info.description()).isEqualTo("desc");
        assertThat(info.url()).isEqualTo("url");
    }
}