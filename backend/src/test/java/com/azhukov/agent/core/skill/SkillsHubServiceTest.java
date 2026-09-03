package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryThreatScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Map;

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

    @Test
    void previewReturnsSkillMarkdownAndManifestForConfiguredSingleRepoHub() {
        properties.getSkills().setHubRepo("https://github.com/user/repo");
        SkillsHubService stub = new StubSkillsHubService(skillManager, properties, threatScanner, Map.of(
            "https://raw.githubusercontent.com/user/repo/main/demo/SKILL.md",
            "---\nname: demo\ndescription: Demo skill\n---\nBody",
            "https://api.github.com/repos/user/repo/contents/demo/references",
            "[{\"name\":\"guide.md\",\"type\":\"file\"}]",
            "https://api.github.com/repos/user/repo/contents/demo/scripts",
            "[{\"name\":\"run.sh\",\"type\":\"file\"}]"
        ));

        SkillsHubService.HubPreview preview = stub.preview("github/user/repo/demo");

        assertThat(preview).isNotNull();
        assertThat(preview.name()).isEqualTo("demo");
        assertThat(preview.identifier()).isEqualTo("github/user/repo/demo");
        assertThat(preview.description()).isEqualTo("Demo skill");
        assertThat(preview.trustLevel()).isEqualTo(TrustLevel.COMMUNITY);
        assertThat(preview.files()).containsExactly("SKILL.md", "references/guide.md", "scripts/run.sh");
    }

    @Test
    void previewReturnsNullWhenSkillMarkdownCannotBeFetched() {
        properties.getSkills().setHubRepo("https://github.com/user/repo");
        SkillsHubService stub = new StubSkillsHubService(skillManager, properties, threatScanner, Map.of());

        assertThat(stub.preview("demo")).isNull();
    }

    @Test
    void scanReturnsHermesLikePolicyAndFindingsWithoutInstalling() {
        properties.getSkills().setHubRepo("https://github.com/user/repo");
        SkillsHubService stub = new StubSkillsHubService(skillManager, properties, threatScanner, Map.of(
            "https://raw.githubusercontent.com/user/repo/main/demo/SKILL.md",
            "---\nname: demo\ndescription: Demo skill\n---\nUse sudo to change permissions."
        ));

        SkillsHubService.HubScan scan = stub.scan("demo");

        assertThat(scan).isNotNull();
        assertThat(scan.verdict()).isEqualTo("dangerous");
        assertThat(scan.policy()).isEqualTo("block");
        assertThat(scan.severityCounts()).containsEntry("high", 1);
        assertThat(scan.findings()).anySatisfy(finding ->
            assertThat(finding).containsEntry("category", "privilege_escalation"));
        verify(skillManager, never()).saveSkill(any(), any(), any());
    }

    private static final class StubSkillsHubService extends SkillsHubService {
        private final Map<String, String> responses;

        StubSkillsHubService(
            SkillManager skillManager,
            AgentProperties properties,
            MemoryThreatScanner threatScanner,
            Map<String, String> responses
        ) {
            super(skillManager, properties, threatScanner);
            this.responses = responses;
        }

        @Override
        protected String fetchUrl(String url) throws IOException, InterruptedException {
            return responses.get(url);
        }
    }
}
