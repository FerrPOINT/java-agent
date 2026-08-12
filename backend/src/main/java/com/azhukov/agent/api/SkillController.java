package com.azhukov.agent.api;

import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.service.AgentRuntimeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Operation(summary = "List all skill names")
    @GetMapping("/agent/skills")
    public List<String> skills() {
        return skillManager.listSkillNames();
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