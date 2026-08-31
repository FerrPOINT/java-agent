package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.SkillAuditLogDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.SkillAuditLogRepository;
import com.azhukov.agent.service.AgentRuntimeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Skills & Bundles", description = "Skill listing, reload, bundle install/uninstall")
public class SkillController {

    private final SkillManager skillManager;
    private final AgentRuntimeService agentRuntimeService;
    private final SkillAuditLogRepository skillAuditLogRepository;
    private final com.azhukov.agent.core.skill.SkillsHubService skillsHubService;
    private final DomainDtoMapper domainDtoMapper;

    @Operation(summary = "List all skill names")
    @GetMapping("/agent/skills")
    public List<String> skills() {
        return skillManager.listSkillNames(UserContext.scopeUserId());
    }

    // ── Skills hub (SIMPLIFIED Hermes parity: one GitHub repo source) ──

    @Operation(summary = "List skills available in the hub repo")
    @GetMapping("/agent/skills-hub")
    public List<com.azhukov.agent.core.skill.SkillsHubService.RemoteSkillInfo> hubList() {
        return skillsHubService.listRemoteSkills();
    }

    @Operation(summary = "Search hub skills by substring over names+descriptions")
    @GetMapping("/agent/skills-hub/search")
    public List<com.azhukov.agent.core.skill.SkillsHubService.RemoteSkillInfo> hubSearch(@RequestParam String q) {
        return skillsHubService.searchRemoteSkills(q);
    }

    public record HubInstallRequest(String skill, Boolean overwrite) {}

    @Operation(summary = "Install a skill from the hub repo (threat-scanned)")
    @PostMapping("/agent/skills-hub/install")
    public java.util.Map<String, Object> hubInstall(@RequestBody HubInstallRequest body) {
        if (body.skill() == null || body.skill().isBlank()) {
            return Map.of("ok", false, "error", "skill is required");
        }
        var result = skillsHubService.install(
            com.azhukov.agent.core.skill.SkillsHubService.DEFAULT_HUB_REPO,
            body.skill(), Boolean.TRUE.equals(body.overwrite()));
        if (result.success()) {
            agentRuntimeService.reloadSkills();
        }
        return Map.of("ok", result.success(), "message", result.message());
    }

    // ── Skill content ──

    @GetMapping("/agent/skills/{name}")
    public Map<String, Object> getSkillContent(@PathVariable String name) {
        String content = skillManager.getSkill(name);
        if (content == null) {
            return Map.of("ok", false, "error", "Skill not found: " + name);
        }
        return Map.of("ok", true, "name", name, "content", content);
    }

    // h77: Curator audit ledger — list audit history for a skill.
    @GetMapping("/agent/skills/{name}/audit")
    public List<SkillAuditLogDto> getSkillAudit(@PathVariable String name) {
        return skillAuditLogRepository.findBySkillNameOrderByTimestampDesc(name).stream()
            .map(domainDtoMapper::toSkillAuditLogDto)
            .toList();
    }

    // ── Reload skills ──

    @Operation(summary = "Reload skills from disk")
    @PostMapping("/agent/reload-skills")
    public void reloadSkills() {
        agentRuntimeService.reloadSkills();
    }

    @PostMapping("/agent/reload")
    public void reloadAll() {
        agentRuntimeService.reloadSkills();
        agentRuntimeService.reloadMcp();
    }

    // ── Bundle install / uninstall ──

    @PostMapping("/agent/bundles/install")
    public Map<String, Object> installBundle(@Valid @RequestBody BundleRequest request) {
        try {
            agentRuntimeService.installBundle(request.bundleName());
            return Map.of("ok", true, "message", "Bundle installed: " + request.bundleName());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/agent/bundles/uninstall")
    public Map<String, Object> uninstallBundle(@Valid @RequestBody BundleRequest request) {
        try {
            agentRuntimeService.uninstallBundle(request.bundleName());
            return Map.of("ok", true, "message", "Bundle uninstalled: " + request.bundleName());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    public record BundleRequest(String bundleName) {}

    @GetMapping("/agent/bundles")
    public List<String> bundles() {
        return agentRuntimeService.listBundles();
    }

    /**
     * Alias kept for backward compatibility.
     */
    @GetMapping("/agent/skills/bundles")
    public List<String> bundlesAlias() {
        return agentRuntimeService.listBundles();
    }
}