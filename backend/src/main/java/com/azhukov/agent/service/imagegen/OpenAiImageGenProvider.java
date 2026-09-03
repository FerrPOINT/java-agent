package com.azhukov.agent.service.imagegen;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI DALL-E image generation provider.
 * Calls the OpenAI images/generations endpoint.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent.image-gen.enabled", havingValue = "true")
public class OpenAiImageGenProvider implements ImageGenProvider {

    private final AgentProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiImageGenProvider(AgentProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Override
    public byte[] generate(String prompt, String aspectRatio) {
        try {
            String apiKey = properties.getImageGen().getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("OpenAI image generation requires agent.image-gen.api-key to be set");
            }
            String model = properties.getImageGen().getModel();
            if (model == null || model.isBlank()) {
                model = "dall-e-3";
            }

            // Map aspect ratio to DALL-E size
            String size = mapAspectRatio(aspectRatio);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("n", 1);
            body.put("size", size);
            body.put("response_format", "b64_json");

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/images/generations"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenAI image generation failed: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("Image generation failed: HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new RuntimeException("Image generation returned no data");
            }

            String b64 = data.get(0).path("b64_json").asText("");
            if (b64.isBlank()) {
                throw new RuntimeException("Image generation returned empty b64_json");
            }

            return Base64.getDecoder().decode(b64);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI image generation error: {}", e.getMessage(), e);
            throw new RuntimeException("Image generation failed: " + e.getMessage(), e);
        }
    }

    private String mapAspectRatio(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) return "1024x1024";
        return switch (aspectRatio.trim()) {
            case "16:9", "landscape" -> "1792x1024";
            case "9:16", "portrait" -> "1024x1792";
            case "1:1", "square" -> "1024x1024";
            default -> "1024x1024";
        };
    }
}
