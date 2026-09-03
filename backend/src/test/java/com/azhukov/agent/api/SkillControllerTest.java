package com.azhukov.agent.api;

import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.SkillsHubService;
import com.azhukov.agent.persistence.entity.SkillAuditLogEntity;
import com.azhukov.agent.persistence.repository.SkillAuditLogRepository;
import com.azhukov.agent.service.AgentRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Focused unit tests for {@link SkillController} — covers success,
 * bad input/error/edge cases for skill listing, content, hub install,
 * audit, reload, and bundle install/uninstall endpoints.
 *
 * Uses {@link Mappers#getMapper} for the real {@link DomainDtoMapper} MapStruct
 * implementation, per project convention (no mocking mappers).
 */
@ExtendWith(MockitoExtension.class)
class SkillControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private SkillManager skillManager;
    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private SkillAuditLogRepository skillAuditLogRepository;
    @Mock private SkillsHubService skillsHubService;

    private final DomainDtoMapper domainDtoMapper = Mappers.getMapper(DomainDtoMapper.class);

    @BeforeEach
    void setUp() {
        SkillController controller = new SkillController(
            skillManager,
            agentRuntimeService,
            skillAuditLogRepository,
            skillsHubService,
            domainDtoMapper
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    // ── List skills ──

    @Test
    void skillsReturnsAllSkillNames() throws Exception {
        when(skillManager.listSkillNames(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(List.of("python-dev", "qa-testing"));

        mockMvc.perform(get("/api/v1/agent/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("python-dev"))
            .andExpect(jsonPath("$[1]").value("qa-testing"));
    }

    @Test
    void skillsReturnsEmptyListWhenNoSkills() throws Exception {
        when(skillManager.listSkillNames(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Skills hub list ──

    @Test
    void hubListReturnsRemoteSkills() throws Exception {
        when(skillsHubService.listRemoteSkills()).thenReturn(List.of(
            new SkillsHubService.RemoteSkillInfo("skill-a", "Description A", "https://example.com/a"),
            new SkillsHubService.RemoteSkillInfo("skill-b", "Description B", "https://example.com/b")
        ));

        mockMvc.perform(get("/api/v1/agent/skills-hub"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("skill-a"))
            .andExpect(jsonPath("$[0].description").value("Description A"))
            .andExpect(jsonPath("$[1].name").value("skill-b"));
    }

    @Test
    void hubListReturnsEmptyWhenNoRemoteSkills() throws Exception {
        when(skillsHubService.listRemoteSkills()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/skills-hub"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Skills hub search ──

    @Test
    void hubSearchReturnsMatchingSkills() throws Exception {
        when(skillsHubService.searchRemoteSkills("python"))
            .thenReturn(List.of(new SkillsHubService.RemoteSkillInfo("python-dev", "Python development", "https://example.com")));

        mockMvc.perform(get("/api/v1/agent/skills-hub/search")
                .param("q", "python"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("python-dev"))
            .andExpect(jsonPath("$[0].description").value("Python development"));
    }

    @Test
    void hubSearchReturnsEmptyWhenNoMatch() throws Exception {
        when(skillsHubService.searchRemoteSkills("nomatch")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/skills-hub/search")
                .param("q", "nomatch"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Hub install ──

    @Test
    void hubInstallWithValidSkillReturnsOk() throws Exception {
        when(skillsHubService.install(eq(SkillsHubService.DEFAULT_HUB_REPO), eq("python-dev"), eq(false)))
            .thenReturn(SkillsHubService.InstallResult.ok("Installed python-dev"));
        doNothing().when(agentRuntimeService).reloadSkills();

        mockMvc.perform(post("/api/v1/agent/skills-hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skill":"python-dev","overwrite":false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.message").value("Installed python-dev"));

        verify(agentRuntimeService).reloadSkills();
    }

    @Test
    void hubInstallWithOverwriteTruePassesOverwriteFlag() throws Exception {
        when(skillsHubService.install(eq(SkillsHubService.DEFAULT_HUB_REPO), eq("python-dev"), eq(true)))
            .thenReturn(SkillsHubService.InstallResult.ok("Installed python-dev (overwrite)"));

        mockMvc.perform(post("/api/v1/agent/skills-hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skill":"python-dev","overwrite":true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(skillsHubService).install(SkillsHubService.DEFAULT_HUB_REPO, "python-dev", true);
    }

    @Test
    void hubInstallWithBlankSkillReturnsError() throws Exception {
        mockMvc.perform(post("/api/v1/agent/skills-hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skill":"","overwrite":false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("skill is required"));

        verify(skillsHubService, never()).install(anyString(), anyString(), anyBoolean());
    }

    @Test
    void hubInstallWithNullSkillReturnsError() throws Exception {
        mockMvc.perform(post("/api/v1/agent/skills-hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skill":null,"overwrite":false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("skill is required"));
    }

    @Test
    void hubInstallWhenInstallFailsReturnsErrorAndDoesNotReload() throws Exception {
        when(skillsHubService.install(eq(SkillsHubService.DEFAULT_HUB_REPO), eq("bad-skill"), eq(false)))
            .thenReturn(SkillsHubService.InstallResult.fail("Skill not found in hub"));

        mockMvc.perform(post("/api/v1/agent/skills-hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skill":"bad-skill","overwrite":false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.message").value("Skill not found in hub"));

        verify(agentRuntimeService, never()).reloadSkills();
    }

    // ── Skill content ──

    @Test
    void getSkillContentReturnsContentWhenFound() throws Exception {
        when(skillManager.getSkill("python-dev")).thenReturn("# Python Development\nSkill content");

        mockMvc.perform(get("/api/v1/agent/skills/{name}", "python-dev"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("python-dev"))
            .andExpect(jsonPath("$.content").value("# Python Development\nSkill content"));
    }

    @Test
    void getSkillContentReturnsErrorWhenNotFound() throws Exception {
        when(skillManager.getSkill("nonexistent")).thenReturn(null);

        mockMvc.perform(get("/api/v1/agent/skills/{name}", "nonexistent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("Skill not found: nonexistent"));
    }

    // ── Skill audit ──

    @Test
    void getSkillAuditReturnsAuditEntries() throws Exception {
        SkillAuditLogEntity entity = SkillAuditLogEntity.create("python-dev", "update", "user-1", "old content", "new content");
        entity.setId(1L);
        entity.setTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        when(skillAuditLogRepository.findBySkillNameOrderByTimestampDesc("python-dev"))
            .thenReturn(List.of(entity));

        mockMvc.perform(get("/api/v1/agent/skills/{name}/audit", "python-dev"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].skillName").value("python-dev"))
            .andExpect(jsonPath("$[0].action").value("update"))
            .andExpect(jsonPath("$[0].userId").value("user-1"))
            .andExpect(jsonPath("$[0].oldValue").value("old content"))
            .andExpect(jsonPath("$[0].newValue").value("new content"));
    }

    @Test
    void getSkillAuditReturnsEmptyWhenNoEntries() throws Exception {
        when(skillAuditLogRepository.findBySkillNameOrderByTimestampDesc("no-audit"))
            .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/skills/{name}/audit", "no-audit"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Reload skills ──

    @Test
    void reloadSkillsCallsAgentRuntime() throws Exception {
        doNothing().when(agentRuntimeService).reloadSkills();

        mockMvc.perform(post("/api/v1/agent/reload-skills"))
            .andExpect(status().isOk());

        verify(agentRuntimeService).reloadSkills();
    }

    // ── Reload all ──

    @Test
    void reloadAllCallsBothReloadSkillsAndMcp() throws Exception {
        doNothing().when(agentRuntimeService).reloadSkills();
        doNothing().when(agentRuntimeService).reloadMcp();

        mockMvc.perform(post("/api/v1/agent/reload"))
            .andExpect(status().isOk());

        verify(agentRuntimeService).reloadSkills();
        verify(agentRuntimeService).reloadMcp();
    }

    // ── Bundle install ──

    @Test
    void installBundleReturnsOkWhenSuccessful() throws Exception {
        doNothing().when(agentRuntimeService).installBundle("my-bundle");

        mockMvc.perform(post("/api/v1/agent/bundles/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"bundleName":"my-bundle"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.message").value("Bundle installed: my-bundle"));

        verify(agentRuntimeService).installBundle("my-bundle");
    }

    @Test
    void installBundleReturnsErrorWhenExceptionThrown() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("Bundle not found"))
            .when(agentRuntimeService).installBundle("bad-bundle");

        mockMvc.perform(post("/api/v1/agent/bundles/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"bundleName":"bad-bundle"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("Bundle not found"));
    }

    // ── Bundle uninstall ──

    @Test
    void uninstallBundleReturnsOkWhenSuccessful() throws Exception {
        org.mockito.Mockito.doNothing().when(agentRuntimeService).uninstallBundle("my-bundle");

        mockMvc.perform(post("/api/v1/agent/bundles/uninstall")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"bundleName":"my-bundle"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.message").value("Bundle uninstalled: my-bundle"));

        verify(agentRuntimeService).uninstallBundle("my-bundle");
    }

    @Test
    void uninstallBundleReturnsErrorWhenExceptionThrown() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("Cannot uninstall"))
            .when(agentRuntimeService).uninstallBundle("bad-bundle");

        mockMvc.perform(post("/api/v1/agent/bundles/uninstall")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"bundleName":"bad-bundle"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("Cannot uninstall"));
    }

    @Test
    void installBundleWithBlankNameStillCallsService() throws Exception {
        // BundleRequest.bundleName has no @NotBlank — blank name is forwarded to the service.
        org.mockito.Mockito.doThrow(new RuntimeException("Bundle not found: "))
            .when(agentRuntimeService).installBundle("");

        mockMvc.perform(post("/api/v1/agent/bundles/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"bundleName":""}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));
    }

    // ── List bundles ──

    @Test
    void bundlesReturnsList() throws Exception {
        when(agentRuntimeService.listBundles()).thenReturn(List.of("bundle-a", "bundle-b"));

        mockMvc.perform(get("/api/v1/agent/bundles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("bundle-a"))
            .andExpect(jsonPath("$[1]").value("bundle-b"));
    }

    @Test
    void bundlesAliasReturnsSameList() throws Exception {
        when(agentRuntimeService.listBundles()).thenReturn(List.of("bundle-a"));

        mockMvc.perform(get("/api/v1/agent/skills/bundles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("bundle-a"));
    }

    @Test
    void bundlesReturnsEmptyListWhenNone() throws Exception {
        when(agentRuntimeService.listBundles()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/bundles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }
}