package com.azhukov.agent.api;

import com.azhukov.agent.core.skill.SkillManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /v1/skills — list installed skills visible to API-server clients.
 */
@RestController
@RequestMapping({"/v1/skills", "/p/{profile}/v1/skills"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "OpenAI-compatible", description = "Skill discovery")
public class SkillsDiscoveryController {

    private final SkillManager skillManager;

    @GetMapping
    @Operation(summary = "List installed skill metadata")
    public ResponseEntity<Map<String, Object>> listSkills() {
        try {
            return ResponseEntity.ok(listSkillsPayload());
        } catch (RuntimeException e) {
            log.warn("GET /v1/skills failed", e);
            return openAiError(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to enumerate skills", "server_error");
        }
    }

    Map<String, Object> listSkillsPayload() {
        List<Map<String, Object>> data = skillManager.listSkills().stream()
            .filter(skill -> !skill.disabled())
            .sorted(Comparator.comparing(SkillsDiscoveryController::skillSortCategory)
                .thenComparing(SkillManager.SkillInfo::name))
            .map(this::toSkillResponse)
            .toList();

        return Map.of(
            "object", "list",
            "data", data
        );
    }

    private ResponseEntity<Map<String, Object>> openAiError(HttpStatus status, String message, String type) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        error.put("param", null);
        error.put("code", null);
        return ResponseEntity.status(status).body(Map.of("error", error));
    }

    private Map<String, Object> toSkillResponse(SkillManager.SkillInfo skill) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", skill.name());
        response.put("description", skill.description() != null ? skill.description() : "");
        response.put("category", skill.category() != null ? skill.category() : "");
        return response;
    }

    private static String skillSortCategory(SkillManager.SkillInfo skill) {
        return skill.category() != null ? skill.category() : "";
    }
}
