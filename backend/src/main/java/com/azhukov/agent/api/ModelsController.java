package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.service.RuntimeConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible GET /v1/models endpoint.
 *
 * Lists the primary configured model plus any fallback-chain models so that
 * OpenAI-compatible frontends (Open WebUI, LobeChat, etc.) can discover what
 * the agent exposes. Mirrors Hermes' GET /v1/models.
 */
@RestController
@RequestMapping("/v1/models")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "OpenAI-compatible", description = "OpenAI-compatible model listing")
public class ModelsController {

    private final AgentProperties properties;
    private final RuntimeConfigService runtimeConfigService;

    @GetMapping
    @Operation(summary = "List available models in OpenAI-compatible format")
    public Map<String, Object> listModels() {
        List<Map<String, Object>> data = new ArrayList<>();

        // Primary model — always listed first
        String primaryModel = resolvePrimaryModel();
        data.add(modelEntry(primaryModel, "java-agent"));

        // Fallback chain models
        if (properties.getFallbackChain() != null) {
            for (FallbackConfig fb : properties.getFallbackChain()) {
                if (fb.getModel() != null && !fb.getModel().isBlank()
                    && !fb.getModel().equals(primaryModel)) {
                    data.add(modelEntry(fb.getModel(), fb.getProvider()));
                }
            }
        }

        // Auxiliary model if configured
        var aux = properties.getAuxiliary();
        if (aux != null && aux.getModelName() != null && !aux.getModelName().isBlank()
            && !aux.getModelName().equals(primaryModel)) {
            data.add(modelEntry(aux.getModelName(), "auxiliary"));
        }

        return Map.of(
            "object", "list",
            "data", data
        );
    }

    private String resolvePrimaryModel() {
        String override = runtimeConfigService.getModelOverride();
        if (override != null && !override.isBlank()) {
            return override;
        }
        return properties.getModel().getModelName();
    }

    private static Map<String, Object> modelEntry(String id, String ownedBy) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("object", "model");
        entry.put("created", Instant.now().getEpochSecond());
        entry.put("owned_by", ownedBy);
        entry.put("permission", List.of());
        entry.put("root", id);
        entry.put("parent", null);
        return entry;
    }
}